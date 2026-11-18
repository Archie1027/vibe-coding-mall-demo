package com.zhicai.platform;

import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.identity.AuthService;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.trade.MockPaymentService;
import com.zhicai.platform.web.ErrorResponder;
import com.zhicai.platform.web.MockPaymentController;
import com.zhicai.platform.write.CatalogWriteExecutor;
import com.zhicai.platform.write.MockPaymentApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(controllers = MockPaymentController.class, properties = "app.payment.mock-enabled=true")
@Import({SecurityConfiguration.class, ErrorResponder.class})
class MockPaymentSecurityTest {
    private static final String START = "/api/v1/payments";
    private static final String EVENT = "/api/v1/admin/payments/11/mock-events";
    private static final String KEY = "mock-payment-key-12345";
    private static final String START_BODY = """
        {"orderId":"5","channel":"MOCK"}
        """;
    private static final String EVENT_BODY = """
        {"eventId":"e1","result":"SUCCEEDED","channelTransactionId":"t1","amountMinor":"100","currency":"CNY"}
        """;

    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean MockPaymentApplication payments;

    @Test void onlyCustomerStartsAndOnlyStaffWithPermissionCanSendEvents() throws Exception {
        when(auth.authenticate("buyer")).thenReturn(actor(AccountType.CUSTOMER, List.of("system:write")));
        when(auth.authenticate("staff")).thenReturn(actor(AccountType.STAFF, List.of()));
        when(auth.authenticate("operator")).thenReturn(actor(AccountType.STAFF, List.of("system:write")));
        mvc.perform(post(START).contentType("application/json").content(START_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(START).header("Authorization", "Bearer operator").contentType("application/json").content(START_BODY))
            .andExpect(status().isForbidden());
        mvc.perform(post(EVENT).header("Authorization", "Bearer buyer").contentType("application/json").content(EVENT_BODY))
            .andExpect(status().isForbidden());
        mvc.perform(post(EVENT).header("Authorization", "Bearer staff").contentType("application/json").content(EVENT_BODY))
            .andExpect(status().isForbidden());
        mvc.perform(get(EVENT).header("Authorization", "Bearer operator")).andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    @Test void strictJsonAndOriginalApplicationResponses() throws Exception {
        when(auth.authenticate("buyer")).thenReturn(actor(AccountType.CUSTOMER, List.of()));
        when(auth.authenticate("operator")).thenReturn(actor(AccountType.STAFF, List.of("system:write")));
        for (String invalid : List.of("{}", "[]", "{\"orderId\":5,\"channel\":\"MOCK\"}",
            "{\"orderId\":\"5\",\"channel\":\"MOCK\",\"amountMinor\":\"1\"}")) {
            mvc.perform(post(START).header("Authorization", "Bearer buyer").header("Idempotency-Key", KEY)
                .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(EVENT).header("Authorization", "Bearer operator").header("Idempotency-Key", KEY)
            .contentType("application/json").content(EVENT_BODY.replace("\"100\"", "100"))).andExpect(status().isBadRequest());
        verifyNoInteractions(payments);

        when(payments.start(any(), eq("5"), eq("MOCK"), eq(KEY), anyString()))
            .thenReturn(new CatalogWriteExecutor.Response(201, "{\"data\":{\"state\":\"PROCESSING\"}}", false));
        when(payments.success(any(), eq("11"), eq("SUCCEEDED"), eq(new MockPaymentService.Success("e1","t1","100","CNY")), eq(KEY), anyString()))
            .thenReturn(new CatalogWriteExecutor.Response(200, "{\"data\":{\"state\":\"SUCCEEDED\"}}", true));
        mvc.perform(post(START).header("Authorization", "Bearer buyer").header("Idempotency-Key", KEY)
            .contentType("application/json").content(START_BODY))
            .andExpect(status().isCreated()).andExpect(content().string("{\"data\":{\"state\":\"PROCESSING\"}}"));
        mvc.perform(post(EVENT).header("Authorization", "Bearer operator").header("Idempotency-Key", KEY)
            .contentType("application/json").content(EVENT_BODY))
            .andExpect(status().isOk()).andExpect(content().string("{\"data\":{\"state\":\"SUCCEEDED\"}}"));
    }

    private static AccountView actor(AccountType type, List<String> grants) {
        return new AccountView("4", "actor", "Actor", type, grants, List.of(), List.of(), true, 0);
    }
}
