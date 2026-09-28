<template>
    <div v-if="task.prUrls?.length" class="dsec">
        <div class="dsec__h">Pull requests</div>
        <div v-if="task.status === 'DELIVERING'" class="holdmeta" style="margin: 0 0 6px">
            Every required role passed; the task completes when these merge.
        </div>
        <div v-for="c in prChips(task)" :key="c.url" class="prrow">
            <div class="deprow">
                <n-tag size="small" :bordered="false" :type="c.type">{{ c.state }}</n-tag>
                <a :href="c.url" target="_blank" rel="noopener" class="prlink2">{{ c.label }}</a>
                <span class="holdmeta" style="margin-top: 0">{{ c.title }}</span>
                <!-- The head that passed against the PR's head now (task 3b97ccfd). -->
                <n-tag v-if="c.heads" size="small" :bordered="false" class="prheads" :type="c.moved ? 'error' : 'default'">
                    {{ c.heads }}
                </n-tag>
                <!-- A PR whose CI does not report here is attested by a person (RD2-10): BOARD_WRITE. -->
                <template v-if="attesting && attestable(c) && !drafts[c.url]">
                    <n-button size="tiny" data-testid="attest-merge" @click="open(c.url, 'DELIVERED')">Attest merge…</n-button>
                    <n-button size="tiny" quaternary data-testid="mark-abandoned" @click="open(c.url, 'ABANDONED')">Mark abandoned…</n-button>
                </template>
            </div>
            <div v-if="drafts[c.url]" class="attest" :data-attest="c.url">
                <n-input v-model:value="drafts[c.url].unit" size="small" placeholder="Unit: the PR" data-testid="attest-unit"/>
                <template v-if="drafts[c.url].outcome === 'DELIVERED'">
                    <n-input v-model:value="drafts[c.url].commit" size="small" placeholder="Merge commit (7 to 40 hex)"
                             data-testid="attest-commit" :status="drafts[c.url].commit && commitProblem(drafts[c.url].commit) ? 'error' : undefined"/>
                    <span v-if="drafts[c.url].commit && commitProblem(drafts[c.url].commit)" class="attest__err" data-testid="attest-commit-error">
                        {{ commitProblem(drafts[c.url].commit) }}
                    </span>
                </template>
                <n-input v-model:value="drafts[c.url].note" size="small" data-testid="attest-note"
                         :placeholder="drafts[c.url].outcome === 'ABANDONED' ? 'Why it will not land (required)' : 'Note (optional)'"/>
                <n-space :size="6">
                    <n-button size="tiny" :type="drafts[c.url].outcome === 'ABANDONED' ? 'error' : 'primary'" data-testid="attest-submit"
                              :disabled="!attestPayload(task, drafts[c.url])" @click="submit(c.url)">
                        {{ drafts[c.url].outcome === 'ABANDONED' ? 'Mark abandoned' : 'Attest merged' }}
                    </n-button>
                    <n-button size="tiny" quaternary @click="close(c.url)">Cancel</n-button>
                </n-space>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NSpace, NTag } from 'naive-ui'
import { AttestDraft, attestable, attestDraftOf, attestPayload, commitProblem, prChips } from '@/utils/agentDelivery'

const props = defineProps<{
    task: any
    /** BOARD_WRITE on the task's board: attesting a delivery is the board's verb (RD2-10). */
    canOperate?: boolean
}>()
const emit = defineEmits<{
    (e: 'delivered', p: { task: any, unit: string, commit: string | null, outcome: string, note: string | null }): void
}>()

// Only a DELIVERING task waits on its PRs; everywhere else the chips are the record.
const attesting = computed(() => !!props.canOperate && props.task?.status === 'DELIVERING')
const drafts = ref<Record<string, AttestDraft>>({})

function open (url: string, outcome: 'DELIVERED' | 'ABANDONED') {
    drafts.value = { ...drafts.value, [url]: attestDraftOf(url, outcome) }
}

function close (url: string) {
    const next = { ...drafts.value }
    delete next[url]
    drafts.value = next
}

function submit (url: string) {
    const p = attestPayload(props.task, drafts.value[url])
    if (!p) return
    emit('delivered', p)
    close(url)
}

watch(() => props.task?.uuid, () => { drafts.value = {} })
</script>

<style scoped lang="scss">
@use './taskSections';

.attest {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 4px 0 8px 12px;
    max-width: 420px;
    &__err { font-size: 11px; color: #d03050; }
}
</style>
