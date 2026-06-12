<template>
    <div class="deviceView">
        <n-card v-if="device" size="small">
            <template #header>
                <n-space align="center">
                    <h2 v-if="expectedRelease" style="margin: 0;">
                        <router-link :to="{ name: 'ProductsOfOrg', params: { orguuid: orguuid, compuuid: expectedRelease.componentDetails?.uuid } }">{{ expectedRelease.componentDetails?.name }}</router-link>
                        <span> — </span>
                        <router-link :to="{ name: 'ProductsOfOrg', params: { orguuid: orguuid, compuuid: expectedRelease.componentDetails?.uuid, branchuuid: expectedRelease.branchDetails?.uuid } }">{{ expectedRelease.branchDetails?.name }}</router-link>
                        <span> — </span>
                        <router-link :to="{ name: 'ReleaseView', params: { uuid: expectedRelease.uuid } }">{{ expectedRelease.version }}</router-link>
                    </h2>
                    <h2 v-else style="margin: 0;">Device {{ primarySerial || shortUuid(device.uuid) }}</h2>
                    <n-tag v-if="expectedRelease?.hardware" size="small" type="info">hardware</n-tag>
                    <n-tag v-if="device.versionDrift" type="warning" size="small">version drift</n-tag>
                    <n-tag v-else-if="observed && observed.items && observed.items.length" type="success" size="small">in sync</n-tag>
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
                <div>
                    <strong>Device uuid: </strong><code>{{ device.uuid }}</code>
                    <n-icon size="15" style="cursor: pointer; vertical-align: middle; margin-left: 6px;" title="Copy device uuid" @click="copyUuid"><Copy /></n-icon>
                </div>
                <div v-if="shipment">
                    <strong>Shipment: </strong>{{ shortUuid(shipment.uuid) }}
                    <span v-if="shipment.shipDate"> · shipped {{ shipment.shipDate }}</span>
                    <n-tooltip v-if="lotSelections.length" trigger="hover" placement="right" style="max-width: 460px;">
                        <template #trigger>
                            <n-tag size="small" type="info" style="margin-left: 8px; cursor: pointer;">lot selections</n-tag>
                        </template>
                        <div>
                            <div v-for="s in lotSelections" :key="s" style="margin: 2px 0;">{{ s }}</div>
                        </div>
                    </n-tooltip>
                    <router-link :to="distributionLink" style="margin-left: 8px;">open distribution</router-link>
                </div>
                <div v-if="device.notes"><strong>Notes: </strong>{{ device.notes }}</div>
                <n-space v-if="isWritable" size="small" align="center" style="margin-top: 4px;">
                    <n-select v-model:value="expectedReleaseEdit" filterable clearable :options="featureSetReleases" placeholder="expected release" size="small" style="width: 300px;" />
                    <n-button size="small" @click="saveExpectedRelease" title="Set the release this unit is expected to run (e.g. after a field upgrade)">Set expected release</n-button>
                    <n-popconfirm @positive-click="deleteThisDevice">
                        <template #trigger><n-button size="small" type="error">Delete device</n-button></template>
                        Delete this device and its twin history?
                    </n-popconfirm>
                </n-space>
            </n-space>
        </n-card>

        <n-tabs v-if="device" type="segment" animated style="margin-top: 10px;">
            <n-tab-pane name="software" tab="Software (SBOM)">
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
                    <p v-else>No observed software state reported yet — agents phone home via the API-key
                        <code>reportDeviceState</code> mutation, or post a report from the Software Events tab.</p>
                </n-space>
            </n-tab-pane>
            <n-tab-pane name="hardware" :tab="`Hardware (HBOM)${installedCount ? ' · ' + installedCount : ''}`">
                <n-space style="margin-bottom: 8px;" align="center">
                    <n-input v-model:value="twinFilter" placeholder="Filter by name / part number / board location" clearable size="small" style="width: 420px;" />
                    <n-tag v-if="replacedCount" type="warning" size="small">{{ replacedCount }} replaced</n-tag>
                    <n-tag v-if="unresolvedCount" type="error" size="small">{{ unresolvedCount }} unresolved choice{{ unresolvedCount > 1 ? 's' : '' }}</n-tag>
                    <n-switch v-model:value="showAlternates" size="small" />
                    <span class="subtle">show approved alternates</span>
                </n-space>
                <n-data-table :columns="twinColumns" :data="filteredTwinHbom" :row-class-name="twinRowClass" :pagination="{ pageSize: 25 }" size="small" />
            </n-tab-pane>
            <n-tab-pane name="softwareEvents" :tab="`Software Events${softwareEvents.length ? ' · ' + softwareEvents.length : ''}`">
                <n-space vertical size="small">
                    <div v-if="isWritable" style="max-width: 760px;">
                        <n-space align="center" size="small" style="margin-bottom: 4px;">
                            <strong>Report observed state (JSON)</strong>
                            <n-tooltip trigger="hover" placement="right" style="max-width: 520px;">
                                <template #trigger>
                                    <n-icon size="16" style="color: #909399; cursor: pointer;"><InfoCircle /></n-icon>
                                </template>
                                <div>
                                    <p style="margin: 2px 0;"><strong>Accepted shapes:</strong></p>
                                    <p style="margin: 2px 0;">1. Object: <code>{"observed": [{"identifier": "registry/drone-fw:1.2", "digest": "sha256:&lt;64-hex&gt;"}], "rawText": "..."}</code></p>
                                    <p style="margin: 2px 0;">2. Bare array of <code>{identifier, digest}</code> items (treated as <code>observed</code>)</p>
                                    <p style="margin: 2px 0;">3. Plain text — one image per line, <code>name@sha256:&lt;digest&gt;</code> (e.g. a k8s image list; treated as <code>rawText</code>)</p>
                                    <p style="margin: 2px 0;">Each digest is reconciled against the expected release tree → MATCHED / DRIFTED / FOREIGN_COMPONENT / UNKNOWN.</p>
                                    <p style="margin: 2px 0;">Same payload as the programmatic API-key mutation <code>reportDeviceState</code>.</p>
                                </div>
                            </n-tooltip>
                        </n-space>
                        <n-input v-model:value="stateReportText" type="textarea" :rows="4"
                            placeholder='{"observed":[{"identifier":"registry/drone-fw:1.2","digest":"sha256:..."}]}  — or paste a k8s image list' />
                        <n-button size="small" type="primary" style="margin-top: 6px;" :loading="stateReportPending" @click="submitStateReport">Submit report</n-button>
                    </div>
                    <n-data-table :columns="softwareEventColumns" :data="softwareEvents" size="small" :pagination="{ pageSize: 15 }" />
                </n-space>
            </n-tab-pane>
            <n-tab-pane name="hardwareEvents" :tab="`Hardware Events${hardwareEvents.length ? ' · ' + hardwareEvents.length : ''}`">
                <n-button v-if="isWritable" size="small" type="primary" style="margin-bottom: 8px;" @click="openEventModal">Record event</n-button>
                <n-data-table :columns="eventColumns" :data="hardwareEvents" size="small" :pagination="{ pageSize: 15 }" />
            </n-tab-pane>
            <n-tab-pane v-if="deviceClass === 'MEDICAL_TRACKED'" name="tracking" tab="Tracking (821)">
                <n-space vertical size="small" style="max-width: 420px;">
                    <n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="trackingEdit.receivedDate" value-format="yyyy-MM-dd" placeholder="received date" />
                    <n-input v-model:value="trackingEdit.patientId" placeholder="patient / recipient ID" />
                    <n-select v-model:value="trackingEdit.disposition" :options="dispositionOptions" clearable placeholder="disposition" />
                    <n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="trackingEdit.dispositionDate" value-format="yyyy-MM-dd" placeholder="disposition date" />
                    <n-button v-if="isWritable" size="small" @click="saveTracking">Update tracking</n-button>
                </n-space>
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
                    <n-date-picker style="width: 100%;" type="date" clearable v-model:formatted-value="eventForm.date" value-format="yyyy-MM-dd" placeholder="defaults to now" />
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
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NDataTable, NDatePicker, NForm, NFormItem, NIcon, NInput, NModal, NPopconfirm, NSelect, NSpace, NSwitch, NTabPane, NTabs, NTag, NTooltip, useNotification, NotificationType } from 'naive-ui'
import { Copy, InfoCircle } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'

