# ReARM UI — Structural & Visual Inventory

A one-shot snapshot of the current UI to feed into a redesign brief. Cuts: sitemap, every routed page, every shared/widget component, and a visual-style audit. Source: `rearm/ui/` on `main` as of the most recent build.

Stack: **Vue 3.5 + naive-ui 2.44 + Vuex 4 + Apollo Client 4 + Vite 7**. Auth via Keycloak (`keycloak-js 26.2`). Charts via Vega/Vega-Lite. Dialogs split between naive-ui's `n-modal` and SweetAlert2.

---

## 1. Sitemap

### Left-nav (in order)
1. **Home** → `/`
2. **Components** → `/componentsOfOrg/:orguuid/:compuuid?/:branchuuid?`
3. **Products** → `/productsOfOrg/:orguuid/:compuuid?/:branchuuid?`
4. **VCS Repositories** → `/vcsReposOfOrg/:orguuid`
5. **Pull Requests** → `/pullRequestsOfOrg/:orguuid`
6. **Instances** → `/instancesOfOrg/:orguuid`
7. **Secrets** → `/secretsOfOrg/:orguuid`
8. **Statistics** → `/analytics/:orguuid`
9. **Finding Analysis** → `/vulnerabilityAnalysis/:orguuid` (3-tab page: Findings / VEX Proposals / Mitigation Attestations)
10. **Organization Settings** → `/orgSettings/:orguuid`

### Top-right
- **User Profile** → `/profile`
- **System Settings** → `/sysSettings` (global admin only)
- **Org switcher / Perspective switcher** (TopNavBar)
- **Logout**

### Deep-link routes (no nav entry)
- `/release/show/:uuid` — release detail
- `/release/:releaseUuid/sbomComponentGraph/:sbomComponentUuid?` — SBOM dependency graph
- `/pullRequest/show/:uuid` — single PR detail
- `/vcsRepository/:uuid` — single VCS repo detail
- `/changelog/:orgprop/:release1prop/:release2prop/:componenttypeprop/:isrouterlink` — changelog comparison
- `/instancesOfOrg/:orguuid/:instuuid` and `/instancesOfOrg/:orguuid/:instuuid/:subinstuuid?` — instance detail
- `/findingsOverTime/:orguuid`, `/releasesPerDay/:orguuid`, `/mostRecentReleases/:orguuid` — full-page chart variants
- `/vexProposal/:orguuid/:uuid`, `/mitigationAttestation/:orguuid/:uuid` — review pages opened in new tab from the inbox
- `/vexProposalsOfOrg/:orguuid`, `/mitigationAttestationsOfOrg/:orguuid` — redirects to `/vulnerabilityAnalysis?tab=…` (legacy URLs from before the tab restructure)
- `/verifyEmail/:secret`, `/joinOrganization/:secret` — one-shot onboarding links
- `/downloadArtifact/:arttype/:artuuid` — auto-download landing page

---

## 2. Routed Pages

### 2.1 Home / Dashboard

#### `AppHome.vue` — `/`
- **Purpose** — Central dashboard. Search releases / components / SBOM, see findings + most-vulnerable components at a glance.
- **Layout** — `n-grid` two-column. Left col: Releases-Per-Day chart, Most-Active selector, Search Releases (4-tab `n-tabs`: by text/digest, by tags, by SBOM components, by findings). Right col: Findings-Over-Time chart, Most Vulnerable Components list, Most Recent Releases widget.
- **Key data shown** — Release frequency, active components/products/branches/feature-sets, vulnerable components with severity counts (Critical/High/Medium/Low/Unassigned + policy violations).
- **GraphQL queries** — `releaseTagKeys`, `searchDigestVersion`, `sbomComponentSearchNative`, `releasesBySbomComponents`, `mostVulnerableComponentsPerOrg`, `findingsPerDay` (or `findingsPerDayByPerspective`).
- **User actions** — Component-type dropdown; cutoff date picker; search submit; click severity circles → `VulnerabilityModal`; click row → release view.
- **Sub-components** — `ReleasesPerDayChart`, `MostActiveChart`, `FindingsOverTimeChart`, `ReleasesByCve`, `ComponentBranchesTable`, `VulnerabilityModal`, `MostRecentReleasesWidget`, `InstanceHistory`.
- **Interactions** — Search results emit modals (Release Comparison, SBOM Component Search with progress bar, Instance Property Search, Instance Changes by Date). Deep links via URL.

### 2.2 Components / Products

#### `ComponentsOfOrg.vue` — `/componentsOfOrg/:orguuid/:compuuid?/:branchuuid?` (and `ProductsOfOrg`)
- **Purpose** — Browse and manage components or products of an org; drill into details.
- **Layout** — Two-column grid. Left: title, add icon, collapsible search input, `n-data-table` (10/page). Right: `ComponentView` for the selected component.
- **Key data** — Component/product name (single-column table); selected component details in right panel.
- **Queries** — Through store (`fetchComponents`, `fetchProducts`); no inline gql.
- **Actions** — Add → `CreateComponent` modal. Search (case-insensitive name filter). Single-click row to select. Double-click row → component settings view.
- **Sub-components** — `CreateComponent`, `ComponentView`.
- **Interactions** — Deep link via `compuuid`/`branchuuid` query params. Pagination syncs the selected component to its page. Selected row highlighted via custom row class.

