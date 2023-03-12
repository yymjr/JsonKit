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
    private static final char[] nc = {'u', 'l', 'l'};
    private static final char[] tc = {'r', 'u', 'e'};
    private static final char[] fc = {'a', 'l', 's', 'e'};
    public static final byte LATIN1 = 0;
    public static final byte UTF16 = 1;
    public static final byte UTF8 = 2;
    private static final VarHandle valueVarHandle;
    private static final VarHandle coderVarHandle;
    private static final MethodHandle newString;
    private static final MethodHandle newStringNoRepl1;

    static {
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafeField.get(null);

            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            long fieldImplLookUpOffset = unsafe.staticFieldOffset(field);
            MethodHandles.Lookup implLoopUp = (MethodHandles.Lookup) unsafe.getObject(MethodHandles.Lookup.class, fieldImplLookUpOffset);
            newString = implLoopUp.findConstructor(String.class, MethodType.methodType(void.class, byte[].class, byte.class));
            newStringNoRepl1 = implLoopUp.findStatic(String.class, "newStringNoRepl1", MethodType.methodType(String.class, byte[].class, Charset.class));
            valueVarHandle = implLoopUp.findVarHandle(String.class, "value", byte[].class);
            coderVarHandle = implLoopUp.findVarHandle(String.class, "coder", byte.class);
        } catch (Throwable ignored) {
            throw new RuntimeException();
        }
    }

    public static Map<String, Object> toJsonObject(BufferedSource source, byte coder) {
        try {
            byte[] value = source.readByteArray();
            ByteArrayStream stream = new ByteArrayStream(value, coder);
            return toJsonObject(stream);
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
        ByteArrayStream stream = new ByteArrayStream(value, coder);
        return toJsonObject(stream);
    }

    public static Map<String, Object> toJsonObject(ByteArrayStream stream) {
        final IntBuffer tokens = new IntBuffer();
        while (stream.hasNext()) {
            char code = stream.next();
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
                    if (compareWith(nc, stream)) {
                        tokens.add(TokenType.NULL);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 't' -> {
                    if (compareWith(tc, stream)) {
                        tokens.add(TokenType.BOOLEAN);
                        tokens.add(1);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 'f' -> {
                    if (compareWith(fc, stream)) {
                        tokens.add(TokenType.BOOLEAN);
                        tokens.add(0);
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case '"' -> {
                    int offset = stream.position();
                    for (; ; ) {
                        code = stream.next();
                        if (code == '\\') {
                            code = stream.next();
                            if (code != '"' && code != '\\' && code != '/' && code != 'b' && code != 'f' && code != 'n' && code != 'r' && code != 't' && code != 'u') {
                                throw new RuntimeException("Illegal character");
                            }

                            if (code == 'u') {
                                for (int j = 0; j < 4; j++) {
                                    code = stream.next();
                                    if (!((code >= '0' && code <= '9') || ('a' <= code && code <= 'f') || ('A' <= code && code <= 'F'))) {
                                        throw new RuntimeException("Illegal character");
                                    }
                                }
                            }
                        } else if (code == '"') {
                            tokens.add(TokenType.STRING);
                            tokens.add(offset);
                            tokens.add(stream.previous());
                            break;
                        } else if (code == '\r' || code == '\n') {
                            throw new RuntimeException("Illegal character");
                        }
                    }
                }
                default -> {
                    if ((code >= '0' && code <= '9') || code == '-') {
                        int offset = stream.previous();
                        for (; ; ) {
                            code = stream.next();
                            if (code == ',') {
                                tokens.add(TokenType.NUMBER);
                                tokens.add(offset);
                                tokens.add(stream.previous());
                                tokens.add(TokenType.SEP_COMMA);
                                break;
                            } else if (code == '}') {
                                tokens.add(TokenType.NUMBER);
                                tokens.add(offset);
                                tokens.add(stream.previous());
                                tokens.add(TokenType.END_OBJECT);
                                break;
                            } else if (code == ']') {
                                tokens.add(TokenType.NUMBER);
                                tokens.add(offset);
                                tokens.add(stream.previous());
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
            return parseJsonObject(stream, tokens);
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static String quickCreateString(ByteArrayStream stream, int from, int to) {
        try {
            byte[] copy = Arrays.copyOfRange(stream.val, from, to);
            if (stream.coder == UTF8) {
                return (String) newStringNoRepl1.invoke(copy, StandardCharsets.UTF_8);
            } else {
                return (String) newString.invoke(copy, stream.coder);
            }
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
    private static Map<String, Object> parseJsonObject(ByteArrayStream stream, IntBuffer tokens) {
        final Map<String, Object> jsonObject = new HashMap<>();
        String key = null;
        int previousToken = TokenType.BEGIN_OBJECT;
        while (tokens.hasNext()) {
            int tokenType = tokens.next();
            switch (tokenType) {
                case TokenType.BEGIN_OBJECT -> jsonObject.put(key, parseJsonObject(stream, tokens));
                case TokenType.END_OBJECT, TokenType.END_DOCUMENT -> {
                    return jsonObject;
                }
                case TokenType.BEGIN_ARRAY -> jsonObject.put(key, parseJsonArray(stream, tokens));
                case TokenType.NULL -> jsonObject.put(key, null);
                case TokenType.NUMBER -> {
                    String tokenValue = quickCreateString(stream, tokens.next(), tokens.next());
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
                }
                case TokenType.BOOLEAN -> jsonObject.put(key, tokens.next() == 1 ? Boolean.TRUE : Boolean.FALSE);
                case TokenType.STRING -> {
                    int from = tokens.next();
                    int to = tokens.next();
                    String tokenValue = quickCreateString(stream, from, to);
                    if (previousToken == TokenType.SEP_COLON) {
                        jsonObject.put(key, tokenValue);
                    } else {
                        key = tokenValue;
                    }
                }
                case TokenType.SEP_COLON, TokenType.SEP_COMMA -> {
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
            previousToken = tokenType;
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static List<Object> parseJsonArray(ByteArrayStream stream, IntBuffer tokens) {
        final List<Object> jsonArray = new ArrayList<>();
        while (tokens.hasNext()) {
            int tokenType = tokens.next();
            switch (tokenType) {
                case TokenType.BEGIN_OBJECT -> jsonArray.add(parseJsonObject(stream, tokens));
                case TokenType.BEGIN_ARRAY -> jsonArray.add(parseJsonArray(stream, tokens));
                case TokenType.END_ARRAY, TokenType.END_DOCUMENT -> {
                    return jsonArray;
                }
                case TokenType.NULL -> jsonArray.add(null);
                case TokenType.NUMBER -> {
                    String tokenValue = quickCreateString(stream, tokens.next(), tokens.next());
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
                }
                case TokenType.BOOLEAN -> jsonArray.add(tokens.next() == 1 ? Boolean.TRUE : Boolean.FALSE);
                case TokenType.STRING -> {
                    String tokenValue = quickCreateString(stream, tokens.next(), tokens.next());
                    jsonArray.add(tokenValue);
                }
                case TokenType.SEP_COLON, TokenType.SEP_COMMA -> {
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static boolean compareWith(char[] dst, ByteArrayStream stream) {
        for (char b : dst) {
            if (stream.hasNext()) {
                if (stream.next() != b) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static final class ByteArrayStream {
        private final byte[] val;
        private final byte coder;
        private final int len;
        private int readIndex;

        public ByteArrayStream(byte[] val, byte coder) {
            this.val = val;
            this.coder = coder;
            len = val.length;
            readIndex = 0;
        }

        public boolean hasNext() {
            return readIndex < len;
        }

        public char next() {
            byte b0 = val[readIndex++];
            if (coder == UTF16) {
                byte b1 = val[readIndex++];
                if (b1 != 0) {
                    b0 = (byte) (b0 | 0x80);
                }
            } else if (coder == UTF8) {
                if (b0 < 0) {
                    if ((b0 >> 5) == -2 && (b0 & 0x1e) != 0) {
                        readIndex++;
                    } else if ((b0 >> 4) == -2) {
                        readIndex += 2;
                    }
                }
            }
            return (char) (b0 & 0xff);
        }

        /**
         * obtain previous char index,
         * but utf8 encoding is not
         * necessarily correct.
         */
        public int previous() {
            return coder == UTF16 ? readIndex - 2 : readIndex - 1;
        }

        public int position() {
            return readIndex;
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
