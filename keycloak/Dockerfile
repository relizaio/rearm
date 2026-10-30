FROM quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_FEATURES=token-exchange
ENV KC_DB=postgres

RUN /opt/keycloak/bin/kc.sh build --health-enabled=true --metrics-enabled=true

# Gate build stage: compiles the Java probe (ci/trust-gate/TrustGate.java) for the trust-gate
# stage below. Only the class file leaves this stage; its own JDK and trust store never do.
FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0 AS gate-build
COPY ci/trust-gate/ /gate/
RUN javac -d /gate /gate/TrustGate.java

# Trust-store regeneration stage (October 2026 eMudhra CA compromise). The Keycloak image keeps
# the p11-kit trust source, /usr/bin/trust and p11-kit-trust.so but not libp11-kit.so.0 (upstream
# ubi-null.sh erases the p11-kit package), so update-ca-trust cannot run in it. This ubi9 stage
# ships the same p11-kit build; it takes the image's own source trees, adds the two vendored root
# PEMs of the gate directory's roots/ (fingerprints pinned in roots.json) to source/blocklist/ (distrust by
# public key and by issuer and serial; never anchors/) and regenerates every extracted store
# without them. Only /etc/pki/ca-trust/extracted/ leaves this stage; the stage's own
# ca-certificates source is deleted first and never read.
FROM registry.access.redhat.com/ubi9/ubi:9.8@sha256:16e9e43d99e181463513cc84a514859b1abb9460f1c4d9e208da4b4e51cd5cc3 AS trust-regen
RUN rm -rf /usr/share/pki/ca-trust-source /etc/pki/ca-trust/source
COPY --from=builder /usr/share/pki/ca-trust-source/ /usr/share/pki/ca-trust-source/
COPY --from=builder /etc/pki/ca-trust/source/ /etc/pki/ca-trust/source/
COPY ci/trust-gate/roots/emSign_ECC_Root_CA_-_G3.pem ci/trust-gate/roots/AAA_Certificate_Services.pem /etc/pki/ca-trust/source/blocklist/
RUN update-ca-trust \
    && find /etc/pki/ca-trust/extracted -type d -exec chmod 0755 {} + \
    && find /etc/pki/ca-trust/extracted -type f -exec chmod 0444 {} +

FROM quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b AS deliverable
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
# October 2026 eMudhra CA compromise: the system trust store, regenerated in the trust-regen stage
# without emSign ECC Root CA - G3 and AAA Certificate Services, replaces the base image's extracted
# stores: the JDK cacerts (/etc/pki/ca-trust/extracted/java/cacerts, where the JDK's
# lib/security/cacerts resolves), the PEM bundles and the CApath directory, the openssl bundle
# and the edk2 store. A COPY into an existing directory merges (adds and overwrites, never
# deletes), so the base's generated tree is cleared first and no stale file of any store
# survives; root is needed for that rm only (the base's directory-hash is root 0555), never for
# update-ca-trust, which this image cannot run. root:root, 0444 files in 0755 directories.
USER root
RUN rm -rf /etc/pki/ca-trust/extracted/*
COPY --from=trust-regen --chown=root:root /etc/pki/ca-trust/extracted/ /etc/pki/ca-trust/extracted/
# The same two roots in this image's own blocklist. This image cannot run update-ca-trust (no
# p11-kit), but an image derived from it that installs p11-kit and regenerates (for example to add
# an internal CA under /etc/pki/ca-trust/source/anchors/) reads this directory and keeps them out.
COPY ci/trust-gate/roots/emSign_ECC_Root_CA_-_G3.pem ci/trust-gate/roots/AAA_Certificate_Services.pem /etc/pki/ca-trust/source/blocklist/
USER 1000

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

FROM deliverable AS trust-gate
USER root
COPY ci/trust-gate/ /gate/
COPY --from=gate-build /gate/TrustGate.class /gate/
RUN java -cp /gate TrustGate && echo "trust-gate ok $(date -u +%FT%TZ)" > /gate/ok

FROM deliverable AS release
COPY --from=trust-gate /gate/ok /trust-gate-ok
