<template>
    <div class="distWrapper">
        <!-- Clients column -->
        <div class="distColumn">
            <h4 v-if="organization">Clients of {{ organization.name }}</h4>
            <n-icon v-if="isWritable" @click="openClientModal()" class="icons clickable" title="Add Client" size="24"><CirclePlus /></n-icon>
            <n-data-table :columns="clientColumns" :data="clients" :row-class-name="clientRowClass" :row-props="clientRowProps" size="small" />
        </div>

        <!-- Sites column -->
        <div class="distColumn">
            <template v-if="selectedClient">
                <h4>
                    {{ selectedClient.name }}
                    <n-tag size="small" :type="domainTagType" style="margin-left: 6px;">{{ selectedClient.domain || 'GENERIC' }}</n-tag>
                </h4>
                <div v-if="selectedClient.contact" class="contactLine">{{ formatContact(selectedClient.contact) }}</div>
                <n-space size="small" style="margin: 4px 0;">
                    <n-button v-if="isWritable" size="tiny" @click="openClientModal(selectedClient)">Edit</n-button>
                    <n-popconfirm v-if="isWritable" @positive-click="deleteClient(selectedClient)">
                        <template #trigger><n-button size="tiny" type="error">Delete</n-button></template>
                        Delete client {{ selectedClient.name }} and all its sites?
                    </n-popconfirm>
                </n-space>
                <h4>Sites</h4>
                <n-icon v-if="isWritable" @click="openSiteModal()" class="icons clickable" title="Add Site" size="22"><CirclePlus /></n-icon>
                <n-data-table :columns="siteColumns" :data="sites" :row-class-name="siteRowClass" :row-props="siteRowProps" size="small" />
            </template>
            <div v-else class="placeholder">Select a client to view its sites.</div>
        </div>

        <!-- Shipments + Devices column -->
        <div class="distColumn">
            <template v-if="selectedSite">
                <h4>{{ selectedSite.name }}</h4>
                <div v-if="selectedSite.contact" class="contactLine">{{ formatContact(selectedSite.contact) }}</div>
                <n-space size="small" style="margin: 4px 0;">
                    <n-button v-if="isWritable" size="tiny" @click="openSiteModal(selectedSite)">Edit</n-button>
                    <n-popconfirm v-if="isWritable" @positive-click="deleteSite(selectedSite)">
                        <template #trigger><n-button size="tiny" type="error">Delete</n-button></template>
                        Delete site {{ selectedSite.name }}?
                    </n-popconfirm>
                </n-space>
                <h4>{{ terms.shipments }} <span class="subtle">({{ terms.installedBase }})</span></h4>
                <n-icon v-if="isWritable" @click="openShipModal()" class="icons clickable" :title="terms.shipAction" size="22"><CirclePlus /></n-icon>
                <n-data-table :columns="shipmentColumns" :data="shipments" :row-class-name="shipmentRowClass" :row-props="shipmentRowProps" size="small" />

                <!-- Devices of selected shipment -->
                <template v-if="selectedShipment">
                    <h4 style="margin-top: 12px;">Devices <span class="subtle">of selected {{ terms.shipment.toLowerCase() }}</span></h4>
                    <n-icon v-if="isWritable" @click="openDeviceModal()" class="icons clickable" title="Add device" size="22"><CirclePlus /></n-icon>
                    <n-data-table :columns="deviceColumns" :data="devices" :row-props="deviceRowProps" size="small" />
                </template>
            </template>
            <div v-else class="placeholder">Select a site to view {{ terms.shipments.toLowerCase() }}.</div>
        </div>

        <!-- Client modal -->
        <n-modal v-model:show="showClientModal" preset="dialog" :show-icon="false" :title="clientForm.uuid ? 'Edit Client' : 'Add Client'">
            <n-form>
                <n-form-item label="Name"><n-input v-model:value="clientForm.name" placeholder="Client name" /></n-form-item>
                <n-form-item label="Framing (domain)"><n-select v-model:value="clientForm.domain" :options="domainOptions" /></n-form-item>
                <n-form-item label="Address"><n-input v-model:value="clientForm.contact.address" /></n-form-item>
                <n-form-item label="Phone"><n-input v-model:value="clientForm.contact.phone" /></n-form-item>
                <n-form-item label="Email"><n-input v-model:value="clientForm.contact.email" /></n-form-item>
                <n-form-item label="Notes"><n-input v-model:value="clientForm.notes" type="textarea" /></n-form-item>
            </n-form>
            <template #action><n-button type="primary" @click="saveClient">Save</n-button></template>
        </n-modal>

        <!-- Site modal -->
        <n-modal v-model:show="showSiteModal" preset="dialog" :show-icon="false" :title="siteForm.uuid ? 'Edit Site' : 'Add Site'">
            <n-form>
                <n-form-item label="Name"><n-input v-model:value="siteForm.name" placeholder="Site name" /></n-form-item>
                <n-form-item label="Address"><n-input v-model:value="siteForm.contact.address" /></n-form-item>
                <n-form-item label="Phone"><n-input v-model:value="siteForm.contact.phone" /></n-form-item>
                <n-form-item label="Email"><n-input v-model:value="siteForm.contact.email" /></n-form-item>
                <n-form-item label="Notes"><n-input v-model:value="siteForm.notes" type="textarea" /></n-form-item>
            </n-form>
            <template #action><n-button type="primary" @click="saveSite">Save</n-button></template>
        </n-modal>

        <!-- Ship modal -->
        <n-modal v-model:show="showShipModal" preset="dialog" :show-icon="false" :title="editingShipmentUuid ? 'Edit Shipment' : terms.shipAction" style="width: 640px;">
            <n-form>
                <n-form-item v-if="!editingShipmentUuid" label="Product">
                    <n-select v-model:value="shipProductUuid" filterable :options="products" placeholder="Pick a product" @update:value="onShipProductChange" />
                </n-form-item>
                <n-form-item v-if="!editingShipmentUuid" label="Feature set">
                    <n-select v-model:value="shipForm.featureSet" filterable :options="featureSetOptions" :disabled="!shipProductUuid" placeholder="Pick a feature set" @update:value="onFeatureSetChange" />
                </n-form-item>
                <div v-if="editingShipmentUuid" class="identityBox">
                    Editing shipment of <strong>{{ releaseLabel(shipForm.release) || shortUuid(shipForm.release) }}</strong> — product / feature set are fixed; adjust release, dates, quantity, identifiers and choices.
                </div>
                <div v-if="resolvedIdentity" class="identityBox">
                    {{ terms.deviceIdentity }}:
                    <n-tag size="small" :type="resolvedIdentity.deviceClass === 'NONE' ? 'default' : 'info'">{{ resolvedIdentity.deviceClass }}</n-tag>
                    <span v-if="resolvedIdentity.udiDi"> &middot; UDI-DI: <code>{{ resolvedIdentity.udiDi }}</code></span>
                </div>
                <n-form-item label="Release">
                    <n-select v-model:value="shipForm.release" filterable :options="shipReleases" :disabled="!shipForm.featureSet" placeholder="Pick a release / version" @update:value="onShipReleaseChange" />
                </n-form-item>
                <n-form-item v-if="shipDeliverables.length" label="Deliverable / lot">
                    <n-select v-model:value="shipForm.deliverable" filterable clearable :options="shipDeliverables" placeholder="Optional — pick a shipped deliverable / lot" />
                </n-form-item>
                <template v-if="shipChoices.length">
                    <n-divider style="margin: 6px 0;">Component choices (fixed per lot)</n-divider>
                    <n-form-item v-for="cv in shipChoices" :key="cv.choice.bomRef"
                        :label="`${cv.choice.name}${cv.choice.boardLocation ? ' @ ' + cv.choice.boardLocation : ''}${cv.choice.quantity ? ' × ' + cv.choice.quantity : ''} [${cv.choice.operator || 'XOR'}]`">
                        <span v-if="(cv.choice.operator || 'XOR').toUpperCase() === 'AND'" class="subtle">
                            All options installed: {{ cv.options.map((o: any) => o.name).join(', ') }}
                        </span>
                        <n-select v-else
                            v-model:value="shipChoiceSelections[cv.choice.bomRef]"
                            :clearable="(cv.choice.operator || '').toUpperCase() === 'OPTIONAL'"
                            :options="choiceOptionSelect(cv)"
                            :placeholder="(cv.choice.operator || '').toUpperCase() === 'OPTIONAL' ? 'Optional — leave empty if not populated' : 'Required — pick the populated option'" />
                    </n-form-item>
                </template>
                <n-form-item label="Ship date"><n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="shipForm.shipDate" value-format="yyyy-MM-dd" placeholder="defaults to today" /></n-form-item>
                <n-form-item label="Quantity"><n-input-number v-model:value="shipForm.quantity" :min="1" /></n-form-item>
                <n-form-item label="Batch identifiers">
                    <n-dynamic-input v-model:value="shipForm.identifiers" :on-create="onCreateBatchId">
                        <template #create-button-default>Add identifier</template>
                        <template #default="{ value }">
                            <n-select style="width: 150px;" v-model:value="value.idType" :options="idTypeOptions" />
                            <n-input v-model:value="value.idValue" placeholder="value" />
                        </template>
                    </n-dynamic-input>
                </n-form-item>
                <n-form-item label="Manufacture date"><n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="shipForm.manufactureDate" value-format="yyyy-MM-dd" /></n-form-item>
                <n-form-item label="Expiry date"><n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="shipForm.expiryDate" value-format="yyyy-MM-dd" /></n-form-item>
            </n-form>
            <template #action><n-button type="primary" @click="shipProduct">{{ editingShipmentUuid ? 'Save changes' : terms.shipAction }}</n-button></template>
        </n-modal>

        <!-- Device add modal -->
        <n-modal v-model:show="showDeviceModal" preset="dialog" :show-icon="false" title="Add device" style="width: 560px;">
            <div class="identityBox" v-if="selectedShipment">
                Adding to {{ terms.shipment.toLowerCase() }} <strong>{{ releaseLabel(selectedShipment.release) || shortUuid(selectedShipment.release) }}</strong>
                · shipped {{ selectedShipment.shipDate }} — the unit inherits this lot's component-choice selections.
            </div>
            <n-form>
                <n-form-item label="Unit identifiers">
                    <n-dynamic-input v-model:value="deviceForm.identifiers" :on-create="onCreateUnitId">
                        <template #create-button-default>Add identifier</template>
                        <template #default="{ value }">
                            <n-select style="width: 150px;" v-model:value="value.idType" :options="idTypeOptions" />
                            <n-input v-model:value="value.idValue" placeholder="value (e.g. serial)" />
                        </template>
                    </n-dynamic-input>
                </n-form-item>
                <n-form-item label="Notes"><n-input v-model:value="deviceForm.notes" type="textarea" /></n-form-item>
            </n-form>
            <template #action><n-button type="primary" @click="saveDevice">Save</n-button></template>
        </n-modal>

    </div>
