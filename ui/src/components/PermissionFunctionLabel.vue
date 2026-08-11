<template>
    <span style="display: inline-flex; align-items: center;">
        {{ label }}
        <n-tooltip v-if="description" trigger="hover">
            <template #trigger>
                <n-icon size="16" style="margin-left: 4px;">
                    <QuestionCircle20Regular />
                </n-icon>
            </template>
            <div style="max-width: 480px; white-space: pre-line;">{{ description }}</div>
        </n-tooltip>
    </span>
</template>

<script lang="ts" setup>
// Label for a single permission function: its display name plus, when one
// exists, a help icon with the function's description tooltip. Replaces the
// per-function v-if chains that were duplicated across the org-wide and
// per-scope function checkbox groups in ScopedPermissions.
import { computed } from 'vue'
import { NTooltip, NIcon } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import commonFunctions from '@/utils/commonFunctions'

const props = defineProps<{ f: string }>()

const label = computed(() => commonFunctions.translateFunctionName(props.f))
const description = computed(() => commonFunctions.translateFunctionDescription(props.f))
</script>
