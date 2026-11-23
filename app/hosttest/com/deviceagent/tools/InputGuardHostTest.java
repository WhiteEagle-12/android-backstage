package com.deviceagent.tools;

public final class InputGuardHostTest {
    private static void expect(String code,Runnable call){try{call.run();throw new AssertionError("Expected "+code);}catch(InputNotSentException e){if(!code.equals(e.code))throw new AssertionError(e.code);}}
    public static void run()throws Exception{
        try(var guard=new InputGuard(null)){guard.check();guard.pause(2);try(var expired=ActionDeadline.until(System.currentTimeMillis()-1)){expect("deadline_exceeded",guard::check);}}
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var guard=new InputGuard(allowed::get)){
            guard.check();allowed.set(false);long end=System.nanoTime()+3000000000L;boolean stopped=false;
            while(System.nanoTime()<end)try{guard.pause(20);}catch(InputNotSentException e){if(!"screen_control_stopped".equals(e.code))throw e;stopped=true;break;}
            if(!stopped)throw new AssertionError("Stop must interrupt a hold");
        }
        try(var guard=new InputGuard(()->{throw new IllegalStateException("provider lost");})){
            long end=System.nanoTime()+3000000000L;boolean stopped=false;
            while(System.nanoTime()<end)try{guard.pause(20);}catch(InputNotSentException e){stopped=true;break;}
            if(!stopped)throw new AssertionError("Uncertain screen permission must stop input");
        }
        var closed=new InputGuard(null);closed.close();expect("screen_control_stopped",closed::check);
        System.out.println("PASS input guard: deadlines, stopped foreground holds, lost provider and cleanup");
    }
}
