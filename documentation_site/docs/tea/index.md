# Transparency Exchange API (TEA)

The Transparency Exchange API defines a standard, format agnostic API for the exchange of product related artefacts, like BOMs, between systems. ReARM serves the CycloneDX TEA 1.0.0 specification; see [TEA GitHub Repository](https://github.com/CycloneDX/transparency-exchange-api/) for the specification itself.

ReARM serves TEA 1.0.0 for what an organization publishes: an organization defines SBOM profiles (named recipes of an aggregated SBOM), sets TEA publishing settings that say which profiles are published, whether and to whom, publishes releases, and hands external keys to the parties that may read what is private. ReARM Pro and ReARM Community Edition both serve it, from the Transparency Exchange API tab of the organization's settings; on Community Edition an organization keeps the one SBOM profile `default` and its TEA publishing settings name exactly that profile, and regulatory packages and submissions, perspectives, [serving modes and custom domains](#custom-domains-rearm-pro) are ReARM Pro only (see [Edition differences](#edition-differences)).

## Discovery and the API base

### TEA ids

Nothing on the Transparency Exchange API carries an internal ReARM uuid. Each organization gets a TEA id the first time it saves TEA publishing settings with publishing ENABLED, when its first [external key](../configure/programmatic-access#external-keys-tea) is created, or, on ReARM Pro, when an organization administrator chooses a [serving mode](#serving-modes) other than **Not served**, whichever comes first; each component gets one on its first publication; neither ever changes.

### The organization's API base

The organization's TEA API base is `<your ReARM URL>/tea/<organization TEA id>`, for example `https://rearm.example.com/tea/55555555-5555-4555-8555-555555555555`. On ReARM Pro this is the base of the serving mode **Legacy path with the organization TEA id**; an organization served on a custom domain has the base `https://<domain>/tea` instead, and an organization that is **Not served**, the default, has none (see [Custom domains](#custom-domains-rearm-pro)). Organization Settings, Admin Settings, Transparency Exchange API shows the base the organization is served at.

The endpoints of TEA 1.0.0 are under `<API base>/v1.0.0/`, on every shape of the base. A client appends `/v1.0.0` to the base itself, as the `versions` of the `.well-known/tea` document below and the `servers[].versions` of a discovery answer tell it.

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

On ReARM Pro, when the TEI domain is the organization's [custom domain](#custom-domains-rearm-pro), the instance can host this document on the domain for the organization (see [Hosting `/.well-known/tea` on the domain](#hosting-well-known-tea-on-the-domain)).

### The instance's own `/.well-known/tea`

ReARM also answers `GET /.well-known/tea` on its own host. On an installation of type OSS it lists one endpoint per organization that has a TEA id and is not archived, sorted by `url`, with `Cache-Control: public, max-age=300`; while no organization has a TEA id yet it answers `404` with `{"error": "OBJECT_UNKNOWN"}`. On any other installation it answers `404` with `{"error": "NOT_IMPLEMENTED"}`: each organization hosts its own `/.well-known/tea` document on its TEI domain, as above. The exception on ReARM Pro is a host assigned to an organization that is served on it and has chosen to have the instance host its document: there `/.well-known/tea` answers that organization's document (see [Hosting `/.well-known/tea` on the domain](#hosting-well-known-tea-on-the-domain)); this applies to the ReARM host too once a global administrator assigns it.

### Discovery

`GET <API base>/v1.0.0/discovery?tei=<TEI>` or `?purl=<purl>`, exactly one of the two, answers with the matching product releases, newest first. Discovery answers product releases only. A TEI or purl that resolves no served product release is answered `404` with `{"error": "OBJECT_UNKNOWN"}`; the specification allows no empty discovery answer.

## Custom domains (ReARM Pro)

::: warning ReARM Pro only
:::

An organization can serve its TEA API on a domain of its own, for example `tea.acme.example`, instead of at a path of the ReARM URL. On that domain the API base is `https://tea.acme.example/tea`, with no TEA id in it, and the instance can host the domain's `/.well-known/tea` document for the organization. Three people take part: the operator lists the domains in the ReARM Pro Helm chart, a global administrator assigns each domain to one organization, and that organization's administrator chooses how the organization is served.

### Serving modes

Each organization has a serving mode, chosen by an organization administrator under **Organization Settings -> Admin Settings -> Transparency Exchange API**, in **Serving mode**:

| Serving mode | API base | `/.well-known/tea` |
|---|---|---|
| **Not served** (the default) | none: every TEA path of the organization answers `404` with `OBJECT_UNKNOWN` | none |
| **Legacy path with the organization TEA id** | `<your ReARM URL>/tea/<organization TEA id>` | hosted by the organization on its TEI domain, as [above](#hosting-well-known-tea-on-your-tei-domain) |
| **On the assigned domain** (offered once a domain is assigned) | `https://<domain>/tea` | the organization's choice, see [Hosting `/.well-known/tea` on the domain](#hosting-well-known-tea-on-the-domain) |

Every organization starts as **Not served**, existing organizations included: after the upgrade that brings serving modes, an organization's TEA API answers nothing, at the earlier base too, until its administrator chooses a mode. To keep serving at the earlier base, choose **Legacy path with the organization TEA id**; the base and the TEA ids do not change.

Choosing a serving mode makes this organization's public TEA content reachable at the API base below at once. Choosing **Legacy path** or **On the assigned domain** also mints the organization's TEA id at once if it has none, so the organization is served right away. Where the panel shows **Legacy path with the organization TEA id** greyed out for an organization without a TEA id, mint the id first by saving TEA publishing settings with publishing ENABLED or by creating an external key, or choose **On the assigned domain**, then choose the legacy path. Choosing **Not served** never removes the TEA id. The panel shows the resulting **TEA API base** exactly as the server advertises it, and the change is saved as soon as it is made.

A client appends `/v1.0.0` to the base on every shape, so on a domain the endpoints are `https://<domain>/tea/v1.0.0/...`, for example `https://tea.acme.example/tea/v1.0.0/products`, and the token endpoint of the [external keys](#authentication) is `https://<domain>/tea/v1.0.0/token`. On a custom domain the host alone names the organization: a path with a TEA id in it, `https://<domain>/tea/<TEA id>/v1.0.0/...`, is answered `404` with `OBJECT_UNKNOWN`. (The ReARM host, even when assigned, keeps serving the legacy paths of the organizations served at the legacy path.)

The same choice is available through GraphQL as `setTeaOrgServing(org, teaServing, teaWellKnownHosting)`, with `teaServing` one of `NONE`, `LEGACY_UUID`, `DOMAIN` and `teaWellKnownHosting` one of `NONE`, `DOMAIN`; it takes ADMIN on the organization. `teaOrgDiscovery(org)` answers the effective `apiBase` (null while not served), the assigned host and, while the instance hosts it, `wellKnownUrl`.

### Assigning a domain to an organization

A global administrator assigns domains in **System Settings** (the gear icon **Global Admin Settings** in the top bar), section **TEA Custom Domains**. The table lists every assignable host with its organization:

- the custom domains the operator listed in the Helm chart (see [Listing the domains in the Helm chart](#listing-the-domains-in-the-helm-chart));
- the ReARM host itself, tagged **ReARM host**, when the host of the ReARM URL is a DNS name, not an IP address. It is assignable without being listed and is unassigned by default; an installation may leave it unassigned. Once it is assigned and its organization chooses **On the assigned domain**, the ReARM host serves that organization's TEA API at `<your ReARM URL>/tea/v1.0.0/...` (base `<your ReARM URL>/tea`) and, if the organization chooses it, its `/.well-known/tea`, while the ReARM host keeps serving everything else as before.

Pick an organization and **Assign**, or **Unassign** an assigned host. A host belongs to at most one organization and an organization holds at most one host. An assignment is refused, with the reason, when the host is already assigned, when the organization already holds another host (unassign that one first) or when the host is neither listed nor the ReARM host. A host cannot be unassigned while its organization's serving mode is **On the assigned domain**: the organization's administrator sets the serving mode to **Not served** or **Legacy path** first (an archived organization's host can be unassigned at any time). A host that the operator removed from the chart's list while it was assigned stays in the table, tagged **not in the allow-list**: it serves nothing until it is listed again, and it can be unassigned once its organization is no longer **On the assigned domain**.

ReARM does not verify that an organization owns the domain assigned to it. The assignment is trusted as the global administrator makes it: check who controls the domain before you list it in the chart and before you assign it, because the organization's public TEA content is served under that domain as soon as the organization chooses **On the assigned domain**. To stop serving on a domain at once, either the organization leaves **On the assigned domain** (its administrator chooses another serving mode in the organization's Transparency Exchange API settings, or a global administrator does so through `setTeaOrgServing`), or the operator removes the domain from `tea.customDomains` and upgrades the chart (and, with your own ingress, stops routing the domain), after which the domain serves nothing. Removing the domain from the list does not change the organization's serving mode: the host can be unassigned once the organization has left **On the assigned domain** (or is archived). Unassigning frees the host for another organization and stops no serving by itself.

Through GraphQL, global administrators use `teaHostAssignments`, `assignTeaHost(host, org)` and `unassignTeaHost(host)`.

### Hosting `/.well-known/tea` on the domain

While the organization is served **On the assigned domain**, its administrator chooses, in **/.well-known/tea**, whether the instance hosts the domain's discovery document:

- **Hosted on `<domain>`**: `GET https://<domain>/.well-known/tea` answers the organization's document, with `url` set to `https://<domain>/tea` and `Cache-Control: public, max-age=300`; the panel shows it under **Hosted at**. Use this when the domain is the TEI domain of the organization's TEIs, so that a consumer holding `tei://<domain>/uuid/<TEA release id>` finds the API on the domain itself.
- **Not hosted by this instance**: `https://<domain>/.well-known/tea` answers `404` with `NOT_IMPLEMENTED`, and the organization hosts the document on its TEI domain as [above](#hosting-well-known-tea-on-your-tei-domain); the panel shows the document to copy, naming the domain base.

Leaving **On the assigned domain** also sets the hosting back to **Not hosted by this instance**.

### What a custom domain serves

A custom domain serves the organization's TEA API and its `/.well-known/tea`, and nothing else:

- only `/tea/...` and `/.well-known/tea` are routed to ReARM on it; with the chart's Traefik routes (`useTraefikLe` or `traefikBehindLb`) every other path, the ReARM UI, its login pages and its GraphQL and REST APIs included, is answered `404` by Traefik (`404 page not found`); with your own ingress, it is that ingress's answer, or the `404` of the UI's nginx below if the ingress forwards the path;
- behind the ingress, the UI's nginx serves these hosts from a server block that again forwards only `/tea/` and `/.well-known/tea` and answers `404` to everything else, encoded path escapes such as `/tea/..%2findex.html` included, and the server answers `404` with `{"message":"Not Found"}` to any other path that still reaches it on such a host;
- a listed domain that is not assigned, or whose organization is not served on it, answers `404` with `OBJECT_UNKNOWN` on `/tea/v1.0.0/...` and `404` with `NOT_IMPLEMENTED` on `/.well-known/tea`.

The ReARM host is not a custom domain: it keeps serving the UI, the APIs and the legacy TEA bases as before.

### Listing the domains in the Helm chart

The operator lists the custom domains in the ReARM Pro Helm chart:

```yaml
tea:
  customDomains:
    - tea.acme.example
    - tea.other.example
```

The list is empty by default, and an empty list changes nothing in the rendered chart. Each entry must be a bare lowercase DNS name: labels of `a`-`z`, `0`-`9` and inner hyphens, 1 to 63 characters each, 239 characters in all, with no scheme, port, path, wildcard, IP address or trailing dot. The list must have no duplicates and must not contain `leHost`, the host of `projectHost` (the ReARM host is assignable without being listed) or `localhost`. Any other entry fails `helm template` and `helm upgrade` with a message naming the entry and the rule.

Listing a domain only makes it assignable; it serves nothing until a global administrator assigns it and its organization chooses **On the assigned domain**. Point each domain's DNS at the cluster's ingress before you list it.

For each listed domain the chart renders routes that match only `/tea/` and `/.well-known/tea` on that host and send them to the UI service, with the same HTTPS redirect and HSTS middlewares as `leHost` (the chart's `hsts` settings apply to each domain; with `includeSubdomains`, the default, they cover the domain's own subdomains), and it passes the list to the backend (`RELIZAPROP_TEA_CUSTOM_DOMAINS`, the property `relizaprops.teaCustomDomains`, comma-separated) and to the UI (`TEA_CUSTOM_DOMAINS`, space-separated). A change to the list restarts the backend and the UI pods. What the routes look like depends on the ingress mode:

- **`useTraefikLe: true`**: one route per domain on `web` and `websecure`, the latter with `certResolver: le`, so Traefik requests one Let's Encrypt certificate per domain (a domain whose DNS is not ready yet delays only its own certificate). Every domain counts toward Let's Encrypt's rate limits.
- **`traefikBehindLb: true`**: one route per domain on `web` and no TLS settings: your load balancer terminates TLS and must carry a certificate for every listed domain.
- **Both `false`** (your own ingress): no route is rendered. The backend and the UI still get the list; your ingress must route only `/tea/` and `/.well-known/tea` of these hosts to the UI service, passing the `Host` header, and must handle `X-Forwarded-Host` as described under [Forwarded headers](#forwarded-headers). Match each listed domain exactly, with no wildcard (`*.acme.example`) and no regex host: any other name that reaches the UI lands in its main server block, with the whole ReARM UI and its APIs. Route to the UI service, never to the backend Service directly: the UI's TEA-only server block and its `X-Forwarded-Host` setting protect only traffic that passes through the UI. Keep `networkPolicy.enabled` (the default) and name your controller under `networkPolicy.ingressController`, so that only your controller reaches the UI and only the UI reaches the backend; without the controller named the chart renders no policy for the UI, and setting `networkPolicy.backend.fromIngressController` to open the backend to the controller fails the render. The chart ships routes for Traefik only; another ingress kind would be one more template over the chart's host list, which is not shipped.

### Forwarded headers

ReARM picks the organization of a custom domain from the request's host, and it takes the host from `X-Forwarded-Host` when the proxy in front of the backend sends one. A client must therefore never be able to choose that header:

- **With `useTraefikLe` or `traefikBehindLb`**, the chart's custom-domain routes remove any `X-Forwarded-Host` before forwarding (the Middleware `tea-strip-forwarded-host`), so the backend sees the domain the route matched, even when Traefik trusts your load balancer's forwarded headers (`forwardedHeaders.trustedIPs`).
- **With your own ingress**, the chart renders no such Middleware. The UI's nginx sets `X-Forwarded-Host` to the host it routed on in its TEA-only server block, so the backend sees the routed domain whatever your ingress forwards; `kubectl exec <UI pod> -- nginx -T` shows `proxy_set_header X-Forwarded-Host $host;` in the server block whose `server_name` lists your domains. With a UI image whose nginx does not show that line, an earlier one, your ingress must set `X-Forwarded-Host` to the routed host or remove it; otherwise a client chooses the TEA organization, and, if a shared cache in front of the ingress (a CDN or a caching proxy) keys on the URL, one such request can make the domain's `/.well-known/tea` name another organization's API for every consumer for up to 5 minutes (it is served with `Cache-Control: public, max-age=300`).
- **On the ReARM host's own routes** (`leHost`), in every ingress mode, the UI's nginx passes `X-Forwarded-Host` on as it receives it, and ReARM takes the TEA host from it: a request to `<your ReARM URL>/.well-known/tea` with `X-Forwarded-Host: tea.acme.example` would be answered as that domain's document, and, through a shared cache keyed on the URL, for every consumer for up to 5 minutes. Traefik replaces a client's `X-Forwarded-Host` unless it trusts the sender, so whatever it trusts in front of it, and your own ingress, must set `X-Forwarded-Host` to the routed host or strip it on these routes too.

### Upgrading and the order of steps

Take these steps in order; the chart cannot check the images it is given:

1. Upgrade the backend to a ReARM Pro release with custom domains. Its startup log names the custom domains it read (`TEA custom domains: <n> listed, instance host <host>`). An earlier backend ignores the list and serves the legacy TEA bases on every host, a customer's domain included.
2. Upgrade the UI image to one whose nginx carries the TEA-only server block for custom domains and, with your own ingress, the `X-Forwarded-Host` setting of [Forwarded headers](#forwarded-headers). `kubectl exec <UI pod> -- nginx -T` shows `server_names_hash_bucket_size 512;` and a server block named `tea-custom-domains.invalid` while no domain is listed. An earlier UI image ignores the list and serves the ReARM UI and its login pages on a custom domain for crafted paths.
3. List the domains in `tea.customDomains` and upgrade the chart.
4. A global administrator assigns each domain to its organization.
5. Each organization's administrator chooses the serving mode and the `/.well-known/tea` hosting.

The serving modes arrive with step 1: from then on every organization is **Not served** until its administrator chooses a mode, whether or not any domain is listed (see [Serving modes](#serving-modes)).

### Other deployments

Outside the ReARM Pro Helm chart, set the same two variables yourself: `RELIZAPROP_TEA_CUSTOM_DOMAINS` on the backend (the property `relizaprops.teaCustomDomains`, which the backend's messages name), the domains separated by commas, and `TEA_CUSTOM_DOMAINS` on the UI container, the same domains separated by spaces, following the rules of [Listing the domains in the Helm chart](#listing-the-domains-in-the-helm-chart). The backend refuses to start on an entry that is not a bare hostname, and the UI container exits on a value that is not lowercase hostnames separated by spaces. Route the domains as described for your own ingress above, with TLS for each domain: each listed domain matched exactly, only `/tea/` and `/.well-known/tea`, to the UI container and never to the backend, with `X-Forwarded-Host` handled as under [Forwarded headers](#forwarded-headers).

## SBOM profiles

An SBOM profile is, in the words of the GraphQL schema, "A named SBOM profile: the recipe of an aggregated SBOM at a scope. Channels name profiles; a lower scope overrides a higher one by defining the same name." An SBOM profile carries no visibility, no TEI, no cascade and no publishing switch: those belong to the [TEA publishing settings](#tea-publishing-settings), and who may read what an SBOM profile produces is decided by the TEA publishing settings that name it, never by the profile.

SBOM profiles live at three scopes: the organization, a perspective (ReARM Pro; never a product-based perspective) and a component or product (a product is a component here). A component's or product's row of a name is either an override with a recipe of its own, or a choice to follow one of the component's perspectives for that name; a following row carries no recipe fields and no description.

A name matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` and names are compared exactly, so `Default` and `default` are different profiles. A description is at most 1024 characters. `default` is the one name with a built-in: it answers at every scope where no row defines it; any scope may define its own `default`, which overrides the built-in there, and deleting the organization's `default` reverts to the built-in.

The recipe (the built-in `default` in brackets):

| Setting | Values |
|---|---|
| Dependency depth | `FULL`, `TOP_LEVEL_ONLY` [`FULL`] |
| Optional dependencies | `INCLUDE`, `EXCLUDE` [`INCLUDE`] |
| SBOM structure | `FLAT`, `HIERARCHICAL` [`FLAT`] |
| SBOM sources | any of `DELIVERABLE`, `RELEASE`, `SOURCE_CODE`, at least one [all three] |
| Excluded coverage | any of `DEV`, `TEST`, `BUILD_TIME`, may be empty [`DEV` and `TEST`] |
| Support metadata | `INCLUDE`, `EXCLUDE` [`EXCLUDE`]: INCLUDE needs the organization support injection setting; it is refused while that setting is off and reads as EXCLUDE while it is off |
| Internal metadata | `INCLUDE`, `EXCLUDE` [`EXCLUDE`] |
| Vulnerability documents | `NONE` (reserved) |

Every field is required on a save; the server defaults none.

### Resolving an SBOM profile by name

A component or product resolves an SBOM profile by name, one name at a time:

1. its own row of that name: an override, or the row of the same name of the perspective it follows for that name;
2. else the row of the single perspective of the component that defines the name; when several of its perspectives define the name and the component neither overrides nor follows one of them for that name, the name resolves to a conflict;
3. else the organization's row of the name;
4. else, for `default`, the built-in;
5. else the name is not found.

A name defined at a lower scope resolves there and below, and is not found above. A product resolves like a component, through the real perspectives it is a member of, and conflicts the same way: when two of its perspectives define the name and the product neither overrides nor follows one of them for that name, the name resolves to a conflict, which the product settles on its TEA tab. A perspective derived from a product is never a candidate, so a product is never a candidate perspective of its own components: its settings reach their releases through the cascade (see [Products](#products)). The catalog of a scope lists `default` and every name stored at the organization, at the candidate perspectives and at the object, each with how it resolves there (`RESOLVED`, `CONFLICT` or `NOT_FOUND`). The catalog does not say whether a publish would succeed; the publish preview does.

### Editing SBOM profiles

The organization's SBOM profiles are edited on the Profiles tab of Organization Settings. A component's or product's overrides are on its TEA tab, by the same name, pre-filled from what the scope would otherwise resolve to. A perspective's are in its TEA dialog on the Perspectives tab of Organization Settings (ReARM Pro). These are the same places as the TEA publishing settings below. Screenshots and the exact labels of these screens follow once the UI task that builds them lands.

What the API enforces: reading an SBOM profile needs read access on its scope's object, and `saveSbomProfile` and `deleteSbomProfile` need ADMIN on the scope's object, that is an ADMIN grant on the component or product, on the perspective, or organization-wide; an organization admin holds it at every scope.

In the UI, the editors of these places (the Profiles tab, the TEA tab's editors, the perspective's TEA dialog) are editable by organization admins, and a perspective, component or product admin can also save through the user GraphQL API with the operations below.

An SBOM profile carries no visibility, so saving one needs no organization admin. But a component, perspective or product admin who defines or deletes an SBOM profile of a name that PUBLIC TEA publishing settings publish changes what is served publicly from the next publish on: the sources, dependency depth, structure, excluded coverage and internal metadata of that aggregated SBOM. A product admin shapes the aggregated SBOMs of the component releases published with the product in the same way, although a product grant writes nothing on those components. None of this needs an organization admin. The controls that stand:

- Every saved SBOM profile records the user who saved it, and a change to an existing SBOM profile or a delete keeps the previous revision in the audit history. A first save of a name at a scope writes no audit record, and the record a delete keeps names who last saved the SBOM profile, so a delete does not record who deleted the SBOM profile.
- A change takes effect only at the next publish act, never at request time.
- Support metadata stays under the organization's support-injection setting whoever saves the SBOM profile: INCLUDE is refused while that setting is off and reads as EXCLUDE.
- Visibility itself, PUBLIC and the names a PUBLIC row publishes, stays behind the organization-admin gate of the [TEA publishing settings](#tea-publishing-settings).

An SBOM profile that TEA publishing settings or [package settings](../workflows/regulatory-packages#package-settings) name, and that would stop resolving there, cannot be deleted until they name another SBOM profile.

### SBOM profiles in the API

The user GraphQL API reads and writes SBOM profiles with `sbomProfiles(org, scope, object)`, `sbomProfilesOfOrg(org)`, `sbomProfile(org, scope, object, name)`, `sbomProfileEffective(org, scope, object, name)`, `sbomProfileCatalog(org, scope, object)`, `sbomProfileEditorView(org, scope, object, name)`, `saveSbomProfile(org, scope, object, name, profile)` and `deleteSbomProfile(org, scope, object, name)`. FREEFORM and USER API keys with read access on the scope's object read them with `sbomProfilesProgrammatic(scope, object)` and `sbomProfileEffectiveProgrammatic(scope, object, name)`; there is no programmatic write.

SBOM profiles work the same on both editions, except that the perspective scope is ReARM Pro, a ReARM Community Edition installation keeps one SBOM profile, `default` (a save under any other name stores `default`; a component or product can still override it), and the number of SBOM profiles a TEA publishing settings row may name differs (see [Edition differences](#edition-differences)).

## TEA publishing settings

TEA publishing settings say whether, how and to whom a scope's releases are published on the Transparency Exchange API (the GraphQL type `TeaProfile` and the perspective's TEA dialog of Organization Settings still call them a TEA profile). The schema describes them as "the channel fields and the names of the SBOM profiles it publishes; a recipe lives in an SBOM profile".

TEA publishing settings live at three scopes: the organization, a perspective (ReARM Pro) and a component or product. Settings at a lower scope replace their parent as a whole, never field by field; the editor pre-fills new settings from what the scope would otherwise resolve to.

A component resolves its TEA publishing settings in this order: its own row when it carries one (an override, or a choice to follow one of its perspectives), then the row of its single perspective that has one, then the organization's row, then the built-in defaults. When two or more of the component's perspectives carry TEA publishing settings and the component has none of its own, the component is in conflict: publishing it is refused, naming the perspectives, until the component overrides or follows one of them. A product resolves its TEA publishing settings like a component, through the real perspectives it is a member of, and conflicts the same way, with the same remedy on its TEA tab. A perspective derived from a product is never a candidate, so a product's settings reach its component releases through the cascade only (see [Products](#products)).

TEA publishing settings carry these fields (defaults in brackets):

| Setting | Values |
|---|---|
| Publishing | `ENABLED`, `DISABLED` [`DISABLED`]: DISABLED conceals everything resolving to the row and blocks new publishes |
| Visibility | `PRIVATE`, `PUBLIC` [`PRIVATE`]: resolved at request time; only an organization admin can make a component PUBLIC: by saving PUBLIC TEA publishing settings, by choosing to follow a perspective whose TEA publishing settings are PUBLIC or removing an override under one, by adding the component to such a perspective or removing the perspective that kept it private, or by removing a perspective's TEA publishing settings or deleting a perspective its members resolved through |
| SBOM profiles | the names of the SBOM profiles the row publishes, in order, one aggregated SBOM each [`default`]; a row names exactly one SBOM profile on ReARM Community Edition (a save naming more is refused with the reason) and up to 10 on ReARM Pro |
| Raw artifacts | `NONE`, `NON_BOM`, `ALL` [`NONE`]: the [ride-along artifacts](#ride-along-artifacts) |
| Product components | `PUBLISH_WITH_PRODUCT`, `PRODUCT_ONLY` [`PUBLISH_WITH_PRODUCT`]: the cascade, see [Products](#products) |
| Minimum lifecycle | `ASSEMBLED`, `READY_TO_SHIP`, `GENERAL_AVAILABILITY` [`ASSEMBLED`] |
| TEI | `DISABLED`, `UUID` [`DISABLED`], with the TEI domain (required with UUID: ASCII, lower case, with a dot): UUID stamps `tei://<TEI domain>/uuid/<TEA release id>` onto published releases |

When publishing is ENABLED, every named SBOM profile must resolve at the scope: a save naming one that does not is refused, naming the profile and the remedy (define it there or at a higher scope, or settle the conflict on the component). The names of a row with publishing DISABLED are not checked. A row that follows a perspective sends no names and takes the followed perspective's, which must resolve at the component. A save that makes anything PUBLIC takes an organization admin, and PUBLIC settings show a red banner wherever they apply. What the API enforces: `saveTeaProfile` and `deleteTeaProfile` need ADMIN on the scope's object (an ADMIN grant on the component or product, on the perspective, or organization-wide; an organization admin holds it at every scope), and any save that leads to PUBLIC needs an organization admin. In the UI, the editors of TEA publishing settings (under Organization Settings, Transparency Exchange; on the TEA tab; in the perspective's TEA dialog) are editable by organization admins, and a perspective, component or product admin can also save through the user GraphQL API. The organization's row cannot be deleted.

Edit the organization's TEA publishing settings under Organization Settings, Transparency Exchange, where the profile picker names the SBOM profiles to publish, in publishing order (a multi select on ReARM Pro); a component's or product's in its settings, on the TEA tab; a perspective's from its TEA dialog on the Perspectives tab of Organization Settings (ReARM Pro). Screenshots and the exact labels follow once the UI task that builds them lands.

An archived component or product keeps its TEA publishing settings so an admin can hide what it published: it accepts only settings with publishing DISABLED and visibility PRIVATE, and the Transparency Exchange table tags it as archived. An archived component or product also takes no perspective change that would make it resolve to settings with publishing ENABLED or visibility PUBLIC.

Creating a component or product in a perspective whose TEA publishing settings are PUBLIC (from the perspective's page, through an API key with write access to it, or by a CI run that names it) makes the new component PUBLIC from the start: the organization admin who made the perspective PUBLIC chose that for its members, and a new component exposes nothing it published before.

### Edition differences

The only table of edition differences for TEA, SBOM profiles and regulatory packages:

| Where | ReARM Pro | ReARM Community Edition |
|---|---|---|
| The Transparency Exchange API surface, publishing, external keys | Served | Served, at `<your ReARM URL>/tea/<organization TEA id>` (no serving modes); the Transparency Exchange API and External Keys tabs show on both editions |
| [Custom domains](#custom-domains-rearm-pro): serving modes, domain assignment, `/.well-known/tea` on the domain | Supported: an organization is Not served, served at the legacy path or on its assigned domain; global administrators assign the domains the Helm chart lists | Not offered: no serving modes and no domain assignment; one organization on the ReARM domain, served at the legacy path (System Settings has no TEA Custom Domains section, and the Transparency Exchange API tab has no serving mode or domain choice) |
| SBOM profiles | Organization, perspective and component scope | Organization and component scope (CE has no perspectives) and one SBOM profile, `default` (a save under another name stores `default`), with the same recipe fields and resolution |
| SBOM profiles a TEA publishing settings row names | Up to 10, one aggregated SBOM each | Exactly one SBOM profile (a save naming more is refused with the reason) |
| [Regulatory packages and submissions](../workflows/regulatory-packages) | Supported (`FDA_PREMARKET`) | Not offered: no package settings and no submissions, in the UI or from the server (a submission read answers an empty list and a submission act answers `EDITION_UNSUPPORTED`; package settings are ReARM Pro only) |

## Publishing releases

Publishing is an explicit act on a release: nothing is published on TEA until someone publishes it or an action of an approval rule publishes it (Automatic publication below), and what is published does not change until it is re-published. On ReARM Community Edition the TEA publishing settings name exactly one SBOM profile.

### What a publication is

A publication gives the release a TEA release id, which is also the id of its TEA collection; the id never changes. Each publish resolves the component's TEA publishing settings, then each SBOM profile they name at the component, and generates one aggregated SBOM per named profile, in the settings' order, with that SBOM profile's recipe (dependency depth, optional dependencies, structure, sources, excluded coverage, support and internal metadata). Each aggregated SBOM is frozen as a generated artifact of the release named after its SBOM profile, listed under Generated artifacts on the release page as `<component>-<version>-<profile name>-tea-aggregate.cdx.json`, and published as one TEA artifact with its own TEA id and revision. The collection then gets a new version: version 1 on the first publish, and one more each time the published content changes. Every collection version is immutable and keeps its own list of artifacts, each with a TEA id and a revision, and the reason it was written (initial release, VEX updated, artifact added, removed or updated).

### Gates

A publish is refused, with the reason, when:

- the release lifecycle is below ASSEMBLED, or below the minimum lifecycle of the TEA publishing settings;
- the component resolves to no single TEA publishing settings row (a conflict between perspectives), or the row has publishing DISABLED, or one of its named SBOM profiles does not resolve at the component (`PROFILE_NOT_FOUND`) or resolves to a conflict (`PROFILE_CONFLICT`): refused before anything is generated, naming the SBOM profile;
- the release or its component is archived, or the release has no SBOM to aggregate. A component release of a product that has no SBOM of its own is left out of the product's TEA aggregate and reported in the publish summary as having no SBOM; ReARM's own exports of the product's SBOM keep a placeholder for it;
- the caller lacks the Publish Externally (TEA) permission on the release's component. Users and RBAC (FREEFORM or USER) API keys can hold it; organization admins have it implicitly;
- another publish or hide of the same release is still running, on this or another ReARM instance, after 30 seconds of waiting (five minutes for automatic publication); the act can simply be repeated once it has finished.

### Re-publish and hide

Re-publish regenerates every named aggregated SBOM and writes a new collection version only when any of them, or a ride-along artifact, changed; otherwise it reports that nothing changed since the latest version. An SBOM profile taken off the list is recorded as removed in the next version while its earlier revisions stay served; an SBOM profile added is recorded as added; an SBOM profile put back keeps its TEA id; a change of order alone writes nothing. Hide stops serving the release on TEA. Nothing is deleted: the collections stay, and a later re-publish makes the release served again and continues their numbering. To a TEA consumer, a hidden release, and every collection and artifact only it published, answers `404` with `{"error": "OBJECT_UNKNOWN"}`, exactly as something that never existed.

Visibility is live, content is frozen: the visibility (PRIVATE or PUBLIC) and publishing setting of the TEA publishing settings are applied when TEA is read, so changing them changes who can read what is already published, while the content of each collection version stays as it was published.

A published release whose lifecycle falls below ASSEMBLED (cancelled, rejected, pending, draft) is concealed on TEA while it stays there, without a hide; it is served again once it is back at ASSEMBLED or beyond.

A publish of a large product can outlast the web proxy's limit; the page then reports a gateway error while the act completes on the server. Reload the release page to see the result; repeating the act is safe, because a publish of what is already published answers that it is already published or unchanged, and a hide of a hidden release changes nothing.

### Automatic publication

The actions TEA Publish (TEA_PUBLISH) and TEA Hide (TEA_HIDE), configured on a component's Actions tab or as policy-wide actions of an approval policy like a VDR snapshot and with no settings of their own, run after the rule's pass on a queue; what they publish is decided by the TEA publishing settings the component resolves to at firing time, and when they are PUBLIC the editor warns before the save and marks the action. Configuring them, the rules and global-rule refs that fire or disable them on any approval policy of the organization, an approval policy that carries them, or a component name an organization rule maps to such a policy, needs the Publish Externally (TEA) permission on the component; a TEA_PUBLISH fires only from actions, rules and refs that a holder of that permission or an organization admin saved, and a publish whose wiring was changed any other way is withheld with a History line (TEA_PUBLISH withheld: NOT_AUTHORIZED) until a holder edits and saves it on the component, or an organization admin saves the policy's lists again; a component's ref that overrides a global rule's outputs to a TEA_PUBLISH is authorized by the ref and the action, and the overridden rule contributes its condition only; a hide always fires; a component created under a matching name is enrolled by the organization's rule; a global action's TEA kind, and whether a global rule fires one, cannot change under the same action or rule, and a removed global's uuid is never reused (remove it and create a new one); while a hide rule matches no automatic publish runs, in the pass or at the act (History: TEA_PUBLISH withheld); a hide that arrives while a publish waits runs instead of it; a refused act is on the release's History as a GUARD line; a publish in progress is retried within minutes; the property relizaprops.teaOutputEventsEnabled pauses automatic publication on an instance.

### Products

When a product's TEA publishing settings have Product components set to PUBLISH_WITH_PRODUCT, publishing the product also publishes each of its component releases of the product's own organization; a component release of another organization is reported as not found and left untouched. The product's named SBOM profiles shape each component release's aggregated SBOMs, one per product name in the product's order. The component's own TEA publishing settings still gate it: a component release whose own settings have publishing DISABLED, or that is in conflict, is refused and reported without stopping the product, and its visibility is never wider than its own settings allow, nor wider than the product's. Raw artifacts follow the narrower of the two settings, and the stricter of the two minimum lifecycles applies. For each product name, the internal metadata of the aggregated SBOM is capped by the component's own view of that name: its own definition of the name where it has one, else the SBOM profiles its own TEA publishing settings name. A component release is refused only over the SBOM profiles of its own settings, never over a product name. A component release that cannot be published (for example a DRAFT one) is reported without stopping the product. A component release that is already published directly, or that was hidden, is left as it is. A component release bundled by several products belongs to the product that published it first: the others report it as published with that product, and a product takes it over only when that product is hidden or no longer lists it. Hiding the product conceals the component releases published with it.

### Ride-along artifacts

With the Raw artifacts setting of the TEA publishing settings at NON_BOM or ALL, the release's own artifacts are published alongside the aggregated SBOMs, after them in the collection (NON_BOM leaves out BOMs). An artifact is publishable when its version is an integer (its TEA revision) and ReARM holds its checksum: for an artifact stored in ReARM, the retained raw bytes of the upload; for an externally stored one, a download link and a declared checksum. Signatures are published with the artifact they sign. An artifact that does not qualify is skipped and named in the publish summary; it never fails the publish.

An external download link is published only when it is an absolute `http` or `https` URL of printable ASCII characters, at most 4096 octets long, with a host and without user information; an artifact whose link is anything else is skipped with `SKIPPED_LINK_NOT_HTTP_URL`, and the same rule leaves out a deliverable's `distributions[].url` that does not meet it. A signature stored outside ReARM is published as the format's `signatureUrl` under the same rule, and its download is a `302` to that link.

### TEI

When the TEI setting of the TEA publishing settings is UUID, the publication carries the TEI `tei://<TEI domain>/uuid/<TEA release id>`, recomputed on each publish.

### On the release page and in the API

The release page header offers Publish on TEA, and once published a TEA badge with the collection version (red when the release is public), Re-publish on TEA and Hide from TEA. Each asks for confirmation first; PUBLIC settings are called out with the URL anyone can read. A public release also shows a red banner under its title. The Transparency Exchange (TEA) section of the release page shows the state (and why it is concealed, when it is), the TEA release id and URL, the TEA publishing settings applied and their revision (or the product it was published with), and for each aggregated SBOM the SBOM profile that produced it, the TEI, the last publish, and every collection version with its artifacts. The release History tab records each publish, re-publish and hide.

The same acts are available through GraphQL: `publishReleaseOnTea`, `republishReleaseOnTea`, `hideReleaseOnTea` and the query `releaseTeaPublicationView`, and for API keys `publishReleaseOnTeaProgrammatic`, `republishReleaseOnTeaProgrammatic`, `hideReleaseOnTeaProgrammatic` and `releaseTeaPublicationViewProgrammatic`. Notification subscriptions can listen for Release published on TEA and Release hidden from TEA. The publication view lists `aggregateArtifacts { profileName artifact }`, one per published SBOM profile in order; each collection entry carries `sbomProfileName`, `sbomProfileUuid` and `sbomProfileRevision` (null on uploaded artifacts and on revisions published before SBOM profiles were named); the publish preview lists the names in `profiles`. `aggregateArtifact` is deprecated and answers the `default` profile's artifact, else the first.

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

Every `{uuid}` is a TEA id. Collection versions are immutable: version 1 on the first publish, one more each time the published content changes. Each carries its update reason, one of `INITIAL_RELEASE`, `VEX_UPDATED`, `ARTIFACT_UPDATED`, `ARTIFACT_ADDED` and `ARTIFACT_REMOVED`, and its artifact entries, each with a TEA id and a revision. A collection lists one `BOM` artifact per published SBOM profile, named with the bare profile name (a name is not unique on TEA; the TEA id is), the aggregated SBOMs first in the settings' order, then the ride-along artifacts. An SBOM profile taken off the settings stays served at the revisions earlier versions list and is recorded as removed in the next version. The download of an aggregated SBOM carries the file name `<profile name>.cdx.json` (a revision published before SBOM profiles were named keeps its earlier file name until its content changes).

Listings answer one page holding every result. `pageSize` is accepted from 1 to 100 and not applied; any `pageToken` is refused, since none is ever issued. `sortField` and `sortOrder` (`asc`, the default, or `desc`) are checked per operation: `name` for `/products` and `/components`; `createdDate` (the default), `releaseDate` or `version` for the release listings; `version` for the collection listings.

Every TEA error body carries `error` and optionally `message`:

| Status | `error` | When |
|---|---|---|
| `404` | `OBJECT_UNKNOWN` | The object is unknown, hidden or not published, the caller's key does not cover it, a discovery query resolves no served product release, the organization TEA id in the base is unknown, malformed or archived, or (ReARM Pro) the organization is not served at that base: Not served, served at the other shape, or a custom domain that is unassigned or carries a TEA id in the path. |
| `400` | `INVALID_REQUEST` | A parameter is wrong, a query value is not valid UTF-8 or percent-encoding, or a download `mediaType` is not a media type. |
| `400` | `INVALID_PAGE_TOKEN` | A `pageToken` is sent. |
| `406` | `NO_ACCEPTABLE_FORMAT` | A download `mediaType` is absent, blank, a wildcard or names no format of the revision. |
| `404` | `SIGNATURE_NOT_FOUND` | The format has no signature. |
| `404` | `NOT_IMPLEMENTED` | The instance's `/.well-known/tea` outside an installation of type OSS, on a host where the instance hosts no organization's document, or a redirect target above 4096 octets. |

A path that no TEA operation serves is answered `404` with `{"message":"Not Found"}`, which is not a TEA error: the client has a wrong base or version and fails over to the next endpoint, or reports it. On a [custom domain](#what-a-custom-domain-serves) a path outside `/tea/` and `/.well-known/tea` is answered `404` before it reaches ReARM.

Requests are limited to 100 per 30 seconds per address, or per key for a caller with a valid token; above that ReARM answers `429` with `Retry-After` and `{"message":"Rate limit exceeded"}`. The address is the first `X-Forwarded-For` entry, which the edge in front of ReARM must set itself (see [Deploying TEA](#deploying-tea)).

JSON answers, artifact metadata included, carry `Cache-Control: no-cache` for an anonymous caller and `private, no-cache` for a caller with a valid key of the organization, whatever the visibility, with `Vary: Authorization`.

## Authentication

The visibility of the TEA publishing settings a component resolves to decides who reads what it published: PUBLIC settings publish to anyone, PRIVATE settings only to holders of an [external key](../configure/programmatic-access#external-keys-tea) whose grants cover the object. An external key is a key the organization hands to a party outside it; it reaches nothing but TEA. The holder exchanges it for a one-hour Bearer token at the token endpoint on the organization's API base, `POST <API base>/v1.0.0/token` with `grant_type=client_credentials`, where the API base is the one the organization is served at: `<your ReARM URL>/tea/<organization TEA id>`, or `https://<domain>/tea` on a [custom domain](#custom-domains-rearm-pro).

An external key is the only key TEA accepts, and reading TEA is the only thing an external key can do: it has no write authority anywhere, reaches no GraphQL field, no download and no CLI, and reads no regulatory submission (see [Regulatory Packages and Submissions](../workflows/regulatory-packages)).

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

A shared cache may go on serving a copy of a PUBLIC revision for at most one day after the organization hides the publication, archives or rejects the release, sets the TEA publishing settings to PRIVATE or archives itself. ReARM itself refuses at once; copies already downloaded cannot be recalled. An operator who fronts `/tea` with a CDN should purge it on a hide to make the withdrawal take effect sooner.

Downloads need no ReARM login: the visibility of the TEA publishing settings and the external key decide, as for every other TEA object.

## Deploying TEA

The TEA endpoints are always mounted on ReARM Pro, but an organization is served only once its administrator chooses a [serving mode](#serving-modes): every organization starts as **Not served**, existing organizations included after the upgrade that brings serving modes, and answers `404` with `OBJECT_UNKNOWN` on every TEA path until then. On ReARM Community Edition there are no serving modes: an organization is served at `<your ReARM URL>/tea/<organization TEA id>` once it has a TEA id. Custom domains take the steps of [Upgrading and the order of steps](#upgrading-and-the-order-of-steps). What a consumer meets on the base the organization is served at depends on the organization:

- Before the organization has a TEA id there is no base: any path under `/tea/<id>` is answered `404` with `OBJECT_UNKNOWN`, and on an installation of type OSS the instance's `/.well-known/tea` is `404` with `OBJECT_UNKNOWN` while no organization has a TEA id. The TEA id is minted when an organization saves TEA publishing settings with publishing ENABLED, when its first external key is created, or when its administrator chooses a serving mode other than **Not served**.
- Once the TEA id exists, the base answers: the four listings answer `200` with an empty list; discovery answers `404` with `OBJECT_UNKNOWN` for a TEI or purl that resolves no served product release, which, with nothing published, is every query (the specification allows no empty discovery answer); every object is `404` with `OBJECT_UNKNOWN`; the token endpoint issues tokens for the organization's external keys; and on an installation of type OSS the instance's `/.well-known/tea` lists the base. Nothing is served until a release is published, by hand or by a TEA_PUBLISH action.

A consumer who reaches an empty listing has the right base. A `404` with `OBJECT_UNKNOWN` from discovery alone does not mean a wrong base: a listing on the same base tells the two apart.

Behind a proxy:

- A redirect `Location` near the 4096-octet bound needs `proxy_buffer_size 16k` on the `location /tea` of the UI front's nginx until the shipped UI front carries that setting; without it such a redirect is answered `502`.
- The per-address limit counts the first `X-Forwarded-For` entry. The edge in front of ReARM must set `X-Forwarded-For` itself and must not pass on a value the client sent (Traefik does this by default for untrusted sources; an nginx edge uses `$remote_addr`, not `$proxy_add_x_forwarded_for`).

On ReARM Community Edition the TEA surface, SBOM profiles, TEA publishing settings and external keys work as this page describes, at `<your ReARM URL>/tea/<organization TEA id>`, with the one SBOM profile `default`; regulatory packages and submissions, perspectives, serving modes and custom domains are ReARM Pro only (see [Edition differences](#edition-differences)). The earlier 0.4.0 beta and its switch are retired.

## Not implemented yet

- **Page tokens.** Listings answer one page; `pageSize` is not applied and every `pageToken` is refused. A listing above 1000 results is logged on the server.
- **CLE 1.0.0 alignment.** The four CLE operations (`/product/{uuid}/cle`, `/component/{uuid}/cle`, `/productRelease/{uuid}/cle`, `/componentRelease/{uuid}/cle`) are served as ReARM built them for the earlier draft: a product's or component's CLE is the union of the lifecycle events of its served releases, a release's CLE its own events. Alignment with CLE 1.0.0 is a task of its own.
- **VDR and VEX.** ReARM generates no vulnerability document for a publication; the Vulnerability documents setting of the SBOM profile is reserved at NONE.
- **Programmatic writes of SBOM profiles and TEA publishing settings, and programmatic reads of TEA publishing settings.** The programmatic API reads SBOM profiles, through `sbomProfilesProgrammatic` and `sbomProfileEffectiveProgrammatic`, and the publish preview `releaseTeaPublicationViewProgrammatic`, whose `profiles` lists the names a publish would use. TEA publishing settings are read and written through the UI or the user GraphQL API only; this is a server change.
- **Several TEA publishing settings rows per scope.** A scope has one row; several SBOMs come from the row's list of SBOM profiles, not from several rows.
- **Signing.** ReARM signs nothing it publishes; signatures are published as the publisher stored them.
- **`preRelease`.** Releases do not set it.
- **Component discovery.** Discovery answers product releases only; an installation that publishes components only lists them through `/componentReleases?idType=PURL` or `idType=TEI` with `idValue`.
- **The rearm-cli `tea` verbs.** `tea discovery` and `tea full_tea_flow` speak the retired 0.4.0 beta and do not work against this API until a CLI task updates them.
