<template>
    <n-modal :show="show" preset="card" title="Reconfigure the follow" style="width: 900px; max-width: 95vw;" :mask-closable="!saving" @update:show="onUpdateShow">
        <div data-testid="upstream-reconfigure">
            <n-table size="small" :single-line="false">
                <thead><tr><th>Item</th><th>Held releases</th><th>Current depth</th><th>Accept</th><th>Depth</th><th></th></tr></thead>
                <tbody>
                    <tr v-for="(r, i) in rows" :key="r.item" :data-testid="`upstream-reconfigure-row-${i}`">
                        <td>
                            <span :data-testid="`upstream-reconfigure-name-${i}`">{{ r.name }}</span>
                            <span class="upstream-small upstream-gap">{{ labelOf(itemKindLabel, r.kind) }}</span>
                        </td>
                        <td :data-testid="`upstream-reconfigure-held-${i}`">{{ r.held }}</td>
                        <td>{{ r.currentDepth }}</td>
                        <td>
                            <n-checkbox v-model:checked="r.accept" :data-testid="`upstream-reconfigure-accept-${i}`">Accept its held releases</n-checkbox>
                        </td>
                        <td>
                            <n-select v-model:value="r.depth" size="small" :options="depthOptions" style="width: 90px;" :data-testid="`upstream-reconfigure-depth-${i}`" />
                        </td>
                        <td>
                            <n-button v-if="r.held > 0" size="tiny" :disabled="proposalBusy(r.item)" :data-testid="`upstream-reconfigure-propose-${i}`" @click="propose(r)">
                                Propose a depth from the history
                            </n-button>
                        </td>
                    </tr>
                </tbody>
            </n-table>
            <div v-if="pendingTruncated" class="upstream-small" data-testid="upstream-reconfigure-truncated">
                Held releases are counted among the oldest 50 that are listed.
            </div>

            <div v-for="r in rowsWithProposal" :key="`proposal-${r.item}`" class="upstream-proposal" :data-testid="`upstream-proposal-${r.item}`">
                <h6>Proposal for {{ r.name }}</h6>
                <div class="upstream-small">{{ proposalCaption }}</div>
                <div v-if="proposals[r.item].state === 'LOADING'" data-testid="upstream-proposal-loading">
                    <n-spin size="small" /> Reading the item and its release history from the source.
                </div>
                <n-alert v-else-if="proposals[r.item].state === 'ERROR'" type="error" data-testid="upstream-proposal-error">{{ proposals[r.item].text }}</n-alert>
                <div v-else-if="proposals[r.item].state === 'FAILED'" data-testid="upstream-proposal-failed">
                    <n-alert type="error">{{ proposals[r.item].text }}</n-alert>
                    <upstream-probe-result v-if="proposals[r.item].sourceFailure"
                        :probe="{ state: 'DONE', result: proposals[r.item].sourceFailure, message: labelOf(probeResultSentence, proposals[r.item].sourceFailure) }" />
                </div>
                <upstream-depth-assessment v-else :proposal="proposals[r.item].proposal" read-only @use="(d: number) => useDepth(r.item, d)" />
            </div>

            <h6>When a later release does not fit</h6>
            <n-radio-group v-model:value="onDrift" data-testid="upstream-reconfigure-drift">
                <div class="upstream-choices">
                    <n-radio v-for="b in DRIFT_BEHAVIOURS" :key="b" :value="b" :data-testid="`upstream-reconfigure-drift-${b}`">
                        {{ labelOf(driftBehaviourSentence, b) }}
                    </n-radio>
                </div>
            </n-radio-group>

            <div class="upstream-sentences">
                <p>{{ reconfigureAcceptSentence }}</p>
                <p>{{ reconfigureRunSentence }}</p>
                <p>{{ reconfigureDriftOnlySentence }}</p>
            </div>

            <n-alert v-if="error" type="error" class="upstream-reconfigure-error" data-testid="upstream-reconfigure-error">{{ error }}</n-alert>
            <div class="upstream-dialog-buttons">
                <span v-if="!changes" class="upstream-small" data-testid="upstream-reconfigure-nothing">{{ nothingToChangeSentence }}</span>
                <n-button :disabled="saving" data-testid="upstream-reconfigure-cancel" @click="close">Cancel</n-button>
                <n-button type="primary" :loading="saving" :disabled="saving || !changes" data-testid="upstream-reconfigure-save" @click="save">Save</n-button>
            </div>
        </div>
    </n-modal>
</template>

<script lang="ts" setup>
// The reconfigure dialog of a follow (ReARM Pro, TPF-185 design 3.9). Checking an item accepts its
// held releases with the depth of its select; the behaviour on drift can change on its own. The
// input is built by upstreamReconfigure.ts. "Propose a depth from the history" prepares the item
// again and shows what ReARM would propose for its history as the source serves it now: it reads
// the source, counts against the organization's preparations, is never confirmed and expires by
// itself. Nothing in this dialog follows anything.
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { NAlert, NButton, NCheckbox, NModal, NRadio, NRadioGroup, NSelect, NSpin, NTable } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { refusalOf } from '@/utils/upstream'
import { GET_UPSTREAM_FOLLOW_PREPARATION, PREPARE_UPSTREAM_FOLLOW, RECONFIGURE_UPSTREAM_FOLLOW } from '@/utils/upstreamFollowQueries'
import { driftBehaviourSentence, itemKindLabel, labelOf, probeResultSentence } from '@/utils/upstreamLabels'
import {
    RECONFIGURE_DEPTHS, buildReconfigureInput, initialReconfigureRows, nothingToChangeSentence, proposalCaption, reconfigureAcceptSentence,
    reconfigureChangesSomething, reconfigureDriftOnlySentence, reconfigureRunSentence, type ReconfigureRow,
} from '@/utils/upstreamReconfigure'
import UpstreamDepthAssessment from './UpstreamDepthAssessment.vue'
import UpstreamProbeResult from './UpstreamProbeResult.vue'

