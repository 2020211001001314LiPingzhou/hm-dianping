package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final long COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        // 用当前时间减去开始时间的结果做时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 使用Redis的自增长生成，Redis的单个key的自增长有上限，为2的64次方，
        // 虽然说64位很大，但是也是有上限的，而且我们要求序列号为32位
        // 所以我们加上一个日期字段，这样上限就变成每天订单的上限，
        // 另外这样做还有统计效果，可以方便知道每天的订单量
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长  key不存在会自动创建，所以不会出现空指针问题。
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        // 使用位运算timestamp左移32位，再与运算和count“拼接”
        // 这比字符串拼接再转回long类型方式 和 timestamp位移后再相加都更加精妙，效率也更高
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        // 获取2022.1.1距1900年时间
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("time = " + second);
    }
}
