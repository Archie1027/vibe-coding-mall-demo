package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zhicai.platform.trade.CheckoutPricing.*;

class CheckoutPricingTest {
    private Item item(String id,long price,int quantity){return new Item(id,price,quantity);}
    private void error(Runnable call,int status,String code){assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);});}
    @Test void noCouponUsesExactCentsAndFreeShipping(){
        var result=calculate(List.of(item("1",289900,2),item("2",899900,1)),null);
        assertThat(result.grossMinor()).isEqualTo(1479700);assertThat(result.payableMinor()).isEqualTo(1479700);
        assertThat(result.discountMinor()).isZero();assertThat(result.shippingMinor()).isZero();
        assertThat(result.items().getFirst().grossMinor()).isEqualTo(579800);
    }
    @Test void duplicateSkuRowsMergeWithoutLosingQuantities(){
        var result=calculate(List.of(item("1",100,2),item("1",100,3)),null);
        assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().quantity()).isEqualTo(5);assertThat(result.grossMinor()).isEqualTo(500);
    }
    @Test void duplicateSkuCannotHideQuantityLimitOrConflictingCurrentPrices(){
        error(()->calculate(List.of(item("1",100,50),item("1",100,50)),null),400,"VALIDATION_ERROR");
        error(()->calculate(List.of(item("1",100,1),item("1",101,1)),null),422,"AMOUNT_INVALID");
    }
    @Test void largestRemainderUsesFractionBeforeNumericSkuId(){
        var result=calculate(List.of(item("10",1,1),item("2",2,1)),new Coupon(2,1));
        assertThat(result.items().get(0).discountMinor()).isZero();assertThat(result.items().get(1).discountMinor()).isEqualTo(1);
        assertThat(result.discountMinor()).isEqualTo(1);assertThat(result.payableMinor()).isEqualTo(2);
    }
    @Test void equalRemaindersBreakTiesByNumericRatherThanLexicalId(){
        var result=calculate(List.of(item("10",100,1),item("2",100,1)),new Coupon(1,1));
        assertThat(result.items().get(0).discountMinor()).isZero();assertThat(result.items().get(1).discountMinor()).isEqualTo(1);
    }
    @Test void allocationIsStableAcrossInputOrder(){
        var a=calculate(List.of(item("10",101,1),item("2",101,1),item("3",101,1)),new Coupon(100,100));
        var b=calculate(List.of(item("3",101,1),item("2",101,1),item("10",101,1)),new Coupon(100,100));
        for(var row:a.items())assertThat(b.items().stream().filter(x->x.skuId().equals(row.skuId())).findFirst().orElseThrow().discountMinor()).isEqualTo(row.discountMinor());
        assertThat(a.items().stream().mapToLong(Line::discountMinor).sum()).isEqualTo(100);
    }
    @Test void bigIntegerIntermediateAvoidsOverflowAndDoesNotRoundMoney(){
        long price=400_000_000_000_000_000L,discount=100_000_000_000_000_001L;
        assertThat(BigInteger.valueOf(price).multiply(BigInteger.valueOf(discount))).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
        var result=calculate(List.of(item("10",price,1),item("2",price,1)),new Coupon(400_000_000_000_000_000L,discount));
        assertThat(result.items().getFirst().discountMinor()).isEqualTo(50_000_000_000_000_000L);
        assertThat(result.items().get(1).discountMinor()).isEqualTo(50_000_000_000_000_001L);
        assertThat(result.payableMinor()).isEqualTo(699_999_999_999_999_999L);
    }
    @Test void maximumMoneyIsAcceptedButOverflowAndContractExcessAreRejected(){
        assertThat(calculate(List.of(item("1",999_999_999_999_999_999L,1)),null).payableMinor()).isEqualTo(999_999_999_999_999_999L);
        error(()->calculate(List.of(item("1",999_999_999_999_999_999L,99)),null),422,"AMOUNT_INVALID");
        error(()->calculate(List.of(item("1",600_000_000_000_000_000L,1),item("2",600_000_000_000_000_000L,1)),null),422,"AMOUNT_INVALID");
        error(()->calculate(List.of(item("1",Long.MAX_VALUE,1)),null),422,"AMOUNT_INVALID");
    }
    @Test void zeroPayableIsRejectedAndThresholdEqualToDiscountRemainsValid(){
        error(()->calculate(List.of(item("1",100,1)),new Coupon(100,100)),422,"AMOUNT_INVALID");
        assertThat(calculate(List.of(item("1",101,1)),new Coupon(100,100)).payableMinor()).isEqualTo(1);
    }
    @Test void couponThresholdAndAmountRulesAreEnforced(){
        error(()->calculate(List.of(item("1",100,1)),new Coupon(101,1)),409,"COUPON_UNAVAILABLE");
        for(var coupon:List.of(new Coupon(100,0),new Coupon(100,-1),new Coupon(99,100)))error(()->calculate(List.of(item("1",200,1)),coupon),422,"AMOUNT_INVALID");
    }
    @Test void invalidItemsAreRejectedBeforeCalculation(){
        error(()->calculate(null,null),400,"VALIDATION_ERROR");error(()->calculate(List.of(),null),400,"VALIDATION_ERROR");
        error(()->calculate(Collections.singletonList(null),null),400,"VALIDATION_ERROR");
        for(String id:Arrays.asList(null,"0","01","1 OR 1=1"))error(()->calculate(List.of(item(id,100,1)),null),400,"VALIDATION_ERROR");
        for(int quantity:new int[]{0,-1,100})error(()->calculate(List.of(item("1",100,quantity)),null),400,"VALIDATION_ERROR");
        for(long price:new long[]{0,-1})error(()->calculate(List.of(item("1",price,1)),null),422,"AMOUNT_INVALID");
        error(()->calculate(Collections.nCopies(101,item("1",100,1)),null),400,"VALIDATION_ERROR");
    }
    @Test void callerInputsAndResultsStayImmutable(){
        var input=new ArrayList<>(List.of(item("2",100,1),item("1",100,1)));var original=List.copyOf(input);
        var result=calculate(input,new Coupon(1,1));assertThat(input).isEqualTo(original);
        assertThatThrownBy(()->result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        input.clear();assertThat(result.items()).hasSize(2);
    }
    @Test void allocationInvariantsHoldAcrossManyDeterministicBaskets(){
        var random=new Random(37);
        for(int n=0;n<200;n++){
            var items=new ArrayList<Item>();long gross=0;
            for(int i=1;i<=8;i++){var row=item(Integer.toString(i),1+random.nextInt(10000),1+random.nextInt(99));items.add(row);gross+=row.unitPriceMinor()*row.quantity();}
            long discount=1+random.nextLong(gross-1);var result=calculate(items,new Coupon(discount,discount));
            assertThat(result.items().stream().mapToLong(Line::discountMinor).sum()).isEqualTo(discount);
            assertThat(result.items().stream().mapToLong(Line::payableMinor).sum()).isEqualTo(gross-discount);
            for(var row:result.items()){assertThat(row.discountMinor()).isBetween(0L,row.grossMinor());assertThat(row.payableMinor()).isEqualTo(row.grossMinor()-row.discountMinor());}
        }
    }
}
