<template>
    <div class="distWrapper">
        <!-- Clients column -->
        <div class="distColumn">
            <h4 v-if="organization">Clients of {{ organization.name }}</h4>
            <n-icon v-if="isWritable" @click="openClientModal()" class="icons clickable" title="Add Client" size="24"><CirclePlus /></n-icon>
            <n-data-table
                :columns="clientColumns"
                :data="clients"
                :row-class-name="clientRowClass"
                :row-props="clientRowProps"
                size="small" />
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
                <n-data-table
                    :columns="siteColumns"
                    :data="sites"
                    :row-class-name="siteRowClass"
                    :row-props="siteRowProps"
                    size="small" />
            </template>
            <div v-else class="placeholder">Select a client to view its sites.</div>
        </div>

        <!-- Shipments column -->
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
                <h4>{{ terms.shipments }} <span class="subtle">({{ terms.installedBase }}: latest per feature set)</span></h4>
                <n-icon v-if="isWritable" @click="openShipModal()" class="icons clickable" :title="terms.shipAction" size="22"><CirclePlus /></n-icon>
                <n-data-table
                    :columns="shipmentColumns"
                    :data="shipments"
                    size="small" />
            </template>
            <div v-else class="placeholder">Select a site to view {{ terms.shipments.toLowerCase() }}.</div>
        </div>

        <!-- Client modal -->
        <n-modal v-model:show="showClientModal" preset="dialog" :show-icon="false" :title="clientForm.uuid ? 'Edit Client' : 'Add Client'">
            <n-form>
                <n-form-item label="Name"><n-input v-model:value="clientForm.name" placeholder="Client name" /></n-form-item>
                <n-form-item label="Framing (domain)">
                    <n-select v-model:value="clientForm.domain" :options="domainOptions" />
                </n-form-item>
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
        <n-modal v-model:show="showShipModal" preset="dialog" :show-icon="false" :title="terms.shipAction" style="width: 640px;">
            <n-form>
                <n-form-item label="Feature set UUID">
                    <n-input v-model:value="shipForm.featureSet" placeholder="UUID of the shipped feature set" @blur="resolveIdentity" />
                </n-form-item>
                <div v-if="resolvedIdentity" class="identityBox">
                    {{ terms.deviceIdentity }}:
                    <n-tag size="small" :type="resolvedIdentity.deviceClass === 'NONE' ? 'default' : 'info'">{{ resolvedIdentity.deviceClass }}</n-tag>
                    <span v-if="resolvedIdentity.udiDi"> &middot; UDI-DI: <code>{{ resolvedIdentity.udiDi }}</code></span>
                </div>
                <n-form-item label="Release UUID"><n-input v-model:value="shipForm.release" placeholder="UUID of the shipped release/version" /></n-form-item>
                <n-form-item label="Ship date"><n-input v-model:value="shipForm.shipDate" placeholder="YYYY-MM-DD (defaults to today)" /></n-form-item>
                <n-form-item label="Quantity"><n-input-number v-model:value="shipForm.quantity" :min="1" /></n-form-item>
                <n-form-item label="Lot"><n-input v-model:value="shipForm.pi.lot" /></n-form-item>
                <n-form-item label="Serial"><n-input v-model:value="shipForm.pi.serial" /></n-form-item>
                <n-form-item label="Expiry date"><n-input v-model:value="shipForm.pi.expiryDate" placeholder="YYYY-MM-DD" /></n-form-item>
                <template v-if="isTracked">
                    <n-divider>Device tracking (821)</n-divider>
                    <n-form-item label="Received date"><n-input v-model:value="shipForm.tracking.receivedDate" placeholder="YYYY-MM-DD" /></n-form-item>
                    <n-form-item label="Patient / recipient ID"><n-input v-model:value="shipForm.tracking.patientId" /></n-form-item>
                    <n-form-item label="Disposition"><n-select v-model:value="shipForm.tracking.disposition" :options="dispositionOptions" clearable /></n-form-item>
                </template>
            </n-form>
            <template #action><n-button type="primary" @click="shipProduct">{{ terms.shipAction }}</n-button></template>
        </n-modal>

        <!-- Actual / field-report modal -->
        <n-modal v-model:show="showActualModal" preset="dialog" :show-icon="false" :title="terms.actualReport" style="width: 520px;">
            <n-form>
                <n-form-item label="Reported release UUID"><n-input v-model:value="actualForm.reportedRelease" placeholder="UUID actually running in the field" /></n-form-item>
                <n-form-item label="Source"><n-select v-model:value="actualForm.source" :options="sourceOptions" /></n-form-item>
            </n-form>
            <template #action><n-button type="primary" @click="reportActual">Save</n-button></template>
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
import { NDataTable, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NButton, NIcon, NTag, NSpace, NPopconfirm, NDivider, useNotification, NotificationType } from 'naive-ui'
import { CirclePlus } from '@vicons/tabler'
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

