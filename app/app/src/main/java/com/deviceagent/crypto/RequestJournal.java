package com.deviceagent.crypto;

import com.deviceagent.tools.Json;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Persists intent before execution. An interrupted side effect is never repeated automatically.
 *
 * Retention: a completed receipt replays its first outcome for at least {@link #RETENTION_MS} while the journal
 * holds at most {@link #MAX_RECEIPTS} receipts and {@link #MAX_BYTES} bytes. Past either cap the oldest completed
 * receipts are evicted first, so a write never fails for quota alone. A receipt that is still "running" has an
 * unknown outcome and is never evicted; only a journal made entirely of such receipts refuses a new write.
 * Directory contents are indexed in memory (one scan per process, refreshed every {@link #RESCAN_MS}) so a write
 * does not list the directory.
 */
public final class RequestJournal {
    public static final long RETENTION_MS = 86400000L;
    public static final int MAX_RECEIPTS = 16384;
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    static final long RESCAN_MS = 600000L;
    private static final int RUNNING_ROW_MAX = 512;

    private static final class Entry {
        final String key;final long time,size;final boolean running;
        Entry(String key,long time,long size,boolean running){this.key=key;this.time=time;this.size=size;this.running=running;}
    }

    private final Path dir;
    private final int maxReceipts;
    private final long maxBytes,retentionMs;
    private final LongSupplier clock;
    /** Oldest update first: track() re-inserts on every write, so iteration order is eviction order. */
    private final Map<String,Entry> index=new LinkedHashMap<>();
    private long indexBytes,scannedAt=Long.MIN_VALUE;
    private boolean scanned;

    public RequestJournal(String configPath) {
        this(Paths.get(configPath).resolveSibling("requests"),MAX_RECEIPTS,MAX_BYTES,RETENTION_MS,System::currentTimeMillis);
    }
    /** Test constructor: small caps and a controllable clock. */
    RequestJournal(Path dir,int maxReceipts,long maxBytes,long retentionMs,LongSupplier clock) {
        this.dir=dir;this.maxReceipts=maxReceipts;this.maxBytes=maxBytes;this.retentionMs=retentionMs;this.clock=clock;
    }
    /** Records and claims the receipt under the lock, then runs the action without it so calls can overlap. */
    public Map<String,Object> execute(String agent,String id,String name,
            Map<String,Object> args,Supplier<Object> action) {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("invalid call id");
        try {
            Path file;Map<String,Object> row;String key;
            synchronized(this){
            Files.createDirectories(dir);
            key=hash(agent+":"+id);String fingerprint=hash(Json.stringify(Json.mapOf("name",name,"arguments",canonical(args))));
            file=dir.resolve(key+".json");
            if(Files.exists(file)) {
                Map<String,Object> old=Json.object(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
                if(!fingerprint.equals(old.get("fingerprint")))throw new IllegalArgumentException("call id reused with different arguments");
                if(old.get("response") instanceof Map)return (Map<String,Object>)old.get("response");
                throw new IllegalStateException("Previous execution outcome is unknown; inspect the app before issuing a new action");
            }
            refresh();
            makeRoom(null);
            if(index.size()>=maxReceipts||indexBytes>=maxBytes)
                throw new IllegalStateException("Device receipt journal is full of "+index.size()+" writes whose outcome is unknown (still running or interrupted); "
                    +"those are never discarded. Let running writes finish or inspect the device state. Completed receipts are evicted automatically.");
            row=Json.mapOf("fingerprint",fingerprint,"state","running");
            String claimed=Json.stringify(row);
            PrivateState.write(file,claimed);
            track(key,claimed,true);
            }
            Map<String,Object> response;
            try { response=Json.mapOf("result",action.get()); }
            catch(RuntimeException e){response=Json.mapOf("error",Json.mapOf("message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
            row.put("state","complete");row.put("response",response);
            String encoded=Json.stringify(row);
            if(encoded.getBytes(StandardCharsets.UTF_8).length>8*1024*1024)throw new IllegalStateException("Result too large to persist; action may have completed");
            synchronized(this){PrivateState.write(file,encoded);track(key,encoded,false);makeRoom(key);}
            return response;
        } catch(java.io.IOException e){throw new IllegalStateException("Cannot persist execution receipt; outcome may be unknown",e);}
    }
    /** Receipts currently indexed (tests and diagnostics). */
    synchronized int size(){return index.size();}

    private void track(String key,String text,boolean running) {
        Entry old=index.remove(key);if(old!=null)indexBytes-=old.size;
        long size=text.getBytes(StandardCharsets.UTF_8).length;
        index.put(key,new Entry(key,clock.getAsLong(),size,running));indexBytes+=size;
    }
    /** Evicts completed receipts older than retention, then the oldest completed ones until both caps hold; never a running one nor {@code keep}. */
    private void makeRoom(String keep) {
        long cutoff=clock.getAsLong()-retentionMs;
        // Leave room for the receipt about to be written (keep==null) as well as honouring both caps.
        int countLimit=keep==null?maxReceipts-1:maxReceipts;long byteLimit=keep==null?maxBytes-1:maxBytes;
        for(Iterator<Entry> it=index.values().iterator();it.hasNext();) {
            Entry e=it.next();
            if(e.running||e.key.equals(keep))continue;
            if(e.time>=cutoff&&index.size()<=countLimit&&indexBytes<=byteLimit)break;
            it.remove();indexBytes-=e.size;
            // A file that cannot be deleted is picked up again by the next rescan.
            try{Files.deleteIfExists(dir.resolve(e.key+".json"));}catch(IOException ignored){}
        }
    }
    /** One directory listing per process and then every RESCAN_MS, to notice files changed behind this instance's back. */
    private void refresh() throws IOException {
        long now=clock.getAsLong();
        if(scanned&&now-scannedAt<RESCAN_MS)return;
        index.clear();indexBytes=0;
        List<Entry> found=new ArrayList<>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(dir)) {
            for(Path f:files) {
                String n=f.getFileName().toString();
                try {
                    long time=Files.getLastModifiedTime(f).toMillis();
                    if(n.endsWith(".json")) {
                        long size=Files.size(f);
                        boolean running=size<=RUNNING_ROW_MAX&&new String(Files.readAllBytes(f),StandardCharsets.UTF_8).contains("\"state\":\"running\"");
                        found.add(new Entry(n.substring(0,n.length()-5),time,size,running));
                    } else if(n.startsWith("state-")&&n.endsWith(".tmp")&&time<now-retentionMs)Files.deleteIfExists(f);
                } catch(NoSuchFileException gone){}
            }
        }
        found.sort(Comparator.<Entry>comparingLong(e->e.time));
        for(Entry e:found){index.put(e.key,e);indexBytes+=e.size;}
        scanned=true;scannedAt=now;
    }
    private static Object canonical(Object value) {
        if(value instanceof Map) {
            Map<String,Object> sorted=new TreeMap<>();
            for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())sorted.put(String.valueOf(e.getKey()),canonical(e.getValue()));
            return sorted;
        }
        if(value instanceof List){List<Object> list=new ArrayList<>();for(Object item:(List<?>)value)list.add(canonical(item));return list;}
        return value;
    }
    private static String hash(String value) {
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