</template>

<script lang="ts">
export default { name: 'DistributionOfOrg' }
</script>
<script lang="ts" setup>
import { ref, Ref, computed, ComputedRef, reactive, h, onMounted, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NDataTable, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NButton, NIcon, NTag, NSpace, NDivider, NDynamicInput, NTooltip, NPopconfirm, NDatePicker, useNotification, NotificationType } from 'naive-ui'
import { CirclePlus, InfoCircle, Edit as EditIcon } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { termsFor, DISTRIBUTION_DOMAIN_OPTIONS } from '@/utils/distributionTerms'

const route = useRoute()
const router = useRouter()
const store = useStore()
const notification = useNotification()

const orguuid: Ref<string> = ref(route.params.orguuid ? route.params.orguuid.toString() : store.getters.myorg.value)
const myUser = store.getters.myuser
const isWritable: boolean = commonFunctions.isWritable(orguuid.value, myUser, 'ORGANIZATION')
const organization: ComputedRef<any> = computed((): any => store.getters.orgById(orguuid.value))

const clients: Ref<any[]> = ref([])
const sites: Ref<any[]> = ref([])
const shipments: Ref<any[]> = ref([])
const devices: Ref<any[]> = ref([])

const selectedClientUuid: Ref<string> = ref(route.params.clientuuid ? route.params.clientuuid.toString() : '')
const selectedSiteUuid: Ref<string> = ref(route.params.siteuuid ? route.params.siteuuid.toString() : '')
const selectedShipmentUuid: Ref<string> = ref('')

