<template>
    <div class="upstream-follow-page" data-testid="upstream-follow-page">
        <div v-if="!available" data-testid="upstream-not-available">{{ notAvailableSentence }}</div>
        <template v-else>
            <n-spin v-if="pageState === 'LOADING'" :show="true"><div class="upstream-loading" /></n-spin>
            <div v-else-if="pageState === 'FORBIDDEN'" data-testid="upstream-follow-forbidden">{{ forbiddenSentence }}</div>
            <div v-else-if="pageState === 'ERROR'" class="upstream-error" data-testid="upstream-follow-error">{{ loadError }}</div>
            <template v-else-if="followRow">
                <!-- 1. Header -->
                <section data-testid="upstream-follow-header">
                    <h4>
                        <span data-testid="upstream-follow-name">{{ item.upstreamName }}</span>
                        <n-tag size="small" class="upstream-tag">{{ labelOf(itemKindLabel, item.kind) }}</n-tag>
                        <n-tag v-if="followRow.status === 'ACTIVE' && item.updateState === 'NO_LONGER_UPDATED'" size="small" type="warning" class="upstream-tag"
                            data-testid="upstream-no-longer-updated">{{ labelOf(itemUpdateStateLabel, 'NO_LONGER_UPDATED') }}</n-tag>
                    </h4>
                    <div class="upstream-small">uuid on the source: {{ item.upstreamUuid }}</div>
                    <div>
                        In ReARM:
                        <router-link v-if="componentLink(item)" :to="componentLink(item)" data-testid="upstream-follow-rearm-link">{{ item.kind === 'PRODUCT' ? 'open the product' : 'open the component' }}</router-link>
                    </div>
                    <div>Source: <span data-testid="upstream-follow-source">{{ sourceName }}</span></div>
                    <div v-if="followRow.status === 'UNFOLLOWED'" data-testid="upstream-follow-unfollowed">
                        Unfollowed on {{ dateOf(followRow.unfollowedDate) }}. What was ingested is kept and no longer updated.
                    </div>
                    <div v-else data-testid="upstream-follow-since">Following since {{ dateOf(followRow.createdDate) }}</div>
                    <div>Depth {{ item.depth }}, {{ labelOf(depthOriginLabel, item.depthOrigin) }}</div>
                    <div>When a release does not fit: {{ labelOf(driftBehaviourLabel, followRow.onDrift) }}</div>
                    <div data-testid="upstream-follow-backfill">Starting point: {{ backfillWords }}</div>
                </section>

                <!-- 2. Lines -->
                <section data-testid="upstream-follow-lines">
                    <h5>Version lines</h5>
                    <div v-if="!(item.lines ?? []).length" data-testid="upstream-follow-no-lines">No release has been ingested yet.</div>
                    <n-table v-else size="small" :single-line="false">
                        <thead><tr><th>Line</th><th>Branch</th><th>Latest version</th></tr></thead>
                        <tbody>
                            <tr v-for="line in item.lines" :key="line.lineKey" data-testid="upstream-follow-line">
                                <td>{{ line.lineKey }}</td>
                                <td><router-link v-if="branchLink(item, line)" :to="branchLink(item, line)">branch</router-link></td>
                                <td data-testid="upstream-follow-line-latest">
                                    <router-link v-if="lineReleaseLink(line)" :to="lineReleaseLink(line)" data-testid="upstream-follow-line-release-link">{{ line.latestVersion }}</router-link>
                                    <template v-else>{{ line.latestVersion ?? '' }}</template>
                                </td>
                            </tr>
                        </tbody>
                    </n-table>
                    <template v-if="item.kind === 'PRODUCT'">
                        <h5>Components</h5>
                        <div v-if="!componentItems.length">No component has been ingested yet.</div>
                        <n-table v-else size="small" :single-line="false">
                            <thead><tr><th>Component</th><th>Depth</th><th>Update state</th><th>Lines</th><th></th></tr></thead>
                            <tbody>
                                <tr v-for="c in componentItems" :key="c.uuid" data-testid="upstream-follow-component">
                                    <td>{{ c.upstreamName }}</td>
                                    <td>{{ c.depth }}, {{ labelOf(depthOriginLabel, c.depthOrigin) }}</td>
                                    <td>{{ labelOf(itemUpdateStateLabel, c.updateState) }}</td>
                                    <td>{{ (c.lines ?? []).length }}</td>
                                    <td><router-link v-if="componentLink(c)" :to="componentLink(c)">open</router-link></td>
                                </tr>
                            </tbody>
                        </n-table>
                    </template>
                </section>

                <!-- 3. Last sync -->
                <section data-testid="upstream-follow-last-run">
                    <h5>Last sync</h5>
                    <div v-if="!run">No run yet.</div>
                    <template v-else>
                        <div>
                            {{ labelOf(runKindLabel, run.kind) }}, {{ labelOf(runOriginLabel, run.origin) }}:
                            <span data-testid="upstream-run-state">{{ labelOf(runStateLabel, run.state) }}</span>
                        </div>
                        <div class="upstream-small">
                            created {{ dateOf(run.createdDate) }}<template v-if="run.startedDate">, started {{ dateOf(run.startedDate) }}</template><template v-if="run.finishedDate">, finished {{ dateOf(run.finishedDate) }}</template>
                        </div>
                        <div v-if="runMessage" data-testid="upstream-run-message">{{ runMessage }}</div>
                        <upstream-probe-result v-if="run.sourceFailure" :probe="{ state: 'DONE', result: run.sourceFailure, message: labelOf(probeResultSentence, run.sourceFailure) }" />
                        <div data-testid="upstream-run-counters">{{ countersSentence }}</div>
                        <n-collapse>
                            <n-collapse-item title="All counters" name="counters">
                                <div v-for="[label, value] in allCounters" :key="label" class="upstream-small">{{ label }}: {{ value }}</div>
                            </n-collapse-item>
                        </n-collapse>
                    </template>
                    <div v-if="source" class="upstream-small" data-testid="upstream-source-schedule">
                        <template v-if="source.lastSync">Last sync of the source: {{ labelOf(syncStateLabel, source.lastSync.state) }}<template v-if="source.lastSync.finished">, {{ dateOf(source.lastSync.finished) }}</template>. </template>
                        <template v-if="source.nextSync">Next sync: {{ dateOf(source.nextSync) }}.</template>
                    </div>
                </section>

                <!-- 4. Sync now -->
                <section v-if="canOperate" data-testid="upstream-follow-sync">
                    <n-button size="small" type="primary" :loading="syncing" :disabled="syncing" data-testid="upstream-sync-now" @click="syncNow">Sync now</n-button>
                    <n-alert v-if="syncError" type="error" class="upstream-inline-alert" data-testid="upstream-sync-now-error">{{ syncError }}</n-alert>
                </section>

                <!-- 5. Run history -->
                <section data-testid="upstream-follow-runs">
                    <h5>Run history</h5>
                    <div class="upstream-small">The newest 20 runs.</div>
                    <n-spin v-if="runsState === 'LOADING'" :show="true"><div class="upstream-loading" /></n-spin>
                    <div v-else-if="runsState === 'ERROR'" class="upstream-error" data-testid="upstream-runs-error">{{ runsError }}</div>
                    <div v-else-if="!runs.length" data-testid="upstream-runs-empty">No run yet.</div>
                    <n-table v-else size="small" :single-line="false">
                        <thead><tr><th>Run</th><th>State</th><th>Created</th><th>Started</th><th>Finished</th><th>Result</th><th></th></tr></thead>
                        <tbody>
                            <template v-for="r in runs" :key="r.uuid">
                                <tr data-testid="upstream-run-row">
                                    <td>{{ labelOf(runKindLabel, r.kind) }}, {{ labelOf(runOriginLabel, r.origin) }}</td>
                                    <td data-testid="upstream-run-row-state">{{ labelOf(runStateLabel, r.state) }}</td>
                                    <td>{{ dateOf(r.createdDate) }}</td>
                                    <td>{{ dateOf(r.startedDate) }}</td>
                                    <td>{{ dateOf(r.finishedDate) }}</td>
                                    <td>
                                        <div>{{ countersSentenceOf(r) }}</div>
                                        <div v-if="runMessageOf(r)" data-testid="upstream-run-row-message">{{ runMessageOf(r) }}</div>
                                    </td>
                                    <td>
                                        <n-button text size="tiny" data-testid="upstream-run-row-toggle" @click="toggleRun(r.uuid)">
                                            {{ expandedRuns.includes(r.uuid) ? 'Hide counters' : 'All counters' }}
                                        </n-button>
                                    </td>
                                </tr>
                                <tr v-if="expandedRuns.includes(r.uuid)" data-testid="upstream-run-row-counters">
                                    <td colspan="7">
                                        <div v-for="[label, value] in allCountersOf(r)" :key="label" class="upstream-small">{{ label }}: {{ value }}</div>
                                    </td>
                                </tr>
                            </template>
                        </tbody>
                    </n-table>
                </section>

                <!-- 6. Releases -->
                <section data-testid="upstream-follow-releases">
                    <h5>Releases</h5>
                    <div class="upstream-filters">
                        <n-select v-if="componentItems.length" :value="releaseItem" :options="releaseItemOptions" size="small" style="max-width: 320px;"
                            data-testid="upstream-releases-item" @update:value="chooseReleaseItem" />
                        <n-select :value="releaseStates" :options="releaseStateOptions" multiple clearable size="small" placeholder="Every state" style="max-width: 420px;"
                            data-testid="upstream-releases-states" @update:value="chooseReleaseStates" />
                    </div>
                    <n-spin v-if="releasesState === 'LOADING'" :show="true"><div class="upstream-loading" /></n-spin>
                    <div v-else-if="releasesState === 'ERROR'" class="upstream-error" data-testid="upstream-releases-error">{{ releasesError }}</div>
                    <div v-else-if="!releases.length" data-testid="upstream-releases-empty">No release is listed for this choice.</div>
                    <template v-else>
                        <n-table size="small" :single-line="false">
                            <thead><tr><th>Version</th><th>Upstream date</th><th>State</th><th>Line</th><th>In ReARM</th></tr></thead>
                            <tbody>
                                <tr v-for="row in releases" :key="row.uuid" data-testid="upstream-release-row">
                                    <td data-testid="upstream-release-version">{{ row.upstreamVersion ?? 'no usable version' }}</td>
                                    <td>{{ dateOf(row.upstreamDate) }}</td>
                                    <td>
                                        <div data-testid="upstream-release-state">{{ labelOf(releaseStateLabel, row.state) }}</div>
                                        <div v-if="row.state === 'FAILED' && row.message" data-testid="upstream-release-message">{{ row.message }}</div>
                                        <div v-if="row.presence === 'GONE'" data-testid="upstream-release-gone">No longer on the source since {{ dateOf(row.goneSince) }}</div>
                                    </td>
                                    <td>{{ row.lineKey ?? '' }}</td>
                                    <td><router-link v-if="releaseLink(row)" :to="releaseLink(row)" data-testid="upstream-release-link">open the release</router-link></td>
                                </tr>
                            </tbody>
                        </n-table>
                        <n-button v-if="releasesMore" size="small" class="upstream-more" :loading="releasesLoadingMore" :disabled="releasesLoadingMore"
                            data-testid="upstream-releases-more" @click="loadReleases(false)">Load more</n-button>
                    </template>
                </section>

                <!-- 7. Pending -->
                <section v-if="(followRow.pendingCount ?? 0) > 0" data-testid="upstream-follow-pending">
                    <h5>Pending</h5>
                    <n-alert type="warning" data-testid="upstream-pending-alert">{{ pendingSentence }}</n-alert>
                    <n-table size="small" :single-line="false" class="upstream-pending-table">
                        <thead><tr><th>Item</th><th>Version</th><th>Upstream date</th><th>Why</th></tr></thead>
                        <tbody>
                            <tr v-for="p in pendingRows" :key="p.uuid" data-testid="upstream-pending-row">
                                <td>{{ itemNameOf(p.item) }}</td>
                                <td>{{ p.upstreamVersion ?? 'no usable version' }}</td>
                                <td>{{ dateOf(p.upstreamDate) }}</td>
                                <td data-testid="upstream-pending-drift">{{ labelOf(driftKindLabel, p.drift) }}</td>
                            </tr>
                        </tbody>
                    </n-table>
                    <div v-if="followRow.pendingCount > pendingRows.length" class="upstream-small" data-testid="upstream-pending-truncated">The oldest 50 are listed.</div>
                    <n-button v-if="canOperate" size="small" class="upstream-more" data-testid="upstream-reconfigure" @click="reconfigureOpen = true">Reconfigure</n-button>
                </section>

                <!-- 8. Unfollow -->
                <section v-if="canOperate" data-testid="upstream-follow-unfollow">
                    <n-button size="small" type="warning" data-testid="upstream-unfollow" @click="openUnfollow">Unfollow</n-button>
                </section>

                <n-modal :show="unfollowOpen" preset="dialog" type="warning" title="Unfollow" :mask-closable="!unfollowing"
                    @update:show="(v: boolean) => { if (!v) cancelUnfollow() }">
                    <div data-testid="upstream-unfollow-dialog">
                        <p data-testid="upstream-unfollow-statement">{{ unfollowStatement(item.upstreamName ?? '') }}</p>
                        <n-alert v-if="unfollowError" type="error" class="upstream-inline-alert" data-testid="upstream-unfollow-error">{{ unfollowError }}</n-alert>
                        <div class="upstream-dialog-buttons">
                            <n-button :disabled="unfollowing" data-testid="upstream-unfollow-cancel" @click="cancelUnfollow">Cancel</n-button>
                            <n-button type="warning" :loading="unfollowing" :disabled="unfollowing" data-testid="upstream-unfollow-confirm" @click="confirmUnfollow">Unfollow</n-button>
                        </div>
                    </div>
                </n-modal>

                <upstream-reconfigure-modal v-if="canOperate" v-model:show="reconfigureOpen" :follow="followRow" @saved="onReconfigured" />
            </template>
        </template>
    </div>
