<template>
    <div class="sbom-score-report">
        <div class="sbom-score-header" data-testid="sbom-score-header">
            <span>
                {{ report.input.format }} {{ report.input.specVersion }} ({{ report.input.serialization }}),
                {{ report.input.components }} components<template v-if="skippedText">&nbsp;{{ skippedText }}</template>
            </span>
            <n-text depth="3" class="sbom-score-engine">{{ report.engine.name }} {{ report.engine.version }}</n-text>
        </div>
        <n-tabs v-model:value="activeProfile" type="segment" data-testid="sbom-score-tabs">
            <n-tab-pane v-for="profile in report.profiles" :key="profile.key" :name="profile.key" :tab="profile.title">
                <div class="sbom-score-profile" :data-testid="`sbom-score-profile-${profile.key}`">
                    <div class="sbom-score-verdict-line">
                        <n-tag :type="verdictTagType(profile.verdict)" data-testid="sbom-score-verdict">{{ verdictText(profile.verdict) }}</n-tag>
                        <strong data-testid="sbom-score-score">{{ scoreText(profile.score) }}</strong>
                        <span data-testid="sbom-score-summary">{{ summaryLine(profile) }}</span>
                    </div>
                    <div class="sbom-score-source">
                        Source:
                        <a v-if="profile.source.url" :href="profile.source.url" target="_blank" rel="noopener" data-testid="sbom-score-source">{{ profile.source.title }}</a>
                        <span v-else data-testid="sbom-score-source">{{ profile.source.title }}</span>
                        <template v-if="profile.source.date">, {{ profile.source.date }}</template>
                    </div>
                    <n-data-table
                        size="small"
                        :columns="columns"
                        :data="orderChecks(profile.checks)"
                        :row-key="rowKey"
                        :row-class-name="rowClassName"
                        data-testid="sbom-score-checks" />
                </div>
            </n-tab-pane>
        </n-tabs>
        <div v-if="engineErrors.length" class="sbom-score-engine-errors" data-testid="sbom-score-engine-errors">
            <strong>Engine errors</strong>
            <ul>
                <li v-for="(e, i) in engineErrors" :key="i">{{ e.check ? `${e.check}: ` : '' }}{{ e.message }}</li>
            </ul>
        </div>
        <n-collapse v-if="report.structure.checks.length" class="sbom-score-structure" data-testid="sbom-score-structure">
            <n-collapse-item :title="`Structure checks (${report.structure.checks.length})`" name="structure">
                <n-data-table
                    size="small"
                    :columns="columns"
                    :data="orderChecks(report.structure.checks)"
                    :row-key="rowKey"
                    :row-class-name="rowClassName"
                    data-testid="sbom-score-structure-checks" />
            </n-collapse-item>
        </n-collapse>
    </div>
</template>

<script lang="ts" setup>
// One parsed SBOM score report (SCORE-6): a tab per profile with its verdict and its checks,
// failed first, and the document's structure checks collapsed below. Presentational only:
// the call, the errors and the raw-report actions live in SbomScorePanel.
import { computed, h, ref, watch } from 'vue'
import type { DataTableColumns } from 'naive-ui'
import { NCollapse, NCollapseItem, NDataTable, NTabPane, NTabs, NTag, NText } from 'naive-ui'
import { orderChecks, summaryLine } from '@/utils/sbomScore'
import type { SbomScoreCheck, SbomScoreReport, SbomScoreStatus, SbomScoreVerdict } from '@/utils/sbomScore'

const props = defineProps<{ report: SbomScoreReport }>()

const activeProfile = ref<string | undefined>(props.report.profiles[0]?.key)
watch(() => props.report, r => { activeProfile.value = r.profiles[0]?.key })

const skippedText = computed((): string => {
    const skipped = props.report.input.componentsSkipped
    if ((typeof skipped === 'number' && skipped > 0) || props.report.options?.skipFiles === true) {
        return `(${skipped ?? 0} file components skipped, not counted)`
    }
    return ''
})

// Every check id the tables show; an error naming none of them is listed on its own.
const checkIds = computed((): Set<string> => new Set([
    ...props.report.profiles.flatMap(p => p.checks.map(c => c.id)),
    ...props.report.structure.checks.map(c => c.id)
]))
const engineErrors = computed(() => props.report.errors.filter(e => !e.check || !checkIds.value.has(e.check)))

function errorMessageOf (checkId: string): string {
    return props.report.errors.find(e => e.check === checkId)?.message || 'engine error'
}

