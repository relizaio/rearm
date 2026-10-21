<template>
    <div class="product-submissions-panel">
        <n-space align="center" class="mb-2">
            <n-select data-testid="submissions-branch" :value="branch" :options="branchOptions" :filterable="false"
                style="min-width: 220px;" :placeholder="branchWord" @update:value="selectBranch" />
            <n-select data-testid="submissions-kind" :value="kindFilter ?? ''" :options="kindFilterOptions" :filterable="false"
                style="min-width: 180px;" @update:value="setKind" />
            <n-select data-testid="submissions-state" :value="stateFilter ?? ''" :options="stateFilterOptions" :filterable="false"
                style="min-width: 140px;" @update:value="setState" />
            <n-button v-if="loadFailed" size="small" data-testid="submissions-retry" @click="read">Retry</n-button>
        </n-space>

        <p v-if="!loadFailed && loaded && !releases.length" data-testid="submissions-no-releases" class="text-muted">
            No release in this feature set yet
        </p>
        <p v-if="!loadFailed && loaded && !heads.length" data-testid="submissions-none" class="text-muted">
            No submission yet; create one from a release that is ASSEMBLED or above
        </p>
        <n-data-table data-testid="submissions-release-rows" :columns="columns" :data="rows" :row-key="rowKey"
            :pagination="false" :bordered="false" size="small" />

        <h6 class="mt-3">Submissions on releases of other feature sets</h6>
        <n-data-table v-if="otherRows.length" data-testid="submissions-other-rows" :columns="otherColumns" :data="otherRows"
            :row-key="otherRowKey" :pagination="false" :bordered="false" size="small" />
        <p v-else data-testid="submissions-other-none" class="text-muted">None</p>
    </div>
</template>

