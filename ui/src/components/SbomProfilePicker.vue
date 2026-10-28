<template>
    <div class="sbom-profile-picker-root">
        <n-select
            data-testid="sbom-profile-picker"
            :value="selectValue"
            :options="options"
            :multiple="multi"
            :max-tag-count="10"
            :filterable="false"
            :virtual-scroll="false"
            :disabled="disabled"
            :node-props="nodeProps"
            :placeholder="multi ? 'Choose one or more SBOM profiles, in publishing order' : 'Choose the SBOM profile'"
            @update:value="onUpdate" />
        <div v-for="l in lines" :key="l.name" data-testid="sbom-picker-line" :class="['sbom-picker-line', { 'text-danger': l.danger }]">
            {{ l.text }}
        </div>
    </div>
</template>

<script setup lang="ts">
// The SBOM profile picker (task TEA-30, design 3.3.5): the catalog's names at a scope, one on CE or
// where the host forces one (the package editor), several in publishing order on Pro. It never
// disables an option and never blocks the host's Save: a CONFLICT or stale name is marked and can
// always be dropped by its tag's close button; the server's refusal is the rule.
import { computed } from 'vue'
import { NSelect } from 'naive-ui'
import { PickerOption, sbomPickerLines, sbomPickerMultiple, sbomPickerOptions } from '@/utils/sbomProfile'

const props = withDefaults(defineProps<{
    modelValue: string[]
    entries?: any[]
    installationType?: string
    disabled?: boolean
    names?: Record<string, string>
    single?: boolean
}>(), {
    entries: () => [],
    installationType: 'OSS',
    disabled: false,
    names: () => ({}),
    single: false,
})

const emit = defineEmits<{ (e: 'update:modelValue', value: string[]): void }>()

const multi = computed(() => sbomPickerMultiple(props.installationType, props.single))
const selected = computed(() => (props.modelValue ?? []).filter(Boolean))
const options = computed(() => sbomPickerOptions(props.entries, selected.value, props.names))
const lines = computed(() => sbomPickerLines(props.entries, selected.value, props.names))
const selectValue = computed(() => multi.value ? selected.value : (selected.value[0] ?? null))

function nodeProps (option: any) {
    return { title: (option as PickerOption).title ?? '' }
}

function onUpdate (value: string | string[] | null) {
    if (multi.value) emit('update:modelValue', Array.isArray(value) ? [...value] : [])
    else emit('update:modelValue', value == null || value === '' ? [] : [Array.isArray(value) ? value[0] : value])
}
</script>

<style scoped>
/* The root takes the form item's width: inside Naive's flex form item a widthless div shrinks to its
   content, so an empty multiple select collapsed and its menu (as wide as the trigger) showed blank. */
.sbom-profile-picker-root {
    flex: 1;
    min-width: 0;
    width: 100%;
}
.sbom-picker-line {
    font-size: 0.85em;
    margin-top: 2px;
}
</style>
