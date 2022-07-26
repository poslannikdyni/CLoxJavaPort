package clox;

import clox.utility.IDGenerator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static clox.Chunk.*;
import static clox.Chunk.OpCode.*;
import static clox.Common.DEBUG_PRINT_CODE;
import static clox.Compiler.FunctionType.*;
import static clox.Compiler.ParseFn.*;
import static clox.Compiler.Precedence.*;
import static clox.Debug.disassembleChunk;
import static clox.Main.*;
import static clox.Memory.markObject;
import static clox.ObjectLox.*;
import static clox.Scanner.*;
import static clox.Scanner.TokenType.*;
import static clox.Value.OBJ_VAL;
import static clox.utility.Utility.*;

public class Compiler {
    public static class Parser {
        Token current;
        Token previous;
        boolean hadError;
        boolean panicMode;
    }

    public enum Precedence {
        PREC_NONE,        // = 0;
        PREC_ASSIGNMENT,  // = 1;   =
        PREC_OR,          // = 2;   or
        PREC_AND,         // = 3;   and
        PREC_EQUALITY,    // = 4;   == !=
        PREC_COMPARISON,  // = 5;   < > <= >=
        PREC_TERM,        // = 6;   + -
        PREC_FACTOR,      // = 7;   * /
        PREC_UNARY,       // = 8;   ! -
        PREC_CALL,        // = 9;   . ()
        PREC_PRIMARY ;    // = 10;

        public int number;
        Precedence() {
            this.number = IDGenerator.getPrecedenceNumber();
        }
    }

     // This replace : typedef void (*ParseFn)();
    public enum ParseFn {
        NULL_FN,
        grouping,
        binary,
        and_,
        or_,
        unary,
        variable,
        number,
        string_compiler,
        call,
        dot,
        this_,
        super_,
        literal
    }

    public static class ParseRule {
        ParseFn prefix;
        ParseFn infix;
        Precedence precedence;

        public ParseRule(ParseFn prefix, ParseFn infix, Precedence precedence) {
            this.prefix = prefix;
            this.infix = infix;
            this.precedence = precedence;
        }
    }

    public static class Local {
        Token name;
        int depth;
        boolean isCaptured;
    }

    public static class Upvalue {
        int index;
        boolean isLocal;
    }

    public enum FunctionType {
        TYPE_FUNCTION,
        TYPE_INITIALIZER,
        TYPE_METHOD,
        TYPE_SCRIPT,
    }

    public static class CompilerCompiler {
        CompilerCompiler enclosing;
        ObjFunction function;
        FunctionType type;
        Local locals[] = new Local[UINT8_COUNT];
        int localCount;
        Upvalue[] upvalues = new Upvalue[UINT8_COUNT];
        int scopeDepth;

        public CompilerCompiler() {
            for (int i = 0; i < UINT8_COUNT; i++) {
                locals[i] = new Local();
            }
        }
    }

    public static class ClassCompiler {
        ClassCompiler enclosing;
        boolean hasSuperclass;
    }

    static Parser parser = new Parser();
    static CompilerCompiler current;
    static ClassCompiler currentClass;
    static Chunk compilingChunk = new Chunk();
    static Chunk currentChunk() {
        return current.function.chunk;
    }

    static void errorAt(Token token, String message) {
        if (parser.panicMode) return;
        parser.panicMode = true;
        fprintf(stderr, "[line %d] Error", token.line);

        if (token.type == TOKEN_EOF) {
            fprintf(stderr," at end");
        } else if (token.type == TOKEN_ERROR) {
            // Nothing.
        } else {
            fprintf(stderr," at '%s'", special(token.length, token.start));
        }

        fprintf(stderr, ": %s\n", message);
        parser.hadError = true;
    }

    static void error(String message) {
        errorAt(parser.previous, message);
    }

    static void errorAtCurrent(String message) {
        errorAt(parser.current, message);
    }

    static void advance() {
        parser.previous = parser.current;

        for (; ; ) {
            parser.current = sckanToken();
            if (parser.current.type != TOKEN_ERROR) break;

            errorAtCurrent(parser.current.start + "");
        }
    }

    static void consume(TokenType type, String message) {
        if (parser.current.type == type) {
            advance();
            return;
        }

       errorAtCurrent(message);
    }

    static boolean check(TokenType type) {
        return parser.current.type == type;
    }

