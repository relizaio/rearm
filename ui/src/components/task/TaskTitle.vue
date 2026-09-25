<template>
    <div class="dhead">
        <div class="dhead__title">{{ task.title }}</div>
        <div class="dhead__sub">
            <a v-if="task.sourceUrl" :href="task.sourceUrl" target="_blank" rel="noopener">
                {{ refLabel(task, boardHasSources) ?? 'link' }}
            </a>
            <span v-else-if="refLabel(task, boardHasSources)">{{ refLabel(task, boardHasSources) }}</span>
            <n-tag size="small" :bordered="false" :type="statusTone(task.status)">
                {{ task.status.replace(/_/g, ' ') }}
            </n-tag>
            <n-tooltip v-if="roleTag" trigger="hover" :disabled="!roleTag.tooltip">
                <template #trigger>
                    <n-tag size="small" :bordered="false" :type="roleTag.kind === 'current' && task.status === 'ASSIGNED' ? 'primary' : 'default'"
                           :class="{ 'tag--history': roleTag.kind === 'history' }">{{ roleTag.text }}</n-tag>
                </template>
                {{ roleTag.tooltip }}
            </n-tooltip>
            <slot/>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NTag, NTooltip } from 'naive-ui'
import { statusTone } from '@/utils/agentTaskFormat'
import { refLabel, roleTagFor } from '@/utils/agentTaskLabels'

const props = defineProps<{ task: any, board?: any }>()
// A board without sources is its own tracker: no task has a ref there, and none is a "draft".
const boardHasSources = computed(() => (props.board?.sources?.length ?? 0) > 0)
const roleTag = computed(() => roleTagFor(props.task))
</script>

<style scoped lang="scss">
.dhead {
    &__title { font-size: 15px; font-weight: 600; }
    &__sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; flex-wrap: wrap; }
}
/* A role tag that names the last hop, not where the task is now (task 562ac668). */
.tag--history { opacity: 0.75; font-style: italic; }
</style>
