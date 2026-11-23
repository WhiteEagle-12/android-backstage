package com.deviceagent.tools;

import java.util.function.BooleanSupplier;

/** Check deadlines on every event; watch foreground revocation without delaying touch frames. */
public final class InputGuard implements AutoCloseable {
    private volatile boolean allowed=true,closed;
    private final Thread watcher;
    public InputGuard(BooleanSupplier permission){
        if(permission==null){watcher=null;return;}
        watcher=new Thread(()->{
            try{
                while(!closed){Thread.sleep(100);if(!closed&&!permission.getAsBoolean()){allowed=false;return;}}
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(RuntimeException e){allowed=false;}
        },"screen-input-stop");
        watcher.setDaemon(true);watcher.start();
    }
    public void check(){ActionDeadline.check();if(!allowed||closed)throw new InputNotSentException("screen_control_stopped","Screen control stopped. No further input was sent.");}
    public void pause(long millis)throws InterruptedException{
        long end=System.nanoTime()+millis*1000000L;
        while(System.nanoTime()<end){check();long left=(end-System.nanoTime())/1000000L;if(left>0)Thread.sleep(Math.min(16,left));}
        check();
    }
    @Override public void close(){closed=true;if(watcher!=null)watcher.interrupt();}
}
