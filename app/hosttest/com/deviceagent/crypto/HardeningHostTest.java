package com.deviceagent.crypto;
import com.deviceagent.daemon.DaemonConfig;
import java.nio.file.*;
import java.util.*;
public final class HardeningHostTest {
 public static void run() throws Exception {
  Path dir=Files.createTempDirectory("backstage-hardening");String key=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
  DaemonConfig c=new DaemonConfig();c.configPath=dir.resolve("daemon.json").toString();
  LinkStore s=new LinkStore(c);s.addLink("a",key,"0","100");
  Envelope first=s.sealResult("a","d","one".getBytes());
  LinkStore recovered=new LinkStore(DaemonConfig.load(c.configPath));
  Envelope second=recovered.sealResult("a","d","two".getBytes());
  check(Long.parseLong(second.n)>Long.parseLong(first.n));check(!first.r.equals(second.r));
  Envelope call=Envelope.sealRandom(LinkPairing.callKey(new byte[32]),"a",100,"cmd".getBytes());
  s.openCall(call);LinkStore after=new LinkStore(DaemonConfig.load(c.configPath));
  try{after.openCall(call);throw new AssertionError("replay accepted");}catch(LinkStore.Unauthorized expected){}
  s.forget("a");Files.copy(Path.of(c.configPath),Path.of(c.configPath+".prev"));
  DaemonConfig stale=new DaemonConfig();stale.configPath=c.configPath;
  DaemonConfig.Link old=new DaemonConfig.Link();old.agentId="a";old.linkKey=key;stale.links.add(old);stale.save();stale.mergePrevCounters();check(stale.links.isEmpty());
  TaskPermits permits=new TaskPermits(c.configPath);permits.set("a","qa","com.example.app",System.currentTimeMillis()+60000);
  permits.authorize("a","android.packages",Map.of("package","com.example.app"));
  try{permits.authorize("a","android.shell",Map.of("command","id"));throw new AssertionError("shell allowed");}catch(SecurityException expected){}
  try{permits.authorize("a","android.packages",Map.of("package","com.other.app"));throw new AssertionError("wrong app allowed");}catch(SecurityException expected){}
  RequestJournal journal=new RequestJournal(c.configPath);
  int[] executions={0};
  Map<String,Object> receipt=journal.execute("a","call-1","android.shell",Map.of("command","id"),()->{executions[0]++;return Map.of("ok",true);});
  RequestJournal restarted=new RequestJournal(c.configPath);
  check(receipt.equals(restarted.execute("a","call-1","android.shell",Map.of("command","id"),()->{throw new AssertionError("duplicate executed");})));
  check(executions[0]==1);
  try{restarted.execute("a","call-1","android.shell",Map.of("command","other"),()->null);throw new AssertionError("id collision accepted");}catch(IllegalArgumentException expected){}
  try{journal.execute("a","call-interrupted","android.shell",Map.of(),()->{throw new AssertionError("simulated process exit");});}catch(AssertionError expected){}
  try{restarted.execute("a","call-interrupted","android.shell",Map.of(),()->{throw new AssertionError("interrupted executed again");});throw new AssertionError("unknown accepted");}catch(IllegalStateException expected){}
  Map<String,Object> label=Map.of("text","Wireless debugging","contentDescription","","centerX",200,"centerY",200,"clickable",false);
  Map<String,Object> toggle=Map.of("text","","contentDescription","Wireless debugging","centerX",900,"centerY",200,"clickable",true);
  check(com.deviceagent.tools.UiHierarchy.find(List.of(toggle,label),"Wireless debugging",1000,2000)==label);
  Map<String,Object> disabled=Map.of("text","Pair","enabled",false,"centerX",200,"centerY",200);
  try{com.deviceagent.tools.UiHierarchy.find(List.of(disabled),"Pair",1000,2000);throw new AssertionError("Disabled target accepted");}catch(IllegalArgumentException expected){}
  System.out.println("PASS Hardening: durable counters, replay, tombstones, scoped QA, restart-safe execution receipts, visible-label targeting");
 }
 private static void check(boolean ok){if(!ok)throw new AssertionError();}
}
