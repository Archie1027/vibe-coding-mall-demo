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

@WebMvcTest(OrderCancelController.class)
@Import({SecurityConfiguration.class,ErrorResponder.class})
class OrderCancelSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean OrderCancelApplication orders;
    static final String ROUTE="/api/v1/orders/5/cancel",KEY="cancel-key-1234567890";
    AccountView actor(AccountType type){return new AccountView("1","buyer","Buyer",type,List.of(),List.of(),List.of(),true,0);}
    @Test void anonymousAndStaffCannotCancel()throws Exception{
        when(auth.authenticate("staff")).thenReturn(actor(AccountType.STAFF));
        mvc.perform(post(ROUTE).contentType("application/json").content("{\"reason\":\"r\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("Authorization","Bearer staff").contentType("application/json").content("{\"reason\":\"r\"}")).andExpect(status().isForbidden());verifyNoInteractions(orders);
    }
    @Test void strictReasonOnlyAndOriginal200Response()throws Exception{
        when(auth.authenticate("customer")).thenReturn(actor(AccountType.CUSTOMER));
        for(String body:List.of("{}","[]","{\"reason\":1}","{\"reason\":null}","{\"reason\":\"r\",\"customerId\":\"2\"}"))
            mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
        when(orders.cancel(any(),eq("5"),eq("r"),eq(KEY),anyString())).thenReturn(new CatalogWriteExecutor.Response(200,"{\"data\":{\"state\":\"CLOSING\"}}",false));
        mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json").content("{\"reason\":\"r\"}"))
            .andExpect(status().isOk()).andExpect(content().json("{\"data\":{\"state\":\"CLOSING\"}}"));
    }
}
