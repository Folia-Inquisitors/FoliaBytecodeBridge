@echo off
java --sun-misc-unsafe-memory-access=allow -Xmx16G -Dfoliabytecodebridge.traceSchedulerCalls=true -Dfoliabytecodebridge.traceUnsafeCalls=true -javaagent:plugins\FoliaBytecodeBridge.jar -jar folia.jar nogui
pause
