package com.deviceagent.tools;
import java.util.*;

/** Small validator for the nonrecursive JSON Schema vocabulary used by ToolCatalog. */
public final class JsonSchema {
 private JsonSchema(){}
 public static void validate(Map<String,Object> schema,Object value){if(!matches(schema,value,schema))throw new IllegalArgumentException("Arguments do not match this tool's schema");}
 private static Map<String,Object> map(Object x){return PrimitiveArguments.map(x);}
 private static boolean matches(Map<String,Object> s,Object v,Map<String,Object> root){
  if(s.containsKey("$ref")){Object ref=root;for(String part:((String)s.get("$ref")).substring(2).split("/"))ref=map(ref).get(part);return matches(map(ref),v,root);}
  if(s.containsKey("const")&&!Objects.equals(s.get("const"),v))return false;
  if(s.get("enum") instanceof List<?> list&&!list.contains(v))return false;
  if(s.containsKey("not")&&matches(map(s.get("not")),v,root))return false;
  for(String key:List.of("oneOf","anyOf","allOf"))if(s.get(key) instanceof List<?> options){long count=options.stream().filter(o->matches(map(o),v,root)).count();if(key.equals("oneOf")?count!=1:key.equals("anyOf")?count==0:count!=options.size())return false;}
  String type=Observations.str(s.get("type"));
  if(!type.isEmpty()&&!switch(type){case "object"->v instanceof Map;case "array"->v instanceof List;case "string"->v instanceof String;case "boolean"->v instanceof Boolean;case "integer"->v instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()==Math.rint(n.doubleValue());case "number"->v instanceof Number n&&Double.isFinite(n.doubleValue());default->false;})return false;
  if(v instanceof Map<?,?> m){
   if(!bounds(s,m.size(),"minProperties","maxProperties"))return false;
   if(s.get("required") instanceof List<?> required&&!m.keySet().containsAll(required))return false;
   var props=s.get("properties") instanceof Map?map(s.get("properties")):Map.<String,Object>of();
   if(Boolean.FALSE.equals(s.get("additionalProperties"))&&!props.keySet().containsAll(m.keySet()))return false;
   for(var e:m.entrySet())if(props.containsKey(e.getKey())&&!matches(map(props.get(e.getKey())),e.getValue(),root))return false;
   if(s.get("additionalProperties") instanceof Map<?,?>)for(var e:m.entrySet())if(!props.containsKey(e.getKey())&&!matches(map(s.get("additionalProperties")),e.getValue(),root))return false;
  }
  if(v instanceof List<?> list){if(!bounds(s,list.size(),"minItems","maxItems"))return false;if(Boolean.TRUE.equals(s.get("uniqueItems"))&&new HashSet<>(list).size()!=list.size())return false;if(s.containsKey("items"))for(Object item:list)if(!matches(map(s.get("items")),item,root))return false;}
  if(v instanceof String str){if(!bounds(s,str.codePointCount(0,str.length()),"minLength","maxLength"))return false;if(s.get("pattern") instanceof String pattern&&!java.util.regex.Pattern.compile(pattern).matcher(str).find())return false;}
  if(v instanceof Number n){if(!bounds(s,n.doubleValue(),"minimum","maximum"))return false;if(s.get("exclusiveMinimum") instanceof Number min&&n.doubleValue()<=min.doubleValue())return false;}
  return true;
 }
 private static boolean bounds(Map<String,Object> s,double n,String low,String high){return !(s.get(low) instanceof Number min&&n<min.doubleValue())&&!(s.get(high) instanceof Number max&&n>max.doubleValue());}
}
