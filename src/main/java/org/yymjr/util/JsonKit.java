package org.yymjr.util;

import java.util.*;

public final class JsonKit {
    private static final int[] nc = {'n', 'l', 'l'};
    private static final int[] tc = {'r', 'u', 'e'};
    private static final int[] fc = {'a', 'l', 's', 'e'};

    public static Map<String, Object> toJsonObject(String src) {
        PrimitiveIterator.OfInt iterator = src.chars().iterator();
        final List<Token> tokens = new ArrayList<>(1 << 6);
        codeLoop:
        for (; ; ) {
            int code;
            do {
                if (!iterator.hasNext()) {
                    tokens.add(new Token(TokenType.END_DOCUMENT, ""));
                    break codeLoop;
                }
                code = iterator.next();
            } while (isWhiteSpace(code));

            switch (code) {
                case '{' -> tokens.add(new Token(TokenType.BEGIN_OBJECT, "{"));
                case '}' -> tokens.add(new Token(TokenType.END_OBJECT, "}"));
                case '[' -> tokens.add(new Token(TokenType.BEGIN_ARRAY, "["));
                case ']' -> tokens.add(new Token(TokenType.END_ARRAY, "]"));
                case ',' -> tokens.add(new Token(TokenType.SEP_COMMA, ","));
                case ':' -> tokens.add(new Token(TokenType.SEP_COLON, ":"));
                case 'n' -> {
                    if (compareWith(nc, iterator)) {
                        tokens.add(new Token(TokenType.NULL, "null"));
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 't' -> {
                    if (compareWith(tc, iterator)) {
                        tokens.add(new Token(TokenType.BOOLEAN, "true"));
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case 'f' -> {
                    if (compareWith(fc, iterator)) {
                        tokens.add(new Token(TokenType.BOOLEAN, "false"));
                        break;
                    }
                    throw new RuntimeException("Invalid json string");
                }
                case '"' -> {
                    final StringBuilder builder = new StringBuilder();
                    for (; ; ) {
                        code = iterator.next();
                        if (code == '\\') {
                            code = iterator.next();
                            if (code != '"' && code != '\\' && code != '/'
                                    && code != 'b' && code != 'f' && code != 'n'
                                    && code != 'r' && code != 't' && code != 'u') {
                                throw new RuntimeException("Illegal character");
                            }
                            builder.append('\\').append((char) code);
                            if (code == 'u') {
                                for (int i = 0; i < 4; i++) {
                                    code = iterator.next();
                                    if (isHex(code)) {
                                        builder.append((char) code);
                                    } else {
                                        throw new RuntimeException("Illegal character");
                                    }
                                }
                            }
                        } else if (code == '"') {
                            tokens.add(new Token(TokenType.STRING, builder.toString()));
                            break;
                        } else if (code == '\r' || code == '\n') {
                            throw new RuntimeException("Illegal character");
                        } else {
                            builder.append((char) code);
                        }
                    }
                }
                case '-' -> {
                    final StringBuilder builder = new StringBuilder();
                    builder.append('-');
                    readNumber(builder, iterator, tokens);
                }
                default -> {
                    if (code >= '0' && code <= '9') {
                        final StringBuilder builder = new StringBuilder();
                        builder.append((char) code);
                        readNumber(builder, iterator, tokens);
                    } else {
                        throw new RuntimeException("Illegal character");
                    }
                }
            }
        }
        Iterator<Token> tokenIterator = tokens.iterator();
        Token token = tokenIterator.next();
        if (token.tokenType() == TokenType.BEGIN_OBJECT) {
            return parseJsonObject(tokenIterator, tokens);
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static void readNumber(StringBuilder builder, PrimitiveIterator.OfInt iterator, List<Token> tokens) {
        for (; ; ) {
            if (iterator.hasNext()) {
                int code = iterator.next();
                if (code == ',') {
                    tokens.add(new Token(TokenType.NUMBER, builder.toString()));
                    tokens.add(new Token(TokenType.SEP_COMMA, ","));
                    break;
                } else if (code == '}') {
                    tokens.add(new Token(TokenType.NUMBER, builder.toString()));
                    tokens.add(new Token(TokenType.END_OBJECT, "}"));
                    break;
                } else {
                    builder.append((char) code);
                }
            } else {
                throw new RuntimeException("Invalid minus number");
            }
        }
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
                    expectToken = TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode() |
                            TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode() |
                            TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
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
        int expectToken = TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.END_ARRAY.getTokenCode() |
                TokenType.END_OBJECT.getTokenCode() | TokenType.NULL.getTokenCode() |
                TokenType.NUMBER.getTokenCode() | TokenType.BOOLEAN.getTokenCode() |
                TokenType.STRING.getTokenCode();
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
                    expectToken = TokenType.NULL.getTokenCode() | TokenType.NUMBER.getTokenCode() |
                            TokenType.BOOLEAN.getTokenCode() | TokenType.STRING.getTokenCode() |
                            TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
                }
                case SEP_COMMA -> {
                    checkExpectToken(tokenType, expectToken);
                    expectToken = TokenType.STRING.getTokenCode() | TokenType.NULL.getTokenCode() |
                            TokenType.NUMBER.getTokenCode() | TokenType.BOOLEAN.getTokenCode() |
                            TokenType.BEGIN_OBJECT.getTokenCode() | TokenType.BEGIN_ARRAY.getTokenCode();
                }
                default -> throw new RuntimeException("Unexpected Token.");
            }
        }
        throw new RuntimeException("Parse error, invalid Token.");
    }

    private static boolean isWhiteSpace(int ch) {
        return (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n');
    }

    private static boolean compareWith(final int[] bytes, PrimitiveIterator.OfInt iterator) {
        for (int code : bytes) {
            if (iterator.hasNext()) {
                if (iterator.next() != code) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(int ch) {
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
        BEGIN_OBJECT(1),
        END_OBJECT(1 << 1),
        BEGIN_ARRAY(1 << 2),
        END_ARRAY(1 << 3),
        NULL(1 << 4),
        NUMBER(1 << 5),
        STRING(1 << 6),
        BOOLEAN(1 << 7),
        SEP_COLON(1 << 8),
        SEP_COMMA(1 << 9),
        END_DOCUMENT(1 << 10);

        private final int code;

        TokenType(int code) {
            this.code = code;
        }

        public int getTokenCode() {
            return code;
        }
    }
}
