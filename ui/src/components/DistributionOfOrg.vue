<template>
    <div>
        <!-- Fleet drift: units diverging from their authorized baseline, org-wide -->
        <div v-if="fleetDrift.length" class="fleetDriftPanel">
            <n-space align="center" size="small" style="margin-bottom: 6px;">
                <n-tag type="warning" size="small">DRIFT</n-tag>
                <strong>{{ fleetDrift.length }} unit{{ fleetDrift.length > 1 ? 's' : '' }} diverging from authorized configuration</strong>
            </n-space>
            <n-data-table :columns="fleetDriftColumns" :data="fleetDrift" size="small" :row-props="driftRowProps" :pagination="fleetDrift.length > 5 ? { pageSize: 5 } : false" />
        </div>
        <!-- D7 fleet question, read-only: which in-field units outlive their software.
             Hidden entirely on a backend whose schema lacks the query (CE before the sync),
             and not shown at all until the first answer is in, so an empty frame never reads
             as "no risk"; follows the client / site selected below. -->
        <div v-if="fleetRiskLoaded && fleetRisk.supported" class="fleetRiskPanel" data-testid="fleet-risk-panel">
            <n-space align="center" size="small" style="margin-bottom: 6px;">
                <n-tag :type="fleetRiskAnyAtRisk ? 'error' : 'default'" size="small">SUPPORT RISK</n-tag>
                <strong data-testid="fleet-risk-headline">{{ fleetRiskHeadlineText }}</strong>
                <span class="subtle">{{ fleetRiskScopeLabel }}</span>
            </n-space>
            <n-alert v-if="fleetRiskError" type="error" :show-icon="false" size="small" style="margin-bottom: 6px;" data-testid="fleet-risk-error-alert">
                <n-space align="center" size="small">
                    <span>Could not load the fleet support risk view: {{ fleetRiskError }}. The rows below are the last answer, not the current one.</span>
                    <n-button size="tiny" @click="loadFleetRisk(fleetRiskPage)">Retry</n-button>
                </n-space>
            </n-alert>
            <n-alert v-if="fleetRisk.degraded" type="warning" :show-icon="false" size="small" style="margin-bottom: 6px;" data-testid="fleet-risk-degraded-alert">
                This backend serves the verdict per unit but not the evidence behind it (release judged, window in force, component end-of-support) nor the unit labels (identifiers, site and client names) or the fleet-wide at-risk count. Those columns are blank, not empty; units are named by id, and the headline counts the rows in hand rather than the fleet.
            </n-alert>
            <!-- Served at-risk-first across the whole fleet; rendered in that order, never re-sorted. -->
            <n-data-table remote :columns="fleetRiskColumns" :data="fleetRisk.rows" :loading="fleetRiskLoading" size="small"
                :row-props="fleetRiskRowProps" :row-key="(r: any) => r.device"
                :pagination="fleetRiskPagination" @update:page="loadFleetRisk" />
        </div>
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
                <h4>{{ terms.shipments }} <span class="subtle">({{ terms.installedBase }})</span></h4>
                <n-icon v-if="isWritable" @click="openShipModal()" class="icons clickable" :title="terms.shipAction" size="22"><CirclePlus /></n-icon>
                <n-tabs v-if="showHardwareTab || showSamdTab" v-model:value="shipmentTab" type="segment" size="small" animated data-testid="shipment-tabs">
                    <n-tab-pane v-if="showHardwareTab" name="hardware" :tab="`Hardware (${hardwareShipments.length})`">
                        <n-data-table :columns="shipmentColumns" :data="hardwareShipments" :row-class-name="shipmentRowClass" :row-props="shipmentRowProps" size="small" />
                    </n-tab-pane>
                    <n-tab-pane v-if="showSamdTab" name="samd" :tab="`SaMD (${samdShipments.length})`">
                        <n-data-table :columns="shipmentColumns" :data="samdShipments" :row-class-name="shipmentRowClass" :row-props="shipmentRowProps" size="small" />
                    </n-tab-pane>
                    <n-tab-pane name="software" :tab="`Software (${softwareShipments.length})`">
                        <n-data-table :columns="softwareShipmentColumns" :data="softwareShipments" :row-class-name="shipmentRowClass" :row-props="shipmentRowProps" size="small" />
                    </n-tab-pane>
                </n-tabs>
                <!-- software-only organization: no tabs, just the software shipments -->
                <div v-else data-testid="shipment-tabs" data-software-only="true">
                    <n-data-table :columns="softwareShipmentColumns" :data="softwareShipments" :row-class-name="shipmentRowClass" :row-props="shipmentRowProps" size="small" />
                </div>

                <!-- Units of the selected HARDWARE / SAMD shipment -->
                <template v-if="selectedShipment && !isPlainSoftware(selectedShipment)">
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
                <n-form-item v-if="!clientForm.uuid" :show-label="false">
                    <n-checkbox v-model:checked="createDefaultSite" data-testid="create-default-site">Create a default site named "Default" with this client's address details</n-checkbox>
                </n-form-item>
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
                <n-form-item v-if="!editingShipmentUuid" label="Product *">
                    <n-select v-model:value="shipProductUuid" filterable :options="products" placeholder="Pick a product" @update:value="onShipProductChange" />
                </n-form-item>
                <n-form-item v-if="!editingShipmentUuid" label="Feature set *">
                    <n-select v-model:value="shipForm.featureSet" filterable :options="featureSetOptions" :disabled="!shipProductUuid" placeholder="Pick a feature set" @update:value="onFeatureSetChange" />
                </n-form-item>
                <div v-if="editingShipmentUuid" class="identityBox">
                    Editing shipment of <strong>{{ releaseLabel(shipForm.release) || shortUuid(shipForm.release) }}</strong> — product / feature set are fixed; adjust the rest. Fields marked * are required.
                </div>
                <div v-if="resolvedIdentity" class="identityBox" data-testid="ship-kind-box">
                    <n-tag size="small" :type="isSoftwareShipment ? 'success' : 'info'">{{ shipmentKindLabel(resolvedIdentity) }}</n-tag>
                    <template v-if="resolvedIdentity.deviceClass !== 'NONE'">
                        &middot; {{ terms.deviceIdentity }}: <n-tag size="small" type="info">{{ resolvedIdentity.deviceClass }}</n-tag>
                        <span v-if="resolvedIdentity.udiDi"> &middot; UDI-DI: <code>{{ resolvedIdentity.udiDi }}</code></span>
                    </template>
                    <span v-if="isSoftwareShipment" class="subtle"> &middot; plain software: no quantity or batch facts; optionally applied to devices at this site</span>
                    <span v-else-if="isSamd(resolvedIdentity)" class="subtle"> &middot; software as a medical device: shipped and tracked per unit like hardware</span>
                </div>
                <div v-if="!editingShipmentUuid" class="subtle" style="margin-bottom: 6px;">Fields marked * are required.</div>
                <n-form-item label="Release *">
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
                <n-form-item v-if="isSoftwareShipment" label="Applies to devices (optional)">
                    <n-select v-model:value="shipForm.devices" multiple filterable clearable :options="siteDeviceOptions" data-testid="ship-devices"
                        :placeholder="siteDeviceOptions.length ? 'Pick devices from hardware or SaMD shipments at this site, or leave empty' : 'No devices at this site yet — leave empty'" />
                </n-form-item>
                <n-form-item v-if="!isSoftwareShipment" label="Quantity *"><n-input-number v-model:value="shipForm.quantity" :min="1" /></n-form-item>

                <!-- THE BATCH'S DEVICE SUPPORT WINDOW OVERRIDE (D7).
                     Hardware and SaMD only: plain software is not a device and has no section
                     524B commitment to override, so the block never renders there.
                     Support commonly runs from SALE OR SHIPMENT ("seven years from date of
                     sale"), which is a fact about a batch -- and the ship date it anchors to is
                     the field directly above. -->
                <template v-if="!isSoftwareShipment">
                    <n-divider style="margin: 14px 0 8px;" />
                    <div style="font-size: 13px; margin-bottom: 4px;"><strong>Device support window</strong></div>
                    <div class="subtle" style="font-size: 12px; margin-bottom: 8px;">
                        <template v-if="effectiveWindowLabel">
                            In force: {{ effectiveWindowLabel }}.
                        </template>
                        <!-- Only claimed when we have actually READ a shipment. On create
                             there is no shipment to resolve through yet, and the old markup
                             fell through to "No window is declared for this device model" --
                             a false statement about the product, made at exactly the moment
                             it would talk someone into a batch override they do not need. -->
                        <template v-else-if="editingShipment">
                            No window is declared for this device model.
                        </template>
                        Leave both blank to inherit from the product component; fill them to
                        override for this batch only.
                    </div>
                    <n-form-item label="Batch end of support">
                        <n-date-picker style="width: 100%;" type="date" clearable
                            v-model:formatted-value="shipForm.deviceWindowEos" value-format="yyyy-MM-dd"
                            placeholder="inherit from the product component" />
                    </n-form-item>
                    <n-form-item label="Batch end of life / end of sale">
                        <n-date-picker style="width: 100%;" type="date" clearable
                            v-model:formatted-value="shipForm.deviceWindowEol" value-format="yyyy-MM-dd"
                            placeholder="inherit from the product component" />
                    </n-form-item>
                </template>
                <n-form-item v-if="!isSoftwareShipment" label="Batch identifiers">
                    <n-dynamic-input v-model:value="shipForm.identifiers" :on-create="onCreateBatchId">
                        <template #create-button-default>Add identifier</template>
                        <template #default="{ value }">
                            <n-select style="width: 150px;" v-model:value="value.idType" :options="idTypeOptions" />
                            <n-input v-model:value="value.idValue" placeholder="value" />
                        </template>
                    </n-dynamic-input>
                </n-form-item>
                <n-form-item v-if="!isSoftwareShipment" label="Manufacture date"><n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="shipForm.manufactureDate" value-format="yyyy-MM-dd" /></n-form-item>
                <n-form-item :label="isSoftwareShipment ? 'Expiry / end of support date (optional)' : 'Expiry date'"><n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="shipForm.expiryDate" value-format="yyyy-MM-dd" /></n-form-item>
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
    </div>
</template>

<script lang="ts">
export default { name: 'DistributionOfOrg' }
</script>
<script lang="ts" setup>
import { ref, Ref, computed, ComputedRef, reactive, h, onMounted, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NTabPane, NTabs, NDataTable, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NButton, NIcon, NTag, NSpace, NDivider, NDynamicInput, NTooltip, NPopconfirm, NDatePicker, useNotification, NotificationType, NCheckbox, NAlert } from 'naive-ui'
import { CirclePlus, InfoCircle, Edit as EditIcon, Archive as ArchiveIcon,
    FileCertificate as StatementIcon, Loader as PendingIcon } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { termsFor, DISTRIBUTION_DOMAIN_OPTIONS } from '@/utils/distributionTerms'
import { applyShipmentWindowToInput,
    effectiveWindowLabel as buildEffectiveWindowLabel } from '@/utils/componentDeviceWindow'
import { loadDevicesAtSupportRisk, fleetRiskTag, fleetRiskHeadline, summarizeFleetRiskPage,
    releaseSourceTag, fleetWindowLabel, FLEET_RISK_DETAIL, FLEET_RISK_DEFAULT_PAGE_SIZE,
    type FleetRiskResult, type FleetRiskRow } from '@/utils/fleetSupportRisk'
import { isDeviceRiskFlagged } from '@/utils/supportStatusTag'
import { generateDeviceSupportStatement } from '@/utils/deviceSupportStatementExport'
import { NO_WINDOW_IN_FORCE } from '@/utils/addendumData'

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
        query: gql`query relDeliverables($u: ID!) { release(releaseUuid: $u) { variantDetails { outboundDeliverableDetails { uuid displayIdentifier identifiers { idType idValue } quantity } } } }`,
        variables: { u: releaseUuid }, fetchPolicy: 'no-cache'
    })
    const variants = resp.data.release?.variantDetails || []
    const seen = new Set<string>()
    const opts: any[] = []
    for (const v of variants) {
        for (const d of (v.outboundDeliverableDetails || [])) {
            if (!d || seen.has(d.uuid)) continue
            seen.add(d.uuid)
            const lot = (d.identifiers || []).find((i: any) => i.idType === 'LOT')
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
// New clients get a "Default" site out of the box (address / phone / email copied from the client) unless unticked.
const createDefaultSite = ref(true)
const siteForm = reactive<any>({ uuid: '', name: '', contact: emptyContact(), notes: '' })
const shipForm = reactive<any>({ featureSet: '', release: '', deliverable: null, shipDate: null, quantity: 1, identifiers: [], manufactureDate: null, expiryDate: null, devices: [],
    // D7 batch override. Baselines so an unchanged window sends nothing -- the server stamps
    // provenance on every write, and a no-op resend would re-attribute a claim nobody touched.
    deviceWindowEos: null, deviceWindowEol: null,
    deviceWindowBaselineEos: null, deviceWindowBaselineEol: null })

/** The shipment row currently open in the modal, for the in-force line. */
const editingShipment: Ref<any> = ref(null)

/** The window in force for the shipment being edited, named with where it was declared. */
const effectiveWindowLabel: ComputedRef<string> = computed((): string =>
    buildEffectiveWindowLabel((editingShipment.value as any)?.effectiveDeviceSupportWindow))
// Shipments carry the same two-axis classification as components: nature (HARDWARE /
// SOFTWARE, hardware if any constituent component is hardware) and deviceClass. HARDWARE =
// physical batch; SOFTWARE + MEDICAL_* = SaMD, shipped and tracked per unit like hardware;
// SOFTWARE + NONE = plain software that may be applied to devices at the site. The server
// derives and stores both; this only drives the form and the tabs.
const isHardware = (x: any) => x?.nature === 'HARDWARE'
const isSamd = (x: any) => !isHardware(x) && !!x?.deviceClass && x.deviceClass !== 'NONE'
const isPlainSoftware = (x: any) => !isHardware(x) && !isSamd(x)
const isSoftwareShipment = computed(() => !!resolvedIdentity.value && isPlainSoftware(resolvedIdentity.value))
const shipmentKindLabel = (x: any) => isHardware(x) ? 'Hardware shipment' : isSamd(x) ? 'SaMD shipment' : 'Software shipment'
const shipmentTab = ref('hardware')
const softwareShipments = computed(() => shipments.value.filter(isPlainSoftware))
const samdShipments = computed(() => shipments.value.filter(isSamd))
const hardwareShipments = computed(() => shipments.value.filter(isHardware))
// Which tabs make sense here follows the org's component classification: no hardware
// components -> software only; SaMD only for MEDICAL-framed clients of an org that has SaMD
// components. A kind that already has rows stays visible so nothing is hidden by accident.
const orgHasHardware = ref(false)
const orgHasSamd = ref(false)
async function loadOrgClassification () {
    try {
        const resp: any = await graphqlClient.query({ query: gql`query componentsClassification($o: ID!) { components(orgUuid: $o, componentType: ANY) { uuid nature deviceClass } }`, variables: { o: orguuid.value }, fetchPolicy: 'no-cache' })
        const comps: any[] = resp.data.components || []
        orgHasHardware.value = comps.some(isHardware)
        orgHasSamd.value = comps.some(isSamd)
    } catch (e) { orgHasHardware.value = false; orgHasSamd.value = false }
}
const showHardwareTab = computed(() => orgHasHardware.value || hardwareShipments.value.length > 0)
const showSamdTab = computed(() => (selectedClient.value?.domain === 'MEDICAL' && orgHasSamd.value) || samdShipments.value.length > 0)
const pickShipmentTab = () => {
    const visible = [showHardwareTab.value ? 'hardware' : '', showSamdTab.value ? 'samd' : '', 'software'].filter(Boolean)
    const counts: Record<string, number> = { hardware: hardwareShipments.value.length, samd: samdShipments.value.length, software: softwareShipments.value.length }
    shipmentTab.value = visible.find(t => counts[t] > 0) || visible[0]
}
watch([showHardwareTab, showSamdTab], () => { if (!['hardware', 'samd', 'software'].includes(shipmentTab.value) || (shipmentTab.value === 'hardware' && !showHardwareTab.value) || (shipmentTab.value === 'samd' && !showSamdTab.value)) pickShipmentTab() })
const siteDevices: Ref<any[]> = ref([])
const siteDeviceOptions = computed(() => siteDevices.value.map((d: any) => ({ label: summarizeIds(d.identifiers) || shortUuid(d.uuid), value: d.uuid })))
async function loadSiteDevices (siteUuid: string) {
    if (!siteUuid) { siteDevices.value = []; return }
    try {
        const resp: any = await graphqlClient.query({ query: gql`query devicesOfSite($s: ID!, $o: ID!) { devicesOfSite(siteUuid: $s, orgUuid: $o) { uuid identifiers { idType idValue } shippedProduct } }`, variables: { s: siteUuid, o: orguuid.value }, fetchPolicy: 'no-cache' })
        siteDevices.value = resp.data.devicesOfSite || []
    } catch (e) { siteDevices.value = [] }
}
const deviceLabel = (uuid: string) => siteDeviceOptions.value.find(o => o.value === uuid)?.label || shortUuid(uuid)
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
// D7: the batch's own override plus the window that actually applies, with the level it was
// declared at. No CORE/FULL split -- the Distribution module is SaaS-only, so this document is
// never issued by a CE build at all, which is a stronger guarantee than a fallback.
const SHIP_FIELDS = 'uuid org site featureSet release deliverable shipDate quantity manufactureDate expiryDate notes nature deviceClass devices identifiers { idType idValue } choiceResolutions { choiceRef selectedRefs }'
    + ' deviceSupportWindow { eos eol } effectiveDeviceSupportWindow { eos eol source }'
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
        const resp: any = await graphqlClient.query({ query: gql`query fsdi($fs: ID!, $org: ID!) { featureSetDeviceIdentity(featureSetUuid: $fs, orgUuid: $org) { deviceClass udiDi nature } }`, variables: { fs: shipForm.featureSet, org: orguuid.value }, fetchPolicy: 'no-cache' })
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
    await Promise.all([loadShipments(selectedSiteUuid.value), loadSiteDevices(selectedSiteUuid.value)])
    pickShipmentTab()
})

