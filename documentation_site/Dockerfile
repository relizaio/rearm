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
RUN echo "b26010fef1b7f29b43e0918a028ee974841316cf1c71e302943d4b95dc9d53c8  docs/workflows/readiness-rules.md" > /tmp/readiness-rules.sha256 && sha256sum -c /tmp/readiness-rules.sha256
RUN npm run docs:build
RUN echo "2284da03c650f772c431be567aa657796b1d47b86eeedecda1be1118efc3cc89  docs/workflows/fda-checker-rules.md" > /tmp/rules.sha256 && sha256sum -c /tmp/rules.sha256
RUN test ! -e ci/regulated-outputs/regulated-outputs.mjs || node --input-type=module -e "import { PAGES, NOT_COMPARED } from './ci/regulated-outputs/regulated-outputs.mjs'; if (PAGES['fda-checker-rules'] !== 'docs/workflows/fda-checker-rules.md' || Object.hasOwn(NOT_COMPARED, 'fda-checker-rules')) { console.error('docs/workflows/fda-checker-rules.md is in this documentation: ci/regulated-outputs/regulated-outputs.mjs must map fda-checker-rules to it in PAGES and leave it out of NOT_COMPARED (FDAW-125)'); process.exit(1) }"

FROM nginx:1.31.8-alpine3.24-slim@sha256:f45171d36c76a8b2f5ef97108a3a8a2294248a6578a0a180edc2d78f7d2edc26 AS artifact-stage
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
