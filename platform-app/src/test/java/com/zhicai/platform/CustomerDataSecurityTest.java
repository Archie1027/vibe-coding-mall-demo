package com.zhicai.platform;

import com.zhicai.platform.identity.*;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.*;
import com.zhicai.platform.write.*;
import com.zhicai.platform.catalog.CatalogModels.PageData;
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

@WebMvcTest(CustomerDataController.class)
@Import({SecurityConfiguration.class, ErrorResponder.class})
class CustomerDataSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean CustomerDataApplication data;
    static final String ADDRESS="{\"recipient\":\"演示\",\"phone\":\"10000000007\",\"province\":\"江苏\",\"city\":\"南京\",\"district\":\"演示区\",\"detail\":\"街道1号\",\"isDefault\":true}";
    void customer() { when(auth.authenticate("customer")).thenReturn(actor(AccountType.CUSTOMER)); }
    AccountView actor(AccountType type) { return new AccountView("1","actor","Actor",type,List.of("catalog:write"),List.of(),List.of(),true,0); }
    @Test void allCustomerRoutesRejectAnonymousAndStaff() throws Exception {
        when(auth.authenticate("staff")).thenReturn(actor(AccountType.STAFF));
        for(String path:List.of("/api/v1/me/addresses","/api/v1/me/cart")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer staff")).andExpect(status().isForbidden());
        }
        for(var req:List.of(post("/api/v1/me/addresses"),put("/api/v1/me/addresses/1"),delete("/api/v1/me/addresses/1"),
            post("/api/v1/me/cart/items"),put("/api/v1/me/cart/items/1"),delete("/api/v1/me/cart/items/1")))
            mvc.perform(req.header("Authorization","Bearer staff")).andExpect(status().isForbidden());
        verifyNoInteractions(data);
    }
    @Test void usesAuthenticatedOwnerAndOriginalWriteEnvelope() throws Exception {
        customer();
        when(data.addresses(any(),eq(2),eq(20))).thenReturn(new PageData<>(List.of(),2,20,21));
        mvc.perform(get("/api/v1/me/addresses?page=2&size=20").header("Authorization","Bearer customer"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(21));
        String stored="{\"code\":\"OK\",\"data\":{\"id\":\"9007199254740993\"},\"traceId\":\"original\"}";
        when(data.saveAddress(any(),isNull(),any(),isNull(),eq("key-12345678901234"),anyString()))
            .thenReturn(new CatalogWriteExecutor.Response(201,stored,true));
        mvc.perform(post("/api/v1/me/addresses").header("Authorization","Bearer customer").header("Idempotency-Key","key-12345678901234")
            .contentType("application/json").content(ADDRESS)).andExpect(status().isCreated()).andExpect(content().json(stored));
        verify(data).saveAddress(eq(actor(AccountType.CUSTOMER)),isNull(),any(),isNull(),eq("key-12345678901234"),anyString());
    }
    @Test void strictWireTypesAndOwnershipFieldsRejectedBeforeApplication() throws Exception {
        customer();
        for(String body:List.of(ADDRESS.replace("true","\"true\""),ADDRESS.replace("10000000007","123"),ADDRESS.replace("}",",\"customerId\":\"2\"}")))
            mvc.perform(post("/api/v1/me/addresses").header("Authorization","Bearer customer").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(String body:List.of("{\"skuId\":1,\"quantity\":1}","{\"skuId\":\"1\",\"quantity\":1.5}","{\"skuId\":\"1\",\"quantity\":\"1\"}","{\"skuId\":\"1\",\"quantity\":100}","{\"skuId\":\"1\",\"quantity\":1,\"priceMinor\":\"1\"}"))
            mvc.perform(post("/api/v1/me/cart/items").header("Authorization","Bearer customer").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/addresses/1").header("Authorization","Bearer customer").contentType("application/json").content(ADDRESS.replace("}",",\"version\":0.5}"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/cart/items/1").header("Authorization","Bearer customer").contentType("application/json").content("{\"quantity\":1,\"selected\":\"true\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(data);
    }
    @Test void paginationAndDeleteBodiesAreValidated() throws Exception {
        customer();
        for(String suffix:List.of("?page=0","?page=1.5","?size=101","?size=no"))
            mvc.perform(get("/api/v1/me/cart"+suffix).header("Authorization","Bearer customer")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/me/addresses/1").header("Authorization","Bearer customer").contentType("application/json").content("null")).andExpect(status().isBadRequest());
        verifyNoInteractions(data);
    }
    @Test void cartWriteAndDeleteKeepStatusAndAck() throws Exception {
        customer();
        when(data.addCart(any(),eq("1"),eq(2),any(),anyString())).thenReturn(new CatalogWriteExecutor.Response(201,"{\"code\":\"OK\",\"data\":{\"quantity\":2},\"traceId\":\"stored\"}",false));
        when(data.deleteCart(any(),eq("9"),any(),anyString())).thenReturn(new CatalogWriteExecutor.Response(200,"{\"code\":\"OK\",\"data\":{\"accepted\":true},\"traceId\":\"stored\"}",false));
        mvc.perform(post("/api/v1/me/cart/items").header("Authorization","Bearer customer").contentType("application/json").content("{\"skuId\":\"1\",\"quantity\":2}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.quantity").value(2));
        mvc.perform(delete("/api/v1/me/cart/items/9").header("Authorization","Bearer customer"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
    }
}
