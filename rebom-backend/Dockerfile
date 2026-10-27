# Node stays on 26.7.0 here (TPF-80 risk 1 fallback): the 26.12.1 binary needs libatomic.so.1,
# which the plain alpine deliverable below does not carry (Error loading shared library
# libatomic.so.1, needed by /usr/local/bin/node). The interim NODE_OPTIONS/SSL_CERT_FILE closes
# the trust window on its own; the 26.12.1 bump is the follow-up.
FROM node:26.7.0-alpine3.24@sha256:aadf416b2cdce311a8811ba3f0608a61b77dbf997500e2eafe781b51f6a0b019 AS builder

ARG TARGETARCH
ARG TARGETOS
# The rearm CLI release rebom runs (bomutils merge-boms, bomutils score). To move to a newer
# release: change this value and the rearm zip line of tools.amd64.sha256 and tools.arm64.sha256
# (hashes from <cdn>/rearm-download/<version>/sha256sums.txt, SCORE-7 design 3.5). The engine
# checks in the tools RUN fail the build on a zip without `bomutils score` or with an older engine:
# the fda report on test/fixtures/score/full.self.cdx.json must carry both "skippedTypes" (SCORE-20)
# and "selfComponentsSkipped" (SCORE-59; pinned by SCORE-60 ADR-2). The merge of the two
# engine-check fixtures (test/fixtures/merge/engine-check-a.cdx.json and engine-check-b.cdx.json)
# must print `folds into root` on stderr (SCORE-67; pinned by SCORE-68 ADR-2).
ARG REARM_CLI_VERSION=26.10.15

# October 2026 eMudhra CA compromise: the fixed Alpine bundle, and Node on it (--use-openssl-ca)
# until FIXED_NODE is set in ci/trust-gate/trust-gate.mjs.
RUN apk upgrade --no-cache ca-certificates ca-certificates-bundle && apk info -e -v ca-certificates-bundle
ENV NODE_OPTIONS=--use-openssl-ca SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt

# Install PostgreSQL and unzip for running tests (same approach as the ReARM backend)
RUN apk add postgresql postgresql-contrib unzip wget

# Initialize PostgreSQL database for tests
RUN mkdir /run/postgresql && \
    chown postgres:postgres /run/postgresql/ && \
    su - postgres -c "mkdir /var/lib/postgresql/data && \
                      chmod 0700 /var/lib/postgresql/data && \
                      initdb -D /var/lib/postgresql/data && \
                      echo \"port = 5438\" > /var/lib/postgresql/data/postgresql.conf && \
                      sed -i \"s#/run/postgresql#/tmp#g\" /var/lib/postgresql/data/postgresql.conf" && \
    su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && \
    su - postgres -c "psql -p 5438 -c \"alter role postgres with encrypted password 'password'\"" && \
    su - postgres -c "pg_ctl -D /var/lib/postgresql/data stop"

RUN mkdir /app
WORKDIR /app

# Download rearm-cli and cyclonedx-cli before tests (needed for merge/diff operations)
COPY tools.${TARGETARCH}.sha256 /tmp/tools.sha256
COPY test/fixtures/score/full.self.cdx.json /tmp/engine-check.cdx.json
COPY test/fixtures/merge/engine-check-a.cdx.json /tmp/engine-check-a.cdx.json
COPY test/fixtures/merge/engine-check-b.cdx.json /tmp/engine-check-b.cdx.json
RUN wget https://d7ge14utcyki8.cloudfront.net/rearm-download/${REARM_CLI_VERSION}/rearm-${REARM_CLI_VERSION}-linux-${TARGETARCH}.zip && \
    unzip rearm-${REARM_CLI_VERSION}-linux-${TARGETARCH}.zip && \
    if [ "$TARGETARCH" = "amd64" ]; then \
        wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.32.0/cyclonedx-linux-musl-x64 ;\
        mv cyclonedx-linux-musl-x64 /usr/local/bin/cyclonedx-cli ;\
    elif [ "$TARGETARCH" = "arm64" ]; then \
        wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.32.0/cyclonedx-${TARGETOS}-${TARGETARCH} ;\
        mv cyclonedx-${TARGETOS}-${TARGETARCH} /usr/local/bin/cyclonedx-cli ;\
    fi && \
    sha256sum -c /tmp/tools.sha256 && \
    mv rearm /usr/local/bin/rearm && \
    chmod +x /usr/local/bin/rearm && \
    chmod +x /usr/local/bin/cyclonedx-cli && \
    rearm bomutils score --help | grep -q -- '--profile' && \
    rearm bomutils score --format json --profile fda --infile /tmp/engine-check.cdx.json > /tmp/engine-check.json && \
    grep -q '"skippedTypes"' /tmp/engine-check.json && \
    grep -q '"selfComponentsSkipped"' /tmp/engine-check.json && \
    rearm bomutils merge-boms --name engine-check --version 1.0.0 --input-files /tmp/engine-check-a.cdx.json --input-files /tmp/engine-check-b.cdx.json --outfile /tmp/engine-check-merged.json 2> /tmp/engine-check-merge.err && \
    test -s /tmp/engine-check-merged.json && \
    grep -q 'folds into root' /tmp/engine-check-merge.err && \
    rm -f /tmp/engine-check.cdx.json /tmp/engine-check.json /tmp/engine-check-a.cdx.json /tmp/engine-check-b.cdx.json /tmp/engine-check-merged.json /tmp/engine-check-merge.err