### 2.3 Pull Requests

#### `PullRequestsOfOrg.vue` — `/pullRequestsOfOrg/:orguuid`
- **Purpose** — Browse PRs across the org with filtering + validation status.
- **Layout** — Header with search input + state checkboxes (OPEN/MERGED/CLOSED) + loading spinner. `n-data-table` (25/page) columns: Identity (link), Title, State (`NTag`), Validation (`NTag`), Target VCS (filterable dropdown, link), Commits count, Created (calendar w/ tooltip), Closed/Merged date, Endpoint (external link).
- **Key data** — PR identity, title, state, validation (SUCCESS/FAILURE/PENDING/NEUTRAL/SKIPPED/CANCELLED), target VCS name, commit count, dates (UTC + local in tooltip), SCM endpoint.
- **Queries** — `fetchVcsRepos`, `fetchPullRequestsOfOrg` (via store).
- **Actions** — Search by identity/title/VCS, toggle state filters (persists as `?states=...`), Target VCS filter (`?vcs=<uuid>`), click PR → `PullRequestView`.
- **Sub-components** — None.
- **Interactions** — URL-hydrated filters. `renderDateCell` helper for consistent date display. `validationDisplay` map for tag colors.

#### `PullRequestView.vue` — `/pullRequest/show/:uuid`
- **Purpose** — Detail view of a single PR with attributed releases + validation events.
- **Layout** — Header (identity + state tag), meta block (target/source VCS, branches, endpoint, commits count, head SHA + commit message), three `n-data-table`s: Releases at PR head, PR validation events, Release validation events. Each table includes UUID copy-tooltip icons.
- **Key data** — PR identity/title/state/endpoint, target+source VCS/branch names, head SHA (12-char), commit message, attributed releases (component/version/lifecycle/created), validation timelines (verdict, head SHA, comment).
- **Queries** — `fetchPullRequest` (store), `fetchVcsRepos`.
- **Actions** — VCS name → `VcsRepository`; release version → `ReleaseView`; UUID copy via icon; hover for full datetime tooltip.
- **Sub-components** — None (uses `h()` to render tooltip+icon inlines).
- **Interactions** — `sortByDateDesc` reverses events newest-first. Reusable `renderUuidTooltip`.

### 2.4 VCS

#### `VcsReposOfOrg.vue` — `/vcsReposOfOrg/:orguuid`
- **Purpose** — Browse / manage VCS repos for the org; see connected components.
- **Layout** — Header (title, add icon, collapsible name/URI search). `n-data-table` (10/page) with inline-editable name & URI columns; actions = external link, settings, PRs, connected components, copy UUID, archive.
- **Key data** — Repo name + URI + edit state; per-row action stack.
- **Queries** — `fetchVcsRepos` (store).
- **Actions** — Add → `CreateVcsRepository` modal. Inline edit (Enter / Escape). Open URI. Settings → `VcsRepository`. View connected components (modal). Archive (confirm dialog).
- **Sub-components** — `CreateVcsRepository`.
- **Interactions** — Connected-components modal lists components per repo.

#### `VcsRepository.vue` — `/vcsRepository/:uuid`
- **Purpose** — Configure PR validation trigger (EXTERNAL_VALIDATION) for one VCS repo; show inherited-vs-per-repo provenance.
- **Layout** — Title, meta URI, provenance section (source / matching rules / stale rules), PR-validation section (empty state | trigger summary | edit form), save/cancel.
- **Key data** — Repo name + URI; trigger source (`PER_VCS` / `ORG_RULE` / `NONE`); matching-rule names; integration label (GH App note/identifier/UUID); installation ID; check-name override.
- **Queries** — `ciIntegrations`; `setVcsRepoOutputTriggers`, `fetchEffectivePrValidationTrigger` (store).
- **Actions** — Add/edit/remove validation trigger; navigate to PR list (GitPullRequest icon).
- **Sub-components** — None.
- **Interactions** — Edit-mode toggle. `provenance-ok`/`provenance-none` CSS classes for visual distinction. Integration dropdown filters to `GITHUB` + `PR_VALIDATE` capability.

### 2.5 Instances / Deployments

#### `InstancesOfOrg.vue` — `/instancesOfOrg/:orguuid/:instuuid?/:subinstuuid?`
- **Purpose** — Browse / manage deployment instances + clusters; drill in.
- **Layout** — Two-column grid. Left: title, add (instance + cluster) icons, filter popover (env / type / createdBy + reset), instance `n-data-table` (10/page), cluster `n-data-table`. Right: `InstanceView` for selection.
- **Key data** — Instance/cluster URI + env/type tooltip; cluster membership; details in `InstanceView`.
- **Queries** — `fetchInstances` (store), `EnvironmentTypesGql`.
- **Actions** — Add → `CreateInstance` modal. Filter by env/type/createdBy. Row click selects + loads detail panel.
- **Sub-components** — `InstanceView`, `CreateInstance`.
- **Interactions** — Selection driven by route param. Inline filter persistence.

