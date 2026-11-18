package com.zhicai.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderExpiryCleanupDeleteTest {
    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Map<String, List<String>> columns = new HashMap<>();
    private final Map<String, List<Map<String, String>>> frozen = new HashMap<>();
    private final Map<String, Set<String>> owned = new HashMap<>();

    OrderExpiryCleanupDeleteTest() {
        for (String table : OrderExpiryAcceptanceGuardTest.RESOURCES) {
            int count = OrderExpiryAcceptanceGuardTest.CAPS.get("INSERT " + table);
            String key = table.equals("task_lease") ? "task_name" : "id";
            columns.put(table, List.of(key));
            var rows = new ArrayList<Map<String, String>>();
            var keys = new HashSet<String>();
            for (int n = 1; n <= count; n++) {
                String id = table.equals("task_lease") ? "order-expiry" : Integer.toString(n);
                var row = new HashMap<String, String>(Map.of(key, id, "_guard_hash", "a".repeat(64)));
                if (table.equals("task_lease")) row.put("owner_token", OWNER);
                rows.add(row); keys.add(id);
            }
            frozen.put(table, rows); owned.put(table, keys);
        }
    }
    @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }
    private void transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private MySqlAcceptanceSupport.SqlGuard guard(boolean approved) {
        var value = new MySqlAcceptanceSupport.SqlGuard(OrderExpiryAcceptanceGuardTest.CAPS,
            OrderExpiryAcceptanceGuardTest.RESOURCES, 15, () -> approved);
        value.armed = true;
        return value;
    }
    @Test void noApprovalOrChangedEvidencePreventsAnyDelete() {
        transaction();
        assertThatThrownBy(() -> OrderExpiryCleanupDelete.deleteAll(jdbc, guard(false), columns, frozen, frozen, owned, OWNER))
            .isInstanceOf(IllegalStateException.class);
        var changed = new HashMap<>(frozen); changed.put("payment", List.of());
        assertThatThrownBy(() -> OrderExpiryCleanupDelete.deleteAll(jdbc, guard(true), columns, frozen, changed, owned, OWNER))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void deletesOnlyWithFullHashAndUsesLeaseStringKey() throws Exception {
        transaction(); var guard = guard(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> { guard.before(call.getArgument(0)); return 1; });
        OrderExpiryCleanupDelete.deleteAll(jdbc, guard, columns, frozen, frozen, owned, OWNER);
        assertThat(guard.deletes.get()).isEqualTo(15);
        assertThat(guard.cleanup).isFalse();
        verify(jdbc).update(contains("WHERE task_name=? AND SHA2("), eq("order-expiry"), eq("a".repeat(64)));
    }
    @Test void missingRowStopsImmediatelyAndDisarmsCleanupPhase() {
        transaction(); var guard = guard(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> OrderExpiryCleanupDelete.deleteAll(jdbc, guard, columns, frozen, frozen, owned, OWNER))
            .isInstanceOf(IllegalStateException.class);
        assertThat(guard.cleanup).isFalse();
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }
}
