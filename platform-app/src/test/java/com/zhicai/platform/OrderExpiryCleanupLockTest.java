package com.zhicai.platform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderExpiryCleanupLockTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }

    private Map<String, List<String>> columns() {
        var result = new HashMap<String, List<String>>();
        for (String table : OrderExpiryAcceptanceGuardTest.RESOURCES)
            result.put(table, List.of(table.equals("task_lease") ? "task_name" : "id", "version"));
        return result;
    }

    @Test void requiresExplicitApprovalAndWritableRepeatableReadBeforeAnySql() {
        assertThatThrownBy(() -> OrderExpiryCleanupLock.lockAll(jdbc, columns(), () -> false))
            .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> OrderExpiryCleanupLock.lockAll(jdbc, columns(), () -> true))
            .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> OrderExpiryCleanupLock.lockAll(jdbc, columns(), () -> true))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsInvalidColumnsBeforeLockingAnyTable() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var columns = columns(); columns.put("task_lease", List.of("task_name", "invalid;drop"));
        assertThatThrownBy(() -> OrderExpiryCleanupLock.lockAll(jdbc, columns, () -> true))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void locksEveryTableInFixedOrderAndUsesLeaseStringPrimaryKey() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(List.of());
        var locked = OrderExpiryCleanupLock.lockAll(jdbc, columns(), () -> true);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(9)).query(sql.capture(), any(ResultSetExtractor.class));
        assertThat(locked.keySet()).containsExactlyInAnyOrderElementsOf(OrderExpiryAcceptanceGuardTest.RESOURCES);
        assertThat(sql.getAllValues()).allMatch(value -> value.contains("FORCE INDEX (PRIMARY)") && value.endsWith("FOR UPDATE"));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("FROM `task_lease`") && value.contains("ORDER BY `task_name` FOR UPDATE"));
    }
}
