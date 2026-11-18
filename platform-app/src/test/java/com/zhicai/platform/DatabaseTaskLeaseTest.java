package com.zhicai.platform;

import com.zhicai.platform.scheduling.DatabaseTaskLease;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline transaction/ownership checks. Real MySQL contention needs a separate approved batch. */
class DatabaseTaskLeaseTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    DatabaseTaskLease service(){
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        return new DatabaseTaskLease(jdbc,manager);
    }
    DatabaseTaskLease.Lease lease(){return new DatabaseTaskLease.Lease("order-expiry","11111111-1111-4111-8111-111111111111");}

    @Test void invalidNamesAndDurationsCannotOpenTransactionOrSql(){
        var service=service();clearInvocations(manager);
        for(var name:new String[]{"", "../expiry", "到期", "a".repeat(65)})
            assertThatThrownBy(()->service.acquire(name,Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        for(var duration:new Duration[]{Duration.ZERO,Duration.ofMillis(1500),Duration.ofSeconds(301)})
            assertThatThrownBy(()->service.acquire("expiry",duration)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc,manager);
    }
    @Test void liveRivalLeaseDoesNotGrantOwnership(){
        var service=service();when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn("rival");
        assertThat(service.acquire("order-expiry",Duration.ofSeconds(30))).isEmpty();
        verify(manager).commit(any());
    }
    @Test void acquisitionUsesANewOwnerForEveryAttempt(){
        var service=service();var token=new AtomicReference<String>();
        when(jdbc.update(startsWith("INSERT INTO task_lease"),any(Object[].class))).thenAnswer(call->{token.set(call.getArgument(2,String.class));return 1;});
        when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenAnswer(call->token.get());
        var first=service.acquire("order-expiry",Duration.ofSeconds(30)).orElseThrow();
        var second=service.acquire("order-expiry",Duration.ofSeconds(30)).orElseThrow();
        assertThat(first.taskName()).isEqualTo("order-expiry");
        assertThat(first.ownerToken()).matches("[0-9a-f-]{36}").isNotEqualTo(second.ownerToken());
    }
    @Test void successfulWorkLocksOwnerThenSamplesDatabaseTimeBeforeCallback(){
        var service=service();var ran=new AtomicBoolean();
        when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn(lease().ownerToken());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenAnswer(call->{assertThat(ran).isFalse();return 1;});
        assertThat(service.execute(lease(),()->ran.set(true))).isTrue();assertThat(ran).isTrue();
        var order=inOrder(jdbc,manager);
        order.verify(manager).getTransaction(any());
        order.verify(jdbc).queryForObject(endsWith("FOR UPDATE"),eq(String.class),eq("order-expiry"));
        order.verify(jdbc).queryForObject(contains("lease_until>UTC_TIMESTAMP(3)"),eq(Integer.class),eq("order-expiry"),eq(lease().ownerToken()));
        order.verify(manager).commit(any());
    }
    @Test void missingLeaseIsARejectionButDatabaseFailurePropagates(){
        var service=service();var ran=new AtomicBoolean();
        when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertThat(service.execute(lease(),()->ran.set(true))).isFalse();assertThat(ran).isFalse();
        var failure=new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenThrow(failure);
        assertThatThrownBy(()->service.execute(lease(),()->ran.set(true))).isSameAs(failure);
        assertThat(ran).isFalse();verify(manager).rollback(any());
    }
    @Test void staleOwnerCannotRunBusinessCallback(){
        var service=service();when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn("new-owner");
        var ran=new AtomicBoolean();assertThat(service.execute(lease(),()->ran.set(true))).isFalse();assertThat(ran).isFalse();
        verify(jdbc,never()).queryForObject(anyString(),eq(Integer.class),any(Object[].class));
    }
    @Test void matchingButExpiredOwnerCannotRunBusinessCallback(){
        var service=service();when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn(lease().ownerToken());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        var ran=new AtomicBoolean();assertThat(service.execute(lease(),()->ran.set(true))).isFalse();assertThat(ran).isFalse();
    }
    @Test void ownedLiveLeaseRunsInsideRcTransactionAndFailureRollsBack(){
        var service=service();when(jdbc.queryForObject(anyString(),eq(String.class),any(Object[].class))).thenReturn(lease().ownerToken());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        var failure=new IllegalStateException("business failure");
        assertThatThrownBy(()->service.execute(lease(),()->{throw failure;})).isSameAs(failure);
        verify(manager).rollback(any());verify(manager,never()).commit(any());
        var capture=org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);verify(manager).getTransaction(capture.capture());
        assertThat(capture.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(capture.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Test void releasedOldHandleCannotReleaseAReplacementOwner(){
        var service=service();assertThat(service.release(lease())).isFalse();
        verify(jdbc).update(contains("WHERE task_name=? AND owner_token=?"),eq("order-expiry"),eq(lease().ownerToken()));
    }
    @Test void callerTransactionIsRejectedBeforeSql(){
        var service=service();clearInvocations(manager);TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->service.execute(lease(),()->{})).isInstanceOf(IllegalTransactionStateException.class);verifyNoInteractions(jdbc,manager);}
        finally{TransactionSynchronizationManager.clear();}
    }
}