    static boolean match(TokenType type) {
        if (!check(type)) return false;
        advance();
        return true;
    }

    static void emitByte(int byte_) {
        writeChunk(currentChunk(), byte_, parser.previous.line);
    }

    static void emitBytes(int byte_1, int byte_2) {
        emitByte(byte_1);
        emitByte(byte_2);
    }

    static void emitLoop(int loopStart){
        emitByte(OP_LOOP);

        int offset = currentChunk().count - loopStart + 2;
        if(offset > UINT16_MAX) error("Loop body too large.");

        emitByte((offset >> 8) & 0xff);
        emitByte(offset & 0xff);
    }

    static int emitJump(int instruction) {
        emitByte(instruction);
        emitByte(0xff);
        emitByte(0xff);
        return currentChunk().count - 2;
    }

    static void emitReturn() {
        if (current.type == TYPE_INITIALIZER) {
            emitBytes(OP_GET_LOCAL, 0);
        } else {
            emitByte(OP_NIL);
        }

        emitByte(OP_RETURN);
    }

    static int makeConstant(Value value) {
        int constant = addConstant(currentChunk(), value);
        if (constant > UINT8_MAX) {
            error("Too many constants in one chunk.");
            return 0;
        }

        return constant;
    }

    static void emitConstant(Value value) {
        emitBytes(OP_CONSTANT.opcode, makeConstant(value));
    }

    static void patchJump(int offset){
        int jump = currentChunk().count - offset - 2;

        if (jump > UINT16_MAX) {
            error("Too much code to jump over.");
        }

        currentChunk().code.set(offset, (jump >> 8) & 0xff);
        currentChunk().code.set(offset + 1, jump & 0xff);
    }

    static void initCompiler(CompilerCompiler compiler, FunctionType type) {
        compiler.enclosing = current;
        compiler.function = null;
        compiler.type = type;
        compiler.localCount = 0;
        compiler.scopeDepth = 0;
        compiler.function = newFunction();
        current = compiler;

        if(type != TYPE_SCRIPT){
            current.function.name = copyString(parser.previous.literal);
        }

        Local local = current.locals[current.localCount++];

        local.depth = 0;
        local.isCaptured = false;
        if (type != TYPE_FUNCTION) {
            local.name = new Token(TOKEN_IDENTIFIER, -1, 4, -1, "this");
        } else {
            local.name = new Token(TOKEN_IDENTIFIER, -1, 0, -1, "");
        }
    }

    static ObjFunction endCompiler() {
        emitReturn();
        ObjFunction function = current.function;
        if (DEBUG_PRINT_CODE) {
            if (!parser.hadError) {
                disassembleChunk(currentChunk(), function.name != null
                        ? function.name.chars : "<script>");
            }
        }
        current = current.enclosing;
        return function;
    }

    static void beginScope() {
        current.scopeDepth++;
    }

    static void endScope() {
        current.scopeDepth--;

        while (current.localCount > 0 && current.locals[current.localCount-1].depth > current.scopeDepth) {
            if (current.locals[current.localCount - 1].isCaptured) {
                emitByte(OP_CLOSE_UPVALUE);
            } else {
                emitByte(OP_POP);
            }
            current.localCount--;
        }
    }

    static int identifierConstant(Token name) {
        return makeConstant(OBJ_VAL(copyString(name.literal)));
    }

    static boolean identifiersEqual(Token a, Token b) {
        if (b == null) return false;
        if (a.length != b.length) return false;
        return a.literal.equals(b.literal);
    }

    static int resolveLocal(CompilerCompiler compiler, Token name){
        for (int i = compiler.localCount - 1; i >= 0; i--) {
            Local local = compiler.locals[i];
            if (identifiersEqual(name, local.name)) {
                if (local.depth == -1) {
                    error("Can't read local variable in its own initializer.");
                }
                return i;
            }
        }

        return -1;
    }

    static int addUpvalue(CompilerCompiler compiler, int index, boolean isLocal){
        int upvalueCount = compiler.function.upvalueCount;

        for(int i = 0; i < upvalueCount; i++){
            Upvalue upvalue = compiler.upvalues[i];
            if(upvalue.index == index && upvalue.isLocal == isLocal){
                return i;
            }
        }

        if (upvalueCount == UINT8_COUNT) {
            error("Too many closure variables in function.");
            return 0;
        }

        compiler.upvalues[upvalueCount] = new Upvalue();
        compiler.upvalues[upvalueCount].isLocal = isLocal;
        compiler.upvalues[upvalueCount].index = index;
        return compiler.function.upvalueCount++;
    }

