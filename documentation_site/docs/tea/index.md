# Transparency Exchange API (TEA)

ReARM is implementing TEA specification as the TEA standard emerges. TEA specification defines a standard, format agnostic, API for the exchange of product related artefacts, like BOMs, between systems. See more details on [TEA GitHub Repository](https://github.com/CycloneDX/transparency-exchange-api/).

Current state of TEA is Beta 2 (as of 2025-12-18).

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

This makes TEA available under `/tea/v0.2.0-beta.2/` on your ReARM installation and also resolves `./well-known/tea` links as described below.

## TEA Discovery and Operations

Below we demonstrate ReARM on ReARM as viewed by TEA. It starts with discovery. 

Use ReARM CLI for TEA discovery as documented [here](https://github.com/relizaio/rearm-cli/blob/main/docs/tea.md).

For example, TEA full flow for ReARM CE release [26.01.34](https://github.com/relizaio/rearm-cli/blob/main/docs/tea.md) can be done as follows:

```
rearm tea full_tea_flow --tei "urn:tei:uuid:demo.rearmhq.com:34de200a-a796-4986-a6e0-014f3d2a5806"
```

Call with debug to see all underlying calls as:

```
rearm tea full_tea_flow --debug true --tei "urn:tei:uuid:demo.rearmhq.com:34de200a-a796-4986-a6e0-014f3d2a5806"
```

## Known issues, limitations and notes
- The data present via TEA is consistent with what is visible via ReARM UI, ReARM CLI or ReARM's own GraphQL API. For example, on ReARM Demo Instance, you may explore data using UI
- ReARM supports TEIs of `uuid` and `purl` types where uuid equals uuid of the Product in ReARM and purl must be set explicitly per release (or can be cnfigured in the Component / Product settings to propagate to releases)
- TEA authentication and authorization is not yet fully defined in the TEA specification and have not been implemented by ReARM. However, links from TEA for downloading artifacts lead to standard ReARM authentication and authorization mechanism. For the [ReARM Demo Instance](https://demo.rearmhq.com), you need to be registered to download artifacts via TEA - Note that registration on ReARM Demo is publicly available
- Data on [ReARM Demo Instance](https://demo.rearmhq.com) under the Demo Organization that can be obtained via TEA, including downloadable artifacts referenced in TEA and hosted on ReARM Demo, is distributed under the [Creative Commons Attribution 4.0 International (CC-BY-4.0)](https://creativecommons.org/licenses/by/4.0/) license. Note that this license applies only to the aforementioned data and artifacts. It does not apply to, without limitation, any ReARM components or source code or any other data obtained from or related to ReARM
- Data on [ReARM Demo Instance](https://demo.rearmhq.com) is subject to change without notice at any time, it may be extended, added or removed entirely.