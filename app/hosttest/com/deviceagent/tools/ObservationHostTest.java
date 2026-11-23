package com.deviceagent.tools;
import java.util.*;
public final class ObservationHostTest {
 static void check(boolean x,String why){EvidenceHostTest.check(x,why);}
 static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected rejection");}
 static Map<String,Object> node(int i,int p,String id,String text,boolean click){return EvidenceHostTest.node(i,p,id,text,click);}
 static Map<String,Object> tree(List<Map<String,Object>> n){return EvidenceHostTest.tree(n);}
 @SuppressWarnings("unchecked") public static void run(){
  long[] time={0};var obs=new Observations(()->time[0]);var parent=node(0,-1,"a","",true);var child=node(1,0,"b","Directions",true);var content=node(2,-1,"c","Clock",false);var before=tree(List.of(parent,child,content));var view=obs.observe(before,40,null);var controls=(List<Map<String,Object>>)view.get("controls");check(controls.size()==1&&controls.get(0).get("label").equals("Directions"),"wrapper-child duplicate collapsed");
  String id=(String)view.get("observation"),target=(String)controls.get(0).get("id");
  var current=tree(List.of(parent,child,node(2,-1,"c","Different clock",false)));check(obs.target(id,target,2,current).get("nodeId").equals("b"),"actual labeled child target survives unrelated text");
  child.put("text","Save"); // wrapper label is part of action identity through its child
  parent.put("contentDescription","Save");rejects(()->obs.target(id,target,2,tree(List.of(parent,child))));parent.put("contentDescription","");child.put("text","Directions");
  var changed=node(1,0,"b","Directions",true);changed.put("bounds","[1,1][101,101]");rejects(()->obs.target(id,target,2,tree(List.of(parent,changed))));
  rejects(()->obs.target(id,target,3,before));
  var delta=obs.observe(current,40,id);check(Boolean.TRUE.equals(delta.get("delta"))&&((List<?>)delta.get("controls")).isEmpty(),"delta omits stable controls");
  var appeared=Map.<String,Object>of("kind","appeared","selector",Map.of("label","Directions"),"observation",id);check(!ObservationCondition.matches(appeared,before,obs,2),"existing text does not prove appearance");
  var absent=Map.<String,Object>of("kind","absent","selector",Map.of("label","Missing"));var truncated=tree(List.of(parent));truncated.put("truncated",true);check(!ObservationCondition.matches(absent,truncated,obs,2),"truncation cannot prove absence");
  var stable=Map.<String,Object>of("kind","stable","selectors",List.of(Map.of("label","Directions")),"durationMs",100);Map<String,Object> state=new HashMap<>();check(!ObservationCondition.matches(stable,before,obs,2,state,0),"stability starts with baseline");check(ObservationCondition.matches(stable,current,obs,2,state,100),"selected controls stable despite clock");
  rejects(()->ObservationCondition.validate(Map.of("all",List.of(Map.of("any",List.of(absent))))));
  rejects(()->ObservationCondition.validate(Map.of("kind","stable","region",Map.of("left",5,"right",4,"top",0,"bottom",10))));
  time[0]=Observations.TTL_MS+1;rejects(()->obs.target(id,target,2,before));
  var staticAbsent=Map.<String,Object>of("kind","absent","selector",Map.of("label","Clock"));check(!ObservationCondition.matches(staticAbsent,before,obs,2),"visible static text cannot satisfy absence");
  var absentTree=tree(List.of(content));check(!ObservationCondition.matches(stable,absentTree,obs,2,state,200),"missing selected control resets stability");check(!ObservationCondition.matches(stable,before,obs,2,state,250),"reappeared control starts a new stability interval");
  contextIdentity();taskResources();sharedSessions();foregroundResources();
  ToolCatalog.validate("android.open",Map.of("sessionId","session_"+"a".repeat(32),"requestId","request_1","url","https://example.com"));
  rejects(()->ToolCatalog.validate("android.open",Map.of("sessionId","session_"+"a".repeat(32),"requestId","request_1","query","chrome")));
  rejects(()->ToolCatalog.schema("android.open_inspect"));
  System.out.println("PASS observations: deduplication, scoped identity, deltas, scoped stability, expiry, session ownership and cleanup, new-only schemas");
 }
 static void contextIdentity(){
  var row=node(0,-1,"row","",false);row.put("bounds","[0,0][300,100]");var title=node(1,0,"title","Place card",false);var share=node(2,0,"share","Share",true);
  var page=tree(List.of(row,title,share));PrimitiveArguments.map(((List<?>)page.get("windows")).get(0)).put("bounds","[0,0][1000,1000]");var observations=new Observations(()->0);var view=observations.observe(page,40,null);var button=PrimitiveArguments.map(((List<?>)view.get("controls")).get(0));check(button.get("context").equals("Place card"),"small row label supplies disambiguating context");
  title.put("text","Different place");rejects(()->observations.target((String)view.get("observation"),(String)button.get("id"),2,page));
 }
 static void taskResources(){try{
  var root=java.nio.file.Files.createTempDirectory("sessions-test");long[] clock={0};Map<String,Object> display=new LinkedHashMap<>(Map.of("active",false));List<String> calls=new ArrayList<>();
  var tasks=new TaskResources(root,(op,args)->{calls.add(op);return switch(op){case "display_status"->new LinkedHashMap<>(display);case "display_start"->{display.putAll(Json.mapOf("active",true,"created",true,"displayId",2,"createdAt",clock[0]));yield new LinkedHashMap<>(display);}case "display_stop"->{display.put("active",false);yield Map.of("ok",true);}case "activities"->Map.of("activityTasks",List.of());default->throw new AssertionError(op);};},()->clock[0]);
  var begin=tasks.begin(Map.of("leaseMinutes",1),"a");String id=(String)begin.get("sessionId");check(tasks.use(id,"a")==2,"owned display acquired");rejects(()->tasks.use(id,"b"));
  rejects(()->tasks.use(id,"a",0));
  java.nio.file.Files.writeString(java.nio.file.Path.of((String)begin.get("temporaryDirectory"),"test"),"x");
  var done=tasks.finish(Map.of("sessionId",id),"a");check(((List<?>)done.get("released")).containsAll(List.of("display","temporaryFiles")),"reports actual released resources");check(((List<?>)tasks.finish(Map.of("sessionId",id),"a").get("released")).isEmpty(),"idempotent finish");
  begin=tasks.begin(Map.of("leaseMinutes",1),"a");String expired=(String)begin.get("sessionId");clock[0]=60001;tasks.sweep();check(tasks.inspect(expired,"a").get("state").equals("finished"),"disconnect lease expiry cleans resources");
  begin=tasks.begin(Map.of(),"a");String replaced=(String)begin.get("sessionId");display.put("createdAt",123L);calls.clear();tasks.finish(Map.of("sessionId",replaced),"a");check(!calls.contains("display_stop"),"replacement display remains intact");
  try(var paths=java.nio.file.Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
 }catch(Exception e){throw new AssertionError(e);}}
 static void foregroundResources(){try{
  var root=java.nio.file.Files.createTempDirectory("screen-sessions-test");long[] clock={1000};Map<String,Object> screen=new LinkedHashMap<>(Map.of("active",false));List<String> calls=new ArrayList<>();
  var tasks=new TaskResources(root,(op,args)->{calls.add(op);return switch(op){
   case "foreground_status"->new LinkedHashMap<>(screen);
   case "foreground_start"->{screen.putAll(Json.mapOf("active",true,"created",true,"sessionId",args.get("sessionId"),"displayId",0,"createdAt",clock[0]));yield new LinkedHashMap<>(screen);}
   case "foreground_renew"->{check(screen.get("sessionId").equals(args.get("sessionId")),"renew only matching session");yield new LinkedHashMap<>(screen);}
   case "foreground_stop"->{check(screen.get("sessionId").equals(args.get("sessionId")),"stop only matching session");screen.put("active",false);yield Map.of("ok",true);}
   case "activities"->Map.of("activityTasks",List.of());default->throw new AssertionError("Foreground must never touch private display: "+op);
  };},()->clock[0]);
  rejects(()->tasks.begin(Map.of("mode","foreground"),"a"));check(calls.isEmpty(),"confirmation preflight precedes all resource operations");
  String id=(String)tasks.begin(Map.of("mode","foreground","confirmed",true,"leaseMinutes",1),"a").get("sessionId");check(tasks.use(id,"a")==0&&calls.contains("foreground_renew"),"main screen has renewable session");
  String activeId=id;rejects(()->tasks.use(activeId,"b"));rejects(()->tasks.use(activeId,"a",2));
  check(Boolean.FALSE.equals(tasks.begin(Map.of("mode","foreground","confirmed",true),"b").get("ok")),"screen owner is exclusive");
  screen.put("active",false);rejects(()->tasks.use(activeId,"a"));screen.put("active",true);
  var done=tasks.finish(Map.of("sessionId",id),"a");check(((List<?>)done.get("released")).contains("screenControl")&&!calls.contains("display_stop"),"finish releases screen control without destroying main display");
  id=(String)tasks.begin(Map.of("mode","foreground","confirmed",true,"leaseMinutes",1),"a").get("sessionId");screen.put("sessionId","replacement");calls.clear();tasks.finish(Map.of("sessionId",id),"a");check(!calls.contains("foreground_stop"),"cleanup preserves replacement foreground owner");screen.put("active",false);
  id=(String)tasks.begin(Map.of("mode","foreground","confirmed",true,"leaseMinutes",1),"a").get("sessionId");clock[0]+=60001;tasks.sweep();check(tasks.inspect(id,"a").get("state").equals("finished"),"expired foreground session releases control");
  try(var paths=java.nio.file.Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
  ToolCatalog.validate("android.task",Map.of("operation","begin","requestId","r","mode","foreground","confirmed",true));
  rejects(()->ToolCatalog.validate("android.task",Map.of("operation","begin","requestId","r","mode","foreground")));
  rejects(()->ToolCatalog.validate("android.task",Map.of("operation","begin","requestId","r","confirmed",true)));
  rejects(()->ToolCatalog.validate("android.task",Map.of("operation","begin","requestId","r","mode","foreground","confirmed",true,"width",1080)));
 }catch(Exception e){throw new AssertionError(e);}}

 static void sharedSessions(){try{
  var root=java.nio.file.Files.createTempDirectory("shared-sessions-test");long[] clock={0};int[] next={10};
  Map<Integer,Map<String,Object>> displays=new LinkedHashMap<>();List<Object> stopped=new ArrayList<>();
  var tasks=new TaskResources(root,(op,args)->switch(op){
   case "display_status"->{Object id=args.get("displayId");yield id instanceof Number n&&displays.containsKey(n.intValue())?new LinkedHashMap<>(displays.get(n.intValue())):new LinkedHashMap<>(Map.of("active",false));}
   case "display_start"->{int id=next[0]++;var d=new LinkedHashMap<String,Object>(Json.mapOf("active",true,"created",true,"displayId",id,"createdAt",clock[0]));displays.put(id,d);yield new LinkedHashMap<>(d);}
   case "display_stop"->{Object id=args.get("displayId");stopped.add(id);if(id instanceof Number n)displays.remove(n.intValue());yield Map.of("ok",true);}
   case "activities"->Map.of("activityTasks",List.of());
   default->throw new AssertionError(op);
  },()->clock[0]);
  String a=(String)tasks.begin(Map.of(),"agentA").get("sessionId"),b=(String)tasks.begin(Map.of(),"agentB").get("sessionId"),c=(String)tasks.begin(Map.of(),"agentC").get("sessionId");
  check(a!=null&&b!=null&&c!=null,"several agents begin private sessions at once");
  check(tasks.use(a,"agentA")!=tasks.use(b,"agentB")&&tasks.use(b,"agentB")!=tasks.use(c,"agentC"),"each private session gets its own display");
  tasks.finish(Map.of("sessionId",b),"agentB");check(stopped.equals(List.of(11))&&displays.containsKey(10)&&displays.containsKey(12),"finishing one session stops only its display");
  rejects(()->tasks.use(a,"agentC"));rejects(()->tasks.share(Map.of("sessionId",a),"agentC"));
  String code=(String)tasks.share(Map.of("sessionId",a),"agentA").get("joinCode");check(code.matches("join_[a-f0-9]{32}"),"owner gets a join code");
  var joined=tasks.begin(Map.of("join",code),"agentC");check(a.equals(joined.get("sessionId"))&&Boolean.TRUE.equals(joined.get("joined")),"join returns the shared session");
  check(tasks.use(a,"agentC")==10,"joined agent acts on the shared display");
  check(tasks.list("agentC").get("sessions") instanceof List<?> l&&l.size()==2,"list shows joined and own sessions");
  check(((List<?>)tasks.list("agentB").get("sessions")).isEmpty(),"list hides other agents' sessions");
  rejects(()->tasks.share(Map.of("sessionId",a),"agentC"));
  var left=tasks.finish(Map.of("sessionId",a),"agentC");check(Boolean.TRUE.equals(left.get("left"))&&displays.containsKey(10),"member finish leaves without releasing");
  rejects(()->tasks.use(a,"agentC"));
  tasks.begin(Map.of("join",code),"agentC");tasks.finish(Map.of("sessionId",a),"agentA");check(!displays.containsKey(10),"owner finish releases the shared display");
  rejects(()->tasks.use(a,"agentC"));
  check(Boolean.FALSE.equals(tasks.begin(Map.of("join","join_"+"0".repeat(32)),"agentC").get("ok")),"unknown join code is refused");
  String d=(String)tasks.begin(Map.of(),"agentA").get("sessionId");String late=(String)tasks.share(Map.of("sessionId",d),"agentA").get("joinCode");clock[0]+=16*60000L;
  check(Boolean.FALSE.equals(tasks.begin(Map.of("join",late),"agentB").get("ok")),"expired join code is refused");
  ToolCatalog.validate("android.task",Map.of("operation","begin","requestId","r","join",late));ToolCatalog.validate("android.task",Map.of("operation","share","requestId","r","sessionId",d));ToolCatalog.validate("android.task",Map.of("operation","list"));
  // Verify compact single-window omission and session auto-diff
  var autoObs=new Observations(()->1000L);
  var singleWin=tree(List.of(node(0,-1,"btn1","Save",true)));
  var firstCompact=autoObs.observe(singleWin,40,null,false,false);
  check(!firstCompact.containsKey("windows"),"single-window compact observation omits windows array");
  var secondCompact=autoObs.observe(singleWin,40,null,false,false);
  check(Boolean.TRUE.equals(secondCompact.get("delta"))&&((List<?>)secondCompact.get("controls")).isEmpty(),"unchanged repeat observation auto-diffs");
  var forcedFull=autoObs.observe(singleWin,40,null,true,false);
  check(!Boolean.TRUE.equals(forcedFull.get("delta"))&&((List<?>)forcedFull.get("controls")).size()==1,"full:true bypasses auto-diff");
  try(var paths=java.nio.file.Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
 }catch(Exception e){throw new AssertionError(e);}}
}