# cyclonedx-cli has no musl build for arm64 (v0.32.0 publishes cyclonedx-linux-musl-x64 and
# cyclonedx-linux-musl-arm only); the arm64 pin is the glibc build and needs gcompat on alpine
# (SCORE-7 design round 3, ADR-8). Nothing is installed on amd64.
RUN if [ "$TARGETARCH" = "arm64" ]; then apk add --no-cache gcompat; fi

COPY package*.json ./
RUN npm ci --ignore-scripts
COPY ./ .
# Trust-store probe: the node that ran npm ci refuses the compromised chains.
RUN node ci/trust-gate/trust-gate.mjs

# Run ALL tests (unit + integration) with PostgreSQL running
ENV MOCK_OCI=true
ENV POSTGRES_HOST=localhost
ENV POSTGRES_PORT=5438
ENV POSTGRES_USER=postgres
ENV POSTGRES_PASSWORD=password
ENV POSTGRES_DATABASE=postgres

# Start PostgreSQL, create schema, run migrations, run tests, then stop PostgreSQL
RUN su - postgres -c "pg_ctl -D /var/lib/postgresql/data start" && \
    sleep 2 && \
    su - postgres -c "psql -p 5438 -d postgres -c 'CREATE SCHEMA IF NOT EXISTS rebom;'" && \
    su - postgres -c "psql -p 5438 -d postgres -c 'SET search_path TO rebom, public;'" && \
    for migration in /app/migrations/*.sql; do \
        echo "Running migration: $migration"; \
        su - postgres -c "psql -p 5438 -d postgres -v ON_ERROR_STOP=1 -c 'SET search_path TO rebom, public;' -f $migration"; \
    done && \
    npm test && \
    su - postgres -c "pg_ctl -D /var/lib/postgresql/data stop"

# Build after tests pass
RUN npm run build

# Production node_modules, installed here because the deliverable stage
# deliberately has no npm (see below)
RUN mkdir /prod && cp package.json package-lock.json /prod/ && \
    cd /prod && npm ci --ignore-scripts --omit=dev

# Runtime base is plain alpine, NOT node:alpine: the app only needs the node
# BINARY (copied from the builder, so it is the same official node 26 build),
# while node:alpine also ships the npm CLI in a base layer we cannot remove --
# and npm's vendored tree (tar, brace-expansion, ip-address, undici) is where
# every npm-ecosystem CVE in this image's scans has come from. With no npm in
# any layer, that finding class is gone structurally, not just until the next
# npm release. Runtime deps stay installable via apk (gpg / ssh-keygen for
# signature verification, ICU for cyclonedx-cli); libstdc++ is for node itself.
# On arm64 only, gcompat lets the glibc-linked cyclonedx-cli start on musl (no musl
# arm64 build exists; SCORE-7 design round 3, ADR-8).
FROM alpine:3.24.3@sha256:b3413572228e34bd1c9245f5565e6cd127005655e8a7899aef33d8e83255c9e6 AS deliverable

ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
ARG TARGETARCH

COPY --from=builder /usr/local/bin/node /usr/local/bin/node
COPY --from=builder /usr/local/bin/cyclonedx-cli /usr/local/bin/
COPY --from=builder /usr/local/bin/rearm /usr/local/bin/

# NODE_OPTIONS and SSL_CERT_FILE: Node reads the fixed Alpine bundle (October 2026 eMudhra CA
# compromise) until FIXED_NODE is set in ci/trust-gate/trust-gate.mjs.
ENV \
    DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=false \
    LC_ALL=en_US.UTF-8 \
    LANG=en_US.UTF-8 \
    NODE_OPTIONS=--use-openssl-ca \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
RUN apk upgrade --no-cache ca-certificates ca-certificates-bundle && apk info -e -v ca-certificates-bundle && \
    apk add --no-cache \
    libstdc++ \
    icu-data-full \
    icu-libs \
    openssh-keygen \
    gnupg && \
    if [ "$TARGETARCH" = "arm64" ]; then apk add --no-cache gcompat; fi && \
    mkdir /app && echo "version=$VERSION" > /app/version && echo "commit=$GIT_COMMIT" >> /app/version && echo "branch=$GIT_BRANCH" >> /app/version

WORKDIR /app

RUN addgroup -S apprunner && adduser -S apprunner -G apprunner && chown apprunner:apprunner -R /app && chown apprunner:apprunner /usr/local/bin/cyclonedx-cli && chmod a+x /usr/local/bin/cyclonedx-cli 

COPY --chown=apprunner:apprunner package*.json ./
COPY --from=builder --chown=apprunner:apprunner /app/dist ./
COPY --from=builder --chown=apprunner:apprunner /prod/node_modules ./node_modules
COPY --chown=apprunner:apprunner migrations /app/migrations

USER apprunner

LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL version=$VERSION

EXPOSE 4000

CMD ["node", "src/index.js"]

# Trust-store gate (October 2026 eMudhra CA compromise): the shipped store must refuse the
# compromised chains. release copies the marker, so this stage always builds.
FROM deliverable AS trust-gate
USER root
RUN apk add --no-cache curl openssl
COPY ci/trust-gate/ /gate/
RUN sh /gate/trust-gate.sh && node /gate/trust-gate.mjs && echo "trust-gate ok $(date -u +%FT%TZ)" > /gate/ok

FROM deliverable AS release
COPY --from=trust-gate /gate/ok /trust-gate-ok
