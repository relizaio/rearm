<template>
    <div class="sbom-score-panel" data-testid="sbom-score-panel">
        <div v-if="state === 'loading'" class="sbom-score-loading" data-testid="sbom-score-loading">
            <n-spin size="small" />
            <span>Scoring… {{ elapsedSeconds }}s</span>
            <div v-if="elapsedSeconds >= SLOW_HINT_SECONDS" class="sbom-score-slow-hint">Large SBOMs can take up to 3 minutes</div>
        </div>
        <template v-else>
            <n-space v-if="raw !== null" size="small" class="sbom-score-actions">
                <n-button size="small" data-testid="sbom-score-download" @click="downloadJson">Download JSON</n-button>
                <n-button size="small" data-testid="sbom-score-copy" @click="copyJson">Copy JSON</n-button>
            </n-space>
            <n-alert v-if="error" type="error" :show-icon="false" class="sbom-score-alert" data-testid="sbom-score-error">
                {{ error.message }}
                <div v-if="error.retryable" class="sbom-score-retry">
                    <n-button size="small" data-testid="sbom-score-retry" @click="run">Retry</n-button>
                </div>
            </n-alert>
            <sbom-score-report v-if="report" :report="report" />
        </template>
    </div>
</template>

<script lang="ts" setup>
// Runs one SBOM score (SCORE-6) and shows what came back: a spinner with the elapsed time while
// it runs, then the report, or the one line that says why there is none. The caller owns the
// query (load); the panel owns the timer, the 185 s abort and the raw-report actions. Mounted
// fresh for each score: closing the dialog or unmounting aborts the request and drops it.
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { NAlert, NButton, NSpace, NSpin, useNotification } from 'naive-ui'
import SbomScoreReport from './SbomScoreReport.vue'
import { SBOM_SCORE_ABORT_MS, SBOM_SCORE_TIMEOUT_MESSAGE, SbomScoreParseError, classifySbomScoreError,
    downloadRawReport, parseSbomScoreReport, rawReportFileName } from '@/utils/sbomScore'
import type { SbomScoreErrorView, SbomScoreFileKind, SbomScoreReport as Report } from '@/utils/sbomScore'

const props = defineProps<{
    // Runs the score query; resolves with the report string as the server returned it.
    load: (signal: AbortSignal) => Promise<string>
    fileKind: SbomScoreFileKind
    fileId: string
    pending?: boolean
}>()
const emit = defineEmits<{ (e: 'update:pending', value: boolean): void }>()

const notification = useNotification()

const SLOW_HINT_SECONDS = 30

const state = ref<'loading' | 'done'>('loading')
const elapsedSeconds = ref(0)
const raw = ref<string | null>(null)
const report = shallowRef<Report | null>(null)
const error = ref<SbomScoreErrorView | null>(null)

let controller: AbortController | null = null
let ticker: ReturnType<typeof setInterval> | null = null
let deadline: ReturnType<typeof setTimeout> | null = null
// Each run's number; a run that settles after a newer one started, or after it was stopped, is dropped.
let currentRun = 0

function stopTimers () {
    if (ticker !== null) clearInterval(ticker)
    if (deadline !== null) clearTimeout(deadline)
    ticker = null
    deadline = null
}

function settle () {
    stopTimers()
    controller = null
    state.value = 'done'
    emit('update:pending', false)
}

async function run () {
    const runNo = ++currentRun
    stopTimers()
    controller = new AbortController()
    const signal = controller.signal
    raw.value = null
    report.value = null
    error.value = null
    elapsedSeconds.value = 0
    state.value = 'loading'
    emit('update:pending', true)
    ticker = setInterval(() => { elapsedSeconds.value++ }, 1000)
    deadline = setTimeout(() => {
        if (runNo !== currentRun) return
        controller?.abort()
        currentRun++
        error.value = { kind: 'TIMEOUT', reason: 'TIMEOUT', message: SBOM_SCORE_TIMEOUT_MESSAGE, retryable: true }
        settle()
    }, SBOM_SCORE_ABORT_MS)
    try {
        const text = await props.load(signal)
        if (runNo !== currentRun) return
        raw.value = typeof text === 'string' ? text : null
        if (raw.value === null) throw new Error('the server returned no report')
        report.value = parseSbomScoreReport(raw.value)
    } catch (err: any) {
        if (runNo !== currentRun) return
        // A report this UI cannot read is still offered for download, so raw stays.
        if (!(err instanceof SbomScoreParseError)) raw.value = null
        error.value = classifySbomScoreError(err)
    }
    settle()
}

function downloadJson () {
    if (raw.value === null) return
    downloadRawReport(raw.value, rawReportFileName(props.fileKind, props.fileId))
}

async function copyJson () {
    if (raw.value === null) return
    // navigator.clipboard exists only on secure origins; a plain-HTTP install has none.
    if (!navigator.clipboard?.writeText) {
        notification.warning({ title: 'Copy unavailable', content: 'Use Download JSON instead.', duration: 4000 })
        return
    }
    try {
        await navigator.clipboard.writeText(raw.value)
        notification.success({ title: 'Report copied', duration: 2000 })
    } catch (e: any) {
        notification.error({ title: 'Copy failed', content: e?.message || 'Could not copy to clipboard', duration: 4000 })
    }
}

onMounted(run)

onBeforeUnmount(() => {
    currentRun++
    controller?.abort()
    controller = null
    stopTimers()
    emit('update:pending', false)
})

defineExpose({ run })
</script>

<style scoped>
.sbom-score-loading {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    padding: 8px 0;
}
.sbom-score-slow-hint {
    width: 100%;
    font-size: 12px;
    color: #888;
}
.sbom-score-actions {
    margin-bottom: 8px;
}
.sbom-score-alert {
    margin-bottom: 8px;
}
.sbom-score-retry {
    margin-top: 6px;
}
</style>