// ---- columns ----
// Edit / archive live inside the list row (no header label); clicks must not select the row.
const rowActions = (r: any, edit: (x: any) => void, archive: (x: any) => Promise<void>, confirmText: string) => h('span', { style: 'white-space: nowrap;', onClick: (e: Event) => e.stopPropagation() }, [
    h(NIcon, { size: 18, class: 'icons clickable', title: 'Edit', style: 'margin-right: 8px;', onClick: () => edit(r) }, { default: () => h(EditIcon) }),
    h(NPopconfirm, { onPositiveClick: () => archive(r) }, {
        trigger: () => h(NIcon, { size: 18, class: 'icons clickable', title: 'Archive', style: 'color: #d03050;' }, { default: () => h(ArchiveIcon) }),
        default: () => confirmText
    })
])
const clientColumns = [
    { key: 'name', title: 'Name' },
    { key: 'domain', title: 'Framing', render: (r: any) => h(NTag, { size: 'small' }, { default: () => r.domain || 'GENERIC' }) },
    ...(isWritable ? [{ key: 'actions', title: '', width: 70, render: (r: any) => rowActions(r, openClientModal, deleteClient, `Archive client ${r.name} with all its sites, shipments, and devices?`) }] : [])
]
const siteColumns = [
    { key: 'name', title: 'Name' },
    ...(isWritable ? [{ key: 'actions', title: '', width: 70, render: (r: any) => rowActions(r, openSiteModal, deleteSite, `Archive site ${r.name} with its shipments and devices?`) }] : [])
]
/**
 * The Device Support Statement for one delivery (plan 7h).
 *
 * GATED ON THE WINDOW IN FORCE, not on the shipment's classification. Every delivery of a
 * device model -- hardware batch, SaMD, or plain software applied to units at a site -- has
 * dates in force for the units it reached, which is what the statement states. What it must
 * NOT be offered for is a delivery with no window at all: the collector refuses that, and a
 * control that is clickable only to answer with a dialog is the pattern the release view
 * already had to walk back (board t20260909-061338-23148 step 6d).
 */
