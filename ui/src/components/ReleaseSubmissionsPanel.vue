<template>
    <div class="release-submissions-panel">
        <div v-if="loadError" class="text-danger">
            <span data-testid="submissions-load-error">{{ loadError }}</span>
            <n-button size="tiny" style="margin-left: 8px;" data-testid="submissions-retry" @click="read">Retry</n-button>
        </div>
        <p v-else-if="loaded && !heads.length" data-testid="submissions-empty" class="text-muted">No submission of this release yet</p>

        <div v-for="k in kindList" :key="k" class="submission-kind" :data-testid="'submission-kind-' + k">
            <div :data-testid="'submission-head-' + k" class="submission-head">
                <strong>{{ packageKindLabel(k) }}</strong>
                <template v-if="headOf(k)">
                    <n-tag size="small" :type="headOf(k).state === 'ACTIVE' ? 'success' : 'warning'" data-testid="submission-state"
                        style="margin-left: 8px;">{{ labelOf(SUBMISSION_STATE_LABELS, headOf(k).state) }}</n-tag>
                    <span style="margin-left: 8px;">v{{ headOf(k).latestVersion }}</span>
                    <span class="text-muted" style="margin-left: 8px;">
                        {{ labelOf(SUBMISSION_ACT_LABELS, headOf(k).lastActKind) }} on {{ dateText(headOf(k).lastActDate) }}
                        by {{ userLabel(headOf(k).lastActBy) }}
                    </span>
                    <div v-if="headOf(k).state === 'WITHDRAWN'" class="submission-withdrawn">
                        Withdrawn on {{ dateText(headOf(k).withdrawnDate) }}:
                        <span data-testid="submission-withdraw-reason" style="white-space: pre-wrap;">{{ headOf(k).withdrawReason }}</span>
                    </div>
                </template>
                <span v-else class="text-muted" style="margin-left: 8px;">No submission</span>
                <n-space v-if="isWritable" class="submission-actions">
                    <n-button v-if="!headOf(k)" size="tiny" data-testid="submission-create" :loading="acts.busy.value"
                        :disabled="acts.busy.value || !canCreate" :title="canCreate ? '' : CREATE_LIFECYCLE_REASON"
                        @click="acts.create(releaseUuid, k, actNames)">Create</n-button>
                    <n-button v-if="headOf(k)" size="tiny" data-testid="submission-resubmit" :loading="acts.busy.value"
                        :disabled="acts.busy.value" @click="acts.resubmit(headOf(k), actNames)">Resubmit</n-button>
                    <n-button v-if="headOf(k)?.state === 'ACTIVE'" size="tiny" type="error" data-testid="submission-withdraw"
                        :disabled="acts.busy.value" @click="acts.withdraw(headOf(k), actNames)">Withdraw</n-button>
                </n-space>
            </div>
            <n-data-table v-if="headOf(k)" :data-testid="'submission-versions-' + k" :columns="versionColumns(k)"
                :data="versionRows(k)" :row-key="versionKey" :expanded-row-keys="expanded[k] ?? []"
                :pagination="false" :bordered="false" size="small" @update:expanded-row-keys="(keys: any) => expanded[k] = keys" />
        </div>
    </div>
</template>

<script setup lang="ts">
// The release page's Regulatory submissions section (task TEA-30, design 3.5.4), Pro only: per
// kind the head, the acts, and the version history with each version's parts and manifest. Reasons,
// file names and the manifest are text bindings; downloads go through the one URL builder with the
// record's uuid and integer version (design 3.5.2).
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NDataTable, NSpace, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import submissionQueries from '@/utils/submissionQueries'
import commonFunctions from '@/utils/commonFunctions'
import { packageKindLabel } from '@/utils/packageSettings'
import {
    CREATE_LIFECYCLE_REASON, SUBMISSION_ACT_LABELS, SUBMISSION_STATE_LABELS, canCreateSubmission, labelOf,
    manifestDownloadRef, submissionPartRows, submissionVersionRows,
} from '@/utils/submissions'
import { ArtifactDownloadRef, artifactDownloadUrl, downloadArtifactBytes, saveBlob } from '@/utils/artifactDownload'
import { useSubmissionActs } from '@/utils/submissionActs'

