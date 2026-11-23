package com.deviceagent.tools;

import java.util.*;
import java.util.function.LongSupplier;

/** The sole UI action engine. Validation precedes reads, wake, and input. */
public final class EvidenceFlow {
    public interface Backend {
        Map<String,Object> action(String name,Map<String,Object> args);
        Map<String,Object> tree(int display);
        default Map<String,Object> tree(int display,String windowId){return tree(display);}
        Map<String,Object> screen(int display);
        default Map<String,Object> screen(int display,double scale){return screen(display);}
        Map<String,Object> display();
        default Map<String,Object> display(int display){return display();}
        String focus(int display);
        default String captureFocus(int display){return focus(display);}
    }
    private final Backend backend;
    private final LongSupplier clock;
    private final Observations observations;
    private final Map<String,Map<String,Object>> shots=Collections.synchronizedMap(new LinkedHashMap<>());
    /** Within a single android.sequence call, step N can reuse step N-1's just-settled tree as its before snapshot. */
    private static final ThreadLocal<Map<String,Object>> SEQUENCE_PRIOR_TREE=new ThreadLocal<>();
    /** Set when the previous step in a sequence skipped observe, so a selector lookup can briefly wait for UI transitions. */
    private static final ThreadLocal<Boolean> SEQUENCE_TRANSITION_WAIT=new ThreadLocal<>();
    static void clearSequencePrior(){SEQUENCE_PRIOR_TREE.remove();SEQUENCE_TRANSITION_WAIT.remove();}
    static void enableSequencePrior(){SEQUENCE_PRIOR_TREE.set(Map.of());}
    static void setSequenceTransitionWait(boolean wait){if(wait)SEQUENCE_TRANSITION_WAIT.set(Boolean.TRUE);else SEQUENCE_TRANSITION_WAIT.remove();}
    public EvidenceFlow(Backend b){this(b,()->System.nanoTime()/1_000_000);}
    public EvidenceFlow(Backend b,LongSupplier c){backend=b;clock=c;observations=new Observations(c);}
    public static boolean handles(String name){return PrimitiveArguments.TOOLS.contains(name);}
    public Observations observations(){return observations;}
    public Map<String,Object> capture(int display){return snapshot(display,null);}
    private static String window(Map<String,Object> args){return Json.string(args,"windowId");}
    /** Not device-wide synchronized: callers hold the target display's turn, so displays run independently. */
    public Map<String,Object> run(String name,Map<String,Object> supplied){
        long start=clock.getAsLong();Map<String,Object> result;
        try{PrimitiveArguments.validate(name,supplied);result=execute(name,new LinkedHashMap<>(supplied));}
        catch(InputNotSentException e){result=failure(e.code,e.getMessage(),false);if(e.details.get("observation") instanceof Map<?,?> view)for(var field:view.entrySet())result.put(field.getKey().toString(),field.getValue());}
        catch(IllegalArgumentException e){result=failure("invalid_argument",e.getMessage(),false);}
        if(supplied.containsKey("verify")||!Set.of("android.observe","android.wait").contains(name)){
            var verification=new LinkedHashMap<String,Object>();
            if(result.get("verification") instanceof Map<?,?> v)v.forEach((k,value)->verification.put(k.toString(),value));
            else if(result.containsKey("valueMatched"))verification.put("matched",result.get("valueMatched"));
            verification.put("status",verification.containsKey("matched")?(Boolean.TRUE.equals(verification.get("matched"))?"verified":"failed"):"unverified");
            result.put("verification",verification);
        }
        if(Boolean.TRUE.equals(supplied.get("timing"))){
            Map<String,Object> timing=new LinkedHashMap<>();
            if(result.get("timing") instanceof Map<?,?> existing)existing.forEach((k,v)->timing.put(k.toString(),v));
            timing.put("deviceTotalMs",clock.getAsLong()-start);
            result.put("timing",timing);
        }
        return result;
    }
    private Map<String,Object> execute(String name,Map<String,Object> args){
        boolean recordTiming=Boolean.TRUE.equals(args.get("timing"));
        Map<String,Object> phases=recordTiming?new LinkedHashMap<>():null;
        int display=GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);
        if(display==0){var state=backend.display(display);if(!Boolean.TRUE.equals(state.get("active"))||!Objects.equals(state.get("displayId"),0)||!(args.get("sessionId") instanceof String)||!Objects.equals(args.get("sessionId"),state.get("sessionId")))return failure("screen_control_stopped","Screen control stopped. Begin a new task when the user is ready.",false);}
        Map<String,Object> verify=args.containsKey("verify")?PrimitiveArguments.map(args.get("verify")):null;
        if(verify!=null)ObservationCondition.references(verify,observations,display);
        if(args.containsKey("since"))observations.get(Json.string(args,"since"),display);
        boolean read=name.equals("android.observe")||name.equals("android.wait"),debug="debug".equals(args.get("detail"));
        Map<String,Object> result=Json.mapOf("ok",true,"outcome","succeeded","inputDelivered",false);
        Map<String,Object> before=null;
        if(!read){
            boolean visual=args.containsKey("screenshotId")||display==0&&name.equals("android.key")&&PrimitiveArguments.GLOBAL_KEYS.contains(args.get("key"));
            long tBefore=clock.getAsLong();
            Map<String,Object> carried=SEQUENCE_PRIOR_TREE.get();
            if(carried!=null&&!carried.isEmpty()&&Objects.equals(carried.get("display"),display)&&Objects.equals(carried.get("windowId"),window(args))){
                SEQUENCE_PRIOR_TREE.set(Map.of());
                if(!name.equals("android.open")&&!visual)before=carried;
            }else{
                if(carried!=null)SEQUENCE_PRIOR_TREE.set(Map.of());
                try{if(!name.equals("android.open")&&!visual)before=snapshot(display,window(args));}catch(InputNotSentException e){if("blocked_app".equals(e.code))return failure(e.code,e.getMessage(),false);return failure("observation_failed","The display could not be read. Observe it again before sending input.",false);}catch(RuntimeException e){return failure("observation_failed","The display could not be read. Observe it again before sending input.",false);}
            }
            if(phases!=null&&before!=null)phases.put("beforeTreeMs",clock.getAsLong()-tBefore);
            if(!name.equals("android.open")&&!visual&&(before==null||!ready(before)))return failure("display_not_ready","Open an app on this display before sending input.",false);
            if(before!=null&&args.get("expectedPackage") instanceof String pkg&&!pkg.equals(focusedPackage(before)))return failure("wrong_app","The expected app is not focused. Observe this display again.",false);
            if(name.equals("android.scroll_until")||name.equals("android.scroll")&&args.containsKey("until"))return scrollUntil(args,before);
            if(args.containsKey("screenshotId"))convertCoordinates(args,display);
            long tAction=clock.getAsLong();
            try{
                ActionDeadline.check();
                if(args.containsKey("target")||args.containsKey("selector"))result=targetAction(args,before,display,name.equals("android.type")?"type":name.equals("android.scroll")?"scroll":"tap");
                else result=new LinkedHashMap<>(backend.action(name,args));
            }catch(InputNotSentException e){return failure(e.code,e.getMessage(),false);}
            catch(IllegalArgumentException e){return failure("invalid_argument",e.getMessage(),false);}
            catch(RuntimeException e){return failure("input_outcome_unknown","The action has no receipt. Read the current state before trying again.",null);}
            if(phases!=null)phases.put("actionMs",clock.getAsLong()-tAction);
            if(!result.containsKey("inputDelivered"))result.put("inputDelivered",Boolean.TRUE.equals(result.get("ok")));
            result.put("outcome",Boolean.TRUE.equals(result.get("ok"))?"succeeded":result.get("inputDelivered")==null?"unknown":"failed");
            if(name.equals("android.open")){result.put("launchAccepted",Boolean.TRUE.equals(result.get("ok")));result.put("readiness","unverified");}
            if(!Boolean.TRUE.equals(result.get("ok"))&&!Boolean.TRUE.equals(result.get("inputDelivered"))){if(phases!=null)result.put("timing",phases);return result;}
        }
        String image=Observations.str(args.getOrDefault("image","never"));
        if(image.equals("only")){
            result.put("accessibility","not_requested");
            long tScreen=clock.getAsLong();
            try{
                var captured=captureReadyImage(display,GesturePlan.number(args,"scale",0.5),GesturePlan.integer(args,"timeoutMs",5000,0,20000));
                String focus=Observations.str(captured.get("focusedActivity"));
                if(args.get("expectedPackage") instanceof String pkg&&!focus.startsWith(pkg+"/"))return failure("wrong_app","The expected app is not focused. Observe this display again.",result.get("inputDelivered"));
                if(!captured.containsKey("screenshotId"))throw new IllegalStateException("No capture");
                result.put("image",captured);result.put("screenshot","available");
            }catch(InputNotSentException e){if("blocked_app".equals(e.code))result.putAll(failure(e.code,e.getMessage(),result.get("inputDelivered")));else result.putAll(failure("screenshot_unavailable","The screen could not be captured. Check the session and observe again.",result.get("inputDelivered")));result.put("screenshot","unavailable");}
            catch(RuntimeException e){result.putAll(failure("screenshot_unavailable","The screen could not be captured. Check the session and observe again.",result.get("inputDelivered")));result.put("screenshot","unavailable");}
            if(phases!=null){phases.put("screenMs",clock.getAsLong()-tScreen);result.put("timing",phases);}
            return result;
        }
        boolean observe=read||!Boolean.FALSE.equals(args.get("observe"))||verify!=null||debug;
        if(!observe&&image.equals("never")){if(phases!=null)result.put("timing",phases);return result;}
        Map<String,Object> tree=null;boolean matched=verify==null;Map<String,Object> conditionState=new HashMap<>();
        String settledSignature=null;long sameSince=0;int samples=0;
        long start=clock.getAsLong();int timeout=GesturePlan.integer(args,"timeoutMs",5000,0,20000);
        try{
            do{
                try{tree=snapshot(display,window(args));}catch(InputNotSentException e){if("blocked_app".equals(e.code))return failure(e.code,e.getMessage(),result.get("inputDelivered"));tree=null;}catch(RuntimeException e){tree=null;}
                matched=tree!=null&&ready(tree)&&(verify==null||ObservationCondition.matches(verify,tree,observations,display,conditionState,clock.getAsLong()));
                long now=clock.getAsLong();samples++;
                boolean settled=false;
                if(verify==null&&matched){
                    String signature=Observations.fingerprint(tree);
                    if(!signature.equals(settledSignature)){settledSignature=signature;sameSince=now;}
                    // A quiet tree improves the returned snapshot, but never
                    // proves a URL or other user intent completed.
                    settled=read||samples>1&&now-sameSince>=150&&now-start>=250;
                }else if(!matched){settledSignature=null;sameSince=now;}
                long budget=verify!=null?timeout:Math.min(timeout,read?500:1000);
                if(verify!=null&&matched||verify==null&&settled||now-start>=budget||ActionDeadline.remaining()==0||samples>=201)break;
                pause(Math.min(50,Math.min(ActionDeadline.remaining(),Math.max(1,budget-(now-start)))));
            }while(true);
            if(phases!=null){phases.put("observeMs",clock.getAsLong()-start);phases.put("observeSamples",samples);}
            result.put("accessibility",tree!=null&&ready(tree)?"available":"unavailable");
            if(tree==null||!ready(tree)){result.putAll(failure("observation_failed","The app could not be read. Observe this display again.",result.get("inputDelivered")));}
            else{
                if(SEQUENCE_PRIOR_TREE.get()!=null)SEQUENCE_PRIOR_TREE.set(tree);
                if(observe)result.putAll(observations.observe(tree,GesturePlan.integer(args,"maxItems",40,1,300),Json.string(args,"since"),Boolean.TRUE.equals(args.get("full")),debug));
                if(debug)result.put("ui",tree);
                if(verify!=null){
                    Map<String,Object> vMap=new LinkedHashMap<>();vMap.put("matched",matched);
                    if(!matched){
                        List<String> failed=ObservationCondition.describeFailures(verify,tree,observations,display);
                        if(!failed.isEmpty())vMap.put("failedConditions",failed);
                        result.put("ok",false);result.put("outcome","failed");result.put("code","verification_timeout");result.put("error","The expected state was not observed. Check the current state before repeating input.");
                    }
                    result.put("verification",vMap);
                }
            }
            if(verify!=null&&name.equals("android.open"))result.put("readiness",matched?"ready":"not_ready");
        }catch(RuntimeException e){result.putAll(failure("observation_failed","The result could not be read. Observe this display again.",result.get("inputDelivered")));result.put("accessibility","unavailable");}
        if(verify!=null){
            if(!(result.get("verification") instanceof Map<?,?>))result.put("verification",Map.of("matched",matched));
            if(name.equals("android.open"))result.put("readiness",matched?"ready":"not_ready");
        }
        if(image.equals("always")||image.equals("if_needed")&&(tree==null||!ready(tree)||!matched||result.containsKey("unlabeledControls")||result.containsKey("ambiguousControls")||Boolean.TRUE.equals(result.get("truncated"))||Observations.appNodes(tree).stream().noneMatch(n->!Observations.label(n).isBlank()))){
            long tScreen=clock.getAsLong();
            try{
                var captured=captureImage(display,GesturePlan.number(args,"scale",0.5));
                if(captured.containsKey("screenshotId")){
                    result.put("image",captured);result.put("screenshot","available");
                    if(verify==null&&"observation_failed".equals(result.get("code"))){result.put("ok",true);result.put("outcome","succeeded");result.remove("code");result.remove("error");result.put("warning","Controls are unavailable. Use this screenshot and screenshotId for coordinate input, or observe again.");}
                }else result.put("screenshot","unavailable");
            }catch(InputNotSentException e){if("blocked_app".equals(e.code))result.putAll(failure(e.code,e.getMessage(),result.get("inputDelivered")));result.put("screenshot","unavailable");}
            catch(RuntimeException e){result.put("screenshot","unavailable");}
            if(phases!=null)phases.put("screenMs",clock.getAsLong()-tScreen);
        }
        if(phases!=null)result.put("timing",phases);
        return result;
    }
    private Map<String,Object> targetAction(Map<String,Object> args,Map<String,Object> tree,int display,String operation){
        Map<String,Object> ref=null;boolean refreshed=false;
        try{
            try{ref=resolve(args,tree,display,operation);}
            catch(InputNotSentException e){
                if("target_not_found".equals(e.code)&&args.containsKey("selector")&&Boolean.TRUE.equals(SEQUENCE_TRANSITION_WAIT.get())){
                    long waitStart=clock.getAsLong();InputNotSentException lastNotFound=e;
                    while(clock.getAsLong()-waitStart<600&&ActionDeadline.remaining()>0){
                        pause(Math.min(50,ActionDeadline.remaining()));
                        ActionDeadline.check();
                        tree=recoverySnapshot(display,window(args));
                        try{ref=resolve(args,tree,display,operation);lastNotFound=null;break;}
                        catch(InputNotSentException retry){if(!"target_not_found".equals(retry.code))throw retry;lastNotFound=retry;}
                    }
                    if(lastNotFound!=null)throw lastNotFound;
                }else if("stale_target".equals(e.code)){
                    tree=recoverySnapshot(display,window(args));ref=observations.refreshTarget(Json.string(args,"observation"),Json.string(args,"target"),display,tree,operation);refreshed=true;
                }else throw e;
            }
            for(String k:List.of("text","mode","holdMs","direction","amount"))if(args.containsKey(k))ref.put(k,args.get(k));
            for(int attempt=0;attempt<2;attempt++){
                ActionDeadline.check();Map<String,Object> result;
                try{result=new LinkedHashMap<>(backend.action("target",ref));}
                catch(InputNotSentException e){result=failure(e.code,e.getMessage(),false);}
                if(!refreshed&&"stale_target".equals(result.get("code"))&&Boolean.FALSE.equals(result.get("inputDelivered"))){tree=recoverySnapshot(display,window(args));ref=Observations.refreshTarget(tree,ref);refreshed=true;continue;}
                if(refreshed)result.put("recovery",Map.of("observationRefreshed",true,"retries",1));
                if("stale_target".equals(result.get("code")))result.putAll(observations.observe(tree,40,null));
                return result;
            }
            throw new IllegalStateException("Unreachable retry state");
        }catch(InputNotSentException e){var result=failure(e.code,e.getMessage(),false);result.putAll(observations.observe(tree,40,null));return result;}
    }
    private Map<String,Object> recoverySnapshot(int display,String windowId){
        try{return snapshot(display,windowId);}catch(InputNotSentException e){throw e;}catch(RuntimeException e){throw new InputNotSentException("observation_failed","No input was sent. Observe the display before retrying.");}
    }
    private Map<String,Object> captureImage(int display,double scale){
        var state=backend.display(display);String focus=backend.captureFocus(display);
        if(!Boolean.TRUE.equals(state.get("active"))||!Objects.equals(state.get("displayId"),display)||focus.isBlank())throw new IllegalStateException("Display unavailable");
        var image=backend.screen(display,scale);
        if(!focus.equals(backend.captureFocus(display)))throw new IllegalStateException("Focus changed during capture");
        String focusedActivity=TaskSnapshot.focusedComponent(focus);
        if(focusedActivity.isEmpty())focusedActivity=backend.focus(display);
        image=new LinkedHashMap<>(image);image.put("focusToken",focus);image.put("focusedActivity",focusedActivity);
        var result=remember(image);if(result.get("screenshotId") instanceof String id)shots.get(id).put("displayCreatedAt",state.get("createdAt"));return result;
    }
    private Map<String,Object> captureReadyImage(int display,double scale,int timeout){
        long start=clock.getAsLong();RuntimeException last=null;
        for(int attempt=0;attempt<51;attempt++){
            ActionDeadline.check();
            try{var image=captureImage(display,scale);if(image.containsKey("screenshotId"))return image;last=new IllegalStateException("Frame unavailable");}
            catch(RuntimeException e){last=e;}
            long remaining=Math.min(timeout-(clock.getAsLong()-start),ActionDeadline.remaining());if(remaining<=0)break;pause(Math.min(100,remaining));
        }
        throw last==null?new IllegalStateException("No screen capture"):last;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> resolve(Map<String,Object> args,Map<String,Object> tree,int display,String operation){
        if(args.containsKey("selector")){
            var view=observations.observe(tree,300,null);var controls=(List<Map<String,Object>>)view.get("controls");var selector=PrimitiveArguments.map(args.get("selector"));
            var matches=controls.stream().filter(c->ObservationCondition.selects(selector,c)).collect(java.util.stream.Collectors.toList());
            if(matches.size()!=1)throw new InputNotSentException(matches.isEmpty()?"target_not_found":"ambiguous_target","Choose a unique control from this observation.",Map.of("observation",view));
            args.put("target",matches.get(0).get("id"));args.put("observation",view.get("observation"));
        }
        return observations.target(Json.string(args,"observation"),Json.string(args,"target"),display,tree,operation);
    }
    private Map<String,Object> scrollUntil(Map<String,Object> args,Map<String,Object> tree){
        int display=((Number)args.get("display")).intValue(),max=GesturePlan.integer(args,"maxScrolls",5,1,20),count=0;Object delivered=false;boolean end=false,found=false,recovered=false;
        long started=clock.getAsLong();int timeout=GesturePlan.integer(args,"timeoutMs",5000,0,20000);
        Map<String,Object> container;var selection=new LinkedHashMap<>(args);
        try{container=resolve(selection,tree,display,"scroll");}
        catch(InputNotSentException e){if(!e.code.equals("stale_target"))throw e;recovered=true;tree=snapshot(display,window(args));container=observations.refreshTarget(Json.string(selection,"observation"),Json.string(selection,"target"),display,tree,"scroll");}
        Map<String,Object> result=Json.mapOf("ok",true,"outcome","succeeded");
        try{while(true){
            var matches=ObservationCondition.controls(tree).stream().filter(c->ObservationCondition.selects(PrimitiveArguments.map(args.get("until")),c)).collect(java.util.stream.Collectors.toList());
            if(matches.size()>1){result=failure("ambiguous_target","The destination matches several controls. Choose a more specific selector.",delivered);break;}
            if(matches.size()==1){found=true;break;}
            if(end||count>=max||clock.getAsLong()-started>=timeout)break;
            // Refresh the same scroll container identity after each scroll so normal action-list
            // or sibling updates (like ACTION_SCROLL_BACKWARD appearing off-top) do not abort scroll_until.
            container=count==0?container:Observations.refreshScrollContainer(tree,container);
            Observations.validateTarget(tree,container);container.put("direction",args.get("direction"));container.put("amount",args.getOrDefault("amount",1));
            ActionDeadline.check();Map<String,Object> step;
            try{step=backend.action("target",container);}catch(InputNotSentException e){step=failure(e.code,e.getMessage(),false);}catch(RuntimeException e){delivered=null;result=failure("input_outcome_unknown","The scroll has no receipt. Observe before trying again.",null);break;}
            if(step.get("inputDelivered")==null)delivered=null;else if(Boolean.TRUE.equals(step.get("inputDelivered")))delivered=true;
            if(!recovered&&"stale_target".equals(step.get("code"))&&Boolean.FALSE.equals(step.get("inputDelivered"))){
                recovered=true;tree=snapshot(display,window(args));container=Observations.refreshTarget(tree,container);ActionDeadline.check();
                try{step=backend.action("target",container);}catch(InputNotSentException e){step=failure(e.code,e.getMessage(),false);}catch(RuntimeException e){step=failure("input_outcome_unknown","The scroll has no receipt. Observe before trying again.",null);}
                if(step.get("inputDelivered")==null)delivered=null;else if(Boolean.TRUE.equals(step.get("inputDelivered")))delivered=true;
                result.put("recovery",Map.of("observationRefreshed",true,"retries",1));
            }
            count++;if(!Boolean.TRUE.equals(step.get("ok"))){result=step;break;}
            end=Boolean.TRUE.equals(step.get("endReached"));tree=snapshot(display,window(args));
        }
        }catch(InputNotSentException e){result=failure(e.code,e.getMessage(),delivered);}catch(RuntimeException e){result=failure("observation_failed","The scroll result could not be read. Observe this display again.",delivered);}
        result.put("inputDelivered",delivered);result.put("scrolls",count);result.put("found",found);result.put("endReached",end);
        if(!found&&Boolean.TRUE.equals(result.get("ok"))){result.put("ok",false);result.put("outcome","failed");result.put("code",end?"end_reached":"scroll_limit");result.put("error",end?"The target was not found before the end of this container.":"The target was not found within the scroll limit.");}
        result.putAll(observations.observe(tree,GesturePlan.integer(args,"maxItems",40,1,300),null));
        if(found&&args.containsKey("verify")){var verifyArgs=Json.mapOf("display",display,"verify",args.get("verify"),"timeoutMs",Math.max(0,timeout-(clock.getAsLong()-started)));for(String key:List.of("sessionId","windowId"))if(args.containsKey(key))verifyArgs.put(key,args.get(key));var check=run("android.observe",verifyArgs);result.putAll(check);result.put("inputDelivered",delivered);}
        return result;
    }
    private Map<String,Object> snapshot(int display,String windowId){
        RuntimeException last=null;
        for(int attempt=0;attempt<3;attempt++){
            ActionDeadline.check();
            try{var state=backend.display(display);if(!Boolean.TRUE.equals(state.get("active"))||!Objects.equals(((Number)state.get("displayId")).intValue(),display))throw new InputNotSentException("display_unavailable","Begin a task to access a display.");
                var tree=new LinkedHashMap<>(backend.tree(display,windowId));if(Boolean.FALSE.equals(tree.get("ok")))throw new IllegalStateException("Tree read failed");if(windowId!=null)tree.put("windowId",windowId);tree.put("display",display);tree.put("focusedActivity",backend.focus(display));tree.put("observedAt",java.time.Instant.now().toString());return tree;
            }catch(InputNotSentException e){throw e;}catch(RuntimeException e){last=e;if(attempt<2)pause(50);}
        }
        throw last;
    }
    private static void pause(long ms){try{if(ms>0)Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();ActionDeadline.check();}}
    private static boolean ready(Map<String,Object> tree){return !Observations.appNodes(tree).isEmpty()&&Observations.windows(tree).stream().anyMatch(w->Observations.selectedWindow(tree,w));}
    private static String focusedPackage(Map<String,Object> tree){return Observations.appNodes(tree).stream().map(n->Observations.str(n.get("package"))).filter(s->!s.isBlank()).findFirst().orElse("");}
    private static Map<String,Object> failure(String code,String message,Object delivered){return Json.mapOf("ok",false,"outcome",delivered==null?"unknown":"failed","inputDelivered",delivered,"code",code,"error",message);}
    public synchronized Map<String,Object> remember(Map<String,Object> screenshot) {
        Map<String,Object> out=new LinkedHashMap<>(screenshot);
        if(Boolean.FALSE.equals(out.get("ok")))return out;
        for(String key:List.of("ok","screenshotCaptureMs","screenshotEncodingMs","elapsedMs"))out.remove(key);
        if(!(out.get("sourceWidth") instanceof Number)||!(out.get("sourceHeight") instanceof Number))return out;
        String id=UUID.randomUUID().toString();
        out.put("screenshotId",id);out.put("observedAt",java.time.Instant.now().toString());
        out.put("scaleX",((Number)out.get("width")).doubleValue()/((Number)out.get("sourceWidth")).doubleValue());
        out.put("scaleY",((Number)out.get("height")).doubleValue()/((Number)out.get("sourceHeight")).doubleValue());
        Map<String,Object> meta=new LinkedHashMap<>(out);meta.remove("pngBase64");meta.put("capturedElapsedMs",clock.getAsLong());shots.put(id,meta);
        while(shots.size()>32)shots.remove(shots.keySet().iterator().next());
        return out;
    }
    private synchronized void convertCoordinates(Map<String,Object> args,int display) {
        Map<String,Object> shot=shots.get(Json.string(args,"screenshotId"));
        if(shot==null||clock.getAsLong()-((Number)shot.get("capturedElapsedMs")).longValue()>120_000)throw new IllegalArgumentException("Screenshot expired. Take a new screenshot before sending input.");
        if(((Number)shot.get("displayId")).intValue()!=display)throw new IllegalArgumentException("Screenshot belongs to a different display");
        Map<String,Object> current=backend.display(display);
        String focus=backend.captureFocus(display);
        if(!Boolean.TRUE.equals(current.get("active"))||!Objects.equals(current.get("displayId"),display)||!Objects.equals(current.get("createdAt"),shot.get("displayCreatedAt"))||focus.isBlank()||!focus.equals(shot.getOrDefault("focusToken",shot.get("focusedActivity"))))throw new InputNotSentException("stale_screenshot","The display or focused window changed. Take a new screenshot.");
        if(args.get("expectedPackage") instanceof String pkg&&!focus.startsWith(pkg+"/"))throw new InputNotSentException("wrong_app","The expected app is not focused. Take a new screenshot.");
        if(((Number)current.get("width")).intValue()!=((Number)shot.get("sourceWidth")).intValue()||((Number)current.get("height")).intValue()!=((Number)shot.get("sourceHeight")).intValue())throw new IllegalArgumentException("Display size changed. Take a new screenshot.");
        if(Boolean.TRUE.equals(args.get("focused")))args.put("captureFocus",focus);
        int width=((Number)shot.get("width")).intValue(),height=((Number)shot.get("height")).intValue();
        double sx=((Number)shot.get("scaleX")).doubleValue(),sy=((Number)shot.get("scaleY")).doubleValue();
        if(args.containsKey("x")){
            int x=GesturePlan.integer(args,"x",-1,0,width-1),y=GesturePlan.integer(args,"y",-1,0,height-1);
            args.put("x",(int)Math.floor(x/sx));args.put("y",(int)Math.floor(y/sy));
        }
        if(args.containsKey("preset")){
            // Validate the complete gesture in capture space, then map every path.
            // This also handles nonuniform screenshot rounding and angled pinches.
            String preset=Json.string(args,"preset");var options=new LinkedHashMap<>(args);
            if(preset.equals("swipe"))options.put("holdMs",0);
            var plan=GesturePlan.create(preset.equals("paths")?"multi_touch":preset.equals("swipe")?"drag":preset,options,width,height);
            if(preset.equals("swipe")||preset.equals("drag")){
                args.put("x1",GesturePlan.number(args,"x1",-1)/sx);args.put("x2",GesturePlan.number(args,"x2",-1)/sx);
                args.put("y1",GesturePlan.number(args,"y1",-1)/sy);args.put("y2",GesturePlan.number(args,"y2",-1)/sy);
            }else{
                var paths=new ArrayList<List<Map<String,Object>>>();
                for(var path:plan.paths){var points=new ArrayList<Map<String,Object>>();for(var point:path)points.add(Json.mapOf("x",point[0]/sx,"y",point[1]/sy));paths.add(points);}
                args.put("preset","paths");args.put("paths",paths);
            }
        }
    }
}