<script setup lang="ts">
// The product page's Submissions tab (task TEA-30, design 3.5.3), Pro only: the main entry point of
// regulatory submissions. One row per release of the selected feature set (50 newest) with each
// kind's head, and below it the heads on releases of other feature sets. The acts run through
// useSubmissionActs and the panel reads again afterwards.
import { computed, h, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { NButton, NDataTable, NSelect, NSpace, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import submissionQueries from '@/utils/submissionQueries'
import commonFunctions from '@/utils/commonFunctions'
import { packageKindLabel } from '@/utils/packageSettings'
import {
    CREATE_LIFECYCLE_REASON, SUBMISSION_ACT_LABELS, SUBMISSION_STATE_LABELS, canCreateSubmission, labelOf,
    submissionOtherRows, submissionReleaseRows,
} from '@/utils/submissions'
import { useSubmissionActs } from '@/utils/submissionActs'

const props = withDefaults(defineProps<{
    orgUuid: string
    componentUuid: string
    componentName?: string | null
    branches?: any[]
    isWritable?: boolean
    installationType?: string
    refreshToken?: number
    branchWord?: string
}>(), {
    componentName: null,
    branches: () => [],
    isWritable: false,
    installationType: 'OSS',
    refreshToken: 0,
    branchWord: 'Feature Set',
})

const RELEASES_PER_FEATURE_SET = 50

const notification = useNotification()
const branch = ref<string | null>(null)
const kindFilter = ref<string | null>(null)
const stateFilter = ref<string | null>(null)
const kinds = ref<any[]>([])
const heads = ref<any[]>([])
const releases = ref<any[]>([])
const loadFailed = ref(false)
const loaded = ref(false)

const acts = useSubmissionActs({ client: graphqlClient, reload: () => read() })

const branchOptions = computed(() => (props.branches ?? []).map((b: any) => ({ label: b.name, value: b.uuid })))
const kindFilterOptions = computed(() => [{ label: 'All kinds', value: '' },
    ...kinds.value.map((k: any) => ({ label: packageKindLabel(k.kind), value: k.kind }))])
const stateFilterOptions = [{ label: 'All', value: '' },
    ...Object.entries(SUBMISSION_STATE_LABELS).map(([value, label]) => ({ label, value }))]
const shownKinds = computed((): string[] => kindFilter.value ? [kindFilter.value] : kinds.value.map((k: any) => k.kind))
const rows = computed(() => submissionReleaseRows(releases.value, heads.value, shownKinds.value))
const otherRows = computed(() => submissionOtherRows(releases.value, heads.value))
const rowKey = (r: any) => r.release
const otherRowKey = (r: any) => r.uuid

function releaseLink (uuid: string, text: string) {
    return h(RouterLink, { to: { name: 'ReleaseView', params: { uuid } }, 'data-testid': 'submission-release-link' }, () => text)
}

function dateText (d: string | null | undefined): string {
    return d ? new Date(d).toLocaleString('en-CA') : ''
}

function kindCell (row: any, kind: string) {
    const head = row.byKind[kind]
    const names = { componentName: props.componentName, releaseVersion: row.version }
    const busy = acts.busy.value
    if (!head) {
        if (!props.isWritable) return h('span', { class: 'text-muted' }, 'No submission')
        const allowed = canCreateSubmission(row.lifecycle)
        return h(NButton, { size: 'tiny', 'data-testid': 'submission-create', disabled: !allowed || busy, loading: busy,
            title: allowed ? '' : CREATE_LIFECYCLE_REASON, onClick: () => acts.create(row.release, kind, names) }, () => 'Create')
    }
    const children: any[] = [
        h(NTag, { size: 'small', type: head.state === 'ACTIVE' ? 'success' : 'warning', 'data-testid': 'submission-state' },
            () => labelOf(SUBMISSION_STATE_LABELS, head.state)),
        ' v' + head.latestVersion,
        h('div', { class: 'text-muted' }, labelOf(SUBMISSION_ACT_LABELS, head.lastActKind) + ' ' + dateText(head.lastActDate)),
    ]
    if (props.isWritable) {
        children.push(h(NButton, { size: 'tiny', 'data-testid': 'submission-resubmit', disabled: busy, loading: busy,
            onClick: () => acts.resubmit(head, names) }, () => 'Resubmit'))
        if (head.state === 'ACTIVE') {
            children.push(h(NButton, { size: 'tiny', type: 'error', style: 'margin-left: 6px;', 'data-testid': 'submission-withdraw',
                disabled: busy, onClick: () => acts.withdraw(head, names) }, () => 'Withdraw'))
        }
    }
    return h('div', { 'data-testid': 'submission-cell-' + kind }, children)
}

const columns = computed(() => [
    { key: 'version', title: 'Release', render: (r: any) => releaseLink(r.release, r.version) },
    { key: 'lifecycle', title: 'Lifecycle' },
    { key: 'createdDate', title: 'Created', render: (r: any) => dateText(r.createdDate) },
    ...shownKinds.value.map(k => ({ key: 'kind-' + k, title: packageKindLabel(k), render: (r: any) => kindCell(r, k) })),
])

const otherColumns = [
    { key: 'releaseVersion', title: 'Release', render: (r: any) => releaseLink(r.release, r.releaseVersion || r.release) },
    { key: 'kind', title: 'Kind', render: (r: any) => packageKindLabel(r.kind) },
    { key: 'state', title: 'State', render: (r: any) => labelOf(SUBMISSION_STATE_LABELS, r.state) },
    { key: 'latestVersion', title: 'Version', render: (r: any) => 'v' + r.latestVersion },
    { key: 'lastActDate', title: 'Last act', render: (r: any) => dateText(r.lastActDate) },
]

async function read () {
    if (!branch.value) return
    try {
        const [k, s, r]: any[] = await Promise.all([
            graphqlClient.query({ query: profilesQueries.PackageKindsGql, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: submissionQueries.SubmissionsOfComponentGql, fetchPolicy: 'no-cache',
                variables: { component: props.componentUuid, kind: kindFilter.value || null, state: stateFilter.value || null } }),
            graphqlClient.query({ query: submissionQueries.SubmissionReleasesOfBranchGql, fetchPolicy: 'no-cache',
                variables: { branch: branch.value, numRecords: RELEASES_PER_FEATURE_SET } }),
        ])
        kinds.value = k.data.packageKinds ?? []
        heads.value = s.data.submissionsOfComponent ?? []
        releases.value = r.data.releases ?? []
        loadFailed.value = false
        loaded.value = true
    } catch (err: any) {
        console.error(err)
        heads.value = []
        releases.value = []
        loadFailed.value = true
        notification.error({ content: commonFunctions.parseGraphQLError(err.message), duration: 5000 })
    }
}

function selectBranch (b: string) {
    branch.value = b
    read()
}

function setKind (k: string) {
    kindFilter.value = k || null
    read()
}

function setState (s: string) {
    stateFilter.value = s || null
    read()
}

/** The selected feature set while it is still listed, else the first (main). */
function pickBranch (): boolean {
    const list = props.branches ?? []
    if (branch.value && list.some((b: any) => b.uuid === branch.value)) return false
    const next = list[0]?.uuid ?? null
    const changed = next !== branch.value
    branch.value = next
    return changed
}

watch(() => props.branches, () => {
    if (pickBranch()) read()
}, { immediate: true })

watch(() => props.refreshToken, () => {
    pickBranch()
    read()
})

defineExpose({ read })
</script>
