<template>
    <div class="release-submissions-panel">
        <div v-if="loadError" class="text-danger">
            <span data-testid="submissions-load-error">{{ loadError }}</span>
            <n-button size="tiny" style="margin-left: 8px;" data-testid="submissions-retry" @click="read">Retry</n-button>
        </div>
        <p v-else-if="loaded && !heads.length && !drafts.length" data-testid="submissions-empty" class="text-muted">No submission of this release yet</p>

        <div v-for="k in kindList" :key="k" class="submission-kind" :data-testid="'submission-kind-' + k">
            <div :data-testid="'submission-head-' + k" class="submission-head">
                <strong>{{ packageKindLabel(k) }}</strong>
                <template v-if="headOf(k)">
                    <n-tag size="small" :type="submissionStateTagType(headOf(k).state)" data-testid="submission-state"
                        style="margin-left: 8px;">{{ submissionStateText(headOf(k)) }}</n-tag>
                    <span style="margin-left: 8px;">v{{ headOf(k).latestVersion }}</span>
                    <span class="text-muted" style="margin-left: 8px;">
                        {{ submissionLastActWord(headOf(k)) }} on {{ dateText(headOf(k).lastActDate) }}
                        by <span data-testid="submission-actor" :title="headOf(k).lastActBy ?? ''">{{
                            submissionActorText(headOf(k).lastActByName, headOf(k).lastActBy) }}</span>
                    </span>
                    <div v-if="submissionCancelNote(headOf(k))" class="submission-withdrawn">
                        {{ submissionCancelNote(headOf(k))?.lead }}
                        <span data-testid="submission-withdraw-reason" style="white-space: pre-wrap;">{{ submissionCancelNote(headOf(k))?.reason }}</span>
                    </div>
                </template>
                <span v-else class="text-muted" style="margin-left: 8px;">No submission</span>
                <n-space v-if="isWritable" class="submission-actions">
                    <n-button v-if="!draftOf(k)" size="tiny" data-testid="draft-start" :loading="acts.busy.value"
                        :disabled="acts.busy.value || !canCreate" :title="canCreate ? '' : CREATE_LIFECYCLE_REASON"
                        @click="startDraft(k)">Start draft</n-button>
                    <n-button v-if="headOf(k)?.state === 'FINAL'" size="tiny" type="error" data-testid="submission-cancel"
                        :disabled="acts.busy.value" @click="acts.cancel(headOf(k), actNames)">Cancel</n-button>
                    <n-button v-if="shareState(k).shown" size="tiny" data-testid="submission-share"
                        :disabled="shareState(k).disabled" :title="shareState(k).title" @click="share(k)">{{ TEA_SHARE_BUTTON }}</n-button>
                </n-space>
            </div>
            <!-- The draft of this kind's next version (FDAW-32 3.6): one card per kind, under its head. -->
            <SubmissionDraftCard v-if="draftOf(k)" :draft="draftOf(k)" :second-confirmer="secondConfirmer" :is-writable="isWritable"
                :busy="acts.busy.value" :still-running="stillRunning(draftOf(k))" :names="actNames" :current-user="currentUser"
                :lifecycle="lifecycle" :head="headOf(k)" @rebuild="acts.rebuild(draftOf(k))" @discard="acts.discard(draftOf(k), actNames)"
                @confirm="acts.confirm(draftOf(k), actNames)" @return="acts.returnToEditing(draftOf(k), actNames)"
                @finalize="acts.finalize(draftOf(k), actNames, secondConfirmer)" @refresh="read" />
            <!-- The newest version's parts, downloads and manifest in the open, without the chevron
                 (task FDAW-4, B13): after Finalize the re-read moves it to the new version. -->
            <div v-if="latestOf(k)" class="submission-latest" :data-testid="'submission-latest-' + k">
                <div class="submission-latest-heading">Latest: v{{ latestOf(k).version }}</div>
                <VersionDetail :kind="k" :version="latestOf(k)" />
            </div>
            <n-data-table v-if="headOf(k)" :data-testid="'submission-versions-' + k" :columns="versionColumns(k)"
                :data="versionRows(k)" :row-key="versionKey" :expanded-row-keys="expanded[k] ?? []"
                :pagination="false" :bordered="false" size="small" @update:expanded-row-keys="(keys: any) => expanded[k] = keys" />
        </div>

        <!-- The assessment narrative a package of a NARRATIVE kind carries, chosen before the Start form opens
             (FDAW-32 3.6); the form follows the write. -->
        <n-modal :show="pending !== null" preset="card" title="Assessment narrative" style="max-width: 720px"
            data-testid="narrative-prompt" :mask-closable="!narrativeSaving" @update:show="(v: boolean) => { if (!v) closePrompt() }">
            <div v-if="pending" data-testid="narrative-form">
                <p class="text-muted">
                    The {{ packageKindLabel(pending.kind) }} package carries the release's assessment narrative.
                    Choose the one this draft uses.
                </p>
                <n-radio-group data-testid="narrative-choice" v-model:value="narrativeChoice" :disabled="narrativeSaving">
                    <n-space vertical>
                        <n-radio :value="NARRATIVE_CHOICE_ORGANIZATION" :disabled="!hasOrgNarrative"
                            data-testid="narrative-choice-organization" label="Use the organization default" />
                        <div class="narrative-block">
                            <n-input v-if="hasOrgNarrative" data-testid="narrative-org-default" type="textarea" :rows="3"
                                readonly :value="orgNarrative ?? ''" />
                            <span v-else data-testid="narrative-no-org-default" class="text-muted">{{ NO_ORG_NARRATIVE }}</span>
                        </div>
                        <n-radio :value="NARRATIVE_CHOICE_RELEASE" data-testid="narrative-choice-release" label="Write one for this release" />
                        <div v-if="narrativeChoice === NARRATIVE_CHOICE_RELEASE" class="narrative-block">
                            <n-input data-testid="narrative-text" v-model:value="narrativeText" type="textarea" :rows="5"
                                :maxlength="FDA_PROSE_MAX_LENGTH" show-count :disabled="narrativeSaving"
                                @update:value="narrativeSaveError = null" />
                        </div>
                    </n-space>
                </n-radio-group>
                <div v-if="narrativeProblem" data-testid="narrative-problem" class="text-muted narrative-line">{{ narrativeProblem }}</div>
                <n-alert v-if="narrativeSaveError" data-testid="narrative-save-error" type="error" :show-icon="true" class="narrative-line">
                    {{ narrativeSaveError }}
                </n-alert>
            </div>
            <template #footer>
                <n-space justify="end">
                    <n-button data-testid="narrative-cancel" :disabled="narrativeSaving" @click="closePrompt">Cancel</n-button>
                    <n-button data-testid="narrative-confirm" type="primary" :loading="narrativeSaving"
                        :disabled="Boolean(narrativeProblem) || narrativeSaving" @click="confirmPrompt">Continue</n-button>
                </n-space>
            </template>
        </n-modal>

        <!-- The Start form (FDAW-32 3.6): the reason and an optional replaced version; opening it sends nothing. -->
        <n-modal :show="startForm !== null" preset="card" title="Start draft" style="max-width: 640px" data-testid="draft-start-modal"
            :mask-closable="!acts.busy.value" @update:show="(v: boolean) => { if (!v) closeStartForm() }">
            <div v-if="startForm" data-testid="draft-start-form">
                <n-space vertical>
                    <n-select data-testid="draft-reason-category" v-model:value="startForm.reasonCategory" :options="startForm.reasonOptions"
                        :disabled="!startForm.hasHead" placeholder="Reason" />
                    <n-input data-testid="draft-reason-text" v-model:value="startForm.reasonText" type="textarea" :rows="3"
                        :maxlength="SUBMISSION_REASON_MAX" show-count placeholder="Reason text" />
                    <n-select v-if="startForm.replacesOptions !== null" data-testid="draft-replaces" v-model:value="startForm.replaces"
                        :options="startForm.replacesOptions" clearable placeholder="Replaces (optional)" />
                    <span v-else-if="startForm.replacesError" data-testid="draft-replaces-error" class="text-muted">{{ REPLACES_UNREAD }}</span>
                    <div v-if="startProblem" data-testid="draft-start-problem" class="text-muted">{{ startProblem }}</div>
                </n-space>
            </div>
            <template #footer>
                <n-space justify="end">
                    <n-button data-testid="draft-start-cancel" :disabled="acts.busy.value" @click="closeStartForm">Cancel</n-button>
                    <n-button data-testid="draft-start-confirm" type="primary" :loading="acts.busy.value"
                        :disabled="Boolean(startProblem) || acts.busy.value" @click="confirmStartForm">Start draft</n-button>
                </n-space>
            </template>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
