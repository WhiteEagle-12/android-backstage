package com.deviceagent.app;

/** Permission and access gate shared by the app UI and the location bridge. */
public final class LocationAccess {
 private LocationAccess(){}
 public static boolean callerAllowed(int caller,int ownUid){return caller==2000||caller==ownUid;}
 public static boolean allowed(boolean coarse,boolean fine,boolean background){return (coarse||fine)&&background;}
 public static String error(boolean access,boolean service,boolean coarse,boolean fine,boolean background){
  if(!access)return "access_paused";
  if(!allowed(coarse,fine,background))return "location_permission_required";
  return service?null:"location_service_unavailable";
 }
}
