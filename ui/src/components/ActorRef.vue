<template>
    <!-- An actor as a person reads it (RD2-11): a session linked and told apart, role first, anyone else by name. -->
    <session-ref v-if="asSession" :session="asSession"/>
    <span v-else>{{ actorLabel(actor) }}</span>
</template>

<script lang="ts" setup>
import { computed, inject, ref } from 'vue'
import SessionRef from './SessionRef.vue'
import { actorLabel } from '@/utils/agentActors'
import { AGENT_DIR, sessionOfActor, sessionOnTask } from '@/utils/agentSessionLabel'

/** The task the actor acted on names the session: the role it worked there and its agent. */
const props = defineProps<{ actor: any, task?: any, role?: string | null }>()
const agents = inject(AGENT_DIR, ref({}))
const asSession = computed(() => {
    const s = sessionOfActor(props.actor, props.role ?? null)
    if (!s || !props.task) return s
    const onTask = sessionOnTask(props.task, s.uuid as string, agents.value, props.role)
    return onTask.name ? onTask : { ...s, role: onTask.role }
})
</script>
