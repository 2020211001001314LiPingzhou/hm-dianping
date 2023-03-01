package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    // 使用我们封装的工具类
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        /* 解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        // this::getById 是传递函数，lambda表达式，还可以写成 (x)->getById(x)
        // 方法定义传入形参类型为 Function<ID, R>，即要求传入一个有参有返回值函数
         */
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_HOT_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_HOT_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);//时间短点，便于测试

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 返回
        return Result.ok(shop);
    }

    // 简单线程池，这个阿里不建议使用，实际工作中要自己手动创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期解决缓存击穿的部分就不用考虑缓存穿透了，默认一定查得到热点key（热点key需要先预热加载，即第一次缓存是我们自己放进去的）
    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_HOT_KEY + id;
        // 1.尝试从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在，直接返回null
            return null;
        }
        // 4.命中，需要先把json反序列化为对象

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        /*
        // 这里使用泛型是有问题的，JSONUtil.toBean(shopJson, RedisData.class);返回的redisData对象中的Data对象实际上是一个JSONObject对象，
        // 编译阶段没问题，但运行时Shop shop = redisData.getData();会报类型转换异常，JSONObject类型无法转换为Shop类型
        // JSONUtil.toBean()这个方法将json字符串转为对象时，其中的对象成员属性应该都统一使用JSONObject类型了，所以出现这种问题
        //RedisData<Shop> redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //Shop shop = redisData.getData();
        //LocalDateTime expireTime = redisData.getExpireTime();
        // 强转更是不行
        //Shop data = (Shop) redisData.getData();
         */
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }

        // 5.2.已过期，需要缓存重建
        // 6.重建缓存
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否成功获取锁
        if (isLock) {
            // 实现DoubleCheck（可以封装成函数）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            LocalDateTime expireTime2 = redisData.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())) {
                return shop;
            }

            // Todo 6.3.获取锁成功，并DoubleCheck过期，则开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> { //lambda表达式形式
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L); //这里测试使用逻辑过期时间有效期设置20s，实际业务不用这么短
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
        return shop;

    }

    // 互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id) {
        // 自旋锁，课程里面用的是递归
        while (true) {
            String key = CACHE_HOT_KEY + id;
            // 1.尝试从redis中查询缓存
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            // 2.判断是否存在 可能的情况：正确数据/第一次请求redis中没有shopJson为null/查过数据库中没有，在redis中设置为空值，结果shopJson为""
            if (StrUtil.isNotBlank(shopJson)) {//isNotBlank能过滤 1.不为 null 2.不为空字符串："" 3.不为空格、全角空格、制表符、换行符，等不可见字符
                // 3.存在，直接返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 过滤为空值""情况，防止短时间内再次缓存穿透 注意null 不等于 ""
            if (shopJson != null) {
                // 返回一个错误信息
                return null;
            }

            // 4.实现缓存重建
            // 4.1.获取互斥锁
            String lockKey = "lock:shop:" + id;
            Shop shop = null;
            try {
                boolean isLock = tryLock(lockKey);
                // 4.2.判断是否获取成功
                if (!isLock) {
                    // 4.3.失败，则休眠重试
                    Thread.sleep(50);
                    continue;
                }

                // 4.4.成功，根据id查询数据库（使用MybatisPlus）
                shop = getById(id);
                // 模拟重建的延时
                Thread.sleep(200);
                // 5.查询数据库中不存在，返回错误
                if (shop == null) {
                    // 为预防缓存击穿，将空值写入redis
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    // 返回错误信息
                    return null;
                }

                // 6.存在，写入redis
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 7.释放互斥锁
                unlock(lockKey);
            }

            // 8.返回
            return shop;
        }
    }

    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.尝试从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在 可能的情况：正确数据/第一次请求redis中没有shopJson为null/查过数据库中没有，在redis中设置为空值，结果shopJson为""
        if (StrUtil.isNotBlank(shopJson)) {//isNotBlank能过滤 1.不为 null 2.不为空字符串："" 3.不为空格、全角空格、制表符、换行符，等不可见字符
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 过滤为空值""情况，防止短时间内再次缓存穿透 注意null 不等于 ""
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库（使用MybatisPlus）
        Shop shop = getById(id);

        // 5.数据库中不存在，返回错误
        if (shop == null) {
            // 为预防缓存击穿，将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }

        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return shop;
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

    // 存储具有逻辑过期时间的店铺信息，预热加载
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 模拟真实业务时重建缓存的延时
        Thread.sleep(200);
        // 2.封装逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));//以 当前时间加上expireSeconds 来设置逻辑过期时间
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_HOT_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 先更新数据库，再删除缓存能降低 缓存不一致情况出现的概率
     * 直接删除缓存比更新缓存更好，频繁更新数据库时只要删除一次缓存就行了
     * redis其实不支持事务的回滚，当redis操作失败时，@Transactional注解不会回滚事务，所以这里其实也会出现不一致问题，具体见redis收藏文章：
     * 注：@Transactional只会回滚MySQL的异常，其后发生的Redis异常并不会让其回滚数据库。Redis也不会回滚（这个和Redis采用的设计策略有关：不对回滚支持，保证操作的简单快速）
     *
     * 这里是单系统上的事务处理方法，如果是分布式，如数据库更新和缓存删除不在同一个系统上，则需要使用mq消息队列，异步通知对方删除缓存，要保证缓存一致性就需要使用TCC方案（SpringCloud内容）
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        // 判断店铺id是否存在
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

}
