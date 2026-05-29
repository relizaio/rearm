<template>
    <n-modal
        :show="show"
        preset="dialog"
        :show-icon="false"
        style="width: 640px;"
        :title="isEdit ? `Edit channel: ${original?.name ?? ''}` : 'New notification channel'"
        @update:show="(v: boolean) => emit('update:show', v)"
    >
        <n-form :model="form" label-placement="left" label-width="auto" require-mark-placement="right-hanging">
            <n-form-item label="Name" path="name" required>
                <n-input v-model:value="form.name" placeholder="e.g. slack-prod-vulns" :disabled="saving"/>
            </n-form-item>

            <n-form-item label="Type" path="type" required>
                <n-select v-model:value="form.type"
                          :options="typeOptions"
                          :disabled="isEdit || saving"
                          placeholder="Pick channel type"/>
            </n-form-item>
            <p v-if="isEdit" class="hint">Channel type can't change after creation — delete and re-create instead.</p>

            <n-form-item label="Status">
                <n-select v-model:value="form.status"
                          :options="statusOptions"
                          :disabled="saving"/>
            </n-form-item>

            <!-- SLACK-specific fields -->
            <template v-if="form.type === 'SLACK'">
                <n-divider title-placement="left">Slack webhook</n-divider>
                <n-form-item :label="isEdit ? 'Webhook URL (leave blank to keep existing)' : 'Webhook URL'"
                             path="slackWebhookUrl"
                             :required="!isEdit">
                    <n-input v-model:value="form.slackWebhookUrl"
                             placeholder="https://hooks.slack.com/services/T.../B.../..."
                             :disabled="saving"/>
                </n-form-item>
                <p class="hint">Must start with <code>https://hooks.slack.com/services/</code> — validated at save and dispatch.</p>
            </template>

            <!-- MS_TEAMS-specific fields -->
            <template v-if="form.type === 'MS_TEAMS'">
                <n-divider title-placement="left">Microsoft Teams webhook</n-divider>
                <n-form-item :label="isEdit ? 'Workflow URL (leave blank to keep existing)' : 'Workflow URL'"
                             path="teamsWebhookUrl"
                             :required="!isEdit">
                    <n-input v-model:value="form.teamsWebhookUrl"
                             placeholder="https://prod-XX.<region>.logic.azure.com:443/workflows/.../triggers/manual/paths/invoke?..."
                             :disabled="saving"/>
                </n-form-item>
                <p class="hint">
                    Power Automate Workflows webhook URL. Create one in Teams via
                    <em>+ → Workflows → Post to a channel when a webhook request is received</em>.
                    Must be HTTPS and end at a <code>logic.azure.com</code> host — validated at save and dispatch.
                    The legacy O365 connector path is deprecated by Microsoft and not supported.
                </p>
            </template>

            <!-- EMAIL-specific fields -->
            <template v-if="form.type === 'EMAIL'">
                <n-divider title-placement="left">Email recipients</n-divider>
                <n-form-item :label="isEdit ? 'Recipients (leave blank to keep existing)' : 'Recipients'"
                             path="recipientsRaw"
                             :required="!isEdit">
                    <n-input v-model:value="form.recipientsRaw"
                             type="textarea"
                             :rows="3"
                             placeholder="oncall@team.com, sre@team.com"
                             :disabled="saving"/>
                </n-form-item>
                <p class="hint">
                    One or more addresses, comma- or newline-separated. SMTP / SendGrid
                    transport uses the system mail config — there's no per-channel SMTP setup.
                </p>
            </template>

            <!-- SENTINEL-specific fields -->
            <template v-if="form.type === 'SENTINEL'">
                <n-divider title-placement="left">Azure Sentinel — service principal</n-divider>
                <p class="hint">
                    Used to acquire an Azure AD bearer token via the client-credentials grant.
                    On edit, leave all three fields blank to keep the existing values.
                </p>
                <n-form-item :label="isEdit ? 'Tenant ID (leave blank to keep existing)' : 'Tenant ID'"
                             :required="!isEdit">
                    <n-input v-model:value="form.sentinelTenantId"
                             placeholder="11111111-2222-3333-4444-555555555555"
                             :disabled="saving"/>
                </n-form-item>
                <n-form-item :label="isEdit ? 'Client ID (leave blank to keep existing)' : 'Client ID'"
                             :required="!isEdit">
                    <n-input v-model:value="form.sentinelClientId"
                             placeholder="App registration UUID"
                             :disabled="saving"/>
                </n-form-item>
                <n-form-item :label="isEdit ? 'Client secret (leave blank to keep existing)' : 'Client secret'"
                             :required="!isEdit">
                    <n-input v-model:value="form.sentinelClientSecret"
                             type="password"
                             show-password-on="click"
                             :placeholder="isEdit ? '••• (existing kept)' : 'Service-principal secret'"
                             :disabled="saving"/>
                </n-form-item>

                <n-divider title-placement="left">Sentinel — DCR routing</n-divider>
                <p class="hint">
                    The Data Collection Endpoint, Data Collection Rule immutable ID, and stream name
                    are required together so the Logs Ingestion API knows where to land the records.
                </p>
                <n-form-item :label="isEdit ? 'DCE URL (leave blank to keep existing)' : 'DCE URL'"
                             :required="!isEdit">
                    <n-input v-model:value="form.sentinelDcrEndpoint"
                             placeholder="https://<name>.<region>.ingest.monitor.azure.com"
                             :disabled="saving"/>
                </n-form-item>
                <n-form-item :label="isEdit ? 'DCR immutable ID (leave blank to keep existing)' : 'DCR immutable ID'"
                             :required="!isEdit">
                    <n-input v-model:value="form.sentinelDcrImmutableId"
                             placeholder="dcr-xxxxxxxxxxxxxxxxxxxxxxxxxx"
                             :disabled="saving"/>
                </n-form-item>
                <n-form-item :label="isEdit ? 'Stream name (leave blank to keep existing)' : 'Stream name'"
                             :required="!isEdit">
                    <n-input v-model:value="form.sentinelStreamName"
                             placeholder="Custom-ReARMNotifications_CL"
                             :disabled="saving"/>
                </n-form-item>
            </template>

            <!-- WEBHOOK-specific fields -->
            <template v-if="form.type === 'WEBHOOK'">
                <n-divider title-placement="left">Generic webhook</n-divider>
                <n-form-item :label="isEdit ? 'URL (leave blank to keep existing)' : 'URL'"
                             path="webhookUrl"
                             :required="!isEdit">
                    <n-input v-model:value="form.webhookUrl"
                             placeholder="https://your-receiver.example.com/path"
                             :disabled="saving"/>
                </n-form-item>
                <p class="hint">Must be HTTPS. Generic POST of a stable JSON envelope; receivers route on <code>eventType</code>.</p>

                <n-form-item label="Auth scheme">
                    <n-select v-model:value="form.webhookAuthScheme"
                              :options="webhookAuthOptions"
                              :disabled="saving"/>
                </n-form-item>

                <n-form-item v-if="form.webhookAuthScheme && form.webhookAuthScheme !== 'NONE'"
                             :label="authTokenLabel"
                             path="webhookAuthToken"
                             :required="!isEdit && form.webhookAuthScheme !== 'NONE'">
                    <n-input v-model:value="form.webhookAuthToken"
                             type="password"
                             show-password-on="click"
                             :placeholder="authTokenPlaceholder"
                             :disabled="saving"/>
                </n-form-item>
                <p v-if="form.webhookAuthScheme === 'HMAC_SHA256'" class="hint">
                    Receiver verifies with <code>HMAC-SHA256(body, secret)</code> against the <code>X-Reliza-Signature: sha256=&lt;hex&gt;</code> header.
                </p>
                <p v-if="form.webhookAuthScheme === 'BEARER'" class="hint">
                    Sent as <code>Authorization: Bearer &lt;token&gt;</code> on every POST.
                </p>
            </template>
        </n-form>

        <template #action>
            <n-space>
                <n-button :disabled="saving" @click="emit('update:show', false)">Cancel</n-button>
                <n-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
                    {{ isEdit ? 'Save changes' : 'Create channel' }}
                </n-button>
            </n-space>
        </template>
    </n-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import {
    NButton, NDivider, NForm, NFormItem, NInput, NModal, NSelect, NSpace,
    useNotification,
} from 'naive-ui'
import {
    NOTIFICATION_CHANNEL_TYPES,
    NOTIFICATION_CHANNEL_STATUSES,
    NOTIFICATION_WEBHOOK_AUTH_SCHEMES,
    type NotificationChannelType,
    type NotificationChannelStatus,
    type NotificationWebhookAuthScheme,
} from '@/utils/notification-constants'

