package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryReservationService;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderClosingServiceTest {
    final OrderClosingMapper mapper=mock(OrderClosingMapper.class);
    final InventoryReservationService inventory=mock(InventoryReservationService.class);
    final OrderClosingService.PaymentCheck payments=mock(OrderClosingService.PaymentCheck.class);
    final Clock clock=mock(Clock.class);
    final Instant now=Instant.parse("2026-10-27T01:02:03.123456789Z");
    final Instant millis=Instant.parse("2026-10-27T01:02:03.123Z");
    final OrderClosingService service=new OrderClosingService(mapper,inventory,payments,clock);
    OrderClosingMapper.Locked row(String state,Instant expiry){return new OrderClosingMapper.Locked("5","1","4",state,null,expiry,null,7);}
    OrderClosingMapper.Item item(){return new OrderClosingMapper.Item("11","5","3",2,0);}
    @BeforeEach void setup(){
        QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,false);
        when(clock.instant()).thenReturn(now);
        when(mapper.lockOwned("1","5")).thenReturn(row("UNPAID",now.plusSeconds(30)));
        when(mapper.lockAny("5")).thenReturn(row("UNPAID",millis));
        when(payments.lockedState("5")).thenReturn(OrderClosingService.PaymentState.NO_ACTIVE_PAYMENT);
        when(mapper.items("5")).thenReturn(List.of(item()));
        when(mapper.markCancelled(anyString(),anyString(),anyLong(),any(),anyBoolean())).thenReturn(1);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    OrderClosingService.Result cancel(){return service.cancel("1","5","不需要了","closing:1");}
    Optional<OrderClosingService.Result> expire(){return service.expire("5","closing:1");}
    void error(Runnable action,int status,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);});}
    void noWrites(){verifyNoInteractions(inventory);verify(mapper,never()).markCancelled(any(),any(),anyLong(),any(),anyBoolean());}
    @Test void remainsInternalMandatoryService()throws Exception{
        assertThat(OrderClosingService.class.getAnnotation(org.springframework.stereotype.Service.class)).isNull();
        assertThat(OrderClosingService.class.getMethod("cancel",String.class,String.class,String.class,String.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(OrderClosingService.class.getMethod("expire",String.class,String.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThatThrownBy(()->new OrderClosingService(mapper,inventory,null,clock)).isInstanceOf(NullPointerException.class);
    }
    @Test void invalidParametersFailBeforeIo(){
        for(var id:Arrays.asList(null,"","0","01","1 OR 1=1","1".repeat(20))){
            error(()->service.cancel(id,"5","reason","trace"),400,"VALIDATION_ERROR");
            error(()->service.cancel("1",id,"reason","trace"),400,"VALIDATION_ERROR");
            error(()->service.expire(id,"trace"),400,"VALIDATION_ERROR");
        }
        for(var reason:Arrays.asList(null,"","😀".repeat(501)))error(()->service.cancel("1","5",reason,"trace"),400,"VALIDATION_ERROR");
        for(var trace:Arrays.asList(null,"","has space","x".repeat(65))){error(()->service.cancel("1","5","r",trace),400,"VALIDATION_ERROR");error(()->service.expire("5",trace),400,"VALIDATION_ERROR");}
        verifyNoInteractions(mapper,inventory,payments,clock);
    }
    @Test void requiresWritableExplicitRcBeforeMapper(){
        for(Integer level:Arrays.asList(null,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE,TransactionDefinition.ISOLATION_READ_UNCOMMITTED)){
            QuoteServiceTest.transaction(true,level,false);
            assertThatThrownBy(this::cancel).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(this::expire).isInstanceOf(IllegalTransactionStateException.class);
        }
        QuoteServiceTest.transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED,false);
        assertThatThrownBy(this::cancel).isInstanceOf(IllegalTransactionStateException.class);assertThatThrownBy(this::expire).isInstanceOf(IllegalTransactionStateException.class);
        QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,true);
        assertThatThrownBy(this::cancel).isInstanceOf(IllegalTransactionStateException.class);assertThatThrownBy(this::expire).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(mapper,inventory,payments,clock);
    }
    @Test void missingOrOtherOwnerIsNotFound(){
        when(mapper.lockOwned("1","5")).thenReturn(null);error(this::cancel,404,"NOT_FOUND");
        when(mapper.lockOwned("1","5")).thenReturn(new OrderClosingMapper.Locked("5","2","4","UNPAID",null,now,null,7));error(this::cancel,404,"NOT_FOUND");
        when(mapper.lockAny("5")).thenReturn(null);error(this::expire,404,"NOT_FOUND");
        noWrites();verifyNoInteractions(payments);verify(mapper,never()).items(any());
    }
    @Test void cancelNonUnpaidAndDuplicateRejectWithoutRelease(){
        for(var state:List.of("CLOSING","CANCELLED","PAID","SHIPPED","COMPLETED")){
            when(mapper.lockOwned("1","5")).thenReturn(row(state,now));error(this::cancel,409,"STATE_CONFLICT");
        }
        noWrites();verifyNoInteractions(payments);verify(mapper,never()).items(any());
    }
    @Test void expireNonUnpaidAndNotExpiredAreEmpty(){
        for(var state:List.of("CLOSING","CANCELLED","PAID","SHIPPED","COMPLETED")){when(mapper.lockAny("5")).thenReturn(row(state,millis));assertThat(expire()).isEmpty();}
        when(mapper.lockAny("5")).thenReturn(row("UNPAID",now.plusNanos(1)));assertThat(expire()).isEmpty();
        noWrites();verifyNoInteractions(payments);verify(mapper,never()).items(any());
    }
    @Test void expireReadsTimeAfterOrderLockAndAcceptsExactBoundary(){
        when(clock.instant()).thenReturn(millis.minusSeconds(20));
        when(mapper.lockAny("5")).thenAnswer(call->{when(clock.instant()).thenReturn(millis);return row("UNPAID",millis);});
        assertThat(expire()).contains(new OrderClosingService.Result("5","CANCELLED",8,millis,"ORDER_EXPIRED"));
        var calls=inOrder(mapper,clock,payments,inventory);calls.verify(mapper).lockAny("5");calls.verify(clock).instant();calls.verify(payments).lockedState("5");calls.verify(mapper).items("5");
        calls.verify(inventory).release(new InventoryReservationService.Command("5","4",List.of(new InventoryReservationService.Line("11","3",2)),null,"closing:1"));
        calls.verify(mapper).markCancelled("1","5",7,millis,true);
    }
    @Test void cancelKeepsUnicodeReasonAndExactInventoryReferences(){
        var lines=List.of(new OrderClosingMapper.Item("11","5","12",2,0),new OrderClosingMapper.Item("12","5","3",99,1));when(mapper.items("5")).thenReturn(lines);
        var reason="😀".repeat(500);var result=service.cancel("1","5",reason,"closing:1");
        assertThat(result).isEqualTo(new OrderClosingService.Result("5","CANCELLED",8,millis,reason));
        var calls=inOrder(mapper,clock,payments,inventory);calls.verify(mapper).lockOwned("1","5");calls.verify(clock).instant();calls.verify(payments).lockedState("5");calls.verify(mapper).items("5");
        calls.verify(inventory).release(new InventoryReservationService.Command("5","4",List.of(new InventoryReservationService.Line("11","12",2),new InventoryReservationService.Line("12","3",99)),"1","closing:1"));
        calls.verify(mapper).markCancelled("1","5",7,millis,false);calls.verifyNoMoreInteractions();
    }
    @Test void bothPathsFailClosedOnUnknownOrNullPayments(){
        for(var state:Arrays.asList(OrderClosingService.PaymentState.UNKNOWN,null)){
            when(payments.lockedState("5")).thenReturn(state);error(this::cancel,503,"DEPENDENCY_UNAVAILABLE");error(this::expire,503,"DEPENDENCY_UNAVAILABLE");
        }
        noWrites();verify(mapper,never()).items(any());
    }
    @Test void activePaymentStartsClosingWithoutReleasingInventory(){
        when(payments.lockedState("5")).thenReturn(OrderClosingService.PaymentState.ACTIVE_PAYMENT);
        when(mapper.markClosing(anyString(),anyString(),anyLong(),any(),anyBoolean())).thenReturn(1);
        assertThat(cancel()).isEqualTo(new OrderClosingService.Result("5","CLOSING",8,null,"不需要了"));
        assertThat(expire()).contains(new OrderClosingService.Result("5","CLOSING",8,null,"ORDER_EXPIRED"));
        verifyNoInteractions(inventory);verify(mapper,never()).items(any());
    }
    @Test void closingIsCompletedOnlyAfterAllAttemptsAreProvenClosed(){
        when(mapper.lockAny("5")).thenReturn(row("CLOSING",now.plusSeconds(30)));
        when(mapper.markClosingCancelled("1","5",7,millis)).thenReturn(1);
        var result=service.completeClosing("5","closing:1");
        assertThat(result).contains(new OrderClosingService.Result("5","CANCELLED",8,millis,"PAYMENT_CLOSED"));
        verify(mapper).markClosingCancelled("1","5",7,millis);verify(inventory).release(any());
    }
    @Test void closingStillActiveIsNoOpAndUnknownIsNotReleased(){
        when(mapper.lockAny("5")).thenReturn(row("CLOSING",millis));
        when(payments.lockedState("5")).thenReturn(OrderClosingService.PaymentState.ACTIVE_PAYMENT);
        assertThat(service.completeClosing("5","closing:1")).isEmpty();
        when(payments.lockedState("5")).thenReturn(OrderClosingService.PaymentState.UNKNOWN);
        error(()->service.completeClosing("5","closing:1"),503,"DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(inventory);verify(mapper,never()).items(any());verify(mapper,never()).markClosingCancelled(any(),any(),anyLong(),any());
    }
    @Test void closingConditionalConflictEscapesAfterReleaseSoCallerMustRollback(){
        when(mapper.lockAny("5")).thenReturn(row("CLOSING",millis));
        error(()->service.completeClosing("5","closing:1"),409,"STATE_CONFLICT");verify(inventory).release(any());
    }
    @Test void activeClosingConflictDoesNotReadItemsOrRelease(){
        when(payments.lockedState("5")).thenReturn(OrderClosingService.PaymentState.ACTIVE_PAYMENT);
        error(this::cancel,409,"STATE_CONFLICT");verifyNoInteractions(inventory);verify(mapper,never()).items(any());
    }
    @Test void closingCompletionKeepsTransactionAndStateGates()throws Exception{
        assertThat(OrderClosingService.class.getMethod("completeClosing",String.class,String.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        for(String id:List.of("0","05"))error(()->service.completeClosing(id,"trace"),400,"VALIDATION_ERROR");
        when(mapper.lockAny("5")).thenReturn(row("PAID",millis));assertThat(service.completeClosing("5","trace")).isEmpty();
        verifyNoInteractions(payments,inventory);
        TransactionSynchronizationManager.clear();assertThatThrownBy(()->service.completeClosing("5","trace")).isInstanceOf(IllegalTransactionStateException.class);
    }
    @Test void bothPathsRejectCouponsWithoutRelease(){
        var row=new OrderClosingMapper.Locked("5","1","4","UNPAID","8",millis,null,7);
        when(mapper.lockOwned("1","5")).thenReturn(row);when(mapper.lockAny("5")).thenReturn(row);
        error(this::cancel,503,"DEPENDENCY_UNAVAILABLE");error(this::expire,503,"DEPENDENCY_UNAVAILABLE");noWrites();verify(mapper,never()).items(any());
    }
    @Test void corruptLockedRowsRejectBeforeRelease(){
        var rows=List.of(
            new OrderClosingMapper.Locked("6","1","4","UNPAID",null,millis,null,7),
            new OrderClosingMapper.Locked("5","1","0","UNPAID",null,millis,null,7),
            new OrderClosingMapper.Locked("5","1","4",null,null,millis,null,7),
            new OrderClosingMapper.Locked("5","1","4","UNPAID",null,null,null,7),
            new OrderClosingMapper.Locked("5","1","4","UNPAID",null,millis,millis,7),
            new OrderClosingMapper.Locked("5","1","4","UNPAID",null,millis,null,-1),
            new OrderClosingMapper.Locked("5","1","4","UNPAID",null,millis,null,9007199254740991L),
            new OrderClosingMapper.Locked("5","1","4","UNPAID",null,millis,null,Long.MAX_VALUE));
        for(var row:rows){when(mapper.lockOwned("1","5")).thenReturn(row);when(mapper.lockAny("5")).thenReturn(row);assertThatThrownBy(this::cancel).isInstanceOf(IllegalStateException.class);assertThatThrownBy(this::expire).isInstanceOf(IllegalStateException.class);}
        when(mapper.lockAny("5")).thenReturn(new OrderClosingMapper.Locked("5","01","4","UNPAID",null,millis,null,7));assertThatThrownBy(this::expire).isInstanceOf(IllegalStateException.class);
        noWrites();
    }
    @Test void maximumSafeIncrementAndOneCodePointReasonAreAccepted(){
        when(mapper.lockOwned("1","5")).thenReturn(new OrderClosingMapper.Locked("5","1","4","UNPAID",null,millis,null,9007199254740990L));
        assertThat(service.cancel("1","5","😀","trace").version()).isEqualTo(9007199254740991L);
    }
    @Test void terminalRowsAtMaximumValidVersionRemainNoOpOrStateConflict(){
        var row=new OrderClosingMapper.Locked("5","1","4","CANCELLED",null,millis,millis,9007199254740991L);
        when(mapper.lockOwned("1","5")).thenReturn(row);when(mapper.lockAny("5")).thenReturn(row);
        error(this::cancel,409,"STATE_CONFLICT");assertThat(expire()).isEmpty();noWrites();verifyNoInteractions(payments);
    }
    @Test void invalidItemsNeverRelease(){
        var invalid=new ArrayList<List<OrderClosingMapper.Item>>();invalid.add(null);invalid.add(List.of());invalid.add(Arrays.asList((OrderClosingMapper.Item)null));
        invalid.add(List.of(new OrderClosingMapper.Item("0","5","3",2,0)));invalid.add(List.of(new OrderClosingMapper.Item("11","6","3",2,0)));invalid.add(List.of(new OrderClosingMapper.Item("11","5","0",2,0)));
        invalid.add(List.of(new OrderClosingMapper.Item("11","5","3",0,0)));invalid.add(List.of(new OrderClosingMapper.Item("11","5","3",100,0)));invalid.add(List.of(new OrderClosingMapper.Item("11","5","3",2,1)));
        invalid.add(List.of(item(),new OrderClosingMapper.Item("11","5","4",2,1)));invalid.add(List.of(item(),new OrderClosingMapper.Item("12","5","3",2,1)));invalid.add(List.of(item(),new OrderClosingMapper.Item("12","5","4",2,2)));
        invalid.add(IntStream.range(0,101).mapToObj(i->new OrderClosingMapper.Item(""+(100+i),"5",""+(200+i),1,i)).toList());
        for(var lines:invalid){when(mapper.items("5")).thenReturn(lines);assertThatThrownBy(this::cancel).isInstanceOf(IllegalStateException.class);assertThatThrownBy(this::expire).isInstanceOf(IllegalStateException.class);}noWrites();
    }
    @Test void oneHundredValidItemsArePassedAsACompleteBatch(){
        var lines=IntStream.range(0,100).mapToObj(i->new OrderClosingMapper.Item(""+(100+i),"5",""+(200+i),1,i)).toList();when(mapper.items("5")).thenReturn(lines);cancel();
        var capture=org.mockito.ArgumentCaptor.forClass(InventoryReservationService.Command.class);verify(inventory).release(capture.capture());assertThat(capture.getValue().lines()).hasSize(100);
    }
    @Test void paymentFailureIsPropagatedWithoutRetry(){var failure=new IllegalStateException("payment unavailable");when(payments.lockedState("5")).thenThrow(failure);assertThatThrownBy(this::cancel).isSameAs(failure);verify(payments).lockedState("5");noWrites();verify(mapper,never()).items(any());}
    @Test void releaseFailureIsPropagatedWithoutFinalUpdate(){var failure=new IllegalStateException("release unknown");when(inventory.release(any())).thenThrow(failure);assertThatThrownBy(this::cancel).isSameAs(failure);verify(inventory).release(any());verify(mapper,never()).markCancelled(any(),any(),anyLong(),any(),anyBoolean());}
    @Test void expireReleaseFailureIsPropagatedWithoutFinalUpdate(){var failure=new IllegalStateException("release unknown");when(inventory.release(any())).thenThrow(failure);assertThatThrownBy(this::expire).isSameAs(failure);verify(inventory).release(any());verify(mapper,never()).markCancelled(any(),any(),anyLong(),any(),anyBoolean());}
    @Test void failedConditionalMarksCannotReturnSuccess(){when(mapper.markCancelled(any(),any(),anyLong(),any(),anyBoolean())).thenReturn(0);error(this::cancel,409,"STATE_CONFLICT");error(this::expire,409,"STATE_CONFLICT");verify(inventory,times(2)).release(any());}
    @Test void conditionalMarkExceptionEscapesWithoutRetry(){var failure=new IllegalStateException("mark unknown");when(mapper.markCancelled(any(),any(),anyLong(),any(),anyBoolean())).thenThrow(failure);assertThatThrownBy(this::cancel).isSameAs(failure);verify(inventory).release(any());verify(mapper).markCancelled("1","5",7,millis,false);}
    @Test void candidatesValidateWholeCursorAndLimitBeforeQuery(){
        error(()->service.expiredCandidates(null,null,1),400,"VALIDATION_ERROR");
        for(int limit:new int[]{0,-1,101})error(()->service.expiredCandidates(now,null,limit),400,"VALIDATION_ERROR");
        for(var cursor:List.of(new OrderClosingService.Cursor(null,"5"),new OrderClosingService.Cursor(now,null),new OrderClosingService.Cursor(now,"01")))error(()->service.expiredCandidates(now,cursor,10),400,"VALIDATION_ERROR");
        verifyNoInteractions(mapper,inventory,payments,clock);
    }
    @Test void candidatesAreImmutableUnLockedAndDoNotRequireATransaction(){
        TransactionSynchronizationManager.clear();var rows=new ArrayList<>(List.of(new OrderClosingService.Candidate("5",millis)));when(mapper.expired(now,null,null,100)).thenReturn(rows);
        var result=service.expiredCandidates(now,null,100);assertThat(result).containsExactly(new OrderClosingService.Candidate("5",millis));rows.clear();assertThat(result).hasSize(1);assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        when(mapper.expired(now,millis,"5",1)).thenReturn(List.of());assertThat(service.expiredCandidates(now,new OrderClosingService.Cursor(millis,"5"),1)).isEmpty();
        verify(mapper).expired(now,null,null,100);verify(mapper).expired(now,millis,"5",1);verifyNoMoreInteractions(mapper);verifyNoInteractions(inventory,payments,clock);
    }
}
