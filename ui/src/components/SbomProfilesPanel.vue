<template>
    <div class="sbom-profiles-panel mt-4">
        <h5>Profiles</h5>
        <div class="sbom-block">
            <h6>SBOM profiles of the organization</h6>
            <div v-if="loadFailed">
                <n-button size="small" data-testid="sbom-retry" @click="reload">Retry</n-button>
            </div>
            <n-button v-else-if="isOrgAdmin" size="small" class="mb-2" data-testid="sbom-create" @click="openEditor(null)">
                Create profile
            </n-button>
            <n-data-table data-testid="sbom-profile-rows" :columns="catalogColumns" :data="catalogRows" :row-key="rowKey"
                :pagination="false" :bordered="false" size="small" />
        </div>

        <div class="sbom-block">
            <h6>SBOM profiles defined at perspectives and components</h6>
            <p class="text-muted">Overrides are edited on the component's TEA settings tab or in the perspective's TEA dialog.</p>
            <n-data-table data-testid="sbom-other-rows" :columns="otherColumns" :data="otherRows" :row-key="otherRowKey"
                :pagination="false" :bordered="false" size="small" />
        </div>

        <div v-if="regulatoryPackagesAvailable(installationType)" class="sbom-block">
            <h6>Regulatory package settings</h6>
            <PackageSettingsEditor
                ref="orgPackageEditor"
                :org-uuid="orgUuid"
                scope="ORGANIZATION"
                :is-writable="isOrgAdmin"
                :installation-type="installationType"
                :names="names" />
        </div>

        <n-modal v-model:show="showEditor" preset="dialog" :show-icon="false" style="width: 900px;">
            <n-card size="huge" :bordered="false" role="dialog" aria-modal="true"
                :title="editingName ? 'SBOM profile' : 'Create SBOM profile'">
                <SbomProfileEditor
                    v-if="showEditor"
                    :org-uuid="orgUuid"
                    scope="ORGANIZATION"
                    :name="editingName"
                    :is-writable="isOrgAdmin"
                    :installation-type="installationType"
                    :names="names"
                    @saved="onSaved"
                    @removed="onRemoved"
                    @cancel="showEditor = false" />
            </n-card>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
// The organization Profiles tab (task TEA-30, design 3.3.3): the organization's SBOM profile
// catalog with create, edit and delete; the profiles stored at perspectives and components, read
// only here; and the organization's regulatory package settings (Pro). Every change of the catalog
// runs afterCatalogChange(), which reads the catalog again and reloads the package editor's picker.
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NCard, NDataTable, NModal, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import profilesQueries from '@/utils/profilesQueries'
import commonFunctions from '@/utils/commonFunctions'
import { TeaNamedObject, teaRowObject } from '@/utils/teaProfile'
import { sbomCatalogRows, sbomValueText } from '@/utils/sbomProfile'
import { regulatoryPackagesAvailable } from '@/utils/packageSettings'
import { deleteSbomProfileConfirmed } from '@/utils/sbomProfileDelete'
import SbomProfileEditor from './SbomProfileEditor.vue'
import PackageSettingsEditor from './PackageSettingsEditor.vue'

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
const catalog = ref<any[]>([])
const stored = ref<any[]>([])
const ofOrg = ref<any[]>([])
const loadFailed = ref(false)
const showEditor = ref(false)
const editingName = ref<string | null>(null)
const orgPackageEditor = ref<any>(null)

// Components and products the lists leave out (archived ones), read one by one after the rows arrive.
const readObjects = ref<Record<string, TeaNamedObject>>({})

const objects = computed((): Record<string, TeaNamedObject> => {
    const out: Record<string, TeaNamedObject> = { ...readObjects.value }
    for (const c of props.components) out[c.uuid] = { name: c.name, status: c.status }
    for (const p of props.perspectives) out[p.uuid] = { name: p.name }
    return out
})

const names = computed((): Record<string, string> => {
    const out: Record<string, string> = {}
    for (const p of props.perspectives) if (p?.uuid) out[p.uuid] = p.name
    return out
})

const catalogRows = computed(() => sbomCatalogRows(catalog.value, stored.value, names.value))
const otherRows = computed(() => ofOrg.value.filter((r: any) => r.scope !== 'ORGANIZATION')
    .map((r: any) => ({ ...r, objectLabel: teaRowObject(r, objects.value) })))

const rowKey = (r: any) => r.name
const otherRowKey = (r: any) => r.uuid ?? (r.scope + ':' + r.object + ':' + r.name)

