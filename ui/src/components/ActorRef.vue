<template>
    <!-- An actor as a person reads it (RD2-11): a session linked and told apart, anyone else by name. -->
    <session-ref v-if="asSession" :session="asSession"/>
    <span v-else>{{ actorLabel(actor) }}</span>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import SessionRef from './SessionRef.vue'
import { actorLabel } from '@/utils/agentActors'
import { sessionOfActor } from '@/utils/agentSessionLabel'

const props = defineProps<{ actor: any, role?: string | null }>()
const asSession = computed(() => sessionOfActor(props.actor, props.role ?? null))
</script>
