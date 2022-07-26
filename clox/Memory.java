package clox;

import static clox.Chunk.freeChunk;
import static clox.Common.DEBUG_LOG_GC;
import static clox.Common.DEBUG_STRESS_GC;
import static clox.Compiler.markCompilerRoots;
import static clox.Main.SIZE_FACTOR;
import static clox.ObjectLox.*;
import static clox.Table.*;
import static clox.Value.*;
import static clox.utility.Utility.printf;
import static java.lang.System.exit;

public class Memory {
    public static final int GC_HEAP_GROW_FACTOR = 2;

    public static<T> T ALLOCATE(T obj) {
        return reallocate(obj, 0, SIZE_FACTOR);
    }

    public static<T> T FREE(T pointer) {
        return reallocate(pointer, SIZE_FACTOR, 0);
    }

    public static int GROW_CAPACITY(int capacity) {
        return ((capacity) < 8 ? 8 : (capacity) * 2);
    }

    public static<T> T GROW_ARRAY(T pointer, int oldCount, int newCount) {
        return reallocate(pointer, SIZE_FACTOR * oldCount, SIZE_FACTOR * newCount);
    }

    public static<T> T FREE_ARRAY(T pointer, int oldCount) {
        return reallocate(pointer, SIZE_FACTOR * oldCount, 0);
    }

    private static<T> T reallocate(T pointer, int oldSize, int newSize) {
        vm.bytesAllocated += newSize - oldSize;
        if(newSize > oldSize){
            if(DEBUG_STRESS_GC) {
                collectGarbage();
            }

            if(vm.bytesAllocated > vm.nextGC){
                collectGarbage();
            }
        }

        if (newSize == 0) {
            free(pointer);
            return null;
        }

        T result = realloc(pointer, newSize);
        if (result == null) exit(1);
        return result;
    }

    public static void freeObject(Obj object) {
        if(DEBUG_LOG_GC){
            printf("%s free type %s\n", object, object.type);
        }
        switch (object.type) {
            case OBJ_BOUND_METHOD: {
                FREE(object);
                break;
            }
            case OBJ_CLASS: {
                ObjClass klass = (ObjClass)object;
                freeTable(klass.methods);
                FREE(object);
                break;
            }
            case OBJ_CLOSURE: {
                ObjClosure closure = (ObjClosure) object;
                FREE_ARRAY(closure.upvalues, closure.upvalueCount);
                FREE(object);
                break;
            }
            case OBJ_FUNCTION: {
                ObjFunction function = (ObjFunction ) object;
                freeChunk(function.chunk);
                FREE(object);
                break;
            }
            case OBJ_INSTANCE: {
                ObjInstance instance = (ObjInstance)object;
                freeTable(instance.fields);
                FREE(object);
                break;
            }
            case OBJ_NATIVE:
                FREE(object);
                break;
            case OBJ_STRING: {
                ObjString string = (ObjString)object;
                FREE_ARRAY(string.chars, string.length + 1);
                FREE(object);
                break;
            }
            case OBJ_UPVALUE: {
                FREE(object);
                break;
            }
        }
    }

    public static void freeObjects() {
        Obj object = vm.objects;
        while (object != null) {
            Obj next = object.next;
            freeObject(object);
            object = next;
        }

        free(vm.grayStack);
    }

    public static void markObject(Obj object) {
        if (object == null) return;
        if (object.isMarked) return;

        if(DEBUG_LOG_GC){
            printf("%s mark ", object);
            printValue(OBJ_VAL(object));
            printf("\n");
        }
        object.isMarked = true;

        if(vm.grayCapacity < vm.grayCount + 1){
            vm.grayCapacity = GROW_CAPACITY(vm.grayCapacity);
            vm.grayStack = realloc(REALLOCATE_ARRAY(vm.grayStack, 5 * vm.grayCapacity), 0);

            if (vm.grayStack == null) exit(1);
        }
        vm.grayStack[vm.grayCount++] = object;
    }

