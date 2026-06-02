<template>
    <div class="aiAgentView" v-if="agent">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="openAgentsOfOrg">AI Agents</n-breadcrumb-item>
            <n-breadcrumb-item>{{ agentDisplay }}</n-breadcrumb-item>
        </n-breadcrumb>
        <div class="hero">
            <div class="hero__mark" :style="{ background: agent.color || '#888' }">
                {{ agent.iconKind || '◆' }}
            </div>
            <div class="hero__title">
                <div v-if="!editingName" class="hero__name-row">
                    <h3>{{ agentDisplay }}</h3>
                    <n-icon v-if="isOrgAdmin" class="hero__edit-name" size="18"
                            title="Edit display name" @click="startEditName"><EditIcon/></n-icon>
                </div>
                <div v-else class="hero__name-edit">
                    <n-input v-model:value="nameDraft" size="small" placeholder="Display name (blank = use registration name)"
                             style="max-width: 320px;" @keyup.enter="saveName"/>
                    <n-button size="small" type="primary" :loading="savingName" @click="saveName">Save</n-button>
                    <n-button size="small" quaternary @click="editingName = false">Cancel</n-button>
                </div>
                <div class="hero__ids">
                    <n-tooltip trigger="hover">
                        <template #trigger>
                            <code class="hero__chip"><span class="hero__chip-l">uuid</span>{{ shortUuid(agent.uuid) }}</code>
                        </template>
                        ReARM-issued row uuid. Used in URLs, commit trailers
                        (<code>ReARM-Agent</code>), and as the owner reference
                        for signing keys. Unique per agent.
                    </n-tooltip>
                    <n-tooltip v-if="agent.agentIdentity" trigger="hover" :width="320">
                        <template #trigger>
                            <code class="hero__chip" :class="identityShareCount > 1 ? 'hero__chip--shared' : ''">
                                <span class="hero__chip-l">identity</span>{{ shortUuid(agent.agentIdentity) }}<span v-if="identityShareCount > 1" class="hero__shared">+{{ identityShareCount - 1 }}</span>
                            </code>
                        </template>
                        Credential-scoped identity. Agents registered through the same
                        FREEFORM key (or OIDC subject) share one identity — their
                        commits and sessions roll up to the same credential.
                        <span v-if="identityShareCount > 1">
                            <br><br>This identity is shared with
                            {{ identityShareCount - 1 }} other agent{{ identityShareCount > 2 ? 's' : '' }}
                            in this org. Click the
                            <strong>Sibling agents</strong> tab below to see them.
                        </span>
                    </n-tooltip>
                </div>
                <div class="hero__sub">
                    <n-tag size="small" :type="agent.agentType === 'ROOT' ? 'info' : 'default'">{{ agent.agentType }}</n-tag>
                    <n-tag v-if="agent.status === 'ARCHIVED'" size="small" type="default">ARCHIVED</n-tag>
                    <span v-if="agent.model" class="dim">
                        <template v-if="agent.model.publisher">{{ agent.model.publisher }} · </template><code>{{ agent.model.name }}{{ agent.model.version && agent.model.version !== 'unknown' ? ' @ ' + agent.model.version : '' }}</code>
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

        <n-tabs type="segment" v-model:value="tab" animated>
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
            <n-tab-pane v-if="siblingAgents.length" name="siblings" :tab="`Sibling agents · ${siblingAgents.length}`">
                <p class="dim sib-note">
                    Agents sharing the same <code>agentIdentity</code> were registered through
                    the same credential. Each row is its own Agent (own uuid, own signing keys);
                    the identity column is the grouping label.
                </p>
                <n-data-table
                    :columns="siblingColumns"
                    :data="siblingAgents"
                    :pagination="{ pageSize: 10 }"
                />
            </n-tab-pane>
            <n-tab-pane name="keys" tab="Signing keys">
                <SigningKeyManager
                    v-if="agent?.org && agent?.uuid"
                    :org="agent.org"
                    owner-type="AGENT"
                    :owner-uuid="agent.uuid"
                    :can-edit-identity="isOrgAdmin"
                />
            </n-tab-pane>
            <n-tab-pane name="metadata" tab="Metadata">
                <n-descriptions :column="1" bordered label-placement="left" label-align="left" :label-style="metaLabelStyle">
                    <n-descriptions-item label="UUID"><code>{{ agent.uuid }}</code></n-descriptions-item>
                    <n-descriptions-item label="Org"><code>{{ agent.org }}</code></n-descriptions-item>
                    <n-descriptions-item>
                        <template #label>
                            Agent name
                            <n-tooltip trigger="hover" :width="320">
                                <template #trigger>
                                    <n-icon size="12" class="help-icon"><Info20Regular/></n-icon>
                                </template>
                                Registration name the runtime supplies via --agent-name.
                                Part of the resolution key — immutable. Weakest input to the
                                shown name.
                            </n-tooltip>
                        </template>
                        <code>{{ agent.name }}</code>
                    </n-descriptions-item>
                    <n-descriptions-item>
                        <template #label>
                            Bound key note(s)
                            <n-tooltip trigger="hover" :width="320">
                                <template #trigger>
                                    <n-icon size="12" class="help-icon"><Info20Regular/></n-icon>
                                </template>
                                Note(s) on the bound FREEFORM key(s). Used as the shown name
                                when no display name is set. Edit under Org Settings › Free
                                Form Keys.
                            </n-tooltip>
                        </template>
                        <span v-if="keyNotes.length">{{ keyNotes.join(', ') }}</span>
                        <span v-else class="dim">—</span>
                    </n-descriptions-item>
                    <n-descriptions-item>
                        <template #label>
                            Display name
                            <n-tooltip trigger="hover" :width="320">
                                <template #trigger>
                                    <n-icon size="12" class="help-icon"><Info20Regular/></n-icon>
                                </template>
                                Admin-set override (edit it in the header). Strongest input —
                                wins over the key note and the registration name.
                            </n-tooltip>
                        </template>
                        <span v-if="agent.displayName">{{ agent.displayName }}</span>
                        <span v-else class="dim">— (not set)</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Shown as">
                        <strong>{{ agentDisplay }}</strong>
                    </n-descriptions-item>
                    <n-descriptions-item>
                        <template #label>
                            Identity
                            <n-tooltip trigger="hover" :width="320">
                                <template #trigger>
                                    <n-icon size="12" class="help-icon"><Info20Regular/></n-icon>
                                </template>
                                Credential-scoped identity. Multiple Agent rows may share one
                                identity if they were registered through the same credential
                                (FREEFORM key / OIDC subject). Use it to group agents that
                                belong to the same installation.
                            </n-tooltip>
                        </template>
                        <code>{{ agent.agentIdentity || '—' }}</code>
                        <span v-if="siblingAgents.length" class="dim">
                            &nbsp;· shared with
                            <a href="#" @click.prevent="tab = 'siblings'">
                                {{ siblingAgents.length }} other agent{{ siblingAgents.length > 1 ? 's' : '' }}
                            </a>
                        </span>
                    </n-descriptions-item>
                    <n-descriptions-item>
                        <template #label>
                            Bound API key(s)
                            <n-tooltip trigger="hover" :width="320">
                                <template #trigger>
                                    <n-icon size="12" class="help-icon"><Info20Regular/></n-icon>
                                </template>
                                FREEFORM API key(s) bound to this agent's identity — the
                                credential an agent runtime authenticates with to drive this
                                agent. Manage them under Org Settings › Free Form Keys.
                            </n-tooltip>
                        </template>
                        <span v-if="boundApiKeys.length">
                            <code v-for="(k, idx) in boundApiKeys" :key="k.uuid" class="bound-key">
                                <span v-if="idx > 0">, </span>{{ apiKeyLabel(k) }}
                            </code>
                        </span>
                        <span v-else class="dim">—</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Type">{{ agent.agentType }}</n-descriptions-item>
                    <n-descriptions-item label="Status">{{ agent.status }}</n-descriptions-item>
                    <n-descriptions-item label="Model">
                        <span v-if="agent.model"><template v-if="agent.model.publisher">{{ agent.model.publisher }} · </template><code>{{ agent.model.name }}{{ agent.model.version && agent.model.version !== 'unknown' ? ' @ ' + agent.model.version : '' }}</code></span>
                        <span v-else class="dim">—</span>
                    </n-descriptions-item>
                    <n-descriptions-item label="Created">{{ formatDate(agent.createdDate) }}</n-descriptions-item>
                    <n-descriptions-item label="Last activity">{{ formatDate(agent.lastActivityAt) }}</n-descriptions-item>
                    <n-descriptions-item label="Notes">
                        <div v-if="!editingNotes" class="notes-row">
                            <span class="notes-text">{{ agent.notes || '—' }}</span>
                            <n-button size="tiny" quaternary @click="startEditNotes">Edit</n-button>
                        </div>
                        <div v-else class="notes-edit">
                            <n-input v-model:value="notesDraft" type="textarea" :rows="3" placeholder="Free-form notes (markdown not rendered)"/>
                            <div class="notes-actions">
                                <n-button size="small" type="primary" :loading="savingNotes" @click="saveNotes">Save</n-button>
                                <n-button size="small" quaternary @click="cancelEditNotes">Cancel</n-button>
                            </div>
                        </div>
                    </n-descriptions-item>
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
import { NBreadcrumb, NBreadcrumbItem, NTabs, NTabPane, NTag, NDataTable, NSpin, NDescriptions, NDescriptionsItem, NButton, NIcon, NInput, NTooltip, DataTableColumns, useNotification } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'
import { Edit as EditIcon } from '@vicons/tabler'
import SessionTable from './AiAgentSessionTable.vue'
import SigningKeyManager from './SigningKeyManager.vue'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const agentUuid = computed(() => route.params.uuid as string)
const agent = ref<any>(null)
const subAgentRows = ref<any[]>([])
const siblingAgents = ref<any[]>([])
const tab = ref<string>('open')
const editingNotes = ref<boolean>(false)
const notesDraft = ref<string>('')
const savingNotes = ref<boolean>(false)
const editingName = ref<boolean>(false)
const nameDraft = ref<string>('')
const savingName = ref<boolean>(false)

