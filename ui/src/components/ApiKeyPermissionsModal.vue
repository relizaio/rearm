<template>
    <n-modal
        preset="dialog"
        :show-icon="false"
        style="width: 90%;"
        :show="show"
        @update:show="(v: boolean) => { if (!v) emit('update:show', false) }"
    >
        <template #header>{{ isRequest ? 'Request a free-form key' : 'Edit key ' + apiKey?.uuid }}</template>
        <div style="height: 700px; overflow-y: auto; padding-right: 8px;">
            <div v-if="isRequest">
                <n-form-item label="Purpose / notes (admins see this)">
                    <n-input v-model:value="notes" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="e.g. CI pipeline for repo X needs release write on component Y" />
                </n-form-item>
                <n-spin :show="loading">
                    <ScopedPermissions
                        v-model="scoped"
                        :org-uuid="orgUuid"
                        :approval-roles="approvalRoles"
                        :perspectives="perspectives"
                        :products="orgProducts"
                        :components="orgComponents"
                        :instances="orgInstances"
                        :clusters="orgClusters"
                        :show-sbom-probing="true"
                    />
                </n-spin>
                <n-space style="margin-top: 20px;">
                    <n-button type="primary" :disabled="loading || !notes.trim()" @click="sendRequest">Send request</n-button>
                    <n-button @click="emit('update:show', false)">Cancel</n-button>
                </n-space>
            </div>
            <template v-else>
            <p v-if="apiKey?.type === 'USER'" class="subtle" style="margin-top: 0;">
                These permissions are a ceiling. Every call is also checked against the owner's own permissions at that moment, and the lower of the two wins.
                Nothing is allowed until at least one permission is set.
            </p>
            <n-alert v-if="apiKey?.type === 'USER' && ownerOrgType" :type="ceilingExceedsOwner ? 'warning' : 'info'" style="margin-bottom: 12px;">
                Owner's own organization-wide permission right now: <strong>{{ ownerOrgType }}</strong>
                <span v-if="ownerOrgType !== 'ADMIN'"> (plus {{ ownerScopedCount }} object-level grant{{ ownerScopedCount === 1 ? '' : 's' }})</span>.
                <span v-if="ceilingExceedsOwner"> The organization-wide level chosen below is higher than that; it will have no effect until the owner is granted it.</span>
            </n-alert>
            <n-tabs v-model:value="editTab" type="segment" animated>
                <n-tab-pane name="permissions" tab="Permissions">
                    <n-spin :show="loading">
                        <ScopedPermissions
                            v-model="scoped"
                            :org-uuid="orgUuid"
                            :approval-roles="approvalRoles"
                            :perspectives="perspectives"
                            :products="orgProducts"
                            :components="orgComponents"
                            :instances="orgInstances"
                            :clusters="orgClusters"
                            :show-sbom-probing="true"
                        />
                    </n-spin>
                    <n-space style="margin-top: 20px;">
                        <n-button type="success" :disabled="loading" @click="savePermissions">Save Permissions</n-button>
                        <n-button @click="emit('update:show', false)">Cancel</n-button>
                    </n-space>
                </n-tab-pane>
                <n-tab-pane name="notes" tab="Notes">
                    <p style="color: #555; margin-top: 0;">Free-text notes for this key: what it is used for, where it lives, expiry.</p>
                    <n-input v-model:value="notes" type="textarea" :autosize="{ minRows: 4, maxRows: 16 }" placeholder="What this key is used for, who owns it, expiry, etc." />
                    <n-space style="margin-top: 20px;">
                        <n-button type="success" @click="saveNotes">Save Notes</n-button>
                        <n-button @click="emit('update:show', false)">Cancel</n-button>
                    </n-space>
                </n-tab-pane>
            </n-tabs>
            </template>
        </div>
    </n-modal>
</template>

<script lang="ts" setup>
/**
 * Permissions + notes editor for an RBAC key (FREEFORM or USER). Loads the org's perspectives,
 * components, products and instances itself, so it can be used from org settings and from the
 * user's own keys on the profile page. Saves through setPermissionsOnFreeformApiKey / setNotesOnApiKey.
 */
import { NModal, NTabs, NTabPane, NSpace, NButton, NInput, NSpin, NAlert, NFormItem } from 'naive-ui'
import { ref, computed, watch } from 'vue'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import constants from '../utils/constants'
import commonFunctions from '@/utils/commonFunctions'
import ScopedPermissions from './ScopedPermissions.vue'

const props = defineProps<{
    show: boolean
    /** the key being edited; null in request mode */
    apiKey: any
    orgUuid: string
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
    /** 'edit' (default) edits an existing key; 'request' asks admins for a new free-form key with purpose and proposed permissions on one screen */
    mode?: 'edit' | 'request'
}>()
const isRequest = computed(() => props.mode === 'request')
const emit = defineEmits(['update:show', 'saved'])
const store = useStore()

