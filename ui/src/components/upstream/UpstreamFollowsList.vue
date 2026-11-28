<template>
    <n-data-table size="small" :columns="columns" :data="follows" :row-key="(row: any) => row.uuid" data-testid="upstream-follows-table" />
</template>

<script lang="ts" setup>
// The follows table of the Upstream page (ReARM Pro, TPF-185 design 3.7): the item with its kind
// and a link to its follow page, the product or component in ReARM, the source, the state, the
// depth, the behaviour on drift, the pending count and the last run. Every value is text; every
// link is a router link to a route name with uuid parameters, and a value that is not a uuid gets
// no link.
import { computed, h } from 'vue'
import { RouterLink } from 'vue-router'
import type { DataTableColumns } from 'naive-ui'
import { NDataTable, NTag } from 'naive-ui'
import commonFunctions from '@/utils/commonFunctions'
import { isUuid } from '@/utils/upstream'
import { depthOriginLabel, driftBehaviourLabel, followStatusLabel, itemKindLabel, itemUpdateStateLabel, labelOf, runStateLabel } from '@/utils/upstreamLabels'
import { DEPTH_HELP, ON_DRIFT_HELP } from '@/utils/upstreamHelp'
import FieldLabel from '../FieldLabel.vue'

const props = defineProps<{
    orgUuid: string
    /** UpstreamFollow rows of GET_UPSTREAM_FOLLOWS. */
    follows: any[]
    /** The sources of the organization, for their names. */
    sources: any[]
}>()

function dateOf (d: string | null | undefined): string {
    return d ? commonFunctions.dateDisplay(d) : ''
}

function link (to: any, text: string, testid: string) {
    return h(RouterLink, { to, 'data-testid': testid }, { default: () => text })
}

function itemCell (row: any) {
    const item = row.item ?? {}
    const name = item.upstreamName ?? ''
    const parts: any[] = [
        isUuid(row.uuid) && isUuid(props.orgUuid)
            ? link({ name: 'UpstreamFollow', params: { orguuid: props.orgUuid, uuid: row.uuid } }, name, 'upstream-follow-link')
            : h('span', name),
        h(NTag, { size: 'small', class: 'upstream-cell-tag' }, { default: () => labelOf(itemKindLabel, item.kind) }),
    ]
    return h('div', parts)
}

function rearmCell (row: any) {
    const item = row.item ?? {}
    if (!isUuid(item.component) || !isUuid(props.orgUuid)) return null
    const name = item.kind === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
    return link({ name, params: { orguuid: props.orgUuid, compuuid: item.component } }, item.kind === 'PRODUCT' ? 'Open product' : 'Open component', 'upstream-rearm-link')
}

function sourceCell (row: any) {
    const source = props.sources.find(s => s.uuid === row.source)
    return h('span', source ? source.name : row.source)
}

function stateCell (row: any) {
    const parts: any[] = [h('span', labelOf(followStatusLabel, row.status))]
    if (row.status === 'UNFOLLOWED' && row.unfollowedDate) parts.push(h('div', { class: 'upstream-cell-small' }, dateOf(row.unfollowedDate)))
    if (row.item?.updateState === 'NO_LONGER_UPDATED') {
        parts.push(h(NTag, { size: 'small', type: 'warning', class: 'upstream-cell-tag', 'data-testid': 'upstream-no-longer-updated' },
            { default: () => labelOf(itemUpdateStateLabel, 'NO_LONGER_UPDATED') }))
    }
    return h('div', parts)
}

function depthCell (row: any) {
    const item = row.item ?? {}
    return h('div', [h('span', String(item.depth ?? '')), h('div', { class: 'upstream-cell-small' }, labelOf(depthOriginLabel, item.depthOrigin))])
}

function pendingCell (row: any) {
    if (!(row.pendingCount > 0)) return h('span', '0')
    return h(NTag, { size: 'small', type: 'warning', 'data-testid': 'upstream-pending-count' }, { default: () => String(row.pendingCount) })
}

function lastRunCell (row: any) {
    const run = row.lastRun
    if (!run) return h('span', 'no run yet')
    return h('div', [h('span', labelOf(runStateLabel, run.state)), run.finishedDate ? h('div', { class: 'upstream-cell-small' }, dateOf(run.finishedDate)) : null])
}

const columns = computed((): DataTableColumns<any> => [
    { key: 'item', title: 'Item', render: itemCell },
    { key: 'rearm', title: 'In ReARM', render: rearmCell },
    { key: 'source', title: 'Source', render: sourceCell },
    { key: 'state', title: 'State', render: stateCell },
    { key: 'depth', title: () => h(FieldLabel, { label: 'Depth', tip: DEPTH_HELP }), render: depthCell },
    { key: 'onDrift', title: () => h(FieldLabel, { label: 'On drift', tip: ON_DRIFT_HELP }), render: (row: any) => h('span', labelOf(driftBehaviourLabel, row.onDrift)) },
    { key: 'pending', title: 'Pending', render: pendingCell },
    { key: 'lastRun', title: 'Last run', render: lastRunCell },
])
</script>

<style scoped>
:deep(.upstream-cell-small) {
    font-size: 0.85em;
    color: #777;
}
:deep(.upstream-cell-tag) {
    margin-left: 6px;
}
</style>
