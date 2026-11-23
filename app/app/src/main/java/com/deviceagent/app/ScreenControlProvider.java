package com.deviceagent.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import com.deviceagent.tools.*;
import java.util.*;

/** App-owned visible state for an explicitly requested main-screen lease. */
public final class ScreenControlProvider extends ContentProvider {
    private static final String PREFS="screen_control",CHANNEL="screen_control";
    private static final int NOTICE=91274;
    @Override public boolean onCreate(){return true;}
    public static synchronized void clear(Context context){context.getSharedPreferences(PREFS,0).edit().clear().commit();context.getSystemService(NotificationManager.class).cancel(NOTICE);}
    @Override public Bundle call(String method,String argument,Bundle extras){synchronized(ScreenControlProvider.class){return callLocked(method,argument,extras);}}
    private Bundle callLocked(String method,String argument,Bundle extras){
        int uid=Binder.getCallingUid();if(!LocationAccess.callerAllowed(uid,android.os.Process.myUid()))throw new SecurityException("Only the Backstage helper may control a screen lease");
        long identity=Binder.clearCallingIdentity();
        try{
            Context context=getContext();var args=Json.object(argument==null?"{}":argument);var prefs=context.getSharedPreferences(PREFS,0);
            String session=Json.string(args,"sessionId"),current=prefs.getString("sessionId","");long now=System.currentTimeMillis(),expiry=prefs.getLong("expiresAt",0);
            var manager=context.getSystemService(NotificationManager.class);
            if(!current.isEmpty()&&(expiry<=now||Arrays.stream(manager.getActiveNotifications()).noneMatch(n->n.getId()==NOTICE))){clear(context);current="";expiry=0;}
            if("finish".equals(method)){
                if(current.isEmpty()||current.equals(session)){clear(context);return reply(Json.mapOf("ok",true,"active",false));}
                return reply(Json.mapOf("ok",false,"code","session_not_owned","error","Another task is using the screen."));
            }
            if(!AgentConfig.load(context).accessEnabled)return reply(Json.mapOf("ok",false,"active",false,"code","access_paused","error","Open Backstage and resume access."));
            if("begin".equals(method)||"renew".equals(method)){
                if(session==null||!session.matches("session_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid session");
                if(!current.isEmpty()&&!current.equals(session))return reply(Json.mapOf("ok",false,"active",true,"code","screen_in_use","error","Another task is using the screen. Finish it first."));
                if("renew".equals(method)&&current.isEmpty())return reply(Json.mapOf("ok",false,"active",false,"code","screen_control_stopped","error","Screen control stopped. Begin a new task when the user is ready."));
                if(!(args.get("expiresAt") instanceof Number time)||time.longValue()<=now||time.longValue()>now+1800000)throw new IllegalArgumentException("Invalid screen lease");
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Screen control",NotificationManager.IMPORTANCE_LOW));
                if(!manager.areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE)return reply(Json.mapOf("ok",false,"active",false,"code","screen_notification_required","error","Allow Backstage notifications before using screen control."));
                long created=current.isEmpty()?now:prefs.getLong("createdAt",now);expiry=time.longValue();
                if(!prefs.edit().putString("sessionId",session).putLong("createdAt",created).putLong("expiresAt",expiry).commit())throw new IllegalStateException("The screen lease could not be saved");
                Intent pause=new Intent(context,StopReceiver.class).setAction("com.androidbackstage.STOP_SCREEN_CONTROL").setData(Uri.parse("backstage://screen/"+session)).putExtra("sessionId",session);
                PendingIntent stop=PendingIntent.getBroadcast(context,NOTICE,pause,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                PendingIntent open=PendingIntent.getActivity(context,NOTICE,new Intent(context,AgentActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                manager.notify(NOTICE,new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Screen control").setContentText("An assistant is using your phone.").setContentIntent(open).setDeleteIntent(stop).addAction(new Notification.Action.Builder(null,"Stop screen control",stop).build()).setOngoing(true).setOnlyAlertOnce(true).setTimeoutAfter(expiry-now).build());
                current=session;
            }else if(!"status".equals(method))throw new IllegalArgumentException("Unknown screen operation");
            boolean visible=Arrays.stream(manager.getActiveNotifications()).anyMatch(n->n.getId()==NOTICE);
            if("begin".equals(method))for(int i=0;!visible&&i<10;i++){android.os.SystemClock.sleep(50);visible=Arrays.stream(manager.getActiveNotifications()).anyMatch(n->n.getId()==NOTICE);}
            if(!visible&&!current.isEmpty()){clear(context);return reply(Json.mapOf("ok",false,"active",false,"code","screen_notification_required","error","Allow Backstage notifications before using screen control."));}
            boolean active=!current.isEmpty()&&expiry>now&&visible;
            return reply(Json.mapOf("ok",true,"active",active,"created","begin".equals(method)&&active,"sessionId",current,"displayId",0,"createdAt",prefs.getLong("createdAt",0),"expiresAt",expiry));
        }catch(RuntimeException e){return reply(Json.mapOf("ok",false,"active",false,"code","screen_control_unavailable","error","Screen control could not start. Open Backstage and check notification access."));}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    private static Bundle reply(Map<String,Object> result){Bundle out=new Bundle();out.putString("result",Json.stringify(result));return out;}
    public static final class StopReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){synchronized(ScreenControlProvider.class){if(Objects.equals(intent.getStringExtra("sessionId"),context.getSharedPreferences(PREFS,0).getString("sessionId","")))clear(context);}}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){throw new UnsupportedOperationException();}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
