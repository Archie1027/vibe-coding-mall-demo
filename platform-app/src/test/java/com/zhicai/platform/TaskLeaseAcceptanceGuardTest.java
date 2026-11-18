package com.zhicai.platform;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline checks cannot authorize the live fixture. */
class TaskLeaseAcceptanceGuardTest {
    @Test void preflightAndMissingApprovalRejectMutationsBeforeDriver() throws Exception {
        var approved=new AtomicBoolean();
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlTaskLeaseIntegrationTest.CAPS,List.of("task_lease"),4,approved::get);
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);
        var statement=guard.connection(raw).createStatement();
        for(boolean armed:List.of(false,true)){
            guard.armed=armed;
            for(String sql:List.of("INSERT INTO task_lease(task_name) VALUES('x')","SELECT * FROM task_lease FOR UPDATE","DELETE FROM task_lease WHERE task_name='x' AND SHA2(owner_token,256)='x'"))
                assertThatThrownBy(()->statement.execute(sql)).isInstanceOf(SQLException.class);
        }
        verifyNoInteractions(target);assertThat(guard.snapshot()).isEmpty();
    }
    @Test void exactTaskBudgetsRejectOtherTablesAndNextAttempt() throws Exception {
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlTaskLeaseIntegrationTest.CAPS,List.of("task_lease"),4,()->true);guard.armed=true;
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);
        var statement=guard.connection(raw).createStatement();
        for(String sql:List.of("INSERT INTO product(id) VALUES(1)","UPDATE account SET enabled=0","CREATE TABLE x(id int)"))
            assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOf(SQLException.class);
        verifyNoInteractions(target);
        for(var cap:MySqlTaskLeaseIntegrationTest.CAPS.entrySet()){
            String sql=cap.getKey().startsWith("INSERT")?"INSERT INTO task_lease(task_name) VALUES(?) ON DUPLICATE KEY UPDATE owner_token=?":"UPDATE task_lease SET lease_until=? WHERE task_name=?";
            for(int i=0;i<cap.getValue();i++)statement.executeUpdate(sql);
            assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOf(SQLException.class);
            verify(target,times(cap.getValue())).executeUpdate(sql);
        }
        assertThat(guard.snapshot()).containsExactlyInAnyOrderEntriesOf(MySqlTaskLeaseIntegrationTest.CAPS);
    }
    @Test void cleanupRequiresTaskIdentityHashAndStopsAtFourRows() throws Exception {
        var guard=new MySqlAcceptanceSupport.SqlGuard(MySqlTaskLeaseIntegrationTest.CAPS,List.of("task_lease"),4,()->true);guard.armed=true;
        String deletion="DELETE FROM task_lease WHERE task_name=? AND owner_token=? AND SHA2(owner_token,256)=?";
        assertThatThrownBy(()->guard.before(deletion)).isInstanceOf(SQLException.class);
        guard.cleanup=true;
        for(String bad:List.of("DELETE FROM task_lease","DELETE FROM task_lease WHERE task_name=?","DELETE FROM product WHERE id=? AND SHA2(id,256)=?"))
            assertThatThrownBy(()->guard.before(bad)).isInstanceOf(SQLException.class);
        for(int i=0;i<4;i++)guard.before(deletion);
        assertThatThrownBy(()->guard.before(deletion)).isInstanceOf(SQLException.class);assertThat(guard.deletes.get()).isEqualTo(4);
    }
}
