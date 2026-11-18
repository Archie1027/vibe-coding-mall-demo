package com.zhicai.platform;

import com.zhicai.platform.identity.*;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.*;
import com.zhicai.platform.write.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderWriteController.class)
@Import({SecurityConfiguration.class,ErrorResponder.class})
class OrderWriteSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean OrderApplication orders;
    static final String ROUTE="/api/v1/orders",KEY="key-12345678901234";
    AccountView actor(AccountType type){return new AccountView("1","actor","Actor",type,List.of(),List.of(),List.of(),true,0);}
    @Test void anonymousAndStaffNeverReachWriter()throws Exception{
        when(auth.authenticate("staff")).thenReturn(actor(AccountType.STAFF));
        mvc.perform(post(ROUTE).contentType("application/json").content("{\"quoteId\":\"2\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("Authorization","Bearer staff").contentType("application/json").content("{\"quoteId\":\"2\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }
    @Test void onlyStrictCustomerBodyReachesApplication()throws Exception{
        when(auth.authenticate("customer")).thenReturn(actor(AccountType.CUSTOMER));
        when(orders.create(any(),any(),eq(KEY),anyString())).thenReturn(new CatalogWriteExecutor.Response(201,"{\"code\":\"OK\"}",false));
        for(String body:List.of("{}","[]","{\"quoteId\":2}","{\"quoteId\":\"2\",\"remark\":null}",
            "{\"quoteId\":\"2\",\"extra\":1}"))
            mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
        mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json")
            .content("{\"quoteId\":\"2\",\"remark\":\"测试\"}"))
            .andExpect(status().isCreated());
        verify(orders).create(eq(actor(AccountType.CUSTOMER)),eq(new com.zhicai.platform.trade.OrderModels.Create("2","测试")),eq(KEY),anyString());
    }
}
