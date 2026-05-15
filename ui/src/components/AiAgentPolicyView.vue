<template>
    <div class="agentPolicyView">
        <div class="head">
            <n-button quaternary @click="back">‹ Back</n-button>
            <h4>{{ isNew ? 'New AI agent policy' : (form.name || 'Edit policy') }}</h4>
        </div>

        <n-spin v-if="loading" size="small"/>
        <n-form v-else label-placement="top" :model="form">
            <n-grid :cols="2" x-gap="20">
                <n-form-item-gi label="Name" required>
                    <n-input v-model:value="form.name" placeholder="e.g. Orientation report required"/>
                </n-form-item-gi>
                <n-form-item-gi label="Enabled">
                    <n-switch v-model:value="form.enabled"/>
                </n-form-item-gi>
            </n-grid>

            <n-form-item label="Description" required>
                <n-input v-model:value="form.description" type="textarea" :rows="2"
                         placeholder="Operator-facing message shown when this policy is violated."/>
            </n-form-item>

            <n-form-item label="Kind" required>
                <n-radio-group v-model:value="form.kind">
                    <n-radio-button v-for="opt in kindOptions" :key="opt.value" :value="opt.value">
                        {{ opt.value }}
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-icon size="14" class="opt-help">
                                    <QuestionCircle20Regular/>
                                </n-icon>
                            </template>
                            <span v-html="opt.help"/>
                        </n-tooltip>
                    </n-radio-button>
                </n-radio-group>
            </n-form-item>

            <n-form-item label="Severity" required>
                <n-radio-group v-model:value="form.severity">
                    <n-radio-button v-for="opt in severityOptions" :key="opt.value" :value="opt.value">
                        {{ opt.value }}
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-icon size="14" class="opt-help">
                                    <QuestionCircle20Regular/>
                                </n-icon>
                            </template>
                            <span v-html="opt.help"/>
                        </n-tooltip>
                    </n-radio-button>
                </n-radio-group>
            </n-form-item>

            <n-form-item required>
                <template #label>
                    CEL expression
                    <n-popover trigger="click" placement="bottom-start" :width="640" style="max-height: 70vh; overflow: auto;">
                        <template #trigger>
                            <n-icon size="14" class="opt-help">
                                <QuestionCircle20Regular/>
                            </n-icon>
                        </template>
                        <div>
                            <div style="margin-bottom: 6px; font-size: 11px; color: #888;">
                                Click the <n-icon size="12" style="vertical-align: middle;"><ClipboardPaste20Regular/></n-icon>
                                icon to insert a snippet (appended with <code>&amp;&amp;</code> when text already exists).
                            </div>
                            <strong>Available variables:</strong>
                            <table class="ref-table">
                                <tbody>
                                    <tr v-for="v in variableDocs" :key="v.name">
                                        <td class="ref-paste">
                                            <n-icon size="14" class="clickable" style="color: #888; vertical-align: middle;"
                                                @click="insertSnippet(v.snippet)" title="Insert">
                                                <ClipboardPaste20Regular/>
                                            </n-icon>
                                        </td>
                                        <td class="ref-name"><code v-html="v.display"/></td>
                                        <td v-html="v.desc"/>
                                    </tr>
                                </tbody>
                            </table>
                            <div style="margin-top: 10px;"><strong>Snippets:</strong></div>
                            <table class="ref-table">
                                <tbody>
                                    <tr v-for="(ex, i) in snippetDocs" :key="i">
                                        <td class="ref-paste">
                                            <n-icon size="14" class="clickable" style="color: #888;"
                                                @click="insertSnippet(ex.cel)" title="Insert">
                                                <ClipboardPaste20Regular/>
                                            </n-icon>
                                        </td>
                                        <td>
                                            <div><code class="snippet">{{ ex.cel }}</code></div>
                                            <div class="snippet-desc">{{ ex.label }}</div>
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </n-popover>
                </template>
                <n-input v-model:value="form.cel" type="textarea" :rows="3"
                         placeholder='e.g. session.artifacts.exists(a, a.type == "AGENTIC_REPORT")'
                         style="font-family: monospace;"/>
            </n-form-item>

            <n-collapse>
                <n-collapse-item title="Sample policies" name="samples">
                    <n-alert type="warning" size="small" :show-icon="false" style="margin-bottom: 10px;">
                        Clicking a sample below overwrites every field on the form
                        (name, description, kind, severity, CEL). Use it as a starting
                        point on a fresh policy — not to tweak an existing one.
                    </n-alert>
                    <div class="samples">
                        <n-button v-for="s in samples" :key="s.name" size="small" @click="applySample(s)">
                            <n-tag size="tiny" :type="s.kind === 'INPUT' ? 'info' : 'default'" style="margin-right: 4px;">{{ s.kind }}</n-tag>
                            <n-tag size="tiny" :type="s.severity === 'BLOCK' ? 'error' : 'warning'" style="margin-right: 6px;">{{ s.severity }}</n-tag>
                            {{ s.name }}
                        </n-button>
                    </div>
                </n-collapse-item>
            </n-collapse>

            <div class="actions">
                <n-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
                    {{ isNew ? 'Create policy' : 'Save changes' }}
                </n-button>
                <n-popconfirm v-if="!isNew" @positive-click="remove">
                    <template #trigger>
                        <n-button quaternary type="error">Delete</n-button>
                    </template>
                    Delete this policy? Past verdicts on sessions keep their recorded policy uuid for forensic value.
                </n-popconfirm>
            </div>
        </n-form>
    </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import {
    NAlert, NButton, NCollapse, NCollapseItem, NForm, NFormItem, NFormItemGi, NGrid,
    NIcon, NInput, NPopconfirm, NPopover, NRadioButton, NRadioGroup, NSpin, NSwitch,
    NTag, NTooltip, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular, ClipboardPaste20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const isNew = computed(() => route.params.uuid === 'new')
const policyUuid = computed(() => route.params.uuid as string)
const orgFromQuery = computed(() => route.query.org as string | undefined)

const loading = ref<boolean>(true)
const saving = ref<boolean>(false)
const form = ref<any>({
    uuid: null,
    org: '',
    name: '',
    description: '',
    kind: 'OUTPUT',
    severity: 'BLOCK',
    cel: '',
    enabled: true,
})

const canSave = computed(() =>
    form.value.name?.trim()
    && form.value.kind
    && form.value.severity
    && form.value.cel?.trim()
)

const kindOptions = [
    {
        value: 'INPUT',
        help: '<strong>INPUT</strong> — precondition on the session itself. Checked at <code>sessionInitializeProgrammatic</code>; a BLOCK-FAILED verdict throws and the session never opens. Use for allowlists (model, branch, identity).',
    },
    {
        value: 'OUTPUT',
        help: '<strong>OUTPUT</strong> — postcondition on session work. Checked at init (records PENDING — soft signal to the agent), re-checked when an artifact attaches, hardens to FAILED when a commit is attributed without satisfying the CEL. Use for "agent must produce X" rules.',
    },
]

const severityOptions = [
    {
        value: 'BLOCK',
        help: '<strong>BLOCK</strong> — failure hardens to FAILED on the session. INPUT throws at init; OUTPUT trips the <code>release.agentSessions[].hasFailedPolicy</code> signal that the approval-policy layer reads.',
    },
    {
        value: 'WARN',
        help: '<strong>WARN</strong> — failure records a WARNING verdict on the session log but never blocks anything.',
    },
]

// CEL-reference table — every row is clickable to insert the snippet.
// Convention mirrors CelExpressionBuilder.vue's popover.
interface VariableDoc { name: string; snippet: string; display: string; desc: string }
const variableDocs: VariableDoc[] = [
    { name: 'session.status',            snippet: 'session.status',                                                          display: 'session.status',            desc: 'string — <code>OPEN</code> / <code>CLOSED</code>' },
    { name: 'session.branch',            snippet: 'session.branch',                                                          display: 'session.branch',            desc: 'string — agent-reported working branch' },
    { name: 'session.title',             snippet: 'session.title',                                                           display: 'session.title',             desc: 'string — agent-supplied title' },
    { name: 'session.clientSessionId',   snippet: 'session.clientSessionId',                                                 display: 'session.clientSessionId',   desc: 'string — agent-supplied session id' },
    { name: 'session.artifacts',         snippet: 'session.artifacts',                                                       display: 'session.artifacts',         desc: 'list of <code>{ uuid, type, displayIdentifier, bomFormat, tags[] }</code>' },
    { name: 'session.commits',           snippet: 'session.commits',                                                         display: 'session.commits',           desc: 'list&lt;string&gt; — SCE uuids' },
    { name: 'agent.name',                snippet: 'agent.name',                                                              display: 'agent.name',                desc: 'string — display name' },
    { name: 'agent.agentType',           snippet: 'agent.agentType',                                                         display: 'agent.agentType',           desc: 'string — <code>ROOT</code> / <code>SUB</code>' },
    { name: 'agent.agentIdentity',       snippet: 'agent.agentIdentity',                                                     display: 'agent.agentIdentity',       desc: 'string — identity-scope uuid' },
    { name: 'agent.status',              snippet: 'agent.status',                                                            display: 'agent.status',              desc: 'string — <code>ACTIVE</code> / <code>ARCHIVED</code>' },
    { name: 'model.name',                snippet: 'model.name',                                                              display: 'model.name',                desc: 'string — model name (e.g. <code>"claude-opus-4-7"</code>)' },
    { name: 'model.version',             snippet: 'model.version',                                                           display: 'model.version',             desc: 'string' },
    { name: 'model.publisher',           snippet: 'model.publisher',                                                         display: 'model.publisher',           desc: 'string' },
]

interface SnippetDoc { label: string; cel: string }
const snippetDocs: SnippetDoc[] = [
    { label: 'Any AGENTIC_REPORT artifact attached',
      cel: 'session.artifacts.exists(a, a.type == "AGENTIC_REPORT")' },
    { label: 'Orientation report (tag agenticPhase=ORIENTATION)',
      cel: 'session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "ORIENTATION"))' },
    { label: 'Final report (tag agenticPhase=FINAL)',
      cel: 'session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "FINAL"))' },
    { label: 'Model allowlist (INPUT)',
      cel: 'model.name == "claude-opus-4-7" || model.name == "claude-sonnet-4-6"' },
    { label: 'No more than 20 commits per session',
      cel: 'size(session.commits) <= 20' },
    { label: 'Branch is main',
      cel: 'session.branch == "main"' },
]

// Full-form scaffolds. Each one overwrites every field.
const samples = [
    {
        name: 'Orientation report required',
        description: 'Every session must carry an AGENTIC_REPORT tagged agenticPhase=ORIENTATION before commits will be accepted.',
        kind: 'OUTPUT',
        severity: 'BLOCK',
        cel: 'session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "ORIENTATION"))',
    },
    {
        name: 'Final report required',
        description: 'Session must produce a final-phase AGENTIC_REPORT before commits land.',
        kind: 'OUTPUT',
        severity: 'BLOCK',
        cel: 'session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "FINAL"))',
    },
    {
        name: 'Any AGENTIC_REPORT artifact required',
        description: 'Looser variant — any AGENTIC_REPORT artifact (no phase distinction).',
        kind: 'OUTPUT',
        severity: 'BLOCK',
        cel: 'session.artifacts.exists(a, a.type == "AGENTIC_REPORT")',
    },
    {
        name: 'Approved model required',
        description: 'Sessions opened by an agent on an unapproved model are rejected at init.',
        kind: 'INPUT',
        severity: 'BLOCK',
        cel: 'model.name == "claude-opus-4-7" || model.name == "claude-sonnet-4-6"',
    },
    {
        name: 'Session has more than 20 commits (warn)',
        description: 'Soft signal for very long-running sessions; does not block.',
        kind: 'OUTPUT',
        severity: 'WARN',
        cel: 'size(session.commits) <= 20',
    },
]

onMounted(load)

async function load () {
    loading.value = true
    try {
        if (isNew.value) {
            form.value.org = orgFromQuery.value || ''
        } else {
            const p = await store.dispatch('fetchAgentPolicy', policyUuid.value)
            if (!p) {
                notification.error({ content: 'Policy not found.' })
                return
            }
            form.value = {
                uuid: p.uuid,
                org: p.org,
                name: p.name,
                description: p.description || '',
                kind: p.kind,
                severity: p.severity,
                cel: p.cel,
                enabled: !!p.enabled,
            }
        }
    } catch (e: any) {
        notification.error({ content: `Failed to load: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

function insertSnippet (snippet: string) {
    const current = (form.value.cel || '').trim()
    form.value.cel = current ? `${current} && ${snippet}` : snippet
}

function applySample (s: any) {
    form.value.name = s.name
    form.value.description = s.description
    form.value.kind = s.kind
    form.value.severity = s.severity
    form.value.cel = s.cel
}

async function save () {
    if (!canSave.value || !form.value.org) {
        notification.warning({ content: 'Missing required fields.' })
        return
    }
    saving.value = true
    try {
        const input: any = {
            uuid: form.value.uuid || null,
            org: form.value.org,
            name: form.value.name.trim(),
            description: form.value.description?.trim() || null,
            kind: form.value.kind,
            severity: form.value.severity,
            cel: form.value.cel.trim(),
            enabled: form.value.enabled,
        }
        const saved = await store.dispatch('upsertAgentPolicy', input)
        notification.success({ content: `Saved "${saved.name}"` })
        if (isNew.value) {
            router.push({ name: 'AiAgentPoliciesOfOrg', params: { orguuid: form.value.org } })
        } else {
            form.value.uuid = saved.uuid
        }
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}`, duration: 8000 })
    } finally {
        saving.value = false
    }
}

async function remove () {
    if (isNew.value || !form.value.uuid) return
    try {
        await store.dispatch('deleteAgentPolicy', form.value.uuid)
        notification.success({ content: 'Policy deleted' })
        router.push({ name: 'AiAgentPoliciesOfOrg', params: { orguuid: form.value.org } })
    } catch (e: any) {
        notification.error({ content: `Delete failed: ${e?.message ?? e}` })
    }
}

function back () {
    if (form.value.org) {
        router.push({ name: 'AiAgentPoliciesOfOrg', params: { orguuid: form.value.org } })
    } else {
        router.back()
    }
}
</script>

<style scoped>
.agentPolicyView { padding: 16px; max-width: 980px; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.head h4 { margin: 0; }
.opt-help {
    cursor: help;
    color: #888;
    margin-left: 6px;
    vertical-align: middle;
}
.actions { margin-top: 16px; display: flex; gap: 12px; }
.samples { display: flex; gap: 8px; flex-wrap: wrap; }
.ref-table {
    border-collapse: collapse;
    margin-top: 6px;
    font-size: 12px;
    width: 100%;
}
.ref-table td { padding: 3px 8px 3px 0; vertical-align: top; }
.ref-paste { white-space: nowrap; width: 24px; }
.ref-name { white-space: nowrap; }
.snippet { white-space: pre-wrap; word-break: break-all; font-size: 11px; }
.snippet-desc { color: #888; font-size: 11px; margin-top: 1px; }
:deep(code) { font-size: 12px; padding: 1px 4px; background: rgba(127,127,127,0.1); border-radius: 3px; }
</style>
