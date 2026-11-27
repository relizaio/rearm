<template>
    <n-modal :show="show" preset="card" title="Follow an item" style="width: 900px; max-width: 95vw;" @update:show="onUpdateShow">
        <div data-testid="upstream-follow-start">
            <n-form label-placement="top" @submit.prevent>
                <n-form-item label="Source" :feedback="tried && !model.source ? SOURCE_REQUIRED : ''" :validation-status="tried && !model.source ? 'error' : undefined">
                    <n-select :value="model.source" :options="sourceOptions" placeholder="Choose a source" data-testid="upstream-start-source" @update:value="onSource" />
                </n-form-item>
                <n-form-item label="Kind">
                    <n-radio-group :value="model.kind" data-testid="upstream-start-kind" @update:value="onKind">
                        <n-space>
                            <n-radio v-for="k in ITEM_KINDS" :key="k" :value="k" :data-testid="`upstream-start-kind-${k}`">{{ itemKindLabel[k] }}</n-radio>
                        </n-space>
                    </n-radio-group>
                </n-form-item>
                <n-form-item :show-label="false">
                    <n-radio-group :value="paneMode" data-testid="upstream-start-mode" @update:value="onMode">
                        <n-radio-button value="LIST" data-testid="upstream-start-mode-LIST">{{ offerModeListLabel }}</n-radio-button>
                        <n-radio-button value="UUID" data-testid="upstream-start-mode-UUID">{{ offerModeUuidLabel }}</n-radio-button>
                    </n-radio-group>
                </n-form-item>
                <n-form-item v-if="paneMode === 'UUID'" label="Upstream uuid" :feedback="uuidFeedback" :validation-status="uuidProblem && tried ? 'error' : undefined">
                    <n-input v-model:value="model.upstreamUuid" type="text" placeholder="00000000-0000-0000-0000-000000000000" data-testid="upstream-start-uuid" />
                </n-form-item>
            </n-form>

            <div v-if="paneMode === 'LIST'" class="upstream-start-list-pane" data-testid="upstream-start-list-pane">
                <div v-if="gate !== 'READY'" class="upstream-start-gate" data-testid="upstream-start-gate">
                    <div v-if="gate === 'NO_SOURCE'">{{ offerChooseSource }}</div>
                    <n-spin v-else-if="gate === 'READING'" :size="16" />
                    <div v-else-if="gate === 'ARCHIVED'">{{ offerSourceArchived }}</div>
                    <template v-else-if="gate === 'NEVER_PROBED'">
                        <div>{{ offerNeverProbed }}</div>
                        <n-button size="small" :loading="probing" :disabled="probing" data-testid="upstream-start-probe" @click="probe">Probe</n-button>
                    </template>
                    <template v-else-if="gate === 'PROBE_PENDING'">
                        <upstream-probe-result :probe="sourceRow?.probe" :credential-kind="sourceRow?.credential?.kind" />
                        <div>{{ offerProbePending }}</div>
                    </template>
                    <template v-else-if="gate === 'PROBE_FAILED'">
                        <upstream-probe-result :probe="sourceRow?.probe" :credential-kind="sourceRow?.credential?.kind" />
                        <div>{{ offerProbeFailed }}</div>
                        <n-button size="small" :loading="probing" :disabled="probing" data-testid="upstream-start-probe" @click="probe">Probe again</n-button>
                    </template>
                    <div v-else-if="gate === 'FORBIDDEN'">{{ offerForbiddenSentence }}</div>
                    <div v-else-if="gate === 'ERROR'" class="upstream-start-gate-error">{{ gateError }}</div>
                    <div v-if="probeError" class="upstream-start-gate-error" data-testid="upstream-start-probe-error">{{ probeError }}</div>
                </div>

                <template v-else>
                    <div class="upstream-start-lookup">
                        <div class="upstream-start-lookup-label">Look up by identifier</div>
                        <n-space align="center">
                            <n-select v-model:value="lookupType" :options="identifierTypeOptions" placeholder="Identifier type" style="width: 200px"
                                data-testid="upstream-start-lookup-type" />
                            <n-input v-model:value="lookupValue" placeholder="Exact value" style="width: 380px" data-testid="upstream-start-lookup-value"
                                @keyup.enter="lookUp" />
                            <n-button size="small" :disabled="listInFlight" data-testid="upstream-start-lookup-submit" @click="lookUp">Look up</n-button>
                        </n-space>
                        <div v-if="lookupProblem" class="upstream-start-problem" data-testid="upstream-start-lookup-problem">{{ lookupProblem }}</div>
                    </div>

                    <div v-if="view === 'LOOKUP'" class="upstream-start-lookup-head">
                        <span>{{ offerLookupHead }}</span>
                        <n-button size="small" data-testid="upstream-start-lookup-back" @click="backToList">Back to the list</n-button>
                    </div>

                    <div v-if="view === 'BROWSE' && browse.set.rows.length" class="upstream-start-filter">
                        <n-input :value="filterText" :placeholder="offerFilterPlaceholder" clearable style="width: 300px" data-testid="upstream-start-filter"
                            @update:value="onFilter" />
                        <span class="upstream-start-note" data-testid="upstream-start-filter-sentence">{{ offerFilterSentence(browse.set.rows.length) }}</span>
                        <div v-if="filterText.trim()" class="upstream-start-note" data-testid="upstream-start-filter-count">
                            {{ offerFilterCount(shownRows.length, browse.set.rows.length) }}
                        </div>
                    </div>

                    <div v-if="current.phase !== 'LOADED' && wantedFor(view)" class="upstream-start-loading" data-testid="upstream-start-waiting">
                        <n-spin :size="14" /> {{ offerWaiting }}
                    </div>
                    <div v-else-if="current.phase === 'LOADING'" class="upstream-start-loading" data-testid="upstream-start-loading">
                        <n-spin :size="14" /> {{ offerLoading }}
                    </div>
                    <div v-else-if="current.phase === 'FAILED' && current.failure" data-testid="upstream-start-list-failure">
                        <upstream-probe-result v-if="current.failure.page?.failure === 'SOURCE_FAILURE'" :probe="failureProbe(current.failure.page)"
                            :credential-kind="sourceRow?.credential?.kind" />
                        <n-alert v-else type="error" class="upstream-start-alert">{{ current.failure.text }}</n-alert>
                        <n-button size="small" :disabled="listInFlight" data-testid="upstream-start-retry" @click="retry">Try again</n-button>
                    </div>
                    <template v-else-if="current.phase === 'LOADED'">
                        <div v-if="cleanEmpty" data-testid="upstream-start-empty">
                            <template v-if="view === 'BROWSE'">
                                <div>{{ labelOf(offerEmptySentence, current.kind) }}</div>
                                <div class="upstream-start-note">{{ offerEmptyHint }}</div>
                            </template>
                            <template v-else>
                                <div>{{ labelOf(offerLookupNoneSentence, current.kind) }}</div>
                                <div class="upstream-start-note">{{ offerLookupExactHint }}</div>
                            </template>
                        </div>
                        <template v-else>
                            <upstream-offer-table
                                v-if="current.set.rows.length"
                                :key="`${view}-${current.id}`"
                                :rows="shownRows"
                                :org-uuid="orgUuid"
                                :disabled="sending"
                                :page="current.tablePage"
                                @update:page="(p: number) => { current.tablePage = p }"
                                @prepare="prepareRow" />
                            <div class="upstream-start-statement" data-testid="upstream-start-statement">
                                <div data-testid="upstream-start-count">{{ statements.count }}</div>
                                <div v-if="current.later.loading" class="upstream-start-loading" data-testid="upstream-start-loading">
                                    <n-spin :size="14" /> {{ offerLoading }}
                                </div>
                                <div v-else-if="current.later.failure" data-testid="upstream-start-list-failure">
                                    <upstream-probe-result v-if="current.later.failure.page?.failure === 'SOURCE_FAILURE'" :probe="failureProbe(current.later.failure.page)"
                                        :credential-kind="sourceRow?.credential?.kind" />
                                    <n-alert v-else type="error" class="upstream-start-alert">{{ current.later.failure.text }}</n-alert>
                                    <n-button v-if="current.later.restart" size="small" :disabled="listInFlight" data-testid="upstream-start-restart" @click="restart">
                                        Start again from the first page
                                    </n-button>
                                    <n-button v-else-if="statements.canLoadMore" size="small" :disabled="listInFlight" data-testid="upstream-start-more" @click="loadMore">
                                        Load more
                                    </n-button>
                                </div>
                                <div v-else data-testid="upstream-start-next">
                                    <span>{{ statements.secondText }}</span>
                                    <n-button v-if="statements.canLoadMore" size="small" class="upstream-start-more" :disabled="listInFlight" data-testid="upstream-start-more"
                                        @click="loadMore">Load more</n-button>
                                </div>
                                <n-alert v-for="w in statements.warnings" :key="w" type="warning" class="upstream-start-alert" data-testid="upstream-start-warning">{{ w }}</n-alert>
                                <div v-if="statements.hint" class="upstream-start-note" data-testid="upstream-start-hint">{{ statements.hint }}</div>
                            </div>
                        </template>
                    </template>
                </template>
            </div>

            <n-alert v-if="error" type="error" class="upstream-start-error" data-testid="upstream-start-error">{{ error }}</n-alert>
            <n-space justify="end">
                <n-button :disabled="sending" data-testid="upstream-start-cancel" @click="close">Cancel</n-button>
                <n-button v-if="paneMode === 'UUID'" type="primary" :loading="sending" :disabled="sending" data-testid="upstream-start-submit" @click="submit">Prepare</n-button>
            </n-space>
        </div>
    </n-modal>
