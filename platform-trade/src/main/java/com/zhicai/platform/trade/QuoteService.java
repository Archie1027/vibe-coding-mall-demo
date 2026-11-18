package com.zhicai.platform.trade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.inventory.StockPolicy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.zhicai.platform.trade.QuoteModels.*;

/**
 * Internal persistent quote core. A future op020 application must explicitly register a transactional
 * proxy and supply its writable READ_COMMITTED outer transaction, permission and idempotency boundary.
 * Quotes never lock or reserve inventory. Failures must propagate so the caller rolls back.
 */
public class QuoteService {
    private final QuoteMapper mapper;
    private final AddressMapper addresses;
    private final CheckoutCatalogService catalog;
    private final InventoryAvailabilityService inventory;
    private final ObjectMapper json;
    private final Clock clock;
    public QuoteService(QuoteMapper mapper,AddressMapper addresses,CheckoutCatalogService catalog,
                        InventoryAvailabilityService inventory,ObjectMapper json,Clock clock){
        this.mapper=Objects.requireNonNull(mapper);this.addresses=Objects.requireNonNull(addresses);
        this.catalog=Objects.requireNonNull(catalog);this.inventory=Objects.requireNonNull(inventory);
        this.json=Objects.requireNonNull(json);this.clock=Objects.requireNonNull(clock);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Quote create(String owner,Request request){
        var quantities=validate(owner,request);requireTransaction();
        if(request.couponId()!=null)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","优惠券服务尚未接入，请取消选券后重试");
        var address=addresses.find(owner,request.addressId());
        if(address==null)throw TradeValidation.notFound("地址");
        var skus=catalog.skus(List.copyOf(quantities.keySet()));
        var pricing=new ArrayList<CheckoutPricing.Item>();
        for(var row:quantities.entrySet()){
            var sku=skus.get(row.getKey());
            if(sku==null)throw new ApiException(409,"SKU_NOT_AVAILABLE","SKU不存在或当前不可售");
            pricing.add(new CheckoutPricing.Item(row.getKey(),price(sku.priceMinor()),row.getValue()));
        }
        var priced=CheckoutPricing.calculate(pricing,null);
        var warehouse=inventory.selectWarehouse(quantities.entrySet().stream().map(row->new StockPolicy.Demand(row.getKey(),row.getValue())).toList());
        var lines=new ArrayList<Line>();var snapshots=new ArrayList<SnapshotLine>();
        for(var row:priced.items()){
            var sku=skus.get(row.skuId());
            var line=new Line(row.skuId(),sku.productName(),sku.specs(),row.quantity(),Long.toString(row.unitPriceMinor()),Long.toString(row.grossMinor()),Long.toString(row.discountMinor()),Long.toString(row.payableMinor()));
            lines.add(line);snapshots.add(new SnapshotLine(sku,line));
        }
        var created=clock.instant().truncatedTo(ChronoUnit.MILLIS);var expires=created.plusSeconds(300);
        var snapshot=new Snapshot(1,owner,request.addressId(),address,warehouse,snapshots,Long.toString(priced.grossMinor()),Long.toString(priced.discountMinor()),Long.toString(priced.shippingMinor()),Long.toString(priced.payableMinor()),request.couponId(),created,expires);
        var draft=new QuoteMapper.NewQuote(owner,warehouse,serialize(snapshot),expires,created);
        if(mapper.insert(draft)!=1 || draft.id==null)throw new IllegalStateException("Quote insert did not return identity");
        return new Quote(draft.id,warehouse,lines,snapshot.grossMinor(),snapshot.discountMinor(),snapshot.shippingMinor(),snapshot.payableMinor(),snapshot.couponId(),expires);
    }
    public static LinkedHashMap<String,Integer> validate(String owner,Request request){
        TradeValidation.id(owner);if(request==null)TradeValidation.bad("报价请求不能为空");
        TradeValidation.id(request.addressId());if(request.couponId()!=null)TradeValidation.id(request.couponId());
        if(request.items()==null || request.items().isEmpty() || request.items().size()>100)TradeValidation.bad("报价商品必须为1至100行");
        var quantities=new LinkedHashMap<String,Integer>();
        for(var item:request.items()){
            if(item==null)TradeValidation.bad("报价商品不能为空");
            TradeValidation.id(item.skuId());TradeValidation.quantity(item.quantity());
            int quantity=quantities.getOrDefault(item.skuId(),0)+item.quantity();TradeValidation.quantity(quantity);quantities.put(item.skuId(),quantity);
        }
        return quantities;
    }
    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Quote creation requires an active, writable, explicitly READ_COMMITTED transaction");
    }
    private static long price(String value){
        if(value==null || !value.matches("[1-9][0-9]{0,17}"))throw new ApiException(422,"AMOUNT_INVALID","金额必须为支持范围内的正整数分");
        return Long.parseLong(value);
    }
    private String serialize(Snapshot snapshot){
        try{return json.writeValueAsString(snapshot);}
        catch(JsonProcessingException e){throw new IllegalStateException("Quote snapshot serialization failed",e);}
    }
}
