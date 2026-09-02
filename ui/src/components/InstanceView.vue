<template>
    <div class="container">
        <div class="instanceView">
            <div class="instanceControls">
                <div class="mainControls">
                    <h4 v-if="updatedInstance">
                        <span v-if="updatedInstance.instanceType !== InstanceType.CLUSTER"> Instance: <a v-bind:href="'https://'+updatedInstance.uri" target="_blank" rel="noopener noreferrer">{{updatedInstance.uri}}</a></span>
                        <span v-else> Cluster: <a v-bind:href="'https://'+updatedInstance.uri" target="_blank" rel="noopener noreferrer">{{updatedInstance.name}}</a></span>
                        <n-icon v-if="isWritable && updatedInstance.instanceType !== InstanceType.CLUSTER_INSTANCE" @click="genApiKey" class="clickable icons" :title="'Generate ' + instanceWord + ' API Key'" size="20"><LockOpen /></n-icon>
                        <n-icon class="clickable icons" title="Export as CycloneDX JSON" size="20" @click="exportCycloneDx"><Download /></n-icon>
                        <n-icon class="clickable icons" :title="instanceWord + ' Settings'" @click="showInstSettingsModal = true" size="20"><Tool /></n-icon>
                        <n-icon class="clickable icons" title="Refresh Instance Data" @click="onCreate" size="20"><Refresh /></n-icon>
                        <n-icon v-if="canSubscribeToDeploymentNotifications" class="clickable icons" title="Subscribe to deployment notifications" @click="subscribeToDeploymentNotifications" size="20" data-testid="instance-subscribe"><Bell /></n-icon>
                    </h4>
                    <span v-if="updatedInstance.instanceType === InstanceType.CLUSTER_INSTANCE"> Part of Cluster: <router-link :to="{ name: 'Instance', params: {orguuid: orguuid, instuuid: cluster.uuid }}">{{ cluster.name }}</router-link></span>
                    <span v-if="updatedInstance.instanceType === InstanceType.CLUSTER_INSTANCE"> Namespace: {{updatedInstance.namespace}}</span>
                </div>
                <!-- Archival moved to the Settings modal's Danger Zone (matches
                     ComponentView's pattern). -->
            </div>
            <!-- Segmented sub-tab pill control — mirrors OrgIntegrations look-and-feel. -->
            <div class="subtab-bar">
                <button class="subtab-pill" :class="{ active: activeTab === 'instance' }" @click="switchTab('instance')" data-testid="instance-tab-instance">
                    <n-icon size="16" class="subtab-icon"><Server /></n-icon>
                    <span>{{ instanceWord }}</span>
                </button>
                <button v-if="updatedInstance.instanceType !== InstanceType.CLUSTER" class="subtab-pill" :class="{ active: activeTab === 'target-releases' }" @click="switchTab('target-releases')" data-testid="instance-tab-target-releases">
                    <n-icon size="16" class="subtab-icon"><Target /></n-icon>
                    <span>Individual Target Releases</span>
                </button>
                <button v-if="updatedInstance.instanceType !== InstanceType.CLUSTER" class="subtab-pill" :class="{ active: activeTab === 'deployed-releases' }" @click="switchTab('deployed-releases')" data-testid="instance-tab-deployed-releases">
                    <n-icon size="16" class="subtab-icon"><Rocket /></n-icon>
                    <span>Deployed Component Releases</span>
                </button>
                <button class="subtab-pill" :class="{ active: activeTab === 'cd-failures' }" @click="switchTab('cd-failures')" data-testid="instance-tab-cd-failures">
                    <n-icon size="16" class="subtab-icon"><AlertTriangle /></n-icon>
                    <span>Deployment Failures</span>
                    <span v-if="deploymentFailureCount > 0" class="subtab-count subtab-count-error" data-testid="instance-tab-cd-failures-count">{{ deploymentFailureCount }}</span>
                </button>
                <button class="subtab-pill" :class="{ active: activeTab === 'plan-history' }" @click="switchTab('plan-history')" data-testid="instance-tab-plan-history">
                    <n-icon size="16" class="subtab-icon"><History /></n-icon>
                    <span>Plan History</span>
                </button>
                <button class="subtab-pill" :class="{ active: activeTab === 'actual-history' }" @click="switchTab('actual-history')" data-testid="instance-tab-actual-history">
                    <n-icon size="16" class="subtab-icon"><History /></n-icon>
                    <span>Actual History</span>
                </button>
            </div>

            <!-- ================= Instance tab (current view) ================== -->
            <div v-if="activeTab === 'instance'">
                <div v-if="updatedInstance && updatedInstance.instanceType === InstanceType.CLUSTER" class="listHeaderText">
                    Cluster Instances:
                    <n-icon v-if="isWritable" class="clickable" @click="showCreateInstanceModal = true" title="Add New Instance on Cluster" size="24"><CirclePlus /></n-icon>
                </div>

                <n-data-table v-if="updatedInstance && updatedInstance.instanceType === InstanceType.CLUSTER"
                    :data="props.childInstances"
                    :columns="childInstanceFields"
                    :row-key="childInstrowKey"
                    @update:checked-row-keys="handleChildInstSelect"
                    v-model:checked-row-keys="selectedChildInstRowKey"
                />
                <div class="instanceBody">
                    <div v-if="updatedInstance && updatedInstance.instanceType != InstanceType.CLUSTER" class="listHeaderText">Product Releases:
                    <n-icon v-if="isWritable" class="clickable" @click="isUpdateLinkedFeatureSet = false; showLinkFeatureSetModal = true" :title="'Link ' + featureSetLabel" size="24"><CirclePlus /></n-icon>
                    <n-icon @click="genProductRelease" class="clickable" title="Generate Integration Product Releases" size="20"><TrendingUp /></n-icon>
                </div>
                <n-data-table v-if="updatedInstance && updatedInstance.instanceType != InstanceType.CLUSTER"
                    :data="updatedInstance.productPlans"
                    :columns="matchedProductFields"
                />
                
                
                <div v-if="updatedInstance" class="instancePropertiesBlock">
                    <div class="listHeaderText">{{ instanceWord }} Properties:
                        <n-icon v-if="isWritable" class="clickable" @click="showAddInstancePropertyModal = true" title="Add New Property Manually" size="24"><CirclePlus /></n-icon>
                    </div>
                    <n-data-table :data="updatedInstance.properties" :columns="instPropFeilds"></n-data-table>
                </div>
                </div>
            </div>

            <!-- ================= Individual Target Releases tab ================== -->
            <div v-if="activeTab === 'target-releases' && updatedInstance && updatedInstance.instanceType !== InstanceType.CLUSTER" class="instanceReleaseBlock individualTargetReleases">
                <div class="listHeaderText">Individual Target Releases:
                    <n-dropdown v-if="showNamespaceFilter" title="Select Namespace" trigger="hover" :options="namespacesForDropdown" @select="$key => {selectedNamespace = $key}">
                        <span>
                            <span>{{ selectedNamespace ? selectedNamespace : 'Filter By Namespace' }}</span>
                            <Icon><CaretDownFilled/></Icon>
                        </span>
                    </n-dropdown>
                    <n-icon v-if="isWritable" class="clickable" @click="showAddComponentTargetReleaseModal = true" title="Add Component Target Release" size="24"><CirclePlus /></n-icon>
                </div>
                <div v-if="targetDetailsLoading" class="historyLoading">Loading…</div>
                <div v-else-if="targetDetailsFailed" class="historyEmpty">Could not load target release details. <a href="#" @click.prevent="retryReleaseDetails('targetReleases')">Retry</a></div>
                <n-data-table v-else :data="targetIndividualReleases" :columns="targetReleaseFeilds" />
            </div>

            <!-- ================= Deployed Component Releases tab ================== -->
            <div v-if="activeTab === 'deployed-releases' && updatedInstance && updatedInstance.instanceType !== InstanceType.CLUSTER">
                <div class="instanceReleaseBlock actualReleasesOnInstance">
                    <div class="listHeaderText">Deployed Component Releases:
                        <n-dropdown v-if="showNamespaceFilter" title="Select Namespace" trigger="hover" :options="namespacesForDropdown" @select="$key => {selectedNamespace = $key}">
                            <span>
                                <span>{{ selectedNamespace ? selectedNamespace : 'Filter By Namespace' }}</span>
                                <Icon><CaretDownFilled/></Icon>
                            </span>
                        </n-dropdown>
                        <n-icon class="ml-1 clickable" @click="showAgentDataModal = true" title="Show Agent Data" size="20"><InfoCircle /></n-icon>
                        <n-icon v-if="isWritable" class="ml-1 clickable" @click="clearAgentData" title="Clear Agent Data" size="20"><CircleX /></n-icon>
                    </div>
                    <div v-if="deployedDetailsLoading" class="historyLoading">Loading…</div>
                    <div v-else-if="deployedDetailsFailed" class="historyEmpty">Could not load deployed release details. <a href="#" @click.prevent="retryReleaseDetails('releases')">Retry</a></div>
                    <n-data-table v-else :data="deployedReleases" :columns="deployedReleaseFeilds"></n-data-table>
                </div>
                <div v-if="filteredUnmatchedReleases.length" class="instanceReleaseBlock unmatchedImagesOnInstance">
                    <div class="listHeaderText">
                        Unmatched Deployed Images:
                        <n-tooltip trigger="hover" placement="top" :style="{maxWidth: '420px'}">
                            <template #trigger><n-icon class="ml-1 clickable" size="18"><InfoCircle /></n-icon></template>
                            Images reported by the cluster watcher whose digest does not resolve to any known deliverable in this org. Typically third-party sidecars (redis, postgres, etc.) or pods missing build-time metadata.
                        </n-tooltip>
                    </div>
                    <n-data-table :data="filteredUnmatchedReleases" :columns="unmatchedImageFields" />
                </div>
            </div>

            <!-- ================= Deployment Failures (ReARM CD) tab ================== -->
            <div v-if="activeTab === 'cd-failures'" class="instanceReleaseBlock deploymentFailuresOnInstance">
                <div class="listHeaderText">
                    Deployment Failures:
                    <n-tooltip trigger="hover" placement="top" :style="{maxWidth: '460px'}">
                        <template #trigger><n-icon class="ml-1 clickable" size="18"><InfoCircle /></n-icon></template>
                        Failures reported by ReARM CD while reconciling this instance. A failure that repeats is shown once with its occurrence count — First Seen is when the current incident started, Last Seen is the most recent report. Rows disappear once the agent stops reporting the failure.
                    </n-tooltip>
                    <n-tag v-if="deploymentHealthChip" :type="deploymentHealthChip.type" size="small" round class="ml-1"
                           :title="deploymentHealthChip.tip">
                        {{ deploymentHealthChip.label }}
                    </n-tag>
                    <n-dropdown v-if="showNamespaceFilter" title="Select Namespace" trigger="hover" :options="namespacesForDropdown" @select="$key => {selectedNamespace = $key}">
                        <span class="ml-1">
                            <span>{{ selectedNamespace ? selectedNamespace : 'Filter By Namespace' }}</span>
                            <Icon><CaretDownFilled/></Icon>
                        </span>
                    </n-dropdown>
                </div>
                <n-data-table v-if="filteredDeploymentFailures.length" :data="filteredDeploymentFailures" :columns="deploymentFailureFields" />
                <div v-else class="historyEmpty">No open deployment failures reported by ReARM CD{{ selectedNamespace && selectedNamespace !== 'ALL' ? ' in namespace ' + selectedNamespace : '' }}.</div>
            </div>

            <!-- ================= Plan History tab ================== -->
            <div v-if="activeTab === 'plan-history'" class="historyTab">
                <div class="historyFilters historyFilterRow">
                    <div class="historyFilterItem">
                        <span>Filter By Change Type:&nbsp;</span>
                        <n-dropdown trigger="hover" :options="planChangeTypeOptions" @select="(k) => { planChangeTypeFilter = k; fetchPlanHistory() }">
                            <span class="clickable">
                                <span>{{ planChangeTypeFilter }}</span>
                                <Icon><CaretDownFilled/></Icon>
                            </span>
                        </n-dropdown>
                    </div>
                    <div class="historyFilterItem">
                        <label>From:&nbsp;</label>
                        <n-date-picker v-model:value="planHistorySearch.dateFrom" type="datetime" clearable update-value-on-close />
                    </div>
                    <div class="historyFilterItem">
                        <label>To:&nbsp;</label>
                        <n-date-picker v-model:value="planHistorySearch.dateTo" type="datetime" clearable update-value-on-close />
                    </div>
                </div>
                <div v-if="planHistoryLoading" class="historyLoading">Loading…</div>
                <instance-history v-else-if="updatedInstance && updatedInstance.org && planHistory.length"
                    :key="'plan-' + planHistory.length"
                    :instanceUuid="updatedInstance.uuid"
                    :history="planHistory"
                    :stateType="'PLAN'"
                    :orgProp="updatedInstance.org"
                />
                <div v-else class="historyEmpty">No plan history events found{{ planHistorySearch.dateFrom ? ' in this range' : '' }}.</div>
            </div>

            <!-- ================= Actual History tab ================== -->
            <div v-if="activeTab === 'actual-history'" class="historyTab">
                <div class="historyFilters historyFilterRow">
                    <div class="historyFilterItem">
                        <span>Filter By Change Type:&nbsp;</span>
                        <n-dropdown trigger="hover" :options="actualChangeTypeOptions" @select="(k) => { actualChangeTypeFilter = k; fetchActualHistory() }">
                            <span class="clickable">
                                <span>{{ actualChangeTypeFilter }}</span>
                                <Icon><CaretDownFilled/></Icon>
                            </span>
                        </n-dropdown>
                    </div>
                    <div class="historyFilterItem">
                        <label>From:&nbsp;</label>
                        <n-date-picker v-model:value="actualHistorySearch.dateFrom" type="datetime" clearable update-value-on-close />
                    </div>
                    <div class="historyFilterItem">
                        <label>To:&nbsp;</label>
                        <n-date-picker v-model:value="actualHistorySearch.dateTo" type="datetime" clearable update-value-on-close />
                    </div>
                </div>
                <div v-if="actualHistoryLoading" class="historyLoading">Loading…</div>
                <instance-history v-else-if="updatedInstance && updatedInstance.org && actualHistory.length"
                    :key="'actual-' + actualHistory.length"
                    :instanceUuid="updatedInstance.uuid"
                    :history="actualHistory"
                    :stateType="'ACTUAL'"
                    :orgProp="updatedInstance.org"
                />
                <div v-else class="historyEmpty">No actual history events found{{ actualHistorySearch.dateFrom ? ' in this range' : '' }}.</div>
            </div>

        </div>
        <n-modal
            v-model:show="showAddComponentTargetReleaseModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Add Individual Component Target Release"
        >
            <create-release
                                v-if="updatedInstance && updatedInstance.org"
                                :orgProp="updatedInstance.org"
                                inputType="COMPONENT"
                                :attemptPickRelease="true"
                                :isChooseNamespace="true"
                                :knownNamespaces="knownNamespaces"
                                :instanceType="updatedInstance.instanceType"
                                :reservedNs="updatedInstance.namespace"
                                createButtonText="Select Release"
                                @createdRelease="componentTargetReleaseAdded" />
        </n-modal>
        <n-modal
            v-model:show="showAgentDataModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Instance Agent Data"
        >
            <prism-editor
                class="editor agentDataEditor"
                v-model="prettyAgentData"
                :highlight="jsonHighlighter"
                :readonly="true"
            ></prism-editor>
        </n-modal>
        <n-modal
            v-model:show="showInstSettingsModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            :title="instanceWord + ' Settings'"
        >
            <div class="settingsBox" v-if="updatedInstance.instanceType !== InstanceType.CLUSTER">
                <h6>URI:</h6>
                <span v-if="isWritable" class="settingsValue">
                    <n-input v-model:value="updatedInstance.uri" placeholder="URI without http or https" />
                    <n-icon class="clickable versionIcon reject" v-if="updatedInstance.uri !== instanceData.uri" @click="updatedInstance.uri = instanceData.uri" title="Discard URI Changes" size="20"><X /></n-icon>
                    <n-icon class="clickable versionIcon accept" v-if="updatedInstance.uri !== instanceData.uri" @click="save" title="Save URI" size="20"><Check /></n-icon>
                </span>
                <span v-else>{{ updatedInstance.uri }} </span>
            </div>
            
            <div class="settingsBox" v-if="updatedInstance.instanceType !== InstanceType.CLUSTER">
                <h6>Environment:</h6>
                <span v-if="!isWritable">{{ updatedInstance.environment }}</span>
                <span class="settingsValue" v-if="isWritable">
                    <n-dropdown v-if="isWritable" trigger="hover" :options="envs" @select="handleEnvironmentChange">
                        <span>
                            <span>{{ updatedInstance.environment }}</span>
                            <Icon><CaretDownFilled/></Icon>
                        </span>
                    </n-dropdown>
                    <n-icon class="clickable versionIcon reject" v-if="updatedInstance.environment !== instanceData.environment" @click="updatedInstance.environment = instanceData.environment" title="Discard Environment Changes" size="20"><X /></n-icon>
                    <n-icon class="clickable versionIcon accept" v-if="updatedInstance.environment !== instanceData.environment" @click="save" title="Save New Environment" size="20"><Check /></n-icon>
                </span>
            </div>
            <div class="settingsBox" v-if="updatedInstance.instanceType === InstanceType.CLUSTER">
                <h6>Name:</h6>                    
                <span v-if="isWritable" class="settingsValue">
                    <n-input v-model:value="updatedInstance.name" placeholder="Name for the cluster" />
                    <n-icon class="clickable versionIcon reject" v-if="updatedInstance.name !== instanceData.name" @click="updatedInstance.name = instanceData.name" title="Discard Name Changes" size="20"><X /></n-icon>
                    <n-icon class="clickable versionIcon accept" v-if="updatedInstance.name !== instanceData.name" @click="save" title="Save Name" size="20"><Check /></n-icon>
                </span>
                <span v-else>{{ updatedInstance.name }} </span>
            </div>
            <div>
                <h6>Notes:</h6  >
                <n-input type="textarea" v-if="isWritable" v-model:value="updatedInstance.notes" rows="4" />
                <n-input type="textarea" v-else :value="updatedInstance.notes" rows="4" readonly/>
                <n-icon class="clickable versionIcon reject" v-if="updatedInstance.notes !== instanceData.notes" @click="updatedInstance.notes = instanceData.notes" title="Discard Notes Changes" size="20"><X /></n-icon>
                <n-icon class="clickable versionIcon accept" v-if="updatedInstance.notes !== instanceData.notes" @click="save" title="Save Notes" size="20"><Check /></n-icon>
            </div>

            <div class="dangerZone">
                <h5 class="dangerZoneHeader">Danger Zone</h5>
                <p class="dangerZoneCopy">
                    Archiving the {{ instanceWord.toLowerCase() }} hides it from the active list.
                    Historical release / deployment data tied to it stays resolvable, but no new
                    activity can be recorded against an archived {{ instanceWord.toLowerCase() }}.
                    <span v-if="updatedInstance.instanceType === InstanceType.CLUSTER">
                        A cluster cannot be archived while it still has active instances —
                        archive the child instances first.
                    </span>
                </p>
                <n-button v-if="isWritable" type="error" @click="archiveInstance">
                    <template #icon>
                        <n-icon><Trash /></n-icon>
                    </template>
                    Archive {{ instanceWord }}
                </n-button>
            </div>
        </n-modal>
        <n-modal
            v-model:show="showAddInstancePropertyModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Add New Property"
        >
            <create-property
                                class="addProperty"
                                v-if="updatedInstance && updatedInstance.org"
                                :orgProp="updatedInstance.org"
                                :knownProducts="knownProducts"
                                :knownNamespaces="knownNamespaces"
                                :instProperties="updatedInstance.properties"
                                :instanceType="updatedInstance.instanceType"
                                :reservedNs="updatedInstance.namespace"
                                @createdProperty="createdProperty"/>
        </n-modal>
        <n-modal
            v-model:show="showRevisionComparisonModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Compare With Target"
        >
            <side-by-side
                :instanceLeft="instanceUuid"
                :revisionLeft="-1"
                :namespaceLeft="namespaceForComparison"
                comparisonTypeRightIn="release"
                :instanceRight="releaseForComparison"/>
        </n-modal>
        <n-modal
            v-model:show="showSelectTargetReleaseModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Set Target Release"
        >
            <create-release
                            v-if="updatedInstance && updatedInstance.org"
                            :orgProp="updatedInstance.org"
                            :inputComponent="isSelectingTargetFeatureSet ? focusedProduct.featureSetDetails.componentDetails.uuid : ''"
                            :inputBranch="isSelectingTargetFeatureSet ? '' : focusedProduct.featureSet"
                            inputType="PRODUCT"
                            :attemptPickRelease="true"
                            :disallowPlaceholder="true"
                            :disallowCreateRelease="true"
                            :isHideReset="true"
                            createButtonText="Select Release"
                            @createdRelease="targetReleaseSet" />
        </n-modal>        
        <n-modal
            v-model:show="showLinkFeatureSetModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            :title="isUpdateLinkedFeatureSet ? 'Update Integration ' + featureSetLabel : 'Add Integration ' + featureSetLabel"
        >
            <add-component-branch
                                    v-if="updatedInstance"
                                    :orgProp="updatedInstance.org"
                                    :instanceUuid="updatedInstance.uuid"
                                    :productUuid="isUpdateLinkedFeatureSet ? focusedProduct.featureSetDetails.componentDetails.uuid : ''"
                                    :namespace="updatedInstance.namespace"
                                    @addedComponentBranch="addedIntegrationFeatureSet" />
        </n-modal>
        <n-modal
            v-model:show="showReleaseViewModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%; min-height: 95vh;"
        >
            <release-view
                            v-if="updatedInstance"
                            :orgprop = "updatedInstance.org"
                            :uuidprop="selectedReleaseIdForModal"
                            @approvalsChanged="fetchInstance" @closeRelease="showReleaseViewModal=false"/>
        </n-modal>
        <n-modal
            v-model:show="showEditPropertyModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            title="Edit Property Value"
        >
            <n-form-item label="Value" v-if="focusedProperty?.property?.dataType === 'JSON' || focusedProperty?.property?.dataType === 'YAML'">
                <prism-editor class="editor" v-model="updatedPropValue" :highlight="highlighter" line-numbers></prism-editor>
            </n-form-item>
            <n-form-item label="Value" v-else>
                <n-input
                            type="textarea"
                            v-model:value="updatedPropValue"
                            placeholder="Enter property value" />
            </n-form-item>

            <n-button type="success" @click="focusedProperty.value = updatedPropValue; save(); showEditPropertyModal = false;">Submit</n-button>
            <n-button type="warning" @click="updatedPropValue = focusedProperty.value">Reset</n-button>
        </n-modal>
        <n-modal
                v-model:show="showCreateInstanceModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%;"
                :title="'Add New Instance ' + (updatedInstance.instanceType === InstanceType.CLUSTER ? ' on Cluster' : '')"
            >
            <create-instance
                        class="addInstance"
                        v-if="orguuid"
                        :orgProp="orguuid"
                        :instanceType="InstanceType.CLUSTER_INSTANCE"
                        :clusterId="updatedInstance.uuid"
                        @instanceCreated="instCreated" />
        </n-modal>
        <n-drawer v-if="!isChildInstance" v-model:show="showChildInstance" :trap-focus="false" :on-after-leave="handleChildInstanceClose" width="900">
            <n-drawer-content closable :native-scrollbar="true">
                <instance-view :instanceType="InstanceType.CLUSTER_INSTANCE" :clusterId="instanceUuid"/>
            </n-drawer-content>
        </n-drawer>
        <n-modal
            v-model:show="showCdxExportModal"
            preset="dialog"
            :show-icon="false"
            title="Export as CycloneDX JSON"
            style="width: 400px;"
        >
            <n-form-item label="State Type">
                <n-select
                    v-model:value="cdxExportStateType"
                    :options="cdxStateTypeOptions"
                    placeholder="Select state type" />
            </n-form-item>
            <template #action>
                <n-button @click="showCdxExportModal = false">Cancel</n-button>
                <n-button type="success" :disabled="!cdxExportStateType" :loading="exportingCdx" @click="doExportCycloneDx">Export</n-button>
            </template>
        </n-modal>
    </div>
