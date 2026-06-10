<template>
    <div class="deviceView">
        <n-card v-if="device" size="small">
            <template #header>
                <n-space align="center">
                    <h2 style="margin: 0;">Device {{ primarySerial || shortUuid(device.uuid) }}</h2>
                    <n-tag v-if="device.versionDrift" type="warning" size="small">version drift</n-tag>
                    <n-tag v-else-if="device.observedState" type="success" size="small">in sync</n-tag>
                    <n-tag v-if="deviceClass && deviceClass !== 'NONE'" size="small" type="info">{{ deviceClass }}</n-tag>
                </n-space>
            </template>
            <n-space vertical size="small">
                <div>
                    <strong>Identifiers: </strong>
                    <n-tag v-for="id in device.identifiers" :key="id.idType + id.idValue" size="small" style="margin-right: 6px;">
                        {{ id.idType }}: {{ id.idValue }}
                    </n-tag>
                    <span v-if="!device.identifiers || !device.identifiers.length" class="subtle">none</span>
                </div>
                <div v-if="expectedRelease">
                    <strong>Product release: </strong>
                    <router-link :to="{ name: 'ReleaseView', params: { uuid: expectedRelease.uuid } }">
                        {{ expectedRelease.componentDetails?.name }} {{ expectedRelease.version }}
                    </router-link>
                    <n-tag v-if="expectedRelease.hardware" size="small" type="info" style="margin-left: 8px;">hardware</n-tag>
                </div>
                <div v-if="shipment">
                    <strong>Shipment: </strong>{{ shortUuid(shipment.uuid) }}
                    <span v-if="shipment.shipDate"> · shipped {{ shipment.shipDate }}</span>
                    <router-link :to="{ name: 'DistributionOfOrg', params: { orguuid: orguuid } }" style="margin-left: 8px;">open distribution</router-link>
                </div>
                <div v-if="device.notes"><strong>Notes: </strong>{{ device.notes }}</div>
            </n-space>
        </n-card>

        <n-tabs v-if="device" type="segment" animated style="margin-top: 10px;">
            <n-tab-pane name="software" tab="Software State">
                <n-space vertical size="small">
                    <div v-if="observed">
                        <n-space align="center" size="small">
                            <strong>Last report:</strong> {{ formatDateTime(observed.reportedAt) }} ({{ observed.source }})
                            <n-tag size="small" type="success">{{ observed.matchedCount || 0 }} matched</n-tag>
                            <n-tag size="small" :type="(observed.driftedCount || 0) > 0 ? 'warning' : 'default'">{{ observed.driftedCount || 0 }} drifted</n-tag>
                            <n-tag size="small">{{ observed.unknownCount || 0 }} unknown</n-tag>
                        </n-space>
                        <n-data-table :columns="observedColumns" :data="observed.items || []" size="small" :pagination="{ pageSize: 15 }" style="margin-top: 8px;" />
                    </div>
                    <div v-else>
                        <p>No observed software state reported yet.</p>
                        <p class="subtle">Agents phone home via the API-key mutation <code>reportDeviceState(report: { device: "{{ device.uuid }}", rawText: "&lt;k8s image list&gt;" })</code> —
                            observed digests are reconciled against the expected release tree.</p>
                    </div>
                </n-space>
            </n-tab-pane>
            <n-tab-pane name="hardware" :tab="`Hardware (HBOM)${twinHbom.length ? ' · ' + twinHbom.length : ''}`">
                <n-space style="margin-bottom: 8px;" align="center">
                    <n-input v-model:value="twinFilter" placeholder="Filter by name / part number / board location" clearable size="small" style="width: 420px;" />
                    <n-tag v-if="replacedCount" type="warning" size="small">{{ replacedCount }} replaced</n-tag>
                    <n-tag v-if="unresolvedCount" type="error" size="small">{{ unresolvedCount }} unresolved choice{{ unresolvedCount > 1 ? 's' : '' }}</n-tag>
                    <n-switch v-model:value="showAlternates" size="small" />
                    <span class="subtle">show approved alternates</span>
                </n-space>
                <n-data-table :columns="twinColumns" :data="filteredTwinHbom" :row-class-name="twinRowClass" :pagination="{ pageSize: 25 }" size="small" />
            </n-tab-pane>
            <n-tab-pane name="events" :tab="`Events${events.length ? ' · ' + events.length : ''}`">
                <n-button v-if="isWritable" size="small" type="primary" style="margin-bottom: 8px;" @click="openEventModal">Record event</n-button>
                <n-data-table :columns="eventColumns" :data="events" size="small" :pagination="{ pageSize: 15 }" />
            </n-tab-pane>
            <n-tab-pane v-if="deviceClass === 'MEDICAL_TRACKED'" name="tracking" tab="Tracking (821)">
                <n-space vertical size="small" v-if="device.tracking">
                    <div><strong>Received: </strong>{{ device.tracking.receivedDate || '—' }}</div>
                    <div><strong>Patient / recipient: </strong>{{ device.tracking.patientId || '—' }}</div>
                    <div><strong>Disposition: </strong>{{ device.tracking.disposition || '—' }}
                        <span v-if="device.tracking.dispositionDate"> ({{ device.tracking.dispositionDate }})</span></div>
                </n-space>
                <p v-else>No tracking record yet. Manage 821 tracking from the Distribution page.</p>
            </n-tab-pane>
        </n-tabs>

        <n-modal v-model:show="showEventModal" preset="dialog" :show-icon="false" title="Record device event" style="width: 620px;">
            <n-form>
                <n-form-item label="Event type">
                    <n-select v-model:value="eventForm.eventType" :options="eventTypeOptions" />
                </n-form-item>
                <n-form-item label="Hardware component (HBOM node)">
                    <n-select v-model:value="eventForm.hbomRef" filterable clearable :options="hbomNodeOptions" placeholder="Pick the affected node (optional for software events)" />
                </n-form-item>
                <n-form-item label="Event date">
                    <n-input v-model:value="eventForm.date" placeholder="YYYY-MM-DD (defaults to now)" />
                </n-form-item>
                <template v-if="eventForm.eventType === 'REPLACEMENT'">
                    <n-form-item v-if="pickedChoiceAlternates.length" label="Replace with (approved alternates for this slot)">
                        <n-space vertical size="small" style="width: 100%;">
                            <span class="subtle" v-if="pickedNode">
                                Choice slot{{ pickedNode.component.boardLocation ? ' @ ' + pickedNode.component.boardLocation : '' }}{{ pickedNode.component.quantity ? ' · qty ' + pickedNode.component.quantity : '' }} — pick from the approved options:
                            </span>
                            <n-select :value="null" :options="choiceAlternateOptions" placeholder="Pick an approved alternate to fill the fields below" @update:value="applyChoiceAlternate" />
                        </n-space>
                    </n-form-item>
                    <n-form-item label="Replacement part #"><n-input v-model:value="eventForm.replacement.partNumber" placeholder="Part number installed" /></n-form-item>
                    <n-form-item label="Replacement lot"><n-input v-model:value="eventForm.replacement.lot" placeholder="Lot of the installed part" /></n-form-item>
                    <n-form-item label="Replacement serial"><n-input v-model:value="eventForm.replacement.serial" placeholder="Serial of the installed part" /></n-form-item>
                    <n-form-item label="Replacement manufacturer"><n-input v-model:value="eventForm.replacement.manufacturer" placeholder="Manufacturer of the installed part" /></n-form-item>
                </template>
                <n-form-item label="Notes">
                    <n-input v-model:value="eventForm.notes" type="textarea" :rows="2" placeholder="Symptom / action taken" />
                </n-form-item>
            </n-form>
            <template #action><n-button type="primary" @click="saveEvent">Save event</n-button></template>
        </n-modal>
    </div>
