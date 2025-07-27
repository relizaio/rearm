FROM eclipse-temurin:21.0.7_6-jdk-alpine-3.21@sha256:8e26f8064f2b89bc8543faf43ded3f223f47dd0a7afca042fdc892f1f6a4a8c3 AS jre-build
# Create a custom Java runtime
# to get list of java modules, use the following from target/ directory: 
# jdeps -R -s --multi-release 16 -cp '.\unjar\BOOT-INF\lib\*' .\rearm-core-Version_Managed_By_CI_AND_Reliza.jar

RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.sql,java.sql.rowset,java.xml,java.logging,java.net.http,java.naming,java.security.jgss,java.datatransfer,java.desktop,java.management,java.instrument,jdk.unsupported,jdk.crypto.ec \
         --strip-java-debug-attributes \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime

FROM maven:3.9.11-eclipse-temurin-21-alpine@sha256:81e62c3fbb981e0d6b3ea97d870a3edf3adc49467337fc70d07db5f3335f22be AS build-cache-stage
RUN mkdir /workdir
WORKDIR /workdir

COPY ./pom.xml .
RUN mvn -B dependency:go-offline -T1C 

FROM maven:3.9.11-eclipse-temurin-21-alpine@sha256:81e62c3fbb981e0d6b3ea97d870a3edf3adc49467337fc70d07db5f3335f22be AS build-stage
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
# following line injects desired version into pom for proper logging
RUN sed -i "s,Version_Managed_By_CI_AND_Reliza,$VERSION," pom.xml

RUN su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && mvn -B clean package spring-boot:repackage && cp target/*.jar application.jar && java -Djarmode=layertools -jar application.jar extract

FROM alpine:3.22.1@sha256:4bcff63911fcb4448bd4fdacec207030997caf25e9bea4045fa6c8c44de311d1 AS artifact-stage
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
COPY --from=build-stage --chown=apprunner:apprunner /workdir/dependencies/ /app/
COPY --from=build-stage --chown=apprunner:apprunner /workdir/snapshot-dependencies/ /app/
# COPY --from=build-stage --chown=apprunner:apprunner /workdir/resources/ /app/
COPY --from=build-stage --chown=apprunner:apprunner /workdir/application/ /app/
COPY --from=build-stage --chown=apprunner:apprunner /workdir/spring-boot-loader/ /app/
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