</template>

<script lang="ts">
export default {
    name: 'InstanceView'
}
</script>
<script lang="ts" setup>
import { ComputedRef, ref, computed, Ref, h, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { NDropdown, NSelect, NEllipsis, NFormItem, NInput, NInputGroup, NButton, NDatePicker, NModal, NTooltip, NTag, useNotification, NotificationType, NIcon, NSwitch, NDataTable, NDrawer, NDrawerContent } from 'naive-ui'
import InstanceHistory from '@/components/InstanceHistory.vue'
import ReleaseView from '@/components/ReleaseView.vue'
import AddComponentBranch from '@/components/AddComponentBranch.vue'
import CreateProperty from '@/components/CreateProperty.vue'
import CreateRelease from '@/components/CreateRelease.vue'
import SideBySide from '@/components/SideBySide.vue'
import commonFunctions from '@/utils/commonFunctions'
import { SwalData } from '@/utils/commonFunctions'
import { isProEdition } from '@/utils/editionCapabilities'
import { CaretDownFilled } from '@vicons/antd'
import { Icon } from '@vicons/utils'
import Swal from 'sweetalert2'
import { PrismEditor } from 'vue-prism-editor';
import 'vue-prism-editor/dist/prismeditor.min.css';
import * as prism from 'prismjs';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-json';
import 'prismjs/themes/prism-tomorrow.css';
import { AlertOff24Regular, AlertOn24Regular, Edit24Regular, Target20Regular, DismissCircle20Regular} from '@vicons/fluent'
import { Copy, LayoutColumns, Filter, Trash, Link as LinkIcon, Share as ShareIcon, ExternalLink, LockOpen, Download, Tool, Refresh, CirclePlus, TrendingUp, InfoCircle, CircleX, X, Check, Server, History, Bell, Target, Rocket, AlertTriangle } from '@vicons/tabler'
import { Commit } from '@vicons/carbon'
import constants from '@/utils/constants'
import CreateInstance from '@/components/CreateInstance.vue'
import graphqlClient from '../utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import gql from 'graphql-tag'


const props = defineProps<{
    childInstances: {
        type: Array,
        default: () => []
    },
    instanceType: String,
    clusterId?: String
}>()

const route = useRoute()
const router = useRouter()
const store = useStore()
const notification = useNotification()
const InstanceType = constants.InstanceType
const isChildInstance: ComputedRef<string> = computed((): any => props.instanceType === InstanceType.CLUSTER_INSTANCE)
const instanceUuid = isChildInstance.value && (route.params.subinstuuid !== undefined && route.params.subinstuuid !== '') ? route.params.subinstuuid.toString() : route.params.instuuid.toString()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const featureSetLabel = computed(() => myorg.value?.terminology?.featureSetLabel || 'Feature Set')
const myUser = store.getters.myuser
const orguuid : Ref<string> = ref('')
if (route.params.orguuid) {
    orguuid.value = route.params.orguuid.toString()
} else {
    orguuid.value = myorg.value
}

const updatedInstance: Ref<any> = ref({})

// The Subscribe deep-link lands on OrgSettings -> Integrations -> Subscriptions,
// which is org-admin-only (integrations is an adminOnlyTab) and Pro-only
// (instance events don't exist on CE). Gate the affordance on both so a
// non-admin / CE user is never sent to a tab they can't open -- a control that
// provably does nothing is worse than no control.
const canSubscribeToDeploymentNotifications = computed<boolean>(() =>
    isProEdition(myUser?.installationType) && commonFunctions.isAdmin(orguuid.value, myUser))

// Deep-link to the org's notification subscriptions, pre-filled to notify on
// this instance's deployments. SubscriptionsOfOrg reads newInstanceSub on mount
// (Pro-only there) and opens a pre-filled create modal.
function subscribeToDeploymentNotifications (): void {
    router.push({
        name: 'OrgSettings',
        params: { orguuid: orguuid.value },
        query: {
            tab: 'integrations',
            integrationsTab: 'subscriptions',
            newInstanceSub: updatedInstance.value?.uri,
        },
    })
}
const envs: Ref<any[]> = ref([])

// --- Tab state (URL-synced) ---
const INST_TABS = ['instance', 'target-releases', 'deployed-releases', 'cd-failures', 'plan-history', 'actual-history'] as const
type InstTab = typeof INST_TABS[number]
function isInstTab (t: unknown): t is InstTab {
    return INST_TABS.includes(t as InstTab)
}
const activeTab = ref<InstTab>(isInstTab(route.query.instTab) ? route.query.instTab : 'instance')

// The instance is fetched in two halves. The core document carries the
// shallow deployed / target release rows (uuid, namespace, state -- what
// updateInstance sends back and what the namespace filter and tab counts
// need) but not DeployedRelease.releaseDetails, which the backend resolves
// per row. Details are fetched only when the tab that renders them opens
// (or when the product-match tooltip, which reads deployed versions, is
// shown), and cached by release uuid for the life of the view so a core
// refetch after a save does not pay for them again. Because the cache is
// keyed by release uuid and merged onto whatever rows are current, a
// response that arrives after the list changed can never resurrect a stale
// list -- it only fills in details for rows that are still there.
type ReleaseListPart = 'releases' | 'targetReleases'
const RELEASE_LIST_PARTS: ReleaseListPart[] = ['releases', 'targetReleases']
const releaseDetailCache = ref<Record<string, any>>({})
const releaseDetailState: Record<ReleaseListPart, { loading: Ref<boolean>, failed: Ref<boolean> }> = {
    releases: { loading: ref(false), failed: ref(false) },
    targetReleases: { loading: ref(false), failed: ref(false) }
}
const releaseRows = (part: ReleaseListPart): any[] => (updatedInstance.value && updatedInstance.value[part]) || []
// True when every row of the list has its details resolved (an empty list is
// trivially loaded). Derived from the rows and the cache rather than stored,
// so it cannot go stale when either changes.
const releaseDetailsLoaded = (part: ReleaseListPart): boolean =>
    releaseRows(part).every((r: any) => r.release in releaseDetailCache.value)
const deployedDetailsLoaded = computed<boolean>(() => releaseDetailsLoaded('releases'))
const targetDetailsLoaded = computed<boolean>(() => releaseDetailsLoaded('targetReleases'))
const deployedDetailsLoading = releaseDetailState.releases.loading
const targetDetailsLoading = releaseDetailState.targetReleases.loading
const deployedDetailsFailed = releaseDetailState.releases.failed
const targetDetailsFailed = releaseDetailState.targetReleases.failed

const isCluster = computed<boolean>(() => updatedInstance.value && updatedInstance.value.instanceType === InstanceType.CLUSTER)
// The two release tabs have neither a pill nor a panel on a CLUSTER, so a
// URL that names one (a link copied from a standalone instance, or the child
// drawer pushing its own tab onto the shared query) falls back to the
// Instance tab instead of landing on a blank page and fetching for nothing.
function tabAvailableHere (t: InstTab): InstTab {
    return isCluster.value && (t === 'target-releases' || t === 'deployed-releases') ? 'instance' : t
}
// The namespace dropdown scopes the release, unmatched-image and failure
// tables; it is only meaningful for a standalone instance, which is the one
// type that can span namespaces.
const showNamespaceFilter = computed<boolean>(() =>
    updatedInstance.value && updatedInstance.value.instanceType === InstanceType.STANDALONE_INSTANCE)
const planHistory: Ref<any[]> = ref([])
const actualHistory: Ref<any[]> = ref([])
const planHistoryLoading = ref(false)
const actualHistoryLoading = ref(false)
const planHistoryLoaded = ref(false)
const actualHistoryLoaded = ref(false)
// Default the filter window to the last 30 days, so the table populates
// on first tab open instead of showing 'no events' until the user picks
// two dates and clicks Search. Users widen / narrow from there.
const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000
const FAR_PAST_MS = new Date('1970-01-01T00:00:00Z').getTime()
const nowMs = () => Date.now()
const planHistorySearch = ref<{dateFrom: number | null, dateTo: number | null}>({ dateFrom: nowMs() - THIRTY_DAYS_MS, dateTo: nowMs() })
const actualHistorySearch = ref<{dateFrom: number | null, dateTo: number | null}>({ dateFrom: nowMs() - THIRTY_DAYS_MS, dateTo: nowMs() })
const planChangeTypeFilter = ref('ANY')
const actualChangeTypeFilter = ref('ANY')
const planChangeTypeOptions = [
    { label: 'Any', key: 'ANY' },
    { label: 'Product Release', key: 'PRODUCT_RELEASE' },
    { label: 'Target Release', key: 'TARGET_RELEASE' },
    { label: 'Property', key: 'PROPERTY' },
    { label: 'Environment', key: 'ENVIRONMENT' }
]
const actualChangeTypeOptions = [
    { label: 'Any', key: 'ANY' },
    { label: 'Deployment', key: 'DEPLOYMENT' },
    { label: 'Agent Data', key: 'AGENT_DATA' },
    { label: 'Product Match', key: 'PRODUCT_MATCH' }
]
const releaseForComparison = ref('')
const namespaceForComparison = ref('default')

let isWritable: boolean = commonFunctions.isWritable(orguuid.value, myUser, 'INSTANCE', instanceUuid, props.clusterId)

const isSelectingTargetFeatureSet = ref(false)
const isUpdateLinkedFeatureSet = ref(false)

const showAgentDataModal = ref(false)
const showCreateInstanceModal = ref(false)
const showEditPropertyModal = ref(false)
const showReleaseViewModal = ref(false)
const showInstSettingsModal = ref(false)
const showLinkFeatureSetModal = ref(false)
const showSelectTargetReleaseModal = ref(false)
const showAddComponentTargetReleaseModal = ref(false)
const showAddInstancePropertyModal = ref(false)
const showRevisionComparisonModal = ref(false)
const selectedReleaseIdForModal = ref('')
const selectedNamespace = ref('')

const focusedProduct: Ref<any> = ref({})
const focusedProperty: Ref<any> = ref({})

const updatedPropValue = ref('')

const targetIndividualReleases: ComputedRef<any> = computed((): any => {
    let deployedRls = []
    if (updatedInstance.value && updatedInstance.value.targetReleases && updatedInstance.value.targetReleases.length) {
        deployedRls = parseDeployedReleases(updatedInstance.value.targetReleases)
    }
    return deployedRls
})

const deployedReleases: ComputedRef<any> = computed((): any => {
    let deployedRls = []
    if (updatedInstance.value && updatedInstance.value.releases && updatedInstance.value.releases.length) {
        deployedRls = parseDeployedReleases(updatedInstance.value.releases)
    }
    return deployedRls
})

// Watcher-reported images whose digest is not in this org's ontology.
// Snapshot only — refreshes on every watcher tick on the backend. Empty
// when nothing unrecognised is currently running (or watcher hasn't
// reported yet). Filtered by namespace when the same dropdown that
// scopes Deployed Component Releases has a value selected.
const filteredUnmatchedReleases: ComputedRef<any[]> = computed((): any[] => {
    const list = (updatedInstance.value && updatedInstance.value.unmatchedReleases) || []
    if (!selectedNamespace.value || selectedNamespace.value === 'ALL') return list
    return list.filter((u: any) => u.namespace === selectedNamespace.value)
})

// Open deployment failures reported by ReARM CD. Namespace-filtered by the
// same dropdown that scopes the other instance tables.
const filteredDeploymentFailures: ComputedRef<any[]> = computed((): any[] => {
    const list = (updatedInstance.value && updatedInstance.value.deploymentFailures) || []
    if (!selectedNamespace.value || selectedNamespace.value === 'ALL') return list
    return list.filter((f: any) => f.namespace === selectedNamespace.value)
})

// Unfiltered count for the tab pill -- the pill must reflect every open
// failure regardless of the namespace filter, which only scopes the table.
const deploymentFailureCount: ComputedRef<number> = computed((): number =>
    ((updatedInstance.value && updatedInstance.value.deploymentFailures) || []).length)

// Header chip. FAILING means the agent re-reported a failure recently;
// DEGRADED means open failures exist but nothing recent — which is genuinely
// ambiguous between "fixed" and "the agent stopped reporting", hence the
// tooltip pointing at Last Seen.
const deploymentHealthChip: ComputedRef<any> = computed((): any => {
    const health = updatedInstance.value && updatedInstance.value.deploymentHealth
    if (!health || health === 'HEALTHY') return null
    if (health === 'FAILING') {
        return { type: 'error', label: 'Deployments failing',
            tip: 'ReARM CD reported a deployment failure within the last 15 minutes.' }
    }
    return { type: 'warning', label: 'Deployments degraded',
        tip: 'Open deployment failures exist but none was re-reported recently — either they were fixed, or the CD agent stopped reporting. Check Last Seen below.' }
})

// "3 minutes ago" style helper for failure recency; the absolute timestamp is
// always shown alongside so the relative form is a convenience, not the source
// of truth.
function relativeFromNow (iso: string): string {
    if (!iso) return ''
    const then = new Date(iso).getTime()
    if (Number.isNaN(then)) return ''
    const secs = Math.max(0, Math.round((Date.now() - then) / 1000))
    if (secs < 60) return `${secs}s ago`
    const mins = Math.round(secs / 60)
    if (mins < 60) return `${mins}m ago`
    const hours = Math.round(mins / 60)
    if (hours < 48) return `${hours}h ago`
    return `${Math.round(hours / 24)}d ago`
}

// Build the body for the bulls-eye Actual-column tooltip. Lists every
// feature-set dependency with its target version, the actual deployed
// version on this instance, and a per-component verdict. JOB-status
// deps are flagged as "drift OK" (excluded from match by design);
// TRANSIENT deps still get a check or X based on whether they actually
// match the target (drift is allowed for them too, but seeing the
// state is useful). REQUIRED deps drive the green/red overall icon
// already; the per-row breakdown explains why.
function buildMatchTooltipBody(row: any) {
    const lines: any[] = []
    const isMatching = !!row.matchedRelease && !row.notMatchingSince
    let headerText: string
    if (isMatching) {
        headerText = 'Matching'
    } else if (row.notMatchingSince) {
        headerText = `Not matching since ${(new Date(row.notMatchingSince)).toLocaleString('en-CA')}`
    } else {
        headerText = 'Not matching'
    }
    lines.push(h('div', { style: 'font-weight: 600; margin-bottom: 4px;' }, headerText))
    if (!deployedDetailsLoaded.value) {
        // Deployed release details are deferred; onMatchTooltipShow kicks off
        // the fetch and this body re-renders once the versions are known.
        // Don't draw the per-dependency rows yet: without actual versions
        // every one would read as a red mismatch under a 'Matching' header.
        lines.push(h('div', { style: 'font-style: italic; color: #888;' }, deployedDetailsFailed.value
            ? 'Deployed release details could not be loaded. Open the Deployed Component Releases tab to retry.'
            : 'Loading deployed releases...'))
        return lines
    }

    const deps = (row.featureSetDetails && row.featureSetDetails.dependencies) || []
    const targetParents = (row.targetReleaseDetails && row.targetReleaseDetails.parentReleases) || []
    const targetByComponent: Record<string, any> = {}
    targetParents.forEach((p: any) => {
        const compUuid = p.releaseDetails && p.releaseDetails.componentDetails && p.releaseDetails.componentDetails.uuid
        if (compUuid) targetByComponent[compUuid] = p.releaseDetails
    })
    const actualReleases = (updatedInstance.value && updatedInstance.value.releases) || []
    const actualByComponent: Record<string, any> = {}
    actualReleases.forEach((r: any) => {
        if (r.namespace !== row.namespace) return
        const rd = r.releaseDetails
        const compUuid = rd && rd.componentDetails && rd.componentDetails.uuid
        if (compUuid && !actualByComponent[compUuid]) actualByComponent[compUuid] = rd
    })

    if (deps.length === 0) {
        lines.push(h('div', { style: 'font-style: italic; color: #888;' }, '(feature set has no dependencies)'))
        return lines
    }

    deps.forEach((dep: any) => {
        if (dep.status === 'IGNORED') return // not in CDX, skip
        const compUuid = dep.uuid
        const tgt = targetByComponent[compUuid]
        const act = actualByComponent[compUuid]
        const compName = (tgt && tgt.componentDetails && tgt.componentDetails.name)
            || (act && act.componentDetails && act.componentDetails.name)
            || compUuid
        const tgtV = tgt ? tgt.version : '—'
        const actV = act ? act.version : '—'
        const versionsMatch = tgt && act && tgt.uuid === act.uuid
        let icon = '✓'
        let iconColor = '#2da44e'
        let note = ''
        if (dep.status === 'JOB') {
            icon = versionsMatch ? '✓' : '⏳'
            iconColor = versionsMatch ? '#2da44e' : '#9a6700'
            note = versionsMatch ? '' : ' (drift OK — schedule-driven, awaiting next run)'
        } else if (dep.status === 'TRANSIENT') {
            icon = versionsMatch || !act ? '✓' : '✗'
            iconColor = (versionsMatch || !act) ? '#2da44e' : '#cf222e'
            note = !versionsMatch && act ? ' (drift; TRANSIENT match allows asymmetry only)' : ''
        } else {
            icon = versionsMatch ? '✓' : '✗'
            iconColor = versionsMatch ? '#2da44e' : '#cf222e'
        }
        const tag = dep.status && dep.status !== 'REQUIRED'
            ? h('span', { style: 'margin-left: 6px; padding: 1px 6px; border-radius: 4px; background: #e8f1ff; color: #1f5ad3; font-size: 10px; font-weight: 600;' }, dep.status)
            : null
        lines.push(h('div', { style: 'display: flex; align-items: center; gap: 8px; line-height: 1.5; white-space: nowrap;' }, [
            h('span', { style: `color: ${iconColor}; font-weight: 700; min-width: 14px; flex: 0 0 14px;` }, icon),
            h('span', { style: 'flex: 0 0 auto;' }, compName),
            tag,
            h('span', { style: 'color: #aaa; flex: 1 1 auto; overflow: hidden; text-overflow: ellipsis;' }, `actual ${actV} / target ${tgtV}${note}`)
        ]))
    })
    return lines
}

const instanceData: ComputedRef<any> = computed((): any => store.getters.instanceById(instanceUuid, -1))
const instanceWord: ComputedRef<any> = computed((): any => props.instanceType === InstanceType.CLUSTER ? 'Cluster' : 'Instance')
const cluster: ComputedRef<any> = computed((): any => {
    let cluster = null
    let storeInstances = store.getters.instancesOfOrg(orguuid.value)
    if (storeInstances && storeInstances.length) {
        cluster = storeInstances.find((x: any) => x.revision === -1 && x.instanceType === InstanceType.CLUSTER && x.instances.includes(instanceUuid))
    }
    return cluster
})
if(!isWritable && cluster.value && cluster.value.uuid && cluster.value.uuid !== ''){
    isWritable = commonFunctions.isWritable(orguuid.value, myUser, 'INSTANCE', cluster.value.uuid)
}
const namespacesForDropdown: ComputedRef<any[]> = computed((): any => {
    let retNs: any[] = []
    const namespaces = new Set()
    namespaces.add('ALL')
    namespaces.add('default')
    if (updatedInstance.value && updatedInstance.value.releases && updatedInstance.value.releases.length) {
        updatedInstance.value.releases.forEach((dr: any) => {
            namespaces.add(dr.namespace)
        })
    }
    if (namespaces.size) {
        retNs = Array.from(namespaces).map(n => {return {key: n, label: n}})
    }
    return retNs
})

const knownProducts: ComputedRef<any[]> = computed((): any => {
    let knownProducts = []
    if (updatedInstance.value && updatedInstance.value.productPlans && updatedInstance.value.productPlans.length) {
        knownProducts = updatedInstance.value.productPlans.map((p: any) => {
            return {
                label: p.featureSetDetails.componentDetails.name,
                value: p.featureSetDetails.componentDetails.uuid
            }
        })
    }
    knownProducts.unshift({label:"", value:""})
    return knownProducts
})

const knownNamespaces: ComputedRef<any[]> = computed((): any => {
    let knownNamespaces = []
    let nsDedup: any = {}
    if (updatedInstance.value && updatedInstance.value.productPlans && updatedInstance.value.productPlans.length) {
        knownNamespaces = updatedInstance.value.productPlans.map((p: any) => {
            let ns = p.namespace
            if (!nsDedup[ns]) {
                nsDedup[ns] = true
                return {
                    label: ns,
                    value: ns
                }
            }
        }).filter((nsObj: any) => nsObj)
    }
    knownNamespaces.unshift({label:"", value:""})
    return knownNamespaces
})

const createdProperty = async function (prop: any) {
    updatedInstance.value.properties.push({
        uuid: prop.uuid,
        type: 'CONFIGURABLE', // always configurable for manually added props
        namespace: prop.namespace,
        value: prop.value,
        product: prop.product
    })
    await save()
    showAddInstancePropertyModal.value = false
}

const genApiKey = async function () {
    const swalResult = await Swal.fire({
        title: 'Are you sure?',
        text: 'A new API Key will be generated, any existing integrations with previous API Key (if exist) will stop working.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, generate it!',
        cancelButtonText: 'No, cancel it'
    })

    if (swalResult.value) {
        // const path = '/v1/instance/setApiKey/' + instanceUuid
        // setInstanceApiKey
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation setInstanceApiKey($instanceUuid: ID!) {
                    setInstanceApiKey(instanceUuid: $instanceUuid) {
                        id
                        apiKey
                        authorizationHeader
                    }
                }`,
            variables: {
                instanceUuid: instanceUuid
            }
        })
        let keyData = response.data.setInstanceApiKey
        let newKeyMessage = `
            <div style="text-align: left;">
            <p>Please record these data as you will see API key only once (although you can re-generate it at any time):</p>
                <table style="width: 95%;">
                    <tr>
                        <td>
                            <strong>API ID:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${keyData.id}</textarea>
                        </td>
                    </tr>
                        <td>
                            <strong>API Key:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${keyData.apiKey}</textarea>
                        </td>
                    <tr>
                        <td>
                            <strong>Header:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled rows="4">${keyData.authorizationHeader}</textarea>
                        </td>
                    </tr>
                </table>
            </div>
        `

        Swal.fire({
            title: 'Generated!',
            customClass: {popup: 'swal-wide'},
            html: newKeyMessage,
            icon: 'success'
        })
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        notification.error({
            content: 'Your existing API Key (if present) is safe',
            meta: 'Cancelled',
            duration: 3500,
            keepAliveOnHover: true
        })
    }
}

const archiveInstance = async function () {
    const swalResult = await Swal.fire({
        title: `Are you sure you want to archive the ${updatedInstance.value.uri} ${instanceWord.value}?`,
        text: `If you proceed, the instance will be archived and you will not have access to its data.`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, archive!',
        cancelButtonText: 'No, cancel'
    })
    
    if (swalResult.value) {
        const archiveInstanceParams = {
            instanceUuid: instanceUuid,
            orgUuid: updatedInstance.value.org
        }
        try {
            const archived = await store.dispatch('archiveInstance', archiveInstanceParams)
            if (archived) {
                router.push({ name: 'InstancesOfOrg', params: { orguuid: updatedInstance.value.org } })
            }
        } catch (err: any) {
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(err.message),
                'error'
            )
        }
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        notification.info({
            content: `Instance archiving cancelled. Your instance is still active.`,
            meta: 'Cancelled',
            duration: 3500,
            keepAliveOnHover: true
        })
    }
}

const addedIntegrationFeatureSet = async function (fsObj: any) {
    if (!isUpdateLinkedFeatureSet.value) {
        if (updatedInstance.value.updProducts && updatedInstance.value.updProducts.length) {
            updatedInstance.value.updProducts.push(fsObj)
        } else {
            updatedInstance.value.updProducts = [fsObj]
        }
        await save()
    } else if (focusedProduct.value.featureSet !== fsObj.featureSet) {
        const prod = updatedInstance.value.productPlans.find((p: any) => (
            p.featureSet === focusedProduct.value.featureSet && p.namespace === focusedProduct.value.namespace))
        prod.featureSet = fsObj.featureSet
        prod.targetRelease = ''
        await save()
    }
    showLinkFeatureSetModal.value = false
}



// --- History tab fetchers (lazy: only run when the tab is shown or a filter changes) ---
// Always use the by-date variant on the server so changeType can be
// passed even when the user only picks one bound (or none) — the
// non-date-range query has no changeType arg. Missing bounds fall
// back to far-past / far-future so the resulting window covers
// 'all of time' when needed.
const FAR_FUTURE_MS = new Date('2999-12-31T23:59:59Z').getTime()
function buildHistoryParams(search: any, changeType: string): any {
    const params: any = { instanceUuid }
    const from = search.dateFrom ? Number(search.dateFrom) : FAR_PAST_MS
    const to = search.dateTo ? Number(search.dateTo) : FAR_FUTURE_MS
    params.fromDate = new Date(from).toISOString()
    params.toDate = new Date(to).toISOString()
    if (changeType && changeType !== 'ANY') params.changeType = changeType
    return params
}

const fetchPlanHistory = async function () {
    planHistoryLoading.value = true
    try {
        const data = await store.dispatch('fetchInstanceHistoryPlan',
            buildHistoryParams(planHistorySearch.value, planChangeTypeFilter.value))
        planHistory.value = data || []
        planHistoryLoaded.value = true
    } catch (err: any) {
        console.error(err)
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        planHistoryLoading.value = false
    }
}

const fetchActualHistory = async function () {
    actualHistoryLoading.value = true
    try {
        const data = await store.dispatch('fetchInstanceHistoryActual',
            buildHistoryParams(actualHistorySearch.value, actualChangeTypeFilter.value))
        actualHistory.value = data || []
        actualHistoryLoaded.value = true
    } catch (err: any) {
        console.error(err)
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        actualHistoryLoading.value = false
    }
}

const switchTab = async function (t: InstTab) {
    activeTab.value = t
    await router.push({ query: { ...route.query, instTab: t } })
    await lazyLoadTab(t)
}

// Attach cached details to the rows of one list. Rows whose release is not
// in the cache are left as they are (the tables skip rows without details).
function applyReleaseDetails (part: ReleaseListPart) {
    releaseRows(part).forEach((r: any) => {
        if (r.release in releaseDetailCache.value) r.releaseDetails = releaseDetailCache.value[r.release]
    })
}

// Fetch details for whichever rows of the list are not cached yet, then merge
// them in. One request in flight per list; `attempt` bounds the follow-up
// fetch that runs when the list changed underneath the request (a row added
// by a save while the request was pending), so a backend that never returns
// a row cannot spin this.
async function ensureReleaseDetails (part: ReleaseListPart, attempt = 0) {
    const state = releaseDetailState[part]
    if (releaseDetailsLoaded(part) || state.loading.value) return
    state.loading.value = true
    state.failed.value = false
    try {
        const rows = await store.dispatch('fetchInstanceReleaseDetails', { id: instanceUuid, part })
        rows.forEach((r: any) => {
            // null is a resolved answer too (release gone): cache it so the
            // row counts as loaded rather than re-fetching forever.
            releaseDetailCache.value[r.release] = r.releaseDetails || null
        })
        applyReleaseDetails(part)
    } catch (err: any) {
        state.failed.value = true
        console.error(err)
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        state.loading.value = false
    }
    if (!state.failed.value && !releaseDetailsLoaded(part) && attempt < 2) {
        await ensureReleaseDetails(part, attempt + 1)
    }
}

function retryReleaseDetails (part: ReleaseListPart) {
    releaseDetailState[part].failed.value = false
    ensureReleaseDetails(part)
}

// The product-match tooltip lists actual deployed versions per dependency,
// so opening it is the one place outside its tab that needs release details.
// A failed fetch is not retried from here -- hovering must not spam error
// toasts; the tab has an explicit Retry.
function onMatchTooltipShow (show: boolean) {
    if (show && !deployedDetailsFailed.value) ensureReleaseDetails('releases')
}

async function lazyLoadTab(t: InstTab) {
    if (t === 'plan-history' && !planHistoryLoaded.value) {
        await fetchPlanHistory()
    } else if (t === 'actual-history' && !actualHistoryLoaded.value) {
        await fetchActualHistory()
    } else if (t === 'target-releases') {
        await ensureReleaseDetails('targetReleases')
    } else if (t === 'deployed-releases') {
        await ensureReleaseDetails('releases')
    }
}

// Query-only navigations no longer remount the view (router-view keys on
// path), so browser back/forward must be applied to local state here. An
// absent/invalid param means the default tab, matching the pre-fix
// remount-initializer behavior.
watch(() => route.query.instTab, async (t) => {
    const next: InstTab = tabAvailableHere(isInstTab(t) ? t : 'instance')
    if (next !== activeTab.value) {
        activeTab.value = next
        await lazyLoadTab(next)
    }
})

// Auto-refetch when a date bound changes. Debounced so picking
// both From and To in quick succession (or the picker emitting
// twice while normalising the value) only triggers one query.
let planFetchTimer: any = null
let actualFetchTimer: any = null
watch(() => [planHistorySearch.value.dateFrom, planHistorySearch.value.dateTo], () => {
    if (activeTab.value !== 'plan-history') return
    if (planFetchTimer) clearTimeout(planFetchTimer)
    planFetchTimer = setTimeout(() => fetchPlanHistory(), 250)
})
watch(() => [actualHistorySearch.value.dateFrom, actualHistorySearch.value.dateTo], () => {
    if (activeTab.value !== 'actual-history') return
    if (actualFetchTimer) clearTimeout(actualFetchTimer)
    actualFetchTimer = setTimeout(() => fetchActualHistory(), 250)
})

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const deleteProperty = function (uuid: string, namespace: string, product: string) {
    updatedInstance.value.properties = updatedInstance.value.properties.filter((p: any) => !(p.uuid === uuid && p.namespace === namespace && p.product === product))
    save()
}

const handleEnvironmentChange = async function (newEnv: string) {
    updatedInstance.value.environment = newEnv
}
const fetchInstance = async function() {
    if( instanceUuid === undefined || instanceUuid === null || instanceUuid === '')
        return
    const instResp = await store.dispatch('fetchInstance', { id: instanceUuid, core: true })
    const updatedInstancePrep = commonFunctions.deepCopy(instResp)
    // sort products
    if (updatedInstancePrep.productPlans && updatedInstancePrep.productPlans.length) {
        updatedInstancePrep.productPlans.sort((a: any, b: any) => {
            if (a.featureSetDetails.componentDetails.name < b.featureSetDetails.componentDetails.name) {
                return -1
            } else if (a.featureSetDetails.componentDetails.name > b.featureSetDetails.componentDetails.name) {
                return 1
            } else if (a.featureSetDetails.name < b.featureSetDetails.name) {
                return -1
            } else if (a.featureSetDetails.name > b.featureSetDetails.name) {
                return 1
            } else if (a.namespace < b.namespace) {
                return -1
            } else if (a.namespace > b.namespace) {
                return 1
            } else {
                return 0
            }
        })
        // merge productActuals data into productPlans for display
        if (updatedInstancePrep.productActuals && updatedInstancePrep.productActuals.length) {
            updatedInstancePrep.productPlans.forEach((plan: any) => {
                const actual = updatedInstancePrep.productActuals.find((a: any) =>
                    a.featureSet === plan.featureSet && a.namespace === plan.namespace)
                if (actual) {
                    plan.matchedRelease = actual.matchedRelease
                    plan.matchedReleaseDetails = actual.matchedReleaseDetails
                    plan.notMatchingSince = actual.notMatchingSince
                    plan.identifier = actual.identifier || plan.identifier
                }
            })
        }
    }
    updatedInstance.value = updatedInstancePrep
    // The core document replaced both release lists with shallow rows; put
    // the cached details back on the rows that are still there, then load
    // whatever the open tab is missing. Not awaited: the view is rendered
    // under Suspense on first mount, and a deep link to a heavy tab should
    // paint the page with its inline loading state, not block on the fetch.
    RELEASE_LIST_PARTS.forEach(applyReleaseDetails)
    activeTab.value = tabAvailableHere(activeTab.value)
    lazyLoadTab(activeTab.value)
}

const genProductRelease = async function () {
    // await axios.post('/v1/instance/generateProductRelease/' + instanceUuid)
    // refetch instance to sync store
    // await fetchInstance()
    try{
        await graphqlClient.mutate({
            mutation: gql`
                mutation generateProductRelease($instanceUuid: ID!) {
                    generateProductRelease(instanceUuid: $instanceUuid) {
                        featureSet
                    }
                }`,
            variables: {
                instanceUuid: instanceUuid
            }
        })
    }   catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    await fetchInstance()
}

const linkifyCommit = function (uri: string, commit: string) {
    return commonFunctions.linkifyCommit(uri, commit)
}

const parseDeployedReleases = function (releases: any) {
    const deployedRls: any[] = []
    releases.forEach((rl: any, index: number) => {
        if (!selectedNamespace.value || selectedNamespace.value === 'ALL' ||
            selectedNamespace.value === rl.namespace) {
            let deployedRl = rl.releaseDetails
            if (deployedRl) {
                let dRlObj = {
                    originalReleaseId: rl.release,
                    release: deployedRl,
                    type: rl.type,
                    component: deployedRl.componentDetails.name,
                    componentUuid: deployedRl.componentDetails.uuid,
                    componentType: deployedRl.componentDetails.type,
                    index: index,
                    namespace: rl.namespace,
                    state: rl.state
                }
                deployedRls.push(dRlObj)
            }
        }
    })
    if (deployedRls) {
        deployedRls.sort((a, b) => {
            if (a.component < b.component) {
                return -1
            } else if (a.component > b.component) {
                return 1
            } else {
                return 0
            }
        })
    }
    return deployedRls
}

const removeProductLink = async function (fsUuid: string, namespace: string) {
    const onSwalConfirm = async function () {
        updatedInstance.value.productPlans = updatedInstance.value.productPlans.filter((p: any) => !(p.featureSet === fsUuid && p.namespace === namespace))
        save()
    }
    const swalData: SwalData = {
        questionText: 'The selected product will be removed from the instance',
        successTitle: 'Removed!',
        successText: 'Selected product has been removed.',
        dismissText: 'Product removal was cancelled.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

const toggleAlerts = async function (prl: any, value: boolean) {
    const updatedProduct = updatedInstance.value.productPlans.find((product: any) => 
        product.featureSet === prl.featureSet && product.namespace === prl.namespace
    )
    updatedProduct.alertsEnabled = value
    const onSwalConfirm = async function () {
        
        save()
    }
    const swalData: SwalData = {
        questionText: !prl.alertsEnabled ? 'Alerts would be disabled for this product!' : 'Enable alerts for this product',
        successTitle: !prl.alertsEnabled ? 'Alerts Disabled!' : 'Alerts Enabled!',
        successText: '',
        dismissText: 'Alert toggle was cancelled.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

const save = async function () {
    try {
        await store.dispatch('updateInstance', updatedInstance.value)
        // Invalidate cached history before the refetch, so that if a history
        // tab is the one open, fetchInstance -> lazyLoadTab reloads it.
        planHistoryLoaded.value = false
        actualHistoryLoaded.value = false
        planHistory.value = []
        actualHistory.value = []
        await fetchInstance()
        notify('info', 'SAVED', 'Instance changes saved successfully!')
    } catch (errOnSave: any) {
        console.error(errOnSave)
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(errOnSave.message),
            'error'
        )
    }
}

const setIntegrateType = async function (prl: any, t: string) {
    const prod = updatedInstance.value.productPlans.find((p:any) => (
        p.featureSet === prl.featureSet && p.namespace === prl.namespace))
    if (prod.type !== t) {
        prod.type = t
        if (prod.type === 'INTEGRATE') {
            prod.targetRelease = null
        }
        await save()
    }
}

const targetReleaseSet = async function (rlz: any) {
    const prod = updatedInstance.value.productPlans.find((p: any) => (
        p.featureSet === focusedProduct.value.featureSet && p.namespace === focusedProduct.value.namespace))
    if (prod.targetRelease !== rlz.uuid || prod.featureSet !== rlz.branch) {
        prod.featureSet = rlz.branch
        prod.targetRelease = rlz.uuid
        await save()
    }
    showSelectTargetReleaseModal.value = false
}

const updateConfiguration = async function (e: any, prl: any) {
    const updatedProduct = updatedInstance.value.productPlans.find((product: any) => 
        product.featureSet === prl.featureSet && product.namespace === prl.namespace
    )
    if (updatedProduct.configuration !== e.target.innerText) {
        updatedProduct.configuration = e.target.innerText
        await save() 
    }
}

const clearAgentData = async function () {
    const onSwalConfirm = async function () {
        updatedInstance.value.agentData = ''
        await save()
    }
    const swalData: SwalData = {
        questionText: 'This will clear all agent data currently stored on the instance.',
        successTitle: 'Cleared!',
        successText: 'Agent data has been deleted.',
        dismissText: 'Agent data is intact.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

const componentTargetReleaseAdded = async function (rlz: any) {
    await store.dispatch('fetchReleaseById', { release: rlz.uuid })
    if (!updatedInstance.value.targetReleases) {
        updatedInstance.value.targetReleases = []
    }
    const deployedRlz = {
        namespace: (rlz.namespace ? rlz.namespace : 'default'),
        release: rlz.uuid,
        type: 'MANUAL'
    }
    updatedInstance.value.targetReleases.push(deployedRlz)
    await save()
    showAddComponentTargetReleaseModal.value = false
}

const removeRelease = async function (releaseUuid: string, namespace: string) {
    updatedInstance.value.targetReleases = updatedInstance.value.targetReleases.filter((r: any) => !(r.release === releaseUuid && r.namespace === namespace))
    await save()
}

const showCdxExportModal = ref(false)
const cdxExportStateType = ref('')
const exportingCdx = ref(false)
const cdxStateTypeOptions = [
    { label: 'Plan', value: 'PLAN' },
    { label: 'Actual', value: 'ACTUAL' }
]

const exportCycloneDx = function () {
    cdxExportStateType.value = ''
    showCdxExportModal.value = true
}

const doExportCycloneDx = async function () {
    exportingCdx.value = true
    try {
        const response = await graphqlClient.query({
            query: gql`
                query getInstanceRevisionCycloneDxExportManual($instanceUuid: ID!, $stateType: InstanceStateType) {
                    getInstanceRevisionCycloneDxExportManual(instanceUuid: $instanceUuid, stateType: $stateType)
                }`,
            variables: { instanceUuid, stateType: cdxExportStateType.value },
            fetchPolicy: 'no-cache'
        })
        const content = response.data.getInstanceRevisionCycloneDxExportManual
        const blob = new Blob([content], { type: 'application/json' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `instance-${instanceUuid}-${cdxExportStateType.value.toLowerCase()}.cdx.json`
        a.click()
        URL.revokeObjectURL(url)
        showCdxExportModal.value = false
    } catch (err: any) {
        notification.error({ title: 'Export Failed', content: commonFunctions.parseGraphQLError(err.message), duration: 5000 })
    } finally {
        exportingCdx.value = false
    }
}

const onCreate = async function () {
    if( instanceUuid === undefined || instanceUuid === null || instanceUuid === '')
        return
    releaseDetailCache.value = {}
    await fetchInstance()

    graphqlClient.query({
        query: graphqlQueries.EnvironmentTypesGql,
        variables: { orgUuid: updatedInstance.value.org }
    }).then((envsResp: any) => {
        envs.value = envsResp.data.environmentTypes.map((e: any) => {return {label: e, key: e}})
    })

    // Tab content (history, release details) is loaded lazily by
    // fetchInstance -> lazyLoadTab for whichever tab the URL selected.
    if(route.params.subinstuuid !== undefined && route.params.subinstuuid !== null && route.params.subinstuuid !== ''){
        selectedChildInstRowKey.value = [route.params.subinstuuid.toString()]
        showChildInstance.value = true
    }else {
        showChildInstance.value = false
    }
    
}

const highlighter = function (code: string) {
    const lang = focusedProperty?.value?.property?.dataType === 'JSON' || focusedProperty?.value?.property?.dataType === 'YAML' ? focusedProperty?.value?.property?.dataType.toLowerCase() : 'markup'
    return prism.highlight(code, prism.languages[lang], lang)
}

// Pretty-printed JSON for the Agent Data modal. The watcher ships a
// JSON string in updatedInstance.agentData; we re-parse + indent so
// PrismEditor renders it readable instead of a single squashed line.
// Falls back to the raw string when the payload isn't valid JSON
// (legacy data, or a watcher mode that ships YAML / plain text).
const prettyAgentData = computed<string>({
    get() {
        const raw = updatedInstance.value && updatedInstance.value.agentData
        if (!raw) return ''
        try {
            return JSON.stringify(JSON.parse(raw), null, 2)
        } catch {
            return raw
        }
    },
    // Editor is :readonly so writes never fire, but v-model still
    // demands a setter — no-op.
    set() {}
})
const jsonHighlighter = function (code: string) {
    return prism.highlight(code, prism.languages.json, 'json')
}

// n-data-table
const matchedProductFields: any[] = [
    {
        key: 'product',
        title: 'Product',
        render: (row: any) => {
            let el = h(
                RouterLink,
                {
                    to: {
                        name: 'ProductsOfOrg',
                        params: {
                            orguuid: updatedInstance.value.org,
                            compuuid: row.featureSetDetails.componentDetails.uuid
                        }
                    }
                },
                { default: () => row.featureSetDetails.componentDetails.name }
            )
            return el
        }
    },
    {
        key: 'fs',
        title: featureSetLabel.value,
        render: (row: any) => {
            let els = []
            els.push(h(
                RouterLink,
                {
                    to: {
                        name: 'ProductsOfOrg',
                        params: {
                            orguuid: updatedInstance.value.org,
                            compuuid: row.featureSetDetails.componentDetails.uuid,
                            branchuuid: row.featureSet
                        }
                    }
                },
                { default: () => row.featureSetDetails.name }
            ))
            if(row.type === 'TARGET' && isWritable){
                els.push(h(NIcon, {
                    title: 'Change ' + featureSetLabel.value + ' and Target Release',
                    class: 'icons clickable',
                    size: 16,
                    onClick: () => {
                        focusedProduct.value = row
                        isSelectingTargetFeatureSet.value = true
                        showSelectTargetReleaseModal.value = true
                    }
                }, { default: () => h(Edit24Regular) 
                }))
            } else if(row.type !== 'TARGET' && isWritable){
                els.push(h(NIcon, {
                    title: 'Change ' + featureSetLabel.value,
                    class: 'icons clickable',
                    size: 16,
                    onClick: () => {
                        focusedProduct.value = row
                        isUpdateLinkedFeatureSet.value = true
                        showLinkFeatureSetModal.value = true
                    }
                }, { default: () => h(Edit24Regular) 
                }))
            }
            
            return els
        }
    },
    {
        key: 'actual',
        title: 'Actual',
        render: (row: any) => {
            let els = []

            if(row.matchedRelease){
                els.push(h(NTooltip, {
                    trigger: 'hover',
                    placement: 'top',
                    width: 720,
                    style: { maxWidth: '720px' },
                    'onUpdate:show': onMatchTooltipShow
                }, {
                    trigger: () => h(NIcon, {
                        size: 16,
                        color: row.notMatchingSince ? 'red' : 'green'
                    }, {
                        default: () => h(Target20Regular)
                    }),
                    default: () => buildMatchTooltipBody(row)
                }))

                els.push(h('span', [
                    h('a', {
                        onClick: (e: Event) => { 
                            e.preventDefault() 
                            selectedReleaseIdForModal.value = row.matchedRelease
                            showReleaseViewModal.value = true
                        },
                        href: '#'
                    },
                    [h('span', row.matchedReleaseDetails.version)]
                    )
                    
                ]))
            }else {
                els.push(h(NTooltip, {
                    trigger: 'hover',
                    placement: 'top',
                    width: 720,
                    style: { maxWidth: '720px' },
                    'onUpdate:show': onMatchTooltipShow
                }, {
                    trigger: () => h(NIcon, {
                        size: 16,
                        color: 'red'
                    }, {
                        default: () => h(DismissCircle20Regular)
                    }),
                    default: () => buildMatchTooltipBody(row)
                }))
                els.push(h('span', { style: 'margin-left: 4px;' }, 'Not Matched'))
            }

            return els
        }
    },
]
matchedProductFields.push({
    key: 'target',
        title: 'Target',
        render: (row: any) => {
            let els = []
            if(row.type !== 'INTEGRATE'){
                if(row.targetRelease && row.targetReleaseDetails.version){
                    els.push(
                        h('span', [
                            h('a', {
                                onClick: (e: Event) => { 
                                    e.preventDefault() 
                                    selectedReleaseIdForModal.value = row.targetRelease
                                    showReleaseViewModal.value = true
                                },
                                href: '#'
                            },
                            [h('span', row.targetReleaseDetails.version)]
                            )
                            
                        ]) 
                    )
                }else{
                    els.push(h('span', 'Not Set'))
                }
            }else{
                els.push(h('span', 'Not Applicable'))
            }
            if(row.type==='TARGET' && isWritable){
                els.push(h(NIcon, {
                    title: 'Set Target Release',
                    class: 'icons clickable',
                    size: 16,
                    onClick: () => {
                        focusedProduct.value = row
                        isSelectingTargetFeatureSet.value = false
                        showSelectTargetReleaseModal.value = true
                    }
                }, { default: () => h(Edit24Regular) 
                }))
            }
            if(row.targetRelease && row.targetReleaseDetails.version){
                els.push(h(NIcon, {
                    title: 'Diff Target Release',
                    class: 'icons clickable',
                    size: 16,
                    onClick: () => {
                        releaseForComparison.value = row.targetRelease
                        namespaceForComparison.value = row.namespace
                        showRevisionComparisonModal.value = true
                    }
                }, { default: () => h(LayoutColumns) 
                }))
            } 
            return els
        }
    })
matchedProductFields.push({
    key: 'config',
    title: 'Config',
    render: (row: any) => {
        if(isWritable){
            return h('span', {
                contenteditable: true,
                onblur: (e: Event) => {
                    updateConfiguration(e, row)
                }
            }, {default: () => row.configuration})
        }else{
            return h('span', row.configuration)
        }
    }
})
if(props.instanceType === InstanceType.STANDALONE_INSTANCE){
    matchedProductFields.push({
        key: 'namespace',
        title: 'Namespace'
    })
}
matchedProductFields.push({
    key: 'integrate',
    title: 'Integrate?',
    render: (row: any) => {
        let els: any[] = []

        if(isWritable){
            els.push(h(NDropdown, {
                trigger: 'hover',
                options: [
                    {key: 'INTEGRATE', label: 'INTEGRATE'},
                    {key: 'FOLLOW', label: 'FOLLOW'}, 
                    {key: 'TARGET', label: 'TARGET'}, 
                    {key: 'NONE', label: 'NONE'},
                    {key: 'UNINSTALL', label: 'UNINSTALL'}
                ],
                value: row.type,
                'on-select': (key: any) => setIntegrateType(row, key)
            },  {default: () => h('span', [row.type, h(NIcon, {}, {default: () => h(CaretDownFilled)})])
                
            }))
        }else{
            els.push(h('span', row.type))
        }

        return els
        // return h('span', row.type)
    }
})
// Alerts? column hidden for now — the per-product alerts toggle is not wired to
// anything yet. Kept commented (not deleted) so it can be re-enabled once the
// alerts feature does something.
// matchedProductFields.push({
//     key: 'alerts',
//     title: 'Alerts?',
//     render: (row: any) => h(NSwitch, {
//         size: 'medium',
//         value: row.alertsEnabled,
//         'onUpdate:value': (value: boolean) => toggleAlerts(row, value)
//     }, {
//         checked: () => h(NIcon, {}, {default: () => h(AlertOn24Regular)}),
//         unchecked: () => h(NIcon, {}, {default: () => h(AlertOff24Regular)})
//     })
// })
matchedProductFields.push({
    key: 'controls',
    title: '',
    render: (row: any) => {
        if(isWritable){
            return h(NIcon, {
                title: 'Remove ' + featureSetLabel.value + ' Link',
                class: 'icons clickable',
                size: 25,
                onClick: () => {
                    removeProductLink(row.featureSet, row.namespace)
                }
            }, { default: () => h(Trash) 
            }) 
        }
    }
})

const targetReleaseFeilds: any[] = [
    {
        key: 'component',
        title: 'Component',
        render: (row: any) => {
            let el
            if (row.componentType === 'COMPONENT') {
                el = h(
                    RouterLink,
                    {
                        to: {
                            name: 'ComponentsOfOrg',
                            params: {
                                orguuid: updatedInstance.value.org,
                                compuuid: row.componentUuid
                            }
                        }
                    },
                    { default: () => row.component }
                )
            }else {
                el = row.component
            }
            
            return el
        }
    },
    {
        key: 'version',
        title: 'Version',
        render: (row: any) => {
            return h('span', [
                h('a', {
                    onClick: (e: Event) => {
                        e.preventDefault()
                        selectedReleaseIdForModal.value = row.release.uuid
                        showReleaseViewModal.value = true
                    },
                    href: '#'
                },
                [h('span', row.release.version)]
                )

            ])
        }
    },
    {
        key: 'namespace',
        title: 'Namespace',
        render: (row: any) => row.namespace
    },
    {
        key: 'deliverable',
        title: 'Deliverable',
        render: (row: any) => {
            let commit
            if (row.release.sourceCodeEntryDetails) {
                commit = linkifyCommit(row.release.sourceCodeEntryDetails.vcsRepository.uri, row.release.sourceCodeEntryDetails.commit)
            }
            let tooltipTrigger: any
            let newTabLink: any
            if(commit){
                tooltipTrigger = h('a', {
                    href: commit,
                    target: '_blank',
                    rel: 'noopener noreferrer',
                }, [h(NIcon, {
                    size: 24,
                }, {
                    default: () => h(Commit)
                })]
                )
                newTabLink = h('a', {
                    href: commit,
                    rel: 'noopener noreferrer',
                    target: '_blank'
                },
                [
                    h(
                        NIcon,  
                        {
                            title: 'Open Commit In New Tab',
                            class: 'icons',
                            size: 24
                        }, 
                        { default: () => h(ExternalLink) }
                    )
                ])
            }
            
            const tooltipDefault: any = h('span', [
                commit,
                newTabLink
            ])
            let els = []

            els.push( h(NTooltip, {
                trigger: 'hover'
            }, {
                trigger: () => tooltipTrigger,
                default: () => tooltipDefault
            }))
            return els
        }
    },
    {
        key: 'controls',
        title: '',
        render: (row: any) => {
            if(isWritable){
                return h(NIcon, {
                    title: 'Remove Release',
                    class: 'icons clickable',
                    size: 24,
                    onClick: () => removeRelease(row.originalReleaseId, row.namespace)
                }, {
                    default: () => h(Trash)
                })
            }
        }
    },
    
]
// Strip the trailing @<algo>:<hex> digest suffix from a container ref so
// the Image column shows just the registry/repo[:tag] portion. Handles
// the variety k8s reports: 'registry/repo@sha256:hex',
// 'registry/repo:tag@sha256:hex', 'registry/repo:tag' (no digest),
// 'sha256:hex' (bare digest fallback when imageID is absent), and null.
function coreImageRef(image: string | null | undefined): string {
    if (!image) return '—'
    const atIdx = image.indexOf('@')
    const core = atIdx > 0 ? image.substring(0, atIdx) : image
    // Bare 'sha256:hex' has no '@' but is itself the digest — keep
    // it as-is rather than emitting an empty string; the digest
    // tooltip will still cover the same info.
    return core || image
}

const unmatchedImageFields: any[] = [
    {
        key: 'image',
        title: 'Image',
        render: (row: any) => {
            const els: any[] = [coreImageRef(row.image)]
            // Build a tooltip body listing every algo:hex digest record.
            // Falls back to parsing the @-suffix off the image ref (or the
            // bare 'sha256:hex' image) when the server didn't include a
            // record — e.g. older data before the DigestRecord migration.
            const records: { algo?: string, digest?: string }[] = Array.isArray(row.digestRecords) ? row.digestRecords : []
            let tooltipLines: string[] = records
                .filter(r => r && r.digest)
                .map(r => `${r.algo || 'unknown'}:${r.digest}`)
            if (tooltipLines.length === 0) {
                const atIdx = (row.image || '').indexOf('@')
                if (atIdx > 0) tooltipLines = [row.image.substring(atIdx + 1)]
                else if (row.image && row.image.startsWith('sha256:')) tooltipLines = [row.image]
            }
            if (tooltipLines.length) {
                const copyValue = tooltipLines.join('\n')
                els.push(h(NTooltip, { trigger: 'hover', placement: 'top' }, {
                    trigger: () => h(NIcon, {
                        size: 16,
                        class: 'ml-1 clickable',
                        title: 'Copy digest',
                        onClick: () => { try { navigator.clipboard.writeText(copyValue) } catch {} }
                    }, { default: () => h(InfoCircle) }),
                    default: () => tooltipLines.join(', ')
                }))
            }
            return h('span', { style: 'word-break: break-all;' }, els)
        }
    },
    { key: 'namespace', title: 'Namespace', render: (row: any) => row.namespace || '—' },
    { key: 'pod', title: 'Pod', render: (row: any) => row.pod || '—' },
    {
        key: 'state',
        title: 'State',
        // Keep state on one line — without this, the n-data-table column
        // can shrink narrow enough to wrap "RUNNING" as "RUNNI / NG".
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, row.state || 'UNSET')
    },
    {
        key: 'lastSeen',
        title: 'Last Seen',
        render: (row: any) => row.lastSeen ? (new Date(row.lastSeen)).toLocaleString('en-CA') : '—'
    }
]
const deploymentFailureFields: any[] = [
    {
        key: 'deploymentName',
        title: 'Deployment',
        render: (row: any) => h('span', { style: 'word-break: break-all;' }, row.deploymentName || '—')
    },
    { key: 'namespace', title: 'Namespace', render: (row: any) => row.namespace || '—' },
    {
        key: 'phase',
        title: 'Phase',
        render: (row: any) => h('span', { style: 'white-space: nowrap;' }, row.phase || 'UNKNOWN')
    },
    {
        key: 'failureClass',
        title: 'Cause',
        render: (row: any) => h(NTag, { type: 'error', size: 'small', round: true },
            () => (row.failureClass || 'UNKNOWN').replaceAll('_', ' '))
    },
    {
        key: 'message',
        title: 'Message',
        render: (row: any) => {
            const msg = row.message || '—'
            const els: any[] = [h('span', { style: 'word-break: break-word;' }, msg)]
            // Detail is redacted + truncated agent-side; show it on demand
            // rather than inline so a long helm dump can't swamp the table.
            if (row.detail) {
                els.push(h(NTooltip, { trigger: 'hover', placement: 'top', style: { maxWidth: '640px' } }, {
                    trigger: () => h(NIcon, { size: 16, class: 'ml-1 clickable', title: 'Show detail' },
                        { default: () => h(InfoCircle) }),
                    default: () => h('pre', {
                        style: 'white-space: pre-wrap; word-break: break-all; margin: 0; max-height: 320px; overflow: auto;'
                    }, row.detail)
                }))
            }
            return h('span', els)
        }
    },
    {
        key: 'firstSeen',
        title: 'First Seen',
        render: (row: any) => row.firstSeen
            ? h('span', { title: relativeFromNow(row.firstSeen), style: 'white-space: nowrap;' },
                (new Date(row.firstSeen)).toLocaleString('en-CA'))
            : '—'
    },
    {
        key: 'lastSeen',
        title: 'Last Seen',
        render: (row: any) => row.lastSeen
            ? h('span', { title: relativeFromNow(row.lastSeen), style: 'white-space: nowrap;' },
                `${(new Date(row.lastSeen)).toLocaleString('en-CA')} (${relativeFromNow(row.lastSeen)})`)
            : '—'
    },
    {
        key: 'occurrenceCount',
        title: 'Occurrences',
        render: (row: any) => row.occurrenceCount ?? 1
    }
]
const deployedReleaseFeilds: any[] = [
    {
        key: 'component',
        title: 'Component',
        render: (row: any) => {
            let el
            if (row.componentType === 'COMPONENT') {
                el = h(
                    RouterLink,
                    {
                        to: {
                            name: 'ComponentsOfOrg',
                            params: {
                                orguuid: updatedInstance.value.org,
                                compuuid: row.componentUuid
                            }
                        }
                    },
                    { default: () => row.component }
                )
            }else {
                el = row.component
            }
            
            return el
        }
    },
    {
        key: 'version',
        title: 'Version',
        render: (row: any) => {
            return h('span', [
                h('a', {
                    onClick: (e: Event) => {
                        e.preventDefault()
                        selectedReleaseIdForModal.value = row.release.uuid
                        showReleaseViewModal.value = true
                    },
                    href: '#'
                },
                [h('span', row.release.version)]
                )

            ])
        }
    },
    {
        key: 'namespace',
        title: 'Namespace',
        render: (row: any) => row.namespace
    },
    {
        key: 'state',
        title: 'State',
        render: (row: any) => row.state
    },
    {
        key: 'deliverable',
        title: 'Deliverable',
        render: (row: any) => {
            let els = []
            if (row.release.sourceCodeEntryDetails) {
                const commit = linkifyCommit(row.release.sourceCodeEntryDetails.vcsRepository.uri, row.release.sourceCodeEntryDetails.commit)                        
                els.push(
                    h('a', {
                        href: commit,
                        target: '_blank',
                        rel: 'noopener noreferrer',
                    }, h(NTooltip, 
                        {
                            trigger: 'hover'
                        }, {
                            trigger: () => h(NIcon, {
                                class: 'icons clickable',
                                size: 24,
                            },{
                                default: () => h(Commit)
                            }),
                            default: () => commit
                        })
                    ))
            }
            return h('span', {}, els)
        }
    }
    
]
const instPropFeilds: any[] = [
    {
        key: 'prop_key',
        title: 'Property Key',
        render: (row: any) => row.property ? row.property.name : ''
    },
    {
        key: 'data_type',
        title: 'Data Type',
        render: (row: any) => row.property ? row.property.dataType : ''
    },
    {
        key: 'namespace',
        title: 'Namespace',
        render: (row: any) => row.namespace
    },
    {
        key: 'product',
        title: 'Product',
        render: (row: any) => row.productDetails && row.productDetails.name ? row.productDetails.name : ''
    },
    {
        key: 'value',
        title: 'Value',
        render: (row: any) => h(NEllipsis, {
            style: 'max-width: 240px'
        }, {default: ()=> h('span', row.value)})
    },
    {
        key: 'controls',
        title: '',
        render: (row: any) => {
            let els: any = []
            if(isWritable){
                els.push(h(NIcon, {
                    title: 'Edit Property Value',
                    class: 'clickable icons',
                    size: 16,
                    onClick: () => {
                        focusedProperty.value = row
                        updatedPropValue.value = focusedProperty.value.value
                        showEditPropertyModal.value = true
                    }
                },{default: () => h(Edit24Regular)}))
                els.push(h(NIcon, {
                    title: 'Delete Property',
                    class: 'clickable icons',
                    size: 16,
                    onClick: () => deleteProperty(row.uuid, row.namespace, row.product)
                },{default: () => h(Trash)}))
            }
            return els
        }
    }
    
]
if(props.instanceType !== InstanceType.STANDALONE_INSTANCE){
    targetReleaseFeilds.splice(2,1)
    deployedReleaseFeilds.splice(2,1)
    instPropFeilds.splice(2,1)
}
if(props.instanceType === InstanceType.CLUSTER){
    instPropFeilds.splice(2,1)
}
const instCreated = async function (inst: any, instanceType: String) {
    console.log('instCreated', inst)
    await store.dispatch('fetchInstances', updatedInstance.value.org)
    showCreateInstanceModal.value = false
    if(inst && instanceType === InstanceType.CLUSTER)
        notify('info', 'Created', `Cluster ${inst.name} created`)
    else
        notify('info', 'Created', `Instance ${inst.uri} created`)
    await onCreate()
}

const childInstanceFields: any[] = [
    {
        type: 'selection',
        multiple: false,
    },
    {
        key: 'uri',
        title: 'URI'
    },
    {
        key: 'namespace',
        title: 'Namespace'
    },
    {
        key: 'environment',
        title: 'ENV'
    },
]

const childInstrowKey = (row: any) => row.uuid
const handleChildInstSelect: any =  (rowKeys: any[]) => {
    selectedChildInstRowKey.value = rowKeys
    showChildInstance.value = true
    selectChildInstance(rowKeys[0])
}
const selectedChildInstRowKey: Ref<any[]> = ref([])
const handleChildInstanceClose: any = () => {
    console.log('child instance closed')
    showChildInstance.value = false
    selectedChildInstRowKey.value = []
    router.push({
        name: 'Instance',
        params: {
            orguuid: orguuid.value,
            instuuid: route.params.instuuid.toString()
        }
    })
}
const selectChildInstance = function (uuid: string) {
    router.push({
        name: 'Instance',
        params: {
            orguuid: orguuid.value,
            instuuid: route.params.instuuid.toString(),
            subinstuuid: uuid,
        }
    })
}

const showChildInstance: Ref<boolean> = ref(false)
await onCreate()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
/* ---- sub-tab bar — mirrors OrgIntegrations.vue ---- */
.subtab-bar {
    display: inline-flex;
    gap: 4px;
    background: #F3F5F4;
    padding: 4px;
    border-radius: 10px;
    margin: 12px 0 18px;
}
.subtab-pill {
    display: inline-flex; align-items: center; gap: 8px;
    padding: 6px 14px;
    border: none; background: transparent;
    border-radius: 7px;
    font-size: 13px; font-weight: 500;
    color: #5b6770; cursor: pointer;
    transition: background .12s, color .12s;
}
.subtab-pill:hover { color: #2b3540; }
.subtab-pill.active {
    background: #FFFFFF;
    color: #2b3540;
    box-shadow: 0 1px 2px rgba(16,24,40,.04);
    font-weight: 600;
}
.subtab-icon { display: inline-flex; }
.subtab-count {
    font-size: 11px; font-weight: 600;
    padding: 2px 8px;
    border-radius: 999px;
}
.subtab-count-error {
    background: #fde8e8; color: #b42318;
    border: 1px solid #f5c2c2;
}

/* ---- History tab content ---- */
.historyTab {
    margin-top: 8px;
}
.historyFilters {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-bottom: 12px;
}
.historyFilterRow {
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    gap: 16px;
}
.historyFilterItem {
    display: inline-flex;
    align-items: center;
}
.historyLoading, .historyEmpty {
    padding: 16px 0;
    color: #7a8590;
    font-style: italic;
}

.settingsBox {
    margin-bottom: 10px;
    width: 90%;
    .settingsValue {
        display: inline-block;
        width: 80%;
    }
    h6 {
        width: 90px;
        display: inline-block;
        margin-right: 10px;
    }
}
.uriBlock {
    display: inline-flex;
    h6 {
        margin-top: 10px;
    }
}
.icons {
    margin-left: 10px;
}
.releaseList {
    display: grid;
    grid-template-columns: 1fr 1fr 0.45fr 185px 95px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
    button {
        margin-right: 10px;
    }
    .releaseDetails {
        grid-column: 1/5;
        overflow-wrap: break-word;
        .releaseDetailContent div {
           border: none;
        }
        .editReleaseIcon {
            float: right;
        }
        .releaseDetailsInnerHeader {
            margin-top: 7px;
            font-weight: bold;
        }
    }
}

.targetReleaseList {
    display: grid;
    grid-template-columns: 1fr 1fr 0.45fr 95px 55px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
    button {
        margin-right: 10px;
    }
    .releaseDetails {
        grid-column: 1/5;
        overflow-wrap: break-word;
        .releaseDetailContent div {
           border: none;
        }
        .editReleaseIcon {
            float: right;
        }
        .releaseDetailsInnerHeader {
            margin-top: 7px;
            font-weight: bold;
        }
    }
}

.propertyList {
    display: grid;
    grid-template-columns: 0.6fr 95px 135px 140px 1fr 60px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.historyList {
    display: grid;
    grid-template-columns: 100px repeat(3, 1fr) 55px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.instanceChangeSearchGroupWrapper {
    display: grid;
    grid-template-columns: 0.9fr 1fr;
    .dateInput {
        max-width: 320px;
    }
    .timeInput {
        max-width: 130px;
    }
    label {
        width: 50px;
    }
}
.productList {
    display: grid;
    grid-template-columns: 1fr 0.7fr 1fr 1fr 0.7fr 100px 100px 50px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.productListI {
    display: grid;
    grid-template-columns: 1fr 0.7fr repeat(2, 1fr) 120px 30px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.releaseList:hover, .propertyList:hover, .historyList:hover, .productList:hover, .targetReleaseList:hover {
    background-color: #d9eef3;
}
.listHeaderText, .releaseHeader, .propertyHeader, .historyHeader, .productHeader {
    border-radius: 9px;
    background-color: #f9dddd;
    font-weight: bold;
}

.listHeaderText {
    margin-top: 0.7rem;
    text-align: center;
}
.textBox {
  width: fit-content;
  margin-bottom: 10px;
}

.mainControls {
    display: grid;
    word-wrap: break-word;
    word-break: break-word;
    white-space: pre-wrap;
}

.instanceControls {
    display: grid;
    grid-template-columns: 1fr 30px;
}
.featureSetSelection {
    display: flex;
}

.editor {
    background: #fffefe;
    color: #3a3838;
    font-family: Fira code, Fira Mono, Consolas, Menlo, Courier, monospace;
    font-size: 14px;
    line-height: 1.5;
    padding: 5px;
  }
.agentDataEditor {
    max-height: 70vh;
    overflow: auto;
}
/* prism-tomorrow is tuned for dark backgrounds; on the white editor its
   token colours wash out. Darker, higher-contrast JSON tokens for the
   read-only agent-data viewer (matches the agentic-report viewer). */
.agentDataEditor :deep(.token) { text-shadow: none; }
.agentDataEditor :deep(.token.property) { color: #0550ae; }
.agentDataEditor :deep(.token.string) { color: #0a6e2e; }
.agentDataEditor :deep(.token.number) { color: #8250df; }
.agentDataEditor :deep(.token.boolean),
.agentDataEditor :deep(.token.null),
.agentDataEditor :deep(.token.keyword) { color: #953800; }
.agentDataEditor :deep(.token.punctuation),
.agentDataEditor :deep(.token.operator) { color: #57606a; }
.dangerZone {
    margin-top: 32px;
    padding-top: 16px;
    border-top: 2px solid #e74c3c;
}
.dangerZoneHeader {
    color: #e74c3c;
    margin: 0 0 8px 0;
}
.dangerZoneCopy {
    color: var(--n-text-color-3, #666);
    font-size: 13px;
    margin-bottom: 12px;
}
</style>