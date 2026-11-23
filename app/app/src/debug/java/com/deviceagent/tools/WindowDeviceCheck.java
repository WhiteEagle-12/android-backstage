package com.deviceagent.tools;

import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Uses only its owned private display. */
public final class WindowDeviceCheck {
    static void check(boolean condition,String label){ParityDeviceCheck.check(condition,label);}
    static Map<String,Object> call(AndroidTools tools,String tool,String session,Map<String,Object> args){return ParityDeviceCheck.call(tools,tool,session,args);}
    public static void main(String[] argv){
        DeviceCheckGuard.start("WindowDeviceCheck");
        AndroidTools tools=null;VirtualDisplayHost display=null;String session=null;int exit=1;
        try{
            if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");
            display=new VirtualDisplayHost(context);tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-window-check/config.json");
            session=Json.string(call(tools,"android.task_begin",null,Map.of("leaseMinutes",3,"width",1080,"height",1920,"dpi",240)),"sessionId");check(session!=null,"private window session");
            var opened=call(tools,"android.open",session,Map.of("package","com.androidbackstage.qa","activity",".WindowFixture","verify",ParityDeviceCheckCondition.exists("SHOW DIALOG")));
            check("succeeded".equals(opened.get("outcome")),"window fixture ready");
            var dialog=call(tools,"android.tap",session,Map.of("selector",Map.of("label","SHOW DIALOG"),"verify",ParityDeviceCheckCondition.exists("CONFIRM")));check("succeeded".equals(dialog.get("outcome")),"focused dialog controls readable");
            check("succeeded".equals(call(tools,"android.tap",session,Map.of("selector",Map.of("label","CONFIRM"),"verify",ParityDeviceCheckCondition.exists("SHOW PANEL"))).get("outcome")),"dialog action restores application controls");
            var panel=call(tools,"android.tap",session,Map.of("selector",Map.of("label","SHOW PANEL")));check("succeeded".equals(panel.get("outcome")),"nonfocused panel opened");
            System.out.println("PANEL WINDOWS "+Json.stringify(panel.get("windows")));
            String selected=null;
            for(Object raw:(List<?>)panel.get("windows")){
                var window=PrimitiveArguments.map(raw);if(Boolean.TRUE.equals(window.get("focused")))continue;
                String id=Json.string(window,"windowId");if(id==null)continue;var view=call(tools,"android.observe",session,Map.of("windowId",id,"timeoutMs",0));
                if(view.get("controls") instanceof List<?> controls&&controls.stream().anyMatch(c->"PANEL ACTION".equals(PrimitiveArguments.map(c).get("label")))){selected=id;break;}
            }
            check(selected!=null,"nonfocused window selectable through public observation");
            var clicked=call(tools,"android.tap",session,Map.of("windowId",selected,"selector",Map.of("label","PANEL ACTION"),"observe",false));check("succeeded".equals(clicked.get("outcome")),"bound action in nonfocused window");
            boolean confirmed=false;for(int attempt=0;attempt<20;attempt++){var state=LocalShell.run("run-as com.androidbackstage.qa cat shared_prefs/windows.xml",null,2000);String saved=new String(state.getByteArray("stdout"),java.nio.charset.StandardCharsets.UTF_8);confirmed=saved.contains("name=\"confirmed\" value=\"true\"")&&saved.contains("name=\"panelAction\" value=\"true\"");if(confirmed)break;Thread.sleep(100);}check(confirmed,"fixture independently confirms both window actions");
            var missing=call(tools,"android.tap",session,Map.of("windowId",selected,"selector",Map.of("label","PANEL ACTION"),"observe",false));check(Boolean.FALSE.equals(missing.get("inputDelivered")),"closed window never falls back to an app control");
            var seen=call(tools,"android.observe",session,Map.of("image","always"));if(seen.get("image") instanceof Map<?,?> image)java.nio.file.Files.write(java.nio.file.Paths.get("/data/local/tmp/backstage-windows.png"),Base64.getDecoder().decode((String)image.get("pngBase64")));
            System.out.println("WINDOW CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
        }catch(Throwable e){e.printStackTrace(System.out);}
        finally{if(tools!=null&&session!=null)try{call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}
        System.exit(exit);
    }
    private static final class ParityDeviceCheckCondition {static Map<String,Object> exists(String label){return Map.of("kind","exists","selector",Map.of("label",label));}}
}
