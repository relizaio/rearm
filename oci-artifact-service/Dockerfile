From golang:1.24.2-alpine3.21@sha256:7772cb5322baa875edd74705556d08f0eeca7b9c4b5367754ce3f2f00041ccee as build-stage
WORKDIR /build
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
COPY ./internal/imports ./internal/imports
RUN go build ./internal/imports
COPY . .
RUN go version
RUN go build -o ./ ./...

FROM alpine:3.21.3@sha256:2436f2b3b7d2537f4c5b622d7a820f00aaea1b6bd14c898142472947d5f02abe as release-stage
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