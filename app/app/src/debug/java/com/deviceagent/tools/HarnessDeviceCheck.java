package com.deviceagent.tools;
import android.os.Looper;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** On-device acceptance through the rebuilt public tool boundary. */
public final class HarnessDeviceCheck {
 private static int checks=0;
 private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 private static Map<String,Object> invoke(AndroidTools tools,String name,Map<String,Object> input,String session){var args=new LinkedHashMap<>(input);if(session!=null)args.put("sessionId",session);if(!Set.of("android.observe","android.wait","android.task_inspect","android.packages","android.logs","android.devices").contains(name))args.put("requestId",UUID.randomUUID().toString());var result=tools.call(name,args);if(!"succeeded".equals(result.get("outcome")))System.out.println("RECEIPT "+Json.stringify(result));return result;}
 private static Map<String,Object> exists(String label){return Map.of("kind","exists","selector",Map.of("label",label));}
 @SuppressWarnings("unchecked") private static String target(Map<String,Object> view,String label){return (String)((List<Map<String,Object>>)view.get("controls")).stream().filter(c->label.equalsIgnoreCase((String)c.get("label"))).findFirst().orElseThrow().get("id");}
 @SuppressWarnings("unchecked") private static String controlLabel(Map<String,Object> view,String label){return (String)((List<Map<String,Object>>)view.get("controls")).stream().filter(c->label.equalsIgnoreCase((String)c.get("label"))).findFirst().orElseThrow().get("label");}
 public static void main(String[] args){
        DeviceCheckGuard.start("HarnessDeviceCheck");
  VirtualDisplayHost display=null;AndroidTools tools=null;String session=null;int exit=1;String fixture=args.length>0?args[0]:"com.androidbackstage.app";
  try{
   if(Looper.myLooper()==null)Looper.prepareMainLooper();var context=SystemContext.create(fixture);display=new VirtualDisplayHost(context);
   tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),fixture,"/data/local/tmp/backstage-harness-check/config.json");
   var begun=invoke(tools,"android.task_begin",Map.of("leaseMinutes",2),null);session=(String)begun.get("sessionId");check("succeeded".equals(begun.get("outcome"))&&session!=null,"session creates a private display");
   var opened=invoke(tools,"android.open",Map.of("package",fixture,"activity","com.deviceagent.app.EvidenceTestActivity","verify",exists("Ready"),"timeoutMs",10000),session);check("succeeded".equals(opened.get("outcome")),"open activity and generic readiness");
   var first=invoke(tools,"android.observe",Map.of(),session);check(first.containsKey("observation")&&first.containsKey("controls")&&!first.containsKey("ui")&&!first.containsKey("ok")&&!first.containsKey("elapsedMs"),"compact canonical observation");
   String checkLabel=controlLabel(first,"Check action"),selectLabel=controlLabel(first,"Select middle");
   String button=target(first,"Check action");
   var clicked=invoke(tools,"android.tap",Map.of("observation",first.get("observation"),"target",button,"verify",exists("Action observed")),session);check("succeeded".equals(clicked.get("outcome"))&&Boolean.TRUE.equals(clicked.get("inputDelivered")),"native target click and verification");
   var stable=invoke(tools,"android.tap",Map.of("observation",first.get("observation"),"target",button),session);check("succeeded".equals(stable.get("outcome")),"target survives unrelated status change");
   var miss=invoke(tools,"android.tap",Map.of("selector",Map.of("label","Does not exist")),session);check("target_not_found".equals(miss.get("code"))&&Boolean.FALSE.equals(miss.get("inputDelivered")),"missing selector sends no input");
   var invalid=invoke(tools,"android.open",Map.of("url","javascript:alert(1)"),session);check("invalid_argument".equals(invalid.get("code"))&&Boolean.FALSE.equals(invalid.get("inputDelivered")),"unsupported URL sends no input");
   String unicode="café 日本語 🙂 e\u0301";
   var typed=invoke(tools,"android.type",Map.of("selector",Map.of("label","Test text"),"text",unicode),session);check("succeeded".equals(typed.get("outcome"))&&Boolean.TRUE.equals(PrimitiveArguments.map(typed.get("verification")).get("matched")),"targeted Unicode replacement and value verification");
   var selected=invoke(tools,"android.tap",Map.of("selector",Map.of("label",selectLabel),"verify",Map.of("all",List.of(Map.of("kind","field","selector",Map.of("label","Test text"),"value","left OLD right"),Map.of("kind","stable","selectors",List.of(Map.of("label","Test text")),"durationMs",500)))),session);
   check("succeeded".equals(selected.get("outcome")),"selected field is ready for insertion");
   var inserted=invoke(tools,"android.type",Map.of("selector",Map.of("label","Test text"),"text","日本語","mode","insert","verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","left 日本語 right")),session);check("succeeded".equals(inserted.get("outcome")),"insertion preserves selection surroundings");
   var sequence=invoke(tools,"android.sequence",Map.of("steps",List.of(Map.of("tool","android.type","arguments",Map.of("selector",Map.of("label","Test text"),"text","sequence")),Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label",checkLabel),"verify",exists("Action observed"))))),session);check("succeeded".equals(sequence.get("outcome"))&&sequence.get("completedSteps").equals(2),"sequence uses the same type and tap primitives");
   var stopped=invoke(tools,"android.sequence",Map.of("steps",List.of(Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label",checkLabel),"verify",exists("Missing"),"timeoutMs",0)),Map.of("tool","android.type","arguments",Map.of("selector",Map.of("label","Test text"),"text","wrong")))),session);check("failed".equals(stopped.get("outcome"))&&stopped.get("completedSteps").equals(0)&&Boolean.TRUE.equals(stopped.get("inputDelivered")),"sequence stops after failed verification with delivery retained");
   var unchanged=invoke(tools,"android.observe",Map.of("verify",Map.of("kind","field","selector",Map.of("label","Test text"),"value","sequence"),"timeoutMs",0),session);check("succeeded".equals(unchanged.get("outcome")),"later sequence write did not run");
   var ownership=tools.callOwned("android.observe",Map.of("sessionId",session),"other");check("session_not_owned".equals(ownership.get("code")),"foreign session is rejected");
   var debug=invoke(tools,"android.observe",Map.of("detail","debug","image","always","scale",0.5),session);check(debug.containsKey("ui")&&debug.get("image") instanceof Map,"debug tree and unified image capture");
   var image=PrimitiveArguments.map(debug.get("image"));check(image.containsKey("screenshotId")&&image.containsKey("scaleX")&&image.containsKey("sourceWidth"),"capture metadata and coordinate mapping");
   java.nio.file.Files.write(java.nio.file.Paths.get("/data/local/tmp/backstage-harness-check.png"),Base64.getDecoder().decode((String)image.get("pngBase64")));
   var inspected=invoke(tools,"android.task_inspect",Map.of(),session);check(inspected.containsKey("activityTasks")&&inspected.containsKey("leaseExpiresAt"),"session inspection includes activity tasks and lease");
   var rows=(List<?>)inspected.get("activityTasks");var task=rows.stream().map(PrimitiveArguments::map).filter(t->fixture.equals(t.get("package"))).findFirst().orElseThrow();
   var reopened=invoke(tools,"android.open",Map.of("activityTaskId",task.get("activityTaskId"),"verify",exists("Action observed"),"timeoutMs",5000),session);
   check("succeeded".equals(reopened.get("outcome"))&&"ready".equals(reopened.get("readiness"))&&Boolean.TRUE.equals(reopened.get("launchAccepted")),"exact owned activity reopens with verified readiness");
   var foreign=invoke(tools,"android.open",Map.of("activityTaskId",2147483647),session);check(Boolean.FALSE.equals(foreign.get("inputDelivered"))&&"task_not_owned".equals(foreign.get("code")),"foreign activity cannot reopen");
   var unverified=invoke(tools,"android.open",Map.of("activityTaskId",task.get("activityTaskId")),session);check("unverified".equals(unverified.get("readiness"))&&Boolean.TRUE.equals(unverified.get("launchAccepted")),"launch acceptance does not claim readiness");
   var shell=invoke(tools,"android.shell",Map.of("command","printf output; printf error >&2; exit 7"),null);check(shell.get("exitCode").equals(7)&&shell.get("stdout").equals("output")&&shell.get("stderr").equals("error"),"shell exit and streams preserved");
   if(Arrays.asList(args).contains("--location")){
    var cache=tools.call("android.location",Map.of("maxAgeMs",0,"timeoutMs",0));
    var cachedFix=PrimitiveArguments.map(cache.get("fix"));
    check(!"succeeded".equals(cache.get("outcome"))||((Number)cachedFix.get("ageMs")).longValue()==0,"cache-only location never accepts stale data");
    long locationStart=android.os.SystemClock.elapsedRealtime();
    var current=tools.call("android.location",Map.of("maxAgeMs",1000,"accuracyMeters",100));
    long locationElapsed=android.os.SystemClock.elapsedRealtime()-locationStart;
    var fix=PrimitiveArguments.map(current.get("fix"));
    System.out.println("LOCATION "+Json.stringify(Json.mapOf("outcome",current.get("outcome"),"elapsedMs",locationElapsed,"source",fix.get("source"),"provider",fix.get("provider"),"ageMs",fix.get("ageMs"),"accuracyMeters",fix.get("accuracyMeters"),"code",current.get("code"))));
    check(locationElapsed<22000,"location respects the request deadline");
    check("succeeded".equals(current.get("outcome"))&&((Number)fix.get("ageMs")).longValue()<=1000&&((Number)fix.get("accuracyMeters")).doubleValue()<=100,"fresh location meets requested limits");
   }
   var finished=invoke(tools,"android.task_finish",Map.of(),session);check("succeeded".equals(finished.get("outcome"))&&((List<?>)finished.get("released")).contains("display"),"finish releases owned display");
   var again=invoke(tools,"android.task_finish",Map.of(),session);check(((List<?>)again.get("released")).isEmpty(),"finish is idempotent");session=null;
   System.out.println("HARNESS CHECK COMPLETE "+checks);exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}
  finally{if(tools!=null&&session!=null)try{invoke(tools,"android.task_finish",Map.of(),session);}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}
  System.exit(exit);
 }
}
