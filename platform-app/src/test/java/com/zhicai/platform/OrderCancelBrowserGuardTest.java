package com.zhicai.platform;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCancelBrowserGuardTest {
    @Test void preflightAndMissingApprovalRejectEveryMutationAndLock()throws Exception {
        var approved=new AtomicBoolean();
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderCancelBrowserIntegrationTest.CAPS,
            MySqlOrderCancelBrowserIntegrationTest.RESOURCES,19,approved::get);
        var raw=mock(Connection.class);var target=mock(Statement.class);
        when(raw.createStatement()).thenReturn(target);var statement=guard.connection(raw).createStatement();
        for(boolean armed:List.of(false,true)){
            guard.armed=armed;
            for(String budget:MySqlOrderCancelBrowserIntegrationTest.CAPS.keySet())
                assertThatThrownBy(()->statement.executeUpdate(sql(budget))).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->statement.executeQuery("SELECT * FROM sales_order FOR UPDATE")).isInstanceOf(SQLException.class);
        }
        verifyNoInteractions(target);assertThat(guard.snapshot()).isEmpty();assertThat(guard.locks.get()).isZero();
    }
    @Test void approvedBudgetsAreExactAndOriginalAccountOrCatalogCannotBeUpdated()throws Exception {
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderCancelBrowserIntegrationTest.CAPS,
            MySqlOrderCancelBrowserIntegrationTest.RESOURCES,19,()->true);guard.armed=true;
        for(String bad:List.of("UPDATE account SET enabled=0","UPDATE product SET state='DRAFT'","INSERT INTO cart_item(id) VALUES(1)"))
            assertThatThrownBy(()->guard.before(bad)).isInstanceOf(SQLException.class);
        for(var cap:MySqlOrderCancelBrowserIntegrationTest.CAPS.entrySet()){
            for(int i=0;i<cap.getValue();i++)guard.before(sql(cap.getKey()));
            assertThatThrownBy(()->guard.before(sql(cap.getKey()))).isInstanceOf(SQLException.class);
        }
        assertThat(guard.snapshot()).containsExactlyInAnyOrderEntriesOf(MySqlOrderCancelBrowserIntegrationTest.CAPS);
    }
    @Test void cleanupRequiresOwnedTableHashPredicateAndNineteenRowLimit()throws Exception {
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderCancelBrowserIntegrationTest.CAPS,
            MySqlOrderCancelBrowserIntegrationTest.RESOURCES,19,()->true);guard.armed=true;
        String delete="DELETE FROM sales_order WHERE id=? AND SHA2(id,256)=?";
        assertThatThrownBy(()->guard.before(delete)).isInstanceOf(SQLException.class);guard.cleanup=true;
        for(String bad:List.of("DELETE FROM sales_order","DELETE FROM sales_order WHERE id=?","DELETE FROM account WHERE id=? AND SHA2(id,256)=?"))
            assertThatThrownBy(()->guard.before(bad)).isInstanceOf(SQLException.class);
        for(int i=0;i<19;i++)guard.before(delete);
        assertThatThrownBy(()->guard.before(delete)).isInstanceOf(SQLException.class);
    }
    private String sql(String budget){
        String[] parts=budget.split(" ");
        return parts[0].equals("INSERT")?"INSERT INTO "+parts[1]+"(id) VALUES(?)":"UPDATE "+parts[1]+" SET id=? WHERE id=?";
    }
}