const myUser = computed<any>(() => store.getters.myuser)
const isOrgAdmin = computed<boolean>(() => {
    const org = agent.value?.org
    const perms = myUser.value?.permissions?.permissions
    if (!org || !perms) return false
    return perms.some((p: any) => p.org === org && p.object === org
        && p.scope === 'ORGANIZATION' && p.type === 'ADMIN')
})

const agentDisplay = computed(() => agent.value?.effectiveDisplayName || agent.value?.name || '')

const openSessions = computed(() => agent.value?.openSessions ?? [])
const closedSessions = computed(() => agent.value?.closedSessions ?? [])

const boundApiKeys = computed(() => agent.value?.boundApiKeys ?? [])

const keyNotes = computed<string[]>(() =>
    boundApiKeys.value.map((k: any) => k.notes).filter((n: any) => !!n && String(n).trim()))

function apiKeyLabel (k: any): string {
    let label = (k.type || 'FREEFORM') + '__' + k.object
    if (k.keyOrder) label += '__ord__' + k.keyOrder
    return label
}

const identityShareCount = computed(() => 1 + siblingAgents.value.length)

const metaLabelStyle = { width: '180px' }

onMounted(load)

async function load () {
    agent.value = await store.dispatch('fetchAgent', agentUuid.value)
    if (agent.value?.subAgents?.length) {
        const subs = await Promise.all(
            agent.value.subAgents.map((uu: string) => store.dispatch('fetchAgent', uu).catch(() => null))
        )
        subAgentRows.value = subs.filter(Boolean)
    }
    // Resolve other ROOT agents in the same org that share this agentIdentity —
    // the identity is the "credential boundary"; siblings are agents that
    // were registered through the same key. Excludes the current agent.
    if (agent.value?.org && agent.value?.agentIdentity) {
        const orgAgents = await store.dispatch('fetchAgentsOfOrg', agent.value.org).catch(() => [])
        siblingAgents.value = (orgAgents || []).filter((a: any) =>
            a.uuid !== agent.value.uuid &&
            a.agentIdentity === agent.value.agentIdentity)
    }
}

