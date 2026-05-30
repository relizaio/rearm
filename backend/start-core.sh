#!/bin/sh
# JVM memory tuning for a ~4000 MB pod limit, Java 25 + ZGC.
#
# Heap (-XX:InitialRAMPercentage/MaxRAMPercentage): container-aware
# sizing so heap rides with the cgroup limit. 25/70 split — heap can
# range from ~1 GB initial up to ~2.8 GB max at the current 4 GB pod
# size. With ZGC's continuous concurrent uncommit, idle regions trim
# gradually back toward Initial during quiet hours; the take-back
# cliff that previously made this risky has been addressed at the
# allocation source (DT cleanup, SBOM reconcile, DT-fetch loops all
# use UUID-only retrieval / SQL UPDATE / per-iteration findById
# instead of bulk Artifact loads that ballooned the persistence
# context). Leaving ~1.2 GB for metaspace + code cache + thread
# stacks + direct buffers + GC overhead + native libs. If the pod
# limit is later bumped (e.g. to 5–6 GB), heap scales automatically
# without re-editing this script.
#
# Garbage collector (-XX:+UseZGC): Java 25's Generational ZGC for
# sub-millisecond pauses regardless of heap size, with continuous
# concurrent uncommit baked in (-XX:ZUncommitDelay default 300s).
# The G1-specific -XX:G1PeriodicGCInterval /
# -XX:+G1PeriodicGCInvokesConcurrent flags are not applicable to ZGC
# and not needed. ZGC also helps with pause-time profile: the
# every-minute scheduler tick no longer competes with request threads
# for stop-the-world windows.
#
# Direct memory (-XX:MaxDirectMemorySize): explicit 512 MB cap. Defaults
# to Xmx, which silently lets Netty/WebClient consume GBs of off-heap
# memory that count against the pod RSS. A surprise direct-buffer
# allocation could otherwise OOM-kill the pod with no Java OOM in the
# logs. 512 MB is generous for our WebFlux/WebClient footprint; if it
# trips it'll surface as a clear native OOM, easy to spot and tune.
#
# Code cache (-XX:ReservedCodeCacheSize): default is 240 MB; the JIT
# uses well under half on this app. Trim to 160 MB to free ~80 MB of
# native memory.
#
# Metaspace stays at 256 MB — observed usage 180–230 MB on Java 21; if
# Java 25 shifts this we'll see it in GC logs and revisit.
#
# OOM behavior (-XX:+ExitOnOutOfMemoryError + -XX:+CrashOnOutOfMemoryError):
# without these, an OutOfMemoryError on one thread is logged and
# swallowed by Spring's DispatcherServlet — the JVM keeps running but
# the heap is exhausted, so every subsequent allocation fails. The
# pod stays "Ready" (Tomcat's listener is still up so TCP probes
# pass) while serving 500s and timing out, a zombie that K8s won't
# restart on its own. ExitOnOutOfMemoryError halts the JVM
# immediately (exit 1); CrashOnOutOfMemoryError additionally writes
# an hs_err_pid log to /app before exit for forensics. K8s then
# restarts the pod within ~10–15s.
cd /app
java -Djava.security.egd=file:/dev/./urandom \
     -Dlog4j2.formatMsgNoLookups=true \
     -XX:+ShowCodeDetailsInExceptionMessages \
     -XX:+ExitOnOutOfMemoryError \
     -XX:+CrashOnOutOfMemoryError \
     -XX:+UseZGC \
     -XX:InitialRAMPercentage=25 \
     -XX:MaxRAMPercentage=70 \
     -XX:MaxDirectMemorySize=512m \
     -XX:MaxMetaspaceSize=256m \
     -XX:ReservedCodeCacheSize=160m \
     org.springframework.boot.loader.launch.JarLauncher
