<template>
    <div class="tea-org-panel mt-4">
        <h5>Transparency Exchange (TEA)</h5>
        <div data-testid="tea-discovery" class="tea-block">
            <h6>Discovery</h6>
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
            <h6>Other TEA profiles of the organization</h6>
            <n-data-table data-testid="tea-profile-rows" :columns="columns" :data="otherRows" :row-class-name="rowClass"
                :pagination="false" :bordered="false" size="small" />
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDataTable, NInput, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import commonFunctions from '@/utils/commonFunctions'
import { TeaNamedObject, teaRowObject } from '@/utils/teaProfile'
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

// Components and products the lists leave out (archived ones), read one by one after the rows arrive.
const readObjects = ref<Record<string, TeaNamedObject>>({})

const objects = computed((): Record<string, TeaNamedObject> => {
    const out: Record<string, TeaNamedObject> = { ...readObjects.value }
    for (const c of props.components) out[c.uuid] = { name: c.name, status: c.status }
    for (const p of props.perspectives) out[p.uuid] = { name: p.name }
    return out
})

const otherRows = computed(() => rows.value.filter((r: any) => r.scope !== 'ORGANIZATION')
    .map((r: any) => ({ ...r, ...teaRowObject(r, objects.value) })))

const columns = [
    { key: 'scope', title: 'Scope' },
    { key: 'name', title: 'Object', render: (r: any) => r.archived
        ? [r.name, ' ', h(NTag, { size: 'small', type: 'warning', 'data-testid': 'tea-archived-tag' }, () => 'archived')]
        : r.name },
    { key: 'mode', title: 'Mode', render: (r: any) => r.mode ?? '' },
    { key: 'publishing', title: 'Publishing', render: (r: any) => r.publishing ?? 'follows its perspective' },
    { key: 'visibility', title: 'Visibility', render: (r: any) => r.visibility ?? '' },
]

function rowClass (r: any) {
    return r.visibility === 'PUBLIC' ? 'tea-public-row' : ''
}

async function reload () {
    try {
        const [d, list]: any[] = await Promise.all([
            graphqlClient.query({ query: graphqlQueries.TeaOrgDiscoveryGql, variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' }),
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
            return c ? [uuid, { name: c.name, status: c.status }] as const : null
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
