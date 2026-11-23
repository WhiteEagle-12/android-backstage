package com.deviceagent.tools;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
public final class DeviceCheckGuardHostTest {
 public static void main(String[] args)throws Exception{
  DeviceCheckGuard.start("host-fixture",args[0].equals("hang")?200:2000);
  if(args[0].equals("hang"))new java.util.concurrent.CountDownLatch(1).await();
  else new Thread(()->{try{new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException ignored){}},"leaked-helper").start();
 }
 public static void run()throws Exception{
  Path directory=Files.createTempDirectory("check-guard-test-");
  try{
   for(String mode:new String[]{"return","hang","busy"}){
    Path lock=directory.resolve(mode+".lock");
    try(var channel=java.nio.channels.FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
     var held=mode.equals("busy")?channel.lock():null;
     Process p=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-Dbackstage.checkLock="+lock,"-cp",System.getProperty("java.class.path"),DeviceCheckGuardHostTest.class.getName(),mode).redirectErrorStream(true).start();
     try{if(!p.waitFor(4,TimeUnit.SECONDS))throw new AssertionError("Helper did not exit: "+mode);int expected=mode.equals("hang")?124:mode.equals("busy")?125:0;if(p.exitValue()!=expected)throw new AssertionError("Wrong helper exit for "+mode+": "+p.exitValue());}
     finally{if(p.isAlive())p.destroyForcibly();if(held!=null)held.close();}
    }
   }
  }finally{try(var files=Files.list(directory)){for(Path p:files.collect(java.util.stream.Collectors.toList()))Files.delete(p);}Files.delete(directory);}
  System.out.println("PASS DeviceCheckGuard: leaked-thread exit, stuck-main deadline and process exclusivity");
 }
}
