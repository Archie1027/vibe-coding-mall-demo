package com.zhicai.platform;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only sequencing: overlap HTTP work after the owner has its idempotency work lock.
 * Starting both claim INSERTs together can legitimately return two REQUEST_IN_PROGRESS responses. */
final class OrderCancelOverlap {
    private final CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
    private final AtomicBoolean first=new AtomicBoolean(true);
    private final long timeoutMillis;
    OrderCancelOverlap(Duration timeout){
        timeoutMillis=timeout.toMillis();if(timeoutMillis<1)throw new IllegalArgumentException("Positive timeout required");
    }
    void ownerLocked()throws SQLException {
        if(!first.compareAndSet(true,false))return;
        locked.countDown();await(release,"Timed out waiting for contender");
    }
    void awaitOwner()throws SQLException {await(locked,"Timed out waiting for owner work lock");}
    void releaseOwner(){release.countDown();}
    private void await(CountDownLatch latch,String message)throws SQLException {
        try{if(!latch.await(timeoutMillis,TimeUnit.MILLISECONDS))throw new SQLException(message);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException("HTTP overlap interrupted",e);}
    }
}
