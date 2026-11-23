package com.deviceagent.tools;
import java.util.*;
import java.util.regex.Pattern;

/** Only the active notification section is a current record source. */
public final class NotificationRecords {
 private NotificationRecords() {}
 private static final Pattern RECORD=Pattern.compile("^\\s+NotificationRecord\\(.*?\\bpkg=([A-Za-z0-9_.]+)\\s+user=UserHandle\\{(-?\\d+)\\}\\s+id=(-?\\d+)\\s");
 private static final Pattern EXTRA=Pattern.compile("^\\s+android\\.(title|text|bigText|subText)=(?:String|SpannableString|SpannedString|SpannableStringBuilder)\\s*\\((.*)\\)$");
 public static Map<String,Object> parse(int exit,String stdout,String stderr,int max){
  if(exit!=0||!stderr.isBlank())return failure("notification_read_failed","Notifications could not be read. Try again.");
  boolean active=false,found=false;String pendingKey=null;StringBuilder pending=null;List<Map<String,Object>> rows=new ArrayList<>();Map<String,Object> current=null;
  for(String line:stdout.split("\\R")){
   if(pending!=null){
    pending.append("\n").append(line);
    if(line.endsWith(")")){pending.setLength(pending.length()-1);current.putIfAbsent(pendingKey,pending.toString());pending=null;pendingKey=null;}
    continue;
   }
   if(line.trim().equals("Notification List:")){active=true;found=true;continue;}
   if(!active)continue;
   if(!line.isBlank()&&line.length()-line.stripLeading().length()<=2)break;
   var record=RECORD.matcher(line);
   if(line.stripLeading().startsWith("NotificationRecord(")&&!record.find())return failure("notification_format_unsupported","Android did not return readable notifications. Read them in the notification shade.");
   record.reset();
   if(record.find()){
    if(pending!=null)return failure("notification_format_unsupported","Android returned incomplete notification text. Read it in the notification shade.");
    current=Json.mapOf("package",record.group(1),"userId",Integer.parseInt(record.group(2)),"id",Integer.parseInt(record.group(3)));
    rows.add(current);continue;
   }
   // Keep the main notification's first value; a nested publicVersion may
   // repeat extras and must not overwrite the active notification's content.
   if(current!=null){
     var value=EXTRA.matcher(line);
     if(value.matches())current.putIfAbsent(value.group(1),value.group(2));
     else{var start=Pattern.compile("^\\s+android\\.(title|text|bigText|subText)=(?:String|SpannableString|SpannedString|SpannableStringBuilder)\\s*\\((.*)$").matcher(line);
      if(start.matches()){pendingKey=start.group(1);pending=new StringBuilder(start.group(2));}
     }
   }
  }
  if(pending!=null)return failure("notification_format_unsupported","Android returned incomplete notification text. Read it in the notification shade.");
  if(!found)return failure("notification_format_unsupported","Android did not return readable notifications. Read them in the notification shade.");
  int keep=Math.min(Math.max(1,max),rows.size());
  var out=Json.mapOf("ok",true,"count",keep,"total",rows.size(),"notifications",new ArrayList<>(rows.subList(0,keep)));
  if(keep<rows.size())out.put("truncated",true);
  return out;
 }
 private static Map<String,Object> failure(String code,String message){return Json.mapOf("ok",false,"code",code,"error",message);}
}
