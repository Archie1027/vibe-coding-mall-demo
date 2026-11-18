package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.trade.*;
import com.zhicai.platform.write.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCancelApplicationTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @SuppressWarnings("unchecked") final ObjectProvider<OrderClosingService> services=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> executors=mock(ObjectProvider.class);
    final OrderClosingService service=mock(OrderClosingService.class);
    final OrderReadService reads=mock(OrderReadService.class);
    final OrderCancelApplication app=new OrderCancelApplication(services,executors,reads,json);
    final String key="cancel-key-1234567890",trace=UUID.randomUUID().toString();
    AccountView actor(AccountType type){return new AccountView("1","buyer","Buyer",type,List.of(),List.of(),List.of(),true,0);}
    @Test void authenticationAndValidationPrecedeDependencies(){
        assertThatThrownBy(()->app.cancel(null,"5","r",key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(401);
        assertThatThrownBy(()->app.cancel(actor(AccountType.STAFF),"5","r",key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        for(String id:List.of("0","05"))assertThatThrownBy(()->app.cancel(actor(AccountType.CUSTOMER),id,"r",key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        assertThatThrownBy(()->app.cancel(actor(AccountType.CUSTOMER),"5","😀".repeat(501),key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        assertThatThrownBy(()->app.cancel(actor(AccountType.CUSTOMER),"5","r","short",trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        verifyNoInteractions(services,executors,reads,service);
    }
    @Test void disabledCoreReturns503WithoutReadingOrders(){
        assertThatThrownBy(()->app.cancel(actor(AccountType.CUSTOMER),"5","r",key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        verifyNoInteractions(service,reads,services);
    }
    @Test void returnsFullClosingOrderReplaysExactlyAndMasksReason()throws Exception{
        var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var writer=new CatalogWriteExecutor(jdbc,manager,json,new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32])),Clock.systemUTC(),AccountType.CUSTOMER);
        when(executors.getIfAvailable()).thenReturn(writer);when(services.getIfAvailable()).thenReturn(service);
        when(service.cancel("1","5","私密取消原因",trace)).thenReturn(new OrderClosingService.Result("5","CLOSING",8,null,"私密取消原因"));
        var order=new OrderModels.Order("5","ZC-test","1","3","CLOSING",List.of(),new TradeModels.AddressInput("收件人","10000000007","江苏","南京","玄武","仅测试",false),"100","0","0","100","0",Instant.now().plusSeconds(100),Instant.now(),8);
        when(reads.find("1","5")).thenReturn(order);
        var first=app.cancel(actor(AccountType.CUSTOMER),"5","私密取消原因",key,trace);
        assertThat(first.status()).isEqualTo(200);assertThat(json.readTree(first.body()).path("data")).isEqualTo(json.readTree(json.writeValueAsString(order)));
        assertThat(jdbc.audit).doesNotContain("私密取消原因","收件人","10000000007");assertThat(json.readTree(jdbc.audit).path("after").path("state").asText()).isEqualTo("CLOSING");
        assertThat(app.cancel(actor(AccountType.CUSTOMER),"5","私密取消原因",key,UUID.randomUUID().toString()).body()).isEqualTo(first.body());
        assertThatThrownBy(()->app.cancel(actor(AccountType.CUSTOMER),"5","新原因",key,trace)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        verify(service).cancel("1","5","私密取消原因",trace);verify(reads).find("1","5");
    }
}
