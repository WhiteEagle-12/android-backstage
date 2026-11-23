package com.deviceagent.shell;
import android.media.*;
import com.deviceagent.daemon.SystemContext;
import java.util.*;
/** Captures only a generated shell-UID tone, never microphone or personal app audio. */
public final class AudioRouteDeviceCheck {
 public static void main(String[] argv){com.deviceagent.tools.DeviceCheckGuard.start("AudioRouteDeviceCheck");AudioTrack tone=null;int exit=1;
  try{
   var base=SystemContext.create("com.androidbackstage.app");var host=new InteractionHost(base);var get=InteractionHost.class.getDeclaredMethod("shellContext");get.setAccessible(true);var shell=(android.content.Context)get.invoke(host);
   try(var route=new AudioRoute(shell,2000,false)){
    var attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();var format=new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
    short[] samples=new short[48000];for(int i=0;i<samples.length;i++)samples[i]=(short)(1200*Math.sin(2*Math.PI*1000*i/48000));
    tone=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.length*2).build();tone.write(samples,0,samples.length);tone.setLoopPoints(0,samples.length,-1);
    route.record.startRecording();tone.play();long until=System.currentTimeMillis()+2000;short[] read=new short[4800];long energy=0,count=0;int peak=0;
    while(System.currentTimeMillis()<until){int n=route.record.read(read,0,read.length,AudioRecord.READ_NON_BLOCKING);if(n<0)throw new IllegalStateException("Audio read "+n);for(int i=0;i<n;i++){energy+=(long)read[i]*read[i];peak=Math.max(peak,Math.abs(read[i]));}count+=n;Thread.sleep(10);}
    System.out.println("AUDIO samples="+count+" peak="+peak+" rms="+Math.sqrt(energy/(double)Math.max(1,count)));if(count<48000||peak<100)throw new AssertionError("Generated app audio was not captured");
   }
   System.out.println("AUDIO ROUTE CHECK COMPLETE");exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}finally{if(tone!=null)tone.release();}System.exit(exit);
 }
}
