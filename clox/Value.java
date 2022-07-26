package clox;

import java.util.ArrayList;
import java.util.List;

import static clox.Memory.*;
import static clox.ObjectLox.*;
import static clox.Value.ValueType.*;
import static clox.utility.Utility.printf;

public abstract class Value {
    public enum ValueType{
        VAL_BOOL,
        VAL_NIL,
        VAL_NUMBER,
        VAL_OBJ
    }

    public ValueType type;
    public Object as;

    public void set(Value value) {
        this.type = value.type;
        this.as = value.as;
    }

    public static boolean IS_BOOL(Value value)    {return value.type == VAL_BOOL;}
    public static boolean IS_NIL(Value value)     {return value.type == VAL_NIL;}
    public static boolean IS_NUMBER(Value value)  {return value.type == VAL_NUMBER;}
    public static boolean IS_OBJ(Value value)     {return value.type == VAL_OBJ;}

    public static Obj AS_OBJ(Value value)        {return (Obj) value.as;}
    public static boolean AS_BOOL(Value value)   {return (Boolean)value.as;}
    public static double AS_NUMBER(Value value)  {return (Double) value.as;}

    public static BoolValue BOOL_VAL(Boolean value)    {return new BoolValue(value);}
    public static NilValue NIL_VAL()                   {return new NilValue();}
    public static DoubleValue NUMBER_VAL(Double value) {return new DoubleValue(value);}
    public static ObjValue OBJ_VAL(Obj value)          {return new ObjValue(value);}

    public static class ValueArray{
        public int capacity;
        public int count;
        public List<Value> values;

        public Value get(int i) {
            return values.get(i);
        }
    }

    public static void initValueArray(ValueArray array){
        array.values = new ArrayList<>();
        array.capacity = 0;
        array.count = 0;
    }

    public static void writeValueArray(ValueArray array, Value value){
        if(array.capacity < array.count+1){
            int oldCapacity = array.capacity;
            array.capacity = GROW_CAPACITY(oldCapacity);
            array.values = GROW_ARRAY(array.values, oldCapacity, array.capacity);
        }

        array.values.add(array.count, value);
        array.count++;
    }

    public static void freeValueArray(ValueArray array){
        FREE_ARRAY(array.values, array.capacity);
        initValueArray(array);
    }

    public static void printValue(Value value){
        printf("%s", value.asString());
    }

    public static boolean valuesEqual(Value a, Value b){
        if (a.type != b.type) return false;
        switch (a.type) {
            case VAL_BOOL:   return AS_BOOL(a) == AS_BOOL(b);
            case VAL_NIL:    return true;
            case VAL_NUMBER: return AS_NUMBER(a) == AS_NUMBER(b);
            case VAL_OBJ:    return AS_OBJ(a) == AS_OBJ(b);
            default:         throw new RuntimeException("Unreachable"); // Unreachable.
        }
    }

    //======================================Advanced functions==========================================================
    public String asString(){
        switch (type){
            case VAL_BOOL :   return as.toString();
            case VAL_NIL :    return "nil";
            case VAL_NUMBER : return as.toString();
            case VAL_OBJ :    return ((Obj)as).asString();
            default:
                throw new RuntimeException("Add value : " + type);
        }
    }

    public Value(ValueType type, Object as) {
        this.type = type;
        this.as = as;
    }

    public static class BoolValue extends Value {
        public BoolValue(boolean value) {
            super(VAL_BOOL, value);
        }
    }

    public static class NilValue extends Value {
        public NilValue() {
            super(VAL_NIL, (double) 0);
        }
    }

    public static class DoubleValue extends Value {
        public DoubleValue(Double value) {
            super(VAL_NUMBER, value);
        }
    }

    public static class ObjValue extends Value {
        public ObjValue(Obj value) {
            super(VAL_OBJ, value);
        }
    }
}

