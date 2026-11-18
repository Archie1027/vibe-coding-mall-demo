package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import java.math.BigInteger;
import java.util.*;

/** Pure server-side pricing. Callers must supply current authorised catalog/coupon values. */
public final class CheckoutPricing {
    private static final long MAX_MONEY=999_999_999_999_999_999L;
    private CheckoutPricing() {}
    public record Item(String skuId,long unitPriceMinor,int quantity) {}
    public record Coupon(long thresholdMinor,long discountMinor) {}
    public record Line(String skuId,long unitPriceMinor,int quantity,long grossMinor,long discountMinor,long payableMinor) {}
    public record Result(List<Line> items,long grossMinor,long discountMinor,long shippingMinor,long payableMinor) {
        public Result { items=List.copyOf(items); }
    }

    public static Result calculate(List<Item> items,Coupon coupon) {
        if(items==null || items.isEmpty() || items.size()>100)TradeValidation.bad("报价商品必须为1至100行");
        var merged=new LinkedHashMap<String,Item>();
        for(var item:items){
            if(item==null)TradeValidation.bad("报价商品不能为空");
            TradeValidation.id(item.skuId());TradeValidation.quantity(item.quantity());
            positiveMoney(item.unitPriceMinor());
            var previous=merged.get(item.skuId());
            if(previous!=null){
                if(previous.unitPriceMinor()!=item.unitPriceMinor())throw amountInvalid("同一SKU的当前单价不一致");
                int quantity=previous.quantity()+item.quantity();TradeValidation.quantity(quantity);
                merged.put(item.skuId(),new Item(item.skuId(),item.unitPriceMinor(),quantity));
            }else merged.put(item.skuId(),item);
        }
        var rows=new ArrayList<>(merged.values());
        var grossLines=new long[rows.size()];long gross=0;
        try{
            for(int i=0;i<rows.size();i++){
                grossLines[i]=Math.multiplyExact(rows.get(i).unitPriceMinor(),rows.get(i).quantity());
                gross=Math.addExact(gross,grossLines[i]);positiveMoney(gross);
            }
        }catch(ArithmeticException overflow){throw amountInvalid("报价金额超过支持范围");}
        long discount=0;
        if(coupon!=null){
            positiveMoney(coupon.thresholdMinor());positiveMoney(coupon.discountMinor());
            if(coupon.discountMinor()>coupon.thresholdMinor())throw amountInvalid("优惠券面额不能大于门槛");
            if(gross<coupon.thresholdMinor())throw new ApiException(409,"COUPON_UNAVAILABLE","商品金额未达到优惠券门槛");
            discount=coupon.discountMinor();
            if(discount>=gross)throw amountInvalid("优惠后应付金额必须大于0，请取消选券");
        }
        var allocated=new long[rows.size()];
        if(discount>0){
            var denominator=BigInteger.valueOf(gross);var remainders=new ArrayList<BigInteger>();
            var order=new ArrayList<Integer>();long floorTotal=0;
            for(int i=0;i<rows.size();i++){
                var division=BigInteger.valueOf(grossLines[i]).multiply(BigInteger.valueOf(discount)).divideAndRemainder(denominator);
                allocated[i]=division[0].longValueExact();floorTotal=Math.addExact(floorTotal,allocated[i]);
                remainders.add(division[1]);order.add(i);
            }
            order.sort(Comparator.comparing((Integer i)->remainders.get(i)).reversed().thenComparing(i->new BigInteger(rows.get(i).skuId())));
            long remaining=discount-floorTotal;
            for(int i=0;i<remaining;i++)allocated[order.get(i)]++;
        }
        var result=new ArrayList<Line>();
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);result.add(new Line(row.skuId(),row.unitPriceMinor(),row.quantity(),grossLines[i],allocated[i],grossLines[i]-allocated[i]));
        }
        return new Result(result,gross,discount,0,gross-discount);
    }
    private static void positiveMoney(long amount){if(amount<=0 || amount>MAX_MONEY)throw amountInvalid("金额必须为支持范围内的正整数分");}
    private static ApiException amountInvalid(String message){return new ApiException(422,"AMOUNT_INVALID",message);}
}
