package dev.fbbprobe.harness;

@FunctionalInterface
public interface ProbeCall {
    void run() throws Throwable;
}