</template>

<script lang="ts" setup>
// The follow page (ReARM Pro, TPF-185 design 3.9): the header, the version lines and the last run
// of one follow (sections 1 to 3), and its operation (sections 4 to 8): sync now, the run history,
// the releases of the item or a component, the pending releases with the reconfigure dialog, and
// unfollow. Every member with READ sees every section; sync now, reconfigure and unfollow are drawn
// for an administrator of the organization on an active follow only, and the backend decides.
// While the run is QUEUED or RUNNING the follow is read again every 5 seconds; when its last run
// changes, the run history and the releases are read again.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useStore } from 'vuex'
import { NAlert, NButton, NCollapse, NCollapseItem, NModal, NSelect, NSpin, NTable, NTag } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isForbidden, isUuid, refusalOf, upstreamSourcesAvailable } from '@/utils/upstream'
import {
    GET_UPSTREAM_FOLLOW, GET_UPSTREAM_INGEST_RUNS, GET_UPSTREAM_RELEASES, START_UPSTREAM_FOLLOW_RUN, UNFOLLOW_UPSTREAM_ITEM,
} from '@/utils/upstreamFollowQueries'
import { GET_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'
import {
    depthOriginLabel, driftBehaviourLabel, driftKindLabel, forbiddenSentence, itemKindLabel, itemUpdateStateLabel, labelOf, notAvailableSentence,
    probeResultSentence, releaseStateLabel, runKindLabel, runOriginLabel, runStateLabel, runStopSentence, syncStateLabel, unfollowStatement,
} from '@/utils/upstreamLabels'
import UpstreamProbeResult from './UpstreamProbeResult.vue'
import UpstreamReconfigureModal from './UpstreamReconfigureModal.vue'

const RUN_POLL_MS = 5000
const OPEN_RUN_STATES = ['QUEUED', 'RUNNING']
const RELEASES_PAGE = 50
const RELEASE_STATES = ['INGESTED', 'DOCUMENTS_PENDING', 'SKIPPED_PRE_RELEASE', 'FAILED', 'HELD']

type PageState = 'LOADING' | 'SHOWN' | 'FORBIDDEN' | 'ERROR'
type ReadState = 'LOADING' | 'SHOWN' | 'ERROR'

const store = useStore()
const route = useRoute()

const myUser = computed((): any => store.getters.myuser)
const available = computed((): boolean => upstreamSourcesAvailable(myUser.value?.installationType))
const orgUuid = computed((): string => String(route.params.orguuid ?? ''))
const uuid = computed((): string => String(route.params.uuid ?? ''))

const pageState = ref<PageState>('LOADING')
const loadError = ref('')
const followRow = ref<any>(null)
const source = ref<any>(null)

const item = computed((): any => followRow.value?.item ?? {})
const componentItems = computed((): any[] => followRow.value?.components ?? [])
const run = computed((): any => followRow.value?.lastRun ?? null)
const sourceName = computed((): string => source.value?.name ?? followRow.value?.source ?? '')

/** An administrator of the organization, on an active follow: sync now, reconfigure and unfollow are drawn. */
const isOrgAdmin = computed((): boolean => commonFunctions.getUserPermission(orgUuid.value, myUser.value).org === 'ADMIN')
const canOperate = computed((): boolean => isOrgAdmin.value && followRow.value?.status === 'ACTIVE')

function dateOf (d: string | null | undefined): string {
    return d ? commonFunctions.dateDisplay(d) : ''
}

function componentLink (it: any) {
    if (!isUuid(it?.component) || !isUuid(orgUuid.value)) return null
    return { name: it.kind === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg', params: { orguuid: orgUuid.value, compuuid: it.component } }
}

function branchLink (it: any, line: any) {
    if (!isUuid(it?.component) || !isUuid(line?.branch) || !isUuid(orgUuid.value)) return null
    return { name: it.kind === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg', params: { orguuid: orgUuid.value, compuuid: it.component, branchuuid: line.branch } }
}

/** The starting point in words, from the follow's backfill. */
const backfillWords = computed((): string => {
    const b = followRow.value?.backfill
    if (!b) return ''
    if (b.kind === 'LATEST_PER_LINE') return `the newest ${b.count} releases of each version line`
    if (b.kind === 'FROM_DATE') return `every release dated on or after ${dateOf(b.date)}`
    if (b.kind === 'FROM_RELEASE') return `from release ${b.releaseVersion ?? b.release} on`
    return ''
})

/** The backend's sentence of a stopped or failed run, else the words for its stop reason. */
const runMessage = computed((): string => runMessageOf(run.value))

function countersSentenceOf (r: any): string {
    const c = r?.counters ?? {}
    return `${c.releasesInScope ?? 0} releases in scope, ${c.created ?? 0} created, ${c.alreadyIngested ?? 0} already ingested, `
        + `${c.failed ?? 0} failed, ${c.held ?? 0} held`
}

const countersSentence = computed((): string => countersSentenceOf(run.value))

const COUNTER_LABELS: Array<[string, string]> = [
    ['releasesInScope', 'Releases in scope'], ['created', 'Created'], ['adopted', 'Adopted'], ['alreadyIngested', 'Already ingested'],
    ['skippedPreReleases', 'Pre-releases skipped'], ['failed', 'Failed'], ['failedComponentRefs', 'Component references failed'],
    ['documentsPending', 'Documents pending'], ['filesStored', 'Files stored'], ['filesNotKept', 'Files not kept'], ['bytes', 'Bytes'],
    ['checked', 'Checked again'], ['newCollectionVersions', 'New collection versions'], ['gone', 'Gone upstream'], ['repaired', 'Repaired'],
    ['held', 'Held'],
]

function allCountersOf (r: any): Array<[string, string]> {
    const c = r?.counters ?? {}
    return COUNTER_LABELS.map(([key, label]) => [label, String(c[key] ?? 0)])
}

const allCounters = computed((): Array<[string, string]> => allCountersOf(run.value))

// ---- 5. Run history ----

const runs = ref<any[]>([])
const runsState = ref<ReadState>('LOADING')
const runsError = ref('')
const expandedRuns = ref<string[]>([])

/** The backend's sentence of a run, else the words for its stop reason. */
function runMessageOf (r: any): string {
    if (!r) return ''
    if (r.message) return r.message
    return r.stopReason ? labelOf(runStopSentence, r.stopReason) : ''
}

function toggleRun (runUuid: string) {
    expandedRuns.value = expandedRuns.value.includes(runUuid)
        ? expandedRuns.value.filter(u => u !== runUuid)
        : [...expandedRuns.value, runUuid]
}

let runsRead = 0

async function loadRuns () {
    const mine = ++runsRead
    if (!runs.value.length) runsState.value = 'LOADING'
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_INGEST_RUNS, variables: { follow: uuid.value }, fetchPolicy: 'no-cache' })
        if (unmounted || mine !== runsRead) return
        runs.value = resp?.data?.upstreamIngestRuns ?? []
        runsState.value = 'SHOWN'
    } catch (err) {
        if (unmounted || mine !== runsRead) return
        runsError.value = refusalOf(err).text
        runsState.value = 'ERROR'
    }
}

