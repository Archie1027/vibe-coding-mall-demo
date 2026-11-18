package com.zhicai.platform.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhicai.platform.catalog.CheckoutCatalogService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryAvailabilityService;
import com.zhicai.platform.inventory.InventoryReservationService;
import com.zhicai.platform.inventory.StockPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal order creation; the caller owns a writable, explicitly READ_COMMITTED transaction.
 * Direct construction does not install a Spring proxy. Any failure must escape the outer transaction.
 */
public class OrderService {
    private final OrderMapper mapper;
    private final CheckoutCatalogService catalog;
    private final InventoryAvailabilityService availability;
    private final InventoryReservationService reservations;
    private final ObjectMapper json;
    private final Clock clock;
    public OrderService(OrderMapper mapper,CheckoutCatalogService catalog,InventoryAvailabilityService availability,InventoryReservationService reservations,ObjectMapper json,Clock clock){
        this.mapper=Objects.requireNonNull(mapper);this.catalog=Objects.requireNonNull(catalog);this.availability=Objects.requireNonNull(availability);this.reservations=Objects.requireNonNull(reservations);this.json=Objects.requireNonNull(json);this.clock=Objects.requireNonNull(clock);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public OrderModels.Order create(String owner,OrderModels.Create request,String traceId){
        validate(owner,request,traceId);
        requireTransaction();
        var row=mapper.lockQuote(owner,request.quoteId());
        if(row==null || !owner.equals(row.owner()))throw new ApiException(404,"NOT_FOUND","报价不存在");
        if(!request.quoteId().equals(row.id()) || !validId(row.warehouseId()) || row.createdAt()==null || row.expiresAt()==null)invalid();
        if(row.usedOrderId()!=null)throw conflict();
        unexpired(row.expiresAt(),clock.instant());
        var quote=snapshot(row);
        if(quote.couponId()!=null)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","优惠券服务尚未接入，请取消选券后重试");
        var ids=quote.items().stream().map(line->line.catalog().skuId()).toList();
        var evidence=catalog.lockOrderEvidence(ids);
        // Fresh SKU/version/price evidence precedes availability, preserving QUOTE_CHANGED semantics.
        OrderQuoteVerifier.verify(quote,evidence.skus(),quote.warehouseId(),clock.instant());
        for(var line:quote.items())if(!evidence.imagesByProduct().containsKey(line.catalog().productId()))throw changed();
        var demands=quote.items().stream().map(line->new StockPolicy.Demand(line.catalog().skuId(),line.amounts().quantity())).toList();
        if(!quote.warehouseId().equals(availability.selectWarehouse(demands)))throw changed();
        var createdAt=clock.instant();
        unexpired(row.expiresAt(),createdAt);
        createdAt=createdAt.truncatedTo(ChronoUnit.MILLIS);
        var expiresAt=createdAt.plusSeconds(1800);
        var address=quote.address();
        var addressSnapshot=new TradeModels.AddressInput(address.recipient(),address.phone(),address.province(),address.city(),address.district(),address.detail(),address.isDefault());
        var order=new OrderMapper.NewOrder("ZC"+UUID.randomUUID().toString().replace("-",""),request.quoteId(),owner,quote.warehouseId(),encode(addressSnapshot),request.remark(),quote.grossMinor(),quote.discountMinor(),quote.shippingMinor(),quote.payableMinor(),expiresAt,createdAt);
        // Finish snapshot serialization before the first write, including every line's public image.
        var specs=new ArrayList<String>();var images=new ArrayList<String>();
        for(var line:quote.items()){
            specs.add(encode(line.amounts().specs()));
            images.add(encode(evidence.imagesByProduct().get(line.catalog().productId())));
        }
        if(mapper.insertOrder(order)!=1 || !validId(order.id))throw new IllegalStateException("Order identity not inserted");
        var lines=new ArrayList<OrderModels.Line>();var holds=new ArrayList<InventoryReservationService.Line>();
        for(int index=0;index<quote.items().size();index++){
            var line=quote.items().get(index);var amounts=line.amounts();
            var item=new OrderMapper.NewItem(order.id,index,amounts.skuId(),line.catalog().productId(),amounts.productName(),specs.get(index),images.get(index),amounts.quantity(),amounts.unitPriceMinor(),amounts.grossMinor(),amounts.discountMinor(),amounts.payableMinor(),createdAt);
            if(mapper.insertItem(item)!=1 || !validId(item.id))throw new IllegalStateException("Order item identity not inserted");
            lines.add(new OrderModels.Line(item.id,amounts.skuId(),amounts.productName(),amounts.specs(),amounts.quantity(),amounts.unitPriceMinor(),amounts.grossMinor(),amounts.discountMinor(),amounts.payableMinor(),0,"0"));
            holds.add(new InventoryReservationService.Line(item.id,amounts.skuId(),amounts.quantity()));
        }
        reservations.hold(new InventoryReservationService.Command(order.id,quote.warehouseId(),holds,owner,traceId));
        var markAt=clock.instant();
        unexpired(row.expiresAt(),markAt);
        if(mapper.markQuoteUsed(owner,request.quoteId(),order.id,markAt)!=1)throw conflict();
        return new OrderModels.Order(order.id,order.orderNo,owner,quote.warehouseId(),"UNPAID",lines,addressSnapshot,quote.grossMinor(),quote.discountMinor(),quote.shippingMinor(),quote.payableMinor(),"0",expiresAt,createdAt,0);
    }

    public static void validate(String owner,OrderModels.Create request,String traceId){
        if(!validId(owner) || request==null || !validId(request.quoteId())
            || (request.remark()!=null && request.remark().codePointCount(0,request.remark().length())>200)
            || traceId==null || !traceId.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new ApiException(400,"VALIDATION_ERROR","订单请求字段不合法");
    }

    private QuoteModels.Snapshot snapshot(OrderMapper.LockedQuote row){
        final QuoteModels.Snapshot quote;
        try{
            var node=json.readTree(row.snapshotJson());
            // Primitive defaults/coercion must not turn missing or malformed persisted fields into evidence.
            if(node==null || !node.isObject() || !node.path("formatVersion").isIntegralNumber() || node.path("formatVersion").intValue()!=1
                || !node.path("items").isArray() || node.path("items").isEmpty() || node.path("items").size()>100)invalid();
            for(var item:node.path("items")){
                var sku=item.path("catalog");var amounts=item.path("amounts");
                if(!item.isObject() || !sku.isObject() || !amounts.isObject() || !amounts.path("quantity").isIntegralNumber()
                    || !amounts.path("quantity").canConvertToInt() || !sku.path("productFeatured").isBoolean()
                    || !sku.path("specs").isArray() || !amounts.path("specs").isArray())invalid();
                for(var key:List.of("skuId","productId","productName","categoryId","brandId","priceMinor"))if(!sku.path(key).isTextual())invalid();
                for(var key:List.of("skuId","productName","unitPriceMinor","grossMinor","discountMinor","payableMinor"))if(!amounts.path(key).isTextual())invalid();
                for(var key:List.of("skuVersion","productVersion","categoryVersion","brandVersion"))if(!sku.path(key).isIntegralNumber() || !sku.path(key).canConvertToLong() || sku.path(key).longValue()<0)invalid();
                validSpecs(sku.path("specs"));validSpecs(amounts.path("specs"));
            }
            for(var key:List.of("owner","addressId","warehouseId","grossMinor","discountMinor","shippingMinor","payableMinor","createdAt","expiresAt"))if(!node.path(key).isTextual())invalid();
            var address=node.path("address");
            if(!address.isObject() || !address.path("id").isTextual() || !address.path("isDefault").isBoolean() || !address.path("version").isIntegralNumber() || !address.path("version").canConvertToLong() || address.path("version").longValue()<0)invalid();
            for(var key:List.of("recipient","phone","province","city","district","detail"))if(!address.path(key).isTextual())invalid();
            if(node.hasNonNull("couponId") && !node.path("couponId").isTextual())invalid();
            quote=json.readValue(row.snapshotJson(),QuoteModels.Snapshot.class);
        }catch(Exception error){throw new IllegalStateException("Invalid persisted quote snapshot",error);}
        if(quote==null || !row.owner().equals(quote.owner()) || !row.warehouseId().equals(quote.warehouseId())
            || !row.createdAt().equals(quote.createdAt()) || !row.expiresAt().equals(quote.expiresAt())
            || !row.createdAt().isBefore(row.expiresAt()) || !validId(quote.addressId()) || quote.address()==null
            || !quote.addressId().equals(quote.address().id()) || !money(quote.grossMinor(),false) || !money(quote.discountMinor(),true)
            || !money(quote.shippingMinor(),true) || !money(quote.payableMinor(),false))invalid();
        var ids=new HashSet<String>();
        for(var line:quote.items()){
            if(line==null || line.catalog()==null || line.amounts()==null)invalid();
            var sku=line.catalog();var amounts=line.amounts();
            if(!validId(sku.skuId()) || !validId(sku.productId()) || !validId(sku.categoryId()) || !validId(sku.brandId())
                || !sku.skuId().equals(amounts.skuId()) || !ids.add(sku.skuId()) || sku.productName()==null || amounts.productName()==null
                || amounts.quantity()<1 || amounts.quantity()>99 || !money(sku.priceMinor(),false) || !money(amounts.unitPriceMinor(),false)
                || !money(amounts.grossMinor(),false) || !money(amounts.discountMinor(),true) || !money(amounts.payableMinor(),true))invalid();
        }
        return quote;
    }
    private static void validSpecs(JsonNode specs){for(var spec:specs)if(!spec.isObject() || !spec.path("name").isTextual() || !spec.path("value").isTextual())invalid();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Cannot serialize order snapshot",error);}}
    private static boolean money(String value,boolean zero){return value!=null && value.matches(zero?"0|[1-9][0-9]{0,17}":"[1-9][0-9]{0,17}");}
    private static boolean validId(String value){return value!=null && value.matches("[1-9][0-9]{0,18}");}
    private static void invalid(){throw new IllegalStateException("Invalid persisted quote snapshot");}
    private static void unexpired(Instant expiresAt,Instant now){if(!now.isBefore(expiresAt))throw new ApiException(409,"QUOTE_EXPIRED","报价已过期，请重新确认");}
    private static ApiException conflict(){return new ApiException(409,"STATE_CONFLICT","报价已使用或状态冲突");}
    private static ApiException changed(){return new ApiException(409,"QUOTE_CHANGED","商品、价格或仓库已变化，请重新确认报价");}
    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Order creation requires an active, writable, explicitly READ_COMMITTED transaction");
    }
}
