package com.zhicai.platform.trade;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderClosingMapperBindingTest {
    final Instant at=Instant.parse("2026-10-27T01:02:03.123Z");
    Configuration config(){var config=new Configuration();try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/OrderClosingMapper.xml")){assertThat(stream).isNotNull();new XMLMapperBuilder(stream,config,"mapper/OrderClosingMapper.xml",config.getSqlFragments()).parse();return config;}catch(Exception e){throw new AssertionError(e);}}
    record Binding(String sql,PreparedStatement ps,org.apache.ibatis.mapping.MappedStatement statement){}
    Binding bind(String name,Class<?>[] types,Object... args)throws Exception{
        var config=config();var params=new ParamNameResolver(config,OrderClosingMapper.class.getMethod(name,types)).getNamedParams(args);var statement=config.getMappedStatement(OrderClosingMapper.class.getName()+"."+name);var bound=statement.getBoundSql(params);var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,params,bound).setParameters(ps);return new Binding(bound.getSql().replaceAll("\\s+"," ").trim(),ps,statement);
    }
    @Test void ownerLockBindsStringIdsAndFullMetadata()throws Exception{
        var b=bind("lockOwned",new Class<?>[]{String.class,String.class},"1","9223372036854775808");
        assertThat(b.sql()).contains("CAST(id AS CHAR)","CAST(customer_id AS CHAR)","CAST(warehouse_id AS CHAR)","CAST(coupon_id AS CHAR)","expires_at","cancelled_at","version","FROM sales_order","customer_id = CAST(? AS UNSIGNED)","id = CAST(? AS UNSIGNED)").endsWith("FOR UPDATE").doesNotContain("${");
        assertThat(b.statement().getResultMaps().getFirst().getType()).isEqualTo(OrderClosingMapper.Locked.class);verify(b.ps()).setString(1,"1");verify(b.ps()).setString(2,"9223372036854775808");
    }
    @Test void internalLockUsesOnlyIdAndLocksOrder()throws Exception{
        var b=bind("lockAny",new Class<?>[]{String.class},"5");assertThat(b.sql()).contains("FROM sales_order WHERE id = CAST(? AS UNSIGNED)").endsWith("FOR UPDATE");verify(b.ps()).setString(1,"5");verifyNoMoreInteractions(b.ps());
    }
    @Test void itemsReadAllUpToOverflowSentinelInStableLineOrder()throws Exception{
        var b=bind("items",new Class<?>[]{String.class},"5");assertThat(b.sql()).contains("CAST(id AS CHAR)","CAST(order_id AS CHAR)","CAST(sku_id AS CHAR)","quantity","line_no","order_id = CAST(? AS UNSIGNED)","ORDER BY line_no,id LIMIT 101").doesNotContain("FOR UPDATE","FOR SHARE","${");assertThat(b.statement().getResultMaps().getFirst().getType()).isEqualTo(OrderClosingMapper.Item.class);verify(b.ps()).setString(1,"5");
    }
    @Test void conditionalCancelBindsOwnerVersionAndUtcTimestamp()throws Exception{
        var b=bind("markCancelled",new Class<?>[]{String.class,String.class,long.class,Instant.class,boolean.class},"1","5",7L,at,false);
        assertThat(b.sql()).contains("UPDATE sales_order SET state='CANCELLED',cancelled_at=?,updated_at=?,version=version+1","customer_id = CAST(? AS UNSIGNED)","id = CAST(? AS UNSIGNED)","state='UNPAID'","coupon_id IS NULL","cancelled_at IS NULL","version=?").doesNotContain("expires_at <=","${");
        verify(b.ps()).setTimestamp(1,Timestamp.from(at));verify(b.ps()).setTimestamp(2,Timestamp.from(at));verify(b.ps()).setString(3,"1");verify(b.ps()).setString(4,"5");verify(b.ps()).setLong(5,7L);verifyNoMoreInteractions(b.ps());
    }
    @Test void expiredConditionalMarkAddsLockedExpiryBoundary()throws Exception{
        var b=bind("markCancelled",new Class<?>[]{String.class,String.class,long.class,Instant.class,boolean.class},"1","5",7L,at,true);assertThat(b.sql()).contains("expires_at <= ?");verify(b.ps()).setTimestamp(6,Timestamp.from(at));
    }
    @Test void candidateScanWithoutCursorIsBoundedOrderedAndUnlocked()throws Exception{
        var b=bind("expired",new Class<?>[]{Instant.class,Instant.class,String.class,int.class},at,null,null,100);assertThat(b.sql()).contains("CAST(id AS CHAR)","expires_at AS expiresAt","FROM sales_order WHERE state='UNPAID' AND expires_at <= ?","ORDER BY expires_at,id LIMIT ?").doesNotContain("FOR UPDATE","FOR SHARE","id >","${");assertThat(b.statement().getResultMaps().getFirst().getType()).isEqualTo(OrderClosingService.Candidate.class);verify(b.ps()).setTimestamp(1,Timestamp.from(at));verify(b.ps()).setInt(2,100);verifyNoMoreInteractions(b.ps());
    }
    @Test void cursorUsesStrictKeysetAcrossEqualExpiryAndUnsignedStringId()throws Exception{
        var after=at.minusSeconds(1);var b=bind("expired",new Class<?>[]{Instant.class,Instant.class,String.class,int.class},at,after,"9223372036854775808",1);assertThat(b.sql()).contains("AND (expires_at > ? OR (expires_at = ? AND id > CAST(? AS UNSIGNED)))","ORDER BY expires_at,id LIMIT ?").doesNotContain("FOR UPDATE","FOR SHARE","OFFSET","${");verify(b.ps()).setTimestamp(1,Timestamp.from(at));verify(b.ps()).setTimestamp(2,Timestamp.from(after));verify(b.ps()).setTimestamp(3,Timestamp.from(after));verify(b.ps()).setString(4,"9223372036854775808");verify(b.ps()).setInt(5,1);verifyNoMoreInteractions(b.ps());
    }
    @Test void closingMarksKeepOwnerVersionAndNeverSetCancelledTime()throws Exception{
        var b=bind("markClosing",new Class<?>[]{String.class,String.class,long.class,Instant.class,boolean.class},"1","5",7L,at,true);
        assertThat(b.sql()).contains("SET state='CLOSING',updated_at=?,version=version+1","state='UNPAID'","coupon_id IS NULL","cancelled_at IS NULL","version=?","expires_at <= ?").doesNotContain("cancelled_at=?");
        verify(b.ps()).setTimestamp(1,Timestamp.from(at));verify(b.ps()).setString(2,"1");verify(b.ps()).setString(3,"5");verify(b.ps()).setLong(4,7L);verify(b.ps()).setTimestamp(5,Timestamp.from(at));
    }
    @Test void finalClosingMarkCannotCancelAnotherState()throws Exception{
        var b=bind("markClosingCancelled",new Class<?>[]{String.class,String.class,long.class,Instant.class},"1","5",7L,at);
        assertThat(b.sql()).contains("SET state='CANCELLED',cancelled_at=?,updated_at=?,version=version+1","state='CLOSING'","customer_id = CAST(? AS UNSIGNED)","id = CAST(? AS UNSIGNED)","coupon_id IS NULL","version=?").doesNotContain("state='UNPAID'","expires_at <=");
        verify(b.ps()).setTimestamp(1,Timestamp.from(at));verify(b.ps()).setTimestamp(2,Timestamp.from(at));verify(b.ps()).setString(3,"1");verify(b.ps()).setString(4,"5");verify(b.ps()).setLong(5,7L);
    }
}
