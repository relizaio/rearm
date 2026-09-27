<template>
    <div class="dhead">
        <div class="dhead__title"><code v-if="task.key" class="dhead__key">{{ task.key }}</code>{{ task.title }}</div>
        <!-- What the task is beyond its one-line title (task fceb1e57): whole on the page, the first
             lines in the drawer's preview with the rest on hover. -->
        <div v-if="task.description" class="dhead__desc" :class="{ 'dhead__desc--clamped': clamp }"
             :title="clamp ? task.description : undefined">{{ task.description }}</div>
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

const props = defineProps<{ task: any, board?: any, clamp?: boolean }>()
// A board without sources is its own tracker: no task has a ref there, and none is a "draft".
const boardHasSources = computed(() => (props.board?.sources?.length ?? 0) > 0)
const roleTag = computed(() => roleTagFor(props.task))
</script>

<style scoped lang="scss">
.dhead {
    &__title { font-size: 15px; font-weight: 600; }
    &__key { margin-right: 8px; font-weight: 600; }
    &__desc { margin-top: 4px; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; }
    &__desc--clamped { display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
    &__sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; flex-wrap: wrap; }
}
/* A role tag that names the last hop, not where the task is now (task 562ac668). */
.tag--history { opacity: 0.75; font-style: italic; }
</style>
