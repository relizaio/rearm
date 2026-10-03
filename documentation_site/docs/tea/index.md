# Transparency Exchange API (TEA)

ReARM is implementing TEA specification as the TEA standard emerges. TEA specification defines a standard, format agnostic, API for the exchange of product related artefacts, like BOMs, between systems. See more details on [TEA GitHub Repository](https://github.com/CycloneDX/transparency-exchange-api/).

Current state of TEA: Community TEA 1.0.0 release is planned for summer 2026.

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

This makes TEA available under `/tea/v0.4.0/` on your ReARM installation and also resolves `./well-known/tea` links as described below.

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

## Publication profiles

A TEA profile says whether, and how, releases are published on the Transparency Exchange API. Profiles live at three scopes: the organization, a perspective (ReARM Pro) and a component or product. A profile at a lower scope replaces its parent as a whole, never field by field; the editor pre-fills a new profile from the profile the scope would otherwise resolve to.

Publication profiles and TEA ids are available in ReARM Pro. ReARM Community Edition does not offer them yet, and its settings show no Transparency Exchange or TEA tab.

A component resolves its profile in this order: its own profile when it carries one (an override, or a choice to follow one of its perspectives), then the profile of its single perspective that has one, then the organization profile, then the built-in defaults. When two or more of the component's perspectives carry a profile and the component has none of its own, the component is in conflict: publishing it is refused, naming the perspectives, until the component overrides or follows one of them. A product's profile is its own component-scope profile, and a product publishes its component releases under that one profile, so a product never conflicts.

Every profile carries these settings (defaults in brackets):

| Setting | Values |
|---|---|
| Publishing | ENABLED, DISABLED [DISABLED]: DISABLED conceals everything resolving to the profile and blocks new publishes |
| Visibility | PRIVATE, PUBLIC [PRIVATE]: resolved at request time; only an organization admin can save a PUBLIC profile |
| Dependency depth | FULL, TOP_LEVEL_ONLY [FULL] |
| Optional dependencies | INCLUDE, EXCLUDE [INCLUDE] |
| SBOM structure | FLAT, HIERARCHICAL [FLAT] |
| SBOM sources | any of DELIVERABLE, RELEASE, SOURCE_CODE [all three] |
| Excluded coverage | any of DEV, TEST, BUILD_TIME [DEV and TEST] |
| Support metadata | INCLUDE, EXCLUDE [EXCLUDE]: INCLUDE needs the organization support injection setting |
| Internal metadata | INCLUDE, EXCLUDE [EXCLUDE] |
| Raw artifacts | NONE, NON_BOM, ALL [NONE] |
| Product components | PUBLISH_WITH_PRODUCT, PRODUCT_ONLY [PUBLISH_WITH_PRODUCT] |
| Minimum lifecycle | ASSEMBLED, READY_TO_SHIP, GENERAL_AVAILABILITY [ASSEMBLED] |
| TEI | DISABLED, UUID [DISABLED]: UUID stamps `tei://<TEI domain>/uuid/<TEA release uuid>` onto published releases |
| Vulnerability documents | NONE (reserved) |

Edit the organization profile under Organization Settings, Transparency Exchange; a component's or product's in its settings, on the TEA tab; a perspective's from the TEA profile action on the Perspectives tab of Organization Settings (ReARM Pro). A PUBLIC profile shows a red banner wherever it applies.

### TEA ids and discovery

Nothing on the Transparency Exchange API carries an internal ReARM uuid. Each organization gets a TEA id the first time it saves a profile with publishing ENABLED, and each component gets one on its first publication; neither ever changes. The organization's TEA API base is `<your ReARM URL>/tea/<organization TEA id>`.

Organization Settings, Transparency Exchange shows the `.well-known/tea` document the organization hosts at `https://<your TEI domain>/.well-known/tea`, for example:

```
{"schemaVersion":1,"endpoints":[{"url":"https://rearm.example.com/tea/<organization TEA id>","versions":["1.0.0"],"priority":1}]}
```

The TEA 1.0.0 endpoints that read these profiles and ids land in a later release; until then the profiles are stored and validated, and the `/tea/v0.4.0/` surface above is unchanged.

## Publishing releases

Publishing is an explicit act on a release: nothing is published on TEA until someone publishes it, and what is published does not change until it is re-published. Publishing is available in ReARM Pro for now.

### What a publication is

A publication gives the release a TEA release id, which is also the id of its TEA collection; the id never changes. Each publish generates the release's aggregated SBOM with the knobs of the effective profile (dependency depth, structure, sources, excluded coverage, support and internal metadata) and freezes it as a generated artifact of the release, listed under Generated artifacts on the release page. The collection then gets a new version: version 1 on the first publish, and one more each time the published content changes. Every collection version is immutable and keeps its own list of artifacts, each with a TEA id and a revision, and the reason it was written (initial release, VEX updated, artifact added, removed or updated).

### Gates

A publish is refused, with the reason, when:

- the release lifecycle is below ASSEMBLED, or below the profile's minimum lifecycle;
- the component resolves to no single profile (a conflict between perspectives), or its profile has publishing DISABLED;
- the release or its component is archived, or the release has no SBOM to aggregate;
- the caller lacks the Publish Externally (TEA) permission on the release's component. Users and RBAC (FREEFORM or USER) API keys can hold it; organization admins have it implicitly.

### Re-publish and hide

Re-publish regenerates the aggregated SBOM and writes a new collection version only when something changed; otherwise it reports that nothing changed since the latest version. Hide stops serving the release on TEA. Nothing is deleted: the collections stay, and a later re-publish makes the release served again and continues their numbering.

Visibility is live, content is frozen: the profile's visibility (PRIVATE or PUBLIC) and publishing setting are applied when TEA is read, so changing them changes who can read what is already published, while the content of each collection version stays as it was published.

### Products

When a product's profile has Product components set to PUBLISH_WITH_PRODUCT, publishing the product also publishes each of its component releases under the product's profile, and a component release that cannot be published on its own (for example a DRAFT one) is reported and skipped without stopping the product. A component release that is already published directly, or that was hidden, is left as it is. Hiding the product conceals the component releases published with it.

### Ride-along artifacts

With Raw artifacts set to NON_BOM or ALL, the release's own artifacts are published alongside the aggregated SBOM (NON_BOM leaves out BOMs). An artifact is publishable when its version is an integer (its TEA revision) and ReARM holds its checksum: for an artifact stored in ReARM, the retained raw bytes of the upload; for an externally stored one, a download link and a declared checksum. Signatures are published with the artifact they sign. An artifact that does not qualify is skipped and named in the publish summary; it never fails the publish.

### TEI

When the profile's TEI is UUID, the publication carries the TEI `tei://<TEI domain>/uuid/<TEA release id>`, recomputed on each publish.

### On the release page and in the API

The release page header offers Publish on TEA, and once published a TEA badge with the collection version (red when the release is public), Re-publish on TEA and Hide from TEA. Each asks for confirmation first; a public profile is called out with the URL anyone can read. A public release also shows a red banner under its title. The Transparency Exchange (TEA) section of the release page shows the state, the TEA release id and URL, the profile applied and its revision, the TEI, the last publish, and every collection version with its artifacts. The release History tab records each publish, re-publish and hide.

The same acts are available through GraphQL: `publishReleaseOnTea`, `republishReleaseOnTea`, `hideReleaseOnTea` and the query `releaseTeaPublicationView`, and for API keys `publishReleaseOnTeaProgrammatic`, `republishReleaseOnTeaProgrammatic`, `hideReleaseOnTeaProgrammatic` and `releaseTeaPublicationViewProgrammatic`. Notification subscriptions can listen for Release published on TEA and Release hidden from TEA.

The TEA 1.0.0 surface that serves publications to TEA clients lands in a later release; until then publications are recorded and visible in ReARM, and publishing is ReARM Pro only.

## Known issues, limitations and notes
- The data present via TEA is consistent with what is visible via ReARM UI, ReARM CLI or ReARM's own GraphQL API. For example, on ReARM Demo Instance, you may explore data using UI
- ReARM supports TEIs of `uuid` and `purl` types where uuid equals uuid of the Product in ReARM and purl must be set explicitly per release (or can be cnfigured in the Component / Product settings to propagate to releases)
- TEA authentication and authorization is not yet fully defined in the TEA specification and have not been implemented by ReARM. However, links from TEA for downloading artifacts lead to standard ReARM authentication and authorization mechanism. For the [ReARM Demo Instance](https://demo.rearmhq.com), you need to be registered to download artifacts via TEA - Note that registration on ReARM Demo is publicly available
- Data on [ReARM Demo Instance](https://demo.rearmhq.com) under the Demo Organization that can be obtained via TEA, including downloadable artifacts referenced in TEA and hosted on ReARM Demo, is distributed under the [Creative Commons Attribution 4.0 International (CC-BY-4.0)](https://creativecommons.org/licenses/by/4.0/) license. Note that this license applies only to the aforementioned data and artifacts. It does not apply to, without limitation, any ReARM components or source code or any other data obtained from or related to ReARM
- Data on [ReARM Demo Instance](https://demo.rearmhq.com) is subject to change without notice at any time, it may be extended, added or removed entirely.