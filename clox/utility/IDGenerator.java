package clox.utility;

// This code is not contained in the original CLox compiler.
// It does not make significant changes to the original program,
// but allows you to use Java features to improve convenience when implementing opcodes.
public class IDGenerator {
    private static int OP_CODE_GENERATOR = -1;
    private static int PRECEDENCE = 10;

    public static int getNextOpCode(){
        return ++OP_CODE_GENERATOR;
    }

    public static int getPrecedenceNumber() {
        return ++PRECEDENCE;
    }
}
