package com.zhicai.platform;

import com.zhicai.platform.identity.*;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.*;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminBrandController.class)
@Import({SecurityConfiguration.class, ErrorResponder.class})
class AdminBrandSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean BrandWriteApplication writes;
    void token(String token, AccountType type, List<String> permissions) {
        when(auth.authenticate(token)).thenReturn(new AccountView("1", "actor", "Actor", type, permissions, List.of(), List.of(), true, 0));
    }
    @Test void onlyStaffWritersCanCreateOrUpdate() throws Exception {
        mvc.perform(post("/api/v1/admin/brands")).andExpect(status().isUnauthorized());
        token("reader", AccountType.STAFF, List.of("catalog:read"));
        token("customer", AccountType.CUSTOMER, List.of("catalog:write"));
        for (String token : List.of("reader", "customer")) {
            mvc.perform(post("/api/v1/admin/brands").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/admin/brands/1").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(writes);
    }
    @Test void returnsStoredEnvelopeStatusAndOriginalTraceWithoutRewrapping() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        String body = "{\"code\":\"OK\",\"data\":{\"id\":\"9007199254740993\"},\"traceId\":\"original-trace\"}";
        when(writes.save(any(), isNull(), eq("品牌"), eq(true), isNull(), eq("test-key-12345678"), anyString()))
            .thenReturn(new CatalogWriteExecutor.Response(201, body, true));
        when(writes.save(any(), eq("1"), eq("新名"), eq(false), eq(3L), eq("test-key-12345678"), anyString()))
            .thenReturn(new CatalogWriteExecutor.Response(200, body, false));
        mvc.perform(post("/api/v1/admin/brands").header("Authorization", "Bearer writer").header("Idempotency-Key", "test-key-12345678")
            .contentType("application/json").content("{\"name\":\"品牌\",\"enabled\":true}"))
            .andExpect(status().isCreated()).andExpect(content().json(body));
        mvc.perform(put("/api/v1/admin/brands/1").header("Authorization", "Bearer writer").header("Idempotency-Key", "test-key-12345678")
            .contentType("application/json").content("{\"name\":\"新名\",\"enabled\":false,\"version\":3}"))
            .andExpect(status().isOk()).andExpect(content().json(body));
    }
    @Test void rejectsInvalidFieldsAndUnknownFieldsBeforeApplication() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        for (String body : List.of("{\"name\":\" \u0020\",\"enabled\":true}", "{\"name\":\"品牌\"}", "{\"name\":\"品牌\",\"enabled\":true,\"role\":\"admin\"}",
            "{\"name\":\"" + "a".repeat(121) + "\",\"enabled\":true}"))
            mvc.perform(post("/api/v1/admin/brands").header("Authorization", "Bearer writer").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(put("/api/v1/admin/brands/0").header("Authorization", "Bearer writer").contentType("application/json")
            .content("{\"name\":\"品牌\",\"enabled\":true,\"version\":0}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/brands/1").header("Authorization", "Bearer writer").contentType("application/json")
            .content("{\"name\":\"品牌\",\"enabled\":true,\"version\":-1}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(writes);
    }
    @Test void rejectsCoercedBooleanAndFractionalVersion() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        mvc.perform(post("/api/v1/admin/brands").header("Authorization", "Bearer writer").contentType("application/json")
            .content("{\"name\":\"品牌\",\"enabled\":\"true\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(put("/api/v1/admin/brands/1").header("Authorization", "Bearer writer").contentType("application/json")
            .content("{\"name\":\"品牌\",\"enabled\":true,\"version\":0.8}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(writes);
    }
    @Test void transactionConnectionFailureReturns503AndBusyReturnsRetryAfter() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        when(writes.save(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("private-db-details"));
        mvc.perform(post("/api/v1/admin/brands").header("Authorization", "Bearer writer").contentType("application/json")
            .content("{\"name\":\"品牌\",\"enabled\":true}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        doThrow(new com.zhicai.platform.common.ApiException(409, "REQUEST_IN_PROGRESS", "处理中"))
            .when(writes).save(any(), any(), any(), any(), any(), any(), any());
        mvc.perform(post("/api/v1/admin/brands").header("Authorization", "Bearer writer").contentType("application/json")
            .content("{\"name\":\"品牌\",\"enabled\":true}"))
            .andExpect(status().isConflict()).andExpect(header().string("Retry-After", "1"));
    }
}
