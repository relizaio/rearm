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
                A CLI asked to sign in at {{ fmt(request.createdDate) }} with code <code>{{ request.userCode }}</code>.
                It will act with the permissions of the key you choose. Only approve a request you started yourself.
                <div style="display: flex; gap: 32px; margin-top: 10px; flex-wrap: wrap;">
                    <div>
                        <div class="subtle" style="font-size: 12px;">Reported by the CLI (the requester controls these)</div>
                        <div><strong>host</strong> {{ request.deviceInfo?.reportedHostname || request.requestedFrom || 'unknown' }}</div>
                        <div><strong>system</strong> {{ request.deviceInfo?.reportedOs || 'unknown' }}</div>
                        <div><strong>time zone</strong> {{ request.deviceInfo?.reportedTimeZone || 'unknown' }}</div>
                        <div><strong>client</strong> {{ request.deviceInfo?.reportedClient || 'unknown' }}</div>
                    </div>
                    <div>
                        <div class="subtle" style="font-size: 12px;">Observed by the server</div>
                        <div><strong>address</strong> {{ request.deviceInfo?.observedIp || 'unknown' }}</div>
                    </div>
                </div>
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
                        <p class="subtle" style="margin: 0 0 8px 0;">Give the key what this session needs. Whatever you set is stored reduced to your own permissions in the organization, and every call is checked against them again; the key can never exceed you. It is deleted when the session ends.</p>
                        <n-alert v-if="boundsError" type="warning" :show-icon="true" style="margin-bottom: 8px;">
                            Your own permissions in this organization could not be read, so this form is not bounded here: {{ boundsError }}.
                            Anything you set is still stored reduced to what you hold - the server applies the same limit.
                        </n-alert>
                        <n-spin :show="loadingBounds">
                            <ScopedPermissions v-if="newKeyOrg"
                                v-model="scoped"
                                :org-uuid="newKeyOrg"
                                :approval-roles="approvalRoles"
                                :perspectives="perspectives"
                                :products="orgProducts"
                                :components="orgComponents"
                                :instances="orgInstances"
                                :clusters="orgClusters"
                                :show-sbom-probing="true"
                                :max-org-type="ownerOrgType"
                                :allowed-functions="ownerFunctions"
                            />
                        </n-spin>
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
import { NAlert, NButton, NFormItem, NInput, NRadio, NRadioGroup, NSelect, NSpace, NSpin, useNotification, NotificationType } from 'naive-ui'
import { computed, onMounted, ref, watch } from 'vue'
import ScopedPermissions from './ScopedPermissions.vue'
import constants from '../utils/constants'
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

// ---- the permission form on the new-key branch, bounded by what the approver holds in the chosen org ----
const scoped = ref<{ orgPermission: any, scopedPermissions: any[] }>({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })
const loadingBounds = ref(false)
const perspectives = ref<any[]>([])
const ownerPerms = ref<any[]>([])
// null while unknown (the query failed): the form goes unbounded rather than silently offering nothing,
// since the server clamps whatever is sent to the approver's own permissions anyway
const boundsError = ref<string>('')
const PERM_ORDER = ['NONE', 'ESSENTIAL_READ', 'READ_ONLY', 'READ_WRITE', 'ADMIN']
const me = computed(() => store.getters.myuser)
const ownerOrgPerm = computed(() => ownerPerms.value.find((x: any) => x.scope === 'ORGANIZATION' && x.object === newKeyOrg.value))
const ownerOrgType = computed<string | undefined>(() => {
    if (me.value?.isGlobalAdmin) return 'ADMIN'
    if (boundsError.value) return undefined
    return ownerOrgPerm.value?.type || 'NONE'
})
const ownerFunctions = computed<string[] | undefined>(() => {
    if (me.value?.isGlobalAdmin || ownerOrgType.value === 'ADMIN' || boundsError.value) return undefined
    const set = new Set<string>()
    for (const p of ownerPerms.value) for (const f of (p.functions || [])) set.add(f)
    return Array.from(set)
})
const approvalRoles = computed(() => store.getters.orgById(newKeyOrg.value)?.approvalRoles || [])
const orgComponents = computed(() => store.getters.componentsOfOrg(newKeyOrg.value) || [])
const orgProducts = computed(() => store.getters.productsOfOrg(newKeyOrg.value) || [])
const orgInstancesAndClusters = computed(() => {
    if (me.value?.installationType === 'OSS') return []
    return (store.getters.instancesOfOrg(newKeyOrg.value) || []).filter((x: any) => x.revision === -1 && (x.status === 'ACTIVE' || !x.status))
})
const orgInstances = computed(() => orgInstancesAndClusters.value
    .filter((x: any) => x.instanceType === constants.InstanceType.STANDALONE_INSTANCE || x.instanceType === constants.InstanceType.CLUSTER_INSTANCE))
