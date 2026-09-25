<template>
    <div class="dsec">
        <div class="dsec__h">History</div>
        <div v-if="!history.length" class="empty">No hops recorded yet.</div>
        <div class="hist">
            <div v-for="(e, i) in history" :key="i" class="hist__row">
                <template v-if="e.kind === 'signoff'">
                    <n-tag size="tiny" :bordered="false"
                           :type="e.rec.outcome === 'PASSED' ? 'success' : 'error'">
                        {{ e.rec.outcome }}
                    </n-tag>
                    <span class="hist__role">{{ e.rec.role }}</span>
                    <n-tag v-if="e.rec.reviewedBy" size="tiny" :bordered="false" type="info">human</n-tag>
                    <span class="hist__agent">{{ actorLabel(e.rec.reviewedBy) || agentName(agentNames, e.rec.agent) }}</span>
                    <span class="hist__time">{{ ts(e.rec.signedOffAt) }}<template v-if="e.rec.assignedAt">
                        · worked {{ dur(e.rec.assignedAt, e.rec.signedOffAt) }}</template></span>
                    <code v-if="e.rec.promptVersion" class="hist__pv"
                          title="Served role-prompt version">{{ e.rec.promptVersion }}</code>
                    <span v-if="hopHasUsage(e.rec)" class="hist__usage"
                          :title="hopTitle(e.rec)">{{ hopLabel(e.rec) }}</span>
                    <div v-if="hopOutputs(e.rec).length" class="hist__outputs">
                        <span v-for="o in hopOutputs(e.rec)" :key="o.uuid ?? ''" class="hist__output">
                            <a v-if="documentFileUrl(o)" :href="documentFileUrl(o) ?? undefined"
                               target="_blank" rel="noopener">{{ documentLabel(o) }}</a>
                            <span v-else>{{ documentLabel(o) }}</span>
                        </span>
                    </div>
                    <div v-if="e.rec.note" class="hist__note">{{ e.rec.note }}</div>
                </template>
                <template v-else>
                    <n-tag size="tiny" :bordered="false" type="warning">RETURNED</n-tag>
                    <span class="hist__role">{{ e.rec.role }}</span>
                    <span class="hist__agent">{{ agentName(agentNames, e.rec.agent) }}</span>
                    <span class="hist__time">{{ ts(e.rec.returnedAt) }} · {{ e.rec.reason }}</span>
                    <span v-if="hopHasUsage(e.rec)" class="hist__usage"
                          :title="hopTitle(e.rec)">{{ hopLabel(e.rec) }}</span>
                    <div v-if="hopOutputs(e.rec).length" class="hist__outputs">
                        <span v-for="o in hopOutputs(e.rec)" :key="o.uuid ?? ''" class="hist__output">
                            <a v-if="documentFileUrl(o)" :href="documentFileUrl(o) ?? undefined"
                               target="_blank" rel="noopener">{{ documentLabel(o) }}</a>
                            <span v-else>{{ documentLabel(o) }}</span>
                        </span>
                    </div>
                    <div v-if="e.rec.description" class="hist__note">{{ e.rec.description }}</div>
                </template>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The hop log: sign-offs and returns, each with its cost and the documents it recorded.
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import { actorLabel } from '@/utils/agentActors'
import { costLabel, formatTokens, totalTokens } from '@/utils/agentUsage'
import { DocumentRelease, documentFileUrl, documentLabel, outputsOfHop } from '@/utils/agentDocuments'
import { agentName, dur, hopHistory, ts } from '@/utils/agentTaskFormat'

const props = defineProps<{ task: any, agentNames: Record<string, string> }>()

const history = computed(() => hopHistory(props.task))
const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])

// The documents a hop recorded as its outputs. A hop stores uuids and the task carries the
// releases, so they are resolved here rather than holding two shapes of the same thing.
function hopOutputs (rec: any): DocumentRelease[] {
    return outputsOfHop(rec?.outputs, taskDocuments.value)
}

// Per-hop cost, shown inline on the history row rather than in a column: a hop
// that cost nothing to report is the common case, and an always-present column
// of dashes would push the role and agent off the row for no gain.
function hopHasUsage (rec: any): boolean {
    return (rec?.usage?.reports ?? 0) > 0
}

function hopLabel (rec: any): string {
    return costLabel(rec.usage) + ' · ' + formatTokens(totalTokens(rec.usage)) + ' tok'
}

function hopTitle (rec: any): string {
    const u = rec.usage ?? {}
    return `${u.requests ?? 0} requests, ${u.turns ?? 0} turns\n` +
        `in ${formatTokens(u.inputTokens)} · out ${formatTokens(u.outputTokens)} · ` +
        `cache read ${formatTokens(u.cacheReadTokens)} · cache write ${formatTokens(u.cacheWriteTokens)}` +
        (u.costComplete === false ? '\nSome rows had no applicable price: the cost is a lower bound.' : '')
}
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
