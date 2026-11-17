<template>
    <div class="submission-draft-card" data-testid="draft-card">
        <div data-testid="draft-heading" class="draft-heading">
            Draft of v{{ draft.plannedVersion }}
            <n-tag size="small" data-testid="draft-state" style="margin-left: 8px;">{{ labelOf(DRAFT_STATE_LABELS, draft.state) }}</n-tag>
        </div>
        <div data-testid="draft-started" class="draft-line">
            {{ startedText }}<span v-if="draft.reasonText" data-testid="draft-reason" class="draft-reason"> {{ draft.reasonText }}</span>
        </div>
        <div v-if="draft.replaces" data-testid="draft-replaces-line" class="draft-line">
            Replaces version {{ draft.replaces.version }} of an earlier submission
        </div>
        <div data-testid="draft-job" class="draft-line">{{ jobText }}</div>
        <div v-if="draft.job?.state === 'FAILED'" data-testid="draft-failure" class="draft-line text-danger">{{ draft.job?.failureText || 'Rebuild failed' }}</div>
        <div v-if="draft.build && draft.job?.state !== 'BUILT'" data-testid="draft-kept-build" class="draft-line text-muted">
            The contents below are of the build of {{ submissionDateText(draft.build.builtAt) }}
        </div>
        <div v-if="stillRunning" data-testid="draft-still-running" class="draft-line">
            {{ STILL_RUNNING }}
            <n-button size="tiny" style="margin-left: 8px;" data-testid="draft-refresh" @click="emit('refresh')">Refresh</n-button>
        </div>
        <div v-if="confirmationLine" data-testid="draft-confirmation" class="draft-line">{{ confirmationLine }}</div>
        <div v-if="isWritable && secondConfirmer === null" data-testid="draft-setting-unread" class="draft-line text-muted">{{ SETTING_UNREAD }}</div>
        <n-alert data-testid="draft-stale" :type="staleType" :show-icon="false" class="draft-line">
            {{ stale.text }}
            <ul v-if="stale.kind === 'changed' && stale.labels.length" data-testid="draft-stale-lines" class="draft-stale-lines">
                <li v-for="l in stale.labels" :key="l">{{ l }}</li>
            </ul>
        </n-alert>
        <div v-if="draft.build" data-testid="draft-diff" class="draft-line">
            <div data-testid="draft-diff-summary">{{ draftDiffSummary(draft.build.diff) }}</div>
            <template v-for="s in diffSections" :key="s.key">
                <details v-if="s.count > 0" :data-testid="'draft-diff-' + s.key" class="draft-diff-section">
                    <summary>{{ s.title }} ({{ s.count }})</summary>
                    <div v-for="(row, i) in s.rows" :key="i" class="draft-diff-line">{{ row }}</div>
                    <div v-if="s.more > 0" class="draft-diff-line text-muted">and {{ s.more }} more</div>
                </details>
            </template>
        </div>
        <table v-if="draft.build" data-testid="draft-parts" class="draft-parts">
            <thead>
                <tr><th>Part</th><th>Size (bytes)</th><th>SHA-256</th><th>Download</th></tr>
            </thead>
            <tbody>
                <tr v-for="r in partRows" :key="r.key" data-testid="draft-part">
                    <td>{{ r.label }}</td>
                    <td>{{ r.size != null ? String(r.size) : '' }}</td>
                    <td><span :title="r.sha256">{{ r.sha256Short }}</span></td>
                    <td>
                        <n-button v-if="r.download" size="tiny" data-testid="draft-part-download" :aria-label="'Download ' + r.label"
                            @click="download(r.download)">Download</n-button>
                    </td>
                </tr>
            </tbody>
        </table>
        <div v-if="draft.build" data-testid="draft-files-hint" class="draft-line text-muted">{{ DRAFT_FILES_HINT }}</div>
        <div v-if="zipOffered" data-testid="draft-zip" class="draft-line">
            <n-button size="tiny" data-testid="draft-zip-download" :loading="zipBusy" :disabled="zipBusy" @click="downloadZip">Download draft zip</n-button>
        </div>
        <n-alert v-if="zipMessage" data-testid="draft-zip-message" type="warning" :show-icon="false" class="draft-line">{{ zipMessage }}</n-alert>
        <div v-if="draft.build?.releaseSbom" data-testid="draft-pin" class="draft-line">Release SBOM version {{ draft.build.releaseSbom.version }}</div>
        <n-space v-if="anyButton" class="draft-actions">
            <n-button v-if="buttons.rebuild.shown" size="tiny" data-testid="draft-rebuild" :disabled="buttons.rebuild.disabled"
                :title="buttons.rebuild.title" @click="emit('rebuild')">Rebuild</n-button>
            <n-button v-if="buttons.discard.shown" size="tiny" type="error" data-testid="draft-discard" :disabled="buttons.discard.disabled"
                :title="buttons.discard.title" @click="emit('discard')">Discard</n-button>
            <n-button v-if="buttons.confirm.shown" size="tiny" data-testid="draft-confirm" :disabled="buttons.confirm.disabled"
                :title="buttons.confirm.title" @click="emit('confirm')">Confirm</n-button>
            <n-button v-if="buttons.returnToEditing.shown" size="tiny" data-testid="draft-return" :disabled="buttons.returnToEditing.disabled"
                :title="buttons.returnToEditing.title" @click="emit('return')">Return to draft</n-button>
            <n-button v-if="buttons.finalize.shown" size="tiny" type="primary" data-testid="draft-finalize" :disabled="buttons.finalize.disabled"
                :title="buttons.finalize.title" @click="emit('finalize')">Finalize</n-button>
        </n-space>
    </div>
