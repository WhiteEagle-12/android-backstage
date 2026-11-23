package com.deviceagent.shell;
import android.media.*;
import com.deviceagent.daemon.SystemContext;

/** Routes a generated signal into this diagnostic UID's recorder; saves no audio. */
public final class AudioInjectionDeviceCheck {
 @android.annotation.SuppressLint("MissingPermission") // app_process runs under shell's permission grant.
 public static void main(String[] argv){
  com.deviceagent.tools.DeviceCheckGuard.start("AudioInjectionDeviceCheck");int exit=1;
  try{
   var base=SystemContext.create("com.androidbackstage.app");var host=new InteractionHost(base);var get=InteractionHost.class.getDeclaredMethod("shellContext");get.setAccessible(true);var shell=(android.content.Context)get.invoke(host);
   if(shell.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Shell lacks RECORD_AUDIO");
   try(var route=new AudioRoute(shell,2000,true)){
    var format=new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
    AudioRecord record=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(format).setBufferSizeInBytes(48000).build();
    try{
     short[] tone=new short[480];for(int i=0;i<tone.length;i++)tone[i]=(short)(1200*Math.sin(2*Math.PI*1000*i/48000));
     short[] data=new short[4800];long until=System.nanoTime()+2_000_000_000L,count=0,energy=0;int peak=0;
     record.startRecording();route.track.play();
     while(System.nanoTime()<until){
      int wrote=route.track.write(tone,0,tone.length,AudioTrack.WRITE_NON_BLOCKING);if(wrote<0)throw new AssertionError("write "+wrote);
      int n=record.read(data,0,data.length,AudioRecord.READ_NON_BLOCKING);if(n<0)throw new AssertionError("read "+n);
      for(int i=0;i<n;i++){peak=Math.max(peak,Math.abs(data[i]));energy+=(long)data[i]*data[i];}count+=n;Thread.sleep(5);
     }
     double rms=Math.sqrt(energy/(double)Math.max(1,count));System.out.println("INJECT samples="+count+" peak="+peak+" rms="+rms);
     if(count<48000||peak!=1200||rms<500||rms>900)throw new AssertionError("The generated signal did not reach the recorder");
    }finally{record.release();}
   }
   System.out.println("AUDIO INJECTION CHECK COMPLETE");exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}System.exit(exit);
 }
}
