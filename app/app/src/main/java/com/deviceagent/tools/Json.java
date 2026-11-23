package com.deviceagent.tools;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    public static Object parse(String text) {
        return new Parser(text).parseValue();
    }

    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("expected JSON object");
    }

    public static String string(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static int integer(Map<String, Object> obj, String key, int fallback) {
        Object value = obj.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Integer.parseInt((String) value);
        }
        return fallback;
    }

    public static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof byte[]) {
            writeString(out, b64((byte[]) value));
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < ' ') out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int i;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skip();
            if (i >= text.length()) throw error("unexpected end");
            return switch (text.charAt(i)) {
                case '"' -> parseString();
                case '{' -> parseObject();
                case '[' -> parseArray();
                case 't' -> parseLiteral("true", true);
                case 'f' -> parseLiteral("false", false);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skip();
                String key = parseString();
                skip();
                expect(':');
                map.put(key, parseValue());
                skip();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skip();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = text.charAt(i++);
                out.append(switch (e) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'u' -> {
                        int code = Integer.parseInt(text.substring(i, i + 4), 16);
                        i += 4;
                        yield (char) code;
                    }
                    default -> throw error("bad escape");
                });
            }
            throw error("unterminated string");
        }

        private Object parseNumber() {
            int start = i;
            if (peek('-')) i++;
            while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
            boolean frac = false;
            if (peek('.')) {
                frac = true;
                i++;
                while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
            }
            String raw = text.substring(start, i);
            if (frac) return Double.parseDouble(raw);
            long n = Long.parseLong(raw);
            if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return (int) n;
            return n;
        }

        private Object parseLiteral(String lit, Object value) {
            if (!text.startsWith(lit, i)) throw error("expected " + lit);
            i += lit.length();
            return value;
        }

        private void skip() {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < text.length() && text.charAt(i) == c;
        }

        private void expect(char c) {
            skip();
            if (!peek(c)) throw error("expected " + c);
            i++;
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at " + i);
        }
    }
}