const props = defineProps<{
    show: boolean,
    orgUuid: string,
    original: any | null,  // null = create, populated row = edit
}>()
const emit = defineEmits<{
    (e: 'update:show', value: boolean): void,
    (e: 'saved', channel: any): void,
}>()

const store = useStore()
const notification = useNotification()

const isEdit = computed(() => !!props.original?.uuid)
const saving = ref(false)

const form = reactive({
    name: '',
    type: 'SLACK' as NotificationChannelType,
    status: 'ENABLED' as NotificationChannelStatus,
    slackWebhookUrl: '',
    teamsWebhookUrl: '',
    webhookUrl: '',
    webhookAuthScheme: 'NONE' as NotificationWebhookAuthScheme,
    webhookAuthToken: '',
    // Comma- or newline-separated recipient list; parsed to an array
    // at save time so the form keeps a friendly single-field shape.
    recipientsRaw: '',
    // Sentinel: all six fields are sensitive — secret blob is
    // assembled and encrypted server-side. Blank-on-edit preserves the
    // existing secret (the partial-update path is intentionally
    // unsupported, matching the backend's validateSentinelConfig).
    sentinelTenantId: '',
    sentinelClientId: '',
    sentinelClientSecret: '',
    sentinelDcrEndpoint: '',
    sentinelDcrImmutableId: '',
    sentinelStreamName: '',
})

