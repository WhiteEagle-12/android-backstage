package com.deviceagent.tools;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One agent at a time on a display. Unlike a lock, a waiter takes the turn from a holder whose
 * request deadline passed long ago, so one stuck call cannot keep a display busy for minutes.
 * The stuck holder's later release is ignored.
 */
public final class DisplayTurn {
    /** How long past its deadline a holder keeps the turn before a waiter takes it. */
    public static final long STUCK_GRACE_MS=3_000;
    private final LongSupplier clock;private final Consumer<String> log;
    private Thread holder;private long holderDeadline;private int depth;
    public DisplayTurn(){this(()->System.nanoTime()/1_000_000,s->{});}
    public DisplayTurn(LongSupplier clock,Consumer<String> log){this.clock=clock;this.log=log;}
    /** Wait up to waitMs. deadline is when the caller's request expires, on the same clock. */
    public synchronized boolean acquire(long waitMs,long deadline) throws InterruptedException {
        Thread me=Thread.currentThread();
        if(holder==me){depth++;return true;}
        long until=clock.getAsLong()+Math.max(0,waitMs);
        while(holder!=null){
            long now=clock.getAsLong();
            if(now>holderDeadline+STUCK_GRACE_MS){
                log.accept("display turn taken from a stuck call ("+(now-holderDeadline)+" ms past its deadline) at "+stack(holder));
                break;
            }
            if(now>=until)return false;
            wait(Math.max(1,Math.min(until,holderDeadline+STUCK_GRACE_MS+1)-now));
        }
        holder=me;holderDeadline=deadline;depth=1;
        return true;
    }
    public synchronized boolean heldByCurrentThread(){return holder==Thread.currentThread();}
    public synchronized void release(){
        if(holder!=Thread.currentThread())return;
        if(--depth==0){holder=null;notifyAll();}
    }
    private static String stack(Thread t){
        StringBuilder out=new StringBuilder(t.getName());
        var frames=t.getStackTrace();for(int i=0;i<Math.min(frames.length,12);i++)out.append("\n  at ").append(frames[i]);
        return out.toString();
    }
}