const PREPARATION_POLL_MS = 2000
const DRIFT_BEHAVIOURS = ['STOP', 'ADAPT']

const props = defineProps<{
    show: boolean
    /** The follow as the follow page read it. */
    follow: any
}>()

const emit = defineEmits<{
    (e: 'update:show', value: boolean): void
    (e: 'saved', uuid: string): void
}>()

type ProposalState = 'LOADING' | 'READY' | 'FAILED' | 'ERROR'
interface Proposal { state: ProposalState, proposal: any, text: string, sourceFailure: string | null, token: number }

const rows = ref<ReconfigureRow[]>([])
const onDrift = ref<string | null>(null)
const saving = ref(false)
const error = ref('')
const proposals = reactive<Record<string, Proposal>>({})

const depthOptions = RECONFIGURE_DEPTHS.map(d => ({ label: String(d), value: d }))

const pendingTruncated = computed((): boolean => (props.follow?.pendingCount ?? 0) > (props.follow?.pending ?? []).length)
const input = computed(() => buildReconfigureInput(rows.value, onDrift.value, props.follow))
const changes = computed((): boolean => reconfigureChangesSomething(input.value))
const rowsWithProposal = computed((): ReconfigureRow[] => rows.value.filter(r => !!proposals[r.item]))

// ---- The proposal: a fresh preparation of the item, read until it is no longer PENDING ----

let token = 0
const timers = new Set<ReturnType<typeof setTimeout>>()

function clearProposals () {
    token++
    for (const t of timers) clearTimeout(t)
    timers.clear()
    for (const key of Object.keys(proposals)) delete proposals[key]
}

function proposalBusy (item: string): boolean {
    return proposals[item]?.state === 'LOADING'
}

function settle (item: string, mine: number, over: Partial<Proposal>) {
    if (proposals[item]?.token !== mine) return
    proposals[item] = { ...proposals[item], ...over }
}

async function readPreparation (item: string, mine: number, preparation: string) {
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_FOLLOW_PREPARATION, variables: { uuid: preparation }, fetchPolicy: 'no-cache' })
        if (proposals[item]?.token !== mine) return
        const p = resp?.data?.upstreamFollowPreparation
        if (p?.state === 'PENDING') {
            const t = setTimeout(() => { timers.delete(t); readPreparation(item, mine, preparation) }, PREPARATION_POLL_MS)
            timers.add(t)
        } else if (p?.state === 'FAILED') {
            settle(item, mine, { state: 'FAILED', text: p.message ?? '', sourceFailure: p.sourceFailure ?? null })
        } else if (p?.item) {
            settle(item, mine, { state: 'READY', proposal: p.item })
        } else {
            settle(item, mine, { state: 'ERROR', text: refusalOf(null).text })
        }
    } catch (err) {
        settle(item, mine, { state: 'ERROR', text: refusalOf(err).text })
    }
}

async function propose (r: ReconfigureRow) {
    if (proposalBusy(r.item)) return
    const mine = ++token
    proposals[r.item] = { state: 'LOADING', proposal: null, text: '', sourceFailure: null, token: mine }
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: PREPARE_UPSTREAM_FOLLOW,
            variables: { source: props.follow?.source, kind: r.kind, upstreamUuid: r.upstreamUuid },
            fetchPolicy: 'no-cache',
        })
        if (proposals[r.item]?.token !== mine) return
        const preparation = resp?.data?.prepareUpstreamFollow?.uuid
        if (typeof preparation !== 'string') {
            settle(r.item, mine, { state: 'ERROR', text: refusalOf(null).text })
            return
        }
        await readPreparation(r.item, mine, preparation)
    } catch (err) {
        settle(r.item, mine, { state: 'ERROR', text: refusalOf(err).text })
    }
}

function useDepth (item: string, depth: number) {
    const row = rows.value.find(r => r.item === item)
    if (row) row.depth = depth
}

// ---- Opening, saving and closing ----

function reset () {
    clearProposals()
    rows.value = initialReconfigureRows(props.follow)
    onDrift.value = props.follow?.onDrift ?? null
    error.value = ''
    saving.value = false
}

watch(() => props.show, (open) => {
    if (open) reset()
    else clearProposals()
}, { immediate: true })

function close () {
    clearProposals()
    emit('update:show', false)
}

function onUpdateShow (value: boolean) {
    if (!value && !saving.value) close()
}

async function save () {
    if (saving.value || !changes.value) return
    saving.value = true
    error.value = ''
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: RECONFIGURE_UPSTREAM_FOLLOW,
            variables: { follow: props.follow?.uuid, input: input.value },
            fetchPolicy: 'no-cache',
        })
        saving.value = false
        emit('saved', resp?.data?.reconfigureUpstreamFollow?.uuid ?? props.follow?.uuid)
        close()
    } catch (err) {
        saving.value = false
        error.value = refusalOf(err).text
    }
}

onBeforeUnmount(clearProposals)
</script>

<style scoped>
.upstream-small {
    font-size: 0.85em;
    color: #777;
}
.upstream-gap {
    margin-left: 6px;
}
.upstream-proposal {
    margin-top: 12px;
    padding: 8px;
    border: 1px solid #eee;
}
.upstream-choices {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
.upstream-sentences {
    margin-top: 12px;
}
.upstream-reconfigure-error {
    margin-bottom: 8px;
}
.upstream-dialog-buttons {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: 8px;
}
</style>
