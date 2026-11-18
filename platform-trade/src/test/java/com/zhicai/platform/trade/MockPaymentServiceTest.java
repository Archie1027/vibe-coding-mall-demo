package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockPaymentServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final MockPaymentService.Success EVENT =
        new MockPaymentService.Success("event-1", "transaction-1", "899900", "CNY");
    private final MockPaymentMapper mapper = mock(MockPaymentMapper.class);
    private final MockPaymentService service = new MockPaymentService(mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void transaction() {
        QuoteServiceTest.transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    @AfterEach void clear() {
        TransactionSynchronizationManager.clear();
    }

    @Test void startUsesLockedOwnerAmountAndCreatesOneProcessingAttempt() {
        when(mapper.lockOrder("5")).thenReturn(order("UNPAID", NOW.plusSeconds(1)));
        when(mapper.insertPayment(any())).thenAnswer(call -> {
            MockPaymentMapper.NewPayment created = call.getArgument(0);
            assertThat(created.orderId).isEqualTo("5");
            assertThat(created.amountMinor).isEqualTo("899900");
            assertThat(created.paymentNo).matches("MOCK[0-9a-f]{32}");
            assertThat(created.createdAt).isEqualTo(NOW);
            created.id = "11";
            return 1;
        });

        var result = service.start("4", "5");
        assertThat(result.id()).isEqualTo("11");
        assertThat(result.state()).isEqualTo("PROCESSING");
        assertThat(result.amountMinor()).isEqualTo("899900");
        assertThat(result.currency()).isEqualTo("CNY");
        var calls = inOrder(mapper);
        calls.verify(mapper).lockOrder("5");
        calls.verify(mapper).findBlockingPayments("5");
        calls.verify(mapper).insertPayment(any());
        calls.verifyNoMoreInteractions();
    }

    @Test void startReusesActiveMockButNeverStartsOverAnActiveOtherChannelOrSucceededPayment() {
        when(mapper.lockOrder("5")).thenReturn(order("UNPAID", NOW.plusSeconds(1)));
        when(mapper.findBlockingPayments("5")).thenReturn(List.of(payment("MOCK", "PROCESSING", null)));
        assertThat(service.start("4", "5").id()).isEqualTo("11");
        when(mapper.findBlockingPayments("5")).thenReturn(List.of(payment("SANDBOX", "PROCESSING", null)));
        error(() -> service.start("4", "5"), 409, "STATE_CONFLICT");
        when(mapper.findBlockingPayments("5")).thenReturn(List.of(payment("MOCK", "SUCCEEDED", EVENT.transactionId())));
        error(() -> service.start("4", "5"), 409, "STATE_CONFLICT");
        verify(mapper, never()).insertPayment(any());
    }

    @Test void foreignOrExpiredOrderCannotStartPayment() {
        when(mapper.lockOrder("5")).thenReturn(order("UNPAID", NOW));
        error(() -> service.start("7", "5"), 404, "NOT_FOUND");
        error(() -> service.start("4", "5"), 409, "STATE_CONFLICT");
        when(mapper.lockOrder("5")).thenReturn(order("CLOSING", NOW.plusSeconds(1)));
        error(() -> service.start("4", "5"), 409, "STATE_CONFLICT");
        verify(mapper, never()).findBlockingPayments(any());
        verify(mapper, never()).insertPayment(any());
    }

    @Test void successfulReceiptLocksOrderBeforePaymentAndMakesOnlyOneStateChangeOnReplay() {
        var hash = ArgumentCaptor.forClass(String.class);
        givenActivePayment("UNPAID");
        givenSuccessfulUpdates("UNPAID");

        assertThat(service.acceptSuccess("11", EVENT).state()).isEqualTo("SUCCEEDED");

        var calls = inOrder(mapper);
        calls.verify(mapper).findOrderId("11");
        calls.verify(mapper).lockOrder("5");
        calls.verify(mapper).lockPayment("11", "5");
        calls.verify(mapper).findSuccessEvent(EVENT.eventId());
        calls.verify(mapper).findBlockingPayments("5");
        calls.verify(mapper).insertSuccessEvent(eq("11"), eq(EVENT.eventId()), hash.capture(), eq(NOW));
        calls.verify(mapper).markSucceeded("11", "5", 1, EVENT.transactionId(), NOW);
        calls.verify(mapper).markPaid("5", "UNPAID", 3, NOW);
        calls.verifyNoMoreInteractions();

        assertThat(hash.getValue()).matches("[a-f0-9]{64}");
        when(mapper.findSuccessEvent(EVENT.eventId())).thenReturn(new MockPaymentMapper.Event("11", hash.getValue()));
        when(mapper.lockOrder("5")).thenReturn(order("SHIPPED", NOW.minusSeconds(5)));
        when(mapper.lockPayment("11", "5")).thenReturn(payment("MOCK", "SUCCEEDED", EVENT.transactionId()));
        for (int i = 0; i < 20; i++) {
            assertThat(service.acceptSuccess("11", EVENT).state()).isEqualTo("SUCCEEDED");
        }
        verify(mapper, times(1)).insertSuccessEvent(any(), any(), any(), any());
        verify(mapper, times(1)).markSucceeded(any(), any(), anyLong(), any(), any());
        verify(mapper, times(1)).markPaid(any(), any(), anyLong(), any());
    }

    @Test void credibleSuccessCanResolveClosingButCannotReopenCancelledOrder() {
        givenActivePayment("CANCELLED");
        error(() -> service.acceptSuccess("11", EVENT), 409, "STATE_CONFLICT");
        verify(mapper, never()).insertSuccessEvent(any(), any(), any(), any());
        givenActivePayment("CLOSING");
        givenSuccessfulUpdates("CLOSING");
        assertThat(service.acceptSuccess("11", EVENT).state()).isEqualTo("SUCCEEDED");
        verify(mapper).markPaid("5", "CLOSING", 3, NOW);
    }

    @Test void invalidCurrencyOrMismatchedAmountCannotWriteOrAdvanceOrder() {
        error(() -> service.acceptSuccess("11", new MockPaymentService.Success("event-1", "transaction-1", "1", "USD")),
            400, "VALIDATION_ERROR");
        verifyNoInteractions(mapper);
        givenActivePayment("UNPAID");
        error(() -> service.acceptSuccess("11", new MockPaymentService.Success("event-1", "transaction-1", "1", "CNY")),
            409, "PAYMENT_AMOUNT_MISMATCH");
        verify(mapper, never()).findSuccessEvent(any());
        verify(mapper, never()).insertSuccessEvent(any(), any(), any(), any());
        verify(mapper, never()).markPaid(any(), any(), anyLong(), any());
    }

    @Test void eventIdentifierCannotBeReusedForAChangedPayload() {
        givenActivePayment("UNPAID");
        when(mapper.findSuccessEvent(EVENT.eventId())).thenReturn(new MockPaymentMapper.Event("11", "a".repeat(64)));
        error(() -> service.acceptSuccess("11", EVENT), 409, "PAYMENT_EVENT_CONFLICT");
        verify(mapper, never()).insertSuccessEvent(any(), any(), any(), any());
        verify(mapper, never()).markPaid(any(), any(), anyLong(), any());
    }

    @Test void anotherSucceededAttemptBlocksNewSuccessOnInconsistentOrder() {
        givenActivePayment("UNPAID");
        var other = new MockPaymentMapper.Payment("12", "5", "MOCK-12", "MOCK", "SUCCEEDED", "899900", "CNY",
            "previous-transaction", NOW.minusSeconds(10), 1);
        when(mapper.findBlockingPayments("5")).thenReturn(List.of(payment("MOCK", "PROCESSING", null), other));
        error(() -> service.acceptSuccess("11", EVENT), 409, "STATE_CONFLICT");
        verify(mapper, never()).insertSuccessEvent(any(), any(), any(), any());
        verify(mapper, never()).markPaid(any(), any(), anyLong(), any());
    }

    @Test void duplicateEventAtInsertDoesNotAttemptPaymentOrOrderUpdate() {
        givenActivePayment("UNPAID");
        when(mapper.insertSuccessEvent(eq("11"), eq(EVENT.eventId()), anyString(), eq(NOW)))
            .thenThrow(new DuplicateKeyException("receipt collision"));
        error(() -> service.acceptSuccess("11", EVENT), 409, "PAYMENT_EVENT_CONFLICT");
        verify(mapper, never()).markSucceeded(any(), any(), anyLong(), any(), any());
        verify(mapper, never()).markPaid(any(), any(), anyLong(), any());
    }

    @Test void missingOrderUpdatePropagatesAnExceptionForTheCallerToRollBack() {
        givenActivePayment("UNPAID");
        givenSuccessfulUpdates("UNPAID");
        when(mapper.markPaid("5", "UNPAID", 3, NOW)).thenReturn(0);
        error(() -> service.acceptSuccess("11", EVENT), 409, "STATE_CONFLICT");
    }

    @Test void requiresAnExplicitWritableReadCommittedTransaction() {
        QuoteServiceTest.transaction(true, TransactionDefinition.ISOLATION_REPEATABLE_READ, false);
        assertThatThrownBy(() -> service.acceptSuccess("11", EVENT)).isInstanceOf(IllegalTransactionStateException.class);
        QuoteServiceTest.transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED, true);
        assertThatThrownBy(() -> service.start("4", "5")).isInstanceOf(IllegalTransactionStateException.class);
        TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> service.start("4", "5")).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(mapper);
    }

    private void givenActivePayment(String state) {
        when(mapper.findOrderId("11")).thenReturn("5");
        when(mapper.lockOrder("5")).thenReturn(order(state, NOW.minusSeconds(1)));
        when(mapper.lockPayment("11", "5")).thenReturn(payment("MOCK", "PROCESSING", null));
        when(mapper.findBlockingPayments("5")).thenReturn(List.of(payment("MOCK", "PROCESSING", null)));
    }

    private void givenSuccessfulUpdates(String state) {
        when(mapper.insertSuccessEvent(eq("11"), eq(EVENT.eventId()), anyString(), eq(NOW))).thenReturn(1);
        when(mapper.markSucceeded("11", "5", 1, EVENT.transactionId(), NOW)).thenReturn(1);
        when(mapper.markPaid("5", state, 3, NOW)).thenReturn(1);
    }

    private static MockPaymentMapper.Order order(String state, Instant expiry) {
        return new MockPaymentMapper.Order("5", "4", state, "899900", null, expiry, 3);
    }

    private static MockPaymentMapper.Payment payment(String channel, String state, String transactionId) {
        return new MockPaymentMapper.Payment("11", "5", "MOCK-11", channel, state, "899900", "CNY",
            transactionId, NOW.minusSeconds(5), 1);
    }

    private static void error(ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.status()).isEqualTo(status);
            assertThat(failure.code()).isEqualTo(code);
        });
    }
}
