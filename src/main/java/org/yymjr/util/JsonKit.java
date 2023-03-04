package org.yymjr.util;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JsonKit {
    private static final byte[] nc = {'u', 'l', 'l'};
    private static final byte[] tc = {'r', 'u', 'e'};
    private static final byte[] fc = {'a', 'l', 's', 'e'};
    static final byte LATIN1 = 0;
    static final byte UTF16 = 1;
    private static final Unsafe UNSAFE;
    private static long FIELD_STRING_VALUE_OFFSET;
    private static long FIELD_STRING_CODER_OFFSET;
    private static MethodHandle hasNegatives;
    private static MethodHandle getChar;

    static {
        Unsafe unsafe = null;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafe = (Unsafe) theUnsafeField.get(null);
            Field field = String.class.getDeclaredField("value");
            FIELD_STRING_VALUE_OFFSET = unsafe.objectFieldOffset(field);
            field = String.class.getDeclaredField("coder");
            FIELD_STRING_CODER_OFFSET = unsafe.objectFieldOffset(field);
            field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            long fieldImplLookUpOffset = unsafe.staticFieldOffset(field);
            MethodHandles.Lookup implLoopUp = (MethodHandles.Lookup)
                    unsafe.getObject(MethodHandles.Lookup.class, fieldImplLookUpOffset);
            Class<?> StringCodingClazz = Class.forName("java.lang.StringCoding");
            hasNegatives = implLoopUp.findStatic(StringCodingClazz, "hasNegatives",
                    MethodType.methodType(boolean.class, byte[].class, int.class, int.class));
            Class<?> StringUTF16Clazz = Class.forName("java.lang.StringUTF16");
            getChar = implLoopUp.findStatic(StringUTF16Clazz, "getChar",
                    MethodType.methodType(char.class, byte[].class, int.class));
        } catch (Throwable ignored) {
        }
        UNSAFE = unsafe;
    }

    public static Map<String, Object> toJsonObject(String src) {
        byte[] value = (byte[]) UNSAFE.getObject(src, FIELD_STRING_VALUE_OFFSET);
        byte codec = UNSAFE.getByte(src, FIELD_STRING_CODER_OFFSET);
        if (codec == UTF16) {
            byte[] utf8Val;
            int dp;
            try {
                utf8Val = new byte[(value.length >> 1) * 3];
                dp = encodeUTF8_UTF16(value, utf8Val);
            } catch (Throwable ignored) {
                utf8Val = src.getBytes(StandardCharsets.UTF_8);
                dp = utf8Val.length;
            }
            return toJsonObject(utf8Val, codec, dp);
        } else if (codec == LATIN1) {
            return toJsonObject(value, codec, value.length);
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static Map<String, Object> toJsonObject(byte[] value, byte codec, int length) {
        final List<Token> tokens = new LinkedList<>();
        for (int i = 0; i < length; i++) {
            byte code = value[i];
            if (isWhiteSpace(code)) {
                continue;
            }

            switch (code) {
                case '{' -> tokens.add(staticTokenHolder.BEGIN_OBJECT_TOKEN);
                case '}' -> tokens.add(staticTokenHolder.END_OBJECT_TOKEN);
                case '[' -> tokens.add(staticTokenHolder.BEGIN_ARRAY_TOKEN);
                case ']' -> tokens.add(staticTokenHolder.END_ARRAY_TOKEN);
                case ',' -> tokens.add(staticTokenHolder.SEP_COMMA_TOKEN);
                case ':' -> tokens.add(staticTokenHolder.SEP_COLON_TOKEN);
                case 'n' -> {
                    if (compareWith(nc, value, i)) {
                        i += nc.length;
                        tokens.add(staticTokenHolder.NULL_TOKEN);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 't' -> {
                    if (compareWith(tc, value, i)) {
                        i += tc.length;
                        tokens.add(staticTokenHolder.BOOLEAN_TRUE_TOKEN);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 'f' -> {
                    if (compareWith(fc, value, i)) {
                        i += fc.length;
                        tokens.add(staticTokenHolder.BOOLEAN_FALSE_TOKEN);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case '"' -> {
                    int offset = i + 1;
                    for (; ; ) {
                        code = value[++i];
                        if (code == '\\') {
                            code = value[++i];
                            if (code != '"' && code != '\\' && code != '/' && code != 'b' && code != 'f'
                                    && code != 'n' && code != 'r' && code != 't' && code != 'u') {
                                throw new RuntimeException("Illegal character");
                            }

                            if (code == 'u') {
                                for (int j = 0; j < 4; j++) {
                                    code = value[++i];
                                    if (!isHex(code)) {
                                        throw new RuntimeException("Illegal character");
                                    }
                                }
                            }
                        } else if (code == '"') {
                            tokens.add(new Token(TokenType.STRING, quickCreateString(value, offset, i, codec)));
                            break;
                        } else if (code == '\r' || code == '\n') {
                            throw new RuntimeException("Illegal character");
                        }
                    }
                }
                default -> {
                    if ((code >= '0' && code <= '9') || code == '-') {
                        int offset = i;
                        for (; ; ) {
                            code = value[++i];
                            if (code == ',') {
                                tokens.add(new Token(TokenType.NUMBER, quickCreateString(value, offset, i, codec)));
                                tokens.add(staticTokenHolder.SEP_COMMA_TOKEN);
                                break;
                            } else if (code == '}') {
                                tokens.add(new Token(TokenType.NUMBER, quickCreateString(value, offset, i, codec)));
                                tokens.add(staticTokenHolder.END_OBJECT_TOKEN);
                                break;
                            } else if (code == ']') {
                                tokens.add(new Token(TokenType.NUMBER, quickCreateString(value, offset, i, codec)));
                                tokens.add(staticTokenHolder.END_ARRAY_TOKEN);
                                break;
                            }
                        }
                    } else {
                        throw new RuntimeException("Illegal character");
                    }
                }
            }
        }
        tokens.add(new Token(TokenType.END_DOCUMENT, ""));
        Iterator<Token> tokenIterator = tokens.iterator();
        Token token = tokenIterator.next();
        if (token.tokenType() == TokenType.BEGIN_OBJECT) {
            return parseJsonObject(tokenIterator);
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static String quickCreateString(byte[] value, int from, int to, byte coder) {
        try {
            if (coder == LATIN1) {
                byte[] copy = Arrays.copyOfRange(value, from, to);
                String dst = (String) UNSAFE.allocateInstance(String.class);
                UNSAFE.putObject(dst, FIELD_STRING_VALUE_OFFSET, copy);
                UNSAFE.putByte(dst, FIELD_STRING_CODER_OFFSET, LATIN1);
                return dst;
            } else if (coder == UTF16) {
                if (!(boolean) hasNegatives.invoke(value, from, to - from)) {
                    byte[] copy = Arrays.copyOfRange(value, from, to);
                    String dst = (String) UNSAFE.allocateInstance(String.class);
                    UNSAFE.putObject(dst, FIELD_STRING_VALUE_OFFSET, copy);
                    UNSAFE.putByte(dst, FIELD_STRING_CODER_OFFSET, LATIN1);
                    return dst;
                }
                return new String(value, from, to - from, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return new String(value, from, to - from);
    }

    /**
     * The <p>previousToken</p> must be {@link staticTokenHolder#BEGIN_OBJECT_TOKEN }
     * when parse JsonObject.
     *
     * @param tokenIterator iterator of tokens
     * @return jsonObject
     */
    private static Map<String, Object> parseJsonObject(Iterator<Token> tokenIterator) {
        final LinkedHashMap<String, Object> jsonObject = new LinkedHashMap<>();
        int expectToken = TokenType.STRING.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
        String key = null;
        Token previousToken = staticTokenHolder.BEGIN_OBJECT_TOKEN;
        while (tokenIterator.hasNext()) {
            Token token = tokenIterator.next();
            TokenType tokenType = token.tokenType();
            String tokenValue = token.value();
            switch (tokenType) {
                case BEGIN_OBJECT -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, parseJsonObject(tokenIterator));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case END_OBJECT, END_DOCUMENT -> {
                    checkExpectToken(tokenType, expectToken);
                    return jsonObject;
                }
                case BEGIN_ARRAY -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, parseJsonArray(tokenIterator));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case NULL -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, null);
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case NUMBER -> {
                    checkExpectToken(tokenType, expectToken);
                    if (tokenValue.contains(".") || tokenValue.contains("e") || tokenValue.contains("E")) {
                        jsonObject.put(key, Double.parseDouble(tokenValue));
                    } else {
                        long num = Long.parseLong(tokenValue);
                        if (num > Integer.MAX_VALUE || num < Integer.MIN_VALUE) {
                            jsonObject.put(key, num);
                        } else {
                            jsonObject.put(key, (int) num);
                        }
                    }
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case BOOLEAN -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, "true".equals(token.value()) ? Boolean.TRUE : Boolean.FALSE);
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case STRING -> {
                    checkExpectToken(tokenType, expectToken);
                    if (previousToken.tokenType() == TokenType.SEP_COLON) {
                        jsonObject.put(key, token.value());
                        expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                    } else {
                        key = token.value();
                        expectToken = TokenType.SEP_COLON.getTokenCode();
                    }
                }
                case SEP_COLON -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode()
                            | TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode()
                            | TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
                }
                case SEP_COMMA -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.STRING.getTokenCode();
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
            previousToken = token;
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static List<Object> parseJsonArray(Iterator<Token> tokenIterator) {
        final List<Object> jsonArray = new LinkedList<>();
        int expectToken = TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode()
                | TokenType.END_ARRAY.getTokenCode() | TokenType.END_OBJECT.getTokenCode()
                | TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode()
                | TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode();
        while (tokenIterator.hasNext()) {
            Token token = tokenIterator.next();
            TokenType tokenType = token.tokenType();
            String tokenValue = token.value();
            switch (tokenType) {
                case BEGIN_OBJECT -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(parseJsonObject(tokenIterator));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case BEGIN_ARRAY -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(parseJsonArray(tokenIterator));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case END_ARRAY, END_DOCUMENT -> {
                    checkExpectToken(tokenType, expectToken);
                    return jsonArray;
                }
                case NULL -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(null);
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case NUMBER -> {
                    checkExpectToken(tokenType, expectToken);
                    if (tokenValue.contains(".") || tokenValue.contains("e") || tokenValue.contains("E")) {
                        jsonArray.add(Double.valueOf(tokenValue));
                    } else {
                        long num = Long.parseLong(tokenValue.trim());
                        if (num > Integer.MAX_VALUE || num < Integer.MIN_VALUE) {
                            jsonArray.add(num);
                        } else {
                            jsonArray.add((int) num);
                        }
                    }
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case BOOLEAN -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(Boolean.valueOf(tokenValue));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case STRING -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(tokenValue);
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case SEP_COLON -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode() | TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode() | TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
                }
                case SEP_COMMA -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.STRING.getTokenCode() | TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode() | TokenType.BOOLEAN.getTokenCode() | TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static boolean isWhiteSpace(byte ch) {
        return (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n');
    }

    private static boolean compareWith(byte[] dst, byte[] src, int offset) {
        if (dst.length + offset > src.length) {
            return false;
        }
        offset++;
        for (int i = 0; i < dst.length; i++) {
            if (src[offset + i] != dst[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(byte ch) {
        return ((ch >= '0' && ch <= '9') || ('a' <= ch && ch <= 'f') || ('A' <= ch && ch <= 'F'));
    }

    private static int encodeUTF8_UTF16(byte[] val, byte[] dst) throws Throwable {
        int dp = 0;
        int sp = 0;
        int sl = val.length >> 1;
        while (sp < sl) {
            // ascii fast loop;
            char c = (char) getChar.invoke(val, sp);
            if (c >= '\u0080') {
                break;
            }
            dst[dp++] = (byte) c;
            sp++;
        }
        while (sp < sl) {
            char c = (char) getChar.invoke(val, sp++);
            if (c < 0x80) {
                dst[dp++] = (byte) c;
            } else if (c < 0x800) {
                dst[dp++] = (byte) (0xc0 | (c >> 6));
                dst[dp++] = (byte) (0x80 | (c & 0x3f));
            } else if (Character.isSurrogate(c)) {
                int uc = -1;
                char c2;
                if (Character.isHighSurrogate(c) && sp < sl &&
                        Character.isLowSurrogate(c2 = (char) getChar.invoke(val, sp))) {
                    uc = Character.toCodePoint(c, c2);
                }
                if (uc < 0) {
                    dst[dp++] = '?';
                } else {
                    dst[dp++] = (byte) (0xf0 | ((uc >> 18)));
                    dst[dp++] = (byte) (0x80 | ((uc >> 12) & 0x3f));
                    dst[dp++] = (byte) (0x80 | ((uc >> 6) & 0x3f));
                    dst[dp++] = (byte) (0x80 | (uc & 0x3f));
                    sp++;  // 2 chars
                }
            } else {
                // 3 bytes, 16 bits
                dst[dp++] = (byte) (0xe0 | ((c >> 12)));
                dst[dp++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                dst[dp++] = (byte) (0x80 | (c & 0x3f));
            }
        }
        return dp;
    }

    private static void checkExpectToken(TokenType tokenType, int expectToken) {
        if ((tokenType.getTokenCode() & expectToken) == 0) {
            throw new RuntimeException("Parse error, invalid Token.");
        }
    }

    private static class staticTokenHolder {
        static final Token BEGIN_OBJECT_TOKEN = new Token(TokenType.BEGIN_OBJECT, "{");
        static final Token END_OBJECT_TOKEN = new Token(TokenType.END_OBJECT, "}");
        static final Token BEGIN_ARRAY_TOKEN = new Token(TokenType.BEGIN_ARRAY, "[");
        static final Token END_ARRAY_TOKEN = new Token(TokenType.END_ARRAY, "]");
        static final Token SEP_COMMA_TOKEN = new Token(TokenType.SEP_COMMA, ",");
        static final Token SEP_COLON_TOKEN = new Token(TokenType.SEP_COLON, ":");
        static final Token NULL_TOKEN = new Token(TokenType.NULL, "null");
        static final Token BOOLEAN_TRUE_TOKEN = new Token(TokenType.BOOLEAN, "true");
        static final Token BOOLEAN_FALSE_TOKEN = new Token(TokenType.BOOLEAN, "false");
    }

    private record Token(TokenType tokenType, String value) {
    }

    private enum TokenType {
        BEGIN_OBJECT(1), END_OBJECT(1 << 1), BEGIN_ARRAY(1 << 2), END_ARRAY(1 << 3), NULL(1 << 4), NUMBER(1 << 5), STRING(1 << 6), BOOLEAN(1 << 7), SEP_COLON(1 << 8), SEP_COMMA(1 << 9), END_DOCUMENT(1 << 10);

        private final int code;

        TokenType(int code) {
            this.code = code;
        }

        public int getTokenCode() {
            return code;
        }
    }
}
