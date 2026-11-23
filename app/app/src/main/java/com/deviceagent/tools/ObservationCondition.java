package com.deviceagent.tools;

import java.util.*;

/** A condition is a leaf, or one all/any group of leaves. Stability is scoped explicitly. */
public final class ObservationCondition {
    private ObservationCondition() {}
    private static Map<String,Object> map(Object o){return PrimitiveArguments.map(o);}
    public static void validate(Map<String,Object> c){
        if(c.containsKey("conditions")){
            if(!Set.of("conditions","match").containsAll(c.keySet())||!Set.of("all","any").contains(c.getOrDefault("match","all"))||!(c.get("conditions") instanceof List<?> children)||children.isEmpty()||children.size()>8)throw new IllegalArgumentException("Use one to eight conditions and match all or any");
            for(Object child:children)leaf(map(child));return;
        }
        for(String group:List.of("all","any"))if(c.containsKey(group)){
            if(c.size()!=1||!(c.get(group) instanceof List<?> children)||children.isEmpty()||children.size()>8)throw new IllegalArgumentException("Use one to eight leaf conditions");
            for(Object child:children)leaf(map(child));return;
        }
        leaf(c);
    }
    private static void leaf(Map<String,Object> c){
        String kind=Observations.str(c.get("kind"));
        Set<String> fields=switch(kind){
            case "exists","absent" -> Set.of("kind","selector");
            case "appeared" -> Set.of("kind","selector","observation");
            case "field" -> Set.of("kind","selector","value");
            case "state" -> Set.of("kind","selector","checked","selected","stateDescription");
            case "window" -> Set.of("kind","package","title","activity");
            case "stable" -> Set.of("kind","selectors","region","durationMs");
            default -> throw new IllegalArgumentException("Unknown condition kind");
        };
        if(!fields.containsAll(c.keySet()))throw new IllegalArgumentException("Unknown condition field");
        if(Set.of("exists","absent","appeared","field","state").contains(kind))PrimitiveArguments.selector(c.get("selector"));
        if(kind.equals("state")){
            if(c.size()<3)throw new IllegalArgumentException("Choose checked, selected or stateDescription");
            for(String key:List.of("checked","selected"))if(c.containsKey(key)&&!(c.get(key) instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");
            if(c.containsKey("stateDescription"))PrimitiveArguments.text(c,"stateDescription",false,2048);
        }
        if(kind.equals("appeared"))PrimitiveArguments.text(c,"observation",false,100);
        if(kind.equals("field"))PrimitiveArguments.text(c,"value",true,10000);
        if(kind.equals("window")){if(c.size()<2)throw new IllegalArgumentException("Choose package, title, or activity");for(String k:c.keySet())if(!k.equals("kind"))PrimitiveArguments.text(c,k,false,2048);}
        if(kind.equals("stable")){
            GesturePlan.integer(c,"durationMs",300,100,5000);
            if(c.containsKey("selectors")==c.containsKey("region"))throw new IllegalArgumentException("Stability needs selectors or a region");
            if(c.containsKey("selectors")){if(!(c.get("selectors") instanceof List<?> ss)||ss.isEmpty()||ss.size()>8)throw new IllegalArgumentException("Use one to eight selectors");for(Object s:ss)PrimitiveArguments.selector(s);}
            else{var r=map(c.get("region"));if(!r.keySet().equals(Set.of("left","top","right","bottom")))throw new IllegalArgumentException("Region needs left, top, right, bottom");for(String k:r.keySet())GesturePlan.integer(r,k,-1,0,100000);if(((Number)r.get("right")).intValue()<=((Number)r.get("left")).intValue()||((Number)r.get("bottom")).intValue()<=((Number)r.get("top")).intValue())throw new IllegalArgumentException("Region must have positive size");}
        }
    }
    public static void references(Map<String,Object> c,Observations observations,int display){
        for(String group:List.of("conditions","all","any"))if(c.get(group) instanceof List<?> children){for(Object child:children)references(map(child),observations,display);return;}
        if(c.get("observation") instanceof String id)observations.get(id,display);
    }
    public static boolean selects(Map<String,Object> selector,Map<String,Object> control){return selector.entrySet().stream().allMatch(e->Objects.equals(e.getValue(),control.get(e.getKey())));}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> controls(Map<String,Object> tree){return (List<Map<String,Object>>)new Observations(()->0).observe(tree,300,null).get("controls");}
    public static boolean matches(Map<String,Object> c,Map<String,Object> tree,Observations observations,int display){return matches(c,tree,observations,display,new HashMap<>(),System.nanoTime()/1_000_000);}
    public static boolean matches(Map<String,Object> c,Map<String,Object> tree,Observations observations,int display,Map<String,Object> state,long now){
        var local=new Observations(()->0);
        Map<String,Object> view=local.observe(tree,300,null);
        return matchesWithView(c,tree,local,view,observations,display,state,now);
    }
    private static boolean matchesWithView(Map<String,Object> c,Map<String,Object> tree,Observations local,Map<String,Object> view,Observations observations,int display,Map<String,Object> state,long now){
        if(c.get("conditions") instanceof List<?> children)return matchesWithView(Map.of(Observations.str(c.getOrDefault("match","all")),children),tree,local,view,observations,display,state,now);
        if(c.get("all") instanceof List<?> children){boolean result=true;for(Object x:children)result=matchesWithView(map(x),tree,local,view,observations,display,state,now)&result;return result;}
        if(c.get("any") instanceof List<?> children){boolean result=false;for(Object x:children)result=matchesWithView(map(x),tree,local,view,observations,display,state,now)|result;return result;}
        String kind=(String)c.get("kind");
        var controls=((List<?>)view.get("controls")).stream().map(PrimitiveArguments::map).collect(java.util.stream.Collectors.toList());
        if(kind.equals("window")){
            if(c.containsKey("package")&&Observations.appNodes(tree).stream().noneMatch(n->c.get("package").equals(n.get("package"))))return false;
            if(c.containsKey("activity")&&!c.get("activity").equals(tree.get("focusedActivity")))return false;
            return !c.containsKey("title")||Observations.windows(tree).stream().anyMatch(w->Observations.selectedWindow(tree,w)&&c.get("title").equals(w.get("title")));
        }
        if(kind.equals("stable")){
            if(Boolean.TRUE.equals(tree.get("truncated"))){state.remove(Json.stringify(c));return false;}
            List<Object> selected=new ArrayList<>();
            if(c.get("selectors") instanceof List<?> selectors){
                var entry=local.get((String)view.get("observation"),display);
                for(Object s:selectors){var found=controls.stream().filter(n->selects(map(s),n)).collect(java.util.stream.Collectors.toList());if(found.size()!=1){state.remove(Json.stringify(c));return false;}var value=new LinkedHashMap<>(found.get(0));value.remove("id");
                var n=entry.targets().get(found.get(0).get("id"));if(n!=null)value.put("identity",Observations.targetSignature(tree,n));
                selected.add(value);}}
            else{var r=map(c.get("region"));for(var n:Observations.appNodes(tree)){int[] b=bounds(n.get("bounds"));if(b!=null&&b[0]<((Number)r.get("right")).intValue()&&b[2]>((Number)r.get("left")).intValue()&&b[1]<((Number)r.get("bottom")).intValue()&&b[3]>((Number)r.get("top")).intValue())selected.add(n);}}
            String key=Json.stringify(c),signature=Json.stringify(Json.mapOf("selected",selected,"window",Observations.windowSignature(tree)));var old=state.get(key) instanceof Map?map(state.get(key)):Map.<String,Object>of();
            if(!signature.equals(old.get("signature"))){state.put(key,Json.mapOf("signature",signature,"since",now));return false;}
            return now-((Number)old.get("since")).longValue()>=GesturePlan.integer(c,"durationMs",300,100,5000);
        }
        var selector=map(c.get("selector"));var found=controls.stream().filter(n->selects(selector,n)).collect(java.util.stream.Collectors.toList());
        if(kind.equals("state"))return found.size()==1&&c.entrySet().stream().filter(e->!Set.of("kind","selector").contains(e.getKey())).allMatch(e->Objects.equals(e.getValue(),found.get(0).get(e.getKey())));
        if(kind.equals("absent"))return found.isEmpty()&&!Boolean.TRUE.equals(view.get("truncated"))&&!(selector.keySet().equals(Set.of("label"))&&visibleText(tree,view,selector.get("label")));
        if(kind.equals("field"))return found.size()==1&&"field".equals(found.get(0).get("role"))&&!Boolean.TRUE.equals(found.get(0).get("valueTruncated"))&&found.get(0).containsKey("value")&&FieldText.matches((String)c.get("value"),(String)found.get(0).get("value"),"phone".equals(found.get(0).get("inputType")),false);
        // Text-only labels can also satisfy existence checks.
        boolean exists=!found.isEmpty()||selector.keySet().equals(Set.of("label"))&&visibleText(tree,view,selector.get("label"));
        if(kind.equals("appeared")){var prior=observations.get((String)c.get("observation"),display).tree();return exists&&matches(Map.of("kind","absent","selector",selector),prior,observations,display,state,now);}
        return exists;
    }
    /** Compact per-condition failure explanations so agents know why verify/wait timed out. */
    public static List<String> describeFailures(Map<String,Object> c,Map<String,Object> tree,Observations observations,int display){
        List<String> out=new ArrayList<>();
        if(tree==null)return out;
        var local=new Observations(()->0);
        Map<String,Object> view=local.observe(tree,300,null);
        collectFailures(c,tree,local,view,observations,display,out);
        return out;
    }
    private static void collectFailures(Map<String,Object> c,Map<String,Object> tree,Observations local,Map<String,Object> view,Observations observations,int display,List<String> out){
        if(c.get("conditions") instanceof List<?> children){for(Object x:children)collectFailures(map(x),tree,local,view,observations,display,out);return;}
        for(String g:List.of("all","any"))if(c.get(g) instanceof List<?> children){for(Object x:children)collectFailures(map(x),tree,local,view,observations,display,out);return;}
        if(matchesWithView(c,tree,local,view,observations,display,new HashMap<>(),0))return;
        String kind=Observations.str(c.get("kind"));
        var controls=((List<?>)view.get("controls")).stream().map(PrimitiveArguments::map).collect(java.util.stream.Collectors.toList());
        if(kind.equals("window")){
            String actualPkg=Observations.appNodes(tree).stream().map(n->Observations.str(n.get("package"))).filter(s->!s.isEmpty()).findFirst().orElse("");
            String actualTitle=Observations.windows(tree).stream().filter(w->Observations.selectedWindow(tree,w)).map(w->Observations.str(w.get("title"))).filter(s->!s.isEmpty()).findFirst().orElse("");
            out.add("window mismatch: actual package="+(actualPkg.isEmpty()?"none":actualPkg)+", activity="+Observations.str(tree.get("focusedActivity"))+", title="+actualTitle);
            return;
        }
        if(kind.equals("stable")){out.add("UI did not stay stable for "+GesturePlan.integer(c,"durationMs",300,100,5000)+"ms");return;}
        var selector=map(c.get("selector"));
        var found=controls.stream().filter(n->selects(selector,n)).collect(java.util.stream.Collectors.toList());
        String targetDesc=selector.containsKey("label")?"\""+selector.get("label")+"\"":Observations.str(selector.get("resourceId"));
        if(kind.equals("absent")){out.add(targetDesc+" is still present"+(Boolean.TRUE.equals(view.get("truncated"))?" (observation truncated)":""));return;}
        if(kind.equals("field")){
            if(found.size()!=1)out.add("field "+targetDesc+" matched "+found.size()+" controls");
            else out.add("field "+targetDesc+" value was \""+Observations.str(found.get(0).get("value"))+"\", expected \""+Observations.str(c.get("value"))+"\"");
            return;
        }
        if(kind.equals("state")){
            if(found.size()!=1)out.add("state target "+targetDesc+" matched "+found.size()+" controls");
            else out.add("state target "+targetDesc+" had checked="+found.get(0).get("checked")+", selected="+found.get(0).get("selected")+", stateDescription="+found.get(0).get("stateDescription"));
            return;
        }
        if(kind.equals("appeared")){out.add(targetDesc+" did not newly appear since "+Observations.str(c.get("observation")));return;}
        out.add("not found: "+targetDesc+" on screen \""+Observations.str(view.get("screen"))+"\" ("+controls.size()+" controls visible)");
    }
    private static boolean visibleText(Map<String,Object> tree,Map<String,Object> view,Object label){
        return Observations.appNodes(tree).stream().anyMatch(n->label.equals(Observations.label(n)))||!Boolean.TRUE.equals(view.get("truncated"))&&(containsText(view.get("content"),label)||
            ((List<?>)view.get("controls")).stream().map(PrimitiveArguments::map).anyMatch(c->containsText(c.get("details"),label)));
    }
    private static boolean containsText(Object value,Object label){
        if(value instanceof String)return value.equals(label);
        if(value instanceof List<?> list)return list.stream().anyMatch(v->containsText(v,label));
        return value instanceof Map<?,?> group&&containsText(group.get("items"),label);
    }
    public static int[] bounds(Object value){var m=java.util.regex.Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(Observations.str(value));if(!m.matches())return null;return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4))};}
}
