package com.deviceagent.shell;

import android.content.Context;
import android.media.*;
import java.lang.reflect.*;

/** A temporary route matching one app UID, always unregistered after use. */
final class AudioRoute implements AutoCloseable {
 private Object policy;private Class<?> policyType;AudioRecord record;AudioTrack track;
 static Object invoke(Object target,String name,Class<?>[] signature,Object... args)throws Exception{return target.getClass().getMethod(name,signature).invoke(target,args);}
 AudioRoute(Context context,int uid,boolean injection)throws Exception{
  this(context,uid,injection,48000,1);
 }
 AudioRoute(Context context,int uid,boolean injection,int sampleRate,int channels)throws Exception{
  try{
   // AudioPolicy's recorder constructor resolves attribution from the process
   // application, rather than its policy Context. A shell app_process otherwise
   // inherits the system application's UID/package and fails audio attribution.
   Class<?> activityThread=Class.forName("android.app.ActivityThread");
   Object current=activityThread.getMethod("currentActivityThread").invoke(null);
   Field initial=activityThread.getDeclaredField("mInitialApplication");initial.setAccessible(true);
   initial.set(current,android.app.Instrumentation.newApplication(android.app.Application.class,context));
   Class<?> rule=Class.forName("android.media.audiopolicy.AudioMixingRule"),mix=Class.forName("android.media.audiopolicy.AudioMix");policyType=Class.forName("android.media.audiopolicy.AudioPolicy");
   Object rules=Class.forName(rule.getName()+"$Builder").getConstructor().newInstance();
   invoke(rules,"setTargetMixRole",new Class<?>[]{int.class},rule.getField(injection?"MIX_ROLE_INJECTOR":"MIX_ROLE_PLAYERS").getInt(null));
   invoke(rules,"addMixRule",new Class<?>[]{int.class,Object.class},rule.getField("RULE_MATCH_UID").getInt(null),Integer.valueOf(uid));
   Object mixBuilder=Class.forName(mix.getName()+"$Builder").getConstructor(rule).newInstance(invoke(rules,"build",new Class<?>[]{}));
   AudioFormat format=new AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO).build();
   invoke(mixBuilder,"setFormat",new Class<?>[]{AudioFormat.class},format);invoke(mixBuilder,"setRouteFlags",new Class<?>[]{int.class},mix.getField(injection?"ROUTE_FLAG_LOOP_BACK":"ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null));Object mixed=invoke(mixBuilder,"build",new Class<?>[]{});
   Object builder=Class.forName(policyType.getName()+"$Builder").getConstructor(Context.class).newInstance(context);invoke(builder,"addMix",new Class<?>[]{mix},mixed);policy=invoke(builder,"build",new Class<?>[]{});
   Method register=AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic",policyType);register.setAccessible(true);int result=(Integer)register.invoke(null,policy);if(result!=0)throw new IllegalStateException("Audio route registration returned "+result);
   if(injection){track=(AudioTrack)invoke(policy,"createAudioTrackSource",new Class<?>[]{mix},mixed);if(track==null||track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Audio input route did not initialize");}
   else{record=(AudioRecord)invoke(policy,"createAudioRecordSink",new Class<?>[]{mix},mixed);if(record==null||record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Audio capture route did not initialize ("+(record==null?"null":"state="+record.getState())+")");}
  }catch(Exception e){try{close();}catch(RuntimeException cleanup){e.addSuppressed(cleanup);}throw e;}
 }
 @Override public void close(){
  RuntimeException failure=null;
  if(record!=null)try{record.release();}catch(RuntimeException e){failure=e;}finally{record=null;}
  if(track!=null)try{track.release();}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{track=null;}
  if(policy!=null)try{Method remove=AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic",policyType);remove.setAccessible(true);remove.invoke(null,policy);}catch(Exception e){if(failure==null)failure=new IllegalStateException("Audio route cleanup was not confirmed",e);else failure.addSuppressed(e);}finally{policy=null;}
  if(failure!=null)throw failure;
 }
}
