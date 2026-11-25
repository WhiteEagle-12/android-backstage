package com.deviceagent.tools;

import java.util.*;

/** Regressions from the 312-task phone benchmark. */
public final class PhoneToolsHostTest {
    private static void check(boolean value,String why){EvidenceHostTest.check(value,why);}
    private static Map<String,Object> node(int i,int p,String id,String label,boolean click){return EvidenceHostTest.node(i,p,id,label,click);}
    private static Map<String,Object> radioTree(String windowBounds,String largeId){
        var list=node(0,-1,"list","",false);list.put("bounds","[0,0][1216,2688]");
        var def=node(1,0,"r1","Default",true);def.put("class","RadioButton");def.put("checked",true);def.put("resourceId","p:id/radio");def.put("bounds","[0,100][1216,200]");
        var large=node(2,0,largeId,"Large",true);large.put("class","RadioButton");large.put("checked",false);large.put("resourceId","p:id/radio");large.put("bounds","[0,200][1216,300]");
        var tree=EvidenceHostTest.tree(new ArrayList<>(List.of(list,def,large)));
        PrimitiveArguments.map(((List<?>)tree.get("windows")).get(0)).put("bounds",windowBounds);
        return tree;
    }
    private static String id(Map<String,Object> view,String label){
        return (String)((List<?>)view.get("controls")).stream().map(PrimitiveArguments::map).filter(c->label.equals(c.get("label"))).findFirst().orElseThrow().get("id");
    }
    private static String code(Runnable r){try{r.run();return "";}catch(InputNotSentException e){return e.code+":"+e.getMessage();}}
    public static void run(){
        stableIdentity();
        System.out.println("PASS phone tools: stable target identity");
    }
    private static void stableIdentity(){
        // The observation is taken while the activity slides in; the window then settles.
        var moving=radioTree("[-181,0][1034,2688]","r2");
        var obs=new Observations(()->0);var view=obs.observe(moving,40,null);String o=(String)view.get("observation"),large=id(view,"Large");
        var settled=radioTree("[0,0][1216,2688]","r2");
        var ref=obs.target(o,large,2,settled,"tap");
        check("r2".equals(ref.get("nodeId")),"window animation does not make a target stale");
        // The list re-renders and the row gets a new node id.
        var rerendered=radioTree("[0,0][1216,2688]","r9");
        ref=obs.target(o,large,2,rerendered,"tap");
        check("r9".equals(ref.get("nodeId")),"re-rendered row re-resolves to its one new node");
        check("r9".equals(Observations.validateTarget(rerendered,ref).get("nodeId")),"refreshed reference validates on the shell side");
        // A label change is reported with the field that changed.
        var renamed=radioTree("[0,0][1216,2688]","r2");
        Observations.nodes(renamed).stream().filter(n->"r2".equals(n.get("nodeId"))).findFirst().orElseThrow().put("text","Huge");
        String why=code(()->obs.target(o,large,2,renamed,"tap"));
        check(why.startsWith("stale_target")&&why.contains("label"),"changed label stays stale and names the field: "+why);
        // Two equal rows after re-render are ambiguous, never a guess.
        var twice=radioTree("[0,0][1216,2688]","r9");
        var copy=new LinkedHashMap<>(Observations.nodes(twice).get(2));copy.put("index",3);copy.put("nodeId","r10");copy.put("bounds","[0,300][1216,400]");
        ((List<Map<String,Object>>)twice.get("nodes")).add(copy);
        why=code(()->obs.target(o,large,2,twice,"tap"));
        check(why.startsWith("ambiguous_target"),"two matching rows are ambiguous: "+why);
        // A reference with no identity (older gateway or stored ref) never matches by guess.
        var old=new LinkedHashMap<>(ref);old.remove("identity");
        check(code(()->Observations.validateTarget(rerendered,old)).startsWith("stale_target"),"reference without identity is stale");
    }
}
