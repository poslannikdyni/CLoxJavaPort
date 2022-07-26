package clox;

import static clox.Scanner.TokenType.*;

public class Scanner {
    public enum TokenType {
        TOKEN_LEFT_PAREN, TOKEN_RIGHT_PAREN,
        TOKEN_LEFT_BRACE, TOKEN_RIGHT_BRACE,
        TOKEN_COMMA, TOKEN_DOT, TOKEN_MINUS, TOKEN_PLUS,
        TOKEN_SEMICOLON, TOKEN_SLASH, TOKEN_STAR,
        TOKEN_BANG, TOKEN_BANG_EQUAL,
        TOKEN_EQUAL, TOKEN_EQUAL_EQUAL,
        TOKEN_GREATER, TOKEN_GREATER_EQUAL,
        TOKEN_LESS, TOKEN_LESS_EQUAL,
        TOKEN_IDENTIFIER, TOKEN_STRING, TOKEN_NUMBER,
        TOKEN_AND, TOKEN_CLASS, TOKEN_ELSE, TOKEN_FALSE,
        TOKEN_FOR, TOKEN_FUN, TOKEN_IF, TOKEN_NIL, TOKEN_OR,
        TOKEN_PRINT, TOKEN_RETURN, TOKEN_SUPER, TOKEN_THIS,
        TOKEN_TRUE, TOKEN_VAR, TOKEN_WHILE,
        TOKEN_ERROR, TOKEN_EOF
    }

    public static class Token {
        public TokenType type;
        public int start;
        public int length;
        public int line;
        public String literal;

        public Token(TokenType type, int start, int length, int line, String literal) {
            this.type = type;
            this.start = start;
            this.line = line;
            this.length = length;
            this.literal = literal;
        }
    }

    static int start;
    static int current;
    static int line;
    static String source;

    public static void initScanner(String source) {
        Scanner.start = 0;
        Scanner.current = 0;
        Scanner.line = 1;
        Scanner.source = source;
    }

    static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                c == '_';
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isAtEnd() {
        return getCurrent() == '\0';
    }

    static char advance() {
        Scanner.current++;
        return getCurrent(-1);
    }

    static char peek() {
        return getCurrent();
    }

    static char peekNext() {
        if (isAtEnd()) return '\0';
        return getCurrent(1);
    }

    static boolean match(char expected) {
        if (isAtEnd()) return false;
        if (getCurrent() != expected) return false;
        Scanner.current++;
        return true;
    }

    static Token makeToken(TokenType type) {
        Token token = new Token(
                type,
                Scanner.start,
                Scanner.current - Scanner.start,
                Scanner.line,
                Scanner.source.substring(Scanner.start, Scanner.current)
        );
        return token;
    }

    static Token errorToken(String message) {
        Token token = new Token(
                TOKEN_ERROR,
                0,
                message.length(),
                Scanner.line,
                message
        );
        return token;
    }

    static void skipWhitespace() {
        for (; ; ) {
            char c = peek();
            switch (c) {
                case ' ':
                case '\r':
                case '\t':
                    advance();
                    break;
                case '\n':
                    Scanner.line++;
                    advance();
                    break;
                case '/':
                    if (peekNext() == '/') {
                        while (peek() != '\n' && !isAtEnd()) advance();
                    } else {
                        return;
                    }
                    break;
                default:
                    return;
            }
        }
    }

    static TokenType checkKeyword(int start, int length,
                                  String rest, TokenType type) {
        if (Scanner.start + length + 1 > Scanner.source.length())
            return TOKEN_IDENTIFIER;

        int startPosition = Scanner.start + start;
        String str = Scanner.source.substring(startPosition, startPosition + length);
        if (Scanner.current - Scanner.start == start + length && rest.equals(str))
            return type;

        return TOKEN_IDENTIFIER;
    }

