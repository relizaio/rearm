<template>
    <div class="upstream-sources-panel" data-testid="upstream-sources-panel">
        <n-space justify="space-between" align="center" class="upstream-sources-head">
            <h5>Upstream sources</h5>
            <n-space align="center">
                <n-switch v-model:value="showArchived" data-testid="upstream-show-archived" />
                <span>Show archived</span>
                <n-button v-if="isOrgAdmin && state === 'LIST'" type="primary" size="small" data-testid="upstream-add-source" @click="openCreate">Add source</n-button>
            </n-space>
        </n-space>

        <n-spin :show="loading">
            <div v-if="state === 'FORBIDDEN'" data-testid="upstream-sources-forbidden">{{ forbiddenSentence }}</div>
            <div v-else-if="state === 'ERROR'" class="upstream-sources-error" data-testid="upstream-sources-error">{{ loadError }}</div>
            <div v-else-if="state === 'LIST' && !sources.length" data-testid="upstream-sources-empty">
                {{ isOrgAdmin ? EMPTY_ADMIN : EMPTY_MEMBER }}
            </div>
            <n-data-table
                v-else-if="state === 'LIST'"
                size="small"
                :columns="columns"
                :data="sources"
                :row-key="rowKey"
                :expanded-row-keys="expandedKeys"
                data-testid="upstream-sources-table"
                @update:expanded-row-keys="onExpand" />
        </n-spin>

        <n-modal :show="!!archiveTarget" preset="dialog" type="warning" title="Archive source" :mask-closable="!archiving"
            @update:show="(v: boolean) => { if (!v) cancelArchive() }">
            <div data-testid="upstream-archive-dialog">
                <p data-testid="upstream-archive-statement">{{ archiveTarget ? archiveSourceStatement(archiveTarget.name) : '' }}</p>
                <n-space justify="end">
                    <n-button :disabled="archiving" data-testid="upstream-archive-cancel" @click="cancelArchive">Cancel</n-button>
                    <n-button type="warning" :loading="archiving" :disabled="archiving" data-testid="upstream-archive-confirm" @click="confirmArchive">Archive source</n-button>
                </n-space>
            </div>
        </n-modal>

        <upstream-source-form-modal
            v-if="isOrgAdmin"
            v-model:show="formOpen"
            :org-uuid="orgUuid"
            :source="formSource"
            @saved="onSaved" />
    </div>
</template>

<script lang="ts" setup>
// The Sources tab of Organization Settings (ReARM Pro, TPF-185 design 3.6): the organization's
// upstream sources with their last probe and their schedule, and for an administrator create,
// edit, probe, sync now and archive. Every member with READ on the organization sees the list; the
// form is not mounted for anyone else, so their page holds no credential field. The backend
// decides who may do what; this component only decides which controls to draw.
import { computed, h, onBeforeUnmount, ref, watch } from 'vue'
import type { DataTableColumns } from 'naive-ui'
import { NButton, NDataTable, NModal, NSpace, NSpin, NSwitch, NTag } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isForbidden, refusalOf } from '@/utils/upstream'
import {
    ARCHIVE_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, GET_UPSTREAM_SYNCS, PROBE_UPSTREAM_SOURCE, SYNC_UPSTREAM_SOURCE_NOW,
} from '@/utils/upstreamSourceQueries'
import { intervalWords } from '@/utils/upstreamSourceForm'
import type { LoadedUpstreamSource } from '@/utils/upstreamSourceForm'
import {
    archiveSourceStatement, autoIntegrateSentence, credentialKindLabel, documentClassLabel, forbiddenSentence, labelOf, probeResultSentence,
    probeTimeoutHint, runStopSentence, syncOriginLabel, syncStateLabel, vexModeLabel,
} from '@/utils/upstreamLabels'
import UpstreamProbeResult from './UpstreamProbeResult.vue'
import UpstreamSourceFormModal from './UpstreamSourceFormModal.vue'