</template>

<script lang="ts" setup>
// The start of a follow (ReARM Pro, TPF-185 design 3.7, TPF-243 design 3.2 to 3.9): a source, a
// kind, and the item, chosen from the source's list or entered by its uuid. A row's Prepare and
// the uuid's Prepare send the same preparation and open the confirmation page; nothing is followed
// by this step. Without the sources prop it reads the active sources of the organization itself.
//
// The list (mode LIST) is read only for a source the dialog itself has just read as ACTIVE with a
// finished probe that reached the server. One list call is in flight at a time for the life of the
// component; a first page wanted meanwhile waits, the latest wins. Every list call carries the
// request number of its moment: a change of source or kind, a close and an open raise it, and an
// answer of an older number changes nothing. Per open the dialog asks for at most one first page
// per chosen source and kind by itself; every other list call is one click. All of this state
// lives in the component only.
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NAlert, NButton, NForm, NFormItem, NInput, NModal, NRadio, NRadioButton, NRadioGroup, NSelect, NSpace, NSpin } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { isForbidden, isUuid, PROBE_POLL_MS, refusalOf } from '@/utils/upstream'
import { PREPARE_UPSTREAM_FOLLOW } from '@/utils/upstreamFollowQueries'
import { GET_UPSTREAM_SOURCE, GET_UPSTREAM_SOURCES, PROBE_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'
import { GET_UPSTREAM_OFFERED_ITEMS, offeredPageOf } from '@/utils/upstreamOfferQueries'
import {
    OFFER_PAGE_SIZE, applyOfferPage, emptyOfferSet, filterOfferRows, identifierInput, identifierProblem, isCleanEmpty, offerFailureText,
    offerStatements, tablePageOf,
} from '@/utils/upstreamOffer'
import type { OfferIdentifier, OfferPage, OfferRow, OfferSet } from '@/utils/upstreamOffer'
import {
    followUuidHint, identifierTypeLabel, itemKindLabel, labelOf, offerChooseSource, offerEmptyHint, offerEmptySentence, offerFilterCount,
    offerFilterPlaceholder, offerFilterSentence, offerForbiddenSentence, offerLoading, offerLookupExactHint, offerLookupHead,
    offerLookupNoneSentence, offerModeListLabel, offerModeUuidLabel, offerNeverProbed, offerProbeFailed, offerProbePending,
    offerSourceArchived, offerWaiting,
} from '@/utils/upstreamLabels'
import UpstreamOfferTable from './UpstreamOfferTable.vue'
import UpstreamProbeResult from './UpstreamProbeResult.vue'

type StartMode = 'LIST' | 'UUID'
type Gate = 'NO_SOURCE' | 'READING' | 'ARCHIVED' | 'NEVER_PROBED' | 'PROBE_PENDING' | 'PROBE_FAILED' | 'READY' | 'FORBIDDEN' | 'ERROR'
type View = 'BROWSE' | 'LOOKUP'
type Phase = 'IDLE' | 'LOADING' | 'LOADED' | 'FAILED'

/** A failure of a list call: a page served with a failure, or a refusal (page null). */
interface ShownFailure {
    page: OfferPage | null
    text: string
}

/** One set of loaded rows: the browse list or a lookup, for the source, kind and identifier it was loaded for. */
interface SetState {
    id: number
    source: string
    kind: string
    identifier: OfferIdentifier | null
    phase: Phase
    set: OfferSet
    failure: ShownFailure | null
    later: { loading: boolean, failure: ShownFailure | null, restart: boolean }
    tablePage: number
}

interface ListCall {
    view: View
    setId: number
    epoch: number
    append: boolean
    variables: { source: string, kind: string, identifier: OfferIdentifier | null, pageSize: number, pageToken: string | null }
}

const props = defineProps<{
    show: boolean
    orgUuid: string
    /** The active sources to choose from ({ uuid, name, status }); read by the modal when absent. */
    sources?: any[] | null
    source?: string | null
    kind?: string | null
    upstreamUuid?: string | null
    /** The mode to open in; else By uuid when upstreamUuid is given, else From the list. */
    mode?: StartMode | null
}>()

const emit = defineEmits<{
    (e: 'update:show', value: boolean): void
}>()

const ITEM_KINDS = ['PRODUCT', 'COMPONENT']
const SOURCE_REQUIRED = 'Choose a source.'
const UUID_PROBLEM = 'Enter the uuid of the item on the supplier\'s server, for example 3f2a6c1e-8b7d-4e5f-9a0b-1c2d3e4f5a6b.'

const router = useRouter()
const model = ref({ source: null as string | null, kind: 'PRODUCT', upstreamUuid: '' })
const tried = ref(false)
const sending = ref(false)
const error = ref('')
const readSources = ref<any[]>([])

const paneMode = ref<StartMode>('LIST')
const gate = ref<Gate>('NO_SOURCE')
/** The source the gate was read for; null until it is read in this open. */
const gateSource = ref<string | null>(null)
const sourceRow = ref<any | null>(null)
const gateError = ref('')
const probing = ref(false)
const probeError = ref('')

const view = ref<View>('BROWSE')
let setCounter = 0
const browse = reactive<SetState>(newSet('', 'PRODUCT', null))
const lookup = reactive<SetState>(newSet('', 'PRODUCT', null))
const filterText = ref('')
const lookupType = ref<string | null>(null)
const lookupValue = ref('')
const lookupProblem = ref('')

/** The one list call in flight for the life of the component, and the one first page wanted meanwhile. */
const listInFlight = ref(false)
const wanted = ref<ListCall | null>(null)
let epoch = 0
let sourceReadNumber = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null
let unmounted = false

const activeSources = computed((): any[] => (props.sources ?? readSources.value).filter(s => s.status === 'ACTIVE'))
const sourceOptions = computed(() => activeSources.value.map(s => ({ label: s.name, value: s.uuid })))
const identifierTypeOptions = Object.keys(identifierTypeLabel).map(k => ({ label: identifierTypeLabel[k], value: k }))

/** The uuid as it is sent: trimmed and lower-cased. */
const normalizedUuid = computed((): string => model.value.upstreamUuid.trim().toLowerCase())
const uuidProblem = computed((): string => isUuid(normalizedUuid.value) ? '' : UUID_PROBLEM)
const uuidFeedback = computed((): string => tried.value && uuidProblem.value ? uuidProblem.value : followUuidHint)

const current = computed((): SetState => view.value === 'BROWSE' ? browse : lookup)
const shownRows = computed((): OfferRow[] => view.value === 'BROWSE' ? filterOfferRows(browse.set.rows, filterText.value) : lookup.set.rows)
const statements = computed(() => offerStatements(current.value.set))
const cleanEmpty = computed((): boolean => isCleanEmpty(current.value.set))

function newSet (source: string, kind: string, identifier: OfferIdentifier | null): SetState {
    setCounter += 1
    return {
        id: setCounter, source, kind, identifier, phase: 'IDLE', set: emptyOfferSet(), failure: null,
        later: { loading: false, failure: null, restart: false }, tablePage: 1,
    }
}

function replaceSet (target: SetState, source: string, kind: string, identifier: OfferIdentifier | null) {
    Object.assign(target, newSet(source, kind, identifier))
}

function wantedFor (v: View): boolean {
    return !!wanted.value && wanted.value.view === v && wanted.value.setId === (v === 'BROWSE' ? browse.id : lookup.id)
}

// ---- Opening, closing and the choices at the top ----

/** Drops both sets, the filter and the lookup fields, returns to the browse view, and forgets a wanted call. */
function dropSets () {
    epoch += 1
    wanted.value = null
    view.value = 'BROWSE'
    replaceSet(browse, model.value.source ?? '', model.value.kind, null)
    replaceSet(lookup, model.value.source ?? '', model.value.kind, null)
    filterText.value = ''
    lookupType.value = null
    lookupValue.value = ''
    lookupProblem.value = ''
}

function resetGate () {
    stopPoll()
    sourceReadNumber += 1
    gate.value = 'NO_SOURCE'
    gateSource.value = null
    sourceRow.value = null
    gateError.value = ''
    probeError.value = ''
}

function reset () {
    model.value = {
        source: props.source ?? null,
        kind: props.kind && ITEM_KINDS.includes(props.kind) ? props.kind : 'PRODUCT',
        upstreamUuid: props.upstreamUuid ?? '',
    }
    paneMode.value = props.mode === 'LIST' || props.mode === 'UUID' ? props.mode : (props.upstreamUuid ? 'UUID' : 'LIST')
    tried.value = false
    error.value = ''
    resetGate()
    dropSets()
}

async function readActiveSources () {
    if (props.sources || !props.orgUuid) return
    try {
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_SOURCES, variables: { org: props.orgUuid, statuses: ['ACTIVE'] }, fetchPolicy: 'no-cache',
        })
        readSources.value = resp?.data?.upstreamSources ?? []
    } catch (err) {
        error.value = refusalOf(err).text
    }
}

