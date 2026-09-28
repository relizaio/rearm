<template>
    <span class="agent-time" :title="tsFull(at) || undefined">{{ ts(at) }}</span>
</template>

<script lang="ts" setup>
// A time on a board surface (task RD2-23, sweep UI-16): the one format, the full timestamp on hover. One root
// element, so a class set where it is used (revhist__at) lands on it.
import { ts, tsFull } from '@/utils/agentTaskFormat'

defineProps<{ at: string | null | undefined }>()
</script>
