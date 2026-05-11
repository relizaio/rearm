<template>
    <div class="global-approval-policy-rules">
        <div class="header">
            <h4>Global Policy Assignment</h4>
            <n-tooltip trigger="hover">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                When a component in this org has no per-component approval
                policy reference (or it points to an archived/missing policy),
                the rule list below is walked in order and the first rule whose
                name regex AND component-type filter match contributes its
                policy. Per-component references always win when present and
                still valid. List order is the priority order — reorder with
                the up/down arrows.
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
                    <n-input v-model:value="draft.name" placeholder="e.g. Default for frontend components"/>
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
                <n-form-item label="Approval Policy" required>
                    <n-select
                        v-model:value="draft.approvalPolicy"
                        placeholder="Select an approval policy"
                        :options="approvalPolicyOptions"/>
                </n-form-item>
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
    NButton, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NRadio, NRadioGroup, NSelect, NSpace, NTooltip, useNotification
} from 'naive-ui'
import { CirclePlus, QuestionMark, Edit as EditIcon, Trash, ArrowUp, ArrowDown } from '@vicons/tabler'

const props = defineProps<{ orgUuid: string, isWritable: boolean }>()

const store = useStore()
const notification = useNotification()

const rules = ref<any[]>([])
const approvalPolicies = ref<any[]>([])

const editorOpen = ref(false)
const editingIndex = ref<number | null>(null)
const draft = reactive({
    name: '',
    namePattern: '',
    componentType: 'ANY' as 'ANY' | 'COMPONENT' | 'PRODUCT',
    approvalPolicy: ''
})

const editorTitle = computed(() => editingIndex.value === null ? 'Add rule' : 'Edit rule')

const approvalPolicyOptions = computed(() => approvalPolicies.value
    .filter((p: any) => p.status !== 'ARCHIVED')
    .map((p: any) => ({ label: p.policyName || p.uuid, value: p.uuid })))

const policyLabel = (uuid: string) => {
    if (!uuid) return '—'
    const found = approvalPolicies.value.find((p: any) => p.uuid === uuid)
    if (!found) return uuid + ' (missing)'
    if (found.status === 'ARCHIVED') return found.policyName + ' (archived)'
    return found.policyName || uuid
}

const typeLabel = (t: string | null | undefined) => {
    if (!t || t === 'ANY') return 'Any (Components and products)'
    if (t === 'COMPONENT') return 'Components only'
    if (t === 'PRODUCT') return 'Products only'
    return t
}

const canSave = computed(() => !!(draft.name && draft.namePattern && draft.approvalPolicy))

const resetDraft = () => {
    draft.name = ''
    draft.namePattern = ''
    draft.componentType = 'ANY'
    draft.approvalPolicy = ''
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
    draft.approvalPolicy = r.approvalPolicy
    editingIndex.value = idx
    editorOpen.value = true
}

const saveDraft = async () => {
    if (!canSave.value) return
    const ruleObject = {
        name: draft.name,
        namePattern: draft.namePattern,
        componentType: draft.componentType,
        approvalPolicy: draft.approvalPolicy
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
        rules.value = await store.dispatch('setGlobalApprovalPolicyRules', {
            orgUuid: props.orgUuid,
            rules: next.map((r: any) => ({
                name: r.name,
                namePattern: r.namePattern,
                componentType: r.componentType || 'ANY',
                approvalPolicy: r.approvalPolicy
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
    {
        title: 'Approval Policy',
        key: 'approvalPolicy',
        render: (row: any) => policyLabel(row.approvalPolicy)
    },
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

onMounted(async () => {
    approvalPolicies.value = (await store.dispatch('listApprovalPoliciesOfOrg', props.orgUuid)) || []
    rules.value = (await store.dispatch('fetchOrgApprovalPolicyRules', props.orgUuid)) || []
})
</script>

<style scoped lang="scss">
.global-approval-policy-rules {
    padding: 0.5rem 0;
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
