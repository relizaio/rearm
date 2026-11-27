<template>
    <n-data-table
        size="small"
        class="upstream-offer-table"
        :columns="columns"
        :data="rows"
        :row-key="rowKey"
        :pagination="pagination"
        :expanded-row-keys="expanded"
        data-testid="upstream-start-list"
        @update:page="onPage"
        @update:expanded-row-keys="onExpand" />
</template>

<script lang="ts" setup>
// The rows of a source's list in the start of a follow (ReARM Pro): name, uuid, identifiers and
// the action of a row. It receives the rows and emits clicks; it sends no request and imports no
// document, the dialog does both. Every text of the source is a string child of h(): no markup,
// no attribute, no class, no link is built from it. Long text is cut for display and wraps; the
// whole text is in the expanded row only.
import { computed, h, ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { DataTableColumns } from 'naive-ui'
import { NButton, NDataTable, NSpace, NTag } from 'naive-ui'
import { isUuid } from '@/utils/upstream'
import {
    OFFER_IDENTIFIERS_SHOWN, OFFER_NAME_SHOWN, OFFER_TABLE_PAGE, OFFER_VALUE_SHOWN, cutForDisplay, rowIsExpandable,
} from '@/utils/upstreamOffer'
import type { OfferIdentifier, OfferRow } from '@/utils/upstreamOffer'
import { identifierTypeLabel, labelOf, offerFollowed, offerIdentifiersDropped, offerMoreIdentifiers, offerNoName } from '@/utils/upstreamLabels'

const props = defineProps<{
    rows: OfferRow[]
    orgUuid: string
    /** A preparation is in flight: every Prepare is disabled. */
    disabled: boolean
    /** The table page, from 1; a view of the loaded rows, never a request. */
    page: number
}>()

const emit = defineEmits<{
    (e: 'prepare', row: OfferRow): void
    (e: 'update:page', page: number): void
}>()

const expanded = ref<string[]>([])

const rowKey = (row: OfferRow): string => row.upstreamUuid

const pagination = computed(() => ({ page: props.page, pageSize: OFFER_TABLE_PAGE }))

function onPage (page: number) {
    emit('update:page', page)
}

function onExpand (keys: Array<string | number>) {
    expanded.value = keys.map(String)
}

function identifierLine (identifier: OfferIdentifier, whole: boolean) {
    const value = whole ? identifier.idValue : cutForDisplay(identifier.idValue, OFFER_VALUE_SHOWN)
    return h('div', { class: 'upstream-offer-identifier' }, [
        h('span', { class: 'upstream-offer-idtype' }, labelOf(identifierTypeLabel, identifier.idType)),
        h('span', { class: 'upstream-offer-idvalue' }, value),
    ])
}

function nameCell (row: OfferRow) {
    if (row.name === null || row.name === undefined) return h('span', { class: 'upstream-offer-noname' }, offerNoName)
    return h('span', { class: 'upstream-offer-cell' }, cutForDisplay(row.name, OFFER_NAME_SHOWN))
}

function identifiersCell (row: OfferRow) {
    const identifiers = row.identifiers ?? []
    const lines: any[] = identifiers.slice(0, OFFER_IDENTIFIERS_SHOWN).map(i => identifierLine(i, false))
    if (identifiers.length > OFFER_IDENTIFIERS_SHOWN) {
        lines.push(h('div', { class: 'upstream-offer-more' }, offerMoreIdentifiers(identifiers.length - OFFER_IDENTIFIERS_SHOWN)))
    }
    return h('div', { class: 'upstream-offer-cell' }, lines)
}

function followLink (row: OfferRow) {
    if (!isUuid(row.activeFollow) || !isUuid(props.orgUuid)) return null
    return h(RouterLink, {
        to: { name: 'UpstreamFollow', params: { orguuid: props.orgUuid, uuid: row.activeFollow } },
        'data-testid': 'upstream-start-row-follow-link',
    }, { default: () => 'Open the follow' })
}

function actionCell (row: OfferRow) {
    if (row.activeFollow === null || row.activeFollow === undefined) {
        return h(NButton, {
            size: 'tiny', type: 'primary', disabled: props.disabled, 'data-testid': 'upstream-start-row-prepare', onClick: () => emit('prepare', row),
        }, { default: () => 'Prepare' })
    }
    return h(NSpace, { size: 'small', align: 'center' }, {
        default: () => [
            h(NTag, { size: 'small', type: 'success', 'data-testid': 'upstream-start-row-followed' }, { default: () => offerFollowed }),
            followLink(row),
        ],
    })
}

function expandCell (row: OfferRow) {
    const parts: any[] = [
        row.name === null || row.name === undefined
            ? h('div', { class: 'upstream-offer-noname' }, offerNoName)
            : h('div', { class: 'upstream-offer-whole-name' }, row.name),
        ...(row.identifiers ?? []).map(i => identifierLine(i, true)),
    ]
    if ((row.identifiersDropped ?? 0) > 0) {
        parts.push(h('div', { class: 'upstream-offer-dropped', 'data-testid': 'upstream-start-row-dropped' }, offerIdentifiersDropped(row.identifiersDropped)))
    }
    return h('div', { class: 'upstream-offer-cell', 'data-testid': 'upstream-start-row-expanded' }, parts)
}

const columns = computed((): DataTableColumns<OfferRow> => [
    { type: 'expand', expandable: rowIsExpandable, renderExpand: expandCell },
    { key: 'name', title: 'Name', render: nameCell },
    { key: 'upstreamUuid', title: 'Uuid', render: (row: OfferRow) => h('span', { class: 'upstream-offer-uuid' }, row.upstreamUuid) },
    { key: 'identifiers', title: 'Identifiers', render: identifiersCell },
    { key: 'action', title: '', render: actionCell },
])
</script>

<style scoped>
:deep(.upstream-offer-cell),
:deep(.upstream-offer-whole-name),
:deep(.upstream-offer-idvalue) {
    overflow-wrap: anywhere;
    word-break: break-word;
}
:deep(.upstream-offer-noname) {
    color: #888;
    font-style: italic;
}
:deep(.upstream-offer-uuid) {
    font-family: monospace;
    font-size: 0.9em;
    overflow-wrap: anywhere;
}
:deep(.upstream-offer-idtype) {
    font-size: 0.85em;
    color: #666;
    margin-right: 6px;
}
:deep(.upstream-offer-more),
:deep(.upstream-offer-dropped) {
    font-size: 0.85em;
    color: #777;
}
</style>
