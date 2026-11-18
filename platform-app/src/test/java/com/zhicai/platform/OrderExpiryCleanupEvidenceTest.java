package com.zhicai.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderExpiryCleanupEvidenceTest {
    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private final Map<String, List<Map<String, String>>> frozen = new HashMap<>();
    private final Map<String, Set<String>> owned = new HashMap<>();

    OrderExpiryCleanupEvidenceTest() {
        for (String table : OrderExpiryAcceptanceGuardTest.RESOURCES) {
            int count = OrderExpiryAcceptanceGuardTest.CAPS.get("INSERT " + table);
            var rows = new ArrayList<Map<String, String>>();
            var keys = new HashSet<String>();
            for (int n = 1; n <= count; n++) {
                String id = table.equals("task_lease") ? "order-expiry" : Integer.toString(n);
                String key = table.equals("task_lease") ? "task_name" : "id";
                var row = new HashMap<String, String>(Map.of(key, id, "value", "test-" + n, "_guard_hash", "a".repeat(64)));
                if (table.equals("task_lease")) row.put("owner_token", OWNER);
                rows.add(row);
                keys.add(id);
            }
            frozen.put(table, rows);
            owned.put(table, keys);
        }
    }

    private Map<String, List<Map<String, String>>> lockedCopy() {
        var result = new HashMap<String, List<Map<String, String>>>();
        frozen.forEach((table, rows) -> result.put(table, new ArrayList<>(rows)));
        return result;
    }

    @Test void acceptsExactOwnedRowsIncludingLeaseStringKey() {
        OrderExpiryCleanupEvidence.verify(frozen, lockedCopy(), owned, OWNER);
    }

    @Test void rejectsLockedRowDriftEvenIfKeysAndGuardHashAreUnchanged() {
        var locked = lockedCopy();
        var row = new HashMap<>(locked.get("sales_order").getFirst());
        row.put("value", "changed");
        locked.get("sales_order").set(0, row);
        assertThatThrownBy(() -> OrderExpiryCleanupEvidence.verify(frozen, locked, owned, OWNER))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsUnownedRowsMissingTablesAndUnexpectedCounts() {
        var wrongOwned = new HashMap<>(owned);
        wrongOwned.put("task_lease", Set.of("another-task"));
        assertThatThrownBy(() -> OrderExpiryCleanupEvidence.verify(frozen, lockedCopy(), wrongOwned, OWNER))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OrderExpiryCleanupEvidence.verify(frozen, lockedCopy(), owned, "22222222-2222-4222-8222-222222222222"))
            .isInstanceOf(IllegalStateException.class);
        var missing = lockedCopy(); missing.remove("payment");
        assertThatThrownBy(() -> OrderExpiryCleanupEvidence.verify(frozen, missing, owned, OWNER))
            .isInstanceOf(IllegalArgumentException.class);
        var extra = lockedCopy(); extra.get("stock_ledger").add(extra.get("stock_ledger").getFirst());
        assertThatThrownBy(() -> OrderExpiryCleanupEvidence.verify(frozen, extra, owned, OWNER))
            .isInstanceOf(IllegalStateException.class);
    }
}
