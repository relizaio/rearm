<template>
    <div>
        <div class="row">

            <n-modal
                v-model:show="showMarketingVersionModal"
                title='Set Next Marketing Version'
                preset="dialog"
                :show-icon="false" >
                <n-form>
                    <n-form-item :label="!nextMarketingVersion ? 'Set Marketing Version' : 'Next Marketing Version is: ' + nextMarketingVersion">
                        <n-input
                            v-model:value="updatedMarketingVersion"
                            required
                            placeholder="Enter Marketing Version" 
                        />
                    </n-form-item>
                    <n-button type="success" @click="setMarketingVersion">Save</n-button>
                </n-form>
            </n-modal>
            <n-modal
                v-model:show="showExportSBOMModal"
                title='Export Release BOM'
                preset="dialog"
                :show-icon="false" >
                <n-form-item label="Select xBOM Type">
                    <n-radio-group v-model:value="exportBomType" name="xBomType">
                        <n-radio-button value="SBOM">
                            SBOM
                        </n-radio-button>
                        <n-radio-button value="OBOM">
                            OBOM
                        </n-radio-button>
                    </n-radio-group>
                </n-form-item>
                <n-form v-if="exportBomType === 'SBOM'">
                    <n-form-item label="Select SBOM configuration for export">
                        <n-radio-group v-model:value="selectedRebomType" name="rebomTypesRG">
                            <n-radio-button
                                v-for="abtn in rebomTypes"
                                :key="abtn.value"
                                :value="abtn.value"
                                :label="abtn.key"
                            />
                        </n-radio-group>
                    </n-form-item>
                        <n-form-item label="Select structure">
                        <n-radio-group v-model:value="selectedBomStructureType" name="bomStructureType">
                            <n-radio-button
                                v-for="abtn in bomStructureTypes"
                                :key="abtn.value"
                                :value="abtn.value"
                                :label="abtn.key"
                            />
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item label="Media Type">
                        <n-radio-group v-model:value="selectedSbomMediaType" name="sbomMediaType">
                            <n-radio-button value="JSON">JSON</n-radio-button>
                            <n-radio-button value="CSV">CSV</n-radio-button>
                            <n-radio-button value="EXCEL">EXCEL</n-radio-button>
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item>
                        Top Level Dependency only: <n-switch v-model:value="tldOnly"/>
                    </n-form-item>
                    <n-spin :show="bomExportPending" small style="margin-top: 5px;">
                        <n-button type="success" 
                            :disabled="bomExportPending"
                            @click="exportReleaseSbom(tldOnly, selectedBomStructureType, selectedRebomType, selectedSbomMediaType)">
                            <span v-if="bomExportPending" class="ml-2">Exporting...</span>
                            <span v-else>Export</span>
                        </n-button>
                    </n-spin>
                </n-form>
                <n-button v-if="exportBomType === 'OBOM'" type="success" 
                    @click.prevent="exportReleaseObom">
                    <span v-if="bomExportPending" class="ml-2">Exporting...</span>
                    <span v-else>Export</span>
                </n-button>
            </n-modal>
            <n-modal
                v-model:show="showDownloadArtifactModal"
                title='Download Artifact'
                preset="dialog"
                :show-icon="false" >
                <n-form-item label="Select Download Type">
                    <n-radio-group v-model:value="downloadType" name="downloadType">
                        <n-radio-button value="DOWNLOAD">
                            Augmented Artifact
                        </n-radio-button>
                        <n-radio-button value="RAW_DOWNLOAD">
                            Raw Artifact
                        </n-radio-button>
                    </n-radio-group>
                </n-form-item>
                <n-button type="success" @click="executeDownload">
                    Download
                </n-button>
            </n-modal>
            <div v-if="release && release.componentDetails">
                <n-grid x-gap="2" :cols="4">
                    <n-gi span="2">
                        <h3 style="color: #537985; display: inline;">                  
                            <router-link
                                style="text-decoration: none; color: rgb(39 179 223);"
                                :to="{name: isComponent ? 'ComponentsOfOrg' : 'ProductsOfOrg', params: { orguuid: release.orgDetails.uuid, compuuid: release.componentDetails.uuid } }">{{
                                release.componentDetails.name }} </router-link>
                            <span style="margin-left: 6px;">{{words.componentFirstUpper}} Release {{ updatedRelease ? updatedRelease.version : '' }}</span>
                        </h3>
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <Icon class="clickable" style="margin-left:10px;" size="16"><Info20Regular/></Icon>
                            </template>
                            <strong>UUID: </strong> {{ releaseUuid }}
                            <div v-if="purl"><strong>PURL:</strong> {{ purl }}</div>
                            <div><strong>Marketing Version: </strong>{{ updatedRelease && updatedRelease.marketingVersion ? updatedRelease.marketingVersion : 'Not Set' }}</div>
                            <div class=""><strong>Organization:</strong> {{ release.orgDetails.name }}</div>
                            <div class="" v-if="updatedRelease.endpoint">
                                <strong>Test endpoint: </strong>
                                <a :href="updatedRelease.endpoint">{{ updatedRelease.endpoint }}</a>
                            </div>
                            <div>
                                <strong>{{ words.branchFirstUpper }}: </strong>
                                <router-link
                                    style="color: white;"
                                    :to="{ name: isComponent ? 'ComponentsOfOrg' : 'ProductsOfOrg', params: { orguuid: release.orgDetails.uuid, compuuid: release.componentDetails.uuid, branchuuid: release.branchDetails.uuid } }">
                                    {{ release.branchDetails.name }}
                                </router-link>
                            </div>
                            <div><strong>Created: </strong>{{ updatedRelease ? (new
                                Date(updatedRelease.createdDate)).toLocaleString('en-CA') : '' }}
                            </div>
                            <div v-if="release.componentDetails.type === 'COMPONENT' && pullRequest !== null && pullRequest.number">
                                <strong>
                                    <span>Pull Request</span>:
                                </strong>
                                <router-link
                                    :to="{ name: 'ComponentsOfOrg', params: { orguuid: release.orgDetails.uuid, compuuid: release.componentDetails.uuid, branchuuid: release.branchDetails.uuid, prnumber: pullRequest.number } }">
                                    #{{ pullRequest.number }} {{ pullRequest.title }}
                                </router-link>
                                <a :href="pullRequest.endpoint">
                                    <Icon class="clickable" size="25" title="Permanent Link"><Link/></Icon>
                                </a>
                            </div>
                        </n-tooltip>
                        <vue-feather v-if="userPermission !== 'READ_ONLY' && release.componentDetails.type === 'PRODUCT'"
                            size="16px"
                            class="clickable icons versionIcon"
                            type="copy"
                            title="Create Feature Set From Release"
                            @click="cloneReleaseToFs(releaseUuid, release.version)"
                            style="margin-left:10px;"
                        />
                        <router-link :to="{ name: 'ReleaseView', params: { uuid: releaseUuid } }">
                            <Icon class="clickable" style="margin-left:10px;" size="16" title="Permanent Link"><Link/></Icon>
                        </router-link>
                        <Icon @click="showExportSBOMModal=true" class="clickable" style="margin-left:10px;" size="16" title="Export Release xBOM" ><Download/></Icon>
                        <Icon v-if="release.lifecycle === 'ASSEMBLED' && release.componentDetails.versionType === 'MARKETING' && userPermission !== 'READ_ONLY'" @click="openMarketingVersionModal" class="clickable" style="margin-left:10px;" size="16" title="Set Marketing Version For this Release" ><GlobeAdd24Regular/></Icon>
                    </n-gi>
                    <n-gi>
                        <n-space :size="1" v-if="updatedRelease.metrics.lastScanned">
                            <span title="Criticial Severity Vulnerabilities" class="circle" style="background: #f86c6b; cursor: help;">{{ updatedRelease.metrics.critical }}</span>    
                            <span title="High Severity Vulnerabilities" class="circle" style="background: #fd8c00; cursor: help;">{{ updatedRelease.metrics.high }}</span>
                            <span title="Medium Severity Vulnerabilities" class="circle" style="background: #ffc107; cursor: help;">{{ updatedRelease.metrics.medium }}</span>
                            <span title="Low Severity Vulnerabilities" class="circle" style="background: #4dbd74; cursor: help;">{{ updatedRelease.metrics.low }}</span>
                            <span title="Vulnerabilities with Unassigned Severity" class="circle" style="background: #777; cursor: help;">{{ updatedRelease.metrics.unassigned }}</span>
                            <div style="width: 30px;"></div>
                            <span title="Licensing Policy Violations" class="circle" style="background: blue; cursor: help;">{{ updatedRelease.metrics.policyViolationsLicenseTotal }}</span>
                            <span title="Security Policy Violations" class="circle" style="background: red; cursor: help;">{{ updatedRelease.metrics.policyViolationsSecurityTotal }}</span>
                            <span title="Operational Policy Violations" class="circle" style="background: grey; cursor: help;">{{ updatedRelease.metrics.policyViolationsOperationalTotal }}</span>
                        </n-space>
                    </n-gi>
                    <n-gi>
                        <span class="lifecycle" style="float: right; margin-right: 80px;">
                            <span v-if="userPermission !== 'READ_ONLY'">
                                <n-dropdown v-if="updatedRelease.lifecycle" trigger="hover" :options="lifecycleOptions" @select="lifecycleChange">
                                    <n-tag type="success">{{ lifecycleOptions.find(lo => lo.key === updatedRelease.lifecycle)?.label }}</n-tag>
                                </n-dropdown>
                            </span>
                            <span v-if="userPermission === 'READ_ONLY'">
                                <n-tag type="success">{{ updatedRelease.lifecycle }}</n-tag>
                            </span>
                        </span>
                    </n-gi>
                </n-grid>
            </div>
        </div>

        <div class="row" v-if="release && release.orgDetails && updatedRelease && updatedRelease.orgDetails">
            <n-tabs style="padding-left:2%;" type="line" @update:value="handleTabSwitch">
                <n-tab-pane name="components" tab="Components">
                    <div class="container" v-if="updatedRelease.componentDetails && updatedRelease.componentDetails.type === 'PRODUCT'">
                        <h3>Components
                            <Icon v-if="isWritable && isUpdatable"
                                class="clickable addIcon" size="25" 
                                title="Add Component Release"
                                @click="showAddComponentReleaseModal=true">
                                <CirclePlus/>
                            </Icon>
                        </h3>
                        <n-data-table :data="updatedRelease.parentReleases" :columns="parentReleaseTableFields" :row-key="artifactsRowKey" />
                        <ul>
                            <div v-for="cr in updatedRelease.parentReleases" :key="'parRel' + cr.release">
                                <!-- release-el :uuid="cr.release" 
                                            :org="updatedRelease.org"
                                            :updatable="userPermission !== 'READ_ONLY'" 
                                            :deletable="userPermission !== 'READ_ONLY'"
                                            @removeRelease="removeRelease" 
                                            @releaseUpdated="releaseUpdated"
                                            class="releaseElInList" 
                                            :showParents="release.componentDetails.type === 'PRODUCT'"
                                / -->
                            </div>
                        </ul>
                    </div>
                    <div class="container" v-if="updatedRelease.type !== 'PLACEHOLDER' && updatedRelease.componentDetails.type !== 'PRODUCT'">
                        <h3>Source Code Entries
                            <Icon v-if="isWritable && isUpdatable" class="clickable addIcon" size="25" title="Update Source Code Entry" @click="showReleaseAddProducesSce=true">
                                <CirclePlus/>
                            </Icon>
                        </h3>
                        <n-data-table :data="commits" :columns="commitTableFields" :row-key="artifactsRowKey" />
                    </div>
                    <div class="container">
                        <h3>Artifacts
                            <Icon v-if="isWritable" class="clickable addIcon" size="25" title="Add Artifact" @click="showReleaseAddProducesArtifactModal=true">
                                <CirclePlus/>
                            </Icon>
                        </h3>
                        <n-data-table :data="artifacts" :columns="artifactsTableFields" :row-key="artifactsRowKey" />
                        <div v-if="release.releaseCollection && release.releaseCollection?.artifactComparison?.changelog">
                            <h4>Artifact Changelog</h4>
                            <h5>Components Added</h5>
                            <ul>
                                <li v-for="added in release.releaseCollection.artifactComparison.changelog.added" :key="added.purl">
                                    {{ added.purl }}
                                </li>
                            </ul>
                            <h5>Components Removed</h5>
                            <ul>
                                <li v-for="removed in release.releaseCollection.artifactComparison.changelog.removed" :key="removed.purl">
                                    {{ removed.purl }}
                                </li>
                            </ul>
                        </div>
                        
                    </div>
                    <div class="container">
                        <h3>
                            Produced Deliverables
                            <Icon v-if="isWritable && isUpdatable" class="clickable addIcon" size="25" title="Add Deliverable" @click="showReleaseAddDeliverableModal=true">
                                <CirclePlus/>
                            </Icon>
                        </h3>
                        <n-data-table :data="outboundDeliverables" :columns="deliverableTableFields" :row-key="artifactsRowKey" />
                    </div>
                    <div class="container" v-if="false">
                        <h3>
                            Inbound Deliverables
                        </h3>
                        <n-data-table :data="inboundDeliverables" :columns="deliverableTableFields" :row-key="artifactsRowKey" />
                    </div>
                    <div class="container" v-if="updatedRelease.inProducts && updatedRelease.inProducts.length">
                        <h3>Part of Products</h3>
                        <n-data-table :data="updatedRelease.inProducts" :columns="inProductsTableFields" :row-key="artifactsRowKey" />
                    </div>
                </n-tab-pane>
                <n-tab-pane name="approvals" tab="Approvals">
                    <div class="container" v-if="updatedRelease.type !== 'PLACEHOLDER'">
                        <n-data-table :data="releaseApprovalTableData" :columns="releaseApprovalTableFields" :row-key="approvalRowKey" />
                        <n-spin :show="approvalPending" small style="margin-top: 5px;">
                            <n-button @click="triggerApproval" :disabled="approvalPending">
                                <span v-if="approvalPending" class="ml-2">Saving...</span>
                                <span v-else>Save Approvals</span>
                            </n-button>
                        </n-spin>
                    </div>
                </n-tab-pane>
                <n-tab-pane v-if="false" name="tickets" tab="Tickets">
                    <div class="container">
                        <h3>Tickets</h3>
                        <ul v-if="release.ticketDetails">
                            <li v-for="t in release.ticketDetails" :key="t.uuid">
                                <b> {{t.identifier}}:</b> {{t.summary}}
                                <n-badge v-if="t.status === 'DONE'" variant="success" :value="t.status"></n-badge>
                                <n-badge v-else-if="t.status === 'IN_PROGRESS'" variant="primary" value="In Progress" ></n-badge>
                                <n-badge v-else variant="light" :value="t.status"></n-badge>
                                <a :href="t.uri" target="_blank" rel="noopener noreferrer">
                                    <Icon class="clickable icons" size="15" title="Open External Ticket In New Tab">
                                        <Link/>
                                    </Icon>
                                </a>
                                <p>{{t.content}}</p>
                                
                            </li>
                        </ul>
                    </div>
                </n-tab-pane>
                <n-tab-pane name="compare" tab="Compare">
                    <div class="container">
                        <n-select style="width:50%;" placeholder="Select a version to compare" label="label" :options="releasesOptions" filterable @update:value="getComparison" />
                        <div v-if="selectedReleaseId">
                            <div>
                                <strong>Git Diff Command: </strong>
                                <span v-if="gitdiff !== ''">
                                    <code>{{ gitdiff }}  </code>
                                    <Icon class="clickable icons" title="Copy to clipboard" size="17" @click="copyToClipboard(gitdiff)">
                                        <ClipboardCheck/>
                                    </Icon>
                                </span>
                                <span v-else>
                                    Current Release has no commits to compare with
                                </span>
                            </div>
                            <div>
                                <changelog-view 
                                    :release1prop="updatedRelease.uuid" 
                                    :release2prop="selectedReleaseId"
                                    :orgprop="updatedRelease.orgDetails.uuid"
                                    :componenttypeprop="release.componentDetails.type" 
                                />
                            </div>
                        </div>
                    </div>
                </n-tab-pane>
                <n-tab-pane name="history" tab="History">
                    <h3>Release History</h3>
                    <n-data-table :columns="releaseHistoryFields" :data="release.updateEvents" class="table-hover" />
                    <h3>Approval History</h3>
                    <n-data-table :columns="approvalHistoryFields" :data="release.approvalEvents" class="table-hover" />
                </n-tab-pane>
                <n-tab-pane name="meta" tab="Meta">
                    <div class="container">
                        <div>
                            <h3>Notes</h3>
                            <n-input type="textarea" v-if="userPermission && userPermission !== 'READ_ONLY'"
                                v-model:value="updatedRelease.notes" rows="2" />
                            <n-input type="textarea" v-else :value="updatedRelease.notes" rows="2" readonly />
                            <n-button v-if="userPermission && userPermission !== 'READ_ONLY'" @click="save"
                                v-show="release.notes !== updatedRelease.notes">Save Notes</n-button>
                        </div>
                        <div>
                            <h3 class="mt-3">Tags</h3>
                            <n-data-table :columns="releaseTagsFields" :data="updatedRelease.tags" class="table-hover" />
                            <n-form class="pb-5 mb-5" 
                                v-if="userPermission && userPermission !== 'READ_ONLY'">
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
            </n-tabs>
        </div>
        <n-modal
            style="width: 90%;"
            v-model:show="showDisapproveProductsConfirmationModal"
            preset="dialog"
            :show-icon="false" >
            <n-card title="NOTICE: Disapprove Product Releases">
                Select products to disapprove parent releases:
                <div v-if="updatedRelease.inProducts && updatedRelease.inProducts.length" class="m-4">
                    <n-form label-placement="left" :style="{maxWidth: '640px'}" size="medium">
                        <n-checkbox-group v-model:value="productsForDisapproval" :options="productOptions">
                            <n-checkbox v-for="b in productOptions" :key="b.value" :value="b.value" :label="b.label"></n-checkbox>
                        </n-checkbox-group>
                        <n-button attr-type="submit" @click="approve" type="success">Save</n-button>
                        <n-button attr-type="reset" @click="resetApprovals">Reset</n-button>
                    </n-form>
                </div>
            </n-card>
        </n-modal>
        <n-modal
            style="width: 90%;"
            v-model:show="showAddComponentReleaseModal"
            preset="dialog"
            :show-icon="false" >
            <create-release class="addComponentRelease" v-if="updatedRelease.orgDetails"
                :attemptPickRelease="true"
                :orgProp="updatedRelease.orgDetails.uuid"
                @createdRelease="addComponentRelease" />
        </n-modal>

        <n-modal
            style="width: 90%;"
            v-model:show="showReleaseAddProducesSce"
            preset="dialog"
            :show-icon="false">
            <create-source-code-entry v-if="updatedRelease.orgDetails" @updateSce="updateSce"
                :inputOrgUuid="updatedRelease.orgDetails.uuid"
                :inputBranch="updatedRelease.branchDetails.uuid" />
        </n-modal>
        <n-modal
            v-model:show="showReleaseAddProducesArtifactModal"
            style="width: 90%;"
            preset="dialog"
            :show-icon="false" >
            <create-artifact v-if="updatedRelease.orgDetails" @addArtifact="addArtifact"
                :inputOrgUuid="updatedRelease.orgDetails.uuid"
                :inputRelease="updatedRelease.uuid"
                :inputSourceCodeEntry="updatedRelease.sourceCodeEntry"
                :inputBelongsTo="'RELEASE'" />
        </n-modal>
        <n-modal
            v-model:show="showSCEAddArtifactModal"
            style="width: 90%;"
            preset="dialog"
            :show-icon="false" >
            <create-artifact v-if="updatedRelease.orgDetails" @addArtifact="addArtifact"
                :inputOrgUuid="updatedRelease.orgDetails.uuid"
                :inputRelease="updatedRelease.uuid"
                :inputSce="sceAddArtifactSceId"
                :inputBelongsTo="'SCE'" />
        </n-modal>
        <n-modal
            v-model:show="showDeliverableAddArtifactModal"
            style="width: 90%;"
            preset="dialog"
            :show-icon="false" >
            <create-artifact v-if="updatedRelease.orgDetails" @addArtifact="addArtifact"
                :inputOrgUuid="updatedRelease.orgDetails.uuid"
                :inputRelease="updatedRelease.uuid"
                :inputDeliverarble="deliverableAddArtifactSceId"
                :inputBelongsTo="'DELIVERABLE'" />
        </n-modal>
        <n-modal
            v-model:show="showAddNewBomVersionModal"
            style="width: 90%;"
            preset="dialog"
            :show-icon="false" >
            <create-artifact v-if="updatedRelease.orgDetails" @addArtifact="addArtifact"
                :inputOrgUuid="updatedRelease.orgDetails.uuid"
                :inputRelease="updatedRelease.uuid"
                :inputSce="deliverableAddArtifactSceId"
                :inputDeliverarble="deliverableAddArtifactSceId"
                :inputBelongsTo="addNewBomBelongsTo"
                :isUpdateExistingBom="true"
                :updateArtifact="artifactToUpdate"
                />
        </n-modal>
        <n-modal
            v-model:show="showReleaseAddDeliverableModal"
            style="width: 90%;"
            preset="dialog"
            :show-icon="false" >
            <create-deliverable v-if="updatedRelease.orgDetails"
                :inputOrgUuid="updatedRelease.orgDetails.uuid"
                :inputRelease="updatedRelease.uuid"
                :inputBranch="updatedRelease.branch"
                @addDeliverable="addArtifact"
                />
        </n-modal>
        <n-modal
            v-model:show="cloneReleaseToFsObj.showModal"
            :title="'Create Feature Set From Release - ' + cloneReleaseToFsObj.version"
            preset="dialog"
            :show-icon="false" >
            <n-form>
                <n-input
                    v-model:value="cloneReleaseToFsObj.fsName"
                    required
                    placeholder="Enter New Feature Set Name" 
                />
                <n-button type="success" @click="createFsFromRelease">Create</n-button>
            </n-form>
        </n-modal>
        <n-modal
            v-model:show="showUploadArtifactModal"
            title='Upload Artifact'
            preset="dialog"
            :show-icon="false" >
            <n-form-item label="Select Artifact: ">
                <n-upload v-model:value="fileList" @change="onFileChange">
                    <n-button>
                    Upload File
                    </n-button>
                </n-upload>
            </n-form-item>
            <n-form-item label="Artifact Tag: ">
                <n-input v-model:value="fileTag" placeholder="Tag for artifact"></n-input>
            </n-form-item>
            <n-button @click="submitForm">Submit</n-button>
        </n-modal>
    </div>
    

