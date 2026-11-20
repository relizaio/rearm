FROM node:26.12.1-alpine3.24@sha256:40cd011b55415bc894e45b02fdd5a5dfde056921b7d23a64c74f80497af48797 AS build-stage
WORKDIR /app
# October 2026 eMudhra CA compromise: the fixed Alpine bundle, and Node on it (--use-openssl-ca)
# until FIXED_NODE is set in ci/trust-gate/trust-gate.mjs.
RUN apk upgrade --no-cache ca-certificates ca-certificates-bundle && apk info -e -v ca-certificates-bundle
ENV NODE_OPTIONS=--use-openssl-ca SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
COPY package*.json ./
RUN npm ci --ignore-scripts
COPY ./ .
# Trust-store probe: the node that ran npm ci refuses the compromised chains.
RUN node ci/trust-gate/trust-gate.mjs
RUN npm run build

# runtime-base is nginx, upgraded; artifact-stage is runtime-base flattened onto scratch.
FROM nginx:1.31.9-alpine3.24-slim@sha256:5bfced78d24d644270bedb0a9968697042e38d5c03fb6354603df025279c1cbe AS runtime-base
# Upgrade every package: nginx:1.31.9-alpine3.24-slim still ships libssl3/libcrypto3 3.5.9-r0 and zlib 1.3.2-r1
# (CVE-2026-4738); the build fails if the upgrade did not reach zlib 1.4-r0. The ca-certificates-bundle check stays
# for the October 2026 eMudhra CA compromise (hygiene; nginx makes no outbound TLS).
RUN apk upgrade --no-cache && apk info -e -v ca-certificates-bundle && \
    apk version -t "$(apk info -e -v zlib | sed 's/^zlib-//')" 1.4-r0 | grep -qv '<'

# Flattened onto scratch: an image SBOM reads every layer, so the base layer's superseded packages
# would otherwise still be reported after the upgrade above. The copy drops the nginx image config,
# so it is restored here; STOPSIGNAL SIGQUIT matters, nginx_start.sh relies on it for a graceful
# `docker stop`. CMD is not restored: the ENTRYPOINT below resets it, as before.
FROM scratch AS artifact-stage
COPY --from=runtime-base / /
ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ENV NGINX_VERSION=1.31.9 PKG_RELEASE=1 DYNPKG_RELEASE=1
EXPOSE 80
STOPSIGNAL SIGQUIT
WORKDIR /
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
COPY --from=build-stage /app/dist /usr/share/nginx/html
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/default.conf.template /etc/nginx/templates/
COPY nginx/nginx_start.sh /nginx_start.sh
RUN echo "version=$VERSION" > /usr/share/nginx/html/version.html && echo "commit=$GIT_COMMIT" >> /usr/share/nginx/html/version.html && echo "branch=$GIT_BRANCH" >> /usr/share/nginx/html/version.html
RUN chmod 0770 /usr/share/nginx/html && chmod 0660 -R /usr/share/nginx/html/* && chown nginx:nginx -R /usr/share/nginx/html && find /usr/share/nginx/html -type d -exec chmod 0770 {} \;
RUN chmod 0770 /nginx_start.sh
ENV HUB_HOST=172.17.0.1
ENV HUB_PORT=8086
ENV KEYCLOAK_HOST=127.0.0.1
ENV KEYCLOAK_PORT=9080
LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL org.opencontainers.image.version=$VERSION
LABEL org.opencontainers.image.vendor="Reliza Incorporated"
LABEL org.opencontainers.image.title="ReARM UI"
LABEL org.opencontainers.image.source="https://github.com/relizaio/rearm"
LABEL org.opencontainers.image.license="AGPL-3.0-only"
LABEL org.opencontainers.image.url="https://rearmhq.com"
LABEL org.opencontainers.image.base.name="registry.relizahub.com/library/rearm-ui"

ENTRYPOINT [ "/nginx_start.sh" ]

# Trust-store gate (October 2026 eMudhra CA compromise): the shipped store must refuse the
# compromised chains. release copies the marker, so this stage always builds.
FROM artifact-stage AS trust-gate
USER root
RUN apk add --no-cache curl openssl
COPY ci/trust-gate/ /gate/
RUN sh /gate/trust-gate.sh && echo "trust-gate ok $(date -u +%FT%TZ)" > /gate/ok

FROM artifact-stage AS release
COPY --from=trust-gate /gate/ok /trust-gate-ok