function verdictTagType (verdict: SbomScoreVerdict): 'success' | 'error' | 'default' {
    if (verdict === 'READY') return 'success'
    if (verdict === 'NOT_READY') return 'error'
    return 'default'
}

function scoreText (score: number | null | undefined): string {
    return typeof score === 'number' ? `Score: ${score} / 100` : 'Score: —'
}

function verdictText (verdict: SbomScoreVerdict): string {
    return verdict.replace(/_/g, ' ')
}

const STATUS_TAG: Record<SbomScoreStatus, 'success' | 'error' | 'default' | 'warning'> = {
    PASS: 'success', FAIL: 'error', NOT_ASSESSED: 'default', ERROR: 'warning'
}

function rowKey (row: SbomScoreCheck): string {
    return row.id
}

function rowClassName (row: SbomScoreCheck): string {
    return row.status === 'NOT_ASSESSED' ? 'sbom-score-not-assessed' : ''
}

function hasFailingList (row: SbomScoreCheck): boolean {
    return row.status === 'FAIL' && Array.isArray(row.failing) && row.failing.length > 0
}

// Rendered inside n-data-table cells, so these elements carry no scope id: their styles are :deep.
// The lines under a check's title: why it failed and what to do, why it was not assessed, or
// what the engine said when the check itself broke.
function detailLines (row: SbomScoreCheck): any[] {
    const lines: any[] = []
    if (row.status === 'FAIL') {
        if (row.note) lines.push(h('div', { class: 'sbom-score-note' }, row.note))
        if (row.remedy) lines.push(h('div', { class: 'sbom-score-remedy' }, `Remedy: ${row.remedy}`))
    } else if (row.status === 'NOT_ASSESSED') {
        if (row.note) lines.push(h('div', { class: 'sbom-score-note' }, row.note))
    } else if (row.status === 'ERROR') {
        lines.push(h('div', { class: 'sbom-score-error' }, errorMessageOf(row.id)))
    }
    return lines
}

const columns: DataTableColumns<SbomScoreCheck> = [
    {
        type: 'expand',
        expandable: hasFailingList,
        renderExpand: (row: SbomScoreCheck) => h('div', { class: 'sbom-score-failing', 'data-testid': 'sbom-score-failing' }, [
            h('div', 'Failing:'),
            h('ul', row.failing.map(f => h('li', f))),
            row.failingTruncated ? h('div', { class: 'sbom-score-truncated' }, 'list truncated by the engine') : null
        ])
    },
    {
        key: 'status',
        title: 'Status',
        width: 130,
        render: (row: SbomScoreCheck) => h(NTag, { type: STATUS_TAG[row.status] ?? 'default', size: 'small' },
            () => row.status.replace(/_/g, ' '))
    },
    {
        key: 'title',
        title: 'Check',
        render: (row: SbomScoreCheck) => h('div', [
            h('div', row.title),
            h('div', { class: 'sbom-score-check-id' }, row.id),
            ...detailLines(row)
        ])
    },
    { key: 'level', title: 'Level', width: 100 },
    {
        key: 'result',
        title: 'Result',
        width: 90,
        render: (row: SbomScoreCheck) => (row.status === 'NOT_ASSESSED' || row.status === 'ERROR')
            ? '—' : `${row.passed}/${row.total}`
    },
    { key: 'ref', title: 'Reference' }
]
</script>

<style scoped>
.sbom-score-header {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin-bottom: 8px;
}
.sbom-score-engine {
    font-size: 12px;
}
.sbom-score-verdict-line {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 10px 0 4px;
}
.sbom-score-source {
    font-size: 12px;
    margin-bottom: 8px;
}
:deep(.sbom-score-check-id) {
    font-family: monospace;
    font-size: 11px;
    color: #888;
}
:deep(.sbom-score-note), :deep(.sbom-score-remedy), :deep(.sbom-score-error) {
    font-size: 12px;
    margin-top: 2px;
}
:deep(.sbom-score-remedy) {
    color: #2080f0;
}
:deep(.sbom-score-error) {
    color: #d03050;
}
:deep(.sbom-score-truncated) {
    font-style: italic;
    color: #888;
}
.sbom-score-engine-errors {
    margin-top: 10px;
    color: #d03050;
}
.sbom-score-structure {
    margin-top: 12px;
}
:deep(.sbom-score-not-assessed td) {
    color: #999;
}
</style>
