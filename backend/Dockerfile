FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS jre-build
# Create a custom Java runtime
# to get list of java modules, use the following from target/ directory: 
# jdeps -R -s --multi-release 16 -cp '.\unjar\BOOT-INF\lib\*' .\rearm-core-Version_Managed_By_CI_AND_Reliza.jar

RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.compiler,java.sql,java.sql.rowset,java.xml,java.logging,java.net.http,java.naming,java.security.jgss,java.datatransfer,java.desktop,java.management,java.instrument,jdk.unsupported,jdk.crypto.ec \
         --strip-java-debug-attributes \
         --no-man-pages \
         --no-header-files \
         --compress=zip-9 \
         --output /javaruntime

FROM maven:3.9.16-eclipse-temurin-25-alpine@sha256:72e2d64836e659d053a573ac9ebab05b78ae78fa7bb69b7452a7cb877b465fc7 AS build-cache-stage
RUN mkdir /workdir
WORKDIR /workdir

COPY ./pom.xml .
RUN mvn -B dependency:go-offline -T1C 

FROM maven:3.9.16-eclipse-temurin-25-alpine@sha256:72e2d64836e659d053a573ac9ebab05b78ae78fa7bb69b7452a7cb877b465fc7 AS build-stage
RUN mkdir -p /root/.m2/repository/com
COPY --from=build-cache-stage /root/.m2 /root/.m2

RUN apk add postgresql postgresql-contrib
# FROM relizaio/maven-postgresql AS build-stage
ARG VERSION=not_versioned
ARG TARGETARCH
RUN mkdir /run/postgresql && chown postgres:postgres /run/postgresql/ && su - postgres -c "mkdir /var/lib/postgresql/data && chmod 0700 /var/lib/postgresql/data && initdb -D /var/lib/postgresql/data && echo \"port = 5440\" > /var/lib/postgresql/data/postgresql.conf && sed -i \"s#/run/postgresql#/tmp#g\" /var/lib/postgresql/data/postgresql.conf" && su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && su - postgres -c "psql -p 5440 -c \"alter role postgres with encrypted password 'relizaPass'\"" && su - postgres -c "pg_ctl -D /var/lib/postgresql/data stop"
RUN mkdir /workdir
WORKDIR /workdir
COPY ./ .
# following line injects desired version into pom for proper logging.
# Slashes in $VERSION (e.g. from `feat/foo` branch names) get treated
# as path separators by Maven's jar-naming, which produces
# `target/rearm-pro-backend-feat/foo.0.jar` (subdir!) and breaks the
# `cp target/*.jar` line below. Normalize to a single token first.
RUN VERSION_SAFE="$(echo $VERSION | tr '/' '-')" && \
    sed -i "s,Version_Managed_By_CI_AND_Reliza,$VERSION_SAFE," pom.xml

RUN su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && mvn -B -T1C clean package spring-boot:repackage && cp target/*.jar application.jar && java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b  AS artifact-stage
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
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