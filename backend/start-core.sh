#!/bin/sh
cd /app
java -Djava.security.egd=file:/dev/./urandom -Dlog4j2.formatMsgNoLookups=true -XX:+ShowCodeDetailsInExceptionMessages -Xms512m -Xmx3g -XX:MaxMetaspaceSize=256m org.springframework.boot.loader.launch.JarLauncher