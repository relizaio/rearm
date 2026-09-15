<template>
    <div class="tlView">
        <div v-if="!lanes.length" class="empty">
            No recorded work yet — bars appear as agents take assignments and sign off.
        </div>
        <template v-else>
            <div class="tl-legend">
                <span class="lg"><i class="sw sw--passed"/>passed hop</span>
                <span class="lg"><i class="sw sw--rejected"/>rejected hop</span>
                <span class="lg"><i class="sw sw--active"/>in progress</span>
                <span class="lg"><i class="sw sw--return"/>◆ returned</span>
            </div>
            <div class="tl-scroll">
                <svg :width="width" :height="height" class="tl-svg">
                    <line v-for="(g, i) in gridLines" :key="'g' + i"
                          :x1="g.x" :y1="0" :x2="g.x" :y2="height - AXIS_H" class="tl-grid"/>
                    <g v-for="(lane, li) in lanes" :key="lane.agent">
                        <line v-if="li > 0" :x1="0" :y1="laneY(li)" :x2="width" :y2="laneY(li)" class="tl-lanesep"/>
                        <text class="tl-agent" x="6" :y="laneY(li) + 16">{{ lane.name }}</text>
                        <g v-for="(bar, bi) in lane.bars" :key="'b' + bi"
                           class="tl-bar" :class="`tl-bar--${bar.tone}`" @click="emit('open', bar.task)">
                            <rect :x="bar.x" :y="laneY(li) + LANE_PAD_TOP" :width="Math.max(bar.w, 4)" :height="BAR_H" rx="4"/>
                            <text v-if="bar.w > 46" :x="bar.x + 6" :y="laneY(li) + LANE_PAD_TOP + BAR_H - 7"
                                  class="tl-bartext">{{ bar.label }}</text>
                            <title>{{ bar.tip }}</title>
                        </g>
                        <g v-for="(m, mi) in lane.marks" :key="'m' + mi"
                           class="tl-mark" @click="emit('open', m.task)">
                            <path :d="diamond(m.x, laneY(li) + LANE_PAD_TOP + BAR_H / 2)"/>
                            <title>{{ m.tip }}</title>
                        </g>
                    </g>
                    <g class="tl-axis">
                        <text v-for="(g, i) in gridLines" :key="'t' + i"
                              :x="g.x + 4" :y="height - 6">{{ g.label }}</text>
                    </g>
                </svg>
            </div>
            <p class="tl-note">
                One lane per agent; each bar is one assignment from pickup to sign-off (click to
                open the task). Diamonds are returns. Gaps are idle time or work on other boards.
            </p>
        </template>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

const props = defineProps<{
    tasks: any[]
    agentNames: Record<string, string>
}>()
const emit = defineEmits<{ (e: 'open', task: any): void }>()

const LANE_H = 46
const LANE_PAD_TOP = 22
const BAR_H = 18
const AXIS_H = 20
const MIN_WIDTH = 760
const LABEL_W = 0 // agent label overlays the lane start

type Bar = { x: number, w: number, tone: string, label: string, tip: string, task: any }
type Mark = { x: number, tip: string, task: any }

const events = computed(() => {
    const out: { agent: string, from?: number, to?: number, at?: number, kind: string, rec: any, task: any }[] = []
    for (const t of props.tasks ?? []) {
        for (const s of t.signOffs ?? []) {
            const from = time(s.assignedAt); const to = time(s.signedOffAt)
            if (from && to) out.push({ agent: s.agent, from, to, kind: s.outcome === 'PASSED' ? 'passed' : 'rejected', rec: s, task: t })
        }
        for (const r of t.returns ?? []) {
            const at = time(r.returnedAt)
            if (at && r.agent) out.push({ agent: r.agent, at, kind: 'return', rec: r, task: t })
        }
        if (t.assignment) {
            const from = time(t.assignment.assignedAt)
            if (from) out.push({ agent: t.assignment.agent, from, to: Date.now(), kind: 'active', rec: t.assignment, task: t })
        }
    }
    return out
})

const domain = computed(() => {
    const starts = events.value.map(e => e.from ?? e.at ?? Date.now())
    const ends = events.value.map(e => e.to ?? e.at ?? Date.now())
    if (!starts.length) return null
    const min = Math.min(...starts)
    const max = Math.max(...ends, Date.now())
    return { min, max: max === min ? min + 60000 : max }
})

const width = computed(() => MIN_WIDTH)
const height = computed(() => lanes.value.length * LANE_H + AXIS_H)

