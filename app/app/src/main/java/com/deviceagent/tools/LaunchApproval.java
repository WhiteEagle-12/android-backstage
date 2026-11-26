package com.deviceagent.tools;

import java.util.*;

/**
 * A single-task app has one task. Opening it on a private display moves it off the screen it is on.
 * If that screen is the owner's, ask the owner and remember an Allow for the rest of the session.
 * If another private session shows the app, refuse: that session's agent is using it.
 */
public final class LaunchApproval {
    /** Shows the owner a question and returns "approved", "cancelled" or "timeout". */
    public interface Prompt { String ask(String pkg,long timeoutMs); }
    static final long MAX_WAIT_MS=25_000,RESERVE_MS=5_000,MIN_WAIT_MS=3_000;
    private final Map<String,Set<String>> allowed=new LinkedHashMap<>(16,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Set<String>> e){return size()>64;}
    };

    public synchronized boolean isAllowed(String session,String pkg){var s=allowed.get(session);return s!=null&&s.contains(pkg);}
    synchronized void allow(String session,String pkg){allowed.computeIfAbsent(session,k->new HashSet<>()).add(pkg);}
    public synchronized void forget(String session){allowed.remove(session);}

    /** How long to wait for the owner so the launch still fits in the call. */
    static long waitFor(long remainingMs){return Math.min(MAX_WAIT_MS,remainingMs-RESERVE_MS);}

    /**
     * conflict is the launch_conflict reply. Returns when the launch may go ahead;
     * otherwise throws app_open_elsewhere.
     */
    public void require(Map<String,Object> conflict,String session,long remainingMs,Prompt prompt){
        if(!Boolean.TRUE.equals(conflict.get("conflict")))return;
        String pkg=String.valueOf(conflict.get("package"));
        Map<String,Object> details=Map.of("package",pkg);
        if(!Integer.valueOf(0).equals(conflict.get("otherDisplay")))
            throw new InputNotSentException("app_open_elsewhere","This app runs in one window, and another session shows it. Opening it here would take it from that session. Finish that session, or join it.",details);
        if(isAllowed(session,pkg))return;
        long wait=waitFor(remainingMs);
        String decision=wait<MIN_WAIT_MS?"timeout":prompt.ask(pkg,wait);
        if("approved".equals(decision)){allow(session,pkg);return;}
        throw new InputNotSentException("app_open_elsewhere","timeout".equals(decision)
            ?"This app runs in one window, and it is open on the phone's screen. The owner did not answer the request to move it here. Try again later, or begin a foreground session."
            :"This app runs in one window, and it is open on the phone's screen. The owner chose not to move it here. Try again later, or begin a foreground session.",
            Map.of("package",pkg,"decision","timeout".equals(decision)?"no_answer":"declined"));
    }
}
