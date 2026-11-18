package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import com.zhicai.platform.scheduling.DatabaseTaskLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** Direct JDBC, no application context, Flyway, Redis, web server or scheduled job. */
@EnabledIfEnvironmentVariable(named="M1_TASK_LEASE_MODE",matches="read-only|approved-single-task-lease")
class MySqlTaskLeaseIntegrationTest {
    static final Map<String,Integer> CAPS=Map.of("INSERT task_lease",24,"UPDATE task_lease",8);
    static final Path BATCH=Path.of(".local/acceptance");
    static final String BASELINE_SHA="0000000000000000000000000000000000000000000000000000000000000000";
    static final String URL="jdbc:mysql://127.0.0.1:3306/zhicai_dev?connectionTimeZone=UTC";
    final ObjectMapper json=new ObjectMapper();
    final String run=UUID.randomUUID().toString();
    final List<String> names=List.of("lease-"+run+"-race","lease-"+run+"-probe","lease-"+run+"-wait","lease-"+run+"-fence");
    final LeaseGuard guard=new LeaseGuard();
    final Map<String,List<String>> columns=new LinkedHashMap<>();
    final List<String> checks=new ArrayList<>();
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    DatabaseTaskLease leases;
    Path manifest;
    Map<String,String> baseline;

    static boolean approved(){return "approved-single-task-lease".equals(System.getenv("M1_TASK_LEASE_MODE"))
        && System.getenv("M1_TASK_LEASE_APPROVAL_ID")!=null&&!System.getenv("M1_TASK_LEASE_APPROVAL_ID").isBlank();}
    static final class LeaseGuard extends MySqlAcceptanceSupport.SqlGuard {
        final AtomicReference<CountDownLatch> insertStarted=new AtomicReference<>(),ownerLockStarted=new AtomicReference<>();
        LeaseGuard(){super(CAPS,List.of("task_lease"),4,MySqlTaskLeaseIntegrationTest::approved);}
        @Override void beforeExecution(String sql,String kind)throws SQLException {
            var signal=kind.equals("INSERT task_lease")?insertStarted:
                sql.startsWith("SELECT owner_token")&&sql.endsWith("FOR UPDATE")?ownerLockStarted:null;
            if(signal!=null){var latch=signal.getAndSet(null);if(latch!=null)latch.countDown();}
        }
    }

