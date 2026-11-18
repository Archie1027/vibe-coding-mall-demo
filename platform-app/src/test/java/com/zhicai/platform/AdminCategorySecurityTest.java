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

@WebMvcTest(AdminCategoryController.class)
@Import({SecurityConfiguration.class, ErrorResponder.class})
class AdminCategorySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean CategoryWriteApplication writes;
    void token(String token, AccountType type, List<String> permissions) {
        when(auth.authenticate(token)).thenReturn(new AccountView("1", "actor", "Actor", type, permissions, List.of(), List.of(), true, 0));
    }
    @Test void onlyStaffWritersCanMaintainCategories() throws Exception {
        mvc.perform(post("/api/v1/admin/categories")).andExpect(status().isUnauthorized());
        token("reader", AccountType.STAFF, List.of("catalog:read")); token("customer", AccountType.CUSTOMER, List.of("catalog:write"));
        for (String token : List.of("reader", "customer"))
            mvc.perform(put("/api/v1/admin/categories/1").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        verifyNoInteractions(writes);
    }
    @Test void createAndUpdateReturnOriginalStatusEnvelopeAndNormalizeNullParent() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        String response = "{\"code\":\"OK\",\"data\":{\"id\":\"9007199254740993\"},\"traceId\":\"original\"}";
        when(writes.save(any(), isNull(), eq("分类"), isNull(), eq(0), eq(true), isNull(), any(), anyString())).thenReturn(new CatalogWriteExecutor.Response(201, response, true));
        for (String parent : List.of("", ",\"parentId\":null"))
            mvc.perform(post("/api/v1/admin/categories").header("Authorization", "Bearer writer").contentType("application/json").content("{\"name\":\"分类\",\"sort\":0,\"enabled\":true" + parent + "}"))
                .andExpect(status().isCreated()).andExpect(content().json(response));
        when(writes.save(any(), eq("1"), eq("分类"), eq("2"), eq(3), eq(false), eq(1L), any(), anyString())).thenReturn(new CatalogWriteExecutor.Response(200, response, false));
        mvc.perform(put("/api/v1/admin/categories/1").header("Authorization", "Bearer writer").contentType("application/json").content("{\"name\":\"分类\",\"parentId\":\"2\",\"sort\":3,\"enabled\":false,\"version\":1}"))
            .andExpect(status().isOk()).andExpect(content().json(response));
    }
    @Test void invalidTypesUnknownFieldsSortAndIdentityNeverReachApplication() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        for (String body : List.of("{\"name\":\"分类\",\"sort\":0.1,\"enabled\":true}", "{\"name\":\"分类\",\"sort\":0,\"enabled\":\"true\"}",
            "{\"name\":\"分类\",\"sort\":-1,\"enabled\":true}", "{\"name\":\"分类\",\"sort\":0,\"enabled\":true,\"parentId\":3}",
            "{\"name\":\"分类\",\"sort\":0,\"enabled\":true,\"unknown\":1}"))
            mvc.perform(post("/api/v1/admin/categories").header("Authorization", "Bearer writer").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(put("/api/v1/admin/categories/0").header("Authorization", "Bearer writer").contentType("application/json").content("{\"name\":\"分类\",\"sort\":0,\"enabled\":true,\"version\":0}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(writes);
    }
}