const typeOptions = NOTIFICATION_CHANNEL_TYPES
const statusOptions = NOTIFICATION_CHANNEL_STATUSES
const webhookAuthOptions = NOTIFICATION_WEBHOOK_AUTH_SCHEMES

const authTokenLabel = computed(() => {
    const base = form.webhookAuthScheme === 'HMAC_SHA256' ? 'Shared secret' : 'Token'
    return isEdit.value ? `${base} (leave blank to keep existing)` : base
})
const authTokenPlaceholder = computed(() => isEdit.value ? '••• (existing kept)' : 'Enter the secret')

const canSave = computed(() => {
    if (!form.name.trim()) return false
    if (!isEdit.value) {
        // Create path: per-type required fields.
        if (form.type === 'SLACK' && !form.slackWebhookUrl.trim()) return false
        if (form.type === 'MS_TEAMS' && !form.teamsWebhookUrl.trim()) return false
        if (form.type === 'WEBHOOK') {
            if (!form.webhookUrl.trim()) return false
            if (form.webhookAuthScheme !== 'NONE' && !form.webhookAuthToken.trim()) return false
        }
        if (form.type === 'EMAIL' && parseRecipients(form.recipientsRaw).length === 0) {
            return false
        }
        if (form.type === 'SENTINEL') {
            // All six fields required on create. Partial updates on
            // edit aren't supported — see backend validateSentinelConfig.
            if (!form.sentinelTenantId.trim()) return false
            if (!form.sentinelClientId.trim()) return false
            if (!form.sentinelClientSecret.trim()) return false
            if (!form.sentinelDcrEndpoint.trim()) return false
            if (!form.sentinelDcrImmutableId.trim()) return false
            if (!form.sentinelStreamName.trim()) return false
        }
    }
    return true
})

/**
 * Split the recipient textarea on commas + newlines, trim, drop blanks.
 * Backend-side validation still runs (Apache Commons EmailValidator);
 * UI side is just for the empty-list canSave gate.
 */
function parseRecipients (raw: string): string[] {
    return raw
        .split(/[,\n]/)
        .map(s => s.trim())
        .filter(s => s.length > 0)
}

