FROM golang:1.26.5-alpine3.24@sha256:0178a641fbb4858c5f1b48e34bdaabe0350a330a1b1149aabd498d0699ff5fb2 AS build-stage
WORKDIR /build
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
COPY ./internal/imports ./internal/imports
RUN go build ./internal/imports
COPY . .
RUN go version
RUN go build -o ./ ./...

FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS release-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
RUN mkdir /app
RUN adduser -u 1000 -D apprunner && chown apprunner:apprunner /app
COPY --from=build-stage --chown=apprunner:apprunner /build/reliza-oci-artifacts-client /app/app
RUN mkdir /indir && chown apprunner:apprunner -R /indir
RUN mkdir /outdir && chown apprunner:apprunner -R /outdir
USER apprunner
RUN echo "version=$VERSION" > /app/version && echo "commit=$GIT_COMMIT" >> /app/version && echo "branch=$GIT_BRANCH" >> /app/version
RUN mkdir /app/localdata

LABEL org.opencontainers.image.revision=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL org.opencontainers.image.version=$VERSION
EXPOSE 8083
ENTRYPOINT ["/app/app"]