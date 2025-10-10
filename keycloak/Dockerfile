FROM quay.io/keycloak/keycloak:26.4.0@sha256:5f3fb534cde6bf006d79f5912473e5d2c828c707cdfc52e16972803aca9d43dd AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_FEATURES=token-exchange
ENV KC_DB=postgres

RUN /opt/keycloak/bin/kc.sh build --health-enabled=true --metrics-enabled=true

FROM quay.io/keycloak/keycloak:26.4.0@sha256:5f3fb534cde6bf006d79f5912473e5d2c828c707cdfc52e16972803aca9d43dd AS deliverable
ARG CI_ENV=noci
ARG GIT_COMMIT=git_commit_undefined
ARG GIT_BRANCH=git_branch_undefined
ARG VERSION=not_versioned

RUN mkdir -p /opt/keycloak/data/tmp && mkdir -p /opt/keycloak/data/import
RUN chown keycloak:keycloak -R /opt/keycloak
COPY --from=builder --chown=keycloak:keycloak /opt/keycloak/lib/quarkus/ /opt/keycloak/lib/quarkus/
COPY --chown=keycloak:keycloak ./realms/2025-02-10-reliza-realm-rearmoss.json /opt/keycloak/data/import/2025-02-10-reliza-realm-rearmoss.json
COPY --chown=keycloak:keycloak ./realms/2025-08-11-master-realm-export.json /opt/keycloak/data/import/2025-08-11-master-realm-export.json
COPY --chown=keycloak:keycloak ./themes/ /opt/keycloak/themes/

WORKDIR /opt/keycloak

ENV KEYCLOAK_ADMIN=admin
ENV KEYCLOAK_ADMIN_PASSWORD=admin

# change these values to point to a running postgres instance
ENV KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak
ENV KC_DB_USERNAME=postgres
ENV KC_DB_PASSWORD=password   

LABEL org.opencontainers.image.revision $GIT_COMMIT
LABEL git_branch $GIT_BRANCH
LABEL ci_environment $CI_ENV
LABEL org.opencontainers.image.version $VERSION
LABEL org.opencontainers.image.vendor Reliza
LABEL org.opencontainers.image.title "ReARM Keycloak"

ENTRYPOINT ["/bin/bash"]