</template>
    
<script lang="ts">
export default {
    name: 'ReleaseView'
}
</script>
<script lang="ts" setup>
import ChangelogView from '@/components/ChangelogView.vue'
import CreateArtifact from '@/components/CreateArtifact.vue'
import CreateDeliverable from '@/components/CreateDeliverable.vue'
import CreateRelease from '@/components/CreateRelease.vue'
import CreateSourceCodeEntry from '@/components/CreateSourceCodeEntry.vue'
import axios from '../utils/axios'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions, { SwalData } from '@/utils/commonFunctions'
import graphqlQueries from '@/utils/graphqlQueries'
import { GlobeAdd24Regular, Info24Regular } from '@vicons/fluent'
import { Box, CirclePlus, ClipboardCheck, Download, Edit, FileDownload, FileInvoice, GitCompare, Link, Trash, Refresh, Upload } from '@vicons/tabler'
import { CancelOutlined } from '@vicons/material'
import { Icon } from '@vicons/utils'
import { BoxArrowUp20Regular, Info20Regular } from '@vicons/fluent'
import { SecurityScanOutlined } from '@vicons/antd'
import type { SelectOption } from 'naive-ui'
import { NBadge, NButton, NCard, NCheckbox, NCheckboxGroup, NDataTable, NDropdown, NForm, NFormItem, NIcon, NInput, NInputGroup, NModal, NRadioButton, NRadioGroup, NSelect, NSpin, NSpace, NSwitch, NTabPane, NTabs, NTag, NFlex, NTooltip, NUpload, NotificationType, useNotification, DataTableColumns, NGrid, NGi } from 'naive-ui'
import Swal from 'sweetalert2'
import { Component, ComputedRef, Ref, computed, h, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import constants from '@/utils/constants'
import { DownloadLink} from '@/utils/commonTypes'

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

const emit = defineEmits(['approvalsChanged', 'closeRelease'])

const lifecycleOptions = constants.LifecycleOptions

onMounted(async () => {
    await fetchRelease()
    await fetchReleaseKeys()
})

const pullRequest: ComputedRef<any> = computed((): any => {
    let pullRequest = null
    pullRequest = release.value.branchDetails.pullRequests && release.value.branchDetails.pullRequests.length ? release.value.branchDetails.pullRequests.find((pr: any) => pr.commits && pr.commits.includes(release.value.sourceCodeEntry)) : null
    return pullRequest || null
})


const releaseUuid: Ref<string> = ref(props.uuidprop ?? route.params.uuid.toString())
const release: Ref<any> = ref({})
const updatedRelease: Ref<any> = ref({})

const instances: Ref<any[]> = ref([])
const instanceUriNameMap: Ref<any[]> = ref([])
const releaseInstances: Ref<any[]> = ref([])

const approvalEntries: Ref<any[]> = ref([])

const availableApprovalIds: Ref<any> = ref({})

const approvalMatrixCheckboxes: Ref<any> = ref({})

const givenApprovals: Ref<any> = ref({})

const words: Ref<any> = ref({})
const isComponent: Ref<boolean> = ref(true)

async function fetchRelease () {
    let rlzFetchObj: any = {
        release: releaseUuid.value
    }
    if (props.orgprop) {
        rlzFetchObj.org = props.orgprop
    }
    release.value = await store.dispatch('fetchReleaseById', rlzFetchObj)

    if (release.value) releaseUuid.value = release.value.uuid
    if (false) {
        await store.dispatch('fetchInstances', release.value.orgDetails.uuid)
        instances.value = store.getters.instancesOfOrg(release.value.orgDetails.uuid)
        instanceUriNameMap.value = instances.value.reduce(function (map, inst) {
            map[inst.uuid] = inst.uri
            return map
        }, {})
    }
    updatedRelease.value = deepCopyRelease(release.value)
    if (!updatedRelease.value.type) {
        updatedRelease.value.type = 'REGULAR'
    }
    let deployedOnInstances = updatedRelease.value.deployedOnInstances
    if (deployedOnInstances) {
        releaseInstances.value = Object.keys(deployedOnInstances).reduce(function (r, k) {
            return r.concat(r, deployedOnInstances[k])
        }, [])
    }

    if (updatedRelease.value.componentDetails.approvalPolicyDetails) {
        givenApprovals.value = computeGivenApprovalsFromRelease()
        approvalEntries.value = updatedRelease.value.componentDetails.approvalPolicyDetails.approvalEntryDetails
        approvalEntries.value.forEach(ae => {
            approvalMatrixCheckboxes.value[ae.uuid] = {}
            ae.approvalRequirements.forEach((ar: any) => {
                availableApprovalIds.value[ar.allowedApprovalRoleIdExpanded[0].id] = ar.allowedApprovalRoleIdExpanded[0].displayView
                let checkBoxValue = 'UNSET'
                if (givenApprovals.value[ae.uuid] && (givenApprovals.value[ae.uuid][ar.allowedApprovalRoleIdExpanded[0].id] === 'APPROVED' || 
                    givenApprovals.value[ae.uuid][ar.allowedApprovalRoleIdExpanded[0].id] === 'DISAPPROVED')) {
                    checkBoxValue = givenApprovals.value[ae.uuid][ar.allowedApprovalRoleIdExpanded[0].id]
                }
                approvalMatrixCheckboxes.value[ae.uuid][ar.allowedApprovalRoleIdExpanded[0].id] = checkBoxValue
            })
        })
    }

    isComponent.value = (updatedRelease.value.componentDetails.type === 'COMPONENT')
    words.value = {
        branchFirstUpper: (isComponent.value) ? 'Branch' : 'Feature Set',
        branchFirstUpperPlural: (isComponent.value) ? 'Branches' : 'Feature Sets',
        branch: (isComponent.value) ? 'branch' : 'feature set',
        componentFirstUpper: (isComponent.value) ? 'Component' : 'Product',
        component: (isComponent.value) ? 'component' : 'product',
        componentsFirstUpper: (isComponent.value) ? 'Components' : 'Products'
    }
}

// BOM EXPORT

// Media type for SBOM export
const selectedSbomMediaType = ref('JSON')

//getAggregatedChangelog
const showExportSBOMModal: Ref<boolean> = ref(false)

const rebomTypes: ComputedRef<any[]> = computed((): any[] => {
    let types: any[] = []
    // if(updatedRelease.value.sourceCodeEntryDetails && updatedRelease.value.sourceCodeEntryDetails.artifacts && updatedRelease.value.sourceCodeEntryDetails.artifacts.length){
    //     types.push(...updatedRelease.value.sourceCodeEntryDetails.artifacts.map((art: any) => art.internalBom.belongsTo))
    // }
    // if(updatedRelease.value.artifactDetails && updatedRelease.value.artifactDetails.length){
    //     types.push(...updatedRelease.value.artifactDetails.flatMap((art: any) => art.internalBom.belongsTo)) )
    // }
    // if(types && types.length){
    //     types = types.filter((type: string) => type)
    //     types =  [...new Set(types)]
    //     types = types.map((t: string) => { return {key: t.charAt(0).toUpperCase() + t.slice(1).toLowerCase(), value: t.toUpperCase() }})
    // }
    types.push(
        {key: 'All', value: ''},
        {key: 'DELIVERABLE', value: 'DELIVERABLE'},
        {key: 'RELEASE', value: 'RELEASE'},
        {key: 'SCE', value: 'SCE'}
    )
    return types
})
const exportBomType: Ref<string> = ref('SBOM')
const selectedRebomType: Ref<string> = ref('')
const tldOnly: Ref<boolean> = ref(true)
const selectedBomStructureType: Ref<string> = ref('FLAT')
const bomExportQuery: ComputedRef<string> = computed((): string => {
    let queryOptions = '?tldOnly=false'
    if(tldOnly.value){
        queryOptions = '?tldOnly=true'
    }
    if(selectedBomStructureType.value !== ''){
        queryOptions = queryOptions + '&' + 'structure=' +  selectedBomStructureType.value
    }
    if(selectedRebomType.value !== ''){
        queryOptions = queryOptions + '&' + 'bomType=' +  selectedRebomType.value
    }
    return queryOptions
})
// const tldOnly = [
//     { key: 'TLD Only', value: true },
//     { key: 'Exhaustive', value: false }
// ]
const bomStructureTypes = [
    { key: 'Hierarchical', value: 'HIERARCHICAL' },
    { key: 'Flat', value: 'FLAT' }
]

const exportSbomWithConfig = async function() {

} 

const showMarketingVersionModal: Ref<boolean> = ref(false)
const nextMarketingVersion: Ref<string> = ref(release.value.marketingVersion)
const updatedMarketingVersion: Ref<string> = ref(release.value.marketingVersion ?? nextMarketingVersion.value)
const openMarketingVersionModal = async function(){
    await getNextVersion(release.value.branchDetails.uuid)
    showMarketingVersionModal.value = true
}
const getNextVersion = async function (branch: string){
    const resp = await graphqlClient.query({
        query: gql`
            query getNextVersion($branchUuid: ID!) {
                getNextVersion(branchUuid: $branchUuid, versionType: MARKETING)
            }
            `,
        variables: { branchUuid: branch },
        fetchPolicy: 'no-cache'
    })
    nextMarketingVersion.value = resp.data.getNextVersion
    updatedMarketingVersion.value = updatedRelease.value.marketingVersion ?? nextMarketingVersion.value

}
const setMarketingVersion = async function (){
    let success = false
    try {
        let resp = await graphqlClient.mutate({
            mutation: gql`
                mutation setReleaseMarketingVersion($releaseUuid: ID!, $versionString: String!) {
                    setReleaseMarketingVersion(releaseUuid: $releaseUuid, versionString: $versionString)
                }
                `,
            variables: { releaseUuid: releaseUuid.value, versionString: updatedMarketingVersion.value },
            fetchPolicy: 'no-cache'
        })
        success = resp.data.setReleaseMarketingVersion
    } catch (err: any) {
        showMarketingVersionModal.value = false
        emit('closeRelease')
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    showMarketingVersionModal.value = false
    if (success) {
        emit('closeRelease')
        Swal.fire(
            'Success',
            `Success setting marketing version on release.`,
            'success'
        )
    }

}
function deepCopyRelease (rlz: any) {
    return JSON.parse(JSON.stringify(rlz))
}

function tranformReleaseTimingVisData(timingData: any) {
    return timingData.filter((data: any) => data.event !== 'DEPLOYED' && data.duration > 0)
}
function tranformDeployTimingVisData(timingData: any) {
    return timingData.filter((data: any) => data.event === 'DEPLOYED' && data.duration > 0).map((data: any) => {
        data.instanceName = instanceUriNameMap.value[data.instanceUuid]
        return data
    })
}
const releaseTimingData: Ref<any> =   ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Duration of Release Phases',
    data: {
        values: []
    },
    mark: {
        type: 'bar',
        tooltip: true
    },
    transform: [
        { 'calculate': 'datum.duration/60', 'as': 'duration_minutes' }
    ],
    encoding: {
        y: {
            field: 'event',
            type: 'nominal',
            axis: {
                title: 'Build Stage'
            }
        },
        x: {
            field: 'duration_minutes',
            type: 'quantitative',
            axis: {
                title: 'Duration, minutes'
            }
        }
    }
})
const deployTimingData: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Duration of Deployments',
    data: {
        values: []
    },
    mark: {
        type: 'bar',
        tooltip: true
    },
    transform: [
        { 'calculate': 'datum.duration/60', 'as': 'duration_minutes' }
    ],
    encoding: {
        y: {
            field: 'instanceName',
            type: 'nominal',
            axis: {
                title: 'Instance'
            }
        },
        x: {
            field: 'duration_minutes',
            type: 'quantitative',
            axis: {
                title: 'Duration, minutes'
            }
        },
        color: {
            field: 'environment',
            type: 'nominal'
        }
    }
})

