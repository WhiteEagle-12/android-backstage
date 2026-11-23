package com.deviceagent.tools;

import java.util.*;

/** Preserve structural text groups and the node that owns each control's state. */
final class ObservationLayout {
    record Control(Map<String,Object> node, Map<String,Object> owner, Map<String,Object> state, String label, String role) {}
    private final List<Map<String,Object>> nodes;
    private final Map<Object,Map<String,Object>> index=new HashMap<>();
    private final Map<Object,List<Map<String,Object>>> children=new HashMap<>();
    private final Map<Object,Control> controls=new LinkedHashMap<>();
    private final Set<Object> covered=new HashSet<>();
    private final long windowHeight;
    private boolean textTruncated;

    ObservationLayout(Map<String,Object> tree) {
        nodes=Observations.appNodes(tree);
        for(var n:nodes){index.put(n.get("index"),n);children.computeIfAbsent(n.get("parent"),k->new ArrayList<>()).add(n);}
        windowHeight=Observations.windows(tree).stream().filter(w->Boolean.TRUE.equals(w.get("focused"))).map(w->ObservationCondition.bounds(w.get("bounds"))).filter(Objects::nonNull).mapToLong(b->b[3]-b[1]).max().orElse(0);
        for(var n:nodes)if(Observations.actionable(n)){
            var owner=n;
            if(stateful(n)&&Observations.label(n).isBlank()){
                var parent=index.get(n.get("parent"));
                for(int d=0;parent!=null&&d<12;d++,parent=index.get(parent.get("parent"))){
                    if(Boolean.TRUE.equals(parent.get("scrollable")))break;
                    if(Observations.actionable(parent)){
                        if(singleState(parent)==n)owner=parent;
                        break;
                    }
                }
            }
            Map<String,Object> state=stateful(n)?n:singleState(n);
            // A row can expose a non-clickable radio state, with input owned by the row.
            if(state!=null&&state!=n&&Observations.actionable(state))state=null;
            String label=label(owner);
            if(!Observations.str(n.get("accessibilityLabel")).isBlank())label=Observations.str(n.get("accessibilityLabel"));
            controls.put(n.get("index"),new Control(n,owner,state,label,state!=null?Observations.role(state):Observations.role(n)));
        }
        // An unlabeled wrapper with one switch is represented by that real switch.
        Set<Object> remove=new HashSet<>();
        for(var c:controls.values())if(c.owner!=c.node)remove.add(c.owner.get("index"));
        // Prefer the actual labeled descendant to an ancestor borrowing its label.
        // In particular a long-press toolbar must not replace its Home button.
        for(var c:controls.values())if(Observations.label(c.node).isBlank()&&!Boolean.TRUE.equals(c.node.get("scrollable")))
            for(var other:controls.values())if(c!=other&&ancestor(c.node,other.node)&&c.label.equals(other.label)&&c.role.equals(other.role))remove.add(c.node.get("index"));
        remove.forEach(controls::remove);
    }

    Collection<Control> controls(){return controls.values();}
    boolean textTruncated(){return textTruncated;}
    Control control(Map<String,Object> n){return controls.get(n.get("index"));}
    String label(Map<String,Object> n){
        String own=Observations.label(n);
        if(!own.isBlank())return own;
        if(Boolean.TRUE.equals(n.get("scrollable")))return "Scroll area";
        for(var child:descendants(n))if(!Observations.label(child).isBlank())return Observations.label(child);
        return "";
    }
    private boolean stateful(Map<String,Object> n){return n.containsKey("checked")||Set.of("switch","checkbox","radio").contains(Observations.role(n));}
    private Map<String,Object> singleState(Map<String,Object> n){
        if(Boolean.TRUE.equals(n.get("scrollable")))return null;
        Map<String,Object> found=null;
        for(var child:descendants(n))if(stateful(child)){if(found!=null)return null;found=child;}
        if(found!=null)for(var child:descendants(n))if(Observations.actionable(child)&&child!=found&&!ancestor(child,found)&&!ancestor(found,child))return null;
        return found;
    }
    private List<Map<String,Object>> descendants(Map<String,Object> n){
        List<Map<String,Object>> out=new ArrayList<>();collect(n,out,0);return out;
    }
    private void collect(Map<String,Object> n,List<Map<String,Object>> out,int depth){
        if(depth>=60)return;
        for(var child:children.getOrDefault(n.get("index"),List.of())){out.add(child);collect(child,out,depth+1);}
    }
    private boolean ancestor(Map<String,Object> parent,Map<String,Object> child){
        var n=index.get(child.get("parent"));
        for(int d=0;n!=null&&d<60;d++,n=index.get(n.get("parent")))if(n==parent)return true;
        return false;
    }

