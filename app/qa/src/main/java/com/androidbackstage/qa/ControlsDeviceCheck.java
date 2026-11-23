package com.androidbackstage.qa;

import android.content.Context;
import android.os.Looper;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs installed product classes as shell UID on its own private display. */
public final class ControlsDeviceCheck {
    private Object tools;
    private Method call;
    private final List<String> passed=new ArrayList<>();
    private int display;
    private static final String PKG="com.androidbackstage.qa";
    public static void main(String[] args) throws Exception {
        Context context=(Context)Class.forName("com.deviceagent.daemon.SystemContext").getMethod("create",String.class).invoke(null,"com.androidbackstage.app");
        new Thread(()->new ControlsDeviceCheck().run(context),"controls-device-test").start();
        Looper.loop();
    }
    private void run(Context context) {
        int exit=1;
        try {
            Class<?> vd=Class.forName("com.deviceagent.shell.VirtualDisplayHost"),access=Class.forName("com.deviceagent.shell.AccessibilityHost"),interact=Class.forName("com.deviceagent.shell.InteractionHost");
            Object executor=Class.forName("com.deviceagent.shell.LocalExecutor").getConstructor(vd,access,interact).newInstance(vd.getConstructor(Context.class).newInstance(context),access.getConstructor().newInstance(),interact.getConstructor(Context.class).newInstance(context));
            tools=Class.forName("com.deviceagent.tools.AndroidTools").getConstructor(Class.forName("com.deviceagent.transport.DeviceExecutor")).newInstance(executor);
            call=tools.getClass().getMethod("call",String.class,Map.class);
            Map<String,Object> opened=ok("vd_open",Map.of("query",PKG));display=((Number)opened.get("display")).intValue();check(display>0,"private display");
            ok("wait_for",Map.of("display",display,"text","Ready","resourceId",PKG+":id/status","package",PKG,"timeoutMs",5000));pass("wait for text/resource/package");
            ok("wait_for",Map.of("display",display,"text","Ready","timeoutMs",0));pass("immediate assertion");
            ok("wait_for",Map.of("display",display,"package",PKG,"activity",".ControlFixture","timeoutMs",5000));pass("wait for focused activity");
            long before=System.nanoTime();Map<String,Object> timed=invoke("wait_for",Map.of("display",display,"text","Never appears","timeoutMs",400));
            check(Boolean.FALSE.equals(timed.get("ok"))&&"wait_timeout".equals(timed.get("code")),"timeout result "+timed);check((System.nanoTime()-before)/1000000<2500,"bounded timeout");pass("bounded missing-text timeout");
            rejects("wait_for",Map.of("display",0,"text","Ready"));rejects("wait_for",Map.of("display",display));rejects("long_press",Map.of("display",display+100,"x",100,"y",100));pass("missing predicate and wrong displays rejected");
            Map<String,Object> tasks=ok("task_status",Map.of("display",display,"package",PKG));
            @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)tasks.get("tasks");
            check(rows.size()==1&&rows.get(0).get("taskId") instanceof Number,"task IDs "+tasks);check(PKG.equals(rows.get(0).get("package")),"task package");pass("structured task status");
            @SuppressWarnings("unchecked") Map<String,Object> front=(Map<String,Object>)executor.getClass().getMethod("interaction",String.class,Map.class).invoke(executor,"front_task",Map.of("taskId",rows.get(0).get("taskId")));
            check(Boolean.TRUE.equals(front.get("ok")),"exact task front API "+front);pass("exact task front API on private display");
            rejects("vd_handoff",Map.of("taskId",Integer.MAX_VALUE));pass("stale handoff selection rejected");
            ok("long_press",Map.of("display",display,"x",300,"y",400,"durationMs",800));JSONObject held=state().getJSONObject("gesture");check(held.getLong("elapsedMs")>=750&&held.getInt("maxPointers")==1,"long press "+held);pass("long press delivered and released");
            ok("drag",Map.of("display",display,"x1",200,"y1",400,"x2",600,"y2",800,"durationMs",400,"holdMs",600));JSONObject drag=state().getJSONObject("gesture");check(drag.getLong("elapsedMs")>=950&&Math.abs(drag.getDouble("endX")-600)<2,"drag "+drag);pass("drag hold and endpoint");
            ok("pinch",Map.of("display",display,"centerX",500,"centerY",700,"startSpan",100,"endSpan",400,"durationMs",300));check(state().getJSONObject("gesture").getInt("maxPointers")==2,"pinch count");pass("two-finger pinch");
            ok("multi_touch",Map.of("display",display,"paths",List.of(List.of(Map.of("x",200,"y",400),Map.of("x",300,"y",800)),List.of(Map.of("x",500,"y",400),Map.of("x",600,"y",800)),List.of(Map.of("x",700,"y",400),Map.of("x",800,"y",800))),"durationMs",300));check(state().getJSONObject("gesture").getInt("maxPointers")==3,"multi-touch count");pass("three-finger gesture");
            Map<String,Object> invalid=invoke("long_press",Map.of("display",display,"x",99999,"y",400));check(Boolean.FALSE.equals(invalid.get("ok")),"bounds rejected");pass("off-display gesture rejected");
            final Map<String,Object>[] interruptedGesture=new Map[1];
            Thread gestureThread=new Thread(()->{try{interruptedGesture[0]=invoke("long_press",Map.of("display",display,"x",300,"y",400,"durationMs",5000));}catch(Exception e){throw new RuntimeException(e);}});
            gestureThread.start();Thread.sleep(250);gestureThread.interrupt();gestureThread.join(3000);
            check(!gestureThread.isAlive() && interruptedGesture[0]!=null && "interrupted".equals(interruptedGesture[0].get("code")),"gesture interruption");
            org.json.JSONArray cancelled=state().getJSONObject("gesture").getJSONArray("events");
            check(cancelled.getJSONObject(cancelled.length()-1).getInt("action")==3,"cancel delivered");pass("interrupted gesture releases pointers with CANCEL");
            final Map<String,Object>[] interruptedWait=new Map[1];
            Thread waitThread=new Thread(()->{try{interruptedWait[0]=invoke("wait_for",Map.of("display",display,"text","Never appears","timeoutMs",5000));}catch(Exception e){throw new RuntimeException(e);}});
            waitThread.start();Thread.sleep(300);waitThread.interrupt();waitThread.join(3000);
            check(!waitThread.isAlive() && interruptedWait[0]!=null && "interrupted".equals(interruptedWait[0].get("code")),"wait interruption "+interruptedWait[0]);pass("wait cancellation");
            Map<String,Object> sessions=ok("media",Map.of("action","status","package",PKG));
            @SuppressWarnings("unchecked") List<Map<String,Object>> media=(List<Map<String,Object>>)sessions.get("sessions");check(media.size()==1,"media session "+sessions);String session=(String)media.get(0).get("sessionId");
            for(String action:List.of("play","pause","next","previous","seek")) {
                ok("media",action.equals("seek")?Map.of("action",action,"sessionId",session,"positionMs",12345):Map.of("action",action,"sessionId",session));
                long deadline=System.currentTimeMillis()+2000;JSONObject recorded;
                do {recorded=state().optJSONObject("media");if(recorded!=null&&action.equals(recorded.optString("action")))break;Thread.sleep(50);}while(System.currentTimeMillis()<deadline);
                check(recorded!=null&&action.equals(recorded.getString("action")),"media callback "+action);if(action.equals("seek"))check(recorded.getLong("positionMs")==12345,"seek position");pass("media "+action);
            }
            ok("shell",Map.of("command","am start --display "+display+" -f 0x18000000 --ei sessions 2 -n "+PKG+"/.ControlFixture"));Thread.sleep(800);
            Map<String,Object> ambiguous=invoke("media",Map.of("action","play","package",PKG));check("ambiguous_session".equals(ambiguous.get("code")),"ambiguous session "+ambiguous);pass("ambiguous media session rejected");
            Map<String,Object> stale=invoke("media",Map.of("action","play","sessionId","missing-session"));check("session_not_found".equals(stale.get("code")),"stale session");pass("stale media session rejected");
            rejects("vd_handoff",Map.of("package",PKG));pass("ambiguous handoff rejected");
            final Map<String,Object>[] lostDisplay=new Map[1];
            Thread losing=new Thread(()->{try{lostDisplay[0]=invoke("wait_for",Map.of("display",display,"text","Never appears","timeoutMs",1000));}catch(Exception e){throw new RuntimeException(e);}});
            losing.start();Thread.sleep(300);ok("vd_stop",Map.of());losing.join(3000);
            check(lostDisplay[0]!=null&&"ui_unavailable".equals(lostDisplay[0].get("code")),"display loss "+lostDisplay[0]);pass("display loss distinguished from predicate timeout");
            System.out.println(new JSONObject(Map.of("ok",true,"passed",passed,"display",display)));exit=0;
        }catch(Throwable e){e.printStackTrace(System.out);System.out.println(new JSONObject(Map.of("ok",false,"passed",passed,"error",e.toString())));}
        finally {try{if(tools!=null&&display>0)invoke("vd_stop",Map.of());}catch(Exception e){e.printStackTrace(System.out);}System.out.flush();System.exit(exit);}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> invoke(String name,Map<String,Object> args) throws Exception {
        try{return (Map<String,Object>)call.invoke(tools,"android."+name,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    private Map<String,Object> ok(String name,Map<String,Object> args) throws Exception {Map<String,Object> result=invoke(name,args);check(!Boolean.FALSE.equals(result.get("ok")),name+": "+result);return result;}
    private void rejects(String name,Map<String,Object> args) throws Exception {try{Map<String,Object> result=invoke(name,args);if(Boolean.FALSE.equals(result.get("ok")))return;}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(name+" should reject");}
    private JSONObject state() throws Exception {Thread.sleep(70);return new JSONObject((String)ok("shell",Map.of("command","run-as "+PKG+" cat files/events.json")).get("stdout"));}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void pass(String name){passed.add(name);System.out.println("PASS "+name);}
}
