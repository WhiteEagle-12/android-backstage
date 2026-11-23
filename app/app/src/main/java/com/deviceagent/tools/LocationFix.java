package com.deviceagent.tools;
import java.util.Map;
/** Monotonic age avoids wall-clock changes making an old fix appear fresh. */
public final class LocationFix {
 private LocationFix() {}
 public static long ageMs(long nowNanos,long fixNanos) { return fixNanos<=0||fixNanos>nowNanos?Long.MAX_VALUE:(nowNanos-fixNanos)/1_000_000; }
 public static boolean acceptable(long age,double accuracy,long maxAge,double desired) { return age>=0&&age<=maxAge&&Double.isFinite(accuracy)&&accuracy>=0&&accuracy<=desired; }
 public static Map<String,Object> limits(Map<String,Object> args) {
  int age=GesturePlan.integer(args,"maxAgeMs",10000,0,3600000),timeout=GesturePlan.integer(args,"timeoutMs",20000,0,20000);
  double accuracy=GesturePlan.number(args,"accuracyMeters",100);
  if(!Double.isFinite(accuracy)||accuracy<1||accuracy>100000)throw new IllegalArgumentException("accuracyMeters must be between 1 and 100000");
  return Json.mapOf("maxAgeMs",age,"accuracyMeters",accuracy,"timeoutMs",timeout);
 }
}
