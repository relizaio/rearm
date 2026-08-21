<template>
    <n-modal
        :show="show"
        preset="card"
        title="Pipeline role configuration"
        style="max-width: 860px"
        @update:show="(v: boolean) => emit('update:show', v)"
    >
        <p class="hint">
            Roles are served prompts plus a pipeline position — any agent can assume any
            role. New tasks snapshot the active non-routing roles in order; edits never
            change tasks already in flight (each passage records the prompt version it ran
            under).
        </p>
        <n-data-table
            :columns="columns"
            :data="sortedRoles"
            :row-key="(r: any) => r.uuid ?? r.name"
            size="small"
        />
        <n-button class="addbtn" size="small" dashed @click="startAdd">+ Add role</n-button>

        <n-modal
            :show="editing !== null"
            preset="card"
            :title="editingIsNew ? 'New role' : `Edit role: ${editing?.name}`"
            style="max-width: 640px"
            @update:show="(v: boolean) => { if (!v) editing = null }"
        >
            <n-space vertical :size="12" v-if="editing">
                <n-input
                    v-model:value="editing.name"
                    :disabled="!editingIsNew"
                    placeholder="Role name (e.g. security-review)"
                >
                    <template #prefix><span class="flabel">name</span></template>
                </n-input>
                <n-input-number v-model:value="editing.orderIndex" :show-button="true">
                    <template #prefix><span class="flabel">pipeline order</span></template>
                </n-input-number>
                <n-space :size="18">
                    <n-checkbox v-model:checked="editing.routing">
                        routing (standing loop, excluded from pipelines)
                    </n-checkbox>
                    <n-checkbox v-model:checked="editing.requireDistinctAgent">
                        require distinct agent
                    </n-checkbox>
                    <n-checkbox v-model:checked="editing.active">active</n-checkbox>
                </n-space>
                <div>
                    <div class="flabel" style="margin-bottom: 4px">
                        role prompt (served to the assuming agent)
                    </div>
                    <n-input
                        v-model:value="editing.prompt"
                        type="textarea"
                        :autosize="{ minRows: 8, maxRows: 20 }"
                        placeholder="You are the ... stage. ..."
                    />
                </div>
                <n-space justify="end">
                    <n-button quaternary @click="editing = null">Cancel</n-button>
                    <n-button type="primary" :loading="saving" @click="save">Save</n-button>
                </n-space>
            </n-space>
        </n-modal>
    </n-modal>
</template>

<script lang="ts" setup>
import { computed, h, ref } from 'vue'
import { useStore } from 'vuex'
import { NButton, NCheckbox, NDataTable, NInput, NInputNumber, NModal, NSpace, NTag, DataTableColumns, useNotification } from 'naive-ui'

const props = defineProps<{
    show: boolean
    orgUuid: string
    roles: any[]
}>()
const emit = defineEmits<{
    (e: 'update:show', v: boolean): void
    (e: 'saved'): void
}>()

const store = useStore()
const notification = useNotification()

const editing = ref<any>(null)
const editingIsNew = ref(false)
const saving = ref(false)

const sortedRoles = computed(() =>
    [...(props.roles ?? [])].sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)))

const columns: DataTableColumns<any> = [
    { title: 'Order', key: 'orderIndex', width: 70 },
    { title: 'Role', key: 'name', width: 140 },
    {
        title: 'Flags', key: 'flags', width: 220,
        render: (r: any) => h('span', {}, [
            r.routing ? h(NTag, { size: 'tiny', bordered: false, type: 'info' }, { default: () => 'routing' }) : null,
            r.requireDistinctAgent ? h(NTag, { size: 'tiny', bordered: false, type: 'warning', style: 'margin-left:4px' }, { default: () => 'distinct agent' }) : null,
            !r.active ? h(NTag, { size: 'tiny', bordered: false, style: 'margin-left:4px' }, { default: () => 'inactive' }) : null,
        ]),
    },
    {
        title: 'Prompt', key: 'prompt', ellipsis: { tooltip: true },
        render: (r: any) => (r.prompt ? r.prompt.split('\n')[0] : '—'),
    },
    {
        title: '', key: 'actions', width: 70,
        render: (r: any) => h(NButton, { size: 'tiny', quaternary: true, onClick: () => startEdit(r) }, { default: () => 'Edit' }),
    },
]

function startEdit (r: any) {
    editingIsNew.value = false
    editing.value = { ...r }
}

function startAdd () {
    editingIsNew.value = true
    const maxOrder = Math.max(0, ...sortedRoles.value.map(r => r.orderIndex ?? 0))
    editing.value = { name: '', prompt: '', orderIndex: maxOrder + 10, routing: false, requireDistinctAgent: false, active: true }
}

async function save () {
    if (!editing.value?.name?.trim()) {
        notification.error({ content: 'Role name is required' })
        return
    }
    saving.value = true
    try {
        await store.dispatch('setAgentTaskRoleConfig', {
            orgUuid: props.orgUuid,
            input: {
                name: editing.value.name.trim(),
                prompt: editing.value.prompt ?? '',
                orderIndex: editing.value.orderIndex ?? 0,
                routing: !!editing.value.routing,
                requireDistinctAgent: !!editing.value.requireDistinctAgent,
                active: !!editing.value.active,
            },
        })
        notification.success({ content: `Role ${editing.value.name} saved` })
        editing.value = null
        emit('saved')
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        saving.value = false
    }
}
</script>

<style scoped lang="scss">
.hint { color: #888; font-size: 12px; margin: 0 0 12px; }
.addbtn { margin-top: 10px; }
.flabel { color: #888; font-size: 12px; }
</style>