const statementColumn = {
    key: 'statement', title: '',
    render: (r: any) => {
        const w = r.effectiveDeviceSupportWindow
        const has = !!(w && (w.eos || w.eol))
        const pending = statementExportPending.value === r.uuid
        const icon = h(NIcon, {
            size: 16,
            'data-testid': 'shipment-statement-action',
            'data-window-in-force': String(has),
            'data-pending': String(pending),
            style: `vertical-align: middle; ${pending ? 'cursor: progress; color: #909399;'
                : (has ? 'cursor: pointer;' : 'color: #c8ccd0;')}`,
            onClick: (e: Event) => { e.stopPropagation(); if (has) exportShipmentStatement(r) }
        }, { default: () => h(pending ? PendingIcon : StatementIcon) })
        return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 420px;' }, {
            trigger: () => icon,
            default: () => pending
                ? 'Assembling the statement. It walks the whole component list, so it can take'
                    + ' a moment on a large release.'
                : has
                    ? 'Device support statement for this delivery. States the dates in force for'
                        + ' these units, and names the delivery they apply to.'
                    // The refusal the generator would give, said before the click instead of
                    // after it. One sentence, one constant: two that must agree, in two
                    // files, is how they stop agreeing.
                    : NO_WINDOW_IN_FORCE
        })
    }
}
// Offered only where SOME delivery in the table has a window in force. An organization that
// declares none would otherwise carry a permanently inert icon whose tooltip gives device-
// model instructions it has no use for; once one delivery has a window, the inert icon on
// its neighbours is the useful signal it was written to be.
const anyWindowInForce = (rows: any[]) => rows.some((r: any) =>
    r.effectiveDeviceSupportWindow?.eos || r.effectiveDeviceSupportWindow?.eol)
