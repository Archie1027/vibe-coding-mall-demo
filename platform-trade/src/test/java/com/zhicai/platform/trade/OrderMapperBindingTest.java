package com.zhicai.platform.trade;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderMapperBindingTest {
    Configuration config(){var config=new Configuration();try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/OrderMapper.xml")){assertThat(stream).isNotNull();new XMLMapperBuilder(stream,config,"mapper/OrderMapper.xml",config.getSqlFragments()).parse();return config;}catch(Exception e){throw new AssertionError(e);}}
    @Test void quoteLockIsOwnerBoundAndHydratesStringIdentities()throws Exception{
        var config=config();var method=OrderMapper.class.getMethod("lockQuote",String.class,String.class);var params=new ParamNameResolver(config,method).getNamedParams(new Object[]{"1","5"});var statement=config.getMappedStatement(OrderMapper.class.getName()+".lockQuote");var bound=statement.getBoundSql(params);assertThat(bound.getSql()).contains("FOR UPDATE","customer_id = CAST(? AS UNSIGNED)","id = CAST(? AS UNSIGNED)","CAST(id AS CHAR)","CAST(customer_id AS CHAR)","CAST(warehouse_id AS CHAR)","CAST(used_order_id AS CHAR)").doesNotContain("${");assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(OrderMapper.LockedQuote.class);var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,params,bound).setParameters(ps);verify(ps).setString(1,"1");verify(ps).setString(2,"5");
    }
    @Test void finalMarkBindsOwnerIdentityAndFreshExpiryCondition()throws Exception{
        var config=config();var at=Instant.parse("2026-10-26T01:02:03.123Z");var method=OrderMapper.class.getMethod("markQuoteUsed",String.class,String.class,String.class,Instant.class);var params=new ParamNameResolver(config,method).getNamedParams(new Object[]{"1","5","10",at});var statement=config.getMappedStatement(OrderMapper.class.getName()+".markQuoteUsed");var bound=statement.getBoundSql(params);assertThat(bound.getSql()).contains("UPDATE checkout_quote","SET used_order_id = CAST(? AS UNSIGNED)","customer_id = CAST(? AS UNSIGNED)","id = CAST(? AS UNSIGNED)","used_order_id IS NULL","expires_at > ?").doesNotContain("${");var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,params,bound).setParameters(ps);verify(ps).setString(1,"10");verify(ps).setString(2,"1");verify(ps).setString(3,"5");verify(ps).setTimestamp(eq(4),any());
    }
    @Test void orderInsertHasLiteralInitialStateAndGeneratedStringKey()throws Exception{
        var config=config();var statement=config.getMappedStatement(OrderMapper.class.getName()+".insertOrder");var at=Instant.parse("2026-10-26T01:02:03.123Z");var row=new OrderMapper.NewOrder("ZCabc","5","1","4","{}","", "200","0","0","200",at.plusSeconds(1800),at);var bound=statement.getBoundSql(row);assertThat(bound.getSql()).contains("INSERT INTO sales_order","'UNPAID'","refunded_minor","version","created_at","updated_at","address_snapshot_json").doesNotContain("${");assertThat(statement.getKeyProperties()).containsExactly("id");assertThat(statement.getKeyGenerator()).isInstanceOf(org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator.class);assertThat(OrderMapper.NewOrder.class.getField("id").getType()).isEqualTo(String.class);var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,row,bound).setParameters(ps);verify(ps).setString(1,"ZCabc");verify(ps).setString(2,"5");verify(ps).setString(3,"1");verify(ps).setString(4,"4");verify(ps).setString(5,"{}");verify(ps).setString(6,"");verify(ps).setString(7,"200");verify(ps,times(3)).setTimestamp(anyInt(),any());
    }
    @Test void itemInsertBindsStableLineOrderSnapshotsAndZeroInitialReturnState()throws Exception{
        var config=config();var statement=config.getMappedStatement(OrderMapper.class.getName()+".insertItem");var row=new OrderMapper.NewItem("10",0,"3","6","商品","[]","{}",2,"100","200","0","200",Instant.parse("2026-10-26T01:02:03.123Z"));var bound=statement.getBoundSql(row);assertThat(bound.getSql()).contains("INSERT INTO sales_order_item","line_no","image_snapshot_json","returned_quantity","refund_quantity_reserved","refunded_minor","version").doesNotContain("${");assertThat(statement.getKeyProperties()).containsExactly("id");assertThat(statement.getKeyGenerator()).isInstanceOf(org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator.class);assertThat(OrderMapper.NewItem.class.getField("id").getType()).isEqualTo(String.class);var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,row,bound).setParameters(ps);verify(ps).setString(1,"10");verify(ps).setInt(2,0);verify(ps).setString(3,"3");verify(ps).setString(4,"6");verify(ps).setString(5,"商品");verify(ps).setString(6,"[]");verify(ps).setString(7,"{}");verify(ps).setInt(8,2);verify(ps).setString(9,"100");verify(ps).setTimestamp(eq(13),any());
    }
    @Test void jdbcGeneratedUnsignedKeysAreAssignedToStringOrderAndItemIdentities()throws Exception{
        var config=config();var at=Instant.parse("2026-10-26T01:02:03.123Z");
        var order=new OrderMapper.NewOrder("ZCabc","5","1","4","{}",null,"200","0","0","200",at.plusSeconds(1800),at);
        var item=new OrderMapper.NewItem("10",0,"3","6","商品","[]","{}",2,"100","200","0","200",at);
        for(var entry:Map.of("insertOrder",order,"insertItem",item).entrySet()){
            var statement=config.getMappedStatement(OrderMapper.class.getName()+"."+entry.getKey());var jdbc=mock(java.sql.Statement.class);var keys=mock(java.sql.ResultSet.class);var metadata=mock(java.sql.ResultSetMetaData.class);
            when(jdbc.getGeneratedKeys()).thenReturn(keys);when(keys.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnType(1)).thenReturn(java.sql.Types.BIGINT);when(keys.next()).thenReturn(true,false);when(keys.getString(1)).thenReturn("9223372036854775808");
            statement.getKeyGenerator().processAfter(null,statement,jdbc,entry.getValue());assertThat(entry.getValue().getClass().getField("id").get(entry.getValue())).isEqualTo("9223372036854775808");verify(keys).close();
        }
    }
}
