package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.web.ApiAdvice;
import com.zhicai.platform.write.CatalogWriteExecutor;
import com.zhicai.platform.write.ReplayCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogWriteExecutorTest {
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final CatalogWriteExecutor executor = new CatalogWriteExecutor(jdbc, manager, json,
        new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32])), Clock.systemUTC());

    @Test void invalidKeyTraceOrActorCannotReachDatabase() {
        assertThatThrownBy(() -> executor.execute(request("1", "short", UUID.randomUUID().toString()), () -> null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> executor.execute(request("1", UUID.randomUUID().toString(), "invalid"), () -> null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute(request("0", UUID.randomUUID().toString(), UUID.randomUUID().toString()), () -> null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, manager);
    }

    @Test void callerCannotWrapExecutorInAnOuterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> executor.execute(request("1", UUID.randomUUID().toString(), UUID.randomUUID().toString()), () -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outer business transaction");
            verifyNoInteractions(jdbc, manager);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void inProgressHttpResponseIncludesRetryAfterOneSecond() throws Exception {
        MockMvcBuilders.standaloneSetup(new BusyController()).setControllerAdvice(new ApiAdvice()).build()
            .perform(get("/busy"))
            .andExpect(status().isConflict()).andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
    }

    @Test void claimedOwnerAndContenderCanBothBeBusyWithoutExecutingBusinessWork() {
        // A duplicate INSERT can briefly hold a shared lock while the owner requests NOWAIT.
        var memory = new CustomerWriteExecutorTest.MemoryJdbc() {
            @Override public <T> T queryForObject(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                if (sql.endsWith("FOR UPDATE NOWAIT"))
                    throw new org.springframework.dao.CannotAcquireLockException("Simulated claim-row lock contention");
                return super.queryForObject(sql, mapper, args);
            }
        };
        var writer = new CustomerWriteExecutorTest().executor(memory, com.zhicai.platform.identity.AccountType.CUSTOMER);
        var request = request("1", UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<CatalogWriteExecutor.Outcome> operation = () -> { calls.incrementAndGet(); throw new AssertionError("No business work allowed"); };
        for (int i = 0; i < 2; i++)
            assertThatThrownBy(() -> writer.execute(request, operation)).isInstanceOfSatisfying(ApiException.class,
                e -> { assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo("REQUEST_IN_PROGRESS"); });
        assertThat(calls).hasValue(0);
        assertThat(memory.state).isEqualTo("IN_PROGRESS");
        assertThat(memory.audit).isNull();
        assertThat(memory.encrypted).isNull();
    }

    private CatalogWriteExecutor.Request request(String actor, String key, String trace) {
        return new CatalogWriteExecutor.Request(actor, "PUT", "/api/v1/admin/brands/1", key, json.createObjectNode(), trace);
    }
    @RestController static class BusyController {
        @GetMapping("/busy") Object busy() { throw new ApiException(409, "REQUEST_IN_PROGRESS", "处理中"); }
    }
}
