package com.deviceagent.shell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import com.deviceagent.tools.GesturePlan;
import com.deviceagent.tools.Json;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shell-UID input and media APIs. Never inject an event without its explicit display. */
@android.annotation.SuppressLint({"SoonBlockedPrivateApi", "MissingPermission"})
public final class InteractionHost {
    private final AudioJobs audioJobs = new AudioJobs();
    private final Context context;
    private final Map<MediaSession.Token,String> sessionIds = new HashMap<>();
    private final NotificationHost notifications=new NotificationHost();
    public InteractionHost(Context base) { context=base; }
    @android.annotation.SuppressLint("WakelockTimeout")
    public com.deviceagent.transport.DeviceExecutor.AccessibilityScope executionScope(){
        try{
            com.deviceagent.tools.ActionDeadline.check();
            var manager=shellContext().getSystemService(android.os.PowerManager.class);
            var lock=manager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"Backstage:operation");
            lock.acquire(Math.max(1,Math.min(660000,com.deviceagent.tools.ActionDeadline.remaining())));
            return ()->{if(lock.isHeld())lock.release();};
        }catch(com.deviceagent.tools.InputNotSentException e){throw e;}
        catch(Exception e){throw new com.deviceagent.tools.InputNotSentException("device_not_ready","Android could not keep this operation running. Open Backstage and try again.");}
    }
    private Context shellContext() throws Exception {
        Context shell=context.createPackageContext("com.android.shell",0);
        // A shell-uid app_process has an android system context; attribute calls to its actual package.
        var op=shell.getClass().getDeclaredField("mOpPackageName");op.setAccessible(true);op.set(shell,"com.android.shell");
        if(android.os.Build.VERSION.SDK_INT>=31){
            var attribution=shell.getClass().getDeclaredField("mAttributionSource");attribution.setAccessible(true);
            attribution.set(shell,new android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build());
        }
        return shell;
    }
    public synchronized Map<String,Object> handle(String op,Map<String,Object> args) {
        try {
            if(op.equals("audio")){initializeMediaServices();return audioJobs.handle(shellContext(),args);}
            if(op.equals("restore_supervision"))return SupervisionRecovery.restore(context);
            if(op.equals("apk_info")){
                var pm=shellContext().getPackageManager();
                int sigFlag=android.os.Build.VERSION.SDK_INT>=28?android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES:android.content.pm.PackageManager.GET_SIGNATURES;
                var info=pm.getPackageArchiveInfo(Json.string(args,"path"),sigFlag);
                if(info==null)throw new IllegalArgumentException("The APK could not be read");
                String cert=certSha256(info);
                long apkVersionCode=info.getLongVersionCode();
                String installedCert="";
                long installedVersionCode=0L;
                try{
                    var installed=pm.getPackageInfo(info.packageName,sigFlag);
                    installedCert=certSha256(installed);
                    installedVersionCode=installed.getLongVersionCode();
                }catch(Exception ignored){}
                return Json.mapOf("ok",true,"package",info.packageName,"certSha256",cert,"installedCertSha256",installedCert,
                        "versionCode",apkVersionCode,"installedVersionCode",installedVersionCode);
            }
            if(op.equals("device_control")){
                if(!"status".equals(args.get("operation"))){if(!Integer.valueOf(0).equals(args.get("display")))throw new IllegalArgumentException("Device changes require a foreground session");try(var guard=inputGuard(0,args)){guard.check();try{return DeviceControls.handle(shellContext(),args);}catch(IllegalArgumentException e){throw e;}catch(Exception e){return Json.mapOf("ok",false,"inputDelivered",null,"code","input_outcome_unknown","error","Android did not confirm the device change. Read device_control status before trying again.");}}}
                return DeviceControls.handle(shellContext(),args);
            }
            if(op.equals("share"))return ShareHost.open(shellContext(),context.getPackageName(),com.deviceagent.tools.PrimitiveArguments.map(args.get("share")),((Number)args.get("display")).intValue(),()->{try(var guard=inputGuard(((Number)args.get("display")).intValue(),args)){guard.check();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Screen control could not be checked");}});
            if(op.equals("device_summary"))return deviceSummary();
            if(op.equals("packages"))return packages(args);
            if(op.equals("resolve_launcher")){
                String pkg=Json.string(args,"package");
                android.content.Intent intent=shellContext().getPackageManager().getLaunchIntentForPackage(pkg);
                if(intent==null||intent.getComponent()==null)return Json.mapOf("ok",false);
                return Json.mapOf("ok",true,"package",intent.getComponent().getPackageName(),"activity",intent.getComponent().getClassName());
            }
            if(op.equals("top_activity")){
                int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
                String component=SystemBinders.topActivityOnDisplay(display);
                return Json.mapOf("ok",!component.isEmpty(),"component",component);
            }
            if(op.equals("display_tasks")){
                int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
                var tasks=SystemBinders.tasksOnDisplay(display);
                return tasks==null?Json.mapOf("ok",false):Json.mapOf("ok",true,"tasks",tasks);
            }
            if(op.equals("process_frozen")){
                Boolean frozen=SystemBinders.isProcessFrozen(Json.string(args,"process"));
                return frozen==null?Json.mapOf("ok",false):Json.mapOf("ok",true,"frozen",frozen);
            }
            if(op.equals("personal"))return PersonalHost.handle(shellContext(),args);
            if(op.equals("tap"))return tap(args);
            if(op.equals("activity_process")){var component=android.content.ComponentName.unflattenFromString(Json.string(args,"component"));if(component==null)throw new IllegalArgumentException("Invalid activity");var info=shellContext().getPackageManager().getActivityInfo(component,0);return Json.mapOf("ok",true,"process",info.processName);}
            if(op.equals("key"))return key(args);
            if(op.equals("user_key"))return userKey(args);
            if(op.equals("user_text"))return userText(args);
            if(op.equals("paste_text"))return pasteText(args);
            if(op.equals("notification"))return notifications.handle(shellContext(),args);
            if(op.equals("clipboard"))return clipboard(args);
            if(op.equals("screen_control"))return screenControl(args);
            if(op.equals("screen_state")){
                var shell=shellContext();
                var dm=shell.getSystemService(DisplayManager.class);
                var pm=shell.getSystemService(android.os.PowerManager.class);
                var km=shell.getSystemService(android.app.KeyguardManager.class);
                var d0=dm==null?null:dm.getDisplay(0);
                boolean screenOn=(d0==null||d0.getState()==android.view.Display.STATE_ON)&&(pm==null||pm.isInteractive());
                boolean locked=km!=null&&km.isDeviceLocked();
                return Json.mapOf("ok",true,"screenOn",screenOn,"locked",locked);
            }
            if(op.equals("screen_capture"))return screenCapture(args);
            if(op.equals("app_label")){String pkg=Json.string(args,"package");var pm=shellContext().getPackageManager();return Json.mapOf("ok",true,"label",pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString());}
            if(op.equals("location")) {
                // app_process has no ActivityManager-registered application thread.
                // Android's content command obtains an external provider handle for shell.
                var limits=com.deviceagent.tools.LocationFix.limits(args);
                String command="content call --uri "+com.deviceagent.protocol.ShellCommand.quote("content://"+context.getPackageName()+".location")
                    +" --method location --arg "+com.deviceagent.protocol.ShellCommand.quote(Json.stringify(args));
                android.os.Bundle result=LocalShell.run(command,null,((Number)limits.get("timeoutMs")).longValue()+5000);
                byte[] bytes=result.getByteArray("stdout");String output=bytes==null?"":new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim();
                int from=output.indexOf("result="),end=output.lastIndexOf("}]");
                if(from<0||end<=from)return Json.mapOf("ok",false,"code","location_service_unavailable","error","Open Backstage and resume access before requesting location.");
                return Json.object(output.substring(from+7,end));
            }
            if(op.equals("front_task")) {
                int task=GesturePlan.integer(args,"taskId",-1,1,Integer.MAX_VALUE);
                if(args.containsKey("display")){
                    int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
                    android.os.Bundle listed=LocalShell.run("am stack list",null,3000);
                    byte[] bytes=listed.getByteArray("stdout");
                    var rows=com.deviceagent.tools.TaskSnapshot.parse(bytes==null?"":new String(bytes,java.nio.charset.StandardCharsets.UTF_8),display);
                    if(rows.stream().noneMatch(t->java.util.Objects.equals(t.get("activityTaskId"),task)))return Json.mapOf("ok",false,"inputDelivered",false,"code","task_not_owned","error","This activity is no longer on the session display. Inspect the session again.");
                }
                Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                Class.forName("android.app.IActivityTaskManager").getMethod("moveTaskToFront",Class.forName("android.app.IApplicationThread"),String.class,int.class,int.class,android.os.Bundle.class)
                        .invoke(service,null,"com.android.shell",task,0,null);
                return Json.mapOf("ok",true,"taskId",task);
            }
            return op.equals("media") ? media(args) : gesture(op,args);
        }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return Json.mapOf("ok",false,"code","interrupted","error","The action was interrupted"); }
        catch (Exception e) {
            Throwable cause=e instanceof java.lang.reflect.InvocationTargetException && e.getCause()!=null?e.getCause():e;
            return Json.mapOf("ok",false,"code","action_failed","error",cause.getClass().getSimpleName()+": "+cause.getMessage());
        }
    }
    private Map<String,Object> packages(Map<String,Object> args) throws Exception {
        var fields=com.deviceagent.tools.ResultFields.packageInfo(Map.of(),args);
        var pm=shellContext().getPackageManager();
        int flags=fields.containsKey("permissions")?android.content.pm.PackageManager.GET_PERMISSIONS:0;
        String pkg=Json.string(args,"package"),query=Json.string(args,"query");
        if(pkg!=null){
            if(com.deviceagent.tools.PrivacyFence.isGloballyBlocked(pkg))return Json.mapOf("ok",false,"code","blocked_app","error","This app is blocked in Backstage privacy settings.");
            try{return packageFields(pm.getPackageInfo(pkg,flags),args);}
            catch(android.content.pm.PackageManager.NameNotFoundException e){return Json.mapOf("ok",false,"code","package_not_found","error","This package is not installed. Search installed packages and choose a matching name.");}
        }
        List<Object> rows=new ArrayList<>();
        String filter=query==null?"":query.toLowerCase(java.util.Locale.ROOT);
        // One binder listing, with no per-package dumpsys or shell process.
        for(var info:pm.getInstalledPackages(flags)){
            if(com.deviceagent.tools.PrivacyFence.isGloballyBlocked(info.packageName))continue;
            if(info.packageName.toLowerCase(java.util.Locale.ROOT).contains(filter)){
                rows.add(args.containsKey("fields")?packageFields(info,args):info.packageName);
            }
        }
        return Json.mapOf("ok",true,"count",rows.size(),"packages",rows);
    }
    @SuppressWarnings("deprecation")
    private static String certSha256(android.content.pm.PackageInfo info){
        if(info==null)return "";
        try{
            android.content.pm.Signature[] sigs=null;
            if(android.os.Build.VERSION.SDK_INT>=28&&info.signingInfo!=null){
                sigs=info.signingInfo.hasMultipleSigners()?info.signingInfo.getApkContentsSigners():info.signingInfo.getSigningCertificateHistory();
            }else{
                sigs=info.signatures;
            }
            if(sigs==null||sigs.length==0)return "";
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder sb=new StringBuilder(digest.length*2);
            for(byte b:digest)sb.append(String.format(java.util.Locale.US,"%02x",b&0xff));
            return sb.toString();
        }catch(Exception e){return "";}
    }
    private static Map<String,Object> packageFields(android.content.pm.PackageInfo info,Map<String,Object> args){
        List<Map<String,Object>> permissions=new ArrayList<>();
        if(info.requestedPermissions!=null)for(int i=0;i<info.requestedPermissions.length;i++)permissions.add(Json.mapOf("name",info.requestedPermissions[i],"granted",info.requestedPermissionsFlags!=null&&(info.requestedPermissionsFlags[i]&android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0));
        return com.deviceagent.tools.ResultFields.packageInfo(Json.mapOf("package",info.packageName,"versionName",info.versionName,"versionCode",Long.toString(info.getLongVersionCode()),"debuggable",info.applicationInfo!=null&&(info.applicationInfo.flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,"permissions",permissions),args);
    }
    private Map<String,Object> deviceSummary() throws Exception {
        Context shell=shellContext();
        var battery=shell.registerReceiver(null,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        Map<String,Object> b=new java.util.LinkedHashMap<>();
        if(battery==null){
            var read=LocalShell.run("dumpsys battery",null,2000);byte[] data=read.getByteArray("stdout");
            if(data!=null){battery=new android.content.Intent();String raw=new String(data,java.nio.charset.StandardCharsets.UTF_8);
                for(String key:List.of("level","scale","status","temperature")){var match=java.util.regex.Pattern.compile("(?m)^\\s*"+key+":\\s*(-?\\d+)\\s*$").matcher(raw);if(match.find())battery.putExtra(key,Integer.parseInt(match.group(1)));}
                int plugged=0;for(var entry:Map.of("AC powered",1,"USB powered",2,"Wireless powered",4).entrySet())if(java.util.regex.Pattern.compile("(?m)^\\s*"+entry.getKey()+":\\s*true\\s*$").matcher(raw).find())plugged|=entry.getValue();battery.putExtra("plugged",plugged);
            }
        }
        if(battery!=null){
            int level=battery.getIntExtra("level",-1),scale=battery.getIntExtra("scale",-1),status=battery.getIntExtra("status",-1);
            b.put("level",level);b.put("scale",scale);b.put("status",status);b.put("plugged",battery.getIntExtra("plugged",-1));
            b.put("percent",level>=0&&scale>0?Math.round(100.0*level/scale):null);
            b.put("charging",status<0?null:status==android.os.BatteryManager.BATTERY_STATUS_CHARGING||status==android.os.BatteryManager.BATTERY_STATUS_FULL);
            b.put("temperatureC",battery.hasExtra("temperature")?battery.getIntExtra("temperature",0)/10.0:null);
        }
        var cm=shell.getSystemService(android.net.ConnectivityManager.class);var network=cm.getActiveNetwork();var caps=network==null?null:cm.getNetworkCapabilities(network);
        Map<String,Object> n=Json.mapOf("active",network!=null,"validated",caps==null?null:caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),"internet",caps==null?null:caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),"metered",network==null?null:cm.isActiveNetworkMetered());
        List<String> transports=new ArrayList<>();
        if(caps!=null){if(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))transports.add("wifi");if(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))transports.add("cellular");if(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))transports.add("ethernet");if(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))transports.add("vpn");}
        n.put("transports",transports);
        return Json.mapOf("ok",true,"battery",b,"network",n,"source","android_system","observedAt",java.time.Instant.now().toString());
    }
    private Map<String,Object> media(Map<String,Object> args) throws Exception {
        String action=Json.string(args,"action");
        long required=switch(action==null?"":action) {
            case "status" -> 0; case "play" -> PlaybackState.ACTION_PLAY; case "pause" -> PlaybackState.ACTION_PAUSE;
            case "next" -> PlaybackState.ACTION_SKIP_TO_NEXT; case "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            case "seek" -> PlaybackState.ACTION_SEEK_TO; default -> throw new IllegalArgumentException("Unknown media action");
        };
        double position=GesturePlan.number(args,"positionMs",-1);
        if(action.equals("seek") && (position<0 || position>9007199254740991d || position!=Math.rint(position))) throw new IllegalArgumentException("seek requires nonnegative integer positionMs");
        if(!action.equals("seek")&&args.containsKey("positionMs"))throw new IllegalArgumentException("positionMs is only used with seek");
        initializeMediaServices();
        List<MediaController> active=shellContext().getSystemService(MediaSessionManager.class).getActiveSessions(null);
        sessionIds.keySet().removeIf(token -> active.stream().noneMatch(c -> c.getSessionToken().equals(token)));
        List<MediaController> selected=new ArrayList<>();
        List<Map<String,Object>> rows=new ArrayList<>();
        String pkg=Json.string(args,"package"),id=Json.string(args,"sessionId");
        for(MediaController c:active) {
            String key=sessionIds.computeIfAbsent(c.getSessionToken(),t->UUID.randomUUID().toString());
            if(pkg!=null&&!pkg.equals(c.getPackageName()) || id!=null&&!id.equals(key))continue;
            selected.add(c); rows.add(session(c,key));
        }
        if(action.equals("status"))return Json.mapOf("ok",true,"sessions",rows);
        if(selected.size()!=1)return Json.mapOf("ok",false,"code",selected.isEmpty()?"session_not_found":"ambiguous_session","error",selected.isEmpty()?"No matching active media session. Open the app and start a media session first.":"Choose a sessionId from the matching sessions.","sessions",rows);
        MediaController c=selected.get(0); PlaybackState state=c.getPlaybackState();
        if(state==null||(state.getActions()&required)==0)return Json.mapOf("ok",false,"code","unsupported_action","error","This session does not support "+action,"session",rows.get(0));
        MediaController.TransportControls controls=c.getTransportControls();
        switch(action) {case "play" -> controls.play(); case "pause" -> controls.pause(); case "next" -> controls.skipToNext(); case "previous" -> controls.skipToPrevious(); case "seek" -> controls.seekTo((long)position); default -> throw new IllegalStateException();}
        return Json.mapOf("ok",true,"accepted",true,"action",action,"session",session(c,sessionIds.get(c.getSessionToken())));
    }
    private static void initializeMediaServices() throws Exception {
        Class<?> initializer;
        try { initializer=Class.forName("android.media.MediaFrameworkPlatformInitializer"); }
        catch (ClassNotFoundException oldAndroid) { return; }
        synchronized (initializer) {
            if(initializer.getMethod("getMediaServiceManager").invoke(null)!=null)return;
            Class<?> manager=Class.forName("android.media.MediaServiceManager");
            initializer.getMethod("setMediaServiceManager",manager).invoke(null,manager.getConstructor().newInstance());
        }
    }
    private static Map<String,Object> session(MediaController c,String id) {
        PlaybackState s=c.getPlaybackState(); List<String> actions=new ArrayList<>();
        String[] names={"play","pause","next","previous","seek"}; long[] flags={PlaybackState.ACTION_PLAY,PlaybackState.ACTION_PAUSE,PlaybackState.ACTION_SKIP_TO_NEXT,PlaybackState.ACTION_SKIP_TO_PREVIOUS,PlaybackState.ACTION_SEEK_TO};
        for(int i=0;i<names.length;i++)if(s!=null&&(s.getActions()&flags[i])!=0)actions.add(names[i]);
        return Json.mapOf("sessionId",id,"package",c.getPackageName(),"state",com.deviceagent.tools.MediaState.name(s==null?null:s.getState()),"stateCode",s==null?null:s.getState(),"positionMs",s==null?null:s.getPosition(),"speed",s==null?null:s.getPlaybackSpeed(),"updatedAtElapsedMs",s==null?null:s.getLastPositionUpdateTime(),"actions",actions);
    }
    private Map<String,Object> gesture(String op,Map<String,Object> args) throws Exception {
        int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
        android.view.Display target=shellContext().getSystemService(DisplayManager.class).getDisplay(display);
        if(target==null)throw new IllegalArgumentException("The virtual display no longer exists. Open it again.");
        android.graphics.Point size=new android.graphics.Point(); target.getRealSize(size);
        GesturePlan plan=GesturePlan.create(op,args,size.x,size.y);
        com.deviceagent.tools.ActionDeadline.check();
        Class<?> cls=Class.forName("android.hardware.input.InputManager"); Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        long down=SystemClock.uptimeMillis(); int count=0; boolean finished=false; double fraction=0;Map<String,Object> result=null;
        try (var guard=inputGuard(display,args)) {
            for(int i=0;i<plan.paths.size();i++) {
                guard.check();
                count=i+1;
                send(manager,inject,setDisplay,plan,display,down,count,i==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_POINTER_DOWN|(i<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),0);
            }
            if(op.equals("long_press")) {
                guard.pause((long)plan.holdMs + plan.durationMs);
                fraction=1;
            } else {
                if(plan.holdMs>0)guard.pause(plan.holdMs);
                long start=SystemClock.uptimeMillis();
                while(fraction<1) {
                    guard.check();
                    fraction=Math.min(1,(SystemClock.uptimeMillis()-start)/(double)plan.durationMs);
                    send(manager,inject,setDisplay,plan,display,down,count,MotionEvent.ACTION_MOVE,fraction,0);
                    if(fraction<1)guard.pause(Math.min(16,Math.max(1,plan.durationMs-(SystemClock.uptimeMillis()-start))));
                }
            }
            while(count>0) {
                send(manager,inject,setDisplay,plan,display,down,count,count==1?MotionEvent.ACTION_UP:MotionEvent.ACTION_POINTER_UP|((count-1)<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),1,2);
                count--;
            }
            finished=true;
            result=Json.mapOf("ok",true,"inputDelivered",true,"display",display,"pointers",plan.paths.size(),"durationMs",plan.durationMs,"holdMs",plan.holdMs);return result;
        } catch(Exception e){result=Json.mapOf("ok",false,"inputDelivered",count==0?false:null,"code",count==0&&e instanceof com.deviceagent.tools.InputNotSentException stopped?stopped.code:"input_outcome_unknown","error",count==0?"No gesture was sent. Check the session and try again.":"The gesture stopped before it finished. Observe this display before another input.");return result;} finally {
            boolean released=true;
            if(!finished&&count>0){
                try{send(manager,inject,setDisplay,plan,display,down,count,MotionEvent.ACTION_CANCEL,fraction,2);}
                catch(Exception syncErr){
                    try{send(manager,inject,setDisplay,plan,display,down,count,MotionEvent.ACTION_CANCEL,fraction,0);}
                    catch(Exception asyncErr){released=false;}
                }
            }
            if(result!=null)result.put("pointersReleased",released);
        }
    }
    private Map<String,Object> key(Map<String,Object> args) throws Exception {
        com.deviceagent.tools.PrimitiveArguments.validate("android.key",args);
        int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
        if(shellContext().getSystemService(DisplayManager.class).getDisplay(display)==null)throw new IllegalArgumentException("The task display no longer exists. Begin a new task.");
        int key=KeyEvent.keyCodeFromString("KEYCODE_"+Json.string(args,"key"));
        if(key==KeyEvent.KEYCODE_UNKNOWN)throw new IllegalArgumentException("This Android version does not support that key");
        int repeat=GesturePlan.integer(args,"repeat",1,1,100),hold=GesturePlan.integer(args,"holdMs",0,0,3000);
        int meta=0;List<Integer> modifiers=new ArrayList<>();
        for(Object mod:(List<?>)args.getOrDefault("modifiers",List.of())){
            switch(mod.toString()){
                case "CTRL" -> {modifiers.add(KeyEvent.KEYCODE_CTRL_LEFT);meta|=KeyEvent.META_CTRL_ON|KeyEvent.META_CTRL_LEFT_ON;}
                case "ALT" -> {modifiers.add(KeyEvent.KEYCODE_ALT_LEFT);meta|=KeyEvent.META_ALT_ON|KeyEvent.META_ALT_LEFT_ON;}
                case "SHIFT" -> {modifiers.add(KeyEvent.KEYCODE_SHIFT_LEFT);meta|=KeyEvent.META_SHIFT_ON|KeyEvent.META_SHIFT_LEFT_ON;}
                case "META" -> {modifiers.add(KeyEvent.KEYCODE_META_LEFT);meta|=KeyEvent.META_META_ON|KeyEvent.META_META_LEFT_ON;}
                case "SYM" -> {modifiers.add(KeyEvent.KEYCODE_SYM);meta|=KeyEvent.META_SYM_ON;}
                case "FUNCTION" -> {modifiers.add(KeyEvent.KEYCODE_FUNCTION);meta|=KeyEvent.META_FUNCTION_ON;}
                default -> throw new IllegalArgumentException("Unknown modifier");
            }
        }
        Class<?> cls=Class.forName("android.hardware.input.InputManager");Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class),setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        List<Integer> pressed=new ArrayList<>();long down=SystemClock.uptimeMillis();boolean attempted=false,complete=false,released=true;
        try(var guard=inputGuard(display,args)){
            guard.check();
            for(int code:modifiers){guard.check();pressed.add(code);attempted=true;sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_DOWN,code,0,meta);}
            guard.check();
            pressed.add(key);attempted=true;sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_DOWN,key,0,meta);
            if(hold>0)guard.pause(hold);
            for(int i=1;i<repeat;i++){guard.check();sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_DOWN,key,i,meta);}
            complete=true;
        }catch(Exception e){/* A dispatched event may have reached the app even if Android lost its receipt. */}
        finally{
            // Release every attempted down, even when injection or the deadline fails.
            for(int i=pressed.size()-1;i>=0;i--)try{sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_UP,pressed.get(i),0,meta);}catch(Exception e){released=false;}
        }
        if(!complete||!released)return Json.mapOf("ok",false,"inputDelivered",attempted?null:false,"code",attempted?"input_outcome_unknown":"input_not_sent","error","The key has no complete receipt. Observe the display before trying again.","keysReleased",released);
        return Json.mapOf("ok",true,"inputDelivered",true,"display",display,"keysReleased",true);
    }
    private Map<String,Object> userKey(Map<String,Object> args) throws Exception {
        int display=GesturePlan.integer(args,"display",-1,1,Integer.MAX_VALUE);
        if(shellContext().getSystemService(DisplayManager.class).getDisplay(display)==null)throw new IllegalArgumentException("The virtual display no longer exists.");
        String keyName=Json.string(args,"key");
        if(keyName==null||!java.util.Set.of("BACK","ENTER","DEL","FORWARD_DEL","TAB","ESCAPE","SPACE","DPAD_UP","DPAD_DOWN","DPAD_LEFT","DPAD_RIGHT","DPAD_CENTER").contains(keyName))
            throw new IllegalArgumentException("Unsupported user takeover key");
        int code=KeyEvent.keyCodeFromString("KEYCODE_"+keyName);
        if(code==KeyEvent.KEYCODE_UNKNOWN)throw new IllegalArgumentException("Unsupported key");
        Class<?> cls=Class.forName("android.hardware.input.InputManager");
        Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        long down=SystemClock.uptimeMillis();
        sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_DOWN,code,0,0);
        sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_UP,code,0,0);
        return Json.mapOf("ok",true,"inputDelivered",true,"display",display,"key",keyName);
    }
    private Map<String,Object> userText(Map<String,Object> args) throws Exception {
        int display=GesturePlan.integer(args,"display",-1,1,Integer.MAX_VALUE);
        if(shellContext().getSystemService(DisplayManager.class).getDisplay(display)==null)throw new IllegalArgumentException("The virtual display no longer exists.");
        String text=Json.string(args,"text");
        if(text==null||text.isEmpty())return Json.mapOf("ok",true,"inputDelivered",false,"display",display);
        if(text.length()>4000)throw new IllegalArgumentException("Text is too long");
        Class<?> cls=Class.forName("android.hardware.input.InputManager");
        Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        KeyCharacterMap kcm=KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events=kcm.getEvents(text.toCharArray());
        if(events!=null){
            for(KeyEvent ev:events){
                KeyEvent scoped=new KeyEvent(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),ev.getAction(),ev.getKeyCode(),ev.getRepeatCount(),ev.getMetaState(),KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
                setDisplay.invoke(scoped,display);
                if(!Boolean.TRUE.equals(inject.invoke(manager,scoped,2)))throw new IllegalStateException("Android rejected key event");
            }
            return Json.mapOf("ok",true,"inputDelivered",true,"method","keys","display",display);
        }
        // Non-ASCII / emoji fallback: paste via clipboard with save + restore.
        Context shell=shellContext();
        var clipboard=shell.getSystemService(android.content.ClipboardManager.class);
        if(clipboard==null||clipboardLocked(shell))return clipboardLockedResult();
        android.content.ClipData saved=null;
        try{saved=clipboard.getPrimaryClip();}catch(Exception ignored){}
        String marker="backstage_user_"+UUID.randomUUID();
        boolean changed=false;
        try{
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(marker,text));changed=true;
            long down=SystemClock.uptimeMillis();
            int meta=KeyEvent.META_CTRL_ON|KeyEvent.META_CTRL_LEFT_ON;
            sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,0,meta);
            sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_V,0,meta);
            sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_V,0,meta);
            sendKey(manager,inject,setDisplay,display,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT,0,0);
            return Json.mapOf("ok",true,"inputDelivered",true,"method","paste","display",display);
        }finally{
            if(changed)try{
                var current=clipboard.getPrimaryClipDescription();
                if(current!=null&&marker.contentEquals(current.getLabel())){if(saved==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(saved);}
            }catch(Exception ignored){}
        }
    }
    private com.deviceagent.tools.InputGuard inputGuard(int display,Map<String,Object> args)throws Exception{
        if(display!=0)return new com.deviceagent.tools.InputGuard(null);
        var current=screenControl(Map.of("operation","status"));String session=Json.string(current,"sessionId");
        if(!Boolean.TRUE.equals(current.get("active"))||session==null||args.containsKey("sessionId")&&!session.equals(args.get("sessionId")))throw new com.deviceagent.tools.InputNotSentException("screen_control_stopped","Screen control stopped.");
        return new com.deviceagent.tools.InputGuard(()->{
            try{var state=screenControl(Map.of("operation","status"));return Boolean.TRUE.equals(state.get("active"))&&session.equals(state.get("sessionId"));}
            catch(Exception e){return false;}
        });
    }
    private Map<String,Object> pasteText(Map<String,Object> args) throws Exception {
        int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
        if(!Boolean.TRUE.equals(args.get("focused"))||!Boolean.TRUE.equals(args.get("clipboard"))||!(args.get("captureFocus") instanceof String expected)||expected.isBlank())throw new IllegalArgumentException("Focused text requires a current screenshot and clipboard:true");
        if("insert".equals(args.get("mode"))&&"".equals(args.get("text")))return Json.mapOf("ok",true,"inputDelivered",false,"clipboardRestored",true);
        Context shell=shellContext();
        if(clipboardLocked(shell))return clipboardLockedResult();
        if(shell.checkSelfPermission("android.permission.READ_CLIPBOARD_IN_BACKGROUND")!=android.content.pm.PackageManager.PERMISSION_GRANTED)return Json.mapOf("ok",false,"inputDelivered",false,"code","clipboard_unavailable","error","Android does not allow Backstage to preserve the clipboard. Use a field target or the on-screen keyboard.");
        var clipboard=shell.getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData saved=clipboard.getPrimaryClip();
        if(clipboardLocked(shell))return clipboardLockedResult();
        String marker="backstage_"+UUID.randomUUID();Object delivered=false;boolean restored=false,changed=false;
        Map<String,Object> result=Json.mapOf("ok",true,"inputDelivered",false);
        try{
            requireFocus(display,expected);
            if(clipboardLocked(shell))return clipboardLockedResult();
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(marker,Json.string(args,"text")));changed=true;
            var posted=clipboard.getPrimaryClipDescription();
            if(posted==null||!marker.contentEquals(posted.getLabel())){result=Json.mapOf("ok",false,"inputDelivered",false,"code","clipboard_unavailable","error","The clipboard could not be read back. Unlock the phone, then try again.");return result;}
            requireFocus(display,expected);
            if("replace".equals(args.get("mode"))){
                result=key(Map.of("display",display,"key","A","modifiers",List.of("CTRL")));delivered=result.get("inputDelivered");
                if(!Boolean.TRUE.equals(result.get("ok")))return result;
                requireFocus(display,expected);
            }
            // Wait-for-finish key injection lets the editor consume the clip before restoration.
            result=key(Map.of("display",display,"key",Json.string(args,"text").isEmpty()?"DEL":"V","modifiers",Json.string(args,"text").isEmpty()?List.of():List.of("CTRL")));
            delivered=result.get("inputDelivered");
            return result;
        }catch(com.deviceagent.tools.InputNotSentException e){result=Json.mapOf("ok",false,"inputDelivered",delivered,"code",e.code,"error",e.getMessage());return result;}
        catch(Exception e){result=Json.mapOf("ok",false,"inputDelivered",changed?null:false,"code",changed?"input_outcome_unknown":"clipboard_unavailable","error","Text entry has no complete receipt. Observe the editor before trying again.");return result;}
        finally{
            if(changed)try{
                var current=clipboard.getPrimaryClipDescription();
                if(current!=null&&marker.contentEquals(current.getLabel())){if(saved==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(saved);restored=true;}
                // If another app changed it, preserve that newer clipboard.
                else if(clipboardLocked(shell))result.put("warning","The phone locked before the clipboard could be restored. Unlock it and check the clipboard before pasting elsewhere.");
                else result.put("clipboardChangedExternally",true);
            }catch(Exception e){result.put("warning","Text may have been entered, but the clipboard could not be restored. Check it before pasting elsewhere.");}
            result.put("clipboardRestored",restored);
        }
    }
    private Map<String,Object> clipboard(Map<String,Object> args) throws Exception {
        var shell=shellContext();if(clipboardLocked(shell))return clipboardLockedResult();
        var manager=shell.getSystemService(android.content.ClipboardManager.class);
        boolean write=args.containsKey("text");
        if(write)manager.setPrimaryClip(android.content.ClipData.newPlainText("",Json.string(args,"text")));
        var clip=manager.getPrimaryClip();List<String> items=new ArrayList<>();
        if(clipboardLocked(shell)){var result=clipboardLockedResult();if(write)result.put("inputDelivered",true);return result;}
        if(clip!=null)for(int i=0;i<clip.getItemCount();i++){CharSequence text=clip.getItemAt(i).getText();if(text!=null)items.add(text.toString());}
        if(write){boolean matched=items.size()==1&&items.get(0).equals(args.get("text"));return Json.mapOf("ok",matched,"inputDelivered",true,"verification",Map.of("status",matched?"verified":"failed"));}
        return Json.mapOf("ok",true,"empty",clip==null,"text",String.join("\n",items),"textItems",items,"hasOtherContent",clip!=null&&items.size()!=clip.getItemCount());
    }
    private static boolean clipboardLocked(Context context){return context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked();}
    private static Map<String,Object> clipboardLockedResult(){return Json.mapOf("ok",false,"inputDelivered",false,"code","phone_locked","error","Unlock the phone to use the clipboard. Android hides it while the phone is locked.");}
    private Map<String,Object> screenControl(Map<String,Object> args) throws Exception {
        String operation=Json.string(args,"operation");var data=new java.util.LinkedHashMap<String,Object>();
        for(String key:List.of("sessionId","expiresAt"))if(args.containsKey(key))data.put(key,args.get(key));
        String command="content call --uri "+com.deviceagent.protocol.ShellCommand.quote("content://"+context.getPackageName()+".screencontrol")+" --method "+com.deviceagent.protocol.ShellCommand.quote(operation)+" --arg "+com.deviceagent.protocol.ShellCommand.quote(Json.stringify(data));
        var raw=LocalShell.run(command,null,4000);byte[] bytes=raw.getByteArray("stdout");String text=bytes==null?"":new String(bytes,java.nio.charset.StandardCharsets.UTF_8);int from=text.indexOf("result="),end=text.lastIndexOf("}]");
        if(from<0||end<=from)return Json.mapOf("ok",false,"active",false,"code","screen_control_unavailable","error","Update Backstage and allow its notifications before using screen control.");
        var result=Json.object(text.substring(from+7,end));
        var display=shellContext().getSystemService(DisplayManager.class).getDisplay(0);if(display==null){result.put("active",false);return result;}
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);result.put("width",size.x);result.put("height",size.y);result.put("displayState",display.getState()==android.view.Display.STATE_ON?"ON":"OFF");return result;
    }
    private Map<String,Object> tap(Map<String,Object> args) throws Exception {
        int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
        int x=GesturePlan.integer(args,"x",-1,0,100000), y=GesturePlan.integer(args,"y",-1,0,100000);
        android.view.Display target=shellContext().getSystemService(DisplayManager.class).getDisplay(display);
        if(target==null)throw new IllegalArgumentException("The virtual display no longer exists. Open it again.");
        com.deviceagent.tools.ActionDeadline.check();
        Class<?> cls=Class.forName("android.hardware.input.InputManager"); Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        long down=SystemClock.uptimeMillis(); boolean sentDown=false, finished=false;
        try (var guard=inputGuard(display,args)) {
            guard.check();
            MotionEvent evDown=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);
            try { evDown.setSource(InputDevice.SOURCE_TOUCHSCREEN); setDisplay.invoke(evDown,display); if(!Boolean.TRUE.equals(inject.invoke(manager,evDown,2)))throw new IllegalStateException("Android rejected touch down"); sentDown=true; }
            finally { evDown.recycle(); }
            guard.pause(20);
            MotionEvent evUp=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);
            try { evUp.setSource(InputDevice.SOURCE_TOUCHSCREEN); setDisplay.invoke(evUp,display); if(!Boolean.TRUE.equals(inject.invoke(manager,evUp,2)))throw new IllegalStateException("Android rejected touch up"); finished=true; }
            finally { evUp.recycle(); }
            return Json.mapOf("ok",true,"inputDelivered",true,"inputMethod","touch","display",display);
        } catch(Exception e){
            return Json.mapOf("ok",false,"inputDelivered",sentDown?null:false,"code",!sentDown&&e instanceof com.deviceagent.tools.InputNotSentException stopped?stopped.code:"input_outcome_unknown","error",sentDown?"The tap has no complete receipt. Observe the display before trying again.":"No tap was sent. Check the session and try again.");
        } finally {
            if(sentDown&&!finished){
                MotionEvent cancel=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,x,y,0);
                try {
                    cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN); setDisplay.invoke(cancel,display);
                    if(!Boolean.TRUE.equals(inject.invoke(manager,cancel,2))) inject.invoke(manager,cancel,0);
                }
                catch(Exception ignored){try{inject.invoke(manager,cancel,0);}catch(Exception ignored2){}}
                finally { cancel.recycle(); }
            }
        }
    }
    private Map<String,Object> screenCapture(Map<String,Object> args) throws Exception {
        if(GesturePlan.integer(args,"display",-1,0,0)!=0)throw new IllegalArgumentException("Choose the main display");
        var state=screenControl(Map.of("operation","status"));if(!Boolean.TRUE.equals(state.get("active")))return Json.mapOf("ok",false,"code","screen_control_stopped","error","Screen control stopped.");
        var raw=LocalShell.run("screencap -p",null,4000);byte[] png=raw.getByteArray("stdout");
        if(png==null)throw new IllegalStateException("No screen capture");
        var source=android.graphics.BitmapFactory.decodeByteArray(png,0,png.length);if(source==null)throw new IllegalStateException("No screen capture");
        android.graphics.Bitmap scaled=null;
        try{
            double scale=GesturePlan.number(args,"scale",0.5);if(scale<=0||scale>1)throw new IllegalArgumentException("Invalid capture scale");
            scaled=android.graphics.Bitmap.createScaledBitmap(source,Math.max(1,(int)Math.round(source.getWidth()*scale)),Math.max(1,(int)Math.round(source.getHeight()*scale)),true);
            var output=new java.io.ByteArrayOutputStream();
            String mime;
            if (android.os.Build.VERSION.SDK_INT >= 30 && scaled.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, output)) {
                mime = "image/webp";
            } else {
                output.reset();
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                mime = "image/png";
            }
            return Json.mapOf("ok",true,"displayId",0,"width",scaled.getWidth(),"height",scaled.getHeight(),"sourceWidth",source.getWidth(),"sourceHeight",source.getHeight(),"mime",mime,"bytes",output.size(),"pngBase64",Json.b64(output.toByteArray()));
        }finally{if(scaled!=null&&scaled!=source)scaled.recycle();source.recycle();}
    }
    private static void requireFocus(int display,String expected){
        var read=LocalShell.run(com.deviceagent.protocol.ShellCommand.focusOnDisplay(display),null,2000);
        byte[] bytes=read.getByteArray("stdout");String focus=com.deviceagent.tools.TaskSnapshot.focusIdentity(bytes==null?"":new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        if(!expected.equals(focus))throw new com.deviceagent.tools.InputNotSentException("stale_screenshot","The focused app changed. Take a new screenshot before entering text.");
    }
    private static void sendKey(Object manager,Method inject,Method setDisplay,int display,long down,int action,int key,int repeat,int meta) throws Exception {
        KeyEvent event=new KeyEvent(down,SystemClock.uptimeMillis(),action,key,repeat,meta,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
        setDisplay.invoke(event,display);
        if(!Boolean.TRUE.equals(inject.invoke(manager,event,2)))throw new IllegalStateException("Android rejected the key event");
    }
    private static void send(Object manager,Method inject,Method setDisplay,GesturePlan plan,int display,long down,int count,int action,double fraction) throws Exception {
        send(manager,inject,setDisplay,plan,display,down,count,action,fraction,2);
    }
    private static void send(Object manager,Method inject,Method setDisplay,GesturePlan plan,int display,long down,int count,int action,double fraction,int mode) throws Exception {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++) {
            properties[i]=new MotionEvent.PointerProperties(); properties[i].id=i; properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            float[] point=plan.at(i,fraction); coords[i]=new MotionEvent.PointerCoords(); coords[i].x=point[0]; coords[i].y=point[1]; coords[i].pressure=1; coords[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {setDisplay.invoke(event,display); if(!Boolean.TRUE.equals(inject.invoke(manager,event,mode)))throw new IllegalStateException("Android rejected the gesture event");}
        finally {event.recycle();}
    }
}
