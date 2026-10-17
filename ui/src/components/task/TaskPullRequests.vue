<template>
    <div v-if="task.prUrls?.length || task.unlinkedPrs?.length" class="dsec">
        <div class="dsec__h">Pull requests</div>
        <div v-if="task.status === 'DELIVERING'" class="holdmeta" style="margin: 0 0 6px">
            Every required role passed; the task completes when these merge.
        </div>
        <!-- A delivery the seat parked for the operator (RD4-17): declaring or superseding here answers it. -->
        <div v-if="actingAnswers(task)" class="holdmeta" style="margin: 0 0 6px" data-testid="acting-answers">
            {{ actingAnswers(task) }}
        </div>
        <div v-for="c in prChips(task)" :key="c.url" class="prrow">
            <div class="deprow">
                <!-- How delivery counts the PR (task t20261010-033525-24393), on hover. -->
                <n-tag size="small" :bordered="false" :type="c.type" :title="c.unit" data-testid="pr-state">{{ c.state }}</n-tag>
                <a :href="c.url" target="_blank" rel="noopener" class="prlink2" :class="{ struck: c.superseded }">{{ c.label }}</a>
                <span class="holdmeta" style="margin-top: 0">{{ c.title }}</span>
                <!-- The head that passed against the PR's head now (task 3b97ccfd). -->
                <n-tag v-if="c.heads" size="small" :bordered="false" class="prheads" :type="c.moved ? 'error' : 'default'">
                    {{ c.heads }}
                </n-tag>
                <!-- Commits CI reported on the PR's base since the round (task RD4-2): not a claim that it conflicts. -->
                <n-tag v-if="c.baseMoved" size="small" :bordered="false" class="prbase" type="warning" data-testid="base-moved">
                    {{ c.baseMoved }}
                </n-tag>
                <!-- A PR whose CI does not report here is declared by a person (RD2-10): BOARD_WRITE. -->
                <template v-if="declaring && declarable(c) && !drafts[c.url]">
                    <n-button size="tiny" data-testid="declare-merge" @click="open(c.url, 'DELIVERED')">Declare merge…</n-button>
                    <n-button size="tiny" quaternary data-testid="mark-abandoned" @click="open(c.url, 'ABANDONED')">Mark abandoned…</n-button>
                </template>
                <!-- A closed PR replaced by another is declared superseded (task RD3-18): BOARD_WRITE. -->
                <disabled-hint v-if="!terminal && offersSupersede(task, c.url) && !supersedes[c.url]"
                               :reason="supersedeDisabledReason(task, c.url, !!canOperate)">
                    <n-button size="tiny" quaternary data-testid="declare-superseded"
                              :disabled="!!supersedeDisabledReason(task, c.url, !!canOperate)"
                              @click="openSupersede(c.url)">Declare superseded…</n-button>
                </disabled-hint>
                <!-- A PR that should never have counted is unlinked (task t20261010-033525-24393): BOARD_WRITE. -->
                <disabled-hint v-if="offersUnlink(task, c.url) && !unlinks[c.url]"
                               :reason="unlinkDisabledReason(task, c.url, !!canOperate)">
                    <n-button size="tiny" quaternary data-testid="unlink-pr"
                              :disabled="!!unlinkDisabledReason(task, c.url, !!canOperate)"
                              @click="openUnlink(c.url)">Unlink…</n-button>
                </disabled-hint>
            </div>
            <div v-if="unlinks[c.url]" class="declare" :data-unlink="c.url">
                <span v-if="unlinkWarning(task, c.url)" class="declare__err" data-testid="unlink-warning">
                    {{ unlinkWarning(task, c.url) }}
                </span>
                <n-input v-model:value="unlinks[c.url].note" size="small" data-testid="unlink-note" :maxlength="DESCRIPTION_MAX"
                         show-count placeholder="Why it should never have counted (optional)"/>
                <n-space :size="6">
                    <n-button size="tiny" type="warning" data-testid="unlink-submit" @click="submitUnlink(c.url)">Unlink</n-button>
                    <n-button size="tiny" quaternary @click="closeUnlink(c.url)">Cancel</n-button>
                </n-space>
            </div>
            <div v-if="supersedes[c.url]" class="declare" :data-supersede="c.url">
                <n-select v-model:value="supersedes[c.url].byUrl" size="small" data-testid="supersede-by"
                          placeholder="The PR that replaces it" :options="candidateOptions(c.url)"/>
                <n-input v-model:value="supersedes[c.url].note" size="small" data-testid="supersede-note"
                         placeholder="Why it was replaced (optional)"/>
                <n-space :size="6">
                    <n-button size="tiny" type="primary" data-testid="supersede-submit"
                              :disabled="!supersedePayload(task, c.url, supersedes[c.url].byUrl, supersedes[c.url].note)"
                              @click="submitSupersede(c.url)">Declare superseded</n-button>
                    <n-button size="tiny" quaternary @click="closeSupersede(c.url)">Cancel</n-button>
                </n-space>
            </div>
            <div v-if="drafts[c.url]" class="declare" :data-declare="c.url">
                <n-input v-model:value="drafts[c.url].unit" size="small" placeholder="Unit: the PR" data-testid="declare-unit"/>
                <template v-if="drafts[c.url].outcome === 'DELIVERED'">
                    <n-input v-model:value="drafts[c.url].commit" size="small" placeholder="Merge commit (7 to 40 hex)"
                             data-testid="declare-commit" :status="drafts[c.url].commit && commitProblem(drafts[c.url].commit) ? 'error' : undefined"/>
                    <span v-if="drafts[c.url].commit && commitProblem(drafts[c.url].commit)" class="declare__err" data-testid="declare-commit-error">
                        {{ commitProblem(drafts[c.url].commit) }}
                    </span>
                </template>
                <n-input v-model:value="drafts[c.url].note" size="small" data-testid="declare-note"
                         :placeholder="drafts[c.url].outcome === 'ABANDONED' ? 'Why it will not land (required)' : 'Note (optional)'"/>
                <n-space :size="6">
                    <n-button size="tiny" :type="drafts[c.url].outcome === 'ABANDONED' ? 'error' : 'primary'" data-testid="declare-submit"
                              :disabled="!declarationPayload(task, drafts[c.url])" @click="submit(c.url)">
                        {{ drafts[c.url].outcome === 'ABANDONED' ? 'Mark abandoned' : 'Declare merged' }}
                    </n-button>
                    <n-button size="tiny" quaternary @click="close(c.url)">Cancel</n-button>
                </n-space>
            </div>
        </div>
        <!-- PRs taken off the task, append-only; a relink stamps the row (task t20261010-033525-24393). -->
        <template v-if="task.unlinkedPrs?.length">
            <div class="dsec__h unlinked__h">Unlinked</div>
            <div v-for="(u, i) in task.unlinkedPrs" :key="u.url + i" class="holdmeta unlinked" data-testid="unlinked-pr">
                <a :href="u.url" target="_blank" rel="noopener" class="prlink2">{{ shortPr(u.url) }}</a>
                unlinked by <actor-ref :actor="u.by" :task="task"/> <agent-time :at="u.at"/><template v-if="u.note">: {{ u.note }}</template>
                <template v-if="u.relinkedAt"> · re-linked by {{ u.relinkedBy || 'someone' }} <agent-time :at="u.relinkedAt"/></template>
            </div>
        </template>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NSelect, NSpace, NTag } from 'naive-ui'
