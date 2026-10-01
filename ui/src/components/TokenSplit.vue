<template>
    <!-- A token total and the classes behind it (task RD2-3): one component, so every usage
         surface reads the same. The split is a muted suffix from 1280px up and a tooltip on the
         total below that; compact (table rows, cards) keeps it in the tooltip at every width. -->
    <span class="tokensplit" :class="{ 'tokensplit--compact': compact }">
        <n-tooltip v-if="split" trigger="hover">
            <template #trigger>
                <span class="tokensplit__total">{{ formatTokens(total) }}{{ suffix }}</span>
            </template>
            <span class="tokensplit__tip">{{ split }}</span>
        </n-tooltip>
        <span v-else class="tokensplit__total">{{ formatTokens(total) }}{{ suffix }}</span>
        <span v-if="split && !compact" class="tokensplit__split">{{ split }}</span>
    </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NTooltip } from 'naive-ui'
import { formatTokenSplit, formatTokens, totalTokens, UsageTotals } from '@/utils/agentUsage'

const props = withDefaults(defineProps<{
    usage?: UsageTotals | null,
    compact?: boolean,
    /** Text after the total, e.g. " tok". */
    suffix?: string,
}>(), { usage: null, compact: false, suffix: '' })

const total = computed(() => totalTokens(props.usage))
const split = computed(() => formatTokenSplit(props.usage))
</script>

<style scoped>
.tokensplit__split {
    margin-left: 6px;
    color: #888;
    font-size: 12px;
    font-weight: normal;
}
@media (max-width: 1279px) {
    .tokensplit__split { display: none; }
}
</style>
