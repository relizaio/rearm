FROM node:26.1.0-alpine3.23@sha256:e71ac5e964b9201072425d59d2e876359efa25dc96bb1768cb73295728d6e4ea AS build-stage
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN set -x; node -v; npm -v; echo "--- package.json ---"; cat package.json; echo "--- package-lock.json (first 40 lines) ---"; head -n 40 package-lock.json; npm ci --ignore-scripts --loglevel=verbose --foreground-scripts; rc=$?; echo "=== npm ci exit code: $rc ==="; echo "=== latest npm debug log ==="; ls -1t /root/.npm/_logs/*-debug-0.log 2>/dev/null | head -n 1 | xargs -r cat || true; exit $rc
COPY ./ .
# Build Next.js and export (default to /app/out)
RUN npx next build

FROM nginx:1.31.0-alpine3.23-slim@sha256:9e666aeefa9801445bc2ff4994c48d314736dae4cf1f551ace03e38ea0373552 AS artifact-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
COPY --from=build-stage /app/out /usr/share/nginx/html
RUN echo "version=$VERSION" > /usr/share/nginx/html/version.html && echo "commit=$GIT_COMMIT" >> /usr/share/nginx/html/version.html && echo "branch=$GIT_BRANCH" >> /usr/share/nginx/html/version.html
RUN chown nginx:nginx -R /usr/share/nginx/html && chmod 0700 /usr/share/nginx/html && chmod 0644 -R /usr/share/nginx/html/* && find /usr/share/nginx/html -type d -exec chmod 0700 {} \;
COPY nginx/default.conf /etc/nginx/conf.d/
COPY nginx/nginx.conf /etc/nginx/
LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL org.opencontainers.image.version=$VERSION