const route = useRoute()
const router = useRouter()
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
// Twin event split: hardware events anchor to HBOM nodes (or carry hardware
// event types); software events come from observations / purl anchors.
const hardwareEvents = computed(() => events.value.filter((e: any) => e.hbomRef || (!e.purl && e.eventType !== 'OBSERVATION')))
const softwareEvents = computed(() => events.value.filter((e: any) => e.purl || e.eventType === 'OBSERVATION'))
const siteClient: Ref<string> = ref('')
const distributionLink = computed(() => ({
    name: 'DistributionOfOrg',
    params: siteClient.value
        ? { orguuid: orguuid.value, clientuuid: siteClient.value, siteuuid: device.value?.site }
        : { orguuid: orguuid.value }
}))
// The lot's CDX #929 choice selections, rendered with resolved option names.
const lotSelections = computed(() => {
    const crs = shipment.value?.choiceResolutions || []
    if (!crs.length) return []
    const nameByRef: Record<string, string> = {}
    for (const r of twinHbom.value) { if (r.component?.bomRef) nameByRef[r.component.bomRef] = r.component.name }
    return crs.map((c: any) => {
        const selected = (c.selectedRefs || []).map((ref: string) => nameByRef[ref] || ref)
        return `${c.choiceRef}: ${selected.length ? selected.join(', ') : '(not populated)'}`
    })
})
function copyUuid () { navigator.clipboard.writeText(deviceuuid.value); notify('success', 'Copied', 'Device uuid copied to clipboard') }
const replacedCount = computed(() => twinHbom.value.filter((r: any) => r.twinStatus === 'REPLACED').length)
// What's physically on this unit: original + replaced nodes (alternates and
// unresolved slot markers are not installed parts).
const installedCount = computed(() => twinHbom.value.filter((r: any) => r.twinStatus === 'ORIGINAL' || r.twinStatus === 'REPLACED').length)
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
    if (r.eventType === 'FAILURE') return 'twin-failure-row'
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
            if (r.twinStatus !== 'REPLACED' && r.eventType) {
                const tagType = r.eventType === 'FAILURE' ? 'error' : (r.eventType === 'REPAIR' ? 'info' : 'default')
                return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 420px;' }, {
                    trigger: () => h(NTag, { size: 'small', type: tagType as any, style: 'cursor: pointer;' }, { default: () => r.eventType }),
                    default: () => h('div', [
                        h('div', { style: 'margin: 2px 0;' }, [h('strong', 'When: '), formatDateTime(r.eventDate)]),
                        r.eventNotes ? h('div', { style: 'margin: 2px 0;' }, [h('strong', 'Notes: '), r.eventNotes]) : null
                    ].filter(Boolean))
                })
            }
            if (r.twinStatus !== 'REPLACED') return h(NTag, { size: 'small' }, { default: () => 'original' })
            const rep = r.replacement || {}
            const details = [
                ['Replaced on', formatDateTime(r.eventDate)],
                ['Part #', rep.partNumber ? `${rep.partNumber}${rep.partNumberType ? ` [${String(rep.partNumberType).toLowerCase().replace(/_/g, '-')}]` : ''}` : null],
                ['Lot', rep.lot], ['Serial', rep.serial],
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

// Software Events: each OBSERVATION row is a full reconciled report.
const softwareEventColumns = [
    { key: 'date', title: 'Date', render: (r: any) => formatDateTime(r.date || r.createdDate) },
    { key: 'source', title: 'Source', render: (r: any) => r.observation?.source || '—' },
    {
        key: 'report', title: 'Report',
        render: (r: any) => {
            const o = r.observation
            if (!o) return h('span', { class: 'subtle' }, '—')
            return h(NSpace, { size: 4 }, { default: () => [
                h(NTag, { size: 'small', type: 'success' }, { default: () => `${o.matchedCount || 0} matched` }),
                h(NTag, { size: 'small', type: (o.driftedCount || 0) > 0 ? 'warning' : 'default' }, { default: () => `${o.driftedCount || 0} drifted` }),
                h(NTag, { size: 'small' }, { default: () => `${o.unknownCount || 0} unknown` })
            ] })
        }
    },
    {
        key: 'details', title: 'Details',
        render: (r: any) => {
            const items = r.observation?.items || []
            if (!items.length) return h('span', { class: 'subtle' }, '—')
            return h(NTooltip, { trigger: 'hover', placement: 'left', style: 'max-width: 560px;' }, {
                trigger: () => h(NIcon, { size: 16, style: 'color: #909399; cursor: pointer; vertical-align: middle;' }, { default: () => h(InfoCircle) }),
                default: () => h('div', items.map((i: any) => h('div', { style: 'margin: 2px 0; font-family: monospace; font-size: 12px;' }, [
                    `${i.identifier || '(digest only)'} · ${(i.digest || '').replace('sha256:', '').substring(0, 12)} → ${i.status}`,
                    i.componentName ? ` — ${i.componentName}` : '',
                    i.observedVersion ? ` ${i.observedVersion}` : '',
                    i.expectedVersion && i.status === 'DRIFTED' ? ` (expected ${i.expectedVersion})` : ''
                ])))
            })
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
            query: gql`query shippedProduct($uuid: ID!) { shippedProduct(uuid: $uuid) { uuid org featureSet release shipDate quantity identifiers { idType idValue } choiceResolutions { choiceRef selectedRefs } } }`,
            variables: { uuid: device.value.shippedProduct }, fetchPolicy: 'no-cache'
        })
        shipment.value = resp.data.shippedProduct
    } catch (e) { /* ignore */ }
    if (device.value.site) {
        try {
            const resp: any = await graphqlClient.query({
                query: gql`query site($uuid: ID!) { site(uuid: $uuid) { uuid client } }`,
                variables: { uuid: device.value.site }, fetchPolicy: 'cache-first'
            })
            siteClient.value = resp.data.site?.client || ''
        } catch (e) { /* ignore */ }
    }
    const releaseUuid = device.value.plan?.expectedRelease || shipment.value?.release
    if (releaseUuid) {
        try {
            const resp: any = await graphqlClient.query({
                query: gql`query release($uuid: ID!) { release(releaseUuid: $uuid) { uuid version hardware componentDetails { uuid name } branchDetails { uuid name } } }`,
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
                twinStatus eventType eventDate eventNotes choiceRef
                replacement { partNumber partNumberType lot serial manufacturer }
                component { uuid bomRef type operator name version description category subcategory partNumbers manufacturer identifiers { party identities { idType idValue } } parties { bomRef roles name } boardLocation deviceType quantity parentRef isRoot }
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
                replacement { partNumber partNumberType lot serial manufacturer }
                observation { source matchedCount driftedCount unknownCount
                    items { identifier digest status componentName observedVersion expectedVersion } }
            } }`,
            variables: { deviceUuid: deviceuuid.value }, fetchPolicy: 'no-cache'
        })
        events.value = resp.data.deviceEventsOfDevice || []
    } catch (e) { /* ignore */ }
}

// ----- Device management (moved here from the old Distribution device modal) -----
const featureSetReleases: Ref<any[]> = ref([])
const expectedReleaseEdit: Ref<string | null> = ref(null)
const trackingEdit = reactive<any>({ receivedDate: null, patientId: '', disposition: null, dispositionDate: null })
const dispositionOptions = ['SHIPPED', 'RECEIVED', 'RETURNED', 'EXPLANTED', 'DISPOSED', 'DONATED', 'LOST'].map(v => ({ label: v, value: v }))
async function loadFeatureSetReleases () {
    if (!shipment.value?.featureSet) return
    try {
        const resp: any = await graphqlClient.query({
            query: gql`query rels($b: ID!, $n: Int) { releases(branchFilter: $b, numRecords: $n) { uuid version } }`,
            variables: { b: shipment.value.featureSet, n: 200 }, fetchPolicy: 'no-cache'
        })
        featureSetReleases.value = (resp.data.releases || []).map((r: any) => ({ label: r.version, value: r.uuid }))
    } catch (e) { /* ignore */ }
}
async function saveExpectedRelease () {
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation updateDevicePlan($uuid: ID!, $r: ID) { updateDevicePlan(uuid: $uuid, expectedRelease: $r) { uuid } }`,
            variables: { uuid: deviceuuid.value, r: expectedReleaseEdit.value || null }
        })
        notify('success', 'Saved', 'Expected release updated')
        await loadDevice(); await Promise.all([loadShipmentAndRelease(), loadTwinHbom()])
    } catch (err: any) { notify('error', 'Error', commonFunctions.parseGraphQLError(err.message)) }
}
async function saveTracking () {
    const input: any = {
        receivedDate: trackingEdit.receivedDate || null,
        patientId: trackingEdit.patientId || null,
        disposition: trackingEdit.disposition,
        dispositionDate: trackingEdit.dispositionDate || null
    }
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation updateDeviceTracking($uuid: ID!, $input: TrackingInput!) { updateDeviceTracking(uuid: $uuid, input: $input) { uuid } }`,
            variables: { uuid: deviceuuid.value, input }
        })
        notify('success', 'Saved', 'Tracking updated'); await loadDevice()
    } catch (err: any) { notify('error', 'Error', commonFunctions.parseGraphQLError(err.message)) }
}
async function deleteThisDevice () {
    try {
        await graphqlClient.mutate({ mutation: gql`mutation deleteDevice($uuid: ID!) { deleteDevice(uuid: $uuid) }`, variables: { uuid: deviceuuid.value } })
        notify('info', 'Deleted', 'Device deleted')
        router.push({ name: 'DistributionOfOrg', params: { orguuid: orguuid.value } })
    } catch (err: any) { notify('error', 'Error', commonFunctions.parseGraphQLError(err.message)) }
}

// ----- Operator JSON state report (same payload as the programmatic phone-home) -----
const stateReportText = ref('')
const stateReportPending = ref(false)
async function submitStateReport () {
    const text = stateReportText.value.trim()
    if (!text) { notify('warning', 'Empty', 'Paste a JSON report or an image list first'); return }
    const report: any = { device: deviceuuid.value, source: 'MANUAL' }
    try {
        const parsed = JSON.parse(text)
        if (Array.isArray(parsed)) report.observed = parsed
        else { if (parsed.observed) report.observed = parsed.observed; if (parsed.rawText) report.rawText = parsed.rawText; if (parsed.source) report.source = parsed.source }
    } catch {
        report.rawText = text // plain text — e.g. a k8s image list
    }
    stateReportPending.value = true
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation reportDeviceStateManual($report: DeviceStateReportInput!) { reportDeviceStateManual(report: $report) { uuid } }`,
            variables: { report }
        })
        notify('success', 'Reported', 'Observed state reconciled')
        stateReportText.value = ''
        await loadDevice(); await loadEvents()
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
    stateReportPending.value = false
}

