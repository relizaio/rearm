<template>
    <div class="global-pr-validation-rules">
        <div class="header">
            <h4>Global PR Validation Trigger Rules</h4>
            <n-tooltip trigger="hover">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                When a VCS repository in this org has no per-repo PR-validation
                trigger configured, the rule list below is walked in order and
                the first rule whose URI regex matches contributes its trigger
                as the effective EXTERNAL_VALIDATION trigger. Per-repo triggers
                always win when present. List order is the priority order — use
                the up/down arrows to reorder.
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
                    <n-input v-model:value="draft.name" placeholder="e.g. Default GitHub PR validation"/>
                </n-form-item>
                <n-form-item label="VCS URI regex" required>
                    <n-input v-model:value="draft.uriPattern" placeholder="github.com/myorg/.*"/>
                </n-form-item>
                <n-form-item label="GitHub Validate Integration" required>
                    <n-select
                        v-model:value="draft.integration"
                        placeholder="Select an integration"
                        :options="validationIntegrationsForSelect"/>
                </n-form-item>
                <n-form-item label="GitHub Installation ID" required>
                    <n-input v-model:value="draft.schedule" placeholder="GitHub App installation id"/>
                </n-form-item>
                <n-form-item label="Check name override">
                    <n-input v-model:value="draft.checkName" placeholder="(optional) defaults to rearm/pr/<identity>"/>
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
    NButton, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NSelect, NSpace, NTooltip, useNotification
} from 'naive-ui'
import { CirclePlus, QuestionMark, Edit as EditIcon, Trash, ArrowUp, ArrowDown } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'

const props = defineProps<{ orgUuid: string, isWritable: boolean }>()

const store = useStore()
const notification = useNotification()

const rules = ref<any[]>([])
const ciIntegrations = ref<any[]>([])

const editorOpen = ref(false)
const editingIndex = ref<number | null>(null)
const draft = reactive({
    name: '',
    uriPattern: '',
    integration: '',
    schedule: '',
    checkName: ''
})

const editorTitle = computed(() => editingIndex.value === null ? 'Add rule' : 'Edit rule')

const validationIntegrationsForSelect = computed(() => ciIntegrations.value
    .filter((x: any) => x.type === 'GITHUB' && (x.capabilities || []).includes('PR_VALIDATE'))
    .map((x: any) => ({ label: x.note || x.identifier || x.uuid, value: x.uuid })))

const integrationLabel = (uuid: string) => {
    if (!uuid) return '—'
    const found = ciIntegrations.value.find((x: any) => x.uuid === uuid)
    return found ? (found.note || found.identifier || uuid) : uuid + ' (missing)'
}

const canSave = computed(() => !!(draft.name && draft.uriPattern && draft.integration && draft.schedule))

const resetDraft = () => {
    draft.name = ''
    draft.uriPattern = ''
    draft.integration = ''
    draft.schedule = ''
    draft.checkName = ''
}

const openAdd = () => {
    resetDraft()
    editingIndex.value = null
    editorOpen.value = true
}

const openEdit = (idx: number) => {
    const r = rules.value[idx]
    draft.name = r.name
    draft.uriPattern = r.uriPattern
    draft.integration = r.trigger?.integration || ''
    draft.schedule = r.trigger?.schedule || ''
    draft.checkName = r.trigger?.checkName || ''
    editingIndex.value = idx
    editorOpen.value = true
}

const saveDraft = async () => {
    if (!canSave.value) return
    const ruleObject = {
        name: draft.name,
        uriPattern: draft.uriPattern,
        trigger: {
            type: 'EXTERNAL_VALIDATION',
            name: draft.name,
            integration: draft.integration,
            schedule: draft.schedule,
            checkName: draft.checkName || null
        }
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
        rules.value = await store.dispatch('setGlobalPrValidationTriggerRules', {
            orgUuid: props.orgUuid,
            rules: next.map((r: any) => ({ name: r.name, uriPattern: r.uriPattern, trigger: r.trigger }))
        })
        notification.success({ title: 'Saved', content: successMsg, duration: 3500 })
    } catch (e: any) {
        notification.error({ title: 'Save failed', content: e?.message || 'Unknown error', duration: 6000 })
    }
}

const columns = computed(() => [
    { title: 'Order', key: 'order', width: 70, render: (_: any, idx: number) => `${idx + 1}` },
    { title: 'Name', key: 'name' },
    { title: 'URI regex', key: 'uriPattern' },
    {
        title: 'Integration',
        key: 'integration',
        render: (row: any) => integrationLabel(row.trigger?.integration)
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

async function fetchCiIntegrations() {
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query ciIntegrations($org: ID!) {
                    ciIntegrations(org: $org) {
                        uuid identifier org isEnabled type note capabilities
                    }
                }`,
            variables: { org: props.orgUuid },
            fetchPolicy: 'network-only'
        })
        ciIntegrations.value = resp.data?.ciIntegrations || []
    } catch (err) { console.error(err) }
}

onMounted(async () => {
    await fetchCiIntegrations()
    rules.value = (await store.dispatch('fetchOrgValidationTriggerRules', props.orgUuid)) || []
})
</script>

<style scoped lang="scss">
.global-pr-validation-rules {
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
