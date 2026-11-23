package com.deviceagent.crypto;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Receipt journal: caps evict the oldest completed receipts, running receipts are never evicted, replay stays idempotent. */
public final class RequestJournalHostTest {
 public static void run() throws Exception {
  evictsOldestCompletedFirst();
  neverEvictsRunning();
  failsClearlyWhenOnlyRunningRemain();
  byteCapEvicts();
  replayAndConflict();
  expiredCompletedReceiptsGo();
  restartKeepsIndexAndRunningState();
  System.out.println("PASS RequestJournal: oldest-completed eviction, running receipts kept, no quota failure, idempotent replay, restart rebuild");
 }
 private static RequestJournal journal(Path dir,int max,long bytes,AtomicLong clock){return new RequestJournal(dir,max,bytes,RequestJournal.RETENTION_MS,clock::get);}
 private static Object write(RequestJournal j,AtomicLong clock,String id,int[] runs){
  clock.addAndGet(10);
  return j.execute("a",id,"android.tap",Map.of("x",1),()->{runs[0]++;return Map.of("id",id);}).get("result");
 }
 private static boolean exists(RequestJournal j,Path dir,String id){
  // A retained receipt replays without running the action again.
  boolean[] ran={false};
  try{j.execute("a",id,"android.tap",Map.of("x",1),()->{ran[0]=true;return Map.of("id",id);});}catch(RuntimeException e){return true;}
  return !ran[0];
 }
 private static void evictsOldestCompletedFirst() throws Exception {
  Path dir=Files.createTempDirectory("journal-evict");AtomicLong clock=new AtomicLong(System.currentTimeMillis());int[] runs={0};
  RequestJournal j=journal(dir,4,1L<<30,clock);
  for(int i=1;i<=4;i++)write(j,clock,"w"+i,runs);
  check(j.size()==4);
  write(j,clock,"w5",runs);                    // over the cap: w1 (oldest) goes, nothing throws
  check(j.size()==4&&runs[0]==5);
  int before=runs[0];
  check(exists(j,dir,"w5")&&exists(j,dir,"w4")&&exists(j,dir,"w3")&&exists(j,dir,"w2"));
  check(runs[0]==before);                      // w2..w5 replayed from receipts
  write(j,clock,"w6",runs);                    // evicts w2
  write(j,clock,"w7",runs);                    // evicts w3
  check(runs[0]==before+2);
  check(!exists(j,dir,"w3"));                  // evicted: treated as a new id, runs again
  check(countJson(dir)<=4);
 }
 private static void neverEvictsRunning() throws Exception {
  Path dir=Files.createTempDirectory("journal-running");AtomicLong clock=new AtomicLong(System.currentTimeMillis());int[] runs={0};
  RequestJournal j=journal(dir,3,1L<<30,clock);
  // Oldest receipt is an interrupted (running) write.
  try{clock.addAndGet(10);j.execute("a","stuck","android.tap",Map.of("x",1),()->{throw new AssertionError("simulated process exit");});}catch(AssertionError expected){}
  for(int i=1;i<=6;i++)write(j,clock,"d"+i,runs);
  check(j.size()==3);
  RequestJournal restarted=journal(dir,3,1L<<30,clock);
  try{restarted.execute("a","stuck","android.tap",Map.of("x",1),()->{throw new AssertionError("stuck write executed again");});throw new AssertionError("unknown outcome replayed");}
  catch(IllegalStateException expected){check(expected.getMessage().contains("unknown"));}
 }
 private static void failsClearlyWhenOnlyRunningRemain() throws Exception {
  Path dir=Files.createTempDirectory("journal-full");AtomicLong clock=new AtomicLong(System.currentTimeMillis());
  RequestJournal j=journal(dir,2,1L<<30,clock);
  for(int i=1;i<=2;i++){try{clock.addAndGet(10);j.execute("a","r"+i,"android.tap",Map.of(),()->{throw new AssertionError("simulated process exit");});}catch(AssertionError expected){}}
  try{j.execute("a","third","android.tap",Map.of(),()->"x");throw new AssertionError("accepted a write with no room");}
  catch(IllegalStateException e){check(e.getMessage().contains("outcome is unknown")&&!e.getMessage().contains("wait for retention"));}
  check(j.size()==2);
 }
 private static void byteCapEvicts() throws Exception {
  Path dir=Files.createTempDirectory("journal-bytes");AtomicLong clock=new AtomicLong(System.currentTimeMillis());int[] runs={0};
  RequestJournal j=journal(dir,1000,1200,clock);                // about a handful of ~150-byte receipts
  for(int i=1;i<=40;i++)write(j,clock,"b"+i,runs);
  check(runs[0]==40);
  long total=0;try(DirectoryStream<Path> s=Files.newDirectoryStream(dir,"*.json")){for(Path p:s)total+=Files.size(p);}
  check(total<=1200&&total>0);
  check(exists(j,dir,"b40"));                                   // newest survives
 }
 private static void replayAndConflict() throws Exception {
  Path dir=Files.createTempDirectory("journal-replay");AtomicLong clock=new AtomicLong(System.currentTimeMillis());int[] runs={0};
  RequestJournal j=journal(dir,50,1L<<30,clock);
  Map<String,Object> first=j.execute("a","same","android.tap",Map.of("x",1),()->{runs[0]++;return Map.of("n",runs[0]);});
  Map<String,Object> second=j.execute("a","same","android.tap",Map.of("x",1),()->{runs[0]++;return Map.of("n",runs[0]);});
  check(first.equals(second)&&runs[0]==1);
  try{j.execute("a","same","android.tap",Map.of("x",2),()->null);throw new AssertionError("conflicting reuse accepted");}catch(IllegalArgumentException expected){}
  check(runs[0]==1);
 }
 private static void expiredCompletedReceiptsGo() throws Exception {
  Path dir=Files.createTempDirectory("journal-expiry");AtomicLong clock=new AtomicLong(System.currentTimeMillis());int[] runs={0};
  RequestJournal j=journal(dir,1000,1L<<30,clock);
  write(j,clock,"old",runs);
  clock.addAndGet(RequestJournal.RETENTION_MS+1);
  write(j,clock,"new",runs);
  check(j.size()==1&&countJson(dir)==1);
 }
 private static void restartKeepsIndexAndRunningState() throws Exception {
  Path dir=Files.createTempDirectory("journal-restart");AtomicLong clock=new AtomicLong(System.currentTimeMillis());int[] runs={0};
  RequestJournal j=journal(dir,3,1L<<30,clock);
  for(int i=1;i<=3;i++){write(j,clock,"p"+i,runs);Thread.sleep(5);}
  RequestJournal restarted=new RequestJournal(dir,3,1L<<30,RequestJournal.RETENTION_MS,System::currentTimeMillis);
  restarted.execute("a","p4","android.tap",Map.of("x",1),()->Map.of("id","p4"));   // rescan sees 3, evicts the oldest on disk
  check(restarted.size()==3&&countJson(dir)==3);
  int before=runs[0];
  check(restarted.execute("a","p3","android.tap",Map.of("x",1),()->{runs[0]++;return null;}).get("result") instanceof Map&&runs[0]==before);
 }
 private static int countJson(Path dir)throws Exception{int n=0;try(DirectoryStream<Path> s=Files.newDirectoryStream(dir,"*.json")){for(Path p:s)n++;}return n;}
 private static void check(boolean ok){if(!ok)throw new AssertionError();}
}
