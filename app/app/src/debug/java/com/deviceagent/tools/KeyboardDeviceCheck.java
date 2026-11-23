package com.deviceagent.tools;
import android.os.Looper;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Exercises editing through the public engine against an owned fixture. */
public final class KeyboardDeviceCheck {
 static int checks;
 static Map<String,Object> call(AndroidTools tools,String name,String session,Map<String,Object> input){
  var args=new LinkedHashMap<>(input);if(session!=null)args.put("sessionId",session);
  if(!Set.of("android.observe","android.wait").contains(name))args.put("requestId",UUID.randomUUID().toString());
  var result=tools.call(name,args);if(!"succeeded".equals(result.get("outcome")))System.out.println("RECEIPT "+Json.stringify(result));return result;
 }
 static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[] argv){
        DeviceCheckGuard.start("KeyboardDeviceCheck");
  VirtualDisplayHost display=null;AndroidTools tools=null;String session=null;int exit=1;
  try{
   if(Looper.myLooper()==null)Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");display=new VirtualDisplayHost(context);
   tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-parity-check/config.json");
   session=(String)call(tools,"android.task_begin",null,Map.of("leaseMinutes",3)).get("sessionId");check(session!=null,"owned private session");
   var opened=call(tools,"android.open",session,Map.of("package","com.androidbackstage.app","activity","com.deviceagent.app.EvidenceTestActivity","verify",Map.of("kind","exists","selector",Map.of("label","Ready"))));check("succeeded".equals(opened.get("outcome")),"fixture readiness");
   call(tools,"android.type",session,Map.of("selector",Map.of("label","Test text"),"text","replace me"));
   long started=android.os.SystemClock.elapsedRealtime();var view=call(tools,"android.observe",session,Map.of("image","only","scale",0.5));
   check("not_requested".equals(view.get("accessibility"))&&view.containsKey("image"),"pixel-only capture");
   System.out.println("PIXEL_CAPTURE_MS "+(android.os.SystemClock.elapsedRealtime()-started));
   var shot=PrimitiveArguments.map(view.get("image")).get("screenshotId");
   var selected=call(tools,"android.key",session,Map.of("key","A","modifiers",List.of("CTRL"),"screenshotId",shot,"observe",false));check("succeeded".equals(selected.get("outcome"))&&Boolean.TRUE.equals(selected.get("keysReleased")),"Ctrl+A delivered and released");
   var typed=call(tools,"android.key",session,Map.of("key","Z","modifiers",List.of("SHIFT"),"screenshotId",shot,"verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","Z")));
   check("succeeded".equals(typed.get("outcome")),"Shift+Z replaces selection");
   typed=call(tools,"android.key",session,Map.of("key","B","repeat",3,"verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","Zbbb")));
   check("succeeded".equals(typed.get("outcome")),"key repeats and no stuck Shift");
   typed=call(tools,"android.key",session,Map.of("key","MOVE_HOME","modifiers",List.of("SHIFT"),"verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","Zbbb")));
   check("succeeded".equals(typed.get("outcome")),"Shift+Home selection");
   typed=call(tools,"android.key",session,Map.of("key","DEL","verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","")));
   check("succeeded".equals(typed.get("outcome")),"Delete removes selected text");
   if(context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked()){
    System.out.println("KEYBOARD CHECK COMPLETE "+checks+"; clipboard checks require unlock");exit=0;
   }else{
   shot=PrimitiveArguments.map(call(tools,"android.observe",session,Map.of("image","only")).get("image")).get("screenshotId");
   typed=call(tools,"android.type",session,Map.of("focused",true,"screenshotId",shot,"clipboard",true,"mode","insert","text","café 日本語 🙂 e\u0301","verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","café 日本語 🙂 e\u0301")));
   check("succeeded".equals(typed.get("outcome"))&&Boolean.TRUE.equals(typed.get("clipboardRestored")),"focused Unicode paste and clipboard restoration");
   typed=call(tools,"android.type",session,Map.of("focused",true,"screenshotId",shot,"clipboard",true,"mode","insert","text","","observe",false));
   check("succeeded".equals(typed.get("outcome"))&&Boolean.FALSE.equals(typed.get("inputDelivered")),"empty insertion sends no input");
   typed=call(tools,"android.type",session,Map.of("focused",true,"screenshotId",shot,"clipboard",true,"mode","replace","text","replaced 日本語","verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","replaced 日本語")));
   check("succeeded".equals(typed.get("outcome"))&&Boolean.TRUE.equals(typed.get("clipboardRestored")),"focused Unicode replacement");
   System.out.println("KEYBOARD CHECK COMPLETE "+checks);exit=0;
   }
  }catch(Throwable e){e.printStackTrace(System.out);}
  finally{if(tools!=null&&session!=null)try{call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}
  System.exit(exit);
 }
}