    List<Object> details(Control c){
        Set<Object> skip=new HashSet<>();
        for(var other:controls.values())if(other!=c&&ancestor(c.owner,other.node))skip.add(other.owner.get("index"));
        List<Object> result=new ArrayList<>(text(c.owner,c.label,skip,0));
        result.removeIf(c.label::equals);
        covered.add(c.node.get("index"));
        if(!Boolean.TRUE.equals(c.node.get("scrollable")))covered.add(c.owner.get("index"));
        // An explicit Android label may be outside the control's subtree.
        if(c.node.get("labeledBy")!=null)for(var n:nodes)if(Objects.equals(n.get("nodeId"),c.node.get("labeledBy"))&&Observations.windowKey(n).equals(Observations.windowKey(c.node)))covered.add(n.get("index"));
        return result;
    }
    List<Object> content(){
        List<Object> result=new ArrayList<>();
        for(var n:nodes)if(!index.containsKey(n.get("parent")))result.addAll(text(n,"",covered,0));
        return result;
    }
    private List<Object> text(Map<String,Object> n,String exclude,Set<Object> skip,int depth){
        if(depth>=60)return List.of();
        if(skip.contains(n.get("index"))&&!Boolean.TRUE.equals(n.get("scrollable")))return List.of();
        String own=Observations.label(n);
        List<Object> result=new ArrayList<>();
        if(!own.isBlank()&&!own.equals(exclude)&&!Observations.actionable(n))result.add(limit(own));
        List<Map<String,Object>> siblings=children.getOrDefault(n.get("index"),List.of());
        Map<String,Object> previous=null;
        for(var child:siblings){
            List<Object> values=text(child,exclude,skip,depth+1);
            // Only merge adjacent leaf text from this parent, in the supplied reading order.
            if(values.size()==1&&values.get(0) instanceof String s&&!result.isEmpty()&&result.get(result.size()-1) instanceof String last&&previous!=null&&inline(previous,child)){
                result.set(result.size()-1,limit(last+(s.matches("^[%‰°℃℉,.;:!?].*")?"":" ")+s));
            }else for(var value:values)if(!value.equals(own)||result.isEmpty())result.add(value);
            previous=values.size()==1&&values.get(0) instanceof String&&children.getOrDefault(child.get("index"),List.of()).isEmpty()?child:null;
        }
        if(result.size()>1&&groupable(n,result))return List.of(Json.mapOf("items",result));
        return result;
    }
    private boolean inline(Map<String,Object> a,Map<String,Object> b){
        if(Observations.actionable(a)||Observations.actionable(b)||!children.getOrDefault(b.get("index"),List.of()).isEmpty())return false;
        int[] x=ObservationCondition.bounds(a.get("bounds")),y=ObservationCondition.bounds(b.get("bounds"));
        if(x==null||y==null)return false;
        int height=Math.min(x[3]-x[1],y[3]-y[1]),overlap=Math.min(x[3],y[3])-Math.max(x[1],y[1]);
        return height>0&&overlap*2>=height&&y[0]>=x[2]&&y[0]-x[2]<=height/2;
    }
    private boolean groupable(Map<String,Object> n,List<Object> values){
        int[] b=ObservationCondition.bounds(n.get("bounds"));
        return !Boolean.TRUE.equals(n.get("scrollable"))&&b!=null&&windowHeight>0&&(long)(b[3]-b[1])*2<windowHeight&&values.size()<=8&&Json.stringify(values).length()<=1200;
    }
    private String limit(String s){if(s.length()>400){textTruncated=true;return s.substring(0,400);}return s;}
}
