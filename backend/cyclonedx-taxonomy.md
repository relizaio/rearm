<!--
  SOURCE OF TRUTH: backend/cyclonedx-taxonomy.md in the rearm-core repository, beside the code
  that emits these properties. The copy in relizaio/rearm is GENERATED from it by
  backend/copy-src.sh, so a change made only in that copy is reverted by the next sync.

  It lives next to the code because it did not used to: this file existed only in the
  public mirror, which the Pro build never touches, and that is how properties came to
  be emitted into a REGISTERED CycloneDX namespace with no published definition. Keeping
  it beside the emitters means a change adding a PROP_* constant can define it in the
  same diff, and CyclonedxTaxonomyDocSyncTest fails the build if it does not.
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
them found in an uploaded BOM is STRIPPED when ReARM serves the document and re-emitted
from ReARM's own record. A supplier cannot pre-populate them and should not try: values
written upstream will not survive. Their presence in a BOM served by ReARM therefore means
ReARM wrote them. The one exception is the raw download, which serves the artifact as
uploaded: ReARM does not alter it and does not claim to have authored anything in it.

Unless stated otherwise, each property MUST occur at most once per component and is
placed in `components[].properties`. Two exceptions are noted in the table:
`reliza:support:disclosure` is written once per document in `metadata.properties`, and
the `reliza:device:*` pair is written on `metadata.component`.

The **Since** column says which build emits a property, so a reader can tell what their
own version produces. "unreleased" means it is in the ReARM source but has not reached a
CE release yet; those arrive together at the next source sync.

> **Applicability.** Releases before per-milestone support disclosure emit
> `reliza:support:source` and `reliza:support:lastAssessed` WITHOUT the `:<milestone>`
> suffix, carrying one value for the whole component. The suffix was added because a
> single component's dates can legitimately come from different sources, which one value
> could not express. Those properties are emitted by CE 26.08.95 only when a component
> carries support data, and no CE release has shipped a UI to enter any, so no published
> BOM carries the unsuffixed form.

