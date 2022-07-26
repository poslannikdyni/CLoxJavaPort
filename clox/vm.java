package clox;

import static clox.Chunk.OpCode.*;
import static clox.Common.DEBUG_TRACE_EXECUTION;
import static clox.Compiler.compile;
import static clox.Debug.disassembleInstruction;
import static clox.Main.UINT8_COUNT;
import static clox.Memory.freeObjects;
import static clox.Table.*;
import static clox.vm.InterpretResult.*;
import static clox.Value.*;
import static clox.ObjectLox.*;
import static clox.utility.Utility.*;

public class vm {
    public static final int FRAMES_MAX = 64;
    public static final int STACK_MAX = (FRAMES_MAX * UINT8_COUNT);

    public static class CallFrame {
        public ObjClosure closure;
        public int ip;
        public int slots;
    }

    public static CallFrame[] frames = new CallFrame[FRAMES_MAX];
    public static int frameCount;
    public static Value[] stack = new Value[STACK_MAX];
    public static int stackTop;
    protected static Table globals = new Table();
    protected static Table strings = new Table();
    protected static ObjString initString;
    protected static ObjUpvalue openUpvalues;
    public static Obj objects;
    public static int grayCount;
    public static int grayCapacity;
    public static Obj[] grayStack;
    public static int bytesAllocated;
    public static int nextGC;

    public enum InterpretResult {
        INTERPRET_OK,
        INTERPRET_COMPILE_ERROR,
        INTERPRET_RUNTIME_ERROR
    }

    public static class ClockNative extends NativeFn {
        static Value clockNative(int argCount, Value... args) {
            return NUMBER_VAL(Double.valueOf(System.currentTimeMillis()) / 1_000);
        }

        @Override
        public Value run(int argCount, Value[] args) {
            return clockNative(0);
        }
    }

    static void resetStack() {
        vm.stackTop = 0;
        vm.frameCount = 0;
        vm.openUpvalues = null;
    }

    static void runtimeError(String... format) {
        vfprintf(stderr, format);
        fputs("\n", stderr);

        for (int i = vm.frameCount - 1; i >= 0; i--) {
            CallFrame frame = vm.frames[i];
            ObjFunction function = frame.closure.function;
            int instruction = function.chunk.code.get(frame.ip);
            fprintf(stderr, "[line %d] in ",
                    function.chunk.lines.get(instruction));
            if (function.name == null) {
                fprintf(stderr, "script\n");
            } else {
                fprintf(stderr, "%s()\n", function.name.chars);
            }
        }

        resetStack();
    }

    static void defineNative(String name, NativeFn function) {
        push(OBJ_VAL(copyString(name)));
        push(OBJ_VAL(newNative(function)));
        tableSet(vm.globals, AS_STRING(vm.stack[0]), vm.stack[1]);
        pop();
        pop();
    }

    static void initVM() {
        resetStack();
        vm.objects = null;
        vm.bytesAllocated = 0;
        vm.nextGC = 1024 * 1024;
        vm.grayCount = 0;
        vm.grayCapacity = 0;
        vm.grayStack = null;

        initTable(vm.globals);
        initTable(vm.strings);

        vm.initString = null;
        vm.initString = copyString("init");

        defineNative("clock", new ClockNative());

        for (int i = 0; i < frames.length; i++) {
            frames[i] = new CallFrame();
        }
    }

    static void freeVM() {
        freeTable(vm.globals);
        freeTable(vm.strings);
        vm.initString = null;
        freeObjects();
    }

    static void push(Value value) {
        vm.stack[stackTop] = value;
        vm.stackTop++;
    }

    static Value pop() {
        vm.stackTop--;
        return vm.stack[stackTop];
    }

    public static Value peek(int distance) {
        return vm.stack[stackTop - 1 - distance];
    }

