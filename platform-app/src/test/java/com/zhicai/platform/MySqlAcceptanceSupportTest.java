package com.zhicai.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Database-free contract tests: approval here cannot arm a live fixture. */
class MySqlAcceptanceSupportTest {
    enum Profile { CREATE, CLOSING }
    enum Escape { METADATA_CONNECTION, QUERY_RESULT, CURRENT_RESULT, GENERATED_KEYS, METADATA_RESULT }
    static final Map<String,Integer> CLOSING=Map.ofEntries(
        Map.entry("INSERT product",1),Map.entry("INSERT sku",2),Map.entry("INSERT product_asset",1),
        Map.entry("INSERT customer_address",1),Map.entry("INSERT stock_balance",2),Map.entry("INSERT checkout_quote",2),
        Map.entry("INSERT sales_order",2),Map.entry("INSERT sales_order_item",4),Map.entry("INSERT stock_reservation",4),
        Map.entry("INSERT stock_ledger",10),Map.entry("UPDATE checkout_quote",2),Map.entry("UPDATE stock_balance",10),
        Map.entry("UPDATE stock_reservation",6),Map.entry("UPDATE sales_order",3));
    static Map<String,Integer> caps(Profile profile){return profile==Profile.CREATE?MySqlOrderCreateIntegrationTest.SqlGuard.CAPS:CLOSING;}
    static int deleteCap(Profile profile){return profile==Profile.CREATE?17:27;}
    static MySqlAcceptanceSupport.SqlGuard guard(Profile profile,boolean approved){
        return new MySqlAcceptanceSupport.SqlGuard(caps(profile),MySqlOrderCreateIntegrationTest.RESOURCES,deleteCap(profile),()->approved);
    }
    static String sql(String key){String[] parts=key.split(" ");return parts[0].equals("INSERT")?"INSERT INTO "+parts[1]+"(id) VALUES(?)":"UPDATE "+parts[1]+" SET id=? WHERE id=?";}

