package com.zhicai.platform.identity;

import java.time.Duration;
public interface SessionStore {
    void put(String tokenHash, String accountId, Duration ttl);
    String find(String tokenHash);
    void remove(String tokenHash);
}
