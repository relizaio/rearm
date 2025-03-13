From golang:1.20.4-alpine3.18@sha256:0a03b591c358a0bb02e39b93c30e955358dadd18dc507087a3b7f3912c17fe13 as build-stage
WORKDIR /build
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
COPY ./internal/imports ./internal/imports
RUN go build ./internal/imports
COPY . .
RUN go version
RUN go build -o ./ ./...

FROM alpine:3.18.0@sha256:c0669ef34cdc14332c0f1ab0c2c01acb91d96014b172f1a76f3a39e63d1f0bda as release-stage
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

LABEL org.opencontainers.image.revision $GIT_COMMIT
LABEL git_branch $GIT_BRANCH
LABEL ci_environment $CI_ENV
LABEL org.opencontainers.image.version $VERSION
EXPOSE 8083
ENTRYPOINT ["/app/app"]