</template>

<script lang="ts">
export default { name: 'DeviceView' }
</script>
<script lang="ts" setup>
import { ref, Ref, computed, reactive, h, onMounted } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { NButton, NCard, NDataTable, NForm, NFormItem, NInput, NModal, NSelect, NSpace, NSwitch, NTabPane, NTabs, NTag, NTooltip, useNotification, NotificationType } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'

const route = useRoute()
const store = useStore()
const notification = useNotification()

const deviceuuid: Ref<string> = ref(route.params.deviceuuid ? route.params.deviceuuid.toString() : '')
const device: Ref<any> = ref(null)
const shipment: Ref<any> = ref(null)
const expectedRelease: Ref<any> = ref(null)
const deviceClass: Ref<string> = ref('')
const twinHbom: Ref<any[]> = ref([])
const events: Ref<any[]> = ref([])
const twinFilter = ref('')

const orguuid = computed(() => device.value?.org || '')
const myUser = store.getters.myuser
const isWritable = computed(() => device.value ? commonFunctions.isWritable(device.value.org, myUser, 'ORGANIZATION') : false)

const observed = computed(() => device.value?.observedState || null)
const primarySerial = computed(() => {
    const ids = device.value?.identifiers || []
    const serial = ids.find((i: any) => i.idType === 'SERIAL') || ids[0]
    return serial ? serial.idValue : ''
})
const replacedCount = computed(() => twinHbom.value.filter((r: any) => r.twinStatus === 'REPLACED').length)
const unresolvedCount = computed(() => twinHbom.value.filter((r: any) => r.twinStatus === 'UNRESOLVED_CHOICE').length)
const showAlternates = ref(false)
const filteredTwinHbom = computed(() => {
    let rows = twinHbom.value
    if (!showAlternates.value) rows = rows.filter((r: any) => r.twinStatus !== 'ALTERNATE')
    const f = twinFilter.value.toLowerCase()
    if (!f) return rows
    return rows.filter((r: any) => {
        const c = r.component || {}
        return [c.name, (c.partNumbers || []).join(' '), c.boardLocation, c.manufacturer]
            .filter(Boolean).join(' ').toLowerCase().includes(f)
    })
})
// Event anchor picker: only nodes actually installed on this unit.
const hbomNodeOptions = computed(() => twinHbom.value
    .filter((r: any) => r.component?.bomRef && (r.twinStatus === 'ORIGINAL' || r.twinStatus === 'REPLACED'))
    .map((r: any) => ({
        label: `${r.component.name || r.component.bomRef}${r.component.boardLocation ? ' @ ' + r.component.boardLocation : ''}`,
        value: r.component.bomRef
    })))
