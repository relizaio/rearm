<template>
    <div class="upstream-preparation-page" data-testid="upstream-preparation-page">
        <div v-if="!available" data-testid="upstream-not-available">{{ notAvailableSentence }}</div>
        <template v-else>
            <h4>Follow an upstream item</h4>
            <n-spin v-if="pageState === 'LOADING'" :show="true"><div class="upstream-loading" /></n-spin>
            <div v-else-if="pageState === 'FORBIDDEN'" data-testid="upstream-preparation-forbidden">{{ forbiddenSentence }}</div>
            <div v-else-if="pageState === 'ERROR'" class="upstream-error" data-testid="upstream-preparation-error">{{ loadError }}</div>

            <div v-else-if="preparation.state === 'PENDING'" data-testid="upstream-preparation-pending">
                <n-spin :size="16" class="upstream-inline-spin" />
                {{ PENDING_TEXT }}
            </div>

            <div v-else-if="preparation.state === 'FAILED'" data-testid="upstream-preparation-failed">
                <n-alert type="error" data-testid="upstream-preparation-failure">{{ preparation.message || labelOf(preparationFailureSentence, preparation.failure) }}</n-alert>
                <upstream-probe-result v-if="preparation.sourceFailure" :probe="sourceFailureProbe" />
                <router-link :to="followsTab" data-testid="upstream-back-to-follows">Back to the follows</router-link>
            </div>

            <div v-else-if="preparation.state === 'CONSUMED'" data-testid="upstream-preparation-consumed">
                <p>{{ CONSUMED_TEXT }}</p>
                <router-link :to="followsTabOfSource" data-testid="upstream-follows-of-source">The follows of this source</router-link>
            </div>

            <div v-else-if="preparation.state === 'READY' && expired" data-testid="upstream-preparation-expired">
                <p>{{ EXPIRED_TEXT }}</p>
                <n-alert v-if="prepareAgainError" type="error" data-testid="upstream-prepare-again-error">{{ prepareAgainError }}</n-alert>
                <n-button type="primary" :loading="preparingAgain" data-testid="upstream-prepare-again" @click="prepareAgain">Prepare again</n-button>
            </div>

            <div v-else-if="preparation.state === 'READY'" class="upstream-confirm-form" data-testid="upstream-confirm-form">
                <!-- 1. What will be followed -->
                <section data-testid="upstream-confirm-what">
                    <h5>What will be followed</h5>
                    <div>Source: <span data-testid="upstream-confirm-source">{{ sourceName }}</span></div>
                    <div>{{ labelOf(itemKindLabel, item.kind) }}: <span data-testid="upstream-confirm-item-name">{{ item.upstreamName }}</span></div>
                    <div class="upstream-small">uuid on the source: {{ item.upstreamUuid }}</div>
                    <div v-if="item.existingItem" data-testid="upstream-confirm-existing">
                        This item is already in ReARM:
                        <router-link v-if="existingLink(item)" :to="existingLink(item)" data-testid="upstream-existing-link">open it</router-link>.
                        Its depth is {{ item.existingItem.depth }} and cannot be chosen again.
                    </div>
                    <n-radio-group v-else v-model:value="model.target" data-testid="upstream-confirm-target">
                        <n-space vertical>
                            <n-radio :value="ItemTarget.CREATE" data-testid="upstream-target-create">Create it in ReARM</n-radio>
                            <div v-if="model.target === ItemTarget.CREATE" class="upstream-indent">
                                <n-input v-model:value="model.name" type="text" :maxlength="NAME_MAX + 1" placeholder="Name in ReARM" data-testid="upstream-confirm-name" />
                                <div v-if="item.nameCollides" class="upstream-small" data-testid="upstream-name-collides">{{ NAME_COLLIDES }}</div>
                            </div>
                            <template v-if="item.adoptionCandidates?.length">
                                <n-radio :value="ItemTarget.ADOPT" data-testid="upstream-target-adopt">Adopt an existing item</n-radio>
                                <div v-if="model.target === ItemTarget.ADOPT" class="upstream-indent">
                                    <n-select v-model:value="model.adopt" :options="candidateOptions(item)" placeholder="Choose the item to adopt" data-testid="upstream-confirm-adopt" />
                                    <div class="upstream-small">{{ adoptionSentence }}</div>
                                </div>
                            </template>
                        </n-space>
                    </n-radio-group>
                </section>

                <!-- 2. The release history -->
                <section data-testid="upstream-confirm-history">
                    <h5>The release history</h5>
                    <div>
                        {{ item.releases }} releases in all: {{ item.finalReleases }} final, {{ item.preReleases }} pre-releases,
                        {{ item.releasesWithUnusableVersion }} without a usable version. Pre-releases are not ingested.
                    </div>
                    <div v-if="item.preReleaseRecognition === 'NOT_STATED' || item.preReleaseRecognition === 'MIXED'" data-testid="upstream-pre-release-not-stated">
                        {{ preReleaseNotStated }}
                    </div>
                </section>

                <!-- 3. Version lines -->
                <section data-testid="upstream-confirm-depth">
                    <h5>Version lines</h5>
                    <div v-if="item.existingItem">Depth {{ item.existingItem.depth }}, as it stands.</div>
                    <upstream-depth-assessment v-else v-model="model.depth" :proposal="item" />
                </section>

                <!-- 4. Components -->
                <section v-if="item.kind === 'PRODUCT'" data-testid="upstream-confirm-components">
                    <h5>Components</h5>
                    <div v-if="!components.length">No component is referenced by the newest releases.</div>
                    <n-table v-else size="small" :single-line="false">
                        <thead>
                            <tr><th>Component</th><th>Proposed depth</th><th>Depth</th><th>In ReARM</th></tr>
                        </thead>
                        <tbody>
                            <tr v-for="(c, i) in components" :key="c.upstreamUuid" data-testid="upstream-component-row">
                                <td>
                                    <div data-testid="upstream-component-name">{{ c.upstreamName }}</div>
                                    <div class="upstream-small">{{ c.upstreamUuid }}</div>
                                </td>
                                <template v-if="c.failure">
                                    <td colspan="3" data-testid="upstream-component-failure">{{ labelOf(preparationFailureSentence, c.failure) }}</td>
                                </template>
                                <template v-else-if="c.existingItem">
                                    <td colspan="2">Depth {{ c.existingItem.depth }}, as it stands.</td>
                                    <td>
                                        Already in ReARM.
                                        <router-link v-if="existingLink(c)" :to="existingLink(c)">open it</router-link>
                                    </td>
                                </template>
                                <template v-else>
                                    <td>{{ c.proposedDepth }}: {{ labelOf(depthBasisLabel, c.depthBasis) }}</td>
                                    <td>
                                        <n-select v-model:value="model.components[i].depth" :options="depthOptions(c)" size="small" style="width: 90px"
                                            :data-testid="`upstream-component-depth-${i}`" />
                                    </td>
                                    <td>
                                        <n-radio-group v-if="c.adoptionCandidates?.length" v-model:value="model.components[i].target" size="small">
                                            <n-radio :value="ItemTarget.CREATE">Create</n-radio>
                                            <n-radio :value="ItemTarget.ADOPT" :data-testid="`upstream-component-adopt-${i}`">Adopt</n-radio>
                                        </n-radio-group>
                                        <span v-else>Created</span>
                                        <n-select v-if="model.components[i].target === ItemTarget.ADOPT" v-model:value="model.components[i].adopt"
                                            :options="candidateOptions(c)" size="small" placeholder="Choose the item to adopt"
                                            :data-testid="`upstream-component-candidate-${i}`" />
                                    </td>
                                </template>
                            </tr>
                        </tbody>
                    </n-table>
                    <div v-if="preparation.componentsTruncated" class="upstream-small" data-testid="upstream-components-truncated">{{ COMPONENTS_TRUNCATED }}</div>
                    <div class="upstream-small">{{ COMPONENT_RELEASES }}</div>
                </section>

                <!-- 5. Starting point -->
                <section data-testid="upstream-confirm-starting-point">
                    <h5>Starting point</h5>
                    <n-radio-group :value="model.backfill.kind" data-testid="upstream-backfill-kind" @update:value="onBackfillKind">
                        <n-space vertical>
                            <n-radio :value="BackfillKind.LATEST_PER_LINE" data-testid="upstream-backfill-LATEST_PER_LINE">The newest N releases of each version line</n-radio>
                            <div v-if="model.backfill.kind === BackfillKind.LATEST_PER_LINE" class="upstream-indent">
                                <n-input-number v-model:value="model.backfill.count" :placeholder="`N, ${COUNT_MIN} to ${COUNT_MAX}`"
                                    style="width: 160px" data-testid="upstream-backfill-count" />
                            </div>
                            <n-radio :value="BackfillKind.FROM_DATE" data-testid="upstream-backfill-FROM_DATE">Every release dated on or after</n-radio>
                            <div v-if="model.backfill.kind === BackfillKind.FROM_DATE" class="upstream-indent">
                                <n-date-picker v-model:formatted-value="model.backfill.day" value-format="yyyy-MM-dd" type="date" data-testid="upstream-backfill-day" />
                                <div class="upstream-small">The day starts at 00:00 UTC.</div>
                            </div>
                            <n-radio :value="BackfillKind.FROM_RELEASE" data-testid="upstream-backfill-FROM_RELEASE">From this release on</n-radio>
                            <div v-if="model.backfill.kind === BackfillKind.FROM_RELEASE" class="upstream-indent" data-testid="upstream-release-picker">
                                <div v-if="releasesError" class="upstream-error">{{ releasesError }}</div>
                                <n-radio-group v-model:value="model.backfill.release">
                                    <n-space vertical :size="2">
                                        <n-radio v-for="r in releases" :key="r.upstreamUuid" :value="r.upstreamUuid" :disabled="!isPickable(r)"
                                            data-testid="upstream-release-option">
                                            <template v-if="r.usable">{{ r.version }}</template>
                                            <template v-else>{{ r.upstreamUuid }}, no usable version</template>
                                            <span v-if="r.date" class="upstream-small"> {{ dateOf(r.date) }}</span>
                                            <span v-if="r.preRelease === 'YES'" class="upstream-small"> pre-release</span>
                                        </n-radio>
                                    </n-space>
                                </n-radio-group>
                                <div class="upstream-small">{{ releases.length }} of {{ releasesTotal }} releases listed.</div>
                                <n-button v-if="releasesCursor" size="small" :loading="releasesLoading" data-testid="upstream-releases-more" @click="loadReleases(releasesCursor)">
                                    Load more
                                </n-button>
                            </div>
                        </n-space>
                    </n-radio-group>
                </section>

                <!-- 6. What this takes in -->
                <section data-testid="upstream-confirm-scope">
                    <h5>What this takes in</h5>
                    <div v-if="!currentScope">Choose a depth and a starting point to see what is taken in.</div>
                    <n-spin v-else-if="scopeStale" :size="14"><div>{{ PREVIEW_PENDING }}</div></n-spin>
                    <div v-else-if="scope.error" class="upstream-error" data-testid="upstream-scope-error">{{ scope.error }}</div>
                    <div v-else-if="scope.answer" data-testid="upstream-scope-answer">
                        <div>
                            {{ scope.answer.releases }} releases on these lines, {{ scope.answer.onBaseBranch }} of them on the base branch,
                            {{ scope.answer.skippedPreReleases }} pre-releases skipped.
                        </div>
                        <upstream-line-table :lines="scope.answer.lines ?? []" :truncated="scope.answer.linesTruncated" />
                        <n-alert v-if="!scope.answer.withinLimit" type="error" data-testid="upstream-scope-over-limit">
                            {{ overLimitSentence(scope.answer.releases, preparation.maxBackfillReleases) }}
                        </n-alert>
                    </div>
                </section>

                <!-- 7. When a later release does not fit -->
                <section data-testid="upstream-confirm-drift">
                    <h5>When a later release does not fit</h5>
                    <n-radio-group v-model:value="model.onDrift" data-testid="upstream-on-drift">
                        <n-space vertical>
                            <n-radio v-for="d in DRIFT_CHOICES" :key="d" :value="d" :data-testid="`upstream-on-drift-${d}`">{{ driftBehaviourSentence[d] }}</n-radio>
                        </n-space>
                    </n-radio-group>
                </section>

                <!-- 8. Confirm -->
                <section data-testid="upstream-confirm-section">
                    <n-alert v-if="confirmRefusal" type="error" data-testid="upstream-confirm-refusal">
                        <div>{{ confirmRefusal.text }}</div>
                        <n-button v-if="offersPrepareAgain" size="small" :loading="preparingAgain" data-testid="upstream-prepare-again" @click="prepareAgain">Prepare again</n-button>
                        <router-link v-if="confirmRefusal.reason === 'ALREADY_FOLLOWED'" :to="followsTabOfSource" data-testid="upstream-follows-of-source">The follows of this source</router-link>
                    </n-alert>
                    <n-alert v-if="prepareAgainError" type="error" data-testid="upstream-prepare-again-error">{{ prepareAgainError }}</n-alert>
                    <n-space align="center">
                        <n-button type="primary" :disabled="blockers.length > 0 || confirming" :loading="confirming" data-testid="upstream-confirm-follow" @click="follow">
                            Follow
                        </n-button>
                        <ul v-if="blockers.length" class="upstream-blockers" data-testid="upstream-confirm-blockers">
                            <li v-for="b in blockers" :key="b">{{ b }}</li>
                        </ul>
                    </n-space>
                </section>
            </div>
        </template>
    </div>