function shortUuid (u: string | null | undefined): string {
    return u ? `${u.slice(0, 8)}…${u.slice(-4)}` : ''
}

function openSibling (uuid: string) {
    router.push({ name: 'AiAgentView', params: { uuid } })
}

const siblingColumns: DataTableColumns<any> = [
    {
        title: 'Name',
        key: 'name',
        render: (row: any) => h('a', {
            href: '#',
            onClick: (e: Event) => { e.preventDefault(); openSibling(row.uuid) },
        }, row.effectiveDisplayName || row.name),
    },
    { title: 'UUID', key: 'uuid', render: (row: any) => h('code', { class: 'mono-cell' }, shortUuid(row.uuid)) },
    { title: 'Type', key: 'agentType', width: 80 },
    { title: 'Status', key: 'status', width: 100 },
    {
        title: 'Model',
        key: 'model',
        render: (row: any) => row.model
            ? `${[row.model.publisher, row.model.name].filter(Boolean).join(' · ')}${row.model.version && row.model.version !== 'unknown' ? ' @ ' + row.model.version : ''}`
            : '—',
    },
    { title: 'Created', key: 'createdDate', render: (row: any) => formatDate(row.createdDate) },
]

function openSession (uuid: string) {
    router.push({ name: 'AiAgentSessionView', params: { uuid } })
}

