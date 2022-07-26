package clox;

import static clox.Chunk.OpCode.*;
import static clox.Value.printValue;
import static clox.utility.Utility.printf;
import static clox.ObjectLox.*;

public class Debug {
    public static void disassembleChunk(Chunk chunk, String name) {
        printf("== %s ==\n", name);

        for (int offset = 0; offset < chunk.getCount(); ) {
            offset = disassembleInstruction(chunk, offset);
        }
    }

    public static int simpleInstruction(String name, int offset) {
        printf("%s\n", name);
        return offset + 1;
    }

    static int byteInstruction(String name, Chunk chunk, int offset) {
        int slot = chunk.code.get(offset + 1);
        printf("%-16s %4d\n", name, slot);
        return offset + 2;
    }

    static int jumpInstruction(String name, int sign, Chunk chunk, int offset) {
        int jump = chunk.code.get(offset + 1) << 8;
        jump |= chunk.code.get(offset + 2);
        printf("%-16s %4d -> %d\n", name, offset, offset + 3 + sign * jump);
        return offset + 3;
    }

    public static int constantInstruction(String name, Chunk chunk, int offset) {
        Integer constant = chunk.code.get(offset + 1);
        printf("%-16s %4d '", name, constant);
        printValue(chunk.constants.get(constant));
        printf("'\n", name);

        return offset + 2;
    }

    public static int invokeInstruction(String name, Chunk chunk, int offset) {
        int constant  = chunk.code.get(offset + 1);
        int argCount  = chunk.code.get(offset + 2);
        printf("%-16s (%d args) %4d '", name, argCount, constant);
        printValue(chunk.constants.values.get(constant));
        printf("'\n");
        return offset + 3;
    }

    public static int disassembleInstruction(Chunk chunk, int offset) {
        printf("%04d", offset);

        if (offset > 0 && chunk.lines.get(offset) == chunk.lines.get(offset - 1)) {
            printf("   | ");
        } else {
            printf("%4d ", chunk.lines.get(offset));
        }

        int instruction = chunk.code.get(offset);
        if (OP_CONSTANT.opcode == instruction) {
            return constantInstruction("OP_CONSTANT", chunk, offset);
        }
        if (OP_NIL.opcode == instruction) {
            return simpleInstruction("OP_NIL", offset);
        }
        if (OP_TRUE.opcode == instruction) {
            return simpleInstruction("OP_TRUE", offset);
        }
        if (OP_FALSE.opcode == instruction) {
            return simpleInstruction("OP_FALSE", offset);
        }
        if (OP_POP.opcode == instruction) {
            return simpleInstruction("OP_POP", offset);
        }
        if (OP_GET_LOCAL.opcode == instruction) {
            return byteInstruction("OP_GET_LOCAL", chunk, offset);
        }
        if (OP_SET_LOCAL.opcode == instruction) {
            return byteInstruction("OP_SET_LOCAL", chunk, offset);
        }
        if (OP_GET_GLOBAL.opcode == instruction) {
            return constantInstruction("OP_GET_GLOBAL", chunk, offset);
        }
        if (OP_DEFINE_GLOBAL.opcode == instruction) {
            return constantInstruction("OP_DEFINE_GLOBAL", chunk, offset);
        }
        if (OP_SET_GLOBAL.opcode == instruction) {
            return constantInstruction("OP_SET_GLOBAL", chunk, offset);
        }
        if (OP_GET_UPVALUE.opcode == instruction) {
            return byteInstruction("OP_GET_UPVALUE", chunk, offset);
        }
        if (OP_SET_UPVALUE.opcode == instruction) {
            return byteInstruction("OP_SET_UPVALUE", chunk, offset);
        }
        if (OP_GET_PROPERTY.opcode == instruction) {
            return constantInstruction("OP_GET_PROPERTY", chunk, offset);
        }
        if (OP_SET_PROPERTY.opcode == instruction) {
            return constantInstruction("OP_SET_PROPERTY", chunk, offset);
        }
        if (OP_GET_SUPER.opcode == instruction) {
            return constantInstruction("OP_GET_SUPER", chunk, offset);
        }
        if (OP_EQUAL.opcode == instruction) {
            return simpleInstruction("OP_EQUAL", offset);
        }
        if (OP_GREATER.opcode == instruction) {
            return simpleInstruction("OP_GREATER", offset);
        }
        if (OP_LESS.opcode == instruction) {
            return simpleInstruction("OP_LESS", offset);
        }
        if (OP_ADD.opcode == instruction) {
            return simpleInstruction("OP_ADD", offset);
        }
        if (OP_SUBTRACT.opcode == instruction) {
            return simpleInstruction("OP_SUBTRACT", offset);
        }
        if (OP_MULTIPLY.opcode == instruction) {
            return simpleInstruction("OP_MULTIPLY", offset);
        }
        if (OP_DIVIDE.opcode == instruction) {
            return simpleInstruction("OP_DIVIDE", offset);
        }
        if (OP_NOT.opcode == instruction) {
            return simpleInstruction("OP_NOT", offset);
        }
        if (OP_NEGATE.opcode == instruction) {
            return simpleInstruction("OP_NEGATE", offset);
        }
        if (OP_PRINT.opcode == instruction) {
            return simpleInstruction("OP_PRINT", offset);
        }
        if (OP_JUMP.opcode == instruction) {
            return jumpInstruction("OP_JUMP", 1, chunk, offset);
        }
        if (OP_JUMP_IF_FALSE.opcode == instruction) {
            return jumpInstruction("OP_JUMP_IF_FALSE", 1, chunk, offset);
        }
        if (OP_LOOP.opcode == instruction) {
            return jumpInstruction("OP_LOOP", -1, chunk, offset);
        }
        if (OP_CALL.opcode == instruction) {
            return byteInstruction("OP_CALL", chunk, offset);
        }
        if (OP_INVOKE.opcode == instruction) {
            return invokeInstruction("OP_INVOKE", chunk, offset);
        }
        if (OP_SUPER_INVOKE.opcode == instruction) {
            return invokeInstruction("OP_SUPER_INVOKE", chunk, offset);
        }
        if (OP_CLOSURE.opcode == instruction) {
            offset++;
            int constant = chunk.code.get(offset++);
            printf("%-16s %4d ", "OP_CLOSURE", constant);
            printValue(chunk.constants.values.get(constant));
            printf("\n");
            ObjFunction function = AS_FUNCTION(chunk.constants.values.get(constant));

            for (int j = 0; j < function.upvalueCount; j++) {
                int isLocal = chunk.code.get(offset++);
                int index = chunk.code.get(offset++);
                printf("%04d      |                     %s %d\n",
                        offset - 2, (isLocal > 0 ? "local" : "upvalue"), index);
            }
            return offset;
        }
        if (OP_CLOSE_UPVALUE.opcode == instruction) {
            return simpleInstruction("OP_CLOSE_UPVALUE", offset);
        }
        if (OP_RETURN.opcode == instruction) {
            return simpleInstruction("OP_RETURN", offset);
        }
        if (OP_CLASS.opcode == instruction) {
            return constantInstruction("OP_CLASS", chunk, offset);
        }
        if (OP_INHERIT.opcode == instruction) {
            return simpleInstruction("OP_INHERIT", offset);
        }
        if (OP_METHOD.opcode == instruction) {
            return constantInstruction("OP_METHOD", chunk, offset);
        }

        printf("Unknown opcode %d\n", instruction);
        return offset + 1;
    }

}
