package com.zhicai.platform.identity;

import com.zhicai.platform.common.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class RedisLoginThrottle implements LoginThrottle {
    private final StringRedisTemplate redis;
    // Separate username/type and network budgets; no reliance on untrusted forwarded headers.
    // count = failed credentials + pending attempts; reserve both budgets before BCrypt begins.
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        local a=tonumber(redis.call('HGET',KEYS[1],'count') or '0')
        local b=tonumber(redis.call('HGET',KEYS[2],'count') or '0')
        if a>=5 or b>=30 then return 0 end
        for _,key in ipairs(KEYS) do
          redis.call('HINCRBY',key,'count',1)
          redis.call('HSET',key,'pending:'..ARGV[1],'1')
          if redis.call('PTTL',key)<0 then redis.call('PEXPIRE',key,900000) end
        end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
        for _,key in ipairs(KEYS) do
          local owned=redis.call('HDEL',key,'pending:'..ARGV[1])
          if owned==1 and ARGV[2]=='release' then redis.call('HINCRBY',key,'count',-1) end
        end
        return 1
        """, Long.class);
    public RedisLoginThrottle(StringRedisTemplate redis) { this.redis = redis; }
    private List<String> keys(String identity, String address) {
        return List.of("zhicai:auth:budget:v2:identity:" + TokenHash.sha256(identity),
            "zhicai:auth:budget:v2:ip:" + TokenHash.sha256(address));
    }
    @Override public Reservation reserve(String identity, String address) {
        var reservation = new Reservation(identity, address, UUID.randomUUID().toString());
        try {
            Long admitted = redis.execute(RESERVE, keys(identity, address), reservation.id());
            if (admitted == null) throw ApiException.unavailable();
            if (admitted == 0) throw new ApiException(429, "RATE_LIMITED", "登录尝试过多，请稍后重试");
            if (admitted != 1) throw ApiException.unavailable();
            return reservation;
        } catch (DataAccessException e) { throw ApiException.unavailable(); }
    }
    @Override public void failed(Reservation reservation) { settle(reservation, "retain"); }
    @Override public void succeeded(Reservation reservation) { settle(reservation, "release"); }
    private void settle(Reservation reservation, String action) {
        try {
            if (redis.execute(SETTLE, keys(reservation.identity(), reservation.address()), reservation.id(), action) == null)
                throw ApiException.unavailable();
        }
        catch (DataAccessException e) { throw ApiException.unavailable(); }
    }
}
