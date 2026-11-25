package com.deviceagent.tools;

import java.util.*;
import java.net.URI;

/** Shared side-effect-free preflight for standalone primitives and sequence steps. */
public final class PrimitiveArguments {
    private PrimitiveArguments() {}
    public static final Set<String> GLOBAL_KEYS=Set.of("HOME","APP_SWITCH","NOTIFICATION","POWER","WAKEUP","SLEEP","VOLUME_UP","VOLUME_DOWN","VOLUME_MUTE","MUTE","MENU","SEARCH","SETTINGS","BRIGHTNESS_UP","BRIGHTNESS_DOWN","ASSIST","VOICE_ASSIST");
    public static final Set<String> TOOLS=Set.of("android.observe","android.open","android.tap","android.type","android.key","android.gesture","android.scroll","android.scroll_until","android.wait");
    private static final Map<String,Set<String>> FIELDS=Map.of(
        "android.observe",Set.of(),"android.wait",Set.of(),
        "android.open",Set.of("package","activity","url","settingsAction","activityTaskId","intent","share"),
        "android.tap",Set.of("target","observation","selector","x","y","screenshotId","holdMs"),
        "android.type",Set.of("target","observation","selector","text","mode","focused","screenshotId","clipboard"),
        "android.key",Set.of("key","modifiers","holdMs","repeat","screenshotId"),
        "android.gesture",Set.of("preset","x1","y1","x2","y2","centerX","centerY","startSpan","endSpan","angleDegrees","paths","durationMs","holdMs","screenshotId"),
        "android.scroll",Set.of("target","observation","selector","direction","amount","until","maxScrolls"),
        "android.scroll_until",Set.of("target","observation","selector","direction","amount","until","maxScrolls"));
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object x){if(!(x instanceof Map))throw new IllegalArgumentException("Expected an object");return (Map<String,Object>)x;}
    public static void text(Map<String,Object> a,String key,boolean empty,int max){if(!(a.get(key) instanceof String s)||!empty&&s.isBlank()||s.length()>max)throw new IllegalArgumentException("Invalid "+key);}
    public static void url(String value){
        try{URI u=URI.create(value);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();}
        catch(RuntimeException e){throw new IllegalArgumentException("Use an http or https URL without embedded credentials");}
    }
    /**
     * Accept the scroll forms agents send most: no container (the phone picks the largest scrollable),
     * selector {role:"scroll"} alone, and until {selector, maxScrolls}. Returns the arguments to validate:
     * with no container, a stand-in selector satisfies the schema and is never used.
     */
    public static Map<String,Object> scrollForms(String name,Map<String,Object> a){
        if(!name.equals("android.scroll")&&!name.equals("android.scroll_until"))return a;
        if(a.get("until") instanceof Map<?,?> until&&until.containsKey("selector")){
            var nested=map(until);a.put("until",nested.get("selector"));
            if(nested.containsKey("maxScrolls"))a.putIfAbsent("maxScrolls",nested.get("maxScrolls"));
        }
        if(a.get("selector") instanceof Map<?,?> sel&&sel.size()==1&&"scroll".equals(sel.get("role")))a.remove("selector");
        if(a.containsKey("target")||a.containsKey("observation")||a.containsKey("selector"))return a;
        var check=new java.util.LinkedHashMap<>(a);check.put("selector",Map.of("label","Scroll area"));return check;
    }
    public static void selector(Object raw){var s=map(raw);if(!Set.of("label","role","context","resourceId").containsAll(s.keySet())||!s.containsKey("label")&&!s.containsKey("resourceId"))throw new IllegalArgumentException("A selector needs label or resourceId, with optional role and context");for(String k:s.keySet())text(s,k,false,1000);}
    public static void validate(String name,Map<String,Object> a){
        if(!TOOLS.contains(name))throw new IllegalArgumentException("Unsupported primitive: "+name);
        var allowed=new HashSet<>(FIELDS.get(name));allowed.addAll(Set.of("display","windowId","verify","timeoutMs","expectedPackage","observe","detail","timing","maxItems","since","full","image","scale","requestId","sessionId"));
        if(!allowed.containsAll(a.keySet()))throw new IllegalArgumentException("Unknown arguments for "+name+": "+a.keySet().stream().filter(k->!allowed.contains(k)).collect(java.util.stream.Collectors.toList()));
        if(a.containsKey("display"))GesturePlan.integer(a,"display",-1,0,Integer.MAX_VALUE);
        if(a.containsKey("windowId")&&(!(a.get("windowId") instanceof String w)||!w.matches("window_[a-f0-9]{24}")))throw new IllegalArgumentException("Use a windowId from observe.windows");
        GesturePlan.integer(a,"timeoutMs",5000,0,20000);GesturePlan.integer(a,"maxItems",40,1,300);
        if(a.containsKey("detail")&&!Set.of("compact","debug").contains(a.get("detail")))throw new IllegalArgumentException("detail must be compact or debug");
        if(a.containsKey("image")&&!Set.of("never","always","if_needed","only").contains(a.get("image")))throw new IllegalArgumentException("Invalid image mode");
        if("only".equals(a.get("image"))&&(a.containsKey("verify")||a.containsKey("since")||"debug".equals(a.get("detail"))))throw new IllegalArgumentException("Pixel-only capture does not read controls. Use image always with verify, since or debug.");
        if(a.containsKey("screenshotId"))text(a,"screenshotId",false,100);
        double scale=GesturePlan.number(a,"scale",0.5);if(scale<=0||scale>1)throw new IllegalArgumentException("scale must be above zero and at most one");
        for(String k:List.of("observe","timing","full"))if(a.containsKey(k)&&!(a.get(k) instanceof Boolean))throw new IllegalArgumentException(k+" must be boolean");
        if(a.containsKey("expectedPackage"))text(a,"expectedPackage",false,256);
        if(a.containsKey("verify"))ObservationCondition.validate(map(a.get("verify")));
        if(a.containsKey("target")||a.containsKey("observation")){text(a,"target",false,100);text(a,"observation",false,100);if(a.containsKey("selector"))throw new IllegalArgumentException("Choose a target or selector");}
        if(a.containsKey("selector"))selector(a.get("selector"));
        switch(name){
            case "android.open" -> {
                long choices=List.of("package","url","settingsAction","activityTaskId","intent","share").stream().filter(a::containsKey).count();
                if(choices!=1)throw new IllegalArgumentException("Open needs exactly one package, url, settingsAction, activityTaskId, or intent");
                for(String k:List.of("package","url","activity","settingsAction"))if(a.containsKey(k))text(a,k,false,2048);
                if(a.containsKey("share")){var share=map(a.get("share"));JsonSchema.validate(map(map(ToolCatalog.schema("android.open").get("properties")).get("share")),share);}
                if(a.containsKey("intent"))IntentCommand.build(map(a.get("intent")),1);
                if(a.containsKey("activityTaskId"))GesturePlan.integer(a,"activityTaskId",-1,1,Integer.MAX_VALUE);
                if(a.containsKey("url"))url((String)a.get("url"));
                if(a.containsKey("package")&&!((String)a.get("package")).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Invalid package");
                if(a.containsKey("activity")&&(!a.containsKey("package")||!((String)a.get("activity")).matches("[A-Za-z0-9_.$]+")))throw new IllegalArgumentException("Activity needs a package and a valid class name");
                if(a.containsKey("settingsAction")&&!((String)a.get("settingsAction")).matches("android\\.settings\\.[A-Z_]+"))throw new IllegalArgumentException("Use an Android settings action");
            }
            case "android.tap" -> {
                int choices=(a.containsKey("target")?1:0)+(a.containsKey("selector")?1:0)+(a.containsKey("x")||a.containsKey("y")?1:0);
                if(choices!=1)throw new IllegalArgumentException("Tap needs one target, selector, or coordinate pair");
                if(a.containsKey("x")||a.containsKey("y")){GesturePlan.integer(a,"x",-1,0,100000);GesturePlan.integer(a,"y",-1,0,100000);}
                if(a.containsKey("screenshotId")&&!a.containsKey("x"))throw new IllegalArgumentException("screenshotId requires coordinates");
                GesturePlan.integer(a,"holdMs",0,0,3000);
            }
            case "android.type" -> {
                text(a,"text",true,10000);
                if(a.containsKey("focused")){
                    if(!Boolean.TRUE.equals(a.get("focused"))||!Boolean.TRUE.equals(a.get("clipboard"))||!a.containsKey("screenshotId")||!a.containsKey("mode")||a.containsKey("target")||a.containsKey("selector"))throw new IllegalArgumentException("Focused text requires screenshotId, mode and clipboard:true, without a field target");
                }else if(!a.containsKey("target")&&!a.containsKey("selector")||a.containsKey("screenshotId")||a.containsKey("clipboard"))throw new IllegalArgumentException("Choose a field target or explicit focused text with clipboard:true");
                if(a.containsKey("mode")&&!Set.of("insert","replace").contains(a.get("mode")))throw new IllegalArgumentException("Unknown text mode");
            }
            case "android.key" -> {
                text(a,"key",false,40);
                var keys=(List<?>)map(map(ToolCatalog.schema(name).get("properties")).get("key")).get("enum");
                if(!keys.contains(a.get("key")))throw new IllegalArgumentException("Unsupported key");
                if(a.get("display") instanceof Number d&&d.intValue()!=0&&(GLOBAL_KEYS.contains(a.get("key"))||a.get("modifiers") instanceof List<?> mods&&(mods.contains("META")||mods.contains("SYM")||mods.contains("FUNCTION"))))throw new IllegalArgumentException("This key requires an explicit foreground session");
                if(a.containsKey("modifiers")){
                    if(!(a.get("modifiers") instanceof List<?> mods)||mods.size()>6||new HashSet<>(mods).size()!=mods.size()||!Set.of("CTRL","ALT","SHIFT","META","SYM","FUNCTION").containsAll(mods))throw new IllegalArgumentException("Use unique keyboard modifiers");
                }
                GesturePlan.integer(a,"holdMs",0,0,3000);GesturePlan.integer(a,"repeat",1,1,100);
            }
            case "android.gesture" -> {
                String p=Observations.str(a.get("preset"));if(!Set.of("swipe","drag","pinch","paths").contains(p))throw new IllegalArgumentException("Use swipe, drag, pinch, or paths");
                var options=new LinkedHashMap<>(a);if(p.equals("swipe"))options.put("holdMs",0);
                GesturePlan.create(p.equals("paths")?"multi_touch":p.equals("swipe")?"drag":p,options,100001,100001);
            }
            case "android.scroll","android.scroll_until" -> {
                if(!a.containsKey("target")&&!a.containsKey("selector"))throw new IllegalArgumentException("Choose a scroll container target or selector");
                if(!Set.of("up","down","left","right").contains(a.get("direction")))throw new IllegalArgumentException("Invalid scroll direction");
                GesturePlan.integer(a,"amount",1,1,5);
                if(name.equals("android.scroll_until")||a.containsKey("until")){selector(a.get("until"));GesturePlan.integer(a,"maxScrolls",5,1,20);}
                else if(a.containsKey("maxScrolls"))throw new IllegalArgumentException("maxScrolls requires until");
            }
            case "android.wait" -> {if(!a.containsKey("verify"))throw new IllegalArgumentException("A wait requires verify");}
            default -> {}
        }
    }
}
