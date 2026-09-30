<template>
    <div class="sbom-graph-page">
        <div class="page-header">
            <div>
                <RouterLink :to="{ name: 'ReleaseView', params: { uuid: releaseUuid } }" class="back-link">
                    &larr; Back to release
                </RouterLink>
                <h2 style="margin: 4px 0 0;">{{ pageTitle }}</h2>
            </div>
            <n-button v-if="releaseUuid" size="small" @click="reload" :loading="loading">Refresh</n-button>
        </div>

        <div v-if="loading && !selected" style="padding: 24px;">
            <n-spin size="medium" />
            <p>{{ loadingMessage }}</p>
        </div>
        <div v-else-if="errorMessage" style="padding: 16px;">
            <p style="color: #d03050;">{{ errorMessage }}</p>
        </div>
        <div v-else-if="selected" class="page-body">
            <div class="component-summary">
                <p style="margin: 4px 0;"><strong>Name:</strong> {{ selected.component?.name || '—' }}</p>
                <p style="margin: 4px 0;"><strong>Version:</strong> {{ selected.component?.version || '—' }}</p>
                <p v-if="selected.component?.group" style="margin: 4px 0;"><strong>Group:</strong> {{ selected.component.group }}</p>
                <p style="margin: 4px 0;"><strong>Type:</strong> {{ selected.component?.type || '—' }}</p>
                <p style="margin: 4px 0; word-break: break-all;"><strong>Canonical purl:</strong> {{ selected.component?.canonicalPurl || '—' }}</p>
                <p style="margin: 4px 0;" v-if="selected.component?.isRoot">
                    <n-tag type="info" size="small" round>Root component</n-tag>
                </p>
                <div v-if="componentFindings" class="findings-badge">
                    <strong>Findings in this release:</strong>
                    <template v-if="componentFindings.length">
                        <span
                            v-for="severity in openSeverities"
                            :key="severity"
                            class="circle"
                            :style="{ background: severityColor(severity) }"
                            :title="`${openCounts[severity]} open ${severity.toLowerCase()}`">{{ openCounts[severity] }}</span>
                        <n-tag v-if="openKevCount" type="error" size="small" :bordered="false"
                            title="CISA Known Exploited Vulnerability">KEV {{ openKevCount }}</n-tag>
                        <span v-if="suppressedCount" class="findings-muted">{{ suppressedCount }} suppressed</span>
                    </template>
                    <span v-else class="findings-muted">none</span>
                </div>
                <p v-if="componentLatest" style="margin: 4px 0;" :title="groupLatestTitle(componentLatest)">
                    <strong>Latest version:</strong> {{ componentLatest.version }}
                    <span class="findings-muted">{{ latestFixesText(componentLatest) }}</span>
                    <span v-if="componentLatest.checked" class="findings-muted" style="margin-left: 6px;">checked {{ checkedDay(componentLatest.checked) }}</span>
                </p>
            </div>

            <template v-if="componentFindings && componentFindings.length">
                <h4 style="margin-top: 16px; margin-bottom: 4px;">Findings ({{ componentFindings.length }})</h4>
                <n-data-table
                    :data="componentFindings"
                    :columns="findingColumns"
                    :row-key="(row: any) => row.rowIndex"
                    :pagination="componentFindings.length > 10 ? { pageSize: 10 } : false"
                    size="small"
                />
            </template>

            <h4 style="margin-bottom: 4px;">
                Upstream paths to root ({{ upstreamPaths.length }}{{ upstreamTruncated ? '+' : '' }})
            </h4>
            <p v-if="selected.component?.isRoot" style="color: #999;">
                This component is itself a release root.
            </p>
            <p v-else-if="!upstreamPaths.length" style="color: #999;">
                No upstream parents found in this release.
            </p>
            <div v-else class="upstream-paths">
                <div v-for="(path, idx) in upstreamPaths" :key="idx" class="upstream-path">
                    <template v-for="(node, nodeIdx) in path" :key="(node.sbomComponentUuid || '') + ':' + nodeIdx">
                        <span v-if="nodeIdx > 0" class="path-arrow">&larr;</span>
                        <span
                            class="path-box"
                            :class="{ 'is-root': node.component?.isRoot, 'is-self': nodeIdx === 0 }"
                            :title="node.component?.canonicalPurl || ''"
                            @click="navigateToComponent(node.sbomComponentUuid)">
                            {{ pathNodeLabel(node) }}
                        </span>
                    </template>
                </div>
                <p v-if="upstreamTruncated" style="color: #999; margin-top: 6px; font-size: 12px;">
                    Showing first {{ MAX_PATHS }} paths.
                </p>
            </div>

            <h4 style="margin-top: 16px; margin-bottom: 4px;">Direct Children (Dependencies - {{ (selected.dependencies || []).length }})</h4>
            <p v-if="!(selected.dependencies && selected.dependencies.length)" style="color: #999;">
                This component has no recorded dependencies in this release.
            </p>
            <n-data-table
                v-else
                :data="selected.dependencies"
                :columns="dependenciesColumns"
                :row-key="(row: any) => (row.targetSbomComponentUuid || '')"
                :pagination="{ pageSize: 10 }"
            />

            <h4 style="margin-top: 16px; margin-bottom: 4px;">Direct Parents (Depended On By - {{ (selected.dependedOnBy || []).length }})</h4>
            <p v-if="!(selected.dependedOnBy && selected.dependedOnBy.length)" style="color: #999;">
                No other components in this release depend on this one.
            </p>
            <n-data-table
                v-else
                :data="selected.dependedOnBy"
                :columns="dependedOnByColumns"
                :row-key="(row: any) => (row.sbomComponentUuid || row.uuid)"
                :pagination="{ pageSize: 10 }"
            />
        </div>

        <vulnerability-details-modal
            v-model:show="vulnDetail.show"
            :org-uuid="findingsOrgUuid"
            :vuln-id="vulnDetail.vulnId"
            :severity="vulnDetail.severity"
            :known-exploited="vulnDetail.knownExploited"
            :finding-purl="vulnDetail.purl"
            :fixed-in="vulnDetail.fixedIn"
        />
    </div>