const props = defineProps<{
    orgUuid: string
    isOrgAdmin: boolean
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()

const EMPTY_ADMIN = 'No upstream source yet. Add the TEA server of a supplier to follow its products and components.'
const EMPTY_MEMBER = 'No upstream source is configured. An administrator of the organization adds them.'
const KEY_ID_NOT_SHOWN = 'key id not shown to you'
const PROBE_POLL_MS = 2000
const SYNC_POLL_MS = 5000

type PanelState = 'LOADING' | 'LIST' | 'FORBIDDEN' | 'ERROR'

const sources = ref<any[]>([])
const state = ref<PanelState>('LOADING')
const loading = ref(false)
const loadError = ref('')
const showArchived = ref(false)
const expandedKeys = ref<string[]>([])
const syncsBySource = ref<Record<string, { loading: boolean, error: string, rows: any[] }>>({})
const formOpen = ref(false)
const formSource = ref<LoadedUpstreamSource | null>(null)
const archiveTarget = ref<any | null>(null)
const archiving = ref(false)
const busy = ref<Record<string, boolean>>({})

const rowKey = (row: any): string => row.uuid

// ---- Reading ----

async function loadSources () {
    if (!props.orgUuid) return
    loading.value = true
    const firstRead = state.value !== 'LIST'
    try {
        const statuses = showArchived.value ? ['ACTIVE', 'ARCHIVED'] : ['ACTIVE']
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_SOURCES, variables: { org: props.orgUuid, statuses }, fetchPolicy: 'no-cache',
        })
        sources.value = resp?.data?.upstreamSources ?? []
        state.value = 'LIST'
        loadError.value = ''
        startPolls()
    } catch (err) {
        const refusal = refusalOf(err)
        if (firstRead) {
            state.value = isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
            loadError.value = refusal.text
        } else {
            props.notify('error', 'Upstream sources', refusal.text)
        }
    } finally {
        loading.value = false
    }
}

watch(() => props.orgUuid, () => {
    state.value = 'LOADING'
    loadSources()
}, { immediate: true })

watch(showArchived, () => loadSources())

function replaceRow (source: any) {
    if (!source?.uuid) return
    const i = sources.value.findIndex(s => s.uuid === source.uuid)
    if (i >= 0) sources.value.splice(i, 1, source)
}

// ---- Waiting for open states: a probe PENDING (every 2 s), a sync RUNNING (every 5 s) ----

const timers = new Map<string, ReturnType<typeof setTimeout>>()
let unmounted = false

function pollDelayOf (source: any): number | null {
    if (source?.probe?.state === 'PENDING') return PROBE_POLL_MS
    if (source?.lastSync?.state === 'RUNNING') return SYNC_POLL_MS
    return null
}

function schedulePoll (source: any) {
    const delay = pollDelayOf(source)
    if (delay === null || timers.has(source.uuid)) return
    timers.set(source.uuid, setTimeout(() => pollRow(source.uuid), delay))
}

function startPolls () {
    for (const s of sources.value) schedulePoll(s)
}

async function pollRow (uuid: string) {
    timers.delete(uuid)
    if (unmounted) return
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_SOURCE, variables: { uuid }, fetchPolicy: 'no-cache' })
        if (unmounted) return
        const source = resp?.data?.upstreamSource
        replaceRow(source)
        if (source) schedulePoll(source)
    } catch {
        // A refused poll ends the polling of that row; the row keeps what it showed.
    }
}

onBeforeUnmount(() => {
    unmounted = true
    for (const t of timers.values()) clearTimeout(t)
    timers.clear()
})

// ---- Recent syncs of an expanded row ----

async function loadSyncs (uuid: string) {
    syncsBySource.value[uuid] = { loading: true, error: '', rows: [] }
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_SYNCS, variables: { source: uuid }, fetchPolicy: 'no-cache' })
        syncsBySource.value[uuid] = { loading: false, error: '', rows: resp?.data?.upstreamSyncs ?? [] }
    } catch (err) {
        syncsBySource.value[uuid] = { loading: false, error: refusalOf(err).text, rows: [] }
    }
}

function onExpand (keys: Array<string | number>) {
    const next = keys.map(String)
    for (const k of next) {
        if (!expandedKeys.value.includes(k)) loadSyncs(k)
    }
    expandedKeys.value = next
}

