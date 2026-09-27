<template>
    <div class="pertView">
        <div v-if="!graph.nodes.length" class="empty">
            No open tasks to chart. Completed and cancelled tasks are hidden here — switch to
            the kanban for the full board.
        </div>
        <template v-else>
            <div class="pert-legend">
                <span class="lg"><i class="sw sw--crit"/>critical path ({{ graph.criticalLength }} deep)</span>
                <span class="lg"><i class="sw sw--done"/>completed</span>
                <span class="lg"><i class="sw sw--assigned"/>assigned</span>
                <span class="lg"><i class="sw sw--ready"/>ready</span>
                <span class="lg"><i class="sw sw--blocked"/>blocked</span>
                <span class="lg"><i class="sw sw--hold"/>on hold</span>
                <span v-if="graph.groups.length" class="lg"><i class="sw sw--gate"/>waits on a group</span>
            </div>
            <div class="pert-scroll">
                <svg :width="graph.width" :height="graph.height" class="pert-svg">
                    <defs>
                        <marker id="pert-arrow" viewBox="0 0 8 8" refX="7" refY="4"
                                markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                            <path d="M 0 0 L 8 4 L 0 8 z" fill="#b0b0b0"/>
                        </marker>
                        <marker id="pert-arrow-crit" viewBox="0 0 8 8" refX="7" refY="4"
                                markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                            <path d="M 0 0 L 8 4 L 0 8 z" fill="#c2410c"/>
                        </marker>
                    </defs>
                    <!-- The board's groups (RD2-31): a box each along the top, laid out by the groups they
                         wait on; a gated task hangs off the group holding it back by a dotted line. -->
                    <path v-for="(e, i) in graph.groupEdges" :key="'ge' + i" :d="e.d" class="pert-gedge"
                          marker-end="url(#pert-arrow)" data-testid="pert-group-edge"/>
                    <path v-for="(e, i) in graph.gateEdges" :key="'gt' + i" :d="e.d" class="pert-gate"
                          :data-gate="e.group + '>' + e.task" data-testid="pert-gate-edge"/>
                    <g v-for="g in graph.groups" :key="'g' + g.key" :transform="`translate(${g.x}, ${g.y})`"
                       class="pert-group" :class="{ 'pert-group--closed': g.closed }" data-testid="pert-group" :data-key="g.key">
                        <rect :width="GROUP_W" :height="GROUP_H" rx="6" :style="{ stroke: g.colour }"/>
                        <rect :width="5" :height="GROUP_H" rx="2" :style="{ fill: g.colour }"/>
                        <text class="pert-ref" x="12" y="16">{{ g.key }}</text>
                        <text class="pert-status" x="12" y="30">{{ g.progress }}</text>
                        <title>{{ g.label }}</title>
                    </g>
                    <path
                        v-for="(e, i) in graph.edges"
                        :key="'e' + i"
                        :d="e.d"
                        class="pert-edge"
                        :class="{ 'pert-edge--crit': e.critical, 'pert-edge--met': e.met }"
                        :marker-end="e.critical ? 'url(#pert-arrow-crit)' : 'url(#pert-arrow)'"
                    />
                    <g
                        v-for="n in graph.nodes"
                        :key="n.t.uuid"
                        :transform="`translate(${n.x}, ${n.y})`"
                        class="pert-node"
                        :class="[`pert-node--${n.tone}`, { 'pert-node--crit': n.critical }]"
                    >
                        <rect :width="NODE_W" :height="NODE_H" rx="6"/>
                        <rect v-if="groupColour(n.t.group?.key)" :width="4" :height="NODE_H" rx="2"
                              :style="{ fill: groupColour(n.t.group?.key) }" class="pert-node__group"/>
                        <text class="pert-ref" x="10" y="18">{{ refLabel(n.t) }}</text>
                        <text class="pert-role" :x="NODE_W - 10" y="18" text-anchor="end">{{ n.t.role || '—' }}</text>
                        <text class="pert-title" x="10" y="35">{{ clip(n.t.title, 26) }}</text>
                        <text class="pert-status" x="10" y="50">{{ statusLabel(n.t) }}</text>
                        <title>{{ n.t.title }} — {{ n.t.status }}{{ n.t.hold?.reason ? ' (' + n.t.hold.reason + ')' : '' }}</title>
                    </g>
                </svg>
            </div>
            <p class="pert-note">
                Layers are dependency depth: a task sits one column right of its latest
                prerequisite. Everything in column 1 is workable now; anything further right waits
                for the arrows feeding it. The critical path is the longest chain — it sets the
                floor on how fast this board can finish however many agents you add.
            </p>
        </template>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { cardRef } from '@/utils/agentTaskFormat'