</template>

<script lang="ts">
export default { name: 'ReleaseSbomComponentGraph' }
</script>

<script setup lang="ts">
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { searchSbomComponentByPurl } from '@/utils/dtrack'
import { computed, h, ref, watch, type Ref, type ComputedRef } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { NButton, NDataTable, NSpin, NTag, NTooltip, type DataTableColumns } from 'naive-ui'
import constants from '@/utils/constants'
import { ANALYSIS_STATE_OPTIONS, isSuppressedAnalysisState } from '@/constants/vulnAnalysis'
import { ROW_SEVERITIES, emptySeverityCounts, getSeverityTagType, renderFindingId, severityBucketOf } from '@/utils/findingUtils'
import { FindingType } from '@/constants/findingType'
import { formatPrimaryScore } from '@/utils/vulnScoreDisplay'
import { fixedInText, fixedInTitle } from '@/utils/fixedInDisplay'
import { loadRichestServed } from '@/utils/graphqlDriftFallback'
import { SBOM_COMPONENT_FINDINGS_QUERY, SBOM_COMPONENT_FINDINGS_QUERY_CORE, SBOM_COMPONENT_FINDINGS_QUERY_LATEST } from '@/utils/sbomComponentFindingsQuery'
import { checkedDay, groupLatestTitle, latestFixCell, latestFixesText, latestOf } from '@/utils/latestVersionDisplay'
import type { GroupLatest } from '@/utils/latestVersionDisplay'
import VulnerabilityDetailsModal from './VulnerabilityDetailsModal.vue'
import { useVulnerabilityDetail } from '@/utils/useVulnerabilityDetail'

interface Props {
    releaseUuid: string
    sbomComponentUuid?: string
    purl?: string
    orgUuid?: string
}

const props = withDefaults(defineProps<Props>(), {
    sbomComponentUuid: '',
    purl: '',
    orgUuid: ''
})

const router = useRouter()