// ---- 6. Releases ----

const releaseItem = ref<string | null>(null)
const releaseStates = ref<string[]>([])
const releases = ref<any[]>([])
const releasesState = ref<ReadState>('LOADING')
const releasesError = ref('')
const releasesMore = ref(false)
const releasesLoadingMore = ref(false)

const releaseItemOptions = computed(() => [followRow.value?.item, ...componentItems.value]
    .filter((it: any) => it && typeof it.uuid === 'string')
    .map((it: any) => ({ label: `${it.upstreamName ?? ''} (${labelOf(itemKindLabel, it.kind)})`, value: it.uuid })))

const releaseStateOptions = RELEASE_STATES.map(v => ({ label: labelOf(releaseStateLabel, v), value: v }))

/** A link to the ReARM release of an upstream release row: from its release field only, and only when set. */
function releaseLink (row: any) {
    if (!isUuid(row?.release)) return null
    return { name: 'ReleaseView', params: { uuid: row.release } }
}

/** A link to the ReARM release of the latest version of a line: from latestRearmRelease only, and only when it is set. */
function lineReleaseLink (line: any) {
    if (!line?.latestVersion) return null
    return releaseLink({ release: line.latestRearmRelease })
}

let releasesRead = 0

/** Reads the first page (fresh) or the next page; after is the number of rows already loaded. */
async function loadReleases (fresh: boolean) {
    const itemUuid = releaseItem.value
    if (!isUuid(itemUuid)) return
    const mine = ++releasesRead
    if (fresh) {
        releasesState.value = 'LOADING'
        releasesMore.value = false
    } else {
        releasesLoadingMore.value = true
    }
    const after = fresh ? null : String(releases.value.length)
    const states = releaseStates.value.length ? [...releaseStates.value] : null
    try {
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_RELEASES,
            variables: { item: itemUuid, states, after },
            fetchPolicy: 'no-cache',
        })
        if (unmounted || mine !== releasesRead) return
        const page: any[] = resp?.data?.upstreamReleases ?? []
        releases.value = fresh ? page : [...releases.value, ...page]
        releasesMore.value = page.length === RELEASES_PAGE
        releasesState.value = 'SHOWN'
    } catch (err) {
        if (unmounted || mine !== releasesRead) return
        releasesError.value = refusalOf(err).text
        releasesState.value = 'ERROR'
    } finally {
        if (mine === releasesRead) releasesLoadingMore.value = false
    }
}