import { refWithLevel } from '@/utils/agentTaskLevel'
import { groupColour, groupLabel, groupLayers, groupProgress, sortedGroups } from '@/utils/agentTaskGroups'

const props = defineProps<{ tasks: any[], board?: any }>()

const NODE_W = 190
const NODE_H = 62
const COL_GAP = 84
const ROW_GAP = 26
const PAD = 14
const GROUP_W = 150
const GROUP_H = 38
const GROUP_GAP = 40

// Charted set: everything still in play plus completed tasks that
// something open depends on (they explain why work is unblocked).
const charted = computed<any[]>(() => {
    const open = (props.tasks ?? []).filter(t => t.status !== 'CANCELLED')
    const live = open.filter(t => t.status !== 'COMPLETED')
    const neededUuids = new Set<string>()
    for (const t of live) for (const d of t.dependsOn ?? []) neededUuids.add(d)
    return open.filter(t => t.status !== 'COMPLETED' || neededUuids.has(t.uuid))
})

const graph = computed(() => {
    const nodesById = new Map<string, any>(charted.value.map(t => [t.uuid, t]))
    const depsOf = (t: any): string[] => (t.dependsOn ?? []).filter((d: string) => nodesById.has(d))

    // Longest-path layering; memoized with cycle guard (the server
    // rejects cycles, so the guard is belt-and-braces).
    const layerMemo = new Map<string, number>()
    const layerOf = (uuid: string, seen: Set<string> = new Set()): number => {
        if (layerMemo.has(uuid)) return layerMemo.get(uuid) as number
        if (seen.has(uuid)) return 0
        seen.add(uuid)
        const t = nodesById.get(uuid)
        const deps = t ? depsOf(t) : []
        const l = deps.length ? Math.max(...deps.map(d => layerOf(d, seen))) + 1 : 0
        layerMemo.set(uuid, l)
        return l
    }

    // Critical path = longest dependency chain (node count), walking back
    // through the heaviest predecessor at each step.
    const chainMemo = new Map<string, number>()
    const chainOf = (uuid: string): number => {
        if (chainMemo.has(uuid)) return chainMemo.get(uuid) as number
        const t = nodesById.get(uuid)
        const deps = t ? depsOf(t) : []
        const c = deps.length ? Math.max(...deps.map(chainOf)) + 1 : 1
        chainMemo.set(uuid, c)
        return c
    }
    let tail: any = null
    for (const t of charted.value) {
        if (!tail || chainOf(t.uuid) > chainOf(tail.uuid)) tail = t
    }
    const criticalSet = new Set<string>()
    let cursor = tail
    while (cursor) {
        criticalSet.add(cursor.uuid)
        const deps = depsOf(cursor)
        cursor = deps.length
            ? nodesById.get(deps.reduce((a, b) => (chainOf(a) >= chainOf(b) ? a : b)))
            : null
    }

    // The group band along the top, when the board has groups.
    const layers = groupLayers(props.board)
    const groupRows = new Map<number, number>()
    const groups: any[] = []
    const groupPos = new Map<string, { x: number, y: number }>()
    for (const g of sortedGroups(props.board)) {
        const l = layers.get(g.key) ?? 0
        const row = groupRows.get(l) ?? 0
        groupRows.set(l, row + 1)
        const x = PAD + l * (GROUP_W + GROUP_GAP)
        const y = PAD + row * (GROUP_H + 8)
        groupPos.set(g.key, { x, y })
        groups.push({ key: g.key, x, y, colour: groupColour(g.key), progress: groupProgress(g), label: groupLabel(g),
            closed: g.status === 'CLOSED' })
    }
    const bandRows = Math.max(0, ...groupRows.values())
    const top = groups.length ? bandRows * (GROUP_H + 8) + ROW_GAP * 2 : 0
    const groupEdges: any[] = []
    for (const g of sortedGroups(props.board)) {
        for (const d of g.dependsOn ?? []) {
            const from = groupPos.get(d)
            const to = groupPos.get(g.key)
            if (!from || !to) continue
            const x1 = from.x + GROUP_W
            const y1 = from.y + GROUP_H / 2
            const x2 = to.x
            const y2 = to.y + GROUP_H / 2
            const dx = Math.max(20, (x2 - x1) / 2)
            groupEdges.push({ d: `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}` })
        }
    }

    const byLayer = new Map<number, any[]>()
    for (const t of charted.value) {
        const l = layerOf(t.uuid)
        if (!byLayer.has(l)) byLayer.set(l, [])
        byLayer.get(l)!.push(t)
    }
    const positions = new Map<string, { x: number, y: number }>()
    const nodes: any[] = []
    for (const [l, group] of [...byLayer.entries()].sort((a, b) => a[0] - b[0])) {
        group.sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
        group.forEach((t, i) => {
            const x = PAD + l * (NODE_W + COL_GAP)
            const y = PAD + top + i * (NODE_H + ROW_GAP)
            positions.set(t.uuid, { x, y })
            nodes.push({ t, x, y, layer: l, tone: toneOf(t), critical: criticalSet.has(t.uuid) })
        })
    }

    const edges: any[] = []
    for (const t of charted.value) {
        for (const d of depsOf(t)) {
            const from = positions.get(d)
            const to = positions.get(t.uuid)
            if (!from || !to) continue
            const x1 = from.x + NODE_W
            const y1 = from.y + NODE_H / 2
            const x2 = to.x
            const y2 = to.y + NODE_H / 2
            const dx = Math.max(30, (x2 - x1) / 2)
            edges.push({
                d: `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`,
                critical: criticalSet.has(d) && criticalSet.has(t.uuid),
                met: nodesById.get(d)?.status === 'COMPLETED',
            })
        }
    }

    // A gated task's dotted line up to each group it waits on.
    const gateEdges: any[] = []
    for (const n of nodes) {
        for (const k of n.t.waitingOnGroups ?? []) {
            const from = groupPos.get(k)
            if (!from) continue
            const x1 = from.x + GROUP_W / 2
            const y1 = from.y + GROUP_H
            const x2 = n.x + NODE_W / 2
            const y2 = n.y
            gateEdges.push({ d: `M ${x1} ${y1} C ${x1} ${y1 + 30}, ${x2} ${y2 - 30}, ${x2} ${y2}`, group: k, task: n.t.uuid })
        }
    }

    const maxLayer = Math.max(0, ...[...byLayer.keys()])
    const maxRows = Math.max(1, ...[...byLayer.values()].map(g => g.length))
    const maxGroupLayer = Math.max(0, ...[...groupRows.keys()])
    return {
        nodes,
        edges,
        groups,
        groupEdges,
        gateEdges,
        width: Math.max(PAD * 2 + (maxLayer + 1) * NODE_W + maxLayer * COL_GAP,
            groups.length ? PAD * 2 + (maxGroupLayer + 1) * GROUP_W + maxGroupLayer * GROUP_GAP : 0),
        height: PAD * 2 + top + maxRows * NODE_H + (maxRows - 1) * ROW_GAP,
        criticalLength: tail ? chainOf(tail.uuid) : 0,
    }
})