### 2.6 Statistics / Analytics

#### `AnalyticsOfOrg.vue` — `/analytics/:orguuid`
- **Purpose** — Org-wide totals as a key-value table.
- **Layout** — Title, loading state, two-column `n-data-table` (metric / count), alphabetised.
- **Key data** — VCS Repositories, Components, Products, Releases, Admin & Write Users, Read-Only Users, Instances, Branches/Feature Sets, Artifacts.
- **Queries** — `totalsAnalytics`.
- **Actions** — None (read-only).
- **Sub-components** — None.

#### `FindingsOverTimePage.vue` — `/findingsOverTime/:orguuid`
- **Purpose** — Full-page chart of findings over time.
- **Layout** — Header w/ start/end `n-date-picker`. Chart container (500px) → `FindingsOverTimeChart`. URL-synced.
- **Queries** — Delegated to chart sub-component.
- **Sub-components** — `FindingsOverTimeChart`.

#### `ReleasesPerDayPage.vue` — `/releasesPerDay/:orguuid`
- **Purpose** — Full-page chart of releases per day.
- **Layout** — Header w/ date pickers. Chart container (500px) → `ReleasesPerDayChart`. Computes `daysBack` from the range.
- **Queries** — Delegated to chart.
- **Sub-components** — `ReleasesPerDayChart`.

#### `MostRecentReleasesPage.vue` — `/mostRecentReleases/:orguuid`
- **Purpose** — Full-page list of most-recent releases with filters.
- **Layout** — Header w/ date pickers + limit dropdown (10/30/50/100/custom) + custom-limit input + component-type dropdown (Any/Component/Product). Widget container → `MostRecentReleasesWidget`. URL-synced for every filter.
- **Sub-components** — `MostRecentReleasesWidget`.

### 2.7 Finding Analysis (tabbed page)

#### `FindingAnalysisPage.vue` — `/vulnerabilityAnalysis/:orguuid`
- **Purpose** — Hub for vulnerability / VEX / mitigation triage. 3 tabs in one URL.
- **Layout** — `n-tabs` (3 panes). Tab choice persisted as `?tab=findings|vex|attestations`.
- **Sub-components** — `VulnerabilityAnalysis`, `VexProposalsInbox`, `MitigationAttestationsInbox`.
- **Interactions** — URL param hydration on mount + write-back on tab change.

#### `VulnerabilityAnalysis.vue` (embedded — "Finding Analysis" tab)
- **Purpose** — Manage vulnerability analysis records (suppress/analyse findings at the right scope). Shows existing analyses + unanalysed findings.
- **Layout** — Filter row (Type, Component/Product, Branch/FeatureSet, Release, Finding ID, Show Suppressed/Unsuppressed). `n-data-table` (20/page) with columns: Finding ID (+ aliases tooltip), Type, Severity, PURL/Location, Scope, Scope Details, Current State (+ justification tooltip), History (reverse-chronological), Actions (Eye / Edit / Add-Scope icons).
- **Key data** — Finding ID with external CVE/CWE/GHSA links; type (VULNERABILITY / VIOLATION / WEAKNESS); severity bucket; PURL; scope (ORG / COMPONENT / BRANCH / RELEASE); analysis state (EXPLOITABLE / IN_TRIAGE / FALSE_POSITIVE / NOT_AFFECTED / RESOLVED); justification + per-history entry.
- **Queries** — `getVulnAnalysis`, `getVulnAnalysisByComponent / ByBranch / ByRelease`, `findingsPerDay*`, `ReleaseVulnerabilityService.fetchReleaseVulnerabilityData`.
- **Actions** — Filter by any axis (URL-persisted); Eye → `ReleasesByCve`; Edit → `Create/UpdateVulnAnalysisModal`; Add Scope; external links (CVE/CWE/GHSA) with confirmation dialog (cached for 15 days in localStorage).
- **Sub-components** — `CreateVulnAnalysisModal`, `UpdateVulnAnalysisModal`, `ReleasesByCve`.
- **Interactions** — `new-` UUID prefix marks unanalysed findings; analysis state coloring via `NTag` type; history reversed for newest-first.

#### `VexProposalsInbox.vue` (embedded — "VEX Statement Proposals" tab)
- **Purpose** — Triage VEX proposals (accept / reject / supersede tracking).
- **Layout** — `n-card` title + status `n-tabs` (PENDING / ACCEPTED / REJECTED / SUPERSEDED / ERRORED). `n-data-table` with columns: CVE, Component, State, Justification, Scope, Status (colored `NTag`), conditionally Acted-at + Acted-by + Eye action (opens review in **new tab**).
- **Queries** — `getVexStatementProposals`.
- **Actions** — Switch status tab (refetches). Eye → `window.open` to `VexProposalReview` so the queue stays in the background.
- **Sub-components** — None (uses `useOrgUsersIndex` for the actor name lookup).