</template>

<script lang="ts" setup>
// The confirmation page (ReARM Pro, TPF-185 design 3.8): what a follow would take in, read from a
// preparation, and the one place that confirms a follow. The form exists only for a preparation
// that is READY and not expired; no starting point is preselected; the scope preview and the
// confirm are built from the same values (upstreamFollowConfirm.ts), and the confirm is sent from
// one function that returns at once while anything blocks it or a confirm is in flight.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { NAlert, NButton, NDatePicker, NInput, NInputNumber, NRadio, NRadioGroup, NSelect, NSpace, NSpin, NTable } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isForbidden, isUuid, refusalOf, upstreamSourcesAvailable } from '@/utils/upstream'
import type { UpstreamRefusal } from '@/utils/upstream'
import {
    CONFIRM_UPSTREAM_FOLLOW, GET_UPSTREAM_FOLLOW_PREPARATION, GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES, GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE,
    PREPARE_UPSTREAM_FOLLOW, confirmedFollowUuid,
} from '@/utils/upstreamFollowQueries'
import { GET_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'
import {
    BackfillKind, COUNT_MAX, COUNT_MIN, DriftBehaviour, ItemTarget, NAME_MAX, PREVIEW_PENDING, buildConfirmInput, confirmBlockers,
    currentScopeKey, initialConfirmModel, isExpired, overLimitSentence, scopeKey, scopeVariables,
} from '@/utils/upstreamFollowConfirm'
import type { ConfirmModel, ScopeState } from '@/utils/upstreamFollowConfirm'
import {
    adoptionSentence, depthBasisLabel, driftBehaviourSentence, forbiddenSentence, itemKindLabel, labelOf, notAvailableSentence,
    preReleaseNotStated, preparationFailureSentence, probeResultSentence,
} from '@/utils/upstreamLabels'
import UpstreamDepthAssessment from './UpstreamDepthAssessment.vue'
import UpstreamLineTable from './UpstreamLineTable.vue'
import UpstreamProbeResult from './UpstreamProbeResult.vue'

const PENDING_TEXT = 'Reading the item and its release history from the source. This can take a few minutes for a long history.'
const CONSUMED_TEXT = 'This preparation was confirmed. The item is followed.'
const EXPIRED_TEXT = 'This preparation has expired. Prepare the item again.'
const NAME_COLLIDES = 'A product or component with the supplier\'s name already exists here, so another name is proposed.'
const COMPONENTS_TRUNCATED = 'Only the components of the newest releases are listed. Others are followed at their proposed depth when a release references them, and an alert tells you.'
const COMPONENT_RELEASES = 'Only the component releases that the ingested product releases reference are ingested.'
const DRIFT_CHOICES = [DriftBehaviour.STOP, DriftBehaviour.ADAPT]
const PREPARE_AGAIN_REASONS = ['PREPARATION_NOT_USABLE', 'PREPARATION_STALE']
const PENDING_POLL_MS = 2000
const SCOPE_DEBOUNCE_MS = 300
const CLOCK_TICK_MS = 10000

type PageState = 'LOADING' | 'SHOWN' | 'FORBIDDEN' | 'ERROR'

const store = useStore()
const route = useRoute()
const router = useRouter()

const myUser = computed((): any => store.getters.myuser)
const available = computed((): boolean => upstreamSourcesAvailable(myUser.value?.installationType))
const orgUuid = computed((): string => String(route.params.orguuid ?? ''))
const uuid = computed((): string => String(route.params.uuid ?? ''))

const pageState = ref<PageState>('LOADING')
const loadError = ref('')
const preparation = ref<any>(null)
const source = ref<any>(null)
const model = ref<ConfirmModel>(initialConfirmModel(null))
const scope = ref<ScopeState & { answer: any }>({ key: null, answer: null, error: null })
const now = ref(new Date())
const confirming = ref(false)
const confirmRefusal = ref<UpstreamRefusal | null>(null)
const preparingAgain = ref(false)
const prepareAgainError = ref('')
const releases = ref<any[]>([])
const releasesTotal = ref(0)
const releasesCursor = ref<string | null>(null)
const releasesLoading = ref(false)
const releasesError = ref('')

const item = computed((): any => preparation.value?.item ?? {})
const components = computed((): any[] => preparation.value?.components ?? [])
const expired = computed((): boolean => isExpired(preparation.value, now.value))
const sourceName = computed((): string => source.value?.name ?? preparation.value?.source ?? '')
const sourceFailureProbe = computed(() => ({
    state: 'DONE',
    result: preparation.value?.sourceFailure,
    message: labelOf(probeResultSentence, preparation.value?.sourceFailure),
}))
const followsTab = computed(() => ({ name: 'UpstreamOfOrg', params: { orguuid: orgUuid.value }, query: { tab: 'follows' } }))
const followsTabOfSource = computed(() => {
    const s = preparation.value?.source
    return { name: 'UpstreamOfOrg', params: { orguuid: orgUuid.value }, query: isUuid(s) ? { tab: 'follows', source: s } : { tab: 'follows' } }
})
const offersPrepareAgain = computed((): boolean => !!confirmRefusal.value?.reason && PREPARE_AGAIN_REASONS.includes(confirmRefusal.value.reason))

const currentScope = computed(() => preparation.value ? scopeVariables(model.value, preparation.value) : null)
const scopeStale = computed((): boolean => !!currentScope.value && scope.value.key !== currentScopeKey(model.value, preparation.value))
const blockers = computed((): string[] => preparation.value ? confirmBlockers(model.value, preparation.value, scope.value, now.value) : [])

function dateOf (d: string): string {
    return commonFunctions.dateDisplay(d)
}

function existingLink (proposal: any) {
    const component = proposal?.existingItem?.component
    if (!isUuid(component) || !isUuid(orgUuid.value)) return null
    return { name: proposal.kind === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg', params: { orguuid: orgUuid.value, compuuid: component } }
}

function candidateOptions (proposal: any) {
    return (proposal?.adoptionCandidates ?? []).map((c: any) => ({
        label: `${c.name}: ${c.releases} releases, ${c.releasesWithUpstreamVersion} with a version the supplier also has`,
        value: c.component,
    }))
}

function depthOptions (proposal: any) {
    return (proposal?.depths ?? []).map((d: any) => ({ label: String(d.depth), value: d.depth }))
}

function isPickable (r: any): boolean {
    return r.usable === true && r.preRelease !== 'YES'
}

// ---- Reading the preparation, and waiting while it is PENDING ----

let pollTimer: ReturnType<typeof setTimeout> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null
let scopeTimer: ReturnType<typeof setTimeout> | null = null
let scopeSeq = 0
let unmounted = false

function clearPoll () {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
}

async function loadPreparation (first: boolean) {
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_FOLLOW_PREPARATION, variables: { uuid: uuid.value }, fetchPolicy: 'no-cache' })
        if (unmounted) return
        const prep = resp?.data?.upstreamFollowPreparation ?? null
        if (!prep) {
            // The schema serves no empty answer; an unknown uuid is refused. Read it as that.
            if (first) pageState.value = 'FORBIDDEN'
            return
        }
        const becameReady = prep.state === 'READY' && preparation.value?.state !== 'READY'
        preparation.value = prep
        pageState.value = 'SHOWN'
        if (becameReady) model.value = initialConfirmModel(prep)
        if (!source.value) loadSource(prep.source)
        if (prep.state === 'PENDING') pollTimer = setTimeout(() => loadPreparation(false), PENDING_POLL_MS)
    } catch (err) {
        if (unmounted) return
        const refusal = refusalOf(err)
        if (first) {
            pageState.value = isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
            loadError.value = refusal.text
        }
        // A refused poll ends the polling; the page keeps what it showed.
    }
}

