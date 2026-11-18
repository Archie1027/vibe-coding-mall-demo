package com.zhicai.platform;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline tests cannot authorize the live cancellation fixture. */
class OrderCancelAcceptanceGuardTest {
    @Test void readonlyAndMissingApprovalStopEveryBudgetBeforeDriver() throws Exception {
        var approved=new AtomicBoolean();var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderCancelHttpIntegrationTest.CAPS,MySqlOrderCancelHttpIntegrationTest.RESOURCES,39,approved::get);
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);var statement=guard.connection(raw).createStatement();
        for(boolean armed:List.of(false,true)){guard.armed=armed;
            for(String key:MySqlOrderCancelHttpIntegrationTest.CAPS.keySet())assertThatThrownBy(()->statement.executeUpdate(sql(key))).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->statement.executeQuery("SELECT * FROM sales_order FOR UPDATE")).isInstanceOf(SQLException.class);
        }
        verifyNoInteractions(target);assertThat(guard.snapshot()).isEmpty();assertThat(guard.locks.get()).isZero();
    }
    @Test void exactCapsAndUnknownTableUpdatesCannotPassDriver() throws Exception {
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderCancelHttpIntegrationTest.CAPS,MySqlOrderCancelHttpIntegrationTest.RESOURCES,39,()->true);guard.armed=true;
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);var statement=guard.connection(raw).createStatement();
        for(String bad:List.of("UPDATE account SET enabled=0","UPDATE product SET state='DRAFT'","INSERT INTO finance_record(id) VALUES(1)","CREATE TABLE x(id int)"))assertThatThrownBy(()->statement.executeUpdate(bad)).isInstanceOf(SQLException.class);
        verifyNoInteractions(target);
        for(var cap:MySqlOrderCancelHttpIntegrationTest.CAPS.entrySet()){
            for(int i=0;i<cap.getValue();i++)statement.executeUpdate(sql(cap.getKey()));
            assertThatThrownBy(()->statement.executeUpdate(sql(cap.getKey()))).isInstanceOf(SQLException.class);
            verify(target,times(cap.getValue())).executeUpdate(sql(cap.getKey()));
        }
        assertThat(guard.snapshot()).containsExactlyInAnyOrderEntriesOf(MySqlOrderCancelHttpIntegrationTest.CAPS);
    }
    @Test void deletionRequiresCleanupHashOwnedTableAnd39Limit()throws Exception {
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlOrderCancelHttpIntegrationTest.CAPS,MySqlOrderCancelHttpIntegrationTest.RESOURCES,39,()->true);guard.armed=true;
        String deletion="DELETE FROM sales_order WHERE id=? AND SHA2(id,256)=?";
        assertThatThrownBy(()->guard.before(deletion)).isInstanceOf(SQLException.class);guard.cleanup=true;
        for(String bad:List.of("DELETE FROM sales_order","DELETE FROM sales_order WHERE id=?","DELETE FROM product WHERE id=? AND SHA2(id,256)=?"))assertThatThrownBy(()->guard.before(bad)).isInstanceOf(SQLException.class);
        for(int i=0;i<39;i++)guard.before(deletion);
        assertThatThrownBy(()->guard.before(deletion)).isInstanceOf(SQLException.class);assertThat(guard.deletes.get()).isEqualTo(39);
    }
    static String sql(String key){String[] parts=key.split(" ");return parts[0].equals("INSERT")?"INSERT INTO "+parts[1]+"(id) VALUES(?)":"UPDATE "+parts[1]+" SET id=? WHERE id=?";}
}
