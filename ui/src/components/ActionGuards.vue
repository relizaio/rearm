<template>
    <div class="action-guards">
        <div class="header">
            <h4>{{ scope === 'ORG' ? 'Organization Action Guards' : 'Guards' }}</h4>
            <n-tooltip trigger="hover" style="max-width: 420px;">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                A guard is the mirror of a rule. A rule says "when this CEL is true, do something";
                a guard says "do this only while the CEL is true, otherwise refuse". Guards written
                here accumulate with the guards the other scopes declare — every one that applies is
                evaluated, and any one of them can refuse. A component can add to what it is held
                to, never subtract from it.
            </n-tooltip>
        </div>

        <p class="text-muted intro">
            <template v-if="scope === 'ORG'">
                Each guard picks the components it governs by a regex over the component name. Leave
                the pattern empty to govern every component in the organization.
            </template>
            <template v-else>
                These guards govern this {{ componentWord }} only, on top of anything the
                organization declares for it.
            </template>
        </p>

        <div class="actions">
            <n-button v-if="isWritable" type="primary" @click="openAdd">
                <template #icon><n-icon><CirclePlus/></n-icon></template>
                Add guard
            </n-button>
        </div>

        <n-data-table :columns="columns" :data="guards" :pagination="false" :bordered="false"/>

        <n-modal
            preset="dialog"
            :show-icon="false"
            style="width: 820px;"
            v-model:show="editorOpen"
            :title="editorTitle">
            <n-form :model="draft" label-placement="top" class="mt-3">
                <n-form-item label="Name" required>
                    <n-input v-model:value="draft.name" placeholder="e.g. Inputs must be baselined"/>
                </n-form-item>
                <n-form-item label="Guarded action" required>
                    <n-select v-model:value="draft.action" :options="actionOptions"/>
                </n-form-item>
                <n-form-item v-if="scope === 'ORG'" label="Component name regex">
                    <n-input v-model:value="draft.namePattern" placeholder="Leave empty for every component, e.g. product-.*"/>
                </n-form-item>
                <n-form-item label="Mode" required>
                    <n-radio-group v-model:value="draft.mode">
                        <n-radio value="BLOCK">Block — refuse the action, naming this guard</n-radio>
                        <n-radio value="WARN">Warn — let it through and record the failure</n-radio>
                        <n-radio value="OFF">Off — keep the guard without evaluating it</n-radio>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="Condition" required>
                    <CelExpressionBuilder
                        v-model="draft.cel"
                        :approval-entry-options="[]"
                        :cel-only="true"
                        :extra-variable-docs="guardVariableDocs"
                        :extra-example-docs="guardExampleDocs"
                        placeholder="The action proceeds only while this is true, e.g. release.dependencies.all(d, d.lifecycle == &quot;READY_TO_SHIP&quot;)"
                    />
                </n-form-item>
                <div class="samples">
                    <div class="samples-title">Start from a sample:</div>
                    <div v-for="s in samples" :key="s.cel" class="sample">
                        <n-button size="tiny" dashed @click="applySample(s)">Use</n-button>
                        <div>
                            <div class="sample-label">{{ s.label }}</div>
                            <code>{{ s.cel }}</code>
                        </div>
                    </div>
                </div>
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
    NButton, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NRadio, NRadioGroup, NSelect,
    NSpace, NTooltip, useNotification
} from 'naive-ui'
import { CirclePlus, QuestionMark, Edit as EditIcon, Trash } from '@vicons/tabler'
import CelExpressionBuilder from './CelExpressionBuilder.vue'

const props = withDefaults(defineProps<{
    scope: 'ORG' | 'COMPONENT'
    uuid: string
    isWritable: boolean
    // The org's own word for a component, so the copy matches the terminology
    // setting the rest of the page honours.
    componentWord?: string
}>(), { componentWord: 'component' })

const store = useStore()
const notification = useNotification()

const guards = ref<any[]>([])
const editorOpen = ref(false)
const editingIndex = ref<number | null>(null)
const draft = reactive({
    name: '',
    action: 'RELEASE_PROMOTION',
    cel: '',
    mode: 'BLOCK' as 'OFF' | 'WARN' | 'BLOCK',
    namePattern: ''
})

const actionOptions = [
    { label: 'Release promotion — moving a release forward through its lifecycle', value: 'RELEASE_PROMOTION' }
]

// action.* exists only where a guard is being written, so it is passed in rather
// than living in the shared variable list the release rules also use.
const guardVariableDocs = [
    {
        name: 'action.targetLifecycle',
        snippet: 'action.targetLifecycle == "READY_TO_SHIP"',
        display: 'action.targetLifecycle',
        desc: 'string — the lifecycle being moved to. release.lifecycle is still the one being left, which is what lets a guard govern one transition and leave the rest alone.'
    }
]

const samples = [
    {
        label: 'Everything this release was built from has been baselined',
        cel: 'release.dependencies.all(d, d.lifecycle == "READY_TO_SHIP")'
    },
    {
        label: 'Documents held to a higher standard than code',
        cel: 'release.dependencies.all(d, d.specification == "TEST_PLAN" ? d.lifecycle == "READY_TO_SHIP" : d.lifecycle != "DRAFT")'
    },
    {
        label: 'Govern general availability only; leave shipping alone',
        cel: 'action.targetLifecycle != "GENERAL_AVAILABILITY" || release.dependencies.all(d, d.lifecycle == "READY_TO_SHIP")'
    },
    {
        label: 'Nothing withdrawn underneath a release that is about to ship',
        cel: 'release.dependencies.all(d, d.maturity >= 3 && d.supported)'
    },
    {
        label: 'At least assembled, without listing lifecycles by hand',
        cel: 'release.dependencies.all(d, d.maturity >= 2)'
    },
    {
        label: 'Only in-house dependencies are governed',
        cel: 'release.dependencies.all(d, d.external || d.maturity >= 3)'
    },
    {
        label: 'A software requirements specification exists and is baselined',
        cel: 'release.dependencies.exists(d, d.specification == "SRS" && d.lifecycle == "READY_TO_SHIP")'
    },
    {
        label: 'Nothing ships with an open critical or known-exploited finding',
        cel: 'release.criticalVulns == 0 && release.kevCount == 0'
    }
]