    public static boolean call(ObjClosure closure, int argCount) {
        if (argCount != closure.function.arity) {
            runtimeError("Expected %d arguments but got %d.",
                    Integer.toString(closure.function.arity), Integer.toString(argCount));
            return false;
        }

        if (vm.frameCount == FRAMES_MAX) {
            runtimeError("Stack overflow.");
            return false;
        }

        CallFrame frame = vm.frames[vm.frameCount++];
        frame.closure = closure;
        frame.ip = 0;
        frame.slots = vm.stackTop - argCount - 1;
        return true;
    }

    public static boolean callValue(Value callee, int argCount) {
        if (IS_OBJ(callee)) {
            switch (OBJ_TYPE(callee)) {
                case OBJ_BOUND_METHOD: {
                    ObjBoundMethod bound = AS_BOUND_METHOD(callee);
                    vm.stack[vm.stackTop - argCount - 1] = bound.receiver;
                    return call(bound.method, argCount);
                }
                case OBJ_CLASS: {
                    ObjClass klass = AS_CLASS(callee);
                    vm.stack[stackTop - argCount - 1] = OBJ_VAL(newInstance(klass));
                    Value initializer = new NilValue();
                    if (tableGet(klass.methods, vm.initString, initializer)) {
                        return call(AS_CLOSURE(initializer), argCount);
                    } else if (argCount != 0) {
                        runtimeError("Expected 0 arguments but got %s.", argCount + "");
                        return false;
                    }
                    return true;
                }
                case OBJ_CLOSURE:
                    return call(AS_CLOSURE(callee), argCount);
                case OBJ_NATIVE: {
                    NativeFn native_ = AS_NATIVE(callee);
                    Value result = native_.run(argCount, getFrame(argCount));
                    vm.stackTop -= argCount + 1;
                    push(result);
                    return true;
                }
                default:
                    break;
            }
        }
        runtimeError("Can only call functions and classes.");
        return false;
    }

    public static boolean invokeFromClass(ObjClass klass, ObjString name, int argCount) {
        Value method = new NilValue();
        if (!tableGet(klass.methods, name, method)) {
            runtimeError("Undefined property '%s'.", name.chars);
            return false;
        }
        return call(AS_CLOSURE(method), argCount);
    }

    public static boolean invoke(ObjString name, int argCount) {
        Value receiver = peek(argCount);

        if (!IS_INSTANCE(receiver)) {
            runtimeError("Only instances have methods.");
            return false;
        }

        ObjInstance instance = AS_INSTANCE(receiver);
        Value value = new NilValue();
        if (tableGet(instance.fields, name, value)) {
            vm.stack[stackTop - argCount - 1] = value;
            return callValue(value, argCount);
        }
        return invokeFromClass(instance.klass, name, argCount);
    }

    static ObjUpvalue captureUpvalue(Value local) {
        ObjUpvalue prevUpvalue = null;
        ObjUpvalue upvalue = vm.openUpvalues;
        while (upvalue != null && upvalue.location != local) {
            prevUpvalue = upvalue;
            upvalue = upvalue.next;
        }

        if (upvalue != null && upvalue.location == local) {
            return upvalue;
        }

        ObjUpvalue createdUpvalue = newUpvalue(local);
        createdUpvalue.next = upvalue;

        if (prevUpvalue == null) {
            vm.openUpvalues = createdUpvalue;
        } else {
            prevUpvalue.next = createdUpvalue;
        }
        return createdUpvalue;
    }

    static void closeUpvalues(Value last) {
        while (vm.openUpvalues != null && vm.openUpvalues.location != last) {
            ObjUpvalue upvalue = vm.openUpvalues;
            upvalue.closed = upvalue.location;
            // upvalue.location = upvalue.closed;
            vm.openUpvalues = upvalue.next;
        }
    }

    public static void defineMethod(ObjString name) {
        Value method = peek(0);
        ObjClass klass = AS_CLASS(peek(1));
        tableSet(klass.methods, name, method);
        pop();
    }

    public static boolean isFalsey(Value value) {
        return IS_NIL(value) || (IS_BOOL(value) && !AS_BOOL(value));
    }

