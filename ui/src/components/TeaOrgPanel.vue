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
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NDataTable, NInput, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import commonFunctions from '@/utils/commonFunctions'
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

const names = computed((): Record<string, string> => {
    const out: Record<string, string> = {}
    for (const c of props.components) out[c.uuid] = c.name
    for (const p of props.perspectives) out[p.uuid] = p.name
    return out
})

const otherRows = computed(() => rows.value.filter((r: any) => r.scope !== 'ORGANIZATION')
    .map((r: any) => ({ ...r, objectName: names.value[r.object] ?? r.object })))

const columns = [
    { key: 'scope', title: 'Scope' },
    { key: 'objectName', title: 'Object' },
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
    }
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
