# Public signing with Cosign and Sigstore using GitHub Actions

N.B. When using this public option, data is uploaded to Public Rekor transparency log server using Public Fulcio CA. Read more in [the Sigstore documentation](https://docs.sigstore.dev/logging/overview/). Do not use this option if you do not want data to be publicly stored. ReARM also provides support for custom signature metadata uploading, we will document and provide samples at a later date.

ReARM GitHub Actions [rearm-docker-action](https://github.com/relizaio/rearm-docker-action) and [rearm-helm-action](https://github.com/relizaio/rearm-helm-action) support public signing of deliverables and SBOMs using Cosign and Sigstore.

To enable it, make sure that your job has id-token write permission as following:

```yaml
    permissions:
      id-token: write
```

Then add input

```yaml
    rearm_enable_public_cosign_sigstore: 'true'
```

to the ReARM GitHub Action you are using.

This will ensure Cosign signing during the job and will store all signing artifacts (Signature, Certificate, Signed Payload) on ReARM, where applicable.