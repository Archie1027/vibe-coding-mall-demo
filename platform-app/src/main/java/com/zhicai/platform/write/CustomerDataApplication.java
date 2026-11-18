package com.zhicai.platform.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.catalog.CatalogModels.PageData;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.trade.AddressService;
import com.zhicai.platform.trade.CartService;
import com.zhicai.platform.trade.TradeModels.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;

@Service
public class CustomerDataApplication {
    private final AddressService addresses;
    private final CartService cart;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    public CustomerDataApplication(AddressService addresses, CartService cart,
        @Qualifier("customerWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors, ObjectMapper json) {
        this.addresses=addresses; this.cart=cart; this.executors=executors; this.json=json;
    }
    public PageData<Address> addresses(AccountView actor,int page,int size) { return addresses.list(owner(actor),page,size); }
    public PageData<CartLine> cart(AccountView actor,int page,int size) { return cart.list(owner(actor),page,size); }
    public CatalogWriteExecutor.Response saveAddress(AccountView actor,String id,AddressInput input,Long version,String key,String trace) {
        String owner=owner(actor);
        if(id==null) AddressService.validate(input); else AddressService.validateUpdate(id,input,version);
        var body=json.valueToTree(input);
        if(id!=null) ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("version",version);
        return write(actor,id==null?"POST":"PUT","/me/addresses"+(id==null?"":"/"+id),body,key,trace,()->{
            var change=id==null?addresses.create(owner,input):addresses.update(owner,id,input,version);
            return outcome(id==null?201:200,change.after(),id==null?"ADDRESS_CREATE":"ADDRESS_UPDATE","ADDRESS",change.after().id(),change.before(),change.after());
        });
    }
    public CatalogWriteExecutor.Response deleteAddress(AccountView actor,String id,String key,String trace) {
        String owner=owner(actor); validId(id);
        return write(actor,"DELETE","/me/addresses/"+id,json.nullNode(),key,trace,()->{
            var change=addresses.delete(owner,id);
            return outcome(200,new Ack(true),"ADDRESS_DELETE","ADDRESS",id,change.before(),null);
        });
    }
    public CatalogWriteExecutor.Response addCart(AccountView actor,String skuId,Integer quantity,String key,String trace) {
        String owner=owner(actor); CartService.validateAdd(skuId,quantity);
        var body=json.createObjectNode().put("skuId",skuId).put("quantity",quantity);
        return write(actor,"POST","/me/cart/items",body,key,trace,()->{
            var change=cart.add(owner,skuId,quantity);
            return outcome(201,change.after(),change.before()==null?"CART_CREATE":"CART_UPDATE","CART",change.after().id(),change.before(),change.after());
        });
    }
    public CatalogWriteExecutor.Response updateCart(AccountView actor,String id,Integer quantity,Boolean selected,String key,String trace) {
        String owner=owner(actor); CartService.validateUpdate(id,quantity,selected);
        var body=json.createObjectNode().put("quantity",quantity).put("selected",selected);
        return write(actor,"PUT","/me/cart/items/"+id,body,key,trace,()->{
            var change=cart.update(owner,id,quantity,selected);
            return outcome(200,change.after(),"CART_UPDATE","CART",id,change.before(),change.after());
        });
    }
    public CatalogWriteExecutor.Response deleteCart(AccountView actor,String id,String key,String trace) {
        String owner=owner(actor); validId(id);
        return write(actor,"DELETE","/me/cart/items/"+id,json.nullNode(),key,trace,()->{
            var change=cart.delete(owner,id);
            return outcome(200,new Ack(true),"CART_DELETE","CART",id,change.before(),null);
        });
    }
    private CatalogWriteExecutor.Response write(AccountView actor,String method,String path,JsonNode body,String key,String trace,Supplier<CatalogWriteExecutor.Outcome> operation) {
        new WriteRequestFingerprint(json).keyHash(key);
        var executor=executors.getIfAvailable();
        if(executor==null) throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","顾客写入功能尚未启用");
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(),method,"/api/v1"+path,key,body,trace),operation);
    }
    private CatalogWriteExecutor.Outcome outcome(int status,Object data,String action,String type,String id,Object before,Object after) {
        return new CatalogWriteExecutor.Outcome(status,data,new CatalogWriteExecutor.Audit(action,type,id,json.valueToTree(before),json.valueToTree(after)));
    }
    private String owner(AccountView actor) {
        if(actor==null) throw ApiException.unauthenticated();
        if(actor.accountType()!=AccountType.CUSTOMER) throw new ApiException(403,"FORBIDDEN","仅顾客可以管理本人地址与购物车");
        return actor.id();
    }
    private static void validId(String id) {
        if(id==null || !id.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(id).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0)
            throw new ApiException(400,"VALIDATION_ERROR","资源ID不合法");
    }
    public record Ack(boolean accepted) {}
}
