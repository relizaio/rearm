<template>
    <div class="agentPolicyView">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="openAgentsOfOrg">AI Agents</n-breadcrumb-item>
            <template v-if="fromSession">
                <n-breadcrumb-item @click="openSession">Session {{ sessionShortLabel }}</n-breadcrumb-item>
            </template>
            <template v-else>
                <n-breadcrumb-item @click="openPolicies">Policies</n-breadcrumb-item>
            </template>
            <n-breadcrumb-item>{{ isNew ? 'New policy' : (form.name || 'Edit policy') }}</n-breadcrumb-item>
        </n-breadcrumb>
        <div class="head">
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
                            <div style="margin-bottom: 8px; padding: 6px 8px; background: #fff8e6; border-left: 3px solid #f0a020; font-size: 11px;">
                                <strong>Match-to-block:</strong> the CEL describes the
                                <em>failure</em> condition. True&nbsp;=&nbsp;match&nbsp;→ verdict
                                FAILED/WARNING/PENDING. False&nbsp;=&nbsp;PASSED. Same
                                shape as component-level CEL gates (e.g.
                                <code>release.commits.exists(c, c.signature.state != "VERIFIED")</code>
                                = "block when any commit is not verified").
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
                         placeholder='e.g. !session.artifacts.exists(a, a.type == "AGENTIC_REPORT")  // match-to-block: true = failure'
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
                            <n-tag size="tiny" :type="kindTagType(s.kind)" style="margin-right: 4px;">{{ s.kind }}</n-tag>
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
    NAlert, NBreadcrumb, NBreadcrumbItem, NButton, NCollapse, NCollapseItem,
    NForm, NFormItem, NFormItemGi, NGrid, NIcon, NInput, NPopconfirm, NPopover,
    NRadioButton, NRadioGroup, NSpin, NSwitch, NTag, NTooltip, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular, ClipboardPaste20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const isNew = computed(() => route.params.uuid === 'new')
const policyUuid = computed(() => route.params.uuid as string)
const orgFromQuery = computed(() => route.query.org as string | undefined)
const fromQuery = computed(() => (route.query.from as string | undefined) || 'policies')
const fromSession = computed(() => fromQuery.value === 'session')
const sessionUuidFromQuery = computed(() => route.query.sessionUuid as string | undefined)

const loading = ref<boolean>(true)
const saving = ref<boolean>(false)
const sessionContext = ref<any>(null)
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

const sessionShortLabel = computed(() => {
    const s = sessionContext.value
    if (!s) return sessionUuidFromQuery.value?.slice(0, 8) ?? ''
    const csid = s.clientSessionId
    if (csid) return csid.length > 12 ? csid.slice(0, 12) + '…' : csid
    return s.uuid?.slice(0, 8) ?? ''
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
        help: '<strong>OUTPUT</strong> — postcondition on session work. Checked at init (records AWAITING — soft signal to the agent), re-checked when an artifact attaches, hardens to FAILED when a commit is attributed without satisfying the CEL. Use for "agent must produce X by commit-time" rules (e.g. orientation report).',
    },
    {
        value: 'CLOSE',
        help: '<strong>CLOSE</strong> — postcondition that stays AWAITING through the entire session lifetime and only locks its verdict at session close. Use when the satisfying artifact arrives <em>after</em> the agent\'s last commit — e.g. a FINAL <code>AGENTIC_REPORT</code> tag the agent files as its closing step. OUTPUT would deterministically FAIL such a rule at commit-attribution time.',
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
    { name: 'session.title',             snippet: 'session.title',                                                           display: 'session.title',             desc: 'string — agent-supplied title' },
    { name: 'session.clientSessionId',   snippet: 'session.clientSessionId',                                                 display: 'session.clientSessionId',   desc: 'string — agent-supplied session id' },
    { name: 'session.artifacts',         snippet: 'session.artifacts',                                                       display: 'session.artifacts',         desc: 'list of <code>{ uuid, type, displayIdentifier, bomFormat, tags[] }</code>' },
    { name: 'session.commits',           snippet: 'session.commits',                                                         display: 'session.commits',           desc: 'list of <code>{ uuid, commit, vcsBranch, commitAuthor, commitEmail, agent, agentSession, signature }</code>' },
    { name: 'session.commits[].signature', snippet: 'session.commits[0].signature.state',                                    display: 'session.commits[].signature', desc: 'object — <code>{ state, format, signedByOwnerType, signedByOwnerUuid, keyFingerprint, verifiedAt }</code>. <code>state</code> ∈ { UNSIGNED, VERIFIED, INVALID_SIGNATURE, UNKNOWN_KEY, KEY_REVOKED, WRONG_SIGNER, PENDING, ERRORED }' },
    { name: 'agent.name',                snippet: 'agent.name',                                                              display: 'agent.name',                desc: 'string — display name' },
    { name: 'agent.agentType',           snippet: 'agent.agentType',                                                         display: 'agent.agentType',           desc: 'string — <code>ROOT</code> / <code>SUB</code>' },
    { name: 'agent.agentIdentity',       snippet: 'agent.agentIdentity',                                                     display: 'agent.agentIdentity',       desc: 'string — identity-scope uuid' },
    { name: 'agent.status',              snippet: 'agent.status',                                                            display: 'agent.status',              desc: 'string — <code>ACTIVE</code> / <code>ARCHIVED</code>' },
    { name: 'model.name',                snippet: 'model.name',                                                              display: 'model.name',                desc: 'string — model name (e.g. <code>"claude-opus-4-7"</code>)' },
    { name: 'model.version',             snippet: 'model.version',                                                           display: 'model.version',             desc: 'string' },
    { name: 'model.publisher',           snippet: 'model.publisher',                                                         display: 'model.publisher',           desc: 'string' },
]

// Match-to-block semantics: CEL true = failure matches → verdict is
// FAILED / WARNING / PENDING. CEL false = no match → PASSED. Snippets
// below describe the *block condition*, mirroring component-level CEL
// gates (`release.commits.exists(c, c.signature.state != "VERIFIED")`
// reads as "any unverified commit → reject").
interface SnippetDoc { label: string; cel: string }
const snippetDocs: SnippetDoc[] = [
    { label: 'Block when no AGENTIC_REPORT artifact attached',
      cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT")' },
    { label: 'Block when no orientation report (tag agenticPhase=ORIENTATION)',
      cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "ORIENTATION"))' },
    { label: 'Block when no final report (tag agenticPhase=FINAL)',
      cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "FINAL"))' },
    { label: 'Block when model is not on the allowlist (INPUT)',
      cel: 'model.name != "claude-opus-4-7" && model.name != "claude-sonnet-4-6"' },
    { label: 'Warn when session has more than 20 commits',
      cel: 'size(session.commits) > 20' },
    { label: 'Block when any commit is not verified-signed',
      cel: 'session.commits.exists(c, c.signature.state != "VERIFIED")' },
    { label: 'Block when any commit is not signed by the session agent',
      cel: 'session.commits.exists(c, c.signature.state != "VERIFIED" || c.signature.signedByOwnerType != "AGENT" || c.signature.signedByOwnerUuid != agent.uuid)' },
]