watch(() => props.show, (open) => {
    if (!open) {
        // Closing sends nothing and cancels a wanted call; an answer still in flight is dropped.
        epoch += 1
        wanted.value = null
        resetGate()
        return
    }
    reset()
    readActiveSources()
    enterList()
}, { immediate: true })

onBeforeUnmount(() => {
    unmounted = true
    epoch += 1
    wanted.value = null
    resetGate()
})

function onSource (value: string | null) {
    if (value === model.value.source) return
    model.value.source = value
    resetGate()
    dropSets()
    enterList()
}

function onKind (value: string) {
    if (value === model.value.kind || !ITEM_KINDS.includes(value)) return
    model.value.kind = value
    dropSets()
    if (paneMode.value === 'LIST' && gate.value === 'READY') wantFirstPage()
}

function onMode (value: StartMode) {
    if (value !== 'LIST' && value !== 'UUID') return
    paneMode.value = value
    error.value = ''
    if (value === 'UUID') {
        stopPoll()
        return
    }
    enterList()
}

/** In mode LIST: read the chosen source unless it was read, or request the first page of a ready gate. */
function enterList () {
    if (paneMode.value !== 'LIST' || !props.show) return
    const source = model.value.source
    if (!source) {
        gate.value = 'NO_SOURCE'
        return
    }
    // A read that was dropped while By uuid was open, and a pending probe whose poll stopped, are read again.
    if (gateSource.value !== source || gate.value === 'READING' || gate.value === 'PROBE_PENDING') {
        readSource(source, false)
        return
    }
    if (gate.value === 'READY' && browse.phase === 'IDLE' && view.value === 'BROWSE') wantFirstPage()
}

