# Transparency Exchange API (TEA)

ReARM is implementing TEA specification as the TEA standard emerges. TEA specification defines a standard, format agnostic, API for the exchange of product related artefacts, like BOMs, between systems. See more details on [TEA GitHub Repository](https://github.com/CycloneDX/transparency-exchange-api/).

Current state of TEA is Beta 1 (as of 2025-05-22).

Current state of ReARM TEA implementation is Alpha (as of 2025-05-22) - which means that most calls and workflows are implemented but with limitations.

Below you will find details of TEA implementation in ReARM.

## Enable TEA on ReARM
TEA implementation is currently enabled on ReARM Demo Instance at [https://demo.rearmhq.com](https://demo.rearmhq.com). It is only set up for the main organization (called `Demo Organization`) and not for any other organization that may be created by the users.

TEA implementation is not production ready and is disabled on ReARM by default.

To enable TEA on ReARM Community Edition, in the helm chart, you need to set the `enableBetaTea` value to `true`:

```
enableBetaTea: true
```

For docker-compose or other installations, set `RELIZAPROP_ENABLE_BETA_TEA` environment variable on ReARM backend to `true`.

This makes TEA available under `/tea/v0.1.0-beta.1/` on your ReARM installation and also resolves `./well-known/tea` links as described below.

## TEA Discovery and Operations

Below we demonstrate ReARM on ReARM as viewed by TEA. It starts with discovery. We defined the following TEI for ReARM Community Edition:

```
urn:tei:uuid:demo.rearmhq.com:eab985e6-f4f1-42db-95ba-5d9d1d695348
```

According to TEA conventions, clients may resolve this TEI via navigating to [https://demo.rearmhq.com/.well-known/tea/eab985e6-f4f1-42db-95ba-5d9d1d695348](https://demo.rearmhq.com/.well-known/tea/eab985e6-f4f1-42db-95ba-5d9d1d695348).

This will redirect to the actual ReARM TEI implementation at [https://demo.rearmhq.com/tea/v0.1.0-beta.1/product/eab985e6-f4f1-42db-95ba-5d9d1d695348](https://demo.rearmhq.com/tea/v0.1.0-beta.1/product/eab985e6-f4f1-42db-95ba-5d9d1d695348).

From there you may proceed with other TEA calls as defined by the specification, for example [https://demo.rearmhq.com/tea/v0.1.0-beta.1/component/5d8d898f-29aa-4734-87c3-93926bbbf8f6/releases](https://demo.rearmhq.com/tea/v0.1.0-beta.1/component/5d8d898f-29aa-4734-87c3-93926bbbf8f6/releases).

## Known issues, limitations and notes
- The data present via TEA is consistent with what is visible via ReARM UI, ReARM CLI or ReARM's own GraphQL API. For example, on ReARM Demo Instance, you may explore data using UI
- ReARM currently only supports TEIs of `uuid` type where uuid equals uuid of the Product in ReARM
- TEA authentication and authorization is not yet fully defined in the TEA specification and have not been implemented by ReARM. However, links from TEA for downloading artifacts lead to standard ReARM authentication and authorization mechanism. For the [ReARM Demo Instance](https://demo.rearmhq.com), you need to be registered to download artifacts via TEA - note that registration on Demo is publicly available.
- Product query search by identifiers has not been implemented
- Artifact digests (checksums) have not been implemented
- Data on [ReARM Demo Instance](https://demo.rearmhq.com) under the Demo Organization that can be obtained via TEA, including downloadable artifacts referenced in TEA and hosted on ReARM Demo, is distributed under the [Creative Commons Attribution 4.0 International (CC-BY-4.0)](https://creativecommons.org/licenses/by/4.0/) license. Note that this license applies only to the aforementioned data and artifacts. It does not apply to, without limitation, any ReARM components or source code or any other data obtained from or related to ReARM
- Data on [ReARM Demo Instance](https://demo.rearmhq.com) is subject to change without notice at any time, it may be extended, added or removed entirely.
