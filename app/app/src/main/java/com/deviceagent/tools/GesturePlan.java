package com.deviceagent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates the entire path before any pointer is put down. */
public final class GesturePlan {
    public final List<List<float[]>> paths;
    public final int durationMs, holdMs;
    private GesturePlan(List<List<float[]>> paths,int duration,int hold) { this.paths=paths; durationMs=duration; holdMs=hold; }
    public static int integer(Map<String,Object> args,String key,int fallback,int min,int max) {
        double v = number(args,key,fallback);
        if (v != Math.rint(v) || v < min || v > max) throw new IllegalArgumentException(key+" must be an integer from "+min+" to "+max);
        return (int)v;
    }
    public static double number(Map<String,Object> args,String key,double fallback) {
        Object value = args.get(key);
        if (!args.containsKey(key)) return fallback;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException(key+" must be a finite number");
        return ((Number)value).doubleValue();
    }
    public static GesturePlan create(String op,Map<String,Object> args,int width,int height) {
        int duration=integer(args,"durationMs",op.equals("long_press")?800:500,1,5000);
        int hold=integer(args,"holdMs",op.equals("drag")?600:0,0,3000);
        List<List<float[]>> paths=new ArrayList<>();
        switch(op) {
            case "long_press" -> {float[] p=point(number(args,"x",-1),number(args,"y",-1),width,height); paths.add(List.of(p,p));}
            case "drag" -> paths.add(List.of(point(number(args,"x1",-1),number(args,"y1",-1),width,height),point(number(args,"x2",-1),number(args,"y2",-1),width,height)));
            case "pinch" -> {
                double cx=number(args,"centerX",-1),cy=number(args,"centerY",-1);
                double start=number(args,"startSpan",-1),end=number(args,"endSpan",-1),angle=number(args,"angleDegrees",0);
                if(start<=0||end<=0||start>32767||end>32767||Math.abs(angle)>360) throw new IllegalArgumentException("Invalid pinch span or angle");
                double dx=Math.cos(Math.toRadians(angle))/2,dy=Math.sin(Math.toRadians(angle))/2;
                for(int sign:new int[]{-1,1}) paths.add(List.of(point(cx+sign*start*dx,cy+sign*start*dy,width,height),point(cx+sign*end*dx,cy+sign*end*dy,width,height)));
            }
            case "multi_touch" -> {
                if(!(args.get("paths") instanceof List<?> raw)||raw.isEmpty()||raw.size()>5)throw new IllegalArgumentException("paths requires 1 to 5 finger paths");
                for(Object item:raw) {
                    if(!(item instanceof List<?> points)||points.size()<2||points.size()>100)throw new IllegalArgumentException("Each finger requires 2 to 100 points");
                    List<float[]> path=new ArrayList<>();
                    for(Object itemPoint:points) {
                        if(!(itemPoint instanceof Map<?,?> m))throw new IllegalArgumentException("Each point requires x and y");
                        @SuppressWarnings("unchecked") Map<String,Object> p=(Map<String,Object>)m;
                        path.add(point(number(p,"x",-1),number(p,"y",-1),width,height));
                    }
                    paths.add(path);
                }
            }
            default -> throw new IllegalArgumentException("Unknown gesture: "+op);
        }
        return new GesturePlan(paths,duration,hold);
    }
    private static float[] point(double x,double y,int width,int height) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=width||y>=height) throw new IllegalArgumentException("Gesture coordinates must stay within the virtual display");
        return new float[]{(float)x,(float)y};
    }
    public float[] at(int finger,double fraction) {
        List<float[]> p=paths.get(finger);
        double position=Math.max(0,Math.min(1,fraction))*(p.size()-1);
        int i=Math.min((int)position,p.size()-2); double t=position-i;
        return new float[]{(float)(p.get(i)[0]+(p.get(i+1)[0]-p.get(i)[0])*t),(float)(p.get(i)[1]+(p.get(i+1)[1]-p.get(i)[1])*t)};
    }
}
