package com.deviceagent.tools;

import android.os.Looper;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;

/** Reproduces independent tool calls with pauses, keeping app content out of logs. */
public final class ReadRecoveryDeviceCheck {
    public static void main(String[] args) {
        DeviceCheckGuard.start("ReadRecoveryDeviceCheck");
        VirtualDisplayHost display=null;AndroidTools tools=null;String session=null;int exit=1;
        try {
            if(Looper.myLooper()==null)Looper.prepareMainLooper();
            var context=SystemContext.create("com.androidbackstage.app");display=new VirtualDisplayHost(context);
            var accessibility=new AccessibilityHost();
            var delegate=new LocalExecutor(display,accessibility,new InteractionHost(context));
            var executor=new com.deviceagent.transport.DeviceExecutor() {
                boolean diagnosed;
                public AccessibilityScope accessibilityScope(){return delegate.accessibilityScope();}
                public com.deviceagent.protocol.ProcessResult exec(String c){return delegate.exec(c);}
                public com.deviceagent.protocol.ProcessResult exec(String c,long t){return delegate.exec(c,t);}
                public byte[] pull(String p){return delegate.pull(p);}
                public void push(String a,String b){delegate.push(a,b);}
                public Map<String,Object> virtualDisplay(String op,Map<String,Object> a){return delegate.virtualDisplay(op,a);}
                public Map<String,Object> interaction(String op,Map<String,Object> a){return delegate.interaction(op,a);}
                public Map<String,Object> status(){return delegate.status();}
                @Override public Map<String,Object> accessibility(String op,Map<String,Object> input) {
                    var out=delegate.accessibility(op,input);
                    if(Boolean.FALSE.equals(out.get("ok")))System.out.println("READ_FAILURE "+Json.stringify(out));
                    if(Boolean.FALSE.equals(out.get("ok"))&&!diagnosed){
                        diagnosed=true;
                        String dump=delegate.exec("dumpsys activity processes",5000).stdoutText();
                        String[] lines=dump.split("\\n");
                        for(int i=0;i<lines.length;i++)if(lines[i].contains("*APP")&&(lines[i].contains("com.google.android.keep")||lines[i].contains("com.google.android.apps.nbu.files"))) {
                            for(int j=i;j<lines.length&&(j==i||!lines[j].matches(".*\\*(APP|PERS)\\*.*"));j++)if(j==i||lines[j].contains("isFrozen")||lines[j].contains("curProcState"))System.out.println("APP_PROCESS "+lines[j]);
                        }
                    }
                    return out;
                }
            };
            tools=new AndroidTools(executor,"com.androidbackstage.app","/data/local/tmp/backstage-read-recovery/config.json");
            var begin=tools.call("android.task_begin",Map.of("requestId",UUID.randomUUID().toString(),"leaseMinutes",10));
            session=(String)begin.get("sessionId");if(session==null)throw new AssertionError(begin);
            boolean keep=Arrays.asList(args).contains("--keep");
            var opened=tools.call("android.open",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString(),"package",keep?"com.google.android.keep":"com.google.android.apps.nbu.files"));
            System.out.println("OPEN "+opened.get("outcome"));
            for(int i=0;i<8;i++) {
                Thread.sleep(1800);
                var out=tools.call("android.observe",Map.of("sessionId",session,"timing",true));
                System.out.println("READ "+i+" "+out.get("outcome")+" "+out.get("timing"));
                if(!"succeeded".equals(out.get("outcome")))throw new AssertionError("independent read "+i);
            }
            Thread.sleep(16_000);
            var resumed=tools.call("android.observe",Map.of("sessionId",session));
            if(!"succeeded".equals(resumed.get("outcome")))throw new AssertionError("read after idle");
            System.out.println("READ AFTER IDLE VERIFIED");
            for(String label:keep?List.of("Search Keep"):List.of("Downloads","More options","Sort by")) {
                Thread.sleep(1800);
                var out=tools.call("android.tap",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString(),"selector",Map.of("label",label)));
                System.out.println("TAP "+label+" "+out.get("outcome"));
                if(!"succeeded".equals(out.get("outcome")))throw new AssertionError("navigation "+label);
            }
            if(keep){
                var typed=tools.call("android.type",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString(),"selector",Map.of("resourceId","com.google.android.keep:id/search_actionbar_query_text"),"text","Backstage test café 日本語"));
                if(!"succeeded".equals(typed.get("outcome"))||!"verified".equals(PrimitiveArguments.map(typed.get("verification")).get("status")))throw new AssertionError("Keep search text did not verify");
                System.out.println("KEEP SEARCH TEXT VERIFIED");
            }
            Thread.sleep(16_000);
            var paused=tools.call("android.observe",Map.of("sessionId",session));
            if(!"succeeded".equals(paused.get("outcome")))throw new AssertionError("read after idle in search or dialog");
            System.out.println("READ AFTER NAVIGATION IDLE VERIFIED");
            var back=tools.call("android.key",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString(),"key","BACK"));
            if(!"succeeded".equals(back.get("outcome")))throw new AssertionError("close unchanged sort dialog");
            System.out.println("READ RECOVERY CHECK COMPLETE");exit=0;
        }catch(Throwable e){e.printStackTrace(System.out);}
        finally {
            if(tools!=null&&session!=null)System.out.println("CLEANUP "+Json.stringify(tools.call("android.task_finish",Map.of("sessionId",session,"requestId",UUID.randomUUID().toString()))));
            if(display!=null)display.handle("stop",new android.os.Bundle());
        }
        System.exit(exit);
    }
}