    static int resolveUpvalue(CompilerCompiler compiler, Token name){
        if(compiler.enclosing == null) return -1;

        int local = resolveLocal(compiler.enclosing, name);
        if(local != -1){
            compiler.enclosing.locals[local].isCaptured = true;
            return addUpvalue(compiler, (int)local, true);
        }

        int upvalue = resolveUpvalue(compiler.enclosing, name);
        if(upvalue != -1){
            return addUpvalue(compiler, (int)upvalue, true);
        }

        return -1;
    }

    static void addLocal(Token name){
        if(current.localCount == UINT8_COUNT){
            error("Too many local variables in function.");
            return;
        }
        Local local = current.locals[current.localCount++];
        local.name = name;
        local.depth = -1; // if depth set -1 then variable not init.
        local.isCaptured = false;
    }

    static void declareVariable(){
        if (current.scopeDepth == 0) return;
        Token name = parser.previous;

        for(int i = current.localCount - 1; i >= 0; i--) {
            Local local = current.locals[i];
            if (local.depth != -1 && local.depth < current.scopeDepth) {
                break;
            }

            if (identifiersEqual(name, local.name)) {
                error("Already a variable with this name in this scope.");
            }
        }
        addLocal(name);
    }

    static int parseVariable(String errorMessage) {
        consume(TOKEN_IDENTIFIER, errorMessage);

        declareVariable();
        if (current.scopeDepth > 0) return 0;

        return identifierConstant(parser.previous);
    }

    static void markInitialized(){
        if(current.scopeDepth == 0) return;
        current.locals[current.localCount-1].depth = current.scopeDepth;
    }

    static void defineVariable(int global) {
        if (current.scopeDepth > 0) {
            markInitialized();
            return;
        }

        emitBytes(OP_DEFINE_GLOBAL, global);
    }

    static int argumentList(){
        int argCount = 0;
        if(!check(TOKEN_RIGHT_PAREN)){
            do{
                expression();
                if (argCount == 255) {
                    error("Can't have more than 255 arguments.");
                }
                argCount++;
            }while (match(TOKEN_COMMA));
        }
        consume(TOKEN_RIGHT_PAREN, "Expect ')' after arguments.");
        return argCount;
    }

    static void and_(boolean canAssign){
        int endJump = emitJump(OP_JUMP_IF_FALSE);

        emitByte(OP_POP);
        parsePrecedence(PREC_AND);

        patchJump(endJump);
    }

    static void binary(boolean canAssign) {
        TokenType operatorType = parser.previous.type;
        ParseRule rule = getRule(operatorType);
        parsePrecedence(getPrecedenceWithNumber(rule.precedence.number + 1));

        switch (operatorType) {
            case TOKEN_BANG_EQUAL:        emitBytes(OP_EQUAL, OP_NOT); break;
            case TOKEN_EQUAL_EQUAL:       emitByte(OP_EQUAL); break;
            case TOKEN_GREATER:           emitByte(OP_GREATER); break;
            case TOKEN_GREATER_EQUAL:     emitBytes(OP_LESS, OP_NOT); break;
            case TOKEN_LESS:              emitByte(OP_LESS); break;
            case TOKEN_LESS_EQUAL:        emitBytes(OP_GREATER, OP_NOT); break;
            case TOKEN_PLUS:              emitByte(OP_ADD); break;
            case TOKEN_MINUS:             emitByte(OP_SUBTRACT); break;
            case TOKEN_STAR:              emitByte(OP_MULTIPLY); break;
            case TOKEN_SLASH:             emitByte(OP_DIVIDE); break;
            default: return; // Unreachable.
        }
    }

    static void call(boolean canAssign){
        int argCount = argumentList();
        emitBytes(OP_CALL, argCount);
    }

    static void dot(boolean canAssign){
        consume(TOKEN_IDENTIFIER, "Expect property name after '.'.");
        int name = identifierConstant(parser.previous);

        if (canAssign && match(TOKEN_EQUAL)) {
            expression();
            emitBytes(OP_SET_PROPERTY, name);
        } else if(match(TOKEN_LEFT_PAREN)){
            int argCount = argumentList();
            emitBytes(OP_INVOKE, name);
            emitByte(argCount);
        } else {
            emitBytes(OP_GET_PROPERTY, name);
        }
    }