    @Test void singleBoundedScenario() throws Exception {
        assertThat(System.getenv("DB_URL")).isEqualTo(URL);
        var config=new HikariConfig();config.setJdbcUrl(URL);config.setUsername(System.getenv("DB_USERNAME"));config.setPassword(System.getenv("DB_PASSWORD"));
        config.setMaximumPoolSize(20);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setValidationTimeout(2000);
        config.setConnectionInitSql("SET time_zone = '+00:00'");config.setPoolName("lease-acceptance");
        try(var pool=new HikariDataSource(config)){
            var source=guard.dataSource(pool);jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(8);
            manager=new DataSourceTransactionManager(source);leases=new DatabaseTaskLease(jdbc,manager);
            prepare();
            if(!approved()){
                assertThat(System.getenv("M1_TASK_LEASE_MODE")).isEqualTo("read-only");
                assertThat(guard.snapshot()).isEmpty();assertThat(guard.locks.get()).isZero();
                note(Map.of("stage","read-only-verified","tables",25,"digests",baseline,"sqlMutationAttempts",0,"lockingSelects",0));return;
            }
            assertThat(System.getenv("M1_TASK_LEASE_FIXTURE_SHA256")).matches("[A-Fa-f0-9]{64}");
            assertThat(System.getenv("M1_TASK_LEASE_IMPLEMENTATION_SHA256")).matches("[A-Fa-f0-9]{64}");
            try{
                note(Map.of("stage","write-intent","names",names,"caps",CAPS,"maxDelete",4));guard.armed=true;
                concurrentFirstAcquire();rollbackIsAtomic();lockedExpiredExecution();expiredWorkerFencing();
                assertThat(guard.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of("INSERT task_lease",21,"UPDATE task_lease",4));
                assertOriginalRows();
                var frozen=MySqlAcceptanceSupport.rows(jdbc,"task_lease",columns.get("task_lease"),false);
                assertThat(frozen).hasSize(4);assertThat(frozen.stream().map(r->r.get("task_name"))).containsExactlyInAnyOrderElementsOf(names);
                note(Map.of("stage","checks-passed","checks",checks,"sqlAttempts",guard.snapshot(),"frozenRows",frozen));
                cleanup(frozen);assertThat(allDigests()).isEqualTo(baseline);
                note(Map.of("stage","cleaned-and-verified","tables",25,"digests",baseline,"sqlAttempts",guard.snapshot(),"deleteAttempts",guard.deletes.get()));
            }catch(Exception|AssertionError failure){
                note(Map.of("stage","retained-after-failure","exceptionClass",failure.getClass().getName(),"names",names,"sqlAttempts",guard.snapshot()));throw failure;
            }finally{guard.armed=false;guard.cleanup=false;}
        }
    }
    void prepare() throws Exception {
        assertThat(Path.of(System.getenv("M1_TASK_LEASE_EVIDENCE_ROOT")).toRealPath()).isEqualTo(BATCH.toRealPath());
        var directory=BATCH.resolve("run-"+run);Files.createDirectory(directory);manifest=directory.resolve("manifest.jsonl");Files.createFile(manifest);
        var bytes=Files.readAllBytes(BATCH.resolve("baseline-v9.json"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(BASELINE_SHA);
        var reference=json.readTree(bytes);assertThat(reference.path("Database").asText()).isEqualTo("zhicai_dev");
        var expected=new TreeSet<String>();for(var row:reference.path("Rows"))expected.add(row.path("table").asText());assertThat(expected).hasSize(25);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("zhicai_dev");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)).containsExactlyInAnyOrderElementsOf(expected);
        for(String table:expected){assertThat(table).matches("[a-z_]+");columns.put(table,jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table));}
        baseline=allDigests();
        for(var row:reference.path("Rows"))assertThat(baseline.get(row.path("table").asText())).isEqualTo(row.path("count").asInt()+":"+row.path("sha256").asText());
        assertThat(count("task_lease")).isZero();
        note(Map.of("stage","preflight-verified","run",run,"mode",System.getenv("M1_TASK_LEASE_MODE"),"baselineSha256",BASELINE_SHA,"digests",baseline));
    }
    void concurrentFirstAcquire() throws Exception {
        var ready=new CountDownLatch(16);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Optional<DatabaseTaskLease.Lease>>>();
        try(var executor=Executors.newFixedThreadPool(16)){
            for(int i=0;i<16;i++)futures.add(executor.submit(()->{ready.countDown();await(start);return leases.acquire(names.get(0),Duration.ofSeconds(30));}));
            try{await(ready);}finally{start.countDown();}
            var winners=new ArrayList<DatabaseTaskLease.Lease>();for(var future:futures)future.get(10,TimeUnit.SECONDS).ifPresent(winners::add);
            assertThat(winners).hasSize(1);assertThat(count("task_lease")).isEqualTo(1);
            var first=winners.getFirst();assertThat(leases.execute(first,()->{})).isTrue();assertThat(leases.release(first)).isTrue();
            var next=leases.acquire(names.get(0),Duration.ofSeconds(30)).orElseThrow();assertThat(next.ownerToken()).isNotEqualTo(first.ownerToken());
            assertThat(leases.execute(first,()->fail("old owner callback"))).isFalse();assertThat(leases.release(first)).isFalse();
            assertThat(leases.execute(next,()->{})).isTrue();checks.add("16 simultaneous initial attempts: one owner; release/takeover fences old handle");
        }
    }
    void rollbackIsAtomic(){
        var probe=leases.acquire(names.get(1),Duration.ofSeconds(30)).orElseThrow();
        var active=new DatabaseTaskLease.Lease(names.get(0),jdbc.queryForObject("SELECT owner_token FROM task_lease WHERE task_name=?",String.class,names.get(0)));
        var before=allDigests();var failure=new IllegalStateException("controlled business rollback");
        assertThatThrownBy(()->leases.execute(active,()->{
            assertThat(jdbc.update("UPDATE task_lease SET lease_until=TIMESTAMPADD(HOUR,1,lease_until) WHERE task_name=? AND owner_token=?",probe.taskName(),probe.ownerToken())).isEqualTo(1);
            throw failure;
        })).isSameAs(failure);
        assertThat(allDigests()).isEqualTo(before);checks.add("callback SQL executed then exception: all25 row digests restored by rollback");
    }
    void lockedExpiredExecution() throws Exception {
        var lease=leases.acquire(names.get(2),Duration.ofSeconds(1)).orElseThrow();
        var held=new CountDownLatch(1);var unlock=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var blocker=executor.submit(()->tx(false).execute(s->{jdbc.queryForObject("SELECT owner_token FROM task_lease WHERE task_name=? FOR UPDATE",String.class,lease.taskName());held.countDown();await(unlock);return true;}));
            Future<Boolean> execution=null;
            try{await(held);guard.ownerLockStarted.set(attempted);execution=executor.submit(()->leases.execute(lease,()->fail("expired callback")));await(attempted);waitForExpiry(lease);assertThat(execution.isDone()).isFalse();}
            finally{unlock.countDown();}
            assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(execution.get(10,TimeUnit.SECONDS)).isFalse();
            checks.add("execute waits for row lock and samples fresh DB time: expired owner rejected");
        }
    }
    void expiredWorkerFencing() throws Exception {
        var lease=leases.acquire(names.get(3),Duration.ofSeconds(2)).orElseThrow();
        var inside=new CountDownLatch(1);var finish=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var running=executor.submit(()->leases.execute(lease,()->{inside.countDown();await(finish);}));
            Future<Optional<DatabaseTaskLease.Lease>> rival=null;
            try{
                await(inside);waitForExpiry(lease);guard.insertStarted.set(attempted);
                rival=executor.submit(()->leases.acquire(lease.taskName(),Duration.ofSeconds(30)));await(attempted);
                var blocked=rival;assertThatThrownBy(()->blocked.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }finally{finish.countDown();}
            assertThat(running.get(10,TimeUnit.SECONDS)).isTrue();var replacement=rival.get(10,TimeUnit.SECONDS).orElseThrow();
            assertThat(replacement.ownerToken()).isNotEqualTo(lease.ownerToken());
            assertThat(leases.execute(lease,()->fail("superseded owner callback"))).isFalse();assertThat(leases.release(lease)).isFalse();
            assertThat(leases.execute(replacement,()->{})).isTrue();checks.add("expired in-flight worker holds row through commit; contender takes over afterwards and fences old owner");
        }
    }
    void waitForExpiry(DatabaseTaskLease.Lease lease){
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT lease_until<=UTC_TIMESTAMP(3) FROM task_lease WHERE task_name=?",Integer.class,lease.taskName()))){
            if(System.nanoTime()>limit)throw new AssertionError("Database clock did not reach lease expiry");
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(15));
        }
    }
    void cleanup(List<Map<String,String>> frozen){
        var transaction=tx(false);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.execute(s->{
            assertThat(jdbc.queryForList("SELECT task_name FROM task_lease FORCE INDEX(PRIMARY) ORDER BY task_name FOR UPDATE",String.class)).containsExactlyInAnyOrderElementsOf(names);
            assertThat(MySqlAcceptanceSupport.rows(jdbc,"task_lease",columns.get("task_lease"),false)).isEqualTo(frozen);
            guard.cleanup=true;
            for(var row:frozen)assertThat(jdbc.update("DELETE FROM task_lease WHERE task_name=? AND owner_token=? AND lease_until=? AND "+MySqlAcceptanceSupport.hashSql(columns.get("task_lease"))+"=?",row.get("task_name"),row.get("owner_token"),row.get("lease_until"),row.get("_guard_hash"))).isEqualTo(1);
            assertThat(count("task_lease")).isZero();return null;
        });guard.cleanup=false;
    }
    Map<String,String> allDigests(){return Objects.requireNonNull(tx(true).execute(s->{
        var result=new LinkedHashMap<String,String>();
        for(var entry:columns.entrySet()){int count=count(entry.getKey());result.put(entry.getKey(),count+":"+MySqlAcceptanceSupport.referenceDigest(jdbc,entry.getKey(),entry.getValue(),count));}
        return result;
    }));}
    void assertOriginalRows(){var actual=allDigests();for(var entry:baseline.entrySet())if(!entry.getKey().equals("task_lease"))assertThat(actual.get(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());}
    int count(String table){return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class));}
    TransactionTemplate tx(boolean readOnly){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setReadOnly(readOnly);tx.setTimeout(10);return tx;}
    static void await(CountDownLatch latch){try{if(!latch.await(6,TimeUnit.SECONDS))throw new AssertionError("Worker rendezvous timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    void note(Map<String,?> value) throws java.io.IOException {Files.writeString(manifest,json.writeValueAsString(value)+"\n",StandardOpenOption.APPEND);}
}
