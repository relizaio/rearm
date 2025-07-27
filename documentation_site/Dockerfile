FROM node:22-alpine3.22@sha256:5539840ce9d013fa13e3b9814c9353024be7ac75aca5db6d039504a56c04ea59 as build-stage
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY ./ .
RUN npm run docs:build

FROM nginx:1.28.0-alpine3.21@sha256:d83c0138ea82c9f05c4378a5001e0c71256b647603c10c186bd7697a4db722d3 as artifact-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
COPY --from=build-stage /app/docs/.vitepress/dist /usr/share/nginx/html
RUN echo "version=$VERSION" > /usr/share/nginx/html/version.html && echo "commit=$GIT_COMMIT" >> /usr/share/nginx/html/version.html && echo "branch=$GIT_BRANCH" >> /usr/share/nginx/html/version.html
RUN chmod 0755 /usr/share/nginx/html && chmod 0644 -R /usr/share/nginx/html/*
RUN find /usr/share/nginx/html -type d -exec chmod 0755 {} \;
COPY nginx/default.conf /etc/nginx/conf.d/
COPY nginx/nginx.conf /etc/nginx/
LABEL git_commit $GIT_COMMIT
LABEL git_branch $GIT_BRANCH
LABEL ci_environment $CI_ENV
LABEL version $VERSION