#### `VexProposalReview.vue` — `/vexProposal/:orguuid/:uuid`
- **Purpose** — Single VEX proposal: review, modify, accept, or reject.
- **Layout** — Spin overlay → `n-card` with the original VEX JSON on the left, proposed ReARM analysis on the right with a Modify button. Edit-form conditional. Related-analyses-at-other-scopes table. Action row (accept/reject with required inputs) or status alert (for completed proposals).
- **Key data** — CVE, component, proposed state / justification / responses / severity / details / recommendation / workaround, source VEX statement (as JSON), existing analyses at other scopes, status badges, actor info, translation notes.
- **Queries** — `getVexStatementProposal`, `getVulnAnalysisByLocationAndFinding`, `getMitigationAttestation`.
- **Actions** — Modify (edit-mode form); Accept (requires severity, optional comment); Reject (requires comment); jump to linked Mitigation Attestation; jump to Finding Analysis (`?cveId=…`).
- **Interactions** — Status-dependent alerts (deferred analysis writes, waived attestations); demotion alerts (`BROADER_SCOPE_CONFLICT`, `SEVERITY_MISSING`); option lists for analysis state / justifications / responses / severities.

#### `MitigationAttestationsInbox.vue` (embedded — "Mitigation Attestations" tab)
- **Purpose** — Manage the mitigation-attestation queue for accepted-but-conditional VEX proposals.
- **Layout** — `n-card` + status `n-tabs` (PENDING / ATTESTED / WAIVED / EXPIRED). `n-data-table` columns: Claim Type, Claim Text, Scope, Assignee (formatted user), Status (colored `NTag`), conditionally Acted-at + Acted-by + Eye action → review page.
- **Queries** — `getMitigationAttestations`.

#### `MitigationAttestationReview.vue` — `/mitigationAttestation/:orguuid/:uuid`
- **Purpose** — Record an attestation (evidence) or waive it.
- **Layout** — Spin overlay → `n-card` with attestation details (claim type/text, scope, originating proposal link, assignee, dates, status). Evidence + waive-reason textareas (PENDING only). Action buttons (Attest / Waive). Status alert for non-pending. Links to related VEX proposal + finding analysis.
- **Queries** — `getMitigationAttestation`, `getVexProposal`, `getVulnAnalysisByLocationAndFinding`.

### 2.8 Secrets

#### `SecretsOfOrg.vue` — `/secretsOfOrg/:orguuid`
- **Purpose** — Manage org secrets + their distribution permissions to instances/clusters.
- **Layout** — Header (title, add icon). `n-data-table` (name / description / actions = edit / distribution / delete). Modals for create / edit / distribution (two-col grid: Instance Permissions + Cluster Permissions).
- **Queries** — `secrets`, `createSecret`, `updateSecretDetails`, `deleteSecret`, `updateSecretDistribution`.
- **Actions** — Add / edit / delete (confirm dialog). Manage distribution (select instances/clusters, set permission NONE/READ_ONLY).

### 2.9 Organization Settings

#### `OrgSettings.vue` — `/orgSettings/:orguuid`
- **Purpose** — Org-level admin (integrations: Slack, MS Teams, Dependency-Track, BEAR; CI integrations: GitHub/GitLab/Jenkins/Azure DevOps; webhooks).
- **Layout** — `n-tabs` ("Integrations" tab with org-wide integrations list + CI integrations table + webhooks section); modals per integration type.
- **Queries** — `ciIntegrations`.
- **Actions** — Add/delete chat integrations; add/edit CI integrations (GITHUB/GITLAB/JENKINS/ADO); add/edit webhooks; sync Dependency-Track projects; toggle capabilities (PR_VALIDATE / WEBHOOK / WORKFLOW_DISPATCH).
- **Sub-components** — `WebhooksOfOrg`.
- **Interactions** — File upload for GitHub App `.pem`. Dynamic form per CI type. Nested capability checkboxes.

### 2.10 User / System

#### `UserProfile.vue` — `/profile`
- **Purpose** — Display name + email + org membership; manage emails and SSH keys (sparse UI).
- **Layout** — Display Name section (editable inline w/ check/X), Emails (`n-data-table` with primary / verified / marketing pref), Your Organizations (unordered list). Modals for update email, create / restore org.
- **Queries** — `fetchMyUser`, `fetchMyOrganizations`.
- **Actions** — Rename, set primary email, marketing opt-in/out, create org, archive/restore (soft-delete).

#### `SystemSettings.vue` — `/sysSettings`
- **Purpose** — Global admin: AWS / Azure / SendGrid / SMTP / license / default org / finalize releases. Gated on `myUser.isGlobalAdmin`.
- **Layout** — Sections per concern; each opens a modal.
- **Queries** — `getSystemInfoIsSet`, `getLicense`, `allOrganizations`.
- **Actions** — Configure/edit/delete credentials, upload license, send test email, set default org, finalize-all-releases (async).

