package com.deviceagent.tools;

import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Acceptance against a separate app. Fixture state is independent of action receipts. */
public final class ParityDeviceCheck {
    static final String FIXTURE="com.androidbackstage.qa";
    static int checks;
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static Map<String,Object> call(AndroidTools tools,String name,String session,Map<String,Object> input){
        var args=new LinkedHashMap<>(input);if(session!=null)args.put("sessionId",session);
        if(!Set.of("android.observe","android.wait","android.task_inspect").contains(name)&&!(name.equals("android.notification")&&!args.containsKey("operation")))args.put("requestId",UUID.randomUUID().toString());
        var result=tools.call(name,args);if(!"succeeded".equals(result.get("outcome")))System.out.println("RECEIPT "+Json.stringify(result));return result;
    }
    static Map<String,String> state() throws Exception {
        var read=LocalShell.run("run-as "+FIXTURE+" cat shared_prefs/parity.xml",null,2000);byte[] bytes=read.getByteArray("stdout");
        var parser=android.util.Xml.newPullParser();parser.setInput(new java.io.StringReader(bytes==null?"":new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
        Map<String,String> state=new HashMap<>();int event;
        while((event=parser.next())!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(event==org.xmlpull.v1.XmlPullParser.START_TAG){String name=parser.getAttributeValue(null,"name");if(name!=null)state.put(name,parser.getName().equals("string")?parser.nextText():parser.getAttributeValue(null,"value"));}
        return state;
    }
    static void expect(String key,String value,String label) throws Exception {for(int i=0;i<20;i++){if(value.equals(state().get(key))){check(true,label);return;}Thread.sleep(100);}throw new AssertionError(label+"; fixture reports "+state().get(key));}
    static Map<String,Object> capture(AndroidTools tools,String session){return PrimitiveArguments.map(call(tools,"android.observe",session,Map.of("image","only","scale",0.5)).get("image"));}
    public static void main(String[] argv){
        DeviceCheckGuard.start("ParityDeviceCheck");
        AndroidTools tools=null;VirtualDisplayHost display=null;String session=null;int exit=1;
        try{
            if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();var context=SystemContext.create("com.androidbackstage.app");
            display=new VirtualDisplayHost(context);var executor=new LocalExecutor(display,new AccessibilityHost(),new InteractionHost(context));
            tools=new AndroidTools(executor,"com.androidbackstage.app","/data/local/tmp/backstage-parity-workflows/config.json");
            session=(String)call(tools,"android.task_begin",null,Map.of("leaseMinutes",5,"width",1080,"height",1920,"dpi",240)).get("sessionId");check(session!=null,"private fixture session");
            var opened=call(tools,"android.open",session,Map.of("package",FIXTURE,"activity",".ParityFixture","image","only"));check("succeeded".equals(opened.get("outcome")),"open inaccessible fixture using pixels");
            var image=capture(tools,session);String shot=Json.string(image,"screenshotId");
            java.nio.file.Files.write(java.nio.file.Paths.get("/data/local/tmp/backstage-parity-canvas.png"),Base64.getDecoder().decode(Json.string(image,"pngBase64")));
            var typed=call(tools,"android.type",session,Map.of("focused",true,"screenshotId",shot,"clipboard",true,"mode","insert","text","Canvas café 日本語 🙂","image","only"));
            var textReceipt=new LinkedHashMap<>(typed);textReceipt.remove("image");System.out.println("TEXT RECEIPT "+Json.stringify(textReceipt));
            if(context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked()){
                check("phone_locked".equals(typed.get("code"))&&Boolean.FALSE.equals(typed.get("inputDelivered")),"locked phone rejects clipboard paste before input");
                expect("text","","fixture confirms locked paste sends no text");
            }else{
                check("succeeded".equals(typed.get("outcome"))&&Boolean.TRUE.equals(typed.get("clipboardRestored")),"Unicode input without an accessible field");
                expect("text","Canvas café 日本語 🙂","fixture independently confirms Unicode");
            }
            shot=Json.string(capture(tools,session),"screenshotId");
            var gesture=call(tools,"android.gesture",session,Map.of("screenshotId",shot,"preset","swipe","x1",100,"y1",240,"x2",200,"y2",300,"durationMs",250,"image","only"));
            check("succeeded".equals(gesture.get("outcome")),"screenshot swipe on canvas");expect("pointers","1","canvas received one pointer");
            check(Math.abs(Float.parseFloat(state().get("endX"))-360)<2,"canvas confirms screenshot x scaling");
            shot=Json.string(capture(tools,session),"screenshotId");
            gesture=call(tools,"android.gesture",session,Map.of("screenshotId",shot,"preset","pinch","centerX",240,"centerY",300,"startSpan",60,"endSpan",180,"durationMs",300,"image","only"));
            check("succeeded".equals(gesture.get("outcome")),"screenshot pinch on canvas");expect("pointers","2","canvas received two pointers");
            shot=Json.string(capture(tools,session),"screenshotId");
            gesture=call(tools,"android.gesture",session,Map.of("screenshotId",shot,"preset","paths","paths",List.of(List.of(Map.of("x",80,"y",240),Map.of("x",100,"y",270)),List.of(Map.of("x",180,"y",240),Map.of("x",200,"y",270)),List.of(Map.of("x",280,"y",240),Map.of("x",300,"y",270))),"durationMs",250,"image","only"));
            check("succeeded".equals(gesture.get("outcome")),"three-finger paths on canvas");expect("pointers","3","canvas received all three pointers");
            var listed=call(tools,"android.notification",null,Map.of("package",FIXTURE));var rows=(List<?>)listed.get("notifications");
            check(rows!=null&&rows.size()==1,"notification fixture listed");var notice=PrimitiveArguments.map(rows.get(0));String notification=Json.string(notice,"notificationId");
            check(((List<?>)notice.get("actions")).size()==2,"notification actions and reply fields exposed");
            var listedAgain=call(tools,"android.notification",null,Map.of("package",FIXTURE));var second=PrimitiveArguments.map(((List<?>)listedAgain.get("notifications")).get(0));check(notification.equals(second.get("notificationId")),"unchanged notification retains handle across listener connections");
            var clicked=call(tools,"android.notification",session,Map.of("operation","action","notificationId",notification,"actionId","action_0"));check("succeeded".equals(clicked.get("outcome")),"notification action accepted");expect("actionCount","1","fixture confirms exactly one notification action");
            var replied=call(tools,"android.notification",null,Map.of("operation","reply","notificationId",notification,"actionId","action_1","responses",Map.of("replyText","Reply café 日本語 🙂")));check("succeeded".equals(replied.get("outcome")),"notification reply accepted");expect("reply","Reply café 日本語 🙂","fixture confirms exact Unicode reply");
            var dismissed=call(tools,"android.notification",null,Map.of("operation","dismiss","notificationId",notification));check("succeeded".equals(dismissed.get("outcome"))&&Boolean.TRUE.equals(dismissed.get("dismissed")),"notification dismissal verified");
            var stale=call(tools,"android.notification",session,Map.of("operation","action","notificationId",notification,"actionId","action_0"));check("stale_notification".equals(stale.get("code"))&&Boolean.FALSE.equals(stale.get("inputDelivered")),"removed notification sends no action");expect("actionCount","1","stale action did not reach fixture");
            String marker="/data/local/tmp/backstage-parity-late-marker";LocalShell.run("rm -f "+marker,null,1000);
            try(var deadline=ActionDeadline.until(System.currentTimeMillis()+250)){var result=LocalShell.run("sleep 2; echo late > "+marker,null,5000);check(result.getInt("exit")==-1,"shell obeys enclosing deadline");}
            Thread.sleep(2300);var markerRead=LocalShell.run("test ! -e "+marker,null,1000);check(markerRead.getInt("exit")==0,"timed-out child cannot perform a late write");
            System.out.println("PARITY CHECK COMPLETE "+checks);exit=0;
        }catch(Throwable e){e.printStackTrace(System.out);}
        finally{if(tools!=null&&session!=null)try{call(tools,"android.task_finish",session,Map.of());}catch(Exception ignored){}if(display!=null)display.handle("stop",new android.os.Bundle());}
        System.exit(exit);
    }
}
