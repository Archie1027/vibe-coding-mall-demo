package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.trade.OrderModels;
import com.zhicai.platform.trade.OrderService;
import com.zhicai.platform.trade.TradeModels;
import com.zhicai.platform.write.*;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderApplicationTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @SuppressWarnings("unchecked") final ObjectProvider<OrderService> services=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> executors=mock(ObjectProvider.class);
    final OrderService service=mock(OrderService.class);
    final OrderApplication app=new OrderApplication(services,executors,json);
    final String key="key-12345678901234",trace=UUID.randomUUID().toString();
    AccountView customer(){return new AccountView("1","buyer","Buyer",AccountType.CUSTOMER,List.of(),List.of(),List.of(),true,0);}

    @Test void validatesActorInputAndKeyBeforeResolvingDependencies(){
        assertThatThrownBy(()->app.create(null,new OrderModels.Create("2",null),key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(401);
        var staff=new AccountView("1","staff","Staff",AccountType.STAFF,List.of(),List.of(),List.of(),true,0);
        assertThatThrownBy(()->app.create(staff,new OrderModels.Create("2",null),key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
        for(var input:List.of(new OrderModels.Create("0",null),new OrderModels.Create("02",null),new OrderModels.Create("2","x".repeat(201))))
            assertThatThrownBy(()->app.create(customer(),input,key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        assertThatThrownBy(()->app.create(customer(),new OrderModels.Create("2",null),"short",trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        verifyNoInteractions(services,executors,service);
    }
    @Test void defaultDisabledReturns503BeforeBusinessService(){
        assertThatThrownBy(()->app.create(customer(),new OrderModels.Create("2",null),key,trace))
            .isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        verify(executors).getIfAvailable();verifyNoInteractions(services,service);
    }
    @Test void acceptedRequestRunsOnceAndReplaysOnlyMaskedAudit()throws Exception{
        var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var writer=new CatalogWriteExecutor(jdbc,manager,json,new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32])),java.time.Clock.systemUTC(),AccountType.CUSTOMER);
        when(executors.getIfAvailable()).thenReturn(writer);when(services.getIfAvailable()).thenReturn(service);
        var input=new OrderModels.Create("2","请勿附私密信息");
        var order=new OrderModels.Order("7","ZCtest","1","3","UNPAID",List.of(),
            new TradeModels.AddressInput("仅测试收件人","10000000007","江苏","南京","玄武","仅测试地址",false),
            "100","0","0","100","0",Instant.parse("2026-10-27T01:30:00Z"),Instant.parse("2026-10-27T01:00:00Z"),0);
        when(service.create("1",input,trace)).thenReturn(order);
        var first=app.create(customer(),input,key,trace);
        assertThat(first.status()).isEqualTo(201);
        assertThat(json.readTree(first.body()).path("data").toString()).isEqualTo(json.valueToTree(order).toString());
        assertThat(json.readTree(jdbc.audit)).isEqualTo(json.readTree("{\"before\":null,\"after\":{\"id\":\"7\",\"state\":\"UNPAID\"}}"));
        assertThat(jdbc.actor).isEqualTo("CUSTOMER:1");
        assertThat(app.create(customer(),input,key,UUID.randomUUID().toString()).body()).isEqualTo(first.body());
        String originalAudit=jdbc.audit;
        for(var changed:List.of(new OrderModels.Create("3",input.remark()),new OrderModels.Create("2","更改备注"))) {
            assertThatThrownBy(()->app.create(customer(),changed,key,UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(ApiException.class,error->{
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        }
        assertThat(jdbc.audit).isEqualTo(originalAudit);
        verify(service,times(1)).create(anyString(),any(),anyString());
    }
    @Test void missingQuoteReplaysRejectionWithoutRepeatingOrderCreation() {
        var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var writer=new CatalogWriteExecutor(jdbc,manager,json,new ReplayCipher(Base64.getEncoder().encodeToString(new byte[32])),java.time.Clock.systemUTC(),AccountType.CUSTOMER);
        when(executors.getIfAvailable()).thenReturn(writer);when(services.getIfAvailable()).thenReturn(service);
        var input=new OrderModels.Create("2",null);
        when(service.create("1",input,trace)).thenThrow(new ApiException(404,"NOT_FOUND","报价不存在"));
        var first=app.create(customer(),input,key,trace);
        var replay=app.create(customer(),input,key,UUID.randomUUID().toString());
        assertThat(first.status()).isEqualTo(404);
        assertThat(replay.status()).isEqualTo(404);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.audit).isNull();
        verify(service,times(1)).create(anyString(),any(),anyString());
    }
}
