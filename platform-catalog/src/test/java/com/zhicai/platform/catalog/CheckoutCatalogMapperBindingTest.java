package com.zhicai.platform.catalog;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Parses actual mapper resources and binds JDBC parameters without opening a database connection. */
class CheckoutCatalogMapperBindingTest {
    private Configuration configuration() throws Exception {
        var configuration=new Configuration();
        try(var input=getClass().getResourceAsStream("/mapper/CheckoutCatalogMapper.xml")){
            new XMLMapperBuilder(input,configuration,"mapper/CheckoutCatalogMapper.xml",configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    private String sql(String id,Map<String,Object> parameters) throws Exception {
        var configuration=configuration();var statement=configuration.getMappedStatement(CheckoutCatalogMapper.class.getName()+"."+id);
        var bound=statement.getBoundSql(parameters);var jdbc=mock(PreparedStatement.class);
        new DefaultParameterHandler(statement,parameters,bound).setParameters(jdbc);
        for(int index=0;index<bound.getParameterMappings().size();index++)verify(jdbc).setString(eq(index+1),anyString());
        return bound.getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void lookupAndQuoteRemainNonlockingButRootsAndCurrentEvidenceUseShareLocks() throws Exception {
        var ids=Map.<String,Object>of("ids",List.of("2","10"));
        assertThat(sql("findEnabledSkus",ids)).doesNotContain("FOR SHARE","FOR UPDATE");
        assertThat(sql("findEnabledSkuProducts",ids)).contains("s.enabled = TRUE","ORDER BY s.id").doesNotContain("FOR SHARE","FOR UPDATE");
        assertThat(sql("lockProduct",Map.of("id","10"))).isEqualTo("SELECT CAST(id AS CHAR) FROM product WHERE id=CAST(? AS UNSIGNED) FOR SHARE");
        assertThat(sql("lockEnabledSkus",ids)).contains("JOIN product","JOIN category","JOIN brand","s.enabled = TRUE","p.state = 'ON_SALE'","c.enabled = TRUE","b.enabled = TRUE").endsWith("ORDER BY s.id FOR SHARE");
    }
    @Test void imageLocksIncludeAssociationAndPublicImmutableAssetWithDeterministicSelectionOrder() throws Exception {
        assertThat(sql("lockPublicImages",Map.of("productIds",List.of("2","10"))))
            .contains("FROM product_asset pa JOIN asset a ON a.id=pa.asset_id","a.visibility='PUBLIC'","a.size_bytes <= 5242880","'image/jpeg','image/png','image/webp'","a.storage_key storageKey","a.content_type contentType","a.size_bytes sizeBytes")
            .endsWith("ORDER BY pa.product_id,pa.sort_order,a.id FOR SHARE");
        var configuration=configuration();
        assertThat(configuration.getMappedStatement(CheckoutCatalogMapper.class.getName()+".lockPublicImages").getResultMaps().getFirst().getType()).isEqualTo(CheckoutCatalogMapper.ImageRow.class);
    }
    @Test void sharedCategoryAnchorSelectsExactlyTheExistingWriterAnchor() throws Exception {
        var configuration=configuration();
        try(var input=getClass().getResourceAsStream("/mapper/CategoryWriteMapper.xml")){
            new XMLMapperBuilder(input,configuration,"mapper/CategoryWriteMapper.xml",configuration.getSqlFragments()).parse();
        }
        var writerSql=configuration.getMappedStatement(CategoryWriteMapper.class.getName()+".lockAnchor")
            .getBoundSql(Map.of()).getSql().replaceAll("\\s+"," ").trim();
        assertThat(writerSql).isEqualTo("SELECT CAST(id AS CHAR) FROM category ORDER BY id LIMIT 1 FOR UPDATE");
        assertThat(sql("lockCategoryAnchor",Map.of())).isEqualTo(writerSql.replace("FOR UPDATE","FOR SHARE"));
    }
}
