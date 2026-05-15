<template>
    <div class="agentPolicies">
        <div class="head">
            <div class="title-row">
                <h4>AI Agent Policies</h4>
                <n-tooltip trigger="hover" :width="380" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="16" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    CEL rules evaluated against every agent session in this org —
                    <strong>INPUT</strong> policies gate session init;
                    <strong>OUTPUT</strong> policies harden when a commit is
                    attributed. Open the editor and expand
                    <em>Sample policies</em> for starter scaffolds.
                </n-tooltip>
            </div>
            <n-button @click="createNew" type="primary">+ New policy</n-button>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!policies.length" class="empty">
                No policies yet. Click <strong>New policy</strong> above to add one.
            </div>
            <n-data-table v-else :columns="columns" :data="policies" :pagination="{ pageSize: 25 }"/>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NDataTable, NIcon, NSpin, NSwitch, NTag, NTooltip, NPopconfirm, DataTableColumns, useNotification } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const orgUuid = computed(() => route.params.orguuid as string)
const policies = ref<any[]>([])
const loading = ref<boolean>(true)

onMounted(load)

async function load () {
    loading.value = true
    try {
        policies.value = await store.dispatch('fetchAgentPoliciesOfOrg', orgUuid.value) || []
    } catch (e: any) {
        notification.error({ content: `Failed to load policies: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

function createNew () {
    router.push({ name: 'AiAgentPolicyView', params: { uuid: 'new' }, query: { org: orgUuid.value } })
}

function openPolicy (uuid: string) {
    router.push({ name: 'AiAgentPolicyView', params: { uuid } })
}

async function toggleEnabled (row: any) {
    try {
        await store.dispatch('setAgentPolicyEnabled', { uuid: row.uuid, enabled: !row.enabled })
        row.enabled = !row.enabled
    } catch (e: any) {
        notification.error({ content: `Toggle failed: ${e?.message ?? e}` })
    }
}

async function remove (row: any) {
    try {
        await store.dispatch('deleteAgentPolicy', row.uuid)
        policies.value = policies.value.filter((p) => p.uuid !== row.uuid)
        notification.success({ content: `Deleted "${row.name}"` })
    } catch (e: any) {
        notification.error({ content: `Delete failed: ${e?.message ?? e}` })
    }
}

const columns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Name',
        key: 'name',
        render: (row: any) => h('a', {
            href: '#',
            onClick: (e: Event) => { e.preventDefault(); openPolicy(row.uuid) },
        }, row.name),
    },
    {
        title: 'Kind',
        key: 'kind',
        width: 100,
        render: (row: any) => h(NTag, { size: 'small', type: row.kind === 'INPUT' ? 'info' : 'default' },
            { default: () => row.kind }),
    },
    {
        title: 'Severity',
        key: 'severity',
        width: 100,
        render: (row: any) => h(NTag, { size: 'small', type: row.severity === 'BLOCK' ? 'error' : 'warning' },
            { default: () => row.severity }),
    },
    {
        title: 'CEL',
        key: 'cel',
        ellipsis: { tooltip: true },
        render: (row: any) => h('code', { class: 'cel-cell' }, row.cel || ''),
    },
    { title: 'Description', key: 'description', ellipsis: { tooltip: true } },
    {
        title: 'Enabled',
        key: 'enabled',
        width: 90,
        render: (row: any) => h(NSwitch, {
            size: 'small',
            value: !!row.enabled,
            onUpdateValue: () => toggleEnabled(row),
        }),
    },
    {
        title: '',
        key: 'actions',
        width: 100,
        render: (row: any) => h(NPopconfirm, {
            onPositiveClick: () => remove(row),
        }, {
            trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'error' }, { default: () => 'Delete' }),
            default: () => `Delete policy "${row.name}"? Past verdicts on sessions keep their recorded policy uuid for forensic value.`,
        }),
    },
])
</script>

<style scoped>
.agentPolicies { padding: 16px; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.title-row { display: flex; align-items: center; gap: 8px; }
.title-row h4 { margin: 0; }
.info-icon { color: #888; cursor: help; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
:deep(.cel-cell) { font-size: 12px; }
</style>