// Approved alternates for the picked node's choice slot (CDX #929): when the
// damaged part sits in a component-choice, the repair flow presents the whole
// slot (options + board location + quantity) instead of a single part.
const pickedNode = computed(() => twinHbom.value.find((r: any) => r.component?.bomRef === eventForm.hbomRef))
const pickedChoiceAlternates = computed(() => {
    const choiceRef = pickedNode.value?.choiceRef
    if (!choiceRef) return []
    return twinHbom.value.filter((r: any) => r.choiceRef === choiceRef && r.component?.bomRef)
})
const choiceAlternateOptions = computed(() => pickedChoiceAlternates.value.map((r: any) => ({
    label: `${r.component.name}${(r.component.partNumbers || []).length ? ' · ' + r.component.partNumbers.join(', ') : ''}${r.component.manufacturer ? ' · ' + r.component.manufacturer : ''}${r.twinStatus === 'ALTERNATE' ? '' : ' (currently installed)'}`,
    value: r.component.bomRef
})))

const shortUuid = (u: string) => u ? u.substring(0, 8) : ''
function formatDateTime (d: any): string { return d ? new Date(d).toLocaleString() : '—' }
function notify (type: NotificationType, title: string, content: string) { notification[type]({ content: title, meta: content, duration: 3500 }) }

const statusTagType: Record<string, string> = { MATCHED: 'success', DRIFTED: 'warning', FOREIGN_COMPONENT: 'error', UNKNOWN: 'default' }
const observedColumns = [
    { key: 'identifier', title: 'Observed item', render: (r: any) => r.identifier || h('span', { class: 'subtle' }, '(digest only)') },
    { key: 'digest', title: 'Digest', render: (r: any) => h('code', {}, (r.digest || '').replace('sha256:', '').substring(0, 12)) },
    { key: 'status', title: 'Status', render: (r: any) => h(NTag, { size: 'small', type: (statusTagType[r.status] || 'default') as any }, { default: () => r.status }) },
    { key: 'componentName', title: 'Component' },
    { key: 'observedVersion', title: 'Observed' },
    { key: 'expectedVersion', title: 'Expected' }
]

