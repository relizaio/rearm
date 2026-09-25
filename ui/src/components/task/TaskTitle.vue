<template>
    <div class="dhead">
        <div class="dhead__title">{{ task.title }}</div>
        <div class="dhead__sub">
            <a v-if="task.sourceUrl" :href="task.sourceUrl" target="_blank" rel="noopener">
                {{ (task.externalRef ?? 'draft').replace(/^github:/, '') }}
            </a>
            <span v-else>{{ (task.externalRef ?? 'draft — no tracker ref yet').replace(/^github:/, '') }}</span>
            <n-tag size="small" :bordered="false" :type="statusTone(task.status)">
                {{ task.status.replace(/_/g, ' ') }}
            </n-tag>
            <n-tag v-if="task.role" size="small" :bordered="false">{{ task.role }} · #{{ task.orderIndex }}</n-tag>
            <slot/>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { NTag } from 'naive-ui'
import { statusTone } from '@/utils/agentTaskFormat'

defineProps<{ task: any }>()
</script>

<style scoped lang="scss">
.dhead {
    &__title { font-size: 15px; font-weight: 600; }
    &__sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; flex-wrap: wrap; }
}
</style>
