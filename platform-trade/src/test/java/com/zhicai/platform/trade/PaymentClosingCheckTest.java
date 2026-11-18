package com.zhicai.platform.trade;

import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentClosingCheckTest {
    final PaymentClosingMapper mapper=mock(PaymentClosingMapper.class);
    final PaymentClosingCheck service=new PaymentClosingCheck(mapper);
    @BeforeEach void transaction(){QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,false);when(mapper.lockOrder("5")).thenReturn("5");}
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    @Test void absentAndEntirelyClosedAttemptsAreProvenUnderOrderLock(){
        for(var facts:List.of(new PaymentClosingMapper.Facts(0,0,0,0),new PaymentClosingMapper.Facts(3,0,0,3))){
            when(mapper.facts("5")).thenReturn(facts);assertThat(service.lockedState("5")).isEqualTo(OrderClosingService.PaymentState.NO_ACTIVE_PAYMENT);
        }
        var order=inOrder(mapper);order.verify(mapper).lockOrder("5");order.verify(mapper).facts("5");order.verify(mapper).lockOrder("5");order.verify(mapper).facts("5");
    }
    @Test void unresolvedAttemptRemainsActiveAndSuccessCannotBeTreatedAsFailure(){
        when(mapper.facts("5")).thenReturn(new PaymentClosingMapper.Facts(4,1,0,3));assertThat(service.lockedState("5")).isEqualTo(OrderClosingService.PaymentState.ACTIVE_PAYMENT);
        when(mapper.facts("5")).thenReturn(new PaymentClosingMapper.Facts(4,0,1,3));assertThat(service.lockedState("5")).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);
    }
    @Test void missingOrCorruptFactsNeverProveNoActivePayment(){
        when(mapper.facts("5")).thenReturn(null);assertThat(service.lockedState("5")).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);
        for(var facts:List.of(new PaymentClosingMapper.Facts(-1,0,0,0),new PaymentClosingMapper.Facts(2,2,0,0),
            new PaymentClosingMapper.Facts(2,0,0,1),new PaymentClosingMapper.Facts(1,0,-1,2),
            new PaymentClosingMapper.Facts(Long.MAX_VALUE,1,Long.MAX_VALUE,0))){
            when(mapper.facts("5")).thenReturn(facts);assertThat(service.lockedState("5")).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);
        }
        clearInvocations(mapper);when(mapper.lockOrder("5")).thenReturn(null);assertThat(service.lockedState("5")).isEqualTo(OrderClosingService.PaymentState.UNKNOWN);verify(mapper,never()).facts(any());
    }
    @Test void dependenciesPropagateAndInvalidContextCannotReadFacts(){
        for(String id:List.of("0","05","5 OR 1=1"))assertThatThrownBy(()->service.lockedState(id)).isInstanceOf(com.zhicai.platform.common.ApiException.class);
        QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_REPEATABLE_READ,false);assertThatThrownBy(()->service.lockedState("5")).isInstanceOf(IllegalTransactionStateException.class);
        QuoteServiceTest.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED,true);assertThatThrownBy(()->service.lockedState("5")).isInstanceOf(IllegalTransactionStateException.class);
        TransactionSynchronizationManager.clear();assertThatThrownBy(()->service.lockedState("5")).isInstanceOf(IllegalTransactionStateException.class);verifyNoInteractions(mapper);
        transaction();var failure=new org.springframework.dao.DataAccessResourceFailureException("missing payment table or lost connection");when(mapper.facts("5")).thenThrow(failure);
        assertThatThrownBy(()->service.lockedState("5")).isSameAs(failure);
    }
}
