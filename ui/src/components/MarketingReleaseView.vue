<template>
    <div>
        <div class="row">
            <div v-if="marketingRelease && marketingRelease.orgDetails">
                <n-grid :cols="7">
                    <n-gi span="4">
                        <h3 style="color: #537985; display: inline;">                  
                            <router-link
                                style="text-decoration: none; color: rgb(39 179 223);"
                                :to="{name: isComponent ? 'ComponentsOfOrg' : 'ProductsOfOrg', params: { orguuid: marketingRelease.orgDetails.uuid, compuuid: marketingRelease.componentDetails.uuid } }">{{
                                marketingRelease.componentDetails.name }} </router-link>
                            <span style="margin-left: 6px;">-</span>
                            <span style="margin-left: 6px;">{{words.componentFirstUpper}} Marketing Release
                            <span>-</span>
                            {{ updatedMarketingRelease ? updatedMarketingRelease.version : '' }}</span>
                        </h3>
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <Icon class="clickable" style="margin-left:10px;" size="16"><Info20Regular/></Icon>
                            </template>
                            <strong>UUID: </strong> {{ marketingReleaseUuid }} 
                            <Icon class="clickable" style="margin-left: 5px;" size="14" @click="copyToClipboard(marketingReleaseUuid)"><Copy20Regular/></Icon>
                            <div class=""><strong>Organization:</strong> {{ marketingRelease.orgDetails.name }}</div>
                            <div><strong>Created: </strong>{{ updatedMarketingRelease ? (new
                                Date(updatedMarketingRelease.createdDate)).toLocaleString('en-CA') : '' }}
                            </div>
                            <div><strong>Status: </strong>{{ updatedMarketingRelease.status.toUpperCase() }}</div>
                        </n-tooltip>
                        <!-- router-link :to="{ name: 'ReleaseView', params: { uuid: releaseUuid } }">
                            <Icon class="clickable" style="margin-left:10px;" size="16" title="Permanent Link"><Link/></Icon>
                        </router-link -->
                    </n-gi>
                    <n-gi span="2">
                        <!-- n-space :size="1" v-if="updatedRelease.metrics.lastScanned">
                            <span title="Criticial Severity Vulnerabilities" class="circle" style="background: #f86c6b; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, 'CRITICAL', 'Vulnerability')">{{ updatedRelease.metrics.critical }}</span>    
                            <span title="High Severity Vulnerabilities" class="circle" style="background: #fd8c00; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, 'HIGH', 'Vulnerability')">{{ updatedRelease.metrics.high }}</span>
                            <span title="Medium Severity Vulnerabilities" class="circle" style="background: #ffc107; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, 'MEDIUM', 'Vulnerability')">{{ updatedRelease.metrics.medium }}</span>
                            <span title="Low Severity Vulnerabilities" class="circle" style="background: #4dbd74; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, 'LOW', 'Vulnerability')">{{ updatedRelease.metrics.low }}</span>
                            <span title="Vulnerabilities with Unassigned Severity" class="circle" style="background: #777; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, 'UNASSIGNED', 'Vulnerability')">{{ updatedRelease.metrics.unassigned }}</span>
                            <div style="width: 30px;"></div>
                            <span title="Licensing Policy Violations" class="circle" style="background: blue; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, '', 'Violation')">{{ updatedRelease.metrics.policyViolationsLicenseTotal }}</span>
                            <span title="Security Policy Violations" class="circle" style="background: red; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, '', 'Violation')">{{ updatedRelease.metrics.policyViolationsSecurityTotal }}</span>
                            <span title="Operational Policy Violations" class="circle" style="background: grey; cursor: pointer;" @click="viewDetailedVulnerabilitiesForRelease(releaseUuid, '', 'Violation')">{{ updatedRelease.metrics.policyViolationsOperationalTotal }}</span>
                        </n-space> -->
                    </n-gi>
                    <n-gi span="1">
                        <span class="lifecycle" style="float: right; margin-right: 80px;">
                            <span v-if="isWritable">
                                <n-dropdown v-if="updatedMarketingRelease.lifecycle" trigger="hover" :options="marketingReleaseLifecyclesOptions" @select="modifyMarketingReleaseLifecycle">
                                    <n-tag type="success">{{ marketingReleaseLifecyclesOptions.find((lo: any) => lo.key === updatedMarketingRelease.lifecycle)?.label }}</n-tag>
                                </n-dropdown>
                            </span>
                            <span v-if="!isWritable">
                                <n-tag type="success">{{ updatedMarketingRelease.lifecycle }}</n-tag>
                            </span>
                        </span>
                    </n-gi>
                </n-grid>
            </div>
            <div v-if="marketingRelease && marketingRelease.orgDetails" class="row" style="margin-left:4%;">
                <div>
                    <strong>Integration Type: </strong> 
                    <n-select
                        v-if="isEditIntegrationType"
                        @update:value="handleIntegrateTypeUpdate"
                        :value="updatedMarketingRelease.integrateType"
                        :options="[{value: 'FOLLOW', label: 'FOLLOW'}, {value: 'TARGET', label: 'TARGET'}, {value: 'NONE', label: 'NONE'}]" 
                    />
                    <span v-else>
                        {{ updatedMarketingRelease.integrateType ? updatedMarketingRelease.integrateType : 'NONE' }}
                    </span>
                    <vue-feather v-if="isWritable" type="edit" class="clickable" @click="isEditIntegrationType = true" title="Edit Integration Settings" />
                </div>
                <div>
                    <strong>Integration {{ words.branchFirstUpper }}: </strong> 
                    <n-select
                        v-if="isEditFollowIntegration"
                        @update:value="handleFollowIntegrationUpdate"
                        :value="updatedMarketingRelease.branch"
                        :options="followBranches" 
                    />
                    <span v-else>
                        {{ updatedMarketingRelease.integrateBranchDetails ? updatedMarketingRelease.integrateBranchDetails.name : 'Not Set' }}
                    </span>
                    <vue-feather v-if="isWritable && updatedMarketingRelease.integrateType === 'FOLLOW'" type="edit" class="clickable" @click="editFollowIntegration" title="Set Follow Integration" />
                </div>
                <div>
                    <strong>Development Release Version: </strong>{{ updatedMarketingRelease && updatedMarketingRelease.devReleasePointer ? updatedMarketingRelease.devReleaseDetails.version : 'Not Set' }}
                    <vue-feather v-if="isWritable && updatedMarketingRelease.integrateType === 'TARGET'" type="edit" class="clickable" @click="showSelectTargetIntegrateModal = true" title="Set Target Integration" />
                </div>
            </div>
        </div>
       
        <div class="row" v-if="marketingRelease && marketingRelease.orgDetails && updatedMarketingRelease && updatedMarketingRelease.orgDetails">
            <n-tabs style="padding-left:2%;" type="line">
                <n-tab-pane name="approvals" tab="Approvals">
                    <div class="container" v-if="updatedMarketingRelease.type !== 'PLACEHOLDER'">
                        <n-grid cols="12" item-responsive>
                            <n-gi :span="3">
                                <div class="row">
                                    <div v-if="isWritable">
                                        <label>Suggested Release Version: {{ computedReleasedVersion }}</label>
                                        <n-input v-model:value="releasedVersion" />
                                        <n-button @click="releaseMarketingRelease">Release!</n-button>
                                    </div>
                                   
                                    <div>
                                        <h3>Approvals:</h3>
                                    </div>
                                </div>
                            </n-gi>
                        </n-grid>
                    </div>
                </n-tab-pane>
                <n-tab-pane name="history" tab="Release History">
                    <n-data-table :data="releasedReleasesItems" :columns="releasedReleasesFields" />
                </n-tab-pane>
                <n-tab-pane name="meta" tab="Meta">
                    <div class="container">
                        <div>
                            <h3>Notes</h3>
                            <n-input type="textarea" v-if="isWritable"
                                v-model:value="updatedMarketingRelease.notes" rows="2" />
                            <n-input type="textarea" v-else :value="updatedMarketingRelease.notes" rows="2" readonly />
                            <n-button v-if="isWritable" @click="save"
                                v-show="marketingRelease.notes !== updatedMarketingRelease.notes">Save Notes</n-button>
                        </div>
                        <div>
                            <h3 class="mt-3">Tags</h3>
                            <n-data-table :columns="releaseTagsFields" :data="updatedMarketingRelease.tags" class="table-hover"></n-data-table>
                            <n-form class="pb-5 mb-5" 
                                v-if="isWritable">
                                <n-input-group>
                                    <n-select class="w-50" v-model:value="newTagKey" placeholder="Input or select tag key"
                                    filterable tag :options="releaseTagKeys" required />
                                    <n-input v-model:value="newTagValue" required placeholder="Enter tag value" />
                                    <n-button attr-type="submit" @click="addTag">Set Tag Entry</n-button>
                                </n-input-group>
                            </n-form>
                        </div>
                    </div>
                </n-tab-pane>
                <n-tab-pane v-if="isAdmin" name="admin" tab="Admin Zone">
                    <div v-if="isAdmin && statuses && updatedMarketingRelease.status === 'ACTIVE'">
                        <n-button tertiary type="warning" @click="archiveMarketingRelease">Archive Marketing Release</n-button>
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>

        <n-modal
            v-model:show="showSelectTargetIntegrateModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Set Target Release"
        >
            <create-release
                            v-if="updatedMarketingRelease && updatedMarketingRelease.org"
                            :orgProp="updatedMarketingRelease.org"
                            :inputComponent="updatedMarketingRelease.component"
                            :inputType="updatedMarketingRelease.componentDetails.type"
                            :attemptPickRelease="true"
                            :disallowPlaceholder="true"
                            :disallowCreateRelease="true"
                            :isHideReset="true"
                            createButtonText="Select Release"
                            @createdRelease="handleTargetIntegrationUpdate" />
        </n-modal>
    </div>
