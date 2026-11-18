package com.zhicai.platform;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Candidate budget only; this test cannot authorize or execute a real database fixture. */
class OrderExpiryAcceptanceGuardTest {
    static final Map<String, Integer> CAPS = Map.ofEntries(
        Map.entry("INSERT customer_address", 1), Map.entry("INSERT stock_balance", 1),
        Map.entry("INSERT checkout_quote", 2), Map.entry("INSERT sales_order", 2),
        Map.entry("INSERT sales_order_item", 2), Map.entry("INSERT stock_reservation", 2),
        Map.entry("INSERT stock_ledger", 3), Map.entry("INSERT payment", 1),
        Map.entry("INSERT task_lease", 1), Map.entry("UPDATE checkout_quote", 2),
        Map.entry("UPDATE stock_balance", 3), Map.entry("UPDATE sales_order", 2),
        Map.entry("UPDATE stock_reservation", 1), Map.entry("UPDATE task_lease", 1));
    static final List<String> RESOURCES = List.of("customer_address", "stock_balance", "checkout_quote",
        "sales_order", "sales_order_item", "stock_reservation", "stock_ledger", "payment", "task_lease");
    static final int MAX_DELETES = 15;

    @Test void unapprovedOrUnarmedRejectsMutationsAndLocksBeforeExecution() {
        var approved = new AtomicBoolean();
        var guard = new MySqlAcceptanceSupport.SqlGuard(CAPS, RESOURCES, MAX_DELETES, approved::get);
        for (boolean armed : List.of(false, true)) {
            guard.armed = armed;
            for (String budget : CAPS.keySet())
                assertThatThrownBy(() -> guard.before(sql(budget))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> guard.before("SELECT id FROM sales_order FOR UPDATE")).isInstanceOf(SQLException.class);
        }
        approved.set(true);
        guard.armed = false;
        assertThatThrownBy(() -> guard.before("INSERT INTO task_lease(task_name) VALUES(?)")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> guard.before("SELECT id FROM sales_order FOR UPDATE")).isInstanceOf(SQLException.class);
        assertThat(guard.snapshot()).isEmpty();
        assertThat(guard.locks.get()).isZero();
    }

    @Test void approvedBudgetRejectsUnlistedTablesAndEveryAttemptBeyondItsCap() throws Exception {
        var guard = new MySqlAcceptanceSupport.SqlGuard(CAPS, RESOURCES, MAX_DELETES, () -> true);
        guard.armed = true;
        for (String bad : List.of("UPDATE account SET enabled=0", "UPDATE product SET state='DRAFT'",
                "INSERT INTO cart_item(id) VALUES(1)", "UPDATE payment SET state='CLOSED'"))
            assertThatThrownBy(() -> guard.before(bad)).isInstanceOf(SQLException.class);
        for (var cap : CAPS.entrySet()) {
            for (int count = 0; count < cap.getValue(); count++) guard.before(sql(cap.getKey()));
            assertThatThrownBy(() -> guard.before(sql(cap.getKey()))).isInstanceOf(SQLException.class);
        }
        assertThat(guard.snapshot()).containsExactlyInAnyOrderEntriesOf(CAPS);
    }

    @Test void cleanupRequiresExplicitPhaseHashPredicateAndBoundedDeletes() throws Exception {
        var guard = new MySqlAcceptanceSupport.SqlGuard(CAPS, RESOURCES, MAX_DELETES, () -> true);
        guard.armed = true;
        String delete = "DELETE FROM task_lease WHERE task_name=? AND SHA2(owner_token,256)=?";
        assertThatThrownBy(() -> guard.before(delete)).isInstanceOf(SQLException.class);
        guard.cleanup = true;
        for (String bad : List.of("DELETE FROM task_lease", "DELETE FROM task_lease WHERE task_name=?",
                "DELETE FROM account WHERE id=? AND SHA2(id,256)=?"))
            assertThatThrownBy(() -> guard.before(bad)).isInstanceOf(SQLException.class);
        for (int count = 0; count < MAX_DELETES; count++) guard.before(delete);
        assertThatThrownBy(() -> guard.before(delete)).isInstanceOf(SQLException.class);
    }

    private static String sql(String budget) {
        String[] parts = budget.split(" ");
        return parts[0].equals("INSERT") ? "INSERT INTO " + parts[1] + "(id) VALUES(?)"
            : "UPDATE " + parts[1] + " SET id=? WHERE id=?";
    }
}