const editTab = ref('permissions')
const loading = ref(false)
const notes = ref('')
const perspectives = ref<any[]>([])
const scoped = ref<{ orgPermission: any, scopedPermissions: any[] }>({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] })

const approvalRoles = computed(() => store.getters.orgById(props.orgUuid)?.approvalRoles || [])
// USER keys: the owner's effective permissions in this org (groups included), so the ceiling can be read against them
const ownerPerms = ref<any[]>([])
const PERM_ORDER = ['NONE', 'ESSENTIAL_READ', 'READ_ONLY', 'READ_WRITE', 'ADMIN']
const ownerOrgType = computed(() => { const p = ownerPerms.value.find((x: any) => x.scope === 'ORGANIZATION' && x.object === props.orgUuid); return p ? p.type : (ownerPerms.value.length ? 'NONE' : '') })
const ownerScopedCount = computed(() => ownerPerms.value.filter((x: any) => x.scope !== 'ORGANIZATION').length)
const ceilingExceedsOwner = computed(() => {
    const t = scoped.value.orgPermission?.type || 'NONE'
    return !!ownerOrgType.value && PERM_ORDER.indexOf(t) > PERM_ORDER.indexOf(ownerOrgType.value)
})
async function loadOwnerPermissions () {
    ownerPerms.value = []
    if (props.apiKey?.type !== 'USER' || !props.apiKey?.object) return
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query combinedUserOrgPermissions($orgUuid: ID!, $userUuid: ID!) { combinedUserOrgPermissions(orgUuid: $orgUuid, userUuid: $userUuid) { permissions { org scope object type functions } } }`,
            variables: { orgUuid: props.orgUuid, userUuid: props.apiKey.object }, fetchPolicy: 'no-cache'
        })
        ownerPerms.value = (resp.data.combinedUserOrgPermissions?.permissions || []).filter((x: any) => x.org === props.orgUuid)
    } catch (e: any) { console.error('owner permissions unavailable', e) }
}
const orgComponents = computed(() => store.getters.componentsOfOrg(props.orgUuid) || [])
const orgProducts = computed(() => store.getters.productsOfOrg(props.orgUuid) || [])
const allComponents = computed(() => [...orgComponents.value, ...orgProducts.value])
const orgInstancesAndClusters = computed(() => {
    if (store.getters.myuser?.installationType === 'OSS') return []
    const all = (store.getters.instancesOfOrg(props.orgUuid) || [])
    return all.filter((x: any) => x.revision === -1 && (x.status === 'ACTIVE' || !x.status))
})
const orgInstances = computed(() => orgInstancesAndClusters.value
    .filter((x: any) => x.instanceType === constants.InstanceType.STANDALONE_INSTANCE || x.instanceType === constants.InstanceType.CLUSTER_INSTANCE))
const orgClusters = computed(() => orgInstancesAndClusters.value.filter((x: any) => x.instanceType === constants.InstanceType.CLUSTER))

async function loadPerspectives () {
    try {
        const response = await graphqlClient.query({
            query: gql`query perspectives($org: ID!) { perspectives(org: $org) { uuid name org createdDate type sidPurlOverride sidAuthoritySegments } }`,
            variables: { org: props.orgUuid }, fetchPolicy: 'no-cache'
        })
        perspectives.value = response.data.perspectives || []
    } catch (error: any) { console.error('Error loading perspectives:', error) }
}

async function resolveScopedObjectName (scope: string, objectId: string): Promise<string> {
    if (scope === 'PERSPECTIVE') {
        const p = perspectives.value.find((x: any) => x.uuid === objectId); return p ? p.name : objectId
    }
    if (scope === 'INSTANCE') {
        const obj = orgInstancesAndClusters.value.find((x: any) => x.uuid === objectId)
        if (!obj) return objectId
        if (obj.instanceType === constants.InstanceType.CLUSTER_INSTANCE) {
            const parent = orgClusters.value.find((c: any) => Array.isArray(c.instances) && c.instances.includes(obj.uuid))
            return `${parent ? parent.name : '(cluster)'} / ${obj.namespace || obj.uuid}`
        }
        if (obj.instanceType === constants.InstanceType.CLUSTER) return obj.name || obj.uri || obj.uuid
        return obj.uri || obj.name || obj.uuid
    }
    const known = allComponents.value.find((c: any) => c.uuid === objectId)
    if (known) return known.name
    try { const fetched = await store.dispatch('fetchComponent', objectId); return fetched?.name || objectId } catch { return objectId }
}

function permissionsPayload (): { orgPermType: string, permissions: any[] } {
    const permissions: any[] = []
    const orgPermType = scoped.value.orgPermission.type
    if (orgPermType && orgPermType !== 'NONE') {
        permissions.push({ org: props.orgUuid, scope: 'ORGANIZATION', type: orgPermType, object: props.orgUuid, functions: scoped.value.orgPermission.functions || [], approvals: scoped.value.orgPermission.approvals || [] })
    }
    for (const sp of (scoped.value.scopedPermissions || [])) {
        if (sp.type && sp.type !== 'NONE') permissions.push({ org: props.orgUuid, scope: sp.scope, type: sp.type, object: sp.objectId, functions: sp.functions || [], approvals: sp.approvals || [] })
    }
    return { orgPermType: orgPermType || 'NONE', permissions }
}

async function sendRequest () {
    const { orgPermType, permissions } = permissionsPayload()
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation requestFreeformApiKey($orgUuid: ID!, $notes: String, $permissionType: PermissionType, $permissions: [PermissionInput]) {
                requestFreeformApiKey(orgUuid: $orgUuid, notes: $notes, permissionType: $permissionType, permissions: $permissions) { uuid status } }`,
            variables: { orgUuid: props.orgUuid, notes: notes.value, permissionType: orgPermType, permissions }, fetchPolicy: 'no-cache'
        })
        props.notify('success', 'Request sent', 'Admins of the organization will review it; you can edit the proposed permissions until it is decided')
        emit('saved'); emit('update:show', false)
    } catch (error: any) {
        props.notify('error', 'Error', `Failed to send the request: ${commonFunctions.parseGraphQLError(error.message)}`)
    }
}

