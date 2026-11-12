<template>
    <!-- FDAW-81 (WRITEUP 6.2): what question 2 = Yes does with the device support statement on this product,
         read from the product's own stored selection. Text interpolated, never v-html. -->
    <div v-if="shown" data-testid="device-fda-statement" class="text-muted" style="font-size: 12px; margin-top: 6px;">
        <span data-testid="device-fda-statement-text">{{ text }}</span>
        <n-button v-if="state === 'EXCLUDED'" text type="primary" size="small" data-testid="device-fda-statement-edit"
            @click="emit('edit')">Edit</n-button>
    </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NButton } from 'naive-ui'
import { statementSetupLine, statementSetupState } from '@/utils/medicalDeviceSetup'

const props = withDefaults(defineProps<{
    status?: any
    answer?: string | null
    componentType?: string | null
}>(), { status: null, answer: null, componentType: null })

const emit = defineEmits<{ (e: 'edit'): void }>()

const state = computed(() => statementSetupState(props.status))
const shown = computed(() => props.answer === 'ENABLED' && props.componentType === 'PRODUCT' && state.value !== null)
const text = computed(() => (state.value ? statementSetupLine(state.value) : ''))
</script>
