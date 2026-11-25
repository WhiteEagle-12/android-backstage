package com.deviceagent.tools;

import java.util.*;
import java.util.function.LongSupplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Short public observations; raw node identities remain private and expire. */
public final class Observations {
    public static final long TTL_MS=60_000;
    private final LongSupplier clock;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Map<String,String> lastByScope=new HashMap<>();
    private long nextTarget=1;
    public record Entry(String id,int display,long at,Map<String,Object> tree,Map<String,Map<String,Object>> targets,Map<String,Object> view) {}
    public Observations(LongSupplier clock){this.clock=clock;}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> nodes(Map<String,Object> tree){return (List<Map<String,Object>>)tree.getOrDefault("nodes",List.of());}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> windows(Map<String,Object> tree){return (List<Map<String,Object>>)tree.getOrDefault("windows",List.of());}
    public static String str(Object x){return x==null?"":x.toString();}
    public static boolean actionable(Map<String,Object> n){return Boolean.TRUE.equals(n.get("clickable"))||Boolean.TRUE.equals(n.get("editable"))||Boolean.TRUE.equals(n.get("scrollable"))||Boolean.TRUE.equals(n.get("longClickable"));}
    public static String windowKey(Map<String,Object> n){return str(n.getOrDefault("windowKey",n.get("window")));}
    public static String label(Map<String,Object> n){
        for(String key:Boolean.TRUE.equals(n.get("editable"))?List.of("accessibilityLabel","fieldLabel","hint","contentDescription","text","tooltip"):List.of("accessibilityLabel","contentDescription","text","tooltip","hint")){
            if(key.equals("text")&&Boolean.TRUE.equals(n.get("password")))continue;
            String s=str(n.get(key)).codePoints().filter(c->Character.getType(c)!=Character.PRIVATE_USE).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString().trim();
            if(!s.isEmpty())return s;
        }return "";
    }
    public static String windowSignature(Map<String,Object> tree){
        List<Object> windows=new ArrayList<>();for(var w:windows(tree))if(Boolean.TRUE.equals(w.get("focused")))windows.add(Json.mapOf("key",w.getOrDefault("key",w.get("id")),"title",w.get("title"),"type",w.get("type"),"bounds",w.get("bounds")));
        return Json.stringify(Json.mapOf("activity",tree.get("focusedActivity"),"windows",windows));
    }
    public static String role(Map<String,Object> n){
        if(Boolean.TRUE.equals(n.get("editable")))return "field";
        String c=str(n.get("class")).toLowerCase(Locale.ROOT),r=str(n.get("roleDescription")).toLowerCase(Locale.ROOT);
        if(c.contains("switch"))return "switch";if(c.contains("checkbox"))return "checkbox";if(c.contains("radio"))return "radio";
        if(r.matches("(?:link|visited link|link visited)")||c.contains("link"))return "link";
        if(Boolean.TRUE.equals(n.get("clickable"))||Boolean.TRUE.equals(n.get("longClickable")))return "button";
        if(Boolean.TRUE.equals(n.get("scrollable")))return "scroll";
        return "text";
    }
    public static List<Map<String,Object>> appNodes(Map<String,Object> tree){
        Set<Object> focused=new HashSet<>();
        for(var w:windows(tree))if(selectedWindow(tree,w))focused.add(w.get("id"));
        List<Map<String,Object>> out=new ArrayList<>();
        for(var n:nodes(tree))if(!Boolean.FALSE.equals(n.get("visible"))&&(windows(tree).isEmpty()&&!tree.containsKey("windowId")||focused.contains(n.get("window"))))out.add(n);
        return out;
    }
    public static boolean selectedWindow(Map<String,Object> tree,Map<String,Object> window){return tree.containsKey("windowId")?Objects.equals(tree.get("windowId"),window.get("windowId")):Boolean.TRUE.equals(window.get("focused"));}
    public static String windowId(int display,int type,String key){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((display+":"+type+":"+key).getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder("window_");for(int i=0;i<12;i++)id.append(String.format(Locale.ROOT,"%02x",digest[i]&255));return id.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static Map<String,Object> signatureNode(Map<String,Object> n){
        Map<String,Object> out=new LinkedHashMap<>();
        for(String k:List.of("nodeId","windowKey","package","class","resourceId","text","contentDescription","hint","bounds","clickable","editable","enabled","checked","selected","scrollable","longClickable","visible","showingHintText","tooltip","fieldValue","fieldLabel","inputType","stateDescription","accessibilityLabel","labeledBy","roleDescription","actions"))if(n.containsKey(k))out.put(k,n.get(k));
        return out;
    }
    public static String fingerprint(Map<String,Object> tree){
        List<Object> ns=new ArrayList<>(),ws=new ArrayList<>();for(var n:appNodes(tree))ns.add(signatureNode(n));
        for(var w:windows(tree))if(Boolean.TRUE.equals(w.get("focused")))ws.add(Json.mapOf("key",w.getOrDefault("key",w.get("id")),"title",w.get("title"),"type",w.get("type"),"bounds",w.get("bounds")));
        try{return Json.b64(MessageDigest.getInstance("SHA-256").digest(Json.stringify(Json.mapOf("nodes",ns,"windows",ws)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public synchronized Entry get(String id,int display){
        Entry e=entries.get(id);
        if(e==null||clock.getAsLong()-e.at>TTL_MS)throw new InputNotSentException("observation_expired","Observe this display again before using its targets or conditions.");
        if(e.display!=display)throw new InputNotSentException("wrong_observation_display","This observation belongs to another display. Observe the requested display.");
        return e;
    }
    public synchronized void clear(int display){entries.values().removeIf(e->e.display==display);lastByScope.keySet().removeIf(k->k.startsWith(display+":"));}
    public synchronized Map<String,Object> target(String id,String target,int display,Map<String,Object> current){return target(id,target,display,current,"tap");}
    public synchronized Map<String,Object> target(String id,String target,int display,Map<String,Object> current,String operation){
        Entry e=get(id,display);Map<String,Object> n=e.targets.get(target);
        if(n==null)throw new InputNotSentException("target_not_found","This target is not in the observation. Observe the display again.");
        return refreshTarget(current,reference(e,n,display,operation));
    }
    public synchronized Map<String,Object> refreshTarget(String id,String target,int display,Map<String,Object> tree,String operation){
        Entry e=get(id,display);var n=e.targets.get(target);
        if(n==null)throw new InputNotSentException("target_not_found","Observe this display again before choosing a target.");
        return refreshTarget(tree,reference(e,n,display,operation));
    }
    private static Map<String,Object> reference(Entry e,Map<String,Object> n,int display,String operation){
        var ref=Json.mapOf("display",display,"nodeId",n.get("nodeId"),"windowKey",windowKey(n),"identity",Json.stringify(identity(e.tree,n)),"targetSignature",targetSignature(e.tree,n),"operation",operation);
        if(e.tree.containsKey("windowId"))ref.put("windowId",e.tree.get("windowId"));
        return ref;
    }
    /** Bind a reference to the control that now carries its identity. Geometry is never part of identity. */
    public static Map<String,Object> refreshTarget(Map<String,Object> tree,Map<String,Object> ref){
        var n=validateTarget(tree,ref);
        var fresh=new LinkedHashMap<>(ref);fresh.put("nodeId",n.get("nodeId"));fresh.put("windowKey",windowKey(n));fresh.put("targetSignature",targetSignature(tree,n));
        return fresh;
    }
    /**
     * Refresh a scroll container between scroll_until steps: scrolling legitimately updates
     * available scroll actions (e.g. adding ACTION_SCROLL_BACKWARD once off the top) and
     * visible sibling context, while preserving the same container identity and window.
     */
    public static Map<String,Object> refreshScrollContainer(Map<String,Object> tree,Map<String,Object> ref){
        var matches=appNodes(tree).stream().filter(n->Objects.equals(n.get("nodeId"),ref.get("nodeId"))&&windowKey(n).equals(ref.get("windowKey"))).collect(java.util.stream.Collectors.toList());
        if(matches.size()!=1)throw new InputNotSentException("stale_target","The scroll container disappeared. Observe the display again.");
        var n=matches.get(0);
        if(!Boolean.TRUE.equals(n.get("scrollable")))throw new InputNotSentException("target_unsupported","Choose a control that supports this action.");
        if(Boolean.FALSE.equals(n.get("enabled")))throw new InputNotSentException("target_disabled","This control is disabled. Wait for it to become available.");
        var oldControl=PrimitiveArguments.map(Json.object(str(ref.get("targetSignature"))).get("control"));
        if(!Objects.equals(oldControl.get("class"),n.get("class"))||!Objects.equals(oldControl.get("resourceId"),n.get("resourceId"))||!Objects.equals(oldControl.get("package"),n.get("package"))){
            throw new InputNotSentException("stale_target","The scroll container changed. Observe the display again.");
        }
        var fresh=new LinkedHashMap<>(ref);fresh.put("targetSignature",targetSignature(tree,n));return fresh;
    }
    /** Stable identity of a control: what it is and where it lives, never where it is drawn. */
    public static Map<String,Object> identity(Map<String,Object> tree,Map<String,Object> n){return identity(tree,new ObservationLayout(tree),n,true);}
    private static Map<String,Object> identity(Map<String,Object> tree,ObservationLayout layout,Map<String,Object> n,boolean withContext){
        var c=layout.control(n);
        // A scroll container often takes its name from the rows it shows, so scrolling must not change its identity.
        String label=Boolean.TRUE.equals(n.get("scrollable"))?"":c==null?layout.label(n):c.label();
        var state=c==null||c.state()==null?n:c.state();
        Map<String,Object> out=Json.mapOf("label",label,"role",c==null?role(n):c.role(),"resourceId",str(n.get("resourceId")),"class",str(n.get("class")),"package",str(n.get("package")),"windowKey",windowKey(n));
        if(state.containsKey("checked"))out.put("checked",state.get("checked"));
        if(withContext)out.put("context",controlContext(tree,n));
        return out;
    }
    private static final List<String> IDENTITY_FIELDS=List.of("label","role","resourceId","class","package","windowKey","checked");
    private static boolean sameIdentity(Map<String,Object> want,Map<String,Object> have,boolean withContext){
        for(String k:IDENTITY_FIELDS)if(!Objects.equals(want.get(k),have.get(k)))return false;
        return !withContext||Objects.equals(want.get("context"),have.get("context"));
    }
    private static String changedFields(Map<String,Object> want,Map<String,Object> have){
        List<String> out=new ArrayList<>();for(String k:IDENTITY_FIELDS)if(!Objects.equals(want.get(k),have.get(k)))out.add(k.equals("checked")?"state":k);
        if(!Objects.equals(want.get("context"),have.get("context")))out.add("context");
        return String.join(", ",out);
    }
    /**
     * Find the control a reference names. The same node must keep its identity; otherwise
     * exactly one control in the same window must match label, resource, role and context.
     */
    private static Map<String,Object> locate(Map<String,Object> current,Map<String,Object> ref){
        String raw=str(ref.get("identity"));
        if(raw.isBlank())throw new InputNotSentException("stale_target","The control changed. Choose a target from the new observation.");
        var want=Json.object(raw);var layout=new ObservationLayout(current);
        var same=appNodes(current).stream().filter(n->!str(n.get("nodeId")).isBlank()&&Objects.equals(n.get("nodeId"),ref.get("nodeId"))&&windowKey(n).equals(ref.get("windowKey"))).collect(java.util.stream.Collectors.toList());
        String changed="";
        if(same.size()==1){
            var have=identity(current,layout,same.get(0),true);
            if(sameIdentity(want,have,true))return same.get(0);
            changed=changedFields(want,have);traceTarget(raw,Json.stringify(have));
        }
        List<Map<String,Object>> found=new ArrayList<>();
        for(var c:layout.controls()){
            var n=c.node();
            if(!sameIdentity(want,identity(current,layout,n,false),false))continue;
            if(sameIdentity(want,identity(current,layout,n,true),true))found.add(n);
        }
        if(found.size()==1)return found.get(0);
        if(found.isEmpty())throw new InputNotSentException("stale_target",changed.isEmpty()?"The control is gone. Choose a target from the new observation.":"The control's "+changed+" changed. Choose a target from the new observation.",changed.isEmpty()?Map.of():Map.of("changed",changed));
        throw new InputNotSentException("ambiguous_target","Several controls now match this target. Choose a target from the new observation.");
    }
    public static Map<String,Object> validateTarget(Map<String,Object> current,Map<String,Object> ref){
        var n=locate(current,ref);
        String op=str(ref.get("operation"));
        boolean supported=op.equals("type")?Boolean.TRUE.equals(n.get("editable")):op.equals("scroll")?Boolean.TRUE.equals(n.get("scrollable")):Boolean.TRUE.equals(n.get("clickable"))||Boolean.TRUE.equals(n.get("longClickable"));
        if(!supported)throw new InputNotSentException("target_unsupported","Choose a control that supports this action.");
        if(Boolean.FALSE.equals(n.get("enabled")))throw new InputNotSentException("target_disabled","This control is disabled. Wait for it to become available.");
        return n;
    }
    private static void traceTarget(String before,String after){
        if(Boolean.getBoolean("backstage.traceTargets"))System.err.println("TARGET_SIGNATURE_CHANGED "+Json.stringify(Json.mapOf("before",Json.object(before),"after",Json.object(after))));
    }
    private static String controlContext(Map<String,Object> tree,Map<String,Object> control){
        List<Map<String,Object>> ns=appNodes(tree);
        Map<Object,Map<String,Object>> byIndex=new HashMap<>(ns.size()*2);
        Map<Object,List<Map<String,Object>>> children=new HashMap<>();
        for(var row:ns){byIndex.put(row.get("index"),row);children.computeIfAbsent(row.get("parent"),k->new ArrayList<>()).add(row);}
        long windowArea=0;for(var w:windows(tree))if(selectedWindow(tree,w)){int[] b=ObservationCondition.bounds(w.get("bounds"));if(b!=null)windowArea=(long)(b[2]-b[0])*(b[3]-b[1]);}
        return controlContextIndexed(control,byIndex,children,windowArea);
    }
    private static String controlContextIndexed(Map<String,Object> control,Map<Object,Map<String,Object>> byIndex,Map<Object,List<Map<String,Object>>> children,long windowArea){
        String controlLabel=label(control);
        Object controlIdx=control.get("index");
        Object p=control.get("parent");
        for(int depth=0;p!=null&&depth<12;depth++){
            var parent=byIndex.get(p);if(parent==null)break;
            String explicit=str(parent.get("paneTitle"));if(explicit.isBlank())explicit=label(parent);
            if(!explicit.isBlank()&&!explicit.equals(controlLabel))return explicit;
            int[] b=ObservationCondition.bounds(parent.get("bounds"));
            if(b!=null&&windowArea>0&&(long)(b[2]-b[0])*(b[3]-b[1])*2<windowArea){
                String found=findSiblingLabel(parent.get("index"),controlIdx,controlLabel,children,0);
                if(found!=null)return found;
            }
            p=parent.get("parent");
        }
        return "";
    }
    private static String findSiblingLabel(Object nodeIdx,Object skipIdx,String controlLabel,Map<Object,List<Map<String,Object>>> children,int depth){
        if(depth>=60)return null;
        for(var child:children.getOrDefault(nodeIdx,List.of())){
            Object idx=child.get("index");
            if(Objects.equals(idx,skipIdx))continue;
            if(!Boolean.TRUE.equals(child.get("scrollable"))){
                String lbl=label(child);
                if(!lbl.isBlank()&&!lbl.equals(controlLabel))return lbl;
            }
            String sub=findSiblingLabel(idx,skipIdx,controlLabel,children,depth+1);
            if(sub!=null)return sub;
        }
        return null;
    }
    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value){
        if(value instanceof Map<?,?> m){
            Map<String,Object> copy=new LinkedHashMap<>(m.size()*2);
            for(var e:m.entrySet())copy.put(String.valueOf(e.getKey()),deepCopy(e.getValue()));
            return copy;
        }
        if(value instanceof List<?> l){
            List<Object> copy=new ArrayList<>(l.size());
            for(Object item:l)copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }
    public static String targetSignature(Map<String,Object> tree,Map<String,Object> n){
        var layout=new ObservationLayout(tree);var control=layout.control(n);
        Map<Object,Map<String,Object>> byIndex=new HashMap<>();for(var row:appNodes(tree))byIndex.put(row.get("index"),row);
        List<Object> parents=new ArrayList<>();Object p=n.get("parent");
        for(int i=0;p!=null&&i<60;i++){var parent=byIndex.get(p);if(parent==null)break;parents.add(Json.mapOf("nodeId",parent.get("nodeId"),"resourceId",parent.get("resourceId"),"label",label(parent),"paneTitle",parent.get("paneTitle")));p=parent.get("parent");}
        // Android can briefly omit a window title when UiAutomation reconnects.
        // The package/process/root key identifies the window; title remains read metadata.
        List<Object> focused=new ArrayList<>();for(var w:windows(tree))if(Boolean.TRUE.equals(w.get("focused"))||selectedWindow(tree,w))focused.add(Json.mapOf("key",w.getOrDefault("key",w.get("id")),"type",w.get("type"),"bounds",w.get("bounds")));
        return Json.stringify(Json.mapOf("control",signatureNode(n),"label",control==null?layout.label(n):control.label(),"state",control==null||control.state()==null?Map.of():signatureNode(control.state()),"context",controlContext(tree,n),"parents",parents,"windows",focused));
    }
    public static Map<String,Object> differences(Map<String,Object> before,Map<String,Object> after){
        List<Map<String,Object>> changes=new ArrayList<>();var a=appNodes(before);var b=appNodes(after);
        for(int i=0;i<Math.max(a.size(),b.size())&&changes.size()<12;i++){
            var old=i<a.size()?signatureNode(a.get(i)):Map.<String,Object>of();var now=i<b.size()?signatureNode(b.get(i)):Map.<String,Object>of();
            Set<String> keys=new LinkedHashSet<>(old.keySet());keys.addAll(now.keySet());Map<String,Object> fields=new LinkedHashMap<>();
            for(String key:keys)if(!Objects.equals(old.get(key),now.get(key)))fields.put(key,Json.mapOf("before",old.get(key),"after",now.get(key)));
            if(!fields.isEmpty())changes.add(Json.mapOf("index",i,"fields",fields));
        }
        return Json.mapOf("changedNodes",changes,"beforeWindows",windows(before),"afterWindows",windows(after),"beforeNodeCount",a.size(),"afterNodeCount",b.size());
    }
    private static String identity(Map<String,Object> n){return windowKey(n)+":"+str(n.get("nodeId"))+":"+str(n.get("resourceId"));}
    private static boolean ancestor(Map<String,Object> ancestor,Map<String,Object> child,Map<Object,Map<String,Object>> byIndex){
        Object p=child.get("parent");for(int i=0;p!=null&&i<60;i++){if(p.equals(ancestor.get("index")))return true;var n=byIndex.get(p);if(n==null)break;p=n.get("parent");}return false;
    }
    public synchronized Map<String,Object> observe(Map<String,Object> tree,int max,String since){
        return observe(tree,max,since,true,true);
    }
    public synchronized Map<String,Object> observe(Map<String,Object> tree,int max,String since,boolean full,boolean debug){
        int display=((Number)tree.get("display")).intValue();
        String scopeKey=display+":"+str(tree.get("windowId"));
        String effectiveSince=since;
        Entry previous=null,last=null;
        String autoId=lastByScope.get(scopeKey);
        if(autoId!=null){
            Entry cand=entries.get(autoId);
            if(cand!=null&&cand.display==display&&clock.getAsLong()-cand.at<=TTL_MS)last=cand;
        }
        if(since!=null){
            previous=get(since,display);
        }else if(!full&&!debug&&last!=null){
            previous=last;effectiveSince=autoId;
        }
        // Target ids always carry over from the last observation of this scope, so an id an agent holds
        // keeps naming the same control in failure and full responses. Only deltas depend on the mode.
        Entry idSource=previous!=null?previous:last;
        List<Map<String,Object>> ns=appNodes(tree);
        Map<Object,Map<String,Object>> byIndex=new HashMap<>(ns.size()*2);
        Map<Object,List<Map<String,Object>>> children=new HashMap<>();
        for(var row:ns){byIndex.put(row.get("index"),row);children.computeIfAbsent(row.get("parent"),k->new ArrayList<>()).add(row);}
        long windowArea=0;for(var w:windows(tree))if(selectedWindow(tree,w)){int[] b=ObservationCondition.bounds(w.get("bounds"));if(b!=null)windowArea=(long)(b[2]-b[0])*(b[3]-b[1]);}
        var layout=new ObservationLayout(tree);
        Map<String,Map<String,Object>> targets=new LinkedHashMap<>();List<Map<String,Object>> controls=new ArrayList<>();int unlabeled=0;
        Map<String,String> previousIds=new HashMap<>();if(idSource!=null)idSource.targets.forEach((id,n)->previousIds.put(identity(n),id));
        for(var presentation:layout.controls()){
            var n=presentation.node();String label=presentation.label();
            if(label.isEmpty()){if(Boolean.TRUE.equals(n.get("scrollable")))label="Scroll area";else{unlabeled++;continue;}}
            String id=previousIds.get(identity(n));if(id==null)id="t"+(nextTarget++);
            targets.put(id,new LinkedHashMap<>(n));
            Map<String,Object> c=Json.mapOf("id",id,"label",label.length()>200?label.substring(0,200):label,"role",presentation.role());
            if(!str(n.get("resourceId")).isBlank())c.put("resourceId",n.get("resourceId"));
            String context=controlContextIndexed(n,byIndex,children,windowArea);if(!context.isBlank()&&!context.equals(label))c.put("context",context.length()>200?context.substring(0,200):context);
            if(Boolean.TRUE.equals(n.get("editable"))&&!Boolean.TRUE.equals(n.get("password"))){
                String value=Boolean.TRUE.equals(n.get("showingHintText"))?"":str(n.getOrDefault("fieldValue",n.get("text")));c.put("value",value.length()>400?value.substring(0,400):value);if(value.length()>400)c.put("valueTruncated",true);if(n.get("inputType") instanceof Number type&&(type.intValue()&15)==3)c.put("inputType","phone");
            }
            var stateNode=presentation.state()==null?n:presentation.state();
            for(String state:List.of("checked","selected","stateDescription"))if(stateNode.containsKey(state))c.put(state,stateNode.get(state));
            if(presentation.state()!=null&&!stateNode.containsKey("checked")&&!stateNode.containsKey("stateDescription"))c.put("state","unknown");
            if(Boolean.FALSE.equals(n.get("enabled")))c.put("enabled",false);
            if(!Boolean.TRUE.equals(n.get("scrollable"))){
                var details=layout.details(presentation);List<Object> limited=new ArrayList<>();
                for(var item:details){if(limited.size()>=8||Json.stringify(limited).length()+Json.stringify(item).length()>1600){c.put("detailsTruncated",true);break;}limited.add(item);}
                if(!limited.isEmpty())c.put("details",limited);
            }
            controls.add(c);
        }
        List<Object> content=layout.content();
        String pkg=ns.stream().map(n->str(n.get("package"))).filter(s->!s.isEmpty()).findFirst().orElse("");
        String title=windows(tree).stream().filter(w->selectedWindow(tree,w)).map(w->str(w.get("title"))).filter(s->!s.isBlank()).findFirst().orElse("");
        String id="obs_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        Map<String,Object> view=Json.mapOf("observation",id,"display",display,"app",tree.getOrDefault("appLabel",pkg),"screen",title,"observedAt",tree.get("observedAt"),"controls",controls,"content",content);
        List<Map<String,Object>> availableWindows=new ArrayList<>();for(var w:windows(tree)){var row=new LinkedHashMap<String,Object>();for(String k:List.of("windowId","title","type","focused","active","bounds","layer","package","root"))if(w.containsKey(k))row.put(k,w.get(k));availableWindows.add(row);}
        view.put("windows",availableWindows);if(tree.containsKey("windowId"))view.put("windowId",tree.get("windowId"));
        if(unlabeled>0)view.put("unlabeledControls",unlabeled);
        long ambiguous=controls.stream().filter(c->controls.stream().filter(other->Objects.equals(c.get("label"),other.get("label"))&&Objects.equals(c.get("role"),other.get("role"))&&Objects.equals(c.get("context"),other.get("context"))).count()>1).count();
        if(ambiguous>0)view.put("ambiguousControls",ambiguous);
        Map<String,Object> publicView=new LinkedHashMap<>(view);
        if(!debug&&!tree.containsKey("windowId")){
            List<Map<String,Object>> nonSystem=new ArrayList<>();
            for(var w:availableWindows){
                if(!"system".equals(w.get("type"))){
                    var compactWin=new LinkedHashMap<String,Object>();
                    for(String k:List.of("windowId","title","type","focused","package"))if(w.containsKey(k))compactWin.put(k,w.get(k));
                    nonSystem.add(compactWin);
                }
            }
            if(nonSystem.size()<=1)publicView.remove("windows");
            else publicView.put("windows",nonSystem);
        }
        if(layout.textTruncated()||controls.stream().anyMatch(c->Boolean.TRUE.equals(c.get("detailsTruncated"))))publicView.put("truncated",true);
        if(previous!=null&&Objects.equals(previous.view.get("app"),view.get("app"))&&Objects.equals(previous.view.get("screen"),view.get("screen"))){
            @SuppressWarnings("unchecked") List<Map<String,Object>> old=(List<Map<String,Object>>)previous.view.get("controls");
            long shared=controls.stream().filter(c->old.stream().anyMatch(o->o.get("id").equals(c.get("id")))).count();
            @SuppressWarnings("unchecked") List<Object> oldText=(List<Object>)previous.view.get("content");
            long sharedText=content.stream().filter(oldText::contains).count();
            if((shared+sharedText)*2>=Math.max(1,Math.max(old.size()+oldText.size(),controls.size()+content.size()))){
                List<Map<String,Object>> changedControls=controls.stream().filter(c->!old.contains(c)).collect(java.util.stream.Collectors.toList());
                List<Object> removedIds=old.stream().filter(o->controls.stream().noneMatch(c->c.get("id").equals(o.get("id")))).map(o->o.get("id")).collect(java.util.stream.Collectors.toList());
                List<Object> changedContent=content.stream().filter(s->!oldText.contains(s)).collect(java.util.stream.Collectors.toList());
                List<Object> removedContent=oldText.stream().filter(s->!content.contains(s)).collect(java.util.stream.Collectors.toList());
                boolean explicitSince=since!=null;
                if(explicitSince||changedControls.size()+changedContent.size()<controls.size()+content.size()||(controls.isEmpty()&&content.isEmpty())){
                    publicView.put("since",effectiveSince);publicView.put("delta",true);
                    publicView.put("controls",changedControls);
                    publicView.put("removed",removedIds);
                    publicView.put("content",changedContent);
                    publicView.put("removedContent",removedContent);
                }
            }
        }
        int remaining=max;for(String key:List.of("controls","content")){List<?> list=(List<?>)publicView.get(key);int keep=Math.min(remaining,list.size());if(list.size()>keep){publicView.put(key,list.subList(0,keep));publicView.put("truncated",true);}remaining-=keep;}
        if(Boolean.TRUE.equals(tree.get("truncated")))publicView.put("truncated",true);
        entries.put(id,new Entry(id,display,clock.getAsLong(),(Map<String,Object>)deepCopy(tree),targets,(Map<String,Object>)deepCopy(view)));while(entries.size()>32)entries.remove(entries.keySet().iterator().next());
        lastByScope.put(scopeKey,id);
        return publicView;
    }
}
