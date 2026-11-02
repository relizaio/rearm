<template>
    <div class="tea-org-panel mt-4">
        <h5>Transparency Exchange (TEA)</h5>
        <div data-testid="tea-discovery" class="tea-block">
            <h6>Discovery</h6>
            <template v-if="!proEdition">
                <!-- CE (task TPF-144, operator decision): the Discovery block as before, no serving controls -->
                <div v-if="discovery?.teaUuid">
                    <div><strong>Organization TEA id:</strong> <code data-testid="tea-org-uuid">{{ discovery.teaUuid }}</code></div>
                    <div><strong>TEA API base:</strong> <code data-testid="tea-api-base">{{ discovery.apiBase }}</code></div>
                    <p class="text-muted mt-2">Host this document at https://&lt;your TEI domain&gt;/.well-known/tea</p>
                    <n-input data-testid="tea-well-known" type="textarea" readonly :value="discovery.wellKnownDocument"
                        :autosize="{ minRows: 3, maxRows: 8 }" />
                    <n-button size="small" class="mt-2" data-testid="tea-well-known-copy" @click="copyWellKnown">Copy</n-button>
                </div>
                <div v-else data-testid="tea-org-uuid-missing" class="text-muted">
                    Organization TEA id: not minted yet: enable publishing on a profile to mint it.
                </div>
            </template>
            <template v-else>
                <!-- Pro (task TPF-144): the assigned domain, the serving mode and the /.well-known/tea hosting, as the server holds them -->
                <div v-if="discovery?.teaUuid"><strong>Organization TEA id:</strong> <code data-testid="tea-org-uuid">{{ discovery.teaUuid }}</code></div>
                <div v-else data-testid="tea-org-uuid-missing" class="text-muted">
                    Organization TEA id: not minted yet: enable publishing on a profile to mint it.
                </div>
                <template v-if="discovery">
                    <div v-if="discovery.assignedHost" data-testid="tea-assigned-domain"><strong>Assigned TEA domain:</strong> <code>{{ discovery.assignedHost }}</code></div>
                    <div v-else data-testid="tea-no-domain-notice" class="text-muted">{{ TEA_NO_DOMAIN_NOTICE }}</div>

                    <div data-testid="tea-serving-mode" class="mt-2"><strong>Serving mode:</strong>
                        <n-radio-group :value="discovery.teaServing" :disabled="!isOrgAdmin || servingSaving" @update:value="onServingChange">
                            <span v-for="o in servingOptions" :key="o.value" :data-testid="'tea-serving-option-' + o.value"><n-radio :value="o.value" :disabled="o.disabled">{{ o.label }}</n-radio></span>
                        </n-radio-group>
                        <div data-testid="tea-serving-public-note" class="text-muted">{{ TEA_SERVING_PUBLIC_NOTE }}</div>
                    </div>

                    <div v-if="discovery.apiBase" data-testid="tea-effective-base"><strong>TEA API base:</strong> <code data-testid="tea-api-base">{{ discovery.apiBase }}</code></div>
                    <div v-else-if="discovery.teaServing === 'NONE'" data-testid="tea-not-served" class="text-warning">{{ TEA_NOT_SERVED }}</div>
                    <div v-else-if="!discovery.teaUuid" data-testid="tea-not-served-no-id" class="text-warning">{{ TEA_NOT_SERVED_NO_ID }}</div>
                    <div v-else data-testid="tea-domain-lost" class="text-warning">{{ TEA_DOMAIN_LOST }}</div>

                    <div v-if="discovery.assignedHost" data-testid="tea-well-known-hosting" class="mt-2"><strong>/.well-known/tea:</strong>
                        <n-radio-group :value="discovery.teaWellKnownHosting" :disabled="!isOrgAdmin || servingSaving" @update:value="onHostingChange">
                            <span data-testid="tea-hosting-option-NONE"><n-radio value="NONE">Not hosted by this instance</n-radio></span>
                            <span data-testid="tea-hosting-option-DOMAIN"><n-radio value="DOMAIN" :disabled="discovery.teaServing !== 'DOMAIN'">Hosted on {{ discovery.assignedHost }}</n-radio></span>
                        </n-radio-group>
                        <div v-if="discovery.teaServing !== 'DOMAIN'" data-testid="tea-hosting-help" class="text-muted">{{ TEA_HOSTING_NEEDS_DOMAIN }}</div>
                    </div>
                    <div v-if="discovery.wellKnownUrl" data-testid="tea-well-known-hosted"><strong>Hosted at:</strong> <code data-testid="tea-well-known-url">{{ discovery.wellKnownUrl }}</code></div>

                    <div v-if="discovery.wellKnownDocument">
                        <p v-if="!discovery.wellKnownUrl" class="text-muted mt-2" data-testid="tea-well-known-self-host">Host this document at https://&lt;your TEI domain&gt;/.well-known/tea</p>
                        <n-input data-testid="tea-well-known" type="textarea" readonly :value="discovery.wellKnownDocument"
                            :autosize="{ minRows: 3, maxRows: 8 }" />
                        <n-button size="small" class="mt-2" data-testid="tea-well-known-copy" @click="copyWellKnown">Copy</n-button>
                    </div>
                    <div v-if="servingError" data-testid="tea-serving-error" class="text-danger">{{ servingError }}</div>
                </template>
            </template>
            <p class="text-muted mt-2">How consumers read this organization's TEA API, and how to host the
                document above, is described in the
                <a :href="brandingState.documentationUrl + '/tea/'" target="_blank" rel="noopener noreferrer"
                    data-testid="tea-docs-link">Transparency Exchange API documentation</a>.</p>
        </div>

        <div class="tea-block">
            <h6>Organization TEA profile</h6>
            <TeaProfileEditor
                :org-uuid="orgUuid"
                scope="ORGANIZATION"
                :is-writable="isOrgAdmin"
                :is-org-admin="isOrgAdmin"
                :installation-type="installationType"
                @saved="reload"
                @removed="reload" />
        </div>

        <div class="tea-block">
            <h6 data-testid="tea-overrides-heading">Overrides below the organization</h6>
            <p data-testid="tea-overrides-help" class="text-muted">TEA profiles saved on a perspective or a component. Edit or
                remove them there; components and perspectives without one inherit the organization profile.</p>
            <n-data-table data-testid="tea-profile-rows" :columns="columns" :data="otherRows" :row-class-name="rowClass"
                :pagination="false" :bordered="false" size="small">
                <template #empty>
                    <span data-testid="tea-overrides-empty" class="text-muted">{{ TEA_OVERRIDES_EMPTY }}</span>
                </template>
            </n-data-table>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { NButton, NDataTable, NInput, NRadio, NRadioGroup, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import commonFunctions from '@/utils/commonFunctions'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import { isProEdition } from '@/utils/editionCapabilities'
import { brandingState } from '@/utils/branding'
import { TEA_SERVING_LABELS, TeaNamedObject, TeaServing, TeaWellKnownHosting, teaRowLink, teaRowObject } from '@/utils/teaProfile'
import TeaProfileEditor from './TeaProfileEditor.vue'

const props = withDefaults(defineProps<{
    orgUuid: string
    isOrgAdmin?: boolean
    installationType?: string
    perspectives?: any[]
    components?: any[]
}>(), {
    isOrgAdmin: false,
    installationType: 'OSS',
    perspectives: () => [],
    components: () => [],
})

const notification = useNotification()
const discovery = ref<any>(null)
const rows = ref<any[]>([])
const servingSaving = ref(false)
const servingError = ref('')
// The edition, from the installation type alone, before any request (task TPF-144, operator decision a).
const proEdition = computed(() => isProEdition(props.installationType))

// Components and products the lists leave out (archived ones), read one by one after the rows arrive.
const readObjects = ref<Record<string, TeaNamedObject>>({})

const objects = computed((): Record<string, TeaNamedObject> => {
    const out: Record<string, TeaNamedObject> = { ...readObjects.value }
    for (const c of props.components) out[c.uuid] = { name: c.name, status: c.status, type: c.type }
    for (const p of props.perspectives) out[p.uuid] = { name: p.name }
    return out
})

const TEA_OVERRIDES_EMPTY = 'No component or perspective overrides the organization profile.'
const TEA_NO_DOMAIN_NOTICE = 'No TEA domain is assigned to this organization. A global administrator can assign one in System Settings.'
const TEA_NOT_SERVED = 'Not served: the TEA API of this organization answers nothing. Choose a serving mode above to enable it.'
const TEA_NOT_SERVED_NO_ID = 'Not served yet: the organization has no TEA id. Enable publishing on a TEA profile to mint one.'
const TEA_DOMAIN_LOST = 'Not served: this organization has no assignable TEA domain any more. A global administrator can assign one in System Settings, or choose another serving mode.'
const TEA_HOSTING_NEEDS_DOMAIN = 'The instance hosts /.well-known/tea on the domain only while the serving mode is On the assigned domain.'
const TEA_SERVING_PUBLIC_NOTE = "Choosing a serving mode makes this organization's public TEA content reachable at the API base below at once."

/** The serving modes offered: DOMAIN only with an assigned host, or while it is the stored mode; LEGACY_UUID needs a TEA id. */
const servingOptions = computed(() => {
    const out: { value: TeaServing, label: string, disabled?: boolean }[] = [
        { value: 'NONE', label: TEA_SERVING_LABELS.NONE },
        { value: 'LEGACY_UUID', label: TEA_SERVING_LABELS.LEGACY_UUID, disabled: !discovery.value?.teaUuid },
    ]
    if (discovery.value?.assignedHost || discovery.value?.teaServing === 'DOMAIN') {
        out.push({ value: 'DOMAIN', label: TEA_SERVING_LABELS.DOMAIN })
    }
    return out
})

const otherRows = computed(() => rows.value.filter((r: any) => r.scope !== 'ORGANIZATION')
    .map((r: any) => ({ ...r, ...teaRowObject(r, objects.value),
        link: teaRowLink(r, props.orgUuid, r.object ? objects.value[r.object]?.type : null) })))

/** The object's name, linked to where its TEA profile is edited. */
function objectName (r: any) {
    return r.link
        ? h(RouterLink, { to: r.link, 'data-testid': 'tea-object-link' }, { default: () => r.name })
        : r.name
}

const columns = [
    { key: 'scope', title: 'Scope' },
    { key: 'name', title: 'Object', render: (r: any) => r.archived
        ? [objectName(r), ' ', h(NTag, { size: 'small', type: 'warning', 'data-testid': 'tea-archived-tag' }, () => 'archived')]
        : objectName(r) },
    { key: 'mode', title: 'Mode', render: (r: any) => r.mode ?? '' },
    { key: 'publishing', title: 'Publishing', render: (r: any) => r.publishing ?? 'follows its perspective' },
    { key: 'visibility', title: 'Visibility', render: (r: any) => r.visibility ?? '' },
]

function rowClass (r: any) {
    return r.visibility === 'PUBLIC' ? 'tea-public-row' : ''
}

async function reload () {
    servingError.value = ''
    try {
        const [d, list]: any[] = await Promise.all([
            graphqlClient.query({ query: proEdition.value ? graphqlQueries.TeaOrgServingGql : graphqlQueries.TeaOrgDiscoveryGql, variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: graphqlQueries.TeaProfilesOfOrgGql, variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' }),
        ])
        discovery.value = d.data.teaOrgDiscovery
        rows.value = list.data.teaProfilesOfOrg ?? []
    } catch (err: any) {
        console.error(err)
        notification.error({ content: commonFunctions.parseGraphQLError(err.message), duration: 5000 })
        return
    }
    await readUnlistedComponents()
}

/**
 * Names every COMPONENT-scope row whose object is in no list (an archived component or product:
 * the lists leave those out) with one component read each; a failed read is logged and that row
 * keeps its uuid.
 */
async function readUnlistedComponents () {
    const unlisted = [...new Set(rows.value.filter((r: any) => r.scope === 'COMPONENT' && r.object
        && !objects.value[r.object]).map((r: any) => r.object as string))]
    const read = await Promise.all(unlisted.map(async (uuid) => {
        try {
            const resp: any = await graphqlClient.query({ query: graphqlQueries.TeaComponentNameGql,
                variables: { componentUuid: uuid }, fetchPolicy: 'no-cache' })
            const c = resp.data.component
            return c ? [uuid, { name: c.name, status: c.status, type: c.type }] as const : null
        } catch (err: any) {
            console.error(err)
            return null
        }
    }))
    const out = { ...readObjects.value }
    for (const r of read) if (r) out[r[0]] = r[1]
    readObjects.value = out
}

async function copyWellKnown () {
    await navigator.clipboard.writeText(discovery.value?.wellKnownDocument ?? '')
    notification.success({ content: 'Copied', duration: 2000 })
}

async function setServing (teaServing: TeaServing, teaWellKnownHosting: TeaWellKnownHosting) {
    if (!proEdition.value || !props.isOrgAdmin || !discovery.value) return
    servingSaving.value = true
    servingError.value = ''
    try {
        const resp: any = await graphqlClient.mutate({ mutation: graphqlQueries.SetTeaOrgServingGql,
            variables: { org: props.orgUuid, teaServing, teaWellKnownHosting } })
        discovery.value = resp.data.setTeaOrgServing
    } catch (err: any) {
        console.error(err)
        const text = graphqlRefusalText(err)
        // The server's state first (a refusal often follows a change made elsewhere), then the text.
        await refreshDiscovery()
        servingError.value = text
    } finally {
        servingSaving.value = false
    }
}

/** Re-reads the discovery only (not the profile rows); a failed read is logged and keeps the shown state. */
async function refreshDiscovery () {
    try {
        const d: any = await graphqlClient.query({ query: graphqlQueries.TeaOrgServingGql,
            variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' })
        discovery.value = d.data.teaOrgDiscovery
    } catch (err: any) {
        console.error(err)
    }
}

function onServingChange (v: TeaServing) {
    // Leaving DOMAIN takes the instance-hosted /.well-known/tea down with it (the server refuses one without the other).
    setServing(v, v === 'DOMAIN' ? discovery.value.teaWellKnownHosting : 'NONE')
}

function onHostingChange (v: TeaWellKnownHosting) {
    setServing(discovery.value.teaServing, v)
}

onMounted(reload)
watch(() => props.orgUuid, reload)
</script>

<style scoped>
.tea-block {
    margin-bottom: 1.5rem;
}
:deep(.tea-public-row td) {
    color: #d03050;
}
</style>
