<template>
    <div class="aiAgentSessionView" v-if="session">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="openAgentsOfOrg">AI Agents</n-breadcrumb-item>
            <n-breadcrumb-item v-if="agent" @click="openAgent">{{ agent.name }}</n-breadcrumb-item>
            <n-breadcrumb-item>Session {{ sessionShortLabel }}</n-breadcrumb-item>
        </n-breadcrumb>
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
                {{ agent.name }}<span v-if="agent.agentIdentity" class="dim agent-id"> — {{ agent.agentIdentity }}</span>
            </a>
        </div>
        <h3>{{ session.title || '(untitled)' }}</h3>

        <n-tabs type="line" v-model:value="tab">
            <n-tab-pane name="overview" tab="Overview">
                <n-descriptions :column="1" bordered label-placement="left" label-align="left" :label-style="metaLabelStyle">
                    <n-descriptions-item label="Status">
                        <n-tag :type="session.status === 'OPEN' ? 'info' : 'default'" size="small">{{ session.status }}</n-tag>
                    </n-descriptions-item>
                    <n-descriptions-item label="Title">{{ session.title || '—' }}</n-descriptions-item>
                    <n-descriptions-item label="Client session ID"><code>{{ session.clientSessionId }}</code></n-descriptions-item>
                    <n-descriptions-item label="API key"><code>{{ session.apiKey || '—' }}</code></n-descriptions-item>
                    <n-descriptions-item label="Started">{{ formatDate(session.startedAt) }}</n-descriptions-item>
                    <n-descriptions-item label="Closed">{{ formatDate(session.closedAt) }}</n-descriptions-item>
                    <n-descriptions-item label="Last activity">{{ formatDate(session.lastActivityAt) }}</n-descriptions-item>
                    <n-descriptions-item label="Commits"><strong>{{ session.commits?.length ?? 0 }}</strong></n-descriptions-item>
                    <n-descriptions-item label="Artifacts"><strong>{{ session.artifacts?.length ?? 0 }}</strong></n-descriptions-item>
                    <n-descriptions-item label="Releases"><strong>{{ releaseRows.length }}</strong></n-descriptions-item>
                    <n-descriptions-item label="Pull requests"><strong>{{ prRows.length }}</strong></n-descriptions-item>
                    <n-descriptions-item label="Policy verdicts">
                        <span v-if="!verdictSummary.total" class="dim">—</span>
                        <span v-else class="verdict-summary">
                            <n-tag v-if="verdictSummary.failed" size="tiny" type="error">{{ verdictSummary.failed }} FAILING</n-tag>
                            <n-tag v-if="verdictSummary.pending" size="tiny" type="warning">{{ verdictSummary.pending }} PENDING</n-tag>
                            <n-tag v-if="verdictSummary.warning" size="tiny" type="warning">{{ verdictSummary.warning }} WARNING</n-tag>
                            <n-tag v-if="verdictSummary.passed" size="tiny" type="success">{{ verdictSummary.passed }} PASSING</n-tag>
                        </span>
                    </n-descriptions-item>
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
            <n-tab-pane name="releases" :tab="`Releases · ${releaseRows.length}`">
                <div v-if="!releaseRows.length" class="empty">No releases produced from this session.</div>
                <n-data-table v-else :columns="releaseColumns" :data="releaseRows" :pagination="{ pageSize: 25 }"/>
            </n-tab-pane>
            <n-tab-pane name="prs" :tab="`Pull requests · ${prRows.length}`">
                <div v-if="!prRows.length" class="empty">No pull requests touched by this session.</div>
                <n-data-table v-else :columns="prColumns" :data="prRows" :pagination="{ pageSize: 25 }"/>
            </n-tab-pane>
            <n-tab-pane name="policies" :tab="`Policies · ${latestPolicyVerdicts.length}`">
                <div v-if="!latestPolicyVerdicts.length" class="empty">No policy evaluations on this session.</div>
                <template v-else>
                    <n-data-table :columns="policyColumns" :data="latestPolicyVerdicts" :pagination="{ pageSize: 25 }"/>
                    <p v-if="session.policyEvents?.length > latestPolicyVerdicts.length" class="dim mt-1">
                        Showing the latest verdict per policy.
                        <a href="#" @click.prevent="showFullPolicyHistory = !showFullPolicyHistory">
                            {{ showFullPolicyHistory ? 'Hide full audit log' : `Show full audit log (${session.policyEvents.length} entries)` }}
                        </a>
                    </p>
                    <n-data-table v-if="showFullPolicyHistory" :columns="policyColumns" :data="session.policyEvents" :pagination="{ pageSize: 25 }" style="margin-top: 12px;"/>
                </template>
            </n-tab-pane>
        </n-tabs>
    </div>
    <n-spin v-else size="small"/>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NBreadcrumb, NBreadcrumbItem, NTabs, NTabPane, NTag, NDataTable, NSpin, NDescriptions, NDescriptionsItem, NButton, NTooltip, DataTableColumns, useNotification } from 'naive-ui'
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
const showFullPolicyHistory = ref<boolean>(false)

const metaLabelStyle = { width: '180px' }

