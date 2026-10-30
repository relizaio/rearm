<template>
    <div class="artifact-scan-status-panel" data-testid="artifact-scan-status-panel">
        <div class="artifact-scan-status-toolbar">
            <n-button size="small" :disabled="pending" :loading="pending" data-testid="artifact-scan-status-refresh" @click="run">Refresh</n-button>
            <span v-if="loadedAt" class="subtle" data-testid="artifact-scan-status-loaded-at">as of {{ loadedAt }}</span>
        </div>
        <div v-if="pending && !status" data-testid="artifact-scan-status-loading"><n-spin size="small" /> Loading scan status</div>
        <n-text v-else-if="supported === false" depth="3" data-testid="artifact-scan-status-unsupported">Scan status is not available on this server</n-text>
        <n-alert v-else-if="failed" type="error" :show-icon="false" data-testid="artifact-scan-status-error">Scan status could not be loaded.</n-alert>
        <n-text v-else-if="supported && !status" depth="3" data-testid="artifact-scan-status-empty">No scan status was returned for this artifact</n-text>
        <template v-else-if="status">
            <div data-testid="artifact-scan-status-state" :data-state="status.state">
                <n-tag :type="line.tone" size="small" round>{{ line.label }}</n-tag>
                <span data-testid="artifact-scan-status-state-text">{{ line.text }}</span>
            </div>
            <div data-testid="artifact-scan-status-candidate" :data-candidate="String(status.inCandidateSet)">
                Fan-out candidate: {{ candidate.flag }}<span class="subtle"> ({{ candidate.rule }})</span>
            </div>
            <dl data-testid="artifact-scan-status-facts">
                <template v-for="f in facts" :key="f.key">
                    <dt>{{ f.label }}</dt>
                    <dd :data-testid="'artifact-scan-status-fact-' + f.key" :title="f.iso || undefined">{{ f.value }}</dd>
                </template>
                <dt>Canonical artifact</dt>
                <dd v-if="status.canonicalArtifactUuid">
                    <span style="display: inline-flex; align-items: center; gap: 4px;">
                        <code data-testid="artifact-scan-status-canonical" style="font-family: monospace; font-size: 12px;">{{ status.canonicalArtifactUuid }}</code>
                        <n-button text size="small" title="Copy Canonical artifact UUID" aria-label="Copy Canonical artifact UUID" data-testid="artifact-scan-status-canonical-copy" @click="copyCanonical"><template #icon><n-icon><Clipboard /></n-icon></template></n-button>
                    </span>
                </dd>
                <dd v-else data-testid="artifact-scan-status-canonical-none">-</dd>
            </dl>
            <template v-if="rows.length > 0">
                <p class="subtle" data-testid="artifact-scan-status-enrichment-note" :data-gated="gatedAttr">{{ enrichmentNote(status.enrichmentGated) }}</p>
                <div data-testid="artifact-scan-status-blockers">
                    <n-data-table size="small" :data="rows" :columns="columns" :row-key="(r: any) => r.key" :row-props="rowProps" />
                </div>
                <p v-if="truncated" class="subtle" data-testid="artifact-scan-status-truncated">{{ truncated }}</p>
            </template>
        </template>
    </div>
</template>

<script setup lang="ts">
/**
 * TPF-122: the scan status of one BOM artifact, in words. Pure of GraphQL: the caller passes the
 * loader and the page's notify; the panel renders only the answer it was given. Mounted fresh on
 * every open (the caller keys it), loads on mount and on each Refresh click, never polls.
 */
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Ref } from 'vue'
import { NAlert, NButton, NDataTable, NIcon, NSpin, NTag, NText } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { Clipboard } from '@vicons/tabler'
import { copyToClipboard } from '@/utils/uuidCopyColumn'
import type { NotifyFn } from '@/utils/uuidCopyColumn'
import { extractError } from '@/utils/notificationsCommon'
import { blockerRows, candidateLine, enrichmentNote, factRows, formatInstant, stateLine, truncatedNote } from '@/utils/artifactScanStatus'
import type { ArtifactScanStatus, BlockerRow, ScanStatusLoadResult } from '@/utils/artifactScanStatus'

const props = defineProps<{
    artifactUuid: string
    load: (artifactUuid: string) => Promise<ScanStatusLoadResult>
    notify: NotifyFn
}>()

const status: Ref<ArtifactScanStatus | null> = ref(null)
const supported: Ref<boolean | null> = ref(null)
const pending = ref(false)
const failed = ref(false)
const loadedAt = ref('')
const now = ref(Date.now())
// A load that settles after a newer one started, or after unmount, is dropped.
let currentRun = 0

async function run () {
    if (pending.value) return
    const runNo = ++currentRun
    pending.value = true
    failed.value = false
    try {
        const r = await props.load(props.artifactUuid)
        if (runNo !== currentRun) return
        now.value = Date.now()
        status.value = r.status
        supported.value = r.supported
        loadedAt.value = formatInstant(new Date(now.value).toISOString())
    } catch (e: any) {
        if (runNo !== currentRun) return
        failed.value = true
        props.notify('error', 'Error', extractError(e))
    } finally {
        if (runNo === currentRun) pending.value = false
    }
}

onMounted(run)
onBeforeUnmount(() => { currentRun++ })

const line = computed(() => status.value ? stateLine(status.value) : { label: '', tone: 'default' as const, text: '' })
const candidate = computed(() => status.value ? candidateLine(status.value) : { flag: '', rule: '' })
const facts = computed(() => status.value ? factRows(status.value, now.value) : [])
const rows = computed(() => status.value ? blockerRows(status.value, now.value) : [])
const truncated = computed(() => status.value ? truncatedNote(status.value) : null)
const gatedAttr = computed(() => status.value?.enrichmentGated === true ? 'true' : status.value?.enrichmentGated === false ? 'false' : 'unknown')

const columns: DataTableColumns<BlockerRow> = [
    { key: 'purl', title: 'Component' },
    { key: 'enrichment', title: 'Enrichment' },
    { key: 'bucket', title: 'Bucket' },
    { key: 'ingestState', title: 'Ingest state' },
    { key: 'bucketUpdated', title: 'Bucket updated', render: (r: BlockerRow) => h('span', { title: r.bucketUpdatedIso || undefined }, r.bucketUpdated) },
    { key: 'reason', title: 'Reason' }
]

const rowProps = (r: BlockerRow) => ({ 'data-testid': 'artifact-scan-status-blocker-' + r.key })

function copyCanonical () {
    return copyToClipboard(status.value?.canonicalArtifactUuid || '', 'Canonical artifact UUID', props.notify)
}
</script>

<style scoped>
.artifact-scan-status-toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}
dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 2px 12px;
}
dd {
    margin: 0;
}
.subtle {
    color: #999;
    font-size: 12px;
}
</style>
