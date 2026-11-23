package com.deviceagent.tools;

import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Run only with the user's explicit permission to test the main screen. */
public final class ForegroundDeviceCheck {
    static void check(boolean condition,String label){ParityDeviceCheck.check(condition,label);}
    static Map<String,Object> call(AndroidTools tools,String name,String session,Map<String,Object> args){return ParityDeviceCheck.call(tools,name,session,args);}
    static Integer focusedTask(Map<String,Object> inspect,String component){
        for(Object raw:(List<?>)inspect.getOrDefault("activityTasks",List.of())){var task=PrimitiveArguments.map(raw);if(Boolean.TRUE.equals(task.get("visible"))&&component.equals(task.get("component")))return ((Number)task.get("activityTaskId")).intValue();}return null;
    }
    public static void main(String[] argv){
        DeviceCheckGuard.start("ForegroundDeviceCheck");
        AndroidTools tools=null;String session=null;Integer previousTask=null;boolean restored=false,wasOn=true;int exit=1;
        try{
            if(!Arrays.asList(argv).contains("--user-authorized"))throw new IllegalArgumentException("Explicit user authorization is required");
            if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");
            if(context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("PHONE_LOCKED: unlock the phone to complete the foreground fixture checks");
            var display=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);wasOn=display.getState()==android.view.Display.STATE_ON;
            tools=new AndroidTools(new LocalExecutor(new VirtualDisplayHost(context),new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-foreground-check/config.json");
            var started=call(tools,"android.task_begin",null,Map.of("mode","foreground","confirmed",true,"leaseMinutes",3));session=Json.string(started,"sessionId");check("succeeded".equals(started.get("outcome"))&&Integer.valueOf(0).equals(started.get("display")),"explicit main-screen lease");
            var inspect=call(tools,"android.task_inspect",session,Map.of());String previous=Json.string(inspect,"focusedComponent");previousTask=focusedTask(inspect,previous);check(previousTask!=null,"previous screen task saved for restoration");
            var listed=call(tools,"android.notification",null,Map.of("package","com.androidbackstage.app"));check(((List<?>)listed.get("notifications")).stream().anyMatch(r->"Screen control".equals(PrimitiveArguments.map(r).get("title"))),"screen-control notification visible");
            if(!wasOn)call(tools,"android.key",session,Map.of("key","WAKEUP","observe",false));
            if(context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("PHONE_LOCKED: unlock the phone to complete the foreground fixture checks");
            var opened=call(tools,"android.open",session,Map.of("package","com.androidbackstage.qa","activity",".WindowFixture","verify",Map.of("kind","exists","selector",Map.of("label","SHOW DIALOG"))));check("succeeded".equals(opened.get("outcome")),"fixture opened on main screen");
            var typed=call(tools,"android.type",session,Map.of("selector",Map.of("label","Note"),"text","Foreground café 日本語 🙂","mode","replace"));check("succeeded".equals(typed.get("outcome")),"Unicode field input on main screen");
            check("succeeded".equals(call(tools,"android.tap",session,Map.of("selector",Map.of("label","OPEN KEYBOARD"))).get("outcome")),"keyboard requested in foreground session");
            Map<String,Object> seen=call(tools,"android.observe",session,Map.of("image","always"));String ime=null;
            for(Object raw:(List<?>)seen.getOrDefault("windows",List.of())){var w=PrimitiveArguments.map(raw);if("ime".equals(w.get("type")))ime=Json.string(w,"windowId");}
            check(ime!=null,"keyboard window exposed");var keyboard=call(tools,"android.observe",session,Map.of("windowId",ime));check("succeeded".equals(keyboard.get("outcome"))&&keyboard.get("controls") instanceof List<?> controls&&!controls.isEmpty(),"keyboard controls readable by window selection");
            if(seen.get("image") instanceof Map<?,?> image)java.nio.file.Files.write(java.nio.file.Paths.get("/data/local/tmp/backstage-foreground-fixture.png"),Base64.getDecoder().decode((String)image.get("pngBase64")));
            var snapshot=call(tools,"android.task_inspect",session,Map.of());Integer fixtureTask=focusedTask(snapshot,Json.string(snapshot,"focusedComponent"));check(fixtureTask!=null,"fixture task identity found");
            check("succeeded".equals(call(tools,"android.key",session,Map.of("key","HOME","observe",false)).get("outcome")),"global Home key through foreground session");
            check("succeeded".equals(call(tools,"android.open",session,Map.of("activityTaskId",fixtureTask,"verify",Map.of("kind","exists","selector",Map.of("label","SHOW DIALOG")))).get("outcome")),"existing foreground task reopened");
            var saved=call(tools,"android.open",session,Map.of("activityTaskId",previousTask,"observe",false));restored="succeeded".equals(saved.get("outcome"));check(restored,"previous screen restored");
            if(!wasOn)call(tools,"android.key",session,Map.of("key","SLEEP","observe",false));
            listed=call(tools,"android.notification",null,Map.of("package","com.androidbackstage.app"));var notice=((List<?>)listed.get("notifications")).stream().map(PrimitiveArguments::map).filter(r->"Screen control".equals(r.get("title"))).findFirst().orElseThrow();
            var stopped=call(tools,"android.notification",session,Map.of("operation","action","notificationId",notice.get("notificationId"),"actionId","action_0"));check("succeeded".equals(stopped.get("outcome")),"visible Stop screen control action invoked");
            Thread.sleep(200);var rejected=call(tools,"android.key",session,Map.of("key","HOME","observe",false));check(Boolean.FALSE.equals(rejected.get("inputDelivered")),"stopped session sends no further input");
            check("succeeded".equals(call(tools,"android.notification",null,Map.of("package","com.androidbackstage.app")).get("outcome")),"background access remains available after screen control stops");
            System.out.println("FOREGROUND CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
        }catch(Throwable e){e.printStackTrace(System.out);}
        finally{
            if(tools!=null&&session!=null){
                if(!restored&&previousTask!=null)try{call(tools,"android.open",session,Map.of("activityTaskId",previousTask,"observe",false));if(!wasOn)call(tools,"android.key",session,Map.of("key","SLEEP","observe",false));}catch(Exception ignored){}
                try{call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}
                LocalShell.run("am force-stop com.androidbackstage.qa",null,2000);
            }
        }
        System.exit(exit);
    }
}