    @ParameterizedTest @EnumSource(Profile.class)
    void eachBudgetRejectsTheNextAttemptBeforeJdbc(Profile profile)throws Exception {
        var guard=guard(profile,true);guard.armed=true;
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);
        var statement=guard.connection(raw).createStatement();
        for(var cap:caps(profile).entrySet()){
            for(int i=0;i<cap.getValue();i++)statement.executeUpdate(sql(cap.getKey()));
            assertThatThrownBy(()->statement.executeUpdate(sql(cap.getKey()))).isInstanceOf(SQLException.class);
            verify(target,times(cap.getValue())).executeUpdate(sql(cap.getKey()));
        }
        assertThat(guard.snapshot()).containsExactlyInAnyOrderEntriesOf(caps(profile));
        assertThat(guard.attempts.entrySet().stream().filter(e->e.getKey().startsWith("INSERT ")).mapToInt(Map.Entry::getValue).sum()).isEqualTo(profile==Profile.CREATE?24:29);
        assertThat(guard.attempts.entrySet().stream().filter(e->e.getKey().startsWith("UPDATE ")).mapToInt(Map.Entry::getValue).sum()).isEqualTo(profile==Profile.CREATE?7:21);
    }
    @ParameterizedTest @EnumSource(Profile.class)
    void cleanupRequiresApprovalResourceHashAndExactDeleteLimit(Profile profile)throws Exception {
        var approval=new AtomicBoolean();
        var guard=new MySqlAcceptanceSupport.SqlGuard(caps(profile),List.of("product"),deleteCap(profile),approval::get);
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);
        var statement=guard.connection(raw).createStatement();
        String deletion="DELETE FROM product WHERE id=? AND SHA2(CAST(id AS BINARY),256)=?";
        assertThatThrownBy(()->statement.executeUpdate(deletion)).isInstanceOf(SQLException.class);
        guard.armed=true;guard.cleanup=true;
        assertThatThrownBy(()->statement.executeUpdate(deletion)).isInstanceOf(SQLException.class);
        approval.set(true);guard.cleanup=false;
        assertThatThrownBy(()->statement.executeUpdate(deletion)).isInstanceOf(SQLException.class);
        guard.cleanup=true;
        for(String bad:List.of("DELETE FROM sku WHERE id=? AND SHA2(id,256)=?","DELETE FROM product WHERE id=?","DELETE FROM product"))
            assertThatThrownBy(()->statement.executeUpdate(bad)).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->statement.executeUpdate("INSERT INTO product(id) VALUES(?)")).isInstanceOf(SQLException.class);
        assertThat(guard.deletes.get()).isZero();verifyNoInteractions(target);
        for(int i=0;i<deleteCap(profile);i++)statement.executeUpdate(deletion);
        assertThatThrownBy(()->statement.executeUpdate(deletion)).isInstanceOf(SQLException.class);
        verify(target,times(deleteCap(profile))).executeUpdate(deletion);assertThat(guard.deletes.get()).isEqualTo(deleteCap(profile));
    }
    @ParameterizedTest @EnumSource(Profile.class)
    void readonlyAndUnknownSqlRejectBeforeDelegate(Profile profile)throws Exception {
        var guard=guard(profile,false);guard.armed=true;
        var raw=mock(Connection.class);var target=mock(Statement.class);when(raw.createStatement()).thenReturn(target);
        var statement=guard.connection(raw).createStatement();
        for(String bad:List.of("INSERT INTO product(id) VALUES(?)","SELECT * FROM product FOR UPDATE","SELECT * FROM product FOR SHARE","SELECT * FROM product LOCK IN SHARE MODE","CREATE TABLE x(id int)","CALL x()","SELECT 1; DELETE FROM product","/* skip */ SELECT 1","SELECT 1 INTO OUTFILE '/tmp/forbidden'","WITH x AS (SELECT 1) DELETE FROM product","SELECT GET_LOCK('x',1)"))
            assertThatThrownBy(()->statement.execute(bad)).isInstanceOf(SQLException.class);
        verifyNoInteractions(target);assertThat(guard.snapshot()).isEmpty();assertThat(guard.locks.get()).isZero();
        var approved=guard(profile,true);approved.armed=true;
        assertThatThrownBy(()->approved.before("UPDATE account SET enabled=0")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->approved.before("INSERT INTO unknown_table(id) VALUES(1)")).isInstanceOf(SQLException.class);
        assertThat(approved.snapshot()).isEmpty();
    }
    @ParameterizedTest @EnumSource(Escape.class)
    void everyOwnerGraphPathContainsReadonlyAndCappedWrites(Escape escape)throws Exception {
        for(Profile profile:Profile.values())for(boolean capped:new boolean[]{false,true}){
            var raw=mock(Connection.class);var target=mock(Statement.class);var metadata=mock(DatabaseMetaData.class);var result=mock(ResultSet.class);
            when(raw.createStatement()).thenReturn(target);when(raw.getMetaData()).thenReturn(metadata);when(metadata.getConnection()).thenReturn(raw);
            when(metadata.getTables(null,null,null,null)).thenReturn(result);when(target.executeQuery("SELECT 1")).thenReturn(result);
            when(target.getResultSet()).thenReturn(result);when(target.getGeneratedKeys()).thenReturn(result);when(result.getStatement()).thenReturn(target);
            var guard=guard(profile,capped);guard.armed=capped;if(capped)guard.before("INSERT INTO product(id) VALUES(?)");
            var connection=guard.connection(raw);var statement=connection.createStatement();
            Statement reached=switch(escape){
                case METADATA_CONNECTION->connection.getMetaData().getConnection().createStatement();
                case QUERY_RESULT->statement.executeQuery("SELECT 1").getStatement();
                case CURRENT_RESULT->statement.getResultSet().getStatement();
                case GENERATED_KEYS->statement.getGeneratedKeys().getStatement();
                case METADATA_RESULT->connection.getMetaData().getTables(null,null,null,null).getStatement();
            };
            assertThatThrownBy(()->reached.executeUpdate("INSERT INTO product(id) VALUES(?)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(reached::executeBatch).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->reached.getConnection().unwrap(Connection.class)).isInstanceOf(SQLException.class);
            verify(target,never()).executeUpdate(anyString());verify(target,never()).executeBatch();
        }
    }
    @Test void preparedCallableBatchMetadataAndResultUpdatesAreContained()throws Exception {
        var raw=mock(Connection.class);var target=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        var resultMetadata=mock(ResultSetMetaData.class);var parameterMetadata=mock(ParameterMetaData.class);
        when(raw.prepareStatement("SELECT 1")).thenReturn(target);when(target.executeQuery()).thenReturn(result);
        when(result.getMetaData()).thenReturn(resultMetadata);when(target.getParameterMetaData()).thenReturn(parameterMetadata);
        var connection=guard(Profile.CLOSING,false).connection(raw);var statement=connection.prepareStatement("SELECT 1");var rs=statement.executeQuery();
        assertThat(statement.getConnection()).isSameAs(connection);assertThat(rs.getStatement()).isSameAs(statement);
        assertThatThrownBy(()->connection.prepareCall("CALL x()")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->statement.addBatch()).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->statement.executeLargeBatch()).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->statement.executeUpdate("INSERT INTO product(id) VALUES(1)")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->rs.updateString(1,"bad")).isInstanceOf(SQLException.class);
        assertThatThrownBy(rs::insertRow).isInstanceOf(SQLException.class);assertThatThrownBy(rs::deleteRow).isInstanceOf(SQLException.class);
        assertThatThrownBy(rs::moveToInsertRow).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->rs.getMetaData().unwrap(ResultSetMetaData.class)).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->statement.getParameterMetaData().unwrap(ParameterMetaData.class)).isInstanceOf(SQLException.class);
        verify(raw,never()).prepareCall(anyString());verify(target,never()).addBatch();verify(target,never()).executeLargeBatch();
        verify(result,never()).updateString(anyInt(),anyString());verify(result,never()).insertRow();verify(result,never()).deleteRow();
    }
    @Test void datasourceHasNoPublicRawTargetAndBothConnectionOverloadsAreGuarded()throws Exception {
        var raw=mock(DataSource.class);var connection=mock(Connection.class);when(raw.getConnection()).thenReturn(connection);when(raw.getConnection("u","p")).thenReturn(connection);
        var guarded=guard(Profile.CLOSING,false).dataSource(raw);
        assertThat(Arrays.stream(guarded.getClass().getMethods()).map(Method::getName)).doesNotContain("getTargetDataSource");
        assertThatThrownBy(()->guarded.unwrap(DataSource.class)).isInstanceOf(SQLException.class);assertThat(guarded.isWrapperFor(DataSource.class)).isFalse();
        assertThatThrownBy(()->guarded.getConnection().unwrap(Connection.class)).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->guarded.getConnection("u","p").unwrap(Connection.class)).isInstanceOf(SQLException.class);
        verify(raw,never()).unwrap(any());verify(connection,never()).unwrap(any());
    }
    @Test void hookInjectsOnlyAfterActualFinalUpdateAndPropagatesDriverFailures()throws Exception {
        var delegated=new AtomicBoolean();var callbacks=new AtomicInteger();var failOnce=new AtomicBoolean(true);
        var guard=new MySqlAcceptanceSupport.SqlGuard(CLOSING,List.of("sales_order"),27,()->true){
            @Override void afterExecution(String sql,String kind,Object value)throws SQLException {
                assertThat(delegated.get()).isTrue();callbacks.incrementAndGet();
                if(kind.equals("UPDATE sales_order")&&failOnce.compareAndSet(true,false))throw new SQLException("injected after delegate","45000");
            }
        };guard.armed=true;
        var raw=mock(Connection.class);var target=mock(PreparedStatement.class);String sql="UPDATE sales_order SET state='CANCELLED' WHERE id=?";
        when(raw.prepareStatement(sql)).thenReturn(target);when(target.executeUpdate()).thenAnswer(i->{delegated.set(true);return 1;});
        var statement=guard.connection(raw).prepareStatement(sql);
        assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class).hasMessage("injected after delegate");
        assertThat(guard.snapshot()).containsEntry("UPDATE sales_order",1);assertThat(callbacks.get()).isEqualTo(1);
        var failure=new SQLException("driver failure");when(target.executeUpdate()).thenThrow(failure);
        assertThatThrownBy(statement::executeUpdate).isSameAs(failure);assertThat(callbacks.get()).isEqualTo(1);
        assertThat(guard.snapshot()).containsEntry("UPDATE sales_order",2);
    }
    @Test void constructorCopiesPolicyAndRequiresExplicitValidInputs()throws Exception {
        var caps=new LinkedHashMap<>(CLOSING);var resources=new ArrayList<>(List.of("product"));
        var guard=new MySqlAcceptanceSupport.SqlGuard(caps,resources,1,()->true);caps.clear();resources.clear();guard.armed=true;
        guard.before("INSERT INTO product(id) VALUES(?)");guard.cleanup=true;guard.before("DELETE FROM product WHERE id=? AND SHA2(id,256)=?");
        assertThatThrownBy(()->new MySqlAcceptanceSupport.SqlGuard(null,List.of("product"),1,()->true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->new MySqlAcceptanceSupport.SqlGuard(CLOSING,List.of("product"),-1,()->true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MySqlAcceptanceSupport.SqlGuard(CLOSING,List.of("product"),1,null)).isInstanceOf(NullPointerException.class);
    }
    @Test void preparedExecuteCallbackReadsActualDriverUpdateCount()throws Exception {
        var callbacks=new AtomicInteger();var target=mock(PreparedStatement.class);var raw=mock(Connection.class);
        String sql="UPDATE sales_order SET state='CANCELLED' WHERE id=?";
        when(raw.prepareStatement(sql)).thenReturn(target);when(target.execute()).thenReturn(false);when(target.getUpdateCount()).thenReturn(1);
        var guard=new MySqlAcceptanceSupport.SqlGuard(CLOSING,List.of("sales_order"),27,()->true){
            @Override void afterExecution(String text,String kind,Object value,Statement statement)throws SQLException {
                assertThat(statement).isSameAs(target);assertThat(value).isEqualTo(false);assertThat(statement.getUpdateCount()).isEqualTo(1);
                callbacks.incrementAndGet();throw new SQLException("controlled after execute and count","45000");
            }
        };guard.armed=true;
        var statement=guard.connection(raw).prepareStatement(sql);
        assertThatThrownBy(statement::execute).isInstanceOf(SQLException.class).hasMessage("controlled after execute and count");
        var order=inOrder(target);order.verify(target).execute();order.verify(target).getUpdateCount();
        assertThat(callbacks.get()).isEqualTo(1);assertThat(guard.snapshot()).containsEntry("UPDATE sales_order",1);
    }
    @Test void preparedExecuteDriverExceptionNeverCallsStatementAwareHook()throws Exception {
        var callbacks=new AtomicInteger();var target=mock(PreparedStatement.class);var raw=mock(Connection.class);
        String sql="UPDATE sales_order SET state='CANCELLED' WHERE id=?";var failure=new SQLException("driver execute failed");
        when(raw.prepareStatement(sql)).thenReturn(target);when(target.execute()).thenThrow(failure);
        var guard=new MySqlAcceptanceSupport.SqlGuard(CLOSING,List.of("sales_order"),27,()->true){
            @Override void afterExecution(String text,String kind,Object value,Statement statement){callbacks.incrementAndGet();}
        };guard.armed=true;
        var statement=guard.connection(raw).prepareStatement(sql);
        assertThatThrownBy(statement::execute).isSameAs(failure);assertThat(callbacks.get()).isZero();verify(target,never()).getUpdateCount();
        assertThat(guard.snapshot()).containsEntry("UPDATE sales_order",1);
    }
    @Test void rowHashesAndUtcRetainReviewedEncoding(){
        var row=new LinkedHashMap<String,String>();row.put("id","1");row.put("nullable",null);row.put("empty","");
        assertThat(MySqlAcceptanceSupport.rowDigest(row)).isEqualTo(MySqlAcceptanceSupport.sha("2:id:1:1;8:nullable:-1:;5:empty:0:;"));
        assertThat(MySqlAcceptanceSupport.digest(List.of(row))).isEqualTo(MySqlAcceptanceSupport.sha(MySqlAcceptanceSupport.rowDigest(row)));
        assertThat(MySqlAcceptanceSupport.hashSql(List.of("id","value"))).isEqualTo("SHA2(CONCAT('id:',IF(`id` IS NULL,'~',HEX(CAST(`id` AS BINARY))),'|' ,'value:',IF(`value` IS NULL,'~',HEX(CAST(`value` AS BINARY))),'|' ),256)");
        assertThat(MySqlAcceptanceSupport.utcTimestamp("2026-10-26 11:04:09.123")).isEqualTo(Instant.parse("2026-10-26T11:04:09.123Z"));
    }
}
