package com.deviceagent.app;

/** Permission and access gate shared by the app UI and the location bridge. */
public final class LocationAccess {
 private LocationAccess(){}
 public static boolean callerAllowed(int caller,int ownUid){return caller==2000||caller==ownUid;}
 public static boolean allowed(boolean coarse,boolean fine){return coarse||fine;}
 public static String error(boolean access,boolean service,boolean coarse,boolean fine){
  if(!access)return "access_paused";
  if(!allowed(coarse,fine))return "location_permission_required";
  return service?null:"location_service_unavailable";
 }
}
