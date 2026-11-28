<template>
    <div class="upstream-one-off-panel" data-testid="upstream-one-off-panel">
        <div v-if="canWrite" class="upstream-tei-form" data-testid="upstream-tei-form">
            <n-form label-placement="top" @submit.prevent>
                <n-form-item label="TEI" :feedback="shownTeiProblem" :validation-status="shownTeiProblem ? 'error' : undefined">
                    <n-input v-model:value="teiText" placeholder="tei://..." data-testid="upstream-tei-input" @keyup.enter="onIngest" />
                </n-form-item>
                <div class="upstream-hint" data-testid="upstream-tei-hint">{{ TEI_HINT }}</div>
                <n-form-item>
                    <template #label><field-label label="Source" :tip="TEI_SOURCE_HELP" /></template>
                    <n-select v-model:value="sourceChoice" :options="sourceOptions" style="max-width: 360px" data-testid="upstream-tei-source" />
                </n-form-item>
            </n-form>
            <n-button type="primary" :loading="resolving" :disabled="resolving" data-testid="upstream-tei-ingest" @click="onIngest">Ingest this release</n-button>
            <n-alert v-if="resolveError" type="error" class="upstream-alert" data-testid="upstream-tei-resolve-error">{{ resolveError }}</n-alert>
        </div>

        <div v-if="openUuid" class="upstream-one-off" data-testid="upstream-one-off">
            <n-spin :show="openLoading">
                <div v-if="openState === 'FORBIDDEN'" data-testid="upstream-one-off-forbidden">{{ forbiddenSentence }}</div>
                <div v-else-if="openState === 'ERROR'" class="upstream-error" data-testid="upstream-one-off-error">{{ openError }}</div>
                <div v-else-if="oneOff" class="upstream-one-off-body" data-testid="upstream-one-off-body">
                    <div class="upstream-one-off-head">
                        <span class="upstream-one-off-tei" data-testid="upstream-one-off-tei">{{ oneOff.tei }}</span>
                        <n-tag size="small" class="upstream-cell-tag" data-testid="upstream-one-off-state">{{ labelOf(oneOffStateLabel, oneOff.state) }}</n-tag>
                    </div>

                    <div v-if="oneOff.state === OneOffState.RESOLVING" data-testid="upstream-one-off-resolving">
                        <n-spin :size="14" class="upstream-inline-spin" />
                        {{ RESOLVING_TEXT }}
                    </div>

                    <div v-else-if="oneOff.state === OneOffState.AMBIGUOUS" data-testid="upstream-one-off-ambiguous">
                        <p>{{ AMBIGUOUS_TEXT }}</p>
                        <n-data-table size="small" :columns="candidateColumns" :data="oneOff.candidates" :row-key="candidateKey" data-testid="upstream-one-off-candidates" />
                        <p class="upstream-hint">{{ CHOICE_OPEN_TEXT }}</p>
                    </div>

                    <div v-else-if="oneOff.state === OneOffState.REFUSED" data-testid="upstream-one-off-refused">
                        <n-alert type="error" data-testid="upstream-one-off-failure">{{ labelOf(oneOffFailureSentence, oneOff.failure) }}</n-alert>
                        <div v-if="oneOff.failure === NO_MATCHING_SOURCE" class="upstream-unmatched" data-testid="upstream-one-off-unmatched">
                            <div>The TEI leads to:</div>
                            <div v-for="root in oneOff.unmatchedRoots" :key="root" class="upstream-unmatched-root" data-testid="upstream-unmatched-root">
                                <span class="upstream-root-text" data-testid="upstream-unmatched-root-text">{{ root }}</span>
                                <n-button v-if="oneOff.sourceCanBeAdded" size="small" data-testid="upstream-add-source" @click="openAddSource(root)">
                                    Add this server as a source
                                </n-button>
                            </div>
                            <div v-if="!oneOff.sourceCanBeAdded" data-testid="upstream-admin-can-add">{{ ADMIN_CAN_ADD_TEXT }}</div>
                            <div v-if="addedSource" class="upstream-resolve-again" data-testid="upstream-resolve-again-block">
                                <n-button type="primary" size="small" :loading="acting" :disabled="acting" data-testid="upstream-resolve-again" @click="onResolveAgain">
                                    Resolve the TEI again
                                </n-button>
                            </div>
                        </div>
                        <upstream-probe-result v-if="oneOff.sourceFailure" :probe="sourceFailureProbe" />
                    </div>

                    <div v-else-if="oneOff.state === OneOffState.QUEUED" data-testid="upstream-one-off-queued">
                        <n-spin :size="14" class="upstream-inline-spin" />
                        {{ QUEUED_TEXT }}
                        <span v-if="oneOff.lastRun" data-testid="upstream-one-off-run-state">Run: {{ labelOf(runStateLabel, oneOff.lastRun.state) }}</span>
                    </div>

                    <div v-else-if="oneOff.state === OneOffState.INGESTED" data-testid="upstream-one-off-ingested">
                        <div>
                            Ingested.
                            <router-link v-if="releaseLink" :to="releaseLink" data-testid="upstream-one-off-release-link">Open the release</router-link>
                        </div>
                        <p data-testid="upstream-one-off-frozen">{{ oneOffFrozenSentence }}</p>
                        <n-button v-if="canWrite" size="small" :loading="acting" :disabled="acting" data-testid="upstream-one-off-refresh" @click="onRefresh">Refresh</n-button>
                    </div>

                    <div v-else-if="oneOff.state === OneOffState.ADOPTED" data-testid="upstream-one-off-adopted">
                        <div>
                            {{ ADOPTED_TEXT }}
                            <router-link v-if="releaseLink" :to="releaseLink" data-testid="upstream-one-off-release-link">Open the release</router-link>
                        </div>
                    </div>

                    <div v-else-if="oneOff.state === OneOffState.FAILED" data-testid="upstream-one-off-failed">
                        <n-alert type="error" data-testid="upstream-one-off-failed-message">{{ failedMessage }}</n-alert>
                        <n-button v-if="canWrite" size="small" :loading="acting" :disabled="acting" data-testid="upstream-one-off-try-again" @click="onTryAgain">Try again</n-button>
                    </div>

                    <n-alert v-if="actionError" type="error" class="upstream-alert" data-testid="upstream-one-off-action-error">{{ actionError }}</n-alert>
                    <n-alert v-if="readError" type="warning" class="upstream-alert" data-testid="upstream-one-off-read-error">{{ readError }}</n-alert>
                </div>
            </n-spin>
        </div>

        <h5 class="upstream-one-offs-title">Ingested by TEI</h5>
        <n-spin :show="listLoading">
            <div v-if="listState === 'FORBIDDEN'" data-testid="upstream-one-offs-forbidden">{{ forbiddenSentence }}</div>
            <div v-else-if="listState === 'ERROR'" class="upstream-error" data-testid="upstream-one-offs-error">{{ listError }}</div>
            <div v-else-if="listState === 'LIST' && !oneOffs.length" data-testid="upstream-one-offs-empty">{{ EMPTY_TEXT }}</div>
            <n-data-table v-else-if="listState === 'LIST'" size="small" :columns="listColumns" :data="oneOffs" :row-key="(row: any) => row.uuid"
                :row-props="listRowProps" data-testid="upstream-one-offs-table" />
        </n-spin>

        <upstream-source-form-modal
            v-if="addRoot !== null"
            :show="addOpen"
            :org-uuid="orgUuid"
            :initial-root="addRoot"
            @update:show="onAddShow"
            @saved="onSourceSaved" />
    </div>
