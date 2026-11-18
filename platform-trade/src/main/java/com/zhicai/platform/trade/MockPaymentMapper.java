package com.zhicai.platform.trade;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MockPaymentMapper {
    String findOrderId(@Param("paymentId") String paymentId);
    Order lockOrder(@Param("orderId") String orderId);
    List<Payment> findBlockingPayments(@Param("orderId") String orderId);
    Payment lockPayment(@Param("paymentId") String paymentId, @Param("orderId") String orderId);
    int insertPayment(NewPayment payment);
    Event findSuccessEvent(@Param("eventId") String eventId);
    int insertSuccessEvent(@Param("paymentId") String paymentId, @Param("eventId") String eventId,
                           @Param("payloadHash") String payloadHash, @Param("now") Instant now);
    int markSucceeded(@Param("paymentId") String paymentId, @Param("orderId") String orderId,
                      @Param("version") long version, @Param("transactionId") String transactionId, @Param("now") Instant now);
    int markPaid(@Param("orderId") String orderId, @Param("state") String state,
                 @Param("version") long version, @Param("now") Instant now);

    record Order(String id, String owner, String state, String payableMinor, String couponId,
                 Instant expiresAt, long version) {}
    record Payment(String id, String orderId, String paymentNo, String channel, String state,
                   String amountMinor, String currency, String transactionId, Instant createdAt, long version) {}
    record Event(String paymentId, String payloadHash) {}

    final class NewPayment {
        public String id;
        public final String orderId, paymentNo, amountMinor;
        public final Instant createdAt;

        NewPayment(String orderId, String paymentNo, String amountMinor, Instant createdAt) {
            this.orderId = orderId;
            this.paymentNo = paymentNo;
            this.amountMinor = amountMinor;
            this.createdAt = createdAt;
        }
    }
}