function requestApproval(type: string) {
    axios.get('/api/manual/v1/release/requestApproval/' + releaseUuid.value + '/' + type).then(response => {
        if (response.data) {
            notify('success', 'Approval Requested', 'Approval Requested for the type: ' + type)
        }
    })
}

const productsForDisapproval: Ref<any[]> = ref([])
const showDisapproveProductsConfirmationModal: Ref<boolean> = ref(false)
const productOptions: ComputedRef<any> = computed((): any => {
    let productOptions: any[] = []
    if (updatedRelease.value.inProducts && updatedRelease.value.inProducts.length) {
        productOptions = updatedRelease.value.inProducts.map((b:any) => { return { 'label': b.componentDetails.name + ' - ' + b.version, 'value': b.uuid } })
    }
    return productOptions
})

async function triggerApproval() {
    approvalPending.value = true
    const approvals = computeApprovals()
    if (approvals.length) {
        approve(approvals)
    } else {
        approvalPending.value = false
    }
}

type WhoUpdated = {
    createdType: string;
    lastUpdatedBy: string;
}

type ApprovalInput = {
    approvalEntry: string;
    approvalRoleId: string;
    state: string;
}

type ApprovalEvent = {
    approvalEntry: string;
    approvalRoleId: string;
    state: string;
    date: string;
    wu: WhoUpdated;
}

