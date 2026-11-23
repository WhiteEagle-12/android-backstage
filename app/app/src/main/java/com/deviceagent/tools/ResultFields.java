package com.deviceagent.tools;
import java.util.*;
/** Explicit data projection before a result leaves the phone. */
public final class ResultFields {
 private ResultFields(){}
 public static Map<String,Object> packageInfo(Map<String,Object> full,Map<String,Object> args){
  List<String> fields=new ArrayList<>(List.of("package","versionName","versionCode"));
  boolean diagnostics=Boolean.TRUE.equals(args.get("diagnostics"));
  if(args.containsKey("fields")){
   if(!(args.get("fields") instanceof List<?> requested)||requested.isEmpty()||requested.size()>5)throw new IllegalArgumentException("fields must contain one to five package fields");fields.clear();
   for(var value:requested){if(!(value instanceof String s)||!Set.of("package","versionName","versionCode","debuggable","permissions").contains(s))throw new IllegalArgumentException("Unknown package field");if(s.equals("permissions")&&!diagnostics)throw new IllegalArgumentException("Permission details require diagnostics=true");fields.add(s);}
  }else if(diagnostics)fields.addAll(List.of("debuggable","permissions"));
  Map<String,Object> out=Json.mapOf("ok",true);for(String field:fields)out.put(field,full.get(field));return out;
 }
}
