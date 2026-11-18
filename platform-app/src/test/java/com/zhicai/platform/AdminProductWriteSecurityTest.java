package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@WebMvcTest(AdminProductWriteController.class)
@Import({SecurityConfiguration.class, ErrorResponder.class})
class AdminProductWriteSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AuthService auth;
    @MockitoBean ProductWriteApplication writes;
    final String product = "{\"name\":\"商品\",\"categoryId\":\"1\",\"brandId\":\"2\",\"imageAssetIds\":[\"3\"],\"description\":\"text\",\"featured\":true}";
    final String sku = "{\"code\":\"CODE-1\",\"specs\":[{\"name\":\"容量\",\"value\":\"1TB\"}],\"priceMinor\":\"9007199254740993\",\"enabled\":true}";
    void token(String token, AccountType type, List<String> permissions) {
        when(auth.authenticate(token)).thenReturn(new AccountView("1", "actor", "Actor", type, permissions, List.of(), List.of(), true, 0));
    }
    @Test void allFiveRoutesRequireStaffAndWritePermission() throws Exception {
        token("reader", AccountType.STAFF, List.of("catalog:read")); token("customer", AccountType.CUSTOMER, List.of("catalog:write"));
        for (String token : List.of("reader", "customer")) {
            for (String path : List.of("/products", "/products/1/state", "/products/1/skus"))
                mvc.perform(post("/api/v1/admin" + path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            for (String path : List.of("/products/1", "/skus/1"))
                mvc.perform(put("/api/v1/admin" + path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/admin/products")).andExpect(status().isUnauthorized()); verifyNoInteractions(writes);
    }
    @Test void validRequestsPreserveOriginalStatusBodyAndExactPriceString() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        String body = "{\"code\":\"OK\",\"data\":{\"id\":\"9007199254740993\"},\"traceId\":\"original\"}";
        when(writes.saveProduct(any(), isNull(), any(), isNull(), any(), anyString())).thenReturn(new CatalogWriteExecutor.Response(201, body, true));
        mvc.perform(post("/api/v1/admin/products").header("Authorization", "Bearer writer").contentType("application/json").content(product))
            .andExpect(status().isCreated()).andExpect(content().json(body));
        when(writes.saveProduct(any(), eq("1"), any(), eq(0L), any(), anyString())).thenReturn(new CatalogWriteExecutor.Response(200, body, false));
        mvc.perform(put("/api/v1/admin/products/1").header("Authorization", "Bearer writer").contentType("application/json").content(product.replace("}", ",\"version\":0}")))
            .andExpect(status().isOk()).andExpect(content().json(body));
        when(writes.state(any(), eq("1"), eq("ON_SALE"), eq(0L), any(), anyString())).thenReturn(new CatalogWriteExecutor.Response(200, body, false));
        mvc.perform(post("/api/v1/admin/products/1/state").header("Authorization", "Bearer writer").contentType("application/json").content("{\"state\":\"ON_SALE\",\"version\":0}"))
            .andExpect(status().isOk()).andExpect(content().json(body));
        when(writes.saveSku(any(), eq("1"), isNull(), argThat(s -> s != null && s.priceMinor().equals("9007199254740993")), isNull(), any(), anyString()))
            .thenReturn(new CatalogWriteExecutor.Response(201, body, false));
        mvc.perform(post("/api/v1/admin/products/1/skus").header("Authorization", "Bearer writer").contentType("application/json").content(sku))
            .andExpect(status().isCreated()).andExpect(content().json(body));
        when(writes.saveSku(any(), isNull(), eq("2"), any(), eq(0L), any(), anyString())).thenReturn(new CatalogWriteExecutor.Response(200, body, false));
        mvc.perform(put("/api/v1/admin/skus/2").header("Authorization", "Bearer writer").contentType("application/json").content(sku.substring(0,sku.length()-1)+",\"version\":0}"))
            .andExpect(status().isOk()).andExpect(content().json(body));
    }
    @Test void malformedOrCoercedProductFieldsNeverReachWrites() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        for (String bad : List.of(product.replace("\"featured\":true", "\"featured\":\"true\""), product.replace("\"categoryId\":\"1\"", "\"categoryId\":1"),
            product.replace("[\"3\"]", "[3]"), product.replace("[\"3\"]", "[\"3\",\"3\"]"), product.replace("\"featured\":true", "\"featured\":true,\"state\":\"ON_SALE\""),
            product.replace("\"name\":\"商品\"", "\"name\":\" \"")))
            mvc.perform(post("/api/v1/admin/products").header("Authorization", "Bearer writer").contentType("application/json").content(bad))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(writes);
    }
    @Test void malformedSkuSpecsMoneyAndVersionsAreRejectedBeforeWrites() throws Exception {
        token("writer", AccountType.STAFF, List.of("catalog:write"));
        for (String bad : List.of(sku.replace("\"9007199254740993\"", "9007199254740993"), sku.replace("\"9007199254740993\"", "\"0\""),
            sku.replace("\"value\":\"1TB\"", "\"value\":\"1TB\",\"unknown\":1"), sku.replace("\"enabled\":true", "\"enabled\":1")))
            mvc.perform(post("/api/v1/admin/products/1/skus").header("Authorization", "Bearer writer").contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        for (String version : List.of("\"0\"", "0.1", "9007199254740991", "-1"))
            mvc.perform(post("/api/v1/admin/products/1/state").header("Authorization", "Bearer writer").contentType("application/json").content("{\"state\":\"ON_SALE\",\"version\":" + version + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(writes);
    }
}