    static void concatenate() {
        ObjString b = AS_STRING(peek(0));
        ObjString a = AS_STRING(peek(1));

        ObjString result = takeString(a.chars + b.chars);
        pop();
        pop();
        push(OBJ_VAL(result));
    }

    public static boolean bindMethod(ObjClass klass, ObjString name) {
        Value method = new NilValue();
        if (!tableGet(klass.methods, name, method)) {
            runtimeError("Undefined property '%s'.", name.chars);
            return false;
        }

        ObjBoundMethod bound = newBoundMethod(peek(0), AS_CLOSURE(method));
        pop();
        push(OBJ_VAL(bound));
        return true;
    }

    static int READ_BYTE(CallFrame frame) {
        int rez = frame.closure.function.chunk.code.get(frame.ip);
        frame.ip = frame.ip + 1;
        return rez;
    }

    static int READ_SHORT(CallFrame frame) {
        frame.ip += 2;
        int i2 = frame.closure.function.chunk.code.get(frame.ip - 2);
        int i1 = frame.closure.function.chunk.code.get(frame.ip - 1);
        return ((i2 << 8) | i1);
    }

    static Value READ_CONSTANT(CallFrame frame) {
        return frame.closure.function.chunk.constants.values.get(READ_BYTE(frame));
    }

    static ObjString READ_STRING(CallFrame frame) {
        return AS_STRING(READ_CONSTANT(frame));
    }

    static InterpretResult BINARY_OP(int instruction) {
        if (!IS_NUMBER(peek(0)) || !IS_NUMBER(peek(1))) {
            runtimeError("Operands must be numbers.");
            return INTERPRET_RUNTIME_ERROR;
        }
        double b = AS_NUMBER(pop());
        double a = AS_NUMBER(pop());

        if (instruction == OP_ADD.opcode)
            push(new DoubleValue(a + b));
        else if (instruction == OP_SUBTRACT.opcode)
            push(new DoubleValue(a - b));
        else if (instruction == OP_MULTIPLY.opcode)
            push(new DoubleValue(a * b));
        else if (instruction == OP_DIVIDE.opcode)
            push(new DoubleValue(a / b));
        else if (instruction == OP_GREATER.opcode)
            push(new BoolValue(a > b));
        else if (instruction == OP_LESS.opcode)
            push(new BoolValue(a < b));
        else
            throw new RuntimeException("Instruction : [ " + instruction + " ] is no binary operator.");
        return null; //Unreachable
    }