import { DeclarationDraft, declarable, declarationDraftOf, declarationPayload, commitProblem, prChips, shortPr } from '@/utils/agentDelivery'
import { actingAnswers, effectiveStatus } from '@/utils/agentOperatorQuestion'
import { DESCRIPTION_MAX } from '@/utils/agentBoardNaming'
import { offersSupersede, offersUnlink, supersedeCandidates, supersedeDisabledReason, supersedePayload,
    unlinkDisabledReason, unlinkPayload, unlinkWarning } from '@/utils/agentTaskAdmin'
import ActorRef from '../ActorRef.vue'
import AgentTime from '../AgentTime.vue'
import DisabledHint from './DisabledHint.vue'

const props = defineProps<{
    task: any
    /** BOARD_WRITE on the task's board: declaring a delivery is the board's verb (RD2-10). */
    canOperate?: boolean
}>()
const emit = defineEmits<{
    (e: 'declare-delivery', p: { task: any, unit: string, commit: string | null, outcome: string, note: string | null }): void
    (e: 'supersede', p: { task: any, oldUrl: string, byUrl: string, note: string | null }): void
    (e: 'unlink', p: { task: any, prUrl: string, note: string | null }): void
}>()

// Only a DELIVERING task waits on its PRs; everywhere else the chips are the record.
// A delivery the seat parked for the operator is declared as a delivery: that answers it (RD4-17).
const declaring = computed(() => !!props.canOperate && effectiveStatus(props.task) === 'DELIVERING')
const drafts = ref<Record<string, DeclarationDraft>>({})

