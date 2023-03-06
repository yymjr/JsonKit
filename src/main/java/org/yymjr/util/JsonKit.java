package org.yymjr.util;

import okio.BufferedSource;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JsonKit {
    private static final byte[] nc = {'u', 'l', 'l'};
    private static final byte[] tc = {'r', 'u', 'e'};
    private static final byte[] fc = {'a', 'l', 's', 'e'};
    static final byte LATIN1 = 0;
    static final byte UTF16 = 1;
    private static final VarHandle valueVarHandle;
    private static final VarHandle coderVarHandle;
    private static final MethodHandle newStringNoRepl1;
    private static final MethodHandle isASCII;
    private static final MethodHandle getChar;

    static {
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafeField.get(null);

            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            long fieldImplLookUpOffset = unsafe.staticFieldOffset(field);
            MethodHandles.Lookup implLoopUp = (MethodHandles.Lookup) unsafe.getObject(MethodHandles.Lookup.class, fieldImplLookUpOffset);
            newStringNoRepl1 = implLoopUp.findStatic(String.class, "newStringNoRepl1", MethodType.methodType(String.class, byte[].class, Charset.class));
            valueVarHandle = implLoopUp.findVarHandle(String.class, "value", byte[].class);
            coderVarHandle = implLoopUp.findVarHandle(String.class, "coder", byte.class);
            isASCII = implLoopUp.findStatic(String.class, "isASCII", MethodType.methodType(boolean.class, byte[].class));
            Class<?> StringUTF16Clazz = Class.forName("java.lang.StringUTF16");
            getChar = implLoopUp.findStatic(StringUTF16Clazz, "getChar", MethodType.methodType(char.class, byte[].class, int.class));
        } catch (Throwable ignored) {
            throw new RuntimeException();
        }
    }

    public static Map<String, Object> toJsonObject(BufferedSource source) {
        try {
            byte[] value = source.readByteArray();
            return toJsonObject(value, value.length);
        } catch (Throwable ignored) {
            try {
                return toJsonObject(source.readUtf8());
            } catch (IOException e) {
                throw new IllegalArgumentException();
            }
        }
    }

    public static Map<String, Object> toJsonObject(String src) {
        byte[] value = (byte[]) valueVarHandle.get(src);
        byte coder = (byte) coderVarHandle.get(src);
        if (coder == UTF16) {
            byte[] utf8Val;
            int dp;
            try {
                utf8Val = new byte[(value.length >> 1) * 3];
                dp = encodeUTF8_UTF16(value, utf8Val);
            } catch (Throwable ignored) {
                utf8Val = src.getBytes(StandardCharsets.UTF_8);
                dp = utf8Val.length;
            }
            return toJsonObject(utf8Val, dp);
        } else if (coder == LATIN1) {
            return toJsonObject(value, value.length);
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static Map<String, Object> toJsonObject(byte[] value, int length) {
        final IntBuffer tokens = new IntBuffer();
        for (int i = 0; i < length; i++) {
            byte code = value[i];
            if (code == ' ' || code == '\t' || code == '\r' || code == '\n') {
                continue;
            }
            switch (code) {
                case '{' -> tokens.add(TokenType.BEGIN_OBJECT);
                case '}' -> tokens.add(TokenType.END_OBJECT);
                case '[' -> tokens.add(TokenType.BEGIN_ARRAY);
                case ']' -> tokens.add(TokenType.END_ARRAY);
                case ',' -> tokens.add(TokenType.SEP_COMMA);
                case ':' -> tokens.add(TokenType.SEP_COLON);
                case 'n' -> {
                    if (compareWith(nc, value, i)) {
                        i += nc.length;
                        tokens.add(TokenType.NULL);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 't' -> {
                    if (compareWith(tc, value, i)) {
                        i += tc.length;
                        tokens.add(TokenType.BOOLEAN);
                        tokens.add(1);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 'f' -> {
                    if (compareWith(fc, value, i)) {
                        i += fc.length;
                        tokens.add(TokenType.BOOLEAN);
                        tokens.add(0);
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
                            if (code != '"' && code != '\\' && code != '/' && code != 'b' && code != 'f' && code != 'n' && code != 'r' && code != 't' && code != 'u') {
                                throw new RuntimeException("Illegal character");
                            }

                            if (code == 'u') {
                                for (int j = 0; j < 4; j++) {
                                    code = value[++i];
                                    if (!((code >= '0' && code <= '9') || ('a' <= code && code <= 'f') || ('A' <= code && code <= 'F'))) {
                                        throw new RuntimeException("Illegal character");
                                    }
                                }
                            }
                        } else if (code == '"') {
                            tokens.add(TokenType.STRING);
                            tokens.add(offset);
                            tokens.add(i);
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
                                tokens.add(TokenType.NUMBER);
                                tokens.add(offset);
                                tokens.add(i);
                                tokens.add(TokenType.SEP_COMMA);
                                break;
                            } else if (code == '}') {
                                tokens.add(TokenType.NUMBER);
                                tokens.add(offset);
                                tokens.add(i);
                                tokens.add(TokenType.END_OBJECT);
                                break;
                            } else if (code == ']') {
                                tokens.add(TokenType.NUMBER);
                                tokens.add(offset);
                                tokens.add(i);
                                tokens.add(TokenType.END_ARRAY);
                                break;
                            }
                        }
                    } else {
                        throw new RuntimeException("Illegal character");
                    }
                }
            }
        }
        tokens.add(TokenType.END_DOCUMENT);
        tokens.flip();
        int token = tokens.next();
        if (token == TokenType.BEGIN_OBJECT) {
            return parseJsonObject(value, tokens);
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static String quickCreateString(byte[] value, int from, int to) {
        try {
            byte[] copy = Arrays.copyOfRange(value, from, to);
            Charset charset = (boolean) isASCII.invoke(copy) ?
                    StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
            return (String) newStringNoRepl1.invoke(copy, charset);
        } catch (Throwable ignored) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * The <p>previousToken</p> must be {@link TokenType#BEGIN_OBJECT }
     * when parse JsonObject.
     *
     * @param tokens iterator of tokens
     * @return jsonObject
     */
    private static Map<String, Object> parseJsonObject(byte[] val, IntBuffer tokens) {
        final Map<String, Object> jsonObject = new HashMap<>();
        int expectToken = TokenType.STRING | TokenType.END_OBJECT;
        String key = null;
        int previousToken = TokenType.BEGIN_OBJECT;
        while (tokens.hasNext()) {
            int tokenType = tokens.next();
            switch (tokenType) {
                case TokenType.BEGIN_OBJECT -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, parseJsonObject(val, tokens));
                    expectToken = TokenType.SEP_COMMA | TokenType.END_OBJECT;
                }
                case TokenType.END_OBJECT, TokenType.END_DOCUMENT -> {
                    checkExpectToken(tokenType, expectToken);
                    return jsonObject;
                }
                case TokenType.BEGIN_ARRAY -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, parseJsonArray(val, tokens));
                    expectToken = TokenType.SEP_COMMA | TokenType.END_OBJECT;
                }
                case TokenType.NULL -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, null);
                    expectToken = TokenType.SEP_COMMA | TokenType.END_OBJECT;
                }
                case TokenType.NUMBER -> {
                    checkExpectToken(tokenType, expectToken);
                    String tokenValue = quickCreateString(val, tokens.next(), tokens.next());
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
                    expectToken = TokenType.SEP_COMMA | TokenType.END_OBJECT;
                }
                case TokenType.BOOLEAN -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonObject.put(key, tokens.next() == 1 ? Boolean.TRUE : Boolean.FALSE);
                    expectToken = TokenType.SEP_COMMA | TokenType.END_OBJECT;
                }
                case TokenType.STRING -> {
                    checkExpectToken(tokenType, expectToken);
                    int from = tokens.next();
                    int to = tokens.next();
                    String tokenValue = quickCreateString(val, from, to);
                    if (previousToken == TokenType.SEP_COLON) {
                        jsonObject.put(key, tokenValue);
                        expectToken = TokenType.SEP_COMMA | TokenType.END_OBJECT;
                    } else {
                        key = tokenValue;
                        expectToken = TokenType.SEP_COLON;
                    }
                }
                case TokenType.SEP_COLON -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.NULL | TokenType.NUMBER | TokenType.BOOLEAN | TokenType.STRING | TokenType.BEGIN_OBJECT | TokenType.BEGIN_ARRAY;
                }
                case TokenType.SEP_COMMA -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.STRING;
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
            previousToken = tokenType;
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static List<Object> parseJsonArray(byte[] val, IntBuffer tokens) {
        final List<Object> jsonArray = new ArrayList<>();
        int expectToken = TokenType.BEGIN_OBJECT | TokenType.BEGIN_ARRAY | TokenType.END_ARRAY | TokenType.END_OBJECT | TokenType.NULL | TokenType.NUMBER | TokenType.BOOLEAN | TokenType.STRING;
        while (tokens.hasNext()) {
            int tokenType = tokens.next();
            switch (tokenType) {
                case TokenType.BEGIN_OBJECT -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(parseJsonObject(val, tokens));
                    expectToken = TokenType.SEP_COMMA | TokenType.END_ARRAY;
                }
                case TokenType.BEGIN_ARRAY -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(parseJsonArray(val, tokens));
                    expectToken = TokenType.SEP_COMMA | TokenType.END_ARRAY;
                }
                case TokenType.END_ARRAY, TokenType.END_DOCUMENT -> {
                    checkExpectToken(tokenType, expectToken);
                    return jsonArray;
                }
                case TokenType.NULL -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(null);
                    expectToken = TokenType.SEP_COMMA | TokenType.END_ARRAY;
                }
                case TokenType.NUMBER -> {
                    checkExpectToken(tokenType, expectToken);
                    String tokenValue = quickCreateString(val, tokens.next(), tokens.next());
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
                    expectToken = TokenType.SEP_COMMA | TokenType.END_ARRAY;
                }
                case TokenType.BOOLEAN -> {
                    checkExpectToken(tokenType, expectToken);
                    jsonArray.add(tokens.next() == 1 ? Boolean.TRUE : Boolean.FALSE);
                    expectToken = TokenType.SEP_COMMA | TokenType.END_ARRAY;
                }
                case TokenType.STRING -> {
                    checkExpectToken(tokenType, expectToken);
                    String tokenValue = quickCreateString(val, tokens.next(), tokens.next());
                    jsonArray.add(tokenValue);
                    expectToken = TokenType.SEP_COMMA | TokenType.END_ARRAY;
                }
                case TokenType.SEP_COLON -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.NULL | TokenType.NUMBER | TokenType.BOOLEAN | TokenType.STRING | TokenType.BEGIN_OBJECT | TokenType.BEGIN_ARRAY;
                }
                case TokenType.SEP_COMMA -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.STRING | TokenType.NULL | TokenType.NUMBER | TokenType.BOOLEAN | TokenType.BEGIN_OBJECT | TokenType.BEGIN_ARRAY;
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
        }
        throw new RuntimeException("Parse error, invalid Token.");
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
                if (Character.isHighSurrogate(c) && sp < sl && Character.isLowSurrogate(c2 = (char) getChar.invoke(val, sp))) {
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

    private static void checkExpectToken(int tokenType, int expectToken) {
        if ((tokenType & expectToken) == 0) {
            throw new RuntimeException("Parse error, invalid Token.");
        }
    }

    private static final class TokenType {
        private static final int BEGIN_OBJECT = 1;
        private static final int END_OBJECT = 1 << 1;
        private static final int BEGIN_ARRAY = 1 << 2;
        private static final int END_ARRAY = 1 << 3;
        private static final int NULL = 1 << 4;
        private static final int NUMBER = 1 << 5;
        private static final int STRING = 1 << 6;
        private static final int BOOLEAN = 1 << 7;
        private static final int SEP_COLON = 1 << 8;
        private static final int SEP_COMMA = 1 << 9;
        private static final int END_DOCUMENT = 1 << 10;
    }

    private final static class IntBuffer {
        private final Segment head;
        private Segment current;

        public IntBuffer() {
            head = current = new Segment();
        }

        public void add(int val) {
            if (current.canWrite()) {
                current.add(val);
            } else {
                current.next = new Segment();
                current = current.next;
                current.add(val);
            }
        }

        public void flip() {
            current = head;
        }

        public boolean hasNext() {
            return current.hasNext() || current.next != null;
        }

        public int next() {
            if (!current.hasNext()) {
                current = current.next;
                if (current == null) {
                    throw new IndexOutOfBoundsException();
                }
            }
            return current.next();
        }

        private static final class Segment {
            private static final int SIZE = 1024 * 64;
            private final int[] buffer;
            private static final VarHandle AA = MethodHandles.arrayElementVarHandle(int[].class);
            private int writeIndex = 0;
            private int readIndex = 0;
            private Segment next = null;

            public Segment() {
                this.buffer = new int[SIZE];
            }

            public boolean hasNext() {
                return readIndex < writeIndex;
            }

            public int next() {
                return (int) AA.get(buffer, readIndex++);
            }

            public boolean canWrite() {
                return writeIndex < SIZE;
            }

            public void add(int val) {
                AA.setRelease(buffer, writeIndex++, val);
            }
        }
    }
}