const selectedClient: ComputedRef<any> = computed(() => clients.value.find(c => c.uuid === selectedClientUuid.value))
const selectedSite: ComputedRef<any> = computed(() => sites.value.find(s => s.uuid === selectedSiteUuid.value))
const selectedShipment: ComputedRef<any> = computed(() => shipments.value.find(s => s.uuid === selectedShipmentUuid.value))
const terms = computed(() => termsFor(selectedClient.value?.domain))
const domainTagType = computed(() => selectedClient.value?.domain === 'DEFENSE' ? 'success' : (selectedClient.value?.domain === 'MEDICAL' ? 'info' : 'default'))

const domainOptions = DISTRIBUTION_DOMAIN_OPTIONS
const idTypeOptions = ['UDI', 'UDI_DI', 'UDI_PI', 'SERIAL', 'LOT'].map(v => ({ label: v.replace('_', '-'), value: v }))

const showClientModal = ref(false)
const showSiteModal = ref(false)
const showShipModal = ref(false)
const showDeviceModal = ref(false)
const resolvedIdentity: Ref<any> = ref(null)

// Selector-driven pickers (Product -> Feature set -> Release), reusing the
// component/branch/release resolution the product-release editor uses.
const shipProductUuid: Ref<string> = ref('')
const shipReleases: Ref<any[]> = ref([])
const shipDeliverables: Ref<any[]> = ref([])
const deviceReleases: Ref<any[]> = ref([])
const products = computed(() => (store.getters.productsOfOrg(orguuid.value) || []).map((p: any) => ({ label: p.name, value: p.uuid })))
const featureSetOptions = computed(() => (shipProductUuid.value ? (store.getters.branchesOfComponent(shipProductUuid.value) || []) : []).map((b: any) => ({ label: b.type ? `${b.name} [${b.type}]` : b.name, value: b.uuid })))