function openAgentsOfOrg () {
    if (agent.value?.org) {
        router.push({ name: 'AiAgentsOfOrg', params: { orguuid: agent.value.org } })
    } else {
        router.back()
    }
}

function formatDate (s: string | null | undefined) {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

function startEditNotes () {
    notesDraft.value = agent.value?.notes ?? ''
    editingNotes.value = true
}

function cancelEditNotes () {
    editingNotes.value = false
}

async function saveNotes () {
    savingNotes.value = true
    try {
        await store.dispatch('updateAgent', {
            uuid: agent.value.uuid,
            notes: notesDraft.value,
        })
        agent.value.notes = notesDraft.value
        editingNotes.value = false
        notification.success({ content: 'Notes saved' })
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        savingNotes.value = false
    }
}

function startEditName () {
    nameDraft.value = agent.value?.displayName ?? ''
    editingName.value = true
}

async function saveName () {
    savingName.value = true
    try {
        const updated = await store.dispatch('setAgentDisplayName', {
            uuid: agent.value.uuid,
            displayName: nameDraft.value.trim() || null,
        })
        agent.value.displayName = updated.displayName
        editingName.value = false
        notification.success({ content: 'Display name saved' })
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        savingName.value = false
    }
}

const subAgentColumns: DataTableColumns<any> = [
    { title: 'Name', key: 'name', render: (row: any) => h('a', { onClick: () => router.push({ name: 'AiAgentView', params: { uuid: row.uuid } }) }, row.effectiveDisplayName || row.name) },
    { title: 'Sessions', key: 'sess', render: (row: any) => (row.sessionCounts?.openSessions ?? 0) + ' open / ' + (row.sessionCounts?.closedSessions ?? 0) + ' closed' },
    { title: 'Last activity', key: 'lastActivityAt', render: (row: any) => row.lastActivityAt ? new Date(row.lastActivityAt).toLocaleString('en-CA') : '—' },
]
</script>

<style scoped>
.aiAgentView { padding: 16px; }
.crumbs { margin-bottom: 12px; font-size: 13px; }
.crumbs :deep(.n-breadcrumb-item__link) { cursor: pointer; }
.hero { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.hero__mark { width: 56px; height: 56px; border-radius: 12px; color: white; display: flex; align-items: center; justify-content: center; font-size: 28px; font-weight: 600; }
.hero__title { flex: 1; }
.hero__name-row { display: flex; align-items: center; gap: 8px; }
.hero__name-row h3 { margin: 0; }
.hero__edit-name { align-self: center; cursor: pointer; color: var(--n-text-color-3, #888); }
.hero__edit-name:hover { color: var(--n-primary-color, #18a058); }
.hero__name-edit { display: flex; align-items: center; gap: 6px; }
.hero__id { font-weight: 400; font-family: monospace; font-size: 14px; color: var(--n-text-color-3, #666); }
.hero__ids { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; margin-bottom: 4px; }
.hero__chip { font-family: monospace; font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--n-color-embedded, #f5f5f5); color: var(--n-text-color-2, #555); border: 1px solid transparent; }
.hero__chip-l { text-transform: uppercase; font-size: 9px; letter-spacing: 0.06em; color: var(--n-text-color-3, #888); margin-right: 4px; }
.hero__chip--shared { background: #fff8e1; border-color: #ffe082; color: #5d4037; }
.hero__shared { margin-left: 4px; font-size: 10px; font-weight: 600; color: #b26a00; }
.hero__sub { display: flex; align-items: center; gap: 8px; font-size: 13px; margin-top: 4px; }
.dim { color: var(--n-text-color-3, #666); }
.hero__stats { display: flex; gap: 24px; }
.help-icon { color: #888; cursor: help; vertical-align: middle; margin-left: 4px; }
.sib-note { font-size: 12px; margin-bottom: 8px; }
:deep(.mono-cell) { font-family: monospace; font-size: 11px; }
.stat__v { font-size: 24px; font-weight: 600; text-align: center; }
.stat__l { font-size: 11px; color: var(--n-text-color-3, #666); text-transform: uppercase; letter-spacing: 0.04em; text-align: center; }
.notes-row { display: flex; align-items: center; gap: 12px; }
.notes-text { white-space: pre-wrap; }
.notes-edit { display: flex; flex-direction: column; gap: 6px; }
.notes-actions { display: flex; gap: 8px; }
</style>