const selectedClientUuid: Ref<string> = ref(route.params.clientuuid ? route.params.clientuuid.toString() : '')
const selectedSiteUuid: Ref<string> = ref(route.params.siteuuid ? route.params.siteuuid.toString() : '')

const selectedClient: ComputedRef<any> = computed(() => clients.value.find(c => c.uuid === selectedClientUuid.value))
const selectedSite: ComputedRef<any> = computed(() => sites.value.find(s => s.uuid === selectedSiteUuid.value))
const terms = computed(() => termsFor(selectedClient.value?.domain))
const domainTagType = computed(() => selectedClient.value?.domain === 'DEFENSE' ? 'success' : (selectedClient.value?.domain === 'MEDICAL' ? 'info' : 'default'))

const domainOptions = DISTRIBUTION_DOMAIN_OPTIONS
const dispositionOptions = ['SHIPPED', 'RECEIVED', 'RETURNED', 'EXPLANTED', 'DISPOSED', 'DONATED', 'LOST'].map(v => ({ label: v, value: v }))
const sourceOptions = ['PHONE_HOME', 'MANUAL', 'SUPPORT_TICKET'].map(v => ({ label: v, value: v }))

const showClientModal = ref(false)
const showSiteModal = ref(false)
const showShipModal = ref(false)
const showActualModal = ref(false)
const resolvedIdentity: Ref<any> = ref(null)

const emptyContact = () => ({ address: '', phone: '', email: '' })
const clientForm = reactive<any>({ uuid: '', name: '', domain: 'GENERIC', contact: emptyContact(), notes: '' })
const siteForm = reactive<any>({ uuid: '', name: '', contact: emptyContact(), notes: '' })
const shipForm = reactive<any>({ featureSet: '', release: '', shipDate: '', quantity: 1, pi: { lot: '', serial: '', expiryDate: '' }, tracking: { receivedDate: '', patientId: '', disposition: null } })
const actualForm = reactive<any>({ uuid: '', reportedRelease: '', source: 'MANUAL' })

const isTracked = computed(() => resolvedIdentity.value?.deviceClass === 'MEDICAL_TRACKED')

const notify = (type: NotificationType, title: string, content: string) =>
    notification[type]({ content, meta: title, duration: 3500, keepAliveOnHover: true })

const formatContact = (c: any) => [c?.address, c?.phone, c?.email].filter(Boolean).join(' · ')

// ---- GraphQL ----
const CLIENT_FIELDS = 'uuid org name domain contact { address phone email } notes'
const SITE_FIELDS = 'uuid org client name contact { address phone email } notes'
const SHIP_FIELDS = 'uuid org site featureSet release shipDate quantity versionDrift pi { lot serial manufactureDate expiryDate } tracking { receivedDate patientId disposition dispositionDate } actual { reportedRelease reportedAt source }'

async function loadClients () {
    const resp: any = await graphqlClient.query({
        query: gql`query clientsOfOrg($orgUuid: ID!) { clientsOfOrg(orgUuid: $orgUuid) { ${CLIENT_FIELDS} } }`,
        variables: { orgUuid: orguuid.value },
        fetchPolicy: 'no-cache'
    })
    clients.value = resp.data.clientsOfOrg || []
}

async function loadSites (clientUuid: string) {
    if (!clientUuid) { sites.value = []; return }
    const resp: any = await graphqlClient.query({
        query: gql`query sitesOfClient($clientUuid: ID!) { sitesOfClient(clientUuid: $clientUuid) { ${SITE_FIELDS} } }`,
        variables: { clientUuid },
        fetchPolicy: 'no-cache'
    })
    sites.value = resp.data.sitesOfClient || []
}

async function loadShipments (siteUuid: string) {
    if (!siteUuid) { shipments.value = []; return }
    const resp: any = await graphqlClient.query({
        query: gql`query shippedProductsOfSite($siteUuid: ID!) { shippedProductsOfSite(siteUuid: $siteUuid) { ${SHIP_FIELDS} } }`,
        variables: { siteUuid },
        fetchPolicy: 'no-cache'
    })
    shipments.value = resp.data.shippedProductsOfSite || []
}

