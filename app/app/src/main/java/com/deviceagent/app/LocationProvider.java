package com.deviceagent.app;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import com.deviceagent.shell.LocationHost;
import com.deviceagent.tools.Json;
import com.deviceagent.tools.ToolCatalog;
import java.util.Map;

/** A shell-only bridge; location runs with the app's granted identity and foreground service. */
public final class LocationProvider extends ContentProvider {
 public static boolean granted(Context context,String permission){return context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
 public static boolean allowed(Context context){return LocationAccess.allowed(granted(context,Manifest.permission.ACCESS_COARSE_LOCATION),granted(context,Manifest.permission.ACCESS_FINE_LOCATION),granted(context,Manifest.permission.ACCESS_BACKGROUND_LOCATION));}
 @Override public boolean onCreate(){return true;}
 @Override public synchronized Bundle call(String method,String argument,Bundle extras){
  int uid=Binder.getCallingUid();
  if(!LocationAccess.callerAllowed(uid,android.os.Process.myUid()))throw new SecurityException("Location is only available to the Backstage helper.");
  if(!"location".equals(method))throw new IllegalArgumentException("Unknown location operation");
  long identity=Binder.clearCallingIdentity();
  try{
   Map<String,Object> args=Json.object(argument==null?"{}":argument);
   ToolCatalog.validate("android.location",args);
   Context context=getContext();AgentService service=AgentService.locationService();
   String error=LocationAccess.error(AgentConfig.load(context).accessEnabled,service!=null,
    granted(context,Manifest.permission.ACCESS_COARSE_LOCATION),granted(context,Manifest.permission.ACCESS_FINE_LOCATION),granted(context,Manifest.permission.ACCESS_BACKGROUND_LOCATION));
   if(error!=null)return reply(Json.mapOf("ok",false,"code",error,"fix",null,"error",switch(error){
    case "access_paused"->"Phone access is paused. Open Backstage and resume access.";
    case "location_permission_required"->"Open Backstage, tap Allow location, and allow location access all the time. Choose precise location for more accuracy.";
    default->"Open Backstage and resume access before requesting location.";
   }));
   service.beginLocation();
   try{return reply(LocationHost.read(context,args));}
   finally{service.endLocation();}
  }catch(IllegalArgumentException e){return reply(Json.mapOf("ok",false,"code","invalid_argument","error",e.getMessage()));}
  catch(SecurityException e){return reply(Json.mapOf("ok",false,"code","location_permission_required","error","Allow location access all the time in Backstage's Android permissions, then retry."));}
  catch(IllegalStateException e){return reply(Json.mapOf("ok",false,"code","location_service_unavailable","error","Open Backstage and resume access, then retry."));}
  finally{Binder.restoreCallingIdentity(identity);}
 }
 private static Bundle reply(Map<String,Object> result){Bundle out=new Bundle();out.putString("result",Json.stringify(result));return out;}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){throw new UnsupportedOperationException();}
 @Override public String getType(Uri uri){return null;}
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
 @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
}
