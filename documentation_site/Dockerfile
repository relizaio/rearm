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
RUN npm run docs:build

FROM nginx:1.31.3-alpine3.24-slim@sha256:45b82ed5f285b90d63df07ba70430fdd8f25624b416617d9e6dc93412b2006dc AS artifact-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
# October 2026 eMudhra CA compromise: the fixed Alpine bundle (hygiene; nginx makes no outbound TLS).
RUN apk upgrade --no-cache ca-certificates ca-certificates-bundle && apk info -e -v ca-certificates-bundle
COPY --from=build-stage /app/docs/.vitepress/dist /usr/share/nginx/html
RUN echo "version=$VERSION" > /usr/share/nginx/html/version.html && echo "commit=$GIT_COMMIT" >> /usr/share/nginx/html/version.html && echo "branch=$GIT_BRANCH" >> /usr/share/nginx/html/version.html
RUN chmod 0755 /usr/share/nginx/html && chmod 0644 -R /usr/share/nginx/html/*
RUN find /usr/share/nginx/html -type d -exec chmod 0755 {} \;
COPY nginx/default.conf /etc/nginx/conf.d/
COPY nginx/nginx.conf /etc/nginx/
LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL version=$VERSION

# Trust-store gate (October 2026 eMudhra CA compromise): the shipped store must refuse the
# compromised chains. release copies the marker, so this stage always builds.
FROM artifact-stage AS trust-gate
USER root
RUN apk add --no-cache curl openssl
COPY ci/trust-gate/ /gate/
RUN sh /gate/trust-gate.sh && echo "trust-gate ok $(date -u +%FT%TZ)" > /gate/ok

FROM artifact-stage AS release
COPY --from=trust-gate /gate/ok /trust-gate-ok
