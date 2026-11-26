package com.deviceagent.app;

/** Permission and access gate shared by the app UI and the location bridge. */
public final class LocationAccess {
 private LocationAccess(){}
 public static boolean callerAllowed(int caller,int ownUid){return caller==2000||caller==ownUid;}
 public static boolean allowed(boolean coarse,boolean fine){return coarse||fine;}
 /**
  * The next location step in setup: "ask" for location while the app is open, then "background"
  * so the daemon can read which Wi-Fi this is after a restart, then "done".
  */
 public static String next(boolean coarse,boolean fine,boolean background){
  if(!allowed(coarse,fine))return "ask";
  return background?"done":"background";
 }
 public static String error(boolean access,boolean service,boolean coarse,boolean fine){
  if(!access)return "access_paused";
  if(!allowed(coarse,fine))return "location_permission_required";
  return service?null:"location_service_unavailable";
 }
}
