<template>
    <n-input ref="inner" :value="value" type="textarea" :autosize="{ minRows, maxRows }" :size="size"
             :placeholder="placeholder" :maxlength="maxlength" :show-count="showCount"
             @update:value="(v: string) => emit('update:value', v)"/>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { NInput } from 'naive-ui'

// One idiom for every instruction, note and reason a person writes to the board (TPF-89): a textarea
// that is one line while short and grows with its content up to maxRows, so Enter starts a new line and
// a paragraph fits. Submitting is always the button beside it. This comment lives here and not in the
// template: a template comment is kept in the dev and test build and would make the root a fragment.
withDefaults(defineProps<{
    /** The text. undefined or null (a v-model target not written yet, e.g. a map entry) renders empty. */
    value?: string | null
    placeholder?: string
    maxlength?: number
    showCount?: boolean
    size?: 'tiny' | 'small' | 'medium' | 'large'
    /** Rows while the text is short: one, so an idle field is as tall as the one-line field it replaces. */
    minRows?: number
    /** Rows before the field scrolls instead of growing. */
    maxRows?: number
}>(), { value: undefined, size: 'small', showCount: false, minRows: 1, maxRows: 12, placeholder: undefined,
    maxlength: undefined })
const emit = defineEmits<{ (e: 'update:value', v: string): void }>()

const inner = ref<InstanceType<typeof NInput> | null>(null)
/** Focuses the textarea: NInput's own focus(). For a parent that holds a template ref (TPF-69's unassign link). */
function focus() { inner.value?.focus() }
defineExpose({ focus })
</script>
