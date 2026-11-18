package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.MockPaymentService;
import com.zhicai.platform.write.CatalogWriteExecutor;
import com.zhicai.platform.write.MockPaymentApplication;
import com.zhicai.platform.write.ReplayCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockPaymentApplicationTest {
    private static final String KEY = "mock-payment-key-12345";
    private static final MockPaymentService.Success EVENT = new MockPaymentService.Success("event-1", "channel-1", "100", "CNY");
    private static final AccountView BUYER = actor("4", AccountType.CUSTOMER, List.of());
    private static final AccountView OPERATOR = actor("7", AccountType.STAFF, List.of("system:write"));
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MockPaymentService service = mock(MockPaymentService.class);
    private final String trace = UUID.randomUUID().toString();

    @Test void invalidRoleChannelAndReceiptDoNotReachExecutorOrCore() {
        var customerJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var staffJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var cipher = new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var app = new MockPaymentApplication(service,
            new CatalogWriteExecutor(customerJdbc, manager, json, cipher, Clock.systemUTC(), AccountType.CUSTOMER),
            new CatalogWriteExecutor(staffJdbc, manager, json, cipher, Clock.systemUTC(), AccountType.STAFF), json);
        assertThatThrownBy(() -> app.start(null, "5", "MOCK", KEY, trace)).isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(401);
        assertThatThrownBy(() -> app.start(OPERATOR, "5", "MOCK", KEY, trace)).isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(403);
        assertThatThrownBy(() -> app.success(actor("7", AccountType.STAFF, List.of()), "11", "SUCCEEDED", EVENT, KEY, trace))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        assertThatThrownBy(() -> app.start(BUYER, "5", "SANDBOX", KEY, trace)).isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(400);
        assertThatThrownBy(() -> app.start(BUYER, "5", "MOCK", null, trace)).isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(400);
        assertThatThrownBy(() -> app.success(OPERATOR, "11", "FAILED", EVENT, KEY, trace))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        assertThatThrownBy(() -> app.success(OPERATOR, "011", "SUCCEEDED", EVENT, KEY, trace))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        verifyNoInteractions(customerJdbc, staffJdbc, manager, service);
    }

    @Test void exactHttpReplayNeverReexecutesAndAuditOnlyContainsIdAndState() throws Exception {
        var customer = new CustomerWriteExecutorTest.MemoryJdbc();
        var staff = new CustomerWriteExecutorTest.MemoryJdbc();
        var app = new MockPaymentApplication(service, writer(customer, AccountType.CUSTOMER), writer(staff, AccountType.STAFF), json);
        when(service.start("4", "5")).thenReturn(payment("PROCESSING"));
        when(service.acceptSuccess("11", EVENT)).thenReturn(payment("SUCCEEDED"));

        var started = app.start(BUYER, "5", "MOCK", KEY, trace);
        assertThat(started.status()).isEqualTo(201);
        assertThat(app.start(BUYER, "5", "MOCK", KEY, UUID.randomUUID().toString()).body()).isEqualTo(started.body());
        assertThat(customer.actor).isEqualTo("CUSTOMER:4");
        assertThat(json.readTree(customer.audit).path("after")).isEqualTo(json.readTree("{\"id\":\"11\",\"state\":\"PROCESSING\"}"));

        var succeeded = app.success(OPERATOR, "11", "SUCCEEDED", EVENT, KEY, trace);
        assertThat(succeeded.status()).isEqualTo(200);
        assertThat(app.success(OPERATOR, "11", "SUCCEEDED", EVENT, KEY, UUID.randomUUID().toString()).body()).isEqualTo(succeeded.body());
        assertThat(staff.actor).isEqualTo("STAFF:7");
        assertThat(json.readTree(staff.audit).path("after")).isEqualTo(json.readTree("{\"id\":\"11\",\"state\":\"SUCCEEDED\"}"));
        assertThatThrownBy(() -> app.success(OPERATOR, "11", "SUCCEEDED",
            new MockPaymentService.Success("event-1", "changed", "100", "CNY"), KEY, trace))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        verify(service).start("4", "5");
        verify(service).acceptSuccess("11", EVENT);
        verifyNoMoreInteractions(service);
    }

    private CatalogWriteExecutor writer(CustomerWriteExecutorTest.MemoryJdbc jdbc, AccountType type) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new CatalogWriteExecutor(jdbc, manager, json, new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32])), Clock.systemUTC(), type);
    }

    private static MockPaymentService.Result payment(String state) {
        return new MockPaymentService.Result("11", "5", "MOCK-11", "MOCK", state, "100", "CNY", Instant.parse("2026-11-18T12:00:00Z"));
    }

    private static AccountView actor(String id, AccountType type, List<String> grants) {
        return new AccountView(id, "actor", "Actor", type, grants, List.of(), List.of(), true, 0);
    }
}