function open (url: string, outcome: 'DELIVERED' | 'ABANDONED') {
    drafts.value = { ...drafts.value, [url]: declarationDraftOf(url, outcome) }
}

function close (url: string) {
    const next = { ...drafts.value }
    delete next[url]
    drafts.value = next
}

function submit (url: string) {
    const p = declarationPayload(props.task, drafts.value[url])
    if (!p) return
    emit('declare-delivery', p)
    close(url)
}

// Declaring a PR superseded (task RD3-18): any status but the terminal ones, as the server takes it.
const terminal = computed(() => ['COMPLETED', 'CANCELLED'].includes(props.task?.status))
const supersedes = ref<Record<string, { byUrl: string | null, note: string }>>({})

function candidateOptions (url: string) {
    return supersedeCandidates(props.task, url).map(u => ({ label: shortPr(u), value: u }))
}

function openSupersede (url: string) {
    const only = supersedeCandidates(props.task, url)
    supersedes.value = { ...supersedes.value, [url]: { byUrl: only.length === 1 ? only[0] : null, note: '' } }
}

function closeSupersede (url: string) {
    const next = { ...supersedes.value }
    delete next[url]
    supersedes.value = next
}

function submitSupersede (url: string) {
    const d = supersedes.value[url]
    const p = d ? supersedePayload(props.task, url, d.byUrl, d.note) : null
    if (!p) return
    emit('supersede', p)
    closeSupersede(url)
}

// Unlinking a PR (task t20261010-033525-24393): a note, then the server's rules.
const unlinks = ref<Record<string, { note: string }>>({})

function openUnlink (url: string) {
    unlinks.value = { ...unlinks.value, [url]: { note: '' } }
}

function closeUnlink (url: string) {
    const next = { ...unlinks.value }
    delete next[url]
    unlinks.value = next
}

function submitUnlink (url: string) {
    const d = unlinks.value[url]
    if (!d) return
    emit('unlink', unlinkPayload(props.task, url, d.note))
    closeUnlink(url)
}

watch(() => props.task?.uuid, () => { drafts.value = {}; supersedes.value = {}; unlinks.value = {} })
</script>

<style scoped lang="scss">
@use './taskSections';

.declare {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 4px 0 8px 12px;
    max-width: 420px;
    &__err { font-size: 11px; color: #d03050; }
}

/* A PR declared superseded by its replacement (task RD3-13): kept in the record, struck through. */
.struck { text-decoration: line-through; opacity: 0.7; }

/* The PRs taken off the task (task t20261010-033525-24393). */
.unlinked__h { margin-top: 10px; }
.unlinked { margin-top: 2px; }
</style>