function twinRowClass (r: any): string {
    if (r.twinStatus === 'REPLACED') return 'twin-replaced-row'
    if (r.twinStatus === 'UNRESOLVED_CHOICE') return 'twin-unresolved-row'
    if (r.twinStatus === 'ALTERNATE') return 'twin-alternate-row'
    return ''
}
const twinColumns = [
    { key: 'name', title: 'Component', render: (r: any) => r.component?.name },
    { key: 'category', title: 'Category', render: (r: any) => r.component?.category },
    { key: 'partNumbers', title: 'Part #', render: (r: any) => (r.component?.partNumbers || []).join(', ') },
    { key: 'boardLocation', title: 'Board loc', render: (r: any) => r.component?.boardLocation },
    { key: 'quantity', title: 'Qty', render: (r: any) => r.component?.quantity },
    {
        key: 'twinStatus', title: 'Twin',
        render: (r: any) => {
            if (r.twinStatus === 'ALTERNATE') {
                return h(NTooltip, { trigger: 'hover', placement: 'left' }, {
                    trigger: () => h(NTag, { size: 'small', style: 'opacity: 0.65;' }, { default: () => 'alternate' }),
                    default: () => 'Approved option of this choice slot — not selected for this unit\'s lot'
                })
            }
            if (r.twinStatus === 'UNRESOLVED_CHOICE') {
                return h(NTooltip, { trigger: 'hover', placement: 'left' }, {
                    trigger: () => h(NTag, { size: 'small', type: 'error' }, { default: () => `UNRESOLVED [${r.component?.operator || 'XOR'}]` }),
                    default: () => 'This component-choice slot was never fixed for the unit\'s lot — resolve choices when producing/shipping the lot'
                })
            }
            if (r.twinStatus !== 'REPLACED') return h(NTag, { size: 'small' }, { default: () => 'original' })
            const rep = r.replacement || {}
            const details = [
                ['Replaced on', formatDateTime(r.eventDate)],
                ['Part #', rep.partNumber], ['Lot', rep.lot], ['Serial', rep.serial],
                ['Manufacturer', rep.manufacturer], ['Notes', r.eventNotes]
            ].filter(([, v]) => v)
            return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 420px;' }, {
                trigger: () => h(NTag, { size: 'small', type: 'warning', style: 'cursor: pointer;' }, { default: () => 'REPLACED' }),
                default: () => h('div', details.map(([k, v]) => h('div', { style: 'margin: 2px 0;' }, [h('strong', `${k}: `), String(v)])))
            })
        }
    }
]

const eventTypeOptions = ['FAILURE', 'REPAIR', 'REPLACEMENT', 'INSPECTION', 'OBSERVATION'].map(v => ({ label: v, value: v }))
const eventTypeTag: Record<string, string> = { FAILURE: 'error', REPAIR: 'info', REPLACEMENT: 'warning', INSPECTION: 'default', OBSERVATION: 'default' }
const eventColumns = [
    { key: 'date', title: 'Date', render: (r: any) => formatDateTime(r.date || r.createdDate) },
    { key: 'eventType', title: 'Type', render: (r: any) => h(NTag, { size: 'small', type: (eventTypeTag[r.eventType] || 'default') as any }, { default: () => r.eventType }) },
    { key: 'componentName', title: 'Component', render: (r: any) => r.componentName || r.hbomRef || r.purl || '—' },
    {
        key: 'replacement', title: 'Replacement',
        render: (r: any) => {
            const rep = r.replacement
            if (!rep || (!rep.partNumber && !rep.lot && !rep.serial)) return '—'
            return [rep.partNumber, rep.lot ? `lot ${rep.lot}` : null, rep.serial ? `sn ${rep.serial}` : null].filter(Boolean).join(' · ')
        }
    },
    { key: 'notes', title: 'Notes' }
]

const DEVICE_FIELDS = `uuid org shippedProduct site notes versionDrift
    identifiers { idType idValue }
    plan { expectedRelease }
    actual { reportedRelease reportedAt source }
    tracking { receivedDate patientId disposition dispositionDate }
    observedState { reportedAt source matchedCount driftedCount unknownCount
        items { identifier digest status componentName observedRelease observedVersion expectedRelease expectedVersion } }`

async function loadDevice () {
    const resp: any = await graphqlClient.query({
        query: gql`query device($uuid: ID!) { device(uuid: $uuid) { ${DEVICE_FIELDS} } }`,
        variables: { uuid: deviceuuid.value }, fetchPolicy: 'no-cache'
    })
    device.value = resp.data.device
}

