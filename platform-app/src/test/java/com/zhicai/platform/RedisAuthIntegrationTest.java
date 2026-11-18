package com.zhicai.platform;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** Opt-in verification against the already approved project Redis, with bounded own-key cleanup. */
@EnabledIfEnvironmentVariable(named="M0_REDIS_INTEGRATION", matches="true")
class RedisAuthIntegrationTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    final List<String> ownedKeys = new ArrayList<>();
    @BeforeEach void connect() {
        var config = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"), Integer.parseInt(System.getenv("REDIS_PORT")));
        config.setPassword(RedisPassword.of(System.getenv("REDIS_PASSWORD")));
        factory = new LettuceConnectionFactory(config, clientConfiguration()); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
    }
    @AfterEach void cleanup() {
        if (redis != null && !ownedKeys.isEmpty()) redis.delete(ownedKeys);
        if (factory != null) factory.destroy();
    }
    @Test void redisSessionStoresOnlyHashAndExpiresAndRevokes() {
        var sessions = new RedisSessionStore(redis);
        String token = UUID.randomUUID().toString(); String hash = TokenHash.sha256(token);
        String key = "zhicai:auth:session:" + hash; ownedKeys.add(key);
        sessions.put(hash, "integration:0", Duration.ofHours(2));
        assertThat(redis.getExpire(key)).isBetween(7190L, 7200L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("integration:0").doesNotContain(token);
        assertThat(sessions.find(hash)).isEqualTo("integration:0");
        sessions.remove(hash); assertThat(sessions.find(hash)).isNull();
        sessions.put(hash, "integration:0", Duration.ofHours(2));
        redis.expire(key, Duration.ZERO); assertThat(sessions.find(hash)).isNull();
    }
    @Test void failuresSetAtomicTtlAndBlockKnownOrUnknownIdentityEqually() {
        var throttle = new RedisLoginThrottle(redis);
        String identity = "integration-unknown-" + UUID.randomUUID();
        String address = "integration-" + UUID.randomUUID();
        ownedKeys.add("zhicai:auth:budget:v2:identity:" + TokenHash.sha256(identity));
        ownedKeys.add("zhicai:auth:budget:v2:ip:" + TokenHash.sha256(address));
        for (int i=0; i<5; i++) throttle.failed(throttle.reserve(identity, address));
        assertThatThrownBy(() -> throttle.reserve(identity, address)).isInstanceOfSatisfying(ApiException.class,
            error -> { assertThat(error.status()).isEqualTo(429); assertThat(error.code()).isEqualTo("RATE_LIMITED"); });
        for (String key : ownedKeys) assertThat(redis.getExpire(key)).isBetween(890L, 900L);
    }
    @Test void actualConnectionFailureMapsTo503() {
        var broken = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1), clientConfiguration());
        broken.afterPropertiesSet(); broken.start();
        try {
            var sessions = new RedisSessionStore(new StringRedisTemplate(broken));
            assertThatThrownBy(() -> sessions.find(TokenHash.sha256("integration"))).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(503));
            var throttle = new RedisLoginThrottle(new StringRedisTemplate(broken));
            assertThatThrownBy(() -> throttle.reserve("integration", "integration")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(503));
        } finally { broken.destroy(); }
    }
    @Test void concurrentIdentityBudgetIncludesPendingAttempts() throws Exception {
        concurrentBudget(12, 5, false);
    }
    @Test void concurrentNetworkBudgetIncludesPendingAttempts() throws Exception {
        concurrentBudget(40, 30, true);
    }
    @Test void successfulReservationReleasesOnlyItselfAndNeverAnotherFailureOrPendingAttempt() {
        var throttle = new RedisLoginThrottle(redis);
        String identity = "settlement-" + UUID.randomUUID();
        String address = "integration-" + UUID.randomUUID();
        ownBudgetKeys(identity, address);
        var failed = throttle.reserve(identity, address);
        var successful = throttle.reserve(identity, address);
        var pending = throttle.reserve(identity, address);
        throttle.failed(failed);
        throttle.succeeded(successful);
        throttle.succeeded(successful); // A repeated callback must not release somebody else's slot.
        throttle.succeeded(failed); // Settled failures cannot later be reclassified as successful.
        assertThat(redis.opsForHash().get(ownedKeys.get(0), "count")).isEqualTo("2");
        for (int i=0; i<3; i++) throttle.failed(throttle.reserve(identity, address));
        assertThatThrownBy(() -> throttle.reserve(identity, address)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.status()).isEqualTo(429));
        throttle.succeeded(pending);
        throttle.failed(throttle.reserve(identity, address));
        assertThatThrownBy(() -> throttle.reserve(identity, address)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.status()).isEqualTo(429));
    }
    @Test void oldReservationCannotReleaseBudgetInAReplacementWindow() {
        var throttle = new RedisLoginThrottle(redis);
        String identity = "expiry-" + UUID.randomUUID();
        String address = "integration-" + UUID.randomUUID();
        ownBudgetKeys(identity, address);
        var old = throttle.reserve(identity, address);
        // The two budgets can expire independently; only the identity window is replaced here.
        redis.expire(ownedKeys.get(0), Duration.ZERO);
        for (int i=0; i<5; i++) throttle.failed(throttle.reserve(identity, address));
        throttle.succeeded(old);
        assertThat(redis.opsForHash().get(ownedKeys.get(0), "count")).isEqualTo("5");
        assertThat(redis.opsForHash().get(ownedKeys.get(1), "count")).isEqualTo("5");
        assertThatThrownBy(() -> throttle.reserve(identity, address)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.status()).isEqualTo(429));
    }
    private void ownBudgetKeys(String identity, String address) {
        ownedKeys.add("zhicai:auth:budget:v2:identity:" + TokenHash.sha256(identity));
        ownedKeys.add("zhicai:auth:budget:v2:ip:" + TokenHash.sha256(address));
    }
    @Test void sessionDependencyFailureReleasesOnlyItsOwnPendingBudget() {
        String username = "outage_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String address = "integration-" + UUID.randomUUID();
        ownBudgetKeys("STAFF:" + username, address);
        var sessions = new SessionStore() {
            public void put(String hash, String subject, Duration ttl) { throw ApiException.unavailable(); }
            public String find(String hash) { throw new UnsupportedOperationException(); }
            public void remove(String hash) { throw new UnsupportedOperationException(); }
        };
        var passwords = new PasswordEncoder() {
            public String encode(CharSequence raw) { return "test-hash"; }
            public boolean matches(CharSequence raw, String hash) { return true; }
        };
        var auth = new AuthService(testAccounts(), sessions, new RedisLoginThrottle(redis), passwords, Clock.systemUTC());
        for (int i=0; i<7; i++) assertThatThrownBy(() -> auth.login(AccountType.STAFF, username, "correctPassword123", address))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(503));
        for (String key : ownedKeys) {
            assertThat(redis.opsForHash().get(key, "count")).isEqualTo("0");
            assertThat(redis.getExpire(key)).isBetween(890L, 900L);
        }
    }
    @Test void successfulLoginDoesNotEraseAConcurrentFailedLogin() throws Exception {
        String username = "mixed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String address = "integration-" + UUID.randomUUID();
        ownBudgetKeys("STAFF:" + username, address);
        CountDownLatch bothCheckingPasswords = new CountDownLatch(2);
        PasswordEncoder passwords = new PasswordEncoder() {
            public String encode(CharSequence raw) { return "test-hash"; }
            public boolean matches(CharSequence raw, String encoded) {
                bothCheckingPasswords.countDown();
                try { if (!bothCheckingPasswords.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent login timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return raw.toString().equals("correctPassword123");
            }
        };
        var auth = new AuthService(testAccounts(), new RedisSessionStore(redis), new RedisLoginThrottle(redis), passwords, Clock.systemUTC());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var success = executor.submit(() -> auth.login(AccountType.STAFF, username, "correctPassword123", address));
            var failure = executor.submit(() -> {
                try { auth.login(AccountType.STAFF, username, "wrongPassword123", address); return 200; }
                catch (ApiException error) { return error.status(); }
            });
            String token = success.get(10, TimeUnit.SECONDS).accessToken();
            ownedKeys.add("zhicai:auth:session:" + TokenHash.sha256(token));
            assertThat(failure.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        }
        assertThat(redis.opsForHash().get(ownedKeys.get(0), "count")).isEqualTo("1");
        for (int i=0; i<4; i++) assertThatThrownBy(() -> auth.login(AccountType.STAFF, username, "wrongPassword123", address))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(401));
        assertThatThrownBy(() -> auth.login(AccountType.STAFF, username, "correctPassword123", address))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(429));
    }
    private void concurrentBudget(int attempts, int allowed, boolean distinctUsers) throws Exception {
        String namespace = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String address = "integration-" + namespace;
        List<String> usernames = new ArrayList<>();
        for (int i=0; i<attempts; i++) usernames.add("race_" + namespace + (distinctUsers ? i : ""));
        for (String username : new HashSet<>(usernames))
            ownedKeys.add("zhicai:auth:budget:v2:identity:" + TokenHash.sha256("STAFF:" + username));
        ownedKeys.add("zhicai:auth:budget:v2:ip:" + TokenHash.sha256(address));
        CountDownLatch decided = new CountDownLatch(attempts);
        CountDownLatch finishPasswordCheck = new CountDownLatch(1);
        AtomicInteger enteredPasswordCheck = new AtomicInteger();
        PasswordEncoder slowPasswords = new PasswordEncoder() {
            public String encode(CharSequence raw) { return "test-hash"; }
            public boolean matches(CharSequence raw, String encoded) {
                enteredPasswordCheck.incrementAndGet(); decided.countDown();
                try { if (!finishPasswordCheck.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Password-check gate timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return false;
            }
        };
        AuthService auth = new AuthService(testAccounts(), new RedisSessionStore(redis), new RedisLoginThrottle(redis), slowPasswords, Clock.systemUTC());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (String username : usernames) responses.add(executor.submit(() -> {
                try { auth.login(AccountType.STAFF, username, "wrongPassword123", address); return 200; }
                catch (ApiException error) { if (error.status() == 429) decided.countDown(); return error.status(); }
            }));
            boolean allDecided;
            int admitted;
            try { allDecided = decided.await(8, TimeUnit.SECONDS); admitted = enteredPasswordCheck.get(); }
            finally { finishPasswordCheck.countDown(); }
            List<Integer> statuses = new ArrayList<>();
            for (var response : responses) statuses.add(response.get(10, TimeUnit.SECONDS));
            assertThat(allDecided).isTrue();
            assertThat(admitted).as("pending attempts must consume the shared budget before password checking").isEqualTo(allowed);
            assertThat(statuses.stream().filter(status -> status == 401).count()).isEqualTo(allowed);
            assertThat(statuses.stream().filter(status -> status == 429).count()).isEqualTo(attempts - allowed);
        }
    }
    private IdentityMapper testAccounts() {
        return new IdentityMapper() {
            public AccountRow findByUsername(AccountType type, String username) {
                return new AccountRow("1", username, "test-hash", "test", type, true, 0);
            }
            public AccountRow findById(String id) { throw new UnsupportedOperationException(); }
            public List<String> permissions(String id) { throw new UnsupportedOperationException(); }
            public List<String> warehouses(String id) { throw new UnsupportedOperationException(); }
            public List<String> roles(String id) { throw new UnsupportedOperationException(); }
        };
    }
    private static LettuceClientConfiguration clientConfiguration() {
        return LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
            .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(2)).build()).build()).build();
    }
}
