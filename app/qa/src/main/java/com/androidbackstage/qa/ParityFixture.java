package com.androidbackstage.qa;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Private-display fixture with inaccessible text, raw pointer events and local notification replies. */
@android.annotation.SuppressLint("SetTextI18n")
public final class ParityFixture extends Activity {
    static final int NOTIFICATION=91273;
    static android.content.SharedPreferences state(Context context){return context.getSharedPreferences("parity",0);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(getDisplay()==null||getDisplay().getDisplayId()==0){finish();return;}
        var root=new FrameLayout(this);root.setBackgroundColor(Color.WHITE);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        EditText editor=new EditText(this);editor.setSingleLine(false);editor.setTextSize(22);editor.setHint("Text");
        var field=new FrameLayout.LayoutParams(-1,160);field.setMargins(40,100,40,0);root.addView(editor,field);
        editor.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){state(ParityFixture.this).edit().putString("text",s.toString()).apply();}public void afterTextChanged(android.text.Editable e){}});
        View canvas=new View(this){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);int count;long started;
            @Override protected void onDraw(Canvas c){paint.setColor(Color.rgb(215,232,255));c.drawRect(0,0,getWidth(),getHeight(),paint);paint.setColor(Color.BLACK);paint.setTextSize(32);c.drawText("Touch area",30,55,paint);}
            @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){count=0;started=e.getEventTime();}count=Math.max(count,e.getPointerCount());if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){state(ParityFixture.this).edit().putInt("pointers",count).putFloat("endX",e.getX()).putFloat("endY",e.getY()).putLong("duration",e.getEventTime()-started).putInt("display",getDisplay().getDisplayId()).apply();performClick();}return true;}
            @Override public boolean performClick(){super.performClick();return true;}
        };
        var area=new FrameLayout.LayoutParams(-1,480);area.setMargins(40,350,40,0);root.addView(canvas,area);
        setContentView(root);editor.requestFocus();getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        state(this).edit().putString("text","").putInt("actionCount",0).remove("reply").apply();
        receiveShare(getIntent());postNotification(this);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);receiveShare(intent);}
    private void receiveShare(Intent intent){
        if(!Intent.ACTION_SEND.equals(intent.getAction())&&!Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))return;
        try{
            List<android.net.Uri> uris=new ArrayList<>();
            if(Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())){var list=intent.<android.net.Uri>getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(list!=null)uris.addAll(list);}
            else{android.net.Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)uris.add(uri);}
            org.json.JSONArray files=new org.json.JSONArray();
            for(var uri:uris){var digest=java.security.MessageDigest.getInstance("SHA-256");long bytes=0;try(var in=getContentResolver().openInputStream(uri)){byte[] chunk=new byte[65536];int count;while((count=in.read(chunk))!=-1){digest.update(chunk,0,count);bytes+=count;}}StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b));files.put(new org.json.JSONObject().put("bytes",bytes).put("sha256",hash.toString()));}
            state(this).edit().putString("shareText",Objects.toString(intent.getStringExtra(Intent.EXTRA_TEXT),"")).putString("shareFiles",files.toString()).putInt("shareDisplay",getDisplay().getDisplayId()).apply();
        }catch(Exception e){state(this).edit().putString("shareError",e.toString()).apply();}
    }
    static void postNotification(Context context){
        if(android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        var manager=context.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("parity","Backstage test",NotificationManager.IMPORTANCE_LOW));
        var action=new Intent(context,NotificationAction.class).setAction("fixture.action");
        var reply=new Intent(context,NotificationAction.class).setAction("fixture.reply");
        var open=new Intent(context,ParityFixture.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        manager.notify(NOTIFICATION,new Notification.Builder(context,"parity").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Backstage fixture").setContentText("Notification action test")
            .setContentIntent(PendingIntent.getActivity(context,10,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
            .addAction(new Notification.Action.Builder(null,"Count",PendingIntent.getBroadcast(context,11,action,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(new Notification.Action.Builder(null,"Reply",PendingIntent.getBroadcast(context,12,reply,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE)).addRemoteInput(new RemoteInput.Builder("replyText").setLabel("Reply").build()).build()).build());
    }
    public static final class NotificationAction extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){var state=state(context);if("fixture.action".equals(intent.getAction()))state.edit().putInt("actionCount",state.getInt("actionCount",0)+1).apply();else{Bundle values=RemoteInput.getResultsFromIntent(intent);state.edit().putString("reply",values==null?"no input":Objects.toString(values.getCharSequence("replyText"),"")).apply();}}
    }
}
