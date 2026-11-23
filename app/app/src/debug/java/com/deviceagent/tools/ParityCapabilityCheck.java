package com.deviceagent.tools;

/** Read-only capability probe. Does not print notification contents or change permissions. */
public final class ParityCapabilityCheck {
 public static void main(String[] args){
        DeviceCheckGuard.start("ParityCapabilityCheck");
  try{
   if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();
   var context=com.deviceagent.daemon.SystemContext.create("com.androidbackstage.app");
   for(String name:java.util.List.of("ACCESS_NOTIFICATIONS","STATUS_BAR_SERVICE","MANAGE_NOTIFICATION_LISTENERS","READ_CLIPBOARD_IN_BACKGROUND","WRITE_SECURE_SETTINGS"))System.out.println("PERMISSION "+name+" "+context.checkPermission("android.permission."+name,android.os.Process.myPid(),android.os.Process.myUid()));
   var read=new com.deviceagent.shell.InteractionHost(context).handle("notification",java.util.Map.of("package","com.androidbackstage.app"));
   System.out.println("LISTENER_ACCESS "+Json.stringify(read));
   var get=android.app.NotificationManager.class.getDeclaredMethod("getService");get.setAccessible(true);Object service=get.invoke(null);
   var method=Class.forName("android.app.INotificationManager").getMethod("getActiveNotifications",String.class);
   var notifications=(android.service.notification.StatusBarNotification[])method.invoke(service,"com.android.shell");
   int actions=0;for(var notification:notifications)if(notification.getNotification().actions!=null)actions+=notification.getNotification().actions.length;
   System.out.println("NOTIFICATION_ACCESS count="+notifications.length+" actions="+actions);
  }catch(Throwable e){e.printStackTrace(System.out);}
  System.exit(0);
 }
}
