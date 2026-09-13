<template>
    <div class="home">
        <h2>Sign in the ReARM CLI</h2>
        <div v-if="phase === 'lookup'">
            <n-form-item label="Code shown by the CLI">
                <n-input v-model:value="codeInput" placeholder="WDJB-MJHT" style="max-width: 240px;" @keyup.enter="lookup" />
                <n-button type="primary" style="margin-left: 8px;" @click="lookup">Continue</n-button>
            </n-form-item>
            <p v-if="lookupError" class="text-muted">{{ lookupError }}</p>
        </div>

        <div v-else-if="phase === 'choose'">
            <n-alert type="info" style="margin-bottom: 16px;">
                A CLI <strong v-if="request.requestedFrom">on {{ request.requestedFrom }}</strong> asked to sign in at {{ fmt(request.createdDate) }}
                with code <code>{{ request.userCode }}</code>. It will act with the permissions of the key you choose. Only approve a request you started yourself.
            </n-alert>
            <n-radio-group v-model:value="choice" style="display: block; margin-bottom: 12px;">
                <n-space vertical>
                    <n-radio value="new">
                        Create a personal key for this session
                    </n-radio>
                    <div v-if="choice === 'new'" style="margin-left: 26px; margin-bottom: 8px;">
                        <n-space align="end">
                            <n-form-item label="Organization"><n-select v-model:value="newKeyOrg" :options="orgOptions" style="min-width: 260px;" /></n-form-item>
                            <n-form-item label="Notes"><n-input v-model:value="newKeyNotes" :placeholder="defaultNotes" style="min-width: 260px;" /></n-form-item>
                        </n-space>
                        <p class="subtle" style="margin: 0;">The key starts with no permissions; set them on your profile afterwards. It is deleted when the session ends.</p>
                    </div>
                    <n-radio value="existing" :disabled="!eligibleKeys.length">
                        Use one of my keys <span class="subtle" v-if="!eligibleKeys.length">(no active personal or held Free Form keys)</span>
                    </n-radio>
                    <div v-if="choice === 'existing'" style="margin-left: 26px;">
                        <n-radio-group v-model:value="chosenKey">
                            <n-space vertical>
                                <n-radio v-for="k in eligibleKeys" :key="k.uuid" :value="k.uuid">
                                    <strong>{{ k.type === 'USER' ? 'Personal' : 'Free Form (held)' }}</strong> · {{ orgName(k.org) }}
                                    <span class="subtle"> · {{ (k.permissions?.permissions || []).length }} permission{{ (k.permissions?.permissions || []).length === 1 ? '' : 's' }}</span>
                                    <span class="subtle" v-if="k.notes"> · {{ k.notes }}</span>
                                </n-radio>
                            </n-space>
                        </n-radio-group>
                    </div>
                </n-space>
            </n-radio-group>
            <n-space>
                <n-button type="primary" :disabled="!canApprove" @click="approve">Approve</n-button>
                <n-button type="error" ghost @click="deny">Deny</n-button>
            </n-space>
        </div>

        <div v-else-if="phase === 'done'">
            <n-alert type="success">The CLI is signed in. You can close this window.</n-alert>
        </div>
        <div v-else-if="phase === 'denied'">
            <n-alert type="warning">Request denied. The CLI will report it. You can close this window.</n-alert>
        </div>
    </div>
</template>

<script lang="ts" setup>
/**
 * Browser side of `rearm login` (device authorization). The user is already signed in to ReARM
 * (the SPA requires it); here they approve the CLI's request by choosing what the session acts as.
 */
import { NAlert, NButton, NFormItem, NInput, NRadio, NRadioGroup, NSelect, NSpace, useNotification, NotificationType } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'

const route = useRoute()
const store = useStore()
const notification = useNotification()
const notify = (type: NotificationType, title: string, content: string) => notification[type]({ content, meta: title, duration: 5000, keepAliveOnHover: true })