    static void literal(boolean canAssign) {
        switch (parser.previous.type) {
            case TOKEN_FALSE: emitByte(OP_FALSE); break;
            case TOKEN_NIL: emitByte(OP_NIL); break;
            case TOKEN_TRUE: emitByte(OP_TRUE); break;
            default: return; // Unreachable.
        }
    }

    static void grouping(boolean canAssign) {
        expression();
        consume(TOKEN_RIGHT_PAREN, "Expect ')' after expression.");
    }

    static void number(boolean canAssign) {
        double value = Double.parseDouble(parser.previous.literal);
        emitConstant(new Value.DoubleValue(value));
    }

    static void or_(boolean canAssign){
        int elseJump = emitJump(OP_JUMP_IF_FALSE);
        int endJump = emitJump(OP_JUMP);

        patchJump(elseJump);
        emitByte(OP_POP);

        parsePrecedence(PREC_OR);
        patchJump(endJump);
    }

    static void string_compiler(boolean canAssign){
        emitConstant(OBJ_VAL(
                copyString(parser.previous.literal.substring(1, parser.previous.length - 1))));
    }

    static void namedVariable(Token name, boolean canAssign){
        OpCode getOp, setOp;
        int arg = resolveLocal(current, name);
        if (arg != -1) {
            getOp = OP_GET_LOCAL;
            setOp = OP_SET_LOCAL;
        } else if ((arg = resolveUpvalue(current, name)) != -1){
            getOp = OP_GET_UPVALUE;
            setOp = OP_SET_UPVALUE;
        }else {
            arg = identifierConstant(name);
            getOp = OP_GET_GLOBAL;
            setOp = OP_SET_GLOBAL;
        }

        if(canAssign && match(TOKEN_EQUAL)){
            expression();
            emitBytes(setOp, arg);
        } else{
            emitBytes(getOp, arg);
        }
    }

    static void variable(boolean canAssign){
        namedVariable(parser.previous, canAssign);
    }

    static Token syntheticToken(String text){
        return new Token(TOKEN_IDENTIFIER, -1, text.length(), -1, text);
    }

    static void super_(boolean canAssign){
        if (currentClass == null) {
            error("Can't use 'super' outside of a class.");
        } else if (!currentClass.hasSuperclass) {
            error("Can't use 'super' in a class with no superclass.");
        }

        consume(TOKEN_DOT, "Expect '.' after 'super'.");
        consume(TOKEN_IDENTIFIER, "Expect superclass method name.");

        int name = identifierConstant(parser.previous);
        namedVariable(syntheticToken("this"), false);
        if (match(TOKEN_LEFT_PAREN)) {
            int argCount = argumentList();
            namedVariable(syntheticToken("super"), false);
            emitBytes(OP_SUPER_INVOKE, name);
            emitByte(argCount);
        } else {
            namedVariable(syntheticToken("super"), false);
            emitBytes(OP_GET_SUPER, name);
        }
    }

    static void this_(boolean canAssign){
        if (currentClass == null) {
            error("Can't use 'this' outside of a class.");
            return;
        }
        variable(false);
    }

    static void unary(boolean canAssign) {
        TokenType operatorType = parser.previous.type;
        parsePrecedence(PREC_UNARY);

        switch (operatorType) {
            case TOKEN_BANG: emitByte(OP_NOT); break;
            case TOKEN_MINUS: emitByte(OP_NEGATE); break;
            default: return; // Unreachable.
        }
    }

