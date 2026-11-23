package com.deviceagent.tools;

/** Read-only clipboard access diagnostic. Never prints clipboard contents. */
public final class ClipboardDeviceCheck {
    public static void main(String[] args) {
        DeviceCheckGuard.start("ClipboardDeviceCheck");
        try {
            if(android.os.Looper.myLooper()==null)android.os.Looper.prepareMainLooper();
            var context=com.deviceagent.daemon.SystemContext.create("com.androidbackstage.app");
            var host=new com.deviceagent.shell.InteractionHost(context);
            var method=host.getClass().getDeclaredMethod("shellContext");method.setAccessible(true);
            var shell=(android.content.Context)method.invoke(host);
            var keyguard=shell.getSystemService(android.app.KeyguardManager.class);
            var manager=shell.getSystemService(android.content.ClipboardManager.class);
            System.out.println("CLIPBOARD ACCESS "+Json.stringify(Json.mapOf("deviceLocked",keyguard.isDeviceLocked(),"keyguardLocked",keyguard.isKeyguardLocked(),"hasClip",manager.hasPrimaryClip(),"descriptionReadable",manager.getPrimaryClipDescription()!=null,"clipReadable",manager.getPrimaryClip()!=null)));
            if(keyguard.isDeviceLocked()){
                for(var operation:java.util.List.of("clipboard","paste_text")){
                    var input=operation.equals("clipboard")?java.util.Map.<String,Object>of():java.util.Map.<String,Object>of("display",1,"focused",true,"clipboard",true,"captureFocus","fixture","text","Must not be entered","mode","insert");
                    var result=host.handle(operation,input);
                    if(!"phone_locked".equals(result.get("code"))||!Boolean.FALSE.equals(result.get("inputDelivered")))throw new AssertionError(operation+" must reject before input");
                    System.out.println("PASS locked phone rejects "+operation+" before input");
                }
            }
        }catch(Throwable error){error.printStackTrace(System.out);}
        System.exit(0);
    }
}
