<template>
    <div class="upstream-depth-assessment" data-testid="upstream-depth-assessment">
        <n-radio-group :value="shownDepth" data-testid="upstream-depth-radio" @update:value="choose">
            <n-space vertical :size="4">
                <n-radio v-for="d in depths" :key="d.depth" :value="d.depth" :data-testid="`upstream-depth-${d.depth}`">
                    <span>Depth {{ d.depth }}</span>
                    <n-tag v-if="d.depth === proposal.proposedDepth" size="small" type="info" class="upstream-depth-tag">proposed</n-tag>
                    <span class="upstream-depth-fit">{{ labelOf(depthFitLabel, d.fit) }}, {{ lineWords(d.lineCount) }}, {{ d.unplaceable }} not placeable</span>
                </n-radio>
            </n-space>
        </n-radio-group>
        <div class="upstream-depth-basis" data-testid="upstream-depth-basis">
            Depth {{ proposal.proposedDepth }} is proposed because {{ labelOf(depthBasisLabel, proposal.depthBasis) }}.
        </div>
        <div v-if="shown" class="upstream-depth-preview" data-testid="upstream-depth-preview">
            <div v-if="shown.depth === 0" data-testid="upstream-depth-zero">No version lines: every release goes to the base branch.</div>
            <upstream-line-table v-else :lines="shown.lines" :truncated="shown.linesTruncated" />
        </div>
        <n-button v-if="readOnly && shown" size="small" class="upstream-depth-use" data-testid="upstream-depth-use" @click="emit('use', shown.depth)">
            Use this depth
        </n-button>
    </div>
</template>

<script lang="ts" setup>
// The depth choice with the line preview (ReARM Pro, TPF-185 design 3.8, section 3 of the form):
// the depths the backend assessed on the real release history, each with its fit, and the lines
// the selected one yields. The preview is the backend's computation, not the UI's. With readOnly
// it is the read-only form of design 3.9: the radio only chooses which depth's lines are shown,
// and "Use this depth" emits that depth; nothing is chosen for the follow by it.
import { computed, ref, watch } from 'vue'
import { NButton, NRadio, NRadioGroup, NSpace, NTag } from 'naive-ui'
import { depthBasisLabel, depthFitLabel, labelOf } from '@/utils/upstreamLabels'
import UpstreamLineTable from './UpstreamLineTable.vue'

const props = defineProps<{
    /** An UpstreamItemProposal of a preparation. */
    proposal: any
    /** The chosen depth; ignored with readOnly. */
    modelValue?: number | null
    readOnly?: boolean
}>()

const emit = defineEmits<{
    (e: 'update:modelValue', depth: number): void
    (e: 'use', depth: number): void
}>()

const depths = computed((): any[] => props.proposal?.depths ?? [])

const viewed = ref<number | null>(null)
watch(() => props.proposal, () => { viewed.value = props.proposal?.proposedDepth ?? null }, { immediate: true })

const shownDepth = computed((): number | null => props.readOnly ? viewed.value : (props.modelValue ?? null))

const shown = computed((): any | null => depths.value.find(d => d.depth === shownDepth.value) ?? null)

function choose (depth: number) {
    if (props.readOnly) viewed.value = depth
    else emit('update:modelValue', depth)
}

function lineWords (n: number): string {
    return n === 1 ? '1 line' : `${n} lines`
}
</script>

<style scoped>
.upstream-depth-tag {
    margin-left: 6px;
}
.upstream-depth-fit {
    margin-left: 8px;
    color: #777;
}
.upstream-depth-basis {
    margin-top: 8px;
}
.upstream-depth-preview {
    margin-top: 8px;
}
.upstream-depth-use {
    margin-top: 8px;
}
</style>
