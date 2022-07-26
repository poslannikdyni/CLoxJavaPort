package clox;

import static clox.Chunk.initChunk;
import static clox.Common.DEBUG_LOG_GC;
import static clox.Main.SIZE_FACTOR;
import static clox.Memory.ALLOCATE;
import static clox.Memory.FREE_ARRAY;
import static clox.ObjectLox.ObjType.*;
import static clox.Table.*;
import static clox.Value.*;
import static clox.utility.Utility.printf;
import static clox.vm.pop;
import static clox.vm.push;

public class ObjectLox {
    public static ObjType OBJ_TYPE(Value value) {return AS_OBJ(value).type;}

    public static boolean IS_BOUND_METHOD(Value value)  {return isObjType(value, OBJ_BOUND_METHOD);}
    public static boolean IS_CLASS(Value value)         {return isObjType(value, OBJ_CLASS);}
    public static boolean IS_CLOSURE(Value value)       {return isObjType(value, OBJ_CLOSURE);}
    public static boolean IS_FUNCTION(Value value)      {return isObjType(value, OBJ_FUNCTION);}
    public static boolean IS_INSTANCE(Value value)      {return isObjType(value, OBJ_INSTANCE);}
    public static boolean IS_NATIVE(Value value)        {return isObjType(value, OBJ_NATIVE);}
    public static boolean IS_STRING(Value value)        {return isObjType(value, OBJ_STRING);}

    public static ObjBoundMethod AS_BOUND_METHOD(Value value)   {return ((ObjBoundMethod)AS_OBJ(value));}
    public static ObjClass       AS_CLASS(Value value)          {return ((ObjClass)AS_OBJ(value));}
    public static ObjClosure     AS_CLOSURE(Value value)        {return ((ObjClosure)AS_OBJ(value));}
    public static ObjFunction    AS_FUNCTION(Value value)       {return ((ObjFunction)AS_OBJ(value));}
    public static ObjInstance    AS_INSTANCE(Value value)       {return ((ObjInstance)AS_OBJ(value));}
    public static NativeFn       AS_NATIVE(Value value)         {return (((ObjNative)AS_OBJ(value)).function);}
    public static ObjString      AS_STRING(Value value)         {return (ObjString) AS_OBJ(value);}
    public static String         AS_CSTRING(Value value)        {return (((ObjString) AS_OBJ(value)).chars);}

    public enum ObjType {
        OBJ_BOUND_METHOD,
        OBJ_CLASS,
        OBJ_CLOSURE,
        OBJ_FUNCTION,
        OBJ_INSTANCE,
        OBJ_NATIVE,
        OBJ_STRING,
        OBJ_UPVALUE
    }

    public abstract static class Obj {
        public ObjType type;
        public boolean isMarked;
        public Obj next;

        public abstract String asString();
    }

    public static class ObjFunction extends Obj {
        public int arity; 
        int upvalueCount;
        public Chunk chunk;
        public ObjString name;

        @Override
        public String asString() {
            return type + " : " + name;
        }
    }

    public abstract static class NativeFn{
        int argCount;
        Value[] args;

        public abstract Value run(int argCount, Value[] args);

        public String asString() {
            return "Native function. Arg : " + argCount;
        }
    }

    public static class ObjNative extends Obj {
        NativeFn function;

        @Override
        public String asString() {
            return type + " : " + function.asString();
        }
    }

    public static class ObjString extends Obj {
        int length;
        String chars;
        int hash;

        @Override
        public String asString() {
            return chars;
        }
    }

    public static class ObjUpvalue extends Obj {
        Value location;
        Value closed;
        ObjUpvalue next;

        @Override
        public String asString() {
            return "ObjUpvalue";
        }
    }

    public static class ObjClosure extends Obj {
        public ObjFunction function;
        public ObjUpvalue[] upvalues;
        public int upvalueCount;

        @Override
        public String asString() {
            return "ObjClosure";
        }
    }

    public static class ObjClass extends Obj {
        public ObjString name;
        public Table methods;

        @Override
        public String asString() {
            return "ObjClass";
        }
    }

    public static class ObjInstance extends Obj {
        public ObjClass klass;
        public Table fields;

        @Override
        public String asString() {
            return "ObjInstance";
        }
    }

    public static class ObjBoundMethod extends Obj {
        public Value receiver;
        public ObjClosure method;

        @Override
        public String asString() {
            return "ObjBoundMethod";
        }
    }

    public static boolean isObjType(Value value, ObjType type) {return IS_OBJ(value) && AS_OBJ(value).type == type;}

    public static Obj ALLOCATE_OBJ(Obj obj, ObjType objectType) {
        return allocateObject(obj, objectType);
    }

    public static int hashString(String key, int len) {
        int rv = 0x811c9dc5;
        for(int i = 0; i < len; i++) {
            rv ^= key.charAt(i);
            rv *= 0x01000193;
        }

        //TODO this not tested code, need another implementation.
        if(rv < 0)
            return rv * (-1);
        return rv;
    }

