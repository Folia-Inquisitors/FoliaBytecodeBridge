package dev.fbbprobe.harness;

@FunctionalInterface
public interface ProbeValueCall {
    String run() throws Throwable;
}
