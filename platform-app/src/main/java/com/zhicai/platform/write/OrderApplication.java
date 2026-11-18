package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.OrderModels;
import com.zhicai.platform.trade.OrderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class OrderApplication {
    private final ObjectProvider<OrderService> orders;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    public OrderApplication(ObjectProvider<OrderService> orders,
        @Qualifier("customerWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors,ObjectMapper json){
        this.orders=orders;this.executors=executors;this.json=json;
    }
    public CatalogWriteExecutor.Response create(AccountView actor,OrderModels.Create input,String key,String trace){
        if(actor==null)throw ApiException.unauthenticated();
        if(actor.accountType()!=AccountType.CUSTOMER)throw new ApiException(403,"FORBIDDEN","仅顾客可以创建本人订单");
        OrderService.validate(actor.id(),input,trace);
        new WriteRequestFingerprint(json).keyHash(key);
        var executor=executors.getIfAvailable();
        if(executor==null)throw unavailable();
        var service=orders.getIfAvailable();
        if(service==null)throw unavailable();
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(),"POST","/api/v1/orders",key,json.valueToTree(input),trace),()->{
            var order=service.create(actor.id(),input,trace);
            var audit=json.createObjectNode().put("id",order.id()).put("state",order.state());
            return new CatalogWriteExecutor.Outcome(201,order,
                new CatalogWriteExecutor.Audit("ORDER_CREATE","ORDER",order.id(),null,audit));
        });
    }
    private static ApiException unavailable(){return new ApiException(503,"DEPENDENCY_UNAVAILABLE","订单写入功能尚未启用");}
}
