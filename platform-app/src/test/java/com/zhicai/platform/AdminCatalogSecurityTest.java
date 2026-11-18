package com.zhicai.platform;

import com.zhicai.platform.catalog.CatalogQueryService;
import com.zhicai.platform.catalog.CatalogModels.*;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.*;
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

@WebMvcTest(AdminCatalogController.class)
@Import({SecurityConfiguration.class, ErrorResponder.class})
class AdminCatalogSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean CatalogQueryService catalog;
    void token(String token, AccountType type, List<String> permissions) {
        when(auth.authenticate(token)).thenReturn(new AccountView("1", "reader", "Reader", type, permissions, List.of(), List.of(), true, 0));
    }
    @Test void anonymousAndAccountsWithoutPermissionCannotRead() throws Exception {
        mvc.perform(get("/api/v1/admin/products")).andExpect(status().isUnauthorized());
        token("customer", AccountType.CUSTOMER, List.of());
        token("staff", AccountType.STAFF, List.of("order:read"));
        for (String value : List.of("customer", "staff"))
            mvc.perform(get("/api/v1/admin/products").header("Authorization", "Bearer " + value))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }
    @Test void readerGetsRealPagingAndDisabledDictionaryValues() throws Exception {
        token("reader", AccountType.STAFF, List.of("catalog:read"));
        when(catalog.adminCategories(1, 20, null)).thenReturn(new PageData<>(List.of(new Category("101", "停用分类", null, 1, false, 2)), 1, 20, 1));
        when(catalog.adminBrands(1, 20, null)).thenReturn(new PageData<>(List.of(new Brand("201", "品牌", false, 2)), 1, 20, 1));
        when(catalog.adminProducts(2, 20, "手机")).thenReturn(new PageData<>(List.of(), 2, 20, 100));
        mvc.perform(get("/api/v1/admin/categories").header("Authorization", "Bearer reader"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].enabled").value(false));
        mvc.perform(get("/api/v1/admin/brands").header("Authorization", "Bearer reader"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("201"));
        mvc.perform(get("/api/v1/admin/products").param("page", "2").param("keyword", "手机").header("Authorization", "Bearer reader"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.total").value(100));
    }
    @Test void readerCannotWriteAndInvalidParametersKeepEnvelope() throws Exception {
        token("reader", AccountType.STAFF, List.of("catalog:read"));
        mvc.perform(post("/api/v1/admin/products").header("Authorization", "Bearer reader"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/products").param("page", "abc").header("Authorization", "Bearer reader"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
