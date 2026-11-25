package com.deviceagent.tools;
import java.util.*;
import java.nio.file.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * Durable session ownership and leases. Only matching display incarnations can be released.
 * Each private session gets its own display, so any number of agents can work side by side;
 * the display pool refuses new displays only when the phone is low on memory. The real
 * screen stays exclusive. An owner can share a session so other connections join its display.
 */
 public final class TaskResources {
 private static final long LEASE_PERSIST_INTERVAL_MS = 60_000L;
 private final Path root;private final BiFunction<String,Map<String,Object>,Map<String,Object>> call;private final LongSupplier clock;
 private final Map<String,Map<String,Object>> stateCache=new LinkedHashMap<>();
 private final Map<String,Long> lastSavedExpiry=new HashMap<>();
 private final Set<String> userTakeovers=new HashSet<>();
 public TaskResources(Path root,String pkg,BiFunction<String,Map<String,Object>,Map<String,Object>> call){this(root,call,System::currentTimeMillis);}
 public TaskResources(Path root,BiFunction<String,Map<String,Object>,Map<String,Object>> call,LongSupplier clock){this.root=root;this.call=call;this.clock=clock;}
 private Path directory(String id){if(id==null||!id.matches("session_[a-f0-9]{32}"))throw new IllegalArgumentException("Use sessionId from task_begin");return root.resolve(id);}
 private Map<String,Object> read(String id){
  Map<String,Object> cached=stateCache.get(id);
  if(cached!=null)return cached;
  try{
   Map<String,Object> loaded=Json.object(new String(Files.readAllBytes(directory(id).resolve("state.json")),java.nio.charset.StandardCharsets.UTF_8));
   stateCache.put(id,loaded);
   if(loaded.get("expiresAt") instanceof Number n)lastSavedExpiry.put(id,n.longValue());
   return loaded;
  }catch(Exception e){throw new InputNotSentException("session_unavailable","This session is unavailable. Begin a new task.");}
 }
 private void save(String id,Map<String,Object> state){try{Path dir=directory(id);Files.createDirectories(dir,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));com.deviceagent.crypto.PrivateState.write(dir.resolve("state.json"),Json.stringify(state));stateCache.put(id,state);if(state.get("expiresAt") instanceof Number n)lastSavedExpiry.put(id,n.longValue());}catch(Exception e){throw new IllegalStateException("The task resource record could not be saved",e);}}
 private static List<?> members(Map<String,Object> state){return state.get("members") instanceof List<?> list?list:List.of();}
 private void owner(Map<String,Object> state,String owner){if(!Objects.equals(state.get("owner"),owner)&&!members(state).contains(owner))throw new InputNotSentException("session_not_owned","This session belongs to another connection. Begin your own task, or ask its owner to share it.");}
 private void creator(Map<String,Object> state,String owner){if(!Objects.equals(state.get("owner"),owner))throw new InputNotSentException("session_not_owned","Only the connection that began this session can share it.");}
 private static boolean foreground(Map<String,Object> state){return "foreground".equals(state.get("mode"));}
 private Map<String,Object> status(Map<String,Object> state){return foreground(state)?call.apply("foreground_status",Map.of()):call.apply("display_status",state.get("display") instanceof Number d?Map.of("displayId",d.intValue()):Map.of());}
 public synchronized Map<String,Object> begin(Map<String,Object> args,String owner){
  if(args.get("join") instanceof String code)return join(code,owner);
  boolean foreground=foreground(args);
  if(foreground&&!Boolean.TRUE.equals(args.get("confirmed")))throw new IllegalArgumentException("Foreground sessions require confirmed:true after user authorization");
  sweep();
  // Only the real screen is exclusive. Each private session gets a display of its own.
  if(foreground&&Boolean.TRUE.equals(status(args).get("active")))return Json.mapOf("ok",false,"code","display_in_use","inputDelivered",false,"error","Another task is using the screen. Finish it first, or ask its owner to share the session.");
  String id="session_"+UUID.randomUUID().toString().replace("-","");long lease=GesturePlan.integer(args,"leaseMinutes",15,1,30)*60000L;
  var state=Json.mapOf("sessionId",id,"owner",owner,"mode",foreground?"foreground":"private","leaseMs",lease,"expiresAt",clock.getAsLong()+lease,"state","starting");save(id,state);
  try{
   Files.createDirectories(directory(id).resolve("files"),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
   var options=new LinkedHashMap<>(args);options.put("idleMinutes",0);options.put("sessionId",id);options.put("expiresAt",state.get("expiresAt"));
   var display=call.apply(foreground?"foreground_start":"display_start",options);
   if(!Boolean.TRUE.equals(display.get("created"))){if(!foreground)call.apply("display_stop",display.get("displayId") instanceof Number d?Map.of("displayId",d.intValue()):Map.of("displayId",-1));throw new InputNotSentException(Observations.str(display.getOrDefault("code","task_start_failed")),Observations.str(display.getOrDefault("error","The session could not access its display")));}
   state.put("display",display.get("displayId"));state.put("createdAt",display.get("createdAt"));state.put("state","active");save(id,state);
   return Json.mapOf("ok",true,"sessionId",id,"mode",state.get("mode"),"display",state.get("display"),"leaseExpiresAt",state.get("expiresAt"),"temporaryDirectory",directory(id).resolve("files").toString());
  }catch(Exception e){return Json.mapOf("ok",false,"sessionId",id,"inputDelivered",false,"code",e instanceof InputNotSentException input?input.code:"task_start_failed","error",e.getMessage(),"recovery","Finish this session to release any resources it created.");}
 }
 public synchronized int use(String id,String owner){return use(id,owner,null);}
 public synchronized int use(String id,String owner,Integer requestedDisplay){
  return use(id,owner,requestedDisplay,true);
 }
 public synchronized int use(String id,String owner,Integer requestedDisplay,boolean renew){
  var state=read(id);owner(state,owner);
  if(!"active".equals(state.get("state"))||((Number)state.get("expiresAt")).longValue()<=clock.getAsLong())throw new InputNotSentException("session_expired","This session expired. Begin a new task.");
  if(userTakeovers.contains(id))throw new InputNotSentException("user_in_control","The user took over this screen. Wait until they hand control back.");
  if(requestedDisplay!=null&&!sameNumber(requestedDisplay,state.get("display")))throw new InputNotSentException("wrong_session_display","The display does not belong to this session.");
  var display=status(state);if(!owns(state,display))throw new InputNotSentException("display_unavailable","The session display is no longer available. Finish it and begin a new task.");
  if(!renew)return ((Number)state.get("display")).intValue();
  long expiry=clock.getAsLong()+((Number)state.get("leaseMs")).longValue();
  if(foreground(state)){var renewed=call.apply("foreground_renew",Map.of("sessionId",id,"expiresAt",expiry));if(!owns(state,renewed))throw new InputNotSentException("screen_control_stopped","Screen control stopped. Begin a new task when the user is ready.");}
  touchLeaseLocked(id,state,expiry);
  return ((Number)state.get("display")).intValue();
 }
 private void touchLeaseLocked(String id,Map<String,Object> state,long expiry){
  state.put("expiresAt",expiry);
  Long savedExp=lastSavedExpiry.get(id);
  if(savedExp==null||expiry-savedExp>=LEASE_PERSIST_INTERVAL_MS)save(id,state);
 }
 /** Pauses or resumes agent control on a session when the user takes over on the device. */
 public synchronized Map<String,Object> setUserTakeover(String id,boolean active){
  var state=read(id);
  if(!"active".equals(state.get("state"))||((Number)state.get("expiresAt")).longValue()<=clock.getAsLong())throw new InputNotSentException("session_expired","This session expired.");
  if(active)userTakeovers.add(id);else userTakeovers.remove(id);
  touchLeaseLocked(id,state,clock.getAsLong()+((Number)state.get("leaseMs")).longValue());
  return Json.mapOf("ok",true,"sessionId",id,"display",state.get("display"),"userInControl",active);
 }
 public synchronized boolean isUserInControl(String id){return id!=null&&userTakeovers.contains(id);}
 /** Resolves an active session's display for direct user interaction and keeps its lease alive. */
 public synchronized int activeDisplayForAdmin(String id){
  var state=read(id);
  if(!"active".equals(state.get("state"))||((Number)state.get("expiresAt")).longValue()<=clock.getAsLong())throw new InputNotSentException("session_expired","This session expired.");
  var display=status(state);
  if(!owns(state,display))throw new InputNotSentException("display_unavailable","This screen is no longer active.");
  touchLeaseLocked(id,state,clock.getAsLong()+((Number)state.get("leaseMs")).longValue());
  return ((Number)state.get("display")).intValue();
 }
 /** Open sessions this connection began or joined, so an agent can resume or finish one whose ID it lost. */
 public synchronized Map<String,Object> list(String owner){
  sweep();List<Map<String,Object>> sessions=new ArrayList<>();
  if(Files.isDirectory(root))try(var dirs=Files.list(root)){for(Path dir:dirs.sorted().collect(java.util.stream.Collectors.toList())){
   String id=dir.getFileName().toString();if(!id.matches("session_[a-f0-9]{32}"))continue;
   Map<String,Object> state;try{state=read(id);}catch(RuntimeException e){continue;}
   if("finished".equals(state.get("state"))||!Objects.equals(state.get("owner"),owner)&&!members(state).contains(owner))continue;
   sessions.add(Json.mapOf("sessionId",id,"mode",state.getOrDefault("mode","private"),"display",state.get("display"),"state",state.get("state"),"leaseExpiresAt",state.get("expiresAt"),"role",Objects.equals(state.get("owner"),owner)?"owner":"member","members",members(state).size()));
  }}catch(java.io.IOException ignored){}
  return Json.mapOf("ok",true,"sessions",sessions);
 }
 /** All active sessions across all agents, for the phone app's Live Backstage view. */
 public synchronized List<Map<String,Object>> listAllActive(){
  sweep();List<Map<String,Object>> sessions=new ArrayList<>();
  if(Files.isDirectory(root))try(var dirs=Files.list(root)){for(Path dir:dirs.sorted().collect(java.util.stream.Collectors.toList())){
   String id=dir.getFileName().toString();if(!id.matches("session_[a-f0-9]{32}"))continue;
   Map<String,Object> state;try{state=read(id);}catch(RuntimeException e){continue;}
   if("finished".equals(state.get("state")))continue;
   sessions.add(Json.mapOf("sessionId",id,"owner",state.getOrDefault("owner","local"),"mode",state.getOrDefault("mode","private"),"display",state.get("display"),"state",state.get("state"),"createdAt",state.get("createdAt"),"leaseExpiresAt",state.get("expiresAt"),"userInControl",userTakeovers.contains(id)));
  }}catch(java.io.IOException ignored){}
  return sessions;
 }
 /** Immediate user-initiated stop from the phone app, regardless of agent owner. */
 public synchronized Map<String,Object> forceFinish(String id){
  var state=read(id);return cleanup(id,state);
 }
 public synchronized Map<String,Object> inspect(String id,String owner){var state=read(id);owner(state,owner);var display=status(state);var out=Json.mapOf("ok",true,"sessionId",id,"mode",state.getOrDefault("mode","private"),"leaseExpiresAt",state.get("expiresAt"),"state",state.get("state"),"userInControl",userTakeovers.contains(id),"role",Objects.equals(state.get("owner"),owner)?"owner":"member","members",members(state).size(),"ownedDisplay",owns(state,display)?Json.mapOf("display",display.get("displayId"),"width",display.get("width"),"height",display.get("height"),"state",display.get("displayState")):null);if(owns(state,display))out.putAll(call.apply("activities",Map.of("display",state.get("display"))));return out;}
 private static boolean sameNumber(Object a,Object b){return a instanceof Number x&&b instanceof Number y&&x.longValue()==y.longValue();}
 private boolean owns(Map<String,Object> state,Map<String,Object> display){return Boolean.TRUE.equals(display.get("active"))&&(foreground(state)?Objects.equals(state.get("sessionId"),display.get("sessionId")):sameNumber(state.get("display"),display.get("displayId"))&&sameNumber(state.get("createdAt"),display.get("createdAt")));}
 /** The owner's finish releases the display for everyone; a member's finish only leaves. */
 public synchronized Map<String,Object> finish(Map<String,Object> args,String owner){
  String id=Json.string(args,"sessionId");var state=read(id);owner(state,owner);
  if(!Objects.equals(state.get("owner"),owner)){var left=new ArrayList<Object>(members(state));left.remove(owner);state.put("members",left);save(id,state);return Json.mapOf("ok",true,"sessionId",id,"left",true,"released",List.of());}
  return cleanup(id,state);
 }
 private static final long SHARE_MS=15*60000L;
 /** Lets other connections join this session's display until the code expires or the session ends. */
 public synchronized Map<String,Object> share(Map<String,Object> args,String owner){
  String id=Json.string(args,"sessionId");var state=read(id);creator(state,owner);
  if(!"active".equals(state.get("state")))throw new InputNotSentException("session_expired","This session ended. Begin a new task.");
  String code="join_"+UUID.randomUUID().toString().replace("-","");
  Map<String,Object> shares=new LinkedHashMap<>();if(state.get("shares") instanceof Map<?,?> old)old.forEach((k,v)->{if(v instanceof Number at&&at.longValue()>clock.getAsLong())shares.put(k.toString(),v);});
  long expires=clock.getAsLong()+SHARE_MS;shares.put(hash(code),expires);state.put("shares",shares);save(id,state);
  return Json.mapOf("ok",true,"sessionId",id,"joinCode",code,"joinExpiresAt",expires,"display",state.get("display"),"mode",state.get("mode"));
 }
 private Map<String,Object> join(String code,String owner){
  if(!code.matches("join_[a-f0-9]{32}"))throw new IllegalArgumentException("Use the joinCode from task_share");
  sweep();String key=hash(code);
  if(Files.isDirectory(root))try(var dirs=Files.list(root)){for(Path dir:dirs.collect(java.util.stream.Collectors.toList())){
   String id=dir.getFileName().toString();if(!id.matches("session_[a-f0-9]{32}"))continue;
   Map<String,Object> state;try{state=read(id);}catch(RuntimeException e){continue;}
   if(!(state.get("shares") instanceof Map<?,?> shares)||!(shares.get(key) instanceof Number at)||at.longValue()<=clock.getAsLong()||!"active".equals(state.get("state")))continue;
   if(!Objects.equals(state.get("owner"),owner)&&!members(state).contains(owner)){var list=new ArrayList<Object>(members(state));list.add(owner);state.put("members",list);save(id,state);}
   return Json.mapOf("ok",true,"sessionId",id,"joined",true,"mode",state.get("mode"),"display",state.get("display"),"leaseExpiresAt",state.get("expiresAt"),"temporaryDirectory",directory(id).resolve("files").toString());
  }}catch(java.io.IOException ignored){}
  return Json.mapOf("ok",false,"code","join_unavailable","inputDelivered",false,"error","That join code expired or its session ended. Ask the session owner to share it again.");
 }
 private static String hash(String value){try{var out=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> cleanup(String id,Map<String,Object> state){
  if("finished".equals(state.get("state")))return Json.mapOf("ok",true,"sessionId",id,"released",List.of());
  List<String> released=new ArrayList<>();var display=status(state);
  if(owns(state,display)){var stopped=call.apply(foreground(state)?"foreground_stop":"display_stop",foreground(state)?Map.of("sessionId",id):Map.of("sessionId",id,"displayId",((Number)state.get("display")).intValue()));if(Boolean.FALSE.equals(stopped.get("ok")))return Json.mapOf("ok",false,"sessionId",id,"code","cleanup_incomplete","released",released,"error","The display could not be released. Run task_finish again.");released.add(foreground(state)?"screenControl":"display");}
  try{Path files=directory(id).resolve("files");if(Files.exists(files,LinkOption.NOFOLLOW_LINKS)){try(var paths=Files.walk(files)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(path);}released.add("temporaryFiles");}}
  catch(Exception e){return Json.mapOf("ok",false,"sessionId",id,"code","cleanup_incomplete","released",released,"error","Some files remain. Run task_finish again.");}
  userTakeovers.remove(id);
  state.put("state","finished");state.put("finishedAt",clock.getAsLong());save(id,state);
  return Json.mapOf("ok",true,"sessionId",id,"released",released);
 }
 /** A finished session's record stays this long, so a repeated finish still gets its answer. */
 static final long FINISHED_RETAIN_MS=10*60000L;
 /** Records read from storage in one sweep; the rest wait for the next sweep. */
 static final int SWEEP_READS=200;
 /** End expired sessions and delete the folders of sessions that finished a while ago. Never touches an active session. */
 public synchronized void sweep(){
  if(!Files.isDirectory(root))return;
  int reads=0;long now=clock.getAsLong();
  try(var dirs=Files.list(root)){for(Path dir:dirs.collect(java.util.stream.Collectors.toList())){
   String id=dir.getFileName().toString();if(!id.matches("session_[a-f0-9]{32}"))continue;
   if(!stateCache.containsKey(id)&&reads++>=SWEEP_READS)continue;
   try{
    var state=read(id);
    if(!"finished".equals(state.get("state"))&&((Number)state.get("expiresAt")).longValue()<=now)cleanup(id,state);
    if("finished".equals(state.get("state"))&&(state.get("finishedAt") instanceof Number f?f.longValue():((Number)state.get("expiresAt")).longValue())+FINISHED_RETAIN_MS<=now)delete(id);
   }catch(RuntimeException ignored){}
  }}catch(Exception ignored){}
 }
 private void delete(String id){
  try(var paths=Files.walk(directory(id))){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(path);}
  catch(Exception e){return;}
  stateCache.remove(id);lastSavedExpiry.remove(id);
 }
}