function chooseReleaseItem (value: string) {
    releaseItem.value = value
    loadReleases(true)
}

function chooseReleaseStates (value: string[] | null) {
    releaseStates.value = value ?? []
    loadReleases(true)
}

// ---- 7. Pending ----

const pendingRows = computed((): any[] => followRow.value?.pending ?? [])

const pendingSentence = computed((): string => {
    const n = followRow.value?.pendingCount ?? 0
    const held = n === 1
        ? '1 release is held because it does not fit the version lines.'
        : `${n} releases are held because they do not fit the version lines.`
    return `${held} Nothing else is held: releases that fit and documents of releases already ingested keep coming in.`
})

/** The name of the item a pending row belongs to: the root item or one of the components. */
function itemNameOf (itemUuid: string): string {
    const found = [followRow.value?.item, ...componentItems.value].find((it: any) => it?.uuid === itemUuid)
    return found?.upstreamName ?? ''
}

const reconfigureOpen = ref(false)

function onReconfigured () {
    reloadFollow()
}

// ---- 4. Sync now ----

const syncing = ref(false)
const syncError = ref('')

async function syncNow () {
    if (syncing.value || !canOperate.value) return
    syncing.value = true
    syncError.value = ''
    try {
        await graphqlClient.mutate({ mutation: START_UPSTREAM_FOLLOW_RUN, variables: { follow: uuid.value }, fetchPolicy: 'no-cache' })
        if (unmounted) return
        reloadFollow()
    } catch (err) {
        if (!unmounted) syncError.value = refusalOf(err).text
    } finally {
        syncing.value = false
    }
}