// ---- Actions of an administrator ----

function openCreate () {
    formSource.value = null
    formOpen.value = true
}

function openEdit (row: any) {
    formSource.value = row
    formOpen.value = true
}

async function onSaved () {
    await loadSources()
}

async function rowAction (row: any, mutation: any, variables: Record<string, any>, field: string, done: string) {
    busy.value[row.uuid] = true
    try {
        const resp: any = await graphqlClient.mutate({ mutation, variables, fetchPolicy: 'no-cache' })
        const source = resp?.data?.[field]
        replaceRow(source)
        if (source) schedulePoll(source)
        props.notify('success', 'Upstream sources', done)
    } catch (err) {
        props.notify('error', 'Upstream sources', refusalOf(err).text)
    } finally {
        busy.value[row.uuid] = false
    }
}

function probe (row: any) {
    return rowAction(row, PROBE_UPSTREAM_SOURCE, { uuid: row.uuid }, 'probeUpstreamSource', 'Probe started')
}

function syncNow (row: any) {
    return rowAction(row, SYNC_UPSTREAM_SOURCE_NOW, { source: row.uuid }, 'syncUpstreamSourceNow', 'Sync started')
}

function askArchive (row: any) {
    archiveTarget.value = row
}

function cancelArchive () {
    if (archiving.value) return
    archiveTarget.value = null
}

async function confirmArchive () {
    const row = archiveTarget.value
    if (!row || archiving.value) return
    archiving.value = true
    try {
        await graphqlClient.mutate({ mutation: ARCHIVE_UPSTREAM_SOURCE, variables: { uuid: row.uuid }, fetchPolicy: 'no-cache' })
        props.notify('success', 'Upstream sources', 'Source archived')
        archiveTarget.value = null
        await loadSources()
    } catch (err) {
        props.notify('error', 'Upstream sources', refusalOf(err).text)
    } finally {
        archiving.value = false
    }
}

// ---- Cells ----

function dateOf (d: string | null | undefined): string {
    return d ? commonFunctions.dateDisplay(d) : ''
}

function credentialCell (row: any) {
    const c = row.credential ?? {}
    const parts: any[] = [h('span', labelOf(credentialKindLabel, c.kind))]
    if (c.kind === 'API_KEY') {
        parts.push(c.keyId
            ? h('div', { class: 'upstream-cell-small' }, `key id ${c.keyId}`)
            : h('div', { class: 'upstream-cell-small', 'data-testid': 'upstream-key-id-hidden' }, KEY_ID_NOT_SHOWN))
    }
    if (c.kind && c.kind !== 'NONE' && c.setDate) parts.push(h('div', { class: 'upstream-cell-small' }, `set on ${dateOf(c.setDate)}`))
    return h('div', parts)
}

function documentsCell (row: any) {
    const lines: string[] = (row.documentClasses ?? []).map((c: string) => labelOf(documentClassLabel, c))
    lines.push(labelOf(vexModeLabel, row.vexImportMode))
    if (row.autoIntegrate === 'ENABLED') lines.push('auto-integrate')
    return h('div', { title: row.autoIntegrate === 'ENABLED' ? autoIntegrateSentence : undefined },
        lines.filter(Boolean).map(l => h('div', l)))
}

function syncCell (row: any) {
    const lines: any[] = []
    const every = intervalWords(row.syncIntervalMinutes)
    if (every) lines.push(h('div', every))
    if (row.nextSync) lines.push(h('div', `next ${dateOf(row.nextSync)}`))
    const last = row.lastSync
    if (last) {
        const finished = last.finished ? `, ${dateOf(last.finished)}` : ''
        lines.push(h('div', `last: ${labelOf(syncStateLabel, last.state)} (${labelOf(syncOriginLabel, last.origin)})${finished}`))
        if (last.state === 'FAILED') {
            if (last.stopReason) lines.push(h('div', { class: 'upstream-cell-small' }, labelOf(runStopSentence, last.stopReason)))
            if (last.sourceFailure) {
                lines.push(h('div', { class: 'upstream-cell-small' }, labelOf(probeResultSentence, last.sourceFailure)))
                if (last.sourceFailure === 'TIMED_OUT') lines.push(h('div', { class: 'upstream-cell-small' }, probeTimeoutHint))
            }
        }
    }
    if (row.syncFailures > 0) {
        lines.push(h(NTag, { size: 'small', type: 'error' }, { default: () => `${row.syncFailures} failed syncs in a row` }))
    }
    return h('div', lines)
}

