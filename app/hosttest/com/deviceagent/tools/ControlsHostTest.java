package com.deviceagent.tools;
import java.util.List;
import java.util.Map;

public final class ControlsHostTest {
    static void check(boolean value) {if(!value)throw new AssertionError();}
    static void rejects(Runnable f) {try{f.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected rejection");}
    public static void run() {
        var intent=Map.<String,Object>of("action","example.OPEN","data","sample://item/1","extras",List.of(Map.of("name","text","type","string","value","café; $(touch /tmp/not-run)"),Map.of("name","count","type","integer","value","7")));
        String command=IntentCommand.build(intent,9);check(command.contains("--display 9")&&command.contains("--es text 'café; $(touch /tmp/not-run)'")&&command.contains("--ei count 7"));
        check(IntentCommand.build(intent,9).contains("--activity-multiple-task")&&!IntentCommand.build(intent,9,true).contains("--activity-multiple-task"));
        rejects(()->IntentCommand.build(Map.of("action","x","activity",".Main"),1));
        rejects(()->IntentCommand.build(Map.of("action","x","extras",List.of(Map.of("name","n","type","integer","value","1.5"))),1));
        rejects(()->IntentCommand.build(Map.of("action","x","extras",List.of(Map.of("name","n","type","boolean","value","yes"))),1));
        rejects(()->IntentCommand.build(Map.of("action","x","raw","; exit"),1));

        check(com.deviceagent.app.LocationAccess.callerAllowed(2000,10123));
        check(com.deviceagent.app.LocationAccess.callerAllowed(10123,10123));
        check(!com.deviceagent.app.LocationAccess.callerAllowed(10124,10123));
        check(!com.deviceagent.app.LocationAccess.callerAllowed(1000,10123));
        check(com.deviceagent.app.LocationAccess.error(true,true,true,false)==null);
        check(com.deviceagent.app.LocationAccess.error(true,true,false,true)==null);
        check("location_permission_required".equals(com.deviceagent.app.LocationAccess.error(true,true,false,false)));
        check("location_service_unavailable".equals(com.deviceagent.app.LocationAccess.error(true,false,true,true)));
        check("access_paused".equals(com.deviceagent.app.LocationAccess.error(false,true,true,true)));
        check("ask".equals(com.deviceagent.app.LocationAccess.next(false,false,false))&&"background".equals(com.deviceagent.app.LocationAccess.next(true,true,false))&&"background".equals(com.deviceagent.app.LocationAccess.next(true,false,false))&&"done".equals(com.deviceagent.app.LocationAccess.next(false,true,true)));
        check(LocationFix.limits(Map.of()).get("timeoutMs").equals(20000));
        check(LocationFix.limits(Map.of("timeoutMs",0)).get("timeoutMs").equals(0));
        rejects(()->LocationFix.limits(Map.of("timeoutMs",20001)));
        check(LocationFix.ageMs(2000000000L,1999000000L)==1);
        check(LocationFix.ageMs(2000000000L,2001000000L)==Long.MAX_VALUE);
        check(LocationFix.acceptable(1000,100,1000,100));
        check(!LocationFix.acceptable(1001,100,1000,100));
        check(!LocationFix.acceptable(1,101,1000,100));
        check(!LocationFix.acceptable(1,Double.NaN,1000,100));
        Map<String,Object> a=Json.mapOf("text","Ready","resourceId","p:id/status","package","p");
        List<Map<String,Object>> windows=List.of(Json.mapOf("id",1,"focused",true));
        Map<String,Object> node=Json.mapOf("text","Ready","resourceId","p:id/status","package","p","window",1);
        UiPredicate.validate(a);check(UiPredicate.matches(a,List.of(node),windows,""));
        check(!UiPredicate.matches(a,List.of(Json.mapOf("text","Ready","package","p","window",1),Json.mapOf("resourceId","p:id/status","package","p","window",1)),windows,""));
        check(!UiPredicate.matches(a,List.of(node),List.of(Json.mapOf("id",1,"focused",false)),""));
        rejects(()->UiPredicate.validate(Map.of())); rejects(()->UiPredicate.validate(Map.of("activity",".Main")));
        check(UiPredicate.matches(Map.of("activity",".Main","package","p"),List.of(),List.of(),"p/p.Main"));
        check(UiPredicate.matches(Map.of("activity","p.Main","package","p"),List.of(),List.of(),"p/.Main"));
        check(!UiPredicate.matches(Map.of("text","Read"),List.of(node),windows,""));
        check(UiPredicate.matches(Map.of("text","Read","contains",true),List.of(node),windows,""));
        String tasks="RootTask id=8 bounds=[0,0][100,100] displayId=2 visible=true\n taskId=9: p/.Main bounds=[0,0][100,100] userId=0 visible=true topActivity=ComponentInfo{p/.Main}\n taskId=10: p/.Main bounds=[0,0][100,100] userId=0 visible=true topActivity=ComponentInfo{p/.Main}\nRootTask id=11 displayId=20 visible=true\n taskId=12: q/.Main\nRootTask id=14 displayId=2 visible=true\n taskId=15: q/.Main\n";
        List<Map<String,Object>> rows=TaskSnapshot.parse(tasks,2);check(rows.size()==3);check(rows.get(0).get("rootTaskCount").equals(2));
        rejects(()->TaskSnapshot.select(rows,"p",null)); rejects(()->TaskSnapshot.select(rows,"p",9)); rejects(()->TaskSnapshot.select(rows,"z",15));
        check(TaskSnapshot.select(rows,"q",15).get("activityTaskId").equals(15));
        check(TaskSnapshot.parse("RootTask id=2 displayId=2 topActivity=ComponentInfo{p/.Main}",2).isEmpty());
        privateAppScope();
        GesturePlan plan=GesturePlan.create("drag",Map.of("x1",10,"y1",20,"x2",90,"y2",80),100,100);
        check(plan.holdMs==600);check(plan.at(0,.5)[0]==50);check(plan.at(0,1)[1]==80);
        GesturePlan pinch=GesturePlan.create("pinch",Map.of("centerX",50,"centerY",50,"startSpan",10,"endSpan",80),100,100);
        check(pinch.paths.size()==2);check(pinch.at(0,1)[0]==10);check(pinch.at(1,1)[0]==90);
        rejects(()->GesturePlan.create("pinch",Map.of("centerX",50,"centerY",50,"startSpan",10,"endSpan",100),100,100));
        rejects(()->GesturePlan.create("long_press",Map.of("x",100,"y",0),100,100));
        rejects(()->GesturePlan.create("long_press",Map.of("x",1,"y",1,"durationMs",1.5),100,100));
        rejects(()->GesturePlan.create("long_press",Map.of("x",Double.NaN,"y",1),100,100));
        rejects(()->GesturePlan.create("multi_touch",Map.of("paths",List.of()),100,100));
        GesturePlan multi=GesturePlan.create("multi_touch",Map.of("paths",List.of(List.of(Map.of("x",0,"y",0),Map.of("x",50,"y",90),Map.of("x",90,"y",90)))),100,100);
        check(multi.at(0,.5)[0]==50);check(multi.at(0,1)[0]==90);
        System.out.println("PASS Controls: predicate conjunction, focused scope, task identity, ambiguity, gesture bounds and interpolation");
    }
    private static void privateAppScope() {
        rejects(()->com.deviceagent.protocol.ShellCommand.focusedAppOnDisplay(0));
        rejects(()->com.deviceagent.protocol.ShellCommand.focusedAppOnDisplay(-1));
        check(TaskSnapshot.singleVisiblePackage(List.of()).isEmpty());
        var visible=Json.mapOf("package","task.app","visible",true);
        check(TaskSnapshot.singleVisiblePackage(List.of(visible,visible,Json.mapOf("package","launcher.app","visible",false))).equals("task.app"));
        check(TaskSnapshot.singleVisiblePackage(List.of(visible,Json.mapOf("package","other.app","visible",true))).isEmpty());
        String dump="  Display: mDisplayId=0\n mFocusedApp=ActivityRecord{a u0 personal.app/.Main t1}\n"
                +"  Display: mDisplayId=130\n mFocusedApp=ActivityRecord{b u0 other.app/.Main t2}\n"
                +"  Display: mDisplayId=13\n mCurrentFocus=Window{c u0 PopupWindow}\n mFocusedApp=ActivityRecord{d u0 task.app/.Main t3}\n"
                +"  Display: mDisplayId=15\n mCurrentFocus=Window{e u0 task.app/.Main}\n mFocusedApp=null\n";
        for(int display:List.of(13,14,15))try {
            Process p=new ProcessBuilder("sh","-c","dumpsys() { cat; }; "+com.deviceagent.protocol.ShellCommand.focusedAppOnDisplay(display)).start();
            p.getOutputStream().write(dump.getBytes(java.nio.charset.StandardCharsets.UTF_8));p.getOutputStream().close();
            String component=TaskSnapshot.focusedComponent(new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            check(p.waitFor()==0);check(component.equals(display==14?"":"task.app/.Main"));
        }catch(Exception e){throw new AssertionError(e);}
    }
}
