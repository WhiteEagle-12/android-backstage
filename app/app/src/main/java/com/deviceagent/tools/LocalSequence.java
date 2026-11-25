package com.deviceagent.tools;
import java.util.*;
import java.util.function.BiFunction;

/** Bounded explicit steps using exactly the standalone primitive validator and executor. */
public final class LocalSequence {
    private LocalSequence(){}
    private static Map<String,Object> map(Object x){return PrimitiveArguments.map(x);}
    public static void validate(Map<String,Object> args){
        GesturePlan.integer(args,"display",-1,0,Integer.MAX_VALUE);GesturePlan.integer(args,"deadlineMs",15000,1,30000);
        if(!(args.get("steps") instanceof List<?> steps)||steps.isEmpty()||steps.size()>12)throw new IllegalArgumentException("Provide one to twelve explicit steps");
        int index=0;
        for(var raw:steps){
            var step=map(raw);if(!Set.of("tool","arguments","before","optional").containsAll(step.keySet()))throw new IllegalArgumentException("Unknown sequence step field");
            if(step.containsKey("optional")&&!(step.get("optional") instanceof Boolean))throw new IllegalArgumentException("optional must be a boolean");
            String name=Observations.str(step.get("tool"));var a=map(step.get("arguments"));
            if(a.containsKey("display")||a.containsKey("requestId")||a.containsKey("sessionId"))throw new IllegalArgumentException("Steps inherit display and request ownership");
            // References are deliberately limited to prior observation IDs. Values and actions remain literal.
            validateReferences(a,index);var scoped=new LinkedHashMap<>(a);scoped.put("display",args.get("display"));PrimitiveArguments.validate(name,PrimitiveArguments.scrollForms(name,scoped));
            if(step.containsKey("before")){validateReferences(step.get("before"),index);ObservationCondition.validate(map(step.get("before")));}
            index++;
        }
    }
    private static void validateReferences(Object x,int index){
        if(x instanceof Map<?,?> m)for(var e:m.entrySet()){
            if(Set.of("observation","screenshotId").contains(e.getKey())&&e.getValue() instanceof String s&&s.startsWith("step:")){try{int i=Integer.parseInt(s.substring(5));if(i<0||i>=index)throw new IllegalArgumentException();}catch(RuntimeException ex){throw new IllegalArgumentException("Capture references must name an earlier step");}}
            else validateReferences(e.getValue(),index);
        }else if(x instanceof List<?> list)for(Object item:list)validateReferences(item,index);
    }
    private static Object bind(Object x,Map<String,Object> previous,List<Map<String,Object>> results){
        if(x instanceof Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();for(var entry:m.entrySet()){
            String k=entry.getKey().toString();Object v=entry.getValue();
            if(Set.of("observation","screenshotId").contains(k)&&v instanceof String str&&(str.equals("previous")||str.startsWith("step:"))){var source=str.equals("previous")?previous:results.get(Integer.parseInt(str.substring(5)));v=k.equals("observation")?source.get(k):source.get("image") instanceof Map<?,?> image?image.get(k):null;if(v==null)throw new IllegalArgumentException("The referenced step has no "+k);}
            out.put(k,bind(v,previous,results));}return out;}
        if(x instanceof List<?> l)return l.stream().map(v->bind(v,previous,results)).collect(java.util.stream.Collectors.toList());return x;
    }
    private static boolean previous(Object x,String key){if(x instanceof Map<?,?> m){if("previous".equals(m.get(key)))return true;for(Object value:m.values())if(previous(value,key))return true;}else if(x instanceof List<?> l)for(Object value:l)if(previous(value,key))return true;return false;}
    private static boolean referencesStep(Object x,int stepIndex){
        String ref="step:"+stepIndex;
        if(x instanceof Map<?,?> m){
            if(ref.equals(m.get("observation"))||ref.equals(m.get("screenshotId")))return true;
            for(Object v:m.values())if(referencesStep(v,stepIndex))return true;
        }else if(x instanceof List<?> l){
            for(Object v:l)if(referencesStep(v,stepIndex))return true;
        }
        return false;
    }
    private static boolean stepNeededByLater(List<?> steps,int index){
        if(index+1>=steps.size())return true;
        boolean allBetweenOptional=true;
        for(int j=index+1;j<steps.size();j++){
            var later=steps.get(j);
            if(referencesStep(later,index))return true;
            if(allBetweenOptional&&(previous(later,"observation")||previous(later,"screenshotId")))return true;
            if(!Boolean.TRUE.equals(map(later).get("optional")))allBetweenOptional=false;
        }
        return false;
    }
    public static Map<String,Object> run(Map<String,Object> args,BiFunction<String,Map<String,Object>,Map<String,Object>> backend){
        try{validate(args);}catch(IllegalArgumentException e){return Json.mapOf("ok",false,"outcome","failed","code","invalid_argument","error",e.getMessage(),"inputDelivered",false,"completedSteps",0,"steps",List.of());}
        int display=((Number)args.get("display")).intValue();long start=System.nanoTime()/1_000_000;boolean timing=Boolean.TRUE.equals(args.get("timing"));
        ActionDeadline.set(start+GesturePlan.integer(args,"deadlineMs",15000,1,30000));
        EvidenceFlow.enableSequencePrior();
        List<Map<String,Object>> receipts=new ArrayList<>(),results=new ArrayList<>();Map<String,Object> current=null;int completed=0,index=0;String code=null;Object delivered=false;
        boolean prevSkippedObserve=false;
        try{
            var steps=(List<?>)args.get("steps");
            var first=steps.get(0);
            var initial=Json.mapOf("display",display,"image",previous(first,"screenshotId")?previous(first,"observation")?"always":"only":"never");if(args.containsKey("sessionId"))initial.put("sessionId",args.get("sessionId"));
            current=previous(first,"observation")||previous(first,"screenshotId")?backend.apply("android.observe",initial):Map.of("ok",true);
            if(!Boolean.TRUE.equals(current.get("ok"))){code=Observations.str(current.get("code"));}
            else for(Object raw:steps){
                ActionDeadline.check();var step=map(raw);var prior=current;
                boolean optional=Boolean.TRUE.equals(step.get("optional"));
                if(step.containsKey("before")){
                    var guardArgs=Json.mapOf("display",display,"verify",bind(step.get("before"),prior,results),"timeoutMs",0);if(args.containsKey("sessionId"))guardArgs.put("sessionId",args.get("sessionId"));
                    var guard=backend.apply("android.observe",guardArgs);
                    if(!Boolean.TRUE.equals(guard.get("ok"))){
                        if(optional){
                            receipts.add(Json.mapOf("index",index,"outcome","skipped","skipped",true,"inputDelivered",false,"code","precondition_failed"));
                            results.add(prior);index++;continue;
                        }
                        current=guard;code="precondition_failed";receipts.add(Json.mapOf("index",index,"outcome","failed","inputDelivered",false,"code",code));break;
                    }
                }
                ActionDeadline.check();var input=map(bind(step.get("arguments"),prior,results));input.put("display",display);
                String stepTool=(String)step.get("tool");
                boolean mustObserve=stepNeededByLater(steps,index);
                boolean explicitObserve=Boolean.TRUE.equals(input.get("observe"));
                boolean needsRead=mustObserve||explicitObserve||input.containsKey("verify")
                        ||"debug".equals(input.get("detail"))
                        ||(input.containsKey("image")&&!"never".equals(input.get("image")))
                        ||"android.observe".equals(stepTool)||"android.wait".equals(stepTool);
                input.put("observe",needsRead);
                if(args.containsKey("sessionId"))input.put("sessionId",args.get("sessionId"));
                input.put("timeoutMs",Math.min(GesturePlan.integer(input,"timeoutMs",5000,0,20000),Math.min(20000,ActionDeadline.remaining())));
                long stepStart=System.nanoTime()/1_000_000;
                EvidenceFlow.setSequenceTransitionWait(prevSkippedObserve&&!optional);
                try{current=backend.apply(stepTool,input);}finally{EvidenceFlow.setSequenceTransitionWait(false);}
                results.add(current);
                var receipt=Json.mapOf("index",index,"outcome",current.get("outcome"),"inputDelivered",current.get("inputDelivered"));
                for(String k:List.of("verification","inputMethod","launchAccepted","readiness","accessibility","screenshot","recovery","activityTaskId","code","error","observation"))if(current.containsKey(k))receipt.put(k,current.get(k));
                if(timing)receipt.put("elapsedMs",System.nanoTime()/1_000_000-stepStart);receipts.add(receipt);
                if(current.containsKey("inputDelivered")&&current.get("inputDelivered")==null)delivered=null;else if(delivered!=null&&Boolean.TRUE.equals(current.get("inputDelivered")))delivered=true;
                if(!Boolean.TRUE.equals(current.get("ok"))){
                    if(optional&&Boolean.FALSE.equals(current.get("inputDelivered"))){
                        receipt.put("outcome","skipped");receipt.put("skipped",true);
                        current=prior;results.set(results.size()-1,prior);index++;continue;
                    }
                    code=Observations.str(current.getOrDefault("code","step_failed"));break;
                }
                prevSkippedObserve=!needsRead;
                ActionDeadline.check();
                completed++;index++;
            }
            if(code==null&&(current==null||!current.containsKey("observation")&&!current.containsKey("image"))){
                var fin=Json.mapOf("display",display);if(args.containsKey("sessionId"))fin.put("sessionId",args.get("sessionId"));
                var obs=backend.apply("android.observe",fin);
                if(Boolean.TRUE.equals(obs.get("ok")))current=obs;
            }
        }catch(InputNotSentException e){code=e.code;}
        catch(RuntimeException e){code="sequence_failed";if(current==null)current=Map.of("error",Observations.str(e.getMessage()));}
        finally{EvidenceFlow.clearSequencePrior();ActionDeadline.clear();}
        var out=Json.mapOf("ok",code==null,"outcome",code==null?"succeeded":delivered==null?"unknown":"failed","inputDelivered",delivered,"completedSteps",completed,"steps",receipts);
        if(current!=null)for(String k:List.of("observation","display","app","screen","windows","windowId","observedAt","controls","content","truncated","error","image","accessibility","screenshot","activityTasks"))if(current.containsKey(k))out.put(k,current.get(k));
        if(code!=null){out.put("code",code);out.put("stoppedAt",index);}
        if(timing)out.put("timing",Map.of("deviceTotalMs",System.nanoTime()/1_000_000-start));return out;
    }
    public static void mergeTiming(Map<String,Object> totals,Map<String,Object> result){if(result.get("timing") instanceof Map<?,?> phases)phases.forEach((k,v)->{if(v instanceof Number n)totals.put(k.toString(),((Number)totals.getOrDefault(k.toString(),0L)).longValue()+n.longValue());});}
}
