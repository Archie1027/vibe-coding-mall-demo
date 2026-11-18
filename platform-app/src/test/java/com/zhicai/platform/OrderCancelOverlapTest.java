package com.zhicai.platform;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** No database: the contender must arrive while the first business lock is held. */
class OrderCancelOverlapTest {
    @Test void contenderRunsDuringOwnersWorkLockAndReleaseIsExplicit()throws Exception {
        var gate=new OrderCancelOverlap(Duration.ofSeconds(2));
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<?> owner=pool.submit(()->{gate.ownerLocked();return null;});
            try{gate.awaitOwner();assertThat(owner.isDone()).isFalse();}
            finally{gate.releaseOwner();}
            owner.get(2,TimeUnit.SECONDS);
            gate.ownerLocked(); // A later lock must not wait a second time.
        }
    }
    @Test void missingWorkLockFailsWithinTheBoundedWait(){
        var gate=new OrderCancelOverlap(Duration.ofMillis(20));
        try{assertThatThrownBy(gate::awaitOwner).isInstanceOf(SQLException.class).hasMessageContaining("owner work lock");}
        finally{gate.releaseOwner();}
    }
    @Test void missingContenderCannotHoldTheOwnersJdbcThreadForever(){
        var gate=new OrderCancelOverlap(Duration.ofMillis(20));
        assertThatThrownBy(gate::ownerLocked).isInstanceOf(SQLException.class).hasMessageContaining("contender");
    }
}