async function loadSource (sourceUuid: string) {
    if (!isUuid(sourceUuid)) return
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_SOURCE, variables: { uuid: sourceUuid }, fetchPolicy: 'no-cache' })
        if (!unmounted) source.value = resp?.data?.upstreamSource ?? null
    } catch {
        // Without the name the page shows the source's uuid.
    }
}

function resetPage () {
    clearPoll()
    preparation.value = null
    source.value = null
    model.value = initialConfirmModel(null)
    scope.value = { key: null, answer: null, error: null }
    confirmRefusal.value = null
    prepareAgainError.value = ''
    releases.value = []
    releasesTotal.value = 0
    releasesCursor.value = null
    releasesError.value = ''
    pageState.value = 'LOADING'
}

watch([available, uuid], ([isAvailable, prep]) => {
    resetPage()
    if (!isAvailable || !isUuid(prep)) return
    loadPreparation(true)
    if (!clockTimer) clockTimer = setInterval(() => { now.value = new Date() }, CLOCK_TICK_MS)
}, { immediate: true })

onBeforeUnmount(() => {
    unmounted = true
    clearPoll()
    if (clockTimer) clearInterval(clockTimer)
    if (scopeTimer) clearTimeout(scopeTimer)
})

// ---- The release picker of FROM_RELEASE ----

