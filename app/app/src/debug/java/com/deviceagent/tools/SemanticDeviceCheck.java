package com.deviceagent.tools;

import android.os.Looper;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Candidate engine tests on a private display. Prints synthetic results only. */
public final class SemanticDeviceCheck {
    private static AndroidTools tools;
    private static String session;
    private static int checks;
    private static Map<String,Object> call(String name,Map<String,Object> args){
        var input=new LinkedHashMap<>(args);
        if(session!=null)input.put("sessionId",session);
        if(!Set.of("android.observe","android.wait","android.task_inspect").contains(name))input.put("requestId",UUID.randomUUID().toString());
        var out=tools.call(name,input);
        if(!"succeeded".equals(out.get("outcome")))System.out.println("RECEIPT "+Json.stringify(out));
        return out;
    }
    private static Map<String,Object> verify(String text){return Map.of("conditions",List.of(Map.of("kind","exists","selector",Map.of("label",text))));}
    private static Map<String,Object> state(String label,boolean checked){return Map.of("conditions",List.of(Map.of("kind","state","selector",Map.of("label",label),"checked",checked)));}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static Map<String,Object> control(Map<String,Object> out,String label){return ((List<?>)out.get("controls")).stream().map(PrimitiveArguments::map).filter(c->label.equals(c.get("label"))).findFirst().orElseThrow();}
    private static boolean passed(Map<String,Object> out){return "succeeded".equals(out.get("outcome"))&&"verified".equals(PrimitiveArguments.map(out.get("verification")).get("status"));}