const props = withDefaults(defineProps<{
    orgUuid?: string | null
    releaseUuid?: string | null
    releaseVersion?: string | null
    lifecycle?: string | null
    componentUuid?: string | null
    componentName?: string | null
    isWritable?: boolean
    installationType?: string
    names?: Record<string, string>
    userLabel?: (id: string) => string
}>(), {
    orgUuid: null,
    releaseUuid: null,
    releaseVersion: null,
    lifecycle: null,
    componentUuid: null,
    componentName: null,
    isWritable: false,
    installationType: 'OSS',
    names: () => ({}),
    userLabel: (id: string) => id ?? '',
})

const NOT_DOWNLOADABLE = 'not downloadable: no artifact version recorded'

const notification = useNotification()
const kinds = ref<any[]>([])
const heads = ref<any[]>([])
const loaded = ref(false)
const loadError = ref<string | null>(null)
const expanded = reactive<Record<string, any[]>>({})
const shownManifests = reactive<Record<string, boolean>>({})

const acts = useSubmissionActs({ client: graphqlClient, reload: () => read(), names: () => props.names })

const kindList = computed((): string[] => {
    const list = kinds.value.map((k: any) => k.kind)
    for (const hd of heads.value) if (hd?.kind && !list.includes(hd.kind)) list.push(hd.kind)
    return list
})
const canCreate = computed(() => canCreateSubmission(props.lifecycle))
const actNames = computed(() => ({ componentName: props.componentName, releaseVersion: props.releaseVersion }))
const versionKey = (r: any) => r.version

function headOf (kind: string): any {
    return heads.value.find((hd: any) => hd?.kind === kind) ?? null
}

function versionRows (kind: string): any[] {
    return submissionVersionRows(headOf(kind), props.names)
}

function dateText (d: string | null | undefined): string {
    return d ? new Date(d).toLocaleString('en-CA') : ''
}

async function copy (text: string) {
    try {
        await navigator.clipboard.writeText(text)
        notification.success({ content: 'Copied', duration: 2000 })
    } catch (err: any) {
        console.error(err)
        notification.error({ content: 'Failed to copy', duration: 3000 })
    }
}

async function download (ref: ArtifactDownloadRef, mediaType?: string | null) {
    try {
        const bytes = await downloadArtifactBytes(artifactDownloadUrl(ref.uuid, true, ref.version))
        saveBlob(bytes, ref.fileName, mediaType || undefined)
    } catch (err: any) {
        console.error(err)
        notification.error({ content: 'Download failed: ' + String(err?.message ?? err), duration: 5000 })
    }
}

function shaCell (full: string, short: string) {
    return full
        ? h('span', { title: full }, [short, ' ', h(NButton, { size: 'tiny', quaternary: true, 'aria-label': 'Copy sha256',
            onClick: () => copy(full) }, () => 'copy')])
        : ''
}

const partColumns = [
    { key: 'label', title: 'Part' },
    { key: 'requirement', title: 'Requirement' },
    { key: 'outcome', title: 'Outcome', render: (r: any) => r.outcome === 'SKIPPED'
        ? r.outcomeLabel + (r.skipText ? ': ' + r.skipText : '') : r.outcomeLabel },
    { key: 'fileName', title: 'File name' },
    { key: 'mediaType', title: 'Media type' },
    { key: 'size', title: 'Size (bytes)', render: (r: any) => r.size != null ? String(r.size) : '' },
    { key: 'sha256', title: 'SHA-256', render: (r: any) => shaCell(r.sha256, r.sha256Short) },
    {
        key: 'download',
        title: 'Download',
        render: (r: any) => r.download
            ? h(NButton, { size: 'tiny', 'data-testid': 'submission-download', title: 'Download ' + r.download.fileName,
                'aria-label': 'Download ' + r.download.fileName, onClick: () => download(r.download, r.mediaType) }, () => 'Download')
            : (r.outcome === 'SKIPPED' ? '' : h('span', { class: 'text-muted', title: NOT_DOWNLOADABLE,
                'data-testid': 'submission-not-downloadable' }, NOT_DOWNLOADABLE)),
    },
]

