package com.deviceagent.tools;

import java.nio.channels.*;
import java.nio.file.*;

/** A diagnostic must not outlive its main thread or its independent wall-clock limit. */
public final class DeviceCheckGuard {
 private static FileChannel channel;private static FileLock lock;
 private DeviceCheckGuard(){}
 public static synchronized void start(String name){start(name,180000);}
 public static synchronized void start(String name,long timeoutMs){
  if(channel!=null)throw new IllegalStateException("Device check guard already started");
  try{
   Path path=Paths.get(System.getProperty("backstage.checkLock","/data/local/tmp/backstage/device-check.lock"));Files.createDirectories(path.toAbsolutePath().getParent());
   channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=channel.tryLock();
   if(lock==null){System.out.println("CHECK REJECTED: another device check is running");System.out.flush();Runtime.getRuntime().halt(125);return;}
  }catch(Exception e){throw new IllegalStateException("Could not acquire the device check lock",e);}
  Thread main=Thread.currentThread();long end=System.nanoTime()+timeoutMs*1000000L;
  Thread guard=new Thread(()->{
   while(true){
    if(!main.isAlive()){System.out.println("CHECK PROCESS EXIT: "+name);System.out.flush();Runtime.getRuntime().halt(0);}
    if(System.nanoTime()>=end){System.out.println("CHECK TIMEOUT: "+name);for(StackTraceElement frame:main.getStackTrace())System.out.println("  at "+frame);System.out.flush();Runtime.getRuntime().halt(124);}
    try{Thread.sleep(50);}catch(InterruptedException ignored){}
   }
  },"device-check-watchdog");guard.setDaemon(true);guard.start();
 }
}