const guardExampleDocs = samples.map(s => s.cel)

const editorTitle = computed(() => editingIndex.value === null ? 'Add guard' : 'Edit guard')
const canSave = computed(() => !!(draft.name && draft.action && (draft.cel || draft.mode === 'OFF')))

const modeLabel = (m: string) => {
    if (m === 'BLOCK') return 'Block'
    if (m === 'WARN') return 'Warn'
    return 'Off'
}

const applySample = (s: { cel: string }) => { draft.cel = s.cel }

const resetDraft = () => {
    draft.name = ''
    draft.action = 'RELEASE_PROMOTION'
    draft.cel = ''
    draft.mode = 'BLOCK'
    draft.namePattern = ''
}

const openAdd = () => {
    resetDraft()
    editingIndex.value = null
    editorOpen.value = true
}

const openEdit = (idx: number) => {
    const g = guards.value[idx]
    draft.name = g.name
    draft.action = g.action || 'RELEASE_PROMOTION'
    draft.cel = g.cel || ''
    draft.mode = g.mode || 'BLOCK'
    draft.namePattern = g.namePattern || ''
    editingIndex.value = idx
    editorOpen.value = true
}

const saveDraft = async () => {
    if (!canSave.value) return
    const guard = {
        name: draft.name,
        action: draft.action,
        cel: draft.cel,
        mode: draft.mode,
        namePattern: props.scope === 'ORG' ? (draft.namePattern || null) : null
    }
    const next = guards.value.slice()
    if (editingIndex.value === null) next.push(guard)
    else next.splice(editingIndex.value, 1, guard)
    const saved = await persist(next, 'Guard saved.')
    if (saved) editorOpen.value = false
}

const remove = async (idx: number) => {
    const next = guards.value.slice()
    next.splice(idx, 1)
    await persist(next, 'Guard deleted.')
}

const persist = async (next: any[], successMsg: string) => {
    const payload = next.map((g: any) => ({
        name: g.name,
        action: g.action,
        cel: g.cel,
        mode: g.mode,
        namePattern: props.scope === 'ORG' ? (g.namePattern || null) : null
    }))
    try {
        guards.value = props.scope === 'ORG'
            ? await store.dispatch('setOrgActionGuards', { orgUuid: props.uuid, guards: payload })
            : await store.dispatch('setComponentActionGuards', { componentUuid: props.uuid, guards: payload })
        notification.success({ title: 'Saved', content: successMsg, duration: 3500 })
        return true
    } catch (e: any) {
        // The backend validates the expression, the name uniqueness and the regex before
        // writing, so its message is the useful one — surface it rather than a generic failure.
        notification.error({ title: 'Save failed', content: e?.message || 'Unknown error', duration: 8000 })
        return false
    }
}

const columns = computed(() => {
    const cols: any[] = [
        { title: 'Name', key: 'name' },
        { title: 'Action', key: 'action', width: 180, render: (row: any) => row.action === 'RELEASE_PROMOTION' ? 'Release promotion' : row.action },
        { title: 'Mode', key: 'mode', width: 90, render: (row: any) => modeLabel(row.mode) }
    ]
    if (props.scope === 'ORG') {
        cols.push({
            title: 'Component name regex',
            key: 'namePattern',
            width: 200,
            render: (row: any) => row.namePattern || 'every component'
        })
    }
    cols.push({
        title: 'Condition',
        key: 'cel',
        render: (row: any) => h('code', { style: 'font-size: 11px; white-space: pre-wrap; word-break: break-all;' }, row.cel)
    })
    cols.push({
        title: 'Actions',
        key: 'actions',
        width: 110,
        render: (row: any, idx: number) => h('div', { style: 'display: flex; gap: 6px;' },
            props.isWritable ? [
                h(NIcon, { size: 22, class: 'clickable', title: 'Edit', onClick: () => openEdit(idx) },
                    { default: () => h(EditIcon) }),
                h(NIcon, { size: 22, class: 'clickable', style: 'color: #d03050;', title: 'Delete',
                    onClick: () => remove(idx) }, { default: () => h(Trash) })
            ] : [])
    })
    return cols
})

onMounted(async () => {
    guards.value = (props.scope === 'ORG'
        ? await store.dispatch('fetchOrgActionGuards', props.uuid)
        : await store.dispatch('fetchComponentActionGuards', props.uuid)) || []
})
</script>

<style scoped lang="scss">
.action-guards {
    padding: 0.5rem 0;
}
.header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 0.25rem;
}
.intro {
    margin: 0 0 0.75rem 0;
    font-size: 13px;
}
.actions {
    margin-bottom: 0.75rem;
}
.samples {
    margin: 0.25rem 0 1rem 0;
    font-size: 12px;
}
.samples-title {
    font-weight: 600;
    margin-bottom: 4px;
}
.sample {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin-bottom: 6px;
}
.sample-label {
    color: #666;
}
.mt-3 { margin-top: 0.75rem; }
.clickable {
    cursor: pointer;
}
</style>
