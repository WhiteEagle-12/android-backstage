package com.deviceagent.tools;
import com.deviceagent.shell.*;
import com.deviceagent.daemon.SystemContext;
import android.media.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
public final class AudioJobDeviceCheck {
 @android.annotation.SuppressLint("MissingPermission") // Diagnostic runs with shell's RECORD_AUDIO grant.
 public static void main(String[] argv){
  DeviceCheckGuard.start("AudioJobDeviceCheck");AudioTrack tone=null;AndroidTools tools=null;String id=null;int exit=1;
  Path file=Paths.get("/data/local/tmp/backstage-audio-job-"+UUID.randomUUID()+".wav");
  try{
   var context=SystemContext.create("com.androidbackstage.app");
   tools=new AndroidTools(new LocalExecutor(new VirtualDisplayHost(context),new AccessibilityHost(),new InteractionHost(context)),"com.androidbackstage.app","/data/local/tmp/backstage-audio-check/config.json");
   var start=tools.call("android.audio",Map.of("operation","capture","package","com.android.shell","path",file.toString(),"channels",1,"leaseSeconds",10,"requestId",UUID.randomUUID().toString()));
   System.out.println("START "+Json.stringify(start));id=Json.string(start,"audioId");ParityDeviceCheck.check(id!=null,"public audio job starts");
   var denied=tools.callOwned("android.audio",Map.of("operation","stop","audioId",id,"requestId",UUID.randomUUID().toString()),"another-owner");ParityDeviceCheck.check("audio_job_not_found".equals(denied.get("code")),"other assistant cannot stop route");
   short[] samples=new short[48000];for(int i=0;i<samples.length;i++)samples[i]=(short)(1200*Math.sin(2*Math.PI*1000*i/48000));
   tone=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.length*2).build();tone.write(samples,0,samples.length);tone.play();Thread.sleep(1200);
   var status=tools.call("android.audio",Map.of("operation","status","audioId",id));ParityDeviceCheck.check(((Number)status.get("bytes")).longValue()>48000,"capture progresses while caller is free");
   tools.call("android.audio",Map.of("operation","renew","audioId",id,"leaseSeconds",20,"requestId",UUID.randomUUID().toString()));
   tools.call("android.audio",Map.of("operation","stop","audioId",id,"requestId",UUID.randomUUID().toString()));
   for(int i=0;i<100;i++){status=tools.call("android.audio",Map.of("operation","status","audioId",id));if(!"running".equals(status.get("state")))break;Thread.sleep(20);}
   ParityDeviceCheck.check("stopped".equals(status.get("state")),"stop finalizes capture");
   try(var input=FileChannel.open(file)){var wave=PcmWave.parse(input);ParityDeviceCheck.check(wave.bytes()==((Number)status.get("bytes")).longValue(),"WAV header matches captured bytes");}
   var injection=tools.call("android.audio",Map.of("operation","inject","package","com.android.shell","path",file.toString(),"leaseSeconds",10,"requestId",UUID.randomUUID().toString()));id=Json.string(injection,"audioId");ParityDeviceCheck.check(id!=null,"public audio input job starts");
   var recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(48000).build();
   try{recorder.startRecording();short[] read=new short[4800];int peak=0;long until=System.nanoTime()+2_000_000_000L;while(System.nanoTime()<until){int n=recorder.read(read,0,read.length,AudioRecord.READ_NON_BLOCKING);if(n<0)throw new AssertionError("recorder read failed");for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs(read[i]));Thread.sleep(5);}System.out.println("INPUT peak="+peak+" job="+Json.stringify(tools.call("android.audio",Map.of("operation","status","audioId",id))));ParityDeviceCheck.check(peak==1200,"recorder confirms the captured tone arrived through the public input job");}
   finally{recorder.release();}
   status=tools.call("android.audio",Map.of("operation","status","audioId",id));ParityDeviceCheck.check("awaiting_stop".equals(status.get("state"))&&Boolean.TRUE.equals(status.get("playbackFinished")),"input route stays reserved after audio drains");
   System.out.println("AUDIO JOB CHECK COMPLETE "+ParityDeviceCheck.checks);exit=0;
  }catch(Throwable e){e.printStackTrace(System.out);}finally{
   if(tone!=null)tone.release();if(tools!=null&&id!=null)try{tools.call("android.audio",Map.of("operation","stop","audioId",id,"requestId",UUID.randomUUID().toString()));Thread.sleep(100);}catch(Exception ignored){}
   try{Files.deleteIfExists(file);}catch(Exception ignored){}
  }System.exit(exit);
 }
}
