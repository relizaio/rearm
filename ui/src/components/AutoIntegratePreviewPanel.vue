<template>
    <!-- One block: the alert of the outcome's type carries the explanation and the table inside it, so its color
         and border (naive-ui's alert theme for that type) cover both; the X closes it from the top-right corner. -->
    <div class="autoIntegratePreviewPanel mt-3" :class="'autoIntegratePreview--' + blockType" data-testid="auto-integrate-preview-panel">
        <button type="button" class="autoIntegratePreviewClose" title="Close" aria-label="Close preview"
            data-testid="auto-integrate-preview-close" @click="emit('close')">
            <n-icon size="18"><X /></n-icon>
        </button>
        <n-alert :type="blockType" :title="'Auto Integrate preview'" data-testid="auto-integrate-preview-outcome">
            <!-- The matching outcome names its release once, as the link inside the sentence (text interpolation only). -->
            <div>{{ outcome.before }}<template v-if="outcome.release"><a v-if="existingReleaseHref" :href="existingReleaseHref"
                target="_blank" rel="noopener noreferrer" data-testid="auto-integrate-preview-existing-release">{{
                    releaseText(outcome.release) }}</a><span v-else data-testid="auto-integrate-preview-existing-release">{{
                    releaseText(outcome.release) }}</span></template>{{ outcome.after }}</div>
            <div v-if="showsExistingReleaseLine(evaluation)">
                Existing release:
                <a v-if="existingReleaseHref" :href="existingReleaseHref" target="_blank" rel="noopener noreferrer"
                    data-testid="auto-integrate-preview-existing-release">{{ releaseText(evaluation.existingRelease) }}</a>
                <span v-else data-testid="auto-integrate-preview-existing-release">{{ releaseText(evaluation.existingRelease) }}</span>
            </div>
            <div v-if="evaluation.projectedVersion">
                Projected version: <strong data-testid="auto-integrate-preview-projected-version">{{ evaluation.projectedVersion }}</strong>
                (advisory: a version reserved or minted in between takes it first)
            </div>
            <!-- Transparent cells and header over the alert's color, not the default white table. -->
            <n-data-table size="small" class="mt-2" :data="evaluation.dependencies" :columns="columns" :row-key="rowKey"
                :row-class-name="rowClass" :row-props="rowProps" :theme-overrides="BLOCK_TABLE_THEME"
                data-testid="auto-integrate-preview-table" />
        </n-alert>
    </div>
</template>

<script setup lang="ts">
import { computed, h } from 'vue'
import { NAlert, NDataTable, NIcon, NTag, DataTableColumns } from 'naive-ui'
import { X } from '@vicons/tabler'
import {
    alertTypeFor, outcomeParts, refLabel, releaseCellText, releaseText, rowKind, showsExistingReleaseLine, sourceLabel,
    type AutoIntegrateDependencyRow, type AutoIntegrateEvaluation, type AutoIntegrateReleaseRef, type AutoIntegrateWords, type RowKind
} from '@/utils/autoIntegratePreview'

/**
 * TPF-120: what the auto-integrate evaluation of a feature set says, as the server returned it. The panel
 * renders the evaluation it is given and nothing else: no store, no router, no other page data, so a row
 * the server redacted is never completed from elsewhere. Every server string is rendered as text.
 */
const props = defineProps<{
    evaluation: AutoIntegrateEvaluation
    words: AutoIntegrateWords
    componentHref?: (row: AutoIntegrateDependencyRow) => string | null
    releaseHref?: (uuid: string) => string | null
}>()
const emit = defineEmits<{ (e: 'close'): void }>()

/** The block's alert type, which colors and borders the whole block (alert and table). */
const blockType = computed(() => alertTypeFor(props.evaluation.outcome))
/** The table inside the alert: no fill of its own, so the alert's color shows through; neutral lines that read on any. */
const BLOCK_TABLE_THEME = {
    tdColor: 'transparent', thColor: 'transparent', tdColorHover: 'rgba(128, 128, 128, 0.08)',
    tdColorStriped: 'transparent', borderColor: 'rgba(128, 128, 128, 0.25)'
}

const outcome = computed(() => outcomeParts(props.evaluation, props.words))
const existingReleaseHref = computed((): string | null => {
    const uuid = props.evaluation.existingRelease?.uuid
    return uuid && props.releaseHref ? props.releaseHref(uuid) : null
})

const ROW_CLASSES: Record<RowKind, string> = {
    redacted: 'autoIntegrateRedacted',
    foreign: 'autoIntegrateForeign',
    none: 'autoIntegrateNoRelease',
    ignored: 'autoIntegrateIgnored',
    release: ''
}

const rowKey = (row: AutoIntegrateDependencyRow): string => row.component.uuid + ':' + (row.branch?.uuid || '')
const rowClass = (row: AutoIntegrateDependencyRow): string => ROW_CLASSES[rowKind(row)]
const rowProps = (row: AutoIntegrateDependencyRow): Record<string, string> => ({
    'data-testid': 'auto-integrate-preview-row-' + row.component.uuid,
    'data-row-kind': rowKind(row)
})

function link (href: string | null | undefined, label: string) {
    if (!href) return label
    return h('a', { href, target: '_blank', rel: 'noopener noreferrer' }, label)
}

function renderRelease (row: AutoIntegrateDependencyRow) {
    const kind = rowKind(row)
    if (kind === 'release') {
        const release = row.release as AutoIntegrateReleaseRef
        return link(props.releaseHref ? props.releaseHref(release.uuid) : null, releaseText(release))
    }
    if (kind === 'redacted') return h(NTag, { size: 'small', type: 'default', bordered: false }, () => 'not shown')
    return releaseCellText(row)
}

const columns: DataTableColumns<AutoIntegrateDependencyRow> = [
    {
        title: 'Component',
        key: 'component',
        render: (row) => link(props.componentHref ? props.componentHref(row) : null, refLabel(row.component))
    },
    {
        title: 'Branch',
        key: 'branch',
        render: (row) => row.branch ? refLabel(row.branch) : '(no branch)'
    },
    {
        title: 'Status',
        key: 'status',
        render: (row) => row.status
    },
    {
        title: 'Source',
        key: 'source',
        render: (row) => sourceLabel(row.source)
    },
    {
        title: 'Release',
        key: 'release',
        render: (row) => renderRelease(row)
    },
    {
        title: 'Note',
        key: 'note',
        render: (row) => row.note
    }
]
</script>

<style scoped lang="scss">
.autoIntegratePreviewPanel {
    position: relative;
    .autoIntegratePreviewClose {
        position: absolute; top: 10px; right: 10px; z-index: 1;
        background: none; border: none; padding: 2px; line-height: 0; cursor: pointer; color: inherit; opacity: 0.65;
        &:hover, &:focus-visible { opacity: 1; }
    }
    :deep(.autoIntegrateRedacted td) {
        font-style: italic;
        color: #8a8a8a;
    }
    :deep(.autoIntegrateNoRelease td) {
        opacity: 0.75;
    }
    :deep(.autoIntegrateForeign td) {
        opacity: 0.75;
    }
    :deep(.autoIntegrateIgnored td) {
        opacity: 0.5;
    }
}
</style>