function xOf (t: number): number {
    const d = domain.value
    if (!d) return 0
    return LABEL_W + ((t - d.min) / (d.max - d.min)) * (width.value - LABEL_W - 10)
}

const lanes = computed(() => {
    const byAgent = new Map<string, { bars: Bar[], marks: Mark[] }>()
    for (const e of events.value) {
        if (!byAgent.has(e.agent)) byAgent.set(e.agent, { bars: [], marks: [] })
        const lane = byAgent.get(e.agent)!
        const ref = refOf(e.task)
        if (e.kind === 'return') {
            lane.marks.push({
                x: xOf(e.at as number),
                tip: `${ref} returned by ${nameOf(e.agent)} — ${e.rec.reason}${e.rec.description ? ': ' + e.rec.description : ''}`,
                task: e.task,
            })
        } else {
            const x1 = xOf(e.from as number); const x2 = xOf(e.to as number)
            const mins = Math.round(((e.to as number) - (e.from as number)) / 60000)
            lane.bars.push({
                x: x1, w: x2 - x1, tone: e.kind,
                label: `${ref} ${e.rec.role}`,
                tip: e.kind === 'active'
                    ? `${ref} — ${e.rec.role} in progress (${mins}m so far)`
                    : `${ref} — ${e.rec.role} ${e.rec.outcome} in ${mins}m${e.rec.note ? ': ' + e.rec.note : ''}`,
                task: e.task,
            })
        }
    }
    return [...byAgent.entries()]
        .map(([agent, v]) => ({ agent, name: nameOf(agent), ...v }))
        .sort((a, b) => a.name.localeCompare(b.name))
})

const gridLines = computed(() => {
    const d = domain.value
    if (!d) return []
    const n = 4
    return Array.from({ length: n + 1 }, (_, i) => {
        const t = d.min + ((d.max - d.min) * i) / n
        return {
            x: xOf(t),
            label: new Date(t).toLocaleString('en-CA', {
                month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
            }),
        }
    })
})

function laneY (i: number): number { return i * LANE_H }
function time (iso: string | null | undefined): number | null {
    if (!iso) return null
    const t = new Date(iso).getTime()
    return isNaN(t) ? null : t
}
function refOf (t: any): string {
    return t.externalRef?.includes('#') ? '#' + t.externalRef.split('#').pop() : (t.title ?? '').slice(0, 12)
}
function nameOf (uuid: string): string {
    return props.agentNames[uuid] ?? (uuid ? uuid.slice(0, 8) : '—')
}
function diamond (cx: number, cy: number): string {
    const r = 6
    return `M ${cx} ${cy - r} L ${cx + r} ${cy} L ${cx} ${cy + r} L ${cx - r} ${cy} Z`
}
</script>

<style scoped lang="scss">
.tlView { .empty { color: #888; font-size: 13px; padding: 8px 0; } }
.tl-legend {
    display: flex;
    gap: 14px;
    margin-bottom: 8px;
    font-size: 11px;
    color: #888;
    .lg { display: inline-flex; align-items: center; gap: 5px; }
    .sw {
        width: 10px; height: 10px; border-radius: 2px; display: inline-block;
        &--passed { background: #cfe9da; border: 1px solid #4a9d6e; }
        &--rejected { background: #f6cfcf; border: 1px solid #b03a3a; }
        &--active { background: #fbe8c9; border: 1px solid #d9a24a; }
        &--return { background: transparent; border: none; width: auto; height: auto; color: #b07724; }
    }
}
.tl-scroll { overflow: auto; }
.tl-svg { display: block; }
.tl-grid { stroke: rgba(128, 128, 128, 0.15); stroke-width: 1; }
.tl-lanesep { stroke: rgba(128, 128, 128, 0.25); stroke-width: 1; }
.tl-agent { font-size: 11px; fill: #777; font-weight: 600; }
.tl-bar {
    cursor: pointer;
    rect { stroke-width: 1; }
    &--passed rect { fill: #cfe9da; stroke: #4a9d6e; }
    &--rejected rect { fill: #f6cfcf; stroke: #b03a3a; }
    &--active rect { fill: #fbe8c9; stroke: #d9a24a; stroke-dasharray: 4 3; }
}
.tl-bartext { font-size: 10px; fill: #444; font-family: monospace; pointer-events: none; }
.tl-mark { cursor: pointer; path { fill: #e2b25f; stroke: #b07724; stroke-width: 1; } }
.tl-axis text { font-size: 10px; fill: #999; }
.tl-note { color: #888; font-size: 11.5px; margin: 10px 0 0; }
</style>
