package me.egg82.ipapi.debug;

public interface IDebugPrinter {
    // functions
    void printInfo(String message);

    void printWarning(String message);

    void printError(String message);
}
