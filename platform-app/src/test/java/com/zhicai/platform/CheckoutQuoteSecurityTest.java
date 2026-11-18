package com.zhicai.platform;

import com.zhicai.platform.identity.*;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.*;
import com.zhicai.platform.write.*;
import com.zhicai.platform.trade.QuoteModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CheckoutQuoteController.class)
@Import({SecurityConfiguration.class,ErrorResponder.class})
class CheckoutQuoteSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean QuoteApplication app;
    static final String ROUTE="/api/v1/checkout/quotes",KEY="key-12345678901234",BODY="{\"addressId\":\"2\",\"items\":[{\"skuId\":\"3\",\"quantity\":1}]}";
    AccountView actor(AccountType type){return new AccountView("1","actor","Actor",type,List.of("catalog:write"),List.of(),List.of(),true,0);}
    void customer(){when(auth.authenticate("customer")).thenReturn(actor(AccountType.CUSTOMER));}
    @Test void anonymous401AndStaff403CannotReachApplication()throws Exception{
        when(auth.authenticate("staff")).thenReturn(actor(AccountType.STAFF));
        mvc.perform(post(ROUTE).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("Authorization","Bearer staff").contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(app);
    }
    @Test void customerGetsExactStored201EnvelopeAndAuthenticatedOwner()throws Exception{
        customer();String stored="{\"code\":\"OK\",\"message\":\"成功\",\"data\":{\"id\":\"9007199254740993\"},\"traceId\":\"original\"}";
        when(app.create(any(),any(),eq(KEY),anyString())).thenReturn(new CatalogWriteExecutor.Response(201,stored,true));
        mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json").content(BODY))
            .andExpect(status().isCreated()).andExpect(content().string(stored)).andExpect(content().contentType("application/json"));
        verify(app).create(eq(actor(AccountType.CUSTOMER)),eq(new Request("2",List.of(new Item("3",1)),null)),eq(KEY),anyString());
    }
    @Test void strictShapeTypesRangesAndOverflowFailBeforeApplication()throws Exception{
        customer();
        var invalid=new ArrayList<>(List.of("null","[]","{}",BODY.replace("\"2\"","2"),BODY.replace("\"2\"","null"),BODY.replace("\"2\"","\"99999999999999999999\""),
            BODY.replace("\"3\"","3"),BODY.replace("\"3\"","null"),BODY.replace("\"3\"","\"99999999999999999999\""),
            BODY.replace("1}]","1.0}]"),BODY.replace("1}]","\"1\"}]"),BODY.replace("1}]","null}]"),BODY.replace("1}]","0}]"),BODY.replace("1}]","100}]"),
            BODY.replace("1}]","2147483648}]"),BODY.replace("1}]","18446744073709551617}]"),BODY.replace("1}]","1,\"priceMinor\":\"1\"}]"),
            BODY.replace("}]}","}],\"owner\":\"2\"}"),BODY.replace("}]}","}],\"couponId\":null}"),BODY.replace("}]}","}],\"couponId\":4}"),
            BODY.replace("}]}","}],\"couponId\":\"99999999999999999999\"}"),"{\"addressId\":\"2\",\"items\":[]}","{\"addressId\":\"2\",\"items\":[null]}"));
        invalid.add("{\"addressId\":\"2\",\"items\":["+String.join(",",Collections.nCopies(101,"{\"skuId\":\"3\",\"quantity\":1}"))+"]}");
        invalid.add("{\"addressId\":\"2\",\"items\":[{\"skuId\":\"3\",\"quantity\":50},{\"skuId\":\"3\",\"quantity\":50}]}");
        for(String body:invalid)mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(app);
    }
    @Test void missingAndMalformedKeysFailBeforeApplication()throws Exception{
        customer();
        mvc.perform(post(ROUTE).header("Authorization","Bearer customer").contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        for(String key:List.of("short","x".repeat(129),"with space-123456789"))mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",key).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(app);
    }
    @Test void optionalCouponAcceptedAndUnsupportedMethodsRemainDenied()throws Exception{
        customer();when(app.create(any(),any(),anyString(),anyString())).thenReturn(new CatalogWriteExecutor.Response(201,"{}",false));
        mvc.perform(post(ROUTE).header("Authorization","Bearer customer").header("Idempotency-Key",KEY).contentType("application/json").content(BODY.replace("}]}","}],\"couponId\":\"4\"}"))).andExpect(status().isCreated());
        verify(app).create(any(),eq(new Request("2",List.of(new Item("3",1)),"4")),eq(KEY),anyString());
        for(var request:List.of(get(ROUTE),put(ROUTE),delete(ROUTE),post(ROUTE+"/7")))mvc.perform(request.header("Authorization","Bearer customer")).andExpect(status().isForbidden());
        verifyNoMoreInteractions(app);
    }
}
