FROM golang:1.26.10-alpine3.24@sha256:5cb67f19c5e7994dcb80c074f7cfca8aeeada45bbdca035f73df269cefdcce61 AS build-stage
WORKDIR /build
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
COPY ./internal/imports ./internal/imports
RUN go build ./internal/imports
COPY . .
RUN go version
# Run the package tests in the build. Nothing else does: the workflow only
# invokes the image build, so a test not run here is a test that never runs.
RUN go test ./...
RUN go build -o ./ ./...

# Skeleton for the scratch release image: the service only ever needs a
# writable /tmp (os.CreateTemp for uploads and compression), a passwd entry so
# it keeps running as uid 1000, and CA certificates for HTTPS to registries.
# Prepared here because scratch has no shell for mkdir/adduser/echo.
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
RUN apk upgrade --no-cache ca-certificates ca-certificates-bundle && \
    apk add --no-cache ca-certificates && \
    mkdir -p /skel/app /skel/tmp && chmod 1777 /skel/tmp && \
    echo "version=$VERSION" > /skel/app/version && \
    echo "commit=$GIT_COMMIT" >> /skel/app/version && \
    echo "branch=$GIT_BRANCH" >> /skel/app/version && \
    echo "apprunner:x:1000:1000:apprunner:/app:/sbin/nologin" > /skel-passwd && \
    echo "apprunner:x:1000:" > /skel-group && \
    chown -R 1000:1000 /skel/app
# Trust-store gate self-test (ci/trust-gate/selftest.sh): hermetic, proves the
# offline check fails on a bundle carrying the two eMudhra-chain roots.
RUN apk add --no-cache openssl && sh ci/trust-gate/selftest.sh

# Trust-store gate (October 2026 eMudhra CA compromise): the toolchain and the
# bundle the scratch image copies must refuse the compromised chains. The
# release stage copies the marker, so this stage always builds.
FROM build-stage AS trust-gate
USER root
RUN apk add --no-cache curl openssl
COPY ci/trust-gate/ /gate/
RUN sh /gate/trust-gate.sh && go test -count=1 -tags trustgate -run TestTrustGate ./ci/trust-gate/ && echo "trust-gate ok $(date -u +%FT%TZ)" > /gate/ok

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
COPY --from=trust-gate /gate/ok /trust-gate-ok
USER apprunner
ENV TMPDIR=/tmp HOME=/app

LABEL org.opencontainers.image.revision=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL org.opencontainers.image.version=$VERSION
EXPOSE 8083
ENTRYPOINT ["/app/app"]