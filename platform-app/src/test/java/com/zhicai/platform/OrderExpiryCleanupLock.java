package com.zhicai.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test fixture building block only: caller owns approval, transaction and SQL guard. */
final class OrderExpiryCleanupLock {
    private OrderExpiryCleanupLock() {}

    static Map<String, List<Map<String, String>>> lockAll(JdbcTemplate jdbc,
            Map<String, List<String>> columns, BooleanSupplier approved) {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(columns); Objects.requireNonNull(approved);
        if (!approved.getAsBoolean() || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), TransactionDefinition.ISOLATION_REPEATABLE_READ))
            throw new IllegalStateException("Approved writable REPEATABLE_READ transaction required");
        if (!columns.keySet().containsAll(OrderExpiryAcceptanceGuardTest.RESOURCES))
            throw new IllegalArgumentException("Missing resource columns");
        for (String table : OrderExpiryAcceptanceGuardTest.RESOURCES) {
            var tableColumns = columns.get(table);
            if (tableColumns == null || tableColumns.isEmpty()
                    || tableColumns.stream().anyMatch(column -> column == null || !column.matches("[a-z_]+")))
                throw new IllegalArgumentException("Invalid resource columns");
            String key = table.equals("task_lease") ? "task_name" : "id";
            if (!tableColumns.contains(key)) throw new IllegalArgumentException("Missing primary key");
        }
        var result = new LinkedHashMap<String, List<Map<String, String>>>();
        for (String table : OrderExpiryAcceptanceGuardTest.RESOURCES) {
            var tableColumns = columns.get(table);
            String key = table.equals("task_lease") ? "task_name" : "id";
            String sql = "SELECT *," + MySqlAcceptanceSupport.hashSql(tableColumns)
                + " AS _guard_hash FROM `" + table + "` FORCE INDEX (PRIMARY) ORDER BY `" + key + "` FOR UPDATE";
            List<Map<String, String>> rows = jdbc.query(sql, (ResultSetExtractor<List<Map<String, String>>>) rs -> {
                var values = new ArrayList<Map<String, String>>();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, String>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getString(i));
                    values.add(java.util.Collections.unmodifiableMap(row));
                }
                return List.copyOf(values);
            });
            result.put(table, Objects.requireNonNull(rows));
        }
        return Map.copyOf(result);
    }
}