    static InterpretResult run() {
        CallFrame frame = vm.frames[vm.frameCount - 1];

        for (; ; ) {
            if (DEBUG_TRACE_EXECUTION) {
                printf("          ");
                for (Value slot : vm.stack) {
                    printf("[ ");
                    printValue(slot);
                    printf(" ]");
                }
                printf("\n");
                disassembleInstruction(frame.closure.function.chunk, frame.ip);
            }

            int instruction = READ_BYTE(frame);

            if (instruction == OP_CONSTANT.opcode) {
                Value constant = READ_CONSTANT(frame);
                push(constant);
            } else if (instruction == OP_NIL.opcode) {
                push(NIL_VAL());
            } else if (instruction == OP_TRUE.opcode) {
                push(BOOL_VAL(true));
            } else if (instruction == OP_FALSE.opcode) {
                push(BOOL_VAL(false));
            } else if (instruction == OP_POP.opcode) {
                pop();
            } else if (instruction == OP_GET_LOCAL.opcode) {
                int slot = READ_BYTE(frame);
                push(getSlotFromFrame(frame, slot));
            } else if (instruction == OP_SET_LOCAL.opcode) {
                int slot = READ_BYTE(frame);
                setSlotFromFrame(frame, slot, peek(0));
            } else if (instruction == OP_GET_GLOBAL.opcode) {
                ObjString name = READ_STRING(frame);
                Value value = new NilValue();
                if (!tableGet(vm.globals, name, value)) {
                    runtimeError(String.format("Undefined variable '%s'.", name.chars));
                    return INTERPRET_RUNTIME_ERROR;
                }
                push(value);
            } else if (instruction == OP_DEFINE_GLOBAL.opcode) {
                ObjString name = READ_STRING(frame);
                tableSet(vm.globals, name, peek(0));
                pop();
            } else if (instruction == OP_SET_GLOBAL.opcode) {
                ObjString name = READ_STRING(frame);
                if (tableSet(vm.globals, name, peek(0))) {
                    tableDelete(vm.globals, name);
                    runtimeError("Undefined variable '%s'.", name.chars);
                    return INTERPRET_RUNTIME_ERROR;
                }
            } else if (instruction == OP_GET_UPVALUE.opcode) {
                int slot = READ_BYTE(frame);
                push(frame.closure.upvalues[slot].location);
            } else if (instruction == OP_SET_UPVALUE.opcode) {
                int slot = READ_BYTE(frame);
                frame.closure.upvalues[slot].location.set(peek(0));
            } else if (instruction == OP_GET_PROPERTY.opcode) {
                if (!IS_INSTANCE(peek(0))) {
                    runtimeError("Only instances have properties.");
                    return INTERPRET_RUNTIME_ERROR;
                }

                ObjInstance instance = AS_INSTANCE(peek(0));
                ObjString name = READ_STRING(frame);
                Value value = new NilValue();
                
                if (tableGet(instance.fields, name, value)) {
                    pop();
                    push(value);
                    continue;
                }
                if (!bindMethod(instance.klass, name)) {
                    return INTERPRET_RUNTIME_ERROR;
                }
            } else if (instruction == OP_SET_PROPERTY.opcode) {
                if (!IS_INSTANCE(peek(1))) {
                    runtimeError("Only instances have fields.");
                    return INTERPRET_RUNTIME_ERROR;
                }

                ObjInstance instance = AS_INSTANCE(peek(1));
                tableSet(instance.fields, READ_STRING(frame), peek(0));
                Value value = pop();
                pop();
                push(value);
            } else if (instruction == OP_GET_SUPER.opcode) {
                ObjString name = READ_STRING(frame);
                ObjClass superclass = AS_CLASS(pop());
                
                if (!bindMethod(superclass, name)) {
                    return INTERPRET_RUNTIME_ERROR;
                }
            } else if (instruction == OP_EQUAL.opcode) {
                Value b = pop();
                Value a = pop();
                push(BOOL_VAL(valuesEqual(a, b)));
            } else if (instruction == OP_GREATER.opcode) {
                BINARY_OP(instruction);
            } else if (instruction == OP_LESS.opcode) {
                BINARY_OP(instruction);
            } else if (instruction == OP_ADD.opcode) {
                if (IS_STRING(peek(0)) && IS_STRING(peek(1))) {
                    concatenate();
                } else if (IS_NUMBER(peek(0)) && IS_NUMBER(peek(1))) {
                    double b = AS_NUMBER(pop());
                    double a = AS_NUMBER(pop());
                    push(NUMBER_VAL(a + b));
                } else {
                    runtimeError(
                            "Operands must be two numbers or two strings.");
                    return INTERPRET_RUNTIME_ERROR;
                }
                continue;
            } else if (instruction == OP_SUBTRACT.opcode || instruction == OP_MULTIPLY.opcode || instruction == OP_DIVIDE.opcode) {
                BINARY_OP(instruction);
            } else if (instruction == OP_NOT.opcode) {
                push(BOOL_VAL(isFalsey(pop())));
            } else if (instruction == OP_NEGATE.opcode) {
                if (!IS_NUMBER(peek(0))) {
                    runtimeError("Operand must be a number.");
                    return INTERPRET_RUNTIME_ERROR;
                }
                push(NUMBER_VAL(-AS_NUMBER(pop())));
            } else if (instruction == OP_PRINT.opcode) {
                printValue(pop());
                printf("\n");
            } else if (instruction == OP_JUMP.opcode) {
                int offset = READ_SHORT(frame);
                frame.ip += offset;
            } else if (instruction == OP_JUMP_IF_FALSE.opcode) {
                int offset = READ_SHORT(frame);
                if (isFalsey(peek(0))) frame.ip += offset;
            } else if (instruction == OP_LOOP.opcode) {
                int offset = READ_SHORT(frame);
                frame.ip -= offset;
            } else if (instruction == OP_CALL.opcode) {
                int argCount = READ_BYTE(frame);
                if (!callValue(peek(argCount), argCount)) {
                    return INTERPRET_RUNTIME_ERROR;
                }
                frame = vm.frames[vm.frameCount - 1];

            } else if (instruction == OP_INVOKE.opcode) {
                ObjString method = READ_STRING(frame);
                int argCount = READ_BYTE(frame);
                if (!invoke(method, argCount)) {
                    return INTERPRET_RUNTIME_ERROR;
                }
                frame = vm.frames[vm.frameCount - 1];
            } else if (instruction == OP_SUPER_INVOKE.opcode) {
                ObjString method = READ_STRING(frame);
                int argCount = READ_BYTE(frame);
                ObjClass superclass = AS_CLASS(pop());
                if (!invokeFromClass(superclass, method, argCount)) {
                    return INTERPRET_RUNTIME_ERROR;
                }
                frame = vm.frames[vm.frameCount - 1];
            } else if (instruction == OP_CLOSURE.opcode) {
                ObjFunction function = AS_FUNCTION(READ_CONSTANT(frame));
                ObjClosure closure = newClosure(function);
                push(OBJ_VAL(closure));
                for (int i = 0; i < closure.upvalueCount; ++i) {
                    boolean isLocal = (READ_BYTE(frame) == 1);
                    int index = READ_BYTE(frame);
                    if (isLocal) {
                        closure.upvalues[i] = captureUpvalue(getSlotFromFrame(frame, index));
                    } else {
                        closure.upvalues[i] = frame.closure.upvalues[index];
                    }
                }
            } else if (instruction == OP_CLOSE_UPVALUE.opcode) {
                closeUpvalues(vm.stack[vm.stackTop - 1]);
                pop();
            } else if (instruction == OP_RETURN.opcode) {
                Value result = pop();
                closeUpvalues(getSlotFromFrame(frame, 0));
                vm.frameCount--;
                if (vm.frameCount == 0) {
                    pop();
                    return INTERPRET_OK;
                }

                vm.stackTop = frame.slots;
                push(result);
                frame = vm.frames[vm.frameCount - 1];
            } else if (instruction == OP_CLASS.opcode) {
                push(OBJ_VAL(newClass(READ_STRING(frame))));
            } else if (instruction == OP_METHOD.opcode) {
                defineMethod(READ_STRING(frame));
            } else if (instruction == OP_INHERIT.opcode) {
                Value superclass = peek(1);

                if (!IS_CLASS(superclass)) {
                    runtimeError("Superclass must be a class.");
                    return INTERPRET_RUNTIME_ERROR;
                }

                ObjClass subclass = AS_CLASS(peek(0));
                tableAddAll(AS_CLASS(superclass).methods, subclass.methods);
                pop();
            } else {
                throw new RuntimeException("Instruction : [ " + instruction + " ] no implement in VM.");
            }
        }
    }

    static InterpretResult interpret(String source) {
        ObjFunction function = compile(source);
        if (function == null) return INTERPRET_COMPILE_ERROR;

        push(OBJ_VAL(function));
        ObjClosure closure = newClosure(function);
        pop();
        push(OBJ_VAL(closure));
        call(closure, 0);

        return run();
    }

    //======================================Advanced functions==========================================================

    private static Value getSlotFromFrame(CallFrame frame, int slot) {
        return vm.stack[frame.slots + slot];
    }

    private static void setSlotFromFrame(CallFrame frame, int slot, Value value) {
        vm.stack[frame.slots + slot].set(value);
    }

    private static Value[] getFrame(int count) {
        Value[] frame = new Value[count];
        int j = 0;
        for (int i = vm.stackTop - count; i < vm.stackTop; i++) {
            frame[j] = vm.stack[i];
            j++;
        }
        return frame;
    }
}

