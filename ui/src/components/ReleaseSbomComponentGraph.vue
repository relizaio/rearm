<template>
    <n-modal
        :show="show"
        @update:show="(v: boolean) => emit('update:show', v)"
        style="width: 90%; max-width: 1100px;"
        preset="card"
        :show-icon="false"
        :title="modalTitle">
        <div v-if="loading">
            <n-spin size="medium" />
            <p>{{ loadingMessage }}</p>
        </div>
        <div v-else-if="errorMessage">
            <p style="color: #d03050;">{{ errorMessage }}</p>
        </div>
        <div v-else-if="selected">
            <div style="margin-bottom: 12px;">
                <p style="margin: 4px 0;"><strong>Name:</strong> {{ selected.component?.name || '—' }}</p>
                <p style="margin: 4px 0;"><strong>Version:</strong> {{ selected.component?.version || '—' }}</p>
                <p v-if="selected.component?.group" style="margin: 4px 0;"><strong>Group:</strong> {{ selected.component.group }}</p>
                <p style="margin: 4px 0;"><strong>Type:</strong> {{ selected.component?.type || '—' }}</p>
                <p style="margin: 4px 0; word-break: break-all;"><strong>Canonical purl:</strong> {{ selected.component?.canonicalPurl || '—' }}</p>
                <p style="margin: 4px 0;" v-if="selected.component?.isRoot">
                    <n-tag type="info" size="small" round>Root component</n-tag>
                </p>
            </div>

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
                    <template v-for="(node, nodeIdx) in path" :key="node.uuid + ':' + nodeIdx">
                        <span v-if="nodeIdx > 0" class="path-arrow">&larr;</span>
                        <span
                            class="path-box"
                            :class="{ 'is-root': node.component?.isRoot, 'is-self': nodeIdx === 0 }"
                            :title="node.component?.canonicalPurl || ''"
                            @click="selectByUuid(node.uuid)">
                            {{ nodeLabel(node) }}
                        </span>
                    </template>
                </div>
                <p v-if="upstreamTruncated" style="color: #999; margin-top: 6px; font-size: 12px;">
                    Showing first {{ MAX_PATHS }} paths.
                </p>
            </div>

            <h4 style="margin-top: 16px; margin-bottom: 4px;">Direct dependencies ({{ (selected.dependencies || []).length }})</h4>
            <p v-if="!(selected.dependencies && selected.dependencies.length)" style="color: #999;">
                This component has no recorded dependencies in this release.
            </p>
            <n-data-table
                v-else
                :data="selected.dependencies"
                :columns="dependenciesColumns"
                :row-key="(row: any) => (row.targetSbomComponentUuid || '') + '::' + (row.relationshipType || '')"
                :pagination="{ pageSize: 10 }"
            />

            <h4 style="margin-top: 16px; margin-bottom: 4px;">Direct dependedOnBy ({{ (selected.dependedOnBy || []).length }})</h4>
            <p v-if="!(selected.dependedOnBy && selected.dependedOnBy.length)" style="color: #999;">
                No other components in this release depend on this one.
            </p>
            <n-data-table
                v-else
                :data="selected.dependedOnBy"
                :columns="dependedOnByColumns"
                :row-key="(row: any) => row.uuid"
                :pagination="{ pageSize: 10 }"
            />
        </div>
    </n-modal>
</template>

<script lang="ts">
export default { name: 'ReleaseSbomComponentGraph' }
</script>

<script setup lang="ts">
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { searchSbomComponentByPurl } from '@/utils/dtrack'
import { computed, h, ref, watch, type Ref, type ComputedRef } from 'vue'
import { NButton, NDataTable, NModal, NSpin, NTag, NTooltip, type DataTableColumns } from 'naive-ui'

interface Props {
    show: boolean
    releaseUuid: string
    orgUuid?: string
    sbomComponentUuid?: string
    purl?: string
}

const props = withDefaults(defineProps<Props>(), {
    orgUuid: '',
    sbomComponentUuid: '',
    purl: ''
})

const emit = defineEmits<{
    'update:show': [value: boolean]
}>()

const MAX_PATHS = 50

