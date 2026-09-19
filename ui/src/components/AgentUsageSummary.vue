<template>
    <div v-if="!usage || (usage.reports ?? 0) === 0" class="usage-empty">
        <n-text depth="3">No usage reported{{ emptyHint }}</n-text>
        <n-tag v-for="b in badges" :key="b.label" :type="b.type" size="small" round style="margin-left: 8px;">
            <n-tooltip trigger="hover">
                <template #trigger><span>{{ b.label }}</span></template>
                <span style="max-width: 360px; display: inline-block;">{{ b.tooltip }}</span>
            </n-tooltip>
        </n-tag>
    </div>
    <div v-else class="usage-summary">
        <n-space align="center" :size="18" style="flex-wrap: wrap;">
            <div class="usage-figure">
                <div class="usage-value">{{ costLabelText }}</div>
                <div class="usage-caption">cost</div>
            </div>
            <div class="usage-figure">
                <div class="usage-value">{{ formatTokens(totalTokens(usage)) }}</div>
                <div class="usage-caption">tokens</div>
            </div>
            <div class="usage-figure">
                <div class="usage-value">{{ usage.turns ?? 0 }}</div>
                <div class="usage-caption">turns</div>
            </div>
            <div class="usage-figure" v-if="(usage.toolCalls ?? 0) > 0">
                <div class="usage-value">{{ usage.toolCalls }}</div>
                <div class="usage-caption">tool calls</div>
            </div>
            <n-tag v-for="b in badges" :key="b.label" :type="b.type" size="small" round>
                <n-tooltip trigger="hover">
                    <template #trigger><span>{{ b.label }}</span></template>
                    <span style="max-width: 360px; display: inline-block;">{{ b.tooltip }}</span>
                </n-tooltip>
            </n-tag>
        </n-space>

        <!-- The token split is where the money actually is: cache reads dominate a
             long session, so the breakdown is shown rather than folded away. -->
        <n-space :size="16" style="margin-top: 6px; flex-wrap: wrap;">
            <n-text depth="3" style="font-size: 12px;">
                in {{ formatTokens(usage.inputTokens) }} ·
                out {{ formatTokens(usage.outputTokens) }} ·
                cache read {{ formatTokens(usage.cacheReadTokens) }} ·
                cache write {{ formatTokens(usage.cacheWriteTokens) }}
            </n-text>
        </n-space>

        <n-data-table
            v-if="showByModel && modelRows.length"
            size="small"
            style="margin-top: 12px;"
            :columns="modelColumns"
            :data="modelRows"
            :pagination="false"
            :bordered="false"
        />
    </div>
</template>

<script setup lang="ts">
import { computed, h } from 'vue'
import { NSpace, NTag, NText, NTooltip, NDataTable, DataTableColumns } from 'naive-ui'
import {
    UsageTotals,
    totalTokens,
    formatTokens,
    costLabel,
    usageBadges,
    byModelRows,
    formatCostMicros,
} from '@/utils/agentUsage'

// One usage line, shared by the session, task and board views so the three
// cannot drift into disagreeing about what "cost" or "tokens" mean.
const props = withDefaults(defineProps<{
    usage?: UsageTotals | null,
    completeness?: string | null,
    modelMismatch?: boolean | null,
    showByModel?: boolean,
    emptyHint?: string,
}>(), {
    usage: null,
    completeness: null,
    modelMismatch: false,
    showByModel: true,
    emptyHint: '',
})

const badges = computed(() => usageBadges(props.completeness, props.modelMismatch, props.usage))
const costLabelText = computed(() => costLabel(props.usage))
const modelRows = computed(() => byModelRows(props.usage))

const modelColumns = computed<DataTableColumns<any>>(() => [
    { title: 'Model', key: 'model', render: (r: any) => r.model ?? '—' },
    { title: 'Requests', key: 'requests', render: (r: any) => r.requests ?? 0 },
    { title: 'Turns', key: 'turns', render: (r: any) => r.turns ?? 0 },
    { title: 'Tokens', key: 'tokens', render: (r: any) => formatTokens(totalTokens(r)) },
    {
        title: 'Cost',
        key: 'cost',
        // Per-model cost can be null while the session total is not: one model
        // priced, another not. Rendered as "no price" rather than as a dash so
        // the reason is legible.
        render: (r: any) => h('span', formatCostMicros(r.derivedCostMicros) ?? 'no price'),
    },
])
</script>

<style scoped>
.usage-figure {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
}
.usage-value {
    font-size: 18px;
    font-weight: 600;
    line-height: 1.1;
}
.usage-caption {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    opacity: 0.6;
}
.usage-empty {
    padding: 4px 0;
}
</style>
