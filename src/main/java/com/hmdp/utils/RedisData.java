package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Data 注解的主要作用是提高代码的简洁，使用这个注解可以省去代码中大量的get()、 set()、 toString()等方法；
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
