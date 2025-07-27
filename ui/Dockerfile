FROM node:22-alpine3.22@sha256:5539840ce9d013fa13e3b9814c9353024be7ac75aca5db6d039504a56c04ea59 AS build-stage
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts && npm install @esbuild/linux-x64@0.25.8
COPY ./ .
RUN npm run build

FROM nginx:1.28.0-alpine3.21@sha256:d83c0138ea82c9f05c4378a5001e0c71256b647603c10c186bd7697a4db722d3 AS artifact-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
COPY --from=build-stage /app/dist /usr/share/nginx/html
COPY nginx/nginx.conf /etc/nginx/conf/
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
LABEL version=$VERSION

ENTRYPOINT [ "/nginx_start.sh" ]