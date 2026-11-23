package com.deviceagent.tools;

/** Field values and comparisons; labels never make general text matching fuzzy. */
public final class FieldText {
    private FieldText() {}
    public static String value(String text,boolean showingHint,String hint,String description,String knownHint) {
        if(showingHint)return "";
        // Some delegates remove hintText and append the previously exposed hint
        // to both text and description. Require that same field's prior hint.
        String suffix=", "+knownHint;
        if(hint.isEmpty()&&!knownHint.isEmpty()&&text.equals(description)&&text.endsWith(suffix))
            return text.substring(0,text.length()-suffix.length());
        return text;
    }
    public static boolean matches(String expected,String actual,boolean phone,boolean hidden) {
        if(hidden)return false;
        if(expected.equals(actual))return true;
        if(!phone)return false;
        String a=phoneText(expected),b=phoneText(actual);
        return !a.isEmpty()&&a.equals(b);
    }
    private static String phoneText(String value) {
        // Remove visual separators only. Retain country prefixes, pauses, waits,
        // service codes and letters; never equate a shortened or changed number.
        return value.replaceAll("[ \\u00a0\\u202f()\\-]","");
    }
}
