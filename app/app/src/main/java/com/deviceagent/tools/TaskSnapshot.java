package com.deviceagent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Root visibility is separate from leaf focus; a root may contain several tasks. */
public final class TaskSnapshot {
    private TaskSnapshot() {}
    private static final Pattern ROOT=Pattern.compile("RootTask id=(\\d+)");
    private static final Pattern DISPLAY=Pattern.compile("displayId=(\\d+)");
    private static final Pattern TASK=Pattern.compile("taskId=(\\d+):\\s*([A-Za-z0-9._]+/[A-Za-z0-9._$]+)");
    private static final Pattern TOP=Pattern.compile("topActivity=ComponentInfo\\{([^}]+)\\}");
    private static final Pattern COMPONENT=Pattern.compile("([A-Za-z0-9._]+/[A-Za-z0-9._$]+)");
    public static String focusedComponent(String text) {
        Matcher m=COMPONENT.matcher(text==null?"":text); return m.find()?m.group(1):"";
    }
    /** System windows may have no activity component. Keep their actual window identity. */
    public static String focusIdentity(String text){
        if(text==null)return "";String value=text.trim();int at=value.indexOf("mCurrentFocus=");if(at>=0)value=value.substring(at+14).trim();
        return value.startsWith("Window{")&&value.endsWith("}")?value:focusedComponent(text);
    }
    public static String singleVisiblePackage(List<Map<String,Object>> tasks) {
        var packages=new java.util.LinkedHashSet<String>();
        for(var task:tasks)if(Boolean.TRUE.equals(task.get("visible")))packages.add(String.valueOf(task.get("package")));
        return packages.size()==1?packages.iterator().next():"";
    }
    public static List<Map<String,Object>> parse(String text,int display) {
        List<Map<String,Object>> rows=new ArrayList<>();
        if(text==null)return rows;
        for(String block:text.split("(?m)^(?=\\s*RootTask id=)")) {
            Matcher root=ROOT.matcher(block),d=DISPLAY.matcher(block),top=TOP.matcher(block);
            if(!root.find()||!d.find()||Integer.parseInt(d.group(1))!=display)continue;
            int rootId=Integer.parseInt(root.group(1)); String topComponent=top.find()?top.group(1):"";
            Matcher task=TASK.matcher(block); int first=rows.size();
            while(task.find()) {
                String component=task.group(2); int slash=component.indexOf('/');
                rows.add(Json.mapOf("activityTaskId",Integer.parseInt(task.group(1)),"rootTaskId",rootId,"displayId",display,
                        "component",component,"package",component.substring(0,slash),"activity",component.substring(slash+1),
                        "visible",block.contains("visible=true"),"rootVisible",block.contains("visible=true"),"rootTopActivity",topComponent));
            }
            // Unknown leaf identities must never become a handoff candidate.
            for(int i=first;i<rows.size();i++)rows.get(i).put("rootTaskCount",rows.size()-first);
        }
        return rows;
    }
    public static Map<String,Object> select(List<Map<String,Object>> tasks,String pkg,Integer taskId) {
        List<Map<String,Object>> matches=new ArrayList<>();
        for(Map<String,Object> row:tasks) {
            if(pkg!=null&&!pkg.equals(row.get("package")))continue;
            if(taskId!=null&&taskId.intValue()!=((Number)row.get("activityTaskId")).intValue())continue;
            matches.add(row);
        }
        if(matches.size()!=1)throw new IllegalArgumentException(matches.isEmpty()?"No matching task on the virtual display":"Several tasks match. Choose activityTaskId from android.task_inspect");
        Map<String,Object> pick=matches.get(0);
        if(((Number)pick.get("rootTaskCount")).intValue()!=1)throw new IllegalArgumentException("This task shares a stack with other tasks. Handoff would move them too; open a separate task first.");
        return pick;
    }
}
