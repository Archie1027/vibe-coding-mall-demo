package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.inventory.InventoryReservationService;
import com.zhicai.platform.inventory.InventoryReservationService.Command;
import com.zhicai.platform.inventory.InventoryReservationService.Line;
import com.zhicai.platform.inventory.StockMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** SQL writes require a new explicit batch approval. Failure deliberately retains all fixture evidence. */
@EnabledIfEnvironmentVariable(named="M1_STOCK_RESERVATION_INTEGRATION",matches="read-only-stock-startup|approved-temporary-stock-reservation")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.flyway.enabled=false","spring.sql.init.mode=never","app.dev-bootstrap.enabled=false",
    "app.catalog.writes-enabled=false","app.customer.writes-enabled=false"})
@Import(MySqlInventoryReservationIntegrationTest.FixtureConfiguration.class)
class MySqlInventoryReservationIntegrationTest {
    @DynamicPropertySource static void localDatabase(DynamicPropertyRegistry properties){
        String url=System.getenv("DB_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/zhicai_dev(?:\\?.*)?"))throw new IllegalStateException("Only local zhicai_dev is permitted");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        properties.add("spring.flyway.enabled",()->false);
    }
    static final List<String> ORIGINAL=List.of("account","role","permission","account_role","role_permission","warehouse","account_warehouse","category","brand","asset","product","sku","product_asset","flyway_schema_history");
    static final List<String> STOCK=List.of("stock_balance","stock_reservation","stock_ledger");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired InventoryReservationService inventory;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionFactory sessions;
    @Autowired WriteBudget budget;
    final String run=UUID.randomUUID().toString(),trace="m1d-"+run;
    final long referenceBase=100_000_000_000_000_000L+new SecureRandom().nextLong(100_000_000_000_000_000L);
    final Map<String,String> baseline=new LinkedHashMap<>();
    final Map<String,Command> ownedLines=new LinkedHashMap<>();
    final Set<String> traces=new HashSet<>();
    final List<String> checks=new ArrayList<>();
    Path directory,manifest;
    String warehouse1,warehouse2,sku1,sku2,actor;
    List<Map<String,Object>> seeds;

    @Test
    @EnabledIfEnvironmentVariable(named="M1_STOCK_RESERVATION_INTEGRATION",matches="read-only-stock-startup")
    void readOnlyStartupAndBaseline() throws Exception {
        prepare();
        for(String name:List.of("lockWarehouse","lockBalance","lockReservation","reserveEvidence","increaseReserved","decreaseReserved","insertReservation","markReleased","insertLedger"))
            assertThat(sessions.getConfiguration().hasStatement("com.zhicai.platform.inventory.StockMapper."+name)).isTrue();
        var command=command(900,warehouse1,List.of(line(901,sku1,1)),actor,"readonly");
        assertThatThrownBy(()->inventory.hold(command)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->inventory.release(command)).isInstanceOf(IllegalTransactionStateException.class);
        for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ}){
            var unsupported=transaction();unsupported.setIsolationLevel(isolation);
            assertThatThrownBy(()->unsupported.execute(status->inventory.hold(command))).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(()->unsupported.execute(status->inventory.release(command))).isInstanceOf(IllegalTransactionStateException.class);
        }
        var readOnly=transaction();readOnly.setReadOnly(true);
        assertThatThrownBy(()->readOnly.execute(status->inventory.hold(command))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->readOnly.execute(status->inventory.release(command))).isInstanceOf(IllegalTransactionStateException.class);
        originalUnchanged();for(String table:STOCK)assertThat(count(table)).isZero();
        note(Map.of("stage","read-only-startup-verified","originalDigests",baseline));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="M1_STOCK_RESERVATION_INTEGRATION",matches="approved-temporary-stock-reservation")
    void approvedSingleInventoryScenario() throws Exception {
        prepare();
        try {
            createBalances();
            var shortage=owned(command(1000,warehouse1,List.of(line(1001,sku1,1),line(1002,sku2,2)),actor,"shortage"));
            var before=stockSnapshot();
            conflict(()->transaction().execute(status->inventory.hold(shortage)),"STOCK_INSUFFICIENT");
            assertThat(stockSnapshot()).isEqualTo(before);checks.add("later SKU shortage leaves all balances/reservations/ledger unchanged");

            String absentActor="9999999999999999999";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE id=CAST(? AS UNSIGNED)",Integer.class,absentActor)).isZero();
            var foreignKey=owned(command(1100,warehouse1,List.of(line(1101,sku1,1)),absentActor,"foreign-key"));
            assertThatThrownBy(()->transaction().execute(status->inventory.hold(foreignKey))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(stockSnapshot()).isEqualTo(before);checks.add("real ledger foreign-key failure rolls balance and reservation back");

            var candidates=new ArrayList<Command>();
            for(int i=1;i<=100;i++)candidates.add(owned(command(i,warehouse1,List.of(line(i,sku1,1)),actor,"race-"+i)));
            var pool=Executors.newFixedThreadPool(8);List<Command> winners=new ArrayList<>();int rejected=0;
            try {
                var work=new ArrayList<Callable<Boolean>>();
                for(var command:candidates)work.add(()->{
                    try{transaction().execute(status->inventory.hold(command));return true;}
                    catch(ApiException failure){assertThat(failure.status()).isEqualTo(409);assertThat(failure.code()).isEqualTo("STOCK_INSUFFICIENT");return false;}
                });
                var results=pool.invokeAll(work,30,TimeUnit.SECONDS);
                for(int i=0;i<results.size();i++){
                    assertThat(results.get(i).isCancelled()).as("No unknown concurrent result").isFalse();
                    if(results.get(i).get())winners.add(candidates.get(i));else rejected++;
                }
            } finally {
                pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).as("All fixture workers stopped before inspection or cleanup").isTrue();
            }
            assertThat(winners).hasSize(1);assertThat(rejected).isEqualTo(99);
            assertThat(reserved(warehouse1,sku1)).isEqualTo(1);assertThat(count("stock_reservation")).isEqualTo(1);assertThat(count("stock_ledger")).isEqualTo(1);
            checks.add("100 real transactions competing for stock1 yield one success and99 stock conflicts");
            var winner=winners.getFirst();before=stockSnapshot();
            transaction().execute(status->inventory.hold(winner));assertThat(stockSnapshot()).isEqualTo(before);
            checks.add("exact hold replay does not increment reserved or ledger");
            var changed=command(1200,warehouse1,winner.lines(),actor,"changed-order");
            conflict(()->transaction().execute(status->inventory.hold(changed)),null);assertThat(stockSnapshot()).isEqualTo(before);
            var moved=command(Long.parseLong(winner.businessId())-referenceBase,warehouse2,winner.lines(),actor,"changed-warehouse");
            conflict(()->transaction().execute(status->inventory.hold(moved)),null);assertThat(stockSnapshot()).isEqualTo(before);
            checks.add("same business-line reference cannot move to another order or warehouse");

            var release=withTrace(winner,"release-winner");
            transaction().execute(status->inventory.release(release));assertThat(reserved(warehouse1,sku1)).isZero();
            before=stockSnapshot();transaction().execute(status->inventory.release(release));assertThat(stockSnapshot()).isEqualTo(before);
            checks.add("release atomically decrements reserved and is replay-safe");
            conflict(()->transaction().execute(status->inventory.hold(winner)),null);assertThat(stockSnapshot()).isEqualTo(before);
            checks.add("released line cannot be held again under the same reference");

            var parallelA=owned(command(1400,warehouse1,List.of(line(1401,sku1,1)),actor,"parallel-a"));
            var parallelB=owned(command(1500,warehouse1,List.of(line(1501,sku2,1)),actor,"parallel-b"));
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var separate=Executors.newFixedThreadPool(2);
            try{
                var futures=new ArrayList<Future<?>>();
                for(var command:List.of(parallelA,parallelB))futures.add(separate.submit(()->{
                    ready.countDown();try{if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Fixture start barrier timeout");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                    transaction().execute(status->inventory.hold(command));
                }));
                assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
                for(var future:futures)future.get(15,TimeUnit.SECONDS);
            }finally{start.countDown();separate.shutdownNow();assertThat(separate.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
            assertThat(reserved(warehouse1,sku1)).isEqualTo(1);assertThat(reserved(warehouse1,sku2)).isEqualTo(1);
            transaction().execute(status->inventory.release(withTrace(parallelA,"release-parallel-a")));
            transaction().execute(status->inventory.release(withTrace(parallelB,"release-parallel-b")));
            checks.add("two different SKU first-holds concurrently succeed at the declared READ_COMMITTED contract");

            var other=owned(command(1300,warehouse2,List.of(line(1301,sku1,2)),actor,"quarantine"));
            transaction().execute(status->inventory.hold(other));transaction().execute(status->inventory.release(withTrace(other,"release-other")));
            assertThat(reserved(warehouse2,sku1)).isZero();
            var balance=jdbc.queryForMap("SELECT on_hand,quarantine FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",warehouse2,sku1);
            assertThat(((Number)balance.get("on_hand")).intValue()).isEqualTo(2);assertThat(((Number)balance.get("quarantine")).intValue()).isEqualTo(7);
            checks.add("hold/release preserves physical onHand and quarantine");
            assertThatThrownBy(()->inventory.hold(other)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(()->inventory.release(other)).isInstanceOf(IllegalTransactionStateException.class);
            checks.add("no outer transaction rejects both methods before SQL");

            originalUnchanged();var validatedRows=validateOwnedRows();
            note(Map.of("stage","checks-passed","checks",checks,"raceRequests",100,"raceWinners",1,"raceRejected",99,"dmlBudget",budget.snapshot()));
            cleanup(validatedRows);originalUnchanged();for(String table:STOCK)assertThat(count(table)).isZero();
            note(Map.of("stage","cleaned-and-verified","checks",checks,"originalDigests",baseline));
        } catch(Exception|AssertionError failure){note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName()));throw failure;}
    }

    private void prepare() throws Exception {
        assertThat(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getURL())).matches("jdbc:mysql://127\\.0\\.0\\.1:3306/zhicai_dev(?:\\?.*)?");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        int[] checksums={-357524081,502337516,174647045,-493016158,237973485,947094408};
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");assertThat(history).hasSize(6);
        for(int i=0;i<6;i++){assertThat(history.get(i).get("version").toString()).isEqualTo(Integer.toString(i+1));assertThat(((Number)history.get(i).get("checksum")).intValue()).isEqualTo(checksums[i]);assertThat(history.get(i).get("success").toString()).isIn("true","1");}
        assertThat(count("account")).isEqualTo(4);assertThat(count("product")).isEqualTo(1000);assertThat(count("sku")).isEqualTo(1000);assertThat(count("asset")).isEqualTo(120);
        for(String table:STOCK)assertThat(count(table)).isZero();for(String table:List.of("customer_address","cart_item","idempotency_request","audit_log"))assertThat(count(table)).isZero();
        var warehouses=jdbc.queryForList("SELECT CAST(id AS CHAR) FROM warehouse WHERE enabled=1 AND fulfillment_enabled=1 ORDER BY priority,id LIMIT 2",String.class);assertThat(warehouses).hasSize(2);warehouse1=warehouses.get(0);warehouse2=warehouses.get(1);
        var skus=jdbc.queryForList("SELECT CAST(s.id AS CHAR) FROM sku s JOIN product p ON p.id=s.product_id WHERE s.enabled=1 AND p.state='ON_SALE' ORDER BY s.id LIMIT 2",String.class);assertThat(skus).hasSize(2);sku1=skus.get(0);sku2=skus.get(1);
        actor=jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE account_type='STAFF' AND username='admin' AND enabled=1",String.class);
        String root=System.getenv("M1_STOCK_EVIDENCE_ROOT");assertThat(root).isNotBlank();Path evidence=Path.of(root);assertThat(evidence.isAbsolute()).isTrue();Files.createDirectories(evidence);evidence=evidence.toRealPath();
        assertThat(evidence.startsWith(Path.of(".").toRealPath())).isFalse();directory=evidence.resolve("inventory-"+run);Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
        for(String table:ORIGINAL)baseline.put(table,digest(table));budget.reset();
        note(Map.of("stage","baseline","run",run,"originalDigests",baseline,"maxBalances",3,"maxReservationInserts",8,"maxLedgerInserts",16,"maxUpdates",32));
    }
    private TransactionTemplate transaction(){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);return tx;}
    private Line line(long suffix,String sku,int quantity){return new Line(Long.toString(referenceBase+suffix),sku,quantity);}
    private Command command(long suffix,String warehouse,List<Line> lines,String actorId,String label){String requestTrace=trace+"-"+label;traces.add(requestTrace);return new Command(Long.toString(referenceBase+suffix),warehouse,lines,actorId,requestTrace);}
    private Command withTrace(Command original,String label){String requestTrace=trace+"-"+label;traces.add(requestTrace);return new Command(original.businessId(),original.warehouseId(),original.lines(),original.actorId(),requestTrace);}
    private Command owned(Command command){for(var line:command.lines()){assertThat(ownedLines.putIfAbsent(line.businessLineId(),command)).isNull();}return command;}
    private void conflict(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(409);if(code!=null)assertThat(e.code()).isEqualTo(code);});}
    private int reserved(String warehouse,String sku){return jdbc.queryForObject("SELECT reserved FROM stock_balance WHERE warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED)",Integer.class,warehouse,sku);}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    private String stockSnapshot() throws Exception {return digest("stock_balance")+":"+digest("stock_reservation")+":"+digest("stock_ledger");}
    private void createBalances() throws Exception {
        note(Map.of("stage","seed-intent","warehouse1",warehouse1,"warehouse2",warehouse2,"sku1",sku1,"sku2",sku2));
        transaction().executeWithoutResult(status->{
            for(var row:List.of(List.of(warehouse1,sku1,"1","0"),List.of(warehouse1,sku2,"1","0"),List.of(warehouse2,sku1,"2","7")))
                assertThat(jdbc.update("INSERT INTO stock_balance(warehouse_id,sku_id,on_hand,reserved,quarantine) VALUES(CAST(? AS UNSIGNED),CAST(? AS UNSIGNED),?,0,?)",row.get(0),row.get(1),Integer.parseInt(row.get(2)),Integer.parseInt(row.get(3)))).isEqualTo(1);
        });
        seeds=jdbc.queryForList("SELECT * FROM stock_balance ORDER BY id");assertThat(seeds).hasSize(3);note(Map.of("stage","seed-created","balances",seeds));
    }
    private Map<String,List<Map<String,Object>>> validateOwnedRows(){
        var balances=jdbc.queryForList("SELECT * FROM stock_balance ORDER BY id");assertThat(balances).hasSize(3);
        for(var seed:seeds){var row=balances.stream().filter(x->text(x,"id").equals(text(seed,"id"))).findFirst().orElseThrow();for(String field:List.of("warehouse_id","sku_id","on_hand","quarantine","warning_threshold","created_at"))assertThat(text(row,field)).isEqualTo(text(seed,field));assertThat(((Number)row.get("reserved")).intValue()).isZero();}
        var reservations=jdbc.queryForList("SELECT * FROM stock_reservation ORDER BY id");var ledgers=jdbc.queryForList("SELECT * FROM stock_ledger ORDER BY id");assertThat(reservations).hasSize(4);assertThat(ledgers).hasSize(8);
        for(var seed:seeds){
            var events=ledgers.stream().filter(row->text(row,"warehouse_id").equals(text(seed,"warehouse_id")) && text(row,"sku_id").equals(text(seed,"sku_id"))).toList();
            var balance=balances.stream().filter(row->text(row,"id").equals(text(seed,"id"))).findFirst().orElseThrow();
            assertThat(((Number)balance.get("version")).longValue()).isEqualTo(((Number)seed.get("version")).longValue()+events.size());
            int previous=0;for(var event:events){
                assertThat(((Number)event.get("reserved_before")).intValue()).isEqualTo(previous);
                previous+=((Number)event.get("delta_reserved")).intValue();assertThat(((Number)event.get("reserved_after")).intValue()).isEqualTo(previous);
                for(String suffix:List.of("before","after")){
                    assertThat(text(event,"on_hand_"+suffix)).isEqualTo(text(seed,"on_hand"));assertThat(text(event,"quarantine_"+suffix)).isEqualTo(text(seed,"quarantine"));
                    assertThat(((Number)event.get("in_transit_"+suffix)).longValue()).isZero();
                }
            }
            assertThat(previous).isZero();
        }
        for(var row:reservations){var command=ownedLines.get(text(row,"business_line_id"));assertThat(command).isNotNull();var line=command.lines().stream().filter(x->x.businessLineId().equals(text(row,"business_line_id"))).findFirst().orElseThrow();assertThat(text(row,"business_type")).isEqualTo("ORDER");assertThat(text(row,"warehouse_id")).isEqualTo(command.warehouseId());assertThat(text(row,"sku_id")).isEqualTo(line.skuId());assertThat(((Number)row.get("quantity")).intValue()).isEqualTo(line.quantity());assertThat(text(row,"state")).isEqualTo("RELEASED");
            assertThat(((Number)row.get("version")).longValue()).isEqualTo(1);
            var actions=ledgers.stream().filter(x->text(x,"biz_line_id").equals(line.businessLineId())).toList();assertThat(actions).hasSize(2);assertThat(actions.stream().map(x->text(x,"action"))).containsExactlyInAnyOrder("RESERVE","RELEASE");
            for(var event:actions){assertThat(text(event,"biz_type")).isEqualTo("ORDER");assertThat(text(event,"biz_id")).isEqualTo(command.businessId());assertThat(text(event,"warehouse_id")).isEqualTo(command.warehouseId());assertThat(text(event,"sku_id")).isEqualTo(line.skuId());assertThat(text(event,"actor_id")).isEqualTo(actor);assertThat(traces).contains(text(event,"trace_id"));assertThat(((Number)event.get("delta_reserved")).intValue()).isEqualTo(text(event,"action").equals("RESERVE")?line.quantity():-line.quantity());for(String field:List.of("delta_on_hand","delta_quarantine","delta_in_transit"))assertThat(((Number)event.get(field)).longValue()).isZero();}
        }
        var validated=new LinkedHashMap<String,List<Map<String,Object>>>();
        validated.put("stock_ledger",freezeRows(ledgers));validated.put("stock_reservation",freezeRows(reservations));validated.put("stock_balance",freezeRows(balances));
        return Collections.unmodifiableMap(validated);
    }
    private List<Map<String,Object>> freezeRows(List<Map<String,Object>> rows){return rows.stream().map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();}
    private void cleanup(Map<String,List<Map<String,Object>>> rows) throws Exception {
        note(Map.of("stage","exact-cleanup-intent","rows",rows));
        transaction().executeWithoutResult(status->{
            try{
                // Freeze membership and values BEFORE deleting anything. Never adopt a new row as ours.
                for(var entry:rows.entrySet()){
                    var current=jdbc.queryForList("SELECT * FROM `"+entry.getKey()+"` ORDER BY id FOR UPDATE");
                    assertThat(json.writeValueAsString(current)).as("No membership/value drift: %s",entry.getKey()).isEqualTo(json.writeValueAsString(entry.getValue()));
                }
                for(var entry:rows.entrySet()){
                    String table=entry.getKey();for(var row:entry.getValue()){
                        var current=jdbc.queryForMap("SELECT * FROM `"+table+"` WHERE id=CAST(? AS UNSIGNED) FOR UPDATE",text(row,"id"));assertThat(json.writeValueAsString(current)).isEqualTo(json.writeValueAsString(row));
                        if(table.equals("stock_ledger"))assertThat(jdbc.update("DELETE FROM stock_ledger WHERE id=CAST(? AS UNSIGNED) AND biz_type='ORDER' AND biz_id=CAST(? AS UNSIGNED) AND biz_line_id=CAST(? AS UNSIGNED) AND warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED) AND action=? AND trace_id=? AND actor_id=CAST(? AS UNSIGNED)",text(row,"id"),text(row,"biz_id"),text(row,"biz_line_id"),text(row,"warehouse_id"),text(row,"sku_id"),text(row,"action"),text(row,"trace_id"),actor)).isEqualTo(1);
                        else if(table.equals("stock_reservation"))assertThat(jdbc.update("DELETE FROM stock_reservation WHERE id=CAST(? AS UNSIGNED) AND business_type='ORDER' AND business_line_id=CAST(? AS UNSIGNED) AND warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED) AND quantity=? AND state='RELEASED' AND version=?",text(row,"id"),text(row,"business_line_id"),text(row,"warehouse_id"),text(row,"sku_id"),row.get("quantity"),row.get("version"))).isEqualTo(1);
                        else assertThat(jdbc.update("DELETE FROM stock_balance WHERE id=CAST(? AS UNSIGNED) AND warehouse_id=CAST(? AS UNSIGNED) AND sku_id=CAST(? AS UNSIGNED) AND on_hand=? AND reserved=0 AND quarantine=? AND version=?",text(row,"id"),text(row,"warehouse_id"),text(row,"sku_id"),row.get("on_hand"),row.get("quarantine"),row.get("version"))).isEqualTo(1);
                    }
                }
                originalUnchanged();for(String table:STOCK)assertThat(count(table)).isZero();
            }catch(Exception failure){throw new IllegalStateException("Exact cleanup could not be proved",failure);}
        });
    }
    private String text(Map<String,Object> row,String column){return Objects.toString(row.get(column),null);}
    private void originalUnchanged() throws Exception {for(String table:ORIGINAL)assertThat(digest(table)).as("Original rows: %s",table).isEqualTo(baseline.get(table));}
    private String digest(String table) throws Exception {
        List<String> rows=jdbc.query("SELECT * FROM `"+table+"`",rs->{var values=new ArrayList<String>();int columns=rs.getMetaData().getColumnCount();while(rs.next()){var row=new StringBuilder();for(int c=1;c<=columns;c++){Object value=rs.getObject(c);String text=value==null?"<null>":value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):value.toString();row.append(c).append(':').append(value==null?-1:text.length()).append(':').append(text).append(';');}values.add(sha(row.toString()));}return values;});Collections.sort(rows);return sha(String.join("\n",rows));
    }
    private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private synchronized void note(Map<String,?> event) throws Exception {byte[] bytes=(json.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);try(FileChannel file=FileChannel.open(manifest,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);}}

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean InventoryReservationService inventoryFixtureService(StockMapper mapper){return new InventoryReservationService(mapper);}
        @Bean WriteBudget inventoryWriteBudget(){return new WriteBudget();}
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    static class WriteBudget implements Interceptor {
        final AtomicInteger reservations=new AtomicInteger(),ledgers=new AtomicInteger(),updates=new AtomicInteger();
        void reset(){reservations.set(0);ledgers.set(0);updates.set(0);}
        Map<String,Integer> snapshot(){return Map.of("reservationInsertAttempts",reservations.get(),"ledgerInsertAttempts",ledgers.get(),"updateAttempts",updates.get());}
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();String prefix="com.zhicai.platform.inventory.StockMapper.";
            if(id.startsWith(prefix)){
                String method=id.substring(prefix.length());
                if(method.equals("insertReservation")){if(reservations.incrementAndGet()>8)throw new IllegalStateException("Fixture reservation budget exceeded before SQL");}
                else if(method.equals("insertLedger")){if(ledgers.incrementAndGet()>16)throw new IllegalStateException("Fixture ledger budget exceeded before SQL");}
                else if(Set.of("increaseReserved","decreaseReserved","markReleased").contains(method)){if(updates.incrementAndGet()>32)throw new IllegalStateException("Fixture update budget exceeded before SQL");}
                else throw new IllegalStateException("Unapproved inventory mapped write: "+method);
            }
            return invocation.proceed();
        }
    }
}
