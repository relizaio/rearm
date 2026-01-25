FROM node:24.12.0-alpine3.23@sha256:c921b97d4b74f51744057454b306b418cf693865e73b8100559189605f6955b8 AS builder

ARG TARGETARCH
ARG TARGETOS

# Install PostgreSQL and unzip for running tests (same approach as rearm-saas/backend)
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
RUN if [ "$TARGETARCH" = "amd64" ]; then \
        wget https://d7ge14utcyki8.cloudfront.net/rearm-download/26.01.3/rearm-26.01.3-linux-amd64.zip ;\
        unzip rearm-26.01.3-linux-amd64.zip ;\
        wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.29.1/cyclonedx-linux-musl-x64 ;\
        mv cyclonedx-linux-musl-x64 /usr/local/bin/cyclonedx-cli ;\
    elif [ "$TARGETARCH" = "arm64" ]; then \
        wget https://d7ge14utcyki8.cloudfront.net/rearm-download/26.01.3/rearm-26.01.3-linux-arm64.zip ;\
        unzip rearm-26.01.3-linux-arm64.zip ;\
        wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.29.1/cyclonedx-${TARGETOS}-${TARGETARCH} ;\
        mv cyclonedx-${TARGETOS}-${TARGETARCH} /usr/local/bin/cyclonedx-cli ;\
    fi && \
    sha256sum -c /tmp/tools.sha256 && \
    mv rearm /usr/local/bin/rearm-cli && \
    chmod +x /usr/local/bin/rearm-cli && \
    chmod +x /usr/local/bin/cyclonedx-cli

COPY package*.json ./
RUN npm ci --ignore-scripts
COPY ./ .

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

FROM node:24.12.0-alpine3.23@sha256:c921b97d4b74f51744057454b306b418cf693865e73b8100559189605f6955b8 AS package

ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned

COPY --from=builder /usr/local/bin/cyclonedx-cli /usr/local/bin/
COPY --from=builder /usr/local/bin/rearm-cli /usr/local/bin/

ENV \
    DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=false \
    LC_ALL=en_US.UTF-8 \
    LANG=en_US.UTF-8
RUN apk add --no-cache \
    icu-data-full \
    icu-libs && \
    mkdir /app && echo "version=$VERSION" > /app/version && echo "commit=$GIT_COMMIT" >> /app/version && echo "branch=$GIT_BRANCH" >> /app/version

WORKDIR /app

RUN addgroup -S apprunner && adduser -S apprunner -G apprunner && chown apprunner:apprunner -R /app && chown apprunner:apprunner /usr/local/bin/cyclonedx-cli && chmod a+x /usr/local/bin/cyclonedx-cli 

COPY --chown=apprunner:apprunner package*.json ./
COPY --from=builder --chown=apprunner:apprunner /app/dist ./
COPY --chown=apprunner:apprunner migrations /app/migrations

USER apprunner

RUN npm ci --ignore-scripts --omit=dev

LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL version=$VERSION

EXPOSE 4000

CMD ["node", "src/index.js"]