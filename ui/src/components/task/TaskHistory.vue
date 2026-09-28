<template>
    <div v-if="task.statusHistory?.length" class="dsec">
        <div class="dsec__h">Status history</div>
        <div class="shist">
            <div v-for="(c, i) in task.statusHistory" :key="i" class="shist__row">
                <span class="shist__time"><agent-time :at="c.at"/></span>
                <span class="shist__arrow">{{ statusRowWords(c).arrow }}</span>
                <!-- Routing says why and where to (RD2-23): "routing: findings decided; back to designer". -->
                <span v-if="statusRowWords(c).routing" class="shist__note" data-testid="routing-note">{{ statusRowWords(c).routing }}</span>
                <template v-else>
                    <code class="shist__trig">{{ statusRowWords(c).trigger }}</code>
                    <span v-if="actorLabel(c.actor)" class="shist__by">by <actor-ref :actor="c.actor" :task="task"/></span>
                    <span v-if="c.note" class="shist__note">“{{ c.note }}”</span>
                </template>
                <span v-if="i > 0" class="shist__dur">+{{ dur(task.statusHistory[i-1].at, c.at) || '0m' }}</span>
            </div>
        </div>
    </div>

    <div class="dsec">
        <div class="dsec__h">Provenance</div>
        <div class="prov">
            <div>created <agent-time :at="task.createdDate"/><template v-if="task.completedAt"> · completed <agent-time :at="task.completedAt"/></template></div>
            <div v-if="task.registeredBySession">registered by session <session-ref :session="sessionWorked(task.registeredBySession)"/></div>
            <div v-if="task.sessions?.length">worked by {{ task.sessions.length }} session{{ task.sessions.length > 1 ? 's' : '' }}:
                <session-ref v-for="s in task.sessions" :key="s" class="sesschip" :session="sessionWorked(s)"/>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
// Status transitions and where the task came from.
import { inject, ref } from 'vue'
import { actorLabel } from '@/utils/agentActors'
import ActorRef from '../ActorRef.vue'
import AgentTime from '../AgentTime.vue'
import SessionRef from '../SessionRef.vue'
import { AGENT_DIR, COORDINATOR_SEAT, sessionOnTask } from '@/utils/agentSessionLabel'
import { dur, statusRowWords } from '@/utils/agentTaskFormat'

const props = defineProps<{ task: any }>()
const agents = inject(AGENT_DIR, ref({}))
const seat = inject(COORDINATOR_SEAT, ref(null))

/** A session on the task, role first and named by its agent (RD2-11). */
function sessionWorked (s: string) {
    return sessionOnTask(props.task, s, agents.value, null, seat.value)
}
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