    public static void markValue(Value value){
        if(IS_OBJ(value)) markObject(AS_OBJ(value));
    }

    public static void markRoots(){
        for(int slot = 0; slot < vm.stackTop; slot++){
            markValue(vm.stack[slot]);
        }

        for(int i = 0; i < vm.frameCount; i++){
            markObject(vm.frames[i].closure);
        }

        for(ObjUpvalue upvalue = vm.openUpvalues; upvalue != null; upvalue = upvalue.next){
            markObject(upvalue);
        }

        markTable(vm.globals);
        markCompilerRoots();
        markObject(vm.initString);
    }

    public static void markArray(ValueArray array){
        for(int i = 0; i < array.count; i++){
            markValue(array.values.get(i));
        }
    }

    public static void blackenObject(Obj object) {
        if(DEBUG_LOG_GC){
            printf("%s blacken ", object.toString());
            printValue(OBJ_VAL(object));
            printf("\n");
        }

        switch (object.type) {
            case OBJ_BOUND_METHOD: {
                ObjBoundMethod bound = (ObjBoundMethod)object;
                markValue(bound.receiver);
                markObject(bound.method);
                break;
            }
            case OBJ_CLASS: {
                ObjClass klass = (ObjClass)object;
                markObject(klass.name);
                markTable(klass.methods);
                break;
            }
            case OBJ_CLOSURE:{
                ObjClosure closure = (ObjClosure) object;
                markObject(closure.function);
                for(int i = 0; i < closure.upvalueCount; i++){
                    markObject(closure.upvalues[i]);
                }
                break;
            }
            case OBJ_FUNCTION:{
                ObjFunction function = (ObjFunction) object;
                markObject(function.name);
                markArray(function.chunk.constants);
                break;
            }
            case OBJ_INSTANCE: {
                ObjInstance instance = (ObjInstance)object;
                markObject(instance.klass);
                markTable(instance.fields);
                break;
            }
            case OBJ_UPVALUE:
                markValue(((ObjUpvalue) object).closed);
                break;
            case OBJ_NATIVE:
            case OBJ_STRING:
                break;
        }
    }

    public static void traceReferences(){
        while(vm.grayCount > 0){
            Obj object = vm.grayStack[--vm.grayCount];
            blackenObject(object);
        }
    }

    public static void sweep(){
        Obj previous = null;
        Obj object = vm.objects;
        while(object != null){
            if(object.isMarked){
                object.isMarked = false;
                previous = object;
                object = object.next;
            }else{
                Obj unreached = object;
                object = object.next;
                if(previous != null){
                    previous.next = object;
                }else{
                    vm.objects = object;
                }
                freeObject(unreached);
            }
        }
    }

    public static void collectGarbage() {
        int before = -1;
        if(DEBUG_LOG_GC){
            printf("-- gc begin\n");
            before = vm.bytesAllocated;
        }

        markRoots();
        traceReferences();
        tableRemoveWhite(vm.strings);
        sweep();
        vm.nextGC = vm.bytesAllocated * GC_HEAP_GROW_FACTOR;

        if(DEBUG_LOG_GC){
                printf("-- gc end\n");
                printf("   collected %d bytes (from %d to %d) next at %d\n",
                        before - vm.bytesAllocated, before, vm.bytesAllocated,
                        vm.nextGC);
        }
    }

    //======================================Advanced functions==========================================================
    public static <T> T realloc(T pointer, int newSize) {
        return pointer;
    }

    private static Obj[] REALLOCATE_ARRAY(Obj[] old, int newSize) {
        Obj[] new_ = new Obj[newSize];
        if(old == null) return new_;
        for (int i = 0; i < old.length; i++) {
            new_[i] = old[i];
        }
        return new_;
    }

    public static <T> void free(T pointer) {
    }

    public static int memcmp(String s1, String s2, int length) {
        return s1.compareTo(s2);
    }
}