function computeGivenApprovalsFromRelease () {
    const givenApprovals: any = {}
    if (updatedRelease.value && updatedRelease.value.approvalEvents && updatedRelease.value.approvalEvents.length) {
        const approvalEvents: ApprovalEvent[] = updatedRelease.value.approvalEvents
        approvalEvents.forEach(ae => {
            if (!givenApprovals[ae.approvalEntry]) givenApprovals[ae.approvalEntry] = {}
            givenApprovals[ae.approvalEntry][ae.approvalRoleId] = ae.state
        })
    }
    return givenApprovals
}

function computeApprovals () : ApprovalInput[] {
    const approvals: ApprovalInput[] = []
    Object.keys(approvalMatrixCheckboxes.value).forEach((x: any) => {
        Object.keys(approvalMatrixCheckboxes.value[x]).forEach(y => {
            if (approvalMatrixCheckboxes.value[x][y] !== 'UNSET' && (!givenApprovals.value[x] || !givenApprovals.value[x][y])) {
                const approvalInput: ApprovalInput = {
                    approvalEntry: x,
                    approvalRoleId: y,
                    state: approvalMatrixCheckboxes.value[x][y]
                }
                approvals.push(approvalInput)
            }
        })
    })
    return approvals
}

async function approve(approvals: ApprovalInput[]) {
    const approvalProps = {
        release: updatedRelease.value.uuid,
        approvals
    }
    store.dispatch('approveRelease', approvalProps).then(response => {
        fetchRelease()
        notify('success', 'Saved', 'Approvals Saved.')
    }).catch(error => {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(error.message),
            'error'
        )
        emit('closeRelease')
    }).finally(() => {
        approvalPending.value = false
    })
}
function resetApprovals () {
    showDisapproveProductsConfirmationModal.value = false
    approvalPending.value = false
    updatedRelease.value.approvals = release.value.approvals
}
const activeApprovalTypes: Ref<any> = ref({})
const newRlzApprovals: Ref<any[]> = ref([])

async function getActiveApprovalTypes() {
    
    let updRlzApprovalsForNew: any[] = []
    let gqlResp = await graphqlClient.query({
        query: gql`
                query activeApprovalTypes {
                    activeApprovalTypes(orgUuid: "${updatedRelease.value.orgDetails.uuid}", appUuid: "${updatedRelease.value.componentDetails.resourceGroup}") {
                        approvalTypes
                    }
                }`
    })
    activeApprovalTypes.value = gqlResp.data.activeApprovalTypes.approvalTypes

    // duplicate approval keys on release to keep track
    let presetApprovalKeys = Object.keys(updatedRelease.value.approvals)
    let updRlzApprovals: any = {}
    Object.keys(activeApprovalTypes.value).forEach(at => {
        updRlzApprovals[at] = updatedRelease.value.approvals[at]
        let approvalObj = {
            type: at,
            modifiable: activeApprovalTypes.value[at]
        }
        updRlzApprovalsForNew.push(approvalObj)
        presetApprovalKeys = presetApprovalKeys.filter(el => { return el !== at })
    })
    // parse remaining approval keys from release
    presetApprovalKeys.forEach(rlzAp => {
        let approvalObj = {
            type: rlzAp,
            modifiable: false
        }
        updRlzApprovalsForNew.push(approvalObj)
        updRlzApprovals[rlzAp] = updatedRelease.value.approvals[rlzAp]
    })
    updatedRelease.value.approvals = updRlzApprovals
    newRlzApprovals.value = updRlzApprovalsForNew
    // axios.get('/api/manual/v1/approvalMatrix/activeApprovalTypes/' + updatedRelease.value.orgDetails.uuid).then(response => {
}

const deployedOnEnvFields: any[] = [
    {
        key: 'env',
        title: 'Environment'
    },
    {
        key: 'approvals',
        title: 'Approvals',
        render(row: any) {
            let el = h('div')
            let els: any[] = []
            row.approvals.forEach((at: any) => {
                let badge = h(NTag, {round: true, size: 'small'}, () => at)
                if(updatedRelease.value.approvals[at] === true){
                    badge = h(NTag, {round: true, size: 'small', type: 'success'}, () => at)
                }else if(updatedRelease.value.approvals[at] === false){
                    badge = h(NTag, {round: true, size: 'small', type: 'error'}, () => at)
                }
                els.push(badge)
            });
            el = h('div', els)
            return el
        }
    },
    {
        key: 'instance',
        title: 'Instance'
    },
    {
        key: 'deployedVersion',
        title: 'Deployed Version'
    },
    {
        key: 'changelog',
        title: 'Changelog',
        render(row: any) {
            let el = h('div','')
            if(row.uuid){
                el = h('div',[
                    h(
                        RouterLink,
                        {
                            to: {
                                name: 'ChangelogView',
                                params: {
                                    release1prop: row.uuid,
                                    release2prop: updatedRelease.value.uuid,
                                    orgprop: updatedRelease.value.orgDetails.uuid,
                                    componenttypeprop: release.value.componentDetails.type,
                                    isrouterlink: 'true'
                                },
                            },
                            target: "_blank"
                        },
                        { default: () => h(renderIcon(GitCompare))}
                    ),
                ]) 
            }
            return el
        }
    }
]
const deployedOnEnv: Ref<any[]> = ref([])
const aggregationType: Ref<string> = ref('NONE')


const userPermission: ComputedRef<any> = computed((): any => {
    let userPermission = ''
    if (release.value && release.value.orgDetails) userPermission = commonFunctions.getUserPermission(release.value.orgDetails.uuid, store.getters.myuser).org
    return userPermission
})

const isWritable: ComputedRef<boolean> = computed((): any => userPermission.value !== 'READ_ONLY')
const isUpdatable: ComputedRef<boolean> = computed(
    (): any => updatedRelease.value.lifecycle === 'DRAFT' )

const approvalPending: Ref<boolean> = ref(false)
const bomExportPending: Ref<boolean> = ref(false)

async function lifecycleChange(newLifecycle: string) {
    approvalPending.value = true
    updatedRelease.value.lifecycle = newLifecycle
    try {
        const updRelease = await store.dispatch('updateReleaseLifecycle', updatedRelease.value)
        release.value = deepCopyRelease(updRelease)
        updatedRelease.value = deepCopyRelease(updRelease)
        notify('success', 'Saved', 'Lifecycle updated.')
    } catch (error: any) {
        console.error(error)
        notify('error', 'Error', 'Error updating release lifecycle.')
        updatedRelease.value = deepCopyRelease(release.value)
    }
    approvalPending.value = false
}

async function save() {
    if (release.value.lifecycle === 'DRAFT' || updatedRelease.value.lifecycle === 'DRAFT') {
        try {
            await store.dispatch('updateRelease', updatedRelease.value)
            fetchRelease()
            notify('success', 'Saved', 'Release saved.')
        } catch (err: any) {
            updatedRelease.value = deepCopyRelease(release.value)
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(err.message),
                'error'
            )
            console.error(err)
        }
    } else if (release.value.notes !== updatedRelease.value.notes ||
        release.value.tags !== updatedRelease.value.tags) {
        try {
            await store.dispatch('updateReleaseTagsMeta', updatedRelease.value)
            fetchRelease()
            notify('success', 'Saved', 'Release saved.')
        } catch (err: any) {
            updatedRelease.value = deepCopyRelease(release.value)
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(err.message),
                'error'
            )
            console.error(err)
        }
    } else {
        updatedRelease.value = deepCopyRelease(release.value)
        notify('error', 'Cannot update release!', 'Only releases in DRAFT Lifecycle state may be updated.')
    }
    
}
function renderIcon (icon: Component) {
    return () => h(NIcon, null, { default: () => h(icon) })
}

// Components
const showAddComponentReleaseModal: Ref<boolean> = ref(false)

function addComponentRelease (rlz: any) {
    if (!updatedRelease.value.parentReleases || !updatedRelease.value.parentReleases.length) {
        updatedRelease.value.parentReleases = []
    }
    updatedRelease.value.parentReleases.push({
        release: rlz.uuid
    })
    showAddComponentReleaseModal.value=false
    save()
}

function deleteComponentRelease (uuid: string) {
    updatedRelease.value.parentReleases = updatedRelease.value.parentReleases.filter((r: any) => (r.release !== uuid))
    save()
}

function releaseUpdated (updProps: any) {
    let rlzToUpdate = updatedRelease.value.parentReleases.find((r: any) => (r.release === updProps.source))
    if (rlzToUpdate && updProps.target) {
        let indexToUpdate = updatedRelease.value.parentReleases.findIndex((r: any) => (r.release === updProps.source))
        rlzToUpdate.release = updProps.target
        updatedRelease.value.parentReleases.splice(indexToUpdate, 1, rlzToUpdate)
        save()
    }
}

