package com.zhicai.platform;

import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.trade.OrderReadService;
import com.zhicai.platform.web.*;
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

@WebMvcTest(OrderReadController.class)
@Import({SecurityConfiguration.class,ErrorResponder.class})
class OrderReadSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean OrderReadService orders;
    private AccountView actor(AccountType type){return new AccountView("7","reader","Reader",type,List.of(),List.of(),List.of(),true,0);}

    @Test void anonymousAndStaffCannotReadOrders()throws Exception{
        when(auth.authenticate("staff")).thenReturn(actor(AccountType.STAFF));
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").header("Authorization","Bearer staff")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/2").header("Authorization","Bearer staff")).andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }

    @Test void customerReadsOwnPageAndDetail()throws Exception{
        when(auth.authenticate("customer")).thenReturn(actor(AccountType.CUSTOMER));
        when(orders.list("7",2,10,"UNPAID")).thenReturn(new PageData<>(List.of(),2,10,0));
        mvc.perform(get("/api/v1/orders?page=2&size=10&state=UNPAID").header("Authorization","Bearer customer"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        mvc.perform(get("/api/v1/orders/2").header("Authorization","Bearer customer"))
            .andExpect(status().isOk());
        verify(orders).list("7",2,10,"UNPAID");
        verify(orders).find("7","2");
    }
}
