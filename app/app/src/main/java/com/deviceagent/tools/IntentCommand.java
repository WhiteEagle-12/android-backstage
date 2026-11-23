package com.deviceagent.tools;

import java.util.*;
import com.deviceagent.protocol.ShellCommand;

/** Typed activity intents; every value remains a shell argument, never command text. */
public final class IntentCommand {
    private IntentCommand() {}
    public static String build(Map<String,Object> a,int display){
        if(display<0)throw new IllegalArgumentException("A session display is required");
        if(!Set.of("action","data","mimeType","package","activity","categories","extras").containsAll(a.keySet()))throw new IllegalArgumentException("Unknown intent field");
        PrimitiveArguments.text(a,"action",false,2048);
        StringBuilder out=new StringBuilder("am start -W --activity-multiple-task --display ").append(display);
        arg(out,"-a",a.get("action"));
        for(String k:List.of("data","mimeType","package","activity"))if(a.containsKey(k))PrimitiveArguments.text(a,k,false,10000);
        if(a.containsKey("data"))arg(out,"-d",a.get("data"));
        if(a.containsKey("mimeType"))arg(out,"-t",a.get("mimeType"));
        if(a.containsKey("package")&&!((String)a.get("package")).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Invalid intent package");
        if(a.containsKey("activity")){
            if(!a.containsKey("package")||!((String)a.get("activity")).matches("[A-Za-z0-9_.$]+"))throw new IllegalArgumentException("Intent activity requires a package and class name");
            arg(out,"-n",a.get("package")+"/"+a.get("activity"));
        }else if(a.containsKey("package"))arg(out,"-p",a.get("package"));
        if(a.containsKey("categories")){
            if(!(a.get("categories") instanceof List<?> rows)||rows.size()>16)throw new IllegalArgumentException("Use at most 16 intent categories");
            for(Object row:rows){if(!(row instanceof String s)||s.isBlank()||s.length()>2048)throw new IllegalArgumentException("Invalid category");arg(out,"-c",row);}
        }
        if(a.containsKey("extras")){
            if(!(a.get("extras") instanceof List<?> rows)||rows.size()>64)throw new IllegalArgumentException("Use at most 64 intent extras");
            Set<String> names=new HashSet<>();
            for(Object row:rows){var e=PrimitiveArguments.map(row);if(!e.keySet().equals(Set.of("name","type","value")))throw new IllegalArgumentException("Extra requires name, type and value");
                PrimitiveArguments.text(e,"name",false,2048);String name=(String)e.get("name");if(!names.add(name))throw new IllegalArgumentException("Duplicate extra name");
                PrimitiveArguments.text(e,"value",true,10000);Object value=e.get("value");String flag;
                switch(String.valueOf(e.get("type"))){
                    case "string","uri" -> {PrimitiveArguments.text(e,"value",true,10000);flag="uri".equals(e.get("type"))?"--eu":"--es";}
                    case "boolean" -> {if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("Boolean extra needs a boolean");flag="--ez";}
                    case "integer","long" -> {String number=(String)value;if(!number.matches("-?[0-9]+"))throw new IllegalArgumentException("Invalid integer extra");long n;try{n=Long.parseLong(number);}catch(NumberFormatException e2){throw new IllegalArgumentException("Integer extra is out of range");}if("integer".equals(e.get("type"))&&(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE))throw new IllegalArgumentException("Integer extra is out of range");value=n;flag="integer".equals(e.get("type"))?"--ei":"--el";}
                    default -> throw new IllegalArgumentException("Unknown intent extra type");
                }
                arg(out,flag,name);out.append(' ').append(ShellCommand.quote(String.valueOf(value)));
            }
        }
        return out.toString();
    }
    private static void arg(StringBuilder out,String flag,Object value){out.append(' ').append(flag).append(' ').append(ShellCommand.quote(String.valueOf(value)));}
}