const loading: Ref<boolean> = ref(false)
const loadingMessage: Ref<string> = ref('Loading dependency graph...')
const errorMessage: Ref<string> = ref('')
const selected: Ref<any> = ref(null)

const pageTitle: ComputedRef<string> = computed(() => {
    const c = selected.value?.component
    if (!c) return 'SBOM Component Graph'
    const v = c.version ? `@${c.version}` : ''
    return `SBOM Component Graph — ${c.name || c.canonicalPurl || 'component'}${v}`
})

const GRAPH_QUERY = gql`
    query getReleaseSbomComponentGraph($releaseUuid: ID!, $sbomComponentUuid: ID!) {
        getReleaseSbomComponentGraph(releaseUuid: $releaseUuid, sbomComponentUuid: $sbomComponentUuid) {
            uuid
            sbomComponentUuid
            releaseUuid
            component { uuid canonicalPurl type group name version isRoot }
            artifactParticipations { artifact exactPurls }
            dependencies {
                targetSbomComponentUuid
                targetCanonicalPurl
                relationshipType
                target {
                    uuid
                    sbomComponentUuid
                    component { canonicalPurl name version }
                }
                declaringArtifacts { artifact sourceExactPurl targetExactPurl }
            }
            dependedOnBy {
                uuid
                sbomComponentUuid
                component { canonicalPurl name version }
            }
            # Transitive dependedOnBy closure delivered as a flat, BFS-ordered
            # list. Used purely for client-side upstream-path walking — only
            # selecting the minimum: identity + immediate parent refs.
            ancestors {
                uuid
                sbomComponentUuid
                component { canonicalPurl name version isRoot }
                dependedOnBy { sbomComponentUuid }
            }
        }
    }
`

const MAX_PATHS = 50

// The component's findings in this release, loaded after the graph and on
// their own: null when the backend does not serve them (a CE build without
// ReleaseSbomComponent.findings) or the load failed, and the badge is hidden.
// Uncached (loadRichestServed): the documents share the graph query's root
// field and arguments, and a cached write would replace the graph's entry.
const componentFindings: Ref<any[] | null> = ref(null)
// The component's latest version (Dependency-Track's repository metadata) and when it
// was checked; null when unknown or not served.
const latestFound: Ref<Pick<GroupLatest, 'version' | 'checked'> | null> = ref(null)
let findingsRequest = 0

// The org whose vulnerability records the details panel reads: the route's
// when the page was opened by purl, else the release's, which comes with the
// findings (the page is opened by component id without one).
const releaseOrgUuid: Ref<string> = ref('')
const findingsOrgUuid = computed(() => props.orgUuid || releaseOrgUuid.value)
const { vulnDetail, openVulnDetail } = useVulnerabilityDetail(() => findingsOrgUuid.value)

async function fetchComponentFindings (releaseUuid: string, sbomComponentUuid: string) {
    const request = ++findingsRequest
    componentFindings.value = null
    latestFound.value = null
    try {
        const result = await loadRichestServed(graphqlClient, {
            documents: [SBOM_COMPONENT_FINDINGS_QUERY_LATEST, SBOM_COMPONENT_FINDINGS_QUERY, SBOM_COMPONENT_FINDINGS_QUERY_CORE],
            variables: { releaseUuid, sbomComponentUuid },
            extractPath: data => data
        })
        // a later navigation owns the badge now
        if (request !== findingsRequest || selected.value?.sbomComponentUuid !== sbomComponentUuid) return
        releaseOrgUuid.value = result.data?.release?.org || ''
        const graphRow = result.data?.getReleaseSbomComponentGraph
        // which tier was served, by the fields it carries: findings, then the latest version
        if (Array.isArray(graphRow?.findings)) {
            componentFindings.value = graphRow.findings.map((f: any, i: number) => ({ ...f, rowIndex: i }))
        }
        const latest = graphRow?.component?.latestVersion
        latestFound.value = latest ? { version: latest, checked: graphRow.component.latestVersionChecked ?? null } : null
    } catch {
        // decorative: the graph above is what the page is for
    }
}

