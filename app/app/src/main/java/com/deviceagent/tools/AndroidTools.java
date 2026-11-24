package com.deviceagent.tools;

import com.deviceagent.app.AgentInstructions;
import com.deviceagent.app.BuildInfo;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.daemon.Starter;
import com.deviceagent.protocol.CommandFailedException;
import com.deviceagent.protocol.ProcessResult;
import com.deviceagent.protocol.ShellCommand;
import com.deviceagent.transport.DeviceExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AndroidTools {
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^\\s*Package \\[([^\\]]+)\\]");
    private static final Pattern VERSION_NAME = Pattern.compile("(?m)^\\s*versionName=(\\S+)");
    private static final Pattern VERSION_CODE = Pattern.compile("(?m)^\\s*versionCode=(\\S+)");
    private static final Pattern PERMISSION = Pattern.compile("(?m)^\\s+(android\\.permission\\.[A-Za-z0-9_]+): granted=(true|false)");

    private final DeviceExecutor device;
    private final String selfPackage;
    private final String configPath;
    private final DaemonConfig daemonConfig;
    private final PrivacyFence privacyFence;
    private final ActivityTimeline timeline;
    private final PendingConfirmations confirmations = new PendingConfirmations();
    private final SharedInbox inbox;
    private volatile boolean allowShell;
    private volatile java.util.function.Consumer<Map<String, Object>> eventEmitter;
    private volatile Runnable eventRulesChanged;
    private TaskResources taskResources;
    private synchronized TaskResources tasks(){if(taskResources==null)taskResources=new TaskResources(java.nio.file.Paths.get(configPath).resolveSibling("sessions"),selfPackage,this::resourceCall);return taskResources;}
    private final Map<String,String> appLabels=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer,Long> lastUnfreezeAt=new java.util.concurrent.ConcurrentHashMap<>();
    /** Agents sharing a display take turns in arrival order; other displays and non-UI calls are not blocked. */
    private final Map<Integer,java.util.concurrent.locks.ReentrantLock> displayLocks=new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.locks.ReentrantLock displayLock(int display){return displayLocks.computeIfAbsent(display,d->new java.util.concurrent.locks.ReentrantLock(true));}
    private final EvidenceFlow evidence = new EvidenceFlow(new EvidenceFlow.Backend() {
        public Map<String,Object> action(String name,Map<String,Object> args) { return callRaw(name,args); }
        public Map<String,Object> tree(int display) {return tree(display,null);}
        public Map<String,Object> tree(int display,String windowId) {
            wakeIfNeeded(display);
            requireDisplayAllowed(display);
            resumePrivateApp(display, false);
            Map<String,Object> out=uiTreeFast(display,windowId);
            if(out==null && display>0){
                resumePrivateApp(display, true);
                out=uiTreeFast(display,windowId);
            }
            if(out==null)throw new IllegalStateException("The screen could not be read: "+lastFastError);
            for(var node:Observations.appNodes(out)) {
                String pkg=Observations.str(node.get("package"));if(pkg.isEmpty())continue;
                privacyFence.requireAllowed(pkg);
                String label=appLabels.get(pkg);
                if(label==null){Map<String,Object> info=device.interaction("app_label",Map.of("package",pkg));label=Observations.str(info.get("label"));if(!label.isEmpty())appLabels.put(pkg,label);}
                if(label!=null&&!label.isEmpty())out.put("appLabel",label);break;
            }
            return out;
        }
        public Map<String,Object> screen(int display) { requireDisplayAllowed(display); return screenshot(display,0.5f); }
        public Map<String,Object> screen(int display,double scale) { requireDisplayAllowed(display); return screenshot(display,(float)scale); }
        public Map<String,Object> display() { return device.virtualDisplay("status",Map.of()); }
        public Map<String,Object> display(int display) { return display==0?device.interaction("screen_control",Map.of("operation","status")):device.virtualDisplay("touch",Map.of("displayId",display)); }
        public String focus(int display) {
            return focusComponent(display);
        }
        public String captureFocus(int display) {
            if (display > 0) {
                try {
                    Map<String, Object> top = device.interaction("top_activity", Map.of("display", display));
                    if (Boolean.TRUE.equals(top.get("ok")) && top.get("component") instanceof String c && !c.isBlank()) return c;
                } catch (RuntimeException ignored) { }
            }
            return TaskSnapshot.focusIdentity(device.exec(ShellCommand.focusOnDisplay(display),2000).trimmedStdout());
        }
    });

    private String focusComponent(int display) {
        try {
            Map<String, Object> top = device.interaction("top_activity", Map.of("display", display));
            if (Boolean.TRUE.equals(top.get("ok")) && top.get("component") instanceof String c && !c.isBlank()) return c;
        } catch (RuntimeException ignored) { }
        return TaskSnapshot.focusedComponent(device.exec(ShellCommand.focusOnDisplay(display), 2000).trimmedStdout());
    }

    private void requireDisplayAllowed(int display) {
        if (privacyFence.count() == 0) return;
        String comp = focusComponent(display);
        if (comp != null && !comp.isBlank()) {
            privacyFence.requireComponentAllowed(comp);
            return;
        }
        try {
            for (Map<String, Object> task : tasksOnDisplay(display)) {
                if (Boolean.FALSE.equals(task.get("visible"))) continue;
                if (task.get("package") instanceof String pkg && !pkg.isBlank()) {
                    privacyFence.requireAllowed(pkg);
                }
            }
        } catch (InputNotSentException e) {
            throw e;
        } catch (RuntimeException ignored) {
        }
    }

    private void requireIntentTargetAllowed(String action, String data, String pkg, String component) {
        if (privacyFence.count() == 0) return;
        if (pkg != null && !pkg.isBlank()) privacyFence.requireAllowed(pkg);
        if (component != null && !component.isBlank()) privacyFence.requireComponentAllowed(component);
        if ((pkg != null && !pkg.isBlank()) || (component != null && !component.isBlank())) return;
        StringBuilder cmd = new StringBuilder("cmd package resolve-activity --brief");
        if (action != null && !action.isBlank()) cmd.append(" -a ").append(ShellCommand.quote(action));
        if (data != null && !data.isBlank()) cmd.append(" -d ").append(ShellCommand.quote(data));
        try {
            ProcessResult res = device.exec(cmd.toString(), 4_000L);
            Matcher m = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_.]*)/(\\S+)\\s*$").matcher(res.stdoutText());
            while (m.find()) privacyFence.requireAllowed(m.group(1));
        } catch (InputNotSentException e) {
            throw e;
        } catch (RuntimeException ignored) {
        }
    }

    public AndroidTools(DeviceExecutor device) {
        this(device, BuildInfo.DEFAULT_PACKAGE, Starter.CONFIG_PATH, null);
    }

    public AndroidTools(DeviceExecutor device, String packageName) {
        this(device, packageName, Starter.CONFIG_PATH, null);
    }

    public AndroidTools(DeviceExecutor device, DaemonConfig config) {
        this(device,
                config == null || config.packageName == null || config.packageName.isBlank()
                        ? BuildInfo.DEFAULT_PACKAGE : config.packageName,
                config == null || config.configPath == null || config.configPath.isBlank()
                        ? Starter.CONFIG_PATH : config.configPath,
                config);
    }

    public AndroidTools(DeviceExecutor device, String packageName, String configPath) {
        this(device, packageName, configPath, null);
    }

    private AndroidTools(DeviceExecutor device, String packageName, String configPath, DaemonConfig config) {
        this.device = device;
        this.selfPackage = packageName == null || packageName.isBlank() ? BuildInfo.DEFAULT_PACKAGE : packageName;
        this.configPath = configPath == null || configPath.isBlank() ? Starter.CONFIG_PATH : configPath;
        this.daemonConfig = config;
        this.allowShell = config != null && config.allowShell;
        this.privacyFence = new PrivacyFence(config);
        Path baseDir = java.nio.file.Paths.get(this.configPath).getParent();
        this.timeline = new ActivityTimeline(baseDir == null ? null : baseDir.resolve("timeline.json"));
        this.inbox = new SharedInbox(baseDir == null ? null : baseDir.resolve("inbox.json"));
        var sweeper=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"session-lease-cleanup");t.setDaemon(true);return t;});
        sweeper.scheduleWithFixedDelay(()->tasks().sweep(),30,30,java.util.concurrent.TimeUnit.SECONDS);
    }

    public PrivacyFence privacyFence() { return privacyFence; }
    public ActivityTimeline timeline() { return timeline; }
    public PendingConfirmations confirmations() { return confirmations; }
    public SharedInbox inbox() { return inbox; }
    public void setEventEmitter(java.util.function.Consumer<Map<String, Object>> emitter) { this.eventEmitter = emitter; }
    public void setEventRulesChanged(Runnable hook) { this.eventRulesChanged = hook; }
    public void emitEvent(Map<String, Object> event) {
        var emitter = this.eventEmitter;
        if (emitter != null && event != null) {
            try { emitter.accept(event); } catch (RuntimeException ignored) {}
        }
    }

    public List<Map<String, Object>> listAllSessions() {
        List<Map<String, Object>> active = tasks().listAllActive();
        for (Map<String, Object> s : active) {
            if (s.get("display") instanceof Number d) {
                int disp = d.intValue();
                String comp = focusComponent(disp);
                String pkg = privacyFence.packageOfComponent(comp);
                if (!comp.isEmpty()) s.put("focusedActivity", comp);
                if (!pkg.isEmpty()) {
                    s.put("package", pkg);
                    s.put("blocked", privacyFence.isBlocked(pkg));
                }
                if (disp > 0) {
                    try {
                        Map<String, Object> st = device.virtualDisplay("status", Map.of("displayId", disp));
                        s.put("recording", Boolean.TRUE.equals(st.get("recording")));
                        s.put("width", st.get("width"));
                        s.put("height", st.get("height"));
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return active;
    }

    public Map<String, Object> stopSessionAdmin(String sessionId) {
        return tasks().forceFinish(sessionId);
    }

    public Map<String, Object> handoffSessionAdmin(String sessionId, Integer displayId) {
        int disp = displayId != null ? displayId : -1;
        if (disp <= 0 && sessionId != null && !sessionId.isBlank()) {
            for (Map<String, Object> s : tasks().listAllActive()) {
                if (sessionId.equals(s.get("sessionId")) && s.get("display") instanceof Number n) {
                    disp = n.intValue();
                    break;
                }
            }
        }
        if (disp <= 0) return Json.mapOf("ok", false, "error", "No active private display for this session.");
        return vdHandoff(disp, null, null);
    }

    public Map<String, Object> catalog() { return ToolCatalog.catalog(); }
    public Map<String,Object> call(String name,Map<String,Object> args){return callOwned(name,args,"local");}
    public Map<String,Object> callOwned(String name,Map<String,Object> supplied,String owner){
        long start=System.nanoTime();Map<String,Object> out;var args=new LinkedHashMap<>(supplied);
        // Normalize legacy tool names into the consolidated contract surface while keeping full backwards compatibility.
        switch(name){
            case "android.task_begin" -> {name="android.task";args.putIfAbsent("operation","begin");}
            case "android.task_inspect" -> {name="android.task";args.putIfAbsent("operation","inspect");}
            case "android.task_list" -> {name="android.task";args.putIfAbsent("operation","list");}
            case "android.task_finish" -> {name="android.task";args.putIfAbsent("operation","finish");}
            case "android.task_share" -> {name="android.task";args.putIfAbsent("operation","share");}
            case "android.handoff" -> {name="android.task";args.putIfAbsent("operation","handoff");}
            case "android.record" -> {name="android.task";Object sub=args.remove("operation");if(sub!=null)args.put("operation","record_"+sub);}
            case "android.pull" -> {name="android.file";args.putIfAbsent("operation","pull");}
            case "android.push" -> {name="android.file";args.putIfAbsent("operation","push");}
            case "android.fetch" -> {name="android.file";args.putIfAbsent("operation","fetch");}
            case "android.scroll_until" -> name="android.scroll";
            case "android.clipboard" -> {name="android.device";args.putIfAbsent("operation","clipboard");}
            case "android.media" -> {name="android.device";args.putIfAbsent("operation","media");}
            case "android.location" -> {name="android.device";args.putIfAbsent("operation","location");}
            case "android.device_control" -> {name="android.device";Object sub=args.remove("operation");if(sub!=null)args.putIfAbsent("action",sub);args.put("operation","control");}
            case "android.audio" -> {name="android.device";Object sub=args.remove("operation");if(sub!=null)args.putIfAbsent("action",sub);args.put("operation","audio");}
            default -> {}
        }
        try{
            ToolCatalog.validate(name,args);
            if(name.equals("android.device")&&"audio".equals(args.get("operation"))){args.put("_owner",owner);args.put("_configPath",configPath);}
            if(PrimitiveArguments.TOOLS.contains(name))PrimitiveArguments.validate(name,args);
            if(name.equals("android.sequence")){var check=new LinkedHashMap<>(args);check.putIfAbsent("display",0);LocalSequence.validate(check);}
            if(name.equals("android.install")||name.equals("android.maintenance")){var source=PrimitiveArguments.map(args.get("source"));if(source.containsKey("url"))PrimitiveArguments.url(Json.string(source,"url"));}
            try(var execution=device.executionScope()){
            if(name.equals("android.device")&&"control".equals(args.get("operation"))&&!"status".equals(args.get("action"))){int d=tasks().use(Json.string(args,"sessionId"),owner,null,false);if(d!=0)throw new InputNotSentException("foreground_required","Device changes require an authorized foreground session");args.put("display",0);}
            java.util.concurrent.locks.ReentrantLock turn=null;
            boolean needsDisplayLock=PrimitiveArguments.TOOLS.contains(name)||Set.of("android.sequence","android.confirm").contains(name)
                    ||name.equals("android.task")&&Set.of("handoff","record_start","record_stop","record_status").contains(args.get("operation"))
                    ||name.equals("android.notification")&&args.containsKey("sessionId");
            if(needsDisplayLock){
                int display=tasks().use(Json.string(args,"sessionId"),owner,args.get("display") instanceof Number n?n.intValue():null,!name.equals("android.notification"));
                args.put("display",display);
                turn=displayLock(display);
            }
            // android.wait reads repeatedly; it takes the accessibility connection per read so
            // other agents' UI calls can run between its checks.
            boolean ui=(PrimitiveArguments.TOOLS.contains(name)||name.equals("android.sequence"))&&!name.equals("android.wait");
            if(turn!=null&&!turn.tryLock(Math.max(0,Math.min(ActionDeadline.remaining(),660000)),java.util.concurrent.TimeUnit.MILLISECONDS))throw new InputNotSentException("display_busy","Another agent kept this display busy until the request expired. Try again.");
            try(var scope=ui?device.accessibilityScope():((DeviceExecutor.AccessibilityScope)()->{})){
                out=switch(name){
                    case "android.task" -> switch(Json.string(args,"operation")){
                        case "begin" -> taskBegin(args,owner);
                        case "finish" -> tasks().finish(args,owner);
                        case "share" -> tasks().share(args,owner);
                        case "list" -> tasks().list(owner);
                        case "inspect" -> tasks().inspect(Json.string(args,"sessionId"),owner);
                        case "handoff" -> vdHandoff(((Number)args.get("display")).intValue(),Json.string(args,"package"),args.containsKey("activityTaskId")?((Number)args.get("activityTaskId")).intValue():null);
                        case "record_start","record_stop","record_status" -> {var rArgs=new LinkedHashMap<>(args);rArgs.put("operation",Json.string(args,"operation").substring(7));yield recordDisplay(rArgs);}
                        default -> throw new IllegalArgumentException("Unknown task operation");
                    };
                    case "android.file" -> switch(Json.string(args,"operation")){
                        case "pull" -> device.pull(Json.string(args,"path"),args.get("offset") instanceof Number n?n.longValue():null,args.get("length") instanceof Number n?n.intValue():null,Json.string(args,"fileId"));
                        case "push" -> push(Json.string(args,"local"),Json.string(args,"remote"));
                        case "fetch" -> fetch(Json.string(args,"url"),Json.string(args,"remote"),Json.string(args,"fileKey"),Json.string(args,"sha256"));
                        default -> throw new IllegalArgumentException("Unknown file operation");
                    };
                    case "android.device" -> deviceOperation(args);
                    case "android.sequence" -> LocalSequence.run(args,evidence::run);
                    case "android.record" -> recordDisplay(args);
                    case "android.confirm" -> confirmAction(args,owner);
                    case "android.personal" -> personalShortcut(args);
                    case "android.events" -> eventSettings(args);
                    default -> EvidenceFlow.handles(name)?evidence.run(name,args):callRaw(name,args);
                };
            }finally{if(turn!=null&&turn.isHeldByCurrentThread())turn.unlock();}
            }
        }catch(InputNotSentException e){out=Json.mapOf("ok",false,"code",e.code,"error",e.getMessage(),"inputDelivered",false);}
        catch(IllegalArgumentException e){out=Json.mapOf("ok",false,"code","invalid_argument","error",e.getMessage(),"inputDelivered",false);}
        catch(InterruptedException e){Thread.currentThread().interrupt();out=Json.mapOf("ok",false,"code","interrupted","error","The request was interrupted before it ran.","inputDelivered",false);}
        if(!Set.of("succeeded","failed","unknown").contains(Observations.str(out.get("outcome"))))out.put("outcome",Boolean.FALSE.equals(out.get("ok"))?out.containsKey("inputDelivered")&&out.get("inputDelivered")==null?"unknown":"failed":"succeeded");
        if(Boolean.TRUE.equals(args.get("timing"))){
            Map<String,Object> timing=new LinkedHashMap<>();
            if(out.get("timing") instanceof Map<?,?> existing)existing.forEach((k,v)->timing.put(k.toString(),v));
            timing.put("deviceTotalMs",(System.nanoTime()-start)/1_000_000);
            out.put("timing",timing);
        }
        if(!"debug".equals(args.get("detail")))for(String k:List.of("command","hint","wokeDisplay","method","component","matched","expectedResultObserved","verified","elapsedMs","screenshotCaptureMs","screenshotEncodingMs"))out.remove(k);
        if(name.equals("android.open")&&Boolean.TRUE.equals(out.get("inputDelivered"))){
            try{out.put("activityTasks",tasksOnDisplay(((Number)args.get("display")).intValue()));}catch(RuntimeException ignored){}
        }
        if(out.containsKey("valueMatched")){Object matched=out.remove("valueMatched");out.putIfAbsent("verification",Map.of("matched",matched));}
        recordActivity(owner,name,args,out);
        out.remove("ok");return out;
    }
    private Map<String,Object> deviceOperation(Map<String,Object> args){
        String op=Json.string(args,"operation");
        Map<String,Object> sub=new LinkedHashMap<>(args);
        sub.remove("operation");
        return switch(op){
            case "clipboard" -> device.interaction("clipboard",sub);
            case "media" -> device.interaction("media",sub);
            case "location" -> device.interaction("location",sub);
            case "control" -> {Object act=sub.remove("action");if(act!=null)sub.put("operation",act);yield device.interaction("device_control",sub);}
            case "audio" -> {if(sub.get("package") instanceof String p)privacyFence.requireAllowed(p);Object act=sub.remove("action");if(act!=null)sub.put("operation",act);yield device.interaction("audio",sub);}
            default -> throw new IllegalArgumentException("Unknown device operation");
        };
    }
    private void recordActivity(String owner,String name,Map<String,Object> args,Map<String,Object> out){
        if(timeline==null||"android.devices".equals(name)||"android.task".equals(name)&&"list".equals(args.get("operation")))return;
        String target=Json.string(args,"package");
        if(target==null||target.isBlank()){
            String url=Json.string(args,"url");
            if(url!=null&&!url.isBlank())target=sanitizeUrlTarget(url);
        }
        if((target==null||target.isBlank())&&out.get("app") instanceof String app&&!app.isBlank())target=app;
        if((target==null||target.isBlank())&&args.get("operation") instanceof String op)target=op;
        if((target==null||target.isBlank())&&args.get("kind") instanceof String k)target=k;
        Integer disp=args.get("display") instanceof Number n?n.intValue():out.get("display") instanceof Number m?m.intValue():null;
        timeline.record(owner,name,target,Observations.str(out.get("outcome")),Json.string(out,"code"),disp);
    }
    private static String sanitizeUrlTarget(String raw){
        try{
            java.net.URI u=java.net.URI.create(raw.trim());
            String scheme=u.getScheme(),host=u.getHost();
            if(scheme!=null&&host!=null)return scheme+"://"+host;
            if(scheme!=null)return scheme+"://";
        }catch(IllegalArgumentException ignored){}
        int cut=raw.indexOf('?');
        int hash=raw.indexOf('#');
        if(hash>=0&&(cut<0||hash<cut))cut=hash;
        return cut>=0?raw.substring(0,cut):raw;
    }
    private Map<String,Object> taskBegin(Map<String,Object> args,String owner){
        if(args.containsKey("package"))privacyFence.requireAllowed(Json.string(args,"package"));
        if(args.get("intent") instanceof Map<?,?> im){
            String ip=im.get("package") instanceof String s?s:null;
            String ic=im.get("component") instanceof String s?s:null;
            String ia=im.get("action") instanceof String s?s:null;
            String id=im.get("data") instanceof String s?s:null;
            requireIntentTargetAllowed(ia,id,ip,ic);
        }
        if(args.get("url") instanceof String u&&!u.isBlank())requireIntentTargetAllowed("android.intent.action.VIEW",u,null,null);
        Map<String,Object> begun=tasks().begin(args,owner);
        if(!Boolean.TRUE.equals(begun.get("ok"))||(!args.containsKey("package")&&!args.containsKey("url")&&!args.containsKey("intent")))return begun;
        if(!(begun.get("display") instanceof Number d))return begun;
        int display=d.intValue();
        java.util.concurrent.locks.ReentrantLock turn=displayLock(display);
        try{
            if(!turn.tryLock(Math.max(0,Math.min(ActionDeadline.remaining(),660000)),java.util.concurrent.TimeUnit.MILLISECONDS))return begun;
            try(var scope=device.accessibilityScope()){
                var openArgs=new LinkedHashMap<String,Object>();
                openArgs.put("display",display);openArgs.put("sessionId",begun.get("sessionId"));
                if(args.containsKey("package"))openArgs.put("package",args.get("package"));
                if(args.containsKey("url"))openArgs.put("url",args.get("url"));
                if(args.containsKey("intent"))openArgs.put("intent",args.get("intent"));
                if(Boolean.TRUE.equals(args.get("timing")))openArgs.put("timing",true);
                Map<String,Object> opened=evidence.run("android.open",openArgs);
                if(Boolean.TRUE.equals(opened.get("inputDelivered"))){
                    try{opened.put("activityTasks",tasksOnDisplay(display));}catch(RuntimeException ignored){}
                }
                Map<String,Object> merged=new LinkedHashMap<>(opened);
                for(String k:List.of("sessionId","mode","display","leaseExpiresAt","temporaryDirectory"))if(begun.containsKey(k))merged.put(k,begun.get(k));
                return merged;
            }finally{if(turn.isHeldByCurrentThread())turn.unlock();}
        }catch(InterruptedException e){Thread.currentThread().interrupt();return begun;}
    }
    private Map<String,Object> recordDisplay(Map<String,Object> args){
        int display=((Number)args.get("display")).intValue();
        if(display<=0)throw new InputNotSentException("private_display_required","Video recording runs on a private virtual display session.");
        String op=Json.string(args,"operation");
        if("start".equals(op)){
            requireDisplayAllowed(display);
            Map<String,Object> req=new LinkedHashMap<>();
            req.put("displayId",display);
            if(args.get("maxSeconds") instanceof Number n)req.put("maxSeconds",n.intValue());
            if(args.get("fps") instanceof Number n)req.put("fps",n.intValue());
            if(args.get("scale") instanceof Number n)req.put("scale",n.floatValue());
            return device.virtualDisplay("record_start",req);
        }
        if("stop".equals(op))return device.virtualDisplay("record_stop",Map.of("displayId",display));
        return device.virtualDisplay("record_status",Map.of("displayId",display));
    }
    private Map<String,Object> confirmAction(Map<String,Object> args,String owner){
        int display=((Number)args.get("display")).intValue();
        String sessionId=Json.string(args,"sessionId");
        String title=Json.string(args,"title");
        String summary=Json.string(args,"summary");
        int timeoutMs=GesturePlan.integer(args,"timeoutMs",60_000,5_000,120_000);
        requireDisplayAllowed(display);
        String comp=focusComponent(display);
        String pkg=privacyFence.packageOfComponent(comp);
        String imgB64="",imgMime="image/webp";
        try{
            Map<String,Object> shot=screenshot(display,0.4f);
            if(shot.get("pngBase64") instanceof String b64)imgB64=b64;
            if(shot.get("mime") instanceof String m)imgMime=m;
        }catch(RuntimeException ignored){}
        PendingConfirmations.Item item=confirmations.create(sessionId,display,owner,title,summary,pkg,imgB64,imgMime,timeoutMs);
        try{
            device.exec("am broadcast -a com.androidbackstage.app.CONFIRM_REQUEST -p "+ShellCommand.quote(selfPackage)
                    +" --es confirmId "+ShellCommand.quote(item.confirmId)
                    +" --es title "+ShellCommand.quote(title)
                    +" --es summary "+ShellCommand.quote(summary==null?"":summary)
                    +" --es sessionId "+ShellCommand.quote(sessionId)
                    +" --ei displayId "+display,3000);
        }catch(RuntimeException ignored){}
        String decision=confirmations.awaitDecision(item.confirmId,timeoutMs);
        boolean handedOff=false;
        if("handoff".equals(decision)&&display>0){
            try{
                Map<String,Object> moved=vdHandoff(display,null,null);
                handedOff=Boolean.TRUE.equals(moved.get("handedOff"));
            }catch(RuntimeException ignored){}
        }
        return Json.mapOf("ok",true,"confirmId",item.confirmId,"decision",decision,"handedOff",handedOff,"inputDelivered",false);
    }
    private Map<String,Object> personalShortcut(Map<String,Object> args){
        String op=Json.string(args,"operation");
        if("inbox".equals(op)){
            if(Boolean.TRUE.equals(args.get("clear"))){inbox.clear();return Json.mapOf("ok",true,"cleared",true,"items",List.of());}
            String removeId=Json.string(args,"itemId");
            if(removeId!=null&&!removeId.isBlank()){boolean removed=inbox.remove(removeId);return Json.mapOf("ok",true,"removed",removed,"itemId",removeId);}
            int limit=GesturePlan.integer(args,"limit",10,1,50);
            List<Map<String,Object>> items=inbox.list(limit);
            return Json.mapOf("ok",true,"count",items.size(),"items",items);
        }
        return device.interaction("personal",args);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> eventSettings(Map<String,Object> args){
        String op=Json.string(args,"operation");
        if("rules".equals(op)){
            Map<String,Object> rules=daemonConfig==null?Map.of():new LinkedHashMap<>(daemonConfig.eventRules);
            return Json.mapOf("ok",true,"rules",rules);
        }
        if("set_rules".equals(op)){
            if(daemonConfig==null)return Json.mapOf("ok",false,"code","unavailable","error","Event rules require daemon config.");
            Map<String,Object> next=new LinkedHashMap<>();
            if(args.get("enabled") instanceof Boolean b)next.put("enabled",b);else next.put("enabled",true);
            if(args.get("notificationPackages") instanceof List<?> pkgs){
                List<String> clean=new ArrayList<>();
                for(Object p:pkgs)if(p instanceof String s&&!s.isBlank()&&!privacyFence.isBlocked(s))clean.add(s.trim());
                next.put("notificationPackages",clean);
            }
            if(args.get("senderContains") instanceof String s&&!s.isBlank())next.put("senderContains",s.trim());
            if(args.get("textContains") instanceof String s&&!s.isBlank())next.put("textContains",s.trim());
            if(args.get("batteryBelow") instanceof Number n)next.put("batteryBelow",Math.max(5,Math.min(95,n.intValue())));
            daemonConfig.eventRules=next;
            try{daemonConfig.save();}catch(Exception e){return Json.mapOf("ok",false,"code","save_failed","error",e.getMessage());}
            Runnable hook=eventRulesChanged;if(hook!=null)try{hook.run();}catch(RuntimeException ignored){}
            return Json.mapOf("ok",true,"rules",next);
        }
        return Json.mapOf("ok",false,"code","invalid_argument","error","Unsupported event operation: "+op);
    }
    private Map<String,Object> resourceCall(String operation,Map<String,Object> args){return switch(operation){
        case "display_status" -> device.virtualDisplay("status",args.get("displayId") instanceof Number d?Map.of("displayId",d.intValue()):Map.of());
        case "display_start" -> vdStart(args);case "display_stop" -> {var out=vdStop(args.get("displayId") instanceof Number d?d.intValue():null);if(out.get("displayId") instanceof Number d){evidence.observations().clear(d.intValue());lastUnfreezeAt.remove(d.intValue());}yield out;}
        case "foreground_status","foreground_start","foreground_renew","foreground_stop" -> {var options=new LinkedHashMap<>(args);options.put("operation",switch(operation){case "foreground_start"->"begin";case "foreground_renew"->"renew";case "foreground_stop"->"finish";default->"status";});var result=device.interaction("screen_control",options);if(operation.equals("foreground_stop"))evidence.observations().clear(0);yield result;}
        case "activities" -> taskStatus(args);default -> throw new IllegalArgumentException("Unknown resource operation");};}
    private Map<String,Object> callRaw(String name,Map<String,Object> args){return switch(name){
        case "target" -> {Integer d=display(args);if(d!=null)requireDisplayAllowed(d);yield device.accessibility("target",args);}
        case "android.open" -> openPrimitive(args);
        case "android.tap" -> {prepareUi(args);Integer d=display(args);if(d!=null)requireDisplayAllowed(d);yield args.get("holdMs") instanceof Number hold&&hold.intValue()>0?gesture("long_press",Json.mapOf("display",args.get("display"),"x",args.get("x"),"y",args.get("y"),"durationMs",hold)):tapCoords(args);}
        case "android.key" -> {prepareUi(args);Integer d=display(args);if(d!=null)requireDisplayAllowed(d);yield device.interaction("key",args);}
        case "android.type" -> {prepareUi(args);Integer d=display(args);if(d!=null)requireDisplayAllowed(d);yield device.interaction("paste_text",args);}
        case "android.gesture" -> {prepareUi(args);Integer d=display(args);if(d!=null)requireDisplayAllowed(d);var options=new LinkedHashMap<>(args);String preset=Json.string(args,"preset");if(preset.equals("swipe"))options.put("holdMs",0);yield gesture(preset.equals("paths")?"multi_touch":preset.equals("swipe")?"drag":preset,options);}
        case "android.devices" -> {var result=status();if("debug".equals(args.get("detail")))result.put("device",device.interaction("device_summary",Map.of()));yield result;}
        case "android.location" -> device.interaction("location",args);
        case "android.media" -> device.interaction("media",args);
        case "android.audio" -> {if(args.get("package") instanceof String p)privacyFence.requireAllowed(p);yield device.interaction("audio",args);}
        case "android.device_control" -> device.interaction("device_control",args);
        case "android.clipboard" -> device.interaction("clipboard",args);
        case "android.notification" -> device.interaction("notification",args);
        case "android.shell" -> shell(Json.string(args,"command"));
        case "android.packages" -> packages(args);
        case "android.logs" -> switch(Observations.str(args.get("kind"))){
            case "crash" -> crashes(Json.string(args,"package"),Json.integer(args,"max",5),Json.string(args,"since"));
            case "jank" -> jankStats(Json.string(args,"package"));
            case "anr" -> anrRecords(Json.string(args,"package"),Json.integer(args,"max",5));
            default -> logcat(Json.string(args,"package"),Json.integer(args,"maxLines",80));
        };
        case "android.install" -> installSource(PrimitiveArguments.map(args.get("source")));
        case "android.maintenance" -> {var source=PrimitiveArguments.map(args.get("source"));yield selfUpdate(Json.string(source,"path"),Json.string(source,"url"),Json.string(source,"fileKey"),Json.string(source,"sha256"));}
        case "android.developer" -> {
            String pkg=Json.string(args,"package");if(pkg!=null&&!pkg.isBlank())privacyFence.requireAllowed(pkg);
            yield switch(Json.string(args,"operation")){
                case "stop"->stop(pkg);
                case "uninstall"->uninstall(pkg);
                case "grant"->grant(pkg,Json.string(args,"permission"));
                case "clear_data"->clearData(pkg);
                case "instrument"->{
                    String comp=Json.string(args,"component");
                    if(comp!=null&&!comp.isBlank())privacyFence.requireComponentAllowed(comp);
                    yield instrument(comp,Json.string(args,"class"),Json.string(args,"args"),Json.integer(args,"timeoutMs",120000));
                }
                default->throw new IllegalArgumentException("Unknown developer operation");
            };
        }
        case "android.handoff" -> vdHandoff(((Number)args.get("display")).intValue(),Json.string(args,"package"),args.containsKey("activityTaskId")?((Number)args.get("activityTaskId")).intValue():null);
        case "android.pull" -> device.pull(Json.string(args,"path"),args.get("offset") instanceof Number n?n.longValue():null,args.get("length") instanceof Number n?n.intValue():null,Json.string(args,"fileId"));
        case "android.push" -> push(Json.string(args,"local"),Json.string(args,"remote"));
        case "android.fetch" -> fetch(Json.string(args,"url"),Json.string(args,"remote"),Json.string(args,"fileKey"),Json.string(args,"sha256"));
        default -> throw new IllegalArgumentException("Unknown tool: "+name);
    };}
    private Map<String,Object> installSource(Map<String,Object> source){return source.containsKey("url")?installUrl(Json.string(source,"url"),Json.string(source,"fileKey"),Json.string(source,"sha256")):install(Json.string(source,"path"));}
    private Map<String,Object> openPrimitive(Map<String,Object> args){
        int display=((Number)args.get("display")).intValue();wakeIfNeeded(display);
        if(args.containsKey("package"))privacyFence.requireAllowed(Json.string(args,"package"));
        if(args.containsKey("activityTaskId")){
            int id=((Number)args.get("activityTaskId")).intValue();
            var match=tasksOnDisplay(display).stream().filter(t->java.util.Objects.equals(t.get("activityTaskId"),id)).findFirst().orElse(null);
            if(match==null)throw new InputNotSentException("task_not_owned","Choose an activityTaskId from this session's task_inspect result.");
            if(match.get("package") instanceof String tp)privacyFence.requireAllowed(tp);
            var out=device.interaction("front_task",Map.of("taskId",id,"display",display));
            out.put("activityTaskId",id);out.put("inputDelivered",Boolean.TRUE.equals(out.get("ok")));return out;
        }
        if(args.containsKey("share")){
            var share=PrimitiveArguments.map(args.get("share"));
            if(share.get("package") instanceof String sp)privacyFence.requireAllowed(sp);
            return device.interaction("share",args);
        }
        if(args.containsKey("intent")){
            var intentMap=PrimitiveArguments.map(args.get("intent"));
            requireIntentTargetAllowed(Json.string(intentMap,"action"),Json.string(intentMap,"data"),Json.string(intentMap,"package"),Json.string(intentMap,"component"));
            var launched=device.exec(IntentCommand.build(intentMap,display));if(!launched.ok()||launched.stdoutText().contains("Error:"))return Json.mapOf("ok",false,"inputDelivered",null,"code","input_outcome_unknown","error","Android did not confirm the launch. Inspect the session before opening it again.");return resultMap(launched);
        }
        if(args.containsKey("url")){
            String u=Json.string(args,"url");
            requireIntentTargetAllowed("android.intent.action.VIEW",u,null,null);
            return intent(Json.mapOf("action","android.intent.action.VIEW","data",u,"display",display));
        }
        if(args.containsKey("settingsAction")){
            String sa=Json.string(args,"settingsAction");
            requireIntentTargetAllowed(sa,null,null,null);
            return intent(Json.mapOf("action",sa,"display",display));
        }
        if(args.containsKey("activity"))return launch(Json.string(args,"package"),Json.string(args,"activity"),display);
        return open(Json.string(args,"package"),display);
    }
    private Map<String,Object> packages(Map<String,Object> args){
        String pkg=Json.string(args,"package");
        if(pkg!=null&&!pkg.isBlank())privacyFence.requireAllowed(pkg);
        var options=new LinkedHashMap<>(args);if("debug".equals(args.get("detail")))options.put("diagnostics",true);ResultFields.packageInfo(Map.of(),options);
        return device.interaction("packages",options);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>(device.status());
        ProcessResult blob = device.exec(
                "id; getprop ro.build.version.sdk; getprop ro.product.model; getprop ro.serialno");
        String[] lines = blob.trimmedStdout().split("\\n", -1);
        String idLine = lines.length > 0 ? lines[0].trim() : "";
        status.put("id", idLine);
        status.put("uid2000", idLine.contains("uid=2000"));
        try {
            status.put("sdk", lines.length > 1 ? Integer.parseInt(lines[1].trim()) : null);
        } catch (RuntimeException e) {
            status.put("sdk", null);
        }
        status.put("model", lines.length > 2 ? lines[2].trim() : "");
        status.put("serial", lines.length > 3 ? lines[3].trim() : "");
        status.put("capabilities", Json.mapOf(
                "shell", true, "install_packages", true, "input_injection", true,
                "screenshots", true, "logcat", true, "intents", true, "clipboard", true,
                "notifications", true, "settings", true, "virtual_display", true, "root", false,
                "arbitrary_app_data", false, "powered_off", false));
        return status;
    }

    /**
     * Identity for relay hello. Uses this process only — never a round-trip.
     */
    public Map<String, Object> identity() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", android.os.Build.MODEL);
        out.put("serial", android.os.Build.DEVICE + "/" + android.os.Build.HARDWARE);
        out.put("sdk", android.os.Build.VERSION.SDK_INT);
        return out;
    }

    public boolean allowShell() {
        return allowShell;
    }

    public synchronized boolean setAllowShell(boolean enabled) {
        this.allowShell = enabled;
        if (daemonConfig != null) {
            daemonConfig.allowShell = enabled;
            try { daemonConfig.save(); } catch (Exception ignored) {}
        }
        return this.allowShell;
    }

    public Map<String, Object> shell(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        if (!allowShell) {
            throw new InputNotSentException("shell_disabled", "Shell commands are off on this phone. Turn on Allow shell commands in Android Backstage Settings.");
        }
        ProcessResult process=device.exec(command);
        Map<String,Object> result=resultMap(process);
        result.put("commandSucceeded",process.ok());
        if(process.exitCode>=0) {
            result.put("ok",true);result.put("outcome","command_exited");result.remove("error");
        } else {
            result.put("outcome","unknown");result.put("inputDelivered",null);
        }
        return result;
    }

    public Map<String, Object> open(String query) {
        return open(query, null);
    }

    public Map<String, Object> open(String query, Integer display) {
        ShellCommand.requirePackage(query);
        String pkg=query;
        try {
            Map<String, Object> resolved = device.interaction("resolve_launcher", Map.of("package", pkg));
            if (Boolean.TRUE.equals(resolved.get("ok"))
                    && resolved.get("package") instanceof String p && !p.isBlank()
                    && resolved.get("activity") instanceof String a && !a.isBlank()) {
                return launch(p, a, display);
            }
        } catch (RuntimeException ignored) {
        }
        ProcessResult resolved = device.exec("cmd package resolve-activity --brief " + ShellCommand.quote(pkg));
        Matcher act = Pattern.compile("([A-Za-z0-9._]+)/(\\S+)").matcher(resolved.stdoutText());
        if (act.find()) return launch(act.group(1), act.group(2), display);
        if (display != null) {
            throw new IllegalArgumentException("no launcher activity for " + pkg + "; monkey cannot target display " + display);
        }
        ProcessResult monkey = device.exec("monkey -p " + ShellCommand.quote(pkg) + " -c android.intent.category.LAUNCHER 1");
        Map<String, Object> out = resultMap(monkey);
        out.put("package", pkg);
        out.put("method", "monkey");
        return out;
    }

    public Map<String, Object> launch(String packageName, String activity) {
        return launch(packageName, activity, null);
    }

    public Map<String, Object> launch(String packageName, String activity, Integer display) {
        ShellCommand.requirePackage(packageName);
        if (activity == null || activity.isBlank()) throw new IllegalArgumentException("activity is required");
        ProcessResult result = device.exec(ShellCommand.amStart(packageName, activity, display));
        Map<String, Object> out = resultMap(result);
        out.put("component", ShellCommand.component(packageName, activity));
        if (display != null) out.put("display", display);
        if (!result.ok() || result.stdoutText().toLowerCase(Locale.US).contains("error")) {
            throw new CommandFailedException(result);
        }
        return out;
    }

    // ---- virtual display -------------------------------------------------

    public Map<String, Object> vdStart(Map<String, Object> args) {
        Map<String, Object> req = new LinkedHashMap<>();
        int width = Json.integer(args, "width", 0);
        int height = Json.integer(args, "height", 0);
        int dpi = Json.integer(args, "dpi", 0);
        if (width > 0) req.put("width", width);
        if (height > 0) req.put("height", height);
        if (dpi > 0) req.put("dpi", dpi);
        int idleMinutes = Json.integer(args, "idleMinutes", -1);
        if (idleMinutes >= 0) req.put("idleMinutes", idleMinutes);
        if (args != null && args.get("sessionId") instanceof String session) req.put("sessionId", session);
        Object decorations = args == null ? null : args.get("decorations");
        if (decorations != null) req.put("decorations", !"false".equalsIgnoreCase(String.valueOf(decorations)));
        Map<String, Object> out = device.virtualDisplay("start", req);
        requireVd(out);
        out.put("hint", "pass display=" + out.get("displayId") + " to open/tap/tap_text/type/swipe/key/ui_tree/screenshot");
        return out;
    }

    /** Stops one private display; without an id, every private display. */
    public Map<String, Object> vdStop(Integer displayId) {
        return device.virtualDisplay("stop", displayId == null ? Map.of() : Map.of("displayId", displayId));
    }

    public Map<String, Object> vdStatus(Integer displayId) {
        Map<String, Object> out = device.virtualDisplay("status", displayId == null ? Map.of() : Map.of("displayId", displayId));
        Integer id = displayIdOf(out);
        if (id != null) {
            out.put("tasks", tasksOnDisplay(id));
        }
        return out;
    }

    /**
     * The "last second" hand-off: move a task from the virtual display to the
     * real screen. Everything before this (search, navigation, login) happened
     * out of sight; only the step the user must see lands on the real screen.
     */
    public Map<String,Object> vdHandoff(int display,String packageName,Integer taskId) {
        Integer id=displayIdOf(device.virtualDisplay("touch",Map.of("displayId",display)));
        if(id==null)throw new IllegalStateException("This session's display is not active; nothing to hand off");
        Map<String,Object> pick=TaskSnapshot.select(tasksOnDisplay(id),packageName,taskId);
        int root=((Number)pick.get("rootTaskId")).intValue();
        // Never launch the component again: it can create a different task and lose prepared state.
        ProcessResult moved=device.exec(ShellCommand.moveTaskToDisplay(root,0));
        if(!moved.ok())return resultMap(moved);
        Map<String,Object> front=device.interaction("front_task",Map.of("taskId",pick.get("activityTaskId")));
        if(!Boolean.TRUE.equals(front.get("ok")))return Json.mapOf("ok",false,"moved",true,"handedOff",false,"fromDisplay",id,"toDisplay",0,"task",pick,"front",front);
        List<Map<String,Object>> after=tasksOnDisplay(0);
        boolean found=after.stream().anyMatch(t -> pick.get("activityTaskId").equals(t.get("activityTaskId")));
        return Json.mapOf("ok",found,"handedOff",found,"task",pick,"fromDisplay",id,"toDisplay",0,
                "error",found?null:"Android did not report the selected task on the phone screen; do not repeat the move blindly");
    }

    // ---- dev loop: launch check, crash triage, tests, self-update ----------

    /** Install-launch-did-it-crash in one call, all on the virtual display. */

    public Map<String, Object> crashes(String packageName, int max, String since) {
        if (since != null && !since.isBlank() && !since.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?")) {
            throw new IllegalArgumentException("since must look like MM-DD HH:MM:SS.mmm (device local time)");
        }
        if (packageName != null && !packageName.isBlank()) {
            ShellCommand.requirePackage(packageName);
            privacyFence.requireAllowed(packageName);
        }
        List<Map<String, Object>> list = collectCrashes(packageName, max <= 0 ? 5 : max, since);
        if (privacyFence.count() > 0) {
            list.removeIf(c -> isBlockedRecord(c));
        }
        return Json.mapOf("ok", true, "count", list.size(), "crashes", list, "package", packageName, "since", since);
    }

    private boolean isBlockedRecord(Map<String, Object> record) {
        if (record == null || privacyFence.count() == 0) return false;
        for (String key : List.of("package", "process")) {
            Object v = record.get(key);
            if (v instanceof String s && !s.isBlank()) {
                String base = s.split("[:\\s/]", 2)[0].trim();
                if (privacyFence.isBlocked(base)) return true;
            }
        }
        return false;
    }

    /** Dropbox first (immune to log.tag=S ROMs), logcat crash buffer as the fallback. */
    private List<Map<String, Object>> collectCrashes(String packageName, int max, String since) {
        List<Map<String, Object>> list = parseDropbox(device.exec(ShellCommand.dropboxCrashes()).stdoutText(), packageName, max, since);
        if (!list.isEmpty()) return list;
        String cmd = ShellCommand.crashLog();
        if (since != null && !since.isBlank()) cmd += " -T " + ShellCommand.quote(since);
        return parseCrashes(device.exec(cmd).stdoutText(), packageName, max);
    }

    private static final Pattern DROPBOX_HEADER = Pattern.compile("^\\d{4}-(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) (data_app_\\w+) \\(");

    /** Newest-first records from {@code dumpsys dropbox --print data_app_*}. */
    static List<Map<String, Object>> parseDropbox(String text, String packageName, int max, String since) {
        List<Map<String, Object>> all = new ArrayList<>();
        if (text == null) return all;
        String sinceKey = since == null || since.isBlank() ? null : since.substring(0, Math.min(since.length(), 14));
        for (String block : text.split("(?m)^={20,}\\s*$")) {
            String[] lines = block.strip().split("\\R");
            if (lines.length == 0) continue;
            Matcher h = DROPBOX_HEADER.matcher(lines[0].strip());
            if (!h.find()) continue;
            String time = h.group(1);
            if (sinceKey != null && time.compareTo(sinceKey) < 0) continue;
            Map<String, Object> crash = new LinkedHashMap<>();
            crash.put("time", time);
            crash.put("source", h.group(2));
            if (h.group(2).contains("native")) crash.put("native", true);
            if (h.group(2).contains("anr")) crash.put("anr", true);
            int i = 1;
            for (; i < lines.length && !lines[i].isBlank(); i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) continue;
                String key = lines[i].substring(0, colon).trim();
                String value = lines[i].substring(colon + 1).trim();
                if (key.equals("Process")) crash.put("process", value);
                else if (key.equals("PID")) crash.put("pid", value);
                else if (key.equals("Package")) crash.put("package", value);
                else if (key.equals("Foreground")) crash.put("foreground", value.equalsIgnoreCase("yes"));
            }
            String exception = null;
            List<String> frames = new ArrayList<>();
            boolean afterCause = false;
            for (; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.isEmpty()) continue;
                if (exception == null) {
                    exception = t;
                } else if (t.startsWith("Caused by:")) {
                    // The root cause is what the developer needs; keep it even past the frame cap.
                    frames.add(t);
                    afterCause = true;
                } else if (t.startsWith("at ") || t.startsWith("#")) {
                    if (frames.size() < 8 || afterCause) frames.add(t);
                    afterCause = false;
                }
            }
            crash.put("exception", exception);
            crash.put("frames", frames);
            String process = String.valueOf(crash.getOrDefault("process", ""));
            if (packageName != null && !packageName.isBlank() && !process.startsWith(packageName)) continue;
            all.add(crash);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int j = all.size() - 1; j >= 0 && out.size() < max; j--) out.add(all.get(j));
        return out;
    }

    private static final Pattern LOG_LINE = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+?)\\s*:\\s?(.*)$");

    /** Newest-first crash records from {@code logcat -b crash}: Java FATAL EXCEPTIONs and native tombstone headers. */
    static List<Map<String, Object>> parseCrashes(String text, String packageName, int max) {
        List<List<String[]>> records = new ArrayList<>();
        List<String[]> current = null;
        for (String raw : text == null ? new String[0] : text.split("\\R")) {
            Matcher m = LOG_LINE.matcher(raw);
            if (!m.matches()) continue;
            String msg = m.group(6);
            boolean start = msg.startsWith("FATAL EXCEPTION") || msg.startsWith("*** *** ***");
            if (start || current == null) {
                current = new ArrayList<>();
                records.add(current);
            }
            current.add(new String[]{m.group(1), m.group(2), m.group(5).trim(), msg});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < max; i--) {
            Map<String, Object> crash = summarizeCrash(records.get(i));
            if (crash == null) continue;
            String process = String.valueOf(crash.getOrDefault("process", ""));
            if (packageName != null && !packageName.isBlank() && !process.startsWith(packageName)) continue;
            out.add(crash);
        }
        return out;
    }

    private static Map<String, Object> summarizeCrash(List<String[]> lines) {
        if (lines.isEmpty()) return null;
        Map<String, Object> crash = new LinkedHashMap<>();
        crash.put("time", lines.get(0)[0]);
        crash.put("pid", lines.get(0)[1]);
        String process = null;
        String exception = null;
        List<String> frames = new ArrayList<>();
        boolean sawProcess = false;
        for (String[] line : lines) {
            String msg = line[3];
            Matcher proc = Pattern.compile("^Process: ([^,\\s]+)").matcher(msg);
            Matcher nat = Pattern.compile(">>> ([^\\s]+) <<<").matcher(msg);
            if (proc.find()) {
                process = proc.group(1);
                sawProcess = true;
                continue;
            }
            if (nat.find()) {
                process = nat.group(1);
                crash.put("native", true);
                continue;
            }
            if (exception == null && sawProcess && !msg.isBlank() && !msg.startsWith("\tat ") && !msg.startsWith("at ")) {
                exception = msg.trim();
                continue;
            }
            if (exception == null && msg.startsWith("signal ")) {
                exception = msg.trim();
                continue;
            }
            String t = msg.trim();
            if ((t.startsWith("at ") || t.startsWith("#") || t.startsWith("Caused by:")) && frames.size() < 8) frames.add(t);
        }
        if (process == null && exception == null && frames.isEmpty()) return null;
        crash.put("process", process);
        crash.put("exception", exception);
        crash.put("frames", frames);
        return crash;
    }

    public Map<String, Object> instrument(String component, String testClass, String extraArgs, int timeoutMs) {
        if (component == null || !component.matches("[A-Za-z0-9._]+/[A-Za-z0-9._$]+")) {
            throw new IllegalArgumentException("component must be testPackage/RunnerClass");
        }
        if (testClass != null && !testClass.isBlank() && !testClass.matches("[A-Za-z0-9._$#,]+")) {
            throw new IllegalArgumentException("invalid test class filter");
        }
        long budget = Math.max(10_000L, Math.min(timeoutMs, 600_000L));
        ProcessResult result = device.exec(ShellCommand.instrument(component, testClass, extraArgs), budget);
        Map<String, Object> out = parseInstrument(result.stdoutText() + "\n" + result.stderrText());
        out.put("exitCode", result.exitCode);
        out.put("command", result.command);
        return out;
    }

    /** Parses {@code am instrument -r} raw status output into counts and failures. */
    static Map<String, Object> parseInstrument(String text) {
        Map<String, String> status = new LinkedHashMap<>();
        StringBuilder pendingValue = null;
        String pendingKey = null;
        int passed = 0, failed = 0, errors = 0, ignored = 0, assumption = 0, started = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Integer code = null;
        String instrumentationFailed = null;
        String resultStream = null;
        for (String line : text == null ? new String[0] : text.split("\\R")) {
            Matcher st = Pattern.compile("^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$").matcher(line);
            Matcher sc = Pattern.compile("^INSTRUMENTATION_STATUS_CODE: (-?\\d+)$").matcher(line);
            Matcher rs = Pattern.compile("^INSTRUMENTATION_RESULT: ([^=]+)=(.*)$").matcher(line);
            Matcher cd = Pattern.compile("^INSTRUMENTATION_CODE: (-?\\d+)$").matcher(line);
            // am prints this inside "onError: commandError=true message=INSTRUMENTATION_FAILED: x/y".
            Matcher fl = Pattern.compile("INSTRUMENTATION_FAILED: (\\S+)").matcher(line);
            if (st.matches()) {
                if (pendingKey != null) status.put(pendingKey, pendingValue.toString());
                pendingKey = st.group(1).trim();
                pendingValue = new StringBuilder(st.group(2));
            } else if (sc.matches()) {
                if (pendingKey != null) status.put(pendingKey, pendingValue.toString());
                pendingKey = null;
                int c = Integer.parseInt(sc.group(1));
                switch (c) {
                    case 1 -> started++;
                    case 0 -> passed++;
                    case -1 -> errors++;
                    case -2 -> failed++;
                    case -3 -> ignored++;
                    case -4 -> assumption++;
                    default -> { }
                }
                if ((c == -1 || c == -2) && status.get("test") != null) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("class", status.get("class"));
                    f.put("test", status.get("test"));
                    f.put("code", c);
                    String stack = status.getOrDefault("stack", "");
                    f.put("stack", stack.length() > 1500 ? stack.substring(0, 1500) + "…" : stack);
                    failures.add(f);
                }
                status.remove("stack");
            } else if (rs.matches()) {
                if (pendingKey != null) status.put(pendingKey, pendingValue.toString());
                pendingKey = null;
                if (rs.group(1).trim().equals("stream")) resultStream = rs.group(2);
            } else if (cd.matches()) {
                code = Integer.parseInt(cd.group(1));
            } else if (fl.find()) {
                instrumentationFailed = fl.group(1).trim();
            } else if (pendingKey != null) {
                pendingValue.append('\n').append(line);
            } else if (resultStream != null && !line.startsWith("INSTRUMENTATION_")) {
                resultStream += "\n" + line;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ran = started > 0 && instrumentationFailed == null;
        out.put("ok", ran && failed == 0 && errors == 0 && (code == null || code == -1));
        out.put("ran", ran);
        out.put("tests", started);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("errors", errors);
        out.put("ignored", ignored);
        out.put("assumptionFailures", assumption);
        out.put("failures", failures);
        out.put("instrumentationCode", code);
        if (instrumentationFailed != null) out.put("instrumentationFailed", instrumentationFailed);
        if (status.containsKey("Error")) out.put("error", status.get("Error"));
        if (resultStream != null) out.put("stream", resultStream.trim());
        return out;
    }

    public Map<String, Object> selfUpdate(String path, String url) {
        return selfUpdate(path, url, null, null);
    }

    public Map<String, Object> selfUpdate(String path, String url, String fileKey, String sha256) {
        device.exec("mkdir -p " + ShellCommand.quote(Starter.DIR));
        String remote = Starter.UPDATE_APK;
        if (url != null && !url.isBlank()) {
            stageDownload(url, remote, fileKey, sha256, true);
        } else if (path != null && !path.isBlank()) {
            if (Files.isRegularFile(java.nio.file.Paths.get(path))) {
                device.push(path, remote);
            } else {
                device.exec("cp " + ShellCommand.quote(path) + " " + ShellCommand.quote(remote)).requireOk();
            }
        } else {
            throw new IllegalArgumentException("path or url is required");
        }
        String size = device.exec("stat -c %s " + ShellCommand.quote(remote)
                + " 2>/dev/null || wc -c < " + ShellCommand.quote(remote)).trimmedStdout();
        if (size.isEmpty() || size.equals("0")) throw new IllegalStateException("staged APK is empty: " + remote);

        Map<String, Object> info = device.interaction("apk_info", Map.of("path", remote));
        if (!Boolean.TRUE.equals(info.get("ok")) || !selfPackage.equals(info.get("package"))) {
            device.exec("rm -f " + ShellCommand.quote(remote));
            throw new IllegalStateException("Refusing self-update: APK package does not match " + selfPackage);
        }
        String apkCert = Json.string(info, "certSha256");
        String installedCert = Json.string(info, "installedCertSha256");
        if (apkCert != null && !apkCert.isBlank() && installedCert != null && !installedCert.isBlank()
                && !apkCert.equalsIgnoreCase(installedCert)) {
            device.exec("rm -f " + ShellCommand.quote(remote));
            throw new IllegalStateException("Refusing self-update: APK signing certificate does not match the installed app.");
        }

        // Stop taking relay jobs: anything started now would die with this process.
        com.deviceagent.rpc.DeviceNode.drain();
        ProcessResult install = device.exec(
                ShellCommand.installRelease(remote), 180_000L);
        if (!install.ok() && !install.stdoutText().toLowerCase(Locale.US).contains("success")) {
            throw new CommandFailedException(install);
        }
        String pmPath = device.exec("pm path " + ShellCommand.quote(selfPackage)).trimmedStdout();
        String newApk = pmPath.replaceFirst("^package:", "").trim();
        if (newApk.isEmpty()) {
            throw new IllegalStateException("pm path " + selfPackage + " returned nothing after install");
        }
        String log = Starter.LOG_PATH;
        // A package replacement may be denied background auto-launch by the OEM.
        // The permission-protected service accepts shell's explicit recovery
        // request and still checks the user's saved access-enabled state.
        ProcessResult supervision=device.exec("am start-foreground-service -n "+ShellCommand.quote(selfPackage+"/com.deviceagent.app.AgentService"),10000);
        boolean supervised=supervision.ok()&&!supervision.stdoutText().contains("Error");
        if(!supervised)supervised=Boolean.TRUE.equals(device.interaction("restore_supervision",Map.of()).get("restored"));
        // The reply must reach the caller before this process goes away. The new
        // daemon retries its bind for 30 s, so spawning it right after our exit is safe.
        String launch = Starter.command(newApk, configPath, log);
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            device.exec("sleep 1; " + launch + " echo scheduled", 10_000L);
            System.exit(0);
        }, "backstage-self-update-exit");
        exit.setDaemon(true);
        exit.start();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("command", launch);
        out.put("scheduled", true);
        out.put("supervisionRequested",supervised);
        out.put("apk", remote);
        out.put("bytes", size);
        out.put("log", log);
        out.put("expect", "inbound :18422 drops now and returns in a few seconds; poll /health, then read "
                + log + " with android.shell");
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tasksOnDisplay(int displayId) {
        try {
            Map<String, Object> reply = device.interaction("display_tasks", Map.of("display", displayId));
            if (Boolean.TRUE.equals(reply.get("ok")) && reply.get("tasks") instanceof List<?> list) {
                List<Map<String, Object>> copy = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) copy.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
                return copy;
            }
        } catch (RuntimeException ignored) {
        }
        return parseTasks(device.exec(ShellCommand.listTasks()).stdoutText(), displayId);
    }

    static List<Map<String,Object>> parseTasks(String text, int displayId) { return TaskSnapshot.parse(text,displayId); }

    private int requirePrivateDisplay(Map<String,Object> args) {
        int id=GesturePlan.integer(args,"display",-1,1,Integer.MAX_VALUE);
        Integer active=displayIdOf(device.virtualDisplay("touch",Map.of("displayId",id)));
        if(active==null || active!=id) throw new IllegalArgumentException("Use the display owned by this session");
        wakeIfNeeded(id);
        return id;
    }
    private int requireTaskDisplay(Map<String,Object> args){
        int id=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
        if(id!=0)return requirePrivateDisplay(args);
        if(!Boolean.TRUE.equals(device.interaction("screen_control",Map.of("operation","status")).get("active")))throw new InputNotSentException("screen_control_stopped","Screen control stopped. Begin a new task when the user is ready.");
        return 0;
    }
    private Map<String,Object> gesture(String op,Map<String,Object> args) {
        requireTaskDisplay(args);
        return device.interaction(op,args);
    }
    private Map<String,Object> waitFor(Map<String,Object> args) {
        UiPredicate.validate(args);
        GesturePlan.integer(args,"timeoutMs",5000,0,20000);
        requirePrivateDisplay(args);
        return device.accessibility("wait",args);
    }
    private Map<String,Object> taskStatus(Map<String,Object> args) {
        int id=requireTaskDisplay(args);
        List<Map<String,Object>> tasks=tasksOnDisplay(id);
        String focus=TaskSnapshot.focusedComponent(device.exec(ShellCommand.focusOnDisplay(id)).trimmedStdout());
        String pkg=Json.string(args,"package");
        tasks.removeIf(task -> pkg!=null&&!pkg.equals(task.get("package")));
        for(Map<String,Object> task:tasks) task.put("focusedComponent",focus);
        return Json.mapOf("ok",true,"display",id,"focusedComponent",focus,"activityTasks",tasks);
    }

    /**
     * The phone's power button sleeps every display group, including the virtual
     * display's, and nothing wakes it again by itself. Any tool aimed at the
     * virtual display first makes sure it is ON; display 0 is never touched.
     */
    private void wakeIfNeeded(Integer display) {
        if (display == null || display == 0) return;
        Map<String, Object> status;
        try {
            // "touch" is status plus a reset of the idle-release timer.
            status = device.virtualDisplay("touch", Map.of("displayId", display));
        } catch (RuntimeException e) {
            return;
        }
        Integer id = displayIdOf(status);
        if (id == null || !id.equals(display)) return;
        if ("ON".equals(String.valueOf(status.get("displayState")))) return;
        Map<String, Object> woke = device.virtualDisplay("wake", Map.of("displayId", display));
        if (!Boolean.TRUE.equals(woke.get("ok"))) {
            throw new IllegalStateException("virtual display " + display + " is " + status.get("displayState")
                    + " and could not be woken: " + woke.get("error") + " steps=" + woke.get("steps"));
        }
    }

    private void resumePrivateApp(int display, boolean force) {
        // Some OEMs freeze even the resumed app on a private display while the
        // handset sleeps. Such an app cannot answer accessibility requests.
        if(display<=0)return;
        long now = System.nanoTime() / 1_000_000L;
        if (!force) {
            Long prev = lastUnfreezeAt.get(display);
            if (prev != null && now - prev < 8_000L) return;
        }
        Map<String, Object> status = device.virtualDisplay("touch", Map.of("displayId", display));
        Integer owned = displayIdOf(status);
        if (owned == null || owned != display) return;
        if (!force && "ON".equals(String.valueOf(status.get("display0State")))) {
            lastUnfreezeAt.put(display, now);
            return;
        }
        String component = "";
        try {
            Map<String, Object> top = device.interaction("top_activity", Map.of("display", display));
            if (Boolean.TRUE.equals(top.get("ok")) && top.get("component") instanceof String c) component = c;
        } catch (RuntimeException ignored) {
        }
        if (component.isEmpty()) {
            component = TaskSnapshot.focusedComponent(
                    device.exec(ShellCommand.focusedAppOnDisplay(display), 2000).trimmedStdout());
        }
        int slash = component.indexOf('/');
        String pkg = slash > 0 ? component.substring(0, slash)
                : TaskSnapshot.singleVisiblePackage(tasksOnDisplay(display));
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return;
        String process=pkg;
        if(slash>0){var info=device.interaction("activity_process",Map.of("component",component));if(info.get("process") instanceof String name&&name.matches("[A-Za-z0-9_.:]+"))process=name;}
        if (!force) {
            try {
                Map<String, Object> frozen = device.interaction("process_frozen", Map.of("process", process));
                if (Boolean.TRUE.equals(frozen.get("ok")) && Boolean.FALSE.equals(frozen.get("frozen"))) {
                    lastUnfreezeAt.put(display, now);
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        // Non-sticky: Android remains free to freeze the app again after use.
        // Older Android versions may not support this command; still try the read.
        device.exec(ShellCommand.join("cmd", "activity", "unfreeze", process), 2000);
        lastUnfreezeAt.put(display, now);
    }
    private void prepareUi(Map<String,Object> args){Integer display=display(args);if(display!=null){wakeIfNeeded(display);resumePrivateApp(display, false);}}

    /** Start the display if needed, then launch there. The app never touches the phone screen. */

    public Map<String, Object> vdScreenshot(int display, Float scale) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("displayId", display);
        if (scale != null) req.put("scale", scale);
        Map<String, Object> shot = device.virtualDisplay("screenshot", req);
        requireVd(shot);
        Object png = shot.remove("png");
        if (!(png instanceof byte[]) || ((byte[]) png).length < 8) {
            throw new IllegalStateException("virtual display returned no image");
        }
        byte[] bytes = (byte[]) png;
        Object mime = shot.get("mime");
        shot.put("mime", mime instanceof String s && !s.isBlank() ? s : "image/png");
        shot.put("bytes", bytes.length);
        shot.put("pngBase64", Json.b64(bytes));
        return shot;
    }

    private static void requireVd(Map<String, Object> reply) {
        if (reply == null) throw new IllegalStateException("no reply from virtual display");
        if (Boolean.FALSE.equals(reply.get("ok"))) {
            Object attempts = reply.get("attempts");
            throw new IllegalStateException(String.valueOf(reply.get("error"))
                    + (attempts == null ? "" : " attempts=" + attempts));
        }
    }

    private static Integer displayIdOf(Map<String, Object> reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.get("active"))) return null;
        Object id = reply.get("displayId");
        return id instanceof Number ? ((Number) id).intValue() : null;
    }

    private static Integer display(Map<String, Object> args) {
        if (args == null || args.get("display") == null) return null;
        Object raw = args.get("display");
        if (raw instanceof Number) return ((Number) raw).intValue();
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || text.equalsIgnoreCase("null")) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("display must be an integer display id (from android.vd_start)");
        }
    }

    public Map<String, Object> stop(String packageName) {
        ShellCommand.requirePackage(packageName);
        return resultMap(device.exec(ShellCommand.forceStop(packageName)).requireOk());
    }

    public Map<String, Object> packageInfo(String packageName) {
        ShellCommand.requirePackage(packageName);
        ProcessResult result = device.exec(ShellCommand.dumpsysPackage(packageName)).requireOk();
        String section = packageSection(result.stdoutText(), packageName);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("package", first(PACKAGE_LINE, section, packageName));
        info.put("versionName", first(VERSION_NAME, section, null));
        String versionCode = first(VERSION_CODE, section, null);
        if (versionCode != null && versionCode.contains(" ")) versionCode = versionCode.split("\\s+")[0];
        info.put("versionCode", versionCode);
        info.put("debuggable", section.contains("DEBUGGABLE"));
        List<Map<String, Object>> permissions = new ArrayList<>();
        Matcher m = PERMISSION.matcher(section);
        while (m.find()) {
            permissions.add(Json.mapOf("name", m.group(1), "granted", Boolean.parseBoolean(m.group(2))));
        }
        info.put("permissions", permissions);
        return info;
    }

    public Map<String, Object> logcat(String packageName, int maxLines) {
        if (packageName != null && !packageName.isBlank()) {
            privacyFence.requireAllowed(packageName);
        }
        StringBuilder cmd = new StringBuilder("logcat -d -v threadtime");
        if (maxLines > 0) cmd.append(" -t ").append(maxLines);
        if (packageName != null && !packageName.isBlank()) cmd.append(" | grep -- ").append(ShellCommand.quote(packageName));
        ProcessResult result = device.exec(cmd.toString());
        String[] raw = result.stdoutText().isEmpty() ? new String[0] : result.stdoutText().split("\\R");
        List<String> filtered;
        if (privacyFence.count() > 0) {
            List<String> blocked = privacyFence.list();
            filtered = new ArrayList<>(raw.length);
            for (String line : raw) {
                boolean hide = false;
                for (String b : blocked) {
                    if (line.contains(b)) { hide = true; break; }
                }
                if (!hide) filtered.add(line);
            }
        } else {
            filtered = List.of(raw);
        }
        Map<String, Object> out = resultMap(result);
        out.put("lines", filtered);
        out.put("count", filtered.size());
        if (privacyFence.count() > 0) out.put("stdout", String.join("\n", filtered));
        return out;
    }

    public Map<String, Object> screenshot() {
        return screenshot(null, null);
    }

    public Map<String, Object> screenshot(Integer display, Float scale) {
        if(display!=null&&display==0)return device.interaction("screen_capture",Json.mapOf("display",0,"scale",scale==null?0.5:scale));
        if (display != null && display != 0) {
            Integer vd = displayIdOf(device.virtualDisplay("status", Map.of("displayId", display)));
            if (vd == null || !vd.equals(display)) {
                throw new IllegalArgumentException("display " + display + " is not an active private display; begin a task first");
            }
            return vdScreenshot(display, scale);
        }
        ProcessResult result = device.exec(ShellCommand.screenshot()).requireOk();
        if (result.stdout.length < 8 || result.stdout[0] != (byte) 0x89 || result.stdout[1] != 'P') {
            throw new IllegalStateException("screencap did not return a PNG");
        }
        return Json.mapOf("mime", "image/png", "bytes", result.stdout.length, "pngBase64", Json.b64(result.stdout));
    }

    private Map<String, Object> tapCoords(Map<String, Object> args) {
        int x = Json.integer(args, "x", -1), y = Json.integer(args, "y", -1);
        Integer disp = display(args);
        if (x < 0 || y < 0) throw new IllegalArgumentException("x and y must be >= 0");
        if (disp != null) {
            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("display", disp);
                req.put("x", x);
                req.put("y", y);
                if (args.containsKey("sessionId")) req.put("sessionId", args.get("sessionId"));
                Map<String, Object> reply = device.interaction("tap", req);
                if (reply != null && reply.containsKey("ok")) return reply;
            } catch (UnsupportedOperationException ignored) {
                // Fall back to shell input tap for mock/legacy executors.
            }
        }
        return tap(x, y, disp);
    }

    public Map<String, Object> tap(int x, int y) {
        return tap(x, y, null);
    }

    public Map<String, Object> tap(int x, int y, Integer display) {
        if (x < 0 || y < 0) throw new IllegalArgumentException("x and y must be >= 0");
        wakeIfNeeded(display);
        return withDisplay(resultMap(device.exec(ShellCommand.tap(x, y, display)).requireOk()), display);
    }

    /**
     * Accessibility read from the shell process: no uiautomator idle wait, so it
     * returns in well under a second even while the user is scrolling on the real
     * screen. Returns null when the accessibility host cannot do it, and the caller
     * falls back to uiautomator.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> uiTreeFast(int display,String windowId) {
        Map<String, Object> reply;
        try {
            var options=Json.mapOf("display", display,"all",true);if(windowId!=null)options.put("windowId",windowId);reply = device.accessibility("tree", options);
        } catch (RuntimeException e) {
            lastFastError = e.getMessage();
            return null;
        }
        if (reply == null || !Boolean.TRUE.equals(reply.get("ok"))) {
            lastFastError = reply == null ? "no reply" : String.valueOf(reply.get("error"));
            // "display N has no windows" is a real answer for a virtual display with nothing on it.
            if (reply != null && reply.get("displays") != null && display != 0) {
                throw new IllegalStateException(lastFastError + "; launch an app on it first (displays with windows: " + reply.get("displays") + ")");
            }
            return null;
        }
        List<Map<String, Object>> nodes;
        Object directNodes = reply.get("nodes");
        if (directNodes instanceof List) {
            nodes = (List<Map<String, Object>>) directNodes;
        } else {
            Object nodesJson = reply.get("nodesJson");
            if (!(nodesJson instanceof String)) return null;
            nodes = (List<Map<String, Object>>) (Object) Json.parse((String) nodesJson);
        }
        Map<String, Object> out = Json.mapOf("ok", true, "display", display, "count", nodes.size(), "nodes", nodes,
                "wokeDisplay", false, "source", "accessibility", "ms", reply.get("ms"));
        if (Boolean.TRUE.equals(reply.get("truncated"))) out.put("truncated", true);
        Object directWindows = reply.get("windows");
        if (directWindows instanceof List) {
            out.put("windows", directWindows);
        } else {
            Object windows = reply.get("windowsJson");
            if (windows instanceof String) out.put("windows", Json.parse((String) windows));
        }
        return out;
    }

    private volatile String lastFastError;

    /** Logical size of a display, for visibility checks. {0,0} when unknown. */

    private static Map<String, Object> withDisplay(Map<String, Object> out, Integer display) {
        if (display != null) out.put("display", display);
        return out;
    }

    public Map<String, Object> install(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        String remote = path;
        if (Files.isRegularFile(java.nio.file.Paths.get(path))) {
            remote = "/data/local/tmp/device-agent-install.apk";
            device.push(path, remote);
        } else if (path.startsWith("/sdcard/") || path.startsWith("/storage/")) {
            remote = "/data/local/tmp/device-agent-install.apk";
            device.exec("cp " + ShellCommand.quote(path) + " " + remote).requireOk();
        }
        var archive=device.interaction("apk_info",Map.of("path",remote));
        if(selfPackage.equals(archive.get("package")))return selfUpdate(remote,null,null,null);
        ProcessResult result = device.exec(ShellCommand.install(remote));
        if (!result.ok() && !result.stdoutText().toLowerCase(Locale.US).contains("success")) {
            throw new CommandFailedException(result);
        }
        Map<String, Object> out = resultMap(result);
        out.put("remote", remote);
        return out;
    }

    public Map<String, Object> installUrl(String url) {
        return installUrl(url, null, null);
    }

    public Map<String, Object> installUrl(String url, String fileKey, String sha256) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        String remote = Starter.DIR + "/install.apk";
        stageDownload(url, remote, fileKey, sha256);
        return install(remote);
    }

    public Map<String, Object> uninstall(String packageName) {
        ShellCommand.requirePackage(packageName);
        return resultMap(device.exec(ShellCommand.uninstall(packageName)).requireOk());
    }

    public Map<String, Object> key(String key) {
        return key(key, null);
    }

    public Map<String, Object> key(String key, Integer display) {
        if (key == null || key.isBlank() || !key.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("invalid keyevent: " + key);
        }
        wakeIfNeeded(display);
        return withDisplay(resultMap(device.exec(ShellCommand.keyevent(key.toUpperCase(Locale.US), display)).requireOk()), display);
    }

    public Map<String, Object> pull(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        byte[] data = device.pull(path);
        return Json.mapOf("path", path, "bytes", data.length, "dataBase64", Json.b64(data));
    }

    public Map<String, Object> push(String local, String remote) {
        if (local == null || local.isBlank() || remote == null || remote.isBlank()) {
            throw new IllegalArgumentException("local and remote paths are required");
        }
        device.push(local, remote);
        return Json.mapOf("ok", true, "local", local, "remote", remote);
    }

    public Map<String, Object> intent(Map<String, Object> args) {
        String action = Json.string(args, "action");
        String data = Json.string(args, "data");
        String pkg = Json.string(args, "package");
        String extra = Json.string(args, "extra");
        StringBuilder cmd = new StringBuilder("am start -W --activity-multiple-task").append(ShellCommand.displayOption(display(args)));
        if (action != null && !action.isBlank()) cmd.append(" -a ").append(ShellCommand.quote(action));
        if (data != null && !data.isBlank()) cmd.append(" -d ").append(ShellCommand.quote(data));
        if (pkg != null && !pkg.isBlank()) cmd.append(pkg.contains("/") ? " -n " : " -p ").append(ShellCommand.quote(pkg));
        if (extra != null && !extra.isBlank()) cmd.append(' ').append(extra);
        ProcessResult launched=device.exec(cmd.toString());if(!launched.ok()||launched.stdoutText().contains("Error:"))return Json.mapOf("ok",false,"inputDelivered",false,"code","open_failed","error","Android could not open this destination.");return resultMap(launched);
    }

    public Map<String, Object> clipboard(String text) {
        ProcessResult result=device.exec(text==null?"cmd clipboard get":"cmd clipboard set "+ShellCommand.quote(text));
        return ClipboardResult.parse(result.exitCode,result.stdoutText(),result.stderrText(),text!=null);
    }

    public Map<String, Object> notifications(int max) {
        ProcessResult result=device.exec("dumpsys notification --noredact",10_000);
        return NotificationRecords.parse(result.exitCode,result.stdoutText(),result.stderrText(),max);
    }

    public Map<String, Object> apps(String query) {
        ProcessResult result = device.exec("pm list packages");
        List<String> packages = new ArrayList<>();
        for (String line : result.stdoutText().split("\\R")) {
            if (!line.startsWith("package:")) continue;
            String pkg = line.substring(8).trim();
            if (query == null || query.isBlank() || pkg.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                packages.add(pkg);
            }
        }
        return Json.mapOf("count", packages.size(), "packages", packages);
    }

    public Map<String, Object> fetch(String url, String remote) {
        return fetch(url, remote, null, null);
    }

    public Map<String, Object> fetch(String url, String remote, String fileKey, String sha256) {
        if (url == null || url.isBlank() || remote == null || remote.isBlank()) {
            throw new IllegalArgumentException("url and remote are required");
        }
        if (!remote.startsWith("/data/local/tmp/") && !remote.startsWith("/sdcard/")) {
            throw new IllegalArgumentException("remote must be under /data/local/tmp or /sdcard");
        }
        stageDownload(url, remote, fileKey, sha256, false);
        long bytes = 0;
        try { bytes = Files.size(java.nio.file.Paths.get(remote)); } catch (Exception ignored) {}
        return Json.mapOf("ok", true, "remote", remote, "bytes", Long.toString(bytes));
    }

    /**
     * Download {@code url} to {@code dest} using pure Java streaming HTTP(S) so stock
     * Android devices (without /system/bin/curl or sha256sum) work reliably. When
     * {@code fileKey} is set the blob is BSF1 ciphertext: decrypt and verify sha256.
     */
    private void stageDownload(String url, String dest, String fileKey, String sha256) {
        stageDownload(url, dest, fileKey, sha256, false);
    }

    private void stageDownload(String url, String dest, String fileKey, String sha256, boolean requireHttps) {
        Path destPath = java.nio.file.Paths.get(dest);
        try {
            if (destPath.getParent() != null) Files.createDirectories(destPath.getParent());
        } catch (java.io.IOException e) {
            device.exec("mkdir -p " + ShellCommand.quote(destPath.getParent() == null ? Starter.DIR : destPath.getParent().toString()));
        }
        if (fileKey == null || fileKey.isBlank()) {
            try {
                downloadToFile(url, destPath, requireHttps);
                if (sha256 != null && !sha256.isBlank()) {
                    com.deviceagent.crypto.FileCipher.verifySha256(destPath, sha256);
                }
            } catch (java.io.IOException | SecurityException e) {
                try { Files.deleteIfExists(destPath); } catch (java.io.IOException ignored) {}
                throw new IllegalStateException("Download failed: " + e.getMessage(), e);
            }
            return;
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 is required when fileKey is set");
        }
        byte[] key;
        try {
            key = com.deviceagent.crypto.LinkPairing.unb64url(fileKey.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("fileKey must be base64url");
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("fileKey must decode to 32 bytes");
        }
        Path encDir = java.nio.file.Paths.get(Starter.DIR);
        try { Files.createDirectories(encDir); } catch (java.io.IOException ignored) {}
        Path encPath = encDir.resolve("dl-" + Long.toHexString(System.nanoTime()) + ".bsf1");
        try {
            downloadToFile(url, encPath, requireHttps);
            com.deviceagent.crypto.FileCipher.decryptFile(encPath, destPath, key);
            com.deviceagent.crypto.FileCipher.verifySha256(destPath, sha256);
        } catch (java.io.IOException | SecurityException e) {
            try { Files.deleteIfExists(destPath); } catch (java.io.IOException ignored) {}
            throw new IllegalStateException("file decrypt failed: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(encPath); } catch (java.io.IOException ignored) {}
        }
    }

    private static void downloadToFile(String rawUrl, Path dest, boolean requireHttps) throws java.io.IOException {
        String current = rawUrl.trim();
        long deadlineMs = System.currentTimeMillis() + downloadBudgetMs();
        for (int hops = 0; hops <= 5; hops++) {
            java.net.URI uri;
            try { uri = java.net.URI.create(current); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid download URL"); }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("Download URL must use https or http");
            }
            if (requireHttps && !"https".equals(scheme)) {
                throw new SecurityException("Updates require HTTPS (refused redirect or URL: " + scheme + ")");
            }
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(45_000);
            conn.setRequestProperty("User-Agent", "Android-Backstage/" + BuildInfo.VERSION);
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isBlank()) throw new java.io.IOException("Redirect missing Location header");
                    current = uri.resolve(location.trim()).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".part-" + Long.toHexString(System.nanoTime()));
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.OutputStream out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (System.currentTimeMillis() > deadlineMs) {
                            throw new java.io.IOException("Download timed out");
                        }
                        if (n > 0) out.write(buf, 0, n);
                    }
                    out.flush();
                } catch (java.io.IOException e) {
                    try { Files.deleteIfExists(tmp); } catch (java.io.IOException ignored) {}
                    throw e;
                }
                try {
                    Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.io.IOException e) {
                    Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new java.io.IOException("Too many redirects");
    }

    private static long downloadBudgetMs() {
        long remaining = com.deviceagent.tools.ActionDeadline.remaining();
        return remaining == Long.MAX_VALUE ? 600_000L : Math.max(30_000L, remaining - 20_000L);
    }

    public Map<String, Object> clearData(String packageName) {
        ShellCommand.requirePackage(packageName);
        privacyFence.requireAllowed(packageName);
        if (selfPackage.equals(packageName)) {
            throw new IllegalArgumentException("Refusing to clear Backstage's own app data");
        }
        return resultMap(device.exec("pm clear " + ShellCommand.quote(packageName)).requireOk());
    }

    public Map<String, Object> jankStats(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("package is required for kind=jank");
        }
        ShellCommand.requirePackage(packageName);
        privacyFence.requireAllowed(packageName);
        ProcessResult res = device.exec("dumpsys gfxinfo " + ShellCommand.quote(packageName), 8_000L);
        return DevDiagnostics.parseGfxInfo(packageName, res.stdoutText());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> anrRecords(String packageName, int max) {
        if (packageName != null && !packageName.isBlank()) {
            ShellCommand.requirePackage(packageName);
            privacyFence.requireAllowed(packageName);
        }
        ProcessResult res = device.exec("logcat -b events -d -v threadtime -t 500", 8_000L);
        Map<String, Object> parsed = DevDiagnostics.parseAnrs(res.stdoutText(), packageName, Math.max(1, Math.min(50, max)));
        if (privacyFence.count() > 0 && parsed.get("anrs") instanceof List<?> rows) {
            List<Map<String, Object>> clean = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> m && !isBlockedRecord((Map<String, Object>) m)) {
                    clean.add((Map<String, Object>) m);
                }
            }
            parsed.put("anrs", clean);
            parsed.put("count", clean.size());
        }
        return parsed;
    }

    public Map<String, Object> grant(String packageName, String permission) {
        ShellCommand.requirePackage(packageName);
        if (permission == null || !permission.matches("[A-Za-z0-9._]+")) {
            throw new IllegalArgumentException("invalid permission");
        }
        return resultMap(device.exec("pm grant " + packageName + " " + permission));
    }

    private static Map<String, Object> resultMap(ProcessResult result) {
        Map<String,Object> out=Json.mapOf(
                "ok", result.ok(), "commandExecuted", result.exitCode>=0?true:null,
                "exitCode", result.exitCode,
                "command", result.command,
                "stdout", result.stdoutText(),
                "stderr", result.stderrText());
        if(!result.ok()) {out.put("code","command_failed");out.put("outcome","command_failed");out.put("error","Command exited with code "+result.exitCode+".");}
        return out;
    }

    private static String first(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    static String packageSection(String text, String packageName) {
        Matcher m = Pattern.compile("(?m)^\\s*Package \\[" + Pattern.quote(packageName) + "\\][^\\n]*\\n").matcher(text);
        if (!m.find()) return text;
        int start = m.start();
        Matcher next = Pattern.compile("(?m)^\\s*Package \\[").matcher(text);
        if (next.find(m.end())) return text.substring(start, next.start());
        return text.substring(start);
    }
}