function close () {
    if (sending.value) return
    emit('update:show', false)
}

function onUpdateShow (value: boolean) {
    if (!value) close()
}

// ---- The gate: a fresh read of the source before any list call ----

function stopPoll () {
    if (pollTimer !== null) clearTimeout(pollTimer)
    pollTimer = null
}

function stillWanted (uuid: string, readNumber: number): boolean {
    return !unmounted && props.show && readNumber === sourceReadNumber && model.value.source === uuid && paneMode.value === 'LIST'
}

async function readSource (uuid: string, poll: boolean) {
    stopPoll()
    sourceReadNumber += 1
    const readNumber = sourceReadNumber
    gateSource.value = uuid
    if (!poll) {
        gate.value = 'READING'
        probeError.value = ''
    }
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_SOURCE, variables: { uuid }, fetchPolicy: 'no-cache' })
        if (!stillWanted(uuid, readNumber)) return
        applySource(resp?.data?.upstreamSource ?? null)
    } catch (err) {
        if (!stillWanted(uuid, readNumber)) return
        const refusal = refusalOf(err)
        gate.value = isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
        gateError.value = refusal.text
    }
}

function applySource (row: any | null) {
    sourceRow.value = row
    if (!row) {
        gate.value = 'FORBIDDEN'
        return
    }
    const uuid = row.uuid
    if (row.status !== 'ACTIVE') gate.value = 'ARCHIVED'
    else if (!row.probe) gate.value = 'NEVER_PROBED'
    else if (row.probe.state === 'PENDING') gate.value = 'PROBE_PENDING'
    else if (row.probe.state !== 'DONE' || row.probe.result !== 'REACHABLE') gate.value = 'PROBE_FAILED'
    else gate.value = 'READY'
    if (gate.value === 'PROBE_PENDING') {
        pollTimer = setTimeout(() => {
            pollTimer = null
            readSource(uuid, true)
        }, PROBE_POLL_MS)
        return
    }
    if (gate.value === 'READY' && browse.phase === 'IDLE' && view.value === 'BROWSE') wantFirstPage()
}

