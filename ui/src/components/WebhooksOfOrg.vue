<template>
    <div>
        <h5>Webhooks (inbound)</h5>
        <p style="color: #666; font-size: 13px; margin-top: 0;">
            Receive GitHub <code>pull_request</code> events at a per-org URL so PR state in ReARM stays in sync with
            GitHub without waiting for the next CI run. Each webhook verifies deliveries with its own HMAC secret.
            Linking a webhook to a GitHub App integration is <strong>optional</strong> — it's an organizational
            association only; a standalone webhook delivers exactly the same. (Posting PR comments or checks back to
            GitHub is configured separately on <strong>PR-validation rules</strong>, not here.)
        </p>
        <n-data-table :data="webhooks" :columns="webhookFields" :single-line="false" />
        <n-button @click="onCreateOpen" style="margin-top: 8px;">Add Webhook</n-button>

        <!-- Create modal -->
        <n-modal v-model:show="showCreateModal" preset="dialog" :show-icon="false">
            <n-card style="width: 700px" size="huge" title="Create Webhook" :borderd="false" role="dialog" aria-modal="true">
                <n-form :model="webhookToCreate" :rules="createRules" ref="createFormRef">
                    <n-form-item label="GitHub App integration (optional)" path="integration"
                        description="Optional. Associate this webhook with a GitHub App integration for organization only. Inbound PR events are received and verified using this webhook's own secret either way — the link is not required for delivery. Posting PR comments/checks back to GitHub is configured separately on PR-validation rules.">
                        <n-select v-model:value="webhookToCreate.integration" clearable
                            :options="webhookCapableIntegrationOptions"
                            placeholder="Leave empty for a standalone webhook" />
                    </n-form-item>
                    <n-form-item label="Slug" path="slug"
                        description="Lowercase a-z, 0-9, and hyphens. 4–63 chars, no leading/trailing hyphen. Becomes part of the public webhook URL.">
                        <n-input v-model:value="webhookToCreate.slug" placeholder="e.g. github-app-pr-events" />
                    </n-form-item>
                    <n-form-item label="Secret" path="secret"
                        description="Random HMAC-SHA256 key. Paste the SAME value into the GitHub App's Webhook secret field. Stored encrypted.">
                        <n-space vertical style="width: 100%;">
                            <n-input v-model:value="webhookToCreate.secret" type="password"
                                show-password-on="click" placeholder="Paste or generate a 32-byte hex string" />
                            <n-button size="small" @click="generateSecret">Generate random secret</n-button>
                        </n-space>
                    </n-form-item>
                    <n-form-item label="Installation ID" path="installation"
                        description="GitHub App installation ID this webhook is scoped to. Optional but recommended.">
                        <n-input v-model:value="webhookToCreate.installation" placeholder="Numeric installation id" />
                    </n-form-item>
                    <n-form-item label="Note" path="note">
                        <n-input v-model:value="webhookToCreate.note" placeholder="Free-form note for ops" />
                    </n-form-item>

                    <!-- Live preview of the URL the user pastes into GitHub -->
                    <n-form-item label="Webhook URL preview" v-if="webhookToCreate.slug">
                        <n-input :value="previewUrl(webhookToCreate.slug)" readonly />
                    </n-form-item>

                    <n-space>
                        <n-button @click="createWebhook" type="success">Create</n-button>
                        <n-button @click="resetCreate" type="error">Reset</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- Edit modal -->
        <n-modal v-model:show="showEditModal" preset="dialog" :show-icon="false">
            <n-card style="width: 700px" size="huge" :title="'Edit Webhook - ' + (webhookToEdit.slug || '')" :borderd="false" role="dialog" aria-modal="true">
                <n-form :model="webhookToEdit">
                    <n-form-item label="Slug"
                        description="Lowercase a-z, 0-9, and hyphens. 4–63 chars, no leading/trailing hyphen. Renaming changes the public URL — re-register on the GitHub App side at the same time.">
                        <n-input v-model:value="webhookToEdit.slug" />
                    </n-form-item>
                    <n-form-item label="Webhook URL">
                        <n-input :value="previewUrl(webhookToEdit.slug)" readonly />
                    </n-form-item>
                    <n-form-item label="GitHub App integration (optional)"
                        description="Optional organizational association — clear it for a standalone webhook. Inbound delivery is unaffected either way; posting back to GitHub is configured on PR-validation rules.">
                        <n-select v-model:value="webhookToEdit.integration" clearable
                            :options="editIntegrationOptions"
                            placeholder="Standalone (no integration)" />
                    </n-form-item>
                    <n-form-item label="Status">
                        <n-radio-group v-model:value="webhookToEdit.status">
                            <n-radio-button label="Active" value="ACTIVE" />
                            <n-radio-button label="Disabled" value="DISABLED" />
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item label="Installation ID">
                        <n-input v-model:value="webhookToEdit.installation" placeholder="Numeric installation id" />
                    </n-form-item>
                    <n-form-item label="Note">
                        <n-input v-model:value="webhookToEdit.note" />
                    </n-form-item>
                    <n-form-item label="Rotate secret"
                        description="Leave empty to keep the existing secret. If set, also update the secret on the GitHub App side or signature verification will fail.">
                        <n-input v-model:value="webhookToEdit.secret" type="password" show-password-on="click" placeholder="(unchanged)" />
                    </n-form-item>
                    <n-space>
                        <n-button @click="updateWebhook" type="success">Save</n-button>
                        <n-button @click="showEditModal = false" type="default">Cancel</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import Swal from 'sweetalert2'
