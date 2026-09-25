<template>
    <div v-if="task.dependsOn?.length || dependents.length" class="dsec">
        <div class="dsec__h">Dependencies</div>
        <div v-if="task.dependsOn?.length" class="deprow">
            <span class="deplab">after</span>
            <n-tag v-for="d in resolvedDeps" :key="d.uuid" size="small" :bordered="false"
                   :type="d.status === 'COMPLETED' ? 'success' : 'warning'"
                   class="depclick" @click="emit('open', d)">
                {{ taskLabel(d) }} · {{ d.status === 'COMPLETED' ? 'done' : 'pending' }}
            </n-tag>
        </div>
        <div v-if="dependents.length" class="deprow">
            <span class="deplab">blocks</span>
            <n-tag v-for="d in dependents" :key="d.uuid" size="small" :bordered="false"
                   class="depclick" @click="emit('open', d)">
                {{ taskLabel(d) }}
            </n-tag>
        </div>
    </div>

    <div v-if="task.parentTask || task.childTasks?.length" class="dsec">
        <div class="dsec__h">Lineage</div>
        <div class="deprow" v-if="parentTask">
            <span class="deplab">parent</span>
            <n-tag size="small" :bordered="false" type="info" class="depclick"
                   @click="emit('open', parentTask)">{{ taskLabel(parentTask) }}</n-tag>
        </div>
        <div class="deprow" v-if="childTasksResolved.length">
            <span class="deplab">subtasks</span>
            <n-tag v-for="c in childTasksResolved" :key="c.uuid" size="small" :bordered="false"
                   :type="c.status === 'COMPLETED' ? 'success' : 'default'" class="depclick"
                   @click="emit('open', c)">{{ taskLabel(c) }}</n-tag>
        </div>
    </div>
</template>

<script lang="ts" setup>
// Dependencies both ways and the split lineage, resolved against the board's tasks.
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import { taskLabel } from '@/utils/agentTaskFormat'

const props = defineProps<{ task: any, tasks: any[] }>()
const emit = defineEmits<{ (e: 'open', task: any): void }>()

const resolvedDeps = computed(() => (props.task?.dependsOn ?? [])
    .map((d: string) => props.tasks.find(t => t.uuid === d) ?? { uuid: d, title: 'unknown', status: 'UNKNOWN' }))
const dependents = computed(() => props.task
    ? props.tasks.filter(t => (t.dependsOn ?? []).includes(props.task.uuid)) : [])
const parentTask = computed(() => props.task?.parentTask
    ? props.tasks.find(t => t.uuid === props.task.parentTask) ?? null : null)
const childTasksResolved = computed(() => (props.task?.childTasks ?? [])
    .map((c: string) => props.tasks.find(t => t.uuid === c)).filter(Boolean))
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
