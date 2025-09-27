FROM node:24.9.0-alpine3.22@sha256:77f3c4d1f33c17dfa4af4b0add57d86957187873e313c2c37f52831d117645c8 AS builder

ARG TARGETARCH
ARG TARGETOS

RUN mkdir /app
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts
COPY ./ .
RUN npm run build


RUN if [ "$TARGETARCH" = "amd64" ]; then \
        wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.29.1/cyclonedx-linux-musl-x64 ;\
        mv cyclonedx-linux-musl-x64 /usr/local/bin/cyclonedx-cli ;\
    elif [ "$TARGETARCH" = "arm64" ]; then \
        wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.29.1/cyclonedx-${TARGETOS}-${TARGETARCH} ;\
        mv cyclonedx-${TARGETOS}-${TARGETARCH} /usr/local/bin/cyclonedx-cli ;\
fi

ADD https://d7ge14utcyki8.cloudfront.net/rearm-download/25.09.1/rearm-25.09.1-${TARGETOS}-${TARGETARCH}.zip rearm-25.09.1-${TARGETOS}-${TARGETARCH}.zip

RUN sha256sum -c tools.${TARGETARCH}.sha256
RUN unzip rearm-25.09.1-${TARGETOS}-${TARGETARCH}.zip
RUN mv rearm /usr/local/bin/rearm-cli

FROM node:24.9.0-alpine3.22@sha256:77f3c4d1f33c17dfa4af4b0add57d86957187873e313c2c37f52831d117645c8 AS package

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

RUN npm ci --omit=dev

LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL version=$VERSION

EXPOSE 4000

CMD ["node", "index.js"]