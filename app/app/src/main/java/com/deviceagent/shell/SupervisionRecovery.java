package com.deviceagent.shell;
import android.content.Context;
import android.os.Bundle;
import com.deviceagent.tools.Json;
import com.deviceagent.protocol.ShellCommand;
import java.util.Map;

/** Restore already-enabled supervision using a temporary private activity. */
final class SupervisionRecovery {
 static Map<String,Object> restore(Context context)throws Exception{
  String pkg=context.getPackageName();VirtualDisplayHost display=new VirtualDisplayHost(context);
  try{
   Bundle options=new Bundle();options.putInt("idleMinutes",1);
   Bundle started=display.handle("start",options);
   if(!started.getBoolean("created"))return Json.mapOf("ok",false,"error","Open Backstage to restore the connection");
   int id=started.getInt("displayId",-1);if(id<=0)throw new IllegalStateException("A private recovery display is required");
   LocalShell.run("am start -W --activity-multiple-task --display "+id+" -n "+ShellCommand.quote(pkg+"/com.deviceagent.app.AgentActivity"),null,10000);
   for(int i=0;i<10;i++){
    var status=LocalShell.run("dumpsys activity services "+ShellCommand.quote(pkg),null,2000);
    byte[] raw=status.getByteArray("stdout");
    if(raw!=null&&new String(raw,java.nio.charset.StandardCharsets.UTF_8).contains("isForeground=true"))return Json.mapOf("ok",true,"restored",true);
    Thread.sleep(200);
   }
   return Json.mapOf("ok",false,"error","Open Backstage to restore the connection");
  }finally{display.handle("stop",new Bundle());}
 }
}