const showEventModal = ref(false)
const eventForm = reactive<any>({ eventType: 'FAILURE', hbomRef: null, date: null, notes: '', replacement: { partNumber: '', partNumberType: null, lot: '', serial: '', manufacturer: '' } })
function openEventModal () {
    eventForm.eventType = 'FAILURE'; eventForm.hbomRef = null; eventForm.date = null; eventForm.notes = ''
    eventForm.replacement = { partNumber: '', partNumberType: null, lot: '', serial: '', manufacturer: '' }
    showEventModal.value = true
}
// Picking an approved alternate from the choice slot fills the replacement facts.
function applyChoiceAlternate (bomRef: string) {
    const alt = pickedChoiceAlternates.value.find((r: any) => r.component?.bomRef === bomRef)
    if (!alt) return
    // Prefer the MPN claim so the recorded part number keeps its attribution
    // (the same part can carry different numbers per asserting party).
    const claims = (alt.component.identifiers || []).flatMap((idf: any) => idf.identities || [])
    const claim = claims.find((c: any) => c.idType === 'MPN' && c.idValue) || claims.find((c: any) => c.idValue)
    eventForm.replacement.partNumber = claim?.idValue || alt.component.name || ''
    eventForm.replacement.partNumberType = claim?.idType || null
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
    expectedReleaseEdit.value = device.value.plan?.expectedRelease || null
    const t = device.value.tracking || {}
    trackingEdit.receivedDate = t.receivedDate || null
    trackingEdit.patientId = t.patientId || ''
    trackingEdit.disposition = t.disposition || null
    trackingEdit.dispositionDate = t.dispositionDate || null
    await Promise.all([loadShipmentAndRelease(), loadTwinHbom(), loadEvents()])
    await loadFeatureSetReleases()
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
:deep(.twin-failure-row td) {
    background-color: rgba(208, 48, 80, 0.14) !important;
}
</style>
