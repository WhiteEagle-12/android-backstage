package com.deviceagent.tools;

import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Proves that an expiring call releases real fingers and modifiers in an owned fixture. */
public final class InputDeadlineDeviceCheck {
    public static void main(String[] argv){
        DeviceCheckGuard.start("InputDeadlineDeviceCheck");
        AndroidTools tools=null;VirtualDisplayHost display=null;String session=null;int exit=1;
        try{
            if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");display=new VirtualDisplayHost(context);
            tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-input-deadline-check/config.json");
            session=Json.string(ParityDeviceCheck.call(tools,"android.task_begin",null,Map.of("leaseMinutes",3,"width",1080,"height",1920,"dpi",240)),"sessionId");
            var opened=ParityDeviceCheck.call(tools,"android.open",session,Map.of("package",ParityDeviceCheck.FIXTURE,"activity",".ParityFixture","image","only"));ParityDeviceCheck.check("succeeded".equals(opened.get("outcome")),"deadline fixture ready");
            String shot=Json.string(ParityDeviceCheck.capture(tools,session),"screenshotId");Map<String,Object> result;
            try(var deadline=ActionDeadline.until(System.currentTimeMillis()+2500)){
                result=ParityDeviceCheck.call(tools,"android.gesture",session,Map.of("screenshotId",shot,"preset","swipe","x1",100,"y1",240,"x2",200,"y2",300,"durationMs",5000,"observe",false));
            }
            ParityDeviceCheck.check(result.get("inputDelivered")==null&&Boolean.TRUE.equals(result.get("pointersReleased")),"expired gesture reports interruption and releases its finger");
            ParityDeviceCheck.expect("pointers","1","fixture receives the cancelled gesture");long duration=Long.parseLong(ParityDeviceCheck.state().get("duration"));ParityDeviceCheck.check(duration>=500&&duration<3500,"fixture confirms gesture stopped before its five-second duration");
            shot=Json.string(ParityDeviceCheck.capture(tools,session),"screenshotId");
            try(var deadline=ActionDeadline.until(System.currentTimeMillis()+1500)){
                result=ParityDeviceCheck.call(tools,"android.key",session,Map.of("screenshotId",shot,"key","A","modifiers",List.of("CTRL"),"holdMs",3000,"observe",false));
            }
            ParityDeviceCheck.check(result.get("inputDelivered")==null&&Boolean.TRUE.equals(result.get("keysReleased")),"expired key hold releases every modifier");
            shot=Json.string(ParityDeviceCheck.capture(tools,session),"screenshotId");
            result=ParityDeviceCheck.call(tools,"android.key",session,Map.of("screenshotId",shot,"key","B","observe",false));ParityDeviceCheck.check("succeeded".equals(result.get("outcome")),"next key remains usable");
            ParityDeviceCheck.expect("text","b","fixture confirms no stuck Ctrl after interruption");
            System.out.println("INPUT DEADLINE CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
        }catch(Throwable error){error.printStackTrace(System.out);}
        finally{if(tools!=null&&session!=null)try{ParityDeviceCheck.call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}
        System.exit(exit);
    }
}
