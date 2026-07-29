<template>
    <div class="team-assignment-rules" v-if="!loadFailed">
        <div class="header">
            <h4>Team Assignment Rules</h4>
            <n-tooltip trigger="hover">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                Assign an owner team by name pattern instead of picking one on every
                component. When a component has no owner of its own, the list below is
                walked in order and the first rule whose name regex AND type filter
                match becomes its owner. A per-component owner always wins. List order
                is the priority order — reorder with the up/down arrows.
            </n-tooltip>
        </div>

        <div class="actions">
            <n-button v-if="isWritable" type="primary" @click="openAdd">
                <template #icon><n-icon><CirclePlus/></n-icon></template>
                Add rule
            </n-button>
        </div>

        <n-data-table
            :columns="columns"
            :data="rules"
            :pagination="false"
            :bordered="false"/>

        <n-modal
            preset="dialog"
            :show-icon="false"
            style="width: 600px;"
            v-model:show="editorOpen"
            :title="editorTitle">
            <n-form :model="draft" label-placement="top" class="mt-3">
                <n-form-item label="Name" required>
                    <n-input v-model:value="draft.name" placeholder="e.g. Frontend components"/>
                </n-form-item>
                <n-form-item label="Component name regex" required>
                    <n-input v-model:value="draft.namePattern" placeholder="frontend-.*"/>
                </n-form-item>
                <n-form-item label="Applies to" required>
                    <n-radio-group v-model:value="draft.componentType">
                        <n-radio value="ANY">Components and products</n-radio>
                        <n-radio value="COMPONENT">Components only</n-radio>
                        <n-radio value="PRODUCT">Products only</n-radio>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="Owner Team" required>
                    <n-select
                        v-model:value="draft.ownerTeam"
                        :options="teamOptions"
                        placeholder="Select a team"/>
                </n-form-item>
                <!-- Match preview: a pattern here can claim hundreds of components at
                     once, so show the blast radius before saving rather than after. -->
                <n-alert :type="previewType" style="margin-bottom: 12px;">
                    <template #header>{{ previewHeader }}</template>
                    <div v-if="preview.names.length" style="font-size: 12px;">
                        {{ preview.names.join(', ') }}
                        <span v-if="preview.more > 0"> and {{ preview.more }} more</span>
                    </div>
                    <div style="font-size: 11px; opacity: 0.75; margin-top: 4px;">
                        Preview only — evaluated in your browser. The server matches with
                        Java regex, which differs in rare edge cases.
                    </div>
                </n-alert>
                <n-space>
                    <n-button type="primary" :disabled="!canSave" @click="saveDraft">Save</n-button>
                    <n-button @click="editorOpen = false">Cancel</n-button>
                </n-space>
            </n-form>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useStore } from 'vuex'
import {
    NAlert, NButton, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NRadio, NRadioGroup,
    NSelect, NSpace, NTooltip, useNotification
} from 'naive-ui'
import { CirclePlus, QuestionMark, Edit as EditIcon, Trash, ArrowUp, ArrowDown } from '@vicons/tabler'
import { previewMatches } from '@/utils/teamAssignmentPreview'

const props = defineProps<{
    orgUuid: string,
    isWritable: boolean,
    // Teams and components are already loaded by the parent; passing them in
    // avoids a second round of queries just to label rows and preview matches.
    teams: any[],
    components: any[]
}>()

const store = useStore()
const notification = useNotification()

const rules = ref<any[]>([])

const editorOpen = ref(false)
const editingIndex = ref<number | null>(null)
const draft = reactive({
    name: '',
    namePattern: '',
    componentType: 'ANY' as 'ANY' | 'COMPONENT' | 'PRODUCT',
    ownerTeam: ''
})

const editorTitle = computed(() => editingIndex.value === null ? 'Add rule' : 'Edit rule')

const teamOptions = computed(() => (props.teams || [])
    .filter((t: any) => t.status !== 'INACTIVE')
    .map((t: any) => ({ label: t.name || t.uuid, value: t.uuid })))

const teamLabel = (uuid: string) => {
    if (!uuid) return '—'
    const found = (props.teams || []).find((t: any) => t.uuid === uuid)
    if (!found) return uuid + ' (missing)'
    if (found.status === 'INACTIVE') return found.name + ' (archived)'
    return found.name || uuid
}

const typeLabel = (t: string | null | undefined) => {
    if (!t || t === 'ANY') return 'Any (Components and products)'
    if (t === 'COMPONENT') return 'Components only'
    if (t === 'PRODUCT') return 'Products only'
    return t
}

const preview = computed(() =>
    previewMatches(draft.namePattern, draft.componentType, props.components))

const previewType = computed(() => {
    if (!preview.value.previewable) return 'warning'
    return preview.value.total === 0 ? 'warning' : 'success'
})

