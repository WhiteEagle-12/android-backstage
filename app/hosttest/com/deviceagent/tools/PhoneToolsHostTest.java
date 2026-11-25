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
        stableIdentity();stableIds();selectors();defaultScroll();displayTurn();
        System.out.println("PASS phone tools: stable target identity, stable ids in full and failure views, tolerant selectors, default scroll container, display turn recovery");
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
    private static void stableIds(){
        var tree=radioTree("[0,0][1216,2688]","r2");var obs=new Observations(()->0);
        String first=id(obs.observe(tree,40,null,false,false),"Large");
        check(first.equals(id(obs.observe(tree,40,null),"Large")),"full and failure views keep the id an agent holds");
        check(first.equals(id(obs.observe(tree,40,null,true,true),"Large")),"debug view keeps the id");
        var other=radioTree("[0,0][1216,2688]","r2");other.put("display",3);
        check(!first.equals(id(obs.observe(other,40,null),"Large")),"another display never shares ids");
    }
    private static Map<String,Object> control(String id,String label,String resourceId){return Json.mapOf("id",id,"label",label,"role","button","resourceId",resourceId);}
    private static void selectors(){
        var controls=List.of(control("t1","London, United Kingdom","com.android.deskclock:id/city"),control("t2","Month view","p:id/month"),control("t3","Monthly report","p:id/report"),control("t4","Search or type URL","com.android.chrome:id/url_bar"));
        check(ObservationCondition.pick(Map.of("label","London"),controls).size()==1,"unique prefix label is accepted");
        check(ObservationCondition.pick(Map.of("label","united kingdom"),controls).size()==1,"unique contained label is accepted, any case");
        check(ObservationCondition.pick(Map.of("label","Month"),controls).size()==2,"two prefix matches stay ambiguous");
        check(ObservationCondition.pick(Map.of("label","Month view"),controls).size()==1,"exact label wins over prefix");
        check(ObservationCondition.pick(Map.of("label","London","role","switch"),controls).isEmpty(),"other selector fields still apply");
        for(String id:List.of("url_bar","#url_bar","id/url_bar","com.android.chrome:id/url_bar"))check(ObservationCondition.pick(Map.of("resourceId",id),controls).size()==1,"resource id form "+id);
        check(ObservationCondition.pick(Map.of("resourceId","other:id/url_bar"),controls).isEmpty(),"a full resource id of another package never matches");
        check(ObservationCondition.pick(Map.of("resourceId","bar"),controls).isEmpty(),"a partial resource id never matches");
        var near=ObservationCondition.closest(Map.of("label","Search Google or type URL"),controls);
        check(!near.isEmpty()&&near.get(0).equals("Search or type URL"),"closest label comes first: "+near);
        check(ObservationCondition.matches(Map.of("kind","absent","selector",Map.of("label","London")),EvidenceHostTest.tree(List.of(node(0,-1,"c","London, United Kingdom",true))),new Observations(()->0),2),"conditions keep exact labels");
        var b=new EvidenceHostTest.Fake();
        var out=new EvidenceFlow(b).run("android.tap",Map.of("display",2,"selector",Map.of("label","Directons"),"observe",false));
        check("target_not_found".equals(out.get("code"))&&String.valueOf(out.get("error")).contains("Directions")&&out.containsKey("closest"),"a missed label names the closest labels: "+out);
    }
    private static void defaultScroll(){
        var b=new EvidenceHostTest.Fake();
        var small=node(1,-1,"small","",false);small.putAll(Map.of("scrollable",true,"class","HorizontalScrollView","bounds","[0,0][1000,200]"));
        var big=node(2,-1,"big","",false);big.putAll(Map.of("scrollable",true,"class","RecyclerView","bounds","[0,200][1000,1800]"));
        b.nodes.add(small);b.nodes.add(big);
        String session="session_"+"a".repeat(32);
        Map<String,Object> args=new LinkedHashMap<>(Map.of("sessionId",session,"requestId","r1","direction","down"));
        var checked=PrimitiveArguments.scrollForms("android.scroll",args);
        ToolCatalog.validate("android.scroll",checked);PrimitiveArguments.validate("android.scroll",checked);
        check(!args.containsKey("selector"),"the stand-in selector is only for validation");
        args.put("display",2);args.remove("sessionId");args.remove("requestId");
        var out=new EvidenceFlow(b).run("android.scroll",args);
        check(Boolean.TRUE.equals(out.get("ok"))&&"big".equals(b.arguments.get("nodeId")),"scroll with no container uses the largest scrollable: "+out+" "+b.arguments);
        var b2=new EvidenceHostTest.Fake();b2.nodes.add(big);
        Map<String,Object> until=new LinkedHashMap<>(Map.of("sessionId",session,"requestId","r2","direction","down","selector",Map.of("role","scroll"),"until",Map.of("selector",Map.of("label","done"),"maxScrolls",3)));
        checked=PrimitiveArguments.scrollForms("android.scroll",until);
        ToolCatalog.validate("android.scroll",checked);PrimitiveArguments.validate("android.scroll",checked);
        check(Integer.valueOf(3).equals(until.get("maxScrolls"))&&!until.containsKey("selector"),"nested until and role-only selector are accepted");
        until.put("display",2);until.remove("sessionId");until.remove("requestId");
        out=new EvidenceFlow(b2).run("android.scroll",until);
        check(Boolean.TRUE.equals(out.get("found"))&&Integer.valueOf(1).equals(out.get("scrolls")),"scroll until finds the label after one scroll: "+out);
    }
    private static void displayTurn(){try{
        long[] now={0};List<String> log=new ArrayList<>();var turn=new DisplayTurn(()->now[0],log::add);
        check(turn.acquire(0,1_000)&&turn.acquire(0,1_000),"the holder may take its turn again");
        boolean[] got={false};
        var waiter=new Thread(()->{try{got[0]=turn.acquire(0,5_000);}catch(InterruptedException e){throw new AssertionError(e);}});
        waiter.start();waiter.join();
        check(!got[0],"a live holder keeps the turn");
        now[0]=1_000+DisplayTurn.STUCK_GRACE_MS+1;
        waiter=new Thread(()->{try{got[0]=turn.acquire(0,60_000);}catch(InterruptedException e){throw new AssertionError(e);}});
        waiter.start();waiter.join();
        check(got[0]&&log.size()==1&&log.get(0).contains("stuck"),"a waiter takes the turn from a holder long past its deadline");
        turn.release();turn.release();
        check(!turn.heldByCurrentThread(),"the stuck holder's late release does not free the new holder's turn");
        boolean[] third={true};
        waiter=new Thread(()->{try{third[0]=turn.acquire(0,70_000);}catch(InterruptedException e){throw new AssertionError(e);}});
        waiter.start();waiter.join();
        check(!third[0],"the new holder still has the turn");
    }catch(InterruptedException e){throw new AssertionError(e);}}
}
