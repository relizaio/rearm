<template>
    <div class="dsec" v-if="(task.usage?.reports ?? 0) > 0">
        <div class="dsec__h">Usage</div>
        <agent-usage-summary :usage="task.usage" :show-by-model="false" />
    </div>
</template>

<script lang="ts" setup>
import AgentUsageSummary from '../AgentUsageSummary.vue'

defineProps<{ task: any }>()
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
