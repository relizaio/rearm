FROM node:24.14.1-alpine3.23@sha256:8510330d3eb72c804231a834b1a8ebb55cb3796c3e4431297a24d246b8add4d5 AS build-stage
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts
COPY ./ .
RUN npm run build

FROM nginx:1.30.0-alpine3.23-slim@sha256:2fb5d772cea6ef1a8dab525df1b9485289eee167d26af9613fce27a12c060caa AS artifact-stage
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