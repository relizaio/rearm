<template>
    <div class="boardusage">
        <n-space align="center" :size="12" style="margin-bottom: 12px;">
            <n-select
                v-model:value="periodHours"
                :options="periodOptions"
                size="small"
                style="width: 180px;"
                @update:value="load"
            />
            <n-spin v-if="loading" size="small"/>
            <n-text depth="3" style="font-size: 12px;">{{ windowLabel }}</n-text>
        </n-space>

        <n-card size="small" title="Total" style="margin-bottom: 14px;">
            <agent-usage-summary :usage="usage" :show-by-model="false"/>
            <n-text v-if="breakdown && !breakdown.costComplete" depth="3" style="font-size: 12px;" data-testid="lower-bound">
                {{ LOWER_BOUND_NOTE }}
            </n-text>
            <!-- The window above is a period; the budget is the board's whole life (task 40f270be). -->
            <div v-if="budgetLine" class="budgetline" data-testid="budget-line">
                <n-tag size="small" :bordered="false" :type="budgetLine.type">{{ budgetLine.label }}</n-tag>
                <n-text depth="3" style="font-size: 12px;">since the board was created</n-text>
            </div>
        </n-card>

        <n-grid :cols="2" :x-gap="14" responsive="screen" item-responsive>
            <n-grid-item span="2 m:1">
                <n-card size="small" title="By model">
                    <n-data-table
                        v-if="modelRows.length"
                        size="small"
                        :columns="modelColumns"
                        :data="modelRows"
                        :pagination="false"
                        :bordered="false"
                    />
                    <n-text v-else depth="3">No usage in this window.</n-text>
                </n-card>
            </n-grid-item>
            <n-grid-item span="2 m:1">
                <n-card size="small" title="By role">
                    <!-- The server's breakdown of the same rows as the total (RD2-8): the roles, the
                         coordinator seat and what no hop owns add up to the total above. -->
                    <n-data-table
                        v-if="byRoleRows.length"
                        size="small"
                        :columns="roleColumns"
                        :data="byRoleRows"
                        :row-key="(r: any) => r.key"
                        :pagination="false"
                        :bordered="false"
                        data-testid="by-role"
                    />
                    <n-text v-else depth="3" data-testid="by-role-empty">No usage in this window.</n-text>
                </n-card>
            </n-grid-item>
        </n-grid>

        <n-card size="small" title="Top sessions" style="margin-top: 14px;">
            <n-data-table
                v-if="sessionRows.length"
                size="small"
                :columns="sessionColumns"
                :data="sessionRows"
                :row-key="(r: any) => r.session"
                :pagination="{ pageSize: 10 }"
                :bordered="false"
                data-testid="top-sessions"
            />
            <n-text v-else depth="3" data-testid="top-sessions-empty">No sessions with usage in this window.</n-text>
        </n-card>
    </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, h } from 'vue'
import { useStore } from 'vuex'
import { NCard, NDataTable, NGrid, NGridItem, NSelect, NSpace, NSpin, NTag, NText, DataTableColumns } from 'naive-ui'
import { budgetChip } from '@/utils/agentBudget'
import AgentUsageSummary from './AgentUsageSummary.vue'
import TokenSplit from './TokenSplit.vue'
import {
    UsageTotals,
    USAGE_PERIODS,
    periodRange,
    formatCostMicros,
    byModelRows,
    modelDisplayName,
} from '@/utils/agentUsage'
import { hopsLabel, LOWER_BOUND_NOTE, roleRows } from '@/utils/agentSpendBreakdown'

const props = defineProps<{
    /** The board's budget and what it has spent since it was created; the budget line shows when both are known. */
    budgetMicros?: number | null
    lifetimeSpentMicros?: number | null
    softAlertPercent?: number | null
    boardUuid: string | null,
    agentNames: Record<string, string>,
}>()