function linkifyCommit(uri: string, commit: string){
    return commonFunctions.linkifyCommit(uri, commit)
}
function dateDisplay(date: any){
    return commonFunctions.dateDisplay(date)
}
const showReleaseAddProducesSce: Ref<boolean> = ref(false)
function  updateSce (value: any) {
    // fetch vcs repo
    let sce = store.getters.sourceCodeEntryById(value)
    let vcs = store.getters.vcsRepoById(sce.vcs)
    if (!vcs) {
        store.dispatch('fetchVcsRepo', releaseUuid.value)
    }
    updatedRelease.value.sourceCodeEntry = value
    showReleaseAddProducesSce.value = false
    save()
}

const showReleaseAddProducesArtifactModal: Ref<boolean> = ref(false)
const showSCEAddArtifactModal: Ref<boolean> = ref(false)
const sceAddArtifactSceId: Ref<string> = ref('')
const showDeliverableAddArtifactModal: Ref<boolean> = ref(false)
const showAddNewBomVersionModal: Ref<boolean> = ref(false)
const deliverableAddArtifactSceId: Ref<string> = ref('')
const showReleaseAddDeliverableModal: Ref<boolean> = ref(false)
const addNewBomBelongsTo: Ref<string> = ref('')
const artifactToUpdate: Ref<any> = ref({})
const showDownloadArtifactModal: Ref<boolean> = ref(false)
const selectedArtifactForDownload: Ref<any> = ref({})
const downloadType: Ref<string> = ref('DOWNLOAD')

function deleteArtifact (artifactUuid: string, releaseUuid: string) {
    Swal.fire({
        title: `Are you sure you want to archive this Artifact?`,
        text: `If you proceed, the artifact will be archived and you will not have access to its data.`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, archive!',
        cancelButtonText: 'No, cancel'
    }).then(async result => {
        if (result.value) {
            try {
                await graphqlClient.mutate({
                    mutation: gql`
                        mutation removeReleaseArtifact($artifactUuid: ID!, $releaseUuid: ID!) {
                            removeReleaseArtifact(artifactUuid: $artifactUuid, releaseUuid: $releaseUuid)
                        }`,
                    variables: {
                        artifactUuid: artifactUuid,
                        releaseUuid: releaseUuid
                    }
                })
                fetchRelease()
            } catch (err: any) {
                Swal.fire(
                    'Error!',
                    commonFunctions.parseGraphQLError(err.message),
                    'error'
                )
            }
        } else if (result.dismiss === Swal.DismissReason.cancel) {
            Swal.fire(
                'Cancelled',
                `Artifact archiving cancelled. The artifact is still active.`,
                'info'
            )
        }
    })
}
async function addArtifact () {
    await fetchRelease()
    showReleaseAddProducesArtifactModal.value = false
    showSCEAddArtifactModal.value = false
    showDeliverableAddArtifactModal.value = false
    showReleaseAddDeliverableModal.value = false
    showAddNewBomVersionModal.value = false

}

function openDownloadArtifactModal (artifact: any) {
    selectedArtifactForDownload.value = artifact
    downloadType.value = 'DOWNLOAD'
    showDownloadArtifactModal.value = true
}

function executeDownload () {
    if (downloadType.value === 'DOWNLOAD') {
        downloadArtifact(selectedArtifactForDownload.value)
    } else if (downloadType.value === 'RAW_DOWNLOAD') {
        downloadRawArtifact(selectedArtifactForDownload.value)
    }
    showDownloadArtifactModal.value = false
    notify('info', 'Processing Download', 'Your artifact is being downloaded...')
}

function setArtifactBelongsTo (art: any, belongsTo: string, belongsToId?: string,  belongsToUUID?: string) {
    const adc = commonFunctions.deepCopy(art)
    adc.belongsTo = belongsTo
    adc.belongsToId = belongsToId
    adc.belongsToUUID = belongsToUUID
    return adc
}

const artifacts: ComputedRef<any> = computed((): any => {
    let artifacts: any[] = []

    if (updatedRelease.value) {
        if(updatedRelease.value.artifactDetails && updatedRelease.value.artifactDetails.length) {
            artifacts.push.apply(artifacts, updatedRelease.value.artifactDetails.map((ad: any) => setArtifactBelongsTo(ad, 'Release', '', updatedRelease.value.uuid)))
        }
        if(updatedRelease.value.sourceCodeEntryDetails && updatedRelease.value.sourceCodeEntryDetails.artifactDetails && updatedRelease.value.sourceCodeEntryDetails.artifactDetails.length) {
            const cursce = updatedRelease.value.sourceCodeEntryDetails
            artifacts.push.apply(artifacts,
                updatedRelease.value.sourceCodeEntryDetails.artifactDetails
                    .filter((ad: any) => ad.componentUuid === updatedRelease.value.componentDetails.uuid)
                    .map((ad: any) => setArtifactBelongsTo(ad, 'Source Code Entry', '', cursce.uuid)))
        }
        
        updatedRelease.value.variantDetails.forEach ((vd: any) => {
            if (vd.outboundDeliverableDetails && vd.outboundDeliverableDetails.length) {
                vd.outboundDeliverableDetails.forEach ((odd: any) => {
                    if (odd.artifactDetails && odd.artifactDetails.length) {
                        artifacts.push.apply(artifacts, odd.artifactDetails.map((ad: any) => setArtifactBelongsTo(ad, 'Deliverable', odd.displayIdentifier, odd.uuid)))
                    }
                })
            }
        })

        if(updatedRelease.value.inboundDeliverableDetails && updatedRelease.value.inboundDeliverableDetails.length) {
            updatedRelease.value.inboundDeliverableDetails.forEach ((id: any) => {
                if (id.artifactDetails && id.artifactDetails.length) {
                    artifacts.push.apply(artifacts, id.artifactDetails.map((ad: any) => setArtifactBelongsTo(ad, 'Deliverable', id.displayIdentifier, id.uuid)))
                }
            })
        }
    }
    
    return artifacts
})

const outboundDeliverables: ComputedRef<any> = computed((): any => {
    let outboundDeliverables: any[] = []
    if(updatedRelease.value && updatedRelease.value.variantDetails && updatedRelease.value.variantDetails.length){
        updatedRelease.value.variantDetails.forEach ((vd: any) => {
            if (vd.outboundDeliverableDetails && vd.outboundDeliverableDetails.length) {
                outboundDeliverables.push.apply(outboundDeliverables, vd.outboundDeliverableDetails)
            }
        })
    }
    return outboundDeliverables
})

const inboundDeliverables: ComputedRef<any> = computed((): any => {
    let inboundDeliverables: any[] = []

    if(updatedRelease.value && updatedRelease.value.inboundDeliverableDetails && updatedRelease.value.inboundDeliverableDetails.length){
        inboundDeliverables = updatedRelease.value.inboundDeliverableDetails
    }
    return inboundDeliverables
})

const commits: ComputedRef<any> = computed((): any => {
    let commits: any[] = []

    if (updatedRelease.value && updatedRelease.value.commitsDetails && updatedRelease.value.commitsDetails.length) {
        commits = updatedRelease.value.commitsDetails
    } else if (updatedRelease.value && updatedRelease.value.sourceCodeEntryDetails && updatedRelease.value.sourceCodeEntryDetails.commit) {
        commits = [updatedRelease.value.sourceCodeEntryDetails]
    }
    return commits
})

const showUploadArtifactModal: Ref<boolean> = ref(false)
const artifactUploadData = ref({
    file: null,
    tag: '',
    uuid: '',
    artifactType: ''
})
const fileList: Ref<any> = ref([])
const artifactType: Ref<any> = ref(null)
const fileTag = ref('')
function onFileChange(newFileList: any) {
    fileList.value = newFileList
}
const submitForm = async () => {
    artifactUploadData.value.file = fileList.value?.file.file
    artifactUploadData.value.uuid = updatedRelease.value.uuid
    artifactUploadData.value.tag = fileTag.value
    artifactUploadData.value.artifactType = artifactType.value
    axios.post('/api/manual/v1/artifact/upload', artifactUploadData.value, {
        headers: {
            "Content-Type": "multipart/form-data"
        }
    }).then(async (response) => {
        showUploadArtifactModal.value = false
        await fetchRelease()
    })
    
};
const downloadArtifact = async (art: any) => {
    axios({
        method: 'get',
        url: '/api/manual/v1/artifact/' + art.uuid + '/download',
        responseType: 'arraybuffer',
    }).then(function (response) {
        const artType = art.tags.find((tag: any) => tag.key === 'mediaType')?.value
        const fileName = art.tags.find((tag: any) => tag.key === 'fileName')?.value
        let blob = new Blob([response.data], { type: artType })
        let link = document.createElement('a')
        link.href = window.URL.createObjectURL(blob)
        link.download = fileName
        link.click()
    })
}

const findReleasesSharedByArtifact = async(id: string) => {
    const response = await graphqlClient.query({
        query: gql`
            query FetchArtifactReleases($artUuid: ID!) {
                artifactReleases(artUuid: $artUuid) {
                    uuid
                    version
                    createdDate
                    sourceCodeEntryDetails {
                        uuid
                        commit
                    }
                }
            }`,
        variables: { artUuid: id }
    })
    return response.data.artifactReleases
}
const getBomVersion = async(id: string) => {
    const response = await graphqlClient.query({
        query: gql`
            query FetchArtifactBomSerial($artUuid: ID!) {
                artifactBomLatestVersion(artUuid: $artUuid)
            }`,
        variables: { artUuid: id }
    })
    return response.data.artifactBomLatestVersion
}
const uploadNewBomVersion = async (art: any) => {
    
    const isBomArtifact = commonFunctions.isCycloneDXBomArtifact(art)
    let questionText = ''
    if(isBomArtifact){
        const releasesSharingThisArtifact = await findReleasesSharedByArtifact(art.uuid)
   
        const latestBomVersion: string = await getBomVersion(art.uuid)
        
        if(releasesSharingThisArtifact && releasesSharingThisArtifact.length > 1){
            const releaseVersions = releasesSharingThisArtifact.map(function(r: any){
                return r.version;
            }).join(", ");
            questionText = `This BOM has serial number \`${art.internalBom.id}\` and version \`${latestBomVersion}\` is currently shared with the following releases - <${releaseVersions}> - if you upload a new BOM version with the same serial number and incremented version, it will be updated for all these releases. If instead you upload a new BOM version with a new serial number, this release will be switched to the BOM you are uploading, while all other mentioned releases will be unaffected. `
        }else {
            questionText = `This BOM has serial number: \`${art.internalBom.id}\`. \nIf you upload a new BOM version with the same serial number, it will be recorded as a new version of the same BOM (recommended).\nIf instead you upload a new BOM version with a new serial number, the BOM reference will be switched to the BOM you are uploading.`
        }


    }else {
        const fileDigest = art.digestRecords.find(dr => dr.scope === 'ORIGINAL_FILE').digest
        questionText = `This Artifact has a file with digest \`${fileDigest}\` and version \`${art.version}\`. \nIf you upload a new file, the artifact reference will be switched to the file you are uploading.`
    }


    const swalResult = await Swal.fire({
        title: 'Update Artifact',
        text: questionText,
        showCancelButton: true,
        confirmButtonText: 'Confirm',
        cancelButtonText: 'Cancel'
    })

    if (swalResult.isConfirmed) {
        showAddNewBomVersionModal.value = true
        artifactToUpdate.value = art
        addNewBomBelongsTo.value = art?.internalBom?.belongsTo
        deliverableAddArtifactSceId.value = art.belongsToUUID
    }else{
        Swal.fire(
            'Cancelled',
            'Cancelled BOM Update',
            'error'
        )
    }
    
}
const downloadRawArtifact = async (art: any) => {
    axios({
        method: 'get',
        url: '/api/manual/v1/artifact/' + art.uuid + '/rawdownload',
        responseType: 'arraybuffer',
    }).then(function (response) {
        const artType = art.tags.find((tag: any) => tag.key === 'mediaType')?.value
        const fileName = art.tags.find((tag: any) => tag.key === 'fileName')?.value
        let blob = new Blob([response.data], { type: artType })
        let link = document.createElement('a')
        link.href = window.URL.createObjectURL(blob)
        link.download = fileName
        link.click()
    })
}