</template>

<script setup lang="ts">
// One draft of a submission's next version on the release's Regulatory tab (task FDAW-32 3.6): its state,
// reason, job, held confirmation, stale banner, diff, contents and the five draft buttons. It emits the
// acts and never calls the client itself except for a part's download. Every value is a text binding;
// a moved fingerprint line is shown by its label only.
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NSpace, NTag, useNotification } from 'naive-ui'
import { DRAFT_JOB_STATE_LABELS, DRAFT_STATE_LABELS, NEW_VERSION_REASON_LABELS, labelOf, submissionActorText, submissionDateText } from '@/utils/submissions'
import {
    DRAFT_FILES_HINT, SETTING_UNREAD, STALE_NOT_BUILT, STILL_RUNNING, contentDispositionFileName, draftButtons, draftConfirmationLine, draftDiffSections,
    draftDiffSummary, draftPartRows, draftStaleBanner, draftZipOffered, type DraftPartDownload,
} from '@/utils/submissionDrafts'
import { DOWNLOAD_NOT_ASKED, draftZipUrl, fetchPackageFile, packageFallbackName, saveFile } from '@/utils/packageDownloads'
import { artifactDownloadUrl, downloadArtifactFile, saveBlob } from '@/utils/artifactDownload'

const props = withDefaults(defineProps<{
    draft: any
    secondConfirmer?: boolean | null
    isWritable?: boolean
    busy?: boolean
    stillRunning?: boolean
    names?: Record<string, any>
    currentUser?: string | null
    lifecycle?: string | null
    head?: any
}>(), {
    secondConfirmer: null,
    isWritable: false,
    busy: false,
    stillRunning: false,
    names: () => ({}),
    currentUser: null,
    lifecycle: null,
    head: null,
})

const emit = defineEmits<{
    (e: 'rebuild'): void, (e: 'discard'): void, (e: 'confirm'): void, (e: 'return'): void, (e: 'finalize'): void, (e: 'refresh'): void
}>()

const notification = useNotification()

const startedText = computed(() => 'Started on ' + submissionDateText(props.draft?.startedAt) + ' by '
    + submissionActorText(props.draft?.startedBy?.actorName, props.draft?.startedBy?.actor) + ': '
    + labelOf(NEW_VERSION_REASON_LABELS, props.draft?.reasonCategory))

