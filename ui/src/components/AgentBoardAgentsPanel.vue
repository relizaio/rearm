<template>
    <!-- The Agents tab (task RD3-5): who works the board over the window, then what the board spent in it. -->
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

        <n-card size="small" title="Agents" style="margin-bottom: 14px;">
            <n-data-table
                v-if="agentRows.length"
                size="small"
                :columns="agentColumns"
                :data="agentRows"
                :row-key="(r: any) => r.session"
                :pagination="{ pageSize: 20 }"
                :bordered="false"
                data-testid="agents"
            />
            <n-text v-else-if="agentsError" type="error" data-testid="agents-error">{{ agentsError }}</n-text>
            <n-text v-else depth="3" data-testid="agents-empty">No session has worked or polled this board.</n-text>
        </n-card>

        <h3 class="spendhead" data-testid="spend-heading">Spend</h3>
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
                    <n-text v-else-if="breakdownError" type="error" data-testid="by-role-error">{{ breakdownError }}</n-text>
                    <n-text v-else depth="3" data-testid="by-role-empty">No usage in this window.</n-text>
                </n-card>
            </n-grid-item>
        </n-grid>

    </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, h } from 'vue'
import { useStore } from 'vuex'
import { RouterLink } from 'vue-router'
import { NCard, NDataTable, NGrid, NGridItem, NSelect, NSpace, NSpin, NTag, NText, DataTableColumns } from 'naive-ui'
import { budgetChip } from '@/utils/agentBudget'
import { tsDate } from '@/utils/agentTaskFormat'
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
import { breakdownErrorText, hopsLabel, LOWER_BOUND_NOTE, roleRows } from '@/utils/agentSpendBreakdown'
import { agentLabel, agentLinks, agentsErrorText, cacheSharePercent, cacheShareTitle, staleTitle, stateTagType, stateWords } from '@/utils/agentsView'
import { ts, tsFull } from '@/utils/agentTaskFormat'

const props = defineProps<{
    /** The board's budget and what it has spent since it was created; the budget line shows when both are known. */
    budgetMicros?: number | null
    lifetimeSpentMicros?: number | null
    softAlertPercent?: number | null
    boardUuid: string | null,
}>()

const store = useStore()
const usage = ref<UsageTotals | null>(null)
const breakdown = ref<any>(null)
/** Why the breakdown could not be read: shown in its place, never as "no usage" beside a total (RD2-8 run 1, T-2). */
const breakdownError = ref<string | null>(null)
/** The Agents table's rows, in the server's order (task RD3-5), and why they could not be read. */
const agentRows = ref<any[]>([])
const agentsError = ref<string | null>(null)
const loading = ref(false)
const periodHours = ref<number>(USAGE_PERIODS[1].hours)

const periodOptions = USAGE_PERIODS.map(p => ({ label: p.label, value: p.hours }))

const budgetLine = computed(() => props.budgetMicros === null || props.budgetMicros === undefined
    || props.lifetimeSpentMicros === null || props.lifetimeSpentMicros === undefined
    ? null : budgetChip(props.lifetimeSpentMicros, props.budgetMicros, props.softAlertPercent))

const windowLabel = computed(() => {
    const { from, to } = periodRange(periodHours.value)
    return tsDate(from) + ' — ' + tsDate(to)
})

async function load () {
    if (!props.boardUuid) {
        usage.value = null
        breakdown.value = null
        breakdownError.value = null
        agentRows.value = []
        agentsError.value = null
        return
    }
    loading.value = true
    try {
        const { from, to } = periodRange(periodHours.value)
        // The agents, the total and its breakdown, over the same window (RD2-8, RD3-5).
        const [u, b, a] = await Promise.all([
            store.dispatch('fetchAgentBoardUsage', { boardUuid: props.boardUuid, from, to }),
            store.dispatch('fetchAgentBoardSpendBreakdown', { boardUuid: props.boardUuid, from, to })
                .then((x: any) => ({ ok: x }), (e: any) => ({ error: breakdownErrorText(e) })),
            store.dispatch('fetchAgentBoardAgents', { boardUuid: props.boardUuid, from, to })
                .then((x: any) => ({ ok: x ?? [] }), (e: any) => ({ error: agentsErrorText(e) })),
        ])
        usage.value = u
        breakdown.value = 'ok' in b ? b.ok : null
        breakdownError.value = 'error' in b ? b.error : null
        agentRows.value = 'ok' in a ? a.ok : []
        agentsError.value = 'error' in a ? a.error : null
    } catch {
        // A failed rollup leaves the panel empty rather than throwing into the
        // board view: usage is an overlay on the board, never a precondition
        // for using it.
        usage.value = null
        breakdown.value = null
        breakdownError.value = null
    } finally {
        loading.value = false
    }
}

onMounted(load)
watch(() => props.boardUuid, load)

const modelRows = computed(() => byModelRows(usage.value))

// By role: the server's breakdown, from the same rows as the total, never a derivation from the
// tasks on screen, which are a page of the board rather than the window. Spend per session is the
// Agents table's (RD3-5), which replaced "Top sessions".
const byRoleRows = computed(() => roleRows(breakdown.value))

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

const link = (to: string | null, text: string, testid: string) => to
    ? h(RouterLink, { to, 'data-testid': testid }, { default: () => text })
    : h('span', text)

const agentColumns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Agent',
        key: 'agent',
        render: (r: any) => h('span', {}, [
            link(agentLinks(r).session, agentLabel(r), 'agent-link'),
            ' ',
            h('code', { style: 'font-size: 11px;', 'data-session': r.session }, String(r.session ?? '').slice(0, 8)),
        ]),
    },
    { title: 'Roles', key: 'roles', render: (r: any) => (r.roles ?? []).join(', ') || '—' },
    {
        title: 'State',
        key: 'state',
        render: (r: any) => h('span', { 'data-state': r.state?.kind }, [
            h(NTag, { size: 'small', bordered: false, type: stateTagType(r.state?.kind) }, { default: () => r.state?.kind ?? '—' }),
            ' ',
            r.state?.kind === 'WORKING' && agentLinks(r).task
                ? link(agentLinks(r).task, stateWords(r.state), 'task-link')
                : h('span', stateWords(r.state)),
            r.stale?.length
                ? h('span', { class: 'stalemark', title: staleTitle(r.stale) ?? '', 'data-testid': 'stale-mark' }, ' ● stale')
                : null,
        ]),
    },
    { title: 'Last poll', key: 'lastPollAt', render: (r: any) => h('span', { title: tsFull(r.lastPollAt) }, r.lastPollAt ? ts(r.lastPollAt) : '—') },
    { title: 'Last offer', key: 'lastOfferAt', render: (r: any) => h('span', { title: tsFull(r.lastOfferAt) }, r.lastOfferAt ? ts(r.lastOfferAt) : '—') },
    { title: 'Done', key: 'tasksCompleted', render: (r: any) => String(r.tasksCompleted ?? 0) },
    { title: 'Spend', key: 'cost', render: (r: any) => r.tokens ? costCell(r.costMicros) : h('span', '—') },
    {
        title: 'Cache',
        key: 'cacheShare',
        render: (r: any) => h('span', { title: cacheShareTitle(r.tokens) ?? '', 'data-testid': 'cache-share' }, cacheSharePercent(r.cacheShare)),
    },
])
</script>

<style scoped>
.budgetline { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.spendhead { margin: 18px 0 10px; font-size: 15px; font-weight: 600; }
.stalemark { color: #d03050; font-size: 12px; }
</style>
