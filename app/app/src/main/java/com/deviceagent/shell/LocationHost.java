package com.deviceagent.shell;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import com.deviceagent.tools.Json;
import com.deviceagent.tools.LocationFix;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded request. No settings changes, permission grants, or lingering location listener. */
@android.annotation.SuppressLint("MissingPermission")
public final class LocationHost {
 private LocationHost() {}
 private record ProviderFix(String provider,Location fix,boolean current) {}
 public static Map<String,Object> read(Context context,Map<String,Object> args) {
  Map<String,Object> limits=LocationFix.limits(args);
  long maxAge=((Number)limits.get("maxAgeMs")).longValue(),timeout=((Number)limits.get("timeoutMs")).longValue();
  double accuracy=((Number)limits.get("accuracyMeters")).doubleValue();
  long start=SystemClock.elapsedRealtime(),requestNanos=SystemClock.elapsedRealtimeNanos();
  LocationManager manager=context.getSystemService(LocationManager.class);
  HandlerThread thread=null; LocationListener listener=null;
  List<CancellationSignal> cancellations=new ArrayList<>();
  List<String> warnings=new ArrayList<>(); Location best=null;
  try {
   if(manager==null||!manager.isLocationEnabled())return Json.mapOf("ok",false,"code","location_disabled","error","Turn on Location in phone settings, then try again.","fix",null);
   List<String> providers=new ArrayList<>(manager.getProviders(true));providers.remove(LocationManager.PASSIVE_PROVIDER);
   // Some devices omit fused from provider enumeration even though it accepts requests.
   if(Build.VERSION.SDK_INT>=31&&manager.hasProvider(LocationManager.FUSED_PROVIDER)
      &&manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)&&!providers.contains(LocationManager.FUSED_PROVIDER))providers.add(0,LocationManager.FUSED_PROVIDER);
   if(providers.isEmpty())return Json.mapOf("ok",false,"code","location_unavailable","error","This device has no enabled location provider.","fix",null);
   for(String provider:providers) {
    try {
     Location candidate=manager.getLastKnownLocation(provider);
     best=better(best,candidate,maxAge,accuracy);
    }catch(SecurityException e){warnings.add(provider+": location access denied");}
   }
   if(!fits(best,maxAge,accuracy)&&timeout>0) {
    LinkedBlockingQueue<ProviderFix> fixes=new LinkedBlockingQueue<>(64);
    listener=location->{if(location!=null)fixes.offer(new ProviderFix(location.getProvider(),new Location(location),false));};
    thread=new HandlerThread("backstage-location");thread.start();int registered=0;
    Handler handler=new Handler(thread.getLooper());
    for(String provider:providers) {
     long remaining=timeout-(SystemClock.elapsedRealtime()-start);if(remaining<=0)break;
     try {
      CancellationSignal cancellation=new CancellationSignal();cancellations.add(cancellation);
      java.util.function.Consumer<Location> receive=location->fixes.offer(new ProviderFix(provider,location==null?null:new Location(location),true));
      if(Build.VERSION.SDK_INT>=31)manager.getCurrentLocation(provider,request(remaining),cancellation,command->handler.post(command),receive);
      else manager.getCurrentLocation(provider,cancellation,command->handler.post(command),receive);
      registered++;
     }
     catch(SecurityException|IllegalArgumentException e){warnings.add(provider+": "+e.getClass().getSimpleName());}
    }
    if(registered>0) {
     while(!fits(best,maxAge,accuracy)) {
      long left=timeout-(SystemClock.elapsedRealtime()-start);if(left<=0)break;
      ProviderFix candidate=fixes.poll(left,TimeUnit.MILLISECONDS);if(candidate==null)break;
      best=better(best,candidate.fix(),maxAge,accuracy);
      // A one-shot result may still be cached or imprecise. Refine only that
      // provider, for the time left in this same request; never restart the deadline.
      if(candidate.current()&&!fits(best,maxAge,accuracy)){
       long remaining=timeout-(SystemClock.elapsedRealtime()-start);
       if(remaining>0)try{
        if(Build.VERSION.SDK_INT>=31)manager.requestLocationUpdates(candidate.provider(),request(remaining),command->handler.post(command),listener);
        else manager.requestLocationUpdates(candidate.provider(),1000,0,listener,thread.getLooper());
       }catch(SecurityException|IllegalArgumentException e){warnings.add(candidate.provider()+": "+e.getClass().getSimpleName());}
      }
     }
    }
   }
   boolean accepted=fits(best,maxAge,accuracy);
   Map<String,Object> out=Json.mapOf("ok",accepted,"outcome",accepted?"location_observed":"partial","limits",limits,"fix",best==null?null:serialize(best,requestNanos),"observedAt",java.time.Instant.now().toString(),"elapsedMs",SystemClock.elapsedRealtime()-start);
   if(!accepted) {out.put("code",best==null?"location_unavailable":"location_limits_not_met");out.put("warning",best==null?"No location fix arrived before the timeout. Check Location and location access in phone settings, then retry.":"This fix misses the requested age or accuracy. Move near a window or outdoors and retry; do not treat this fix as current.");}
   if(!warnings.isEmpty())out.put("warnings",warnings);
   return out;
  } catch(InterruptedException e) {Thread.currentThread().interrupt();return Json.mapOf("ok",false,"code","interrupted","error","Location request interrupted.");}
  catch(SecurityException e) {return Json.mapOf("ok",false,"code","location_permission_denied","error","Android denied location access. Allow location access before trying again.");}
  finally {
   for(CancellationSignal cancellation:cancellations)try{cancellation.cancel();}catch(RuntimeException ignored){}
   if(listener!=null)try{manager.removeUpdates(listener);}catch(RuntimeException ignored){}
   if(thread!=null)thread.quitSafely();
  }
 }
 @android.annotation.TargetApi(31)
 private static LocationRequest request(long duration){return new LocationRequest.Builder(1000)
  .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(0).setDurationMillis(duration).build();}
 private static boolean fits(Location fix,long maxAge,double desired){return fix!=null&&fix.hasAccuracy()&&LocationFix.acceptable(LocationFix.ageMs(SystemClock.elapsedRealtimeNanos(),fix.getElapsedRealtimeNanos()),fix.getAccuracy(),maxAge,desired);}
 private static Location better(Location a,Location b,long maxAge,double desired) {
  if(b==null)return a;if(a==null)return new Location(b);
  if(fits(b,maxAge,desired)&&!fits(a,maxAge,desired))return new Location(b);
  if(fits(a,maxAge,desired)&&!fits(b,maxAge,desired))return a;
  return b.getElapsedRealtimeNanos()>a.getElapsedRealtimeNanos()?new Location(b):a;
 }
 private static Map<String,Object> serialize(Location fix,long requestNanos) {
  long age=LocationFix.ageMs(SystemClock.elapsedRealtimeNanos(),fix.getElapsedRealtimeNanos());
  return Json.mapOf("latitude",fix.getLatitude(),"longitude",fix.getLongitude(),"ageMs",age==Long.MAX_VALUE?null:age,"accuracyMeters",fix.hasAccuracy()?fix.getAccuracy():null,"fixedAt",java.time.Instant.ofEpochMilli(fix.getTime()).toString(),"source",fix.getElapsedRealtimeNanos()<requestNanos?"cached":"fresh","provider",fix.getProvider(),"mock",fix.isFromMockProvider(),"movementBearingDegrees",fix.hasBearing()?fix.getBearing():null,"bearingAccuracyDegrees",fix.hasBearingAccuracy()?fix.getBearingAccuracyDegrees():null,"speedMetersPerSecond",fix.hasSpeed()?fix.getSpeed():null);
 }
}
