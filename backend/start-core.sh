#!/bin/sh
# JVM memory tuning for a ~4000 MB pod limit.
#
# Heap (-XX:InitialRAMPercentage/MaxRAMPercentage): switched from fixed
# -Xms/-Xmx to container-aware sizing so heap rides with the cgroup
# limit. At a 4 GB pod limit this gives ~2.8 GB heap, leaving ~1.2 GB
# for metaspace + code cache + thread stacks + direct buffers + GC
# overhead + native libs. If the pod limit is later bumped (e.g. to
# 5–6 GB), heap scales automatically without re-editing this script.
#
# Direct memory (-XX:MaxDirectMemorySize): explicit 512 MB cap. Defaults
# to Xmx, which silently lets Netty/WebClient consume GBs of off-heap
# memory that count against the pod RSS. A surprise direct-buffer
# allocation could otherwise OOM-kill the pod with no Java OOM in the
# logs. 512 MB is generous for our WebFlux/WebClient footprint; if it
# trips it'll surface as a clear native OOM, easy to spot and tune.
#
# Periodic concurrent GC (-XX:G1PeriodicGCInterval / +G1PeriodicGCInvokesConcurrent):
# G1 normally holds committed regions between the every-minute scheduler
# ticks. With these flags it runs a concurrent (no stop-the-world) GC
# during idle windows and uncommits unused regions back to the OS.
# Drops pod RSS naturally during quiet periods. 300000 ms = 5 min.
#
# Code cache (-XX:ReservedCodeCacheSize): default is 240 MB; the JIT
# uses well under half on this app. Trim to 160 MB to free ~80 MB of
# native memory.
#
# Metaspace stays at 256 MB — current observed usage is 180–230 MB and
# GC logs haven't shown metaspace pressure.
cd /app
java -Djava.security.egd=file:/dev/./urandom \
     -Dlog4j2.formatMsgNoLookups=true \
     -XX:+ShowCodeDetailsInExceptionMessages \
     -XX:InitialRAMPercentage=25 \
     -XX:MaxRAMPercentage=70 \
     -XX:MaxDirectMemorySize=512m \
     -XX:MaxMetaspaceSize=256m \
     -XX:ReservedCodeCacheSize=160m \
     -XX:G1PeriodicGCInterval=300000 \
     -XX:+G1PeriodicGCInvokesConcurrent \
     org.springframework.boot.loader.launch.JarLauncher