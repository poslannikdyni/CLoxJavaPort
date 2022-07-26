This repository contains a ported implementation of the CLox language compiler.
The original project is located at http://craftinginterpreters.com
Link to the repository https://github.com/munificent/craftinginterpreters

The current project embodies the following ideas:
1) Maximum compatibility with the implementation of CLox at the level at which the capabilities of the Java language allow.
2) Preservation of all functions, variables and original code execution path.
3) Saving the garbage collector api, algorithms for marking unused objects. Comment. No actual garbage collection occurs. The Java language, in version 17, does not allow manual management of memory and object placement. Unused objects are removed by the garbage collector of the java virtual machine.