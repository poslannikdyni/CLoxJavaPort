package clox.utility;

import clox.ObjectLox;

import java.io.PrintStream;
import java.util.MissingFormatArgumentException;

// This place contains utilities that are used in many places in the source code. They are intended to be compatible with C code.
public class Utility {
    public static PrintStream stderr = System.err;

    public static void printf(String msg, String arg) {
        System.out.printf(msg, arg);
    }

    public static void printf(String msg, int i1, String str, int i2){
        System.out.printf(msg, i1, str, i2);
    }

    public static void printf(String msg, String name, int slot) {
        System.out.printf(msg, name, slot);
    }
    
    public static void printf(String msg, String name, int offset, int i) {
        System.out.printf(msg, name, offset, i);
    }

    public static void printf(String msg) {
        System.out.printf(msg);
    }

    public static void printf(String msg, int offset) {
        System.out.printf(msg, offset);
    }

    public static void printf(String msg, ObjectLox.Obj object, Integer size, ObjectLox.ObjType type) {
        System.out.printf(msg, object.toString(), size, type.toString());
    }

    public static void printf(String msg, ObjectLox.Obj object, ObjectLox.ObjType type) {
        System.out.printf(msg, object.toString(), type.toString());
    }

    public static void printf(String msg, int i1, int i2, int i3, int i4) {
        System.out.printf(msg, i1, i2, i3, i4);
    }

    public static void printf(String msg, ObjectLox.Obj object) {
        System.out.printf(msg, object.toString());
    }

    public static void vfprintf(PrintStream target, String[] format) {
        for (String msg : format) {
            printfOrPrint(target, msg);
        }
    }

    private static void printfOrPrint(PrintStream target, String msg) {
        try {
            target.printf(msg);
        }
        catch (MissingFormatArgumentException nop){
            System.out.print(msg);
        }
    }

    public static void fprintf(PrintStream target, String msg, Integer integer) {
        target.printf(msg, integer);
    }

    public static void fprintf(PrintStream target, String msg, String integer) {
        target.printf(msg, integer);
    }

    public static void fprintf(PrintStream target, String msg) {
        target.printf(msg);
    }

    public static void fputs(String msg, PrintStream target) {
        target.printf(msg);
    }

    public static String special(int length, Object value){
        return String.format("%-10s", value.toString());
    }
}