const store = useStore()
const usage = ref<UsageTotals | null>(null)
const breakdown = ref<any>(null)
const loading = ref(false)
const periodHours = ref<number>(USAGE_PERIODS[1].hours)

const periodOptions = USAGE_PERIODS.map(p => ({ label: p.label, value: p.hours }))

const budgetLine = computed(() => props.budgetMicros === null || props.budgetMicros === undefined
    || props.lifetimeSpentMicros === null || props.lifetimeSpentMicros === undefined
    ? null : budgetChip(props.lifetimeSpentMicros, props.budgetMicros, props.softAlertPercent))

const windowLabel = computed(() => {
    const { from, to } = periodRange(periodHours.value)
    return new Date(from).toLocaleDateString() + ' — ' + new Date(to).toLocaleDateString()
})

async function load () {
    if (!props.boardUuid) {
        usage.value = null
        return
    }
    loading.value = true
    try {
        const { from, to } = periodRange(periodHours.value)
        // The total and its breakdown, over the same window (RD2-8).
        const [u, b] = await Promise.all([
            store.dispatch('fetchAgentBoardUsage', { boardUuid: props.boardUuid, from, to }),
            store.dispatch('fetchAgentBoardSpendBreakdown', { boardUuid: props.boardUuid, from, to }).catch(() => null),
        ])
        usage.value = u
        breakdown.value = b
    } catch {
        // A failed rollup leaves the panel empty rather than throwing into the
        // board view: usage is an overlay on the board, never a precondition
        // for using it.
        usage.value = null
        breakdown.value = null
    } finally {
        loading.value = false
    }
}

onMounted(load)
watch(() => props.boardUuid, load)

const modelRows = computed(() => byModelRows(usage.value))

// By role and by session: the server's breakdown, from the same rows as the total, never a
// derivation from the tasks on screen, which are a page of the board rather than the window.
const byRoleRows = computed(() => roleRows(breakdown.value))
const sessionRows = computed(() => breakdown.value?.bySession ?? [])

const costCell = (micros: number | null) => h('span', formatCostMicros(micros) ?? 'no price')

const modelColumns = computed<DataTableColumns<any>>(() => [
    { title: 'Model', key: 'model', render: (r: any) => modelDisplayName(r) },
    { title: 'Requests', key: 'requests', render: (r: any) => r.requests ?? 0 },
    { title: 'Tokens', key: 'tokens', render: (r: any) => h(TokenSplit, { usage: r, compact: true }) },
    { title: 'Cost', key: 'cost', render: (r: any) => costCell(r.derivedCostMicros ?? null) },
])

const roleColumns = computed<DataTableColumns<any>>(() => [
    { title: 'Role', key: 'label', render: (r: any) => h('span', { 'data-row': r.key }, r.label) },
    { title: 'Hops', key: 'hops', render: (r: any) => hopsLabel(r) },
    { title: 'Tokens', key: 'tokens', render: (r: any) => r.tokens ? h(TokenSplit, { usage: r.tokens, compact: true }) : '' },
    { title: 'Cost', key: 'cost', render: (r: any) => costCell(r.costMicros) },
])

const sessionColumns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Session',
        key: 'session',
        render: (r: any) => h('code', { style: 'font-size: 11px;', 'data-session': r.session },
            r.session ? String(r.session).slice(0, 8) + '…' : '—'),
    },
    { title: 'Agent', key: 'agent', render: (r: any) => props.agentNames?.[r.agent] ?? (r.agent ? String(r.agent).slice(0, 8) : '—') },
    { title: 'Role', key: 'role', render: (r: any) => r.role ?? 'unattributed' },
    { title: 'Tokens', key: 'tokens', render: (r: any) => r.tokens ? h(TokenSplit, { usage: r.tokens, compact: true }) : '' },
    { title: 'Cost', key: 'cost', render: (r: any) => costCell(r.costMicros) },
])
</script>

<style scoped>
.budgetline { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
</style>