    static HashMap<TokenType, ParseRule> rules = new HashMap<>();
    static {
        rules.put(TOKEN_LEFT_PAREN,       new ParseRule(ParseFn.grouping,       call,                   PREC_CALL));
        rules.put(TOKEN_RIGHT_PAREN,      new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_LEFT_BRACE,       new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_RIGHT_BRACE,      new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_COMMA,            new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_DOT,              new ParseRule(NULL_FN, dot,                PREC_CALL));
        rules.put(TOKEN_MINUS,            new ParseRule(ParseFn.unary,          ParseFn.binary,         Precedence.PREC_TERM));
        rules.put(TOKEN_PLUS,             new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_TERM));
        rules.put(TOKEN_SEMICOLON,        new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_SLASH,            new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_FACTOR));
        rules.put(TOKEN_STAR,             new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_FACTOR));
        rules.put(TOKEN_BANG,             new ParseRule(ParseFn.unary, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_BANG_EQUAL,       new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_NONE));
        rules.put(TOKEN_EQUAL,            new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_EQUAL_EQUAL,      new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_EQUALITY));
        rules.put(TOKEN_GREATER,          new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_COMPARISON));
        rules.put(TOKEN_GREATER_EQUAL,    new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_COMPARISON));
        rules.put(TOKEN_LESS,             new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_COMPARISON));
        rules.put(TOKEN_LESS_EQUAL,       new ParseRule(NULL_FN,                ParseFn.binary,         Precedence.PREC_COMPARISON));
        rules.put(TOKEN_IDENTIFIER,       new ParseRule(variable, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_STRING,           new ParseRule(string_compiler, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_NUMBER,           new ParseRule(ParseFn.number, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_AND,              new ParseRule(NULL_FN,                and_,                   PREC_AND));
        rules.put(TOKEN_CLASS,            new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_ELSE,             new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_FALSE,            new ParseRule(ParseFn.literal, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_FOR,              new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_FUN,              new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_IF,               new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_NIL,              new ParseRule(ParseFn.literal, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_OR,               new ParseRule(NULL_FN,                or_,                    PREC_OR));
        rules.put(TOKEN_PRINT,            new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_RETURN,           new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_SUPER,            new ParseRule(super_, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_THIS,             new ParseRule(this_, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_TRUE,             new ParseRule(ParseFn.literal, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_VAR,              new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_WHILE,            new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_ERROR,            new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
        rules.put(TOKEN_EOF,              new ParseRule(NULL_FN, NULL_FN,                Precedence.PREC_NONE));
    }

    static void parsePrecedence(Precedence precedence) {
        advance();
        ParseFn prefixRule = getRule(parser.previous.type).prefix;
        if (prefixRule == NULL_FN) {
            error("Expect expression.");
            return;
        }

        boolean canAssign = precedence.number <= PREC_ASSIGNMENT.number;
        executePrecedence(prefixRule, null, canAssign);

        while (precedence.number <= getRule(parser.current.type).precedence.number) {
            advance();
            ParseFn infixRule = getRule(parser.previous.type).infix;
            executePrecedence(infixRule, null, canAssign);
        }

        if(canAssign && match(TOKEN_EQUAL)){
            error("Invalid assignment target.");
        }
    }

    static ParseRule getRule(TokenType type) {
        for (Map.Entry<TokenType, ParseRule> pair : rules.entrySet()) {
            if (type == pair.getKey())
                return pair.getValue();
        }
        throw new RuntimeException("Unreachable");
    }

    static void expression() {
        parsePrecedence(PREC_ASSIGNMENT);
    }

    static void block() {
        while (!check(TOKEN_RIGHT_BRACE) && !check(TOKEN_EOF)) {
            declaration();
        }

        consume(TOKEN_RIGHT_BRACE, "Expect '}' after block.");
    }

    static void function(FunctionType type){
        CompilerCompiler compiler = new CompilerCompiler();
        initCompiler(compiler, type);
        beginScope();

        consume(TOKEN_LEFT_PAREN, "Expect '(' after function name.");
        if(!check(TOKEN_RIGHT_PAREN)){
            do{
                current.function.arity++;
                if (current.function.arity > 255) {
                    errorAtCurrent("Can't have more than 255 parameters.");
                }
                int constant = parseVariable("Expect parameter name.");
                defineVariable(constant);
            }while (match(TOKEN_COMMA));
        }
        consume(TOKEN_RIGHT_PAREN, "Expect ')' after parameters.");
        consume(TOKEN_LEFT_BRACE, "Expect '{' before function body.");
        block();

        ObjFunction function = endCompiler();
        emitBytes(OP_CLOSURE, makeConstant(OBJ_VAL(function)));

        for(int i = 0; i < function.upvalueCount; i++){
            emitByte(compiler.upvalues[i].isLocal ? 1 : 0);
            emitByte(compiler.upvalues[i].index);
        }
    }

    static void method(){
        consume(TOKEN_IDENTIFIER, "Expect method name.");
        int constant = identifierConstant(parser.previous);

        FunctionType type = TYPE_METHOD;
        if (parser.previous.length == 4 && parser.previous.literal.equals("init")) {
            type = TYPE_INITIALIZER;
        }
        function(type);

        emitBytes(OP_METHOD, constant);
    }

    static void classDeclaration(){
        consume(TOKEN_IDENTIFIER, "Expect class name.");
        Token className = parser.previous;
        int nameConstant = identifierConstant(parser.previous);
        declareVariable();

        emitBytes(OP_CLASS, nameConstant);
        defineVariable(nameConstant);

        ClassCompiler classCompiler = new ClassCompiler();
        classCompiler.hasSuperclass = false;
        classCompiler.enclosing = currentClass;
        currentClass = classCompiler;

        if (match(TOKEN_LESS)) {
            consume(TOKEN_IDENTIFIER, "Expect superclass name.");

            variable(false);

            if (identifiersEqual(className, parser.previous)) {
                error("A class can't inherit from itself.");
            }

            beginScope();
            addLocal(syntheticToken("super")); // this и super различны. см super_()
            defineVariable(0);
            namedVariable(className, false);
            emitByte(OP_INHERIT);
            classCompiler.hasSuperclass = true;
        }

        namedVariable(className, false);

        consume(TOKEN_LEFT_BRACE, "Expect '{' before class body.");
        while (!check(TOKEN_RIGHT_BRACE) && !check(TOKEN_EOF)) {
            method();
        }
        consume(TOKEN_RIGHT_BRACE, "Expect '}' after class body.");
        emitByte(OP_POP);

        if (classCompiler.hasSuperclass) {
            endScope();
        }

        currentClass = currentClass.enclosing;
    }

    static void funDeclaration(){
        int global = parseVariable("Expect function name.");
        markInitialized();
        function(TYPE_FUNCTION);
        defineVariable(global);
    }

    static void varDeclaration() {

        int global = parseVariable("Expect variable name.");

        if (match(TOKEN_EQUAL)) {
            expression();
        } else {
            emitByte(OP_NIL);
        }
        consume(TOKEN_SEMICOLON,
                "Expect ';' after variable declaration.");

        defineVariable(global);
    }

    static void expressionStatement() {
        expression();
        consume(TOKEN_SEMICOLON, "Expect ';' after expression.");
        emitByte(OP_POP);
    }

    static void forStatement() {
        beginScope();
        consume(TOKEN_LEFT_PAREN, "Expect '(' after 'for'.");
        if(match(TOKEN_SEMICOLON)){
            // NOP.
        }else if(match(TOKEN_VAR)){
            varDeclaration();
        } else{
            expressionStatement();
        }

        int loopStart = currentChunk().count;
        int exitJump = -1;
        if(!match(TOKEN_SEMICOLON)){
            expression();
            consume(TOKEN_SEMICOLON, "Expect ';' after loop condition.");
            exitJump = emitJump(OP_JUMP_IF_FALSE);
            emitByte(OP_POP);
        }

        if(!match(TOKEN_RIGHT_PAREN)){
            int bodyJump = emitJump(OP_JUMP);
            int incrementStart = currentChunk().count;
            expression();
            emitByte(OP_POP);
            consume(TOKEN_RIGHT_PAREN, "Expect ')' after for clauses.");
            emitLoop(loopStart);
            loopStart = incrementStart;
            patchJump(bodyJump);
        }

        statement();
        emitLoop(loopStart);

        if(exitJump != -1){
            patchJump(exitJump);
            emitByte(OP_POP);
        }

        endScope();
    }

    static void ifStatement() {
        consume(TOKEN_LEFT_PAREN, "Expect '(' after 'if'.");
        expression();
        consume(TOKEN_RIGHT_PAREN, "Expect ')' after condition.");

        int thenJump = emitJump(OP_JUMP_IF_FALSE);
        emitByte(OP_POP);
        statement();

        int elseJump = emitJump(OP_JUMP);
        patchJump(thenJump);
        emitByte(OP_POP);

        if(match(TOKEN_ELSE)) statement();
        patchJump(elseJump);
    }

    static void printStatement() {
        expression();
        consume(TOKEN_SEMICOLON, "Expect ';' after value.");
        emitByte(OP_PRINT);
    }

    static void returnStatement(){
        if (current.type == TYPE_SCRIPT) {
            error("Can't return from top-level code.");
        }

        if (match(TOKEN_SEMICOLON)) {
            emitReturn();
        } else {
            if (current.type == TYPE_INITIALIZER) {
                error("Can't return a value from an initializer.");
            }

            expression();
            consume(TOKEN_SEMICOLON, "Expect ';' after return value.");
            emitByte(OP_RETURN);
        }
    }

    static void whileStatement() {
        int loopStart = currentChunk().count;

        consume(TOKEN_LEFT_PAREN, "Expect '(' after 'while'.");
        expression();
        consume(TOKEN_RIGHT_PAREN, "Expect ')' after condition.");

        int exitJump = emitJump(OP_JUMP_IF_FALSE);
        emitByte(OP_POP);
        statement();
        emitLoop(loopStart);

        patchJump(exitJump);
        emitByte(OP_POP);
    }

    static void synchronize() {
        parser.panicMode = false;

        while (parser.current.type != TOKEN_EOF) {
            if (parser.previous.type == TOKEN_SEMICOLON) return;
            switch (parser.current.type) {
                case TOKEN_CLASS:
                case TOKEN_FUN:
                case TOKEN_VAR:
                case TOKEN_FOR:
                case TOKEN_IF:
                case TOKEN_WHILE:
                case TOKEN_PRINT:
                case TOKEN_RETURN:
                    return;
                default:
                    ; // Do nothing.
            }

            advance();
        }
    }

    static void declaration() {
        if (match(TOKEN_CLASS)) {
            classDeclaration();
        } else if (match(TOKEN_FUN)) {
            funDeclaration();
        } else if (match(TOKEN_VAR)) {
            varDeclaration();
        } else {
            statement();
        }

        if(parser.panicMode) synchronize();
    }

    static void statement() {
        if (match(TOKEN_PRINT)) {
            printStatement();
        } else if (match(TOKEN_FOR)) {
            forStatement();
        } else if (match(TOKEN_IF)) {
            ifStatement();
        } else if (match(TOKEN_RETURN)) {
            returnStatement();
        } else if (match(TOKEN_WHILE)) {
            whileStatement();
        } else if (match(TOKEN_LEFT_BRACE)) {
            beginScope();
            block();
            endScope();
        } else {
            expressionStatement();
        }
    }

    static ObjFunction compile(String source) {
        initScanner(source);
        CompilerCompiler compiler = new CompilerCompiler();
        initCompiler(compiler, TYPE_SCRIPT);

        parser.hadError = false;
        parser.panicMode = false;

        advance();

        while (!match(TOKEN_EOF)){
            declaration();
        }

        ObjFunction function = endCompiler();
        return parser.hadError ? null : function;
    }

    public static void markCompilerRoots() {
        CompilerCompiler compiler = current;
        while(compiler != null){
            markObject(compiler.function);
            compiler = compiler.enclosing;
        }
    }

    //======================================Advanced functions==========================================================
    public static Precedence getPrecedenceWithNumber(int i) {
        return Arrays.asList(Precedence.values()).stream().filter(p -> p.number > i).findFirst().get();
    }

    private static void emitByte(OpCode opCode) {
        emitByte(opCode.opcode);
    }

    private static void emitBytes(OpCode op1, OpCode op2) {
        emitBytes(op1.opcode, op2.opcode);
    }

    private static void emitBytes(OpCode op1, int op2) {
        emitBytes(op1.opcode, op2);
    }

    private static int emitJump(OpCode instruction) {
        return emitJump(instruction.opcode);
    }

    private static void executePrecedence(ParseFn rule, Token name, boolean canAssign) {
        switch (rule) {
            case grouping:
                grouping(canAssign);
                break;
            case and_:
                and_(canAssign);
                break;
            case or_:
                or_(canAssign);
                break;
            case binary:
                binary(canAssign);
                break;
            case unary:
                unary(canAssign);
                break;
            case variable:
                variable(canAssign);
                break;
            case number:
                number(canAssign);
                break;
            case string_compiler:
                string_compiler(canAssign);
                break;
            case call:
                call(canAssign);
                break;
            case dot:
                dot(canAssign);
                break;
            case this_:
                this_(canAssign);
                break;
            case super_:
                super_(canAssign);
                break;
            case literal:
                literal(canAssign);
                break;
            // In this place NULL_FN - Unreachable.
            default:
                throw new RuntimeException("Add rule to switch : " + rule.name());
        }
    }
}