async function exportReleaseSbom (tldOnly: boolean, selectedBomStructureType: string, selectedRebomType: string, mediaType: string) {
    try {
        bomExportPending.value = true
        const gqlResp: any = await graphqlClient.mutate({
            mutation: gql`
                mutation releaseSbomExport($release: ID!, $tldOnly: Boolean, $structure: BomStructureType, $belongsTo: ArtifactBelongsToEnum, $mediaType: BomMediaType) {
                    releaseSbomExport(release: $release, tldOnly: $tldOnly, structure: $structure, belongsTo: $belongsTo, mediaType: $mediaType)
                }
            `,
            variables: {
                release: updatedRelease.value.uuid,
                tldOnly: tldOnly,
                structure: selectedBomStructureType,
                belongsTo: selectedRebomType ? selectedRebomType : null,
                mediaType: mediaType.toUpperCase()
            },
            fetchPolicy: 'no-cache'
        })
        let blobType = mediaType === 'JSON' ? 'application/json' : mediaType === 'EXCEL' ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' : 'text/csv'
        let exportContent = gqlResp.data.releaseSbomExport
        if (mediaType === 'JSON') {
            if (typeof exportContent !== 'string') {
                exportContent = JSON.stringify(exportContent, null, 2)
            }
        } else if (mediaType === 'EXCEL') {
            const binary = atob(exportContent);
            exportContent = Uint8Array.from(binary, c => c.charCodeAt(0));
        }
        const blob = new Blob([exportContent], { type: blobType })
        const link = document.createElement('a')
        link.href = window.URL.createObjectURL(blob)
        link.download = updatedRelease.value.uuid + '-sbom.' + (mediaType === 'EXCEL' ? 'xlsx' : mediaType.toLowerCase())
        link.click()
        notify('info', 'Processing Download', 'Your artifact is being downloaded...')
    } catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        bomExportPending.value = false
    }
}

async function exportReleaseObom () {
    try {
        bomExportPending.value = true
        const gqlResp: any = await graphqlClient.query({
            query: gql`
                query exportAsObomManual($releaseUuid: ID!) {
                    exportAsObomManual(releaseUuid: $releaseUuid)
                }
            `,
            variables: {
                releaseUuid: updatedRelease.value.uuid
            },
            fetchPolicy: 'no-cache'
        })
        const fileName = updatedRelease.value.uuid + '-obom.json'
        const blob = new Blob([gqlResp.data.exportAsObomManual], { type: 'application/json' })
        const link = document.createElement('a')
        link.href = window.URL.createObjectURL(blob)
        link.download = fileName
        link.click()
        notify('info', 'Processing Download', 'Your artifact is being downloaded...')
    } catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        bomExportPending.value = false
    }
}

//Compare
const releases: Ref<any[]> = ref([])
const gitdiff: Ref<string> = ref('')
const selectedReleaseId: Ref<string> = ref('')
const changelog: Ref<any> = ref({})

async function getComparison (uuid: string, option: SelectOption)  {
    changelog.value = await getChangelogWith(uuid)
    let selectedRelease = releases.value.find((r: any) => r.uuid === uuid)
    if (updatedRelease.value.sourceCodeEntryDetails && selectedRelease.sourceCodeEntryDetails) {
        gitdiff.value = 'git diff ' + updatedRelease.value.sourceCodeEntryDetails.commit + ' ' + selectedRelease.sourceCodeEntryDetails.commit
    }
    selectedReleaseId.value = uuid
}
async function getChangelogWith(uuid: string) {
    changelog.value = {}
    if (uuid) {
        let fetchRlzParams = {
            release1: updatedRelease.value.uuid,
            release2: uuid,
            org: updatedRelease.value.orgDetails.uuid,
            aggregated: aggregationType.value,
            timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
        }
        changelog.value = await store.dispatch('fetchChangelogBetweenReleases', fetchRlzParams)
    }
    return changelog.value
}
async function fetchReleases () {
    changelog.value = {}
    const response = await graphqlClient.query({
        query: gql`
            query FetchReleases($branchID: ID!) {
                releases(branchFilter: $branchID) {
                    uuid
                    version
                    createdDate
                    sourceCodeEntryDetails {
                        uuid
                        commit
                    }
                }
            }`,
        variables: { branchID: updatedRelease.value.branchDetails.uuid }
    })
    releases.value = response.data.releases.filter((rlz: any) => rlz.uuid !== updatedRelease.value.uuid)
}
const releasesOptions: ComputedRef<any> = computed((): any => {
    return releases.value.map((r: any) => {
        return {
            'label': r.version + " - " + (new Date(r.createdDate)).toLocaleString('en-CA'),
            'value': r.uuid
        }
    })
})

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

const releaseHistoryFields: any[] = [
    {
        key: 'date',
        title: 'Date'
    },
    {
        key: 'updatedBy',
        title: 'Updated By',
        render(row: any) {
            if (row.wu && row.wu.lastUpdatedBy) {
                return h(
                    NTooltip,
                    {
                        trigger: 'hover'
                    }, 
                    {
                        trigger: () => {
                            return h(
                                NIcon,
                                {
                                    class: 'icons',
                                    size: 25
                                }, { default: () => h(Info24Regular) }
                            )
                        },
                        default: () => row.wu.lastUpdatedBy
                    }
                )
            }
        }
    },
    {
        key: 'rua',
        title: 'Action'
    },
    {
        key: 'rus',
        title: 'Scope'
    },
    {
        key: 'objectId',
        title: 'Object ID'
    }
]

const approvalHistoryFields: any[] = [
    {
        key: 'date',
        title: 'Date'
    },
    {
        key: 'updatedBy',
        title: 'Updated By',
        render(row: any) {
            if (row.wu && row.wu.lastUpdatedBy) {
                return h(
                    NTooltip,
                    {
                        trigger: 'hover'
                    }, 
                    {
                        trigger: () => {
                            return h(
                                NIcon,
                                {
                                    class: 'icons',
                                    size: 25
                                }, { default: () => h(Info24Regular) }
                            )
                        },
                        default: () => row.wu.lastUpdatedBy
                    }
                )
            }
        }
    },
    {
        key: 'approvalEntry',
        title: 'Approval Entry'
    },
    {
        key: 'approvalRoleId',
        title: 'Role ID'
    },
    {
        key: 'state',
        title: 'Approval State'
    }
]

async function deleteTag (key: string) {
    updatedRelease.value.tags = updatedRelease.value.tags.filter((t: any) => (t.key !== key))
    await save()
}
async function addTag () {
    if (newTagKey.value && newTagValue.value) {
        const tpresent = updatedRelease.value.tags.filter((t: any) => (t.key === newTagKey.value))
        if (tpresent && tpresent.length) {
            notify('error', 'Failed', 'The tag with this key already exists on the release')
        } else {
            updatedRelease.value.tags.push(
                {
                    key: newTagKey.value,
                    value: newTagValue.value
                }
            )
            await save()
            newTagKey.value = ''
            newTagValue.value = ''
        }
    }
}
async function fetchReleaseKeys () {
    const response = await graphqlClient.query({
        query: gql`
            query releaseTagKeys($orgId: ID!) {
                releaseTagKeys(orgUuid: $orgId)
            }`,
        variables: { orgId: release.value.org }
    })
    releaseTagKeys.value = response.data.releaseTagKeys.map((tag: string) => {return {'label': tag, 'value': tag}})
}
const cloneReleaseToFsObj = ref({
    showModal: false,
    releaseUuid: '',
    version: '',
    fsName: ''
})

const cloneReleaseToFs = async function(uuid: string, version: string) {
    cloneReleaseToFsObj.value.releaseUuid = uuid
    cloneReleaseToFsObj.value.version = version
    cloneReleaseToFsObj.value.showModal = true
}
const createFsFromRelease = async function(){
    const gqlResp: any = await graphqlClient.mutate({
        mutation: gql`
            mutation createFeatureSetFromRelease($featureSetName: String!, $releaseUuid: ID!, $org: ID!) {
                createFeatureSetFromRelease(featureSetName: $featureSetName, releaseUuid: $releaseUuid, org: $org)
                {
                    ${graphqlQueries.BranchGql}
                }
            }
        `,
        variables: {
            featureSetName: cloneReleaseToFsObj.value.fsName,
            releaseUuid: cloneReleaseToFsObj.value.releaseUuid,
            org: release.value.org
        },
        fetchPolicy: 'no-cache'
    })
    cloneReleaseToFsObj.value.showModal = false
    cloneReleaseToFsObj.value.releaseUuid = ''
    cloneReleaseToFsObj.value.fsName = ''
    cloneReleaseToFsObj.value.version = ''
    notify('success', 'Success', 'Redirecting to new Feature Set')
    router.push({
        name: 'ProductsOfOrg',
        params: {
            orguuid: gqlResp.data.createFeatureSetFromRelease.org,
            compuuid: gqlResp.data.createFeatureSetFromRelease.componentUuid,
            branchuuid: gqlResp.data.createFeatureSetFromRelease.uuid
        }
    })
    
    //redirect to created fs
}