### 2.11 Release surfaces

#### `ReleaseView.vue` — `/release/show/:uuid`
- **Purpose** — Comprehensive release detail. SBOM / VDR / VEX / CLE exports, metadata, components, artifacts, deployment info.
- **Layout** — Release loading overlay. Header (version / component / branch / lifecycle). Tabs: Code Changes, SBOM, Findings, Deployments, VEX, Meta. Export-SBOM modal with type (SBOM/OBOM/VDR/VEX/CLE), config (DELIVERABLE/RELEASE/SCE), format (JSON/CSV/EXCEL), top-level/optional/coverage toggles.
- **Key data** — Release version, component name+UUID, branch, lifecycle, created date, deployment list (instances + versions).
- **Actions** — Export (downloads file via API). Click into many sub-views.

#### `ReleaseSbomComponentGraph.vue` — `/release/:releaseUuid/sbomComponentGraph/:sbomComponentUuid?`
- **Purpose** — Visualise SBOM component dependencies; upstream paths + direct children + direct parents.
- **Layout** — Header with back-link, page title, refresh button. Component summary card (name/version/group/type/purl/isRoot). "Upstream paths to root" section with arrow-rendered chains. Direct Children table. Direct Parents table.
- **Queries** — `getReleaseSbomComponentGraph`, `searchSbomComponentByPurl`.
- **Actions** — Click any node → navigate to that component's graph; click Children → drill; back to release; refresh.
- **Interactions** — Truncation alert when paths exceed `MAX_PATHS`. DFS for upstream paths; BFS for transitive closure.

#### `ChangelogView.vue` — `/changelog/:orgprop/:release1prop/:release2prop/:componenttypeprop/:isrouterlink`
- **Purpose** — Compare two releases or summarise component/product changes over a date range.
- **Layout** — Header (mode-dependent). `ChangelogControls` for date picker + aggregation (`AGGREGATED` / `NONE`). Tabs: Code Changes, SBOM Changes, Finding Changes. Export-PDF button.
- **Sub-components** — `ChangelogControls`, `CodeChangesDisplay`, `SbomChangesDisplay`, `FindingChangesDisplay`, `FindingChangesDisplayWithAttribution`, `ReleaseHeader`, `SeverityFilter`.

### 2.12 One-shot / onboarding

#### `VerifyEmail.vue` — `/verifyEmail/:secret`
- **Purpose** — Auto-verify email from a link. Fires `verifyEmail` mutation on mount, SweetAlert success/error → redirect to `/`.

#### `JoinOrganization.vue` — `/joinOrganization/:secret`
- **Purpose** — Accept an org invitation. Fires `joinUserToOrganization` mutation on mount, SweetAlert flow.

#### `DownloadTeaArtifactView.vue` — `/downloadArtifact/:arttype/:artuuid`
- **Purpose** — Auto-download a release artifact. Fetches artifact metadata (`artifact` query for media type + filename), downloads via blob, SweetAlert post-download.

---

## 3. Shared / Widget Components

Non-routed components under `src/components/` — modals, drawers, embedded widgets, reusable tables. 53 root + 9 under `changelog/`.

### 3.1 Release management
| Component | Type | Purpose | Used by |
|---|---|---|---|
| `CreateRelease` | Inline form | Org/component/branch/version selector | `BranchView`, `CreateInstance`, `InstanceView`, `MarketingReleaseView`, `ReleaseEl` |
| `ReleaseEl` | Display | Single-release entry; embeds `CreateRelease` for child releases | `CreateInstance` |
| `ReleaseRevision` | Inline | Diff-comparison detail (via `SideBySide`) | `SideBySide` |
| `MarketingReleaseView` | Page-like | Marketing release detail with embedded create modal | `MrktReleasesOfComponent` |
| `CreateMarketingRelease` | Inline form | Marketing release creation | `MrktReleasesOfComponent` |
| `MrktReleasesOfComponent` | Widget | Marketing releases list + create/view modals | `ComponentView` |

### 3.2 Release analytics & charts
| Component | Type | Purpose | Used by |
|---|---|---|---|
| `ReleasesPerDayChart` | Chart | Line chart of releases over time | `AppHome`, `ComponentView`, `ReleasesPerDayPage` |
| `ReleasesByDateRange` | Filter form | Date range selector for release analytics | `ReleasesPerDayChart` |
| `ReleasesByCve` | Modal viewer | Releases affected by a CVE with filtering / export | `AppHome`, `VulnerabilityAnalysis` |
| `FindingsOverTimeChart` | Chart | Line chart of findings over time (with modal toggle) | `AppHome`, `ComponentView`, `FindingsOverTimePage` |
| `MostActiveChart` | Chart | Bar chart of most-active components/products | `AppHome` |
| `MostRecentReleasesWidget` | Widget | Recent releases table w/ refresh + search | `AppHome`, `MostRecentReleasesPage` |
| `ComponentAnalytics` | Page-like | Component-level analytics dashboard | `ComponentView` |

