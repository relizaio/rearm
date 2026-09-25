<template>
    <div v-if="task.statusHistory?.length" class="dsec">
        <div class="dsec__h">Status history</div>
        <div class="shist">
            <div v-for="(c, i) in task.statusHistory" :key="i" class="shist__row">
                <span class="shist__time">{{ ts(c.at) }}</span>
                <span class="shist__arrow">{{ (c.from ?? '·').toLowerCase().replace(/_/g, ' ') }} → {{ c.to.toLowerCase().replace(/_/g, ' ') }}</span>
                <code class="shist__trig">{{ c.trigger }}</code>
                <span v-if="actorLabel(c.actor)" class="shist__by">by {{ actorLabel(c.actor) }}</span>
                <span v-if="c.note" class="shist__note">“{{ c.note }}”</span>
                <span v-if="i > 0" class="shist__dur">+{{ dur(task.statusHistory[i-1].at, c.at) || '0m' }}</span>
            </div>
        </div>
    </div>

    <div class="dsec">
        <div class="dsec__h">Provenance</div>
        <div class="prov">
            <div>created {{ ts(task.createdDate) }}<template v-if="task.completedAt"> · completed {{ ts(task.completedAt) }}</template></div>
            <div v-if="task.registeredBySession">registered by session <code>{{ shortId(task.registeredBySession) }}</code></div>
            <div v-if="task.sessions?.length">worked by {{ task.sessions.length }} session{{ task.sessions.length > 1 ? 's' : '' }}:
                <code v-for="s in task.sessions" :key="s" class="sesschip">{{ shortId(s) }}</code>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
// Status transitions and where the task came from.
import { actorLabel } from '@/utils/agentActors'
import { dur, shortId, ts } from '@/utils/agentTaskFormat'

defineProps<{ task: any }>()
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