// The release page's Regulatory submissions section (task TEA-30, design 3.5.4), Pro only, and the package
// page of the draft flow (task FDAW-32 3.6, 3.7): per kind the head, Start draft and Cancel, the draft card
// with its five acts, and the version history with each version's parts, record lines and manifest. The
// kinds are the host's (Release.regulatoryPackageKinds: switched on for the component, plus any the
// release holds a submission of), in that order; without them, the whole packageKinds catalog, which
// is read either way for its parts: a kind with a REQUIRED NARRATIVE part asks for the release's
// assessment narrative (the organization default or one for this release) before the Start form, and
// writes it first. While a draft is shown the panel polls it (3.7). Reasons,
// file names and the manifest are text bindings; downloads go through the one URL builder with the
// record's uuid and integer version (design 3.5.2).
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { NAlert, NButton, NDataTable, NInput, NModal, NRadio, NRadioGroup, NSelect, NSpace, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import submissionQueries from '@/utils/submissionQueries'
import { graphqlErrorEntries, graphqlRefusalText } from '@/utils/graphqlErrorText'
import { packageKindLabel } from '@/utils/packageSettings'
import { labelOrHumanized, REQUIREMENT_LABELS } from '@/utils/fdaLabels'
import {
    CREATE_LIFECYCLE_REASON, FIRST_VERSION_REASON, SUBMISSION_REASON_MAX, canCreateSubmission,
    manifestDownloadRef, submissionActorText, submissionCancelNote, submissionLastActWord, submissionPartRows, submissionStateTagType,
    submissionStateText, submissionVersionRows, componentTypeWord,
} from '@/utils/submissions'
import {
    DRAFT_NOT_FOUND, DRAFT_POLL_FAST_MAX, DRAFT_POLL_SLOW_MS, REPLACES_UNREAD, confirmationRecordLine, draftJobInFlight,
    draftPollDelay, draftReasonOptions, draftReplacesOptions, draftStartProblem, draftStillRunning, secondConfirmerOf,
} from '@/utils/submissionDrafts'
import { ArtifactDownloadRef, artifactDownloadUrl, downloadArtifactBytes, saveBlob } from '@/utils/artifactDownload'
import { useSubmissionActs } from '@/utils/submissionActs'
import { FDA_PROSE_MAX_LENGTH } from '@/utils/fdaProseInput'
import { TEA_SHARE_BUTTON, teaShareButtonState, type TeaShare } from '@/utils/teaPublication'
import {
    NARRATIVE_CHOICE_ORGANIZATION, NARRATIVE_CHOICE_RELEASE, NO_ORG_NARRATIVE, NarrativeChoice, UPDATE_RELEASE_NARRATIVE_GQL,
    kindNeedsNarrative, narrativeChoiceError, narrativeChoiceVariables, narrativeInitialChoice, narrativeInitialText,
} from '@/utils/submissionNarrative'
import SubmissionDraftCard from './SubmissionDraftCard.vue'

const props = withDefaults(defineProps<{
    orgUuid?: string | null
    releaseUuid?: string | null
    releaseVersion?: string | null
    lifecycle?: string | null
    componentUuid?: string | null
    componentName?: string | null
    kinds?: string[] | null
    isWritable?: boolean
    installationType?: string
    componentType?: string | null
    names?: Record<string, string>
    narrativeOverride?: string | null
    orgNarrative?: string | null
    teaView?: any
    teaActing?: boolean
    currentUser?: string | null
}>(), {
    orgUuid: null,
    releaseUuid: null,
    releaseVersion: null,
    lifecycle: null,
    componentUuid: null,
    componentName: null,
    kinds: null,
    isWritable: false,
    installationType: 'OSS',
    componentType: null,
    names: () => ({}),
    narrativeOverride: null,
    orgNarrative: null,
    teaView: null,
    teaActing: false,
    currentUser: null,
})

const emit = defineEmits<{ (e: 'narrative-saved', narrative: string): void, (e: 'share', share: TeaShare): void }>()

const NOT_DOWNLOADABLE = 'not downloadable: no artifact version recorded'

const notification = useNotification()
const catalogKinds = ref<any[]>([])
const heads = ref<any[]>([])
const loaded = ref(false)
const loadError = ref<string | null>(null)
const expanded = reactive<Record<string, any[]>>({})
const shownManifests = reactive<Record<string, boolean>>({})

const acts = useSubmissionActs({ client: graphqlClient, reload: () => read() })

// The drafts of this release and the organization's second-confirmer setting as the page read it: true or
// false when the answer holds this organization, null when the read failed or it is not in the answer (3.6).
const drafts = ref<any[]>([])
const secondConfirmer = ref<boolean | null>(null)

const kindList = computed((): string[] => {
    const list = props.kinds ? [...props.kinds] : catalogKinds.value.map((k: any) => k.kind)
    for (const hd of heads.value) if (hd?.kind && !list.includes(hd.kind)) list.push(hd.kind)
    for (const d of drafts.value) if (d?.kind && !list.includes(d.kind)) list.push(d.kind)
    return list
})
const canCreate = computed(() => canCreateSubmission(props.lifecycle))
const actNames = computed(() => ({ componentName: props.componentName, releaseVersion: props.releaseVersion,
    componentType: props.componentType }))
const versionKey = (r: any) => r.version

// The assessment-narrative prompt (any release): open for a kind whose catalog entry has a REQUIRED
// NARRATIVE part; the release's stored override is held here too, so a second act right after a
// write starts from it before the host's refresh lands.
const pending = ref<{ act: 'start', kind: string, head: any } | null>(null)
const narrativeChoice = ref<NarrativeChoice | null>(null)
const narrativeText = ref('')
const narrativeSaving = ref(false)
const narrativeSaveError = ref<string | null>(null)
const storedOverride = ref<string | null>(props.narrativeOverride)
watch(() => props.narrativeOverride, (v) => { storedOverride.value = v })
const hasOrgNarrative = computed(() => Boolean((props.orgNarrative || '').trim()))
const narrativeProblem = computed(() => pending.value
    ? narrativeChoiceError(narrativeChoice.value, narrativeText.value, props.orgNarrative) : null)

function needsNarrative (kind: string): boolean {
    return kindNeedsNarrative(catalogKinds.value.find((k: any) => k?.kind === kind))
}

function openPrompt (act: 'start', kind: string, head: any) {
    narrativeChoice.value = narrativeInitialChoice(storedOverride.value, props.orgNarrative)
    narrativeText.value = narrativeInitialText(storedOverride.value, props.orgNarrative)
    narrativeSaveError.value = null
    pending.value = { act, kind, head }
}

function closePrompt () {
    if (narrativeSaving.value) return
    pending.value = null
}

/** Start draft: the narrative prompt first for a kind that needs it, then the Start form. */
function startDraft (kind: string) {
    if (needsNarrative(kind)) openPrompt('start', kind, headOf(kind))
    else openStartForm(kind)
}

// The Start form: the reason category (FIRST_VERSION alone without a head), the reason text, and the
// replaceable versions of the component's other releases, read when the form opens (3.6).
const startForm = ref<{
    kind: string, hasHead: boolean, reasonOptions: { label: string, value: string }[], reasonCategory: string | null,
    reasonText: string, replaces: string | null, replacesOptions: { label: string, value: string }[] | null, replacesError: boolean,
} | null>(null)
const startProblem = computed(() => startForm.value
    ? draftStartProblem(startForm.value.reasonCategory, startForm.value.reasonText) : null)

function openStartForm (kind: string) {
    const hasHead = Boolean(headOf(kind))
    const form = reactive({ kind, hasHead, reasonOptions: draftReasonOptions(hasHead),
        reasonCategory: hasHead ? null : FIRST_VERSION_REASON as string | null, reasonText: '', replaces: null as string | null,
        replacesOptions: null as { label: string, value: string }[] | null, replacesError: false })
    startForm.value = form
    readReplaceable(form)
}

async function readReplaceable (form: any) {
    try {
        const resp: any = await graphqlClient.query({ query: submissionQueries.SubmissionsOfComponentGql,
            variables: { component: props.componentUuid, kind: form.kind, state: null }, fetchPolicy: 'no-cache' })
        form.replacesOptions = draftReplacesOptions(resp?.data?.submissionsOfComponent ?? [], props.releaseUuid as string)
    } catch (err: any) {
        console.error(err)
        form.replacesError = true
    }
}

function closeStartForm () {
    if (acts.busy.value) return
    startForm.value = null
}

async function confirmStartForm () {
    const form = startForm.value
    if (!form || startProblem.value || acts.busy.value) return
    const started = await acts.start(props.releaseUuid as string, form.kind, { reasonCategory: form.reasonCategory as string,
        reasonText: form.reasonText, replaces: form.replaces })
    if (started && startForm.value === form) startForm.value = null
}

/** Writes the chosen narrative when it changes the release; false (the error shown) when the write fails. */
async function saveNarrativeChoice (): Promise<boolean> {
    const vars = narrativeChoiceVariables(props.releaseUuid as string, props.orgUuid as string,
        narrativeChoice.value as NarrativeChoice, narrativeText.value, storedOverride.value)
    if (!vars) return true
    narrativeSaving.value = true
    try {
        const resp: any = await graphqlClient.mutate({ mutation: UPDATE_RELEASE_NARRATIVE_GQL, variables: { release: vars } })
        const saved = resp?.data?.updateRelease?.fdaAssessmentNarrative ?? (vars.fdaAssessmentNarrative as string)
        storedOverride.value = saved || null
        emit('narrative-saved', saved || '')
        return true
    } catch (err: any) {
        console.error(err)
        narrativeSaveError.value = graphqlRefusalText(err)
        return false
    } finally {
        narrativeSaving.value = false
    }
}

async function confirmPrompt () {
    const p = pending.value
    if (!p || narrativeProblem.value || narrativeSaving.value) return
    if (!await saveNarrativeChoice()) return
    pending.value = null
    openStartForm(p.kind)
}

function headOf (kind: string): any {
    return heads.value.find((hd: any) => hd?.kind === kind) ?? null
}

function draftOf (kind: string): any {
    return drafts.value.find((d: any) => d?.kind === kind) ?? null
}

/** The head's newest version: the one at latestVersion, else the highest the head lists. */
function latestOf (kind: string): any {
    const versions: any[] = headOf(kind)?.versions ?? []
    if (!versions.length) return null
    const latest = headOf(kind)?.latestVersion
    return versions.find((v: any) => v?.version === latest)
        ?? versions.reduce((a: any, b: any) => ((b?.version ?? 0) > (a?.version ?? 0) ? b : a))
}

/** Share with customers on the head of a kind (FDAW-39 3.8): shown from Final onward while the release's TEA settings publish. */
function shareState (kind: string): { shown: boolean, disabled: boolean, title: string } {
    return teaShareButtonState(props.teaView, headOf(kind), { writable: props.isWritable,
        busy: props.teaActing || acts.busy.value })
}

/** The share click: the release page runs the TEA publish or re-publish with this head's facts. */
function share (kind: string) {
    if (shareState(kind).disabled) return
    const head = headOf(kind)
    emit('share', { kind, latestVersion: head?.latestVersion, state: head?.state,
        profileName: latestOf(kind)?.sbomProfile?.name ?? null })
}

function versionRows (kind: string): any[] {
    return submissionVersionRows(headOf(kind), props.names, componentTypeWord(props.componentType))
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
        ? h('span', { title: full, 'data-testid': 'submission-sha256' }, [short, ' ', h(NButton, { size: 'tiny', quaternary: true, 'aria-label': 'Copy sha256',
            onClick: () => copy(full) }, () => 'copy')])
        : ''
}

const partColumns = [
    { key: 'label', title: 'Part' },
    { key: 'requirement', title: 'Requirement', render: (r: any) => labelOrHumanized(REQUIREMENT_LABELS, r.requirement) },
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

/** One version's parts, downloads and manifest: the inline newest block and each history row's expansion. */
function versionDetail (kind: string, v: any) {
    const key = kind + ':' + v?.version
    const manifest = manifestDownloadRef(v)
    return h('div', { class: 'submission-version-detail' }, [
        h(NDataTable, { 'data-testid': 'submission-parts-' + kind + '-' + v?.version, columns: partColumns,
            data: submissionPartRows(v), rowKey: (r: any) => r.key, size: 'small', pagination: false, bordered: false }),
        ...(v?.confirmations ?? []).map((c: any) => h('div', { 'data-testid': 'submission-confirmation', class: 'submission-confirmation' },
            confirmationRecordLine(c))),
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

// The template's handle on versionDetail; defined once, so the inline block is not remounted per render.
const VersionDetail = (p: { kind: string, version: any }) => versionDetail(p.kind, p.version)

function versionColumns (kind: string) {
    return [
        { type: 'expand' as const, renderExpand: (row: any) => versionDetail(kind, row.versionObject) },
        { key: 'version', title: 'Version', render: (r: any) => 'v' + r.version },
        { key: 'createdDate', title: 'Created', render: (r: any) => dateText(r.createdDate) },
        { key: 'createdBy', title: 'By', render: (r: any) => r.createdBy
            ? h('span', { 'data-testid': 'submission-actor', title: r.createdBy }, submissionActorText(r.createdByName, r.createdBy))
            : '' },
        { key: 'reason', title: 'Reason', render: (r: any) => h('span', { 'data-testid': 'submission-reason', style: 'white-space: pre-wrap;' }, r.reason) },
        { key: 'settingsLabel', title: 'Settings' },
        { key: 'profileLabel', title: 'SBOM profile' },
        { key: 'parts', title: 'Parts', render: (r: any) => r.producedCount + ' generated, ' + r.skippedCount + ' skipped of ' + r.partCount },
        { key: 'manifestSha256', title: 'Manifest sha256', render: (r: any) => shaCell(r.manifestSha256, r.manifestSha256Short) },
    ]
}

/** The setting held for this organization: true or false when the answer holds it, else null (3.6). */
function settingOf (resp: any): boolean | null {
    return secondConfirmerOf(resp, props.orgUuid)
}

function readSetting () {
    return graphqlClient.query({ query: submissionQueries.OrgSecondConfirmerGql, fetchPolicy: 'no-cache' })
}

async function read () {
    if (!props.releaseUuid) return
    clearPoll()
    loadError.value = null
    try {
        // The catalog always: its parts tell which kinds carry the assessment narrative. With the
        // host's kinds the rows are those, and a failed catalog read only loses the narrative prompt.
        // The drafts are required like the heads; a failed setting read is the setting not read.
        const kindsRead = graphqlClient.query({ query: profilesQueries.PackageKindsGql, fetchPolicy: 'no-cache' })
        const [k, s, d, o]: any[] = await Promise.all([
            props.kinds ? kindsRead.catch((err: any) => { console.error(err); return null }) : kindsRead,
            graphqlClient.query({ query: submissionQueries.SubmissionsOfReleaseGql, variables: { release: props.releaseUuid },
                fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: submissionQueries.ReleaseDraftsGql, variables: { releaseID: props.releaseUuid,
                orgID: props.orgUuid }, fetchPolicy: 'no-cache' }),
            readSetting().catch((err: any) => { console.error(err); return null }),
        ])
        if (unmounted) return
        if (k) catalogKinds.value = k.data.packageKinds ?? []
        heads.value = s.data.submissionsOfRelease ?? []
        drafts.value = d?.data?.release?.drafts ?? []
        secondConfirmer.value = settingOf(o)
        loaded.value = true
        const now = Date.now()
        for (const key of Object.keys(fastPolls)) delete fastPolls[key]
        for (const dr of drafts.value) {
            fastPolls[dr.uuid] = 0
            lastFullRead[dr.uuid] = now
            requestedAtOf[dr.uuid] = dr.job?.requestedAt ?? null
        }
        settingReadAt = now
        schedulePoll()
    } catch (err: any) {
        console.error(err)
        if (unmounted) return
        heads.value = []
        drafts.value = []
        loadError.value = graphqlRefusalText(err)
    }
}

// Polling (3.7): one timer, a chain of setTimeout. A job in flight is read alone every 5 seconds for at
// most DRAFT_POLL_FAST_MAX polls; every other draft, and the setting, every 30 seconds. Nothing is read
// while the browser tab is hidden, an act runs or a form is open, nor after the panel is unmounted, and a
// panel without a draft has no timer. A poll never opens a dialog or shows the load error.
const fastPolls = reactive<Record<string, number>>({})
const lastFullRead: Record<string, number> = {}
const requestedAtOf: Record<string, string | null> = {}
let settingReadAt = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null
let unmounted = false

function inFlight (draft: any): boolean {
    return draftJobInFlight(draft)
}

function stillRunning (draft: any): boolean {
    return draftStillRunning(draft, fastPolls[draft?.uuid] ?? 0)
}

function clearPoll () {
    if (pollTimer !== null) clearTimeout(pollTimer)
    pollTimer = null
}

function schedulePoll () {
    clearPoll()
    if (unmounted || !drafts.value.length) return
    const delay = Math.min(...drafts.value.map((d: any) => draftPollDelay(d, fastPolls[d.uuid] ?? 0)))
    pollTimer = setTimeout(tick, delay)
}

/** A poll that failed: true (a full read owed) for the server's `Draft not found`; anything else is logged. */
function pollFailed (err: any): boolean {
    if (graphqlErrorEntries(err)[0]?.message === DRAFT_NOT_FOUND) return true
    console.error(err)
    return false
}

/** Replaces the held draft of that uuid; its fast polls start again when its job was requested again. */
function replaceDraft (uuid: string, next: any) {
    const i = drafts.value.findIndex((d: any) => d?.uuid === uuid)
    if (i < 0) return
    const requestedAt = next?.job?.requestedAt ?? null
    if (requestedAt !== requestedAtOf[uuid]) {
        fastPolls[uuid] = 0
        requestedAtOf[uuid] = requestedAt
    }
    drafts.value.splice(i, 1, next)
}

function pollJob (uuid: string): Promise<boolean> {
    fastPolls[uuid] = (fastPolls[uuid] ?? 0) + 1
    return graphqlClient.query({ query: submissionQueries.DraftJobGql, variables: { uuid }, fetchPolicy: 'no-cache' })
        .then((resp: any) => {
            const answered = resp?.data?.draft
            if (!answered) return true
            const held = drafts.value.find((d: any) => d?.uuid === uuid)
            if (held) replaceDraft(uuid, { ...held, state: answered.state, revision: answered.revision, job: answered.job })
            return !inFlight(answered)
        }, pollFailed)
}

function pollDraft (uuid: string): Promise<boolean> {
    return graphqlClient.query({ query: submissionQueries.DraftGql, variables: { uuid }, fetchPolicy: 'no-cache' })
        .then((resp: any) => {
            lastFullRead[uuid] = Date.now()
            const answered = resp?.data?.draft
            if (!answered) return true
            replaceDraft(uuid, answered)
            return false
        }, (err: any) => {
            lastFullRead[uuid] = Date.now()
            return pollFailed(err)
        })
}

async function tick () {
    pollTimer = null
    if (unmounted) return
    if (document.hidden || acts.busy.value || startForm.value !== null || pending.value !== null) {
        schedulePoll()
        return
    }
    const now = Date.now()
    const reads: Promise<boolean>[] = []
    for (const draft of [...drafts.value]) {
        if (inFlight(draft) && (fastPolls[draft.uuid] ?? 0) < DRAFT_POLL_FAST_MAX) reads.push(pollJob(draft.uuid))
        else if (now - (lastFullRead[draft.uuid] ?? 0) >= DRAFT_POLL_SLOW_MS) reads.push(pollDraft(draft.uuid))
    }
    const fullReadOwed = (await Promise.all(reads)).some(Boolean)
    if (unmounted) return
    if (!fullReadOwed && Date.now() - settingReadAt >= DRAFT_POLL_SLOW_MS) {
        try {
            secondConfirmer.value = settingOf(await readSetting())
        } catch (err: any) {
            console.error(err)
        }
        settingReadAt = Date.now()
        if (unmounted) return
    }
    if (fullReadOwed) await read()
    else schedulePoll()
}

onMounted(read)
watch(() => props.releaseUuid, () => { clearPoll(); read() })
onBeforeUnmount(() => {
    unmounted = true
    clearPoll()
})

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
.submission-latest {
    margin-bottom: 0.5rem;
}
.submission-latest-heading {
    font-weight: 600;
    margin-bottom: 4px;
}
.submission-manifest-block {
    margin-top: 6px;
}
.narrative-block {
    margin-left: 24px;
}
.narrative-line {
    margin-top: 8px;
}
</style>