| Property | Description | Since |
|----------|-------------|-------|
| `reliza:support:levelOfSupport` | The manufacturer's ATTESTED claim about what the component's upstream maintainer is doing. **The value is FDA's phrase verbatim and lowercase -- `actively maintained`, `no longer maintained`, `abandoned` -- NOT an enum name.** Match on those strings; there is no `ACTIVELY_MAINTAINED` on the wire. Absent when nobody has attested a level. Never emitted without `reliza:support:assessedAt` | unreleased; reaches CE at the next sync |
| `reliza:support:assessedAt` | When a human assessed this component, as an RFC-3339 UTC instant. Caller-supplied, so it may precede the moment the assessment was recorded. Also emitted on its own, with no level, to record that a component WAS assessed and no upstream dates were found | unreleased; reaches CE at the next sync |
| `reliza:support:justification` | The manufacturer's stated BASIS for the claim -- what they checked and what they found. Required by ReARM before a negative level (`no longer maintained`, `abandoned`) may be recorded, so a claim about a third party's project always ships with its evidence. Also carries the reason no upstream dates could be found, which is the whole disclosure for a component whose upstream publishes none | unreleased; reaches CE at the next sync |
| `reliza:support:status` | DERIVED, never attested: the support state the recorded dates entail as of the moment the BOM was served. Known values: `SECURITY_ONLY`, `END_OF_SUPPORT`, `UNKNOWN`. See the note on derived values below | CE 26.08.95 |
| `reliza:support:party` | Whether the organisation serving the BOM is the FIRST party for this component's support facts (its own software) or a THIRD party describing a component it does not control. Known values: `FIRST_PARTY`, `THIRD_PARTY` | unreleased; reaches CE at the next sync |
| `reliza:support:source:<milestone>` | Provenance of one milestone's date, where `<milestone>` is `endOfGuaranteedSupport`, `endOfSupport` or `endOfLife`. Known values: `MANUAL` (a human recorded it), `SUPPLIER` (from a supplier-provided BOM), `ENRICHED` (machine-gathered). Per milestone, because one component's dates can come from different places | suffixed form unreleased; reaches CE at the next sync; unsuffixed in CE 26.08.95 |
| `reliza:support:lastAssessed:<milestone>` | When that milestone's date was last assessed, as an RFC-3339 UTC instant. Per milestone, same reasoning | suffixed form unreleased; reaches CE at the next sync; unsuffixed in CE 26.08.95 |
| `reliza:support:deviceSupportRisk` | DERIVED: whether the component's end-of-support falls before the enclosing device release's own end-of-support. Known values: `OK`, `EOS_BEFORE_DEVICE`, `UNKNOWN`. Emitted only for a product/device release | unreleased; reaches CE at the next sync |
| `reliza:support:disclosure` | Document-level marker in `metadata.properties` (NOT on `metadata.component`, where the `reliza:device:*` pair goes), describing how the support properties in this BOM were produced. Known values: `derived-non-attested-current-state` (support properties in this document were derived from recorded dates at serve time; a component with none means no date is recorded for it) and `provenance-stripped-no-disclosure` (server-owned properties were removed so nothing here is an uploader's forged claim, but NOTHING was disclosed either -- absence of a support property says nothing about what is recorded; emitted on the SPDX-augmented download and the JSON release SBOM export). TWO KINDS OF DOCUMENT CARRY NO MARKER: the raw download, which is served as uploaded and makes no claim about its contents, and any export whose caller passed `includeSupportMetadata=false`, because the marker qualifies a support statement and a caller who declined the disclosure is not making one. Both are still SWEPT -- the sweep is the anti-spoofing control, the marker is a statement about it. Do not read the second value as the first, and do not read an ABSENT marker as either | first value CE 26.08.95; second unreleased, reaches CE at the next sync |

### A note for renderer authors

The CSV and EXCEL forms of the release SBOM export are rendered from the stored BOM and do
NOT pass through the property sweep that the JSON forms get. They are safe today only
because they read a fixed column list -- `name`, `group`, `version`, `purl`, `author`,
`license` -- and never look at `components[].properties`. **Adding a properties column to
either renderer would serve an uploader's forged `reliza:support:*` values under ReARM's
attribution, with no sweep in front of it.** If you add one, route those exports through the
sweep first.

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

## reliza bom-ref identifiers in the `declarations` block

Not properties. These are `bom-ref` identifiers ReARM mints inside the document-level
CycloneDX `declarations` block (Layer B of the support disclosure), and they use the
registered `reliza` namespace for the same reason the properties do: so a consumer can
tell what this server wrote from what an uploader did. They are listed here because the
namespace is one namespace, and a reader who finds `reliza:claim:...` in a BOM comes
looking in this file.

`declarations` is CycloneDX 1.6+. A BOM served at an earlier spec version carries the
support properties only -- emitting the block there would produce a document that fails
its own declared schema.

| Identifier | Description | Since |
|----------|-------------|-------|
| `reliza:claim:<componentKey>` | `bom-ref` of one `claims[]` entry: what ReARM attests about one component. `<componentKey>` is the component's canonical purl, or its cpe when there is no purl -- the same key the injector matches on | unreleased; reaches CE at the next sync |
| `reliza:evidence:<n>:<componentKey>` | `bom-ref` of one `evidence[]` entry backing a claim. `<n>` is a per-document ordinal, present only to keep the ref unique when one component has several pieces of evidence | unreleased; reaches CE at the next sync |
| `reliza:assessor:<partyRole>` | `bom-ref` of one `assessors[]` entry, where `<partyRole>` is the CycloneDX party role: `manufacturer` for a first-party assessment, `supplier` for a third-party one. Joined to the claims it made by an `attestations[]` entry -- an assessor nothing points at would tell a reader nothing | unreleased; reaches CE at the next sync |
| `reliza:bomref:<componentKey>` | A `bom-ref` ReARM ASSIGNED to a component that arrived without one, so a claim has something to target. Namespaced rather than set to the bare component key for two reasons: it cannot collide with a ref the document already had, and it cannot accidentally resolve a `dependsOn` edge that was dangling before ReARM touched the document. Removed again on any egress that strips, so it never outlives the claim it was minted for | unreleased; reaches CE at the next sync |

Two values inside the block are worth naming because they are not obvious from the
identifiers:

* `claims[].predicate` is `reliza:support:levelOfSupport: <value>` when a level is
  attested, and the bare string `reliza:support:assessed` when the component was
  assessed but no level was published. The second is a real state, not a placeholder:
  the assessment having happened IS the record, and dropping it would make this block
  disagree with the properties about whether the component was assessed at all.
* `evidence[].propertyName` reuses the property names from the `reliza:support` and
  `cdx:lifecycle:milestone` taxonomies above rather than inventing parallel ones, which
  is what lets a consumer reconcile the two layers. `propertyName` is defined by
  CycloneDX as a taxonomy-property reference, so this is its intended use. **The VALUES
  match too**: `reliza:support:party` carries `FIRST_PARTY`/`THIRD_PARTY` in the
  evidence exactly as it does in the property, never the CycloneDX party role. The role
  has its own slot -- the `assessors[]` entry and its `bom-ref` -- because one
  documented property must not carry two vocabularies depending on which layer a
  consumer reads it from.
* The milestone dates (`cdx:lifecycle:milestone:endOfSupport` and its siblings) appear
  as evidence too, each with its OWN assessment instant in `created`, which is what
  per-milestone provenance is for. An attestation whose only content is a date is a
  real and common state: it produces a claim with milestone evidence and no level.

**ReARM strips any inbound `declarations` block from an uploaded BOM before serving it,
on every egress and whichever way the export setting is set** -- the same server-owned
guarantee the property namespaces get, and for a stronger reason: `declarations` is the
standard's own attestation vocabulary, so a forged one is more credible than a forged
property, not less.

## reliza:device Namespace Taxonomy

The enclosing device release's own lifecycle dates, written on `metadata.component`
so a reader can compare component dates against the device's. Emitted only for a
product/device release that declares at least one of them.

| Property | Description | Since |
|----------|-------------|-------|
| `reliza:device:endOfSupport` | The device release's end-of-support date (ISO-8601 `YYYY-MM-DD`) | unreleased; reaches CE at the next sync |
| `reliza:device:endOfLife` | The device release's end-of-life date (ISO-8601 `YYYY-MM-DD`) | unreleased; reaches CE at the next sync |

## Standard CycloneDX lifecycle keys written by ReARM

ReARM also writes three keys from the official `cdx:lifecycle:milestone` taxonomy
rather than inventing its own. It uses CycloneDX's definitions, and this section
records what it INFERS from each, so the inference is public rather than implied.

| Property | How ReARM uses it |
|----------|-------------------|
| `cdx:lifecycle:milestone:endOfGuaranteedSupport` | CycloneDX's definition applies: the manufacturer no longer provides assured services, and any support beyond this point is discretionary. **ReARM derives `reliza:support:status` = `SECURITY_ONLY` after this date -- meaning assured support has ended and only security fixes are expected.** This is a derived EXPECTATION, not a claim that security fixes are guaranteed: under CycloneDX's own definition nothing after this milestone is assured, and the value must not be read as a promise |
| `cdx:lifecycle:milestone:endOfSupport` | CycloneDX's definition applies: all support ceases. ReARM derives `reliza:support:status` = `END_OF_SUPPORT` on and after this date, and compares it against the device's own end-of-support for `reliza:support:deviceSupportRisk` |
| `cdx:lifecycle:milestone:endOfLife` | CycloneDX's definition applies: the manufacturer stops SELLING the product after its defined useful life. Because that is an end-of-sale event and routinely precedes end of support, ReARM derives NO support state from it and does not order it against the other two milestones. A component past this date may still be supported |
