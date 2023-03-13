package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        // 配置Redis所在服务器Ipv4地址端口 和 登录密码 （useSingleServer是单节点模式）
        config.useSingleServer().setAddress("redis://192.168.240.130:6379").setPassword("123321");
        // 创建Redisson对象
        return Redisson.create(config);
    }
}