async function resolveIdentity () {
    resolvedIdentity.value = null
    if (!shipForm.featureSet) return
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query featureSetDeviceIdentity($fs: ID!, $org: ID!) { featureSetDeviceIdentity(featureSetUuid: $fs, orgUuid: $org) { featureSet deviceClass udiBearingComponent udiBearingRelease udiDi } }`,
            variables: { fs: shipForm.featureSet, org: orguuid.value },
            fetchPolicy: 'no-cache'
        })
        resolvedIdentity.value = resp.data.featureSetDeviceIdentity
    } catch (e: any) {
        // leave unresolved; invalid UUID etc.
    }
}

// ---- selection / routing ----
function selectClient (uuid: string) {
    router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: uuid } })
}
function selectSite (uuid: string) {
    router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: selectedClientUuid.value, siteuuid: uuid } })
}

watch(() => route.params.clientuuid, async (v) => {
    selectedClientUuid.value = v ? v.toString() : ''
    selectedSiteUuid.value = ''
    await loadSites(selectedClientUuid.value)
    shipments.value = []
})
watch(() => route.params.siteuuid, async (v) => {
    selectedSiteUuid.value = v ? v.toString() : ''
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
    { key: 'release', title: 'Release', render: (r: any) => shortUuid(r.release) },
    { key: 'quantity', title: 'Qty' },
    {
        key: 'drift', title: terms.value.fieldState,
        render: (r: any) => r.versionDrift
            ? h(NTag, { size: 'small', type: 'warning' }, { default: () => 'DRIFT' })
            : (r.actual && r.actual.reportedRelease ? h(NTag, { size: 'small', type: 'success' }, { default: () => 'match' }) : h('span', { class: 'subtle' }, '—'))
    },
    {
        key: 'actions', title: '',
        render: (r: any) => isWritable ? h(NSpace, { size: 4 }, { default: () => [
            h(NButton, { size: 'tiny', onClick: () => openActualModal(r) }, { default: () => terms.value.actualReport }),
            h(NButton, { size: 'tiny', type: 'error', onClick: () => deleteShipment(r) }, { default: () => 'Del' })
        ] }) : null
    }
])

const shortUuid = (u: string) => u ? u.substring(0, 8) : ''
const clientRowClass = (r: any) => r.uuid === selectedClientUuid.value ? 'selectedRow' : ''
const siteRowClass = (r: any) => r.uuid === selectedSiteUuid.value ? 'selectedRow' : ''
const clientRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => selectClient(r.uuid) })
const siteRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => selectSite(r.uuid) })

// ---- mutations ----
function openClientModal (c?: any) {
    clientForm.uuid = c?.uuid || ''
    clientForm.name = c?.name || ''
    clientForm.domain = c?.domain || 'GENERIC'
    clientForm.contact = c?.contact ? { ...c.contact } : emptyContact()
    clientForm.notes = c?.notes || ''
    showClientModal.value = true
}
async function saveClient () {
    if (!clientForm.name) { notify('warning', 'Missing', 'Client name is required'); return }
    const input: any = { org: orguuid.value, name: clientForm.name, domain: clientForm.domain, notes: clientForm.notes, contact: clientForm.contact }
    if (clientForm.uuid) input.uuid = clientForm.uuid
    await graphqlClient.mutate({
        mutation: gql`mutation upsertClient($input: ClientInput!) { upsertClient(input: $input) { uuid } }`,
        variables: { input }
    })
    showClientModal.value = false
    notify('success', 'Saved', `Client ${clientForm.name} saved`)
    await loadClients()
}
async function deleteClient (c: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteClient($uuid: ID!) { deleteClient(uuid: $uuid) }`, variables: { uuid: c.uuid } })
    notify('info', 'Deleted', `Client ${c.name} deleted`)
    if (selectedClientUuid.value === c.uuid) router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value } })
    await loadClients()
}

