package com.deviceagent.shell;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import com.deviceagent.tools.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A shell-authorized listener exists only during a tool call. No settings are changed. */
@android.annotation.SuppressLint({"SoonBlockedPrivateApi","MissingPermission"})
public final class NotificationHost {
    private record Seen(String key,long postTime,String fingerprint,long observedAt,List<PendingIntent> intents) {}
    private final Map<String,Seen> seen=new LinkedHashMap<>();
    private static final class Listener extends NotificationListenerService {
        final CountDownLatch connected=new CountDownLatch(1);
        void attach(Context context){attachBaseContext(context);}
        @Override public void onListenerConnected(){connected.countDown();}
    }
    public synchronized Map<String,Object> handle(Context shell,Map<String,Object> args) {
        String operation=Objects.toString(args.getOrDefault("operation","list"));
        HandlerThread thread=new HandlerThread("backstage-notifications");thread.start();
        Context context=new ContextWrapper(shell){@Override public Looper getMainLooper(){return thread.getLooper();}};
        Listener listener=new Listener();listener.attach(context);boolean registered=false,attempted=false;
        try{
            int user=(int)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
            var register=NotificationListenerService.class.getDeclaredMethod("registerAsSystemService",Context.class,ComponentName.class,int.class);
            register.setAccessible(true);registered=true;register.invoke(listener,context,new ComponentName("com.android.shell",NotificationHost.class.getName()),user);
            if(!listener.connected.await(2500,TimeUnit.MILLISECONDS))return failure("notification_unavailable","Android did not connect notification access. Try again.",false);
            var active=listener.getActiveNotifications();
            if(active==null)return failure("notification_unavailable","Android did not return current notifications. Try again.",false);
            long now=android.os.SystemClock.elapsedRealtime();seen.entrySet().removeIf(e->now-e.getValue().observedAt>300000);
            if(operation.equals("list")){
                List<Map<String,Object>> rows=new ArrayList<>();String pkg=Json.string(args,"package");int total=0,max=GesturePlan.integer(args,"max",20,1,100);
                if(pkg!=null&&PrivacyFence.isGloballyBlocked(pkg))return failure("blocked_app","This app is blocked in Backstage privacy settings.",false);
                for(var notification:active){
                    if(PrivacyFence.isGloballyBlocked(notification.getPackageName()))continue;
                    if(pkg!=null&&!pkg.equals(notification.getPackageName()))continue;total++;if(rows.size()>=max)continue;
                    String fingerprint=fingerprint(notification),handle=null;
                    for(var entry:seen.entrySet())if(same(entry.getValue(),notification,fingerprint)){handle=entry.getKey();break;}
                    if(handle==null){handle="notification_"+UUID.randomUUID();seen.put(handle,new Seen(notification.getKey(),notification.getPostTime(),fingerprint,now,intents(notification)));}
                    var row=describe(notification);row.put("notificationId",handle);rows.add(row);
                }
                while(seen.size()>300)seen.remove(seen.keySet().iterator().next());
                return Json.mapOf("ok",true,"count",rows.size(),"total",total,"truncated",total>rows.size(),"notifications",rows);
            }
            var previous=seen.get(Json.string(args,"notificationId"));
            if(previous==null)return failure("notification_expired","List notifications again before acting.",false);
            StatusBarNotification selected=null;
            for(var n:active)if(same(previous,n,fingerprint(n))){selected=n;break;}
            if(selected==null)return failure("stale_notification","The notification changed or disappeared. List notifications again.",false);
            if(PrivacyFence.isGloballyBlocked(selected.getPackageName()))return failure("blocked_app","This app is blocked in Backstage privacy settings.",false);
            if(operation.equals("dismiss")){
                if(!selected.isClearable())return failure("notification_not_clearable","Android does not allow this notification to be dismissed.",false);
                attempted=true;listener.cancelNotification(selected.getKey());
                boolean removed=false;
                for(int i=0;i<10;i++){var current=listener.getActiveNotifications(new String[]{selected.getKey()});if(current!=null&&current.length==0){removed=true;break;}Thread.sleep(50);}
                return Json.mapOf("ok",removed,"inputDelivered",true,"verification",Map.of("status",removed?"verified":"failed"),"dismissed",removed);
            }
            Notification notification=selected.getNotification();PendingIntent pending;Intent fill=new Intent();
            if(operation.equals("open"))pending=notification.contentIntent;
            else{
                String id=Json.string(args,"actionId");
                if(id==null||!id.matches("action_[0-9]+"))return failure("invalid_action","Choose an actionId from this notification.",false);
                int index=Integer.parseInt(id.substring(7));
                if(notification.actions==null||index>=notification.actions.length)return failure("invalid_action","Choose an actionId from this notification.",false);
                Notification.Action action=notification.actions[index];pending=action.actionIntent;
                if(operation.equals("reply")){
                    var inputs=action.getRemoteInputs();var responses=PrimitiveArguments.map(args.get("responses"));Bundle values=new Bundle();
                    if(inputs==null||pending==null||pendingProperty(pending,"isImmutable"))return failure("reply_unavailable","This action does not accept a reply. Open the app to reply.",false);
                    for(var entry:responses.entrySet()){
                        RemoteInput target=null;for(var input:inputs)if(input.getResultKey().equals(entry.getKey()))target=input;
                        String text=entry.getValue().toString();
                        if(target==null||!target.getAllowFreeFormInput()&&(target.getChoices()==null||Arrays.stream(target.getChoices()).noneMatch(c->text.contentEquals(c))))return failure("invalid_reply","Use the reply fields and choices listed on this notification.",false);
                        values.putCharSequence(entry.getKey(),text);
                    }
                    RemoteInput.addResultsToIntent(inputs,fill,values);RemoteInput.setResultsSource(fill,RemoteInput.SOURCE_FREE_FORM_INPUT);
                }else if(!operation.equals("action"))return failure("invalid_argument","Unknown notification operation.",false);
            }
            if(pending==null)return failure("action_unavailable","This notification has no available action. Open its app instead.",false);
            ActivityOptions options=ActivityOptions.makeBasic();
            if(pendingProperty(pending,"isActivity")){
                int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);options.setLaunchDisplayId(display);
                if(android.os.Build.VERSION.SDK_INT>=34)options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            ActionDeadline.check();attempted=true;
            pending.send(context,0,fill,null,null,null,options.toBundle());
            return Json.mapOf("ok",true,"inputDelivered",true,"verification",Map.of("status","unverified"));
        }catch(PendingIntent.CanceledException e){return failure("stale_notification","The action is no longer available. List notifications again.",false);}
        catch(Exception e){if(Boolean.getBoolean("backstage.traceNotifications"))e.printStackTrace(System.err);return failure(attempted?"input_outcome_unknown":"notification_unavailable",attempted?"The action has no complete receipt. Check the notification or app before trying again.":"Android did not allow notification access. Read notifications in the app or notification shade.",attempted?null:false);}
        finally{
            if(registered)try{var unregister=NotificationListenerService.class.getDeclaredMethod("unregisterAsSystemService");unregister.setAccessible(true);unregister.invoke(listener);}catch(Exception ignored){}
            thread.quitSafely();
        }
    }
    private static boolean same(Seen seen,StatusBarNotification current,String fingerprint){return seen.key.equals(current.getKey())&&seen.postTime==current.getPostTime()&&seen.fingerprint.equals(fingerprint)&&seen.intents.equals(intents(current));}
    private static boolean pendingProperty(PendingIntent pending,String name) throws Exception{return Boolean.TRUE.equals(PendingIntent.class.getDeclaredMethod(name).invoke(pending));}
    private static List<PendingIntent> intents(StatusBarNotification current){var n=current.getNotification();List<PendingIntent> out=new ArrayList<>();out.add(n.contentIntent);if(n.actions!=null)for(var a:n.actions)out.add(a.actionIntent);return out;}
    private static String fingerprint(StatusBarNotification current){
        var n=current.getNotification();var row=describe(current);row.put("when",n.when);
        return Json.stringify(row);
    }
    private static Map<String,Object> describe(StatusBarNotification current){
        var n=current.getNotification();var row=Json.mapOf("package",current.getPackageName(),"id",current.getId(),"postTime",current.getPostTime(),"clearable",current.isClearable(),"openable",n.contentIntent!=null);
        for(var field:Map.of("title",Notification.EXTRA_TITLE,"text",Notification.EXTRA_TEXT,"bigText",Notification.EXTRA_BIG_TEXT,"subText",Notification.EXTRA_SUB_TEXT).entrySet()){
            CharSequence value=n.extras.getCharSequence(field.getValue());if(value!=null)row.put(field.getKey(),value.toString());
        }
        List<Map<String,Object>> actions=new ArrayList<>();
        if(n.actions!=null)for(int i=0;i<n.actions.length;i++){
            var action=n.actions[i];var item=Json.mapOf("actionId","action_"+i,"label",Objects.toString(action.title,""),"available",action.actionIntent!=null);
            var inputs=action.getRemoteInputs();List<Map<String,Object>> replies=new ArrayList<>();
            if(inputs!=null)for(var input:inputs){var reply=Json.mapOf("key",input.getResultKey(),"label",Objects.toString(input.getLabel(),""),"freeText",input.getAllowFreeFormInput());if(input.getChoices()!=null)reply.put("choices",Arrays.stream(input.getChoices()).map(Object::toString).collect(java.util.stream.Collectors.toList()));replies.add(reply);}
            if(!replies.isEmpty())item.put("replyFields",replies);actions.add(item);
        }
        row.put("actions",actions);return row;
    }
    private static Map<String,Object> failure(String code,String message,Object delivered){return Json.mapOf("ok",false,"code",code,"error",message,"inputDelivered",delivered);}
}
