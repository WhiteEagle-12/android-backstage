package com.deviceagent.tools;

import java.util.*;

/** Fix 2.0.13: a single-task app on the owner's screen asks the owner instead of refusing. */
public final class LaunchApprovalHostTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Map<String,Object> main(){return Json.mapOf("ok",true,"conflict",true,"package","com.google.android.apps.maps","otherDisplay",0);}
    private static InputNotSentException refused(Runnable r){try{r.run();}catch(InputNotSentException e){return e;}throw new AssertionError("launch was not refused");}
    public static void run(){
        var approval=new LaunchApproval();int[] asks={0};long[] waited={0};
        LaunchApproval.Prompt allow=(pkg,ms)->{asks[0]++;waited[0]=ms;return "approved";};
        LaunchApproval.Prompt notNow=(pkg,ms)->{asks[0]++;return "cancelled";};
        LaunchApproval.Prompt silent=(pkg,ms)->{asks[0]++;return "timeout";};

        approval.require(Json.mapOf("ok",true,"conflict",false),"s1",30_000,notNow);
        check(asks[0]==0,"no conflict asks nobody");

        approval.require(main(),"s1",30_000,allow);
        check(asks[0]==1&&waited[0]==25_000,"Allow lets the launch go after one question with a 25 s limit");
        approval.require(main(),"s1",30_000,notNow);
        check(asks[0]==1,"an Allow lasts for the session");

        var e=refused(()->approval.require(main(),"s2",30_000,notNow));
        check(asks[0]==2&&"app_open_elsewhere".equals(e.code)&&"declined".equals(e.details.get("decision"))&&e.getMessage().contains("chose not"),"Not now refuses with app_open_elsewhere");
        e=refused(()->approval.require(main(),"s3",30_000,silent));
        check("app_open_elsewhere".equals(e.code)&&"no_answer".equals(e.details.get("decision"))&&e.getMessage().contains("did not answer"),"no answer refuses with app_open_elsewhere");

        int before=asks[0];
        e=refused(()->approval.require(Json.mapOf("ok",true,"conflict",true,"package","com.example.single","otherDisplay",7),"s1",30_000,allow));
        check(asks[0]==before&&"app_open_elsewhere".equals(e.code)&&e.getMessage().contains("another session"),"another session refuses without a question");

        approval.require(main(),"s4",12_000,allow);
        check(waited[0]==7_000,"the wait leaves time for the launch");
        before=asks[0];
        e=refused(()->approval.require(main(),"s5",6_000,allow));
        check(asks[0]==before&&"no_answer".equals(e.details.get("decision")),"too little time to ask refuses without a question");

        approval.forget("s1");
        approval.require(main(),"s1",30_000,allow);
        check(asks[0]==before+1,"a finished session forgets its Allow");

        var pending=new PendingConfirmations();
        var item=pending.create("s1",3,null,"launch","An agent wants to use Maps","It will leave your screen.","com.google.android.apps.maps","","",5_000);
        check("launch".equals(item.kind)&&"launch".equals(pending.list(false).get(0).get("kind")),"the launch question keeps its kind for the app");
    }
}