async function loadShipmentAndRelease () {
    if (!device.value?.shippedProduct) return
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query shippedProduct($uuid: ID!) { shippedProduct(uuid: $uuid) { uuid org featureSet release shipDate quantity identifiers { idType idValue } } }`,
            variables: { uuid: device.value.shippedProduct }, fetchPolicy: 'no-cache'
        })
        shipment.value = resp.data.shippedProduct
    } catch (e) { /* ignore */ }
    const releaseUuid = device.value.plan?.expectedRelease || shipment.value?.release
    if (releaseUuid) {
        try {
            const resp: any = await graphqlClient.query({
                query: gql`query release($uuid: ID!) { release(releaseUuid: $uuid) { uuid version hardware componentDetails { uuid name } } }`,
                variables: { uuid: releaseUuid }, fetchPolicy: 'no-cache'
            })
            expectedRelease.value = resp.data.release
        } catch (e) { /* ignore */ }
    }
    if (shipment.value?.featureSet) {
        try {
            const resp: any = await graphqlClient.query({
                query: gql`query fsdi($fs: ID!, $org: ID!) { featureSetDeviceIdentity(featureSetUuid: $fs, orgUuid: $org) { deviceClass udiDi } }`,
                variables: { fs: shipment.value.featureSet, org: device.value.org }, fetchPolicy: 'no-cache'
            })
            deviceClass.value = resp.data.featureSetDeviceIdentity?.deviceClass || ''
        } catch (e) { /* ignore */ }
    }
}

async function loadTwinHbom () {
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query deviceHbomComponents($deviceUuid: ID!) { deviceHbomComponents(deviceUuid: $deviceUuid) {
                twinStatus eventDate eventNotes choiceRef
                replacement { partNumber lot serial manufacturer }
                component { uuid bomRef type operator name version description category subcategory partNumbers manufacturer boardLocation deviceType quantity parentRef isRoot }
            } }`,
            variables: { deviceUuid: deviceuuid.value }, fetchPolicy: 'no-cache'
        })
        twinHbom.value = resp.data.deviceHbomComponents || []
    } catch (e) { /* ignore */ }
}

async function loadEvents () {
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query deviceEventsOfDevice($deviceUuid: ID!) { deviceEventsOfDevice(deviceUuid: $deviceUuid) {
                uuid eventType date createdDate hbomRef componentName purl notes
                replacement { partNumber lot serial manufacturer }
            } }`,
            variables: { deviceUuid: deviceuuid.value }, fetchPolicy: 'no-cache'
        })
        events.value = resp.data.deviceEventsOfDevice || []
    } catch (e) { /* ignore */ }
}

const showEventModal = ref(false)
const eventForm = reactive<any>({ eventType: 'FAILURE', hbomRef: null, date: '', notes: '', replacement: { partNumber: '', lot: '', serial: '', manufacturer: '' } })
function openEventModal () {
    eventForm.eventType = 'FAILURE'; eventForm.hbomRef = null; eventForm.date = ''; eventForm.notes = ''
    eventForm.replacement = { partNumber: '', lot: '', serial: '', manufacturer: '' }
    showEventModal.value = true
}
// Picking an approved alternate from the choice slot fills the replacement facts.
function applyChoiceAlternate (bomRef: string) {
    const alt = pickedChoiceAlternates.value.find((r: any) => r.component?.bomRef === bomRef)
    if (!alt) return
    eventForm.replacement.partNumber = (alt.component.partNumbers || [])[0] || alt.component.name || ''
    eventForm.replacement.manufacturer = alt.component.manufacturer || ''
}
async function saveEvent () {
    const input: any = { device: deviceuuid.value, eventType: eventForm.eventType }
    if (eventForm.hbomRef) {
        input.hbomRef = eventForm.hbomRef
        const node = twinHbom.value.find((r: any) => r.component?.bomRef === eventForm.hbomRef)
        if (node?.component?.name) input.componentName = node.component.name
    }
    if (eventForm.date) input.date = new Date(eventForm.date).toISOString()
    if (eventForm.notes) input.notes = eventForm.notes
    if (eventForm.eventType === 'REPLACEMENT') {
        const rep = Object.fromEntries(Object.entries(eventForm.replacement).filter(([, v]) => v))
        if (Object.keys(rep).length) input.replacement = rep
    }
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation addDeviceEvent($input: DeviceEventInput!) { addDeviceEvent(input: $input) { uuid } }`,
            variables: { input }
        })
        showEventModal.value = false
        notify('success', 'Recorded', 'Device event recorded')
        await Promise.all([loadEvents(), loadTwinHbom()])
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

onMounted(async () => {
    await loadDevice()
    if (!device.value) return
    await Promise.all([loadShipmentAndRelease(), loadTwinHbom(), loadEvents()])
})
</script>

<style scoped lang="scss">
.deviceView {
    width: 97%;
    margin-left: 1.5%;
    margin-top: 10px;
}
.subtle { color: #909399; }
:deep(.twin-replaced-row td) {
    background-color: rgba(240, 160, 32, 0.12) !important;
}
:deep(.twin-unresolved-row td) {
    background-color: rgba(208, 48, 80, 0.10) !important;
}
:deep(.twin-alternate-row td) {
    opacity: 0.6;
}
</style>