// Full-form scaffolds. Each one overwrites every field.
// Match-to-block semantics: each CEL below describes the *failure*
// condition — CEL true = match = the session/commit violates the policy.
const samples = [
    {
        name: 'Orientation report required',
        description: 'Every session must carry an AGENTIC_REPORT tagged agenticPhase=ORIENTATION before commits will be accepted.',
        kind: 'OUTPUT',
        severity: 'BLOCK',
        cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "ORIENTATION"))',
    },
    {
        name: 'Final report required',
        description: 'Session must produce a final-phase AGENTIC_REPORT before close. CLOSE kind — the FINAL report is by definition filed after the agent\'s last commit, so OUTPUT would FAIL it at commit-attribution.',
        kind: 'CLOSE',
        severity: 'BLOCK',
        cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && a.tags.exists(t, t.key == "agenticPhase" && t.value == "FINAL"))',
    },
    {
        name: 'Any AGENTIC_REPORT artifact required',
        description: 'Looser variant — any AGENTIC_REPORT artifact (no phase distinction). CLOSE kind so a report filed after the last commit still counts.',
        kind: 'CLOSE',
        severity: 'BLOCK',
        cel: '!session.artifacts.exists(a, a.type == "AGENTIC_REPORT")',
    },
    {
        name: 'Approved model required',
        description: 'Sessions opened by an agent on an unapproved model are rejected at init.',
        kind: 'INPUT',
        severity: 'BLOCK',
        cel: 'model.name != "claude-opus-4-7" && model.name != "claude-sonnet-4-6"',
    },
    {
        name: 'Session has more than 20 commits (warn)',
        description: 'Soft signal for very long-running sessions; does not block.',
        kind: 'OUTPUT',
        severity: 'WARN',
        cel: 'size(session.commits) > 20',
    },
    {
        name: 'All commits verified-signed',
        description: 'Every commit attributed to this session must have a VERIFIED signature against an enrolled key.',
        kind: 'OUTPUT',
        severity: 'BLOCK',
        cel: 'session.commits.exists(c, c.signature.state != "VERIFIED")',
    },
    {
        name: 'Commits signed by session agent',
        description: 'Cryptographic agentic attribution — every commit in the session must be VERIFIED and signed by a key enrolled to this session\u2019s agent (not a committer key, not another agent).',
        kind: 'OUTPUT',
        severity: 'BLOCK',
        cel: 'session.commits.exists(c, c.signature.state != "VERIFIED" || c.signature.signedByOwnerType != "AGENT" || c.signature.signedByOwnerUuid != agent.uuid)',
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
        if (fromSession.value && sessionUuidFromQuery.value) {
            sessionContext.value = await store.dispatch('fetchSession', sessionUuidFromQuery.value).catch(() => null)
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

function kindTagType (kind: string): 'info' | 'success' | 'default' {
    if (kind === 'INPUT') return 'info'
    if (kind === 'CLOSE') return 'success'
    return 'default'
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

function openAgentsOfOrg () {
    const orgUuid = form.value.org || orgFromQuery.value
    if (orgUuid) {
        router.push({ name: 'AiAgentsOfOrg', params: { orguuid: orgUuid } })
    } else {
        router.back()
    }
}

function openPolicies () {
    const orgUuid = form.value.org || orgFromQuery.value
    if (orgUuid) {
        router.push({ name: 'AiAgentPoliciesOfOrg', params: { orguuid: orgUuid } })
    } else {
        router.back()
    }
}

function openSession () {
    if (sessionUuidFromQuery.value) {
        router.push({ name: 'AiAgentSessionView', params: { uuid: sessionUuidFromQuery.value } })
    } else {
        router.back()
    }
}
</script>

<style scoped>
.agentPolicyView { padding: 16px; max-width: 980px; }
.crumbs { margin-bottom: 12px; font-size: 13px; }
.crumbs :deep(.n-breadcrumb-item__link) { cursor: pointer; }
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