async function probe () {
    const uuid = model.value.source
    if (!uuid || probing.value) return
    probing.value = true
    probeError.value = ''
    const readNumber = sourceReadNumber
    try {
        const resp: any = await graphqlClient.mutate({ mutation: PROBE_UPSTREAM_SOURCE, variables: { uuid }, fetchPolicy: 'no-cache' })
        if (!stillWanted(uuid, readNumber)) return
        applySource(resp?.data?.probeUpstreamSource ?? null)
    } catch (err) {
        if (!stillWanted(uuid, readNumber)) return
        probeError.value = refusalOf(err).text
    } finally {
        probing.value = false
    }
}

// ---- List calls ----

function callFor (target: SetState, v: View, append: boolean): ListCall {
    return {
        view: v,
        setId: target.id,
        epoch,
        append,
        variables: {
            source: target.source, kind: target.kind, identifier: target.identifier, pageSize: OFFER_PAGE_SIZE,
            pageToken: append ? target.set.token : null,
        },
    }
}

/** The first page of the browse set, sent now or, while a call is in flight, wanted (the latest wins). */
function wantFirstPage () {
    const source = model.value.source
    if (!source) return
    replaceSet(browse, source, model.value.kind, null)
    const call = callFor(browse, 'BROWSE', false)
    if (listInFlight.value) {
        wanted.value = call
        return
    }
    send(call)
}