async function loadReleases (after: string | null) {
    if (!preparation.value) return
    releasesLoading.value = true
    releasesError.value = ''
    try {
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_FOLLOW_PREPARATION_RELEASES, variables: { preparation: preparation.value.uuid, after }, fetchPolicy: 'no-cache',
        })
        const page = resp?.data?.upstreamFollowPreparationReleases
        releases.value = after ? releases.value.concat(page?.releases ?? []) : (page?.releases ?? [])
        releasesTotal.value = page?.total ?? releases.value.length
        releasesCursor.value = page?.endCursor ?? null
    } catch (err) {
        releasesError.value = refusalOf(err).text
    } finally {
        releasesLoading.value = false
    }
}

function onBackfillKind (kind: BackfillKind) {
    model.value.backfill.kind = kind
    if (kind === BackfillKind.FROM_RELEASE && !releases.value.length && !releasesLoading.value) loadReleases(null)
}

// ---- The scope preview: 300 ms after the last change, with exactly the values in the form ----

watch(() => currentScopeKey(model.value, preparation.value), () => {
    if (scopeTimer) clearTimeout(scopeTimer)
    scopeTimer = null
    const variables = currentScope.value
    if (!variables || preparation.value?.state !== 'READY') return
    scopeTimer = setTimeout(() => sendScope(variables), SCOPE_DEBOUNCE_MS)
})

