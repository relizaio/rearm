<template>
    <div class="agentPolicies">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="openAgentsOfOrg">AI Agents</n-breadcrumb-item>
            <n-breadcrumb-item>Policies</n-breadcrumb-item>
        </n-breadcrumb>
        <div class="head">
            <div class="title-row">
                <h4 v-if="!props.embedded">AI Agent Policies</h4>
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
            <n-space>
                <!-- Populate Default Agent Policies — only when the org has
                     none yet, mirroring the empty-state defaults button on
                     the approval-policy side. Seeds a single starter policy
                     that requires every agent session to file an orientation
                     report (AGENTIC_REPORT artifact) before any of its
                     commits can be attributed. -->
                <n-button v-if="!loading && !policies.length && !populating"
                          @click="populateDefaults" type="default">
                    Populate Default Agent Policies
                </n-button>
                <n-button v-if="populating" type="default" loading>Populating…</n-button>
                <n-button @click="createNew" type="primary">+ New policy</n-button>
            </n-space>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!policies.length" class="empty">
                No policies yet. Click <strong>Populate Default Agent Policies</strong> to seed the starter set, or <strong>+ New policy</strong> to add your own.
            </div>
            <n-data-table v-else :columns="columns" :data="policies" :pagination="{ pageSize: 25 }"/>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NBreadcrumb, NBreadcrumbItem, NButton, NDataTable, NIcon, NSpace, NSpin, NSwitch, NTag, NTooltip, NPopconfirm, DataTableColumns, useNotification } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import { Edit as EditIcon, Trash } from '@vicons/tabler'

const props = defineProps<{ embedded?: boolean }>()

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

// When embedded inside OrgSettings the page reads orgUuid from a different
// route slot (`/orgSettings/:orguuid`). Fall through to the active org from
// the store if neither slot is populated so the inner panel still works.
const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => (route.params.orguuid as string) || myorg.value?.uuid)
const policies = ref<any[]>([])
const loading = ref<boolean>(true)
const populating = ref<boolean>(false)

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
    router.push({
        name: 'AiAgentPolicyView',
        params: { uuid: 'new' },
        query: { org: orgUuid.value, from: 'policies' },
    })
}

function openPolicy (uuid: string) {
    router.push({
        name: 'AiAgentPolicyView',
        params: { uuid },
        query: { from: 'policies' },
    })
}

function openAgentsOfOrg () {
    router.push({ name: 'AiAgentsOfOrg', params: { orguuid: orgUuid.value } })
}

async function toggleEnabled (row: any) {
    try {
        await store.dispatch('setAgentPolicyEnabled', { uuid: row.uuid, enabled: !row.enabled })
        row.enabled = !row.enabled
    } catch (e: any) {
        notification.error({ content: `Toggle failed: ${e?.message ?? e}` })
    }
}

async function populateDefaults () {
    if (populating.value) return
    populating.value = true
    try {
        // Single starter policy: every agent session must file an
        // orientation report (AGENTIC_REPORT artifact) before any of
        // its commits can be attributed. OUTPUT + BLOCK because the
        // report normally arrives during the session; gating session
        // init (INPUT) would be too early.
        const input = {
            uuid: null,
            org: orgUuid.value,
            name: 'Require Orientation Report',
            description: 'Reject agent sessions that have not filed an AGENTIC_REPORT artifact (orientation report) by the time their commits are attributed.',
            kind: 'OUTPUT',
            severity: 'BLOCK',
            cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT")',
            enabled: true
        }
        await store.dispatch('upsertAgentPolicy', input)
        notification.success({ content: 'Seeded "Require Orientation Report".' })
        await load()
    } catch (e: any) {
        notification.error({ content: `Populate failed: ${e?.message ?? e}` })
    } finally {
        populating.value = false
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
        title: 'Actions',
        key: 'actions',
        width: 90,
        render: (row: any) => h('div', { style: 'display: flex; gap: 6px; align-items: center;' }, [
            h(NIcon, {
                title: 'Edit policy',
                size: 18,
                style: 'cursor: pointer; color: #555;',
                onClick: () => openPolicy(row.uuid)
            }, { default: () => h(EditIcon) }),
            h(NPopconfirm, {
                onPositiveClick: () => remove(row),
            }, {
                trigger: () => h(NIcon, {
                    title: 'Delete policy',
                    size: 18,
                    style: 'cursor: pointer; color: #c0392b;'
                }, { default: () => h(Trash) }),
                default: () => `Delete policy "${row.name}"? Past verdicts on sessions keep their recorded policy uuid for forensic value.`,
            })
        ]),
    },
])
</script>

<style scoped>
.agentPolicies { padding: 16px; }
.crumbs { margin-bottom: 12px; font-size: 13px; }
.crumbs :deep(.n-breadcrumb-item__link) { cursor: pointer; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.title-row { display: flex; align-items: center; gap: 8px; }
.title-row h4 { margin: 0; }
.info-icon { color: #888; cursor: help; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
:deep(.cel-cell) { font-size: 12px; }
</style>