const editShipmentColumn = {
    key: 'edit', title: '',
    render: (r: any) => h(NIcon, {
        size: 16, style: 'cursor: pointer; vertical-align: middle;', title: 'Edit shipment',
        onClick: (e: Event) => { e.stopPropagation(); openShipModal(r) }
    }, { default: () => h(EditIcon) })
}
const releaseShipmentColumn = {
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
}
const shipmentColumns = computed(() => [
    { key: 'shipDate', title: 'Date' },
    releaseShipmentColumn,
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
    ...(anyWindowInForce([...hardwareShipments.value, ...samdShipments.value]) ? [statementColumn] : []),
    ...(isWritable ? [editShipmentColumn] : [])
])

/**
 * The Device Support Statement for ONE DELIVERY (plan 7h).
 *
 * Same document and the same refusals as the release view's Export modal -- one helper
 * serves both -- but the dates and the provenance line come from this shipment: the units in
 * a delivery that overrode the model window do not run under the model's dates, and a
 * statement that named the batch while printing the model's dates would contradict itself
 * about which units it describes. ShipmentStatementContext carries the two together so a
 * caller cannot supply one without the other.
 */
// The shipment whose statement is being assembled, so the row that was clicked is the row
// that shows it. A bare boolean would spin every row at once.
const statementExportPending: Ref<string> = ref('')
async function exportShipmentStatement (r: any) {
    if (statementExportPending.value) return
    statementExportPending.value = r.uuid
    try {
        const w = r.effectiveDeviceSupportWindow
        const outcome = await generateDeviceSupportStatement(graphqlClient as any, {
            releaseUuid: r.release,
            orgUuid: orguuid.value,
            shipment: {
                // From the ROW, not the page selection: the context exists so one delivery's
                // provenance cannot be paired with another's window, and reading the site off
                // a page-level computed leaves that true only by accident of scope.
                siteName: sites.value.find((x: any) => x.uuid === r.site)?.name
                    || selectedSite.value?.name || null,
                shipDate: r.shipDate || null,
                // The batch as a reader can match it against a delivery note: the identifiers
                // recorded on the shipment, joined the same way this page shows them.
                batchIdentifier: summarizeIds(r.identifiers) || null,
                eos: w?.eos || null,
                eol: w?.eol || null,
                // The provenance line follows the DATES: an inherited window is the model's
                // statement about every unit, not this delivery's about its own.
                windowSource: w?.source || null
            }
        })
        if (!outcome.ok) {
            notify(outcome.kind === 'BLOCKED' ? 'warning' : 'error', 'Statement not generated', outcome.message)
            return
        }
        notify('success', 'Statement exported', `Device support statement downloaded (${outcome.fileName}).`)
    } catch (e: any) {
        notify('error', 'Statement not generated', e?.message || 'unknown error')
    } finally {
        statementExportPending.value = ''
    }
}
const softwareShipmentColumns = computed(() => [
    { key: 'shipDate', title: 'Date' },
    releaseShipmentColumn,
    { key: 'expiryDate', title: 'Expires', render: (r: any) => r.expiryDate || h('span', { class: 'subtle' }, '—') },
    {
        key: 'devices', title: 'Devices',
        render: (r: any) => {
            const ids: string[] = r.devices || []
            if (!ids.length) return h('span', { class: 'subtle', title: 'Shipped without devices' }, 'none')
            return h(NTooltip, { trigger: 'hover', placement: 'left' }, {
                trigger: () => h(NTag, { size: 'small', type: 'info' }, { default: () => `${ids.length} device${ids.length === 1 ? '' : 's'}` }),
                default: () => h('div', ids.map((u: string) => h('div', deviceLabel(u))))
            })
        }
    },
    {
        key: 'info', title: '',
        render: (r: any) => {
            const facts: [string, any][] = [['Notes', r.notes]].filter(([, v]) => v !== null && v !== undefined && v !== '') as [string, any][]
            return facts.length ? h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 460px;' }, {
                trigger: () => h(NIcon, { size: 16, style: 'color: #909399; vertical-align: middle;', onClick: (e: Event) => e.stopPropagation() }, { default: () => h(InfoCircle) }),
                default: () => h('div', facts.map(([k, v]) => h('div', { style: 'margin: 2px 0;' }, [h('strong', `${k}: `), String(v)])))
            }) : ''
        }
    },
    ...(anyWindowInForce(softwareShipments.value) ? [statementColumn] : []),
    ...(isWritable ? [editShipmentColumn] : [])
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
    createDefaultSite.value = true
    showClientModal.value = true
}
async function saveClient () {
    if (!clientForm.name) { notify('warning', 'Missing', 'Client name is required'); return }
    const input: any = { org: orguuid.value, name: clientForm.name, domain: clientForm.domain, notes: clientForm.notes, contact: clientForm.contact }
    const creating = !clientForm.uuid
    if (!creating) input.uuid = clientForm.uuid
    const resp: any = await graphqlClient.mutate({ mutation: gql`mutation upsertClient($input: ClientInput!) { upsertClient(input: $input) { uuid } }`, variables: { input } })
    let withSite = false
    if (creating && createDefaultSite.value && resp?.data?.upsertClient?.uuid) {
        const siteInput = { org: orguuid.value, client: resp.data.upsertClient.uuid, name: 'Default', contact: { ...clientForm.contact } }
        await graphqlClient.mutate({ mutation: gql`mutation upsertSite($input: SiteInput!) { upsertSite(input: $input) { uuid } }`, variables: { input: siteInput } })
        withSite = true
    }
    showClientModal.value = false; notify('success', 'Saved', `Client ${clientForm.name} saved${withSite ? ' with a Default site' : ''}`); await loadClients()
}
async function deleteClient (c: any) {
    await graphqlClient.mutate({ mutation: gql`mutation deleteClient($uuid: ID!) { deleteClient(uuid: $uuid) }`, variables: { uuid: c.uuid } })
    notify('info', 'Archived', `Client ${c.name} archived`)
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
    notify('info', 'Archived', `Site ${s.name} archived`)
    if (selectedSiteUuid.value === s.uuid) router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value, clientuuid: selectedClientUuid.value } })
    await loadSites(selectedClientUuid.value)
}

