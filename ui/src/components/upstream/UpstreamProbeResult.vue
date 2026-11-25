<template>
    <div class="upstream-probe-result" data-testid="upstream-probe-result">
        <span v-if="!probe" data-testid="upstream-probe-never">never probed</span>
        <span v-else-if="probe.state === 'PENDING'" data-testid="upstream-probe-pending">
            <n-spin :size="12" class="upstream-probe-spin" />
            {{ probeStateLabel.PENDING }}
        </span>
        <div v-else data-testid="upstream-probe-done">
            <n-tag size="small" :type="tagType" data-testid="upstream-probe-tag">{{ resultLabel }}</n-tag>
            <span v-if="probe.message" class="upstream-probe-message" data-testid="upstream-probe-message">{{ probe.message }}</span>
            <div v-for="hint in hints" :key="hint" class="upstream-probe-hint" data-testid="upstream-probe-hint">{{ hint }}</div>
            <div v-if="probe.finishedDate" class="upstream-probe-date" data-testid="upstream-probe-date">{{ dateOf(probe.finishedDate) }}</div>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The probe of an upstream source in words (TPF-185 design 3.6): the result class as a tag, the
// backend's fixed sentence, the two hints the UI adds, and the date. Nothing else of a probe is
// read: the backend serves no address, status code or header, and this component shows none of
// whatever else an object might carry. Reused for the sourceFailure of a sync, a preparation and
// a one-off.
import { computed } from 'vue'
import { NSpin, NTag } from 'naive-ui'
import commonFunctions from '@/utils/commonFunctions'
import { labelOf, probeApiKeyHint, probeResultLabel, probeStateLabel, probeTimeoutHint } from '@/utils/upstreamLabels'

interface UpstreamProbeShown {
    state: string
    result?: string | null
    message?: string | null
    finishedDate?: string | null
}

const props = defineProps<{
    probe: UpstreamProbeShown | null | undefined
    credentialKind?: string | null
}>()

const WARNING_RESULTS = ['NOT_RUN', 'NOT_COMPLETED', 'FETCHER_BUSY', 'FETCHER_NOT_AVAILABLE', 'FETCHER_NOT_CONFIGURED']

const result = computed((): string => props.probe?.result ?? '')

const tagType = computed((): 'success' | 'warning' | 'error' => {
    if (result.value === 'REACHABLE') return 'success'
    if (WARNING_RESULTS.includes(result.value)) return 'warning'
    return 'error'
})

const resultLabel = computed((): string => labelOf(probeResultLabel, result.value))

const hints = computed((): string[] => {
    const out: string[] = []
    if (result.value === 'TIMED_OUT') out.push(probeTimeoutHint)
    if (result.value === 'AUTHENTICATION_FAILED' && props.credentialKind === 'API_KEY') out.push(probeApiKeyHint)
    return out
})

function dateOf (d: string): string {
    return commonFunctions.dateDisplay(d)
}
</script>

<style scoped>
.upstream-probe-message {
    margin-left: 6px;
}
.upstream-probe-hint {
    font-size: 0.9em;
    margin-top: 2px;
}
.upstream-probe-date {
    font-size: 0.85em;
    color: #888;
}
.upstream-probe-spin {
    vertical-align: middle;
    margin-right: 4px;
}
</style>