</template>

<script lang="ts" setup>
// The tab "By TEI" of the Upstream page (ReARM Pro, TPF-185 design 3.10): ingest one release by
// its TEI, follow the one-off through its states, and the one-offs of the organization. The TEI
// is checked by its characters only and sent as a variable of the resolve; it is never resolved,
// fetched or put into a route here. The open one-off is named in the route by its uuid alone.
// Roots and TEIs are shown as text, never as links; the only link is a router link to the
// release by the uuid the backend served.
import { computed, h, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { DataTableColumns } from 'naive-ui'
import { NAlert, NButton, NDataTable, NForm, NFormItem, NInput, NSelect, NSpin, NTag } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isForbidden, isUuid, refusalOf } from '@/utils/upstream'
import {
    CHOOSE_UPSTREAM_ONE_OFF, GET_UPSTREAM_ONE_OFF, GET_UPSTREAM_ONE_OFFS, REFRESH_UPSTREAM_ONE_OFF, RESOLVE_UPSTREAM_TEI,
} from '@/utils/upstreamOneOffQueries'
import { teiOf, teiProblem } from '@/utils/upstreamTei'
import {
    forbiddenSentence, labelOf, oneOffFailureSentence, oneOffFrozenSentence, oneOffStateLabel, probeResultSentence, runStateLabel,
} from '@/utils/upstreamLabels'
import UpstreamProbeResult from './UpstreamProbeResult.vue'
import UpstreamSourceFormModal from './UpstreamSourceFormModal.vue'
import FieldLabel from '../FieldLabel.vue'
import { TEI_SOURCE_HELP } from '@/utils/upstreamHelp'

