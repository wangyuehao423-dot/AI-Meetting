package com.example.interview.infrastructure.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component 
public class QuestionLockManager {
    private final StringRedisTemplate redis;
    public QuestionLockManager(StringRedisTemplate redis)
    {
        this.redis = redis;
    }
    // 尝试获取一把针对 key 的分布式锁。
    // 参数 key：业务唯一标识，不同 key 之间相互独立，可并发加锁。
    // 返回值：加锁成功返回 LockHandle，用于后续释放锁；加锁失败（锁已被占用）返回 null。
    public LockHandle tryLock(String key)
    {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(30));
        return new LockHandle
        (redis, key, token,Boolean.TRUE.equals(acquired));
    }

    public record LockHandle(StringRedisTemplate redis, String key, String token, boolean acquired) implements AutoCloseable
    {
        @Override 
        public void close()
        {
            if(acquired)
            {
                String current = redis.opsForValue().get(key);
                if(token.equals(current))
                {
                    redis.delete(key);
                }
            }
        }
    }
}