### 3.3 Artifact & SBOM
| Component | Type | Purpose | Used by |
|---|---|---|---|
| `ComponentBranchesTable` | Data table | Reusable branches table with release info / status / links | `AppHome`, `ReleaseView`, `ReleasesByDateRange` |
| `CreateArtifact` | Form | Multi-step artifact (SBOM / logs) upload with type selection | `ReleaseView` |
| `CreateDeliverable` | Form | Deliverable / package entry form | `ReleaseView` |
| `CreateSourceCodeEntry` | Form | Source-code-repo link form; embeds `LinkVcs` | `ReleaseView` |
| `LinkVcs` | Modal form | VCS repo linking with OAuth / credential handling | `BranchView`, `CreateSourceCodeEntry` |
| `CreateVcsRepository` | Form | VCS repo registration with platform + credential | `CreateComponent`, `VcsReposOfOrg` |

### 3.4 VEX / Findings
| Component | Type | Purpose | Used by |
|---|---|---|---|
| `VulnerabilityModal` | `n-modal` | Vuln details table with suppression, filtering, PDF export | `AppHome`, `BranchView`, `FindingsOverTimeChart`, `MostRecentReleasesWidget`, `ReleaseView` |
| `CreateVulnAnalysisModal` | `n-modal` | New finding-analysis form | `ViewVulnAnalysisModal`, `VulnerabilityAnalysis` |
| `UpdateVulnAnalysisModal` | `n-modal` | Update finding-analysis with merge support | `ViewVulnAnalysisModal`, `VulnerabilityAnalysis` |
| `ViewVulnAnalysisModal` | `n-modal` | Read-only viewer with embedded create/update modals | `VulnerabilityModal` |

### 3.5 Organization / IAM
| Component | Type | Purpose | Used by |
|---|---|---|---|
| `CreateComponent` | Form | Multi-step component/product creation | `ComponentsOfOrg` |
| `CreateInstance` | Tabs + forms | Instance creation w/ releases, artifacts, properties | `InstanceView`, `InstancesOfOrg` |
| `CreateProperty` | Form | Custom property / metadata entry | `InstanceView` |
| `CreateApprovalPolicy` | Form | Org approval-policy creation | `OrgSettings` |
| `CreateApprovalEntry` | Form | Approval-checklist entry creation | `OrgSettings` |
| `ScopedPermissions` | Modal form | Role / permission configuration scoped to org / component / team | `OrgSettings` |
| `OrgGlobalApprovalPolicyRules` | Widget | Org-wide approval-policy rule management | `OrgSettings` |
| `OrgGlobalPrValidationRules` | Widget | PR-validation rule management | `OrgSettings` |
| `WebhooksOfOrg` | Widget + modal | Webhook CRUD | `OrgSettings` |
| `DownloadLogView` | Component | Deployment / operation log export | `OrgSettings` |

### 3.6 Component / Instance / Branch views
| Component | Type | Purpose | Used by |
|---|---|---|---|
| `ComponentView` | Page-like | Component detail with tabs: analytics, releases, artifacts, VCS, settings | `ComponentsOfOrg` |
| `BranchView` | Page-like | Branch detail with releases, artifacts, VCS, feature-set config | `ComponentView` |
| `InstanceView` | Page-like | Instance deployment view with history, diff, property editor | `InstancesOfOrg` |
| `InstanceHistory` | Widget + modal | Deployment history with `SideBySide` diffs | `InstanceView` |
| `InstanceRevision` | Inline | Revision detail in diffs (via `SideBySide`) | `SideBySide` |
| `SideBySide` | Layout | Two-column diff/comparison for revisions | `BranchView`, `InstanceHistory`, `InstanceView` |
| `AddComponent` | Form | Add existing component as dependency | `BranchView`, `InstanceView` |
| `AddComponentBranch` | Form | Add component branch as dependency | `InstanceView` |
| `FeatureSetParticipation` | Modal | Feature-set membership configuration | `BranchView`, `ComponentView` |

### 3.7 Changelog sub-components (`src/components/changelog/`)
| Component | Purpose |
|---|---|
| `ChangelogControls` | Severity / type / component filter form |
| `CodeChangesDisplay` | Commit-changes display |
| `ComponentHeader` | Component metadata header |
| `FindingChangesDisplay` | Vulnerability changes with color-coded status |
| `FindingChangesDisplayWithAttribution` | Same + blame / attribution |
| `FindingListSection` | Reusable findings list with severity indicators |
| `ReleaseHeader` | Release metadata header |
| `SbomChangesDisplay` | Dependency changes with attribution |
| `SeverityFilter` | Checkbox group for severity / status filtering |

### 3.8 Navigation & layout
| Component | Purpose | Used by |
|---|---|---|
| `LeftNavBar` | Sidebar with `n-layout-sider` + `router-view` | `AppWrapper` |
| `TopNavBar` | Top bar w/ org + perspective dropdowns, profile, logout | `AppWrapper` |
| `AppWrapper` | Root wrapper. Integrates nav + auth flows (`SignUpFlow`, `VerifyEmail`, `JoinOrganization`) | — |

