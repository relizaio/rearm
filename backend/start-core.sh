#!/bin/sh
# JVM memory tuning for a ~4000 MB pod limit.
#
# GC: switched from G1 to generational ZGC on Java 25. Reasons:
#   * Sub-millisecond max pause times — predictable latency under load.
#   * Concurrent class unloading + thread-stack scanning means no
#     stop-the-world phases growing with heap size.
#   * Native uncommit (-XX:+ZUncommit, on by default) hands unused
#     committed memory back to the OS automatically; ZUncommitDelay
#     controls how long a region must stay idle before it's released.
# Generational mode (-XX:+ZGenerational) is the default on Java 25 so
# we don't have to opt in explicitly.
#
# Heap (-XX:InitialRAMPercentage/MaxRAMPercentage): container-aware
# sizing. At a 4 GB pod limit ~2.8 GB heap, leaving ~1.2 GB for
# metaspace + code cache + thread stacks + direct buffers + ZGC
# overhead + native libs. If the pod limit is bumped, heap scales
# automatically without re-editing this script.
#
# Direct memory (-XX:MaxDirectMemorySize): explicit 512 MB cap.
# Defaults to Xmx, which silently lets Netty/WebClient consume GBs of
# off-heap memory that count against the pod RSS. 512 MB is generous
# for our WebFlux/WebClient footprint; if it trips it'll surface as a
# clear native OOM, easy to spot and tune.
#
# Code cache (-XX:ReservedCodeCacheSize): default is 240 MB; JIT uses
# well under half. Trim to 160 MB to free ~80 MB of native memory.
#
# Metaspace stays at 256 MB — current observed usage is 180–230 MB and
# GC logs haven't shown metaspace pressure.
#
# ZUncommitDelay (-XX:ZUncommitDelay=300): seconds a heap region must
# stay idle before ZGC releases it back to the OS. Mirrors the 5-min
# idle window the old G1PeriodicGCInterval=300000ms targeted, so the
# RSS-drop behaviour during quiet periods is preserved.
cd /app
java -Djava.security.egd=file:/dev/./urandom \
     -Dlog4j2.formatMsgNoLookups=true \
     -XX:+ShowCodeDetailsInExceptionMessages \
     -XX:+UseZGC \
     -XX:InitialRAMPercentage=25 \
     -XX:MaxRAMPercentage=70 \
     -XX:MaxDirectMemorySize=512m \
     -XX:MaxMetaspaceSize=256m \
     -XX:ReservedCodeCacheSize=160m \
     -XX:ZUncommitDelay=300 \
     org.springframework.boot.loader.launch.JarLauncher
