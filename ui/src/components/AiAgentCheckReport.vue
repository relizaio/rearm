<template>
    <!-- The element checks of one document (elements.md §7): a line under the document row, and the
         report table when opened. Re-run asks the board to check again in the current scope. -->
    <div v-if="report" class="chk">
        <div class="chk__line" :class="{ 'chk__line--blocked': summary.blockingFailed.length }" @click="open = !open">
            <span class="chk__label">checks</span>
            <span>{{ summaryLine(summary) }}</span>
            <span v-if="round" class="chk__meta">· report round {{ round }}</span>
            <n-tag v-if="summary.blockingFailed.length" size="tiny" :bordered="false" type="error"
                   title="Hand-over is refused while these fail">blocks: {{ summary.blockingFailed.join(', ') }}</n-tag>
            <n-tag v-if="stale" size="tiny" :bordered="false" type="warning"
                   title="An input has a newer document since this report; re-run to check against it">stale</n-tag>
            <n-button size="tiny" quaternary :loading="running" @click.stop="rerun">Re-run</n-button>
        </div>
        <div v-if="error" class="chk__error">{{ error }}</div>
        <table v-if="open" class="chk__table">
            <tr v-for="r in report.results ?? []" :key="r.check ?? ''">
                <td class="chk__check" :title="describeCheck(catalogue, r.check)"><code>{{ r.check }}</code></td>
                <td><n-tag size="tiny" :bordered="false" :type="resultType(r.result)">{{ r.result }}</n-tag></td>
                <td class="chk__meta">{{ r.blocking ? 'blocking' : '' }}</td>
                <td>
                    <div v-if="r.reason" class="chk__reason">{{ r.reason }}</div>
                    <div v-for="g in offencesByElement(r)" :key="g.elementId" class="chk__off">
                        <a v-if="elementLink(g.elementId)" :href="elementLink(g.elementId) ?? undefined" target="_blank"
                           rel="noopener"><code>{{ g.elementId }}</code></a>
                        <code v-else>{{ g.elementId }}</code>
                        {{ g.messages.join('; ') }}
                    </div>
                </td>
            </tr>
        </table>
    </div>
</template>

<script lang="ts">
let cataloguePromise: Promise<any> | null = null
</script>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { useStore } from 'vuex'
import type { DocumentRelease } from '@/utils/agentDocuments'
import { documentFileUrl } from '@/utils/agentDocuments'
import {
    CatalogueEntry, CheckReport, describeCheck, isStale, latestReportFor, offencesByElement, reportOf, resultType,
    summarise, summaryLine,
} from '@/utils/agentChecks'

const props = defineProps<{
    /** The document the report is about. */
    release: DocumentRelease
    /** The task's documents, newest first; the report rounds are among them. */
    documents: DocumentRelease[]
}>()

const store = useStore()
// The catalogue is static: read once for every report on the page, for the check descriptions.
const catalogue = ref<CatalogueEntry[]>([])
if (!cataloguePromise) cataloguePromise = store.dispatch('fetchCheckCatalogue').catch(() => [])
cataloguePromise.then((c: CatalogueEntry[]) => { catalogue.value = c ?? [] })
const open = ref(false)
const running = ref(false)
const error = ref<string | null>(null)
// A re-run answers with the report's release before the task list refreshes; shown until then.
const fresh = ref<DocumentRelease | null>(null)

const reportRelease = computed(() => fresh.value ?? latestReportFor(props.documents, props.release.uuid))
const report = computed<CheckReport | null>(() => reportOf(reportRelease.value))
const round = computed(() => reportRelease.value?.document?.round ?? null)
const summary = computed(() => summarise(report.value))
const stale = computed(() => isStale(report.value, props.documents))

/** The file at the element's line, when this document defines it and its host is one we can link. */
function elementLink (id: string): string | null {
    const els = (props.release.document as any)?.elements?.elements
    const line = Array.isArray(els) ? els.find((e: any) => e?.id === id)?.line : null
    const file = documentFileUrl(props.release)
    return file ? (typeof line === 'number' ? `${file}#L${line}` : file) : null
}

async function rerun () {
    if (!props.release.uuid) return
    running.value = true
    error.value = null
    try {
        fresh.value = await store.dispatch('runAgentChecks', props.release.uuid)
        open.value = true
    } catch (e: any) {
        error.value = 'Could not re-run the checks: ' + (e?.message ?? e)
    } finally {
        running.value = false
    }
}
</script>

<style scoped lang="scss">
.chk {
    margin: 0 0 4px 12px; font-size: 12px;
    &__line { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; cursor: pointer; }
    &__line--blocked { color: #d03050; }
    &__label { font-weight: 600; }
    &__meta { color: #888; }
    &__error { color: #d03050; }
    &__table { border-collapse: collapse; margin: 4px 0; td { padding: 2px 6px; vertical-align: top; } }
    &__check { white-space: nowrap; }
    &__reason { color: #888; }
    &__off { margin-top: 2px; }
}
</style>
