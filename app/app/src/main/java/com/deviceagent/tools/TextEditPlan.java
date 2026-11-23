package com.deviceagent.tools;

/** Build one text replacement, preserving the selection semantics of typing. */
public final class TextEditPlan {
    public final String value;
    public final int cursor;
    private TextEditPlan(String value,int cursor) {this.value=value;this.cursor=cursor;}
    public static TextEditPlan create(String current,int start,int end,String text,boolean replace,boolean password) {
        if(text==null)throw new InputNotSentException("invalid_arguments","text is required");
        if(replace)return new TextEditPlan(text,text.length());
        if(password)throw new InputNotSentException("text_unavailable","This field hides its text. Use mode=replace to set its full value.");
        if("".equals(current)&&start<=0&&end<=0){start=0;end=0;}
        if(current==null||start<0||end<0||start>current.length()||end>current.length())
            throw new InputNotSentException("selection_unavailable","This field does not expose its cursor. Use mode=replace to set its full value.");
        int from=Math.min(start,end),to=Math.max(start,end);
        if(splitsPair(current,from)||splitsPair(current,to))throw new InputNotSentException("invalid_selection","The cursor splits a character. Select the field again or use mode=replace.");
        return new TextEditPlan(current.substring(0,from)+text+current.substring(to),from+text.length());
    }
    private static boolean splitsPair(String s,int at) {
        return at>0&&at<s.length()&&Character.isHighSurrogate(s.charAt(at-1))&&Character.isLowSurrogate(s.charAt(at));
    }
}