async function sendScope (variables: { preparation: string, depth: number, backfill: any }) {
    scopeTimer = null
    const seq = ++scopeSeq
    const key = scopeKey(variables.depth, variables.backfill)
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_FOLLOW_PREPARATION_SCOPE, variables, fetchPolicy: 'no-cache' })
        if (unmounted || seq !== scopeSeq) return
        scope.value = { key, answer: resp?.data?.upstreamFollowPreparationScope ?? null, error: null }
    } catch (err) {
        if (unmounted || seq !== scopeSeq) return
        scope.value = { key, answer: null, error: refusalOf(err).text }
    }
}

// ---- The confirm: the one sender ----

async function follow () {
    if (confirming.value || !preparation.value) return
    if (confirmBlockers(model.value, preparation.value, scope.value, new Date()).length) return
    confirming.value = true
    confirmRefusal.value = null
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: CONFIRM_UPSTREAM_FOLLOW,
            variables: { preparation: preparation.value.uuid, follow: buildConfirmInput(model.value, preparation.value) },
            fetchPolicy: 'no-cache',
        })
        const followUuid = confirmedFollowUuid(resp?.data)
        if (isUuid(followUuid)) router.replace({ name: 'UpstreamFollow', params: { orguuid: orgUuid.value, uuid: followUuid } })
    } catch (err) {
        confirmRefusal.value = refusalOf(err)
    } finally {
        confirming.value = false
    }
}

