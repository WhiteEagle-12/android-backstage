package com.deviceagent.tools;
import java.util.Map;
/** Shell exit zero does not prove the clipboard command exists. */
public final class ClipboardResult {
 private ClipboardResult() {}
 public static Map<String,Object> parse(int exit,String stdout,String stderr,boolean write){
  String diagnostic=stdout+"\n"+stderr;
  if(diagnostic.contains("No shell command implementation")||diagnostic.contains("Unknown command")||diagnostic.contains("Can't find service: clipboard"))
   return Json.mapOf("ok",false,"code","clipboard_unavailable","inputDelivered",false,"error","Android does not expose clipboard access through this connection. Enter text directly in the target app.");
  if(exit!=0||!stderr.isBlank())return Json.mapOf("ok",false,"code","clipboard_failed","inputDelivered",write?null:false,"error",write?"The clipboard change could not be confirmed. Check the clipboard before trying again.":"The clipboard could not be read. Enter text directly in the target app.");
  return write?Json.mapOf("ok",true,"inputDelivered",true):Json.mapOf("ok",true,"text",stdout.endsWith("\n")?stdout.substring(0,stdout.length()-1):stdout);
 }
}
