package com.deviceagent.tools;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class ReliabilityHostTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("should reject");}
    public static void run() {
        String unicode="café 日本語 🙂 e\u0301\nline two %s ' \"";
        check(TextEditPlan.create("",0,0,unicode,false,false).value.equals(unicode));
        check(TextEditPlan.create("",-1,-1,unicode,false,false).value.equals(unicode));
        TextEditPlan edit=TextEditPlan.create("left right",5,5,unicode,false,false);
        check(edit.value.equals("left "+unicode+"right")&&edit.cursor==5+unicode.length());
        check(TextEditPlan.create("abc",3,1,"日本語",false,false).value.equals("a日本語"));
        check(TextEditPlan.create(null,-1,-1,unicode,true,true).value.equals(unicode));
        check(TextEditPlan.create("text",0,4,"",true,false).value.equals(""));
        rejects(()->TextEditPlan.create("hidden",0,6,"x",false,true));
        rejects(()->TextEditPlan.create("abc",-1,1,"x",false,false));
        rejects(()->TextEditPlan.create("abc",0,4,"x",false,false));
        rejects(()->TextEditPlan.create("🙂",1,1,"x",false,false));
        check(TextEditPlan.create("🙂",2,2,"x",false,false).value.equals("🙂x"));
        try {UiHierarchy.find(List.of(),"missing");throw new AssertionError();}
        catch(InputNotSentException e){check(e.code.equals("target_not_found"));}

        System.out.println("PASS Reliability: Unicode, cursor selection, replacement, passwords, surrogate pairs and lookup failures");
    }
}
