package com.sqxdl.executor.storage;

/**
 * 极简 JSON 工具：仅覆盖与存储核心（storage_core.exe）通信所需的子集。
 * 解析：JSON 文本 -> Java 对象（Map/List/String/Long/Double/Boolean/null）；
 * 序列化：只提供字符串加引号转义（{@link #quote}），复杂结构由调用方拼装。
 * 手写实现是为了避免引入第三方依赖，保证四人协作时离线可构建。
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** 解析 JSON 文本，返回对应 Java 对象；格式非法时抛出 IllegalArgumentException */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("JSON 文本为空");
        }
        Json parser = new Json(text);
        parser.skipWs();
        Object value = parser.parseValue();
        parser.skipWs();
        if (parser.pos < parser.text.length()) {
            throw parser.error("JSON 末尾有多余内容");
        }
        return value;
    }

    /** 把字符串序列化为带双引号的 JSON 字符串（含转义） */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // 控制字符统一转为 unicode 转义序列，其余原样输出（中文保持原样，存储核心按 UTF-8 处理）
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private Object parseValue() {
        return switch (peek()) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> { expect("true"); yield Boolean.TRUE; }
            case 'f' -> { expect("false"); yield Boolean.FALSE; }
            case 'n' -> { expect("null"); yield null; }
            default -> parseNumber();
        };
    }

    private java.util.Map<String, Object> parseObject() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        pos++; // 跳过 '{'
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("对象键必须是字符串");
            }
            String key = parseString();
            skipWs();
            if (peek() != ':') {
                throw error("缺少冒号");
            }
            pos++;
            skipWs();
            map.put(key, parseValue());
            skipWs();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("对象中缺少 , 或 }");
            }
        }
    }

    private java.util.List<Object> parseArray() {
        java.util.List<Object> list = new java.util.ArrayList<>();
        pos++; // 跳过 '['
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(parseValue());
            skipWs();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("数组中缺少 , 或 ]");
            }
        }
    }

    private String parseString() {
        pos++; // 跳过开引号
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            // 转义序列
            char esc = next();
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> sb.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                default -> throw error("非法转义字符 \\" + esc);
            }
            if (esc == 'u') {
                pos += 4;
            }
        }
    }

    private Number parseNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String number = text.substring(start, pos);
        if (number.isEmpty()) {
            throw error("意外的字符 '" + peek() + "'");
        }
        try {
            // 含小数点或指数时用 Double，否则用 Long（行数等整数值保持整数）
            return number.contains(".") || number.contains("e") || number.contains("E")
                    ? (Number) Double.valueOf(number)
                    : Long.valueOf(number);
        } catch (NumberFormatException e) {
            throw error("非法数字: " + number);
        }
    }

    private void expect(String word) {
        if (!text.startsWith(word, pos)) {
            throw error("期望 " + word);
        }
        pos += word.length();
    }

    private void skipWs() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("JSON 提前结束");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " (偏移 " + pos + "): " + excerpt());
    }

    /** 截取出错位置附近的文本，便于定位 */
    private String excerpt() {
        int start = Math.max(0, pos - 20);
        return text.substring(start, Math.min(text.length(), pos + 20));
    }
}