const openFindings = computed(() => (componentFindings.value || []).filter(f => !isSuppressedAnalysisState(f.analysisState)))
const openCounts = computed(() => {
    const counts = emptySeverityCounts()
    openFindings.value.forEach(f => counts[severityBucketOf(f)]++)
    return counts
})
const openSeverities = computed(() => ROW_SEVERITIES.filter(s => openCounts.value[s] > 0))
const openKevCount = computed(() => openFindings.value.filter(f => f.knownExploited).length)
const suppressedCount = computed(() => (componentFindings.value || []).length - openFindings.value.length)
const severityColor = (severity: string) => (constants.VulnerabilityColors as Record<string, string>)[severity]
const analysisStateLabel = (state: string) => ANALYSIS_STATE_OPTIONS.find(o => o.value === state)?.label ?? state
// counted over the rows, as the per-row column and the findings modal count
const componentLatest = computed(() => latestFound.value
    ? latestOf(latestFound.value.version, latestFound.value.checked, (componentFindings.value || []).map(f => f.latestFix))
    : null)

const latestFixColumn = {
    title: 'Latest fixes',
    key: 'latestFix',
    width: 130,
    render: (row: any) => {
        const cell = latestFixCell(row.latestFix)
        return h('span', { title: cell.title }, cell.text)
    }
}

// The Latest fixes column only when the component has a latest version: the
// backend may not serve it, and without one every cell would be blank.
const findingColumns: ComputedRef<DataTableColumns<any>> = computed(() => [
    { title: 'Vulnerability', key: 'vulnId', minWidth: 180, render: (row: any) => renderFindingId(h, row.vulnId, FindingType.VULNERABILITY, (vulnId: string) => openVulnDetail(vulnId, row)) },
    {
        title: 'Severity',
        key: 'severity',
        width: 120,
        render: (row: any) => h(NTag, { type: getSeverityTagType(severityBucketOf(row)), size: 'small' }, () => severityBucketOf(row))
    },
    { title: 'Score', key: 'topScore', width: 90, render: (row: any) => row.topScore ? formatPrimaryScore(row.topScore) : '' },
    { title: 'Fixed in', key: 'fixedIn', width: 160, render: (row: any) => h('span', { title: fixedInTitle(row.fixedIn) }, fixedInText(row.fixedIn)) },
    ...(componentLatest.value ? [latestFixColumn] : []),
    {
        title: 'Status',
        key: 'analysisState',
        width: 140,
        render: (row: any) => [
            row.knownExploited
                ? h(NTag, { type: 'error', size: 'small', bordered: false, style: 'margin-right: 4px;', title: 'CISA Known Exploited Vulnerability' }, () => 'KEV')
                : null,
            row.analysisState ? h('span', analysisStateLabel(row.analysisState)) : null
        ]
    }
])

async function fetchGraph (releaseUuid: string, sbomComponentUuid: string, useNetworkOnly = false) {
    loading.value = true
    loadingMessage.value = 'Loading dependency graph...'
    errorMessage.value = ''
    try {
        const resp = await graphqlClient.query({
            query: GRAPH_QUERY,
            variables: { releaseUuid, sbomComponentUuid },
            // cache-and-network: render any cached row immediately, then refresh
            // from the server. The merged row uuid is deterministic (v3 of
            // releaseUuid + sbomComponentUuid) so cache identity is stable.
            fetchPolicy: useNetworkOnly ? 'network-only' : 'cache-and-network'
        })
        const row = (resp.data as any)?.getReleaseSbomComponentGraph
        if (!row) {
            selected.value = null
            errorMessage.value = 'This component is not present in the release SBOM.'
            return
        }
        selected.value = row
        fetchComponentFindings(releaseUuid, sbomComponentUuid)
    } catch (err: any) {
        errorMessage.value = err?.message || 'Failed to load release SBOM graph.'
        selected.value = null
    } finally {
        loading.value = false
    }
}

