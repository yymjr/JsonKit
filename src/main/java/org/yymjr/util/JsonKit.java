package org.yymjr.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JsonKit {
    private static final byte[] nc = {'n', 'l', 'l'};
    private static final byte[] tc = {'r', 'u', 'e'};
    private static final byte[] fc = {'a', 'l', 's', 'e'};
    static final byte LATIN1 = 0;
    static final byte UTF16 = 1;
    public static final Unsafe UNSAFE;
    private static long FIELD_STRING_VALUE_OFFSET;
    private static long FIELD_STRING_CODER_OFFSET;

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
        } catch (Throwable ignored) {
        }
        UNSAFE = unsafe;
    }

    public static Map<String, Object> toJsonObject(String src) {
        byte[] value = (byte[]) UNSAFE.getObject(src, FIELD_STRING_VALUE_OFFSET);
        byte codec = UNSAFE.getByte(src, FIELD_STRING_CODER_OFFSET);
        final List<Token> tokens = new ArrayList<>(1 << 6);
        /*LATIN1编码无需处理,UTF16转换成UTF8*/
        if (codec == UTF16) {
            value = src.getBytes(StandardCharsets.UTF_8);
        }
        for (int i = 0, length = value.length; i < length; i++) {
            byte code = value[i];
            if (isWhiteSpace(code)) {
                continue;
            }

            switch (code) {
                case '{' -> tokens.add(new Token(TokenType.BEGIN_OBJECT, "{"));
                case '}' -> tokens.add(new Token(TokenType.END_OBJECT, "}"));
                case '[' -> tokens.add(new Token(TokenType.BEGIN_ARRAY, "["));
                case ']' -> tokens.add(new Token(TokenType.END_ARRAY, "]"));
                case ',' -> tokens.add(new Token(TokenType.SEP_COMMA, ","));
                case ':' -> tokens.add(new Token(TokenType.SEP_COLON, ":"));
                case 'n' -> {
                    if (compareWith(nc, value, i)) {
                        i += nc.length;
                        tokens.add(new Token(TokenType.NULL, "null"));
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 't' -> {
                    if (compareWith(tc, value, i)) {
                        i += tc.length;
                        tokens.add(new Token(TokenType.BOOLEAN, "true"));
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 'f' -> {
                    if (compareWith(fc, value, i)) {
                        i += fc.length;
                        tokens.add(new Token(TokenType.BOOLEAN, "false"));
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
                                tokens.add(new Token(TokenType.SEP_COMMA, ","));
                                break;
                            } else if (code == '}') {
                                tokens.add(new Token(TokenType.NUMBER, quickCreateString(value, offset, i, codec)));
                                tokens.add(new Token(TokenType.END_OBJECT, "}"));
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
            return parseJsonObject(tokenIterator, tokens);
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static String quickCreateString(byte[] value, int from, int to, byte coder) {
        try {
            if (coder == LATIN1) {
                String dst = (String) UNSAFE.allocateInstance(String.class);
                UNSAFE.putObject(dst, FIELD_STRING_VALUE_OFFSET, Arrays.copyOfRange(value, from, to));
                UNSAFE.putByte(dst, FIELD_STRING_CODER_OFFSET, coder);
                return dst;
            } else if (coder == UTF16) {
                return new String(value, from, to - from, StandardCharsets.UTF_8);
            }
        } catch (InstantiationException ignored) {
        }
        return new String(value, from, to - from);
    }

    private static Map<String, Object> parseJsonObject(Iterator<Token> tokenIterator, List<Token> tokens) {
        final Map<String, Object> jsonObject = new HashMap<>();
        int expectToken = TokenType.STRING.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
        String key = null;
        while (tokenIterator.hasNext()) {
            Token token = tokenIterator.next();
            TokenType tokenType = token.tokenType();
            String tokenValue = token.value();
            switch (tokenType) {
                case BEGIN_OBJECT -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, parseJsonObject(tokenIterator, tokens));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case END_OBJECT, END_DOCUMENT -> {
                    checkExpectToken(tokenType, expectToken);
                    return jsonObject;
                }
                case BEGIN_ARRAY -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, parseJsonArray(tokenIterator, tokens));
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
                        jsonObject.put(key, Double.valueOf(tokenValue));
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
                    jsonObject.put(key, Boolean.valueOf(token.value()));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_OBJECT.getTokenCode();
                }
                case STRING -> {
                    checkExpectToken(tokenType, expectToken);
                    int index = tokens.indexOf(token);
                    if (index < 1) {
                        throw new RuntimeException("Parse error, invalid Token.");
                    }
                    Token previousToken = tokens.get(index - 1);
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
                    expectToken = TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode() | TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode() | TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
                }
                case SEP_COMMA -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.STRING.getTokenCode();
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static List<Object> parseJsonArray(Iterator<Token> tokenIterator, List<Token> tokens) {
        final List<Object> jsonArray = new ArrayList<>();
        int expectToken = TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.END_ARRAY.getTokenCode() | TokenType.END_OBJECT.getTokenCode() | TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode() | TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode();
        while (tokenIterator.hasNext()) {
            Token token = tokenIterator.next();
            TokenType tokenType = token.tokenType();
            String tokenValue = token.value();
            switch (tokenType) {
                case BEGIN_OBJECT -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(parseJsonObject(tokenIterator, tokens));
                    expectToken = TokenType.SEP_COMMA.getTokenCode() | TokenType.END_ARRAY.getTokenCode();
                }
                case BEGIN_ARRAY -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(parseJsonArray(tokenIterator, tokens));
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
                        long num = Long.parseLong(tokenValue);
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

    private static void checkExpectToken(TokenType tokenType, int expectToken) {
        if ((tokenType.getTokenCode() & expectToken) == 0) {
            throw new RuntimeException("Parse error, invalid Token.");
        }
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
