# The JDK jlink runs from: Temurin 25.0.5.1+2 for alpine-linux, which fixes CVE-2026-114938 (java4shell), from the
# Adoptium archive, because no eclipse-temurin image carries it yet; go back to eclipse-temurin:25-jdk-alpine once
# one of its tags carries 25.0.5.1 or later. The archive must match its pinned sha256 and carry a signature made by
# the Adoptium signing key kept in ci/adoptium/adoptium.asc (no keyserver at build time), and IMPLEMENTOR_VERSION
# must name JDK_RELEASE; the jlinked runtime must be 25.0.5.1 or later. To move to a newer build, take link and
# checksum for x64 and aarch64 from
# https://api.adoptium.net/v3/assets/release_name/eclipse/<release>?image_type=jdk&os=alpine-linux
# (the signature is the link plus .sig).
FROM alpine:3.24.4@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS jre-build
ARG TARGETARCH
ARG JDK_RELEASE=jdk-25.0.5.1+2
ARG JDK_URL_AMD64=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jdk_x64_alpine-linux_hotspot_25.0.5.1_2.tar.gz
ARG JDK_SHA256_AMD64=dbd75c6332ad995d20b6e25624b84652ef332d6babee4e9ddac3f36ee38d57be
ARG JDK_URL_ARM64=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jdk_aarch64_alpine-linux_hotspot_25.0.5.1_2.tar.gz
ARG JDK_SHA256_ARM64=0501ef55ea9ec19b14987def7a73e044cd1748f7f3b97237b49007d387a84a1a
ARG ADOPTIUM_GPG_FINGERPRINT=3B04D753C9050D9A5D343F39843C48A565F8F04B
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
COPY ci/adoptium/adoptium.asc /tmp/adoptium.asc
RUN case "${TARGETARCH:-amd64}" in \
        amd64) url="${JDK_URL_AMD64}" sha="${JDK_SHA256_AMD64}" ;; \
        arm64) url="${JDK_URL_ARM64}" sha="${JDK_SHA256_ARM64}" ;; \
        *) echo "jre-build: no Temurin JDK pinned for TARGETARCH ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && apk add --no-cache gnupg \
 && wget -q -O /tmp/jdk.tar.gz "${url}" \
 && wget -q -O /tmp/jdk.tar.gz.sig "${url}.sig" \
 && echo "${sha}  /tmp/jdk.tar.gz" | sha256sum -c - \
 && export GNUPGHOME="$(mktemp -d)" \
 && gpg --batch --import /tmp/adoptium.asc \
 && gpg --batch --status-fd 1 --verify /tmp/jdk.tar.gz.sig /tmp/jdk.tar.gz | grep -q "^\[GNUPG:\] VALIDSIG .* ${ADOPTIUM_GPG_FINGERPRINT}\$" \
 && mkdir -p "${JAVA_HOME}" \
 && tar -xzf /tmp/jdk.tar.gz -C "${JAVA_HOME}" --strip-components=1 --no-same-owner \
 && grep -qx "IMPLEMENTOR_VERSION=\"Temurin-${JDK_RELEASE#jdk-}\"" "${JAVA_HOME}/release" \
 && gpgconf --kill all \
 && rm -rf "${GNUPGHOME}" /tmp/jdk.tar.gz /tmp/jdk.tar.gz.sig /tmp/adoptium.asc
# Create a custom Java runtime
# to get list of java modules, use the following from target/ directory: 
# jdeps -R -s --multi-release 16 -cp '.\unjar\BOOT-INF\lib\*' .\rearm-core-Version_Managed_By_CI_AND_Reliza.jar

# October 2026 eMudhra CA compromise: remove emSign ECC Root CA - G3 and AAA Certificate
# Services from this JDK's cacerts by SHA-256 fingerprint (ci/trust-gate/jdk-distrust.sh).
COPY ci/trust-gate/jdk-distrust.sh /gate/
RUN sh /gate/jdk-distrust.sh
# jlink copies the edited lib/security/cacerts into /javaruntime.
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.compiler,java.sql,java.sql.rowset,java.xml,java.logging,java.net.http,java.naming,java.security.jgss,java.datatransfer,java.desktop,java.management,java.instrument,jdk.unsupported,jdk.crypto.ec \
         --strip-java-debug-attributes \
         --no-man-pages \
         --no-header-files \
         --compress=zip-9 \
         --output /javaruntime
# CVE-2026-114938 (java4shell): start-core.sh no longer turns off Base64 intrinsics, so the build fails unless the
# jlinked runtime is 25.0.5.1 or later.
RUN v=$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' /javaruntime/release) && \
    { [ -n "$v" ] || { echo "cannot read JAVA_VERSION from /javaruntime/release" >&2; exit 1; }; } && \
    { [ "$(printf '%s\n' 25.0.5.1 "$v" | sort -V | head -n1)" = 25.0.5.1 ] || \
      { echo "jlinked runtime java $v is older than 25.0.5.1 (CVE-2026-114938)" >&2; exit 1; }; }

FROM maven:3.9.16-eclipse-temurin-25-alpine@sha256:781f1da87cd3d142e66e47cc149c4582a652fb7f574648206d1e956170c0dc90 AS build-cache-stage
# October 2026 eMudhra CA compromise: remove emSign ECC Root CA - G3 and AAA Certificate
# Services from this JDK's cacerts by SHA-256 fingerprint (ci/trust-gate/jdk-distrust.sh).
COPY ci/trust-gate/jdk-distrust.sh /gate/
RUN sh /gate/jdk-distrust.sh
RUN mkdir /workdir
WORKDIR /workdir

