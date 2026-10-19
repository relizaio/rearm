# Transparency Exchange API (TEA)

The Transparency Exchange API defines a standard, format agnostic API for the exchange of product related artefacts, like BOMs, between systems. ReARM serves the CycloneDX TEA 1.0.0 specification; see [TEA GitHub Repository](https://github.com/CycloneDX/transparency-exchange-api/) for the specification itself.

ReARM Pro serves TEA 1.0.0 for what an organization publishes: an organization sets up publication profiles, publishes releases, and hands external keys to the parties that may read what is private. ReARM Community Edition does not offer TEA yet: it has no publication profiles, no publishing and no TEA surface. The earlier 0.4.0 beta is retired.

## Discovery and the API base

### TEA ids

Nothing on the Transparency Exchange API carries an internal ReARM uuid. Each organization gets a TEA id the first time it saves a profile with publishing ENABLED, or when its first [external key](../configure/programmatic-access#external-keys-tea) is created, whichever comes first; each component gets one on its first publication; neither ever changes.

### The organization's API base

The organization's TEA API base is `<your ReARM URL>/tea/<organization TEA id>`, for example `https://rearm.example.com/tea/55555555-5555-4555-8555-555555555555`. Organization Settings, Transparency Exchange, Discovery shows it once the organization has a TEA id.

The endpoints of TEA 1.0.0 are under `<API base>/v1.0.0/`. A client appends `/v1.0.0` to the base itself, as the `versions` of the `.well-known/tea` document below and the `servers[].versions` of a discovery answer tell it.

### Hosting `.well-known/tea` on your TEI domain

A consumer that holds a TEI, `tei://<TEI domain>/uuid/<TEA release id>`, finds the API through the TEI domain. The organization hosts this document at `https://<TEI domain>/.well-known/tea`, served with `Content-Type: application/json`, where `<TEI domain>` is the domain in the organization's TEIs:

```json
{
  "schemaVersion": 1,
  "endpoints": [
    {
      "url": "https://rearm.example.com/tea/55555555-5555-4555-8555-555555555555",
      "versions": ["1.0.0"],
      "priority": 1
    }
  ]
}
```

Organization Settings, Transparency Exchange, Discovery shows the organization's own document, with its own API base as `url`, in the compact form the server builds, ready to copy:

```
{"schemaVersion":1,"endpoints":[{"url":"https://rearm.example.com/tea/55555555-5555-4555-8555-555555555555","versions":["1.0.0"],"priority":1}]}
```

The consumer reads the document, calls `<url>/v1.0.0/discovery?tei=<the TEI>` and continues on that API base.

### The instance's own `/.well-known/tea`

ReARM also answers `GET /.well-known/tea` on its own host. On an installation of type OSS it lists one endpoint per organization that has a TEA id and is not archived, sorted by `url`, with `Cache-Control: public, max-age=300`; while no organization has a TEA id yet it answers `404` with `{"error": "OBJECT_UNKNOWN"}`. On any other installation it answers `404` with `{"error": "NOT_IMPLEMENTED"}`: each organization hosts its own `/.well-known/tea` document on its TEI domain, as above.

### Discovery

`GET <API base>/v1.0.0/discovery?tei=<TEI>` or `?purl=<purl>`, exactly one of the two, answers with the matching product releases, newest first. Discovery answers product releases only. A TEI or purl that resolves no served product release is answered `404` with `{"error": "OBJECT_UNKNOWN"}`; the specification allows no empty discovery answer.

## Publication profiles

A TEA profile says whether, and how, releases are published on the Transparency Exchange API. Profiles live at three scopes: the organization, a perspective (ReARM Pro) and a component or product. A profile at a lower scope replaces its parent as a whole, never field by field; the editor pre-fills a new profile from the profile the scope would otherwise resolve to.

Publication profiles and TEA ids are available in ReARM Pro. ReARM Community Edition does not offer them yet, and its settings show no Transparency Exchange or TEA tab.

A component resolves its profile in this order: its own profile when it carries one (an override, or a choice to follow one of its perspectives), then the profile of its single perspective that has one, then the organization profile, then the built-in defaults. When two or more of the component's perspectives carry a profile and the component has none of its own, the component is in conflict: publishing it is refused, naming the perspectives, until the component overrides or follows one of them. A product's profile is its own component-scope profile, so a product never conflicts.

Every profile carries these settings (defaults in brackets):

| Setting | Values |
|---|---|
| Publishing | ENABLED, DISABLED [DISABLED]: DISABLED conceals everything resolving to the profile and blocks new publishes |
| Visibility | PRIVATE, PUBLIC [PRIVATE]: resolved at request time; only an organization admin can make a component PUBLIC: by saving a PUBLIC profile, by following a PUBLIC perspective or removing an override under one, by adding the component to a PUBLIC perspective or removing the perspective that kept it private, or by removing a perspective profile or deleting a perspective its members resolved through |
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

An archived component or product keeps its profile so an admin can hide what it published: it accepts only a profile with publishing DISABLED and visibility PRIVATE, and the Transparency Exchange table tags it as archived. An archived component or product also takes no perspective change that would make it resolve to a profile with publishing ENABLED or visibility PUBLIC.

Creating a component or product in a PUBLIC perspective (from the perspective's page, through an API key with write access to it, or by a CI run that names it) makes the new component PUBLIC from the start: the organization admin who made the perspective PUBLIC chose that for its members, and a new component exposes nothing it published before.

## Publishing releases

Publishing is an explicit act on a release: nothing is published on TEA until someone publishes it or an action of an approval rule publishes it (Automatic publication below), and what is published does not change until it is re-published. Publishing is available in ReARM Pro for now.

### What a publication is

A publication gives the release a TEA release id, which is also the id of its TEA collection; the id never changes. Each publish generates the release's aggregated SBOM with the knobs of the effective profile (dependency depth, structure, sources, excluded coverage, support and internal metadata) and freezes it as a generated artifact of the release, listed under Generated artifacts on the release page. The collection then gets a new version: version 1 on the first publish, and one more each time the published content changes. Every collection version is immutable and keeps its own list of artifacts, each with a TEA id and a revision, and the reason it was written (initial release, VEX updated, artifact added, removed or updated).

### Gates

A publish is refused, with the reason, when:

- the release lifecycle is below ASSEMBLED, or below the profile's minimum lifecycle;
- the component resolves to no single profile (a conflict between perspectives), or its profile has publishing DISABLED;
- the release or its component is archived, or the release has no SBOM to aggregate. A component release of a product that has no SBOM of its own is left out of the product's TEA aggregate and reported in the publish summary as having no SBOM; ReARM's own exports of the product's SBOM keep a placeholder for it;
- the caller lacks the Publish Externally (TEA) permission on the release's component. Users and RBAC (FREEFORM or USER) API keys can hold it; organization admins have it implicitly;
- another publish or hide of the same release is still running, on this or another ReARM instance, after 30 seconds of waiting (five minutes for automatic publication); the act can simply be repeated once it has finished.

### Re-publish and hide

Re-publish regenerates the aggregated SBOM and writes a new collection version only when something changed; otherwise it reports that nothing changed since the latest version. Hide stops serving the release on TEA. Nothing is deleted: the collections stay, and a later re-publish makes the release served again and continues their numbering. To a TEA consumer, a hidden release, and every collection and artifact only it published, answers `404` with `{"error": "OBJECT_UNKNOWN"}`, exactly as something that never existed.

Visibility is live, content is frozen: the profile's visibility (PRIVATE or PUBLIC) and publishing setting are applied when TEA is read, so changing them changes who can read what is already published, while the content of each collection version stays as it was published.

A published release whose lifecycle falls below ASSEMBLED (cancelled, rejected, pending, draft) is concealed on TEA while it stays there, without a hide; it is served again once it is back at ASSEMBLED or beyond.

A publish of a large product can outlast the web proxy's limit; the page then reports a gateway error while the act completes on the server. Reload the release page to see the result; repeating the act is safe, because a publish of what is already published answers that it is already published or unchanged, and a hide of a hidden release changes nothing.

### Automatic publication

The actions TEA Publish (TEA_PUBLISH) and TEA Hide (TEA_HIDE), configured on a component's Actions tab or as policy-wide actions of an approval policy like a VDR snapshot and with no settings of their own, run after the rule's pass on a queue; what they publish is decided by the effective TEA profile at firing time, and when that profile is PUBLIC the editor warns before the save and marks the action. Configuring them, the rules and global-rule refs that fire or disable them on any approval policy of the organization, an approval policy that carries them, or a component name an organization rule maps to such a policy, needs the Publish Externally (TEA) permission on the component; a TEA_PUBLISH fires only from actions, rules and refs that a holder of that permission or an organization admin saved, and a publish whose wiring was changed any other way is withheld with a History line (TEA_PUBLISH withheld: NOT_AUTHORIZED) until a holder edits and saves it on the component, or an organization admin saves the policy's lists again; a component's ref that overrides a global rule's outputs to a TEA_PUBLISH is authorized by the ref and the action, and the overridden rule contributes its condition only; a hide always fires; a component created under a matching name is enrolled by the organization's rule; a global action's TEA kind, and whether a global rule fires one, cannot change under the same action or rule, and a removed global's uuid is never reused (remove it and create a new one); while a hide rule matches no automatic publish runs, in the pass or at the act (History: TEA_PUBLISH withheld); a hide that arrives while a publish waits runs instead of it; a refused act is on the release's History as a GUARD line; a publish in progress is retried within minutes; the property relizaprops.teaOutputEventsEnabled pauses automatic publication on an instance.

### Products

When a product's profile has Product components set to PUBLISH_WITH_PRODUCT, publishing the product also publishes each of its component releases of the product's own organization; a component release of another organization is reported as not found and left untouched. The product's profile decides the shape of each component release's aggregated SBOM (sources, dependency depth, structure, excluded coverage, support metadata, TEI). The component's own TEA profile still applies: a component release whose own profile disables publishing, or that is in conflict, is refused and reported, and its visibility is never wider than its own profile allows, nor wider than the product's. Raw artifacts and internal metadata follow the narrower of the two profiles, and the stricter of the two minimum lifecycles applies. A component release that cannot be published (for example a DRAFT one) is reported without stopping the product. A component release that is already published directly, or that was hidden, is left as it is. A component release bundled by several products belongs to the product that published it first: the others report it as published with that product, and a product takes it over only when that product is hidden or no longer lists it. Hiding the product conceals the component releases published with it.

### Ride-along artifacts

With Raw artifacts set to NON_BOM or ALL, the release's own artifacts are published alongside the aggregated SBOM (NON_BOM leaves out BOMs). An artifact is publishable when its version is an integer (its TEA revision) and ReARM holds its checksum: for an artifact stored in ReARM, the retained raw bytes of the upload; for an externally stored one, a download link and a declared checksum. Signatures are published with the artifact they sign. An artifact that does not qualify is skipped and named in the publish summary; it never fails the publish.

An external download link is published only when it is an absolute `http` or `https` URL of printable ASCII characters, at most 4096 octets long, with a host and without user information; an artifact whose link is anything else is skipped with `SKIPPED_LINK_NOT_HTTP_URL`, and the same rule leaves out a deliverable's `distributions[].url` that does not meet it. A signature stored outside ReARM is published as the format's `signatureUrl` under the same rule, and its download is a `302` to that link.

### TEI

When the profile's TEI is UUID, the publication carries the TEI `tei://<TEI domain>/uuid/<TEA release id>`, recomputed on each publish.

### On the release page and in the API

The release page header offers Publish on TEA, and once published a TEA badge with the collection version (red when the release is public), Re-publish on TEA and Hide from TEA. Each asks for confirmation first; a public profile is called out with the URL anyone can read. A public release also shows a red banner under its title. The Transparency Exchange (TEA) section of the release page shows the state (and why it is concealed, when it is), the TEA release id and URL, the profile applied and its revision (or the product it was published with), the TEI, the last publish, and every collection version with its artifacts. The release History tab records each publish, re-publish and hide.

The same acts are available through GraphQL: `publishReleaseOnTea`, `republishReleaseOnTea`, `hideReleaseOnTea` and the query `releaseTeaPublicationView`, and for API keys `publishReleaseOnTeaProgrammatic`, `republishReleaseOnTeaProgrammatic`, `hideReleaseOnTeaProgrammatic` and `releaseTeaPublicationViewProgrammatic`. Notification subscriptions can listen for Release published on TEA and Release hidden from TEA.

## What a consumer reads

Every operation is a `GET` under `<API base>/v1.0.0`, except the token exchange:

- listings: `/products`, `/components`, `/productReleases`, `/componentReleases` (the last two take `idType`, `TEI` or `PURL`, with `idValue`);
- products: `/product/{uuid}`, `/product/{uuid}/releases`, `/product/{uuid}/cle`;
- product releases: `/productRelease/{uuid}`, `/productRelease/{uuid}/collections`, `/productRelease/{uuid}/collection/latest`, `/productRelease/{uuid}/collection/{collectionVersion}`, `/productRelease/{uuid}/cle`;
- components: `/component/{uuid}`, `/component/{uuid}/releases`, `/component/{uuid}/cle`;
- component releases: `/componentRelease/{uuid}`, `/componentRelease/{uuid}/collections`, `/componentRelease/{uuid}/collection/latest`, `/componentRelease/{uuid}/collection/{collectionVersion}`, `/componentRelease/{uuid}/cle`;
- artifacts: `/artifact/{uuid}/latest` and `/artifact/{uuid}/{artifactVersion}` (the metadata), and their `/download` and `/signature/download` (see [Artifact downloads and caching](#artifact-downloads-and-caching));
- discovery: `/discovery?tei=` or `?purl=` (see [Discovery](#discovery));
- the one `POST`: `/token` (see [Authentication](#authentication)).

Every `{uuid}` is a TEA id. Collection versions are immutable: version 1 on the first publish, one more each time the published content changes. Each carries its update reason, one of `INITIAL_RELEASE`, `VEX_UPDATED`, `ARTIFACT_UPDATED`, `ARTIFACT_ADDED` and `ARTIFACT_REMOVED`, and its artifact entries, each with a TEA id and a revision.

Listings answer one page holding every result. `pageSize` is accepted from 1 to 100 and not applied; any `pageToken` is refused, since none is ever issued. `sortField` and `sortOrder` (`asc`, the default, or `desc`) are checked per operation: `name` for `/products` and `/components`; `createdDate` (the default), `releaseDate` or `version` for the release listings; `version` for the collection listings.

Every TEA error body carries `error` and optionally `message`:

| Status | `error` | When |
|---|---|---|
| `404` | `OBJECT_UNKNOWN` | The object is unknown, hidden or not published, the caller's key does not cover it, a discovery query resolves no served product release, or the organization TEA id in the base is unknown, malformed or archived. |
| `400` | `INVALID_REQUEST` | A parameter is wrong, a query value is not valid UTF-8 or percent-encoding, or a download `mediaType` is not a media type. |
| `400` | `INVALID_PAGE_TOKEN` | A `pageToken` is sent. |
| `406` | `NO_ACCEPTABLE_FORMAT` | A download `mediaType` is absent, blank, a wildcard or names no format of the revision. |
| `404` | `SIGNATURE_NOT_FOUND` | The format has no signature. |
| `404` | `NOT_IMPLEMENTED` | The instance's `/.well-known/tea` outside an installation of type OSS, or a redirect target above 4096 octets. |

A path that no TEA operation serves is answered `404` with `{"message":"Not Found"}`, which is not a TEA error: the client has a wrong base or version and fails over to the next endpoint, or reports it.

Requests are limited to 100 per 30 seconds per address, or per key for a caller with a valid token; above that ReARM answers `429` with `Retry-After` and `{"message":"Rate limit exceeded"}`. The address is the first `X-Forwarded-For` entry, which the edge in front of ReARM must set itself (see [Deploying TEA](#deploying-tea)).

JSON answers, artifact metadata included, carry `Cache-Control: no-cache` for an anonymous caller and `private, no-cache` for a caller with a valid key of the organization, whatever the visibility, with `Vary: Authorization`.

## Authentication

A profile's visibility decides who reads what resolves to it: a PUBLIC profile publishes to anyone, a PRIVATE profile only to holders of an [external key](../configure/programmatic-access#external-keys-tea) whose grants cover the object. An external key is a key the organization hands to a party outside it; it reaches nothing but TEA. The holder exchanges it for a one-hour Bearer token at the token endpoint on the organization's API base, `POST <API base>/v1.0.0/token` with `grant_type=client_credentials`, where the API base is `<your ReARM URL>/tea/<organization TEA id>`.

The full exchange, with an example and every answer of the token endpoint, is under [Exchanging an external key for a token](../configure/programmatic-access#exchanging-an-external-key-for-a-token). The rules a consumer needs:

- A PUBLIC object needs no key.
- The credentials go as HTTP Basic or as the form fields `client_id` and `client_secret` in the request body, never both in one request and never a parameter twice (`400` with `invalid_request`), and never in the URL query string: a token-request parameter in the query is refused with `400` and `invalid_request` naming the parameters.
- The token goes as `Authorization: Bearer <token>` on every other TEA endpoint.

A PRIVATE object requested without a token is answered `401` with a `WWW-Authenticate: Bearer realm="tea"` challenge. An invalid, expired or revoked token is answered `401` with `error="invalid_token"` in the challenge, and the client exchanges its key again and retries once. A valid token whose key does not cover the object is answered `404` with `OBJECT_UNKNOWN`, exactly as an object that does not exist or is not published. A PUBLIC object is served to every caller, and a token sent along is ignored.

A token issued by another organization is no credential on this organization's API base: the request is handled as one without a token, so a PUBLIC object is served and a PRIVATE one is answered with the `401` challenge. The API base of an archived organization answers `404` with `OBJECT_UNKNOWN` to everything, the token endpoint included, before any credential is read.

## Artifact downloads and caching

An artifact revision is downloaded with

```
GET <API base>/v1.0.0/artifact/{uuid}/{artifactVersion}/download?mediaType=<media type>
GET <API base>/v1.0.0/artifact/{uuid}/latest/download?mediaType=<media type>
```

and its signature with `.../signature/download?mediaType=<media type>` on the same two paths. `mediaType` names one of the formats of the revision: absent, blank, a wildcard (`*/*`, `text/*`) or a value that names no format of the revision is answered `406` with `NO_ACCEPTABLE_FORMAT`, and a value that is not a media type (`not a media type`, `text/`) `400` with `INVALID_REQUEST`. A revision with no signature for that format answers `404` with `SIGNATURE_NOT_FOUND`.

- A format stored in ReARM is served with a strong `ETag` (its sha256), `Repr-Digest` (`sha-256=:<base64>:`), an absolute versioned `Content-Location` and a `Content-Disposition` attachment name; a request with a matching `If-None-Match` is answered `304`.
- A format or signature stored outside ReARM is a `302` to its `url` or `signatureUrl`, as frozen in the collection. **A client must not send its TEA bearer token to the host the redirect names.**
- The `Content-Type` of a download is the canonical rendering of the format's media type (lower-case type and subtype, no whitespace around `;`), while `mediaType` in the metadata and in `Content-Location` keeps the spelling the publisher stored.

`Cache-Control` and `Vary` of downloads and `302`s (a `302` is of a PUBLIC or a PRIVATE revision as its download is; a refusal carries no `Vary`):

| Answer | `Cache-Control` | `Vary` |
|---|---|---|
| PUBLIC, versioned download | `public, max-age=86400, immutable` | `Authorization` |
| PUBLIC, `latest` download, and a `302` of a PUBLIC revision | `no-cache` | `Authorization` |
| PRIVATE download, and a `302` of a PRIVATE revision | `private, no-cache` | `Authorization` |
| Refusals, the `401` challenges included | `no-store` | none |

The artifact metadata (`/artifact/{uuid}/latest`, `/artifact/{uuid}/{artifactVersion}`) is a JSON answer and follows the rule for JSON answers above, not this table: `no-cache` for an anonymous caller, `private, no-cache` for a valid key of the organization, whatever the visibility.

A shared cache may go on serving a copy of a PUBLIC revision for at most one day after the organization hides the publication, archives or rejects the release, narrows the profile to PRIVATE or archives itself. ReARM itself refuses at once; copies already downloaded cannot be recalled. An operator who fronts `/tea` with a CDN should purge it on a hide to make the withdrawal take effect sooner.

Downloads need no ReARM login: the profile's visibility and the external key decide, as for every other TEA object.

## Deploying TEA

There is nothing to switch on: the TEA endpoints are always mounted on ReARM Pro, and an upgrade needs no setting and no step. What a consumer meets depends on the organization:

- Before the organization has a TEA id there is no base: any path under `/tea/<id>` is answered `404` with `OBJECT_UNKNOWN`, and on an installation of type OSS the instance's `/.well-known/tea` is `404` with `OBJECT_UNKNOWN` while no organization has a TEA id. The TEA id is minted when an organization saves a profile with publishing ENABLED, or when its first external key is created.
- Once the TEA id exists, the base answers: the four listings answer `200` with an empty list; discovery answers `404` with `OBJECT_UNKNOWN` for a TEI or purl that resolves no served product release, which, with nothing published, is every query (the specification allows no empty discovery answer); every object is `404` with `OBJECT_UNKNOWN`; the token endpoint issues tokens for the organization's external keys; and on an installation of type OSS the instance's `/.well-known/tea` lists the base. Nothing is served until a release is published, by hand or by a TEA_PUBLISH action.

A consumer who reaches an empty listing has the right base. A `404` with `OBJECT_UNKNOWN` from discovery alone does not mean a wrong base: a listing on the same base tells the two apart.

Behind a proxy:

- A redirect `Location` near the 4096-octet bound needs `proxy_buffer_size 16k` on the `location /tea` of the UI front's nginx until the shipped UI front carries that setting; without it such a redirect is answered `502`.
- The per-address limit counts the first `X-Forwarded-For` entry. The edge in front of ReARM must set `X-Forwarded-For` itself and must not pass on a value the client sent (Traefik does this by default for untrusted sources; an nginx edge uses `$remote_addr`, not `$proxy_add_x_forwarded_for`).

ReARM Community Edition does not offer TEA yet; the earlier 0.4.0 beta and its switch are retired.

## Not implemented yet

- **Page tokens.** Listings answer one page; `pageSize` is not applied and every `pageToken` is refused. A listing above 1000 results is logged on the server.
- **CLE 1.0.0 alignment.** The four CLE operations (`/product/{uuid}/cle`, `/component/{uuid}/cle`, `/productRelease/{uuid}/cle`, `/componentRelease/{uuid}/cle`) are served as ReARM built them for the earlier draft: a product's or component's CLE is the union of the lifecycle events of its served releases, a release's CLE its own events. Alignment with CLE 1.0.0 is a task of its own.
- **VDR and VEX.** ReARM generates no vulnerability document for a publication; the profile's Vulnerability documents setting is reserved at NONE.
- **Signing.** ReARM signs nothing it publishes; signatures are published as the publisher stored them.
- **`preRelease`.** Releases do not set it.
- **Component discovery.** Discovery answers product releases only; an installation that publishes components only lists them through `/componentReleases?idType=PURL` or `idType=TEI` with `idValue`.
- **The rearm-cli `tea` verbs.** `tea discovery` and `tea full_tea_flow` speak the retired 0.4.0 beta and do not work against this API until a CLI task updates them.