// Reset form state every time the dialog opens.
watch(() => props.show, (opening) => {
    if (!opening) return
    if (props.original) {
        form.name = props.original.name ?? ''
        form.type = props.original.type ?? 'SLACK'
        form.status = props.original.status ?? 'ENABLED'
    } else {
        form.name = ''
        form.type = 'SLACK'
        form.status = 'ENABLED'
    }
    // Always start secret fields blank — even on edit we never display
    // the existing value (the read API doesn't return it, two-layer
    // credential hygiene). Blank on save = preserve existing.
    form.slackWebhookUrl = ''
    form.teamsWebhookUrl = ''
    form.webhookUrl = ''
    form.webhookAuthScheme = 'NONE'
    form.webhookAuthToken = ''
    form.recipientsRaw = ''
    form.sentinelTenantId = ''
    form.sentinelClientId = ''
    form.sentinelClientSecret = ''
    form.sentinelDcrEndpoint = ''
    form.sentinelDcrImmutableId = ''
    form.sentinelStreamName = ''
})

async function save () {
    if (!canSave.value || saving.value) return
    saving.value = true
    try {
        const input: any = {
            org: props.orgUuid,
            name: form.name.trim(),
            type: form.type,
            status: form.status,
        }
        if (isEdit.value) input.uuid = props.original.uuid

        if (form.type === 'SLACK') {
            if (form.slackWebhookUrl.trim()) {
                input.slackConfig = { webhookUrl: form.slackWebhookUrl.trim() }
            }
        } else if (form.type === 'MS_TEAMS') {
            if (form.teamsWebhookUrl.trim()) {
                input.teamsConfig = { webhookUrl: form.teamsWebhookUrl.trim() }
            }
        } else if (form.type === 'WEBHOOK') {
            const wc: any = {}
            if (form.webhookUrl.trim()) wc.url = form.webhookUrl.trim()
            wc.authScheme = form.webhookAuthScheme
            if (form.webhookAuthToken.trim()) wc.authToken = form.webhookAuthToken.trim()
            // Only attach webhookConfig when the operator actually
            // wants to change something — empty url AND empty token on
            // update should not touch the encrypted blob.
            if (wc.url || wc.authToken || (!isEdit.value && wc.authScheme === 'NONE')) {
                input.webhookConfig = wc
            }
        } else if (form.type === 'EMAIL') {
            // Blank textarea on update = "preserve existing recipients"
            // (matches backend's null-emailConfig branch in
            // NotificationChannelService.mergeConfigData).
            const recipients = parseRecipients(form.recipientsRaw)
            if (recipients.length > 0) {
                input.emailConfig = { recipients }
            }
        } else if (form.type === 'SENTINEL') {
            // All-six-or-none on the wire — partial updates aren't
            // supported (see backend validateSentinelConfig). If any one
            // field is populated, all should be (canSave gates this on
            // create; on edit, all-blank means "preserve existing").
            const allBlank =
                !form.sentinelTenantId.trim()
                && !form.sentinelClientId.trim()
                && !form.sentinelClientSecret.trim()
                && !form.sentinelDcrEndpoint.trim()
                && !form.sentinelDcrImmutableId.trim()
                && !form.sentinelStreamName.trim()
            if (!allBlank) {
                input.sentinelConfig = {
                    tenantId: form.sentinelTenantId.trim(),
                    clientId: form.sentinelClientId.trim(),
                    clientSecret: form.sentinelClientSecret.trim(),
                    dcrEndpoint: form.sentinelDcrEndpoint.trim(),
                    dcrImmutableId: form.sentinelDcrImmutableId.trim(),
                    streamName: form.sentinelStreamName.trim(),
                }
            }
        }

        const saved = await store.dispatch('upsertNotificationChannel', input)
        notification.success({
            content: `${isEdit.value ? 'Saved' : 'Created'} channel "${saved.name}"`,
        })
        emit('saved', saved)
        emit('update:show', false)
    } catch (e: any) {
        notification.error({
            content: `Save failed: ${e?.message ?? e}`,
            duration: 8000,
        })
    } finally {
        saving.value = false
    }
}
</script>

<style scoped>
.hint {
    font-size: 12px;
    color: #888;
    margin: -10px 0 12px 0;
}
.hint code {
    background: rgba(0,0,0,0.04);
    padding: 1px 4px;
    border-radius: 3px;
}
</style>
