package com.zhicai.platform.identity;

import com.zhicai.platform.common.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class RedisSessionStore implements SessionStore {
    private static final String PREFIX = "zhicai:auth:session:";
    private final StringRedisTemplate redis;
    public RedisSessionStore(StringRedisTemplate redis) { this.redis = redis; }
    @Override public void put(String hash, String subject, Duration ttl) {
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + hash, subject, ttl))) throw ApiException.unavailable();
        } catch (DataAccessException e) { throw ApiException.unavailable(); }
    }
    @Override public String find(String hash) {
        try { return redis.opsForValue().get(PREFIX + hash); }
        catch (DataAccessException e) { throw ApiException.unavailable(); }
    }
    @Override public void remove(String hash) {
        try { redis.delete(PREFIX + hash); }
        catch (DataAccessException e) { throw ApiException.unavailable(); }
    }
}
