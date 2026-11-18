package com.example.interview.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端：只用来做分布式锁，不接管 Spring Data Redis。
 *
 * <p>为什么不用 {@code redisson-spring-boot-starter}：那个 starter 会把自己的
 * RedisConnectionFactory / RedisTemplate 自动配置顶上去，和项目里已有的
 * spring-boot-starter-data-redis 配置打架（序列化器、编解码器都会被换掉）。
 * 这里只需要一把锁，直接建一个 RedissonClient，各管各的连接池，互不干扰。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {

        Config config = new Config();
        var single = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                // 锁的等待/重试都在业务层控制，连接层只负责快速失败
                .setConnectTimeout(3000)
                .setTimeout(3000)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
