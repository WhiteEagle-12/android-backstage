package com.deviceagent.tools;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;
import java.nio.file.*;
public final class ShareDeviceCheck {
 public static void main(String[] argv){
  DeviceCheckGuard.start("ShareDeviceCheck");AndroidTools tools=null;VirtualDisplayHost display=null;String session=null;int exit=1;
  Path file=Paths.get("/data/local/tmp/backstage-share-fixture.bin");
  try{
   byte[] data=new byte[1048593];new Random(742).nextBytes(data);Files.write(file,data);
   var hash=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hex=new StringBuilder();for(byte b:hash.digest(data))hex.append(String.format(Locale.ROOT,"%02x",b));
   var context=SystemContext.create("com.androidbackstage.app");display=new VirtualDisplayHost(context);
   tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-share-check/config.json");
   session=Json.string(ParityDeviceCheck.call(tools,"android.task_begin",null,Map.of("leaseMinutes",1)),"sessionId");
   var share=Map.of("mimeType","application/octet-stream","text","Share café 日本語 🙂","package",ParityDeviceCheck.FIXTURE,"activity",".ParityFixture","files",List.of(Map.of("path",file.toString(),"name","café.bin","mimeType","application/octet-stream"),Map.of("path",file.toString(),"name","second.bin","mimeType","application/octet-stream")));
   var result=ParityDeviceCheck.call(tools,"android.open",session,Map.of("share",share,"image","only"));
   ParityDeviceCheck.check("succeeded".equals(result.get("outcome")),"multiple granted files launch recipient");
   ParityDeviceCheck.expect("shareText","Share café 日本語 🙂","recipient confirms Unicode text");
   var received=new org.json.JSONArray(ParityDeviceCheck.state().get("shareFiles"));
   ParityDeviceCheck.check(received.length()==2,"recipient read both URI attachments");
   for(int i=0;i<2;i++){ParityDeviceCheck.check(received.getJSONObject(i).getLong("bytes")==data.length,"complete attachment length");ParityDeviceCheck.check(hex.toString().equals(received.getJSONObject(i).getString("sha256")),"recipient attachment SHA-256 matches source");}
   ParityDeviceCheck.check(Integer.parseInt(ParityDeviceCheck.state().get("shareDisplay"))>0,"share stays on private display");
   System.out.println("SHARE CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}finally{
   if(tools!=null&&session!=null)try{ParityDeviceCheck.call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}
   if(display!=null)display.handle("stop",new android.os.Bundle());try{Files.deleteIfExists(file);}catch(Exception ignored){}
  }System.exit(exit);
 }
}