const loading: Ref<boolean> = ref(false)
const loadingMessage: Ref<string> = ref('Loading dependency graph...')
const errorMessage: Ref<string> = ref('')
// Per-release cache of the loaded graph: releaseUuid -> { byUuid }.
// Apollo's cache-first policy backs this on the network side, but we also keep
// a local map so the in-memory traversal helpers (paths, lookups) don't re-walk
// the response on every navigation between rows in the same modal session.
const graphCache: Ref<Record<string, { byUuid: Record<string, any> }>> = ref({})
const currentReleaseUuid: Ref<string> = ref('')
const selectedUuid: Ref<string> = ref('')

const byUuid: ComputedRef<Record<string, any>> = computed(() =>
    graphCache.value[currentReleaseUuid.value]?.byUuid || {}
)

const selected: ComputedRef<any> = computed(() => byUuid.value[selectedUuid.value] || null)

const modalTitle: ComputedRef<string> = computed(() => {
    const c = selected.value?.component
    if (!c) return 'SBOM Component Graph'
    const v = c.version ? `@${c.version}` : ''
    return `SBOM Component Graph — ${c.name || c.canonicalPurl || 'component'}${v}`
})

function nodeLabel (row: any): string {
    const c = row?.component
    if (!c) return row?.uuid || '—'
    return c.canonicalPurl || `${c.name || ''}${c.version ? '@' + c.version : ''}` || row.uuid
}

// Releases marked here are forced to bypass Apollo cache on the next load —
// set when invalidateCache(releaseUuid) is called from the parent.
const forceRefetchReleases: Ref<Set<string>> = ref(new Set())