const releaseApprovalTableFields: ComputedRef<DataTableColumns<any>> = computed((): DataTableColumns<any> => {
    const fields: DataTableColumns<any> = [
        {
            key: 'approvalName',
            title: 'Approval Name'
        }
    ]
    Object.keys(availableApprovalIds.value).forEach(aid => {
        fields.push({
            key: aid,
            title: availableApprovalIds.value[aid],
            render: (row: any) => {
                if (row[aid]) {
                    let isDisabled = false
                    const orgPerm = myUser.permissions.permissions.find((p: any) => p.scope === 'ORGANIZATION' && p.org === release.value.org)
                    if (!orgPerm || ((orgPerm.type !== 'ADMIN') && !orgPerm.approvals) || ((orgPerm.type !== 'ADMIN') && !orgPerm.approvals.includes(aid))) isDisabled = true
                    if (!isDisabled && givenApprovals.value[row.uuid]) isDisabled = (givenApprovals.value[row.uuid][aid].length > 0)
                    const isDisapproved = approvalMatrixCheckboxes.value[row.uuid] ? approvalMatrixCheckboxes.value[row.uuid][aid] === 'DISAPPROVED' : false
                    const isApproved = approvalMatrixCheckboxes.value[row.uuid] ? approvalMatrixCheckboxes.value[row.uuid][aid] === 'APPROVED' : false
                    let title: string
                    if (isApproved && isDisabled) {
                        title = 'Approved'
                    } else if (isApproved) {
                        title = 'Your Approval Pending'
                    } else if (isDisapproved && isDisabled) {
                        title = 'Disapproved'
                    } else if (isDisapproved) {
                        title = 'Your Disapproval Pending'
                    } else {
                        title = 'Unset'
                    }
                    const checkBoxEl = h(NCheckbox,
                        {
                            checked: isApproved,
                            indeterminate: isDisapproved,
                            disabled: isDisabled,
                            title,
                            style: isDisapproved ? "--n-color-checked: red; --n-color-disabled: red;" : "",
                            size: 'large',
                            onClick: (e: any, i: any) => {
                                e.preventDefault()
                                if (!isDisabled && isApproved) {
                                    updateMatrixCheckbox('DISAPPROVED', {uuid: row.uuid, approval: aid})
                                } else if (!isDisabled && isDisapproved) {
                                    updateMatrixCheckbox('UNSET', {uuid: row.uuid, approval: aid})
                                } else if (!isDisabled) {
                                    updateMatrixCheckbox('APPROVED', {uuid: row.uuid, approval: aid})
                                }
                            }
                        }
                    )
                    return h(NSpace, {}, () => {return [checkBoxEl]})
                } else {
                    return h('div')
                }
            }
        })
    })
    return fields
})

function updateMatrixCheckbox (state: string, row: any) {
    approvalMatrixCheckboxes.value[row.uuid][row.approval] = state
}

const approvalRowKey = (row: any) => row.uuid

const releaseApprovalTableData: ComputedRef<any[]> = computed((): any[] => {
    let approvalData : any[] = []
    if (approvalEntries.value) {
        approvalData = approvalEntries.value.map((ae: any) => {
            const aeObj: any= {
                uuid: ae.uuid,
                approvalName: ae.approvalName

            }
            ae.approvalRequirements.forEach((ar: any) => {
                aeObj[ar.allowedApprovalRoleIdExpanded[0].id] = true
            })
            return aeObj
        })
    }
    return approvalData
})

const purl: ComputedRef<string | undefined> = computed((): string | undefined => {
    let purl = undefined
    if (release.value && release.value.identifiers && release.value.identifiers.length) {
        const purlObj = release.value.identifiers.find((id: any) => id.idType === 'PURL')
        if (purlObj) {
            purl = purlObj.idValue
        }
    }
    return purl
})

const artifactsRowKey = (row: any) => row.uuid