// ---- shipment ----
function cleanIds (ids: any[]) { return (ids || []).filter(i => i.idType && i.idValue) }
async function openShipModal (existing?: any) {
    shipForm.featureSet = ''; shipForm.release = ''; shipForm.deliverable = null; shipForm.shipDate = null; shipForm.quantity = 1
    shipForm.identifiers = []; shipForm.manufactureDate = null; shipForm.expiryDate = null; shipForm.devices = []
    shipProductUuid.value = ''; shipReleases.value = []; shipDeliverables.value = []
    await loadSiteDevices(selectedSiteUuid.value)
    shipChoices.value = []; Object.keys(shipChoiceSelections).forEach(k => delete shipChoiceSelections[k])
    resolvedIdentity.value = null
    editingShipmentUuid.value = existing?.uuid || ''
    editingShipment.value = existing || null
    shipForm.deviceWindowEos = null
    shipForm.deviceWindowEol = null
    shipForm.deviceWindowBaselineEos = null
    shipForm.deviceWindowBaselineEol = null
    if (existing) {
        shipForm.featureSet = existing.featureSet
        shipForm.release = existing.release
        shipForm.deliverable = existing.deliverable || null
        shipForm.shipDate = existing.shipDate || null
        shipForm.quantity = existing.quantity || 1
        shipForm.identifiers = (existing.identifiers || []).map((i: any) => ({ idType: i.idType, idValue: i.idValue }))
        shipForm.manufactureDate = existing.manufactureDate || null
        shipForm.expiryDate = existing.expiryDate || null
        shipForm.devices = [...(existing.devices || [])]
        // The batch's OWN override, not the effective window: seeding from the effective one
        // would turn an inherited value into an override the moment anything else was saved.
        shipForm.deviceWindowEos = existing.deviceSupportWindow?.eos || null
        shipForm.deviceWindowEol = existing.deviceSupportWindow?.eol || null
        shipForm.deviceWindowBaselineEos = shipForm.deviceWindowEos
        shipForm.deviceWindowBaselineEol = shipForm.deviceWindowEol
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
    const input: any = isSoftwareShipment.value
        ? { org: orguuid.value, site: selectedSiteUuid.value, featureSet: shipForm.featureSet, release: shipForm.release, quantity: 1, identifiers: [], devices: shipForm.devices || [] }
        : { org: orguuid.value, site: selectedSiteUuid.value, featureSet: shipForm.featureSet, release: shipForm.release, quantity: shipForm.quantity, identifiers: cleanIds(shipForm.identifiers) }
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
    if (shipForm.manufactureDate && !isSoftwareShipment.value) input.manufactureDate = shipForm.manufactureDate
    if (shipForm.expiryDate) input.expiryDate = shipForm.expiryDate
    // D7, same three rules as the component panel: unchanged sends nothing, blank-both after
    // something was declared sends the CLEAR FLAG, and an empty window object is never sent --
    // the server does not read it as a retraction, so it would leave the old override silently
    // in force while the form showed it gone.
    if (!isSoftwareShipment.value) {
        applyShipmentWindowToInput(input,
            { eos: shipForm.deviceWindowEos, eol: shipForm.deviceWindowEol },
            { eos: shipForm.deviceWindowBaselineEos, eol: shipForm.deviceWindowBaselineEol })
    }
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

// ---- fleet drift (org-wide, single query) ----
const fleetDrift: Ref<any[]> = ref([])
async function loadFleetDrift () {
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query driftedDevicesOfOrg($orgUuid: ID!) { driftedDevicesOfOrg(orgUuid: $orgUuid) {
                siteName clientName
                device { uuid site identifiers { idType idValue } plan { expectedRelease } actual { reportedRelease } observedState { driftedCount } }
            } }`,
            variables: { orgUuid: orguuid.value }, fetchPolicy: 'no-cache'
        })
        fleetDrift.value = resp.data.driftedDevicesOfOrg || []
    } catch (e) { /* backend without the query yet */ }
}
const fleetDriftColumns = [
    { key: 'unit', title: 'Unit', render: (r: any) => summarizeIds(r.device?.identifiers) || shortUuid(r.device?.uuid) },
    { key: 'client', title: 'Client', render: (r: any) => r.clientName || '—' },
    { key: 'site', title: 'Site', render: (r: any) => r.siteName || '—' },
    {
        key: 'cause', title: 'Drift source',
        render: (r: any) => {
            const observed = (r.device?.observedState?.driftedCount || 0) > 0
            const reported = r.device?.actual?.reportedRelease && r.device?.plan?.expectedRelease
                && r.device.actual.reportedRelease !== r.device.plan.expectedRelease
            const parts = []
            if (observed) parts.push(`observed software (${r.device.observedState.driftedCount} item${r.device.observedState.driftedCount > 1 ? 's' : ''})`)
            if (reported) parts.push('reported release')
            return parts.join(' + ') || '—'
        }
    }
]
const driftRowProps = (r: any) => ({ style: 'cursor: pointer;', onClick: () => router.push({ name: 'DeviceView', params: { deviceuuid: r.device.uuid } }) })

// ---- fleet support risk (D7, read-only, paged, follows the client / site selection) ----
// Three facts the template needs kept apart: has ANY answer arrived (`fleetRiskLoaded`, gates
// the whole panel so an empty frame never reads as "no risk"), does the backend have the
// query at all (`fleetRisk.supported`, hides it for good on CE), and did the LAST request
// fail for some other reason (`fleetRiskError`, shown over the previous rows with a retry --
// a 403 or a timeout is not a reason to make the panel disappear).
const fleetRisk: Ref<FleetRiskResult> = ref({ supported: true, degraded: false, rows: [], total: 0, atRiskTotal: null })
const fleetRiskLoaded = ref(false)
const fleetRiskLoading = ref(false)
const fleetRiskError: Ref<string> = ref('')
const fleetRiskPage = ref(1)  // one-based for n-data-table; the server is zero-based
const fleetRiskPageSummary = computed(() => summarizeFleetRiskPage(fleetRisk.value.rows))
const fleetRiskHeadlineText = computed(() => fleetRiskHeadline(fleetRisk.value.total, fleetRisk.value.rows, fleetRisk.value.atRiskTotal))
// The fleet-wide count when the server gave one; the page's when it did not (CORE).
const fleetRiskAnyAtRisk = computed(() => fleetRisk.value.atRiskTotal !== null
    ? fleetRisk.value.atRiskTotal > 0
    : fleetRiskPageSummary.value.atRisk > 0)
const fleetRiskScopeLabel = computed(() => selectedSite.value
    ? `at site ${selectedSite.value.name}`
    : (selectedClient.value ? `for client ${selectedClient.value.name}` : 'org-wide'))
const fleetRiskPagination = computed(() => ({
    page: fleetRiskPage.value,
    pageSize: FLEET_RISK_DEFAULT_PAGE_SIZE,
    itemCount: fleetRisk.value.total
}))
// Unit identifiers and the site / client names ride on the row (FULL). Behind a presence
// guard like the evidence fields: a CORE-served page names the unit by its short id, and a
// FULL page with a null name is a unit at an archived site, shown by id so it is not lost.
const fleetRiskUnitLabel = (r: FleetRiskRow) => summarizeIds('identifiers' in r ? (r.identifiers || []) : []) || shortUuid(r.device)
const fleetRiskSiteName = (r: FleetRiskRow) => ('siteName' in r && r.siteName) || (r.site ? shortUuid(r.site) : '')
// No id fallback, unlike the site: the row carries no client uuid, so a unit whose CLIENT was
// archived reads the same as a site with no client at all. The panel will not invent a
// distinction it was not given; closing it needs `client: ID` on the row, backend-side.
const fleetRiskClientName = (r: FleetRiskRow) => ('clientName' in r && r.clientName) || ''
// Every load takes a ticket; only the newest ticket may write. A client switch while a
// slow org-wide page is in flight would otherwise land the org-wide rows under the client's
// scope label -- a wrong roster with a confident heading.
let fleetRiskTicket = 0
// `page` is one-based (from n-data-table); the server counts from zero.
async function loadFleetRisk (page: number = 1) {
    const ticket = ++fleetRiskTicket
    fleetRiskLoading.value = true
    try {
        const result = await loadDevicesAtSupportRisk(graphqlClient, {
            orgUuid: orguuid.value,
            clientUuid: selectedClientUuid.value || null,
            siteUuid: selectedSiteUuid.value || null,
            page: Math.max(0, page - 1),
            size: FLEET_RISK_DEFAULT_PAGE_SIZE
        }, { skipFull: fleetRisk.value.degraded })  // once FULL is rejected, stop asking
        if (ticket !== fleetRiskTicket) return
        fleetRisk.value = result
        fleetRiskPage.value = page
        fleetRiskError.value = ''
        if (result.supported && result.rows.length) {
            await resolveReleaseInfos(result.rows.map(r => r.release || ''))
        }
    } catch (e: any) {
        if (ticket !== fleetRiskTicket) return
        // Not schema drift (that is `supported: false`): a real failure the operator must
        // see, over the rows that were there, with a way to ask again.
        fleetRiskError.value = e?.message || 'unknown error'
        notify('error', 'Failed', `Could not load the fleet support risk view: ${fleetRiskError.value}`)
    } finally {
        if (ticket === fleetRiskTicket) {
            fleetRiskLoading.value = false
            fleetRiskLoaded.value = true
        }
    }
}
const blankCell = () => h('span', { class: 'subtle' }, '\u2014')
const fleetRiskColumns = [
    { key: 'unit', title: 'Unit', minWidth: 220, render: (r: FleetRiskRow) => fleetRiskUnitLabel(r) },
    { key: 'client', title: 'Client', minWidth: 110, render: (r: FleetRiskRow) => fleetRiskClientName(r) || blankCell() },
    { key: 'site', title: 'Site', minWidth: 110, render: (r: FleetRiskRow) => fleetRiskSiteName(r) || blankCell() },
    {
        key: 'release', title: 'Release judged',
        render: (r: FleetRiskRow) => {
            if (!r.release) return h('span', { class: 'subtle' }, 'none')
            const label = releaseLabel(r.release) || shortUuid(r.release)
            // Presence-guarded: absent on a CORE-served page, and a plan is not ground truth.
            const st = 'releaseSource' in r ? releaseSourceTag(r.releaseSource) : null
            const source = st
                ? h(NTag, { size: 'tiny', type: st.type, style: 'margin-left: 6px;' }, { default: () => st.label })
                : null
            return h('span', [label, source])
        }
    },
    {
        key: 'window', title: 'Device window',
        render: (r: FleetRiskRow) => ('window' in r ? fleetWindowLabel(r.window) : '') || blankCell()
    },
    {
        key: 'risk', title: 'Risk',
        render: (r: FleetRiskRow) => {
            const t = fleetRiskTag(r.risk)
            const tag = h(NTag, { size: 'small', type: t.type }, { default: () => t.label })
            if (!isDeviceRiskFlagged(r.risk)) return tag
            return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 420px;' }, {
                trigger: () => tag, default: () => FLEET_RISK_DETAIL[r.risk]
            })
        }
    },
    { key: 'eos', title: 'Earliest component EOS', render: (r: FleetRiskRow) => ('earliestComponentEos' in r && r.earliestComponentEos) || blankCell() },
    { key: 'driving', title: 'Components driving risk', render: (r: FleetRiskRow) => ('componentsDrivingRisk' in r && typeof r.componentsDrivingRisk === 'number') ? String(r.componentsDrivingRisk) : blankCell() }
]
const fleetRiskRowProps = (r: FleetRiskRow) => ({ style: 'cursor: pointer;', onClick: () => router.push({ name: 'DeviceView', params: { deviceuuid: r.device } }) })
// The selection is the filter: a client or site change re-asks from page 1. Only a backend
// without the query stops the asking; a failed load is retried on the next selection.
watch([selectedClientUuid, selectedSiteUuid], () => { if (fleetRisk.value.supported) loadFleetRisk(1) })

onMounted(async () => {
    loadOrgClassification()
    store.dispatch('fetchProducts', orguuid.value)
    loadFleetDrift()
    loadFleetRisk(1)
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
.fleetDriftPanel { margin: 8px 1% 4px 1%; padding: 8px 10px; border: 1px solid #f0a020; border-radius: 6px; background: #fffaf0; }
.fleetRiskPanel { margin: 8px 1% 4px 1%; padding: 8px 10px; border: 1px solid #d9dde1; border-radius: 6px; background: #fafbfc; }
.identityBox { background: #f4f8fb; border-radius: 6px; padding: 6px 8px; margin-bottom: 8px; font-size: 13px; }
.icons { margin: 4px; vertical-align: middle; }
.clickable { cursor: pointer; }
:deep(.selectedRow td) { background-color: #f1f1f1 !important; }
:deep(.shipLink) { color: #2080f0; cursor: pointer; }
:deep(.shipLink:hover) { text-decoration: underline; }
</style>