enum OneOffState {
    RESOLVING = 'RESOLVING',
    AMBIGUOUS = 'AMBIGUOUS',
    REFUSED = 'REFUSED',
    QUEUED = 'QUEUED',
    INGESTED = 'INGESTED',
    FAILED = 'FAILED',
    ADOPTED = 'ADOPTED',
}
const NO_MATCHING_SOURCE = 'NO_MATCHING_SOURCE'
const RELEASE_FAILED = 'FAILED'
/** The states the backend still moves on: the open one-off is read again while it is in one. */
const OPEN_STATES: ReadonlySet<string> = new Set([OneOffState.RESOLVING, OneOffState.QUEUED])
const POLL_MS = 2000
/** The select's value for "any source": source values are uuids, so it cannot collide. */
const ANY_SOURCE = 'ANY'

const TEI_HINT = 'A TEI names one release, for example tei://example.com/...'
const RESOLVING_TEXT = 'Resolving the TEI through ReARM. This takes up to two minutes.'
const AMBIGUOUS_TEXT = 'This TEI names more than one release on your sources. Choose the one to ingest.'
const CHOICE_OPEN_TEXT = 'The choice stays open for 30 minutes.'
const ADMIN_CAN_ADD_TEXT = 'An administrator of the organization can add the server as an upstream source.'
const QUEUED_TEXT = 'The release is queued for ingestion.'
const ADOPTED_TEXT = 'A follow has taken this release over. Its syncs keep it up to date.'
const EMPTY_TEXT = 'No release has been ingested by TEI yet.'

type ReadState = 'LOADING' | 'LIST' | 'FORBIDDEN' | 'ERROR'
type OpenState = 'LOADING' | 'SHOWN' | 'FORBIDDEN' | 'ERROR'

const props = defineProps<{
    orgUuid: string
    /** The sources of the organization, active and archived, for their names and the source choice. */
    sources: any[]
    /** WRITE on the organization: resolve, choose and refresh. The backend decides; this only draws. */
    canWrite: boolean
}>()

const emit = defineEmits<{
    (e: 'source-added', uuid: string): void
}>()

const route = useRoute()
const router = useRouter()

// ---- the form ----

const teiText = ref('')
const sourceChoice = ref<string>(ANY_SOURCE)
const attempted = ref(false)
const resolving = ref(false)
const resolveError = ref('')

const teiProblemNow = computed((): string | null => teiProblem(teiText.value))
const shownTeiProblem = computed((): string => (attempted.value || teiOf(teiText.value) !== '') ? (teiProblemNow.value ?? '') : '')

const activeSources = computed((): any[] => props.sources.filter(s => s.status === 'ACTIVE'))
const sourceOptions = computed(() => [
    { label: 'Any source that matches', value: ANY_SOURCE },
    ...activeSources.value.map(s => ({ label: s.name, value: s.uuid })),
])

function sourceName (uuid: string | null | undefined): string {
    if (!uuid) return ''
    const s = props.sources.find(x => x.uuid === uuid)
    return s ? s.name : uuid
}

async function onIngest () {
    attempted.value = true
    if (resolving.value || teiProblemNow.value) return
    const source = sourceChoice.value === ANY_SOURCE ? null : sourceChoice.value
    resolving.value = true
    resolveError.value = ''
    try {
        const shown = await resolve(teiOf(teiText.value), source)
        if (shown) {
            teiText.value = ''
            attempted.value = false
        }
    } catch (err) {
        resolveError.value = refusalOf(err).text
    } finally {
        resolving.value = false
    }
}

/** Sends the resolve and opens its answer. Throws the refusal for the caller to show. */
async function resolve (tei: string, source: string | null): Promise<boolean> {
    const resp: any = await graphqlClient.mutate({ mutation: RESOLVE_UPSTREAM_TEI, variables: { org: props.orgUuid, tei, source }, fetchPolicy: 'no-cache' })
    const answer = resp?.data?.resolveUpstreamTei
    if (!answer || !isUuid(answer.uuid)) return false
    show(answer)
    openInRoute(answer.uuid)
    loadList()
    return true
}

// ---- the open one-off ----

const openUuid = computed((): string | null => {
    const v = route.query.oneOff
    return typeof v === 'string' && isUuid(v) ? v : null
})

