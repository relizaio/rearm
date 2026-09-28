<template>
    <div>
        <p class="subtle" style="margin-top: 0;">{{ SESSION_LIMIT_HELP }}</p>
        <n-form-item label="Device-login session limit (minutes)" :validation-status="problem ? 'error' : undefined" :feedback="problem || lifetime">
            <n-input-number v-model:value="minutes" clearable :show-button="false" placeholder="No limit: 30 days sliding, at most 90" style="max-width: 320px;" />
        </n-form-item>
        <n-space>
            <n-button type="success" :disabled="!!problem || unchanged || saving" @click="save">Save Limit</n-button>
        </n-space>
    </div>
</template>

<script lang="ts" setup>
/**
 * The key's sessionMaxMinutes (task RD3-7), for a personal or Free Form key: its owner or holder,
 * or an org admin, sets it; the server checks the same bounds and the same gate as the key's notes.
 */
import { NButton, NFormItem, NInputNumber, NSpace } from 'naive-ui'
import { computed, ref, watch } from 'vue'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { describeMinutes, SESSION_LIMIT_HELP, sessionMinutesProblem } from '@/utils/cliSessionLifetime'

const props = defineProps<{
    apiKey: any
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits(['saved'])

const minutes = ref<number | null>(null)
const saving = ref(false)
watch(() => props.apiKey, (k) => { minutes.value = k?.sessionMaxMinutes ?? null }, { immediate: true })
const problem = computed(() => sessionMinutesProblem(minutes.value))
const unchanged = computed(() => (minutes.value ?? null) === (props.apiKey?.sessionMaxMinutes ?? null))
const lifetime = computed(() => minutes.value ? 'Sessions approved on this key end ' + describeMinutes(minutes.value) + ' after approval.' : '')

async function save () {
    saving.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: gql`mutation setApiKeySessionMaxMinutes($apiKeyUuid: ID!, $sessionMaxMinutes: Int) {
                setApiKeySessionMaxMinutes(apiKeyUuid: $apiKeyUuid, sessionMaxMinutes: $sessionMaxMinutes) { uuid sessionMaxMinutes } }`,
            variables: { apiKeyUuid: props.apiKey.uuid, sessionMaxMinutes: minutes.value ?? null }
        })
        props.notify('success', 'Saved', minutes.value ? 'Device-login sessions on this key are now limited' : 'The session limit was lifted')
        emit('saved', resp.data.setApiKeySessionMaxMinutes?.sessionMaxMinutes ?? null)
    } catch (error: any) {
        props.notify('error', 'Error', `Failed to save the session limit: ${commonFunctions.parseGraphQLError(error.message)}`)
    } finally { saving.value = false }
}
</script>
