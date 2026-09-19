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
                    <n-data-table
                        v-if="roleRows.length"
                        size="small"
                        :columns="roleColumns"
                        :data="roleRows"
                        :pagination="false"
                        :bordered="false"
                    />
                    <!-- Roles come from the tasks on screen, not from the period
                         rollup, so this says plainly when the two disagree rather
                         than rendering an empty table that looks like zero spend. -->
                    <n-text v-else depth="3">
                        No hop usage on the tasks currently loaded for this board.
                    </n-text>
                </n-card>
            </n-grid-item>
        </n-grid>

        <n-card size="small" title="Top sessions" style="margin-top: 14px;">
            <n-data-table
                v-if="sessionRows.length"
                size="small"
                :columns="sessionColumns"
                :data="sessionRows"
                :pagination="{ pageSize: 10 }"
                :bordered="false"
            />
            <n-text v-else depth="3">No sessions with recorded usage on these tasks.</n-text>
        </n-card>
    </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, h } from 'vue'
import { useStore } from 'vuex'
import { NCard, NDataTable, NGrid, NGridItem, NSelect, NSpace, NSpin, NText, DataTableColumns } from 'naive-ui'
import AgentUsageSummary from './AgentUsageSummary.vue'
import {
    UsageTotals,
    USAGE_PERIODS,
    periodRange,
    formatTokens,
    formatCostMicros,
    totalTokens,
    byModelRows,
    modelDisplayName,
} from '@/utils/agentUsage'

const props = defineProps<{
    boardUuid: string | null,
    tasks: any[],
    agentNames: Record<string, string>,
}>()

const store = useStore()
const usage = ref<UsageTotals | null>(null)
const loading = ref(false)
const periodHours = ref<number>(USAGE_PERIODS[1].hours)

const periodOptions = USAGE_PERIODS.map(p => ({ label: p.label, value: p.hours }))

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
        usage.value = await store.dispatch('fetchAgentBoardUsage', {
            boardUuid: props.boardUuid, from, to,
        })
    } catch {
        // A failed rollup leaves the panel empty rather than throwing into the
        // board view: usage is an overlay on the board, never a precondition
        // for using it.
        usage.value = null
    } finally {
        loading.value = false
    }
}

onMounted(load)
watch(() => props.boardUuid, load)

const modelRows = computed(() => byModelRows(usage.value))

/**
 * Role and session breakdowns are derived from the hop snapshots on the tasks
 * already loaded, because the server's period rollup is summed by model and
 * does not carry a role dimension. That makes them a view of the loaded tasks
 * rather than of the period, which the empty states say out loud instead of
 * implying the numbers are the same thing as the total above.
 */
const hopRecords = computed(() => {
    const out: { role: string, session: string | null, usage: any }[] = []
    for (const t of (props.tasks ?? [])) {
        for (const so of (t.signOffs ?? [])) {
            if ((so?.usage?.reports ?? 0) > 0) out.push({ role: so.role, session: so.session, usage: so.usage })
        }
        for (const r of (t.returns ?? [])) {
            if ((r?.usage?.reports ?? 0) > 0) out.push({ role: r.role, session: r.session, usage: r.usage })
        }
    }
    return out
})

function accumulate (key: (h: any) => string | null) {
    const acc = new Map<string, { key: string, tokens: number, cost: number | null, hops: number, turns: number }>()
    for (const rec of hopRecords.value) {
        const k = key(rec) ?? '—'
        const cur = acc.get(k) ?? { key: k, tokens: 0, cost: null, hops: 0, turns: 0 }
        cur.tokens += totalTokens(rec.usage)
        cur.hops += 1
        cur.turns += rec.usage.turns ?? 0
        if (rec.usage.derivedCostMicros != null) {
            // Null stays null until something priced: summing nulls as zero
            // would present an unpriced role as a free one.
            cur.cost = (cur.cost ?? 0) + rec.usage.derivedCostMicros
        }
        acc.set(k, cur)
    }
    return Array.from(acc.values()).sort((a, b) => {
        if (a.cost != null && b.cost != null && a.cost !== b.cost) return b.cost - a.cost
        return b.tokens - a.tokens
    })
}

const roleRows = computed(() => accumulate(r => r.role))
const sessionRows = computed(() => accumulate(r => r.session))

const costCell = (micros: number | null) => h('span', formatCostMicros(micros) ?? 'no price')

const modelColumns = computed<DataTableColumns<any>>(() => [
    { title: 'Model', key: 'model', render: (r: any) => modelDisplayName(r) },
    { title: 'Requests', key: 'requests', render: (r: any) => r.requests ?? 0 },
    { title: 'Tokens', key: 'tokens', render: (r: any) => formatTokens(totalTokens(r)) },
    { title: 'Cost', key: 'cost', render: (r: any) => costCell(r.derivedCostMicros ?? null) },
])

const roleColumns = computed<DataTableColumns<any>>(() => [
    { title: 'Role', key: 'key' },
    { title: 'Hops', key: 'hops' },
    { title: 'Turns', key: 'turns' },
    { title: 'Tokens', key: 'tokens', render: (r: any) => formatTokens(r.tokens) },
    { title: 'Cost', key: 'cost', render: (r: any) => costCell(r.cost) },
])

const sessionColumns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Session',
        key: 'key',
        render: (r: any) => h('code', { style: 'font-size: 11px;' },
            r.key === '—' ? '—' : String(r.key).slice(0, 8) + '…'),
    },
    { title: 'Hops', key: 'hops' },
    { title: 'Tokens', key: 'tokens', render: (r: any) => formatTokens(r.tokens) },
    { title: 'Cost', key: 'cost', render: (r: any) => costCell(r.cost) },
])
</script>
