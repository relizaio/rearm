<!--
  GENERATED. Source of truth is cyclonedx-taxonomy.md in the rearm-core repo,
  carried here by backend/copy-src.sh. Edit it there, not here: a change made only
  in this copy is silently reverted by the next sync.
-->
# `reliza` CycloneDX Taxonomy

This is the namespace for CycloneDX properties used by [Reliza](https://reliza.io/) and particularly by [ReARM](https://rearmhq.com).

For details about the taxonomy, refer to the official CycloneDX Taxonomy Repository [here](https://github.com/CycloneDX/cyclonedx-property-taxonomy).

----

## reliza Namespace Taxonomy

| Property | Description |
|----------|-------------|
| `reliza:containerSafeVersion` | The variant of the release version that is safe to use as a container tag, i.e. if the full version is "1.2.3+metadata", this will be "1.2.3" |
| `reliza:rearmImport` | Namespace for properties used when importing CycloneDX components to ReARM |
| `reliza:devops` | Namespace for properties used for managing DevOps operations from ReARM |
| `reliza:componentMetadata` | Namespace for additional component metadat properties |
| `reliza:support` | Namespace for per-component software support disclosure (level of support, end-of-support dates and their provenance) |
| `reliza:device` | Namespace for the enclosing device release's own lifecycle dates |


## reliza:rearmImport Namespace Taxonomy

| Property | Description |
|----------|-------------|
| `reliza:rearmImport:rearmImportable` | Set to "true" if this comopnent can be imported to ReARM, otherwise it will be skipped on import |
| `reliza:rearmImport:componentName` | Component name to use on import (required) |
| `reliza:rearmImport:baseBranch` | Base branch of component, defaults to "main" |
| `reliza:rearmImport:vcsBranch` | VCS branch for this release, defaults to base branch |
| `reliza:rearmImport:vcsUri` | VCS Uri used for this component |
| `reliza:rearmImport:vcsPath` | Path for this component inside VCS repository, defaults to "." |
| `reliza:rearmImport:vcsTag` | VCS Tag for this release |
| `reliza:rearmImport:componentVersionSchema` | Component versioning schema |
| `reliza:rearmImport:branchVersionSchema` | Versioning schema for branches outside of base branch |

## reliza:devops Namespace Taxonomy

| Property | Description |
|----------|-------------|
| `reliza:devops:integrationType` | Enum, known values: FOLLOW, INTEGRATE, TARGET, NONE, UNINSTALL |

## reliza:componentMetadata Namespace Taxonomy

| Property | Description |
|----------|-------------|
| `reliza:componentMetadata:componentDistribution` | Enum, known values: PRIVATE, PUBLIC |

## reliza:support Namespace Taxonomy

Per-component support disclosure, written by ReARM onto components in a served
CycloneDX BOM. Aimed at the software-level-of-support and end-of-support elements
recommended by FDA's premarket cybersecurity guidance (section V.A.4(b)), but not
specific to medical devices.

**`reliza:support:*` and `reliza:device:*` are RESERVED PREFIXES.** Any property under
them found in an uploaded BOM is STRIPPED on ingest and re-emitted from ReARM's own
record. A supplier cannot pre-populate them and should not try: values written upstream
will not survive. Their presence in a BOM served by ReARM therefore means ReARM wrote
them. The one exception is a raw signed artifact downloaded unmodified, which ReARM
cannot alter and does not claim to have authored.

Unless stated otherwise, each property MUST occur at most once per component and is
placed in `components[].properties`.

> **Applicability.** Releases before per-milestone support disclosure emit
> `reliza:support:source` and `reliza:support:lastAssessed` WITHOUT the `:<milestone>`
> suffix, carrying one value for the whole component. The suffix was added because a
> single component's dates can legitimately come from different sources, which one value
> could not express. Those properties are emitted by CE 26.08.95 only when a component
> carries support data, and no CE release has shipped a UI to enter any, so no published
> BOM carries the unsuffixed form.

| Property | Description |
|----------|-------------|
| `reliza:support:levelOfSupport` | The manufacturer's ATTESTED claim about what the component's upstream maintainer is doing. **The value is FDA's phrase verbatim and lowercase -- `actively maintained`, `no longer maintained`, `abandoned` -- NOT an enum name.** Match on those strings; there is no `ACTIVELY_MAINTAINED` on the wire. Absent when nobody has attested a level. Never emitted without `reliza:support:assessedAt` |
| `reliza:support:assessedAt` | When a human assessed this component, as an RFC-3339 UTC instant. Caller-supplied, so it may precede the moment the assessment was recorded. Also emitted on its own, with no level, to record that a component WAS assessed and no upstream dates were found |
| `reliza:support:justification` | The manufacturer's stated BASIS for the claim -- what they checked and what they found. Required by ReARM before a negative level (`no longer maintained`, `abandoned`) may be recorded, so a claim about a third party's project always ships with its evidence. Also carries the reason no upstream dates could be found, which is the whole disclosure for a component whose upstream publishes none |
| `reliza:support:status` | DERIVED, never attested: the support state the recorded dates entail as of the moment the BOM was served. Known values: `SECURITY_ONLY`, `END_OF_SUPPORT`, `UNKNOWN`. See the note on derived values below |
| `reliza:support:party` | Whether the organisation serving the BOM is the FIRST party for this component's support facts (its own software) or a THIRD party describing a component it does not control. Known values: `FIRST_PARTY`, `THIRD_PARTY` |
| `reliza:support:source:<milestone>` | Provenance of one milestone's date, where `<milestone>` is `endOfGuaranteedSupport`, `endOfSupport` or `endOfLife`. Known values: `MANUAL` (a human recorded it), `SUPPLIER` (from a supplier-provided BOM), `ENRICHED` (machine-gathered). Per milestone, because one component's dates can come from different places |
| `reliza:support:lastAssessed:<milestone>` | When that milestone's date was last assessed, as an RFC-3339 UTC instant. Per milestone, same reasoning |
| `reliza:support:deviceSupportRisk` | DERIVED: whether the component's end-of-support falls before the enclosing device release's own end-of-support. Known values: `OK`, `EOS_BEFORE_DEVICE`, `UNKNOWN`. Emitted only for a product/device release |
| `reliza:support:disclosure` | Document-level marker on `metadata.component`, describing how the support properties in this BOM were produced. Known value: `derived-non-attested-current-state` |

### A note on derived values

`reliza:support:status` and `reliza:support:deviceSupportRisk` are COMPUTED from the
recorded dates at the moment the BOM is served. They are not claims by the
manufacturer, and unlike `reliza:support:levelOfSupport` nobody attested them. Two
consequences for a consumer:

* they can change between two downloads of the same BOM without any data changing,
  because the computation is relative to the current date;
* where a derived value and an attested one appear to disagree -- a
  `levelOfSupport` of `no longer maintained` alongside a far-future
  `cdx:lifecycle:milestone:endOfSupport`, say -- **that disagreement is deliberate and
  is not reconciled**. The attested claim is what a human asserts; the derived one is
  what the dates imply. Both are shown so a reader can judge.

## reliza:device Namespace Taxonomy

The enclosing device release's own lifecycle dates, written on `metadata.component`
so a reader can compare component dates against the device's. Emitted only for a
product/device release that declares at least one of them.

| Property | Description |
|----------|-------------|
| `reliza:device:endOfSupport` | The device release's end-of-support date (ISO-8601 `YYYY-MM-DD`) |
| `reliza:device:endOfLife` | The device release's end-of-life date (ISO-8601 `YYYY-MM-DD`) |

## Standard CycloneDX lifecycle keys written by ReARM

ReARM also writes three keys from the official `cdx:lifecycle:milestone` taxonomy
rather than inventing its own. It uses CycloneDX's definitions, and this section
records what it INFERS from each, so the inference is public rather than implied.

| Property | How ReARM uses it |
|----------|-------------------|
| `cdx:lifecycle:milestone:endOfGuaranteedSupport` | CycloneDX's definition applies: the manufacturer no longer provides assured services, and any support beyond this point is discretionary. **ReARM derives `reliza:support:status` = `SECURITY_ONLY` after this date -- meaning assured support has ended and only security fixes are expected.** This is a derived EXPECTATION, not a claim that security fixes are guaranteed: under CycloneDX's own definition nothing after this milestone is assured, and the value must not be read as a promise |
| `cdx:lifecycle:milestone:endOfSupport` | CycloneDX's definition applies: all support ceases. ReARM derives `reliza:support:status` = `END_OF_SUPPORT` on and after this date, and compares it against the device's own end-of-support for `reliza:support:deviceSupportRisk` |
| `cdx:lifecycle:milestone:endOfLife` | CycloneDX's definition applies: the manufacturer stops SELLING the product after its defined useful life. Because that is an end-of-sale event and routinely precedes end of support, ReARM derives NO support state from it and does not order it against the other two milestones. A component past this date may still be supported |
