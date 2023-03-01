package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 基于StringRedisTemplate封装工具类
 */
@Component
public class CacheClient {

    // 请注意看方法中使用了stringRedisTemplate, 它是通过ioc注入进来的，ioc是通过new的方式创建bean
    // new出来的对象是在堆里面，static修饰的对象是优先于对象存在的，所以这里不用static
    private final StringRedisTemplate stringRedisTemplate;

    public  CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 缓存存入
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 具有逻辑过期时间缓存存入，解决缓存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 解决缓存穿透问题，因为是工具类，传入值类型不确定，所以使用泛型方法。
     * 封装工具类方法应具有通用性，我们要查询数据库，数据库查询是一个问题，不同请求要查的库，表，条件等等都不同，
     * 所以说我们就让调用者自己告诉我们该怎么查，查数据库是一个函数，所以我们使用 函数式编程，参数中传入一个函数
     * @param keyPrefix
     * @param id
     * @param type
     * @param <R>
     * @param <ID>
     * @param dbFallback 传入有参有返回值的回调函数使用Function<ID, R>类型，左ID是参数，右R是返回值
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.尝试从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在 可能的情况：正确数据/第一次请求redis中没有shopJson为null/查过数据库中没有，在redis中设置为空值，结果shopJson为""
        if (StrUtil.isNotBlank(json)) {//isNotBlank能过滤 1.不为 null 2.不为空字符串："" 3.不为空格、全角空格、制表符、换行符，等不可见字符
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }

        // 过滤为空值""情况，防止短时间内再次缓存穿透 注意null 不等于 ""
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库（使用MybatisPlus）
        R r = dbFallback.apply(id);

        // 5.数据库中不存在，返回错误
        if (r == null) {
            // 为预防缓存击穿，将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }

        // 6.存在，写入redis
        this.set(key, r, time, unit);

        // 7.返回
        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        /*// 1.尝试从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回null
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);*/
        RedisData redisData = this.getWithLogicalExpire(key);
        if (redisData == null) {
            return null;
        }

        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }

        // 5.2.已过期，需要缓存重建
        // 6.重建缓存
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否成功获取锁
        if (isLock) {
            // DoubleCheck
            redisData = this.getWithLogicalExpire(key);
            if (redisData == null) {
                return null;
            }

            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            // 判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                //未过期，直接返回店铺信息
                return r;
            }

            // Todo 6.3.获取锁成功，并DoubleCheck过期，则开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> { //lambda表达式形式
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });

            // 这里还要DoubleCheck
            // 因为你成功获取互斥锁有一种可能时机是刚好有一个线程刚释放完互斥锁，也就证明此时缓存中的数据是新鲜热乎的，
            // 此时不需要再去重建缓存，刚刚有线程才重建完。问题就是出在你判断缓存已经过期到获取锁这个时间段，有其他线程在这个期间释放锁。
            // 用jmeter测试当qps达到3000时就有此问题，可以看到控制台有多个数据库的查询结果，所以说未避免重复重建缓存，需要在获取到锁后再查询一次redis
            // 判断逻辑过期时间是否过期，过期了就继续操作，发现没过期就直接释放锁
        }

        // 6.4.返回过期的商铺信息
        return r;
    }


    // 获取逻辑缓存对象
    public RedisData getWithLogicalExpire(String key){
        // 1.尝试从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回null
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        return JSONUtil.toBean(json, RedisData.class);
    }


    /**
     * 用setnx的方式来构造锁，仅当key不存在时，才能设置值，并返回1；如果已经存在key，则无法更新，返回0
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        // setIfAbsent()相当于redis命令中的setnx
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 使用工具类是防止Boolean为null，自动拆箱为基本类型boolean时，会发生空指针异常（boolean基本类型我好像都没用过）
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }



}
