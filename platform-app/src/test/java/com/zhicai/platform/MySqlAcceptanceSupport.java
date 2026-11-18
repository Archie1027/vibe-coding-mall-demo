package com.zhicai.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

/** Shared test-only JDBC containment; every fixture must supply its exact policy and approval predicate. */
final class MySqlAcceptanceSupport {
    private MySqlAcceptanceSupport() {}
    /** The reviewed baseline hashes MySQL JSON values, in PRIMARY order, then the concatenated row hashes.
     * Stream the exact 64-byte row hashes instead of GROUP_CONCAT, so no session limit can truncate evidence. */
    static String referenceDigest(JdbcTemplate jdbc,String table,List<String> columns,int expectedCount){
        var primary=jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='PRIMARY' ORDER BY seq_in_index",String.class,table);
        assertThat(primary).isNotEmpty();
        for(String column:columns)assertThat(column).matches("[A-Za-z_][A-Za-z0-9_]*");
        for(String column:primary)assertThat(column).matches("[A-Za-z_][A-Za-z0-9_]*");
        String columnSql=String.join(",",columns.stream().map(c->"`"+c+"`").toList());
        String orderSql=String.join(",",primary.stream().map(c->"`"+c+"`").toList());
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)connection->{
            try(var statement=connection.createStatement()){
                var concatenated=new StringBuilder();int count=0;
                try(var rs=statement.executeQuery("SELECT SHA2(CAST(JSON_ARRAY("+columnSql+") AS CHAR CHARACTER SET utf8mb4),256) FROM `"+table+"` ORDER BY "+orderSql)){
                    while(rs.next()){String hash=rs.getString(1);assertThat(hash).matches("[a-f0-9]{64}");concatenated.append(hash);count++;}
                    assertThat((Object)rs.getWarnings()).as("No truncated row evidence").isNull();
                }
                assertThat((Object)statement.getWarnings()).as("No SQL digest warnings").isNull();
                assertThat(count).as("Digest includes every row of %s",table).isEqualTo(expectedCount);
                return sha(concatenated.toString());
            }
        });
    }
    static String hashSql(List<String> columns){return "SHA2(CONCAT("+String.join(",",columns.stream().map(c->"'"+c+":',IF(`"+c+"` IS NULL,'~',HEX(CAST(`"+c+"` AS BINARY))),'|' ").toList())+"),256)";}
    static List<Map<String,String>> rows(JdbcTemplate jdbc,String table,List<String> columns,boolean lock){return jdbc.query("SELECT *,"+hashSql(columns)+" AS _guard_hash FROM `"+table+"`"+(lock?" FORCE INDEX (PRIMARY) ORDER BY "+(table.equals("product_asset")?"product_id,asset_id":"id")+" FOR UPDATE":""),rs->{var out=new ArrayList<Map<String,String>>();while(rs.next()){var row=new LinkedHashMap<String,String>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));out.add(Collections.unmodifiableMap(row));}out.sort(Comparator.comparing(MySqlAcceptanceSupport::rowDigest));return List.copyOf(out);});}
    static String rowDigest(Map<String,String> row){var b=new StringBuilder();row.forEach((k,v)->b.append(k.length()).append(':').append(k).append(':').append(v==null?-1:v.length()).append(':').append(v==null?"":v).append(';'));return sha(b.toString());}
    static String digest(List<Map<String,String>> rows){return sha(String.join("\n",rows.stream().map(MySqlAcceptanceSupport::rowDigest).sorted().toList()));}
    static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static Instant utcTimestamp(String value){return LocalDateTime.parse(value.replace(' ','T')).toInstant(ZoneOffset.UTC);}
    /** Implements only DataSource: no inherited public target getter or wrapper escape. */
    static final class GuardedDataSource implements DataSource {
        private final DataSource delegate;
        private final SqlGuard guard;
        GuardedDataSource(DataSource delegate,SqlGuard guard){this.delegate=Objects.requireNonNull(delegate);this.guard=guard;}
        @Override public Connection getConnection()throws SQLException{return guard.connection(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return guard.connection(delegate.getConnection(user,password));}
        @Override public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("DataSource unwrap forbidden");}
        @Override public boolean isWrapperFor(Class<?> type){return false;}
        @Override public java.io.PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}
        @Override public void setLogWriter(java.io.PrintWriter writer)throws SQLException{delegate.setLogWriter(writer);}
        @Override public void setLoginTimeout(int seconds)throws SQLException{delegate.setLoginTimeout(seconds);}
        @Override public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}
        @Override public java.util.logging.Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
    }
    static class SqlGuard {
        final Map<String,Integer> caps;
        final Set<String> resources;
        final int maxDeletes;
        private final BooleanSupplier approval;
        SqlGuard(Map<String,Integer> caps,Collection<String> resources,int maxDeletes,BooleanSupplier approval){
            this.caps=Map.copyOf(Objects.requireNonNull(caps));
            this.resources=Set.copyOf(Objects.requireNonNull(resources));
            if(maxDeletes<0||this.caps.values().stream().anyMatch(cap->cap<0))throw new IllegalArgumentException("Nonnegative SQL budgets required");
            this.maxDeletes=maxDeletes;this.approval=Objects.requireNonNull(approval);
        }
        DataSource dataSource(DataSource delegate){return new GuardedDataSource(delegate,this);}
        void beforeExecution(String sql,String kind)throws SQLException {}
        void afterExecution(String sql,String kind,Object value)throws SQLException {}
        // The real driver statement supplies affected rows for MyBatis execute()'s Boolean return.
        void afterExecution(String sql,String kind,Object value,Statement statement)throws SQLException {afterExecution(sql,kind,value);}
        final Map<String,Integer> attempts=new LinkedHashMap<>();final AtomicInteger locks=new AtomicInteger(),executions=new AtomicInteger(),deletes=new AtomicInteger();
        volatile boolean armed,cleanup;
        synchronized Map<String,Integer> snapshot(){return Map.copyOf(attempts);}
        boolean approved(){return approval.getAsBoolean();}
        void beforeUnchecked(String sql){try{before(sql);}catch(SQLException e){throw new IllegalStateException(e);}}
        synchronized String before(String sql)throws SQLException {
            String normalized=sql.replaceAll("\\s+"," ").trim().replace("`","");String upper=normalized.toUpperCase(Locale.ROOT);
            if(upper.contains(";")||upper.contains("/*")||upper.contains("--")||upper.contains("#"))throw new SQLException("Multiple/comment SQL rejected before delegate");
            if(upper.matches(".*\\bINTO\\b.*")&&upper.startsWith("SELECT ")||upper.matches(".*\\b(GET_LOCK|RELEASE_LOCK|SLEEP)\\s*\\(.*"))throw new SQLException("Side-effect SELECT rejected before delegate");
            boolean lock=upper.matches(".*\\bFOR (UPDATE|SHARE)\\b.*")||upper.contains("LOCK IN SHARE MODE");
            if(upper.startsWith("SELECT ")){if(lock){if(!armed||!approved())throw new SQLException("Locking SELECT forbidden in read-only preflight");locks.incrementAndGet();}executions.incrementAndGet();return "SELECT";}
            if(upper.equals("SET TIME_ZONE = '+00:00'")||upper.equals("SET TIME_ZONE='+00:00'")){executions.incrementAndGet();return "SET";}
            var matcher=Pattern.compile("^(INSERT INTO|UPDATE|DELETE FROM) ([a-z_]+)(?:[ (]|$)",Pattern.CASE_INSENSITIVE).matcher(normalized);
            if(!matcher.find()||!armed||!approved())throw new SQLException("Unapproved/unknown mutation blocked before SQL delegate");
            String verb=matcher.group(1).toUpperCase(Locale.ROOT).split(" ")[0],table=matcher.group(2).toLowerCase(Locale.ROOT),key=verb+" "+table;
            if(verb.equals("DELETE")){if(!cleanup||!resources.contains(table)||!upper.contains("SHA2(")||!upper.contains(" WHERE ")||deletes.get()>=maxDeletes)throw new SQLException("Unapproved cleanup DELETE before delegate");deletes.incrementAndGet();}
            else {Integer cap=caps.get(key);if(cleanup||cap==null||attempts.getOrDefault(key,0)>=cap)throw new SQLException("Mutation attempt cap exceeded or unknown mutation before delegate");attempts.merge(key,1,Integer::sum);}
            executions.incrementAndGet();return key;
        }
        Connection connection(Connection raw){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,m,args)->{
            String name=m.getName();if(name.equals("unwrap"))throw new SQLException("Unwrap forbidden by fixture guard");if(name.equals("isWrapperFor"))return false;
            if(name.equals("prepareCall"))throw new SQLException("Callable SQL forbidden");
            if(name.equals("prepareStatement")){var target=(PreparedStatement)invoke(raw,m,args);return statement(target,(String)args[0],(Connection)proxy);}
            if(name.equals("createStatement"))return statement((Statement)invoke(raw,m,args),null,(Connection)proxy);
            return guardedValue(invoke(raw,m,args),(Connection)proxy,null);
        });}
        Object guardedValue(Object value,Connection connection,Statement owner){
            if(value instanceof ResultSet result)return resultSet(result,connection,owner);
            if(value instanceof DatabaseMetaData metadata)return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DatabaseMetaData.class},(proxy,m,args)->{
                if(m.getName().equals("unwrap"))throw new SQLException("Metadata unwrap forbidden");if(m.getName().equals("isWrapperFor"))return false;
                if(m.getName().equals("getConnection"))return connection;
                return guardedValue(invoke(metadata,m,args),connection,null);
            });
            Class<?> metadataType=value instanceof ResultSetMetaData?ResultSetMetaData.class:value instanceof ParameterMetaData?ParameterMetaData.class:null;
            if(metadataType!=null)return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{metadataType},(proxy,m,args)->{
                if(m.getName().equals("unwrap"))throw new SQLException("JDBC metadata unwrap forbidden");if(m.getName().equals("isWrapperFor"))return false;
                return guardedValue(invoke(value,m,args),connection,owner);
            });
            return value;
        }
        Object resultSet(ResultSet target,Connection connection,Statement owner){return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(proxy,m,args)->{
            String name=m.getName();if(name.equals("unwrap"))throw new SQLException("ResultSet unwrap forbidden");if(name.equals("isWrapperFor"))return false;
            if(name.equals("getStatement")){if(owner!=null)return owner;var statement=target.getStatement();return statement==null?null:statement(statement,null,connection);}
            // Updatable ResultSet methods can mutate without executing SQL through Statement.
            if(name.startsWith("update")||Set.of("insertRow","deleteRow","moveToInsertRow").contains(name))throw new SQLException("Updatable ResultSet forbidden");
            return guardedValue(invoke(target,m,args),connection,owner);
        });}
        Object statement(Statement target,String prepared,Connection connection){Class<?> type=prepared==null?Statement.class:PreparedStatement.class;
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{type},(proxy,m,args)->{
                String name=m.getName();if(name.equals("unwrap"))throw new SQLException("Unwrap forbidden");if(name.equals("isWrapperFor"))return false;if(name.equals("getConnection"))return connection;
                if(name.contains("Batch"))throw new SQLException("Batch SQL forbidden before delegate");
                if(name.startsWith("execute")){
                    String sql=prepared==null||args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:prepared;String kind=before(sql);
                    beforeExecution(sql,kind);
                    Object value=invoke(target,m,args);
                    afterExecution(sql,kind,value,target);
                    return guardedValue(value,connection,(Statement)proxy);
                }return guardedValue(invoke(target,m,args),connection,(Statement)proxy);
            });
        }
        static Object invoke(Object target,Method method,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    }
}
