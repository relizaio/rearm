FROM golang:1.26.6-alpine3.24@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83 AS build-stage
WORKDIR /build
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
COPY ./internal/imports ./internal/imports
RUN go build ./internal/imports
COPY . .
RUN go version
RUN go build -o ./ ./...

# Skeleton for the scratch release image: the service only ever needs a
# writable /tmp (os.CreateTemp for uploads and compression), a passwd entry so
# it keeps running as uid 1000, and CA certificates for HTTPS to registries.
# Prepared here because scratch has no shell for mkdir/adduser/echo.
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
RUN apk add --no-cache ca-certificates && \
    mkdir -p /skel/app /skel/tmp && chmod 1777 /skel/tmp && \
    echo "version=$VERSION" > /skel/app/version && \
    echo "commit=$GIT_COMMIT" >> /skel/app/version && \
    echo "branch=$GIT_BRANCH" >> /skel/app/version && \
    echo "apprunner:x:1000:1000:apprunner:/app:/sbin/nologin" > /skel-passwd && \
    echo "apprunner:x:1000:" > /skel-group && \
    chown -R 1000:1000 /skel/app

# The binary is static (CGO_ENABLED=0) and the service shells out to nothing,
# so the release image carries no distro: FROM scratch drops every Alpine
# package finding along with the shell and package manager. /indir, /outdir and
# /app/localdata are not referenced anywhere in the service -- they were
# inherited from the CLI's Dockerfile -- so they are dropped rather than
# recreated.
FROM scratch AS release-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
COPY --from=build-stage /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build-stage /skel-passwd /etc/passwd
COPY --from=build-stage /skel-group /etc/group
COPY --from=build-stage /skel/ /
COPY --from=build-stage --chown=1000:1000 /build/reliza-oci-artifacts-client /app/app
USER apprunner
ENV TMPDIR=/tmp HOME=/app

LABEL org.opencontainers.image.revision=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL org.opencontainers.image.version=$VERSION
EXPOSE 8083
ENTRYPOINT ["/app/app"]