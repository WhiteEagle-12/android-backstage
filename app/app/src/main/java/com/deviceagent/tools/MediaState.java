package com.deviceagent.tools;
public final class MediaState {
 private MediaState(){}
 public static String name(Integer state){if(state==null)return "unknown";return switch(state){
  case 0->"none";case 1->"stopped";case 2->"paused";case 3->"playing";case 4->"fast_forwarding";case 5->"rewinding";case 6->"buffering";case 7->"error";case 8->"connecting";case 9->"skipping_previous";case 10->"skipping_next";case 11->"skipping_queue_item";default->"unknown";
 };}
}
