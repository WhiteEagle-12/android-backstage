package com.deviceagent.shell;

import android.content.Context;
import android.media.AudioRecord;
import android.media.AudioTrack;
import com.deviceagent.tools.Json;
import com.deviceagent.tools.PcmWave;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** App-UID audio routes with explicit ownership, progress, cancellation and leases. */
final class AudioJobs {
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private static final class Job {
        String id, owner, pkg, path, operation, configPath;
        int uid, rate, channels;
        volatile String state = "running", error;
        volatile long bytes, expires, monotonicEnd;
        volatile boolean stop, playbackFinished;
        Thread worker;
        Map<String,Object> result() {
            var out = Json.mapOf("ok", true, "audioId", id, "state", state, "package", pkg,
                    "operation", operation, "path", path, "bytes", bytes, "sampleRate", rate,
                    "channels", channels, "expiresAt", expires, "playbackFinished",playbackFinished);
            if (error != null) out.put("error", error);
            return out;
        }
        void lease(int seconds) { expires = System.currentTimeMillis() + seconds * 1000L; monotonicEnd = System.nanoTime() + seconds * 1_000_000_000L; }
    }
    synchronized Map<String,Object> handle(Context context, Map<String,Object> args) throws Exception {
        String op = Json.string(args,"operation"), owner = Json.string(args,"_owner");
        if (!op.equals("capture") && !op.equals("inject")) {
            Job job = jobs.get(Json.string(args,"audioId"));
            if (job == null || !job.owner.equals(owner)) return Json.mapOf("ok",false,"code","audio_job_not_found","error","No audio job is available for this assistant. A phone service restart ends active audio routes.");
            if (op.equals("stop")) job.stop = true;
            if (op.equals("renew") && active(job)) job.lease(Json.integer(args,"leaseSeconds",60));
            return job.result();
        }
        String pkg = Json.string(args,"package");
        int uid = context.getPackageManager().getApplicationInfo(pkg,0).uid;
        String[] shared = context.getPackageManager().getPackagesForUid(uid);
        if (shared != null && shared.length > 1) return Json.mapOf("ok",false,"inputDelivered",false,"code","shared_audio_uid","error","Android groups this app's audio with other packages under one UID; an app-only route cannot be guaranteed.","packages",Arrays.asList(shared));
        for (Job active : jobs.values()) if (active.uid == uid && active(active))
            return Json.mapOf("ok",false,"inputDelivered",false,"code","audio_busy","error","An audio route is already active for this app. Stop it before starting another.");
        Path path = Paths.get(Json.string(args,"path")).toAbsolutePath();
        boolean injection = op.equals("inject");
        FileChannel file = injection ? FileChannel.open(path, StandardOpenOption.READ)
                : FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        AudioRoute route = null;
        try {
            PcmWave.Format format = injection ? PcmWave.parse(file) : new PcmWave.Format(Json.integer(args,"sampleRate",48000),Json.integer(args,"channels",2),44,0);
            route = new AudioRoute(context,uid,injection,format.rate(),format.channels());
            if (!injection) { PcmWave.header(file,format.rate(),format.channels(),0); file.position(44); route.record.startRecording(); }
            else { file.position(format.offset()); route.track.play(); }
            Job job = new Job();job.id="audio_"+UUID.randomUUID().toString().replace("-","");job.owner=owner;job.pkg=pkg;job.uid=uid;job.configPath=Json.string(args,"_configPath");
            job.path=path.toString();job.operation=op;job.rate=format.rate();job.channels=format.channels();job.lease(Json.integer(args,"leaseSeconds",60));
            AudioRoute ownedRoute = route;
            job.worker = new Thread(() -> run(job,ownedRoute,file,format,injection),"backstage-audio");job.worker.setDaemon(true);
            jobs.entrySet().removeIf(e -> !active(e.getValue()) && System.currentTimeMillis() - e.getValue().expires > 86400000L);
            jobs.put(job.id,job);job.worker.start();return job.result();
        } catch (Exception e) {
            if (route != null) route.close();file.close();if (!injection) Files.deleteIfExists(path);throw e;
        }
    }
    private static boolean active(Job job){return job.state.equals("running")||job.state.equals("awaiting_stop");}
    private static void checkOwner(Job job)throws java.io.IOException {
        if(job.owner.equals("local"))return;
        var config=com.deviceagent.daemon.DaemonConfig.load(job.configPath);
        if(config.links.stream().noneMatch(link->link.agentId.equals(job.owner)))throw new java.io.IOException("This assistant's access was removed");
    }
    private static void run(Job job, AudioRoute route, FileChannel file, PcmWave.Format format, boolean injection) {
        boolean drained=false;
        try {
            byte[] bytes = new byte[8192];int pending=0, offset=0;long checked=0;
            while (!job.stop && System.nanoTime() < job.monotonicEnd) {
                if (!job.owner.equals("local") && System.nanoTime()-checked > 250_000_000L) {
                    checked=System.nanoTime();
                    checkOwner(job);
                }
                if (injection) {
                    if (pending == 0) {
                        long left = format.bytes() - job.bytes;
                        if (left == 0) break;
                        pending = file.read(ByteBuffer.wrap(bytes,0,(int)Math.min(bytes.length,left)));offset=0;
                        if (pending <= 0) throw new java.io.IOException("The input audio changed or ended early");
                    }
                    int n = route.track.write(bytes,offset,pending,AudioTrack.WRITE_NON_BLOCKING);
                    if (n < 0) throw new java.io.IOException("Android stopped accepting audio ("+n+")");
                    pending-=n;offset+=n;job.bytes+=n;
                } else {
                    int n = route.record.read(bytes,0,bytes.length,AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) throw new java.io.IOException("Android stopped capturing audio ("+n+")");
                    ByteBuffer data=ByteBuffer.wrap(bytes,0,n);while(data.hasRemaining())file.write(data);job.bytes+=n;
                    if (job.bytes > 0xffffffffL - 36 - bytes.length) break;
                }
                Thread.sleep(5);
            }
            if (injection && !job.stop && job.bytes == format.bytes()) {
                long targetFrames=job.bytes/(job.channels*2);
                while (!job.stop && Integer.toUnsignedLong(route.track.getPlaybackHeadPosition()) < targetFrames && System.nanoTime()<job.monotonicEnd) Thread.sleep(5);
                drained=Integer.toUnsignedLong(route.track.getPlaybackHeadPosition())>=targetFrames;
            }
            if(injection&&drained&&!job.stop){
                job.playbackFinished=true;job.state="awaiting_stop";
                // Keep this route silent and reserved until the caller finishes
                // the app's recorder. Unregistering here restores its microphone.
                while(!job.stop&&System.nanoTime()<job.monotonicEnd){checkOwner(job);Thread.sleep(100);}
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { job.error=Objects.toString(e.getMessage(),"Audio stopped unexpectedly"); }
        finally {
            try { route.close(); } catch (Exception e) { job.error="Android did not confirm audio route cleanup"; }
            try { if (!injection) PcmWave.header(file,job.rate,job.channels,job.bytes);file.force(true); }
            catch (Exception e) { job.error="The audio file could not be finalized"; }
            try { file.close(); } catch (Exception ignored) {}
            job.state=job.error!=null?"failed":job.stop?"stopped":"expired";
        }
    }
}
