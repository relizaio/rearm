<template>
    <div v-if="task.prUrls?.length" class="dsec">
        <div class="dsec__h">Pull requests</div>
        <div v-if="task.status === 'DELIVERING'" class="holdmeta" style="margin: 0 0 6px">
            Every required role passed; the task completes when these merge.
        </div>
        <div v-for="c in prChips(task)" :key="c.url" class="deprow">
            <n-tag size="small" :bordered="false" :type="c.type">{{ c.state }}</n-tag>
            <a :href="c.url" target="_blank" rel="noopener" class="prlink2">{{ c.label }}</a>
            <span class="holdmeta" style="margin-top: 0">{{ c.title }}</span>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { NTag } from 'naive-ui'
import { prChips } from '@/utils/agentDelivery'

defineProps<{ task: any }>()
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
