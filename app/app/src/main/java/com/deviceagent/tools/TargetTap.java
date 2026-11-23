package com.deviceagent.tools;

import java.util.*;

/** Select input before dispatch. Never turn an accepted click into a second input. */
public final class TargetTap {
    private TargetTap() {}

    public static boolean webLink(Map<String,Object> tree, Map<String,Object> target) {
        if (!Observations.role(target).equals("link")) return false;
        Map<Object,Map<String,Object>> index = new HashMap<>();
        for (var n : Observations.appNodes(tree)) index.put(n.get("index"), n);
        var n = target;
        for (int depth=0; n!=null && depth<60; depth++, n=index.get(n.get("parent"))) {
            if (Observations.str(n.get("class")).endsWith("WebView")) return true;
        }
        return false;
    }

    public static int[] point(Map<String,Object> tree, Map<String,Object> target) {
        int[] visible = ObservationCondition.bounds(target.get("bounds"));
        if (visible==null) throw unavailable();
        Map<Object,Map<String,Object>> index = new HashMap<>();
        for (var n : Observations.appNodes(tree)) index.put(n.get("index"), n);
        var n = index.get(target.get("parent"));
        for (int depth=0; n!=null && depth<60; depth++, n=index.get(n.get("parent"))) {
            int[] bounds = ObservationCondition.bounds(n.get("bounds"));
            if (bounds!=null) intersect(visible,bounds);
        }
        Map<String,Object> window = null;
        for (var w : Observations.windows(tree)) if (Objects.equals(w.get("id"),target.get("window")) && Observations.selectedWindow(tree,w)) window=w;
        if (window==null) throw unavailable();
        int[] bounds = ObservationCondition.bounds(window.get("bounds"));
        if (bounds==null) throw unavailable();
        intersect(visible,bounds);
        visible[0]=Math.max(0,visible[0]); visible[1]=Math.max(0,visible[1]);
        if (visible[2]-visible[0]<2 || visible[3]-visible[1]<2) throw unavailable();
        int x=visible[0]+(visible[2]-visible[0])/2, y=visible[1]+(visible[3]-visible[1])/2;
        List<int[]> obstacles=new ArrayList<>();
        for(var other:Observations.appNodes(tree)){
            if(other==target||!Objects.equals(other.get("window"),target.get("window"))||!Observations.actionable(other)||related(index,other,target)||related(index,target,other))continue;
            int[] hit=ObservationCondition.bounds(other.get("bounds"));
            if(hit!=null)obstacles.add(hit);
        }
        int layer=((Number)window.getOrDefault("layer",0)).intValue();
        for (var w : Observations.windows(tree)) {
            if (Objects.equals(w.get("id"),window.get("id"))) continue;
            int[] other=ObservationCondition.bounds(w.get("bounds"));
            if (((Number)w.getOrDefault("layer",0)).intValue()>layer && other!=null)obstacles.add(other);
        }
        if(obstacles.stream().noneMatch(b->x>=b[0]&&x<b[2]&&y>=b[1]&&y<b[3]))return new int[]{x,y};
        // Search the remaining visible area when an overlay covers the center.
        // Horizontal bands avoid a grid whose cost grows with display resolution.
        var edges=new TreeSet<Integer>();edges.add(visible[1]);edges.add(visible[3]);
        for(var b:obstacles){edges.add(Math.max(visible[1],Math.min(visible[3],b[1])));edges.add(Math.max(visible[1],Math.min(visible[3],b[3])));}
        var bands=new ArrayList<>(edges);int[] best=null;long bestArea=0;
        for(int i=1;i<bands.size();i++){
            int top=bands.get(i-1),bottom=bands.get(i);if(bottom-top<2)continue;
            var spans=new ArrayList<int[]>();
            for(var b:obstacles)if(b[1]<bottom&&b[3]>top&&b[0]<visible[2]&&b[2]>visible[0])spans.add(new int[]{Math.max(visible[0],b[0]),Math.min(visible[2],b[2])});
            spans.sort(Comparator.comparingInt(b->b[0]));spans.add(new int[]{visible[2],visible[2]});int left=visible[0];
            for(var span:spans){long area=(long)(span[0]-left)*(bottom-top);if(span[0]-left>=2&&area>bestArea){bestArea=area;best=new int[]{left+(span[0]-left)/2,top+(bottom-top)/2};}left=Math.max(left,span[1]);}
        }
        if(best==null)throw unavailable();return best;
    }

    private static void intersect(int[] a,int[] b) {
        a[0]=Math.max(a[0],b[0]); a[1]=Math.max(a[1],b[1]); a[2]=Math.min(a[2],b[2]); a[3]=Math.min(a[3],b[3]);
    }
    private static boolean related(Map<Object,Map<String,Object>> index,Map<String,Object> parent,Map<String,Object> child){
        var n=index.get(child.get("parent"));
        for(int depth=0;n!=null&&depth<60;depth++,n=index.get(n.get("parent")))if(n==parent)return true;
        return false;
    }
    private static InputNotSentException unavailable() {
        return new InputNotSentException("target_obscured","The control has no clear touch point. Observe it or scroll it into view.");
    }
}
