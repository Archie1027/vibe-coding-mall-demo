package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CheckoutCatalogMapper;
import com.zhicai.platform.inventory.InventoryAvailabilityMapper;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteMapperBindingTest {
    Configuration configuration(String resource){
        var config=new Configuration();
        try(var stream=getClass().getClassLoader().getResourceAsStream(resource)){assertThat(stream).isNotNull();new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();return config;}
        catch(Exception e){throw new AssertionError(e);}
    }
    @Test void insertBindsOnlyValidatedQuoteColumnsAndGeneratedStringIdentity() throws Exception {
        var config=configuration("mapper/QuoteMapper.xml");var statement=config.getMappedStatement(QuoteMapper.class.getName()+".insert");
        var row=new QuoteMapper.NewQuote("1","4","{\"formatVersion\":1}",Instant.parse("2026-10-25T01:07:03.123Z"),Instant.parse("2026-10-25T01:02:03.123Z"));
        var bound=statement.getBoundSql(row);assertThat(bound.getSql()).contains("INSERT INTO checkout_quote","created_at").doesNotContain("used_order_id");
        assertThat(statement.getKeyProperties()).containsExactly("id");assertThat(QuoteMapper.NewQuote.class.getField("id").getType()).isEqualTo(String.class);
        var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,row,bound).setParameters(ps);
        verify(ps).setString(1,"1");verify(ps).setString(2,"4");verify(ps).setString(3,row.snapshotJson);verify(ps,times(2)).setTimestamp(anyInt(),any());
    }
    @Test void catalogSqlIsBatchCurrentEnabledSnapshotWithFourVersionColumns() throws Exception {
        var config=configuration("mapper/CheckoutCatalogMapper.xml");var statement=config.getMappedStatement(CheckoutCatalogMapper.class.getName()+".findEnabledSkus");
        var parameters=Map.of("ids",List.of("3","9"));var bound=statement.getBoundSql(parameters);var sql=bound.getSql();
        assertThat(sql).contains("s.enabled = TRUE","p.state = 'ON_SALE'","c.enabled = TRUE","b.enabled = TRUE","skuVersion","productVersion","categoryVersion","brandVersion").doesNotContain("FOR UPDATE","FOR SHARE","${");
        var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,parameters,bound).setParameters(ps);verify(ps).setString(1,"3");verify(ps).setString(2,"9");
    }
    @Test void inventorySqlHasOnlyBatchSelectsWithNoLocksOrMutations() throws Exception {
        var config=configuration("mapper/InventoryAvailabilityMapper.xml");
        assertThat(config.getMappedStatementNames().stream().filter(n->n.startsWith(InventoryAvailabilityMapper.class.getName()+".")).toList()).hasSize(2);
        var statement=config.getMappedStatement(InventoryAvailabilityMapper.class.getName()+".balances");
        var parameters=Map.of("warehouseIds",List.of("4","5"),"skuIds",List.of("3","9"));var bound=statement.getBoundSql(parameters);
        assertThat(bound.getSql()).contains("warehouse_id IN","sku_id IN").doesNotContain("FOR UPDATE","FOR SHARE","UPDATE ","INSERT ","DELETE ","${");
        var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,parameters,bound).setParameters(ps);
        verify(ps).setString(1,"4");verify(ps).setString(2,"5");verify(ps).setString(3,"3");verify(ps).setString(4,"9");
    }
}
