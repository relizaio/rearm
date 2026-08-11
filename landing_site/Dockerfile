FROM node:26.5.0-alpine3.24@sha256:a2bcf9238874d3900c086e685b8dc43e1ca8fa4e4a6c6e5f14a21f2f42e3af5f AS build-stage
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY ./ .
# Build Astro static site (outputs to /app/dist)
RUN npm run build

FROM nginx:1.31.3-alpine3.24-slim@sha256:45b82ed5f285b90d63df07ba70430fdd8f25624b416617d9e6dc93412b2006dc AS artifact-stage
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned
COPY --from=build-stage /app/dist /usr/share/nginx/html
RUN echo "version=$VERSION" > /usr/share/nginx/html/version.html && echo "commit=$GIT_COMMIT" >> /usr/share/nginx/html/version.html && echo "branch=$GIT_BRANCH" >> /usr/share/nginx/html/version.html
RUN chown nginx:nginx -R /usr/share/nginx/html && chmod 0700 /usr/share/nginx/html && chmod 0644 -R /usr/share/nginx/html/* && find /usr/share/nginx/html -type d -exec chmod 0700 {} \;
COPY nginx/default.conf /etc/nginx/conf.d/
COPY nginx/nginx.conf /etc/nginx/
LABEL git_commit=$GIT_COMMIT
LABEL git_branch=$GIT_BRANCH
LABEL ci_environment=$CI_ENV
LABEL org.opencontainers.image.version=$VERSION
