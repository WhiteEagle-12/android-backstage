package com.deviceagent.tools;
import android.os.Looper;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Regression receipts contain synthetic inputs and counts, never personal content. */
public final class UsabilityDeviceCheck {
 private static AndroidTools tools;private static String session;private static int checks;
 private static Map<String,Object> call(String name,Map<String,Object> args){var input=new LinkedHashMap<>(args);if(PrimitiveArguments.TOOLS.contains(name)||name.equals("android.task_finish"))input.put("sessionId",session);if(ToolCatalog.schema(name).toString().contains("requestId"))input.put("requestId",UUID.randomUUID().toString());return tools.call(name,input);}
 private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 private static Map<String,Object> exists(String label){return Map.of("kind","exists","selector",Map.of("label",label));}
 private static boolean has(Map<String,Object> out,String label){return out.get("controls") instanceof List<?> rows&&rows.stream().map(PrimitiveArguments::map).anyMatch(c->label.equals(c.get("label")));}
 public static void main(String[] args){
        DeviceCheckGuard.start("UsabilityDeviceCheck");VirtualDisplayHost display=null;int exit=1;boolean draft=false;
  try{
   if(Looper.myLooper()==null)Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");display=new VirtualDisplayHost(context);
   tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-usability-check/config.json");
   long start=System.nanoTime();var packages=call("android.packages",Map.of("query","google.android","fields",List.of("package")));
   check("succeeded".equals(packages.get("outcome"))&&((List<?>)packages.get("packages")).size()>0,"package search with selected fields");
   System.out.println("PACKAGE_QUERY_MS "+(System.nanoTime()-start)/1000000);
   var single=call("android.packages",Map.of("package","com.google.android.contacts"));check(single.get("versionName")!=null,"single package version lookup");
   var missing=call("android.packages",Map.of("package","com.example.backstage.nonexistent"));check("package_not_found".equals(missing.get("code")),"missing package reports failure");
   var clipboard=call("android.clipboard",Map.of());check("clipboard_unavailable".equals(clipboard.get("code"))&&"failed".equals(clipboard.get("outcome")),"unsupported clipboard is a failure");
   var notifications=call("android.notification",Map.of("max",2));check("succeeded".equals(notifications.get("outcome"))&&notifications.containsKey("notifications")&&!notifications.containsKey("stdout")&&!notifications.containsKey("stderr"),"notifications contain records without diagnostics");
   check(((List<?>)notifications.get("notifications")).size()<=2,"notification limit counts records");
   session=(String)call("android.task_begin",Map.of("leaseMinutes",5)).get("sessionId");check(session!=null,"private session begins");
   var opened=call("android.open",Map.of("package","com.google.android.contacts","verify",exists("Create contact")));check("succeeded".equals(opened.get("outcome")),"Contacts opens");
   var create=call("android.tap",Map.of("selector",Map.of("label","Create contact"),"verify",exists("First name")));draft=true;check("succeeded".equals(create.get("outcome")),"empty contact draft exposes its hint");
   var typed=call("android.type",Map.of("selector",Map.of("label","First name"),"text","Backstage check","verify",Map.of("kind","field","selector",Map.of("label","First name"),"value","Backstage check")));
   check("succeeded".equals(typed.get("outcome"))&&Boolean.TRUE.equals(PrimitiveArguments.map(typed.get("verification")).get("matched")),"Contacts name verifies without its hint");
   var observed=call("android.observe",Map.of("verify",Map.of("kind","field","selector",Map.of("label","First name"),"value","Backstage check"),"timeoutMs",0));check("succeeded".equals(observed.get("outcome")),"later observation keeps the field label and value separate");
   var image=call("android.observe",Map.of("image","if_needed"));check(image.containsKey("image"),"unlabeled controls request a screenshot");
   var cancel=call("android.tap",Map.of("selector",Map.of("label","Cancel")));check(has(cancel,"Discard"),"Cancel returns the discard dialog without another observe");
   var discarded=call("android.tap",Map.of("selector",Map.of("label","Discard"),"verify",exists("Create contact")));check("succeeded".equals(discarded.get("outcome")),"test contact discarded");draft=false;
   call("android.task_finish",Map.of());session=null;System.out.println("USABILITY CHECK COMPLETE "+checks);exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}
  finally{
   if(draft&&tools!=null&&session!=null)try{var view=call("android.observe",Map.of());if(has(view,"Cancel"))call("android.tap",Map.of("selector",Map.of("label","Cancel"),"verify",exists("Discard")));view=call("android.observe",Map.of());if(has(view,"Discard"))call("android.tap",Map.of("selector",Map.of("label","Discard"),"verify",exists("Create contact")));}catch(Exception e){System.out.println("CLEANUP NEEDS CHECK");}
   if(tools!=null&&session!=null)try{call("android.task_finish",Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());
  }System.exit(exit);
 }
}
