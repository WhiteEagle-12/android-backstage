package com.deviceagent.tools;

import java.nio.file.*;
import java.util.*;

public final class SemanticHostTest {
    private static void check(boolean value,String why){EvidenceHostTest.check(value,why);}
    private static Map<String,Object> node(int i,int p,String id,String label,boolean click){return EvidenceHostTest.node(i,p,id,label,click);}
    private static Map<String,Object> tree(List<Map<String,Object>> nodes){
        var tree=EvidenceHostTest.tree(nodes);
        PrimitiveArguments.map(((List<?>)tree.get("windows")).get(0)).put("bounds","[0,0][1000,2000]");
        return tree;
    }
    private static List<Map<String,Object>> controls(Map<String,Object> view){return ((List<?>)view.get("controls")).stream().map(PrimitiveArguments::map).toList();}
    private static Map<String,Object> control(Map<String,Object> view,String label){return controls(view).stream().filter(c->label.equals(c.get("label"))).findFirst().orElseThrow();}
    public static void run() throws Exception {
        battery();relationships();windowIdentity();tapPlan();receiptsAndConditions();
        System.out.println("PASS semantic controls: Redmagic rows, state ownership, text groups, safe web taps, verification receipts and compact conditions");
    }
    private static void battery() throws Exception {
        var tree=Json.object(Files.readString(Path.of(System.getProperty("backstage.fixtures"),"redmagic-battery.json")));
        var obs=new Observations(()->0);var view=obs.observe(tree,40,null);
        for(String label:List.of("Power saver","Ultra Power saver","Turbo charge","Extreme Cold Mode")){
            var c=control(view,label);check("switch".equals(c.get("role"))&&Boolean.FALSE.equals(c.get("checked")),"real switch state: "+label);
            var ref=obs.target((String)view.get("observation"),(String)c.get("id"),106,tree);
            var target=Observations.validateTarget(tree,ref);
            check("Switch".equals(target.get("class")),"tap targets actual switch: "+label);
        }
        var radio=control(view,"80% upper limit");
        check("radio".equals(radio.get("role"))&&Boolean.FALSE.equals(radio.get("checked")),"non-clickable radio state is attached to row action");
        check(controls(view).stream().noneMatch(c->"53".equals(c.get("label"))),"scroll containers do not consume a numeric value as their name");
        String content=Json.stringify(view.get("content"));
        check(content.contains("53%")&&content.contains("Remaining battery")&&content.contains("24h 9min"),"percentage and estimated time retain separate text groups");
        check(ObservationCondition.matches(Map.of("kind","exists","selector",Map.of("label","53%")),tree,obs,106),"combined visible text can verify existence");
        check(!ObservationCondition.matches(Map.of("kind","absent","selector",Map.of("label","53%")),tree,obs,106),"combined visible text cannot verify absence");
        check(Json.stringify(control(view,"Power saver").get("details")).contains("33h 30min"),"row details stay attached to their control");
        String id=(String)radio.get("id");
        Observations.nodes(tree).stream().filter(n->"RadioButton".equals(n.get("class"))).findFirst().orElseThrow().put("checked",true);
        ObservationHostTest.rejects(()->obs.target((String)view.get("observation"),id,106,tree));
    }
    private static void relationships(){
        var row=node(0,-1,"row","",true);row.put("bounds","[0,0][800,300]");
        var title=node(1,0,"title","Download",false);var toggle=node(2,0,"switch","",true);toggle.put("class","Switch");toggle.put("checked",false);
        var labelOutside=node(3,-1,"label","Outside label",false);
        var field=node(4,-1,"field","typed",true);field.putAll(Map.of("editable",true,"accessibilityLabel","Outside label","labeledBy","label"));
        var repeated=node(5,-1,"repeat","Download",false);
        var unknown=node(6,-1,"unknown","Sync",true);unknown.put("class","Switch");
        var tree=tree(List.of(row,title,toggle,labelOutside,field,repeated,unknown));var view=new Observations(()->0).observe(tree,40,null);
        check(controls(view).stream().filter(c->"Download".equals(c.get("label"))).count()==1,"single state row has one named switch");
        check(((List<?>)view.get("content")).contains("Download"),"unrelated duplicate text remains visible");
        check(!Json.stringify(view.get("content")).contains("Outside label"),"explicit field label does not repeat as unrelated content");
        check("unknown".equals(control(view,"Sync").get("state")),"missing switch state stays unknown");
        check(!control(view,"Sync").containsKey("checked"),"unknown never means off");
        var condition=Map.<String,Object>of("conditions",List.of(Map.of("kind","state","selector",Map.of("label","Sync"),"checked",false)));
        check(!ObservationCondition.matches(condition,tree,new Observations(()->0),2),"unknown switch cannot verify off");
        toggle.put("checked",true);var fresh=new Observations(()->0).observe(tree,40,null);
        check(Boolean.TRUE.equals(control(fresh,"Download").get("checked")),"switch state is read afresh");
        var link=node(0,-1,"link","",true);link.put("contentDescription","Go back");link.put("roleDescription","link");
        var a=node(1,0,"a","Go",false);a.put("bounds","[0,0][50,50]");var b=node(2,0,"b","back",false);b.put("bounds","[50,0][100,50]");
        var joined=new Observations(()->0).observe(tree(List.of(link,a,b)),40,null);
        check(!control(joined,"Go back").containsKey("details"),"joined text does not repeat a complete control label");
        List<Map<String,Object>> many=new ArrayList<>();for(int i=0;i<301;i++)many.add(node(i,-1,"item"+i,"Item "+i,true));
        var large=tree(many);var observations=new Observations(()->0);var prior=observations.observe(large,300,null);
        var last=Map.<String,Object>of("label","Item 300","role","button");
        check(!ObservationCondition.matches(Map.of("kind","absent","selector",last),large,observations,2),"shortened observations cannot prove absence");
        check(!ObservationCondition.matches(Map.of("kind","appeared","selector",last,"observation",prior.get("observation")),tree(List.of(many.get(300))),observations,2),"shortened prior observations cannot prove appearance");
        var longText=tree(List.of(node(0,-1,"long","x".repeat(401),false)));
        check(Boolean.TRUE.equals(new Observations(()->0).observe(longText,40,null).get("truncated")),"shortened static text reports truncation");
        check(!ObservationCondition.matches(Map.of("kind","exists","selector",Map.of("label","x".repeat(400))),longText,observations,2),"a shortened text prefix cannot verify an exact label");
    }
    private static void windowIdentity(){
        var button=node(0,-1,"button","Open",true);var tree=tree(List.of(button));var observations=new Observations(()->0);var view=observations.observe(tree,40,null);
        var window=PrimitiveArguments.map(((List<?>)tree.get("windows")).get(0));window.put("title","");
        var ref=observations.target((String)view.get("observation"),(String)control(view,"Open").get("id"),2,tree);
        check("button".equals(ref.get("nodeId")),"temporarily missing window title does not change target identity");
        check(!ObservationCondition.matches(Map.of("kind","window","title","Page"),tree,observations,2),"title verification does not invent a missing title");
        // While the original window is still open, a copy in another window is a different control.
        var stays=node(1,-1,"label","Header",false);stays.put("windowKey",ref.get("windowKey"));
        var both=tree(new ArrayList<>(List.of(button,stays)));button.put("windowKey","another-window");
        ObservationHostTest.rejects(()->Observations.validateTarget(both,ref));
        // When the original window is gone (the activity was recreated), the same control matches.
        var recreated=tree(List.of(button));PrimitiveArguments.map(((List<?>)recreated.get("windows")).get(0)).put("key","another-window");
        check("button".equals(Observations.validateTarget(recreated,ref).get("nodeId")),"a recreated window keeps the control");
    }
    private static void tapPlan(){
        var web=node(0,-1,"web","",false);web.put("class","WebView");web.put("bounds","[0,100][1000,1800]");
        var link=node(1,0,"link","Learn more",true);link.put("roleDescription","link visited");link.put("bounds","[100,50][300,150]");
        var tree=tree(List.of(web,link));
        check("link".equals(Observations.role(link))&&TargetTap.webLink(tree,link),"visited web link selects touch before input");
        check(Arrays.equals(TargetTap.point(tree,link),new int[]{200,125}),"point clips to web viewport");
        web.put("class","FrameLayout");check(!TargetTap.webLink(tree,link),"native accessibility links retain native input");
        link.put("bounds","[100,1900][300,2100]");ObservationHostTest.rejects(()->TargetTap.point(tree,link));
        link.put("bounds","[100,100][300,150]");
        ((List<Map<String,Object>>)tree.get("windows")).get(0).put("layer",0);
        tree.put("windows",List.of(((List<?>)tree.get("windows")).get(0),Json.mapOf("id",2,"layer",1,"bounds","[0,0][400,400]")));
        ObservationHostTest.rejects(()->TargetTap.point(tree,link));
        tree.put("windows",List.of(((List<?>)tree.get("windows")).get(0),Json.mapOf("id",2,"layer",1,"bounds","[180,100][300,150]")));
        check(Arrays.equals(TargetTap.point(tree,link),new int[]{140,125}),"partly covered control uses its visible portion");
        tree.put("windows",List.of(((List<?>)tree.get("windows")).get(0),Json.mapOf("id",2,"layer",1,"bounds","[100,100][300,130]"),Json.mapOf("id",3,"layer",2,"bounds","[100,120][170,150]")));
        check(Arrays.equals(TargetTap.point(tree,link),new int[]{235,140}),"overlapping overlays leave a clear point");
    }
    private static void receiptsAndConditions(){
        var b=new EvidenceHostTest.Fake();var flow=new EvidenceFlow(b);
        var out=flow.run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions"),"observe",false));
        check("unverified".equals(PrimitiveArguments.map(out.get("verification")).get("status")),"delivered tap does not claim verified intent");
        var condition=Map.<String,Object>of("conditions",List.of(EvidenceHostTest.exists("Done")));
        out=flow.run("android.observe",Map.of("display",2,"verify",condition,"timeoutMs",0));
        check("verified".equals(PrimitiveArguments.map(out.get("verification")).get("status")),"compact condition verifies observed state");
        out=flow.run("android.tap",Map.of("display",2,"selector",Map.of("label","Directions"),"verify",Map.of("conditions",List.of(EvidenceHostTest.exists("Never"))),"timeoutMs",0));
        check(b.writes==2&&Boolean.TRUE.equals(out.get("inputDelivered"))&&"failed".equals(PrimitiveArguments.map(out.get("verification")).get("status")),"failed verification never repeats accepted input");
        ObservationCondition.validate(condition);
        ObservationHostTest.rejects(()->ObservationCondition.validate(Map.of("conditions",List.of(condition))));
        ObservationHostTest.rejects(()->ObservationCondition.validate(Map.of("conditions",List.of(EvidenceHostTest.exists("Done")),"match","sometimes")));
        ObservationHostTest.rejects(()->ObservationCondition.validate(Map.of("kind","state","selector",Map.of("label","X"))));
        var any=Map.<String,Object>of("conditions",List.of(EvidenceHostTest.exists("Never"),EvidenceHostTest.exists("Done")),"match","any");
        check(ObservationCondition.matches(any,b.tree(2),flow.observations(),2),"compact any is disjunction");
        any=new LinkedHashMap<>(any);any.put("match","all");check(!ObservationCondition.matches(any,b.tree(2),flow.observations(),2),"compact all is conjunction");
        String session="session_"+"a".repeat(32);
        ToolCatalog.validate("android.wait",Map.of("sessionId",session,"verify",condition));
        ToolCatalog.validate("android.wait",Map.of("sessionId",session,"verify",EvidenceHostTest.exists("Done")));
    }
}
