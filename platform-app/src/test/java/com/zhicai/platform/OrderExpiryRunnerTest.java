package com.zhicai.platform;

import com.zhicai.platform.scheduling.DatabaseTaskLease;
import com.zhicai.platform.scheduling.OrderExpiryRunner;
import com.zhicai.platform.trade.OrderClosingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderExpiryRunnerTest {
    @Test void expiredLeaseStopsBeforeProcessingAndStillReleasesItsOwnHandle() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var closing = mock(OrderClosingService.class);
        var token = new AtomicReference<String>();
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(jdbc.update(startsWith("INSERT INTO task_lease"), any(Object[].class)))
            .thenAnswer(call -> { token.set(call.getArgument(2)); return 1; });
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(call -> token.get());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        var now = Instant.parse("2026-11-16T00:00:00Z");
        when(closing.expiredCandidates(eq(now), isNull(), eq(100)))
            .thenReturn(List.of(new OrderClosingService.Candidate("1", now)));
        new OrderExpiryRunner(new DatabaseTaskLease(jdbc, manager), closing, Clock.fixed(now, ZoneOffset.UTC)).runOnce();
        verify(closing, never()).expire(anyString(), anyString());
        verify(jdbc).update(contains("UPDATE task_lease SET lease_until"), any(Object[].class));
    }

    @Test void scanFailurePropagatesAndReleasesLease() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var closing = mock(OrderClosingService.class);
        var token = new AtomicReference<String>();
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(jdbc.update(startsWith("INSERT INTO task_lease"), any(Object[].class)))
            .thenAnswer(call -> { token.set(call.getArgument(2)); return 1; });
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(call -> token.get());
        var failure = new IllegalStateException("scan failed");
        when(closing.expiredCandidates(any(), isNull(), eq(100))).thenThrow(failure);
        var runner = new OrderExpiryRunner(new DatabaseTaskLease(jdbc, manager), closing, Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThatThrownBy(runner::runOnce).isSameAs(failure);
        verify(jdbc).update(contains("UPDATE task_lease SET lease_until"), any(Object[].class));
        verify(closing, never()).expire(anyString(), anyString());
    }

    @Test void resumesAfterBoundedBatchInsteadOfRepeatedlyStartingAtFirstPage() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var closing = mock(OrderClosingService.class);
        var token = new AtomicReference<String>();
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(jdbc.update(startsWith("INSERT INTO task_lease"), any(Object[].class)))
            .thenAnswer(call -> { token.set(call.getArgument(2)); return 1; });
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(call -> token.get());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        var now = Instant.parse("2026-11-16T00:00:00Z");
        var page = java.util.stream.IntStream.rangeClosed(1, 100)
            .mapToObj(id -> new OrderClosingService.Candidate(Integer.toString(id), now)).toList();
        var calls = new AtomicInteger();
        var resumed = new AtomicReference<OrderClosingService.Cursor>();
        when(closing.expiredCandidates(any(), nullable(OrderClosingService.Cursor.class), eq(100))).thenAnswer(call -> {
            if (calls.incrementAndGet() <= 10) return page;
            resumed.set(call.getArgument(1));
            return List.of();
        });
        var runner = new OrderExpiryRunner(new DatabaseTaskLease(jdbc, manager), closing, Clock.fixed(now, ZoneOffset.UTC));
        runner.runOnce();
        runner.runOnce();
        org.assertj.core.api.Assertions.assertThat(resumed.get()).isEqualTo(new OrderClosingService.Cursor(now, "100"));
    }

    @Test void processesCandidatesSeparatelyAndContinuesAfterAnOrderFails() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var closing = mock(OrderClosingService.class);
        var token = new AtomicReference<String>();
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(jdbc.update(startsWith("INSERT INTO task_lease"), any(Object[].class)))
            .thenAnswer(call -> { token.set(call.getArgument(2)); return 1; });
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(call -> token.get());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        var now = Instant.parse("2026-11-16T00:00:00Z");
        when(closing.expiredCandidates(eq(now), isNull(), eq(100))).thenReturn(List.of(
            new OrderClosingService.Candidate("1", now), new OrderClosingService.Candidate("2", now)));
        when(closing.expire(eq("1"), anyString())).thenThrow(new IllegalStateException("test"));
        new OrderExpiryRunner(new DatabaseTaskLease(jdbc, manager), closing, Clock.fixed(now, ZoneOffset.UTC)).runOnce();
        verify(closing).expire(eq("1"), anyString());
        verify(closing).expire(eq("2"), anyString());
        verify(manager).rollback(any());
        verify(jdbc).update(contains("UPDATE task_lease SET lease_until"), any(Object[].class));
    }

    @Test void rivalOwnerPreventsAnyScan() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var closing = mock(OrderClosingService.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("rival");
        new OrderExpiryRunner(new DatabaseTaskLease(jdbc, manager), closing, Clock.systemUTC()).runOnce();
        verifyNoInteractions(closing);
    }
}
