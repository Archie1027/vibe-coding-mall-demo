package com.zhicai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.write.CatalogWriteExecutor;
import com.zhicai.platform.write.CatalogWriteExecutor.*;
import com.zhicai.platform.write.ReplayCipher;
import com.zhicai.platform.write.WriteRequestFingerprint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Writes only self-owned V3 test metadata. Enabling requires explicit approval; never edits catalog rows. */
@EnabledIfEnvironmentVariable(named = "M1_WRITE_MYSQL_INTEGRATION", matches = "approved-v3-test-metadata")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.flyway.enabled=false")
class MySqlWriteExecutorIntegrationTest {
    private static final String ACTOR = "1", RESOURCE = "9007199254740993";
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    private final List<Request> owned = new ArrayList<>();
    private final Set<String> traces = new HashSet<>();
    private MutableClock clock;
    private CatalogWriteExecutor executor;
    private ExecutorService workers;

    @BeforeEach void prepareApprovedEmptyTables() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        clock = new MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        executor = new CatalogWriteExecutor(jdbc, transactions, json, new ReplayCipher(Base64.getEncoder().encodeToString(key)), clock);
        workers = Executors.newSingleThreadExecutor();
    }

    @AfterEach void cleanOnlyOwnedMetadata() throws Exception {
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("No live transaction during cleanup").isTrue();
        }
        for (String trace : traces)
            jdbc.update("DELETE FROM audit_log WHERE actor_id=1 AND resource_type='BRAND' AND resource_id=9007199254740993 AND action='BRAND_UPDATE' AND trace_id=?", trace);
        var fingerprints = new WriteRequestFingerprint(json);
        for (Request request : owned)
            jdbc.update("DELETE FROM idempotency_request WHERE actor_scope='STAFF:1' AND method=? AND route=? AND key_hash=?",
                request.method(), request.route(), fingerprints.keyHash(request.key()));
        if (!owned.isEmpty()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Integer.class)).isEqualTo(26);
        }
    }

    @Test void successReplaysOriginalResponseOnceAndMasksAudit() throws Exception {
        var request = request("PUT"); var calls = new AtomicInteger();
        Supplier<Outcome> work = () -> { calls.incrementAndGet(); return success(); };
        Response first = executor.execute(request, work);
        // A new HTTP trace is allowed; the original response and trace must be replayed exactly.
        var retry = new Request(ACTOR, request.method(), request.route(), request.key(), request.body(), trace());
        Response second = executor.execute(retry, work);
        assertThat(first.status()).isEqualTo(200); assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue(); assertThat(second.body()).isEqualTo(first.body());
        assertThat(calls.get()).isEqualTo(1); assertThat(auditCount()).isEqualTo(1);
        byte[] stored = jdbc.queryForObject("SELECT encrypted_response FROM idempotency_request", byte[].class);
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("演示事务", request.traceId());
        JsonNode audit = json.readTree(jdbc.queryForObject("SELECT masked_before_after FROM audit_log", String.class));
        assertThat(audit.path("after").path("name").asText()).isEqualTo("演示事务");
        assertThat(audit.path("after").has("password")).isFalse();
        assertThat(audit.path("after").has("specs")).isFalse();
    }

    @Test void changedBodyConflictsWithoutAnotherAudit() {
        var request = request("PUT"); executor.execute(request, this::success);
        var changed = new Request(ACTOR, request.method(), request.route(), request.key(), json.createObjectNode().put("name", "different"), trace());
        assertCode(() -> executor.execute(changed, this::success), "IDEMPOTENCY_CONFLICT");
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test void liveTransactionRejectsConcurrentReplayWithoutWaitingForItsRowLock() throws Exception { concurrentAttempt(false); }
    @Test void expiredLeaseCannotStealALiveBusinessTransaction() throws Exception { concurrentAttempt(true); }

    private void concurrentAttempt(boolean expireLease) throws Exception {
        var request = request("PUT"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        Future<Response> first = workers.submit(() -> executor.execute(request, () -> {
            entered.countDown();
            try { if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return success();
        }));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (expireLease) clock.advance(Duration.ofSeconds(121));
            long started = System.nanoTime();
            assertCode(() -> executor.execute(request, this::success), "REQUEST_IN_PROGRESS");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
        Response committed = first.get(5, TimeUnit.SECONDS);
        assertThat(executor.execute(request, this::success).body()).isEqualTo(committed.body());
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test void businessRejectionRollsBackMutationAndCachesOriginalFailure() {
        var request = request("PUT"); var calls = new AtomicInteger();
        Supplier<Outcome> rejected = () -> {
            calls.incrementAndGet(); marker(request.traceId());
            throw new ApiException(409, "VERSION_CONFLICT", "演示版本冲突");
        };
        Response first = executor.execute(request, rejected);
        Response retry = executor.execute(request, rejected);
        assertThat(first.status()).isEqualTo(409); assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.replayed()).isTrue(); assertThat(calls.get()).isEqualTo(1);
        assertThat(auditCount()).isZero();
    }

    @Test void unknownFailureKeepsClaimUntilLockedLeaseRecovery() {
        var request = request("PUT");
        assertThatThrownBy(() -> executor.execute(request, () -> {
            marker(request.traceId()); throw new IllegalStateException("simulated infrastructure failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(auditCount()).isZero();
        assertCode(() -> executor.execute(request, this::success), "REQUEST_IN_PROGRESS");
        clock.advance(Duration.ofSeconds(121));
        assertThat(executor.execute(request, this::success).replayed()).isFalse();
        assertThat(executor.execute(request, this::success).replayed()).isTrue();
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test void expiredCompletedKeyStartsNewGenerationAndMethodPathHaveIndependentScopes() {
        var request = request("PUT"); Response first = executor.execute(request, this::success);
        clock.advance(Duration.ofHours(25));
        assertThat(executor.execute(request, this::success).replayed()).isFalse();
        assertThat(executor.execute(request, this::success).body()).isEqualTo(first.body());
        var differentMethod = new Request(ACTOR, "POST", request.route(), request.key(), request.body(), trace()); owned.add(differentMethod);
        var differentPath = new Request(ACTOR, "PUT", request.route() + "/other", request.key(), request.body(), trace()); owned.add(differentPath);
        assertThat(executor.execute(differentMethod, this::success).replayed()).isFalse();
        assertThat(executor.execute(differentPath, this::success).replayed()).isFalse();
        assertThat(auditCount()).isEqualTo(4);
    }

    private Request request(String method) {
        Request request = new Request(ACTOR, method, "/api/v1/admin/write-executor-test/" + UUID.randomUUID(), UUID.randomUUID().toString(), json.createObjectNode().put("name", "演示事务"), trace());
        owned.add(request); return request;
    }
    private String trace() { String value = UUID.randomUUID().toString(); traces.add(value); return value; }
    private Outcome success() {
        return new Outcome(200, Map.of("id", RESOURCE, "name", "演示事务"),
            new Audit("BRAND_UPDATE", "BRAND", RESOURCE, null, json.createObjectNode().put("name", "演示事务").put("password", "excluded-secret").set("specs", json.createObjectNode())));
    }
    private void marker(String trace) {
        jdbc.update("INSERT INTO audit_log(actor_id,action,resource_type,resource_id,masked_before_after,result,trace_id) VALUES(1,'BRAND_UPDATE','BRAND',9007199254740993,'{}','SUCCESS',?)", trace);
    }
    private int auditCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(409); assertThat(error.code()).isEqualTo(code);
        });
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time;
        MutableClock(Instant time) { this.time = new AtomicReference<>(time); }
        void advance(Duration duration) { time.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("UTC only"); return this; }
        @Override public Instant instant() { return time.get(); }
    }
}