function openSiteModal (s?: any) {
    siteForm.uuid = s?.uuid || ''
    siteForm.name = s?.name || ''
    siteForm.contact = s?.contact ? { ...s.contact } : emptyContact()
    siteForm.notes = s?.notes || ''
    showSiteModal.value = true
}
async function saveSite () {
    if (!siteForm.name) { notify('warning', 'Missing', 'Site name is required'); return }
    const input: any = { org: orguuid.value, client: selectedClientUuid.value, name: siteForm.name, notes: siteForm.notes, contact: siteForm.contact }
    if (siteForm.uuid) input.uuid = siteForm.uuid
    await graphqlClient.mutate({
        mutation: gql`mutation upsertSite($input: SiteInput!) { upsertSite(input: $input) { uuid } }`,
        variables: { input }
    })
    showSiteModal.value = false
    notify('success', 'Saved', `Site ${siteForm.name} saved`)
    await loadSites(selectedClientUuid.value)
}
async function deleteSite (s: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteSite($uuid: ID!) { deleteSite(uuid: $uuid) }`, variables: { uuid: s.uuid } })
    notify('info', 'Deleted', `Site ${s.name} deleted`)
    if (selectedSiteUuid.value === s.uuid) router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: selectedClientUuid.value } })
    await loadSites(selectedClientUuid.value)
}

function openShipModal () {
    shipForm.featureSet = ''
    shipForm.release = ''
    shipForm.shipDate = ''
    shipForm.quantity = 1
    shipForm.pi = { lot: '', serial: '', expiryDate: '' }
    shipForm.tracking = { receivedDate: '', patientId: '', disposition: null }
    resolvedIdentity.value = null
    showShipModal.value = true
}
function cleanObject (o: any) {
    const out: any = {}
    Object.keys(o).forEach(k => { if (o[k] !== '' && o[k] !== null && o[k] !== undefined) out[k] = o[k] })
    return Object.keys(out).length ? out : null
}
async function shipProduct () {
    if (!shipForm.featureSet || !shipForm.release) { notify('warning', 'Missing', 'Feature set and release are required'); return }
    const input: any = { org: orguuid.value, site: selectedSiteUuid.value, featureSet: shipForm.featureSet, release: shipForm.release, quantity: shipForm.quantity }
    if (shipForm.shipDate) input.shipDate = shipForm.shipDate
    const pi = cleanObject(shipForm.pi); if (pi) input.pi = pi
    if (isTracked.value) { const tr = cleanObject(shipForm.tracking); if (tr) input.tracking = tr }
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation shipProduct($input: ShipProductInput!) { shipProduct(input: $input) { uuid } }`,
            variables: { input }
        })
        showShipModal.value = false
        notify('success', 'Saved', `${terms.value.shipment} recorded`)
        await loadShipments(selectedSiteUuid.value)
    } catch (e: any) {
        notify('error', 'Failed', e.message || 'Could not record shipment')
    }
}
async function deleteShipment (r: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteShippedProduct($uuid: ID!) { deleteShippedProduct(uuid: $uuid) }`, variables: { uuid: r.uuid } })
    notify('info', 'Deleted', 'Record deleted')
    await loadShipments(selectedSiteUuid.value)
}

function openActualModal (r: any) {
    actualForm.uuid = r.uuid
    actualForm.reportedRelease = r.actual?.reportedRelease || ''
    actualForm.source = r.actual?.source || 'MANUAL'
    showActualModal.value = true
}
async function reportActual () {
    const input: any = { reportedRelease: actualForm.reportedRelease, source: actualForm.source, reportedAt: new Date().toISOString() }
    await graphqlClient.mutate({
        mutation: gql`mutation reportShippedProductActual($uuid: ID!, $input: ActualReportInput!) { reportShippedProductActual(uuid: $uuid, input: $input) { uuid } }`,
        variables: { uuid: actualForm.uuid, input }
    })
    showActualModal.value = false
    notify('success', 'Saved', `${terms.value.actualReport} recorded`)
    await loadShipments(selectedSiteUuid.value)
}

onMounted(async () => {
    await loadClients()
    if (selectedClientUuid.value) await loadSites(selectedClientUuid.value)
    if (selectedSiteUuid.value) await loadShipments(selectedSiteUuid.value)
})
</script>

<style scoped lang="scss">
.distWrapper {
    display: grid;
    grid-template-columns: 280px 320px 1fr;
    grid-gap: 8px;
}
.distColumn {
    border-right: 1px solid #edf2f3;
    padding-right: 8px;
}
.contactLine {
    font-size: 12px;
    color: #666;
    margin-bottom: 4px;
}
.placeholder {
    color: #999;
    padding-top: 24px;
    font-style: italic;
}
.subtle {
    color: #999;
    font-size: 12px;
}
.identityBox {
    background: #f4f8fb;
    border-radius: 6px;
    padding: 6px 8px;
    margin-bottom: 8px;
    font-size: 13px;
}
.icons {
    margin: 4px;
    vertical-align: middle;
}
.clickable {
    cursor: pointer;
}
:deep(.selectedRow td) {
    background-color: #f1f1f1 !important;
}
</style>
