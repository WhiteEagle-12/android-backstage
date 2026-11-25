package com.deviceagent.rpc;

import java.util.concurrent.atomic.AtomicLong;

/** A job id the relay sends again never runs twice; finished ids age out, running ids never do. */
public final class ReceivedJobsHostTest {
    public static void run() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ReceivedJobs jobs = new ReceivedJobs(clock::get);
        check(jobs.receive("a") == ReceivedJobs.Seen.NEW, "first time runs");
        check(jobs.receive("a") == ReceivedJobs.Seen.RUNNING, "sent again while running: ack only");
        jobs.done("a");
        check(jobs.receive("a") == ReceivedJobs.Seen.DONE, "sent again after it finished: re-send the result");
        check(jobs.receive("b") == ReceivedJobs.Seen.NEW, "another id runs");

        clock.addAndGet(ReceivedJobs.KEEP_DONE_MS + 1);
        check(jobs.receive("b") == ReceivedJobs.Seen.RUNNING, "a running id is never forgotten");
        check(jobs.receive("a") == ReceivedJobs.Seen.NEW, "a finished id ages out");

        for (int i = 0; i < ReceivedJobs.MAX_DONE + 10; i++) {
            jobs.receive("n" + i);
            jobs.done("n" + i);
        }
        jobs.receive("last");
        check(jobs.receive("b") == ReceivedJobs.Seen.RUNNING, "the cap keeps running ids");
        check(jobs.receive("n0") == ReceivedJobs.Seen.NEW, "the cap drops the oldest finished id");
        check(jobs.receive("n" + (ReceivedJobs.MAX_DONE + 9)) == ReceivedJobs.Seen.DONE, "the newest finished id stays");
        System.out.println("PASS ReceivedJobs: duplicate ids never run twice, finished ids age out, running ids kept");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
