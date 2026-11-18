package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CheckoutCatalogService.CheckoutSku;
import com.zhicai.platform.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Compares a locked quote snapshot with fresh catalog/pricing evidence before order writes. */
public final class OrderQuoteVerifier {
    private OrderQuoteVerifier() {}

    public static CheckoutPricing.Result verify(QuoteModels.Snapshot quote, Map<String,CheckoutSku> current,
                                                 String warehouseId, Instant now) {
        Objects.requireNonNull(quote);Objects.requireNonNull(current);Objects.requireNonNull(now);
        if(quote.formatVersion()!=1 || quote.items()==null || quote.items().isEmpty() || quote.items().size()>100
            || quote.expiresAt()==null || quote.address()==null || quote.warehouseId()==null)
            throw new IllegalStateException("Invalid persisted quote snapshot");
        if(!now.isBefore(quote.expiresAt()))
            throw new ApiException(409,"QUOTE_EXPIRED","报价已过期，请重新确认");
        if(quote.couponId()!=null)
            throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","优惠券服务尚未接入，请取消选券后重试");
        if(!quote.warehouseId().equals(warehouseId))throw changed();
        var seen=new HashSet<String>();var items=new ArrayList<CheckoutPricing.Item>();
        for(var line:quote.items()){
            if(line==null || line.catalog()==null || line.amounts()==null
                || !line.catalog().skuId().equals(line.amounts().skuId())
                || !seen.add(line.catalog().skuId()))throw new IllegalStateException("Invalid quote lines");
            var sku=current.get(line.catalog().skuId());
            if(sku==null || !sku.equals(line.catalog()))throw changed();
            items.add(new CheckoutPricing.Item(sku.skuId(),parsePrice(sku.priceMinor()),line.amounts().quantity()));
        }
        var result=CheckoutPricing.calculate(items,null);
        for(int i=0;i<result.items().size();i++){
            var expected=result.items().get(i);var saved=quote.items().get(i).amounts();
            if(!saved.productName().equals(quote.items().get(i).catalog().productName())
                || !saved.specs().equals(quote.items().get(i).catalog().specs())
                || !saved.unitPriceMinor().equals(Long.toString(expected.unitPriceMinor()))
                || !saved.grossMinor().equals(Long.toString(expected.grossMinor()))
                || !saved.discountMinor().equals(Long.toString(expected.discountMinor()))
                || !saved.payableMinor().equals(Long.toString(expected.payableMinor())))throw changed();
        }
        if(!quote.grossMinor().equals(Long.toString(result.grossMinor()))
            || !quote.discountMinor().equals(Long.toString(result.discountMinor()))
            || !quote.shippingMinor().equals(Long.toString(result.shippingMinor()))
            || !quote.payableMinor().equals(Long.toString(result.payableMinor())))throw changed();
        return result;
    }

    private static long parsePrice(String value){
        if(value==null || !value.matches("[1-9][0-9]{0,17}"))throw changed();
        try{return Long.parseLong(value);}catch(NumberFormatException error){throw changed();}
    }
    private static ApiException changed(){return new ApiException(409,"QUOTE_CHANGED","商品、价格或仓库已变化，请重新确认报价");}
}
