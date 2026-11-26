<template>
    <div v-if="available && origin" class="upstream-marker" :data-testid="'upstream-marker-' + subject">
        <n-alert type="info" :title="expanded ? 'Ingested from an upstream source' : undefined" :closable="false">
            <div class="upstream-marker-headline-row">
                <span data-testid="upstream-marker-headline">{{ headline }}</span>
                <n-button text size="tiny" type="primary" class="upstream-marker-toggle" data-testid="upstream-marker-toggle"
                    :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? 'Hide details' : 'Show details' }}</n-button>
            </div>
            <template v-if="expanded">
                <template v-if="subject === 'ITEM'">
                    <div class="upstream-small">uuid on the source: {{ origin.upstreamUuid }}</div>
                    <div v-if="origin.mapping === 'ADOPTED'" data-testid="upstream-marker-adopted">{{ itemMappingLabel.ADOPTED }}</div>
                </template>
                <template v-else>
                    <div class="upstream-small">
                        <span v-if="origin.upstreamDate">dated {{ dateOf(origin.upstreamDate) }}, </span>uuid on the source: {{ origin.upstreamUuid }}
                    </div>
                    <div>{{ OWNED_BY_SYNC }}</div>
                </template>
            </template>

            <!-- the update state: always when it is a warning, else with the details -->
            <div v-if="expanded || noLongerUpdated" class="upstream-marker-line">
                <n-tag size="small" :type="noLongerUpdated ? 'warning' : 'success'" data-testid="upstream-marker-update-state">
                    {{ labelOf(itemUpdateStateLabel, origin.updateState) }}
                </n-tag>
                <upstream-info-tip v-if="labelOf(UPDATE_STATE_HELP, origin.updateState)" :tip="labelOf(UPDATE_STATE_HELP, origin.updateState)"
                    testid="upstream-marker-update-state-help" />
                <span v-if="noLongerUpdated" class="upstream-marker-text">{{ NO_LONGER_UPDATED_SENTENCE }}</span>
            </div>

            <template v-if="subject === 'RELEASE'">
                <div v-if="origin.presence === 'GONE'" class="upstream-marker-line" data-testid="upstream-marker-gone">
                    <n-tag size="small" type="warning">{{ presenceLabel.GONE }}</n-tag>
                    <span class="upstream-marker-text">The source no longer lists this release since {{ dateOf(origin.goneSince) }}. What was ingested is kept.</span>
                </div>
                <div v-if="origin.integrity === 'UPSTREAM_REWROTE_COLLECTION'" class="upstream-warning" data-testid="upstream-marker-rewrote">
                    {{ releaseIntegrityLabel.UPSTREAM_REWROTE_COLLECTION }}
                </div>
                <template v-if="expanded">
                    <div v-if="origin.state === 'DOCUMENTS_PENDING'" data-testid="upstream-marker-documents-pending">{{ DOCUMENTS_PENDING_SENTENCE }}</div>
                    <div v-if="origin.oneOff" data-testid="upstream-marker-one-off">
                        {{ oneOffFrozenSentence }}
                        <upstream-info-tip :tip="ONE_OFF_HELP" testid="upstream-marker-one-off-help" />
                        <router-link v-if="oneOffLink" :to="oneOffLink" data-testid="upstream-marker-one-off-link">Open the one-off</router-link>
                    </div>
                    <div v-if="newestCollection" data-testid="upstream-marker-newest">
                        Newest ingested documents: collection version {{ newestCollection.version }}, ingested on {{ dateOf(newestCollection.ingestedDate) }}
                    </div>
                </template>
            </template>

            <div v-if="expanded && follow" class="upstream-marker-line" data-testid="upstream-marker-follow">
                <router-link v-if="followLink" :to="followLink" data-testid="upstream-marker-follow-link">Open the follow</router-link>
                <span v-if="follow.lastRun" class="upstream-marker-text" data-testid="upstream-marker-last-sync">
                    Last sync of its follow: {{ labelOf(runStateLabel, follow.lastRun.state) }}<template v-if="follow.lastRun.finishedDate">, {{ dateOf(follow.lastRun.finishedDate) }}</template>
                </span>
            </div>
            <div v-if="follow?.lastRun?.stopReason === 'ITEM_GONE'" class="upstream-marker-line" data-testid="upstream-marker-item-gone">
                <n-tag size="small" type="warning">{{ presenceLabel.GONE }}</n-tag>
                <span class="upstream-marker-text">{{ runStopSentence.ITEM_GONE }}</span>
            </div>

            <div v-if="expanded && subject === 'ITEM'" data-testid="upstream-marker-depth">
                Version lines at depth {{ origin.depth }}<template v-if="labelOf(depthOriginLabel, origin.depthOrigin)">, {{ labelOf(depthOriginLabel, origin.depthOrigin) }}</template>
                <upstream-info-tip :tip="VERSION_LINES_AT_DEPTH_HELP" testid="upstream-marker-depth-help" />
            </div>

            <div v-if="expanded && canFollow" class="upstream-marker-line">
                <n-button size="small" data-testid="upstream-marker-follow-item" @click="showStart = true">Follow this item</n-button>
            </div>

            <n-collapse v-if="expanded && subject === 'RELEASE' && collections.length" class="upstream-snapshot" data-testid="upstream-snapshot" @item-header-click="onSnapshotHeader">
                <n-collapse-item title="Ingested documents (read-only)" name="snapshot">
                    <div v-for="c in collections" :key="c.version" class="upstream-snapshot-version" :data-testid="'upstream-snapshot-version-' + c.version">
                        <div><strong>Collection version {{ c.version }}</strong><span v-if="c.upstreamCreatedDate">, created upstream {{ dateOf(c.upstreamCreatedDate) }}</span>, ingested on {{ dateOf(c.ingestedDate) }}</div>
                        <div v-if="c.updateReasonType || c.updateReasonComment" data-testid="upstream-snapshot-reason">
                            Update reason: {{ c.updateReasonType }}<template v-if="c.updateReasonType && c.updateReasonComment">: </template>{{ c.updateReasonComment }}
                        </div>
                        <n-table size="small" :single-line="false">
                            <thead>
                                <tr><th>#</th><th>Name</th><th>Type</th><th>Class</th><th>Size</th><th>SHA-256</th><th>Outcome</th></tr>
                            </thead>
                            <tbody>
                                <tr v-for="f in c.files" :key="f.number" :data-testid="'upstream-snapshot-file-' + c.version + '-' + f.number">
                                    <td>{{ f.number }}</td>
                                    <td>{{ f.upstreamArtifactName }}</td>
                                    <td>{{ f.upstreamArtifactType }}</td>
                                    <td>{{ labelOf(documentClassLabel, f.documentClass) }}</td>
                                    <td>{{ sizeOf(f.sizeBytes) }}</td>
                                    <td><code v-if="f.sha256">{{ f.sha256.slice(0, 12) }}</code></td>
                                    <td>
                                        {{ labelOf(fileOutcomeLabel, f.outcome) }}<template v-if="f.outcome === 'NOT_KEPT' && f.message">: {{ f.message }}</template>
                                        <n-tag v-if="f.artifact" size="small" class="upstream-tag">Stored</n-tag>
                                        <span v-if="f.conflictArtifact" class="upstream-marker-text">conflicts with a document already here</span>
                                        <div v-if="vexImportLine(c, f)" class="upstream-small" data-testid="upstream-snapshot-vex-import">{{ vexImportLine(c, f) }}</div>
                                    </td>
                                </tr>
                            </tbody>
                        </n-table>
                    </div>
                </n-collapse-item>
            </n-collapse>
        </n-alert>
        <upstream-follow-start-modal v-if="showStart" v-model:show="showStart" :org-uuid="orgUuid" :sources="source ? [source] : []"
                                     :source="origin.source" :kind="origin.kind" :upstream-uuid="origin.upstreamUuid" />
    </div>