const oneOff = ref<any | null>(null)
const openState = ref<OpenState>('LOADING')
const openLoading = ref(false)
const openError = ref('')
const acting = ref(false)
const actionError = ref('')
const readError = ref('')
const addRoot = ref<string | null>(null)
const addOpen = ref(false)
const addedSource = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

function stopPolling () {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
}

function openInRoute (uuid: string) {
    router.replace({ query: { ...route.query, tab: 'tei', oneOff: uuid } })
}

function show (answer: any) {
    const before = oneOff.value
    const changedOneOff = before?.uuid !== answer.uuid
    oneOff.value = answer
    openState.value = 'SHOWN'
    openError.value = ''
    if (changedOneOff) {
        actionError.value = ''
        readError.value = ''
        addedSource.value = null
        addRoot.value = null
        addOpen.value = false
    }
    stopPolling()
    if (OPEN_STATES.has(answer.state)) pollTimer = setTimeout(poll, POLL_MS)
    // A one-off that closed changes its row in the list.
    if (before && !changedOneOff && OPEN_STATES.has(before.state) && !OPEN_STATES.has(answer.state)) loadList()
}

async function readOneOff (uuid: string): Promise<any | null> {
    const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_ONE_OFF, variables: { uuid }, fetchPolicy: 'no-cache' })
    return resp?.data?.upstreamOneOff ?? null
}

async function loadOpen (uuid: string) {
    stopPolling()
    openLoading.value = true
    openState.value = 'LOADING'
    try {
        const answer = await readOneOff(uuid)
        if (openUuid.value !== uuid) return
        if (answer) show(answer)
    } catch (err) {
        if (openUuid.value !== uuid) return
        const refusal = refusalOf(err)
        oneOff.value = null
        openState.value = isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
        openError.value = refusal.text
    } finally {
        openLoading.value = false
    }
}

async function poll () {
    pollTimer = null
    const uuid = oneOff.value?.uuid
    if (!uuid) return
    try {
        const answer = await readOneOff(uuid)
        if (answer && oneOff.value?.uuid === uuid) {
            readError.value = ''
            show(answer)
        }
    } catch (err) {
        // A failed read while waiting is shown; the one-off stays as it was last read and is read
        // again at the next interval.
        if (oneOff.value?.uuid !== uuid) return
        readError.value = refusalOf(err).text
        if (!pollTimer && OPEN_STATES.has(oneOff.value.state)) pollTimer = setTimeout(poll, POLL_MS)
    }
}

watch(openUuid, (uuid) => {
    if (!uuid) {
        stopPolling()
        oneOff.value = null
        return
    }
    if (oneOff.value?.uuid === uuid) return
    loadOpen(uuid)
}, { immediate: true })

onBeforeUnmount(stopPolling)

const releaseLink = computed(() => {
    const uuid = oneOff.value?.release?.release
    return typeof uuid === 'string' && isUuid(uuid) ? { name: 'ReleaseView', params: { uuid } } : null
})

const failedMessage = computed((): string => {
    const o = oneOff.value
    if (o?.lastRun?.message) return o.lastRun.message
    if (o?.release?.state === RELEASE_FAILED && o.release.message) return o.release.message
    return labelOf(oneOffStateLabel, OneOffState.FAILED)
})

const sourceFailureProbe = computed(() => ({
    state: 'DONE',
    result: oneOff.value?.sourceFailure,
    message: labelOf(probeResultSentence, oneOff.value?.sourceFailure),
}))

/** One mutation on the open one-off at a time; its answer is shown, its refusal in the alert. */
async function act (send: () => Promise<any>) {
    if (acting.value) return
    acting.value = true
    actionError.value = ''
    try {
        const answer = await send()
        if (answer && isUuid(answer.uuid)) {
            show(answer)
            loadList()
        }
    } catch (err) {
        actionError.value = refusalOf(err).text
    } finally {
        acting.value = false
    }
}

function onChoose (candidate: any) {
    const uuid = oneOff.value?.uuid
    if (!uuid) return
    act(async () => {
        const resp: any = await graphqlClient.mutate({
            mutation: CHOOSE_UPSTREAM_ONE_OFF,
            variables: { oneOff: uuid, upstreamRelease: candidate.upstreamRelease, source: candidate.source },
            fetchPolicy: 'no-cache',
        })
        return resp?.data?.chooseUpstreamOneOff
    })
}

function onRefresh () {
    const uuid = oneOff.value?.uuid
    if (!uuid) return
    act(async () => {
        const resp: any = await graphqlClient.mutate({ mutation: REFRESH_UPSTREAM_ONE_OFF, variables: { oneOff: uuid }, fetchPolicy: 'no-cache' })
        return resp?.data?.refreshUpstreamOneOff
    })
}

