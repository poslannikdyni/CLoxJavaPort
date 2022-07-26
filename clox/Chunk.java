package clox;

import clox.utility.IDGenerator;

import java.util.ArrayList;
import java.util.List;

import static clox.Memory.*;
import static clox.vm.pop;
import static clox.vm.push;
import static clox.Value.*;

public class Chunk {
    public enum OpCode {
        OP_CONSTANT,
        OP_NIL,
        OP_TRUE,
        OP_FALSE,
        OP_POP,
        OP_GET_LOCAL ,
        OP_SET_LOCAL ,
        OP_GET_UPVALUE,
        OP_SET_UPVALUE,
        OP_GET_GLOBAL,
        OP_DEFINE_GLOBAL,
        OP_SET_GLOBAL,
        OP_GET_PROPERTY,
        OP_SET_PROPERTY,
        OP_GET_SUPER,
        OP_EQUAL,
        OP_GREATER,
        OP_LESS,
        OP_ADD,
        OP_SUBTRACT,
        OP_MULTIPLY,
        OP_DIVIDE,
        OP_NOT,
        OP_NEGATE,
        OP_PRINT,
        OP_JUMP,
        OP_JUMP_IF_FALSE,
        OP_LOOP,
        OP_CALL,
        OP_INVOKE,
        OP_SUPER_INVOKE,
        OP_CLOSURE,
        OP_CLOSE_UPVALUE,
        OP_RETURN,
        OP_CLASS,
        OP_INHERIT,
        OP_METHOD;

        public final int opcode;
        OpCode() {
            this.opcode = IDGenerator.getNextOpCode();
        }
    }

    public int count;
    public int capacity;
    List<Integer> code;
    List<Integer> lines;
    public ValueArray constants;

    public static void initChunk(Chunk chunk) {
        chunk.count = 0;
        chunk.capacity = 0;
        chunk.code = new ArrayList<>();
        chunk.lines = new ArrayList<>();
        chunk.constants = new ValueArray();
        initValueArray(chunk.constants);
    }

    public static void freeChunk(Chunk chunk) {
        FREE_ARRAY(chunk.code, chunk.capacity);
        FREE_ARRAY(chunk.lines, chunk.capacity);
        freeValueArray(chunk.constants);
        initChunk(chunk);
    }

    public static void writeChunk(Chunk chunk, Integer byte_, int line) {
        if (chunk.capacity < chunk.count + 1) {
            int oldCapacity = chunk.capacity;
            chunk.capacity = GROW_CAPACITY(oldCapacity);
            chunk.code = GROW_ARRAY(chunk.code, oldCapacity, chunk.capacity);
            chunk.lines = GROW_ARRAY(chunk.lines, oldCapacity, chunk.capacity);
        }

        chunk.code.add(chunk.count, byte_);
        chunk.lines.add(chunk.count, line);
        chunk.count++;
    }

    public static int addConstant(Chunk chunk, Value value) {
        push(value);
        writeValueArray(chunk.constants, value);
        pop();
        return chunk.constants.count - 1;
    }

    //======================================Advanced functions==========================================================
    public int getCount() {
        return count;
    }

}
