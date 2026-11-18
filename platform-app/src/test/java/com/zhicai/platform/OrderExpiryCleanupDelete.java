package com.zhicai.platform;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Candidate fixture component; callers must let any failure roll back the entire transaction. */
final class OrderExpiryCleanupDelete {
    private static final List<String> DELETE_ORDER = List.of("payment", "stock_ledger", "stock_reservation",
        "sales_order_item", "sales_order", "checkout_quote", "stock_balance", "customer_address", "task_lease");
    private OrderExpiryCleanupDelete() {}

    static void deleteAll(JdbcTemplate jdbc, MySqlAcceptanceSupport.SqlGuard guard, Map<String, List<String>> columns,
            Map<String, List<Map<String, String>>> frozen, Map<String, List<Map<String, String>>> locked,
            Map<String, Set<String>> owned, String leaseOwner) {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(guard); Objects.requireNonNull(columns);
        if (!guard.approved() || !guard.armed || guard.cleanup
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), TransactionDefinition.ISOLATION_REPEATABLE_READ))
            throw new IllegalStateException("Approved guarded writable REPEATABLE_READ transaction required");
        OrderExpiryCleanupEvidence.verify(frozen, locked, owned, leaseOwner);
        for (String table : DELETE_ORDER) {
            var names = columns.get(table);
            String key = table.equals("task_lease") ? "task_name" : "id";
            if (names == null || names.isEmpty() || !names.contains(key)
                    || names.stream().anyMatch(name -> name == null || !name.matches("[a-z_]+")))
                throw new IllegalArgumentException("Invalid cleanup columns for " + table);
        }
        guard.cleanup = true;
        try {
            for (String table : DELETE_ORDER) {
                boolean lease = table.equals("task_lease");
                String key = lease ? "task_name" : "id";
                String predicate = lease ? "task_name=?" : "id=CAST(? AS UNSIGNED)";
                String sql = "DELETE FROM `" + table + "` WHERE " + predicate + " AND "
                    + MySqlAcceptanceSupport.hashSql(columns.get(table)) + "=?";
                for (var row : frozen.get(table)) {
                    if (jdbc.update(sql, row.get(key), row.get("_guard_hash")) != 1)
                        throw new IllegalStateException("Cleanup row changed or is missing in " + table);
                }
            }
        } finally {
            guard.cleanup = false;
        }
    }
}
