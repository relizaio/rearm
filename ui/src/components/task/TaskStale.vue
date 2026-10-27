<template>
    <!-- A staleness rule the task breaches now (TPF-69), on the marks alone: a DELIVERING task and a queued
         overdue investigation have no assignment, so this is not part of the assignment block. -->
    <div v-if="stale" class="dsec" data-testid="task-stale">
        <div class="dsec__h">Stale</div>
        <div class="tstale" :title="stale.tooltip">{{ stale.label }}</div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { staleChip } from '@/utils/agentTaskStale'

const props = defineProps<{ task: any }>()
const stale = computed(() => staleChip(props.task))
</script>

<style scoped lang="scss">
@use './taskSections';
.tstale { color: #d03050; font-size: 12.5px; }
</style>