const jobText = computed(() => {
    const job = props.draft?.job ?? {}
    switch (job.state) {
    case 'NONE': return STALE_NOT_BUILT
    case 'REQUESTED': return 'Rebuild requested on ' + submissionDateText(job.requestedAt) + ' by '
        + submissionActorText(job.requestedBy?.actorName, job.requestedBy?.actor)
    case 'RUNNING': return 'Rebuilding since ' + submissionDateText(job.startedAt) + ' (attempt ' + (job.attempt ?? '') + ')'
    case 'BUILT': return 'Built on ' + submissionDateText(props.draft?.build?.builtAt)
    case 'FAILED': return 'Rebuild failed on ' + submissionDateText(job.finishedAt)
    default: return labelOf(DRAFT_JOB_STATE_LABELS, job.state)
    }
})

const stale = computed(() => draftStaleBanner(props.draft))
const staleType = computed(() => stale.value.kind === 'changed' ? 'warning' : (stale.value.kind === 'unchanged' ? 'success' : 'default'))
const diffSections = computed(() => draftDiffSections(props.draft?.build?.diff))
const partRows = computed(() => draftPartRows(props.draft))
const confirmationLine = computed(() => draftConfirmationLine(props.draft, props.secondConfirmer))
const buttons = computed(() => draftButtons(props.draft, { secondConfirmer: props.secondConfirmer, writable: props.isWritable,
    busy: props.busy, currentUser: props.currentUser, lifecycle: props.lifecycle, head: props.head }))
const anyButton = computed(() => Object.values(buttons.value).some(b => b.shown))

// The draft zip (FDAW-122): offered at job BUILT, to everyone who sees the card; the server decides who gets it.
const zipOffered = computed(() => draftZipOffered(props.draft))
const zipBusy = ref(false)
const zipMessage = ref('')
// The message is about one draft. It stays when the panel reads that draft again (a 409 asks for that read at
// once), and goes when the card is given another draft.
watch(() => props.draft?.uuid, () => { zipMessage.value = '' })

/** One request; a refusal stays on the card as text, and a 409 has the panel read the draft again. */
async function downloadZip () {
    if (zipBusy.value || !zipOffered.value) return
    zipBusy.value = true
    zipMessage.value = ''
    const asked = props.draft.uuid
    try {
        const result = await fetchPackageFile('draft-zip', draftZipUrl(asked),
            packageFallbackName('draft-zip', props.draft.plannedVersion), { writable: props.isWritable })
        if (result.ok) saveFile(result.blob, result.fileName)
        else if (props.draft?.uuid === asked) {
            // said only on the card of the draft that was asked for
            zipMessage.value = result.message
            if (result.status === 409) emit('refresh')
        }
    } catch (err: any) {
        // the browser refused the save: a constant sentence, never the thrown text
        console.error(err)
        if (props.draft?.uuid === asked) zipMessage.value = DOWNLOAD_NOT_ASKED
    } finally {
        zipBusy.value = false
    }
}

/** A part's raw download, named by the server's Content-Disposition (cleaned), else the fallback name. */
async function download (part: DraftPartDownload) {
    try {
        const file = await downloadArtifactFile(artifactDownloadUrl(part.uuid, true))
        saveBlob(file.bytes, contentDispositionFileName(file.disposition, part.fallbackName), file.mediaType || undefined)
    } catch (err: any) {
        console.error(err)
        notification.error({ content: 'Download failed: ' + String(err?.message ?? err), duration: 5000 })
    }
}
</script>

<style scoped>
.submission-draft-card {
    border: 1px solid var(--n-border-color, #e0e0e6);
    border-radius: 4px;
    padding: 8px;
    margin-bottom: 0.5rem;
}
.draft-heading {
    font-weight: 600;
    margin-bottom: 4px;
}
.draft-line {
    margin-top: 4px;
}
.draft-reason {
    white-space: pre-wrap;
}
.draft-stale-lines {
    margin: 4px 0 0 1rem;
}
.draft-diff-line {
    margin-left: 1rem;
    word-break: break-all;
}
.draft-parts {
    margin-top: 6px;
    border-collapse: collapse;
}
.draft-parts th, .draft-parts td {
    padding: 2px 8px;
    text-align: left;
}
.draft-actions {
    margin-top: 6px;
}
</style>
