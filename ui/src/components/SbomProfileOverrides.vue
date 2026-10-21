<template>
    <div class="sbom-profile-overrides">
        <div v-if="loadFailed">
            <n-button size="small" data-testid="sbom-retry" @click="reload">Retry</n-button>
        </div>
        <n-button v-else-if="isWritable" size="small" class="mb-2" data-testid="sbom-define" @click="openEditor(null)">
            Define a new name here
        </n-button>
        <n-data-table data-testid="sbom-override-rows" :columns="columns" :data="rows" :row-key="rowKey"
            :pagination="false" :bordered="false" size="small" />

        <n-modal v-model:show="showEditor" preset="dialog" :show-icon="false" style="width: 900px;">
            <n-card size="huge" :bordered="false" role="dialog" aria-modal="true"
                :title="editingName ? 'SBOM profile override' : 'Define an SBOM profile here'">
                <SbomProfileEditor
                    v-if="showEditor"
                    :org-uuid="orgUuid"
                    :scope="scope"
                    :object-uuid="objectUuid"
                    :object-name="objectName"
                    :object-is-product="objectIsProduct"
                    :name="editingName"
                    :is-writable="isWritable"
                    :perspective-options="perspectiveOptions"
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
// Per-scope SBOM profile overrides (task TEA-30, design 3.3.4): every name the catalog offers at a
// perspective or a component, its effective resolution here, and whether this scope stores its own
// definition. An override is edited in SbomProfileEditor, pre-filled from the effective parent.
// After every successful write here, once the two reads ran again, the panel emits `changed` so the
// host can reload the catalogs of the TEA and package editors beside it.
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NCard, NDataTable, NModal, NTag, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import commonFunctions from '@/utils/commonFunctions'
import { Option, sbomCatalogRows, sbomEffectiveLine, sbomScopeWord } from '@/utils/sbomProfile'
import { deleteSbomProfileConfirmed } from '@/utils/sbomProfileDelete'
import SbomProfileEditor from './SbomProfileEditor.vue'

const props = withDefaults(defineProps<{
    orgUuid: string
    scope: 'PERSPECTIVE' | 'COMPONENT'
    objectUuid: string
    objectName?: string | null
    objectIsProduct?: boolean
    isWritable?: boolean
    perspectiveOptions?: Option[]
    installationType?: string
    names?: Record<string, string>
}>(), {
    objectName: null,
    objectIsProduct: false,
    isWritable: false,
    perspectiveOptions: () => [],
    installationType: 'OSS',
    names: () => ({}),
})

const emit = defineEmits<{ (e: 'changed'): void }>()

const notification = useNotification()
const catalog = ref<any[]>([])
const stored = ref<any[]>([])
const loadFailed = ref(false)
const showEditor = ref(false)
const editingName = ref<string | null>(null)

const allNames = computed((): Record<string, string> => {
    const out: Record<string, string> = { ...props.names }
    for (const o of props.perspectiveOptions) out[o.value] = o.label
    return out
})
const rows = computed(() => sbomCatalogRows(catalog.value, stored.value, allNames.value))
const scopeWord = computed(() => sbomScopeWord(props.scope, props.objectIsProduct))
const rowKey = (r: any) => r.name

const columns = computed(() => [
    { key: 'name', title: 'Name' },
    {
        key: 'effective',
        title: 'Effective here',
        render: (r: any) => r.status === 'CONFLICT'
            ? h('span', {}, [
                h(NTag, { size: 'small', type: 'warning', 'data-testid': 'sbom-conflict-tag' }, () => 'conflict'),
                ' ', sbomEffectiveLine(r.resolution, allNames.value),
                h('div', { class: 'text-muted' }, 'Choose Override here, or Follow one of the perspectives.'),
            ])
            : sbomEffectiveLine(r.resolution, allNames.value),
    },
    {
        key: 'storedHere',
        title: 'Stored here',
        render: (r: any) => r.storedHere
            ? h(NTag, { size: 'small', type: 'info', 'data-testid': 'sbom-override-tag' }, () => 'override')
            : '',
    },
    { key: 'revision', title: 'Revision', render: (r: any) => r.revision != null ? String(r.revision) : '' },
    {
        key: 'actions',
        title: 'Actions',
        render: (r: any) => props.isWritable ? h('span', {}, [
            h(NButton, { size: 'tiny', 'data-testid': 'sbom-override', onClick: () => openEditor(r.name) },
                () => r.storedHere ? 'Edit override' : 'Override'),
            r.storedHere
                ? h(NButton, { size: 'tiny', type: 'error', 'data-testid': 'sbom-remove-override', style: 'margin-left: 6px;',
                    onClick: () => removeOverride(r.name) }, () => 'Remove override')
                : null,
        ]) : null,
    },
])

async function reload (): Promise<boolean> {
    const variables = { org: props.orgUuid, scope: props.scope, object: props.objectUuid }
    try {
        const [c, s]: any[] = await Promise.all([
            graphqlClient.query({ query: profilesQueries.SbomProfileCatalogGql, variables, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: profilesQueries.SbomProfilesGql, variables, fetchPolicy: 'no-cache' }),
        ])
        catalog.value = c.data.sbomProfileCatalog ?? []
        stored.value = s.data.sbomProfiles ?? []
        loadFailed.value = false
        return true
    } catch (err: any) {
        console.error(err)
        catalog.value = []
        stored.value = []
        loadFailed.value = true
        notification.error({ content: commonFunctions.parseGraphQLError(err.message), duration: 5000 })
        return false
    }
}

async function afterWrite () {
    await reload()
    emit('changed')
}

function openEditor (name: string | null) {
    editingName.value = name
    showEditor.value = true
}

async function onSaved (profile: any) {
    if (!editingName.value && profile?.name) editingName.value = profile.name
    await afterWrite()
}

async function onRemoved () {
    showEditor.value = false
    await afterWrite()
}

async function removeOverride (name: string) {
    const outcome = await deleteSbomProfileConfirmed(graphqlClient, { orgUuid: props.orgUuid, scope: props.scope,
        objectUuid: props.objectUuid, name, scopeWord: scopeWord.value, installationType: props.installationType })
    if (outcome === 'deleted') await afterWrite()
}

onMounted(reload)
watch(() => [props.orgUuid, props.scope, props.objectUuid], reload)

defineExpose({ reload })
</script>