const phase = ref<'lookup' | 'choose' | 'done' | 'denied'>('lookup')
const codeInput = ref('')
const lookupError = ref('')
const request = ref<any>({})
const myKeys = ref<any[]>([])
const choice = ref<'new' | 'existing'>('new')
const chosenKey = ref<string | null>(null)
const newKeyOrg = ref<string | null>(null)
const newKeyNotes = ref('')

const organizations = computed(() => store.getters.allOrganizations || [])
const orgOptions = computed(() => organizations.value.map((o: any) => ({ label: o.name, value: o.uuid })))
const orgName = (uuid: string) => organizations.value.find((o: any) => o.uuid === uuid)?.name || uuid
const eligibleKeys = computed(() => myKeys.value.filter((k: any) => k.status === 'ACTIVE' && (k.type === 'USER' || k.type === 'FREEFORM')))
const defaultNotes = computed(() => 'CLI session' + (request.value.requestedFrom ? ' on ' + request.value.requestedFrom : ''))
const canApprove = computed(() => choice.value === 'new' ? !!newKeyOrg.value : !!chosenKey.value)
const fmt = (d: any) => d ? new Date(d).toLocaleString('en-CA') : ''

onMounted(async () => {
    await store.dispatch('fetchMyOrganizations')
    let stored: string | null = null
    try { stored = window.localStorage.getItem('relizaOrgUuid') } catch { stored = null }
    newKeyOrg.value = orgOptions.value.find((o: any) => o.value === stored)?.value || orgOptions.value[0]?.value || null
    // the parameter is user_code: a URL carrying code= collides with the OIDC redirect through Keycloak
    const code = (route.query.user_code as string) || ''
    if (code) { codeInput.value = code; await lookup() }
})

async function lookup () {
    lookupError.value = ''
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query cliLoginRequest($userCode: String!) { cliLoginRequest(userCode: $userCode) { uuid status userCode requestedFrom createdDate expiresDate } }`,
            variables: { userCode: codeInput.value }, fetchPolicy: 'no-cache'
        })
        if (!resp.data.cliLoginRequest) { lookupError.value = 'No pending sign-in for this code. It may have expired: run rearm login again.'; return }
        request.value = resp.data.cliLoginRequest
        const keys: any = await graphqlClient.query({ query: gql`query myApiKeys { myApiKeys { uuid org type status notes permissions { permissions { scope type } } } }`, fetchPolicy: 'no-cache' })
        myKeys.value = keys.data.myApiKeys || []
        phase.value = 'choose'
    } catch (e: any) { lookupError.value = commonFunctions.parseGraphQLError(e.message) }
}

async function approve () {
    try {
        const vars: any = { userCode: request.value.userCode }
        if (choice.value === 'existing') vars.apiKeyUuid = chosenKey.value
        else { vars.createKeyOrgUuid = newKeyOrg.value; vars.createKeyNotes = newKeyNotes.value || defaultNotes.value }
        await graphqlClient.mutate({
            mutation: gql`mutation approveCliLogin($userCode: String!, $apiKeyUuid: ID, $createKeyOrgUuid: ID, $createKeyNotes: String) {
                approveCliLogin(userCode: $userCode, apiKeyUuid: $apiKeyUuid, createKeyOrgUuid: $createKeyOrgUuid, createKeyNotes: $createKeyNotes) { uuid status } }`,
            variables: vars, fetchPolicy: 'no-cache'
        })
        phase.value = 'done'
    } catch (e: any) { notify('error', 'Could not approve', commonFunctions.parseGraphQLError(e.message)) }
}

async function deny () {
    try {
        await graphqlClient.mutate({ mutation: gql`mutation denyCliLogin($userCode: String!) { denyCliLogin(userCode: $userCode) }`, variables: { userCode: request.value.userCode }, fetchPolicy: 'no-cache' })
        phase.value = 'denied'
    } catch (e: any) { notify('error', 'Could not deny', commonFunctions.parseGraphQLError(e.message)) }
}
</script>