async function loadReleasesForBranch (branchUuid: string): Promise<any[]> {
    if (!branchUuid) return []
    const resp: any = await graphqlClient.query({
        query: gql`query distReleases($b: ID!, $n: Int) { releases(branchFilter: $b, numRecords: $n) { uuid version createdDate } }`,
        variables: { b: branchUuid, n: 200 }, fetchPolicy: 'no-cache'
    })
    return (resp.data.releases || []).map((r: any) => ({ label: `${r.version} · ${new Date(r.createdDate).toLocaleDateString()}`, value: r.uuid }))
}
async function onShipProductChange (uuid: string) {
    shipForm.featureSet = ''; shipForm.release = ''; shipReleases.value = []; resolvedIdentity.value = null
    await store.dispatch('fetchBranches', { componentId: uuid, forceRefresh: false })
}
async function onFeatureSetChange (branchUuid: string) {
    shipForm.featureSet = branchUuid; shipForm.release = ''; shipForm.deliverable = null; shipDeliverables.value = []
    shipReleases.value = await loadReleasesForBranch(branchUuid)
    await resolveIdentity()
}
const shipChoices: Ref<any[]> = ref([])
const shipChoiceSelections = reactive<Record<string, string | null>>({})
function choiceOptionSelect (cv: any) {
    return (cv.options || []).map((o: any) => ({
        label: `${o.name}${(o.partNumbers || []).length ? ' · ' + o.partNumbers.join(', ') : ''}${o.manufacturer ? ' · ' + o.manufacturer : ''}`,
        value: o.bomRef
    }))
}
async function loadShipChoices (releaseUuid: string) {
    shipChoices.value = []
    Object.keys(shipChoiceSelections).forEach(k => delete shipChoiceSelections[k])
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query releaseComponentChoices($u: ID!) { releaseComponentChoices(releaseUuid: $u) {
                choice { bomRef name operator boardLocation quantity }
                options { bomRef name partNumbers manufacturer boardLocation quantity }
            } }`,
            variables: { u: releaseUuid }, fetchPolicy: 'no-cache'
        })
        shipChoices.value = (resp.data.releaseComponentChoices || []).filter((cv: any) => cv.choice?.bomRef)
    } catch (e) { /* hardware-less releases simply have no choices */ }
}
async function onShipReleaseChange (releaseUuid: string) {
    shipForm.deliverable = null; shipDeliverables.value = []
    shipChoices.value = []; Object.keys(shipChoiceSelections).forEach(k => delete shipChoiceSelections[k])
    if (!releaseUuid) return
    loadShipChoices(releaseUuid)
    const resp: any = await graphqlClient.query({
        query: gql`query relDeliverables($u: ID!) { release(releaseUuid: $u) { variantDetails { outboundDeliverableDetails { uuid displayIdentifier rearmIdentifiers { idType idValue } quantity } } } }`,
        variables: { u: releaseUuid }, fetchPolicy: 'no-cache'
    })
    const variants = resp.data.release?.variantDetails || []
    const seen = new Set<string>()
    const opts: any[] = []
    for (const v of variants) {
        for (const d of (v.outboundDeliverableDetails || [])) {
            if (!d || seen.has(d.uuid)) continue
            seen.add(d.uuid)
            const lot = (d.rearmIdentifiers || []).find((i: any) => i.idType === 'LOT')
            const parts = [d.displayIdentifier || shortUuid(d.uuid)]
            if (lot) parts.push(`lot ${lot.idValue}`)
            if (d.quantity) parts.push(`×${d.quantity}`)
            opts.push({ label: parts.join(' · '), value: d.uuid })
        }
    }
    shipDeliverables.value = opts
}

const emptyContact = () => ({ address: '', phone: '', email: '' })
const clientForm = reactive<any>({ uuid: '', name: '', domain: 'GENERIC', contact: emptyContact(), notes: '' })
const siteForm = reactive<any>({ uuid: '', name: '', contact: emptyContact(), notes: '' })
const shipForm = reactive<any>({ featureSet: '', release: '', deliverable: null, shipDate: null, quantity: 1, identifiers: [], manufactureDate: null, expiryDate: null })
const editingShipmentUuid: Ref<string> = ref('')

// Resolved display info per release uuid (product / feature set / version)
// so shipment rows show names instead of uuids.
const releaseInfoMap: Ref<Record<string, any>> = ref({})
async function resolveReleaseInfos (releaseUuids: string[]) {
    const missing = [...new Set(releaseUuids)].filter(u => u && !releaseInfoMap.value[u])
    await Promise.all(missing.map(async (u) => {
        try {
            const resp: any = await graphqlClient.query({
                query: gql`query relInfo($u: ID!) { release(releaseUuid: $u) { uuid version componentDetails { uuid name } branchDetails { uuid name } } }`,
                variables: { u }, fetchPolicy: 'cache-first'
            })
            const r = resp.data.release
            if (r) releaseInfoMap.value[u] = { version: r.version, productName: r.componentDetails?.name, productUuid: r.componentDetails?.uuid, fsName: r.branchDetails?.name, fsUuid: r.branchDetails?.uuid }
        } catch (e) { /* ignore */ }
    }))
}
function releaseLabel (u: string): string {
    const i = releaseInfoMap.value[u]
    return i ? `${i.productName} — ${i.fsName} — ${i.version}` : ''
}
const deviceForm = reactive<any>({ identifiers: [], notes: '' })


const notify = (type: NotificationType, title: string, content: string) =>
    notification[type]({ content, meta: title, duration: 3500, keepAliveOnHover: true })

const formatContact = (c: any) => [c?.address, c?.phone, c?.email].filter(Boolean).join(' · ')
const summarizeIds = (ids: any[]) => (ids || []).map(i => `${i.idType}:${i.idValue}`).join(', ')
const shortUuid = (u: string) => u ? u.substring(0, 8) : ''
const onCreateBatchId = () => ({ idType: 'LOT', idValue: '' })
const onCreateUnitId = () => ({ idType: 'SERIAL', idValue: '' })

// ---- GraphQL ----
const CLIENT_FIELDS = 'uuid org name domain contact { address phone email } notes'
const SITE_FIELDS = 'uuid org client name contact { address phone email } notes'
const SHIP_FIELDS = 'uuid org site featureSet release deliverable shipDate quantity manufactureDate expiryDate notes identifiers { idType idValue } choiceResolutions { choiceRef selectedRefs }'
const DEVICE_FIELDS = 'uuid org shippedProduct site versionDrift notes identifiers { idType idValue } plan { expectedRelease } actual { reportedRelease reportedAt source } tracking { receivedDate patientId disposition dispositionDate }'

async function loadClients () {
    const resp: any = await graphqlClient.query({ query: gql`query clientsOfOrg($orgUuid: ID!) { clientsOfOrg(orgUuid: $orgUuid) { ${CLIENT_FIELDS} } }`, variables: { orgUuid: orguuid.value }, fetchPolicy: 'no-cache' })
    clients.value = resp.data.clientsOfOrg || []
}
async function loadSites (clientUuid: string) {
    if (!clientUuid) { sites.value = []; return }
    const resp: any = await graphqlClient.query({ query: gql`query sitesOfClient($clientUuid: ID!) { sitesOfClient(clientUuid: $clientUuid) { ${SITE_FIELDS} } }`, variables: { clientUuid }, fetchPolicy: 'no-cache' })
    sites.value = resp.data.sitesOfClient || []
}
async function loadShipments (siteUuid: string) {
    if (!siteUuid) { shipments.value = []; return }
    const resp: any = await graphqlClient.query({ query: gql`query shippedProductsOfSite($siteUuid: ID!) { shippedProductsOfSite(siteUuid: $siteUuid) { ${SHIP_FIELDS} } }`, variables: { siteUuid }, fetchPolicy: 'no-cache' })
    shipments.value = resp.data.shippedProductsOfSite || []
    resolveReleaseInfos(shipments.value.map((s: any) => s.release))
}
async function loadDevices (shipUuid: string) {
    if (!shipUuid) { devices.value = []; return }
    const resp: any = await graphqlClient.query({ query: gql`query devicesOfShipment($u: ID!) { devicesOfShipment(shippedProductUuid: $u) { ${DEVICE_FIELDS} } }`, variables: { u: shipUuid }, fetchPolicy: 'no-cache' })
    devices.value = resp.data.devicesOfShipment || []
}
async function resolveIdentity () {
    resolvedIdentity.value = null
    if (!shipForm.featureSet) return
    try {
        const resp: any = await graphqlClient.query({ query: gql`query fsdi($fs: ID!, $org: ID!) { featureSetDeviceIdentity(featureSetUuid: $fs, orgUuid: $org) { deviceClass udiDi } }`, variables: { fs: shipForm.featureSet, org: orguuid.value }, fetchPolicy: 'no-cache' })
        resolvedIdentity.value = resp.data.featureSetDeviceIdentity
    } catch (e: any) { /* invalid uuid etc */ }
}

// ---- selection ----
function selectClient (uuid: string) { router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: uuid } }) }
function selectSite (uuid: string) { router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: selectedClientUuid.value, siteuuid: uuid } }) }
async function selectShipment (uuid: string) {
    selectedShipmentUuid.value = uuid
    await loadDevices(uuid)
    const ship = shipments.value.find(s => s.uuid === uuid)
    deviceReleases.value = ship ? await loadReleasesForBranch(ship.featureSet) : []
}

watch(() => route.params.clientuuid, async (v) => {
    selectedClientUuid.value = v ? v.toString() : ''
    selectedSiteUuid.value = ''
    selectedShipmentUuid.value = ''
    shipments.value = []; devices.value = []
    await loadSites(selectedClientUuid.value)
})
watch(() => route.params.siteuuid, async (v) => {
    selectedSiteUuid.value = v ? v.toString() : ''
    selectedShipmentUuid.value = ''; devices.value = []
    await loadShipments(selectedSiteUuid.value)
})

// ---- columns ----
const clientColumns = [
    { key: 'name', title: 'Name' },
    { key: 'domain', title: 'Framing', render: (r: any) => h(NTag, { size: 'small' }, { default: () => r.domain || 'GENERIC' }) }
]
const siteColumns = [{ key: 'name', title: 'Name' }]
const shipmentColumns = computed(() => [
    { key: 'shipDate', title: 'Date' },
    {
        key: 'release', title: 'Release',
        render: (r: any) => {
            const i = releaseInfoMap.value[r.release]
            if (!i) return shortUuid(r.release)
            const link = (text: string, to: any) => h('a', {
                class: 'shipLink',
                onClick: (e: Event) => { e.stopPropagation(); router.push(to) }
            }, text)
            return h('span', [
                link(i.productName, { name: 'ProductsOfOrg', params: { orguuid: orguuid.value, compuuid: i.productUuid } }),
                ' — ',
                link(i.fsName, { name: 'ProductsOfOrg', params: { orguuid: orguuid.value, compuuid: i.productUuid, branchuuid: i.fsUuid } }),
                ' — ',
                link(i.version, { name: 'ReleaseView', params: { uuid: r.release } })
            ])
        }
    },
    { key: 'quantity', title: 'Qty' },
    {
        key: 'info', title: '',
        render: (r: any) => {
            const facts: [string, any][] = [
                ['Batch ids', summarizeIds(r.identifiers)],
                ['Manufactured', r.manufactureDate],
                ['Expires', r.expiryDate],
                ['Lot choices', (r.choiceResolutions || []).map((c: any) => `${c.choiceRef} → ${(c.selectedRefs || []).join(', ') || '(none)'}`).join('; ')],
                ['Notes', r.notes]
            ].filter(([, v]) => v !== null && v !== undefined && v !== '') as [string, any][]
            return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 460px;' }, {
                trigger: () => h(NIcon, { size: 16, style: 'color: #909399; vertical-align: middle;', onClick: (e: Event) => e.stopPropagation() }, { default: () => h(InfoCircle) }),
                default: () => h('div', facts.length
                    ? facts.map(([k, v]) => h('div', { style: 'margin: 2px 0;' }, [h('strong', `${k}: `), String(v)]))
                    : ['No batch identifiers or extra facts'])
            })
        }
    },
    ...(isWritable ? [{
        key: 'edit', title: '',
        render: (r: any) => h(NIcon, {
            size: 16, style: 'cursor: pointer; vertical-align: middle;', title: 'Edit shipment',
            onClick: (e: Event) => { e.stopPropagation(); openShipModal(r) }
        }, { default: () => h(EditIcon) })
    }] : [])
])
const deviceColumns = computed(() => [
    { key: 'ids', title: 'Unit ids', render: (r: any) => summarizeIds(r.identifiers) || h('span', { class: 'subtle' }, '—') },
    {
        // Plan-vs-reported indicator: DRIFT when the unit's observed/reported
        // state diverges from its expected release; match when it agrees.
        key: 'drift', title: 'Drift',
        render: (r: any) => r.versionDrift
            ? h(NTag, { size: 'small', type: 'warning' }, { default: () => 'DRIFT' })
            : (r.actual && r.actual.reportedRelease ? h(NTag, { size: 'small', type: 'success' }, { default: () => 'match' }) : h('span', { class: 'subtle' }, '—'))
    },
    {
        key: 'info', title: '',
        render: (r: any) => {
            const relLabel = (u: string) => (deviceReleases.value.find((o: any) => o.value === u)?.label) || (u ? u.substring(0, 8) : null)
            const facts: [string, any][] = [
                ['Identifiers', summarizeIds(r.identifiers)],
                ['Expected release', relLabel(r.plan?.expectedRelease)],
                ['Reported release', relLabel(r.actual?.reportedRelease)],
                ['Report source', r.actual?.source],
                ['Drift', r.versionDrift ? 'yes' : 'no'],
                ['Notes', r.notes]
            ].filter(([, v]) => v !== null && v !== undefined && v !== '') as [string, any][]
            return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 420px;' }, {
                trigger: () => h(NIcon, { size: 16, style: 'color: #909399; vertical-align: middle;', onClick: (e: Event) => e.stopPropagation() }, { default: () => h(InfoCircle) }),
                default: () => h('div', [
                    ...facts.map(([k, v]) => h('div', { style: 'margin: 2px 0;' }, [h('strong', `${k}: `), String(v)])),
                    h('div', { style: 'margin-top: 4px; font-style: italic;' }, 'Click the row to open the device twin')
                ])
            })
        }
    }
])

const clientRowClass = (r: any) => r.uuid === selectedClientUuid.value ? 'selectedRow' : ''
const siteRowClass = (r: any) => r.uuid === selectedSiteUuid.value ? 'selectedRow' : ''
const shipmentRowClass = (r: any) => r.uuid === selectedShipmentUuid.value ? 'selectedRow' : ''
const clientRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => selectClient(r.uuid) })
const siteRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => selectSite(r.uuid) })
const shipmentRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => selectShipment(r.uuid) })
const deviceRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => router.push({ name: 'DeviceView', params: { deviceuuid: r.uuid } }) })

// ---- client/site mutations ----
function openClientModal (c?: any) {
    clientForm.uuid = c?.uuid || ''; clientForm.name = c?.name || ''; clientForm.domain = c?.domain || 'GENERIC'
    clientForm.contact = c?.contact ? { ...c.contact } : emptyContact(); clientForm.notes = c?.notes || ''
    showClientModal.value = true
}
async function saveClient () {
    if (!clientForm.name) { notify('warning', 'Missing', 'Client name is required'); return }
    const input: any = { org: orguuid.value, name: clientForm.name, domain: clientForm.domain, notes: clientForm.notes, contact: clientForm.contact }
    if (clientForm.uuid) input.uuid = clientForm.uuid
    await graphqlClient.mutate({ mutation: gql`mutation upsertClient($input: ClientInput!) { upsertClient(input: $input) { uuid } }`, variables: { input } })
    showClientModal.value = false; notify('success', 'Saved', `Client ${clientForm.name} saved`); await loadClients()
}
async function deleteClient (c: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteClient($uuid: ID!) { deleteClient(uuid: $uuid) }`, variables: { uuid: c.uuid } })
    notify('info', 'Deleted', `Client ${c.name} deleted`)
    if (selectedClientUuid.value === c.uuid) router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value } })
    await loadClients()
}
function openSiteModal (s?: any) {
    siteForm.uuid = s?.uuid || ''; siteForm.name = s?.name || ''
    siteForm.contact = s?.contact ? { ...s.contact } : emptyContact(); siteForm.notes = s?.notes || ''
    showSiteModal.value = true
}
async function saveSite () {
    if (!siteForm.name) { notify('warning', 'Missing', 'Site name is required'); return }
    const input: any = { org: orguuid.value, client: selectedClientUuid.value, name: siteForm.name, notes: siteForm.notes, contact: siteForm.contact }
    if (siteForm.uuid) input.uuid = siteForm.uuid
    await graphqlClient.mutate({ mutation: gql`mutation upsertSite($input: SiteInput!) { upsertSite(input: $input) { uuid } }`, variables: { input } })
    showSiteModal.value = false; notify('success', 'Saved', `Site ${siteForm.name} saved`); await loadSites(selectedClientUuid.value)
}
async function deleteSite (s: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteSite($uuid: ID!) { deleteSite(uuid: $uuid) }`, variables: { uuid: s.uuid } })
    notify('info', 'Deleted', `Site ${s.name} deleted`)
    if (selectedSiteUuid.value === s.uuid) router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: selectedClientUuid.value } })
    await loadSites(selectedClientUuid.value)
}

// ---- shipment ----
function cleanIds (ids: any[]) { return (ids || []).filter(i => i.idType && i.idValue) }
async function openShipModal (existing?: any) {
    shipForm.featureSet = ''; shipForm.release = ''; shipForm.deliverable = null; shipForm.shipDate = null; shipForm.quantity = 1
    shipForm.identifiers = []; shipForm.manufactureDate = null; shipForm.expiryDate = null
    shipProductUuid.value = ''; shipReleases.value = []; shipDeliverables.value = []
    shipChoices.value = []; Object.keys(shipChoiceSelections).forEach(k => delete shipChoiceSelections[k])
    resolvedIdentity.value = null
    editingShipmentUuid.value = existing?.uuid || ''
    if (existing) {
        shipForm.featureSet = existing.featureSet
        shipForm.release = existing.release
        shipForm.deliverable = existing.deliverable || null
        shipForm.shipDate = existing.shipDate || null
        shipForm.quantity = existing.quantity || 1
        shipForm.identifiers = (existing.identifiers || []).map((i: any) => ({ idType: i.idType, idValue: i.idValue }))
        shipForm.manufactureDate = existing.manufactureDate || null
        shipForm.expiryDate = existing.expiryDate || null
        shipReleases.value = await loadReleasesForBranch(existing.featureSet)
        await Promise.all([loadShipChoices(existing.release), resolveIdentity()])
        for (const cr of (existing.choiceResolutions || [])) {
            // XOR/OPTIONAL selects bind a single ref; AND slots are implicit
            shipChoiceSelections[cr.choiceRef] = (cr.selectedRefs || [])[0] || null
        }
    }
    showShipModal.value = true
}
async function shipProduct () {
    if (!shipForm.featureSet || !shipForm.release) { notify('warning', 'Missing', 'Feature set and release are required'); return }
    const input: any = { org: orguuid.value, site: selectedSiteUuid.value, featureSet: shipForm.featureSet, release: shipForm.release, quantity: shipForm.quantity, identifiers: cleanIds(shipForm.identifiers) }
    if (shipChoices.value.length) {
        const resolutions: any[] = []
        for (const cv of shipChoices.value) {
            const op = (cv.choice.operator || 'XOR').toUpperCase()
            const sel = shipChoiceSelections[cv.choice.bomRef]
            if (op === 'AND') {
                resolutions.push({ choiceRef: cv.choice.bomRef, selectedRefs: (cv.options || []).map((o: any) => o.bomRef).filter(Boolean) })
            } else if (op === 'OPTIONAL') {
                resolutions.push({ choiceRef: cv.choice.bomRef, selectedRefs: sel ? [sel] : [] })
            } else { // XOR
                if (!sel) { notify('warning', 'Unresolved choice', `Pick the populated option for "${cv.choice.name}" — a produced lot fixes all component choices`); return }
                resolutions.push({ choiceRef: cv.choice.bomRef, selectedRefs: [sel] })
            }
        }
        input.choiceResolutions = resolutions
    }
    if (shipForm.deliverable) input.deliverable = shipForm.deliverable
    if (shipForm.shipDate) input.shipDate = shipForm.shipDate
    if (shipForm.manufactureDate) input.manufactureDate = shipForm.manufactureDate
    if (shipForm.expiryDate) input.expiryDate = shipForm.expiryDate
    try {
        if (editingShipmentUuid.value) {
            await graphqlClient.mutate({
                mutation: gql`mutation updateShippedProduct($uuid: ID!, $input: ShipProductInput!) { updateShippedProduct(uuid: $uuid, input: $input) { uuid } }`,
                variables: { uuid: editingShipmentUuid.value, input }
            })
            notify('success', 'Saved', `${terms.value.shipment} updated`)
        } else {
            await graphqlClient.mutate({ mutation: gql`mutation shipProduct($input: ShipProductInput!) { shipProduct(input: $input) { uuid } }`, variables: { input } })
            notify('success', 'Saved', `${terms.value.shipment} recorded`)
        }
        showShipModal.value = false; editingShipmentUuid.value = ''
        await loadShipments(selectedSiteUuid.value)
    } catch (e: any) { notify('error', 'Failed', e.message || 'Could not record shipment') }
}
async function deleteShipment (r: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteShippedProduct($uuid: ID!) { deleteShippedProduct(uuid: $uuid) }`, variables: { uuid: r.uuid } })
    notify('info', 'Deleted', 'Record deleted'); await loadShipments(selectedSiteUuid.value)
}

