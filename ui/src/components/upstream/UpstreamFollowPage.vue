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
                                <td data-testid="upstream-follow-line-latest">{{ line.latestVersion ?? '' }}</td>
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
            </template>
        </template>
    </div>
</template>

<script lang="ts" setup>
// The follow page (ReARM Pro, TPF-185 design 3.9): the header, the version lines and the last run
// of one follow, read-only. Its sections 4 to 8 (sync now, run history, releases, pending,
// unfollow) are added by a later part of the feature. While the run is QUEUED or RUNNING the
// follow is read again every 5 seconds.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useStore } from 'vuex'
import { NCollapse, NCollapseItem, NSpin, NTable, NTag } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isForbidden, isUuid, refusalOf, upstreamSourcesAvailable } from '@/utils/upstream'
import { GET_UPSTREAM_FOLLOW } from '@/utils/upstreamFollowQueries'
import { GET_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'
import {
    depthOriginLabel, driftBehaviourLabel, forbiddenSentence, itemKindLabel, itemUpdateStateLabel, labelOf, notAvailableSentence, probeResultSentence,
    runKindLabel, runOriginLabel, runStateLabel, runStopSentence, syncStateLabel,
} from '@/utils/upstreamLabels'
import UpstreamProbeResult from './UpstreamProbeResult.vue'

const RUN_POLL_MS = 5000
const OPEN_RUN_STATES = ['QUEUED', 'RUNNING']

type PageState = 'LOADING' | 'SHOWN' | 'FORBIDDEN' | 'ERROR'

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
const runMessage = computed((): string => {
    const r = run.value
    if (!r) return ''
    if (r.message) return r.message
    return r.stopReason ? labelOf(runStopSentence, r.stopReason) : ''
})

const countersSentence = computed((): string => {
    const c = run.value?.counters ?? {}
    return `${c.releasesInScope ?? 0} releases in scope, ${c.created ?? 0} created, ${c.alreadyIngested ?? 0} already ingested, `
        + `${c.failed ?? 0} failed, ${c.held ?? 0} held`
})

const COUNTER_LABELS: Array<[string, string]> = [
    ['releasesInScope', 'Releases in scope'], ['created', 'Created'], ['adopted', 'Adopted'], ['alreadyIngested', 'Already ingested'],
    ['skippedPreReleases', 'Pre-releases skipped'], ['failed', 'Failed'], ['failedComponentRefs', 'Component references failed'],
    ['documentsPending', 'Documents pending'], ['filesStored', 'Files stored'], ['filesNotKept', 'Files not kept'], ['bytes', 'Bytes'],
    ['checked', 'Checked again'], ['newCollectionVersions', 'New collection versions'], ['gone', 'Gone upstream'], ['repaired', 'Repaired'],
    ['held', 'Held'],
]

const allCounters = computed((): Array<[string, string]> => {
    const c = run.value?.counters ?? {}
    return COUNTER_LABELS.map(([key, label]) => [label, String(c[key] ?? 0)])
})

// ---- Reading, and waiting while the run is open ----

let pollTimer: ReturnType<typeof setTimeout> | null = null
let unmounted = false

function clearPoll () {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
}

async function loadFollow (first: boolean) {
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_FOLLOW, variables: { uuid: uuid.value }, fetchPolicy: 'no-cache' })
        if (unmounted) return
        followRow.value = resp?.data?.upstreamFollow ?? null
        pageState.value = 'SHOWN'
        if (first && followRow.value) loadSource(followRow.value.source)
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

watch([available, uuid], ([isAvailable, follow]) => {
    clearPoll()
    followRow.value = null
    source.value = null
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
</style>