function expandOf (kind: string, row: any) {
    const v = row.versionObject
    const key = kind + ':' + row.version
    const manifest = manifestDownloadRef(v)
    return h('div', { class: 'submission-version-detail' }, [
        h(NDataTable, { 'data-testid': 'submission-parts-' + kind + '-' + row.version, columns: partColumns,
            data: submissionPartRows(v), rowKey: (r: any) => r.key, size: 'small', pagination: false, bordered: false }),
        h('div', { class: 'submission-manifest-block' }, [
            manifest
                ? h(NButton, { size: 'tiny', 'data-testid': 'submission-manifest-download', title: 'Download ' + manifest.fileName,
                    'aria-label': 'Download ' + manifest.fileName, onClick: () => download(manifest, 'application/json') },
                () => 'Download manifest')
                : h('span', { class: 'text-muted', title: NOT_DOWNLOADABLE }, NOT_DOWNLOADABLE),
            h(NButton, { size: 'tiny', style: 'margin-left: 6px;', 'data-testid': 'submission-manifest-show',
                onClick: () => { shownManifests[key] = !shownManifests[key] } }, () => shownManifests[key] ? 'Hide manifest' : 'Show manifest'),
            h('span', { style: 'margin-left: 8px; word-break: break-all;' }, ['manifest sha256 ', v?.manifestSha256 ?? '', ' ',
                h(NButton, { size: 'tiny', quaternary: true, 'aria-label': 'Copy manifest sha256',
                    onClick: () => copy(v?.manifestSha256 ?? '') }, () => 'copy')]),
            shownManifests[key] ? h('pre', { 'data-testid': 'submission-manifest',
                style: 'white-space: pre-wrap; word-break: break-all; max-height: 400px; overflow: auto;' }, v?.manifest ?? '') : null,
        ]),
    ])
}

function versionColumns (kind: string) {
    return [
        { type: 'expand' as const, renderExpand: (row: any) => expandOf(kind, row) },
        { key: 'version', title: 'Version', render: (r: any) => 'v' + r.version },
        { key: 'createdDate', title: 'Created', render: (r: any) => dateText(r.createdDate) },
        { key: 'createdBy', title: 'By', render: (r: any) => r.createdBy ? props.userLabel(r.createdBy) : '' },
        { key: 'reason', title: 'Reason', render: (r: any) => h('span', { 'data-testid': 'submission-reason', style: 'white-space: pre-wrap;' }, r.reason) },
        { key: 'settingsLabel', title: 'Settings' },
        { key: 'profileLabel', title: 'SBOM profile' },
        { key: 'parts', title: 'Parts', render: (r: any) => r.producedCount + ' generated, ' + r.skippedCount + ' skipped of ' + r.partCount },
        { key: 'manifestSha256', title: 'Manifest sha256', render: (r: any) => shaCell(r.manifestSha256, r.manifestSha256Short) },
    ]
}

async function read () {
    if (!props.releaseUuid) return
    loadError.value = null
    try {
        const [k, s]: any[] = await Promise.all([
            graphqlClient.query({ query: profilesQueries.PackageKindsGql, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: submissionQueries.SubmissionsOfReleaseGql, variables: { release: props.releaseUuid },
                fetchPolicy: 'no-cache' }),
        ])
        kinds.value = k.data.packageKinds ?? []
        heads.value = s.data.submissionsOfRelease ?? []
        for (const hd of heads.value) {
            const newest = Math.max(0, ...(hd.versions ?? []).map((v: any) => v.version ?? 0))
            if (newest && !expanded[hd.kind]) expanded[hd.kind] = [newest]
        }
        loaded.value = true
    } catch (err: any) {
        console.error(err)
        heads.value = []
        loadError.value = commonFunctions.parseGraphQLError(err.message)
    }
}

onMounted(read)
watch(() => props.releaseUuid, read)

defineExpose({ read })
</script>

<style scoped>
.submission-kind {
    margin-bottom: 1rem;
}
.submission-head {
    margin-bottom: 0.5rem;
}
.submission-actions {
    margin-top: 4px;
}
.submission-manifest-block {
    margin-top: 6px;
}
</style>
