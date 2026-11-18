package com.zhicai.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Test-only proof checker: no SQL execution, no approval or cleanup authority. */
final class OrderExpiryCleanupEvidence {
    private OrderExpiryCleanupEvidence() {}

    static void verify(Map<String, List<Map<String, String>>> frozen,
            Map<String, List<Map<String, String>>> locked, Map<String, Set<String>> owned, String leaseOwner) {
        Objects.requireNonNull(frozen); Objects.requireNonNull(locked); Objects.requireNonNull(owned);
        if (leaseOwner == null || !leaseOwner.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Canonical lease owner required");
        var tables = Set.copyOf(OrderExpiryAcceptanceGuardTest.RESOURCES);
        if (!frozen.keySet().equals(tables) || !locked.keySet().equals(tables) || !owned.keySet().equals(tables))
            throw new IllegalArgumentException("Exact resource tables required");
        int total = 0;
        for (String table : tables) {
            var expected = owned.get(table);
            var before = frozen.get(table);
            var after = locked.get(table);
            Integer planned = OrderExpiryAcceptanceGuardTest.CAPS.get("INSERT " + table);
            if (expected == null || before == null || after == null || planned == null
                    || expected.size() != planned || before.size() != expected.size())
                throw new IllegalStateException("Unexpected row count for " + table);
            String key = table.equals("task_lease") ? "task_name" : "id";
            var keys = new HashSet<String>();
            for (var row : before) {
                if (row == null || row.get(key) == null || !keys.add(row.get(key))
                        || row.get("_guard_hash") == null || !row.get("_guard_hash").matches("[a-fA-F0-9]{64}"))
                    throw new IllegalStateException("Invalid row identity or hash for " + table);
            }
            if (!keys.equals(expected)) throw new IllegalStateException("Unowned row for " + table);
            if (table.equals("task_lease") && (!keys.equals(Set.of("order-expiry"))
                    || !leaseOwner.equals(before.getFirst().get("owner_token"))))
                throw new IllegalStateException("Lease does not belong to this run");
            // Digest includes all returned columns, including the database-generated guard hash.
            if (!MySqlAcceptanceSupport.digest(new ArrayList<>(before)).equals(MySqlAcceptanceSupport.digest(new ArrayList<>(after))))
                throw new IllegalStateException("Locked rows differ from frozen evidence for " + table);
            total += before.size();
        }
        if (total != OrderExpiryAcceptanceGuardTest.MAX_DELETES)
            throw new IllegalStateException("Unexpected total row count");
    }
}