async function load () {
    if (!props.orgUuid) return
    if (isRequest.value) {
        loading.value = true
        try {
            const loads: Promise<any>[] = [loadPerspectives(), store.dispatch('fetchComponents', props.orgUuid), store.dispatch('fetchProducts', props.orgUuid)]
            if (store.getters.myuser?.installationType !== 'OSS') loads.push(store.dispatch('fetchInstances', props.orgUuid))
            await Promise.all(loads)
            scoped.value = { orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] }
            notes.value = ''
        } finally { loading.value = false }
        return
    }
    if (!props.apiKey) return
    loading.value = true
    try {
        const loads: Promise<any>[] = [loadPerspectives(), loadOwnerPermissions(), store.dispatch('fetchComponents', props.orgUuid), store.dispatch('fetchProducts', props.orgUuid)]
        if (store.getters.myuser?.installationType !== 'OSS') loads.push(store.dispatch('fetchInstances', props.orgUuid))
        await Promise.all(loads)
        const scopedPerms: any[] = []
        let orgPerm = { type: 'NONE', functions: [] as string[], approvals: [] as string[] }
        for (const up of (props.apiKey.permissions?.permissions || [])) {
            if (up.scope === 'ORGANIZATION' && up.org === props.orgUuid) {
                orgPerm = { type: up.type, functions: up.functions || [], approvals: up.approvals || [] }
            } else if ((up.scope === 'PERSPECTIVE' || up.scope === 'COMPONENT' || up.scope === 'INSTANCE') && up.org === props.orgUuid) {
                scopedPerms.push({ scope: up.scope, objectId: up.object, objectName: await resolveScopedObjectName(up.scope, up.object), type: up.type, functions: up.functions || [], approvals: up.approvals || [] })
            }
        }
        scoped.value = { orgPermission: orgPerm, scopedPermissions: scopedPerms }
        notes.value = props.apiKey.notes || ''
        editTab.value = 'permissions'
    } finally { loading.value = false }
}
watch(() => props.show, (v) => { if (v) load() })

async function savePermissions () {
    const { orgPermType, permissions } = permissionsPayload()
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: gql`mutation setPermissionsOnFreeformApiKey($apiKeyUuid: ID!, $permissionType: PermissionType, $permissions: [PermissionInput]) {
                setPermissionsOnFreeformApiKey(apiKeyUuid: $apiKeyUuid, permissionType: $permissionType, permissions: $permissions) { uuid } }`,
            variables: { apiKeyUuid: props.apiKey.uuid, permissionType: orgPermType || 'NONE', permissions }
        })
        if (!resp.data.setPermissionsOnFreeformApiKey?.uuid) throw new Error('Failed to save key permissions. Please retry or contact support.')
        props.notify('success', 'Saved', 'Saved key permissions successfully!')
        emit('saved'); emit('update:show', false)
    } catch (error: any) {
        console.error(error)
        props.notify('error', 'Error', `Failed to save key permissions: ${commonFunctions.parseGraphQLError(error.message)}`)
    }
}

async function saveNotes () {
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation setNotesOnApiKey($apiKeyUuid: ID!, $notes: String) { setNotesOnApiKey(apiKeyUuid: $apiKeyUuid, notes: $notes) { uuid notes } }`,
            variables: { apiKeyUuid: props.apiKey.uuid, notes: notes.value }
        })
        props.notify('success', 'Saved', 'Saved key notes successfully!')
        emit('saved'); emit('update:show', false)
    } catch (error: any) {
        props.notify('error', 'Error', `Failed to save key notes: ${commonFunctions.parseGraphQLError(error.message)}`)
    }
}
</script>