// ---- Prepare again: a new preparation of the same item, then its page ----

async function prepareAgain () {
    const prep = preparation.value
    if (!prep?.item || preparingAgain.value) return
    preparingAgain.value = true
    prepareAgainError.value = ''
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: PREPARE_UPSTREAM_FOLLOW,
            variables: { source: prep.source, kind: prep.item.kind, upstreamUuid: prep.item.upstreamUuid },
            fetchPolicy: 'no-cache',
        })
        const next = resp?.data?.prepareUpstreamFollow?.uuid
        if (isUuid(next)) router.push({ name: 'UpstreamFollowPreparation', params: { orguuid: orgUuid.value, uuid: next } })
    } catch (err) {
        prepareAgainError.value = refusalOf(err).text
    } finally {
        preparingAgain.value = false
    }
}
</script>

<style scoped>
.upstream-confirm-form section {
    margin-bottom: 20px;
}
.upstream-indent {
    margin-left: 24px;
}
.upstream-small {
    font-size: 0.85em;
    color: #777;
}
.upstream-error {
    color: #d03050;
}
.upstream-inline-spin {
    vertical-align: middle;
    margin-right: 6px;
}
.upstream-loading {
    min-height: 60px;
}
.upstream-blockers {
    margin: 0;
    color: #777;
}
</style>