import { NButton, NCard, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NRadioButton, NRadioGroup, NSelect, NSpace, NText, NotificationType, useNotification } from 'naive-ui'
import { computed, ComputedRef, h, onMounted, Ref, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { Edit as EditIcon, Trash, Copy } from '@vicons/tabler'

const route = useRoute()
const store = useStore()
const notification = useNotification()
const notify = (type: NotificationType, title: string, content: string) => {
    notification[type]({ content, meta: title, duration: 3500, keepAliveOnHover: true })
}

// ciIntegrations is sourced from the parent (OrgIntegrations) so the
// "Add Webhook" button unlocks the moment the user adds a WEBHOOK-
// capable GitHub integration on the Catalog sub-tab. Previously this
// component fetched once on mount and stayed stale until F5.
const props = defineProps<{
    orguuid?: string
    ciIntegrations?: any[]
}>()

const myorg: ComputedRef<any> = computed(() => store.getters.myorg)
const orgResolved = ref('')

const webhooks: Ref<any[]> = ref([])
const localCiIntegrations: Ref<any[]> = ref([])
// Prefer the prop; only fall back to a self-fetched list if the parent
// didn't pass one (defensive — every current call site passes the prop).
const effectiveCiIntegrations = computed(() =>
    props.ciIntegrations ?? localCiIntegrations.value
)

// Public base URL for the webhook reception path. Origin is the same
// as the UI itself — agent-scully or whatever the user is logged into.
function previewUrl(slug: string): string {
    if (!slug) return ''
    return `${window.location.origin}/api/programmatic/v1/webhook/${orgResolved.value}/${slug}`
}

const webhookCapableIntegrationOptions = computed(() => {
    return effectiveCiIntegrations.value
        .filter((ci: any) => ci.type === 'GITHUB' && (ci.capabilities || []).includes('WEBHOOK'))
        .map((ci: any) => ({ label: `${ci.note || ci.identifier} (${ci.uuid.substring(0, 8)})`, value: ci.uuid }))
})

// Options for the EDIT picker. If the webhook is currently linked to an
// integration that's no longer WEBHOOK-capable (capability removed after the
// link was made), it won't be in webhookCapableIntegrationOptions and the
// picker would render blank — hiding what it's actually linked to. Inject a
// synthetic, disabled entry so the current link stays visible (and labelled).
const editIntegrationOptions = computed(() => {
    const opts = [...webhookCapableIntegrationOptions.value]
    const cur = webhookToEdit.value?.integration
    if (cur && !opts.some((o: any) => o.value === cur)) {
        opts.unshift({ label: `${integrationLabel(cur)} (capability removed)`, value: cur, disabled: true })
    }
    return opts
})

const SLUG_REGEX = /^[a-z0-9]([a-z0-9-]{2,61}[a-z0-9])?$/
const createRules = {
    // integration is intentionally NOT required — a standalone webhook
    // (no association) is a fully supported, intentional configuration.
    slug: {
        required: true, trigger: ['blur', 'input'],
        validator: (_rule: any, value: string) => {
            if (!value) return new Error('Slug required')
            if (!SLUG_REGEX.test(value)) return new Error('Lowercase a-z, 0-9, hyphen; 4–63 chars; no leading/trailing hyphen')
            return true
        }
    },
    secret: {
        required: true, trigger: ['blur'],
        validator: (_rule: any, value: string) => {
            if (!value || value.trim().length < 16) return new Error('Use at least 16 chars (32-byte hex recommended)')
            return true
        }
    }
}

const emptyWebhook = {
    integration: null as string | null,
    slug: '',
    secret: '',
    installation: '',
    note: ''
}
const webhookToCreate: Ref<any> = ref({ ...emptyWebhook })
const webhookToEdit: Ref<any> = ref({ uuid: '', slug: '', status: 'ACTIVE', installation: '', note: '', secret: '', integration: null as string | null })
const showCreateModal = ref(false)
const showEditModal = ref(false)
const createFormRef = ref<any>(null)

function generateSecret() {
    // 32 random bytes → 64-char hex. crypto.getRandomValues is universally
    // available in browsers; no need for the Node-style require.
    const bytes = new Uint8Array(32)
    window.crypto.getRandomValues(bytes)
    webhookToCreate.value.secret = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')
}

function resetCreate() {
    webhookToCreate.value = { ...emptyWebhook }
}

function onCreateOpen() {
    resetCreate()
    showCreateModal.value = true
}

// Resolve an integration uuid to a friendly label for the list. Falls back
// to the short uuid if the integration isn't in the (webhook-capable) list
// the parent supplied — e.g. a pre-existing link to an integration whose
// WEBHOOK capability was later removed.
function integrationLabel(uuid: string | null): string {
    if (!uuid) return ''
    const ci = effectiveCiIntegrations.value.find((c: any) => c.uuid === uuid)
    return ci ? (ci.note || ci.identifier || uuid.substring(0, 8)) : uuid.substring(0, 8)
}

const webhookFields: any[] = [
    { key: 'slug', title: 'Slug' },
    {
        key: 'integration', title: 'Integration',
        // A no-integration webhook is an intentional "standalone" config, not
        // a broken one — label it so explicitly.
        render: (row: any) => row.integration
            ? integrationLabel(row.integration)
            : h('span', { style: { color: '#888', fontStyle: 'italic' } }, 'Standalone')
    },
    {
        key: 'status', title: 'Status',
        render: (row: any) => row.status || 'ACTIVE'
    },
    {
        key: 'lastDeliveryStatus', title: 'Last Delivery',
        render: (row: any) => {
            if (!row.lastDeliveryAt) return '—'
            const t = new Date(row.lastDeliveryAt).toLocaleString()
            return `${row.lastDeliveryStatus || ''} @ ${t}`
        }
    },
    {
        key: 'consecutiveFailureCount', title: 'Failures',
        render: (row: any) => {
            const n = row.consecutiveFailureCount || 0
            return n === 0 ? '0' : h('span', { style: { color: '#c00', fontWeight: 'bold' } }, String(n))
        }
    },
    { key: 'note', title: 'Note' },
    {
        key: 'controls', title: 'Actions',
        render: (row: any) => h('div', { style: { display: 'flex', gap: '6px' } }, [
            h(NIcon, { title: 'Copy webhook URL', class: 'clickable', size: 22, onClick: () => copyUrl(row) }, () => h(Copy)),
            h(NIcon, { title: 'Edit webhook', class: 'clickable', size: 22, onClick: () => onEditOpen(row) }, () => h(EditIcon)),
            h(NIcon, { title: 'Delete webhook', class: 'clickable', size: 22, onClick: () => onDelete(row) }, () => h(Trash)),
        ])
    }
]

async function copyUrl(row: any) {
    try {
        await navigator.clipboard.writeText(previewUrl(row.slug))
        notify('success', 'Copied', 'Webhook URL copied to clipboard')
    } catch (e: any) {
        notify('error', 'Copy failed', e?.message || 'Could not copy to clipboard')
    }
}

function onEditOpen(row: any) {
    webhookToEdit.value = {
        uuid: row.uuid,
        slug: row.slug,
        status: row.status || 'ACTIVE',
        installation: row.installation || '',
        note: row.note || '',
        secret: '',
        integration: row.integration || null
    }
    showEditModal.value = true
}

async function loadWebhooks() {
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query webhooks($orgUuid: ID!) {
                    webhooks(orgUuid: $orgUuid) {
                        uuid org integration slug note status
                        lastDeliveryAt lastDeliveryStatus consecutiveFailureCount
                    }
                }`,
            variables: { orgUuid: orgResolved.value },
            fetchPolicy: 'no-cache'
        })
        webhooks.value = resp.data?.webhooks || []
    } catch (e: any) {
        console.error(e)
        notify('error', 'Load failed', `Could not load webhooks: ${e?.message || e}`)
    }
}

async function loadCiIntegrations() {
    // Skipped when the parent supplies ciIntegrations via prop.
    if (props.ciIntegrations) return
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query ciIntegrations($org: ID!) {
                    ciIntegrations(org: $org) {
                        uuid identifier type note capabilities
                    }
                }`,
            variables: { org: orgResolved.value },
            fetchPolicy: 'no-cache'
        })
        localCiIntegrations.value = resp.data?.ciIntegrations || []
    } catch (e: any) {
        console.error(e)
    }
}