const previewHeader = computed(() => {
    if (!draft.namePattern) return 'Enter a pattern to preview which components it matches'
    // Java accepts constructs JS does not (possessive quantifiers, atomic
    // groups). That means "cannot preview here", not "invalid" -- the server is
    // the authority, so we say so and still allow saving.
    if (!preview.value.previewable) return 'Cannot preview this pattern in the browser'
    if (preview.value.total === 0) return 'Matches no components right now'
    return `Matches ${preview.value.total} component${preview.value.total === 1 ? '' : 's'}`
})

const canSave = computed(() => !!(draft.name && draft.namePattern && draft.ownerTeam))

const resetDraft = () => {
    draft.name = ''
    draft.namePattern = ''
    draft.componentType = 'ANY'
    draft.ownerTeam = ''
}

const openAdd = () => {
    resetDraft()
    editingIndex.value = null
    editorOpen.value = true
}

const openEdit = (idx: number) => {
    const r = rules.value[idx]
    draft.name = r.name
    draft.namePattern = r.namePattern
    draft.componentType = r.componentType || 'ANY'
    draft.ownerTeam = r.ownerTeam
    editingIndex.value = idx
    editorOpen.value = true
}

const saveDraft = async () => {
    if (!canSave.value) return
    const ruleObject = {
        name: draft.name,
        namePattern: draft.namePattern,
        componentType: draft.componentType,
        ownerTeam: draft.ownerTeam
    }
    const next = rules.value.slice()
    if (editingIndex.value === null) next.push(ruleObject)
    else next.splice(editingIndex.value, 1, ruleObject)
    await persist(next, 'Rule saved.')
    editorOpen.value = false
}

const remove = async (idx: number) => {
    const next = rules.value.slice()
    next.splice(idx, 1)
    await persist(next, 'Rule deleted.')
}

const move = async (idx: number, delta: number) => {
    const target = idx + delta
    if (target < 0 || target >= rules.value.length) return
    const next = rules.value.slice()
    const [item] = next.splice(idx, 1)
    next.splice(target, 0, item)
    await persist(next, 'Rule reordered.')
}

const persist = async (next: any[], successMsg: string) => {
    try {
        rules.value = await store.dispatch('setGlobalTeamAssignmentRules', {
            orgUuid: props.orgUuid,
            rules: next.map((r: any) => ({
                name: r.name,
                namePattern: r.namePattern,
                componentType: r.componentType || 'ANY',
                ownerTeam: r.ownerTeam
            }))
        })
        notification.success({ title: 'Saved', content: successMsg, duration: 3500 })
    } catch (e: any) {
        notification.error({ title: 'Save failed', content: e?.message || 'Unknown error', duration: 6000 })
    }
}

const columns = computed(() => [
    { title: 'Order', key: 'order', width: 70, render: (_: any, idx: number) => `${idx + 1}` },
    { title: 'Name', key: 'name' },
    { title: 'Component name regex', key: 'namePattern' },
    { title: 'Applies to', key: 'componentType', render: (row: any) => typeLabel(row.componentType) },
    { title: 'Owner Team', key: 'ownerTeam', render: (row: any) => teamLabel(row.ownerTeam) },
    {
        title: 'Actions',
        key: 'actions',
        width: 200,
        render: (row: any, idx: number) => h('div', { style: 'display: flex; gap: 6px;' },
            props.isWritable ? [
                h(NIcon, { size: 22, class: 'clickable', title: 'Move up', onClick: () => move(idx, -1) },
                    { default: () => h(ArrowUp) }),
                h(NIcon, { size: 22, class: 'clickable', title: 'Move down', onClick: () => move(idx, 1) },
                    { default: () => h(ArrowDown) }),
                h(NIcon, { size: 22, class: 'clickable', title: 'Edit', onClick: () => openEdit(idx) },
                    { default: () => h(EditIcon) }),
                h(NIcon, { size: 22, class: 'clickable', style: 'color: #d03050;', title: 'Delete',
                    onClick: () => remove(idx) }, { default: () => h(Trash) })
            ] : [])
    }
])

const loadFailed = ref(false)

onMounted(async () => {
    try {
        rules.value = (await store.dispatch('fetchOrgTeamAssignmentRules', props.orgUuid)) || []
    } catch (e: any) {
        // A backend predating this feature would otherwise leave an empty table
        // with a live Add button that can only fail.
        console.warn('Team assignment rules unavailable on this backend', e?.message)
        loadFailed.value = true
    }
})
</script>

<style scoped lang="scss">
.team-assignment-rules {
    padding: 0.5rem 0;
    margin-top: 1.5rem;
}
.header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 0.5rem;
}
.actions {
    margin-bottom: 0.75rem;
}
.mt-3 { margin-top: 0.75rem; }
.clickable {
    cursor: pointer;
}
</style>
