package clox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static clox.ObjectLox.hashString;
import static clox.vm.InterpretResult;
import static clox.vm.InterpretResult.INTERPRET_COMPILE_ERROR;
import static clox.vm.InterpretResult.INTERPRET_RUNTIME_ERROR;

public class Main {
    public static final int UINT16_MAX = 65535;
    public static final int UINT8_MAX = 256;
    public static final int UINT8_COUNT = UINT8_MAX + 1;
    public static final int SIZE_FACTOR = 4;

    private static void repl() {
        try {
            while (true) {
                System.out.println(">");
                String line = new java.util.Scanner(System.in).nextLine();
                vm.interpret(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Could not read console");
            System.exit(190);
        }
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            System.err.println("Could not read file \\" + path);
            System.exit(74);
        }
        return null;
    }

    private static void runFile(String path) {
        String source = readFile(path);
        InterpretResult result = vm.interpret(source);

        if (result == INTERPRET_COMPILE_ERROR) System.exit(65);
        if (result == INTERPRET_RUNTIME_ERROR) System.exit(70);
    }

    public static void main(String[] args) {
        vm.initVM();
        if (args.length == 0) {
            repl();
        } else if (args.length == 1) {
            runFile(args[0]);
        } else {
            System.err.println("Usage: clox [path]");
            System.exit(64);
        }
        vm.freeVM();
    }
}