const artifactsTableFields: DataTableColumns<any> = [
    {
        key: 'type',
        title: 'Type',
        render: (row: any) => {
            let content = row.type
            if (row.type === 'BOM') content += ` - ${row.bomFormat}`
            return h('div', {}, content)
        }
    },
    {
        key: 'artBelongsTo',
        title: 'Belongs To',
        render: (row: any) => {
            const els: any[] = [
                h('span', row.belongsTo)
            ]
            if (row.belongsToId) {
                els.push(
                    h(NTooltip, {
                        trigger: 'hover'
                    }, {trigger: () => h(NIcon,
                        {
                            class: 'icons',
                            size: 25,
                        }, { default: () => h(Info20Regular) }),
                    default: () =>  h('div', row.belongsToId)
                    }
                    )
                )
            }
            return h('div', els)
        }
    },
    {
        key: 'facts',
        title: 'Facts',
        render: (row: any) => {
            const factContent: any[] = []
            factContent.push(h('li', h('span', [`UUID: ${row.uuid}`, h(ClipboardCheck, {size: 1, class: 'icons clickable iconInTooltip', onclick: () => copyToClipboard(row.uuid) })])))
            row.tags.forEach((t: any) => factContent.push(h('li', `${t.key}: ${t.value}`)))
            if (row.displayIdentifier) factContent.push(h('li', `Display ID: ${row.displayIdentifier}`))
            if (row.version) factContent.push(h('li', `Version: ${row.version}`))
            if (row.digestRecords && row.digestRecords.length) row.digestRecords.forEach((d: any) => factContent.push(h('li', `digest (${d.scope}): ${d.algo}:${d.digest}`)))
            if (row.downloadLinks && row.downloadLinks.length) factContent.push(h('li', 'DownloadLinks:'), h('ul', row.downloadLinks.map((dl: DownloadLink) => h('li', `${dl.content}: ${dl.uri}`)))) 

            if (row.notes && row.notes.length) factContent.push(h('li', `notes: ${row.notes}`))
            if (row.metrics && row.metrics.lastScanned) factContent.push(h('li', `last scanned: ${new Date(row.metrics.lastScanned).toLocaleString('en-Ca')}`))
            if (row.artifactDetails && row.artifactDetails.length) {
                row.artifactDetails.forEach((ad: any) => {
                    factContent.push(h('li', {}, [h('span', `${ad.type}: `), h('a', {class: 'clickable', onClick: () => downloadArtifact(ad)}, 'download')]))
                })
            }
            const els: any[] = [
                h(NTooltip, {
                    trigger: 'hover'
                }, {trigger: () => h(NIcon,
                    {
                        class: 'icons',
                        size: 25,
                    }, { default: () => h(Info20Regular) }),
                default: () =>  h('ul', factContent)
                }
                )
            ]
            return h('div', els)
        }
    },
    {
        key: 'vulnerabilities',
        title: 'Vulnerabilities',
        render: (row: any) => {
            let els: any[] = []
            if (row.metrics && row.metrics.lastScanned) {
                const criticalEl = h('div', {title: 'Criticial Severity Vulnerabilities', class: 'circle', style: 'background: #f86c6b; cursor: help;'}, row.metrics.critical)
                const highEl = h('div', {title: 'High Severity Vulnerabilities', class: 'circle', style: 'background: #fd8c00; cursor: help;'}, row.metrics.high)
                const medEl = h('div', {title: 'Medium Severity Vulnerabilities', class: 'circle', style: 'background: #ffc107; cursor: help;'}, row.metrics.medium)
                const lowEl = h('div', {title: 'Low Severity Vulnerabilities', class: 'circle', style: 'background: #4dbd74; cursor: help;'}, row.metrics.low)
                const unassignedEl = h('div', {title: 'Vulnerabilities with Unassigned Severity', class: 'circle', style: 'background: #777; cursor: help;'}, row.metrics.unassigned)
                els = [h(NSpace, {size: 1}, () => [criticalEl, highEl, medEl, lowEl, unassignedEl])]
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    },
    {
        key: 'violations',
        title: 'Policy Violations',
        render: (row: any) => {
            let els: any[] = []
            if (row.metrics && row.metrics.lastScanned) {
                const licenseEl = h('div', {title: 'Licensing Policy Violations', class: 'circle', style: 'background: blue; cursor: help;'}, row.metrics.policyViolationsLicenseTotal)
                const securityEl = h('div', {title: 'Security Policy Violations', class: 'circle', style: 'background: red; cursor: help;'}, row.metrics.policyViolationsSecurityTotal)
                const operationalEl = h('div', {title: 'Operational Policy Violations', class: 'circle', style: 'background: grey; cursor: help;'}, row.metrics.policyViolationsOperationalTotal)
                els = [h(NSpace, {size: 1}, () => [licenseEl, securityEl, operationalEl])]
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
           
            const uploadEl = h(NIcon,
                {
                    title: 'Upload New Bom Version',
                    class: 'icons clickable',
                    size: 25,
                    onClick: () => uploadNewBomVersion(row)
                }, { default: () => h(Edit) })
            els.push(uploadEl)
            
            
            const isDownloadable = row.tags.find((t: any) => t.key === 'downloadableArtifact' && t.value === "true")
            if (isDownloadable) {
                const downloadEl = h(NIcon,
                    {
                        title: 'Download Artifact',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => openDownloadArtifactModal(row)
                    }, { default: () => h(Download) })
                els.push(downloadEl)
            }
            if (row.metrics && row.metrics.dependencyTrackFullUri) {
                const dtrackElIcon = h(NIcon,
                    {
                        title: 'Open Dependency-Track Project in New Window',
                        class: 'icons clickable',
                        size: 25
                    }, { default: () => h(Link) })
                const dtrackEl = h('a', {target: '_blank', href: row.metrics.dependencyTrackFullUri}, dtrackElIcon)
                els.push(dtrackEl)

                const dtrackRescanEl = h(NIcon,
                    {
                        title: 'Request Refresh of Dependency-Track Metrics',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => requestRefreshDependencyTrackMetrics(row.uuid)
                    }, { default: () => h(SecurityScanOutlined) })
                els.push(dtrackRescanEl)

                const dtrackRefetchEl = h(NIcon,
                    {
                        title: 'Refetch Dependency-Track Metrics',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => refetchDependencyTrackMetrics(row.uuid)
                    }, { default: () => h(Refresh) })
                els.push(dtrackRefetchEl)
            }
            if (!els.length) els.push(h('span', 'N/A'))
            return h('div', els)
        }
    }
]

const deliverableTableFields: DataTableColumns<any> = [
    {
        key: 'displayIdentifier',
        title: 'Display ID'
    },
    {
        key: 'type',
        title: 'Type'
    },
    {
        key: 'facts',
        title: 'Facts',
        render: (row: any) => {
            const factContent: any[] = []
            factContent.push(h('li', h('span', [`UUID: ${row.uuid}`, h(ClipboardCheck, {size: 1, class: 'icons clickable iconInTooltip', onclick: () => copyToClipboard(row.uuid) })])))
            if (row.group) factContent.push(h('li', `group: ${row.group}`))
            if (row.publisher) factContent.push(h('li', `group: ${row.publisher}`))
            if (row.name) factContent.push(h('li', `name: ${row.name}`))
            if (row.identifiers && row.identifiers.length) row.identifiers.forEach((i: any) => factContent.push(h('li', [`${i.idType}: ${i.idValue}`, h(ClipboardCheck, {size: 1, class: 'icons clickable iconInTooltip', onclick: () => copyToClipboard(i.idValue) })])))
            row.tags.forEach((t: any) => factContent.push(h('li', `${t.key}: ${t.value}`)))
            if (row.softwareMetadata) {
                Object.keys(row.softwareMetadata).forEach(k => {
                    if (k !== 'digestRecords' && k !== 'downloadLinks' && row.softwareMetadata[k]) factContent.push(h('li', `${k}: ${row.softwareMetadata[k]}`))
                })
            }
            if (row.softwareMetadata.downloadLinks && row.softwareMetadata.downloadLinks.length) row.softwareMetadata.downloadLinks.forEach((d: any) => factContent.push(h('li', `download link: ${d.uri}`)))
            if (row.softwareMetadata.digestRecords && row.softwareMetadata.digestRecords.length) row.softwareMetadata.digestRecords.forEach((d: any) => factContent.push(h('li', `digest: ${d.algo} - ${d.digest}`)))
            if (row.notes && row.notes.length) factContent.push(h('li', `notes: ${row.notes}`))
            if (row.supportedCpuArchitectures && row.supportedCpuArchitectures.length) factContent.push(h('li', `Supported CPU Architectures: ${row.supportedCpuArchitectures.toString()}`))
            if (row.supportedOs && row.supportedOs.length) factContent.push(h('li', `Supported OS: ${row.supportedOs.toString()}`))
            
            const els: any[] = [
                h(NTooltip, {
                    trigger: 'hover'
                }, {trigger: () => h(NIcon,
                    {
                        class: 'icons',
                        size: 25,
                    }, { default: () => h(Info20Regular) }),
                default: () =>  h('ul', factContent)
                }
                )
            ]
            return h('div', els)
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            
            const addArtifactEl = h(NIcon,
                {
                    title: 'Add Artifact',
                    class: 'icons clickable',
                    size: 25,
                    onClick: () => { 
                        deliverableAddArtifactSceId.value = row.uuid
                        showDeliverableAddArtifactModal.value = true
                    
                    }
                }, { default: () => h(BoxArrowUp20Regular) })
            els.push(addArtifactEl)
            if (!els.length) els.push(h('span', 'N/A'))
            return h('div', els)
        }
    }
]

const inProductsTableFields: DataTableColumns<any> = [
    {
        key: 'productName',
        title: 'Product',
        render(row: any) {
            return h(RouterLink, 
                {to: {name: 'ProductsOfOrg',
                    params: {orguuid: release.value.org, compuuid: row.componentDetails.uuid}},
                style: "text-decoration: none;"},
                () => row.componentDetails.name )
        }
    },
    {
        key: 'featureSetName',
        title: 'Feature Set',
        render(row: any) {
            return h(RouterLink, 
                {to: {name: 'ProductsOfOrg',
                    params: {orguuid: release.value.org, compuuid: row.componentDetails.uuid,
                        branchuuid: row.branchDetails.uuid
                    }},
                style: "text-decoration: none;"},
                () => row.branchDetails.name )
        }
    },
    {
        key: 'version',
        title: 'Version',
        render(row: any) {
            return h(RouterLink, 
                {to: {name: 'ReleaseView', params: {uuid: row.uuid}},
                    style: "text-decoration: none;"},
                () => row.version )
        }
    },
    {
        key: 'lifecycle',
        title: 'Lifecycle'
    }
]

const parentReleaseTableFields: DataTableColumns<any> = [
    {
        type: 'expand',
        expandable: (row: any) => row.releaseDetails && row.releaseDetails.componentDetails ? row.releaseDetails.componentDetails.type === 'PRODUCT' && row.releaseDetails.parentReleases : false,
        renderExpand: (row: any) => {
            if (row.releaseDetails && row.releaseDetails.componentDetails) {
                return h(NDataTable, {
                    data: row.releaseDetails.parentReleases,
                    columns: parentReleaseTableFields
                })
            }
        }
    },
    {
        key: 'component',
        title: 'Component / Product',
        render(row: any) {
            if (row.releaseDetails && row.releaseDetails.componentDetails) {
                const routeName = row.releaseDetails.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
                return h(RouterLink, 
                    {to: {name: routeName,
                        params: {orguuid: release.value.org, compuuid: row.releaseDetails.componentDetails.uuid}},
                    style: "text-decoration: none;"},
                    () => row.releaseDetails.componentDetails.name )
            }
        }
    },
    {
        key: 'branch',
        title: 'Branch / Feature Set',
        render(row: any) {
            if (row.releaseDetails && row.releaseDetails.componentDetails) {
                const routeName = row.releaseDetails.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
                return h(RouterLink, 
                    {to: {name: routeName,
                        params: {orguuid: release.value.org, compuuid: row.releaseDetails.componentDetails.uuid,
                            branchuuid: row.releaseDetails.branchDetails.uuid
                        }},
                    style: "text-decoration: none;"},
                    () => row.releaseDetails.branchDetails.name )
            }
        }
    },
    {
        key: 'version',
        title: 'Version',
        render(row: any) {
            if (row.releaseDetails && row.releaseDetails.componentDetails) {
                return h(RouterLink, 
                    {to: {name: 'ReleaseView', params: {uuid: row.release}},
                        style: "text-decoration: none;"},
                    () => row.releaseDetails.version )
            }
        }
    },
    {
        key: 'lifecycle',
        title: 'Lifecycle',
        render(row: any) {
            if (row.releaseDetails && row.releaseDetails.componentDetails) {
                return h('span', row.releaseDetails.lifecycle )
            }
        }
    },
    {
        key: 'vulnerabilities',
        title: 'Vulnerabilities',
        render: (row: any) => {
            let els: any[] = []
            if (row.releaseDetails.metrics && row.releaseDetails.metrics.lastScanned) {
                const criticalEl = h('div', {title: 'Criticial Severity Vulnerabilities', class: 'circle', style: 'background: #f86c6b; cursor: help;'}, row.releaseDetails.metrics.critical)
                const highEl = h('div', {title: 'High Severity Vulnerabilities', class: 'circle', style: 'background: #fd8c00; cursor: help;'}, row.releaseDetails.metrics.high)
                const medEl = h('div', {title: 'Medium Severity Vulnerabilities', class: 'circle', style: 'background: #ffc107; cursor: help;'}, row.releaseDetails.metrics.medium)
                const lowEl = h('div', {title: 'Low Severity Vulnerabilities', class: 'circle', style: 'background: #4dbd74; cursor: help;'}, row.releaseDetails.metrics.low)
                const unassignedEl = h('div', {title: 'Vulnerabilities with Unassigned Severity', class: 'circle', style: 'background: #777; cursor: help;'}, row.releaseDetails.metrics.unassigned)
                els = [h(NSpace, {size: 1}, () => [criticalEl, highEl, medEl, lowEl, unassignedEl])]
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    },
    {
        key: 'violations',
        title: 'Violations',
        render: (row: any) => {
            let els: any[] = []
            if (row.releaseDetails.metrics && row.releaseDetails.metrics.lastScanned) {
                const licenseEl = h('div', {title: 'Licensing Policy Violations', class: 'circle', style: 'background: blue; cursor: help;'}, row.releaseDetails.metrics.policyViolationsLicenseTotal)
                const securityEl = h('div', {title: 'Security Policy Violations', class: 'circle', style: 'background: red; cursor: help;'}, row.releaseDetails.metrics.policyViolationsSecurityTotal)
                const operationalEl = h('div', {title: 'Operational Policy Violations', class: 'circle', style: 'background: grey; cursor: help;'}, row.releaseDetails.metrics.policyViolationsOperationalTotal)
                els = [h(NSpace, {size: 1}, () => [licenseEl, securityEl, operationalEl])]
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (row.releaseDetails && row.releaseDetails.componentDetails && isWritable.value && isUpdatable.value) {
                const deleteEl = h(NIcon, {
                    title: 'Delete Component',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        deleteComponentRelease(row.release)
                    }
                }, 
                { 
                    default: () => h(Trash) 
                }
                )
                els.push(deleteEl)
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    }
]

const commitTableFields: DataTableColumns<any> = [
    {
        key: 'date',
        title: 'Date',
        render: (row: any) => {
            if (row.dateActual) return (new Date(row.dateActual)).toLocaleString('en-CA')
        }
    },
    {
        key: 'commitMessage',
        title: 'Message'
    },
    {
        key: 'author',
        title: 'Author',
        render: (row: any) => {
            let authorContent = ''
            if (row.commitAuthor) {
                authorContent += row.commitAuthor
                if (row.commitEmail) authorContent += ', '
            }
            if (row.commitEmail) authorContent += row.commitEmail
            return authorContent
        }
    },
    {
        key: 'facts',
        title: 'Facts',
        render: (row: any) => {
            const factContent: any[] = []
            factContent.push(h('li', h('span', [`UUID: ${row.uuid}`, h(ClipboardCheck, {size: 1, class: 'icons clickable iconInTooltip', onclick: () => copyToClipboard(row.uuid) })])))
            factContent.push(h('li', `Commit Hash: ${row.commit}`))
            factContent.push(h('li', `Branch: ${updatedRelease.value.sourceCodeEntryDetails.vcsBranch}`))
            if (updatedRelease.value.sourceCodeEntryDetails.vcsRepository?.uri) factContent.push(h('li', `VCS Repo URI: ${updatedRelease.value.sourceCodeEntryDetails.vcsRepository?.uri}`))
            const els: any[] = [
                h(NTooltip, {
                    trigger: 'hover'
                }, {trigger: () => h(NIcon,
                    {
                        class: 'icons',
                        size: 25,
                    }, { default: () => h(Info20Regular) }),
                default: () =>  h('ul', factContent)
                }
                )
            ]
            return h('div', els)
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (updatedRelease.value.sourceCodeEntryDetails.vcsRepository?.uri) {
                const link = linkifyCommit(updatedRelease.value.sourceCodeEntryDetails.vcsRepository?.uri, row.commit)
                if (link) {
                    const openCommitIcon = h(NIcon,
                        {
                            title: 'Open Commit in New Window',
                            class: 'icons clickable',
                            size: 25
                        }, { default: () => h(Link) })
                    const ocEl = h('a', {target: '_blank', href: link}, openCommitIcon)
                    els.push(ocEl)
                }
            }
            const addArtifactEl = h(NIcon,
                {
                    title: 'Add Artifact',
                    class: 'icons clickable',
                    size: 25,
                    onClick: () => { 
                        sceAddArtifactSceId.value = row.uuid
                        showSCEAddArtifactModal.value = true
                        
                    }
                }, { default: () => h(BoxArrowUp20Regular) })
            els.push(addArtifactEl)
            if (!els.length) els.push(h('span', 'N/A'))
            return h('div', els)
        }
    }
]

async function refetchDependencyTrackMetrics (artifact: string) {
    const resp = await graphqlClient.mutate({
        mutation: gql`
            mutation refetchDependencyTrackMetrics($artifact: ID!) {
                refetchDependencyTrackMetrics(artifact: $artifact)
            }
            `,
        variables: { artifact },
        fetchPolicy: 'no-cache'
    })
    if (resp.data && resp.data.refetchDependencyTrackMetrics) {
        notify('success', 'Metrics Refetched', 'Metrics refetched for the artifact from Dependency-Track.')
        fetchRelease()
    } else {
        notify('error', 'Failed to Refetch Metrics', 'Could not refetch Dependency-Track metrics. Please try again later or contact support.')
    }
}

async function requestRefreshDependencyTrackMetrics (artifact: string) {
    const resp = await graphqlClient.mutate({
        mutation: gql`
            mutation requestRefreshDependencyTrackMetrics($artifact: ID!) {
                requestRefreshDependencyTrackMetrics(artifact: $artifact)
            }
            `,
        variables: { artifact },
        fetchPolicy: 'no-cache'
    })
    if (resp.data && resp.data.requestRefreshDependencyTrackMetrics) {
        notify('success', 'Refresh Requested', 'Dependency-Track metrics refresh requested.')
    } else {
        notify('error', 'Failed to Request Refresh', 'Could not request refresh of Dependency-Track metrics. Please try again later or contact support.')
    }
}

async function handleTabSwitch(tabName: string) {
    if (tabName === "compare") {
        await fetchReleases()
    }
}

</script>
    
<style scoped lang="scss">
.row {
    padding-left: 1%;
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