// ---- device ----
function openDeviceModal () { deviceForm.identifiers = [{ idType: 'SERIAL', idValue: '' }]; deviceForm.notes = ''; showDeviceModal.value = true }
async function saveDevice () {
    const ids = cleanIds(deviceForm.identifiers)
    if (!ids.length) { notify('warning', 'Missing identifier', 'A device needs at least one unit identifier (e.g. SERIAL)'); return }
    // expected release intentionally NOT set here — the unit inherits its
    // shipment's release (service defaults the plan); field upgrades are
    // recorded later from the device twin page.
    const input: any = { org: orguuid.value, shippedProduct: selectedShipmentUuid.value, identifiers: ids, notes: deviceForm.notes }
    try {
        await graphqlClient.mutate({ mutation: gql`mutation upsertDevice($input: DeviceInput!) { upsertDevice(input: $input) { uuid } }`, variables: { input } })
        showDeviceModal.value = false; notify('success', 'Saved', 'Device added'); await loadDevices(selectedShipmentUuid.value)
    } catch (e: any) { notify('error', 'Failed', e.message || 'Could not add device') }
}

onMounted(async () => {
    store.dispatch('fetchProducts', orguuid.value)
    await loadClients()
    if (selectedClientUuid.value) await loadSites(selectedClientUuid.value)
    if (selectedSiteUuid.value) await loadShipments(selectedSiteUuid.value)
})
</script>

<style scoped lang="scss">
.distWrapper { display: grid; grid-template-columns: 270px 300px 1fr; grid-gap: 8px; }
.distColumn { border-right: 1px solid #edf2f3; padding-right: 8px; }
.contactLine { font-size: 12px; color: #666; margin-bottom: 4px; }
.placeholder { color: #999; padding-top: 24px; font-style: italic; }
.subtle { color: #999; font-size: 12px; }
.identityBox { background: #f4f8fb; border-radius: 6px; padding: 6px 8px; margin-bottom: 8px; font-size: 13px; }
.icons { margin: 4px; vertical-align: middle; }
.clickable { cursor: pointer; }
:deep(.selectedRow td) { background-color: #f1f1f1 !important; }
:deep(.shipLink) { color: #2080f0; cursor: pointer; }
:deep(.shipLink:hover) { text-decoration: underline; }
</style>
