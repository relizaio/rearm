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
    webhookUrl: '',
    webhookAuthScheme: 'NONE' as NotificationWebhookAuthScheme,
    webhookAuthToken: '',
    // Comma- or newline-separated recipient list; parsed to an array
    // at save time so the form keeps a friendly single-field shape.
    recipientsRaw: '',
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
        if (form.type === 'WEBHOOK') {
            if (!form.webhookUrl.trim()) return false
            if (form.webhookAuthScheme !== 'NONE' && !form.webhookAuthToken.trim()) return false
        }
        if (form.type === 'EMAIL' && parseRecipients(form.recipientsRaw).length === 0) {
            return false
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
    form.webhookUrl = ''
    form.webhookAuthScheme = 'NONE'
    form.webhookAuthToken = ''
    form.recipientsRaw = ''
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
