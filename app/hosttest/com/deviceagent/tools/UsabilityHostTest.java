package com.deviceagent.tools;
import java.util.*;
public final class UsabilityHostTest {
 private static void check(boolean b,String why){EvidenceHostTest.check(b,why);}
 public static void run(){
  check(FieldText.value("First name",true,"First name","First name","First name").isEmpty(),"empty hint is no value");
  check(FieldText.value("Ada, First name",false,"","Ada, First name","First name").equals("Ada"),"known hint separated from decorated value");
  check(FieldText.value("Ada, First name",false,"","Ada, First name","").equals("Ada, First name"),"unknown suffix remains text");
  check(FieldText.value("Ada, First name",false,"First name","Ada, First name","First name").equals("Ada, First name"),"real hint metadata prevents stripping literal suffix");
  check(FieldText.value("Ada, First name, First name",false,"","Ada, First name, First name","First name").equals("Ada, First name"),"only one known decoration removed");
  check(FieldText.matches("+12025550123","+1 (202) 555-0123",true,false),"phone formatting matches");
  for(String value:List.of("12025550123","+1202555012","+12025550123,","+12025550123;","+12025550123#"))check(!FieldText.matches("+12025550123",value,true,false),"meaningful dial characters remain distinct");
  check(!FieldText.matches("a-b","ab",false,false)&&!FieldText.matches("x","x ",false,false)&&!FieldText.matches("x","x",false,true),"plain and hidden text stay strict");
  for(boolean write:List.of(false,true))check(Boolean.FALSE.equals(ClipboardResult.parse(0,"No shell command implementation.\n","",write).get("ok")),"zero exit unsupported clipboard fails");
  check(ClipboardResult.parse(0," x \n\n","",false).get("text").equals(" x \n"),"clipboard whitespace preserved");
  check(ClipboardResult.parse(1,"","Denied",true).get("inputDelivered")==null,"uncertain clipboard write never claims no input");
  String dump="Notification service\n  Notification List:\n    NotificationRecord(0xabc: pkg=com.example.mail user=UserHandle{0} id=42 tag=null)\n                android.title=String(A title)\n                android.text=SpannableString(A (message))\n                android.title=String(Public title)\n    NotificationRecord(0xdef: pkg=com.example.clock user=UserHandle{0} id=43 tag=null)\n                android.title=String(Timer)\n  Snoozed notifications:\n    NotificationRecord(0xabc: pkg=com.example.old user=UserHandle{0} id=44 tag=null)\n";
  var records=NotificationRecords.parse(0,dump,"",1);check(records.get("count").equals(1)&&records.get("total").equals(2)&&Boolean.TRUE.equals(records.get("truncated")),"max counts complete active records");
  var row=PrimitiveArguments.map(((List<?>)records.get("notifications")).get(0));check(row.get("text").equals("A (message)")&&row.get("title").equals("A title"),"extras clean and public version does not overwrite");
  check(Boolean.FALSE.equals(NotificationRecords.parse(0,"unexpected output","",20).get("ok")),"unknown format not empty success");
  check(NotificationRecords.parse(0,"  Notification List:\n  Ranking Config:\n","",20).get("count").equals(0),"valid empty list succeeds");
  var multiline=NotificationRecords.parse(0,dump.replace("A (message)","First line\nsecond line"),"",2);
  check(PrimitiveArguments.map(((List<?>)multiline.get("notifications")).get(0)).get("text").equals("First line\nsecond line"),"multiline notification text retained");
  check(Boolean.FALSE.equals(NotificationRecords.parse(0,dump.replace("user=UserHandle{0}","user=unexpected"),"",20).get("ok")),"unrecognized records are not reported as an empty list");
  var spaced=NotificationRecords.parse(0,dump.replace("String(","String ("),"",2);
  var spacedRow=PrimitiveArguments.map(((List<?>)spaced.get("notifications")).get(0));
  check("A title".equals(spacedRow.get("title"))&&"A (message)".equals(spacedRow.get("text")),"Android 16 spaces before notification values retain title and text");
  var spacedMultiline=NotificationRecords.parse(0,dump.replace("String(","String (").replace("A (message)","café 日本語\nsecond line"),"",2);
  check("café 日本語\nsecond line".equals(PrimitiveArguments.map(((List<?>)spacedMultiline.get("notifications")).get(0)).get("text")),"spaced multiline Unicode notification retained");
  fieldCondition();contexts();transition();
  System.out.println("PASS usability: hint values, phone formatting, clipboard outcomes, notification records, delayed observations");
 }
 private static void fieldCondition(){
  var phone=EvidenceHostTest.node(0,-1,"phone","+1 (202) 555-0123",true);phone.put("editable",true);phone.put("inputType",3);phone.put("hint","Phone");
  var condition=Json.mapOf("kind","field","selector",Map.of("label","Phone"),"value","+12025550123");
  check(ObservationCondition.matches(condition,EvidenceHostTest.tree(List.of(phone)),new Observations(()->0),2),"field condition uses phone type metadata");
  phone.put("inputType",1);check(!ObservationCondition.matches(condition,EvidenceHostTest.tree(List.of(phone)),new Observations(()->0),2),"text fields never inherit phone formatting");
 }
 private static void contexts(){
  var row=EvidenceHostTest.node(0,-1,"row","",false);row.put("bounds","[0,0][300,100]");
  var filename=EvidenceHostTest.node(1,0,"file","receipt.pdf",true);var menu=EvidenceHostTest.node(2,0,"menu","Show file actions",true);
  var tree=EvidenceHostTest.tree(List.of(row,filename,menu));PrimitiveArguments.map(((List<?>)tree.get("windows")).get(0)).put("bounds","[0,0][1000,1000]");
  var view=new Observations(()->0).observe(tree,40,null);var action=((List<?>)view.get("controls")).stream().map(PrimitiveArguments::map).filter(c->"Show file actions".equals(c.get("label"))).findFirst().orElseThrow();
  check("receipt.pdf".equals(action.get("context")),"clickable filename supplies file-action context");
  var repeated=EvidenceHostTest.tree(List.of(EvidenceHostTest.node(0,-1,"one","Image",true),EvidenceHostTest.node(1,-1,"two","Image",true)));
  check(new Observations(()->0).observe(repeated,40,null).containsKey("ambiguousControls"),"indistinguishable controls trigger image fallback metadata");
 }
 private static void transition(){
  var button=EvidenceHostTest.node(0,-1,"button","Cancel",true);int[] reads={0},writes={0};long[] time={0};
  var backend=new EvidenceFlow.Backend(){
   public Map<String,Object> action(String name,Map<String,Object> args){writes[0]++;return Json.mapOf("ok",true,"inputDelivered",true);}
   public Map<String,Object> tree(int display){reads[0]++;return EvidenceHostTest.tree(writes[0]>0&&reads[0]>=4?List.of(EvidenceHostTest.node(1,-1,"discard","Discard",true)):List.of(button));}
   public Map<String,Object> screen(int d){return Map.of();}public String focus(int d){return "p/.Main";}public Map<String,Object> display(){return Json.mapOf("active",true,"displayId",2);}
  };
  var result=new EvidenceFlow(backend,()->{time[0]+=50;return time[0];}).run("android.tap",Map.of("display",2,"selector",Map.of("label","Cancel")));
  check(writes[0]==1&&((List<?>)result.get("controls")).stream().map(PrimitiveArguments::map).anyMatch(c->"Discard".equals(c.get("label"))),"post-action snapshot waits for delayed dialog without repeating tap");
 }
}
