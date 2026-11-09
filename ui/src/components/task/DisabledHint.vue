<template>
    <!-- A disabled button says why on hover (RD2-16): the tooltip hangs on a wrapper, as a disabled
         button takes no pointer events of its own. With no reason the button still sits in an element:
         a bare slot renders a fragment, and a popconfirm whose trigger is this component then loses
         the click it attaches, so its confirm never opens. -->
    <n-tooltip v-if="reason" trigger="hover">
        <template #trigger><span class="dhint" :data-hint="reason"><slot/></span></template>
        {{ reason }}
    </n-tooltip>
    <span v-else><slot/></span>
</template>

<script lang="ts" setup>
import { NTooltip } from 'naive-ui'

defineProps<{ reason: string | null }>()
</script>
