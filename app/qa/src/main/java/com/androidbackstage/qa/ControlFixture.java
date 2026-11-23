package com.androidbackstage.qa;

import android.app.Activity;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Separate test APK: no network, audio, permissions, or access to the product's data. */
public final class ControlFixture extends Activity {
    private final List<MediaSession> sessions=new ArrayList<>();
    private JSONObject state=new JSONObject();
    private JSONArray events=new JSONArray();
    private long started;
    private int maxPointers;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(getDisplay()==null || getDisplay().getDisplayId()==0) { finish(); return; }
        TextView view=new TextView(this) {
            @Override public boolean onTouchEvent(MotionEvent e) {
                try {
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){events=new JSONArray();started=e.getEventTime();maxPointers=0;}
                    maxPointers=Math.max(maxPointers,e.getPointerCount());
                    if(e.getActionMasked()!=MotionEvent.ACTION_MOVE || events.length()<15)events.put(new JSONObject().put("action",e.getActionMasked()).put("count",e.getPointerCount()).put("x",e.getX()).put("y",e.getY()));
                    if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                        state.put("gesture",new JSONObject().put("display",getDisplay().getDisplayId()).put("events",events).put("maxPointers",maxPointers).put("elapsedMs",e.getEventTime()-started).put("endX",e.getX()).put("endY",e.getY()));save();
                        if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
                    }
                }catch(Exception ex){throw new RuntimeException(ex);}
                return true;
            }
            @Override public boolean performClick(){super.performClick();return true;}
        };
        view.setId(R.id.status);view.setText("Waiting");view.setTextSize(24);view.setGravity(android.view.Gravity.CENTER);view.setClickable(true);setContentView(view);
        new Handler(Looper.getMainLooper()).postDelayed(()->view.setText("Ready"),getIntent().getIntExtra("delayMs",800));
        int count=getIntent().getIntExtra("sessions",1);
        for(int i=0;i<count;i++) {
            MediaSession session=new MediaSession(this,"Backstage-QA-"+i); final int index=i;
            session.setCallback(new MediaSession.Callback(){
                private void record(String action,long position,int playback) {
                    try {state.put("media",new JSONObject().put("action",action).put("positionMs",position).put("sessionIndex",index));save();}catch(Exception e){throw new RuntimeException(e);}
                    update(session,playback,position);
                }
                @Override public void onPlay(){record("play",0,PlaybackState.STATE_PLAYING);}
                @Override public void onPause(){record("pause",0,PlaybackState.STATE_PAUSED);}
                @Override public void onSkipToNext(){record("next",0,PlaybackState.STATE_PAUSED);}
                @Override public void onSkipToPrevious(){record("previous",0,PlaybackState.STATE_PAUSED);}
                @Override public void onSeekTo(long p){record("seek",p,PlaybackState.STATE_PAUSED);}
            },new Handler(Looper.getMainLooper()));
            update(session,PlaybackState.STATE_PAUSED,0);session.setActive(true);sessions.add(session);
        }
    }
    private static void update(MediaSession session,int playback,long position) {
        session.setPlaybackState(new PlaybackState.Builder().setState(playback,position,playback==PlaybackState.STATE_PLAYING?1:0)
                .setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO).build());
    }
    private void save() throws Exception {
        try(java.io.FileOutputStream out=openFileOutput("events.json",MODE_PRIVATE)){out.write(state.toString().getBytes(StandardCharsets.UTF_8));}
    }
    @Override public void onDestroy(){for(MediaSession session:sessions)session.release();super.onDestroy();}
}
