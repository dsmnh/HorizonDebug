package dev.dsmnh.horizon.debug.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写：只覆盖调试 API 需要的对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 *
 * <p>不引第三方库：1.7.10 运行环境没有 Gson 的编译期依赖，本仓库手写 YAML 解析
 * （{@code WorldNames}）已有先例，这里沿用同一风格。
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------ 解析

    /** 解析一个 JSON 值；失败抛 {@link IllegalArgumentException} */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipSpace();
        Object v = p.value();
        p.skipSpace();
        if (p.pos < p.src.length()) throw new IllegalArgumentException("尾部多余内容 @" + p.pos);
        return v;
    }

    /** 解析失败返回 {@code null}，便于调用方统一报 BAD_JSON */
    public static Object tryParse(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        final String src;
        int pos;

        Parser(String src) {
            this.src = src;
        }

        void skipSpace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        Object value() {
            skipSpace();
            if (pos >= src.length()) throw new IllegalArgumentException("意外结束");
            char c = src.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            expect('{');
            skipSpace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                map.put(key, value());
                skipSpace();
                char c = peek();
                pos++;
                if (c == ',') continue;
                if (c == '}') return map;
                throw new IllegalArgumentException("对象分隔符不合法 @" + (pos - 1));
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<Object>();
            expect('[');
            skipSpace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipSpace();
                char c = peek();
                pos++;
                if (c == ',') continue;
                if (c == ']') return list;
                throw new IllegalArgumentException("数组分隔符不合法 @" + (pos - 1));
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) throw new IllegalArgumentException("字符串未闭合");
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= src.length()) throw new IllegalArgumentException("转义未闭合");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw new IllegalArgumentException("\\u 未闭合");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("未知转义 \\" + e);
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            if (start == pos) throw new IllegalArgumentException("值不合法 @" + pos);
            String raw = src.substring(start, pos);
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                try {
                    return Long.valueOf(raw);
                } catch (NumberFormatException ignored) {
                    // 溢出时退化为浮点
                }
            }
            return Double.valueOf(raw);
        }

        char peek() {
            if (pos >= src.length()) throw new IllegalArgumentException("意外结束");
            return src.charAt(pos);
        }

        void expect(char c) {
            skipSpace();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("期望 '" + c + "' @" + pos);
            }
            pos++;
        }
    }

    // ------------------------------------------------------------ 输出

    /** 把 Map / List / String / Number / Boolean / null 序列化为单行 JSON */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append(((Number) v).longValue());
            } else {
                sb.append(v.toString());
            }
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------ 取值辅助

    /** 构建有序对象：{@code obj("k1", v1, "k2", v2)} */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /** 取字符串字段；缺省或类型不符时返回 {@code def} */
    public static String str(Map<String, Object> map, String key, String def) {
        if (map == null) return def;
        Object v = map.get(key);
        return v == null ? def : v.toString();
    }

    /** 取数值字段；缺省或无法解析时返回 {@code null} */
    public static Double num(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return null;
        try {
            return Double.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取整数字段；缺省或无法解析时返回 {@code null} */
    public static Integer integer(Map<String, Object> map, String key) {
        Double d = num(map, key);
        return d == null ? null : Integer.valueOf(d.intValue());
    }

    /** 取布尔字段；缺省时返回 {@code def} */
    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        if (map == null) return def;
        Object v = map.get(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v == null) return def;
        String s = v.toString();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s);
    }
}