// Policies are an append-only log — collapse to the latest verdict per
// policy uuid by default so the table reads "what is currently true",
// with an opt-in audit-log expander for the full history.
const latestPolicyVerdicts = computed(() => {
    const events = session.value?.policyEvents ?? []
    const byPolicy = new Map<string, any>()
    for (const ev of events) {
        if (!ev?.policyUuid) continue
        byPolicy.set(ev.policyUuid, ev)
    }
    return Array.from(byPolicy.values())
})

// Per-policy outcome summary for the overview card.
const verdictSummary = computed(() => {
    const out = { passed: 0, failed: 0, pending: 0, warning: 0, total: 0 }
    for (const ev of latestPolicyVerdicts.value) {
        out.total++
        if (ev.state === 'PASSED') out.passed++
        else if (ev.state === 'FAILED') out.failed++
        else if (ev.state === 'PENDING') out.pending++
        else if (ev.state === 'WARNING') out.warning++
    }
    return out
})

// Distinct releases / PRs from per-SCE hydration; falls back to the
// Session.releases / Session.pullRequests lists when the session
// query returns them directly.
const releaseRows = computed<any[]>(() => {
    if (session.value?.releases?.length) return session.value.releases
    const byUuid = new Map<string, any>()
    for (const sce of commitRows.value) {
        for (const rel of (sce.releases ?? [])) {
            if (rel?.uuid) byUuid.set(rel.uuid, rel)
        }
    }
    return Array.from(byUuid.values())
})

const prRows = computed<any[]>(() => session.value?.pullRequests ?? [])

onMounted(load)

async function load () {
    session.value = await store.dispatch('fetchSession', sessionUuid.value)
    if (session.value?.agent) {
        agent.value = await store.dispatch('fetchAgent', session.value.agent).catch(() => null)
    }
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

function openAgentsOfOrg () {
    const orgUuid = agent.value?.org ?? session.value?.org
    if (orgUuid) {
        router.push({ name: 'AiAgentsOfOrg', params: { orguuid: orgUuid } })
    } else {
        router.back()
    }
}

const sessionShortLabel = computed(() => {
    const csid = session.value?.clientSessionId
    if (csid) return csid.length > 12 ? csid.slice(0, 12) + '…' : csid
    return session.value?.uuid?.slice(0, 8) ?? ''
})

function commitUrl (row: any): string | null {
    const uri: string | undefined = row.vcsRepository?.uri
    const commit: string | undefined = row.commit
    if (!uri || !commit) return null
    // Normalise common Git remotes to their commit-view URL.
    const trimmed = uri.replace(/\.git$/, '').replace(/\/$/, '')
    if (trimmed.startsWith('git@')) {
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
    // REARM-stored bytes — fetch the blob through the authenticated API
    // and trigger a browser download. For EXTERNALLY-stored artifacts
    // the UI links out to the first downloadLink directly (see template).
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
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

function openPolicy (uuid: string) {
    router.push({
        name: 'AiAgentPolicyView',
        params: { uuid },
        query: { from: 'session', sessionUuid: sessionUuid.value },
    })
}

function renderSignatureBadge (sig: any) {
    if (!sig || !sig.state || sig.state === 'UNSIGNED') {
        return h(NTag, { size: 'tiny', type: 'default' }, { default: () => 'unsigned' })
    }
    const state = sig.state as string
    const tone: 'success' | 'warning' | 'error' | 'default' =
        state === 'VERIFIED' ? 'success'
        : state === 'INVALID_SIGNATURE' || state === 'WRONG_SIGNER' || state === 'ERRORED' ? 'error'
        : state === 'UNKNOWN_KEY' || state === 'KEY_REVOKED' ? 'warning'
        : 'default'
    const tip = [
        sig.format ? `format: ${sig.format}` : '',
        sig.signedByOwnerType ? `owner: ${sig.signedByOwnerType}` : '',
        sig.keyFingerprint ? `fp: ${sig.keyFingerprint}` : '',
        sig.verifiedAt ? `verified: ${new Date(sig.verifiedAt).toLocaleString('en-CA')}` : '',
    ].filter(Boolean).join(' · ')
    return h(NTooltip, {}, {
        trigger: () => h(NTag, { size: 'tiny', type: tone, bordered: false }, { default: () => state }),
        default: () => tip || state,
    })
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
        title: 'Signature',
        key: 'signature',
        width: 130,
        render: (row: any) => renderSignatureBadge(row.signature),
    },
    {
        title: 'Message',
        key: 'commitMessage',
        ellipsis: { tooltip: true },
        render: (row: any) => (row.commitMessage || '').split('\n')[0] || '—',
    },
    {
        title: 'Release',
        key: 'releases',
        width: 240,
        render: (row: any) => {
            const releases: any[] = row.releases ?? []
            if (!releases.length) return '—'
            return h('span', null, releases.flatMap((rel, i) => {
                const label = rel.componentDetails?.name
                    ? `${rel.componentDetails.name} ${rel.version || ''}`.trim()
                    : (rel.version || rel.uuid.slice(0, 8))
                const lc = rel.lifecycle ? h(NTag, { size: 'small', bordered: false }, { default: () => rel.lifecycle }) : null
                const link = h('a', {
                    href: '#',
                    onClick: (e: Event) => { e.preventDefault(); router.push({ name: 'ReleaseView', params: { uuid: rel.uuid } }) },
                }, label)
                const parts = [link]
                if (lc) parts.push(h('span', { style: 'margin-left: 4px;' }, [lc]))
                if (i < releases.length - 1) parts.push(', ')
                return parts
            }))
        },
    },
    { title: 'Date', key: 'dateActual', width: 170, render: (row: any) => row.dateActual ? new Date(row.dateActual).toLocaleString('en-CA') : '—' },
]

function externalUri (a: any): string | null {
    const links = a.downloadLinks ?? []
    if (!links.length) return null
    return links[0].uri || null
}

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
        title: 'Tags',
        key: 'tags',
        render: (row: any) => {
            const tags = row.tags ?? []
            if (!tags.length) return ''
            return h('span', { style: 'display: inline-flex; gap: 4px; flex-wrap: wrap;' },
                tags.map((t: any) => h(NTag, { size: 'tiny', bordered: false },
                    { default: () => `${t.key}=${t.value}` })))
        },
    },
    {
        title: '',
        key: 'download',
        width: 140,
        render: (row: any) => {
            if (row.storedIn === 'REARM') {
                return h(NButton, { size: 'tiny', onClick: () => downloadArtifact(row) },
                    { default: () => 'Download' })
            }
            const uri = externalUri(row)
            if (uri) {
                return h('a', { href: uri, target: '_blank', rel: 'noopener' },
                    h(NButton, { size: 'tiny' }, { default: () => 'Open link ↗' }))
            }
            return h('span', { class: 'dim' }, 'external (no link)')
        },
    },
]