    public static Obj allocateObject(Obj obj, ObjType type) {
        Obj object = obj;
        object.type = type;
        object.isMarked = false;
        object.next = vm.objects;
        vm.objects = object;

        if(DEBUG_LOG_GC) {
            Integer size = SIZE_FACTOR;
            printf("%s allocate %d for %s\n", object, size, type);
        }
        return object;
    }

    public static ObjBoundMethod newBoundMethod(Value receiver, ObjClosure method) {
        ObjBoundMethod bound = (ObjBoundMethod) ALLOCATE_OBJ(new ObjBoundMethod(),OBJ_BOUND_METHOD);
        bound.receiver = receiver;
        bound.method = method;
        return bound;
    }

    public static ObjClass newClass(ObjString name) {
        ObjClass klass = (ObjClass) ALLOCATE_OBJ(new ObjClass(), OBJ_CLASS);
        klass.name = name;
        klass.methods = new Table();
        initTable(klass.methods);
        return klass;
    }

    public static ObjClosure newClosure(ObjFunction function) {
        ObjUpvalue[] upvalues = ALLOCATE(new ObjUpvalue[function.upvalueCount]);
        for(int i = 0; i < function.upvalueCount; i++){
            upvalues[i] = null;
        }

        ObjClosure closure = (ObjClosure) ALLOCATE_OBJ(new ObjClosure(), OBJ_CLOSURE);
        closure.function = function;
        closure.upvalues = upvalues;
        closure.upvalueCount = function.upvalueCount;
        return closure;
    }

    public static ObjFunction newFunction(){
        ObjFunction function = (ObjFunction) ALLOCATE_OBJ(new ObjFunction(), OBJ_FUNCTION);
        function.arity = 0;
        function.upvalueCount = 0;
        function.name = null;
        function.chunk = new Chunk();
        initChunk(function.chunk);
        return function;
    }

    public static ObjInstance newInstance(ObjClass klass) {
        ObjInstance instance = (ObjInstance) ALLOCATE_OBJ(new ObjInstance(), OBJ_INSTANCE);
        instance.klass = klass;
        instance.fields = new Table();
        initTable(instance.fields);
        return instance;
    }

    public static ObjNative newNative(NativeFn function){
        ObjNative native_ = (ObjNative) ALLOCATE_OBJ(new ObjNative(), OBJ_NATIVE);
        native_.function = function;
        return native_;
    }

    public static ObjString allocateString(String chars, int hash) {
        ObjString string = (ObjString) ALLOCATE_OBJ(new ObjString(), OBJ_STRING);
        string.length = chars.length();
        string.chars = chars;
        string.hash = hash;
        push(OBJ_VAL(string));
        tableSet(vm.strings, string, NIL_VAL());
        pop();
        return string;
    }

    public static ObjString copyString(String chars) {
        int hash = hashString(chars, chars.length());
        ObjString interned = tableFindString(vm.strings, chars, chars.length(), hash);
        if (interned != null) return interned;
        return allocateString(chars, hash);
    }

    public static ObjUpvalue newUpvalue(Value slot) {
        ObjUpvalue upvalue = (ObjUpvalue) ALLOCATE_OBJ(new ObjUpvalue(), OBJ_UPVALUE);
        upvalue.closed = NIL_VAL();
        upvalue.location = slot;
        upvalue.next = null;
        return upvalue;
    }

    static void printFunction(ObjFunction function){
        if(function.name == null){
            printf("<script.");
            return;
        }
        printf("<fn %s.", function.name.chars);
    }

    public static ObjString takeString(String chars) {
        int hash = hashString(chars, chars.length());

        ObjString interned = tableFindString(vm.strings, chars, chars.length(), hash);
        if (interned != null) {
            FREE_ARRAY(chars, chars.length() + 1);
            return interned;
        }
        return allocateString(chars, hash);
    }

    public static void printObject(Value value) {
        switch (OBJ_TYPE(value)) {
            case OBJ_BOUND_METHOD:
                printFunction(AS_BOUND_METHOD(value).method.function);
                break;
            case OBJ_CLASS:
                printf("%s", AS_CLASS(value).name.chars);
                break;
            case OBJ_CLOSURE:
                printFunction(AS_CLOSURE(value).function);
                break;
            case OBJ_FUNCTION:
                printFunction(AS_FUNCTION(value));
                break;
            case OBJ_INSTANCE:
                printf("%s instance",
                        AS_INSTANCE(value).klass.name.chars);
                break;
            case OBJ_NATIVE:
                printf("<native fn>");
                break;
            case OBJ_STRING:
                printf("%s", AS_CSTRING(value));
                break;
            case OBJ_UPVALUE:
                printf("upvalue");
                break;
        }
    }
}
