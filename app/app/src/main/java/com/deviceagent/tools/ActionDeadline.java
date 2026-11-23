package com.deviceagent.tools;
/** Shared by a local sequence and its synchronous steps. Never starts a late action. */
public final class ActionDeadline {
    private record Limit(long monotonic,long epoch){}
    private static final ThreadLocal<java.util.Deque<Limit>> limits=ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private ActionDeadline(){}
    public static void set(long deadline){long remaining=Math.max(0,deadline-System.nanoTime()/1_000_000);limits.get().push(new Limit(deadline,System.currentTimeMillis()+remaining));}
    public static AutoCloseableScope until(long epoch){limits.get().push(new Limit(System.nanoTime()/1_000_000+Math.max(0,epoch-System.currentTimeMillis()),epoch));return ActionDeadline::clear;}
    public interface AutoCloseableScope extends AutoCloseable{@Override void close();}
    public static void clear(){var stack=limits.get();if(!stack.isEmpty())stack.pop();if(stack.isEmpty())limits.remove();}
    public static long remaining(){long remaining=Long.MAX_VALUE,now=System.nanoTime()/1_000_000,epoch=System.currentTimeMillis();for(var limit:limits.get())remaining=Math.min(remaining,Math.min(limit.monotonic-now,limit.epoch-epoch));return Math.max(0,remaining);}
    public static void check(){if(Thread.currentThread().isInterrupted()||remaining()==0)throw new InputNotSentException("deadline_exceeded","The sequence deadline passed. No further action was sent.");}
}
