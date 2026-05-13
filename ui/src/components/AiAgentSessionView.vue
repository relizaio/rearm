<template>
    <div class="aiAgentSessionView" v-if="session">
        <div class="hero">
            <n-tag :type="session.status === 'OPEN' ? 'info' : 'default'" size="small">
                {{ session.status }}
            </n-tag>
            <code class="dim">{{ session.uuid }}</code>
            <span class="dim">·</span>
            <a v-if="agent" @click.prevent="openAgent" href="#">
                <span :style="{ background: agent.color || '#888', color: 'white', padding: '0 4px', borderRadius: '4px', marginRight: '4px' }">
                    {{ agent.iconKind || '◆' }}
                </span>
                {{ agent.name }}
            </a>
        </div>
        <h3>{{ session.title || '(untitled)' }}</h3>
        <div class="meta">
            <div><span class="dim">Branch </span><code>{{ session.branch || '—' }}</code></div>
            <div><span class="dim">Client session ID </span><code>{{ session.clientSessionId }}</code></div>
            <div><span class="dim">Started </span>{{ formatDate(session.startedAt) }}</div>
            <div v-if="session.closedAt"><span class="dim">Closed </span>{{ formatDate(session.closedAt) }}</div>
            <div v-if="session.lastActivityAt"><span class="dim">Last activity </span>{{ formatDate(session.lastActivityAt) }}</div>
        </div>

        <n-tabs type="line" v-model:value="tab">
            <n-tab-pane name="overview" tab="Overview">
                <n-descriptions :column="2" bordered>
                    <n-descriptions-item label="Commits"><strong>{{ session.commits?.length ?? 0 }}</strong></n-descriptions-item>
                    <n-descriptions-item label="Artifacts"><strong>{{ session.artifacts?.length ?? 0 }}</strong></n-descriptions-item>
                    <n-descriptions-item label="Policy verdicts"><strong>{{ session.policyEvents?.length ?? 0 }}</strong></n-descriptions-item>
                    <n-descriptions-item label="API key"><code>{{ session.apiKey || '—' }}</code></n-descriptions-item>
                </n-descriptions>
            </n-tab-pane>
            <n-tab-pane name="commits" :tab="`Commits · ${session.commits?.length ?? 0}`">
                <div v-if="!session.commits?.length" class="empty">No commits attributed yet.</div>
                <n-data-table v-else :columns="commitColumns" :data="session.commits.map((c: string) => ({ uuid: c }))" :pagination="{ pageSize: 25 }"/>
            </n-tab-pane>
            <n-tab-pane name="artifacts" :tab="`Artifacts · ${session.artifacts?.length ?? 0}`">
                <div v-if="!session.artifacts?.length" class="empty">No artifacts attached.</div>
                <n-data-table v-else :columns="artifactColumns" :data="session.artifacts.map((a: string) => ({ uuid: a }))" :pagination="{ pageSize: 25 }"/>
            </n-tab-pane>
            <n-tab-pane name="policies" :tab="`Policies · ${session.policyEvents?.length ?? 0}`">
                <div v-if="!session.policyEvents?.length" class="empty">No policy evaluations on this session.</div>
                <n-data-table v-else :columns="policyColumns" :data="session.policyEvents" :pagination="{ pageSize: 25 }"/>
            </n-tab-pane>
        </n-tabs>
    </div>
    <n-spin v-else size="small"/>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NTabs, NTabPane, NTag, NDataTable, NSpin, NDescriptions, NDescriptionsItem, DataTableColumns } from 'naive-ui'

const store = useStore()
const route = useRoute()
const router = useRouter()

const sessionUuid = computed(() => route.params.uuid as string)
const session = ref<any>(null)
const agent = ref<any>(null)
const tab = ref<string>('overview')

onMounted(load)

async function load () {
    session.value = await store.dispatch('fetchSession', sessionUuid.value)
    if (session.value?.agent) {
        agent.value = await store.dispatch('fetchAgent', session.value.agent).catch(() => null)
    }
}

function openAgent () {
    if (agent.value) router.push({ name: 'AiAgentView', params: { uuid: agent.value.uuid } })
}

function formatDate (s: string | null | undefined) {
    return s ? new Date(s).toLocaleString() : '—'
}

const commitColumns: DataTableColumns<any> = [
    { title: 'SCE uuid', key: 'uuid', render: (row: any) => h('code', null, row.uuid) },
]
const artifactColumns: DataTableColumns<any> = [
    { title: 'Artifact uuid', key: 'uuid', render: (row: any) => h('code', null, row.uuid) },
]
const policyColumns: DataTableColumns<any> = [
    { title: 'Policy', key: 'policyName' },
    { title: 'Kind', key: 'kind', width: 90 },
    { title: 'Severity', key: 'severity', width: 100 },
    {
        title: 'State',
        key: 'state',
        width: 110,
        render: (row: any) => {
            const tone = row.state === 'PASSED' ? 'success'
                : row.state === 'WARNING' ? 'warning'
                : row.state === 'FAILED' ? 'error' : 'default'
            return h(NTag, { size: 'small', type: tone }, { default: () => row.state })
        },
    },
    { title: 'Message', key: 'message', render: (row: any) => row.message || '—' },
    { title: 'Evaluated', key: 'evaluatedAt', render: (row: any) => row.evaluatedAt ? new Date(row.evaluatedAt).toLocaleString() : '—' },
]
</script>

<style scoped>
.aiAgentSessionView { padding: 16px; }
.hero { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 13px; }
.dim { color: var(--n-text-color-3, #666); }
.meta { display: flex; gap: 24px; margin: 6px 0 18px 0; font-size: 13px; flex-wrap: wrap; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
</style>
