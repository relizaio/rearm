<template>
    <div class="dhead">
        <div class="dhead__title">
            <code v-if="task.key" class="dhead__key">{{ task.key }}</code>
            <!-- The level after the key (RD2-1); none for a task with no level and no board default. -->
            <n-tooltip v-if="levelLabel(task, board)" trigger="hover">
                <template #trigger>
                    <n-tag size="small" :bordered="false" class="dhead__level" data-testid="level-chip">{{ levelLabel(task, board) }}</n-tag>
                </template>
                {{ levelTooltip(task, board, actorLabel) }}
            </n-tooltip>
            <!-- The group beside the level, in its colour (RD2-31). -->
            <n-tooltip v-if="task.group?.key" trigger="hover">
                <template #trigger>
                    <n-tag size="small" :bordered="false" class="dhead__group" data-testid="group-chip"
                           :color="{ color: `${groupColour(task.group.key)}22`, textColor: groupColour(task.group.key) ?? undefined }">
                        {{ task.group.key }}
                    </n-tag>
                </template>
                {{ groupLabel(groupByKey(board, task.group.key) ?? task.group) }}
            </n-tooltip>
            {{ task.title }}
        </div>
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
                {{ statusWord(task.status) }}
            </n-tag>
            <n-tooltip v-if="roleTag" trigger="hover" :disabled="!roleTag.tooltip">
                <template #trigger>
                    <n-tag size="small" :bordered="false" :type="roleTag.kind === 'current' && task.status === 'ASSIGNED' ? 'primary' : 'default'"
                           :class="{ 'tag--history': roleTag.kind === 'history' }">{{ roleTag.text }}</n-tag>
                </template>
                {{ roleTag.tooltip }}
            </n-tooltip>
            <n-tag v-if="waitingOnLabel(task)" size="small" :bordered="false" type="warning" data-testid="waiting-chip">
                {{ waitingOnLabel(task) }}
            </n-tag>
            <n-tag v-for="k in tagKeys(task)" :key="k" size="small" :bordered="false" round class="dhead__tag"
                   data-testid="tag-chip">#{{ k }}</n-tag>
            <slot/>
        </div>
        <!-- The required roles still to pass, under the status on the page and in the drawer (RD2-23). -->
        <div v-if="requiredLine" class="dhead__required" data-testid="required-signoffs">{{ requiredLine }}</div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NTag, NTooltip } from 'naive-ui'
import { missingRequiredRoles, requiredSignOffsLine, statusTone } from '@/utils/agentTaskFormat'
import { statusWord } from '@/utils/agentWords'
import { refLabel, roleTagFor } from '@/utils/agentTaskLabels'
import { levelLabel, levelTooltip } from '@/utils/agentTaskLevel'
import { groupByKey, groupColour, groupLabel, tagKeys, waitingOnLabel } from '@/utils/agentTaskGroups'
import { actorLabel } from '@/utils/agentActors'

const props = defineProps<{ task: any, board?: any, clamp?: boolean, roles?: any[] }>()
// A board without sources is its own tracker: no task has a ref there, and none is a "draft".
const boardHasSources = computed(() => (props.board?.sources?.length ?? 0) > 0)
const roleTag = computed(() => roleTagFor(props.task))
const requiredLine = computed(() => requiredSignOffsLine(missingRequiredRoles(props.task, props.roles)))
</script>

<style scoped lang="scss">
.dhead {
    &__title { font-size: 15px; font-weight: 600; }
    &__key { margin-right: 8px; font-weight: 600; }
    &__level { margin-right: 8px; vertical-align: 2px; }
    &__group { margin-right: 8px; vertical-align: 2px; font-family: monospace; }
    &__tag { opacity: 0.85; }
    &__desc { margin-top: 4px; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; }
    &__desc--clamped { display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
    &__sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; flex-wrap: wrap; }
    &__required { margin-top: 4px; font-size: 12px; color: #d03050; }
}
/* A role tag that names the last hop, not where the task is now (task 562ac668). */
.tag--history { opacity: 0.75; font-style: italic; }
</style>
