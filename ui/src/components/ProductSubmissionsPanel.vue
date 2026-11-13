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
        <p v-if="!loadFailed && loaded && !heads.length && noKindOn" data-testid="submissions-none" class="text-muted">
            {{ submissionsNotSetUpLead(componentName) }}<RouterLink data-testid="submission-setup-link"
                :to="setupRoute">{{ MEDICAL_DEVICE_SETUP_LINK_TEXT }}</RouterLink>{{ SUBMISSIONS_NOT_SET_UP_TAIL }}{{
                canConfigure ? '' : ' ' + ADMIN_DOES_THIS }}
        </p>
        <p v-else-if="!loadFailed && loaded && !heads.length" data-testid="submissions-none" class="text-muted">
            No submission yet; start a draft on a release that is Assembled or later
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
// kind's head, and below it the heads on releases of other feature sets. It does not act (FDAW-32 3.8): a
// release without a version of a kind links to its release page, where the draft is started.
import { computed, h, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { NButton, NDataTable, NSelect, NSpace, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import submissionQueries from '@/utils/submissionQueries'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import { packageKindLabel } from '@/utils/packageSettings'
import { fdaLifecycleLabel } from '@/utils/fdaLabels'
import {
    MEDICAL_DEVICE_SETUP_LINK_TEXT, SUBMISSIONS_ADMIN_DOES_THIS, SUBMISSIONS_NOT_SET_UP_CELL_LINK, SUBMISSIONS_NOT_SET_UP_TAIL,
    submissionsNotSetUpCell, submissionsNotSetUpLead
} from '@/utils/medicalDeviceSetup'
import {
    CREATE_LIFECYCLE_REASON, SUBMISSION_FILTERED_OUT_TEXT, SUBMISSION_STATE_LABELS, canCreateSubmission,
    labelOf, submissionInStateFilter, submissionLastActWord, submissionOtherRows, submissionReleaseRows, submissionStateTagType,
    submissionStateText,
} from '@/utils/submissions'

const props = withDefaults(defineProps<{
    orgUuid: string
    componentUuid: string
    componentName?: string | null
    branches?: any[]
    isWritable?: boolean
    installationType?: string
    refreshToken?: number
    branchWord?: string
    canConfigure?: boolean
}>(), {
    componentName: null,
    branches: () => [],
    isWritable: false,
    installationType: 'OSS',
    refreshToken: 0,
    branchWord: 'Feature Set',
    canConfigure: false,
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
// The kinds switched on for this product (Component.regulatoryPackageKinds); null while unknown (not
// read yet, or the read failed), when every kind is offered and the server's gate decides.
const enabledKinds = ref<string[] | null>(null)

// The settings modal's Medical device pane (FDAW-18; ComponentView opens it on componentSettingsView=true and
// picks the pane from componentSettingsTab): one save, whose organization steps need an organization administrator.
const ADMIN_DOES_THIS = SUBMISSIONS_ADMIN_DOES_THIS
const route = useRoute()
const setupRoute = computed(() => ({ path: route?.path, query: { ...(route?.query ?? {}),
    componentSettingsView: 'true', componentSettingsTab: 'device' } }))
const noKindOn = computed(() => enabledKinds.value !== null && enabledKinds.value.length === 0)

const branchOptions = computed(() => (props.branches ?? []).map((b: any) => ({ label: b.name, value: b.uuid })))
const kindFilterOptions = computed(() => [{ label: 'All kinds', value: '' },
    ...kinds.value.map((k: any) => ({ label: packageKindLabel(k.kind), value: k.kind }))])
const stateFilterOptions = [{ label: 'All', value: '' },
    ...Object.entries(SUBMISSION_STATE_LABELS).map(([value, label]) => ({ label, value }))]
const shownKinds = computed((): string[] => kindFilter.value ? [kindFilter.value] : kinds.value.map((k: any) => k.kind))
const rows = computed(() => submissionReleaseRows(releases.value, heads.value, shownKinds.value))
// The heads are read unfiltered by state (FDAW-63): the state filter applies here, so a release whose head the
// filter hides is told apart from a release without a head.
const otherRows = computed(() => submissionOtherRows(releases.value,
    heads.value.filter((s: any) => submissionInStateFilter(s, stateFilter.value))))
const rowKey = (r: any) => r.release
const otherRowKey = (r: any) => r.uuid

function releaseLink (uuid: string, text: string) {
    return h(RouterLink, { to: { name: 'ReleaseView', params: { uuid } }, 'data-testid': 'submission-release-link' }, () => text)
}

function dateText (d: string | null | undefined): string {
    return d ? new Date(d).toLocaleString('en-CA') : ''
}

function notSetUpCell (kind: string) {
    const children: any[] = [submissionsNotSetUpCell(packageKindLabel(kind)) + ' ',
        h(RouterLink, { to: setupRoute.value, 'data-testid': 'submission-setup-link' },
            () => SUBMISSIONS_NOT_SET_UP_CELL_LINK)]
    if (!props.canConfigure) children.push(' ' + ADMIN_DOES_THIS)
    return h('div', { class: 'text-muted', 'data-testid': 'submission-not-set-up-' + kind }, children)
}

/** A release with no version of a kind: its draft is started on the release page (FDAW-32 3.8). */
const DRAFT_LINK_TEXT = 'Draft on the release page'

function kindCell (row: any, kind: string) {
    const head = row.byKind[kind]
    if (head && !submissionInStateFilter(head, stateFilter.value)) {
        return h('span', { class: 'text-muted', 'data-testid': 'submission-filtered-' + kind }, SUBMISSION_FILTERED_OUT_TEXT)
    }
    if (!head) {
        if (!props.isWritable) return h('span', { class: 'text-muted' }, 'No submission')
        if (enabledKinds.value !== null && !enabledKinds.value.includes(kind)) return notSetUpCell(kind)
        if (!canCreateSubmission(row.lifecycle)) return h('span', { class: 'text-muted' }, CREATE_LIFECYCLE_REASON)
        return h(RouterLink, { to: { name: 'ReleaseView', params: { uuid: row.release } }, 'data-testid': 'submission-draft-link' },
            () => DRAFT_LINK_TEXT)
    }
    return h('div', { 'data-testid': 'submission-cell-' + kind }, [
        h(NTag, { size: 'small', type: submissionStateTagType(head.state), 'data-testid': 'submission-state' },
            () => submissionStateText(head)),
        ' v' + head.latestVersion,
        h('div', { class: 'text-muted' }, submissionLastActWord(head) + ' ' + dateText(head.lastActDate)),
    ])
}

const columns = computed(() => [
    { key: 'version', title: 'Release', render: (r: any) => releaseLink(r.release, r.version) },
    { key: 'lifecycle', title: 'Lifecycle', render: (r: any) => fdaLifecycleLabel(r.lifecycle) },
    { key: 'createdDate', title: 'Created', render: (r: any) => dateText(r.createdDate) },
    ...shownKinds.value.map(k => ({ key: 'kind-' + k, title: packageKindLabel(k), render: (r: any) => kindCell(r, k) })),
])

const otherColumns = [
    { key: 'releaseVersion', title: 'Release', render: (r: any) => releaseLink(r.release, r.releaseVersion || 'release') },
    { key: 'kind', title: 'Kind', render: (r: any) => packageKindLabel(r.kind) },
    { key: 'state', title: 'State', render: (r: any) => labelOf(SUBMISSION_STATE_LABELS, r.state) },
    { key: 'latestVersion', title: 'Version', render: (r: any) => 'v' + r.latestVersion },
    { key: 'lastActDate', title: 'Last act', render: (r: any) => dateText(r.lastActDate) },
]

// The product's switched-on kinds; a failed read leaves them unknown (today's cells: Create offered).
async function readEnabledKinds (): Promise<string[] | null> {
    try {
        const resp: any = await graphqlClient.query({ query: submissionQueries.ComponentRegulatoryPackageKindsGql,
            variables: { componentUuid: props.componentUuid }, fetchPolicy: 'no-cache' })
        return resp?.data?.component?.regulatoryPackageKinds ?? null
    } catch (err: any) {
        console.error(err)
        return null
    }
}

async function read () {
    if (!branch.value) return
    try {
        const [k, s, r, e]: any[] = await Promise.all([
            graphqlClient.query({ query: profilesQueries.PackageKindsGql, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: submissionQueries.SubmissionsOfComponentGql, fetchPolicy: 'no-cache',
                variables: { component: props.componentUuid, kind: kindFilter.value || null, state: null } }),
            graphqlClient.query({ query: submissionQueries.SubmissionReleasesOfBranchGql, fetchPolicy: 'no-cache',
                variables: { branch: branch.value, numRecords: RELEASES_PER_FEATURE_SET } }),
            readEnabledKinds(),
        ])
        enabledKinds.value = e
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
        notification.error({ content: graphqlRefusalText(err), duration: 5000 })
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
