package com.deviceagent.shell;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import com.deviceagent.tools.PcmWave;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AudioJobsTest {
 private Context context() throws Exception {
  Context context=mock(Context.class);PackageManager pm=mock(PackageManager.class);ApplicationInfo app=mock(ApplicationInfo.class);app.uid=12345;
  when(context.getPackageManager()).thenReturn(pm);when(pm.getApplicationInfo("example.app",0)).thenReturn(app);when(pm.getPackagesForUid(12345)).thenReturn(new String[]{"example.app"});return context;
 }
 private Map<String,Object> args(Path path){return new LinkedHashMap<>(Map.of("operation","capture","package","example.app","path",path.toString(),"_owner","local","_configPath","unused","leaseSeconds",2));}
 @Test public void captureCanBePolledRenewedStoppedAndFinalized() throws Exception {
  Path dir=Files.createTempDirectory("audio-jobs");Path path=dir.resolve("capture.wav");AudioJobs jobs=new AudioJobs();Context context=context();
  try(var routes=mockConstruction(AudioRoute.class,(route,construction)->{
   route.record=mock(AudioRecord.class);
   when(route.record.read(any(byte[].class),eq(0),eq(8192),eq(AudioRecord.READ_NON_BLOCKING))).thenReturn(4);
  })){
   var started=jobs.handle(context,args(path));String id=(String)started.get("audioId");assertNotNull(id);
   var denied=jobs.handle(context,Map.of("operation","stop","audioId",id,"_owner","another"));assertEquals(false,denied.get("ok"));
   var renewed=jobs.handle(context,Map.of("operation","renew","audioId",id,"_owner","local","leaseSeconds",30));assertTrue((long)renewed.get("expiresAt")>(long)started.get("expiresAt"));
   assertEquals("audio_busy",jobs.handle(context,args(dir.resolve("other.wav"))).get("code"));
   jobs.handle(context,Map.of("operation","stop","audioId",id,"_owner","local"));
   Map<String,Object> status=null;
   for(int i=0;i<200;i++){status=jobs.handle(context,Map.of("operation","status","audioId",id,"_owner","local"));if(!status.get("state").equals("running"))break;Thread.sleep(5);}
   assertEquals("stopped",status.get("state"));
   try(var file=FileChannel.open(path)){assertEquals(status.get("bytes"),PcmWave.parse(file).bytes());}
   verify(routes.constructed().get(0)).close();
  }finally{Files.deleteIfExists(path);Files.delete(dir);}
 }
 @Test public void rejectsSharedUidAndDoesNotOverwriteOutput() throws Exception {
  Path path=Files.createTempFile("audio-existing", ".wav");Files.write(path,"keep me".getBytes(java.nio.charset.StandardCharsets.UTF_8));Context context=context();AudioJobs jobs=new AudioJobs();
  try{
   assertThrows(FileAlreadyExistsException.class,()->jobs.handle(context,args(path)));assertEquals("keep me",new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8));
   when(context.getPackageManager().getPackagesForUid(12345)).thenReturn(new String[]{"example.app","example.other"});
   assertEquals("shared_audio_uid",jobs.handle(context,args(path)).get("code"));
  }finally{Files.delete(path);}
 }
 @Test public void failedRoutePreparationRemovesOnlyNewOutput() throws Exception {
  Path dir=Files.createTempDirectory("audio-failure");Path path=dir.resolve("capture.wav");Context context=context();
  try(var routes=mockConstruction(AudioRoute.class,(route,construction)->{route.record=mock(AudioRecord.class);doThrow(new IllegalStateException("no recorder")).when(route.record).startRecording();})){
   assertThrows(IllegalStateException.class,()->new AudioJobs().handle(context,args(path)));assertFalse(Files.exists(path));verify(routes.constructed().get(0)).close();
  }finally{Files.delete(dir);}
 }
}
