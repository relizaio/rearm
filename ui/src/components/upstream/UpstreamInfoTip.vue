<template>
    <n-tooltip trigger="hover" placement="top" style="max-width: 420px;">
        <template #trigger>
            <n-icon size="14" class="upstream-info-tip" :data-testid="testid" :aria-label="tip" role="img">
                <Info20Regular />
            </n-icon>
        </template>
        {{ tip }}
    </n-tooltip>
</template>

<script lang="ts" setup>
// An info icon whose tooltip explains the thing beside it (ReARM Pro upstream pages). The icon only,
// for a tag or a sentence; a label with its icon is FieldLabel.
import { NIcon, NTooltip } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'

defineProps<{ tip: string, testid?: string }>()
</script>

<style scoped>
.upstream-info-tip {
    color: #909399;
    cursor: help;
    vertical-align: middle;
    margin-left: 4px;
}
</style>
