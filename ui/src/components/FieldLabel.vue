<template>
    <span style="display: inline-flex; align-items: center; gap: 4px;">
        <span>{{ label }}</span>
        <n-tooltip v-if="tip" trigger="hover" placement="top" style="max-width: 360px;">
            <template #trigger>
                <n-icon size="14" style="color: #909399; cursor: help;">
                    <Info20Regular />
                </n-icon>
            </template>
            {{ tip }}
        </n-tooltip>
    </span>
</template>

<script lang="ts">
export default {
    name: 'FieldLabel'
}
</script>

<script lang="ts" setup>
import { NTooltip, NIcon } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'

defineProps<{ label: string; tip?: string }>()
</script>