async function ensureGraphLoaded (releaseUuid: string): Promise<boolean> {
    if (!releaseUuid) return false
    const forceRefetch = forceRefetchReleases.value.has(releaseUuid)
    if (graphCache.value[releaseUuid] && !forceRefetch) return true
    loading.value = true
    loadingMessage.value = 'Loading release dependency graph...'
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query getReleaseSbomComponentsGraph($releaseUuid: ID!) {
                    getReleaseSbomComponents(releaseUuid: $releaseUuid) {
                        uuid
                        sbomComponentUuid
                        component { uuid canonicalPurl type group name version isRoot }
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
                    }
                }`,
            variables: { releaseUuid },
            fetchPolicy: forceRefetch ? 'network-only' : 'cache-first'
        })
        const rows: any[] = (resp.data as any)?.getReleaseSbomComponents || []
        const map: Record<string, any> = {}
        rows.forEach((r: any) => { map[r.uuid] = r })
        graphCache.value = { ...graphCache.value, [releaseUuid]: { byUuid: map } }
        if (forceRefetch) {
            const next = new Set(forceRefetchReleases.value)
            next.delete(releaseUuid)
            forceRefetchReleases.value = next
        }
        return true
    } catch (err: any) {
        errorMessage.value = err?.message || 'Failed to load release SBOM graph.'
        return false
    } finally {
        loading.value = false
    }
}

async function resolveSelection () {
    errorMessage.value = ''
    if (!props.show) return
    if (!props.releaseUuid) {
        errorMessage.value = 'No release context provided.'
        return
    }
    currentReleaseUuid.value = props.releaseUuid
    const loaded = await ensureGraphLoaded(props.releaseUuid)
    if (!loaded) return

    let targetUuid = props.sbomComponentUuid
    if (!targetUuid && props.purl) {
        loading.value = true
        loadingMessage.value = 'Resolving purl...'
        try {
            const orgUuid = props.orgUuid
            if (!orgUuid) {
                errorMessage.value = 'No organization context provided for purl lookup.'
                return
            }
            const sbomUuid = await searchSbomComponentByPurl(orgUuid, props.purl)
            if (!sbomUuid) {
                errorMessage.value = `No SBOM component found for purl "${props.purl}".`
                return
            }
            targetUuid = sbomUuid
        } finally {
            loading.value = false
        }
    }

    if (!targetUuid) {
        errorMessage.value = 'No component identifier provided.'
        return
    }

    // The release_sbom_components row UUID and the canonical sbom_components.uuid
    // are different. The list query keys rows by the release-row UUID; the
    // searchSbomComponentByPurl call returns the canonical UUID. Match by either
    // so callers can pass whichever they have.
    let row = byUuid.value[targetUuid]
    if (!row) {
        row = Object.values(byUuid.value).find((r: any) =>
            r.sbomComponentUuid === targetUuid ||
            r.component?.uuid === targetUuid
        )
    }
    if (!row) {
        errorMessage.value = 'This component is not present in the release SBOM yet (it may be pending reconcile).'
        return
    }
    selectedUuid.value = row.uuid
}

function selectByUuid (rowUuid: string) {
    if (byUuid.value[rowUuid]) selectedUuid.value = rowUuid
}

watch(() => [props.show, props.releaseUuid, props.sbomComponentUuid, props.purl] as const, ([showing]) => {
    if (showing) {
        selectedUuid.value = ''
        resolveSelection()
    }
}, { immediate: true })

// Upstream paths: walk dependedOnBy upward to find each distinct path that
// terminates at a root (or at a node with no further parents). Cycles are
// stopped at the first repeat in the current path. Capped at MAX_PATHS.
const upstreamTruncated: Ref<boolean> = ref(false)
const upstreamPaths: ComputedRef<any[][]> = computed((): any[][] => {
    upstreamTruncated.value = false
    if (!selected.value) return []
    const start = selected.value
    if (start.component?.isRoot) return []

    const map = byUuid.value
    const paths: any[][] = []
    // DFS with explicit stack: each frame is (currentRow, pathSoFar, ancestorsInPath)
    const stack: { row: any; path: any[]; ancestors: Set<string> }[] = [
        { row: start, path: [start], ancestors: new Set([start.uuid]) }
    ]

    while (stack.length && paths.length < MAX_PATHS) {
        const frame = stack.pop()!
        const parents = frame.row.dependedOnBy || []
        const isRoot = frame.row.component?.isRoot
        const noParents = parents.length === 0
        if (frame.path.length > 1 && (isRoot || noParents)) {
            paths.push(frame.path)
            continue
        }
        if (noParents) {
            // start node with no parents and not a root → emit nothing
            continue
        }
        for (const p of parents) {
            const parentRow = map[p.uuid]
            if (!parentRow) continue
            if (frame.ancestors.has(parentRow.uuid)) {
                // cycle — emit the path up to the cycle point
                paths.push([...frame.path, parentRow])
                continue
            }
            const nextAncestors = new Set(frame.ancestors)
            nextAncestors.add(parentRow.uuid)
            stack.push({
                row: parentRow,
                path: [...frame.path, parentRow],
                ancestors: nextAncestors
            })
        }
    }

    if (stack.length) upstreamTruncated.value = true
    return paths
})

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
        key: 'relationshipType',
        title: 'Relationship',
        render: (row: any) => row.relationshipType || ''
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
                    d.sourceExactPurl ? h('div', { style: 'font-size: 11px; color: #666;' }, `source: ${d.sourceExactPurl}`) : null,
                    d.targetExactPurl ? h('div', { style: 'font-size: 11px; color: #666;' }, `target: ${d.targetExactPurl}`) : null
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
            const targetUuid = row.target?.uuid
            if (!targetUuid || !byUuid.value[targetUuid]) return h('span', '')
            return h(NButton, {
                size: 'tiny',
                tertiary: true,
                onClick: () => selectByUuid(targetUuid)
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
            if (!byUuid.value[row.uuid]) return h('span', '')
            return h(NButton, {
                size: 'tiny',
                tertiary: true,
                onClick: () => selectByUuid(row.uuid)
            }, () => 'Open')
        }
    }
]

function invalidateCache (releaseUuid?: string) {
    const refetch = new Set(forceRefetchReleases.value)
    if (releaseUuid) {
        const next = { ...graphCache.value }
        delete next[releaseUuid]
        graphCache.value = next
        refetch.add(releaseUuid)
    } else {
        graphCache.value = {}
        Object.keys(graphCache.value).forEach(k => refetch.add(k))
        if (currentReleaseUuid.value) refetch.add(currentReleaseUuid.value)
    }
    forceRefetchReleases.value = refetch
    if (props.show) {
        resolveSelection()
    }
}

defineExpose({
    selectByUuid,
    invalidateCache
})
</script>

<style scoped>
.upstream-paths {
    max-height: 240px;
    overflow: auto;
    padding: 6px 4px;
    border: 1px solid var(--n-border-color, rgba(255, 255, 255, 0.12));
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
    font-size: 11px;
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
    border-color: #f0a020;
    color: #f0a020;
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
