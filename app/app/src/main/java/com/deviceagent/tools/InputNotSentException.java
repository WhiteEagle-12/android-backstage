package com.deviceagent.tools;

/** A failure proven to precede input dispatch. */
public final class InputNotSentException extends IllegalArgumentException {
    public final String code;
    public final java.util.Map<String,Object> details;
    public InputNotSentException(String code,String message){this(code,message,java.util.Map.of());}
    public InputNotSentException(String code,String message,java.util.Map<String,Object> details){super(message);this.code=code;this.details=details;}
}