function setOf (call: ListCall): SetState | null {
    const target = call.view === 'BROWSE' ? browse : lookup
    return call.epoch === epoch && target.id === call.setId ? target : null
}

async function send (call: ListCall) {
    if (listInFlight.value) return
    const target = setOf(call)
    if (!target) return
    listInFlight.value = true
    if (call.append) {
        target.later = { loading: true, failure: null, restart: false }
    } else {
        target.phase = 'LOADING'
        target.failure = null
    }
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_OFFERED_ITEMS, variables: call.variables, fetchPolicy: 'no-cache' })
        const still = setOf(call)
        if (still) applyPage(still, call, offeredPageOf(resp?.data))
    } catch (err) {
        const still = setOf(call)
        if (still) applyRefusal(still, call, err)
    } finally {
        listInFlight.value = false
        const next = wanted.value
        wanted.value = null
        if (next && !unmounted && props.show) send(next)
    }
}

function applyPage (target: SetState, call: ListCall, page: OfferPage | null) {
    if (!page) {
        applyFailure(target, call, { page: null, text: offerSourceFailedText() }, false)
        return
    }
    if (page.failure) {
        applyFailure(target, call, { page, text: offerFailureText(page) }, page.failure === 'TOKEN_NOT_ACCEPTED')
        return
    }
    const before = call.append ? target.set.rows.length : 0
    target.set = applyOfferPage(call.append ? target.set : emptyOfferSet(), page)
    target.phase = 'LOADED'
    target.failure = null
    target.later = { loading: false, failure: null, restart: false }
    if (!call.append) target.tablePage = 1
    else if (target.set.rows.length > before && !(call.view === 'BROWSE' && filterText.value.trim())) target.tablePage = tablePageOf(before)
}

function applyRefusal (target: SetState, call: ListCall, err: unknown) {
    const refusal = refusalOf(err)
    const text = isForbidden(refusal) ? offerForbiddenSentence : refusal.text
    applyFailure(target, call, { page: null, text }, refusal.reason === 'INVALID_PAGE_TOKEN')
}

