package com.zhicai.platform.trade;

import java.util.Objects;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local evidence only. All payment creation, state updates and callbacks must lock order first.
 * SUCCEEDED on an unpaid/closing order is inconsistent evidence; never interpret it as closed. */
public class PaymentClosingCheck implements OrderClosingService.PaymentCheck {
    private final PaymentClosingMapper mapper;
    public PaymentClosingCheck(PaymentClosingMapper mapper){this.mapper=Objects.requireNonNull(mapper);}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public OrderClosingService.PaymentState lockedState(String id){
        TradeValidation.id(id);
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Payment closing evidence requires writable explicit READ_COMMITTED");
        if(!id.equals(mapper.lockOrder(id)))return OrderClosingService.PaymentState.UNKNOWN;
        var facts=mapper.facts(id);
        if(facts==null || facts.total()<0 || facts.active()<0 || facts.succeeded()<0 || facts.terminal()<0
            || facts.active()>1 || facts.active()>facts.total() || facts.succeeded()>facts.total()-facts.active()
            || facts.terminal()!=facts.total()-facts.active()-facts.succeeded() || facts.succeeded()>0)
            return OrderClosingService.PaymentState.UNKNOWN;
        return facts.active()==1?OrderClosingService.PaymentState.ACTIVE_PAYMENT:OrderClosingService.PaymentState.NO_ACTIVE_PAYMENT;
    }
}