// ---- 8. Unfollow ----

const unfollowOpen = ref(false)
const unfollowing = ref(false)
const unfollowError = ref('')

function openUnfollow () {
    unfollowError.value = ''
    unfollowOpen.value = true
}

function cancelUnfollow () {
    if (unfollowing.value) return
    unfollowOpen.value = false
    unfollowError.value = ''
}

/** The one place the unfollow is sent from: the positive button of its dialog. */
async function confirmUnfollow () {
    if (unfollowing.value || !unfollowOpen.value) return
    unfollowing.value = true
    unfollowError.value = ''
    try {
        await graphqlClient.mutate({ mutation: UNFOLLOW_UPSTREAM_ITEM, variables: { follow: uuid.value }, fetchPolicy: 'no-cache' })
        unfollowing.value = false
        unfollowOpen.value = false
        if (!unmounted) reloadFollow()
    } catch (err) {
        unfollowing.value = false
        if (!unmounted) unfollowError.value = refusalOf(err).text
    }
}

// ---- Reading, and waiting while the run is open ----

let pollTimer: ReturnType<typeof setTimeout> | null = null
let unmounted = false

function clearPoll () {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
}

/** The last run as far as the history and the releases depend on it. */
function runSignature (f: any): string {
    const r = f?.lastRun
    return r ? `${r.uuid}:${r.state}` : ''
}