async function createWebhook() {
    try {
        if (createFormRef.value) {
            await createFormRef.value.validate()
        }
        const input = {
            org: orgResolved.value,
            integration: webhookToCreate.value.integration,
            slug: webhookToCreate.value.slug,
            secret: webhookToCreate.value.secret,
            installation: webhookToCreate.value.installation || null,
            note: webhookToCreate.value.note || null
        }
        await graphqlClient.mutate({
            mutation: gql`
                mutation createWebhook($webhook: WebhookInput!) {
                    createWebhook(webhook: $webhook) { uuid slug status }
                }`,
            variables: { webhook: input },
            fetchPolicy: 'no-cache'
        })
        notify('success', 'Webhook created', `Now register the URL on the GitHub App side: ${previewUrl(input.slug)}`)
        showCreateModal.value = false
        resetCreate()
        await loadWebhooks()
    } catch (e: any) {
        // form validate() errors are arrays of errors per field
        const msg = Array.isArray(e) ? e.map((er: any) => er?.[0]?.message || er.message).join('; ') : (e?.message || String(e))
        notify('error', 'Create failed', msg)
    }
}

async function updateWebhook() {
    try {
        // Slug validation client-side — matches the server regex so we don't
        // round-trip a doomed mutation. Server still enforces uniqueness.
        if (webhookToEdit.value.slug && !SLUG_REGEX.test(webhookToEdit.value.slug)) {
            notify('error', 'Invalid slug', 'Lowercase a-z, 0-9, hyphen; 4–63 chars; no leading/trailing hyphen')
            return
        }
        const input: any = {
            uuid: webhookToEdit.value.uuid,
            note: webhookToEdit.value.note || null,
            status: webhookToEdit.value.status,
            installation: webhookToEdit.value.installation || '',
            slug: webhookToEdit.value.slug,
            // Tri-state on the server: '' clears the link (-> standalone), a uuid
            // sets/keeps it. The modal always shows the current value, so sending
            // it back is a no-op unless the user changed or cleared it.
            integration: webhookToEdit.value.integration || ''
        }
        if (webhookToEdit.value.secret && webhookToEdit.value.secret.trim().length > 0) {
            input.secret = webhookToEdit.value.secret.trim()
        }
        await graphqlClient.mutate({
            mutation: gql`
                mutation updateWebhook($webhook: WebhookUpdateInput!) {
                    updateWebhook(webhook: $webhook) { uuid slug status }
                }`,
            variables: { webhook: input },
            fetchPolicy: 'no-cache'
        })
        notify('success', 'Saved', 'Webhook updated')
        showEditModal.value = false
        await loadWebhooks()
    } catch (e: any) {
        notify('error', 'Update failed', e?.message || String(e))
    }
}