const releaseColumns: DataTableColumns<any> = [
    {
        title: 'Component',
        key: 'component',
        render: (row: any) => row.componentDetails?.name || row.component?.slice(0, 8) || '—',
    },
    {
        title: 'Version',
        key: 'version',
        render: (row: any) => h('a', {
            href: '#',
            onClick: (e: Event) => { e.preventDefault(); router.push({ name: 'ReleaseView', params: { uuid: row.uuid } }) },
        }, row.version || '—'),
    },
    {
        title: 'Lifecycle',
        key: 'lifecycle',
        width: 160,
        render: (row: any) => row.lifecycle ? h(NTag, { size: 'small', bordered: false }, { default: () => row.lifecycle }) : '—',
    },
    {
        title: 'Created',
        key: 'createdDate',
        width: 170,
        render: (row: any) => row.createdDate ? new Date(row.createdDate).toLocaleString('en-CA') : '—',
    },
]

const prColumns: DataTableColumns<any> = [
    {
        title: 'PR',
        key: 'identity',
        width: 140,
        render: (row: any) => h('a', {
            href: '#',
            onClick: (e: Event) => { e.preventDefault(); router.push({ name: 'PullRequestView', params: { uuid: row.uuid } }) },
        }, row.identity || row.uuid?.slice(0, 8)),
    },
    { title: 'Title', key: 'title', ellipsis: { tooltip: true } },
    {
        title: 'State',
        key: 'state',
        width: 120,
        render: (row: any) => row.state ? h(NTag, { size: 'small', bordered: false, type: row.state === 'OPEN' ? 'success' : (row.state === 'MERGED' ? 'info' : 'default') }, { default: () => row.state }) : '—',
    },
    { title: 'Source', key: 'sourceBranchName', render: (row: any) => row.sourceBranchName ? h('code', null, row.sourceBranchName) : '—' },
    { title: 'Target', key: 'targetBranchName', render: (row: any) => row.targetBranchName ? h('code', null, row.targetBranchName) : '—' },
    {
        title: 'Created',
        key: 'prCreatedDate',
        width: 170,
        render: (row: any) => row.prCreatedDate ? new Date(row.prCreatedDate).toLocaleString('en-CA') : '—',
    },
]

const policyColumns: DataTableColumns<any> = [
    {
        title: 'Policy',
        key: 'policyName',
        render: (row: any) => row.policyUuid
            ? h('a', {
                href: '#',
                onClick: (e: Event) => { e.preventDefault(); openPolicy(row.policyUuid) },
            }, row.policyName || row.policyUuid.slice(0, 8))
            : (row.policyName || '—'),
    },
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
    { title: 'Evaluated', key: 'evaluatedAt', width: 170, render: (row: any) => row.evaluatedAt ? new Date(row.evaluatedAt).toLocaleString('en-CA') : '—' },
]
</script>

<style scoped>
.aiAgentSessionView { padding: 16px; }
.crumbs { margin-bottom: 12px; font-size: 13px; }
.crumbs :deep(.n-breadcrumb-item__link) { cursor: pointer; }
.hero { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 13px; }
.dim { color: var(--n-text-color-3, #666); }
.agent-id { font-family: monospace; font-size: 11px; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
.mt-1 { margin-top: 8px; font-size: 12px; }
.verdict-summary { display: inline-flex; gap: 6px; flex-wrap: wrap; }
</style>