### 3.9 Auth & misc
| Component | Purpose | Used by |
|---|---|---|
| `SignUpFlow` | Multi-step user registration with modals | `AppWrapper` |
| `HubProfile` | User profile / hub view | `UserProfile` (routed page) |

### 3.10 Utility
| Component | Purpose | Used by |
|---|---|---|
| `FieldLabel` | Form field wrapper with tooltip support | `CreateVulnAnalysisModal`, `UpdateVulnAnalysisModal` |
| `CelExpressionBuilder` | CEL-expression builder for policies/rules | `ComponentView`, `OrgSettings` |

---

## 4. Visual-Style Audit

### 4.1 Stack
- **Vue** 3.5.31, **Vue Router** 4, **Vuex** 4.1.0
- **naive-ui** 2.44.1 (sole UI library)
- **Vite** 7.3.1 build
- **Apollo Client** 4.1.6 for GraphQL
- **Vega** 6.2.0 / **Vega-Lite** 6.4.2 for charts
- **Keycloak-js** 26.2.3 for auth
- **SweetAlert2** 11.26.24 for some modal dialogs
- **Prism** 1.30.0 for code highlighting (theme: `prism-tomorrow.css`)
- **pdfmake** 0.3.7 for PDF export

### 4.2 Color palette
No `themeOverrides` on `NConfigProvider` — naive-ui defaults rule. Color literals concentrated in `src/components/changelog/_finding-common.scss` and ad-hoc `style="..."` attributes.

| Token | Hex | Where |
|---|---|---|
| Error / Critical | `#d03050` | Finding state NEW; critical severity bg variant `#5c0011` |
| Success / Resolved | `#18a058` | Finding state RESOLVED |
| Warning / Changed | `#f0a020` | Finding state CHANGED / PARTIAL |
| Info / Primary (link) | `#2080f0` | Links, highlights, PRESENT state |
| Inherited | `#c04080` | Finding state INHERITED |
| Tooltip link | `#70c0ff` | Secondary links in tooltips |
| Neutral dark text | `#666` | Body text |
| Neutral medium | `#999` | Muted labels, disabled states |
| Neutral border | `#eee`, `#ddd`, `#f0f0f0` | Borders, list-item dividers |
| Code bg | `#f5f5f5` | Inline code blocks |

Chart palette: `theme: 'powerbi'` (Vega-Lite default). Spinner / hover: naive-ui implicit `cornflowerblue` ≈ `#2080f0`.

