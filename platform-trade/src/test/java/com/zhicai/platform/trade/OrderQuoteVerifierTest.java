package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CatalogModels.Spec;
import com.zhicai.platform.catalog.CheckoutCatalogService.CheckoutSku;
import com.zhicai.platform.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderQuoteVerifierTest {
    private static final Instant NOW=Instant.parse("2026-10-26T00:00:00Z");
    private static final CheckoutSku SKU=new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",
        List.of(new Spec("颜色","蓝")),"100",1,2,3,4);
    private static QuoteModels.Snapshot snapshot(CheckoutSku sku,QuoteModels.Line amounts,String warehouse,Instant expires,String coupon){
        var address=new TradeModels.Address("2","收件人","10000000008","江苏","南京","鼓楼","地址",true,0);
        return new QuoteModels.Snapshot(1,"1","2",address,warehouse,
            List.of(new QuoteModels.SnapshotLine(sku,amounts)),"200","0","0","200",coupon,NOW.minusSeconds(60),expires);
    }
    private static QuoteModels.Line line(){return new QuoteModels.Line("3","商品",SKU.specs(),2,"100","200","0","200");}
    private static QuoteModels.Snapshot valid(){return snapshot(SKU,line(),"4",NOW.plusSeconds(60),null);}
    private static void rejects(Runnable call,int status,String code){
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.status()).isEqualTo(status);assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test void unchangedQuoteRepricesExactly(){
        var actual=OrderQuoteVerifier.verify(valid(),Map.of("3",SKU),"4",NOW);
        assertThat(actual.grossMinor()).isEqualTo(200);
        assertThat(actual.payableMinor()).isEqualTo(200);
        assertThat(actual.items()).singleElement().satisfies(item->assertThat(item.quantity()).isEqualTo(2));
    }
    @Test void changedCatalogOrWarehouseRequiresNewConfirmation(){
        var changedVersion=new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",SKU.specs(),"100",2,2,3,4);
        var changedPrice=new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",SKU.specs(),"101",1,2,3,4);
        rejects(()->OrderQuoteVerifier.verify(valid(),Map.of("3",changedVersion),"4",NOW),409,"QUOTE_CHANGED");
        rejects(()->OrderQuoteVerifier.verify(valid(),Map.of("3",changedPrice),"4",NOW),409,"QUOTE_CHANGED");
        rejects(()->OrderQuoteVerifier.verify(valid(),Map.of(),"4",NOW),409,"QUOTE_CHANGED");
        rejects(()->OrderQuoteVerifier.verify(valid(),Map.of("3",SKU),"9",NOW),409,"QUOTE_CHANGED");
    }
    @Test void expiryIsExclusiveAndCouponCannotBeSilentlyIgnored(){
        var expired=snapshot(SKU,line(),"4",NOW,null);
        rejects(()->OrderQuoteVerifier.verify(expired,Map.of("3",SKU),"4",NOW),409,"QUOTE_EXPIRED");
        var coupon=snapshot(SKU,line(),"4",NOW.plusSeconds(60),"7");
        rejects(()->OrderQuoteVerifier.verify(coupon,Map.of("3",SKU),"4",NOW),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void alteredSavedAmountsCannotBeUsedForAnOrder(){
        var altered=new QuoteModels.Line("3","商品",SKU.specs(),2,"100","200","0","199");
        rejects(()->OrderQuoteVerifier.verify(snapshot(SKU,altered,"4",NOW.plusSeconds(60),null),Map.of("3",SKU),"4",NOW),409,"QUOTE_CHANGED");
    }
    @Test void quoteIsValidImmediatelyBeforeExpiryButNotAfterIt(){
        var quote=snapshot(SKU,line(),"4",NOW,null);
        assertThat(OrderQuoteVerifier.verify(quote,Map.of("3",SKU),"4",NOW.minusNanos(1)).payableMinor()).isEqualTo(200);
        rejects(()->OrderQuoteVerifier.verify(quote,Map.of("3",SKU),"4",NOW.plusNanos(1)),409,"QUOTE_EXPIRED");
    }
    @Test void productCategoryAndBrandVersionsAreRechecked(){
        var changedVersions=List.of(
            new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",SKU.specs(),"100",1,3,3,4),
            new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",SKU.specs(),"100",1,2,4,4),
            new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",SKU.specs(),"100",1,2,3,5));
        for(var changedVersion:changedVersions)
            rejects(()->OrderQuoteVerifier.verify(valid(),Map.of("3",changedVersion),"4",NOW),409,"QUOTE_CHANGED");
    }
    @Test void changedCatalogContentRequiresNewConfirmationEvenWithUnchangedVersions(){
        var changedContent=List.of(
            new CheckoutSku("3","8","新商品","说明",false,"5","分类","6","品牌","ABC",SKU.specs(),"100",1,2,3,4),
            new CheckoutSku("3","8","商品","说明",false,"5","新分类","6","品牌","ABC",SKU.specs(),"100",1,2,3,4),
            new CheckoutSku("3","8","商品","说明",false,"5","分类","6","新品牌","ABC",SKU.specs(),"100",1,2,3,4),
            new CheckoutSku("3","8","商品","说明",false,"5","分类","6","品牌","ABC",List.of(new Spec("颜色","红")),"100",1,2,3,4));
        for(var changed:changedContent)
            rejects(()->OrderQuoteVerifier.verify(valid(),Map.of("3",changed),"4",NOW),409,"QUOTE_CHANGED");
    }
    @Test void multipleLinesKeepQuoteOrderAndRepriceEveryLine(){
        var second=new CheckoutSku("9","8","商品","说明",false,"5","分类","6","品牌","DEF",SKU.specs(),"150",1,2,3,4);
        var secondLine=new QuoteModels.Line("9","商品",SKU.specs(),3,"150","450","0","450");
        var base=valid();
        var quote=new QuoteModels.Snapshot(1,base.owner(),base.addressId(),base.address(),"4",
            List.of(new QuoteModels.SnapshotLine(SKU,line()),new QuoteModels.SnapshotLine(second,secondLine)),
            "650","0","0","650",null,base.createdAt(),base.expiresAt());
        var current=new java.util.LinkedHashMap<String,CheckoutSku>();current.put("9",second);current.put("3",SKU);
        var actual=OrderQuoteVerifier.verify(quote,current,"4",NOW);
        assertThat(actual.items()).extracting(CheckoutPricing.Line::skuId).containsExactly("3","9");
        assertThat(actual.items()).extracting(CheckoutPricing.Line::grossMinor).containsExactly(200L,450L);
        assertThat(actual.payableMinor()).isEqualTo(650);
    }
    @Test void everySavedTotalIsRechecked(){
        var base=valid();
        for(var totals:List.of(List.of("201","0","0","200"),List.of("200","1","0","200"),
            List.of("200","0","1","200"),List.of("200","0","0","201"))){
            var changed=new QuoteModels.Snapshot(1,base.owner(),base.addressId(),base.address(),base.warehouseId(),base.items(),
                totals.get(0),totals.get(1),totals.get(2),totals.get(3),null,base.createdAt(),base.expiresAt());
            rejects(()->OrderQuoteVerifier.verify(changed,Map.of("3",SKU),"4",NOW),409,"QUOTE_CHANGED");
        }
    }
    @Test void everySavedLineAmountAndDisplaySnapshotIsRechecked(){
        var changedLines=List.of(
            new QuoteModels.Line("3","其他商品",SKU.specs(),2,"100","200","0","200"),
            new QuoteModels.Line("3","商品",List.of(new Spec("颜色","红")),2,"100","200","0","200"),
            new QuoteModels.Line("3","商品",SKU.specs(),2,"101","200","0","200"),
            new QuoteModels.Line("3","商品",SKU.specs(),2,"100","201","0","200"),
            new QuoteModels.Line("3","商品",SKU.specs(),2,"100","200","1","200"));
        for(var changed:changedLines)
            rejects(()->OrderQuoteVerifier.verify(snapshot(SKU,changed,"4",NOW.plusSeconds(60),null),Map.of("3",SKU),"4",NOW),409,"QUOTE_CHANGED");
    }
}