    public static void main(String[] args){
        DeviceCheckGuard.start("SemanticDeviceCheck");
        System.setProperty("backstage.traceTargets","true");
        VirtualDisplayHost display=null;int exit=1;String fixture=args.length>0?args[0]:"com.androidbackstage.semanticqa";
        try {
            if(Looper.myLooper()==null)Looper.prepareMainLooper();var context=SystemContext.create(fixture);display=new VirtualDisplayHost(context);
            tools=new AndroidTools(new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context)),fixture,"/data/local/tmp/backstage-semantic-check/config.json");
            var begin=call("android.task_begin",Map.of("leaseMinutes",10));session=(String)begin.get("sessionId");
            check(session!=null&&((Number)begin.get("display")).intValue()>0,"private test session");
            var opened=call("android.open",Map.of("package",fixture,"activity","com.deviceagent.app.SemanticTestActivity","verify",verify("Ready")));
            check(passed(opened),"synthetic controls open with compact verification");
            check("switch".equals(control(opened,"Test switch").get("role"))&&Boolean.FALSE.equals(control(opened,"Test switch").get("checked")),"unlabeled child switch keeps its row label and off state");
            check("radio".equals(control(opened,"Test radio").get("role"))&&Boolean.FALSE.equals(control(opened,"Test radio").get("checked")),"non-clickable radio exposes row state");
            for(int i=0;i<4;i++){
                boolean checked=i%2==0;
                var switched=call("android.tap",Map.of("selector",Map.of("label","Test switch"),"verify",state("Test switch",checked)));
                check(passed(switched)&&"accessibility".equals(switched.get("inputMethod")),"native switch transition "+i);
            }
            var radio=call("android.tap",Map.of("selector",Map.of("label","Test radio"),"verify",state("Test radio",true)));
            check(passed(radio),"radio row click verifies child state");
            var delayed=call("android.tap",Map.of("selector",Map.of("label","Delayed action"),"verify",verify("Completed 1"),"timeoutMs",3000,"timing",true));
            check(passed(delayed),"delayed click completes once");
            var stillOnce=call("android.observe",Map.of("verify",verify("Completed 1"),"timeoutMs",0));check(passed(stillOnce),"no second delayed activation");
            var held=call("android.tap",Map.of("selector",Map.of("label","Long press action"),"holdMs",800,"verify",verify("Long press observed")));
            check(passed(held)&&"touch".equals(held.get("inputMethod")),"long press uses one touch sequence");
            String text="café — 日本語 😀";
            var typed=call("android.type",Map.of("selector",Map.of("label","Test text"),"text",text));check(passed(typed),"Unicode typing remains automatically verified");
            var screenshot=call("android.observe",Map.of("image","always","scale",0.5));
            var image=PrimitiveArguments.map(screenshot.get("image"));
            java.nio.file.Files.write(java.nio.file.Paths.get("/data/local/tmp/backstage-semantic-check.png"),Base64.getDecoder().decode((String)image.get("pngBase64")));
            check(image.containsKey("screenshotId")&&((Number)image.get("scaleX")).doubleValue()==0.5,"screenshot coordinate scale");
            var invalid=call("android.sequence",Map.of("steps",List.of(Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label","Delayed action"))),Map.of("tool","android.type","arguments",Map.of("selector",Map.of("label","Test text"))))));
            check("failed".equals(invalid.get("outcome"))&&Boolean.FALSE.equals(invalid.get("inputDelivered")),"invalid later sequence step prevents all input");
            var seq=call("android.sequence",Map.of("steps",List.of(Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label","Test switch"),"verify",state("Test switch",true))),Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label","Test switch"),"verify",state("Test switch",false))))));
            check("succeeded".equals(seq.get("outcome"))&&seq.get("completedSteps").equals(2),"two-action sequence shares compact conditions and state checks");
            if(Arrays.asList(args).contains("--chrome"))chrome();
            if(Arrays.asList(args).contains("--battery"))battery();
            var done=call("android.task_finish",Map.of());session=null;
            check(((List<?>)done.get("released")).contains("display"),"private display released");
            System.out.println("SEMANTIC CHECK COMPLETE "+checks);exit=0;
        } catch(Throwable e){e.printStackTrace(System.out);}
        finally {if(tools!=null&&session!=null)try{call("android.task_finish",Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}
        System.exit(exit);
    }

    private static void chrome(){
        var opened=call("android.open",Map.of("url","https://example.com","verify",verify("Learn more"),"timeoutMs",10000));
        check(passed(opened),"Chrome opens the reported example page");
        for(int i=0;i<6;i++){
            var seen=call("android.observe",Map.of("verify",Map.of("conditions",List.of(Map.of("kind","stable","selectors",List.of(Map.of("label","Learn more","role","link")),"durationMs",500))),"timeoutMs",5000));
            check(passed(seen),"Chrome source link remains stable "+i);var link=control(seen,"Learn more");
            check("link".equals(link.get("role")),"Chrome visited link role "+i);
            Map<String,Object> input=new LinkedHashMap<>(i%2==0?Map.of("selector",Map.of("label","Learn more")):Map.of("target",link.get("id"),"observation",seen.get("observation")));
            input.put("verify",Map.of("conditions",List.of(Map.of("kind","field","selector",Map.of("resourceId","com.android.chrome:id/url_bar"),"value","iana.org/help/example-domains"),Map.of("kind","exists","selector",Map.of("label","Example Domains")))));input.put("timeoutMs",8000);input.put("timing",true);
            var tapped=call("android.tap",input);
            check(passed(tapped)&&"touch".equals(tapped.get("inputMethod")),"Chrome link navigation "+i);
            check("iana.org/help/example-domains".equals(control(tapped,"Search Google or type URL").get("value")),"Chrome destination address "+i);
            System.out.println("CHROME_TAP_MS "+PrimitiveArguments.map(tapped.get("timing")).get("deviceTotalMs"));
            if(i<5)check(passed(call("android.key",Map.of("key","BACK","verify",verify("Learn more")))),"Chrome returns to source "+i);
        }
    }
    private static void battery(){
        check(passed(call("android.open",Map.of("package","com.android.settings","verify",verify("Search settings")))),"Settings opens privately");
        var found=call("android.scroll_until",Map.of("selector",Map.of("resourceId","com.android.settings:id/recycler_view"),"direction","down","until",Map.of("label","Battery"),"maxScrolls",5));
        check("succeeded".equals(found.get("outcome"))&&Boolean.TRUE.equals(found.get("found")),"Battery row found with native scrolling");
        var opened=call("android.tap",Map.of("selector",Map.of("label","Battery"),"verify",Map.of("conditions",List.of(Map.of("kind","exists","selector",Map.of("label","Power saver")),Map.of("kind","exists","selector",Map.of("label","Remaining battery"))))));
        check(passed(opened),"Battery opens with native tap");
        for(String name:List.of("Power saver","Ultra Power saver","Turbo charge","Extreme Cold Mode")){
            var c=control(opened,name);check("switch".equals(c.get("role"))&&c.get("checked") instanceof Boolean,"Redmagic switch state: "+name);
        }
        String content=Json.stringify(opened.get("content"));
        System.out.println("BATTERY_OBSERVATION "+Json.stringify(Json.mapOf("controls",opened.get("controls"),"content",opened.get("content"))));
        check(content.matches("(?s).*\\d+%.*")&&content.contains("Remaining battery")&&content.contains("items"),"Redmagic percentage and remaining-time groups");
    }
}
