<template>
    <div class="dsec">
        <div class="dsec__h">Hops</div>
        <div v-if="!history.length" class="empty">No hops recorded yet.</div>
        <div class="hist">
            <div v-for="(e, i) in history" :key="i" class="hist__row">
                <template v-if="e.kind === 'signoff'">
                    <n-tag size="tiny" :bordered="false"
                           :type="e.rec.outcome === 'PASSED' ? 'success' : 'error'">
                        {{ outcomeWord(e.rec.outcome) }}
                    </n-tag>
                    <span v-if="e.rec.reviewedBy || !e.rec.session" class="hist__role">{{ e.rec.role }}</span>
                    <n-tag v-if="e.rec.reviewedBy" size="tiny" :bordered="false" type="info">human</n-tag>
                    <!-- The architect's sign-off said the design fits one coder pass (task TPF-153): the server's noSplit. -->
                    <n-tag v-if="e.rec.noSplit" size="tiny" :bordered="false" type="warning" data-testid="hop-no-split">--no-split</n-tag>
                    <!-- The session that worked the hop, role first and linked (RD2-11); a person's verdict by name. -->
                    <span class="hist__agent"><actor-ref v-if="e.rec.reviewedBy" :actor="e.rec.reviewedBy"/><session-ref
                        v-else-if="e.rec.session" :session="sessionOf(e.rec.session, e.rec.agent, agentDir, e.rec.role)"/><template
                        v-else>{{ agentName(agentNames, e.rec.agent) }}</template></span>
                    <span class="hist__time"><agent-time :at="e.rec.signedOffAt"/><template v-if="e.rec.assignedAt">
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
                    <div v-if="reviewedChips(e.rec).length" class="hist__reviewed">
                        <n-tooltip v-for="c in reviewedChips(e.rec)" :key="c.release" :disabled="!c.title" trigger="hover">
                            <template #trigger>
                                <n-tag size="tiny" :bordered="false" :type="c.type" class="hist__rev">{{ c.label }}</n-tag>
                            </template>
                            {{ c.title }}
                        </n-tooltip>
                    </div>
                    <div v-if="e.rec.note" class="hist__note">{{ e.rec.note }}</div>
                </template>
                <!-- The design checkpoint's one row (task SPLIT-8), in the words of its phase. The tag, role and time are the
                     row's own children, as in the branches above, so the row's flex gap spaces them (T-40). -->
                <template v-else-if="e.kind === 'checkpoint'">
                    <n-tag size="tiny" :bordered="false" data-testid="hop-checkpoint" :type="e.phase === 'waiting' ? 'warning' : 'default'">design checkpoint</n-tag>
                    <span class="hist__role">before {{ e.rec.beforeRole }}</span>
                    <span class="hist__time"><agent-time :at="e.rec.reachedAt"/> · <template
                        v-if="e.phase === 'waiting'">waiting for the coordinator<template v-if="waiverIgnored(task)">; --no-split by
                        {{ task.designCheckpointWaiver.role }} ignored: the board's architectCheckpointWaiver is off</template></template><template
                        v-else-if="e.phase === 'ahead'">now with {{ roleAhead(task) }}</template><template
                        v-else-if="e.phase === 'closed'">closed from the checkpoint</template><template
                        v-else-if="e.phase === 'lapsed'">not settled; the task went on to {{ roleAhead(task) }}</template><template
                        v-else-if="checkpointWaiver(e.rec)">{{ waivedPhrase(e.rec) }}<template v-if="e.rec.settledBy"> by <actor-ref
                        :actor="e.rec.settledBy" :task="task"/></template> <agent-time v-if="e.rec.settledAt" :at="e.rec.settledAt"/></template><template
                        v-else>{{ e.phase === 'split' ? 'split' : 'sent on' }}<template v-if="e.rec.settledBy"> by <actor-ref
                        :actor="e.rec.settledBy" :task="task"/></template> <agent-time v-if="e.rec.settledAt" :at="e.rec.settledAt"/></template></span>
                    <!-- A waived record's note is the phrase above (task TPF-153), not printed twice. -->
                    <div v-if="e.rec.note && !checkpointWaiver(e.rec)" class="hist__note">{{ e.rec.note }}</div>
                </template>
                <!-- The split request's one row (task SPLIT-13), in the words of its state. Its reason is the SPLIT return's
                     note just above; the proposed children follow as a numbered list. -->
                <template v-else-if="e.kind === 'split_request'">
                    <n-tag size="tiny" :bordered="false" data-testid="hop-split-request"
                           :type="splitRequestWaits(task) ? 'warning' : 'default'">split request</n-tag>
                    <span class="hist__role">by {{ e.rec.role }}</span>
                    <span class="hist__time hist__time--wrap"><agent-time :at="e.rec.requestedAt"/> · {{ e.rec.proposedChildren?.length ? childCount(e.rec.proposedChildren.length) + ' proposed' : 'no children proposed' }} · <template
                        v-if="e.rec.state === 'REQUESTED'">requested<template
                        v-if="splitRequestWaits(task)">, waiting for the coordinator</template></template><template
                        v-else-if="e.rec.state === 'LAPSED'">lapsed <agent-time v-if="e.rec.settledAt" :at="e.rec.settledAt"/></template><template
                        v-else>{{ e.rec.state === 'DECLINED' ? 'declined' : enumWord(e.rec.state) }}<template v-if="e.rec.settledBy"> by <actor-ref
                        :actor="e.rec.settledBy" :task="task"/></template> <agent-time v-if="e.rec.settledAt" :at="e.rec.settledAt"/></template><template
                        v-if="e.rec.state !== 'REQUESTED' && e.rec.note">: {{ e.rec.note }}</template></span>
                    <ol v-if="e.rec.proposedChildren?.length" class="hist__children" data-testid="split-request-children">
                        <li v-for="(c, j) in e.rec.proposedChildren" :key="j" :title="c.description ?? undefined">{{ proposedChildTitle(c) }}<template
                            v-if="c.dependsOnSiblingIndexes?.length"> · after {{ c.dependsOnSiblingIndexes.map((x: number) => x + 1).join(', ') }}</template><template
                            v-if="c.workLevel != null"> · level {{ c.workLevel }}</template></li>
                    </ol>
                </template>
                <!-- A role routing passed over because its runWhen was not met (task TPF-156): muted, one row per entry; the
                     reason is routing's text, never a worker's. -->
                <template v-else-if="e.kind === 'condition_skip'">
                    <n-tag size="tiny" :bordered="false" data-testid="hop-passed-over" class="hist__muted"
                           :class="{ 'hist__history': e.rec.live === false }">passed over</n-tag>
                    <span class="hist__role hist__muted" :class="{ 'hist__history': e.rec.live === false }">{{ e.rec.role }}</span>
                    <span class="hist__time hist__time--wrap hist__muted" :class="{ 'hist__history': e.rec.live === false }"><agent-time :at="e.rec.at"/> &middot; by routing: {{ e.rec.reason }}<template
                        v-if="e.rec.waives?.length">; waives {{ e.rec.waives.join(', ') }}</template><template
                        v-if="e.rec.live === false"> (since ran)</template></span>
                </template>
                <!-- A split child's carried design (task TPF-164): its own row at the record's at, by the seat that split; the
                     words come from the record, one shared phrase per carried pass, and no session is printed. -->
                <template v-else-if="e.kind === 'carried_design'">
                    <n-tag size="tiny" :bordered="false" data-testid="hop-carried-design"
                           :class="{ 'hist__history': e.rec.standing === false }">design carried</n-tag>
                    <span class="hist__role">from {{ e.rec.parentKey }}</span>
                    <span class="hist__time hist__time--wrap"><agent-time :at="e.rec.at"/><template v-if="e.rec.by"> &middot; by <actor-ref
                        :actor="e.rec.by" :task="task"/></template></span>
                    <div class="hist__note" data-testid="carried-design">{{ carriedDesignLine(task) }}</div>
                </template>
                <template v-else>
                    <n-tag size="tiny" :bordered="false" type="warning">returned</n-tag>
                    <span v-if="!e.rec.session" class="hist__role">{{ e.rec.role }}</span>
                    <span class="hist__agent"><session-ref v-if="e.rec.session" :session="sessionOf(e.rec.session, e.rec.agent, agentDir, e.rec.role)"/><template
                        v-else>{{ agentName(agentNames, e.rec.agent) }}</template></span>
                    <span class="hist__time"><agent-time :at="e.rec.returnedAt"/> · {{ enumWord(e.rec.reason) }}</span>
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
import ActorRef from '../ActorRef.vue'
import AgentTime from '../AgentTime.vue'
import SessionRef from '../SessionRef.vue'
import { AgentName, sessionOf } from '@/utils/agentSessionLabel'
// The hop log: sign-offs and returns, each with its cost, the documents it recorded and, for a
// review, what it reviewed and promoted (task fda2c9f1).
import { computed } from 'vue'
import { NTag, NTooltip } from 'naive-ui'
import { actorLabel } from '@/utils/agentActors'
import { costLabel, formatTokenSplit, formatTokens, totalTokens } from '@/utils/agentUsage'
import { DocumentRelease, documentFileUrl, documentLabel, outputsOfHop } from '@/utils/agentDocuments'
import { agentName, dur, hopHistory } from '@/utils/agentTaskFormat'
import { checkpointWaiver, childCount, proposedChildTitle, roleAhead, splitRequestWaits, waiverIgnored } from '@/utils/agentTaskLabels'
import { carriedDesignLine } from '@/utils/agentCarriedDesign'
import { enumWord, outcomeWord } from '@/utils/agentWords'
import { reviewedChips } from '@/utils/agentReviewed'

const props = defineProps<{ task: any, agentNames: Record<string, string>, agentDir?: Record<string, AgentName> }>()

const history = computed(() => hopHistory(props.task))

/** A waived checkpoint's phrase (task TPF-153): the role and the note, or the role alone for a blind reader. */
function waivedPhrase (rec: any): string {
    const w = checkpointWaiver(rec)
    if (!w) return ''
    return w.note ? `checkpoint waived by ${w.role}: ${w.note}` : `checkpoint waived by ${w.role}`
}
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
    const split = formatTokenSplit(u)
    return `${u.requests ?? 0} requests, ${u.turns ?? 0} turns` + (split ? `\n${split}` : '') +
        (u.costComplete === false ? '\nSome rows had no applicable price: the cost is a lower bound.' : '')
}
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
