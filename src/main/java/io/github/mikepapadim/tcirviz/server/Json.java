/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.mikepapadim.tcirviz.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON: writes maps, collections, strings, numbers, booleans and null; parses the small
 * request bodies the browser sends. No dependency, so the tool runs on the SDK's classpath alone.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        var sb = new StringBuilder(4096);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> string(sb, s);
            case Boolean b -> sb.append(b);
            case Double d -> sb.append(Double.isFinite(d) ? d.toString() : "null");
            case Float f -> sb.append(Float.isFinite(f) ? f.toString() : "null");
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (var v : c) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, v);
                }
                sb.append(']');
            }
            case int[] a -> {
                sb.append('[');
                for (int i = 0; i < a.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(a[i]);
                }
                sb.append(']');
            }
            default -> string(sb, value.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parses a JSON document into maps, lists, strings, doubles, booleans and null. */
    public static Object parse(String text) {
        var p = new Parser(text);
        var value = p.value();
        p.skip();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            var map = new LinkedHashMap<String, Object>();
            pos++;
            skip();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skip();
                var key = string();
                skip();
                expect(':');
                map.put(key, value());
                skip();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        List<Object> array() {
            var list = new ArrayList<>();
            pos++;
            skip();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skip();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        String string() {
            expect('"');
            var sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
            throw error("unterminated string");
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("unexpected character '" + s.charAt(pos) + "'");
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON: " + message + " at " + pos);
        }
    }
}