</template>

<script lang="ts">
/** Which ingested thing a marker describes: a product or component (an upstream item), or a release. */
export enum MarkerSubject {
    ITEM = 'ITEM',
    RELEASE = 'RELEASE',
}
</script>

<script lang="ts" setup>
// The marker of something an upstream source ingested (ReARM Pro): on a product or component the
// item it came from, on a release the upstream release with its read-only snapshot. It reads its
// own data through the feature's documents, only behind the edition gate; the host's documents do
// not change. The source's name and the follow need read access to the whole organization: a
// refusal of either leaves them out and is not an error. Every supplier text is shown as text.
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useStore } from 'vuex'
import { NAlert, NButton, NCollapse, NCollapseItem, NTable, NTag } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isUuid, upstreamSourcesAvailable } from '@/utils/upstream'
import {
    GET_ARTIFACT_VEX_IMPORT, GET_COMPONENT_INGESTED_FROM, GET_MARKER_FOLLOWS, GET_RELEASE_INGESTED_FROM, ingestedOriginOf,
} from '@/utils/upstreamMarkerQueries'
import { GET_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'
import {
    depthOriginLabel, documentClassLabel, fileOutcomeLabel, itemKindLabel, itemMappingLabel, itemUpdateStateLabel, labelOf,
    oneOffFrozenSentence, presenceLabel, releaseIntegrityLabel, runStateLabel, runStopSentence, vexImportReasonLabel,
    vexImportStateLabel, vexModeLabel,
} from '@/utils/upstreamLabels'
import UpstreamFollowStartModal from './UpstreamFollowStartModal.vue'
import UpstreamInfoTip from './UpstreamInfoTip.vue'
import { ONE_OFF_HELP, UPDATE_STATE_HELP, VERSION_LINES_AT_DEPTH_HELP } from '@/utils/upstreamHelp'

const props = defineProps<{
    /** A value of MarkerSubject. */
    subject: 'ITEM' | 'RELEASE'
    orgUuid: string
    /** The uuid of the component (ITEM) or of the release (RELEASE). */
    uuid: string
    isOrgAdmin: boolean
}>()

const emit = defineEmits<{
    /** The artifacts the ingested collection versions name; the release view keeps them read-only. */
    (e: 'snapshot-artifacts', artifacts: string[]): void
}>()

const UNKNOWN_SOURCE = 'an upstream source'
const OWNED_BY_SYNC = 'The version and the upstream identifiers are owned by the sync.'
const NO_LONGER_UPDATED_SENTENCE = 'No active follow of an active source covers it. What was ingested is kept.'
const DOCUMENTS_PENDING_SENTENCE = 'Some documents of this release are still to be ingested.'

const store = useStore()
const available = computed((): boolean => upstreamSourcesAvailable(store.getters.myuser?.installationType))

const origin = ref<any>(null)
const source = ref<any>(null)
const follows = ref<any[] | null>(null)
const vexImports = ref<Record<string, any>>({})
const vexImportsRead = ref(false)
const showStart = ref(false)
/** Whether the details show; every page starts with the headline and any warning only. */
const expanded = ref(false)
let readNumber = 0

const sourceName = computed((): string => source.value?.name || UNKNOWN_SOURCE)

/** The one line the marker shows folded: where it came from and what it is upstream. */
const headline = computed((): string => (props.subject === MarkerSubject.ITEM
    ? `Ingested from ${sourceName.value}: ${labelOf(itemKindLabel, origin.value?.kind)} ${origin.value?.upstreamName ?? ''}`
    : `Ingested from ${sourceName.value}: upstream release ${origin.value?.upstreamVersion ?? ''}`))

const noLongerUpdated = computed((): boolean => origin.value?.updateState === 'NO_LONGER_UPDATED')

/** The upstream item the marker's follow is looked up by: the item itself, or the item of the release. */
const itemUuid = computed((): string | null => (props.subject === MarkerSubject.ITEM ? origin.value?.uuid : origin.value?.item) ?? null)

const follow = computed((): any => {
    const item = itemUuid.value
    if (!item || !follows.value) return null
    return follows.value.find(f => f?.item?.uuid === item || (f?.components ?? []).some((c: any) => c?.uuid === item)) ?? null
})

const followLink = computed(() => (follow.value && isUuid(props.orgUuid) && isUuid(follow.value.uuid)
    ? { name: 'UpstreamFollow', params: { orguuid: props.orgUuid, uuid: follow.value.uuid } }
    : null))

const oneOffLink = computed(() => (isUuid(props.orgUuid) && isUuid(origin.value?.oneOff)
    ? { name: 'UpstreamOfOrg', params: { orguuid: props.orgUuid }, query: { tab: 'tei', oneOff: origin.value.oneOff } }
    : null))

/** Follow this item: an administrator, an active source that was read, and the follows read with none covering the item. */
const canFollow = computed((): boolean => props.subject === MarkerSubject.ITEM && props.isOrgAdmin && source.value?.status === 'ACTIVE'
    && follows.value !== null && !follow.value)

/** The collection versions, newest first. */
const collections = computed((): any[] => [...(origin.value?.collections ?? [])].sort((a, b) => b.version - a.version))
const newestCollection = computed((): any => collections.value[0] ?? null)

function dateOf (d: string | null | undefined): string {
    return d ? commonFunctions.dateDisplay(d) : ''
}

function sizeOf (bytes: number | null | undefined): string {
    if (typeof bytes !== 'number') return ''
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

/** The stored VEX files of the newest collection version: the ones whose import is read when the section opens. */
const vexFiles = computed((): any[] => (newestCollection.value?.files ?? []).filter((f: any) => f.documentClass === 'VEX' && f.artifact))

function vexImportLine (c: any, f: any): string {
    if (c !== newestCollection.value || f.documentClass !== 'VEX' || !f.artifact) return ''
    const imp = vexImports.value[f.artifact]
    if (!imp) return ''
    const parts = [labelOf(vexImportStateLabel, imp.state)]
    const reason = labelOf(vexImportReasonLabel, imp.reason)
    if (reason) parts.push(reason)
    const mode = labelOf(vexModeLabel, imp.modeUsed)
    if (mode) parts.push(mode)
    if (typeof imp.statementsTotal === 'number') {
        parts.push(imp.statementsTotal + ' statements, ' + (imp.proposalsStaged ?? 0) + ' offered for review, ' + (imp.proposalsAccepted ?? 0)
            + ' accepted, ' + (imp.statementsUnmatched ?? 0) + ' unmatched, ' + (imp.statementsErrored ?? 0) + ' with errors')
    }
    return parts.join('; ')
}

async function readVexImports () {
    if (vexImportsRead.value) return
    vexImportsRead.value = true
    await Promise.all(vexFiles.value.map(async (f: any) => {
        try {
            const resp: any = await graphqlClient.query({ query: GET_ARTIFACT_VEX_IMPORT, variables: { uuid: f.artifact }, fetchPolicy: 'no-cache' })
            const imp = resp?.data?.artifact?.vexImport
            if (imp) vexImports.value = { ...vexImports.value, [f.artifact]: imp }
        } catch (err) {
            // A refused read of an import leaves its line out.
        }
    }))
}

function onSnapshotHeader (data: { name: string | number, expanded: boolean }) {
    if (data.expanded) readVexImports()
}

async function read () {
    const mine = ++readNumber
    origin.value = null
    source.value = null
    follows.value = null
    vexImports.value = {}
    vexImportsRead.value = false
    if (!available.value || !props.uuid) return
    let found: any = null
    try {
        if (props.subject === MarkerSubject.ITEM) {
            const resp: any = await graphqlClient.query({ query: GET_COMPONENT_INGESTED_FROM, variables: { uuid: props.uuid }, fetchPolicy: 'no-cache' })
            found = ingestedOriginOf(resp?.data)
        } else {
            const resp: any = await graphqlClient.query({
                query: GET_RELEASE_INGESTED_FROM, variables: { uuid: props.uuid, org: props.orgUuid || null }, fetchPolicy: 'no-cache',
            })
            found = ingestedOriginOf(resp?.data)
        }
    } catch (err) {
        // The host shows its own errors; a marker that cannot be read is not shown.
        found = null
    }
    if (mine !== readNumber) return
    if (props.subject === MarkerSubject.RELEASE) {
        // Always told, so that a host that moved to another release drops the set of the previous one.
        const artifacts = new Set<string>()
        for (const c of found?.collections ?? []) for (const f of c.files ?? []) if (f.artifact) artifacts.add(f.artifact)
        emit('snapshot-artifacts', [...artifacts])
    }
    if (!found) return
    origin.value = found
    await Promise.all([readSource(found.source, mine), readFollows(found.source, mine)])
}

async function readSource (uuid: string, mine: number) {
    try {
        const resp: any = await graphqlClient.query({ query: GET_UPSTREAM_SOURCE, variables: { uuid }, fetchPolicy: 'no-cache' })
        if (mine === readNumber) source.value = resp?.data?.upstreamSource ?? null
    } catch (err) {
        // Read access to the whole organization is needed: the marker names "an upstream source" instead.
    }
}

async function readFollows (sourceUuid: string, mine: number) {
    if (!props.orgUuid) return
    try {
        const resp: any = await graphqlClient.query({
            query: GET_MARKER_FOLLOWS, variables: { org: props.orgUuid, source: sourceUuid }, fetchPolicy: 'no-cache',
        })
        if (mine === readNumber) follows.value = resp?.data?.upstreamFollows ?? []
    } catch (err) {
        // As for the source: no follow line.
    }
}

watch(() => [available.value, props.uuid, props.orgUuid, props.subject], () => { read() }, { immediate: true })
// another component or release on the same view starts folded again
watch(() => [props.uuid, props.subject], () => { expanded.value = false })
</script>

<style scoped>
.upstream-marker {
    margin: 4px 0;
}
.upstream-marker-headline-row {
    display: flex;
    align-items: baseline;
    gap: 12px;
    flex-wrap: wrap;
}
.upstream-marker-line {
    margin-top: 4px;
}
.upstream-marker-text {
    margin-left: 6px;
}
.upstream-tag {
    margin-left: 6px;
}
.upstream-small {
    font-size: 0.85em;
    color: #777;
}
.upstream-warning {
    color: #f0a020;
}
.upstream-snapshot {
    margin-top: 8px;
}
.upstream-snapshot-version {
    margin-bottom: 12px;
}
</style>
