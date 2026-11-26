package com.deviceagent.tools;
import java.util.*;
public final class EvidenceHostTest {
 static void check(boolean x,String why){if(!x)throw new AssertionError(why);}
 static Map<String,Object> node(int index,int parent,String id,String text,boolean click){return Json.mapOf("index",index,"parent",parent,"nodeId",id,"text",text,"contentDescription","","resourceId","p:id/"+id,"class",click?"Button":"TextView","clickable",click,"visible",true,"enabled",true,"package","p","window",1,"windowKey","window-p","bounds","[0,0][100,100]");}
 static Map<String,Object> tree(List<Map<String,Object>> ns){return Json.mapOf("ok",true,"display",2,"focusedActivity","p/.Main","nodes",ns,"windows",List.of(Json.mapOf("id",1,"key","window-p","focused",true,"title","Page","type","application")));}
 static Map<String,Object> exists(String label){return Map.of("kind","exists","selector",Map.of("label",label));}
 static class Fake implements EvidenceFlow.Backend {
  Map<String,Object> screenControl=Json.mapOf("active",false,"displayId",0);
  int writes,reads,screenFailures,screens;String last;Map<String,Object> arguments;boolean done,failRead,unknown,uncertainReceipt;int stale;boolean changeLabel;String focus="p/.Main";List<Map<String,Object>> nodes=new ArrayList<>(List.of(node(0,-1,"1","Directions",true)));
  public Map<String,Object> action(String n,Map<String,Object> a){writes++;if(stale>0){stale--;nodes.get(0).put(changeLabel?"text":"bounds",changeLabel?"Different":"[5,5][105,105]");throw new InputNotSentException("stale_target","Changed before input");}if(uncertainReceipt)return Json.mapOf("ok",false,"inputDelivered",null,"pointersReleased",true);last=n;arguments=new LinkedHashMap<>(a);if(unknown)throw new IllegalStateException("lost receipt");done=true;return Json.mapOf("ok",true,"inputDelivered",true,"display",2);}
  public Map<String,Object> tree(int display){reads++;if(failRead)throw new IllegalStateException("read failed");var ns=new ArrayList<>(nodes);if(done)ns.add(node(7,-1,"7","Done",true));return EvidenceHostTest.tree(ns);}
  public Map<String,Object> display(){return Json.mapOf("active",true,"displayId",2,"width",200,"height",200);}
  public Map<String,Object> display(int d){return d==0?screenControl:display();}
  public String focus(int d){return focus;}
  public Map<String,Object> screen(int d){screens++;if(screenFailures-->0)throw new IllegalStateException("No frame yet");return Json.mapOf("displayId",2,"width",100,"height",100,"sourceWidth",200,"sourceHeight",200,"mime","image/png","pngBase64","AA==");}
 }
 public static void run(){
  Fake interrupted=new Fake();interrupted.uncertainReceipt=true;var receipt=new EvidenceFlow(interrupted).run("android.key",Map.of("display",2,"key","A","observe",false));check(receipt.get("inputDelivered")==null&&"unknown".equals(receipt.get("outcome")),"explicit unknown delivery must not become false");
  Fake b=new Fake();EvidenceFlow flow=new EvidenceFlow(b);
  var invalid=flow.run("android.open",Map.of("display",2,"url","javascript:alert(1)"));check(invalid.get("code").equals("invalid_argument")&&Boolean.FALSE.equals(invalid.get("inputDelivered"))&&b.reads==0&&b.writes==0,"URL preflight before reads or writes");
  invalid=flow.run("android.tap",Map.of("display",2,"selector",Map.of("label","x","unexpected",true)));check(b.reads==0&&b.writes==0&&invalid.get("code").equals("invalid_argument"),"selector preflight");
  var opened=flow.run("android.open",Map.of("display",2,"url","https://example.com","verify",exists("Done"),"timeoutMs",0));check(b.last.equals("android.open")&&b.arguments.get("url").equals("https://example.com")&&Boolean.TRUE.equals(opened.get("ok")),"URL reaches shared open");
  b=new Fake();flow=new EvidenceFlow(b);var seen=flow.run("android.observe",Map.of("display",2,"image","always"));var controls=(List<?>)seen.get("controls");String id=(String)((Map<?,?>)controls.get(0)).get("id");
  b.nodes.add(node(3,-1,"clock","10:02",false));
  var tap=flow.run("android.tap",Json.mapOf("display",2,"target",id,"observation",seen.get("observation"),"holdMs",700,"verify",exists("Done"),"timeoutMs",0));check(b.writes==1&&b.last.equals("target")&&b.arguments.get("holdMs").equals(700)&&Boolean.TRUE.equals(tap.get("ok")),"unrelated clock permits held target and verification");
  b.nodes.get(0).put("text","Changed");tap=flow.run("android.tap",Json.mapOf("display",2,"target",id,"observation",seen.get("observation")));check(b.writes==1&&tap.get("code").equals("stale_target")&&Boolean.FALSE.equals(tap.get("inputDelivered")),"changed target rejected");
  var missing=flow.run("android.tap",Map.of("display",2,"selector",Map.of("label","Missing")));check(missing.get("code").equals("target_not_found")&&missing.containsKey("observation"),"lookup recovery returns current observation");
  b=new Fake();flow=new EvidenceFlow(b);var field=node(2,-1,"field","",false);field.put("editable",true);field.put("hint","Search");b.nodes.add(field);
  var typed=flow.run("android.type",Map.of("display",2,"selector",Map.of("resourceId","p:id/field"),"text","café 日本語 🙂","mode","replace"));check(b.arguments.get("operation").equals("type")&&b.arguments.get("text").equals("café 日本語 🙂")&&Boolean.TRUE.equals(typed.get("ok")),"bound Unicode field action");
  b=new Fake();flow=new EvidenceFlow(b);b.unknown=true;var unknown=flow.run("android.key",Map.of("display",2,"key","BACK"));check(unknown.get("inputDelivered")==null&&b.writes==1,"unknown writes never retry");
  b=new Fake();flow=new EvidenceFlow(b);b.failRead=true;var unread=flow.run("android.tap",Map.of("display",2,"x",2,"y",3));check(b.reads==3&&b.writes==0&&Boolean.FALSE.equals(unread.get("inputDelivered")),"bounded read retries and no write");
  b=new Fake();flow=new EvidenceFlow(b);
  var sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.open","arguments",Map.of("url","https://example.com","verify",exists("Done"),"timeoutMs",0)))),flow::run);check(b.last.equals("android.open")&&b.arguments.get("url").equals("https://example.com")&&sequence.get("completedSteps").equals(1),"sequence URL uses same action");
  b=new Fake();flow=new EvidenceFlow(b);sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","BACK")),Map.of("tool","android.open","arguments",Map.of("url","ftp://bad")))),flow::run);check(b.writes==0&&b.reads==0&&sequence.get("code").equals("invalid_argument"),"entire sequence preflight");
  b=new Fake();flow=new EvidenceFlow(b);sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","BACK","verify",exists("Missing"),"timeoutMs",0)),Map.of("tool","android.key","arguments",Map.of("key","ENTER")))),flow::run);check(b.writes==1&&sequence.get("completedSteps").equals(0)&&sequence.get("stoppedAt").equals(0)&&Boolean.TRUE.equals(sequence.get("inputDelivered"))&&sequence.containsKey("observation"),"sequence failure preserves delivery and stops");
  b=new Fake();flow=new EvidenceFlow(b);sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","BACK"),"before",exists("Missing")))),flow::run);check(b.writes==0&&((List<?>)sequence.get("steps")).size()==1,"precondition failure gets a receipt");
  b=new Fake();flow=new EvidenceFlow(b);sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","BACK")),Map.of("tool","android.key","arguments",Map.of("key","ENTER")))),flow::run);
  check(Boolean.TRUE.equals(sequence.get("ok"))&&b.writes==2&&sequence.get("completedSteps").equals(2)&&!((Map<?,?>)((List<?>)sequence.get("steps")).get(0)).containsKey("observation")&&sequence.containsKey("observation"),"intermediate sequence step skips observe by default");
  b=new Fake();flow=new EvidenceFlow(b);sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label","NoSuchPopup")),"optional",true),Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label","Missing")),"before",exists("Missing"),"optional",true),Map.of("tool","android.tap","arguments",Map.of("selector",Map.of("label","Directions"))))),flow::run);
  check(Boolean.TRUE.equals(sequence.get("ok"))&&b.writes==1&&sequence.get("completedSteps").equals(1)&&Boolean.TRUE.equals(((Map<?,?>)((List<?>)sequence.get("steps")).get(0)).get("skipped"))&&Boolean.TRUE.equals(((Map<?,?>)((List<?>)sequence.get("steps")).get(1)).get("skipped"))&&sequence.containsKey("observation"),"optional steps skip when input was not sent and continue");
  b=new Fake();flow=new EvidenceFlow(b);sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","BACK","verify",exists("Missing"),"timeoutMs",0),"optional",true),Map.of("tool","android.key","arguments",Map.of("key","ENTER")))),flow::run);
  check(Boolean.FALSE.equals(sequence.get("ok"))&&b.writes==1&&Boolean.TRUE.equals(sequence.get("inputDelivered")),"optional step never skips after input was delivered");
  int[] calls={0};try(var expired=ActionDeadline.until(System.currentTimeMillis()-1)){sequence=LocalSequence.run(Map.of("display",2,"deadlineMs",30000,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","BACK")))),(n,a)->{calls[0]++;return Json.mapOf("ok",true,"observation","o");});}check(calls[0]==0&&sequence.get("code").equals("deadline_exceeded"),"expired caller deadline stops before input without being extended by the sequence");
  check(MediaState.name(3).equals("playing")&&MediaState.name(2).equals("paused")&&MediaState.name(null).equals("unknown")&&MediaState.name(99).equals("unknown"),"readable media states preserve unknown values");
  scrollChecks();screenshotChecks();recoveryChecks();visualInputChecks();foregroundChecks();windowChecks();
  check(!seen.containsKey("timing")&&!seen.containsKey("ui"),"compact defaults");
  phase1Checks();
  System.out.println("PASS shared primitives: preflight, target resolution, Unicode forwarding, reads, unknown writes, URL parity, sequence recovery");
 }
 static void phase1Checks(){
  var cfg=new com.deviceagent.daemon.DaemonConfig();cfg.blockedPackages=new ArrayList<>(List.of("com.bank.app"));
  var fence=new PrivacyFence(cfg);
  check(fence.isBlocked("com.bank.app")&&!fence.isBlocked("com.android.settings"),"privacy fence matches blocked packages");
  try{fence.requireComponentAllowed("com.bank.app/.MainActivity");throw new AssertionError("blocked app allowed");}catch(InputNotSentException e){check("blocked_app".equals(e.code),"blocked_app code");}
  check(!cfg.allowShell&&!cfg.toJson().contains("allowShell"),"allowShell defaults off and omitted");
  cfg.allowShell=true;check(cfg.toJson().contains("\"allowShell\":true"),"allowShell serializes when enabled");
  var tl=new ActivityTimeline(null);tl.record("agt_1","android.open","com.android.settings","succeeded",null,2);
  check(tl.recent(10).size()==1&&"android.open".equals(tl.recent(10).get(0).get("tool")),"timeline records activity");
  var inbox=new SharedInbox(null);var added=inbox.add("https://example.com","Link","text/plain","com.android.chrome",List.of());
  check(inbox.list(10).size()==1&&inbox.remove((String)added.get("itemId"))&&inbox.list(10).isEmpty(),"shared inbox add/list/remove");
  var cfms=new PendingConfirmations();var item=cfms.create("session_a",2,"agt_1","Send money?","Review","com.example","","image/webp",5000);
  new Thread(()->{try{Thread.sleep(40);}catch(InterruptedException ignored){}cfms.decide(item.confirmId,"approved");}).start();
  check("approved".equals(cfms.awaitDecision(item.confirmId,2000)),"pending confirmation resolves");
  var handoffItem=cfms.create("session_a",2,"agt_1","handoff","Sign in required","Enter your password","com.example","","image/webp",5000);
  check("handoff".equals(handoffItem.kind)&&cfms.markTakenOver(handoffItem.confirmId,60_000L)&&handoffItem.takenOver,"handoff confirmation supports takeover extension");
  new Thread(()->{try{Thread.sleep(40);}catch(InterruptedException ignored){}cfms.decide(handoffItem.confirmId,"completed");}).start();
  check("completed".equals(cfms.awaitDecision(handoffItem.confirmId,2000)),"handoff confirmation resolves completed on hand back");
  var jank=DevDiagnostics.parseGfxInfo("com.example","Total frames rendered: 120\nJanky frames: 6 (5.00%)\n50th percentile: 6ms\n90th percentile: 11ms\n95th percentile: 15ms\n99th percentile: 22ms\nNumber Missed Vsync: 2\n");
  check(Integer.valueOf(120).equals(jank.get("totalFrames"))&&Integer.valueOf(6).equals(jank.get("jankyFrames"))&&Integer.valueOf(22).equals(jank.get("p99Ms")),"gfxinfo jank parser");
  var anr=DevDiagnostics.parseAnrs("11-22 00:10:00.123  1000  1234 I am_anr  : [0,5678,com.example,123,Input dispatching timed out]\n","com.example",5);
  check(Integer.valueOf(1).equals(anr.get("count")),"am_anr parser");
 }
 static void foregroundChecks(){
  check(TaskSnapshot.focusIdentity("mCurrentFocus=Window{123 u0 NotificationShade}").equals("Window{123 u0 NotificationShade}"),"system windows have a screenshot identity without an activity component");
  check(TaskSnapshot.focusIdentity("mCurrentFocus=null").isEmpty(),"missing focus cannot guard input");
  Fake b=new Fake();EvidenceFlow f=new EvidenceFlow(b);String session="session_"+"b".repeat(32);
  var args=Json.mapOf("sessionId",session,"display",0,"key","HOME","observe",false);
  var result=f.run("android.key",args);check(Boolean.FALSE.equals(result.get("inputDelivered"))&&b.writes==0&&b.reads==0,"main-screen input requires active screen control");
  b.screenControl.putAll(Map.of("active",true,"sessionId","someone-else"));result=f.run("android.key",args);check(Boolean.FALSE.equals(result.get("inputDelivered"))&&b.writes==0,"main-screen input requires matching session");
  b.screenControl.put("sessionId",session);result=f.run("android.key",args);check(Boolean.TRUE.equals(result.get("inputDelivered"))&&b.writes==1&&b.reads==0,"owned global key works without readable app or unlocked screen");
  b.screenControl.put("active",false);result=f.run("android.key",args);check(Boolean.FALSE.equals(result.get("inputDelivered"))&&b.writes==1,"pause blocks next input");
  result=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.key","arguments",Map.of("key","ENTER")),Map.of("tool","android.key","arguments",Map.of("key","HOME")))),f::run);
  check(Boolean.FALSE.equals(result.get("inputDelivered"))&&b.writes==1&&b.reads==0,"private sequence rejects later global key before first input");
  result=f.run("android.key",Map.of("display",2,"key","A","modifiers",List.of("META"),"observe",false));check(Boolean.FALSE.equals(result.get("inputDelivered"))&&b.writes==1,"private shortcuts cannot invoke global Meta actions");
 }
 static void windowChecks(){
  var app=node(0,-1,"app","Save",true);var keyboard=node(1,-1,"keyboard","Save",true);keyboard.put("window",2);keyboard.put("windowKey","ime-root");
  var page=tree(List.of(app,keyboard));page.put("windows",List.of(Json.mapOf("id",1,"focused",true,"type","application","key","window-p","windowId","window_"+"a".repeat(24),"bounds","[0,0][200,200]","layer",0),Json.mapOf("id",2,"focused",false,"type","input_method","title","Keyboard","key","ime-root","windowId","window_"+"b".repeat(24),"bounds","[0,0][200,200]","layer",1)));
  var observations=new Observations(()->0);var seen=observations.observe(page,40,null);check(((List<?>)seen.get("controls")).size()==1&&((List<?>)seen.get("windows")).size()==2,"focused observation lists other windows without merging controls");
  page.put("windowId","window_"+"b".repeat(24));seen=observations.observe(page,40,null);var control=PrimitiveArguments.map(((List<?>)seen.get("controls")).get(0));
  var ref=observations.target(Json.string(seen,"observation"),Json.string(control,"id"),2,page);check(ref.get("windowId").equals("window_"+"b".repeat(24))&&ref.get("nodeId").equals("keyboard")&&seen.get("screen").equals("Keyboard"),"selected keyboard window has usable bound targets");
  check(TargetTap.point(page,keyboard)[0]==50,"nonfocused keyboard target has a visible touch point");
  PrimitiveArguments.map(((List<?>)page.get("windows")).get(1)).put("id",44);keyboard.put("window",44);check(Observations.validateTarget(page,ref).get("nodeId").equals("keyboard"),"window handle and target survive Android numeric ID replacement");
  check(!Observations.windowId(2,1,"p:123:root").equals(Observations.windowId(2,1,"p:456:root"))&&!Observations.windowId(2,1,"p:123:root").equals(Observations.windowId(3,1,"p:123:root")),"window handles distinguish restarted apps and displays");
  var missing=new LinkedHashMap<>(page);missing.put("windowId","window_"+"c".repeat(24));check(Observations.appNodes(missing).isEmpty(),"missing selected window never falls back to application");
  var changed=new LinkedHashMap<>(page);changed.put("windowId","window_"+"a".repeat(24));try{Observations.validateTarget(changed,ref);throw new AssertionError("cross-window target accepted");}catch(InputNotSentException expected){}
  page.remove("windowId");PrimitiveArguments.map(((List<?>)page.get("windows")).get(0)).put("focused",false);PrimitiveArguments.map(((List<?>)page.get("windows")).get(1)).put("focused",true);
  check(Observations.appNodes(page).get(0).get("nodeId").equals("keyboard"),"focused system window works without application root");
 }
 static void visualInputChecks(){
  Fake b=new Fake();b.failRead=true;EvidenceFlow f=new EvidenceFlow(b);
  var seen=f.run("android.observe",Map.of("display",2,"image","only"));
  check(Boolean.TRUE.equals(seen.get("ok"))&&b.reads==0&&seen.get("accessibility").equals("not_requested"),"pixel observation must not wait for accessibility");
  var shot=PrimitiveArguments.map(seen.get("image")).get("screenshotId");
  var key=f.run("android.key",Map.of("display",2,"key","A","modifiers",List.of("CTRL"),"screenshotId",shot,"observe",false));
  check(Boolean.TRUE.equals(key.get("ok"))&&b.reads==0&&b.writes==1&&b.arguments.get("modifiers").equals(List.of("CTRL")),"screenshot-bound shortcut works with no tree");
  var swipe=f.run("android.gesture",Map.of("display",2,"preset","swipe","x1",10,"y1",20,"x2",80,"y2",90,"screenshotId",shot,"observe",false));
  check(Boolean.TRUE.equals(swipe.get("ok"))&&b.reads==0&&b.arguments.get("x1").equals(20.0)&&b.arguments.get("y2").equals(180.0),"all gesture points use screenshot scale without a tree");
  var bad=f.run("android.gesture",Map.of("display",2,"preset","paths","paths",List.of(List.of(Map.of("x",10,"y",10),Map.of("x",100,"y",90))),"screenshotId",shot,"observe",false));
  check(Boolean.FALSE.equals(bad.get("inputDelivered"))&&b.writes==2,"path point outside screenshot rejected before input");
  bad=f.run("android.key",Map.of("display",2,"key","POWER","screenshotId",shot));
  check(Boolean.FALSE.equals(bad.get("inputDelivered"))&&b.writes==2,"global key cannot escape private display");
  bad=f.run("android.observe",Map.of("display",2,"image","only","verify",exists("Ready")));
  check(Boolean.FALSE.equals(bad.get("inputDelivered"))&&b.reads==0,"tree conditions cannot silently verify in pixel-only mode");
  b.focus="other/.Main";bad=f.run("android.key",Map.of("display",2,"key","ENTER","screenshotId",shot,"observe",false));
  check(Boolean.FALSE.equals(bad.get("inputDelivered"))&&b.writes==2,"screenshot guards apply to keys too");
  b=new Fake();b.failRead=true;f=new EvidenceFlow(b);
  var sequence=LocalSequence.run(Map.of("display",2,"steps",List.of(Map.of("tool","android.observe","arguments",Map.of("image","only")),Map.of("tool","android.key","arguments",Map.of("key","A","modifiers",List.of("CTRL"),"screenshotId","step:0","image","only")))),f::run);
  check(Boolean.TRUE.equals(sequence.get("ok"))&&b.reads==0&&b.writes==1&&sequence.get("completedSteps").equals(2),"visual sequence works without initial accessibility and binds screenshot references");
  var reply=Json.mapOf("operation","reply","notificationId","n","actionId","action_0","responses",Map.of(),"requestId","r");
  try{ToolCatalog.validate("android.notification",reply);throw new AssertionError("empty reply accepted");}catch(IllegalArgumentException expected){}
  reply.put("responses",Map.of("text",123));try{ToolCatalog.validate("android.notification",reply);throw new AssertionError("non-text reply accepted");}catch(IllegalArgumentException expected){}
  reply.put("responses",Map.of("text","日本語"));ToolCatalog.validate("android.notification",reply);
  try(var scope=ActionDeadline.until(System.currentTimeMillis()+1000)){
   ActionDeadline.set(System.nanoTime()/1_000_000+10000);check(ActionDeadline.remaining()<=1000,"nested sequences cannot extend the caller's deadline");ActionDeadline.clear();check(ActionDeadline.remaining()<=1000,"ending a sequence preserves the caller deadline");
  }
  check(ActionDeadline.remaining()==Long.MAX_VALUE,"deadline scope releases after the call");
  try(var scope=ActionDeadline.until(System.currentTimeMillis()-1)){try{ActionDeadline.check();throw new AssertionError("expired deadline accepted");}catch(InputNotSentException expected){}}
  b=new Fake();b.failRead=true;b.screenFailures=1;f=new EvidenceFlow(b);
  var opened=f.run("android.open",Map.of("display",2,"package","com.example.fixture","image","only"));
  check(Boolean.TRUE.equals(opened.get("ok"))&&b.writes==1&&b.screens==2&&b.reads==0,"frame readiness retries capture without relaunch or accessibility");
 }
 /** Chrome tab switcher: closing one tab moves the next row up while the tap is on its way. */
 static void reflowCheck(){
  Fake b=new Fake(){
   int calls;
   @Override public Map<String,Object> action(String n,Map<String,Object> a){
    if(calls++==0){writes++;nodes.remove(0);nodes.get(0).put("bounds","[0,0][100,100]");nodes.get(0).put("index",0);throw new InputNotSentException("stale_target","The control changed. Observe the screen again.");}
    return super.action(n,a);
   }
  };
  b.nodes=new ArrayList<>(List.of(node(0,-1,"1","Close Alpha tab",true),node(1,-1,"2","Close Beta tab",true)));b.nodes.get(1).put("bounds","[0,100][100,200]");
  var out=new EvidenceFlow(b).run("android.tap",Map.of("display",2,"selector",Map.of("label","Close Beta tab")));
  check(Boolean.TRUE.equals(out.get("ok"))&&b.writes==2&&"2".equals(b.arguments.get("nodeId"))&&out.containsKey("recovery"),"selector tap survives a list reflow: "+out);
  b=new Fake(){
   @Override public Map<String,Object> action(String n,Map<String,Object> a){
    if(writes==0){writes++;nodes.clear();nodes.add(node(0,-1,"3","Something else",true));throw new InputNotSentException("stale_target","The control changed. Observe the screen again.");}
    return super.action(n,a);
   }
  };
  out=new EvidenceFlow(b).run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions")));
  check(b.writes==1&&"target_not_found".equals(out.get("code"))&&Boolean.FALSE.equals(out.get("inputDelivered")),"selector that is gone fails as not found");
 }
 /** Maps started cold by a VIEW intent stays white until the intent arrives again. */
 static void coldBlankCheck(){
  Fake b=new Fake(){
   int opens;
   @Override public Map<String,Object> action(String n,Map<String,Object> a){writes++;last=n;arguments=new LinkedHashMap<>(a);if(opens++>0)nodes.get(0).put("text","Coffee near you");return Json.mapOf("ok",true,"inputDelivered",true,"stdout","Status: ok\nLaunchState: COLD\n");}
  };
  b.nodes.get(0).put("text","");
  var out=new EvidenceFlow(b).run("android.open",Map.of("display",2,"url","https://maps.example.com/?q=coffee"));
  check(b.writes==2&&Boolean.TRUE.equals(b.arguments.get("redeliver"))&&out.get("recovery") instanceof Map<?,?> r&&Boolean.TRUE.equals(r.get("redelivered"))&&!((List<?>)out.get("controls")).isEmpty(),"blank cold VIEW launch gets the intent again: "+out);
  Fake drawn=new Fake(){
   @Override public Map<String,Object> action(String n,Map<String,Object> a){writes++;return Json.mapOf("ok",true,"inputDelivered",true,"stdout","LaunchState: COLD\n");}
  };
  out=new EvidenceFlow(drawn).run("android.open",Map.of("display",2,"url","https://maps.example.com/?q=coffee"));
  check(drawn.writes==1&&!out.containsKey("recovery"),"cold launch that draws is not sent again");
  Fake blankPackage=new Fake(){
   @Override public Map<String,Object> action(String n,Map<String,Object> a){writes++;return Json.mapOf("ok",true,"inputDelivered",true,"stdout","LaunchState: COLD\n");}
  };
  blankPackage.nodes.get(0).put("text","");
  new EvidenceFlow(blankPackage).run("android.open",Map.of("display",2,"package","com.example.fixture"));
  check(blankPackage.writes==1,"package launch is never sent again");
  Fake blankSend=new Fake(){
   @Override public Map<String,Object> action(String n,Map<String,Object> a){writes++;return Json.mapOf("ok",true,"inputDelivered",true,"stdout","LaunchState: COLD\n");}
  };
  blankSend.nodes.get(0).put("text","");
  new EvidenceFlow(blankSend).run("android.open",Map.of("display",2,"intent",Map.of("action","android.intent.action.SENDTO","data","smsto:5550100")));
  check(blankSend.writes==1,"an intent that is not VIEW is never sent again");
 }
 static void recoveryChecks(){
  Fake b=new Fake();EvidenceFlow f=new EvidenceFlow(b);b.stale=1;
  var out=f.run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions")));
  check(Boolean.TRUE.equals(out.get("ok"))&&b.writes==2&&out.containsKey("recovery"),"one retry after proven no input and geometry refresh");
  b=new Fake();f=new EvidenceFlow(b);b.stale=2;out=f.run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions")));
  check(Boolean.TRUE.equals(out.get("ok"))&&b.writes==3&&Integer.valueOf(2).equals(((Map<?,?>)out.get("recovery")).get("retries")),"selector tap resolves again while the list keeps moving");
  b=new Fake();f=new EvidenceFlow(b);b.stale=9;out=f.run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions")));
  check(Boolean.FALSE.equals(out.get("inputDelivered"))&&b.writes==4&&out.containsKey("observation"),"stale retries bounded and recovery observation returned");
  b=new Fake();f=new EvidenceFlow(b);var seenId=f.run("android.observe",Map.of("display",2));var boundId=PrimitiveArguments.map(((List<?>)seenId.get("controls")).get(0)).get("id");b.stale=2;
  out=f.run("android.tap",Map.of("display",2,"target",boundId,"observation",seenId.get("observation")));
  check(Boolean.FALSE.equals(out.get("inputDelivered"))&&b.writes==2&&"stale_target".equals(out.get("code")),"target id gets one refresh only");
  reflowCheck();
  coldBlankCheck();
  b=new Fake();f=new EvidenceFlow(b);b.stale=1;b.changeLabel=true;out=f.run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions")));
  check(b.writes==1&&Boolean.FALSE.equals(out.get("inputDelivered")),"changed semantic target never retried");
  b=new Fake();f=new EvidenceFlow(b);var seen=f.run("android.observe",Map.of("display",2));var id=PrimitiveArguments.map(((List<?>)seen.get("controls")).get(0)).get("id");b.nodes.get(0).put("bounds","[2,2][102,102]");
  out=f.run("android.tap",Map.of("display",2,"target",id,"observation",seen.get("observation")));
  check(Boolean.TRUE.equals(out.get("ok"))&&b.writes==1,"bound target refreshes geometry but retains identity");
  b=new Fake();f=new EvidenceFlow(b);out=f.run("android.open",Map.of("display",2,"url","https://example.com"));
  check(Boolean.TRUE.equals(out.get("launchAccepted"))&&out.get("readiness").equals("unverified"),"old page cannot prove URL readiness");
  out=f.run("android.open",Map.of("display",2,"url","https://example.com","verify",exists("Never"),"timeoutMs",0));
  check(Boolean.TRUE.equals(out.get("launchAccepted"))&&out.get("readiness").equals("not_ready")&&Boolean.FALSE.equals(out.get("ok")),"readiness failure retains accepted launch");
 }
 static void screenshotChecks(){
  Fake b=new Fake();EvidenceFlow f=new EvidenceFlow(b);var view=f.run("android.observe",Map.of("display",2,"image","always"));var image=PrimitiveArguments.map(view.get("image"));
  f.run("android.tap",Map.of("display",2,"x",25,"y",30,"screenshotId",image.get("screenshotId")));
  check(b.arguments.get("x").equals(50)&&b.arguments.get("y").equals(60),"screenshot coordinates scale into target display");
  var wrong=f.run("android.tap",Map.of("display",2,"x",100,"y",30,"screenshotId",image.get("screenshotId")));check(Boolean.FALSE.equals(wrong.get("inputDelivered"))&&b.writes==1,"out-of-image coordinates send no input");
  b=new Fake();f=new EvidenceFlow(b);b.failRead=true;
  var screenshotOnly=f.run("android.observe",Map.of("display",2,"image","always"));image=PrimitiveArguments.map(screenshotOnly.get("image"));
  check(Boolean.TRUE.equals(screenshotOnly.get("ok"))&&screenshotOnly.get("accessibility").equals("unavailable")&&screenshotOnly.get("screenshot").equals("available"),"screenshot availability independent of tree");
  var tapped=f.run("android.tap",Map.of("display",2,"x",20,"y",20,"screenshotId",image.get("screenshotId"),"observe",false));
  check(Boolean.TRUE.equals(tapped.get("ok"))&&b.writes==1,"validated screenshot tap works without tree");
  b.focus="other/.Main";tapped=f.run("android.tap",Map.of("display",2,"x",20,"y",20,"screenshotId",image.get("screenshotId")));
  check(Boolean.FALSE.equals(tapped.get("inputDelivered"))&&b.writes==1,"changed app rejects screenshot input");
  b=new Fake();f=new EvidenceFlow(b);
  long[] time={0};f=new EvidenceFlow(b,()->time[0]);image=PrimitiveArguments.map(f.run("android.observe",Map.of("display",2,"image","always")).get("image"));time[0]=120001;
  var expired=f.run("android.tap",Map.of("display",2,"x",2,"y",3,"screenshotId",image.get("screenshotId")));check(Boolean.FALSE.equals(expired.get("inputDelivered"))&&b.writes==0,"expired screenshot sends no input");
 }
 static void scrollChecks(){
  int[] scrolls={0};boolean[] unknown={false};var container=node(0,-1,"list","Items",false);container.put("scrollable",true);
  EvidenceFlow f=new EvidenceFlow(new EvidenceFlow.Backend(){
   public Map<String,Object> action(String n,Map<String,Object> a){check(n.equals("target")&&a.get("operation").equals("scroll"),"semantic scrolling shares target engine");scrolls[0]++;if(unknown[0])throw new IllegalStateException("lost");return Json.mapOf("ok",true,"inputDelivered",true,"moved",true,"endReached",false);}
   public Map<String,Object> tree(int d){return EvidenceHostTest.tree(scrolls[0]>=2?List.of(container,node(1,0,"end","Destination",true)):List.of(container));}
   public Map<String,Object> display(){return Json.mapOf("active",true,"displayId",2);}
   public String focus(int d){return "p/.Main";}public Map<String,Object> screen(int d){return Map.of();}
  });
  var result=f.run("android.scroll_until",Map.of("display",2,"selector",Map.of("label","Items"),"direction","down","until",Map.of("label","Destination"),"maxScrolls",3));check(Boolean.TRUE.equals(result.get("found"))&&scrolls[0]==2&&Boolean.TRUE.equals(result.get("inputDelivered")),"scroll until stops on first unique target");
  // Simulate Android adding ACTION_SCROLL_BACKWARD after scroll #1: scroll_until must keep going instead of stale_target.
  int[] dynScrolls={0};var dynContainer=node(0,-1,"list","Items",false);dynContainer.put("scrollable",true);
  EvidenceFlow dyn=new EvidenceFlow(new EvidenceFlow.Backend(){
   public Map<String,Object> action(String n,Map<String,Object> a){dynScrolls[0]++;return Json.mapOf("ok",true,"inputDelivered",true,"moved",true,"endReached",false);}
   public Map<String,Object> tree(int d){var c=new LinkedHashMap<>(dynContainer);c.put("actions",dynScrolls[0]==0?List.of(4096):List.of(4096,8192));return EvidenceHostTest.tree(dynScrolls[0]>=2?List.of(c,node(1,0,"end","Destination",true)):List.of(c));}
   public Map<String,Object> display(){return Json.mapOf("active",true,"displayId",2);}
   public String focus(int d){return "p/.Main";}public Map<String,Object> screen(int d){return Map.of();}
  });
  var dynRes=dyn.run("android.scroll_until",Map.of("display",2,"selector",Map.of("label","Items"),"direction","down","until",Map.of("label","Destination"),"maxScrolls",3));
  check(Boolean.TRUE.equals(dynRes.get("found"))&&dynScrolls[0]==2,"scroll_until survives container action list changes after scroll");
  // Verification timeout returns diagnostic failedConditions.
  var timeoutRes=dyn.run("android.observe",Map.of("display",2,"verify",exists("NeverThere"),"timeoutMs",0));
  var verMap=PrimitiveArguments.map(timeoutRes.get("verification"));
  check("failed".equals(verMap.get("status"))&&verMap.get("failedConditions") instanceof List<?> fc&&!fc.isEmpty(),"verification timeout includes failedConditions diagnostics");
  result=f.run("android.scroll_until",Map.of("display",2,"selector",Map.of("label","Items"),"direction","down","until",Map.of("label","Destination"),"maxScrolls",3));check(scrolls[0]==2&&Boolean.FALSE.equals(result.get("inputDelivered")),"already visible destination sends no scroll");
  scrolls[0]=0;result=f.run("android.scroll_until",Map.of("display",2,"selector",Map.of("label","Items"),"direction","down","until",Map.of("label","Never"),"maxScrolls",1));check(scrolls[0]==1&&result.get("code").equals("scroll_limit"),"scroll bound stops before a second action");
  unknown[0]=true;scrolls[0]=0;result=f.run("android.scroll_until",Map.of("display",2,"selector",Map.of("label","Items"),"direction","down","until",Map.of("label","Never")));check(scrolls[0]==1&&result.get("inputDelivered")==null&&result.get("outcome").equals("unknown"),"uncertain scroll retains unknown delivery and does not repeat");
 }

}