</template>
    
<script lang="ts">
export default {
    name: 'MarketingReleaseView'
}
</script>
<script lang="ts" setup>
import CreateRelease from '@/components/CreateRelease.vue'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import graphqlQueries from '@/utils/graphqlQueries'
import { Info20Regular, Copy20Regular } from '@vicons/fluent'
import { Box, CirclePlus, ClipboardCheck, Download, FileInvoice, GitCompare, Link, Trash, Upload } from '@vicons/tabler'
import { Icon } from '@vicons/utils'
import type { SelectOption } from 'naive-ui'
import { NButton, NCard, NCheckbox, NCheckboxGroup, NDataTable, DataTableColumns, NDropdown, NForm, NFormItem, NGi, NGrid, NIcon, NInput, NInputGroup, NModal, NRadioButton, NRadioGroup, NSelect, NSpin, NSwitch, NTabPane, NTabs, NTag, NTooltip, NotificationType, useNotification } from 'naive-ui'
import Swal, { SweetAlertOptions } from 'sweetalert2'
import { SwalData } from '@/utils/commonFunctions'
import { Component, ComputedRef, Ref, computed, h, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'

const route = useRoute()
const router = useRouter()
const store = useStore()
const notification = useNotification()
const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const myUser = store.getters.myuser
const isWritable = ref(false)
const isAdmin = ref(false)
const isComponent: Ref<boolean> = ref(true)

const copyToClipboard = async function (text: string) {
    try {
        navigator.clipboard.writeText(text);
        notify('success', 'Copied', 'Successfully copied: ' + text)
    } catch (e) {
        notify('error', 'Failed', 'Failed to copy text')
        console.error(e)
    }
}
const props = defineProps<{
    uuidprop?: string
    orgprop?: string
}>()

const emit = defineEmits(['marketingReleaseApprovalsChanged', 'closeMarketingRelease'])

const statuses: Ref<any[]> = ref([])
onMounted(async () => {
    await fetchMarketingRelease()
    fetchLifecycles()
})

const marketingReleaseUuid: Ref<string> = ref(props.uuidprop ?? route.params.uuid.toString())
const marketingRelease: Ref<any> = ref({})
const updatedMarketingRelease: Ref<any> = ref({})
const computedReleasedVersion: ComputedRef<string> = computed((): any => {
    let rlzVer = ''
    if (marketingRelease.value && marketingRelease.value.version && marketingRelease.value.lifecycle && marketingReleaseLifecycles.value.length) {
        const lifecycle = marketingReleaseLifecycles.value.find((l: any) => l.lifecycle === marketingRelease.value.lifecycle)
        rlzVer = marketingRelease.value.version + "-" + lifecycle.suffix
    }
    return rlzVer
})
const releasedVersion: Ref<string> = ref('')

const words: Ref<any> = ref({})
const marketingReleaseLifecycles: Ref<any[]> = ref([])

const marketingReleaseLifecyclesOptions: ComputedRef<SelectOption[]> = computed(() => {
    return marketingReleaseLifecycles.value.map((lifecycle: any) => ({
        key: lifecycle.lifecycle,
        label: lifecycle.prettyName || lifecycle.lifecycle,
        value: lifecycle.lifecycle
    }))
})

const isEditIntegrationType = ref(false)
const isEditFollowIntegration = ref(false)
const showSelectTargetIntegrateModal = ref(false)
const followBranches: Ref<any[]> = ref([])

const releasedReleasesItems: ComputedRef<any> = computed((): any => {
    let releaseItems: any[] = []
    if (updatedMarketingRelease.value && updatedMarketingRelease.value.events && updatedMarketingRelease.value.events.length) {
        releaseItems = updatedMarketingRelease.value.events.map((ev: any) => {
            return {
                uuid: ev.release,
                version: ev.releaseDetails.version,
                marketingVersion: ev.releaseDetails.marketingVersion,
                createdDate: ev.releaseDetails.createdDate,
                releasedDate: ev.date,
                releasedBy: ev.wu.lastUpdatedBy,
                status: ev.releaseDetails.status
            }
        })
    }
    return releaseItems
})


const releasedReleasesFields: DataTableColumns<any> = [
    {
        key: 'version',
        title: 'Dev Version'
    },
    {
        key: 'marketingVersion',
        title: 'Marketing Version',
    },
    {
        key: 'createdDate',
        title: 'Created Date'
    },
    {
        key: 'releasedDate',
        title: 'Released Date'
    },
    {
        key: 'releasedBy',
        title: 'Released By'
    },
    {
        key: 'status',
        title: 'Status'
    }
]

const userPermission: ComputedRef<any> = computed((): any => {
    let userPermission = ''
    if (marketingRelease.value && marketingRelease.value.orgDetails) userPermission = commonFunctions.getUserPermission(marketingRelease.value.orgDetails.uuid, store.getters.myuser).org
    return userPermission
})

async function fetchMarketingRelease () {
    const mrResponse = await graphqlClient.query({
        query: gql`
            query marketingRelease($marketingReleaseUuid: ID!) {
                marketingRelease(marketingReleaseUuid: $marketingReleaseUuid) {
                    ${graphqlQueries.MarketingRelease}
                }
            }
            `,
        variables: {
            marketingReleaseUuid: marketingReleaseUuid.value
        },
        fetchPolicy: 'no-cache'
    })
    marketingRelease.value = mrResponse.data.marketingRelease
    updatedMarketingRelease.value = deepCopyRelease(marketingRelease.value)
    words.value = commonFunctions.resolveWords(marketingRelease.value.componentDetails.type === 'COMPONENT')
    isWritable.value = commonFunctions.isWritable(marketingRelease.value.orgDetails.uuid, myUser, 'COMPONENT')
    isAdmin.value = commonFunctions.isAdmin(marketingRelease.value.orgDetails.uuid, myUser)
    isComponent.value = (updatedMarketingRelease.value.componentDetails.type === 'COMPONENT')
}

async function fetchLifecycles () {
    const resp = await graphqlClient.query({
        query: gql`
            query marketingReleaseLifecycles {
                marketingReleaseLifecycles {
                    lifecycle
	                suffix
	                prettyName
	                ordinal
                }
            }
            `
    })
    marketingReleaseLifecycles.value = resp.data.marketingReleaseLifecycles
}

function deepCopyRelease (rlz: any) {
    return Object.assign({}, rlz)
}

async function editFollowIntegration () {
    isEditFollowIntegration.value = true
    const storeBranches = await store.dispatch('fetchBranches', marketingRelease.value.component)
    followBranches.value = storeBranches.map((b: any) => {return {label: b.name, value: b.uuid};})
}

function handleIntegrateTypeUpdate (value: string) {
    updatedMarketingRelease.value.integrateType = value
    save()
    isEditIntegrationType.value = false
}

function handleFollowIntegrationUpdate (value: string) {
    updatedMarketingRelease.value.integrateBranch = value
    save()
    isEditFollowIntegration.value = false
}

function handleTargetIntegrationUpdate (rlz: any) {
    console.log(rlz)
    updatedMarketingRelease.value.integrateBranch = rlz.branch
    updatedMarketingRelease.value.devReleasePointer = rlz.uuid
    save()
    showSelectTargetIntegrateModal.value = false
}

async function modifyMarketingReleaseLifecycle(newLifecycle: string) {
    const onSwalConfirm = async function () {
        const mrResponse = await graphqlClient.mutate({
            mutation: gql`
                mutation modifyMarketingReleaseLifecycle($marketingReleaseUuid: ID!, $newLifecycle: MarketingReleaseLifecycleEnum) {
                    modifyMarketingReleaseLifecycle(marketingReleaseUuid: $marketingReleaseUuid, newLifecycle: $newLifecycle) {
                        ${graphqlQueries.MarketingRelease}
                    }
                }
                `,
            variables: {
                marketingReleaseUuid: marketingReleaseUuid.value,
                newLifecycle
            }
        })
        marketingRelease.value = mrResponse.data.modifyMarketingReleaseLifecycle
        updatedMarketingRelease.value = deepCopyRelease(marketingRelease.value)
    }
    const lifecycleLabel = marketingReleaseLifecyclesOptions.value.find((lo: any) => lo.key === newLifecycle)?.label || newLifecycle
    const swalData: SwalData = {
        questionText: `Are you sure you want to change lifecycle to ${lifecycleLabel} for the Marketing Release version ${marketingRelease.value.version}?`,
        successTitle: 'Lifecycle Updated!',
        successText: `The Marketing Release version ${marketingRelease.value.version} lifecycle has been updated to ${lifecycleLabel}.`,
        dismissText: 'Lifecycle update has been cancelled.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

async function releaseMarketingRelease() {
    const rlzConfirmObject: any = {
        uuid: marketingReleaseUuid.value,
        lifecycle: updatedMarketingRelease.value.lifecycle,
	    devReleasePointer: updatedMarketingRelease.value.devReleasePointer
    }

    const onSwalConfirm = async function () {
        const mrResponse = await graphqlClient.mutate({
            mutation: gql`
                mutation releaseMarketingRelease($marketingRelease: MarketingReleaseInput!, $marketingVersion: String!) {
                    releaseMarketingRelease(marketingRelease: $marketingRelease, marketingVersion: $marketingVersion) {
                        ${graphqlQueries.MarketingRelease}
                    }
                }
                `,
            variables: {
                marketingRelease: rlzConfirmObject,
                marketingVersion: releasedVersion.value
            }
        })
        marketingRelease.value = mrResponse.data.releaseMarketingRelease
        updatedMarketingRelease.value = deepCopyRelease(marketingRelease.value)
    }

    const swalData: SwalData = {
        questionText: `Are you sure you want to perform a release with the version ${releasedVersion.value}?`,
        successTitle: 'Released!',
        successText: `The release ${releasedVersion.value} has been released!`,
        dismissText: 'The release has been cancelled!'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

async function archiveMarketingRelease() {
    const onSwalConfirm = async function () {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation archiveMarketingRelease($marketingReleaseUuid: ID!) {
                    archiveMarketingRelease(marketingReleaseUuid: $marketingReleaseUuid)
                }
                `,
            variables: {
                marketingReleaseUuid: marketingReleaseUuid.value
            }
        })
    }
    const swalData: SwalData = {
        questionText: `Are you sure you want to archive the Marketing Release version ${marketingRelease.value.version}?`,
        successTitle: 'Archived!',
        successText: `The Marketing Release version ${marketingRelease.value.version} has been archived.`,
        dismissText: 'Archiving has been cancelled.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

async function save() {
    const rlzUpdObject: any = {
        uuid: marketingReleaseUuid.value,
        integrateType: updatedMarketingRelease.value.integrateType,
	    integrateBranch: updatedMarketingRelease.value.integrateBranch,
	    devReleasePointer: updatedMarketingRelease.value.devReleasePointer
    }
    const mrResponse = await graphqlClient.mutate({
        mutation: gql`
            mutation updateMarketingRelease($marketingRelease: MarketingReleaseInput!) {
                updateMarketingRelease(marketingRelease: $marketingRelease) {
                    ${graphqlQueries.MarketingRelease}
                }
            }
            `,
        variables: {
            marketingRelease: rlzUpdObject
        }
    })
    marketingRelease.value = mrResponse.data.updateMarketingRelease
    updatedMarketingRelease.value = deepCopyRelease(marketingRelease.value)
}

// Meta
const newTagKey: Ref<string> = ref('')
const newTagValue: Ref<string> = ref('')
const releaseTagKeys: Ref<any[]> = ref([])

const releaseTagsFields: any[] = [
    {
        key: 'key',
        title: 'Key'
    },
    {
        key: 'value',
        title: 'Value'
    },
    {
        key: 'controls',
        title: 'Manage',
        render(row: any) {
            return h(
                NIcon,
                {
                    title: 'Delete Tag',
                    class: 'icons clickable',
                    size: 25,
                    onClick: () => deleteTag(row.key)
                }, { default: () => h(Trash) }
            )
        }
    }
]

async function deleteTag (key: string) {
    updatedMarketingRelease.value.tags = updatedMarketingRelease.value.tags.filter((t: any) => (t.key !== key))
    await save()
}
function addTag () {
    if (newTagKey.value && newTagValue.value) {
        const tpresent = updatedMarketingRelease.value.tags.filter((t: any) => (t.key === newTagKey.value))
        if (tpresent && tpresent.length) {
            notify('error', 'Failed', 'The tag with this key already exists on the release')
        } else {
            updatedMarketingRelease.value.tags.push(
                {
                    key: newTagKey.value,
                    value: newTagValue.value
                }
            )
            save().then(() => {
                newTagKey.value = ''
                newTagValue.value = ''
            })
        }
    }
}

</script>
    
<style scoped lang="scss">
.row {
    padding-left: 1%;
    min-height: 200px;
    font-size: 16px;
}

.container{
    margin-top: 1%;
    margin-right: 2%;
    margin-bottom: 1%;
}
.red {
    color: red;
}

.alert {
    display: inline-block;
}

.inline {
    display: inline;
}

.releaseElInList {
    display: inline-block;
}

.addIcon {
    margin-left: 5px;
}

.removeFloat {
    clear: both;
}

.textBox {
    width: fit-content;
    margin-bottom: 10px;
}

.historyList {
    display: grid;
    grid-template-columns: 75px 200px repeat(2, 0.5fr) 1.2fr;

    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}

.historyList:hover {
    background-color: #d9eef3;
}

.historyHeader {
    background-color: #f9dddd;
    font-weight: bold;
}

</style>