const orgClusters = computed(() => orgInstancesAndClusters.value.filter((x: any) => x.instanceType === constants.InstanceType.CLUSTER))
async function loadBounds () {
    if (!newKeyOrg.value) return
    loadingBounds.value = true
    boundsError.value = ''
    ownerPerms.value = []
    scoped.value = { orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] }
    try {
        // every list is best effort: the queries already return only what this user may read
        const loads: Promise<any>[] = [
            store.dispatch('fetchComponents', newKeyOrg.value), store.dispatch('fetchProducts', newKeyOrg.value),
            graphqlClient.query({ query: gql`query perspectives($org: ID!) { perspectives(org: $org) { uuid name org type } }`, variables: { org: newKeyOrg.value }, fetchPolicy: 'no-cache' })
                .then((r: any) => { perspectives.value = r.data.perspectives || [] }),
            graphqlClient.query({ query: gql`query combinedUserOrgPermissions($orgUuid: ID!, $userUuid: ID!) { combinedUserOrgPermissions(orgUuid: $orgUuid, userUuid: $userUuid) { permissions { org scope object type functions } } }`,
                variables: { orgUuid: newKeyOrg.value, userUuid: me.value?.uuid }, fetchPolicy: 'no-cache' })
                .then((r: any) => { ownerPerms.value = (r.data.combinedUserOrgPermissions?.permissions || []).filter((x: any) => x.org === newKeyOrg.value) })
                .catch((e: any) => { boundsError.value = e?.message || 'the request was refused'; throw e })
        ]
        if (me.value?.installationType !== 'OSS') loads.push(store.dispatch('fetchInstances', newKeyOrg.value))
        const results = await Promise.allSettled(loads)
        for (const r of results) if (r.status === 'rejected') console.warn('cli login: an org object list is unavailable', r.reason?.message || r.reason)
    } finally { loadingBounds.value = false }
}
watch(newKeyOrg, () => loadBounds())
function permissionsPayload () {
    const permissions: any[] = []
    const orgPermType = scoped.value.orgPermission.type
    if (orgPermType && orgPermType !== 'NONE') {
        permissions.push({ org: newKeyOrg.value, scope: 'ORGANIZATION', type: orgPermType, object: newKeyOrg.value, functions: scoped.value.orgPermission.functions || [], approvals: scoped.value.orgPermission.approvals || [] })
    }
    for (const sp of (scoped.value.scopedPermissions || [])) {
        if (sp.type && sp.type !== 'NONE') permissions.push({ org: newKeyOrg.value, scope: sp.scope, type: sp.type, object: sp.objectId, functions: sp.functions || [], approvals: sp.approvals || [] })
    }
    return { orgPermType: orgPermType || 'NONE', permissions }
}
const fmt = (d: any) => d ? new Date(d).toLocaleString('en-CA') : ''

onMounted(async () => {
    await store.dispatch('fetchMyOrganizations')
    let stored: string | null = null
    try { stored = window.localStorage.getItem('relizaOrgUuid') } catch { stored = null }
    newKeyOrg.value = orgOptions.value.find((o: any) => o.value === stored)?.value || orgOptions.value[0]?.value || null
    if (newKeyOrg.value) loadBounds()
    // the parameter is user_code: a URL carrying code= collides with the OIDC redirect through Keycloak
    const code = (route.query.user_code as string) || ''
    if (code) { codeInput.value = code; await lookup() }
})

async function lookup () {
    lookupError.value = ''
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query cliLoginRequest($userCode: String!) { cliLoginRequest(userCode: $userCode) { uuid status userCode requestedFrom createdDate expiresDate
                deviceInfo { reportedHostname reportedOs reportedTimeZone reportedClient observedIp } } }`,
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
        else {
            vars.createKeyOrgUuid = newKeyOrg.value; vars.createKeyNotes = newKeyNotes.value || defaultNotes.value
            const { orgPermType, permissions } = permissionsPayload()
            vars.permissionType = orgPermType; vars.permissions = permissions
        }
        await graphqlClient.mutate({
            mutation: gql`mutation approveCliLogin($userCode: String!, $apiKeyUuid: ID, $createKeyOrgUuid: ID, $createKeyNotes: String, $permissionType: PermissionType, $permissions: [PermissionInput]) {
                approveCliLogin(userCode: $userCode, apiKeyUuid: $apiKeyUuid, createKeyOrgUuid: $createKeyOrgUuid, createKeyNotes: $createKeyNotes, permissionType: $permissionType, permissions: $permissions) { uuid status } }`,
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
