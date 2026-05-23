#!/bin/sh
# JVM memory tuning for a ~4000 MB pod limit, Java 25 + ZGC.
#
# Heap (-XX:InitialRAMPercentage/MaxRAMPercentage): container-aware
# sizing so heap rides with the cgroup limit. 25/70 split — heap can
# range from ~1 GB initial up to ~2.8 GB max at the current 4 GB pod
# size. ZGC uncommit is disabled (see below), so the heap grows from
# Initial up to its working-set high-water-mark and stays there for
# the remainder of the pod's life — no take-back cliff. Leaving
# ~1.2 GB for metaspace + code cache + thread stacks + direct buffers
# + GC overhead + native libs. If the pod limit is later bumped
# (e.g. to 5–6 GB), heap scales automatically without re-editing this
# script.
#
# Garbage collector (-XX:+UseZGC -XX:-ZUncommit): Java 25's Generational
# ZGC for sub-millisecond pauses regardless of heap size. ZGC's default
# behavior includes continuous concurrent uncommit (regions empty for
# -XX:ZUncommitDelay=300s are returned to the OS). That give-back is
# gentler than G1's periodic-spike pattern, but in practice we still
# hit take-back-cliff OOMs during quiet-hours bursts (e.g. an
# every-minute SBOM-reconcile / DT-fetch tick landing on a heap
# already trimmed near InitialRAMPercentage). -ZUncommit disables the
# uncommit step entirely: heap can still GROW from Initial up to Max
# as needed (so cold-start RSS stays small), but never shrinks within
# a single pod lifetime. Combined with the 25/70 split below this
# gives "grow-but-don't-shrink" behavior — pod RSS rises to whatever
# the workload's high-water-mark needs and stays there for the
# remainder of the pod's life. The G1-specific
# -XX:G1PeriodicGCInterval / -XX:+G1PeriodicGCInvokesConcurrent flags
# are not applicable to ZGC and not needed. ZGC also helps with
# pause-time profile: the every-minute scheduler tick no longer
# competes with request threads for stop-the-world windows.
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
cd /app
java -Djava.security.egd=file:/dev/./urandom \
     -Dlog4j2.formatMsgNoLookups=true \
     -XX:+ShowCodeDetailsInExceptionMessages \
     -XX:+UseZGC \
     -XX:-ZUncommit \
     -XX:InitialRAMPercentage=25 \
     -XX:MaxRAMPercentage=70 \
     -XX:MaxDirectMemorySize=512m \
     -XX:MaxMetaspaceSize=256m \
     -XX:ReservedCodeCacheSize=160m \
     org.springframework.boot.loader.launch.JarLauncher