async function resolveSelection () {
    errorMessage.value = ''
    if (!props.releaseUuid) {
        errorMessage.value = 'No release context provided.'
        return
    }

    let sbomUuid = props.sbomComponentUuid
    if (!sbomUuid && props.purl) {
        loading.value = true
        loadingMessage.value = 'Resolving purl...'
        try {
            if (!props.orgUuid) {
                errorMessage.value = 'No organization context provided for purl lookup.'
                return
            }
            const resolved = await searchSbomComponentByPurl(props.orgUuid, props.purl)
            if (!resolved) {
                errorMessage.value = `No SBOM component found for purl "${props.purl}".`
                return
            }
            sbomUuid = resolved
        } finally {
            loading.value = false
        }
    }

    if (!sbomUuid) {
        errorMessage.value = 'No component identifier provided.'
        return
    }

    await fetchGraph(props.releaseUuid, sbomUuid)
}

function navigateToComponent (sbomComponentUuid: string) {
    if (!sbomComponentUuid) return
    router.push({
        name: 'SbomComponentGraph',
        params: { releaseUuid: props.releaseUuid, sbomComponentUuid }
    })
}

function reload () {
    if (!props.releaseUuid) return
    if (props.sbomComponentUuid) {
        fetchGraph(props.releaseUuid, props.sbomComponentUuid, true)
    } else {
        resolveSelection()
    }
}

watch(() => [props.releaseUuid, props.sbomComponentUuid, props.purl, props.orgUuid] as const, () => {
    selected.value = null
    resolveSelection()
}, { immediate: true })

// Upstream paths: walk dependedOnBy upward through the ancestors map, emit
// each distinct path that terminates at a root or a parent outside the
// ancestor set. Cycles are broken at the first repeated hop. Capped at
// MAX_PATHS so high-fanout DAGs don't explode the render.
const upstreamTruncated: Ref<boolean> = ref(false)
const upstreamPaths: ComputedRef<any[][]> = computed((): any[][] => {
    upstreamTruncated.value = false
    const root = selected.value
    if (!root) return []
    if (root.component?.isRoot) return []
    const ancestors: any[] = root.ancestors || []
    if (!ancestors.length) return []

    const byUuid = new Map<string, any>()
    ancestors.forEach((a: any) => { if (a.sbomComponentUuid) byUuid.set(a.sbomComponentUuid, a) })

    const startKey = root.sbomComponentUuid
    const startNode = {
        sbomComponentUuid: startKey,
        component: root.component,
        dependedOnBy: (root.dependedOnBy || []).map((p: any) => ({ sbomComponentUuid: p.sbomComponentUuid }))
    }

    const paths: any[][] = []
    let truncated = false

    const stack: { node: any; path: any[]; visited: Set<string> }[] = [
        { node: startNode, path: [], visited: new Set([startKey]) }
    ]

    while (stack.length) {
        if (paths.length >= MAX_PATHS) {
            truncated = true
            break
        }
        const frame = stack.pop()!
        const next = [...frame.path, frame.node]
        const isRoot = frame.node.component?.isRoot
        const parentRefs = frame.node.dependedOnBy || []
        if (isRoot || parentRefs.length === 0) {
            paths.push(next)
            continue
        }
        for (const ref of parentRefs) {
            const parentKey = ref?.sbomComponentUuid
            if (!parentKey) continue
            if (frame.visited.has(parentKey)) {
                // cycle — terminate the path here
                paths.push(next)
                continue
            }
            const parent = byUuid.get(parentKey)
            if (!parent) {
                // parent outside ancestor set — treat as terminal
                paths.push(next)
                continue
            }
            const nextVisited = new Set(frame.visited)
            nextVisited.add(parentKey)
            stack.push({ node: parent, path: next, visited: nextVisited })
        }
    }

    upstreamTruncated.value = truncated
    return paths
})

function pathNodeLabel (node: any): string {
    const c = node?.component
    if (!c) return node?.sbomComponentUuid || '—'
    return c.canonicalPurl || `${c.name || ''}${c.version ? '@' + c.version : ''}` || node.sbomComponentUuid
}

function renderRowRef (component: any, fallbackPurl?: string) {
    const purl = component?.canonicalPurl || fallbackPurl
    const name = component?.name
    const version = component?.version
    const lines: any[] = []
    if (name) lines.push(h('div', `${name}${version ? '@' + version : ''}`))
    if (purl) lines.push(h('div', { style: 'font-family: monospace; font-size: 11px; color: #666; word-break: break-all;' }, purl))
    if (!lines.length) lines.push(h('span', '—'))
    return h('div', lines)
}