    static TokenType identifierType() {
        switch (source.charAt(start)) {
            case 'a':
                return checkKeyword(1, 2, "nd", TOKEN_AND);
            case 'c':
                return checkKeyword(1, 4, "lass", TOKEN_CLASS);
            case 'e':
                return checkKeyword(1, 3, "lse", TOKEN_ELSE);
            case 'f':
                if (Scanner.current - Scanner.start > 1) {
                    switch (getCharFromStart(1)) {
                        case 'a':
                            return checkKeyword(2, 3, "lse", TOKEN_FALSE);
                        case 'o':
                            return checkKeyword(2, 1, "r", TOKEN_FOR);
                        case 'u':
                            return checkKeyword(2, 1, "n", TOKEN_FUN);
                    }
                }
                break;
            case 'i':
                return checkKeyword(1, 1, "f", TOKEN_IF);
            case 'n':
                return checkKeyword(1, 2, "il", TOKEN_NIL);
            case 'o':
                return checkKeyword(1, 1, "r", TOKEN_OR);
            case 'p':
                return checkKeyword(1, 4, "rint", TOKEN_PRINT);
            case 'r':
                return checkKeyword(1, 5, "eturn", TOKEN_RETURN);
            case 's':
                return checkKeyword(1, 4, "uper", TOKEN_SUPER);
            case 't':
                if (Scanner.current - Scanner.start > 1) {
                    switch (getCharFromStart(1)) {
                        case 'h':
                            return checkKeyword(2, 2, "is", TOKEN_THIS);
                        case 'r':
                            return checkKeyword(2, 2, "ue", TOKEN_TRUE);
                    }
                }
                break;
            case 'v':
                return checkKeyword(1, 2, "ar", TOKEN_VAR);
            case 'w':
                return checkKeyword(1, 4, "hile", TOKEN_WHILE);
        }
        return TOKEN_IDENTIFIER;
    }

    static Token identifier() {
        while (isAlpha(peek()) || isDigit(peek())) advance();
        return makeToken(identifierType());
    }

    static Token number() {
        while (isDigit(peek())) advance();
        if (peek() == '.' && isDigit(peekNext())) {
            advance();
            while (isDigit(peek())) advance();
        }
        return makeToken(TOKEN_NUMBER);
    }

    static Token string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') Scanner.line++;
            advance();
        }

        if (isAtEnd()) return errorToken("Unterminated string.");
        advance();
        return makeToken(TOKEN_STRING);
    }

    public static Token sckanToken() {
        skipWhitespace();
        Scanner.start = Scanner.current;

        if (isAtEnd()) return makeToken(TOKEN_EOF);

        char c = advance();
        if (isAlpha(c)) return identifier();
        if (isDigit(c)) return number();

        switch (c) {
            case '(':
                return makeToken(TOKEN_LEFT_PAREN);
            case ')':
                return makeToken(TOKEN_RIGHT_PAREN);
            case '{':
                return makeToken(TOKEN_LEFT_BRACE);
            case '}':
                return makeToken(TOKEN_RIGHT_BRACE);
            case ';':
                return makeToken(TOKEN_SEMICOLON);
            case ',':
                return makeToken(TOKEN_COMMA);
            case '.':
                return makeToken(TOKEN_DOT);
            case '-':
                return makeToken(TOKEN_MINUS);
            case '+':
                return makeToken(TOKEN_PLUS);
            case '/':
                return makeToken(TOKEN_SLASH);
            case '*':
                return makeToken(TOKEN_STAR);
            case '!':
                return makeToken(
                        match('=') ? TOKEN_BANG_EQUAL : TOKEN_BANG);
            case '=':
                return makeToken(
                        match('=') ? TOKEN_EQUAL_EQUAL : TOKEN_EQUAL);
            case '<':
                return makeToken(
                        match('=') ? TOKEN_LESS_EQUAL : TOKEN_LESS);
            case '>':
                return makeToken(
                        match('=') ? TOKEN_GREATER_EQUAL : TOKEN_GREATER);
            case '"':
                return string();
        }

        return errorToken("Unexpected character.");
    }


    //======================================Advanced functions==========================================================
    private static char getCurrent() {
        if (Scanner.current == Scanner.source.length())
            return '\0';
        return Scanner.source.charAt(Scanner.current);
    }

    private static char getCurrent(int pos) {
        return Scanner.source.charAt(Scanner.current + pos);
    }

    private static int getCharFromStart(int pos) {
        return Scanner.source.charAt(Scanner.start + pos);
    }
}
