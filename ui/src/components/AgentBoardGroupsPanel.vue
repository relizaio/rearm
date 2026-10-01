<template>
    <!-- The board's task groups (task RD2-31, task-groups-and-tags.md §6): one row per group in the
         board's order, what it has done and spent, what it waits on; edited by who configures the board. -->
    <div class="boardGroups">
        <div v-if="!groups.length" class="boardGroups__empty">
            No groups yet. A group batches tasks, can wait on another group<template v-if="ladder">, and gives its tasks a
            default work level</template>.
        </div>
        <table v-else class="boardGroups__table">
            <thead>
                <tr><th/><th>Key</th><th>Name</th><th>Progress</th><th>Open</th><th>Spent</th><th/><th/></tr>
            </thead>
            <tbody>
                <tr v-for="g in groups" :key="g.key" class="boardGroups__row" data-testid="group-row" :data-key="g.key"
                    :class="{ 'boardGroups__row--closed': g.status === 'CLOSED' }">
                    <td><span class="boardGroups__swatch" :style="{ background: groupColour(g.key) ?? undefined }"/></td>
                    <td><code>{{ g.key }}</code></td>
                    <td :title="g.description ?? undefined">{{ g.name ?? '' }}</td>
                    <td>{{ groupProgress(g) }}</td>
                    <td>{{ g.progress?.open ?? 0 }}</td>
                    <td>{{ formatCostMicros(g.spentMicros) ?? '—' }}</td>
                    <td>
                        <n-tag v-if="groupWaitingOn(g, board).length" size="tiny" :bordered="false" type="warning"
                               data-testid="group-waiting">waiting on {{ groupWaitingOn(g, board).join(', ') }}</n-tag>
                        <n-tag v-if="g.status === 'CLOSED'" size="tiny" :bordered="false" data-testid="group-closed">closed</n-tag>
                        <!-- Only on a board with a ladder (task RD3-6); a level kept from before is ignored. -->
                        <span v-if="ladder && g.defaultWorkLevel != null" class="boardGroups__meta" data-testid="group-default-level">
                            {{ levelLabel(g.defaultWorkLevel, board) }} default
                        </span>
                    </td>
                    <td class="boardGroups__actions">
                        <template v-if="canConfigure">
                            <n-button size="tiny" quaternary data-testid="group-edit" @click="startEdit(g)">Edit</n-button>
                            <n-button size="tiny" quaternary data-testid="group-close" @click="toggleClosed(g)">
                                {{ g.status === 'CLOSED' ? 'Reopen' : 'Close' }}
                            </n-button>
                        </template>
                    </td>
                </tr>
            </tbody>
        </table>
        <n-text v-if="rowError" type="error" class="boardGroups__rowError" data-testid="group-row-error">{{ rowError }}</n-text>
        <n-button v-if="canConfigure" size="small" dashed class="boardGroups__add" data-testid="group-add" @click="startAdd">
            + Add group
        </n-button>

        <n-modal :show="draft !== null" preset="card" :title="draft?.uuid ? `Edit group ${draft.key}` : 'New group'"
                 style="max-width: 520px" @update:show="(v: boolean) => { if (!v) draft = null }">
            <n-space v-if="draft" vertical :size="10" data-testid="group-form">
                <n-input v-model:value="draft.key" placeholder="Key, e.g. core-work (2 to 24: a-z, 0-9, -)"
                         data-testid="group-key" :status="fieldError('key') ? 'error' : undefined"/>
                <n-text v-if="fieldError('key')" type="error" class="boardGroups__err" data-testid="group-key-error">{{ fieldError('key') }}</n-text>
                <n-input v-model:value="draft.name" placeholder="Name (optional)" data-testid="group-name"
                         :status="fieldError('name') ? 'error' : undefined"/>
                <n-text v-if="fieldError('name')" type="error" class="boardGroups__err">{{ fieldError('name') }}</n-text>
                <n-input v-model:value="draft.description" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }"
                         placeholder="Description (optional)"/>
                <n-select v-model:value="draft.dependsOn" multiple clearable :options="dependencyOptions(board, draft.key)"
                          placeholder="Waits on groups (optional)" data-testid="group-depends"
                          :status="fieldError('dependsOn') ? 'error' : undefined"/>
                <n-text v-if="fieldError('dependsOn')" type="error" class="boardGroups__err" data-testid="group-depends-error">{{ fieldError('dependsOn') }}</n-text>
                <!-- A default work level only on a board with a ladder, one of its rungs (task RD3-6). -->
                <n-select v-if="ladder" v-model:value="draft.defaultWorkLevel" :options="levelOptions(board)" clearable
                          placeholder="Default work level for its tasks (optional)" data-testid="group-level"
                          :status="fieldError('defaultWorkLevel') ? 'error' : undefined"/>
                <n-text v-if="fieldError('defaultWorkLevel')" type="error" class="boardGroups__err">{{ fieldError('defaultWorkLevel') }}</n-text>
                <n-radio-group v-model:value="draft.status" size="small">
                    <n-radio-button value="OPEN" label="open"/>
                    <n-radio-button value="CLOSED" label="closed"/>
                </n-radio-group>
                <n-text v-if="error && !groupFieldOfError(error)" type="error" class="boardGroups__err" data-testid="group-error">{{ error }}</n-text>
                <n-space justify="end">
                    <n-button size="small" @click="draft = null">Cancel</n-button>
                    <n-button size="small" type="primary" :disabled="!draft.key.trim() || saving" data-testid="group-save" @click="save">
                        Save
                    </n-button>
                </n-space>
            </n-space>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NButton, NInput, NModal, NRadioButton, NRadioGroup, NSelect, NSpace, NTag, NText } from 'naive-ui'