const dependenciesColumns: DataTableColumns<any> = [
    {
        key: 'target',
        title: 'Target',
        render: (row: any) => renderRowRef(row.target?.component, row.targetCanonicalPurl)
    },
    {
        key: 'declaringArtifacts',
        title: 'Declared by',
        render: (row: any) => {
            const list: any[] = row.declaringArtifacts || []
            if (!list.length) return h('span', '—')
            const tooltip = h('ul', { style: 'margin: 0; padding-left: 18px;' },
                list.map((d: any) => h('li', { style: 'word-break: break-all;' }, [
                    h('div', `artifact: ${d.artifact}`),
                    d.sourceExactPurl ? h('div', { style: 'font-size: 11px; color: pink;' }, `source: ${d.sourceExactPurl}`) : null,
                    d.targetExactPurl ? h('div', { style: 'font-size: 11px; color: pink;' }, `target: ${d.targetExactPurl}`) : null
                ]))
            )
            return h(NTooltip, {
                trigger: 'hover',
                contentStyle: 'max-width: 700px; white-space: normal; word-break: break-word;'
            }, {
                trigger: () => h('span', { style: 'text-decoration: underline dotted; cursor: pointer;' }, String(list.length)),
                default: () => tooltip
            })
        }
    },
    {
        key: 'actions',
        title: '',
        render: (row: any) => {
            const targetSbomUuid = row.target?.sbomComponentUuid || row.targetSbomComponentUuid
            if (!targetSbomUuid) return h('span', '')
            return h(NButton, {
                size: 'tiny',
                tertiary: true,
                onClick: () => navigateToComponent(targetSbomUuid)
            }, () => 'Open')
        }
    }
]

const dependedOnByColumns: DataTableColumns<any> = [
    {
        key: 'parent',
        title: 'Component',
        render: (row: any) => renderRowRef(row.component)
    },
    {
        key: 'actions',
        title: '',
        render: (row: any) => {
            const parentSbomUuid = row.sbomComponentUuid || row.component?.uuid
            if (!parentSbomUuid) return h('span', '')
            return h(NButton, {
                size: 'tiny',
                tertiary: true,
                onClick: () => navigateToComponent(parentSbomUuid)
            }, () => 'Open')
        }
    }
]
</script>

<style scoped>
.findings-badge {
    display: flex;
    align-items: center;
    gap: 4px;
    margin: 6px 0 4px;
}
.findings-muted {
    color: #999;
    font-size: 12px;
}
.sbom-graph-page {
    padding: 16px 24px;
    max-width: 100%;
}

.page-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 16px;
    gap: 16px;
}

.back-link {
    color: #4ea8c8;
    text-decoration: none;
    font-size: 13px;
}

.back-link:hover {
    text-decoration: underline;
}

.component-summary {
    margin-bottom: 16px;
    padding: 12px;
    border: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.2));
    border-radius: 4px;
}

.upstream-paths {
    max-height: 320px;
    overflow: auto;
    padding: 6px 4px;
    border: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.2));
    border-radius: 4px;
}

.upstream-path {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    padding: 3px 0;
    border-bottom: 1px dashed rgba(128, 128, 128, 0.2);
}

.upstream-path:last-child {
    border-bottom: none;
}

.path-box {
    display: inline-block;
    padding: 2px 6px;
    margin: 2px 2px;
    border: 1px solid #4ea8c8;
    border-radius: 3px;
    font-family: monospace;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
    max-width: 360px;
    overflow: hidden;
    text-overflow: ellipsis;
}

.path-box:hover {
    background: rgba(78, 168, 200, 0.12);
}

.path-box.is-root {
    border-color: #5c4624;
    color: #5c4624;
}

.path-box.is-self {
    border-color: #18a058;
    color: #18a058;
    font-weight: 600;
}

.path-arrow {
    color: #888;
    margin: 0 2px;
}
</style>
