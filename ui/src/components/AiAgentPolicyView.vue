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

            <n-grid :cols="2" x-gap="20">
                <n-form-item-gi label="Kind" required>
                    <n-radio-group v-model:value="form.kind">
                        <n-radio-button value="INPUT">INPUT — gate on init / artifact attach</n-radio-button>
                        <n-radio-button value="OUTPUT">OUTPUT — harden on commit attribution</n-radio-button>
                    </n-radio-group>
                </n-form-item-gi>
                <n-form-item-gi label="Severity" required>
                    <n-radio-group v-model:value="form.severity">
                        <n-radio-button value="BLOCK">BLOCK — fail hardens to FAILED</n-radio-button>
                        <n-radio-button value="WARN">WARN — fail records WARNING only</n-radio-button>
                    </n-radio-group>
                </n-form-item-gi>
            </n-grid>

            <n-form-item label="CEL expression" required>
                <n-input v-model:value="form.cel" type="textarea" :rows="3"
                         placeholder='e.g. session.artifacts.exists(a, a.type == "AGENTIC_REPORT")'
                         style="font-family: monospace;"/>
            </n-form-item>

            <n-collapse>
                <n-collapse-item title="Activation reference + sample policies" name="ref">
                    <p>
                        The CEL expression evaluates against the agent session context. Top-level
                        variables: <code>session</code>, <code>agent</code>, <code>model</code>.
                    </p>
                    <h5>session</h5>
                    <ul>
                        <li><code>session.uuid</code>, <code>session.clientSessionId</code>, <code>session.agent</code>, <code>session.org</code></li>
                        <li><code>session.status</code> — <code>OPEN</code> / <code>CLOSED</code></li>
                        <li><code>session.branch</code>, <code>session.title</code></li>
                        <li><code>session.artifacts</code> — list of <code>{ uuid, type, displayIdentifier, bomFormat }</code></li>
                        <li><code>session.commits</code> — list of SCE uuid strings</li>
                    </ul>
                    <h5>agent</h5>
                    <ul>
                        <li><code>agent.uuid</code>, <code>agent.name</code>, <code>agent.agentIdentity</code></li>
                        <li><code>agent.agentType</code> — <code>ROOT</code> / <code>SUB</code></li>
                        <li><code>agent.status</code> — <code>ACTIVE</code> / <code>ARCHIVED</code></li>
                    </ul>
                    <h5>model</h5>
                    <ul>
                        <li><code>model.name</code>, <code>model.version</code>, <code>model.publisher</code></li>
                    </ul>
                    <h5>Samples</h5>
                    <div class="samples">
                        <n-button v-for="s in samples" :key="s.name" size="small" @click="applySample(s)">
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
    NButton, NCollapse, NCollapseItem, NForm, NFormItem, NFormItemGi, NGrid, NInput,
    NPopconfirm, NRadioButton, NRadioGroup, NSpin, NSwitch, useNotification,
} from 'naive-ui'

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

const samples = [
    {
        name: 'Orientation report required',
        description: 'Every session must carry an AGENTIC_REPORT artifact before commits will be accepted.',
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
        name: 'Session has more than 20 commits',
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
        // After create, navigate to the list — after update, stay on the edit page.
        if (isNew.value) {
            router.push({ name: 'AiAgentPoliciesOfOrg', params: { orguuid: form.value.org } })
        } else {
            form.value.uuid = saved.uuid
        }
    } catch (e: any) {
        // Surface server-side CEL validation errors directly.
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
.actions { margin-top: 16px; display: flex; gap: 12px; }
.samples { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
:deep(code) { font-size: 12px; padding: 1px 4px; background: rgba(127,127,127,0.1); border-radius: 3px; }
:deep(h5) { margin: 12px 0 4px 0; font-size: 13px; }
:deep(ul) { font-size: 13px; margin: 0; padding-left: 18px; }
</style>