import { dependencyOptions, groupColour, groupDraftOf, GroupDraft, groupFieldOfError, groupInputOf, groupKeyTaken, groupProgress,
    groupWaitingOn, sortedGroups, TaskGroup } from '@/utils/agentTaskGroups'
import { formatCostMicros } from '@/utils/agentUsage'
import { hasLadder, levelLabel, levelOptions } from '@/utils/agentTaskLevel'

const props = defineProps<{
    board: any
    /** CONFIGURATION_WRITE on the board: edit, close and add. */
    canConfigure?: boolean
    /** Sends a TaskGroupInput (agentBoardGroupSet); a refusal rejects with the server's message. */
    saveGroup: (group: Record<string, any>) => Promise<unknown>
}>()

const groups = computed<TaskGroup[]>(() => sortedGroups(props.board))
// Whether the board has a level ladder (task RD3-6): without one the default level is neither shown nor sent.
const ladder = computed(() => hasLadder(props.board))
const draft = ref<GroupDraft | null>(null)
const error = ref<string | null>(null)
const rowError = ref<string | null>(null)
const saving = ref(false)

function startAdd () {
    error.value = null
    draft.value = groupDraftOf()
}

function startEdit (g: TaskGroup) {
    error.value = null
    draft.value = groupDraftOf(g)
}

/** The refusal beside the field it is about. */
function fieldError (field: string): string | null {
    return error.value && groupFieldOfError(error.value) === field ? error.value : null
}

function messageOf (e: any): string {
    return String(e?.message ?? e).replace(/^GraphQL error:\s*/, '')
}

async function save () {
    if (!draft.value) return
    // An Add with a key the board has would edit that group (create-or-edit by key): refuse it here.
    const taken = groupKeyTaken(props.board, draft.value)
    if (taken) {
        error.value = taken
        return
    }
    saving.value = true
    error.value = null
    try {
        await props.saveGroup(groupInputOf(draft.value, { withLevel: ladder.value }))
        draft.value = null
    } catch (e: any) {
        error.value = messageOf(e)
    } finally {
        saving.value = false
    }
}

async function toggleClosed (g: TaskGroup) {
    rowError.value = null
    try {
        await props.saveGroup({ key: g.key, status: g.status === 'CLOSED' ? 'OPEN' : 'CLOSED' })
    } catch (e: any) {
        rowError.value = messageOf(e)
    }
}
</script>

<style scoped lang="scss">
.boardGroups {
    &__empty { color: #888; font-size: 13px; padding: 8px 0; }
    &__table { border-collapse: collapse; width: 100%; font-size: 13px;
        th { text-align: left; font-weight: 500; color: #888; padding: 4px 8px; }
        td { padding: 4px 8px; border-top: 1px solid #eee; vertical-align: middle; }
    }
    &__row--closed { opacity: 0.6; }
    &__swatch { display: inline-block; width: 10px; height: 10px; border-radius: 2px; }
    &__meta { color: #888; font-size: 11px; margin-left: 6px; }
    &__actions { white-space: nowrap; text-align: right; }
    &__add { margin-top: 8px; }
    &__err, &__rowError { font-size: 12px; margin-top: -6px; }
    &__rowError { display: block; margin-top: 6px; }
}
</style>