async function loadFollow (first: boolean) {
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_FOLLOW, variables: { uuid: uuid.value }, fetchPolicy: 'no-cache' })
        if (unmounted) return
        const before = runSignature(followRow.value)
        followRow.value = resp?.data?.upstreamFollow ?? null
        pageState.value = 'SHOWN'
        if (first && followRow.value) {
            loadSource(followRow.value.source)
            releaseItem.value = followRow.value.item?.uuid ?? null
            loadRuns()
            loadReleases(true)
        } else if (followRow.value && runSignature(followRow.value) !== before) {
            loadRuns()
            loadReleases(true)
        }
        if (OPEN_RUN_STATES.includes(followRow.value?.lastRun?.state)) pollTimer = setTimeout(() => loadFollow(false), RUN_POLL_MS)
    } catch (err) {
        if (unmounted || !first) return
        const refusal = refusalOf(err)
        pageState.value = isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
        loadError.value = refusal.text
    }
}

async function loadSource (sourceUuid: string) {
    if (!isUuid(sourceUuid)) return
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_SOURCE, variables: { uuid: sourceUuid }, fetchPolicy: 'no-cache' })
        if (!unmounted) source.value = resp?.data?.upstreamSource ?? null
    } catch {
        // Without the source the page shows its uuid and goes on.
    }
}

/** Reads the follow again now, after an operation of this page; the poll goes on from there. */
function reloadFollow () {
    clearPoll()
    loadFollow(false)
}

watch([available, uuid], ([isAvailable, follow]) => {
    clearPoll()
    followRow.value = null
    source.value = null
    runs.value = []
    releases.value = []
    releaseItem.value = null
    releaseStates.value = []
    expandedRuns.value = []
    pageState.value = 'LOADING'
    if (!isAvailable || !isUuid(follow)) return
    loadFollow(true)
}, { immediate: true })

onBeforeUnmount(() => {
    unmounted = true
    clearPoll()
})
</script>

<style scoped>
.upstream-follow-page section {
    margin-bottom: 20px;
}
.upstream-tag {
    margin-left: 6px;
}
.upstream-small {
    font-size: 0.85em;
    color: #777;
}
.upstream-error {
    color: #d03050;
}
.upstream-loading {
    min-height: 60px;
}
.upstream-filters {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 8px;
}
.upstream-more {
    margin-top: 8px;
}
.upstream-inline-alert {
    margin: 8px 0;
}
.upstream-pending-table {
    margin-top: 8px;
}
.upstream-dialog-buttons {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
}
</style>