### 4.3 Typography
- **No custom typeface loaded** (no `index.html` font import; `App.vue` / `main.ts` don't declare). naive-ui's system stack rules.
- **Size scale** — ad-hoc, no token file. Common values in `_finding-common.scss`:
  - small labels: `0.85em`
  - monospace / inline code: `0.82em`
  - status badges: `0.9em`
- Code blocks: Prism `prism-tomorrow.css` (dark background).

### 4.4 Spacing scale
Inline `style="margin: …"` everywhere; no token file. Frequent values:

| Value | Common use |
|---|---|
| `4px` | finding row padding, tag spacing |
| `6px` | flex gap in finding rows |
| `8px` | tag spacing, footer link gap |
| `12px` | finding header margins, footer content gap |
| `16px` | attribution left padding, footer vertical padding |
| `20px` | empty-state padding, footer horizontal padding |

### 4.5 Layout primitives
- **Header** — `TopNavBar`, full-width flex, ~16px vertical padding.
- **Sidebar** — `NLayoutSider` collapsed 64px, expanded 240px, `width`-mode collapse, bordered.
- **Main** — `NLayout` + `NLayoutContent`; `router-view` margin `5px` horizontal.
- **Footer** — sticky bottom (`margin-top: auto`); 20px horizontal padding.
- **Responsive** — single breakpoint at `768px` (footer turns column-direction). Modals: `width: 90%` blanket override. No explicit max-width container; content stretches.

### 4.6 Iconography
Mixed from `@vicons/*` — **no unified set**:
- `@vicons/antd` (Ant Design)
- `@vicons/tabler` (primary)
- `@vicons/fluent` (Microsoft Fluent)
- `@vicons/carbon` (IBM Carbon, dev-only)
- `@vicons/utils` (helper wrapper)

Pattern: `<NIcon size="20"><Logout /></NIcon>`. Names PascalCase.

### 4.7 Feedback & state
- **Modals** — both `n-modal` (in-tree) and `SweetAlert2` (`.swal-wide { width: 90% !important }`). Inconsistent.
- **Notifications / toasts** — `NMessageProvider` and `useNotification` (bottom-right placement).
- **Badges** — `NTag` with implicit `type` map (success / warning / error / info / default). No wrapper.
- **Loading** — `NSpin` (small in forms) + a hand-rolled `.spinner-overlay` CSS animation.
- **Banners** — `NAlert` used sparingly; most "callout" content goes through SweetAlert2.
- **Empty states** — `.empty-state { padding: 20px; text-align: center; color: #999 }`. Inline, no shared component.

### 4.8 Table conventions
- **`NDataTable`** used 62+ times directly — **no wrapper component**.
- Columns declared as `DataTableColumns` with `render` callables that invoke `h()` (vue/render-h pattern) for badges, action icon stacks, status tags, links.
- Common column shapes: ID/name (sometimes editable inline), status (colored `NTag`), dates with calendar tooltip, actions (icon row), copy-UUID tooltip.

### 4.9 Form conventions
- **Components** — `NForm` + `NFormItem`, `NInput`, `NSelect`, `NRadio(Group)`, `NCheckbox(Group)`.
- **Label placement** — naive-ui default (above the field), no left-label-grid except a few modals using explicit `label-placement="left"`.
- **Validation feedback** — split between native `NFormItem.feedback` and SweetAlert2 dialogs.
- **Buttons** — `NButton` with `type="primary|success|warning|error"`. Mix of `variant` and `text`/`secondary` styles.

### 4.10 Dark mode
**Not supported.** `NConfigProvider` has no `theme` prop; no toggle in `TopNavBar`. Vega charts default to `powerbi` (light). Visual personality is fully light.

---

## 5. Cross-cutting Patterns

- **List-detail (split-pane) pages** — `ComponentsOfOrg`, `ProductsOfOrg`, `InstancesOfOrg` all share a left-table + right-detail-panel layout with the selection driven by route params.
- **Tabbed pages** — `AppHome` (search modes), `FindingAnalysisPage` (3-tab wrapper), `VexProposalsInbox` / `MitigationAttestationsInbox` (status filter tabs), `ChangelogView` (content tabs), `ReleaseView` (Code / SBOM / Findings / Deployments / VEX / Meta).
- **Modal-driven CRUD** — `OrgSettings`, `InstancesOfOrg`, `SecretsOfOrg`, `ComponentsOfOrg` (create), `ReleaseView` (export). Forms are dense and use conditional rendering rather than a wizard pattern.
- **Pagination defaults** — 10 (components/instances/VCS), 20 (Finding Analysis), 25 (PRs).
- **URL-as-state** — every long-lived filter (status, type, tab, dates, finding ID, scope) is mirrored to the URL query string for shareable / bookmarkable views.
- **Inline-editable cells** — `VcsReposOfOrg` (Enter / Escape save/cancel pattern).
- **Perspective awareness** — store getters (`myorg`, `myperspective`) propagate into queries; many queries have a `*ByPerspective` variant.
- **Deep-link + modal-as-route** — Modals like `ReleasesByCve` open from a route query param (`?cveId=…`) so they're reachable as direct links.
- **Actor formatting** — `useOrgUsersIndex` composable formats `actedBy` / `assignee` UUIDs as "Name (email)" consistently across VEX + Mitigation tables.
- **New-tab navigation** — VEX proposal review opens via `window.open(...)` instead of `router.push` so the inbox queue stays in the background while triaging.
- **Date display** — calendar-style ("3 days ago") with full UTC + local in a hover tooltip; helper `renderDateCell`.
- **SweetAlert2 vs `n-modal`** — coexist; older flows lean on SweetAlert, newer ones on `n-modal`. No clear separation; candidate for consolidation in a redesign.
- **Confirmation-on-external-link** — confirmed-once-per-15-days dialog for CVE / CWE / GHSA external links (cached in localStorage).
- **PDF export** — `pdfmake` for `ChangelogView` and parts of `VulnerabilityModal`. Layout templates inline in the components.

---

## 6. What a redesign would inherit vs need to (re)build

| Area | Inherits cleanly | Needs design work |
|---|---|---|
| Component library | naive-ui covers most surfaces (datatable, tabs, forms, popovers, drawers) | nothing to replace at the lib level; theming layer is missing |
| Color system | base palette implicit via naive-ui defaults | no semantic token file; status colors hard-coded in SCSS; no dark mode |
| Spacing | n/a | no scale; inline px values everywhere |
| Typography | system stack works | no scale, no font choice; status-label sizing ad-hoc |
| Iconography | `@vicons/*` available | five icon sets in play; pick one |
| Modals | `n-modal` ergonomics fine | SweetAlert2 duplication needs to be retired |
| Tables | `NDataTable` is solid | no shared wrapper; status / actions / date columns are reimplemented per page |
| Forms | `NForm` works | no shared "modal form" pattern; label-placement is inconsistent |
| Layout | sidebar + content shell is stable | no responsive design beyond 768px breakpoint; no max-width container |
| Pages | 27 routed pages, all functional | split-pane vs tab-vs-modal mix is inconsistent; some routes (`ProductsOfOrg` aliases `ComponentsOfOrg`) carry historical baggage |
| URL state | well-established pattern (filters mirrored to query string) | redesign should preserve this — it's load-bearing |
