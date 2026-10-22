<template>
    <div class="dsec tsum">
        <div class="dsec__h">Summary</div>
        <div v-if="task.key" class="deprow">
            <span class="deplab">key</span><code>{{ task.key }}</code>
        </div>
        <div class="deprow">
            <span class="deplab">review items</span>
            <template v-if="summary.openReviewItems.length">
                <n-tag v-for="g in summary.openReviewItems" :key="String(g.priority)" size="small" :bordered="false"
                       :type="g.priority === 1 ? 'error' : 'warning'">
                    {{ g.count }} open P{{ g.priority ?? '?' }}
                </n-tag>
            </template>
            <span v-else class="tsum__none">none open</span>
        </div>
        <div v-if="summary.questions" class="deprow">
            <span class="deplab">questions</span>
            <span>{{ summary.questions }}</span>
        </div>
        <div v-if="summary.latestDocuments.length" class="deprow">
            <span class="deplab">documents</span>
            <template v-for="d in summary.latestDocuments" :key="d.uuid ?? ''">
                <a v-if="documentFileUrl(d)" :href="documentFileUrl(d) ?? undefined" target="_blank" rel="noopener"
                   class="tsum__doc">{{ documentLabel(d) }}</a>
                <span v-else class="tsum__doc">{{ documentLabel(d) }}</span>
                <n-tag v-if="advisoryLabel(d)" size="tiny" :bordered="false" type="warning">{{ advisoryLabel(d) }}</n-tag>
            </template>
        </div>
        <div v-if="task.dependsOn?.length || summary.dependencies.blocks" class="deprow">
            <span class="deplab">depends</span>
            <span v-if="task.dependsOn?.length">
                after {{ summary.dependencies.done }} done<template v-if="summary.dependencies.pending">,
                    {{ summary.dependencies.pending }} pending</template>
            </span>
            <span v-if="summary.dependencies.blocks"><template v-if="task.dependsOn?.length">· </template>blocks {{ summary.dependencies.blocks }}</span>
            <n-tag v-for="d in pendingDeps" :key="d.uuid" size="small" :bordered="false" type="warning"
                   class="depclick" @click="emit('open', d)">{{ taskLabel(d) }}</n-tag>
        </div>
        <div v-if="summary.assignment" class="deprow">
            <span class="deplab">assigned</span><span>{{ summary.assignment }}</span>
        </div>
        <!-- A staleness rule the task breaches now (TPF-69), on the marks alone: a DELIVERING task has no assignment. -->
        <div v-if="stale" class="deprow" data-testid="summary-stale">
            <span class="deplab">stale</span><span class="stalemark" :title="stale.tooltip">{{ stale.label }}</span>
            <n-button v-if="unassignLink" text size="tiny" type="error" data-testid="summary-unassign-link"
                      @click="emit('focus-unassign')">unassign</n-button>
        </div>
        <div v-if="summary.usage" class="deprow">
            <span class="deplab">usage</span><span :title="summary.usageSplit ?? undefined" class="usage-line">{{ summary.usage }}</span>
        </div>
        <div v-if="task.prUrls?.length" class="deprow">
            <span class="deplab">PRs</span>
            <n-tag v-for="c in prChips(task)" :key="c.url" size="small" :bordered="false" :type="c.type">
                <a :href="c.url" target="_blank" rel="noopener">{{ c.label }}</a> · {{ c.state }}
            </n-tag>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The drawer's preview: counts and one-liners, enough to decide whether to open the task page.
import { computed } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { advisoryLabel, documentFileUrl, documentLabel } from '@/utils/agentDocuments'
import { prChips } from '@/utils/agentDelivery'
import { taskLabel } from '@/utils/agentTaskFormat'
import { taskSummary } from '@/utils/agentTaskSummary'
import { staleChip } from '@/utils/agentTaskStale'
import { canOperate } from '@/utils/agentBoardAccess'

const props = defineProps<{
    task: any
    tasks: any[]
    roles?: any[]
    agentNames: Record<string, string>
    /** The task's board: the unassign link shows only to a person who may operate it. */
    board?: any
}>()
const emit = defineEmits<{ (e: 'open', task: any): void, (e: 'focus-unassign'): void }>()

const summary = computed(() => taskSummary(props.task, props.tasks, props.roles, props.agentNames))
const stale = computed(() => staleChip(props.task))
// The ALERT's remedy for a stalled hop, one click from the mark: it focuses the Unassign reason, it sends nothing.
const unassignLink = computed(() => stale.value?.rules[0] === 'hopNoProgress' && canOperate(props.board))
// What the task still waits for, one click away in the drawer as before.
const pendingDeps = computed(() => (props.task?.dependsOn ?? [])
    .map((u: string) => props.tasks.find(t => t.uuid === u))
    .filter((t: any) => t && t.status !== 'COMPLETED'))
</script>

<style scoped lang="scss">
@use './taskSections';
.tsum {
    font-size: 12.5px;
    .deplab { min-width: 72px; }
    &__none { color: #999; }
    &__doc { font-size: 12px; margin-right: 4px; }
    .stalemark { color: #d03050; margin-right: 6px; }
}
</style>
