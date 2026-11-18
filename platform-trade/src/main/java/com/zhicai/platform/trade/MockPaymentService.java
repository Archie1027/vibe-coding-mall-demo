package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal MOCK success core. A future trusted dev/test caller must own the writable RC transaction. */
public class MockPaymentService {
    public record Success(String eventId, String transactionId, String amountMinor, String currency) {}
    public record Result(String id, String orderId, String paymentNo, String channel, String state,
                         String amountMinor, String currency, Instant createdAt) {}

    private final MockPaymentMapper mapper;
    private final Clock clock;

    public MockPaymentService(MockPaymentMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result start(String owner, String orderId) {
        TradeValidation.id(owner);
        TradeValidation.id(orderId);
        requireTransaction();
        var order = mapper.lockOrder(orderId);
        if (order == null || !owner.equals(order.owner())) throw TradeValidation.notFound("订单");
        verifyOrder(order, orderId);
        Instant checkedAt = clock.instant();
        if (!"UNPAID".equals(order.state()) || !checkedAt.isBefore(order.expiresAt())) throw conflict();
        requireNoCoupon(order);
        var blocking = mapper.findBlockingPayments(orderId);
        if (!blocking.isEmpty()) {
            var payment = blocking.getFirst();
            if (blocking.size() != 1 || !"MOCK".equals(payment.channel()) || !active(payment.state())) throw conflict();
            verifyPayment(payment, order);
            return result(payment, payment.state());
        }

        Instant createdAt = clock.instant();
        if (!createdAt.isBefore(order.expiresAt())) throw conflict();
        var payment = new MockPaymentMapper.NewPayment(orderId,
            "MOCK" + UUID.randomUUID().toString().replace("-", ""), order.payableMinor(), createdAt.truncatedTo(ChronoUnit.MILLIS));
        if (mapper.insertPayment(payment) != 1) throw conflict();
        requirePersistedId(payment.id);
        return new Result(payment.id, orderId, payment.paymentNo, "MOCK", "PROCESSING", payment.amountMinor, "CNY", payment.createdAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result acceptSuccess(String paymentId, Success event) {
        TradeValidation.id(paymentId);
        validateSuccess(event);
        requireTransaction();

        // Discover the order without taking any payment lock. The order root is shared with cancellation and expiry.
        String orderId = mapper.findOrderId(paymentId);
        if (orderId == null) throw TradeValidation.notFound("支付");
        requirePersistedId(orderId);
        var order = mapper.lockOrder(orderId);
        if (order == null) throw TradeValidation.notFound("订单");
        verifyOrder(order, orderId);
        var payment = mapper.lockPayment(paymentId, orderId);
        if (payment == null || !paymentId.equals(payment.id()) || !"MOCK".equals(payment.channel()))
            throw TradeValidation.notFound("支付");
        verifyPayment(payment, order);
        if (!payment.amountMinor().equals(event.amountMinor()) || !payment.currency().equals(event.currency()))
            throw new ApiException(409, "PAYMENT_AMOUNT_MISMATCH", "支付金额或币种与订单不一致");

        String hash = payloadHash(paymentId, event);
        var recorded = mapper.findSuccessEvent(event.eventId());
        if (recorded != null) {
            if (!paymentId.equals(recorded.paymentId()) || !hash.equals(recorded.payloadHash()))
                throw new ApiException(409, "PAYMENT_EVENT_CONFLICT", "支付事件编号已被使用");
            if (!"SUCCEEDED".equals(payment.state()) || !event.transactionId().equals(payment.transactionId())
                || !("PAID".equals(order.state()) || "SHIPPED".equals(order.state()) || "COMPLETED".equals(order.state())))
                throw new IllegalStateException("Recorded MOCK success does not match payment and order");
            return result(payment, "SUCCEEDED");
        }

        // An existing active attempt may succeed while expiry is checking it. A cancelled order is never reopened.
        if (!active(payment.state()) || !("UNPAID".equals(order.state()) || "CLOSING".equals(order.state()))
            || order.version() >= TradeValidation.MAX_SAFE_VERSION || payment.version() >= TradeValidation.MAX_SAFE_VERSION)
            throw conflict();
        requireNoCoupon(order);
        var blocking = mapper.findBlockingPayments(orderId);
        if (blocking.size() != 1 || !paymentId.equals(blocking.getFirst().id())) throw conflict();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        try {
            if (mapper.insertSuccessEvent(paymentId, event.eventId(), hash, now) != 1
                || mapper.markSucceeded(paymentId, orderId, payment.version(), event.transactionId(), now) != 1
                || mapper.markPaid(orderId, order.state(), order.version(), now) != 1)
                throw conflict();
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409, "PAYMENT_EVENT_CONFLICT", "支付事件编号或渠道交易号已被使用");
        }
        return result(payment, "SUCCEEDED");
    }

    public static void validateSuccess(Success event) {
        if (event == null || !identifier(event.eventId()) || !identifier(event.transactionId())
            || !money(event.amountMinor()) || !"CNY".equals(event.currency()))
            TradeValidation.bad("模拟支付成功事件字段不合法");
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,100}");
    }

    private static boolean money(String value) {
        return value != null && value.matches("[1-9][0-9]{0,17}");
    }

    private static boolean active(String state) {
        return "CREATED".equals(state) || "PROCESSING".equals(state);
    }

    private static void verifyOrder(MockPaymentMapper.Order order, String id) {
        if (!id.equals(order.id()) || !money(order.payableMinor()) || order.expiresAt() == null || order.version() < 0)
            throw new IllegalStateException("Invalid locked order for payment");
    }

    private static void verifyPayment(MockPaymentMapper.Payment payment, MockPaymentMapper.Order order) {
        requirePersistedId(payment.id());
        if (!order.id().equals(payment.orderId()) || !order.payableMinor().equals(payment.amountMinor())
            || !"CNY".equals(payment.currency()) || payment.version() < 0 || payment.createdAt() == null)
            throw new IllegalStateException("Invalid locked MOCK payment");
    }

    private static void requirePersistedId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new IllegalStateException("Invalid persisted payment identity");
    }

    private static void requireNoCoupon(MockPaymentMapper.Order order) {
        if (order.couponId() != null) throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "优惠券核销尚未接入");
    }

    private static Result result(MockPaymentMapper.Payment payment, String state) {
        return new Result(payment.id(), payment.orderId(), payment.paymentNo(), payment.channel(), state,
            payment.amountMinor(), payment.currency(), payment.createdAt());
    }

    private static String payloadHash(String paymentId, Success event) {
        String canonical = String.join("|", paymentId, event.eventId(), event.transactionId(), event.amountMinor(), event.currency());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static ApiException conflict() {
        return new ApiException(409, "STATE_CONFLICT", "订单或支付状态冲突");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("MOCK payment requires a writable READ_COMMITTED transaction");
    }
}