function onTryAgain () {
    if (oneOff.value?.release) {
        onRefresh()
        return
    }
    // Nothing was matched: the form gets the same TEI back, to resolve again as it is or with a source.
    teiText.value = oneOff.value?.tei ?? ''
    attempted.value = false
}

function openAddSource (root: string) {
    addRoot.value = root
    addOpen.value = true
}

function onAddShow (open: boolean) {
    addOpen.value = open
    if (!open) addRoot.value = null
}

function onSourceSaved (uuid: string) {
    if (isUuid(uuid)) {
        addedSource.value = uuid
        emit('source-added', uuid)
    }
}

function onResolveAgain () {
    const tei = oneOff.value?.tei
    const source = addedSource.value
    if (!tei || !source) return
    act(async () => {
        await resolve(tei, source)
        return null
    })
}

const candidateKey = (row: any) => row.upstreamRelease + ':' + row.source

const candidateColumns = computed((): DataTableColumns<any> => [
    { key: 'source', title: 'Source', render: (row: any) => h('span', sourceName(row.source)) },
    { key: 'root', title: 'Server root', render: (row: any) => h('span', { class: 'upstream-root-text' }, row.root) },
    { key: 'upstreamRelease', title: 'Upstream release', render: (row: any) => h('span', row.upstreamRelease) },
    {
        key: 'choose',
        title: '',
        render: (row: any) => props.canWrite
            ? h(NButton, { size: 'small', disabled: acting.value, 'data-testid': 'upstream-choose-candidate', onClick: () => onChoose(row) }, { default: () => 'Ingest this one' })
            : null,
    },
])

// ---- the list ----

const oneOffs = ref<any[]>([])
const listState = ref<ReadState>('LOADING')
const listLoading = ref(false)
const listError = ref('')

async function loadList () {
    listLoading.value = true
    const firstRead = listState.value !== 'LIST'
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_ONE_OFFS, variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' })
        oneOffs.value = resp?.data?.upstreamOneOffs ?? []
        listState.value = 'LIST'
        listError.value = ''
    } catch (err) {
        const refusal = refusalOf(err)
        listState.value = firstRead && isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
        listError.value = refusal.text
    } finally {
        listLoading.value = false
    }
}

watch(() => props.orgUuid, (org) => {
    if (isUuid(org)) loadList()
}, { immediate: true })

function releaseCell (row: any) {
    const uuid = row.release?.release
    if (typeof uuid !== 'string' || !isUuid(uuid)) return null
    return h(RouterLink, { to: { name: 'ReleaseView', params: { uuid } }, 'data-testid': 'upstream-one-off-row-release' }, { default: () => 'Open the release' })
}

const listColumns = computed((): DataTableColumns<any> => [
    { key: 'tei', title: 'TEI', render: (row: any) => h('span', { class: 'upstream-one-off-tei' }, row.tei) },
    { key: 'state', title: 'State', render: (row: any) => h('span', labelOf(oneOffStateLabel, row.state)) },
    { key: 'source', title: 'Source', render: (row: any) => h('span', sourceName(row.source)) },
    { key: 'release', title: 'Release', render: releaseCell },
    { key: 'createdDate', title: 'Created', render: (row: any) => h('span', commonFunctions.dateDisplay(row.createdDate)) },
])

function listRowProps (row: any) {
    return {
        class: 'upstream-one-off-row',
        'data-testid': 'upstream-one-off-row',
        onClick: () => {
            if (isUuid(row.uuid)) openInRoute(row.uuid)
        },
    }
}
</script>

<style scoped>
.upstream-tei-form {
    max-width: 720px;
    margin-bottom: 16px;
}
.upstream-hint {
    color: #888;
    font-size: 12px;
    margin: -8px 0 12px;
}
.upstream-alert {
    margin-top: 8px;
}
.upstream-one-off {
    border: 1px solid #ddd;
    border-radius: 4px;
    padding: 12px;
    margin-bottom: 16px;
}
.upstream-one-off-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}
.upstream-one-off-tei,
.upstream-root-text {
    font-family: monospace;
    word-break: break-all;
}
.upstream-unmatched {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 8px 0;
}
.upstream-unmatched-root {
    display: flex;
    align-items: center;
    gap: 8px;
}
.upstream-inline-spin {
    margin-right: 6px;
}
.upstream-one-off-row {
    cursor: pointer;
}
.upstream-error {
    color: #d03050;
}
.upstream-one-offs-title {
    margin: 8px 0;
}
</style>