/** A failed first page fails the set; a failed later page keeps the rows, and drops the token when the token was refused. */
function applyFailure (target: SetState, call: ListCall, failure: ShownFailure, tokenRefused: boolean) {
    if (!call.append) {
        target.phase = 'FAILED'
        target.failure = failure
        return
    }
    if (tokenRefused) target.set = { ...target.set, token: null }
    target.later = { loading: false, failure, restart: tokenRefused }
}

function offerSourceFailedText (): string {
    return offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'FAILED', message: null })
}

function failureProbe (page: OfferPage | null) {
    return { state: 'DONE', result: page?.sourceFailure ?? null, message: page ? offerFailureText(page) : null }
}

function loadMore () {
    const target = current.value
    if (listInFlight.value || target.phase !== 'LOADED' || target.later.restart || !offerStatements(target.set).canLoadMore) return
    send(callFor(target, view.value, true))
}

function retry () {
    const target = current.value
    if (listInFlight.value || target.phase !== 'FAILED') return
    send(callFor(target, view.value, false))
}

function restart () {
    const target = current.value
    if (listInFlight.value) return
    replaceSet(target, target.source, target.kind, target.identifier)
    send(callFor(target, view.value, false))
}

// ---- The filter and the lookup ----

function onFilter (value: string | null) {
    filterText.value = value ?? ''
    browse.tablePage = 1
}

function lookUp () {
    if (listInFlight.value || gate.value !== 'READY' || !model.value.source) return
    lookupProblem.value = identifierProblem(lookupType.value, lookupValue.value)
    const identifier = identifierInput(lookupType.value, lookupValue.value)
    if (lookupProblem.value || !identifier) return
    replaceSet(lookup, model.value.source, model.value.kind, identifier)
    view.value = 'LOOKUP'
    send(callFor(lookup, 'LOOKUP', false))
}

function backToList () {
    view.value = 'BROWSE'
}

// ---- Preparing ----

async function prepare (source: string, kind: string, upstreamUuid: string) {
    sending.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: PREPARE_UPSTREAM_FOLLOW,
            variables: { source, kind, upstreamUuid },
            fetchPolicy: 'no-cache',
        })
        const uuid = resp?.data?.prepareUpstreamFollow?.uuid
        if (!isUuid(uuid)) return
        sending.value = false
        emit('update:show', false)
        router.push({ name: 'UpstreamFollowPreparation', params: { orguuid: props.orgUuid, uuid } })
    } catch (err) {
        error.value = refusalOf(err).text
    } finally {
        sending.value = false
    }
}

async function submit () {
    tried.value = true
    error.value = ''
    if (!model.value.source || uuidProblem.value || sending.value) return
    await prepare(model.value.source, model.value.kind, normalizedUuid.value)
}

async function prepareRow (row: OfferRow) {
    const target = current.value
    if (sending.value || !isUuid(row?.upstreamUuid) || !target.source) return
    error.value = ''
    await prepare(target.source, target.kind, row.upstreamUuid)
}
</script>

<style scoped>
.upstream-start-error {
    margin-bottom: 12px;
}
.upstream-start-list-pane {
    margin-bottom: 12px;
}
.upstream-start-gate > * {
    margin-bottom: 6px;
}
.upstream-start-gate-error {
    color: #d03050;
}
.upstream-start-lookup,
.upstream-start-lookup-head,
.upstream-start-filter {
    margin-bottom: 10px;
}
.upstream-start-lookup-label {
    font-weight: 500;
    margin-bottom: 4px;
}
.upstream-start-lookup-head > * {
    margin-right: 8px;
}
.upstream-start-filter > * {
    margin-right: 8px;
    vertical-align: middle;
}
.upstream-start-problem {
    color: #d03050;
    margin-top: 4px;
}
.upstream-start-note {
    font-size: 0.9em;
    color: #666;
}
.upstream-start-statement {
    margin-top: 8px;
}
.upstream-start-statement > * {
    margin-bottom: 4px;
}
.upstream-start-more {
    margin-left: 8px;
}
.upstream-start-alert {
    margin-bottom: 6px;
}
.upstream-start-loading {
    margin: 8px 0;
}
</style>
