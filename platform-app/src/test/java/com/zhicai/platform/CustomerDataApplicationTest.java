package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.write.*;
import com.zhicai.platform.trade.*;
import com.zhicai.platform.trade.TradeModels.*;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CustomerDataApplicationTest {
    final AddressService addresses=mock(AddressService.class);
    final CartService cart=mock(CartService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> provider=mock(ObjectProvider.class);
    final CustomerDataApplication app=new CustomerDataApplication(addresses,cart,provider,new ObjectMapper());
    final AccountView customer=new AccountView("1","customer","Customer",AccountType.CUSTOMER,List.of(),List.of(),List.of(),true,0);
    final AddressInput input=new AddressInput("演示","10000000007","江苏","南京","演示区","街道1号",true);
    final String key="key-12345678901234",trace=UUID.randomUUID().toString();
    CustomerWriteExecutorTest.MemoryJdbc enable() {
        var jdbc=new CustomerWriteExecutorTest.MemoryJdbc();
        var executor=new CustomerWriteExecutorTest().executor(jdbc,AccountType.CUSTOMER);
        when(provider.getIfAvailable()).thenReturn(executor);
        return jdbc;
    }
    @Test void readsUseActorAndWritesFailClosedBeforeDomainWhenDisabled() {
        app.addresses(customer,1,20); app.cart(customer,2,10);
        verify(addresses).list("1",1,20); verify(cart).list("1",2,10);
        assertThatThrownBy(()->app.saveAddress(customer,null,input,null,key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(503);
        verify(addresses,never()).create(any(),any());
        assertThatThrownBy(()->app.addCart(null,"1",1,key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(401);
        var staff=new AccountView("1","staff","Staff",AccountType.STAFF,List.of("catalog:write"),List.of(),List.of(),true,0);
        assertThatThrownBy(()->app.deleteCart(staff,"1",key,trace)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(403);
    }
    @Test void encryptedAddressReplayKeepsOwnerAndDoesNotCallDomainTwice() throws Exception {
        var jdbc=enable(); var after=new Address("7","演示","10000000007","江苏","南京","演示区","街道1号",true,0);
        when(addresses.create("1",input)).thenReturn(new AddressChange(null,after));
        var first=app.saveAddress(customer,null,input,null,key,trace);
        assertThat(first.status()).isEqualTo(201);assertThat(jdbc.actor).isEqualTo("CUSTOMER:1");
        assertThat(jdbc.audit).doesNotContain("10000000007","街道","recipient");
        assertThat(app.saveAddress(customer,null,input,null,key,UUID.randomUUID().toString()).body()).isEqualTo(first.body());
        verify(addresses,times(1)).create("1",input);
    }
    @Test void cartMergeRemains201AndDeleteReturnsAck() throws Exception {
        enable(); var before=new CartLine("9","4","商品",List.of(),"99",1,true,true,null,null);
        var after=new CartLine("9","4","商品",List.of(),"99",3,true,true,null,null);
        when(cart.add("1","4",2)).thenReturn(new CartChange(before,after));
        assertThat(app.addCart(customer,"4",2,key,trace).status()).isEqualTo(201);
        enable(); when(cart.delete("1","9")).thenReturn(new CartChange(after,null));
        var deleted=app.deleteCart(customer,"9",key,trace);
        assertThat(new ObjectMapper().readTree(deleted.body()).at("/data/accepted").booleanValue()).isTrue();
    }
}
