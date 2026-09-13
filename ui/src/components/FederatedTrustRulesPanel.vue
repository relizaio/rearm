<template>
    <div>
        <div class="programmaticAccessBlock mt-4">
            <h5>Trust Rules</h5>
            <p class="subtle">
                Identity tokens from an external CI (GitHub Actions) act as credentials when they match a rule: no secret is stored anywhere.
                A rule covers a whole owner (organization) on the provider side, narrowed by repositories, refs, environments, workflows or events.
                Its grant is either a <strong>template</strong> evaluated against the calling repository on every call (its own components, optionally creating them),
                or one <strong>Free Form key</strong> to act as. Several matching rules add up. Editing or disabling a rule invalidates the tokens minted through it at once.
            </p>
            <n-spin :show="loading">
                <n-data-table :columns="ruleFields" :data="rules" :scroll-x="2000" class="table-hover"></n-data-table>
            </n-spin>
            <n-icon v-if="canManage" class="clickable" @click="openCreate" title="Add Trust Rule" size="24"><CirclePlus /></n-icon>
        </div>
        <div class="programmaticAccessBlock mt-4">
            <h5>Federated Identities</h5>
            <p class="subtle">
                One row per repository that has authenticated through a template rule. Nothing is stored on it: what it may do is computed from the rules on every call.
                The repository id is pinned on first use, so a renamed or recreated repository is refused until the pin is reset. Deactivating a row refuses that repository alone and holds
                until an admin activates it again; deleting a row only forgets its history, the next run from that repository materialises it again as long as a rule trusts it.
            </p>
            <n-data-table :columns="identityFields" :data="identities" :scroll-x="1900" class="table-hover"></n-data-table>
        </div>

        <n-modal preset="dialog" :show-icon="false" style="width: 85%;" :show="showEditor" @update:show="(v: boolean) => { if (!v) showEditor = false }">
            <template #header>{{ editing ? 'Edit trust rule' : 'New trust rule' }}</template>
            <div style="max-height: 75vh; overflow-y: auto; padding-right: 8px;">
                <n-form label-placement="top">
                    <n-grid :cols="3" :x-gap="12">
                        <n-form-item-gi label="Name" :span="1">
                            <n-input v-model:value="form.name" placeholder="e.g. relizaio GitHub Actions" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Provider" :span="1">
                            <n-select v-model:value="form.provider" :options="providerOptions" :disabled="!!editing" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Issuer (only for GitHub Enterprise Server: https://HOST/_services/token)" :span="1">
                            <n-input v-model:value="form.issuer" :disabled="!!editing" placeholder="default: https://token.actions.githubusercontent.com" />
                        </n-form-item-gi>
                    </n-grid>
                    <h5>Who is trusted</h5>
                    <n-grid :cols="2" :x-gap="12">
                        <n-form-item-gi label="Owner on the provider (GitHub organization or user), required" :span="1">
                            <n-input v-model:value="form.matcher.owner" placeholder="e.g. relizaio" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Repositories (globs over the name, or owner/name; * spans separators; empty = every repository of the owner)" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.repositories" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Excluded repositories (write them as precisely as the inclusions: * spans separators)" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.excludeRepositories" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Refs (main, release/*, refs/tags/v*; a bare pattern matches branches and tags)" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.refs" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Environments" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.environments" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Workflow files (release.yml) or globs over workflow_ref" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.workflows" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Events (push, workflow_dispatch, release, ...)" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.events" />
                        </n-form-item-gi>
                        <n-form-item-gi label="Subject globs (advanced: raw sub claim, e.g. repo:relizaio/*:environment:prod)" :span="1">
                            <n-dynamic-tags v-model:value="form.matcher.subjects" />
                        </n-form-item-gi>
                    </n-grid>
                    <h5>What it may do</h5>
                    <n-radio-group v-model:value="form.grant.type" style="margin-bottom: 12px;">
                        <n-radio value="TEMPLATE">Scope by repository (template)</n-radio>
                        <n-radio value="KEY">Act as a Free Form key</n-radio>
                    </n-radio-group>
                    <div v-if="form.grant.type === 'KEY'">
                        <n-form-item label="Free Form key of this organization to act as (its permissions and attribution)">
                            <n-select v-model:value="form.grant.keyUuid" :options="keyOptions" placeholder="Choose a key" filterable />
                        </n-form-item>
                    </div>
                    <div v-else>
                        <n-grid :cols="3" :x-gap="12">
                            <n-form-item-gi label="On the calling repository's components, branches and releases" :span="1">
                                <n-select v-model:value="form.grant.vcsPermission" :options="vcsPermissionOptions" />
                            </n-form-item-gi>
                            <n-form-item-gi label="Organization-wide (READ_ONLY lets names resolve and lists work)" :span="1">
                                <n-select v-model:value="form.grant.orgPermission" :options="orgPermissionOptions" />
                            </n-form-item-gi>
                            <n-form-item-gi label="Creation" :span="1">
                                <n-checkbox v-model:checked="form.grant.createComponents" :disabled="form.grant.vcsPermission !== 'READ_WRITE'">
                                    May create components (and the VCS repository record) bound to the calling repository
                                </n-checkbox>
                            </n-form-item-gi>
                        </n-grid>
                        <n-form-item label="Functions on those permissions (RESOURCE is implicit)">
                            <n-checkbox-group v-model:value="form.grant.functions">
                                <n-space>
                                    <n-checkbox v-for="f in permissionFunctions" :key="f" :value="f" :label="translateFunctionName(f)" />
                                </n-space>
                            </n-checkbox-group>
                        </n-form-item>
                        <n-form-item label="Extra static permissions (optional: products, perspectives, instances the repository cannot express)">
                            <div style="width: 100%;">
                                <table v-if="form.grant.permissions.length" class="table-hover" style="width: 100%; font-size: 13px; margin-bottom: 8px;">
                                    <thead><tr><th style="text-align:left;">Scope</th><th style="text-align:left;">Object</th><th style="text-align:left;">Type</th><th style="text-align:left;">Functions</th><th></th></tr></thead>
                                    <tbody>
                                        <tr v-for="(sp, idx) in form.grant.permissions" :key="idx">
                                            <td style="width: 150px;"><n-select v-model:value="sp.scope" :options="scopeOptions" size="small" @update:value="sp.object = null" /></td>
                                            <td><n-select v-model:value="sp.object" :options="objectOptions(sp.scope)" size="small" filterable placeholder="Object" /></td>
                                            <td style="width: 150px;"><n-select v-model:value="sp.type" :options="extraTypeOptions" size="small" /></td>
                                            <td><n-select v-model:value="sp.functions" :options="functionOptions" size="small" multiple placeholder="none" /></td>
                                            <td style="width: 40px;"><n-icon class="icons clickable" size="20" title="Remove" @click="form.grant.permissions.splice(idx, 1)"><Trash /></n-icon></td>
                                        </tr>
                                    </tbody>
                                </table>
                                <n-button size="tiny" dashed @click="form.grant.permissions.push({ scope: 'COMPONENT', object: null, type: 'READ_ONLY', functions: [] })">Add permission</n-button>
                            </div>
                        </n-form-item>
                    </div>
                    <n-form-item label="Expires (optional; tokens minted through the rule end at this time at the latest)">
                        <n-date-picker v-model:value="form.expiresAt" type="datetime" clearable />
                    </n-form-item>
                </n-form>
                <n-space style="margin-top: 12px;">
                    <n-button type="success" :disabled="saving || !form.name.trim() || !form.matcher.owner.trim() || (form.grant.type === 'KEY' && !form.grant.keyUuid)" @click="save">{{ editing ? 'Save' : 'Create' }}</n-button>
                    <n-button @click="showEditor = false">Cancel</n-button>
                </n-space>
                <p v-if="editing" class="subtle" style="margin-top: 10px;">Saving bumps the rule version: access tokens minted through it stop working and the next exchange mints new ones.</p>
            </div>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
/**
 * Federated identity administration (org admins): the trust rules an organization holds for
 * external CI identity tokens, and the identity rows those rules materialise per repository.
 * The identity rows are ordinary FEDERATED api keys, so their status kill switch and deletion
 * reuse the shared key controls; they have no secrets and no stored permissions.
 */
import { h, ref, computed, watch } from 'vue'
import { NDataTable, NModal, NForm, NFormItem, NFormItemGi, NGrid, NInput, NSelect, NDynamicTags, NRadioGroup, NRadio, NCheckbox, NCheckboxGroup,
    NSpace, NButton, NIcon, NTag, NTooltip, NDatePicker, NSpin } from 'naive-ui'
import { CirclePlus, Edit as EditIcon, Trash, Refresh } from '@vicons/tabler'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import Swal from 'sweetalert2'
import graphqlClient from '../utils/graphql'
import constants from '../utils/constants'
import commonFunctions from '@/utils/commonFunctions'
import { apiKeyIdsColumn } from '../utils/apiKeyControls'

const props = defineProps<{
    orgUuid: string
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
    /** FEDERATED key rows of the org (already date-formatted by the caller) */
    identities: any[]
    /** Free Form keys of the org, for key-bound rules */
    freeFormKeys: any[]
    canManage: boolean
    /** shared key controls of the caller: status cell (kill switch) and delete */
    apiKeyControls: any
}>()
const emit = defineEmits(['changed'])
const store = useStore()

const rules = ref<any[]>([])
const loading = ref(false)
const perspectives = ref<any[]>([])
const fail = (e: any) => props.notify('error', 'Error', commonFunctions.parseGraphQLError(e.message))

async function loadRules () {
    if (!props.orgUuid) return
    loading.value = true
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query federatedTrustRules($orgUuid: ID!) { federatedTrustRules(orgUuid: $orgUuid) {
                uuid org name provider issuer status version pinnedOwnerId expiresDate createdDate lastUpdatedDate lastUsedDate
                matcher { owner repositories excludeRepositories refs environments workflows events subjects }
                grant { type keyUuid orgPermission vcsPermission createComponents functions permissions { scope object type functions } } } }`,
            variables: { orgUuid: props.orgUuid }, fetchPolicy: 'no-cache'
        })
        rules.value = resp.data.federatedTrustRules || []
    } catch (e: any) { fail(e) } finally { loading.value = false }
}
watch(() => props.orgUuid, () => loadRules(), { immediate: true })
defineExpose({ reload: loadRules })

function fmt (d: any): string { return d ? new Date(d).toLocaleString('en-CA', { hour12: false }).slice(0, 17) : '' }
function translateFunctionName (fn: string): string { return commonFunctions.translateFunctionName(fn) }

// ---- rule table ----
function matcherSummary (m: any): { main: string, constraints: string[] } {
    const repos = (m?.repositories || []).length ? m.repositories.join(', ') : '*'
    const constraints: string[] = []
    if ((m?.excludeRepositories || []).length) constraints.push('not ' + m.excludeRepositories.join(', '))
    if ((m?.refs || []).length) constraints.push('refs: ' + m.refs.join(', '))
    if ((m?.environments || []).length) constraints.push('environments: ' + m.environments.join(', '))
    if ((m?.workflows || []).length) constraints.push('workflows: ' + m.workflows.join(', '))
    if ((m?.events || []).length) constraints.push('events: ' + m.events.join(', '))
    if ((m?.subjects || []).length) constraints.push('subjects: ' + m.subjects.join(', '))
    return { main: `${m?.owner || '?'} / ${repos}`, constraints }
}
function keyLabel (uuid: string): string {
    const k = props.freeFormKeys.find((x: any) => x.uuid === uuid)
    return k ? (k.notes || k.uuid) : (uuid || '?')
}
function grantSummary (g: any): string {
    if (!g) return ''
    if (g.type === 'KEY') return `acts as key: ${keyLabel(g.keyUuid)}`
    const parts: string[] = [`own repository: ${g.vcsPermission || 'NONE'}${g.createComponents ? ' + create' : ''}`, `org: ${g.orgPermission || 'NONE'}`]
    if ((g.functions || []).length) parts.push('functions: ' + g.functions.join(', '))
    if ((g.permissions || []).length) parts.push(`+${g.permissions.length} extra`)
    return parts.join(' · ')
}
async function setStatus (row: any, status: string) {
    if (status === 'DISABLED') {
        const r = await Swal.fire({ title: 'Disable this rule?', text: 'Identities admitted through it lose what it grants at once; access tokens minted through it stop working.', icon: 'warning', showCancelButton: true, confirmButtonText: 'Disable', cancelButtonText: 'Cancel' })
        if (!r.value) return
    }
    try {
        await graphqlClient.mutate({ mutation: gql`mutation setFederatedTrustRuleStatus($uuid: ID!, $status: FederatedTrustRuleStatus!) { setFederatedTrustRuleStatus(uuid: $uuid, status: $status) { uuid status version } }`, variables: { uuid: row.uuid, status }, fetchPolicy: 'no-cache' })
        props.notify('success', status === 'ACTIVE' ? 'Enabled' : 'Disabled', `Rule ${row.name} ${status.toLowerCase()}`); await loadRules(); emit('changed')
    } catch (e: any) { fail(e) }
}
async function deleteRule (row: any) {
    const r = await Swal.fire({ title: `Delete rule ${row.name}?`, text: 'Tokens minted through it stop working. Identity rows it created stay listed and can be deleted separately.', icon: 'warning', showCancelButton: true, confirmButtonText: 'Delete', cancelButtonText: 'Cancel' })
    if (!r.value) return
    try {
        await graphqlClient.mutate({ mutation: gql`mutation deleteFederatedTrustRule($uuid: ID!) { deleteFederatedTrustRule(uuid: $uuid) }`, variables: { uuid: row.uuid }, fetchPolicy: 'no-cache' })
        props.notify('success', 'Deleted', 'Rule deleted'); await loadRules(); emit('changed')
    } catch (e: any) { fail(e) }
}
async function resetRulePin (row: any) {
    const r = await Swal.fire({ title: 'Reset the pinned owner id?', text: `The next exchange pins whatever owner id the provider presents. Only do this after the owner ${row.matcher?.owner} was renamed or recreated on the provider.`, icon: 'warning', showCancelButton: true, confirmButtonText: 'Reset', cancelButtonText: 'Cancel' })
    if (!r.value) return
    try {
        await graphqlClient.mutate({ mutation: gql`mutation resetFederatedTrustRulePin($uuid: ID!) { resetFederatedTrustRulePin(uuid: $uuid) { uuid pinnedOwnerId } }`, variables: { uuid: row.uuid }, fetchPolicy: 'no-cache' })
        props.notify('success', 'Reset', 'Owner pin cleared'); await loadRules()
    } catch (e: any) { fail(e) }
}
const ruleFields = computed(() => [
    { key: 'name', width: 200, title: 'Name', render: (row: any) => h('div', [h('div', row.name), h('div', { class: 'subtle', style: 'font-size: 11px;' }, `v${row.version}`)]) },
    { key: 'provider', width: 170, title: 'Provider', render: (row: any) => h('div', [h('div', row.provider === 'GITHUB_ACTIONS' ? 'GitHub Actions' : row.provider), h('div', { class: 'subtle', style: 'font-size: 11px; word-break: break-all;' }, row.issuer)]) },
    {
        key: 'matcher', width: 320, title: 'Trusted identities',
        render: (row: any) => {
            const s = matcherSummary(row.matcher)
            const kids: any[] = [h('div', [h('strong', s.main)])]
            for (const c of s.constraints) kids.push(h('div', { class: 'subtle', style: 'font-size: 12px;' }, c))
            return h('div', kids)
        }
    },
    { key: 'grant', width: 320, title: 'Grant', render: (row: any) => h('div', { style: 'white-space: normal;' }, grantSummary(row.grant)) },
    {
        key: 'status', width: 170, title: 'Status',
        render: (row: any) => {
            const active = row.status === 'ACTIVE'
            const expired = !!row.expiresDate && new Date(row.expiresDate).getTime() <= Date.now()
            const kids: any[] = [h(NTag, { size: 'small', type: !active ? 'error' : (expired ? 'warning' : 'success'), style: 'margin-right: 6px;' }, { default: () => !active ? 'DISABLED' : (expired ? 'EXPIRED' : 'ACTIVE') })]
            if (props.canManage) kids.push(h(NButton, { size: 'tiny', type: active ? 'warning' : 'primary', onClick: () => setStatus(row, active ? 'DISABLED' : 'ACTIVE') }, { default: () => active ? 'Disable' : 'Enable' }))
            return h('div', { style: 'display: flex; align-items: center; white-space: nowrap;' }, kids)
        }
    },
    {
        key: 'pinnedOwnerId', width: 190, title: 'Pinned owner id',
        render: (row: any) => {
            if (!row.pinnedOwnerId) return h('span', { class: 'text-muted' }, 'not yet used')
            const kids: any[] = [h('code', { style: 'margin-right: 6px;' }, row.pinnedOwnerId)]
            if (props.canManage) kids.push(h(NIcon, { title: 'Reset pin (after a rename on the provider)', class: 'icons clickable', size: 20, onClick: () => resetRulePin(row) }, { default: () => h(Refresh) }))
            return h('div', { style: 'display: flex; align-items: center;' }, kids)
        }
    },
    { key: 'expiresDate', width: 150, title: 'Expires', render: (row: any) => h('span', { class: row.expiresDate ? '' : 'text-muted' }, row.expiresDate ? fmt(row.expiresDate) : 'never') },
    { key: 'lastUsedDate', width: 150, title: 'Last used', render: (row: any) => h('span', { class: row.lastUsedDate ? '' : 'text-muted' }, row.lastUsedDate ? fmt(row.lastUsedDate) : 'never') },
    {
        key: 'controls', title: 'Manage',
        render: (row: any) => {
            if (!props.canManage) return h('div')
            return h('div', [
                h(NIcon, { title: 'Edit rule', class: 'icons clickable', size: 25, onClick: () => openEdit(row) }, { default: () => h(EditIcon) }),
                h(NIcon, { title: 'Delete rule', class: 'icons clickable', size: 25, onClick: () => deleteRule(row) }, { default: () => h(Trash) })
            ])
        }
    }
])

// ---- identity table ----
async function resetIdentityPin (row: any) {
    const r = await Swal.fire({ title: 'Reset the pinned repository id?', text: `The next exchange from ${row.federation?.repository} pins whatever repository id the provider presents. Only do this after the repository was renamed or recreated.`, icon: 'warning', showCancelButton: true, confirmButtonText: 'Reset', cancelButtonText: 'Cancel' })
    if (!r.value) return
    try {
        await graphqlClient.mutate({ mutation: gql`mutation resetFederatedIdentityPin($apiKeyUuid: ID!) { resetFederatedIdentityPin(apiKeyUuid: $apiKeyUuid) { uuid } }`, variables: { apiKeyUuid: row.uuid }, fetchPolicy: 'no-cache' })
        props.notify('success', 'Reset', 'Repository pin cleared'); emit('changed')
    } catch (e: any) { fail(e) }
}
const identityFields = computed(() => [
    {
        key: 'repository', width: 260, title: 'Repository',
        render: (row: any) => h('div', [h('strong', row.federation?.repository || row.keyOrder), h('div', { class: 'subtle', style: 'font-size: 11px;' }, row.federation?.provider === 'GITHUB_ACTIONS' ? 'GitHub Actions' : (row.federation?.provider || ''))])
    },
    apiKeyIdsColumn(),
    { key: 'repositoryUri', width: 260, title: 'VCS repository', render: (row: any) => h('code', { style: 'font-size: 12px;' }, row.federation?.repositoryUri || '') },
    {
        key: 'pins', width: 230, title: 'Pinned ids',
        render: (row: any) => {
            const f = row.federation || {}
            if (!f.repositoryId) return h('span', { class: 'text-muted' }, 'not pinned')
            const kids: any[] = [h('div', [h('span', { class: 'subtle' }, 'repo '), h('code', f.repositoryId), h('span', { class: 'subtle' }, ' owner '), h('code', f.ownerId || '?')])]
            const line2: any[] = [h('span', { class: 'subtle', style: 'font-size: 11px; margin-right: 6px;' }, `since ${fmt(f.pinnedDate)}`)]
            if (props.canManage) line2.push(h(NIcon, { title: 'Reset pin (after a rename on the provider)', class: 'icons clickable', size: 18, onClick: () => resetIdentityPin(row) }, { default: () => h(Refresh) }))
            kids.push(h('div', { style: 'display: flex; align-items: center;' }, line2))
            return h('div', kids)
        }
    },
    {
        key: 'lastRun', width: 260, title: 'Last run',
        render: (row: any) => {
            const f = row.federation || {}
            if (!f.lastRunId && !f.lastRef) return h('span', { class: 'text-muted' }, '—')
            return h('div', [h('div', f.lastRef || ''), h('div', { class: 'subtle', style: 'font-size: 11px;' }, `run ${f.lastRunId || '?'}${f.lastActor ? ' by ' + f.lastActor : ''}`)])
        }
    },
    { key: 'accessDate', width: 170, title: 'Last Accessed' },
    { key: 'status', width: 170, title: 'Status', render: (row: any) => props.apiKeyControls.statusCell(row) },
    {
        key: 'controls', title: 'Manage',
        render: (row: any) => {
            if (!props.canManage) return h('div')
            return h('div', [h(NIcon, { title: 'Delete identity', class: 'icons clickable', size: 25, onClick: () => props.apiKeyControls.deleteApiKey(row, 'this identity') }, { default: () => h(Trash) })])
        }
    }
])

// ---- editor ----
const showEditor = ref(false)
const editing = ref<any>(null)
const saving = ref(false)
function emptyForm () {
    return {
        name: '', provider: 'GITHUB_ACTIONS', issuer: '', expiresAt: null as number | null,
        matcher: { owner: '', repositories: [] as string[], excludeRepositories: [] as string[], refs: [] as string[], environments: [] as string[], workflows: [] as string[], events: [] as string[], subjects: [] as string[] },
        grant: { type: 'TEMPLATE', keyUuid: null as string | null, orgPermission: 'READ_ONLY', vcsPermission: 'READ_WRITE', createComponents: true, functions: [] as string[], permissions: [] as any[] }
    }
}
const form = ref(emptyForm())
const providerOptions = [{ label: 'GitHub Actions', value: 'GITHUB_ACTIONS' }]
const vcsPermissionOptions = ['NONE', 'READ_ONLY', 'READ_WRITE'].map(v => ({ label: v, value: v }))
const orgPermissionOptions = ['NONE', 'ESSENTIAL_READ', 'READ_ONLY'].map(v => ({ label: v, value: v }))
const extraTypeOptions = ['READ_ONLY', 'READ_WRITE'].map(v => ({ label: v, value: v }))
const permissionFunctions: string[] = constants.PermissionFunctions
const functionOptions = permissionFunctions.map(f => ({ label: translateFunctionName(f), value: f }))
const installationType = computed(() => store.getters.myuser?.installationType)
const scopeOptions = computed(() => {
    const o = [{ label: 'Component / Product', value: 'COMPONENT' }, { label: 'Perspective', value: 'PERSPECTIVE' }]
    if (installationType.value !== 'OSS') o.push({ label: 'Instance / Cluster', value: 'INSTANCE' })
    return o
})
const keyOptions = computed(() => props.freeFormKeys.map((k: any) => ({ label: `${k.notes || '(no notes)'} · ${k.uuid.slice(0, 8)}`, value: k.uuid })))
function objectOptions (scope: string) {
    if (scope === 'PERSPECTIVE') return perspectives.value.map((p: any) => ({ label: p.name, value: p.uuid }))
    if (scope === 'INSTANCE') {
        const all = (store.getters.instancesOfOrg(props.orgUuid) || []).filter((x: any) => x.revision === -1 && (x.status === 'ACTIVE' || !x.status))
        return all.map((i: any) => ({ label: i.uri || i.name || i.uuid, value: i.uuid }))
    }
    const comps = [...(store.getters.componentsOfOrg(props.orgUuid) || []), ...(store.getters.productsOfOrg(props.orgUuid) || [])]
    return comps.map((c: any) => ({ label: c.name, value: c.uuid }))
}
async function loadEditorObjects () {
    const loads: Promise<any>[] = [store.dispatch('fetchComponents', props.orgUuid), store.dispatch('fetchProducts', props.orgUuid)]
    if (installationType.value !== 'OSS') loads.push(store.dispatch('fetchInstances', props.orgUuid))
    loads.push(graphqlClient.query({ query: gql`query perspectives($org: ID!) { perspectives(org: $org) { uuid name org type } }`, variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' })
        .then((r: any) => { perspectives.value = r.data.perspectives || [] }))
    const results = await Promise.allSettled(loads)
    for (const r of results) if (r.status === 'rejected') console.warn('trust rule editor: an org object list is unavailable', r.reason?.message || r.reason)
}
function openCreate () {
    editing.value = null
    form.value = emptyForm()
    showEditor.value = true
    loadEditorObjects()
}
function openEdit (row: any) {
    editing.value = row
    const f = emptyForm()
    f.name = row.name; f.provider = row.provider; f.issuer = row.issuer || ''
    f.expiresAt = row.expiresDate ? new Date(row.expiresDate).getTime() : null
    const m = row.matcher || {}
    f.matcher = { owner: m.owner || '', repositories: [...(m.repositories || [])], excludeRepositories: [...(m.excludeRepositories || [])], refs: [...(m.refs || [])],
        environments: [...(m.environments || [])], workflows: [...(m.workflows || [])], events: [...(m.events || [])], subjects: [...(m.subjects || [])] }
    const g = row.grant || {}
    f.grant = { type: g.type || 'TEMPLATE', keyUuid: g.keyUuid || null, orgPermission: g.orgPermission || 'NONE', vcsPermission: g.vcsPermission || 'NONE',
        createComponents: !!g.createComponents, functions: [...(g.functions || [])], permissions: (g.permissions || []).map((p: any) => ({ scope: p.scope, object: p.object, type: p.type, functions: [...(p.functions || [])] })) }
    form.value = f
    showEditor.value = true
    loadEditorObjects()
}
function payload () {
    const f = form.value
    const matcher: any = { owner: f.matcher.owner.trim() }
    for (const k of ['repositories', 'excludeRepositories', 'refs', 'environments', 'workflows', 'events', 'subjects'] as const) {
        const v = (f.matcher[k] || []).map((x: string) => x.trim()).filter((x: string) => x)
        if (v.length) matcher[k] = v
    }
    const grant: any = { type: f.grant.type }
    if (f.grant.type === 'KEY') {
        grant.keyUuid = f.grant.keyUuid
    } else {
        grant.orgPermission = f.grant.orgPermission
        grant.vcsPermission = f.grant.vcsPermission
        grant.createComponents = f.grant.vcsPermission === 'READ_WRITE' && f.grant.createComponents
        grant.functions = f.grant.functions
        grant.permissions = f.grant.permissions.filter((p: any) => p.scope && p.object && p.type).map((p: any) => ({ scope: p.scope, object: p.object, type: p.type, functions: p.functions || [] }))
    }
    return { name: f.name.trim(), provider: f.provider, issuer: f.issuer.trim() || null, matcher, grant, expiresDate: f.expiresAt ? new Date(f.expiresAt).toISOString() : null }
}
async function save () {
    saving.value = true
    const p = payload()
    try {
        if (editing.value) {
            await graphqlClient.mutate({
                mutation: gql`mutation updateFederatedTrustRule($uuid: ID!, $name: String!, $matcher: FederatedMatcherInput!, $grant: FederatedGrantInput!, $expiresDate: DateTime) {
                    updateFederatedTrustRule(uuid: $uuid, name: $name, matcher: $matcher, grant: $grant, expiresDate: $expiresDate) { uuid version } }`,
                variables: { uuid: editing.value.uuid, name: p.name, matcher: p.matcher, grant: p.grant, expiresDate: p.expiresDate }, fetchPolicy: 'no-cache'
            })
            props.notify('success', 'Saved', 'Rule updated; tokens minted through it are invalidated')
        } else {
            await graphqlClient.mutate({
                mutation: gql`mutation createFederatedTrustRule($orgUuid: ID!, $name: String!, $provider: FederatedProvider!, $issuer: String, $matcher: FederatedMatcherInput!, $grant: FederatedGrantInput!, $expiresDate: DateTime) {
                    createFederatedTrustRule(orgUuid: $orgUuid, name: $name, provider: $provider, issuer: $issuer, matcher: $matcher, grant: $grant, expiresDate: $expiresDate) { uuid } }`,
                variables: { orgUuid: props.orgUuid, name: p.name, provider: p.provider, issuer: p.issuer, matcher: p.matcher, grant: p.grant, expiresDate: p.expiresDate }, fetchPolicy: 'no-cache'
            })
            props.notify('success', 'Created', 'Trust rule created')
        }
        showEditor.value = false
        await loadRules(); emit('changed')
    } catch (e: any) { fail(e) } finally { saving.value = false }
}
</script>