function blocked (t: any): boolean {
    return (t.dependsOn ?? []).some((d: string) => {
        const dep = (props.tasks ?? []).find(x => x.uuid === d)
        return !dep || dep.status !== 'COMPLETED'
    })
}

function toneOf (t: any): string {
    if (t.status === 'COMPLETED') return 'done'
    if (t.status === 'ON_HOLD') return 'hold'
    if (t.status === 'ASSIGNED') return 'assigned'
    if (blocked(t)) return 'blocked'
    if (t.status === 'QUEUED') return 'ready'
    return 'idle'
}

function statusLabel (t: any): string {
    if (t.status === 'QUEUED' && blocked(t)) return 'QUEUED · blocked'
    return t.status.replace(/_/g, ' ').toLowerCase()
}

/** The card's reference: the task's key first (task 36d0549e), else its tracker number, else "draft". */
function refLabel (t: any): string {
    return refWithLevel(cardRef(t, 'draft'), t, props.board)
}

function clip (s: string, n: number): string {
    if (!s) return ''
    return s.length > n ? s.slice(0, n - 1) + '…' : s
}
</script>

<style scoped lang="scss">
.pertView { .empty { color: #888; font-size: 13px; padding: 8px 0; } }
.pert-legend {
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
    margin-bottom: 8px;
    font-size: 11px;
    color: #888;
    .lg { display: inline-flex; align-items: center; gap: 5px; }
    .sw {
        width: 10px;
        height: 10px;
        border-radius: 2px;
        display: inline-block;
        &--crit { background: #c2410c; }
        &--done { background: #e2f3e8; border: 1px solid #4a9d6e; }
        &--assigned { background: #fdf1de; border: 1px solid #d9a24a; }
        &--ready { background: #e8f0fb; border: 1px solid #6c8fc7; }
        &--blocked { background: #f2f2f2; border: 1px dashed #b0b0b0; }
        &--hold { background: #fbe3e3; border: 1px solid #b03a3a; }
        &--gate { background: transparent; border: 1px dotted #d9a24a; }
    }
}
.pert-scroll { overflow: auto; max-width: 100%; padding-bottom: 4px; }
.pert-svg { display: block; }
.pert-edge {
    fill: none;
    stroke: #b0b0b0;
    stroke-width: 1.5;
    stroke-dasharray: 4 3;
    &--met { stroke-dasharray: none; stroke: #4a9d6e; }
    &--crit { stroke: #c2410c; stroke-width: 2.5; stroke-dasharray: none; }
}
.pert-gedge { fill: none; stroke: #9a9a9a; stroke-width: 1.5; }
.pert-gate { fill: none; stroke: #d9a24a; stroke-width: 1.5; stroke-dasharray: 1 3; stroke-linecap: round; }
.pert-group {
    rect:first-child { fill: #fafafa; stroke-width: 1.5; }
    text { font-size: 11px; fill: #333; }
    .pert-ref { font-family: monospace; font-weight: 600; }
    .pert-status { font-size: 10px; fill: #888; }
    &--closed { opacity: 0.55; }
}
.pert-node {
    rect {
        fill: #fff;
        stroke: #d0d0d0;
        stroke-width: 1;
    }
    text { font-size: 11px; fill: #333; }
    .pert-ref { font-family: monospace; font-weight: 600; }
    .pert-role { font-size: 10px; fill: #999; }
    .pert-title { font-size: 11.5px; }
    .pert-status { font-size: 10px; fill: #888; }
    &--done rect { fill: #e2f3e8; stroke: #4a9d6e; }
    &--assigned rect { fill: #fdf1de; stroke: #d9a24a; }
    &--ready rect { fill: #e8f0fb; stroke: #6c8fc7; }
    &--blocked rect { fill: #f7f7f7; stroke: #b0b0b0; stroke-dasharray: 4 3; }
    &--hold rect { fill: #fbe3e3; stroke: #b03a3a; }
    &--crit rect { stroke-width: 2.5; stroke: #c2410c; }
}
.pert-note { color: #888; font-size: 11.5px; margin: 10px 0 0; max-width: 860px; }
</style>
