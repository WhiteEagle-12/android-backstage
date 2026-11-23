package com.deviceagent.shell;
import android.content.Context;
import android.media.AudioManager;
import com.deviceagent.tools.*;
import java.util.*;

@android.annotation.SuppressLint("MissingPermission") // Shell-UID service; Android enforces its grants.
final class DeviceControls {
 private static final Map<String,Integer> STREAMS=Map.of("media",3,"ring",2,"notification",5,"alarm",4,"call",0,"system",1);
 static Map<String,Object> handle(Context context,Map<String,Object> args)throws Exception{
  String op=Json.string(args,"operation");var audio=context.getSystemService(AudioManager.class);
  var wifi=context.getSystemService(android.net.wifi.WifiManager.class);
  var bluetooth=context.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
  if(op.equals("status")){
   var volumes=new LinkedHashMap<String,Object>();for(var stream:STREAMS.entrySet())volumes.put(stream.getKey(),Map.of("value",audio.getStreamVolume(stream.getValue()),"min",audio.getStreamMinVolume(stream.getValue()),"max",audio.getStreamMaxVolume(stream.getValue())));
   var windows=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
   boolean frozen=(Boolean)Class.forName("android.view.IWindowManager").getMethod("isDisplayRotationFrozen",int.class).invoke(windows,0);
   int rotation=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0).getRotation()*90;
   return Json.mapOf("ok",true,"volume",volumes,"wifi",wifi.isWifiEnabled(),"bluetooth",bluetooth!=null&&bluetooth.isEnabled(),"autoRotate",!frozen,"rotation",rotation);
  }
  if(op.equals("volume")){
   int stream=STREAMS.get(Json.string(args,"stream")),value=Json.integer(args,"value",-1),before=audio.getStreamVolume(stream);
   if(value<audio.getStreamMinVolume(stream)||value>audio.getStreamMaxVolume(stream))throw new IllegalArgumentException("Choose a volume within the stream's min and max from device_control status");
   if(value!=before)audio.setStreamVolume(stream,value,0);int after=audio.getStreamVolume(stream);
   return Json.mapOf("ok",true,"inputDelivered",value!=before,"value",after,"verification",Map.of("status",after==value?"verified":"failed"));
  }
  if(op.equals("rotation")){
   String rotation=Json.string(args,"rotation");
   var service=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);Class<?> api=Class.forName("android.view.IWindowManager");
   if(rotation.equals("auto"))api.getMethod("thawDisplayRotation",int.class,String.class).invoke(service,0,"Backstage");
   else api.getMethod("freezeDisplayRotation",int.class,int.class,String.class).invoke(service,0,Integer.parseInt(rotation)/90,"Backstage");
   return Json.mapOf("ok",true,"inputDelivered",true,"verification",Map.of("status","unverified"));
  }
  boolean wanted=Boolean.TRUE.equals(args.get("enabled"));boolean before=op.equals("wifi")?wifi.isWifiEnabled():bluetooth!=null&&bluetooth.isEnabled();
  if(before==wanted)return Json.mapOf("ok",true,"inputDelivered",false,"enabled",before,"verification",Map.of("status","verified"));
  boolean accepted=op.equals("wifi")?wifi.setWifiEnabled(wanted):bluetooth!=null&&(wanted?bluetooth.enable():bluetooth.disable());
  return Json.mapOf("ok",accepted,"inputDelivered",accepted,"code",accepted?"accepted":"android_rejected","verification",Map.of("status","unverified"));
 }
}