function nameCell (row: any) {
    const parts: any[] = [h('span', row.name)]
    if (row.status === 'ARCHIVED') {
        parts.push(h(NTag, { size: 'small', class: 'upstream-cell-tag' }, { default: () => 'Archived' }))
        if (row.archivedDate) parts.push(h('div', { class: 'upstream-cell-small' }, dateOf(row.archivedDate)))
    }
    return h('div', parts)
}

function actionsCell (row: any) {
    if (!props.isOrgAdmin || row.status !== 'ACTIVE') return null
    const disabled = !!busy.value[row.uuid]
    const button = (label: string, testid: string, onClick: () => void, type: 'default' | 'warning' = 'default') =>
        h(NButton, { size: 'tiny', type, disabled, 'data-testid': testid, onClick }, { default: () => label })
    return h(NSpace, { size: 'small' }, {
        default: () => [
            button('Edit', 'upstream-action-edit', () => openEdit(row)),
            button('Probe', 'upstream-action-probe', () => probe(row)),
            button('Sync now', 'upstream-action-sync', () => syncNow(row)),
            button('Archive', 'upstream-action-archive', () => askArchive(row), 'warning'),
        ],
    })
}

function syncsExpand (row: any) {
    const entry = syncsBySource.value[row.uuid]
    if (!entry || entry.loading) return h('div', 'Loading recent syncs')
    if (entry.error) return h('div', entry.error)
    if (!entry.rows.length) return h('div', 'No sync yet.')
    return h('div', { 'data-testid': 'upstream-recent-syncs' }, [
        h('strong', 'Recent syncs'),
        ...entry.rows.map((s: any) => {
            const c = s.counters ?? {}
            return h('div', { class: 'upstream-sync-line' },
                `${labelOf(syncOriginLabel, s.origin)}, ${labelOf(syncStateLabel, s.state)}, created ${dateOf(s.createdDate)}`
                + (s.finished ? `, finished ${dateOf(s.finished)}` : '')
                + `: ${c.follows ?? 0} follows, ${c.runsCompleted ?? 0} runs completed, ${c.runsWithFailures ?? 0} with failures, `
                + `${c.runsStopped ?? 0} stopped, ${c.ingested ?? 0} releases ingested, ${c.newCollectionVersions ?? 0} new collection versions, `
                + `${c.held ?? 0} held, ${c.failed ?? 0} failed`)
        }),
    ])
}

const columns = computed((): DataTableColumns<any> => {
    const cols: DataTableColumns<any> = [
        { type: 'expand', renderExpand: syncsExpand },
        { key: 'name', title: 'Name', render: nameCell },
        { key: 'root', title: 'Server', render: (row: any) => h('span', row.tea?.root ?? '') },
        { key: 'credential', title: 'Credential', render: credentialCell },
        { key: 'documents', title: 'Documents', render: documentsCell },
        { key: 'probe', title: 'Last probe', render: (row: any) => h(UpstreamProbeResult, { probe: row.probe, credentialKind: row.credential?.kind }) },
        { key: 'sync', title: 'Sync', render: syncCell },
    ]
    if (props.isOrgAdmin) cols.push({ key: 'actions', title: 'Actions', render: actionsCell })
    return cols
})
</script>

<style scoped>
.upstream-sources-head h5 {
    margin: 0;
}
.upstream-sources-error {
    color: #d03050;
}
:deep(.upstream-cell-small) {
    font-size: 0.85em;
    color: #777;
}
:deep(.upstream-cell-tag) {
    margin-left: 6px;
}
:deep(.upstream-sync-line) {
    font-size: 0.9em;
}
</style>
