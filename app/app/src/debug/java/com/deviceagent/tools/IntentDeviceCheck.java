package com.deviceagent.tools;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;
public final class IntentDeviceCheck {
 public static void main(String[] argv){
        DeviceCheckGuard.start("IntentDeviceCheck");AndroidTools tools=null;VirtualDisplayHost display=null;String session=null;int exit=1;
  try{
   if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");display=new VirtualDisplayHost(context);
   tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-intent-check/config.json");
   session=Json.string(ParityDeviceCheck.call(tools,"android.task_begin",null,Map.of("leaseMinutes",3)),"sessionId");
   String text="Intent café 日本語 🙂; $(echo not-a-command)";
   var result=ParityDeviceCheck.call(tools,"android.open",session,Map.of("intent",Map.of("action","android.intent.action.SEND","package",ParityDeviceCheck.FIXTURE,"activity",".ParityFixture","mimeType","text/plain","extras",List.of(Map.of("name","android.intent.extra.TEXT","type","string","value",text))),"image","only"));
   ParityDeviceCheck.check("succeeded".equals(result.get("outcome")),"typed share intent opens owned fixture");
   ParityDeviceCheck.expect("shareText",text,"recipient confirms exact Unicode and literal shell characters");
   ParityDeviceCheck.check(Integer.parseInt(ParityDeviceCheck.state().get("shareDisplay"))>0,"intent remains on private display");
   System.out.println("INTENT CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}finally{if(tools!=null&&session!=null)try{ParityDeviceCheck.call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}System.exit(exit);
 }
}
