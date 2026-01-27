#!/bin/sh
cd /app
java -Djava.security.egd=file:/dev/./urandom -Dlog4j2.formatMsgNoLookups=true -XX:+ShowCodeDetailsInExceptionMessages org.springframework.boot.loader.launch.JarLauncher -Xms1g -Xmx3g