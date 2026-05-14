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
                <n-data-table v-else :columns="commitColumns" :data="commitRows" :pagination="{ pageSize: 25 }"/>
            </n-tab-pane>
            <n-tab-pane name="artifacts" :tab="`Artifacts · ${session.artifacts?.length ?? 0}`">
                <div v-if="!session.artifacts?.length" class="empty">No artifacts attached.</div>
                <n-data-table v-else :columns="artifactColumns" :data="artifactRows" :pagination="{ pageSize: 25 }"/>
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
import { NTabs, NTabPane, NTag, NDataTable, NSpin, NDescriptions, NDescriptionsItem, NButton, DataTableColumns, useNotification } from 'naive-ui'
import { fetchArrayBufferWithAuth } from '@/utils/fetchClient'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const sessionUuid = computed(() => route.params.uuid as string)
const session = ref<any>(null)
const agent = ref<any>(null)
const commitRows = ref<any[]>([])
const artifactRows = ref<any[]>([])
const tab = ref<string>('overview')

onMounted(load)

async function load () {
    session.value = await store.dispatch('fetchSession', sessionUuid.value)
    if (session.value?.agent) {
        agent.value = await store.dispatch('fetchAgent', session.value.agent).catch(() => null)
    }
    // Resolve commit + artifact uuids to objects in parallel so the
    // tabs render rich rows without per-row N+1 fetches at render time.
    const commitUuids: string[] = session.value?.commits ?? []
    const artifactUuids: string[] = session.value?.artifacts ?? []
    commitRows.value = (await Promise.all(commitUuids.map((u) =>
        store.dispatch('fetchSourceCodeEntryByUuid', u).catch(() => ({ uuid: u }))
    ))).filter(Boolean)
    artifactRows.value = (await Promise.all(artifactUuids.map((u) =>
        store.dispatch('fetchArtifactByUuid', u).catch(() => ({ uuid: u }))
    ))).filter(Boolean)
}

function openAgent () {
    if (agent.value) router.push({ name: 'AiAgentView', params: { uuid: agent.value.uuid } })
}

function commitUrl (row: any): string | null {
    const uri: string | undefined = row.vcsRepository?.uri
    const commit: string | undefined = row.commit
    if (!uri || !commit) return null
    // Normalise common Git remotes to their commit-view URL. github.com /
    // gitlab.com / bitbucket.org / GitHub Enterprise all use `/commit/<sha>`.
    const trimmed = uri.replace(/\.git$/, '').replace(/\/$/, '')
    if (trimmed.startsWith('git@')) {
        // git@host:owner/repo -> https://host/owner/repo
        const idx = trimmed.indexOf(':')
        if (idx > 0) {
            return `https://${trimmed.slice(4, idx)}/${trimmed.slice(idx + 1)}/commit/${commit}`
        }
        return null
    }
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
        return `${trimmed}/commit/${commit}`
    }
    return null
}

async function downloadArtifact (a: any) {
    try {
        const url = `/api/manual/v1/artifact/${a.uuid}/rawdownload`
        const buf = await fetchArrayBufferWithAuth(url)
        const blob = new Blob([buf])
        const link = document.createElement('a')
        link.href = URL.createObjectURL(blob)
        link.download = `${a.displayIdentifier || a.uuid}-${a.type || 'artifact'}`
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(link.href)
    } catch (e: any) {
        notification.error({ content: `Download failed: ${e?.message ?? e}` })
    }
}

function formatDate (s: string | null | undefined) {
    return s ? new Date(s).toLocaleString() : '—'
}

const commitColumns: DataTableColumns<any> = [
    {
        title: 'Commit',
        key: 'commit',
        width: 110,
        render: (row: any) => {
            const short = row.commit ? row.commit.slice(0, 10) : row.uuid?.slice(0, 8)
            const url = commitUrl(row)
            if (url) {
                return h('a', { href: url, target: '_blank', rel: 'noopener' }, h('code', null, short ?? '—'))
            }
            return h('code', null, short ?? '—')
        },
    },
    { title: 'Branch', key: 'vcsBranch', render: (row: any) => row.vcsBranch ? h('code', null, row.vcsBranch) : '—' },
    {
        title: 'Message',
        key: 'commitMessage',
        ellipsis: { tooltip: true },
        render: (row: any) => {
            const subject = (row.commitMessage || '').split('\n')[0] || '—'
            return subject
        },
    },
    { title: 'Date', key: 'dateActual', render: (row: any) => row.dateActual ? new Date(row.dateActual * 1000).toLocaleString() : '—' },
]
const artifactColumns: DataTableColumns<any> = [
    {
        title: 'Name',
        key: 'displayIdentifier',
        render: (row: any) => row.displayIdentifier || h('code', null, row.uuid?.slice(0, 8) ?? ''),
    },
    {
        title: 'Type',
        key: 'type',
        width: 180,
        render: (row: any) => row.type ? h(NTag, { size: 'small', type: row.type === 'AGENTIC_REPORT' ? 'info' : 'default' }, { default: () => row.type }) : '—',
    },
    {
        title: '',
        key: 'download',
        width: 110,
        render: (row: any) => h(NButton, {
            size: 'tiny',
            quaternary: true,
            disabled: row.storedIn !== 'REARM',
            onClick: () => downloadArtifact(row),
        }, { default: () => row.storedIn === 'REARM' ? 'Download' : 'External' }),
    },
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
