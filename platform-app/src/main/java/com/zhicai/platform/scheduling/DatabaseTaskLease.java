package com.zhicai.platform.scheduling;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit wiring only: no scheduler or application Bean is enabled by this foundation.
 * Each business unit must execute through execute(), joining its transaction. Holding the lease
 * row until commit fences an expired worker even when another process attempts takeover.
 * Never perform network calls or REQUIRES_NEW business writes inside the callback.
 */
public final class DatabaseTaskLease {
    public record Lease(String taskName,String ownerToken) {
        public Lease {
            name(taskName);
            if(ownerToken==null || !ownerToken.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("Canonical lease owner token required");
        }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public DatabaseTaskLease(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=Objects.requireNonNull(jdbc);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }
    public Optional<Lease> acquire(String taskName,Duration duration){
        name(taskName);
        if(duration==null || duration.getNano()!=0 || duration.getSeconds()<1 || duration.getSeconds()>300)
            throw new IllegalArgumentException("Task lease duration must be 1 to 300 whole seconds");
        outsideTransaction();
        var lease=new Lease(taskName,UUID.randomUUID().toString());
        return Objects.requireNonNull(transaction.execute(status->{
            // owner assignment precedes expiry assignment: both conditions see the old lease_until.
            jdbc.update("INSERT INTO task_lease(task_name,owner_token,lease_until) VALUES(?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3))) "
                +"ON DUPLICATE KEY UPDATE owner_token=IF(lease_until<=UTC_TIMESTAMP(3),?,owner_token), "
                +"lease_until=IF(lease_until<=UTC_TIMESTAMP(3),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),lease_until)",
                taskName,lease.ownerToken(),duration.getSeconds(),lease.ownerToken(),duration.getSeconds());
            return lease.ownerToken().equals(owner(taskName,false))?Optional.of(lease):Optional.<Lease>empty();
        }));
    }
    public boolean execute(Lease lease,Runnable operation){
        Objects.requireNonNull(lease);Objects.requireNonNull(operation);outsideTransaction();
        return Boolean.TRUE.equals(transaction.execute(status->{
            if(!lease.ownerToken().equals(owner(lease.taskName(),true)))return false;
            // Sample database time in a new statement AFTER any row-lock wait.
            var live=jdbc.queryForObject("SELECT lease_until>UTC_TIMESTAMP(3) FROM task_lease WHERE task_name=? AND owner_token=?",
                Integer.class,lease.taskName(),lease.ownerToken());
            if(!Integer.valueOf(1).equals(live))return false;
            operation.run();
            return true;
        }));
    }
    public boolean release(Lease lease){
        Objects.requireNonNull(lease);outsideTransaction();
        return Boolean.TRUE.equals(transaction.execute(status->jdbc.update(
            "UPDATE task_lease SET lease_until=UTC_TIMESTAMP(3) WHERE task_name=? AND owner_token=?",lease.taskName(),lease.ownerToken())==1));
    }
    private String owner(String taskName,boolean lock){
        try{return jdbc.queryForObject("SELECT owner_token FROM task_lease WHERE task_name=?"+(lock?" FOR UPDATE":""),String.class,taskName);}
        catch(EmptyResultDataAccessException missing){return null;}
    }
    private static void name(String name){if(name==null || !name.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))throw new IllegalArgumentException("Canonical task name required");}
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalTransactionStateException("Task lease must own the outer transaction");}
}
