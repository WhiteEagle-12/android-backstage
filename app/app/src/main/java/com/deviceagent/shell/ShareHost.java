package com.deviceagent.shell;
import android.content.*;
import android.app.ActivityOptions;
import android.net.Uri;
import com.deviceagent.tools.*;
import com.deviceagent.protocol.ShellCommand;
import java.nio.file.*;
import java.util.*;

/** Stage immutable, verified files and launch a display-bound Android share intent. */
final class ShareHost {
 static Map<String,Object> callProvider(String authority,String method,Map<String,Object> args){
  var result=LocalShell.run("content call --uri "+ShellCommand.quote("content://"+authority)+" --method "+method+" --arg "+ShellCommand.quote(Json.stringify(args)),null,30000);
  byte[] bytes=result.getByteArray("stdout");String text=bytes==null?"":new String(bytes,java.nio.charset.StandardCharsets.UTF_8);int from=text.indexOf("result="),end=text.lastIndexOf("}]");
  if(from<0||end<=from)throw new IllegalStateException("Backstage could not prepare the file. Update the phone app and try again.");var out=Json.object(text.substring(from+7,end));if(!Boolean.TRUE.equals(out.get("ok")))throw new IllegalStateException(Objects.toString(out.get("error"),"The file could not be prepared"));return out;
 }
 static Map<String,Object> open(Context shell,String appPackage,Map<String,Object> args,int display,Runnable checkAccess){
  List<Uri> uris=new ArrayList<>();List<Map<String,Object>> files=new ArrayList<>();String authority=appPackage+".shares";boolean dispatched=false;
  try{
   for(Object raw:(List<?>)args.getOrDefault("files",List.of())){
    ActionDeadline.check();var row=PrimitiveArguments.map(raw);Path source=Paths.get(Json.string(row,"path"));
    if(!Files.isRegularFile(source))throw new IllegalArgumentException("Choose a regular file to share");
    var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(source)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){ActionDeadline.check();digest.update(bytes,0,n);}}
    StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b));
    var created=callProvider(authority,"create",Map.of("name",row.getOrDefault("name",source.getFileName().toString()),"mimeType",row.get("mimeType")));Uri uri=Uri.parse((String)created.get("uri"));uris.add(uri);
    var copied=LocalShell.run("content write --uri "+ShellCommand.quote(uri.toString())+" < "+ShellCommand.quote(source.toString()),null,30000);
    if(copied.getInt("exit")!=0)throw new IllegalStateException("The attachment could not be copied");
    files.add(callProvider(authority,"finish",Map.of("uri",uri.toString(),"sha256",hash.toString())));
   }
   Intent intent=new Intent(uris.size()>1?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND).setType(Json.string(args,"mimeType"));
   if(args.containsKey("text"))intent.putExtra(Intent.EXTRA_TEXT,Json.string(args,"text"));
   if(args.containsKey("subject"))intent.putExtra(Intent.EXTRA_SUBJECT,Json.string(args,"subject"));
   if(!uris.isEmpty()){
    ClipData clip=new ClipData("Attachments",new String[]{Json.string(args,"mimeType")},new ClipData.Item(uris.get(0)));for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));intent.setClipData(clip);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if(uris.size()==1)intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(uris));
   }
   if(args.containsKey("activity"))intent.setComponent(new ComponentName(Json.string(args,"package"),Json.string(args,"activity").startsWith(".")?Json.string(args,"package")+Json.string(args,"activity"):Json.string(args,"activity")));
   else if(args.containsKey("package"))intent.setPackage(Json.string(args,"package"));
   if(!args.containsKey("package"))intent=Intent.createChooser(intent,null);
   intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);var options=ActivityOptions.makeBasic();options.setLaunchDisplayId(display);ActionDeadline.check();checkAccess.run();
   // app_process has no registered ApplicationThread. Use the same null-caller
   // ActivityTaskManager entry point as Android's `am start`, preserving URI extras.
   Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
   var start=Class.forName("android.app.IActivityTaskManager").getMethod("startActivityAsUser",Class.forName("android.app.IApplicationThread"),String.class,String.class,Intent.class,String.class,android.os.IBinder.class,String.class,int.class,int.class,Class.forName("android.app.ProfilerInfo"),android.os.Bundle.class,int.class);
   dispatched=true;int started=(Integer)start.invoke(service,null,"com.android.shell",null,intent,intent.getType(),null,null,0,0,null,options.toBundle(),android.os.Process.myUid()/100000);
   if(started<0)return Json.mapOf("ok",false,"inputDelivered",false,"code","share_rejected","error","Android could not open the receiving app.","androidResult",started);
   return Json.mapOf("ok",true,"inputDelivered",true,"display",display,"files",files);
  }catch(Exception e){return Json.mapOf("ok",false,"inputDelivered",dispatched?null:false,"code",dispatched?"input_outcome_unknown":"share_prepare_failed","error",dispatched?"Check the receiving app before sharing again.":Objects.toString(e.getMessage(),"The files could not be prepared"));}
  finally{if(!dispatched)for(Uri uri:uris)try{callProvider(authority,"remove",Map.of("uri",uri.toString()));}catch(Exception ignored){}}
 }
}
