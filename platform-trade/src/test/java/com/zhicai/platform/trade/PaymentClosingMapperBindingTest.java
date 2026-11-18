package com.zhicai.platform.trade;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentClosingMapperBindingTest {
    @Test void rootIsLockedByUnsignedIdBeforeFactsAndFactsPartitionEveryAttempt()throws Exception{
        var config=new Configuration();
        try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/PaymentClosingMapper.xml")){
            assertThat(stream).isNotNull();new XMLMapperBuilder(stream,config,"mapper/PaymentClosingMapper.xml",config.getSqlFragments()).parse();
        }
        for(String name:new String[]{"lockOrder","facts"}){
            var params=new ParamNameResolver(config,PaymentClosingMapper.class.getMethod(name,String.class)).getNamedParams(new Object[]{"9223372036854775808"});
            var statement=config.getMappedStatement(PaymentClosingMapper.class.getName()+"."+name);var bound=statement.getBoundSql(params);
            var ps=mock(PreparedStatement.class);new DefaultParameterHandler(statement,params,bound).setParameters(ps);
            verify(ps).setString(1,"9223372036854775808");verifyNoMoreInteractions(ps);
            String sql=bound.getSql().replaceAll("\\s+"," ").trim();assertThat(sql).doesNotContain("${");
            if(name.equals("lockOrder"))assertThat(sql).contains("CAST(id AS CHAR)","FROM sales_order WHERE id=CAST(? AS UNSIGNED)").endsWith("FOR UPDATE");
            else{assertThat(sql).contains("COUNT(*) AS total","COALESCE(SUM(state IN ('CREATED','PROCESSING')),0) AS active","COALESCE(SUM(state='SUCCEEDED'),0) AS succeeded","COALESCE(SUM(state IN ('FAILED','CLOSED')),0) AS terminal","FROM payment WHERE order_id=CAST(? AS UNSIGNED)").doesNotContain("LIMIT","FOR UPDATE");
                assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(PaymentClosingMapper.Facts.class);}
        }
    }
}
