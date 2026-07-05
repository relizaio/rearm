---
title: "Understanding Artifact Processing in ReARM"
date: "2025-07-01"
---

ReARM supports uploading a wide range of [Artifacts](https://docs.rearmhq.com/concepts/#artifact). During many experiments, we have established a consistent approach for uploading and processing artifacts. This blog post will share technical details of how this is handled in ReARM.

#### I Determining Storage Type

ReARM supports artifacts stored both internally or externally. When an artifact is stored externally, ReARM treats it as a pointer in its database.

#### II Determining Artifact Type

Once the storage type is determined, the next step is identifying the artifact type. ReARM applies different levels of processing depending on the type of artifact. Bills of Materials (BoMs) are treated as a special category. They can be processed by ReARM's Rebom component and further via [Dependency-Track integration](https://docs.rearmhq.com/integrations/dtrack.html).

#### III Understanding SHA256 Digests

ReARM makes extensive use of Artifacts' [SHA-256 Digests](https://en.wikipedia.org/wiki/SHA-2). Currently, it recognizes the following 3 types of digests for each artifact:

1. **Digest of the original uploaded file.** This is the SHA-256 value you would get by running `sha256sum` on the file before uploading. If this digest is provided during upload and ReARM’s computed value does not match it, the upload will fail with an error.
2. **Digest of the image as reported by OCI storage.** ReARM uses Project [ORAS](https://github.com/oras-project/oras) to upload artifacts to OCI-compatible storage. Upon upload, the storage returns a [SHA-256 digest of the uploaded image](https://oras.land/docs/concepts/reference/). ReARM then uses this digest to reference and retrieve the artifact from OCI storage.
3. **Digest of the BOM component list (CycloneDX BOMs only).** For CycloneDX Bills of Materials, ReARM computes a SHA-256 digest of the list of components within the BOM. This digest is used to determine equality between different BOMs based on their content.

Below is a real life example how all the digests mentioned above are displayed in the ReARM UI. You can also explore [this view](https://demo.rearmhq.com/release/show/926e3c42-0c43-46ad-955a-7ac516474d07) interactively on the [ReARM Demo](https://rearmhq.com) instance.

![SBOM Facts showing 3 types of SHA256 digests in ReARM](/blog_images/2025-07-01-backend-image-digests-from-demo.png)

#### IV Understanding Whether Processing on Dependency-Track is Needed

Once artifact digests are computed, if the artifact is a BOM and Dependency-Track Integration is enabled, ReARM uses the BOM equality digest to determine whether a new project is needed in Dependency-Track. That helps optimize Dependency-Track performance, as it currently lacks built-in logic for detecting or handling duplicate BOMs - see discussion [here](https://github.com/DependencyTrack/dependency-track/issues/593).

If ReARM determines that the equality digest is unique, it will create a new project in Dependency-Track. Otherwise, it will simply add a reference to the existing project.

#### V Summary

Below we present the overall picture of how artifact processing is done in ReARM:

![ReARM Artifact Processing Flowchart](/blog_images/2025-07-01-rearm-artifact-processing.png)
