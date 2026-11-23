package com.deviceagent.tools;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;
public final class DeviceControlCheck {
 static Map<String,Object> call(AndroidTools t,Map<String,Object> a){var args=new LinkedHashMap<>(a);if(!"status".equals(a.get("operation")))args.put("requestId",UUID.randomUUID().toString());var r=t.call("android.device_control",args);System.out.println("CONTROL "+Json.stringify(r));return r;}
 public static void main(String[] argv){
  DeviceCheckGuard.start("DeviceControlCheck");AndroidTools tools=null;String session=null;int before=-1,exit=1;
  try{
   var context=SystemContext.create("com.androidbackstage.app");tools=new AndroidTools(new LocalExecutor(new VirtualDisplayHost(context),new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-control-check/config.json");
   var initial=call(tools,Map.of("operation","status"));var volume=PrimitiveArguments.map(PrimitiveArguments.map(initial.get("volume")).get("media"));before=((Number)volume.get("value")).intValue();int max=((Number)volume.get("max")).intValue(),min=((Number)volume.get("min")).intValue();
   var begin=tools.call("android.task_begin",Map.of("mode","foreground","confirmed",true,"leaseMinutes",1,"requestId",UUID.randomUUID().toString()));System.out.println("BEGIN "+Json.stringify(begin));session=Json.string(begin,"sessionId");ParityDeviceCheck.check(session!=null&&Integer.valueOf(0).equals(begin.get("display")),"device controls acquire foreground lease");
   int changed=before<max?before+1:Math.max(min,before-1);var result=call(tools,Map.of("operation","volume","stream","media","value",changed,"sessionId",session));ParityDeviceCheck.check(Integer.valueOf(changed).equals(result.get("value")),"volume change read back");
   result=call(tools,Map.of("operation","volume","stream","media","value",before,"sessionId",session));ParityDeviceCheck.check(Integer.valueOf(before).equals(result.get("value")),"original volume restored");
   result=call(tools,Map.of("operation","rotation","rotation",Boolean.TRUE.equals(initial.get("autoRotate"))?"auto":String.valueOf(initial.get("rotation")),"sessionId",session));ParityDeviceCheck.check("succeeded".equals(result.get("outcome")),"current rotation mode accepted");
   result=call(tools,Map.of("operation","wifi","enabled",initial.get("wifi"),"sessionId",session));ParityDeviceCheck.check(Boolean.FALSE.equals(result.get("inputDelivered")),"Wi-Fi state retained without disconnecting");
   result=call(tools,Map.of("operation","bluetooth","enabled",initial.get("bluetooth"),"sessionId",session));ParityDeviceCheck.check(Boolean.FALSE.equals(result.get("inputDelivered")),"Bluetooth state retained");
   tools.call("android.task_finish",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString()));
   result=call(tools,Map.of("operation","volume","stream","media","value",before,"sessionId",session));ParityDeviceCheck.check("failed".equals(result.get("outcome")),"ended lease blocks device changes");
   System.out.println("DEVICE CONTROL CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}finally{
   if(tools!=null&&session!=null)try{if(before>=0)call(tools,Map.of("operation","volume","stream","media","value",before,"sessionId",session));tools.call("android.task_finish",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString()));}catch(Exception ignored){}
  }System.exit(exit);
 }
}
