<template>
    <div class="aiAgentView" v-if="agent">
        <div class="hero">
            <div class="hero__mark" :style="{ background: agent.color || '#888' }">
                {{ agent.iconKind || '◆' }}
            </div>
            <div class="hero__title">
                <h3>{{ agent.name }}</h3>
                <div class="hero__sub">
                    <n-tag size="small" :type="agent.agentType === 'ROOT' ? 'info' : 'default'">{{ agent.agentType }}</n-tag>
                    <n-tag size="small" :type="agent.status === 'ACTIVE' ? 'success' : 'default'">{{ agent.status }}</n-tag>
                    <span v-if="agent.model" class="dim">
                        {{ agent.model.publisher }} · <code>{{ agent.model.name }}{{ agent.model.version ? ' @ ' + agent.model.version : '' }}</code>
                    </span>
                    <span class="dim" v-if="agent.lastActivityAt">
                        last activity {{ formatDate(agent.lastActivityAt) }}
                    </span>
                </div>
            </div>
            <div class="hero__stats">
                <div><div class="stat__v">{{ agent.sessionCounts?.openSessions ?? 0 }}</div><div class="stat__l">open</div></div>
                <div><div class="stat__v">{{ agent.sessionCounts?.closedSessions ?? 0 }}</div><div class="stat__l">closed</div></div>
                <div><div class="stat__v">{{ agent.subAgents?.length ?? 0 }}</div><div class="stat__l">sub-agents</div></div>
            </div>
        </div>

        <n-tabs type="line" v-model:value="tab">
            <n-tab-pane name="open" :tab="`Open sessions · ${openSessions.length}`">
                <SessionTable :rows="openSessions" :on-open="openSession"/>
            </n-tab-pane>
            <n-tab-pane name="closed" :tab="`Closed sessions · ${closedSessions.length}`">
                <SessionTable :rows="closedSessions" :on-open="openSession"/>
            </n-tab-pane>
            <n-tab-pane name="subs" :tab="`Sub-agents · ${subAgentRows.length}`">
                <n-data-table
                    :columns="subAgentColumns"
                    :data="subAgentRows"
                    :pagination="{ pageSize: 10 }"
                />
            </n-tab-pane>
            <n-tab-pane name="settings" tab="Settings">
                <n-descriptions :column="2" bordered>
                    <n-descriptions-item label="UUID"><code>{{ agent.uuid }}</code></n-descriptions-item>
                    <n-descriptions-item label="Org"><code>{{ agent.org }}</code></n-descriptions-item>
                    <n-descriptions-item label="API keys">{{ agent.apiKeys?.length ?? 0 }}</n-descriptions-item>
                    <n-descriptions-item label="Created">{{ formatDate(agent.createdDate) }}</n-descriptions-item>
                    <n-descriptions-item label="Notes" :span="2">{{ agent.notes || '—' }}</n-descriptions-item>
                </n-descriptions>
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
import SessionTable from './AiAgentSessionTable.vue'

const store = useStore()
const route = useRoute()
const router = useRouter()

const agentUuid = computed(() => route.params.uuid as string)
const agent = ref<any>(null)
const subAgentRows = ref<any[]>([])
const tab = ref<string>('open')

const openSessions = computed(() => agent.value?.openSessions ?? [])
const closedSessions = computed(() => agent.value?.closedSessions ?? [])

onMounted(load)

async function load () {
    agent.value = await store.dispatch('fetchAgent', agentUuid.value)
    if (agent.value?.subAgents?.length) {
        const subs = await Promise.all(
            agent.value.subAgents.map((uu: string) => store.dispatch('fetchAgent', uu).catch(() => null))
        )
        subAgentRows.value = subs.filter(Boolean)
    }
}

function openSession (uuid: string) {
    router.push({ name: 'AiAgentSessionView', params: { uuid } })
}

function formatDate (s: string | null | undefined) {
    return s ? new Date(s).toLocaleString() : '—'
}

const subAgentColumns: DataTableColumns<any> = [
    { title: 'Name', key: 'name', render: (row: any) => h('a', { onClick: () => router.push({ name: 'AiAgentView', params: { uuid: row.uuid } }) }, row.name) },
    { title: 'Status', key: 'status' },
    { title: 'Sessions', key: 'sess', render: (row: any) => (row.sessionCounts?.openSessions ?? 0) + ' open / ' + (row.sessionCounts?.closedSessions ?? 0) + ' closed' },
    { title: 'Last activity', key: 'lastActivityAt', render: (row: any) => row.lastActivityAt ? new Date(row.lastActivityAt).toLocaleString() : '—' },
]
</script>

<style scoped>
.aiAgentView { padding: 16px; }
.hero { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.hero__mark { width: 56px; height: 56px; border-radius: 12px; color: white; display: flex; align-items: center; justify-content: center; font-size: 28px; font-weight: 600; }
.hero__title { flex: 1; }
.hero__sub { display: flex; align-items: center; gap: 8px; font-size: 13px; margin-top: 4px; }
.dim { color: var(--n-text-color-3, #666); }
.hero__stats { display: flex; gap: 24px; }
.stat__v { font-size: 24px; font-weight: 600; text-align: center; }
.stat__l { font-size: 11px; color: var(--n-text-color-3, #666); text-transform: uppercase; letter-spacing: 0.04em; text-align: center; }
</style>
