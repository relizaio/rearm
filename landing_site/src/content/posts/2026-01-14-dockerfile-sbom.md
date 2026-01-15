---
title: "Dockerfile.sbom"
date: "2026-01-14"
ogImage: "2026-01-14-dockerfile-sbom.png"
---

With the recent [ReARM release](https://github.com/relizaio/rearm/releases/tag/26.01.34), and updated [GitHub Actions](https://github.com/relizaio/rearm-actions), and our brand new [Azure Extension](https://marketplace.visualstudio.com/items?itemName=Reliza.rearm-cli-tasks), we would like to discuss a great workflow feature that is natively supported by ReARM tooling but should also be useful for a wider ecosystem.

*Dockerfile.sbom* is a way to specify custom way to produce your source code level SBOM which lives alongside software code. The idea is to have a Dockerfile that produces SBOM during the `docker build` command and places it into `/sbom/sbom.json` file in the produced container image.

Here is a sample Dockerfile.sbom that is currently used by ReARM project itself (here is a [GitHub link](https://github.com/relizaio/rearm/blob/main/backend/Dockerfile.sbom)):

```dockerfile
FROM maven:3.9.11-eclipse-temurin-21@sha256:1829262961db972b3924b5b8e4a240083626da2d92a49788c7dfb6de4692410d
ARG VERSION=not_versioned
RUN mkdir /app && mkdir /sbom
COPY . /app/
WORKDIR /app
RUN sed -i "s,Version_Managed_By_CI_AND_Reliza,$VERSION," pom.xml \
    && mvn org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
    -DincludeBomSerialNumber=true \
    && cp /app/target/bom.json /sbom/sbom.json
```

This is a Maven project that uses CycloneDX Maven plugin to generate SBOM during the build process and places it into `/sbom/sbom.json` file in the produced container image.

Then during the CI phase, ReARM GitHub action extracts this SBOM and uploads it to ReARM as a source code level SBOM. To enable this feature in GitHub Actions, you need to set `source_code_sbom_type` parameter to `custom` in the ReARM's [sbom-sign-scan](https://github.com/relizaio/rearm-actions/tree/main/sbom-sign-scan) action. 

However, similar approach can be used with other tools. Here is a sample script that can be used to extract SBOM from a container image:

```bash
docker run -d --name sbom-container --rm --entrypoint sleep sbom-container 60
sleep 3
docker cp sbom-container:/sbom/sbom.json ./fs.cdx.bom.json
```

Using Dockerfile.sbom allows for great degree of flexibility in terms of SBOM generation tools and methods and gives full control over the process to the user.