async function onDelete(row: any) {
    const result = await Swal.fire({
        title: `Delete webhook ${row.slug}?`,
        text: 'GitHub will start failing deliveries to the URL — remember to remove the webhook on the GitHub App side too.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, delete',
        cancelButtonText: 'Cancel'
    })
    if (!result.value) return
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation deleteWebhook($uuid: ID!) { deleteWebhook(uuid: $uuid) }`,
            variables: { uuid: row.uuid },
            fetchPolicy: 'no-cache'
        })
        notify('success', 'Deleted', `Webhook ${row.slug} removed`)
        await loadWebhooks()
    } catch (e: any) {
        notify('error', 'Delete failed', e?.message || String(e))
    }
}

onMounted(() => {
    if (props.orguuid) {
        orgResolved.value = props.orguuid
    } else if (route.params.orguuid) {
        orgResolved.value = route.params.orguuid.toString()
    } else if (myorg.value?.uuid) {
        orgResolved.value = myorg.value.uuid
    }
    if (orgResolved.value) {
        loadWebhooks()
        loadCiIntegrations()
    }
})

watch(() => props.orguuid, (val) => {
    if (val && val !== orgResolved.value) {
        orgResolved.value = val
        loadWebhooks()
        loadCiIntegrations()
    }
})
</script>

<style scoped lang="scss">
code {
    background: #f0f0f0;
    padding: 1px 4px;
    border-radius: 3px;
    font-size: 12px;
}
</style>
