package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryReservationService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal closing core; the caller owns the writable, explicitly READ_COMMITTED transaction.
 * Direct construction does not install a transaction proxy. Every failure must escape the outer
 * transaction so that inventory changes and the final order transition roll back together.
 * Result.reason is returned for future same-transaction auditing; this core does not persist it.
 */
public class OrderClosingService {
    public enum PaymentState { NO_ACTIVE_PAYMENT, ACTIVE_PAYMENT, UNKNOWN }
    @FunctionalInterface public interface PaymentCheck {
        /** Prove local payment state under the same locked order and writable RC transaction.
         * Never call an external payment channel here. No default implementation is provided. */
        PaymentState lockedState(String orderId);
    }
    public record Result(String id,String state,long version,Instant cancelledAt,String reason) {}
    public record Cursor(Instant expiresAt,String id) {}
    public record Candidate(String id,Instant expiresAt) {}
    private final OrderClosingMapper mapper;
    private final InventoryReservationService inventory;
    private final PaymentCheck payments;
    private final Clock clock;
    public OrderClosingService(OrderClosingMapper mapper,InventoryReservationService inventory,PaymentCheck payments,Clock clock){
        this.mapper=Objects.requireNonNull(mapper);this.inventory=Objects.requireNonNull(inventory);
        this.payments=Objects.requireNonNull(payments);this.clock=Objects.requireNonNull(clock);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Result cancel(String owner,String id,String reason,String traceId){
        validateCancel(owner,id,reason,traceId);
        requireTransaction();
        var row=mapper.lockOwned(owner,id);
        if(row==null || !owner.equals(row.owner()))throw notFound();
        validateLocked(row,id);
        if(!"UNPAID".equals(row.state()))throw conflict();
        var now=clock.instant();
        return close(row,reason,traceId,owner,now,false);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Result> expire(String id,String traceId){
        if(!validId(id) || !validTrace(traceId))bad();
        requireTransaction();
        var row=mapper.lockAny(id);
        if(row==null)throw notFound();
        validateLocked(row,id);
        if(!"UNPAID".equals(row.state()))return Optional.empty();
        // A lock wait may cross the expiry boundary; never sample time before acquiring this lock.
        var now=clock.instant();
        if(now.isBefore(row.expiresAt()))return Optional.empty();
        return Optional.of(close(row,"ORDER_EXPIRED",traceId,null,now,true));
    }
    /** Only a trusted channel-result workflow may call this after persisting its local evidence.
     * It never calls a channel and cannot turn an unresolved payment into a failure. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Result> completeClosing(String id,String traceId){
        if(!validId(id) || !validTrace(traceId))bad();
        requireTransaction();var row=mapper.lockAny(id);
        if(row==null)throw notFound();validateLocked(row,id);
        if(!"CLOSING".equals(row.state()))return Optional.empty();
        if(row.version()==9007199254740991L)invalid();
        var state=knownPaymentState(id);
        if(state==PaymentState.ACTIVE_PAYMENT)return Optional.empty();
        if(row.couponId()!=null)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","优惠券释放尚未接入");
        return Optional.of(releaseAndCancel(row,"PAYMENT_CLOSED",traceId,null,clock.instant(),false,true));
    }
    public static void validateCancel(String owner,String id,String reason,String traceId){
        if(!validId(owner) || !validId(id) || reason==null || reason.codePointCount(0,reason.length())<1
            || reason.codePointCount(0,reason.length())>500 || !validTrace(traceId))bad();
    }
    /** Unlocked keyset candidates only; callers must expire each ID under a fresh order lock. */
    public List<Candidate> expiredCandidates(Instant cutoff,Cursor after,int limit){
        if(cutoff==null || limit<1 || limit>100 || (after!=null && (after.expiresAt()==null || !validId(after.id()))))bad();
        return List.copyOf(mapper.expired(cutoff,after==null?null:after.expiresAt(),after==null?null:after.id(),limit));
    }
    private Result close(OrderClosingMapper.Locked row,String reason,String traceId,String actor,Instant now,boolean expiredOnly){
        if(row.version()==9007199254740991L)invalid();
        var state=knownPaymentState(row.id());
        if(row.couponId()!=null)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","优惠券释放尚未接入");
        if(state==PaymentState.ACTIVE_PAYMENT){
            if(mapper.markClosing(row.owner(),row.id(),row.version(),now.truncatedTo(ChronoUnit.MILLIS),expiredOnly)!=1)throw conflict();
            return new Result(row.id(),"CLOSING",row.version()+1,null,reason);
        }
        return releaseAndCancel(row,reason,traceId,actor,now,expiredOnly,false);
    }
    private PaymentState knownPaymentState(String id){
        var state=payments.lockedState(id);
        if(state==null || state==PaymentState.UNKNOWN)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","支付状态尚未证明可关闭");
        return state;
    }
    private Result releaseAndCancel(OrderClosingMapper.Locked row,String reason,String traceId,String actor,Instant now,boolean expiredOnly,boolean completing){
        var items=mapper.items(row.id());
        if(items==null || items.isEmpty() || items.size()>100)invalid();
        var ids=new HashSet<String>();var skus=new HashSet<String>();
        for(int i=0;i<items.size();i++){
            var item=items.get(i);
            if(item==null || !validId(item.id()) || !row.id().equals(item.orderId()) || !validId(item.skuId())
                || item.quantity()<1 || item.quantity()>99 || item.lineNo()!=i || !ids.add(item.id()) || !skus.add(item.skuId()))invalid();
        }
        var lines=items.stream().map(item->new InventoryReservationService.Line(item.id(),item.skuId(),item.quantity())).toList();
        // release proves original RESERVE references and owns warehouse -> numeric SKU -> reservation locks.
        inventory.release(new InventoryReservationService.Command(row.id(),row.warehouseId(),lines,actor,traceId));
        var cancelledAt=now.truncatedTo(ChronoUnit.MILLIS);
        int updated=completing?mapper.markClosingCancelled(row.owner(),row.id(),row.version(),cancelledAt):
            mapper.markCancelled(row.owner(),row.id(),row.version(),cancelledAt,expiredOnly);
        if(updated!=1)throw conflict();
        return new Result(row.id(),"CANCELLED",row.version()+1,cancelledAt,reason);
    }
    private static void validateLocked(OrderClosingMapper.Locked row,String id){
        if(!id.equals(row.id()) || !validId(row.owner()) || !validId(row.warehouseId()) || row.state()==null
            || row.expiresAt()==null || row.version()<0 || row.version()>9007199254740991L
            || (("UNPAID".equals(row.state()) || "CLOSING".equals(row.state())) && row.cancelledAt()!=null))invalid();
    }
    private static boolean validId(String value){return value!=null && value.matches("[1-9][0-9]{0,18}");}
    private static boolean validTrace(String value){return value!=null && value.matches("[A-Za-z0-9._:-]{1,64}");}
    private static void bad(){throw new ApiException(400,"VALIDATION_ERROR","订单关闭请求字段不合法");}
    private static void invalid(){throw new IllegalStateException("Invalid locked order or order items");}
    private static ApiException notFound(){return new ApiException(404,"NOT_FOUND","订单不存在");}
    private static ApiException conflict(){return new ApiException(409,"STATE_CONFLICT","订单状态冲突");}
    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Order closing requires an active, writable, explicitly READ_COMMITTED transaction");
    }
}