const recipeColumn = (key: string, title: string) =>
    ({ key, title, render: (r: any) => sbomValueText(key, r.profile?.[key]) })

const catalogColumns = computed(() => [
    { key: 'name', title: 'Name' },
    { key: 'description', title: 'Description', render: (r: any) => r.profile?.description ?? '' },
    { key: 'source', title: 'Source', render: (r: any) => r.storedHere ? 'stored' : r.sourceLabel },
    { key: 'revision', title: 'Revision', render: (r: any) => r.storedHere && r.revision != null ? String(r.revision) : '' },
    recipeColumn('dependencyDepth', 'Dependency depth'),
    recipeColumn('optionalDependencies', 'Optional dependencies'),
    recipeColumn('structure', 'Structure'),
    recipeColumn('sources', 'Sources'),
    recipeColumn('excludedCoverage', 'Excluded coverage'),
    recipeColumn('supportMetadata', 'Support metadata'),
    recipeColumn('internalMetadata', 'Internal metadata'),
    {
        key: 'actions',
        title: 'Actions',
        render: (r: any) => props.isOrgAdmin ? h('span', { class: 'sbom-actions' }, [
            h(NButton, { size: 'tiny', 'data-testid': 'sbom-edit', onClick: () => openEditor(r.name) }, () => 'Edit'),
            r.storedHere
                ? h(NButton, { size: 'tiny', type: 'error', 'data-testid': 'sbom-delete-row', style: 'margin-left: 6px;',
                    onClick: () => deleteRow(r.name) }, () => 'Delete')
                : null,
        ]) : null,
    },
])

const otherColumns = [
    { key: 'scope', title: 'Scope' },
    { key: 'object', title: 'Object', render: (r: any) => r.objectLabel.archived
        ? [r.objectLabel.name, ' ', h(NTag, { size: 'small', type: 'warning', 'data-testid': 'sbom-archived-tag' }, () => 'archived')]
        : r.objectLabel.name },
    { key: 'name', title: 'Name' },
    { key: 'mode', title: 'Mode', render: (r: any) => r.mode ?? '' },
    { key: 'revision', title: 'Revision', render: (r: any) => r.revision != null ? String(r.revision) : '' },
]

async function reload (): Promise<boolean> {
    try {
        const [c, s, o]: any[] = await Promise.all([
            graphqlClient.query({ query: profilesQueries.SbomProfileCatalogGql,
                variables: { org: props.orgUuid, scope: 'ORGANIZATION', object: null }, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: profilesQueries.SbomProfilesGql,
                variables: { org: props.orgUuid, scope: 'ORGANIZATION', object: null }, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: profilesQueries.SbomProfilesOfOrgGql,
                variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' }),
        ])
        catalog.value = c.data.sbomProfileCatalog ?? []
        stored.value = s.data.sbomProfiles ?? []
        ofOrg.value = o.data.sbomProfilesOfOrg ?? []
        loadFailed.value = false
    } catch (err: any) {
        console.error(err)
        catalog.value = []
        stored.value = []
        ofOrg.value = []
        loadFailed.value = true
        notification.error({ content: commonFunctions.parseGraphQLError(err.message), duration: 5000 })
        return false
    }
    await readUnlistedComponents()
    return true
}

/** After every successful write of the catalog: the reads again, then the package editor's picker. */
async function afterCatalogChange () {
    await reload()
    orgPackageEditor.value?.reloadCatalog()
}

/** Names every COMPONENT-scope row whose object is in no list (archived) with one read each. */
async function readUnlistedComponents () {
    const unlisted = [...new Set(ofOrg.value.filter((r: any) => r.scope === 'COMPONENT' && r.object
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

function openEditor (name: string | null) {
    editingName.value = name
    showEditor.value = true
}

async function onSaved (profile: any) {
    if (!editingName.value && profile?.name) editingName.value = profile.name
    await afterCatalogChange()
}

async function onRemoved () {
    showEditor.value = false
    await afterCatalogChange()
}

async function deleteRow (name: string) {
    const outcome = await deleteSbomProfileConfirmed(graphqlClient, { orgUuid: props.orgUuid, scope: 'ORGANIZATION',
        objectUuid: null, name, scopeWord: 'organization', installationType: props.installationType })
    if (outcome === 'deleted') await afterCatalogChange()
}

onMounted(reload)
watch(() => props.orgUuid, reload)

defineExpose({ reload, afterCatalogChange })
</script>

<style scoped>
.sbom-block {
    margin-bottom: 1.5rem;
}
</style>