COPY ./pom.xml .
RUN mvn -B dependency:go-offline -T1C 

FROM maven:3.9.16-eclipse-temurin-25-alpine@sha256:781f1da87cd3d142e66e47cc149c4582a652fb7f574648206d1e956170c0dc90 AS build-stage
# October 2026 eMudhra CA compromise: remove emSign ECC Root CA - G3 and AAA Certificate
# Services from this JDK's cacerts by SHA-256 fingerprint (ci/trust-gate/jdk-distrust.sh).
COPY ci/trust-gate/jdk-distrust.sh /gate/
RUN sh /gate/jdk-distrust.sh
RUN mkdir -p /root/.m2/repository/com
COPY --from=build-cache-stage /root/.m2 /root/.m2

RUN apk add postgresql postgresql-contrib
# FROM relizaio/maven-postgresql AS build-stage
ARG VERSION=not_versioned
ARG TARGETARCH
# Test phase control. SKIP_TESTS=true|false forces it; the default "auto" runs the
# tests when GIT_BRANCH is main or master, or when no branch is given (local builds),
# and skips them (Maven -DskipTests, tests still compile) for every other branch, so
# PR and feature builds do not pay for the suite while main stays fully tested.
# GIT_BRANCH is already passed by rearm-docker-action; SKIP_TESTS needs no workflow change.
ARG SKIP_TESTS=auto
ARG GIT_BRANCH=git_branch_undefined
RUN mkdir /run/postgresql && chown postgres:postgres /run/postgresql/ && su - postgres -c "mkdir /var/lib/postgresql/data && chmod 0700 /var/lib/postgresql/data && initdb -D /var/lib/postgresql/data && echo \"port = 5440\" > /var/lib/postgresql/data/postgresql.conf && sed -i \"s#/run/postgresql#/tmp#g\" /var/lib/postgresql/data/postgresql.conf" && su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && su - postgres -c "psql -p 5440 -c \"alter role postgres with encrypted password 'relizaPass'\"" && su - postgres -c "pg_ctl -D /var/lib/postgresql/data stop"
RUN mkdir /workdir
WORKDIR /workdir
COPY ./ .
# The Java probe of the trust-gate stage, compiled with this JDK 25 for the jlink 25 runtime.
RUN javac -d /gate ci/trust-gate/TrustGate.java
# following line injects desired version into pom for proper logging.
# Slashes in $VERSION (e.g. from `feat/foo` branch names) get treated
# as path separators by Maven's jar-naming, which produces
# `target/rearm-pro-backend-feat/foo.0.jar` (subdir!) and breaks the
# `cp target/*.jar` line below. Normalize to a single token first.
RUN VERSION_SAFE="$(echo $VERSION | tr '/' '-')" && \
    sed -i "s,Version_Managed_By_CI_AND_Reliza,$VERSION_SAFE," pom.xml

RUN SKIP="$SKIP_TESTS" && if [ "$SKIP" = "auto" ]; then case "$GIT_BRANCH" in main|master|git_branch_undefined) SKIP=false ;; *) SKIP=true ;; esac; fi && \
    echo "SKIP_TESTS=$SKIP_TESTS GIT_BRANCH=$GIT_BRANCH -> skipTests=$SKIP" && \
    su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && mvn -B -T1C clean package spring-boot:repackage -DskipTests=$SKIP && cp target/*.jar application.jar && java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a AS artifact-stage
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN apk upgrade --no-cache ca-certificates ca-certificates-bundle && apk info -e -v ca-certificates-bundle
COPY --from=jre-build /javaruntime $JAVA_HOME
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
RUN mkdir /app
RUN echo "version=$VERSION" > /app/version && echo "commit=$GIT_COMMIT" >> /app/version && echo "branch=$GIT_BRANCH" >> /app/version
RUN addgroup -S apprunner && adduser -S apprunner -G apprunner && chown apprunner:apprunner -R /app
USER apprunner
COPY --from=build-stage --chown=apprunner:apprunner /workdir/extracted/dependencies/ /app/
COPY --from=build-stage --chown=apprunner:apprunner /workdir/extracted/snapshot-dependencies/ /app/
# COPY --from=build-stage --chown=apprunner:apprunner /workdir/extracted/resources/ /app/
COPY --from=build-stage --chown=apprunner:apprunner /workdir/extracted/application/ /app/
COPY --from=build-stage --chown=apprunner:apprunner /workdir/extracted/spring-boot-loader/ /app/
RUN mkdir /app/config
COPY --chown=apprunner:apprunner ./application.yaml /app/config/
COPY --chown=apprunner:apprunner ./start-core.sh /app/
RUN chmod 0700 /app/start-core.sh
ENV PG_PORT=5440
ENV PG_HOST=172.17.0.1
ENV PG_PASS=relizaPass
ENV PROJECT_PROTOCOL=http
ENV PROJECT_HOST=localhost:8086
ENV IS_PLAYGROUND=false
LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL version=$VERSION
CMD ["/app/start-core.sh"]

# Trust-store gate (October 2026 eMudhra CA compromise): the Alpine bundle and the jlink
# runtime's cacerts must refuse the compromised chains. release copies the marker, so this
# stage always builds.
FROM artifact-stage AS trust-gate
USER root
RUN apk add --no-cache curl openssl
COPY ci/trust-gate/ /gate/
COPY --from=build-stage /gate/TrustGate.class /gate/
RUN sh /gate/trust-gate.sh && java -cp /gate TrustGate && echo "trust-gate ok $(date -u +%FT%TZ)" > /gate/ok

FROM artifact-stage AS release
COPY --from=trust-gate /gate/ok /trust-gate-ok
