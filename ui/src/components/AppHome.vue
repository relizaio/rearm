<template>
    <div class="home">
        <!-- Findings Per Day Display -->
        <div v-if="showFindingsPerDay" class="findingsPerDayBlock">
            <h2>Findings for {{ findingsPerDayDate }}
                <n-icon class="clickable" size="25" title="Recalculate Findings" @click="recalculateFindingsForDate" :component="Refresh" style="margin-left: 10px; vertical-align: middle;" />
            </h2>
            <n-spin :show="findingsPerDayLoading">
                <n-data-table
                    :columns="findingsPerDayColumns"
                    :data="findingsPerDayData"
                    :row-key="(row: any) => row.type + '-' + row.id + '-' + row.purl"
                    :pagination="{ pageSize: 20 }"
                />
            </n-spin>
        </div>
        <div v-else class="dashboardBlock">
            <n-grid x-gap="24" cols="2">
                <n-gi>
                    <div class="charts">
                        <div id="releaseCreationVisHome"></div>
                    </div>
                </n-gi>
                <n-gi>
                    <div>
                        <n-input-number style="display:inline-block; width:80px;" 
                            v-model:value="activeComponentsInput.maxComponents"
                            :on-update:value="(value: number|null) => {activeComponentsInput.maxComponents = value ? value : 3; fetchActiveComponentsBranchesAnalytics();}" />
                        <span>Most Active </span>
                        <n-dropdown title="Select Type" trigger="hover"
                            :options="[{label: 'Components', key: 'COMPONENT'}, {label: 'Products', key: 'PRODUCT'}, {label: 'Branches', key: 'BRANCH'}, {label: 'Feature Sets', key: 'FEATURE_SET'}]"
                            @select="$key => {activeComponentsInput.componentType = $key ? $key: 'COMPONENT'; fetchActiveComponentsBranchesAnalytics();}">
                            <span>
                                <span>{{ displayActiveComponentType() }}</span>
                                <Icon><CaretDownFilled/></Icon>
                            </span>
                        </n-dropdown>
                        <span>since </span>
                        <n-date-picker style="display:inline-block; width:130px;"
                            v-model:value="activeComponentsInput.cutOffDate"
                            :on-update:show="fetchActiveComponentsBranchesAnalytics"/>
                    </div>
                    <div class="charts">
                        <div id="mostActiveVisHome"></div>
                    </div>
                </n-gi>
                <n-gi span="2"><n-divider /></n-gi>
                <n-gi>
                    <div class="charts">
                        <div id="analyticsMetricsVisHome"></div>
                    </div>
                </n-gi>
                <n-gi>
                    <div class="searchBlock">
                        <h3>Search Releases</h3>
                        <div class="searchUnit artifactSearch">
                            <n-tabs
                            class="card-tabs"
                            default-value="searchreleasesbytext"
                            size="large"
                            animated
                            style="margin: 0 -4px"
                            pane-style="padding-left: 4px; padding-right: 4px; box-sizing: border-box;"
                            >
                                <n-tab-pane name="searchreleasesbytext" tab="Search by Version, Digest, Commit">
                                    <h5>Search For Releases By Digest, Version, Commit, Git Tag</h5>
                                    <n-form
                                        inline
                                        @submit="searchHashVersion">
                                        <n-input-group>
                                            <n-input
                                                placeholder="Search by digest, version, commit, tag, build ID"
                                                v-model:value="hashSearchQuery"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                attr-type="submit">
                                                Find
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane name="searchreleasesbytags" tab="Search by Tags">
                                    <h5>Search For Releases By Reliza Tags</h5>
                                    <n-form
                                        inline
                                        @submit="searchReleasesByTags">
                                        <n-input-group>
                                            <n-select :options="releaseTagKeys" v-model:value="releaseKeySearchObj.key">
                                            </n-select>
                                            <n-input
                                                placeholder="Release Tag Value (Optional)"
                                                v-model:value="releaseKeySearchObj.value"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                attr-type="submit">
                                                Search
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane name="searchreleasesbydsbom" tab="Search by SBOM Components">
                                    <h5>Search For Releases By SBOM Component Name, Group or Purl</h5>
                                    <n-radio-group v-model:value="sbomSearchMode" style="margin-bottom: 10px;">
                                        <n-radio-button value="simple">Simple</n-radio-button>
                                        <n-radio-button value="json">Batch</n-radio-button>
                                    </n-radio-group>
                                    <n-tooltip trigger="hover" style="max-width: 500px;">
                                        <template #trigger>
                                            <n-icon size="18" style="cursor: pointer; vertical-align: middle; margin-left: 5px;">
                                                <QuestionMark />
                                            </n-icon>
                                        </template>
                                        <div>
                                            <p>{{ constants.BatchModeHelp.description }}</p>
                                            <p>{{ constants.BatchModeHelp.formatInfo }}</p>
                                            <p><strong>Examples:</strong></p>
                                            <p style="font-style: italic;">Plain text: {{ constants.BatchModeHelp.examplePlain }}</p>
                                            <p style="font-style: italic;">JSON: {{ constants.BatchModeHelp.exampleJson }}</p>
                                        </div>
                                    </n-tooltip>
                                    <n-form
                                        v-if="sbomSearchMode === 'simple'"
                                        inline
                                        @submit="searchSbomComponent">
                                        <n-input-group>
                                            <n-input
                                                placeholder="SBOM Component Name or Purl"
                                                v-model:value="sbomSearchQuery"
                                            />
                                            <n-input
                                                placeholder="Version (Optional)"
                                                v-model:value="sbomSearchVersion"
                                                style="max-width: 150px;"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                attr-type="submit">
                                                Find
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                    <n-form
                                        v-else
                                        @submit="searchSbomComponent">
                                        <n-input
                                            type="textarea"
                                            placeholder="@posthog/clickhouse&#10;lodash&#9;4.17.21&#10;express"
                                            v-model:value="sbomSearchJson"
                                            :rows="3"
                                            style="margin-bottom: 10px;"
                                        />
                                        <n-button
                                            variant="contained-text"
                                            attr-type="submit">
                                            Find
                                        </n-button>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane v-if="false && installationType !== 'OSS'" name="searchinstancesbytags" tab="Search Instances by Tags">
                                    <h5 class="mt-4">Search For Instances By Properties</h5>
                                    <n-form
                                        inline
                                        @submit="searchInstProp"
                                        label-placement="left"
                                    >
                                        <n-input-group>
                                            <n-select :options="properties" v-model:value="instancePropsSearchObj.key">
                                            </n-select>
                                            <n-input
                                                placeholder="Search For Property Value"
                                                v-model:value="releaseKeySearchObj.value"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                @click="searchInstProp">
                                                Search
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane v-if="false && installationType !== 'OSS'" name="searchinstancechangesbydate" tab="Search Instance Changes by Date">
                                    <h5 class="mt-4">Search For Instance Changes By Date</h5>
                                    <n-form label-placement="left" inline>
                                        <n-input-group>
                                            <n-form-item label="From">
                                                <n-date-picker v-model:value="instanceChangeSearchObj.dateFrom" type="datetime" />
                                                
                                            </n-form-item>
                                        </n-input-group>
                                        <n-input-group>
                                            <n-form-item label="To">
                                                <n-date-picker v-model:value="instanceChangeSearchObj.dateTo" type="datetime" />
                                            
                                            </n-form-item>
                                        </n-input-group>
                                        <n-button
                                                variant="contained-text"
                                                @click="searchInstanceChanges">
                                                Search
                                            </n-button>
                                    </n-form>
                                </n-tab-pane>
                            </n-tabs>
                        </div>

                        <n-modal
                            v-model:show="showSearchResultsModal"
                            preset="dialog"
                            :show-icon="false"
                            style="width: 90%"
                        >
                            <div style="height: 710px; overflow: auto;">
                                <h3>{{ searchResultsModalMode === 'hash' ? 'Search by Version, Digest, Commit' : 'Search by Tags' }}</h3>
                                <n-form
                                    v-if="searchResultsModalMode === 'hash'"
                                    style="margin-bottom:20px;"
                                    inline
                                    @submit="searchHashVersion">
                                    <n-input-group>
                                        <n-input
                                            placeholder="Search by digest, version, commit, tag, build ID"
                                            v-model:value="hashSearchQuery"
                                        />
                                        <n-button
                                            variant="contained-text"
                                            attr-type="submit">
                                            Find
                                        </n-button>
                                    </n-input-group>
                                </n-form>
                                <n-form
                                    v-else
                                    style="margin-bottom:20px;"
                                    inline
                                    @submit="searchReleasesByTags">
                                    <n-input-group>
                                        <n-select :options="releaseTagKeys" v-model:value="releaseKeySearchObj.key">
                                        </n-select>
                                        <n-input
                                            placeholder="Release Tag Value (Optional)"
                                            v-model:value="releaseKeySearchObj.value"
                                        />
                                        <n-button
                                            variant="contained-text"
                                            attr-type="submit">
                                            Search
                                        </n-button>
                                    </n-input-group>
                                </n-form>
                                <div class="searchResults">
                                    <h4>Releases:</h4>
                                    <n-data-table
                                        :data="hashSearchResults.commitReleases || []"
                                        :columns="releaseSearchResultRows"
                                        :pagination="pagination"
                                    />

                                    <div v-if="releaseInstances && releaseInstances.length">
                                        <h4>Deployed on Instances:</h4>
                                        <ul>
                                            <li v-for="id in releaseInstances" :key="id.uuid">
                                                <router-link :to="{ name: 'Instance', params: {orguuid: myorg.uuid, instuuid: id.uuid }}">{{ id.uri }}</router-link>
                                            </li>
                                        </ul>
                                    </div>
                                </div>
                            </div>
                        </n-modal>
                        <n-modal
                            v-model:show="showDtrackSearchResultsModal"
                            preset="dialog"
                            :show-icon="false"
                            style="width: 90%"
                        >
                            <div style="height: 710px; overflow: auto;">
                                <h3 style="margin-top:0px;">Search by SBOM Components</h3>
                                <n-radio-group v-model:value="sbomSearchMode" style="margin-bottom: 10px;">
                                    <n-radio-button value="simple">Simple</n-radio-button>
                                    <n-radio-button value="json">Batch</n-radio-button>
                                </n-radio-group>
                                <n-tooltip trigger="hover" style="max-width: 500px;">
                                    <template #trigger>
                                        <n-icon size="18" style="cursor: pointer; vertical-align: middle; margin-left: 5px;">
                                            <QuestionMark />
                                        </n-icon>
                                    </template>
                                    <div>
                                        <p>{{ constants.BatchModeHelp.description }}</p>
                                        <p>{{ constants.BatchModeHelp.formatInfo }}</p>
                                        <p><strong>Examples:</strong></p>
                                        <p style="font-style: italic;">Plain text: {{ constants.BatchModeHelp.examplePlain }}</p>
                                        <p style="font-style: italic;">JSON: {{ constants.BatchModeHelp.exampleJson }}</p>
                                    </div>
                                </n-tooltip>
                                <n-form
                                    v-if="sbomSearchMode === 'simple'"
                                    style="margin-bottom:1px;"
                                    inline
                                    @submit="searchSbomComponent">
                                    <n-input-group>
                                        <n-input
                                            placeholder="SBOM Component Name or Purl"
                                            v-model:value="sbomSearchQuery"
                                        />
                                        <n-input
                                            placeholder="Version (Optional)"
                                            v-model:value="sbomSearchVersion"
                                            style="max-width: 150px;"
                                        />
                                        <n-button
                                            variant="contained-text"
                                            attr-type="submit">
                                            Find
                                        </n-button>
                                    </n-input-group>
                                </n-form>
                                <n-form
                                    v-else
                                    style="margin-bottom:5px;"
                                    @submit="searchSbomComponent">
                                    <n-input
                                        type="textarea"
                                        placeholder="@posthog/clickhouse&#10;lodash&#9;4.17.21&#10;express"
                                        v-model:value="sbomSearchJson"
                                        :rows="3"
                                        style="margin-bottom: 5px;"
                                    />
                                    <n-button
                                        variant="contained-text"
                                        attr-type="submit">
                                        Find
                                    </n-button>
                                </n-form>
                                <div v-if="showSearchProgress" style="margin-bottom: 5px;">
                                    <p style="margin-bottom: 5px;">
                                        {{ sbomSearchMode === 'simple' ? `"${sbomSearchQuery}"` : 'Batch' }}
                                        {{ searchFailed ? 'search failed!' : `search progress: ${searchProgress}%` }}
                                    </p>
                                    <n-progress
                                        type="line"
                                        :percentage="searchProgress"
                                        :show-indicator="true"
                                        :status="searchFailed ? 'error' : 'success'"
                                    />
                                </div>
                                <n-grid x-gap="3" cols="2">
                                    <n-gi>
                                        <n-data-table
                                            :data="dtrackSearchResults"
                                            :columns="dtrackSearchResultRows"
                                            :pagination="pagination"
                                            :loading="dtrackSearchLoading"
                                        />
                                    </n-gi>
                                    <n-gi>
                                        <n-data-table
                                            :data="dtrackSearchReleases"
                                            :columns="dtrackSearchReleaseRows"
                                            :pagination="releasePagination"
                                            :loading="dtrackReleasesLoading"
                                        />
                                    </n-gi>
                                </n-grid>
                            </div>
                        </n-modal>
                        <n-modal
                                v-model:show="showInstPropSearchResultsModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                                title="Search for Instances By Properties"
                                :hide-footer="true"
                                >
                                <n-form
                                    inline
                                    @submit="searchInstProp"
                                    label-placement="left"
                                >
                                    <n-input-group>
                                        <n-select :options="properties" v-model:value="instancePropsSearchObj.key">
                                        </n-select>
                                        <n-input
                                            placeholder="Search For Property Value"
                                            v-model:value="releaseKeySearchObj.value"
                                        />
                                        <n-button
                                            variant="contained-text"
                                            @click="searchInstProp">
                                            Search
                                        </n-button>
                                    </n-input-group>
                                </n-form>
                                <div class="searchResults">
                                    <h5>Search Results:</h5>
                                    <div v-if="instPropsSearchResults.length">
                                        <ul>
                                            <li v-for="inst in instPropsSearchResults" :key="inst.uuid">
                                                <router-link :to="{ name: 'Instance', params: {orguuid: myorg.uuid, instuuid: inst.uuid }}">{{ inst.uri }}</router-link> - {{ inst.environment }}
                                            </li>
                                        </ul>
                                    </div>
                                    <div v-else>No results.</div>
                                </div>
                        </n-modal>
                        <n-modal
                            v-model:show="showInstChangeSearchResultsModal"
                            preset="dialog"
                            :show-icon="false"
                            style="width: 90%"
                            title="Search For Instance Changes By Date"
                            :hide-footer="true"
                            >
                            <n-form label-placement="left" inline>
                                <n-input-group>
                                    <n-form-item label="From">
                                        <n-date-picker v-model:value="instanceChangeSearchObj.dateFrom" type="datetime" />
                                        
                                    </n-form-item>
                                </n-input-group>
                                <n-input-group>
                                    <n-form-item label="To">
                                        <n-date-picker v-model:value="instanceChangeSearchObj.dateTo" type="datetime" />
                                        
                                    </n-form-item>
                                </n-input-group>
                                <n-button
                                        variant="contained-text"
                                        @click="searchInstanceChanges">
                                        Search
                                    </n-button>
                            </n-form>
                            <div v-if="instChangeSearchResults.length" class="instanceChangeSearchResults">
                                <h5>Search Results:</h5>
                                <div v-for="ir in instChangeSearchResults" :key="ir.instanceData.uuid">
                                    <h5><router-link :to="{ name: 'Instance', params: {orguuid: myorg.uuid, instuuid: ir.instanceData.uuid }}">{{ ir.instanceData.uri }}:</router-link></h5>
                                    <instance-history
                                        :instanceUuid="ir.instanceData.uuid"
                                        :history="ir.instanceHistory"
                                        :orgProp="myorg.uuid"
                                    />
                                </div>
                            </div>
                            <div v-else>No results.</div>
                        </n-modal>
                    </div>
                </n-gi>
                <n-gi>
                </n-gi>
            </n-grid>

        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'AppHome'
}
</script>
<script lang="ts" setup>
import { NTabs, NTabPane, NInputGroup, NInput, NInputNumber, NButton, NDropdown, NForm, NModal, NDataTable, NSelect, NFormItem, NDatePicker, NTooltip, DataTableColumns, NIcon, NGrid, NGi, NDivider, NRadioButton, NRadioGroup, NProgress, NSpin, NTag, useNotification, NotificationType } from 'naive-ui'
import { useStore } from 'vuex'
import { ComputedRef, h, computed, ref, Ref, onMounted, watch, toRaw } from 'vue'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import GqlQueries from '../utils/graphqlQueries'
import InstanceHistory from './InstanceHistory.vue'
import { RouterLink } from 'vue-router'
import axios from '../utils/axios'
import { Commit } from '@vicons/carbon'
import { AspectRatio, Box, Eye, QuestionMark, Refresh } from '@vicons/tabler'
import { CaretDownFilled } from '@vicons/antd'
import { Icon } from '@vicons/utils'
import { useRouter, useRoute } from 'vue-router'
import * as vegaEmbed from 'vega-embed'
import Swal from 'sweetalert2'
import commonFunctions from '@/utils/commonFunctions'
import constants from '@/utils/constants'
import { processMetricsData, buildVulnerabilityColumns } from '@/utils/metrics'

const store = useStore()
const router = useRouter()
const route = useRoute()
const notification = useNotification()

const notify = (type: NotificationType, title: string, content: string) => {
    notification[type]({
        content: title,
        meta: content,
        duration: 3000,
        keepAliveOnHover: true
    })
}

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const installationType: ComputedRef<any> = computed((): any => store.getters.myuser.installationType)

const hashSearchQuery = ref('')
const sbomSearchQuery = ref('')
const sbomSearchVersion = ref('')
const sbomSearchMode = ref('simple')
const sbomSearchJson = ref('')
const searchProgress = ref(0)
const showSearchProgress = ref(false)
const searchFailed = ref(false)
const selectedPurl = ref('')

// Findings per day display
const showFindingsPerDay = computed(() => route.query.display === 'findingsPerDay' && route.query.date)
const findingsPerDayDate = computed(() => route.query.date as string || '')
const findingsPerDayData: Ref<any[]> = ref([])
const findingsPerDayLoading: Ref<boolean> = ref(false)
const findingsPerDayColumns: DataTableColumns<any> = buildVulnerabilityColumns(h, NTag, NTooltip, NIcon, RouterLink)

async function fetchFindingsPerDay() {
    if (!showFindingsPerDay.value || !myorg.value?.uuid) return
    
    findingsPerDayLoading.value = true
    try {
        const response = await graphqlClient.query({
            query: gql`
                query findingsPerDay($orgUuid: ID!, $date: String!) {
                    findingsPerDay(orgUuid: $orgUuid, date: $date) {
                        vulnerabilityDetails {
                            purl
                            vulnId
                            severity
                            analysisState
                            analysisDate
                            aliases {
                                type
                                aliasId
                            }
                            sources {
                                artifact
                                release
                                variant
                                releaseDetails {
                                    version
                                    componentDetails {
                                        name
                                    }
                                }
                                artifactDetails {
                                    type
                                }
                            }
                            severities {
                                source
                                severity
                            }
                        }
                        violationDetails {
                            purl
                            type
                            license
                            violationDetails
                            analysisState
                            analysisDate
                            sources {
                                artifact
                                release
                                variant
                                releaseDetails {
                                    version
                                    componentDetails {
                                        name
                                    }
                                }
                                artifactDetails {
                                    type
                                }
                            }
                        }
                        weaknessDetails {
                            cweId
                            ruleId
                            location
                            fingerprint
                            severity
                            analysisState
                            analysisDate
                            sources {
                                artifact
                                release
                                variant
                                releaseDetails {
                                    version
                                    componentDetails {
                                        name
                                    }
                                }
                                artifactDetails {
                                    type
                                }
                            }
                        }
                    }
                }
            `,
            variables: {
                orgUuid: myorg.value.uuid,
                date: findingsPerDayDate.value
            },
            fetchPolicy: 'no-cache'
        })
        
        if (response.data.findingsPerDay) {
            findingsPerDayData.value = processMetricsData(response.data.findingsPerDay)
        }
    } catch (error) {
        console.error('Error fetching findings per day:', error)
    } finally {
        findingsPerDayLoading.value = false
    }
}

async function recalculateFindingsForDate() {
    if (!myorg.value?.uuid || !findingsPerDayDate.value) return
    
    findingsPerDayLoading.value = true
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation computeAnalyticsMetricsForDate($orgUuid: ID!, $date: String!) {
                    computeAnalyticsMetricsForDate(orgUuid: $orgUuid, date: $date) {
                        vulnerabilityDetails {
                            purl
                            vulnId
                            severity
                            analysisState
                            analysisDate
                            aliases {
                                type
                                aliasId
                            }
                            sources {
                                artifact
                                release
                                variant
                                releaseDetails {
                                    version
                                    componentDetails {
                                        name
                                    }
                                }
                                artifactDetails {
                                    type
                                }
                            }
                            severities {
                                source
                                severity
                            }
                        }
                        violationDetails {
                            purl
                            type
                            license
                            violationDetails
                            analysisState
                            analysisDate
                            sources {
                                artifact
                                release
                                variant
                                releaseDetails {
                                    version
                                    componentDetails {
                                        name
                                    }
                                }
                                artifactDetails {
                                    type
                                }
                            }
                        }
                        weaknessDetails {
                            cweId
                            ruleId
                            location
                            fingerprint
                            severity
                            analysisState
                            analysisDate
                            sources {
                                artifact
                                release
                                variant
                                releaseDetails {
                                    version
                                    componentDetails {
                                        name
                                    }
                                }
                                artifactDetails {
                                    type
                                }
                            }
                        }
                    }
                }
            `,
            variables: {
                orgUuid: myorg.value.uuid,
                date: findingsPerDayDate.value
            },
            fetchPolicy: 'no-cache'
        })
        
        if (response.data.computeAnalyticsMetricsForDate) {
            findingsPerDayData.value = processMetricsData(response.data.computeAnalyticsMetricsForDate)
        }
        notify('success', 'Recalculation Complete', `Finding recalculation for ${findingsPerDayDate.value} is completed.`)
    } catch (error) {
        console.error('Error recalculating findings:', error)
        notify('error', 'Error', 'Failed to recalculate findings for the day.')
    } finally {
        findingsPerDayLoading.value = false
    }
}

onMounted(() => {
    if (myorg.value) 
        initLoad()
    if (showFindingsPerDay.value)
        fetchFindingsPerDay()
})
watch(myorg, (currentValue, oldValue) => {
    activeComponentsInput.value.organization = myorg.value.uuid
    initLoad()
    if (showFindingsPerDay.value)
        fetchFindingsPerDay()
});

watch(() => route.query, () => {
    if (showFindingsPerDay.value)
        fetchFindingsPerDay()
});

const releaseTagKeys: Ref<any[]> = ref([])
const releaseKeySearchObj: Ref<any> = ref({
    value: '',
    key: ''
})

const fetchReleaseKeys = async function (orgUuid: string) {
    const response = await graphqlClient.query({
        query: gql`
            query releaseTagKeys($orgId: ID!) {
                releaseTagKeys(orgUuid: $orgId)
            }`,
        variables: { orgId: orgUuid }
    })
    releaseTagKeys.value = [{value: '', label: 'Select Tag Key', disabled: true}, ...(response.data.releaseTagKeys.map((tag: string) => {return {'label': tag, 'value': tag}}))]
}
const instances: Ref<any[]> = ref([])

const initLoad = async function () {
    instances.value = []
    activeComponentsInputDate.value = new Date()
    activeComponentsInputDate.value.setDate(activeComponentsInputDate.value.getDate() - 30)
    activeComponentsInput.value.cutOffDate = activeComponentsInputDate.value.getTime()
    fetchReleaseKeys(myorg.value.uuid)
    fetchActiveComponentsBranchesAnalytics()
    fetchReleaseAnalytics()
    fetchVulnerabilityViolationAnalytics()
}

const hashSearchResults : Ref<any> = ref({})
const dtrackSearchResults : Ref<any[]> = ref([])
const dtrackSearchReleases : Ref<any[]> = ref([])
const releaseInstances : Ref<any[]> = ref([])
const showSearchResultsModal : Ref<boolean> = ref(false)
const searchResultsModalMode : Ref<'hash' | 'tags'> = ref('hash')
const showDtrackSearchResultsModal : Ref<boolean> = ref(false)
const dtrackSearchLoading : Ref<boolean> = ref(false)
const dtrackReleasesLoading : Ref<boolean> = ref(false)

const executeGqlSearchHashVersion = async function (params : any) {
    const response = await graphqlClient.query({
        query: gql`
            query searchDigestVersion($orgUuid: ID!, $query: String!) {
                searchDigestVersion(orgUuid: $orgUuid, query: $query) {
                    commitReleases {
                        ${GqlQueries.MultiReleaseGqlData}
                    }
                }
            }`,
        variables: { orgUuid: params.org, query: params.query },
        fetchPolicy: 'no-cache'
    })
    return response.data.searchDigestVersion
}

async function searchHashVersion (e: Event) {
    e.preventDefault()
    document.body.style.cursor = 'wait'
    try {
        const params = {
            org: myorg.value.uuid,
            query: hashSearchQuery.value
        }
        hashSearchResults.value = await executeGqlSearchHashVersion(params)
        searchResultsModalMode.value = 'hash'
        showSearchResultsModal.value = true
    } catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        document.body.style.cursor = 'default'
    }
}

function validateSbomSearchInput(input: any): input is { name: string; version?: string } {
    return typeof input === 'object' && 
           input !== null && 
           typeof input.name === 'string' && 
           input.name.length > 0 &&
           (input.version === undefined || typeof input.version === 'string')
}

function parsePlainTextBatch(text: string): { name: string; version?: string }[] | null {
    const lines = text.split('\n').map(line => line.trim()).filter(line => line.length > 0)
    if (lines.length === 0) {
        Swal.fire('Error!', 'Input must not be empty', 'error')
        return null
    }
    const results: { name: string; version?: string }[] = []
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i]
        // Split by tab or multiple spaces/whitespace
        const parts = line.split(/\t+|\s{1,}/).map(p => p.trim()).filter(p => p.length > 0)
        if (parts.length === 0) continue
        if (parts.length > 2) {
            Swal.fire('Error!', `Invalid format on line ${i + 1}: "${line}". Each line must have only a package name and optionally a version separated by tab.`, 'error')
            return null
        }
        const entry: { name: string; version?: string } = { name: parts[0] }
        if (parts.length === 2) {
            entry.version = parts[1]
        }
        results.push(entry)
    }
    return results.length > 0 ? results : null
}

function parseSbomSearchQueries(): { name: string; version?: string }[] | null {
    if (sbomSearchMode.value === 'simple') {
        const searchInput: { name: string; version?: string } = {
            name: sbomSearchQuery.value
        }
        if (sbomSearchVersion.value) {
            searchInput.version = sbomSearchVersion.value
        }
        return [searchInput]
    } else {
        const inputText = sbomSearchJson.value.trim()
        if (!inputText) {
            Swal.fire('Error!', 'Input must not be empty', 'error')
            return null
        }
        
        // Try JSON first if it looks like JSON
        if (inputText.startsWith('[')) {
            try {
                const parsed = JSON.parse(inputText)
                if (!Array.isArray(parsed)) {
                    Swal.fire('Error!', 'JSON must be an array', 'error')
                    return null
                }
                if (parsed.length === 0) {
                    Swal.fire('Error!', 'Array must not be empty', 'error')
                    return null
                }
                for (const item of parsed) {
                    if (!validateSbomSearchInput(item)) {
                        Swal.fire('Error!', 'Each element must have a "name" (string, required) and optional "version" (string)', 'error')
                        return null
                    }
                }
                return parsed
            } catch (err) {
                Swal.fire('Error!', 'Invalid JSON format', 'error')
                return null
            }
        } else {
            // Plain text format: one package per line, optionally with version separated by tab/whitespace
            return parsePlainTextBatch(inputText)
        }
    }
}

const BATCH_SIZE = 100

async function executeSbomSearchBatch(queries: { name: string; version?: string }[]): Promise<any[]> {
    const response = await graphqlClient.query({
        query: gql`
            query sbomComponentSearch($orgUuid: ID!, $queries: [SbomComponentSearchInput!]!) {
                sbomComponentSearch(orgUuid: $orgUuid, queries: $queries) {
                    purl
                    projects
                }
            }`,
        variables: { 
            orgUuid: myorg.value.uuid, 
            queries
        },
        fetchPolicy: 'no-cache'
    })
    return response.data.sbomComponentSearch
}

async function searchSbomComponent (e: Event) {
    e.preventDefault()
    const queries = parseSbomSearchQueries()
    if (!queries) return
    
    document.body.style.cursor = 'wait'
    dtrackSearchLoading.value = true
    showDtrackSearchResultsModal.value = true
    dtrackSearchResults.value = []
    dtrackSearchReleases.value = []
    showSearchProgress.value = true
    searchProgress.value = 0
    searchFailed.value = false
    
    try {
        if (queries.length <= BATCH_SIZE) {
            // Single batch
            const results = await executeSbomSearchBatch(queries)
            dtrackSearchResults.value = results
            searchProgress.value = 100
        } else {
            // Multiple batches - process sequentially
            const totalBatches = Math.ceil(queries.length / BATCH_SIZE)
            const allResults: any[] = []
            
            for (let i = 0; i < queries.length; i += BATCH_SIZE) {
                const batch = queries.slice(i, i + BATCH_SIZE)
                const batchResults = await executeSbomSearchBatch(batch)
                allResults.push(...batchResults)
                const completedBatches = Math.floor(i / BATCH_SIZE) + 1
                searchProgress.value = Math.round((completedBatches / totalBatches) * 100)
                // Update results incrementally so user sees progress
                dtrackSearchResults.value = [...allResults]
            }
            
            searchProgress.value = 100
        }
    } catch (err: any) {
        searchFailed.value = true
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        document.body.style.cursor = 'default'
        dtrackSearchLoading.value = false
    }
}

async function searchReleasesByDtrackProjects (dtrackProjects: string[]) {
    document.body.style.cursor = 'wait'
    dtrackReleasesLoading.value = true
    try {
        const searchParams = {
            orgUuid: myorg.value.uuid,
            dtrackProjects
        }
        dtrackSearchReleases.value = await store.dispatch('searchReleasesByDtrackProject', searchParams)
    } catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        document.body.style.cursor = 'default'
        dtrackReleasesLoading.value = false
    }
}

const instancePropsSearchObj: Ref<any> = ref({
    value: '',
    key: ''
})

const properties: ComputedRef<any> = computed((): any => {
    let props = []
    if (myorg.value && myorg.value.uuid) {
        let storeProps = store.getters.propertiesOfOrg(myorg.value.uuid)
        props = storeProps.map((prop: any) => {
            let propObj = {
                label: prop.name,
                value: prop.uuid
            }
            return propObj
        })
        props.push({
            label: 'Select Property Key (Optional)',
            value: ''
        })
    }
    return props
})

async function searchReleasesByTags (e: Event) {
    e.preventDefault()
    document.body.style.cursor = 'wait'
    try {
        const cleanedSearchObj = {
            org: myorg.value.uuid,
            tagKey: releaseKeySearchObj.value.key,
            tagValue: releaseKeySearchObj.value.value
        }
        const releases = await store.dispatch('searchReleasesByTags', cleanedSearchObj)
        hashSearchResults.value = {commitReleases: releases, releaseInstances: []}
        searchResultsModalMode.value = 'tags'
        showSearchResultsModal.value = true
    } catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        document.body.style.cursor = 'default'
    }
}
const instPropsSearchResults: Ref<any[]> = ref([])
const showInstPropSearchResultsModal: Ref<boolean> = ref(false)
const searchInstProp = async function (e: Event) {
    e.preventDefault()
    let cleanedSearchObj = {
        org: myorg.value.uuid,
        query: instancePropsSearchObj.value.value,
        property: instancePropsSearchObj.value.key
    }
    axios.post('/api/manual/v1/instance/searchInstancesByPropVal', cleanedSearchObj).then(response => {
        instPropsSearchResults.value = response.data
        showInstPropSearchResultsModal.value = true
    })
}
const instanceChangeSearchObj: Ref<any> = ref({
    dateFrom: (new Date()).getTime(),
    dateTo: (new Date()).getTime(),
})
const instChangeSearchResults: Ref<any[]> = ref([])
const showInstChangeSearchResultsModal: Ref<boolean> = ref(false)
const searchInstanceChanges = function(e: Event) {
    e.preventDefault()
    let cleanedSearchObj = {
        dateFrom: new Date(instanceChangeSearchObj.value.dateFrom).toISOString(),
        dateTo: new Date(instanceChangeSearchObj.value.dateTo).toISOString(),
        org: myorg.value.uuid
    }

    axios.post('/api/manual/v1/instance/searchChangesOverTime', cleanedSearchObj).then(response => {
        instChangeSearchResults.value = response.data
        showInstChangeSearchResultsModal.value=true
    })
}
function openRelease (uuid: string) {
    const routeData = router.resolve({ name: 'ReleaseView', params: { uuid: uuid } })
    window.open(routeData.href, '_blank')
}
const releaseSearchResultRows = [
    {
        key: 'component',
        title: 'Component / Product',
        render(row: any) {
            const routeName = row.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
            return h(RouterLink, 
                {to: {name: routeName,
                params: {orguuid: myorg.value.uuid, compuuid: row.componentDetails.uuid}},
                style: "text-decoration: none;"},
                () => row.componentDetails.name )
        }
    },
    {
        title: 'Type',
        key: 'type',
        render: (row: any) => row.componentDetails.type === 'PRODUCT' ? 'PRODUCT' : row.componentDetails.type 

    },
    {
        key: 'branch',
        title: 'Branch / Feature Set',
        render(row: any) {
            const routeName = row.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
            return h(RouterLink, 
                {to: {name: routeName,
                params: {orguuid: myorg.value.uuid, compuuid: row.componentDetails.uuid,
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
    },
    {
        title: 'Marketing Version',
        key: 'marketingVersion'
    }
]
const pagination = { pageSize: 7 }
const releasePagination = { pageSize: 8 }

const dtrackSearchResultRows = [
    {
        key: 'purl',
        title: 'Purl',
        render: (row: any) => {
            let style = "cursor: pointer;"
            if (selectedPurl.value === row.purl) style = "cursor: pointer; background-color: orange;"
            return h('div', {style, onClick: () => handlePurlSearchClick(row)}, row.purl)
        }
    }
]

async function handlePurlSearchClick (row: any) {
    selectedPurl.value = row.purl
    searchReleasesByDtrackProjects(row.projects)
}

const dtrackSearchReleaseRows: DataTableColumns<any> = [
    {
        type: 'expand',
        expandable: (row: any) => row.componentDetails ? row.componentDetails.type === 'PRODUCT' && row.parentReleases : false,
        renderExpand: (row: any) => {
            if (row.componentDetails) {
                return h(NDataTable, {
                    data: row.parentReleases,
                    columns: dtrackSearchReleaseRows
                })
            }
        }
    },
    {
        key: 'component',
        title: 'Component / Product',
        render(row: any) {
            if (row.componentDetails) {
                const routeName = row.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
                return h(RouterLink, 
                    {to: {name: routeName,
                        params: {orguuid: myorg.value.uuid, compuuid: row.componentDetails.uuid}},
                    style: "text-decoration: none;"},
                    () => row.componentDetails.name )
            }
        }
    },
    {
        key: 'branch',
        title: 'Branch / Feature Set',
        render(row: any) {
            if (row.componentDetails) {
                const routeName = row.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
                return h(RouterLink, 
                    {to: {name: routeName,
                        params: {orguuid: myorg.value.uuid, compuuid: row.componentDetails.uuid,
                            branchuuid: row.branchDetails.uuid
                        }},
                    style: "text-decoration: none;"},
                    () => row.branchDetails.name )
            }
        }
    },
    {
        key: 'version',
        title: 'Version',
        render(row: any) {
            if (row.componentDetails) {
                return h('a', {onclick: () => openRelease(row.uuid), style: "cursor: pointer; color: blue;"}, row.version)
            }
        }
    },
    {
        key: 'lifecycle',
        title: 'Lifecycle',
        render(row: any) {
            if (row.componentDetails) {
                return h('span', row.lifecycle )
            }
        }
    }
]

const activeComponentsInputDate = ref(new Date())
const activeComponentsInput = ref({
    organization: myorg.value.uuid,
    cutOffDate: activeComponentsInputDate.value.getTime(),
    componentType: 'COMPONENT',
    maxComponents: 3
})

function parseActiveComponentsInput () {
    const parsedActiveComponentsInput = {
        organization: activeComponentsInput.value.organization,
        cutOffDate: new Date(activeComponentsInput.value.cutOffDate),
        componentType: activeComponentsInput.value.componentType,
        maxComponents: activeComponentsInput.value.maxComponents
    }
    if (activeComponentsInput.value.componentType === 'COMPONENT' || activeComponentsInput.value.componentType === 'BRANCH') {
        parsedActiveComponentsInput.componentType = 'COMPONENT'
    } else parsedActiveComponentsInput.componentType = 'PRODUCT'
    return parsedActiveComponentsInput
}

function embedActiveComponentsVega () {
    const mostActiveToEmbed = toRaw(mostActiveOverTime.value)
    vegaEmbed.default('#mostActiveVisHome', mostActiveToEmbed,
        {
            actions: {
                editor: false
            },
            theme: 'powerbi'
        }
    )
}

async function fetchActiveComponentsBranchesAnalytics() {
    if (activeComponentsInput.value.componentType === 'COMPONENT' || activeComponentsInput.value.componentType === 'PRODUCT') {
        await fetchActiveComponentsAnalytics()
    } else {
        await fetchActiveBranchesAnalytics()
    }
}

function transformMostActiveDataBasedOnType () {
    if (activeComponentsInput.value.componentType === 'COMPONENT') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/componentsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Component"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Component"
    } else if (activeComponentsInput.value.componentType === 'PRODUCT') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/productsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Product"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Product"
    } else if (activeComponentsInput.value.componentType === 'BRANCH') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/componentsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid + '/' + datum.branchuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Branch"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Branch"
    } else if (activeComponentsInput.value.componentType === 'FEATURE_SET') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/productsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid + '/' + datum.branchuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Feature Set"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Feature Set"
    }
}

async function fetchActiveBranchesAnalytics() {
    const parsedActiveComponentsInput = parseActiveComponentsInput()

    const response = await graphqlClient.query({
        query: gql`
            query mostActiveBranchesOverTime($activeComponentsInput: ActiveComponentsInput!) {
                mostActiveBranchesOverTime(activeComponentsInput: $activeComponentsInput) {
                    componentuuid
                    componentname
                    branchuuid
                    branchname
                    rlzcount
                }
            }`,
        variables: { 
            activeComponentsInput: parsedActiveComponentsInput
        }
    })
    if (response && response.data) {
        mostActiveOverTime.value.data.values = []
        response.data.mostActiveBranchesOverTime.forEach((e: any) => {
            const analyticsEl = {
                componentname: e.componentname + " - " + e.branchname,
                componentuuid: e.componentuuid,
                branchuuid: e.branchuuid,
                rlzcount: e.rlzcount,
                componenttype: e.componenttype
            }
            mostActiveOverTime.value.data.values.push(analyticsEl)
        })
        transformMostActiveDataBasedOnType ()
        embedActiveComponentsVega()
    }
}

async function fetchActiveComponentsAnalytics() {
    const parsedActiveComponentsInput = parseActiveComponentsInput()

    const response = await graphqlClient.query({
        query: gql`
            query mostActiveComponentsOverTime($activeComponentsInput: ActiveComponentsInput!) {
                mostActiveComponentsOverTime(activeComponentsInput: $activeComponentsInput) {
                    componentuuid
                    componentname
                    rlzcount
                }
            }`,
        variables: { 
            activeComponentsInput: parsedActiveComponentsInput
        }
    })
    if (response && response.data) {
        mostActiveOverTime.value.data.values = []
        response.data.mostActiveComponentsOverTime.forEach((e: any) => mostActiveOverTime.value.data.values.push(Object.assign({}, e)))
        transformMostActiveDataBasedOnType ()
        embedActiveComponentsVega()
    }
}

const releaseVisData: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    background: 'white',
    title: 'Number of Releases Created Per Day',
    height: 220,
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: 'line',
        point: {
            "filled": false,
            "fill": "white"
        },
        tooltip: true
    },
    encoding: {
        y: {
            field: 'num',
            type: 'quantitative',
            aggregate: 'sum',
            axis: {
                title: null
            },
            title: 'Releases'
        },
        x: {
            field: 'date',
            type: 'temporal',
            timeUnit: 'utcyearmonthdate',
            axis: {
                title: null
            },
            title: 'Date'
        }
    }
})

const analyticsMetrics: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    background: 'white',
    title: 'Vulnerabilities and Policy Violations Over Time',
    height: 220,
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: 'line',
        point: {
            "filled": false,
            "fill": "white"
        },
        tooltip: true
    },
    encoding: {
        y: {
            field: 'num',
            type: 'quantitative',
            aggregate: 'max',
            axis: {
                title: null
            },
            title: 'Occurrences'
        },
        x: {
            field: 'createdDate',
            type: 'temporal',
            timeUnit: 'utcyearmonthdate',
            axis: {
                title: null
            },
            title: 'Date'
        },
        color: {
            field: 'type',
            legend: null
        }
    }
})

const mostActiveOverTime: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    background: 'white',
    width: 'container',
    height: 220,
    data: {
        values: []
    },
    mark: {
        type: "arc",
        innerRadius: 50
    },
    transform: [{
        calculate: "'/componentsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid", "as": "url"
    }],
    encoding: {
        theta: {field: "rlzcount", type: "quantitative"},
        color: {
            field: "componentname",
            type: "nominal", 
            title: "Name",
            legend: {
                direction: 'horizontal',
                orient: 'bottom'
            }
        },
        href: {field: "url", type: "nominal"},
        tooltip: [
            {field: "componentname", type: "nominal", title: "Name"},
            {field: "rlzcount", type: "quantitative", title: "Releases"}
        ]
    }
})

async function fetchReleaseAnalytics() {
    const cutOffDate = new Date()
    cutOffDate.setDate(cutOffDate.getDate() - 60)
    const resp = await graphqlClient.query({
        query: gql`
            query releaseAnalytics($orgUuid: ID!, $cutOffDate: DateTime!) {
                releaseAnalytics(orgUuid: $orgUuid, cutOffDate: $cutOffDate) {
                    date
                    num
                }
            }
            `,
        variables: { 
            orgUuid: myorg.value.uuid,
            cutOffDate
        },
        fetchPolicy: 'no-cache'
    })
    resp.data.releaseAnalytics.map((item: any) => {
        item.date = item.date.split('[')[0]
    })
    releaseVisData.value.data.values = resp.data.releaseAnalytics
    vegaEmbed.default('#releaseCreationVisHome', 
        toRaw(releaseVisData.value),
        {
            actions: {
                editor: false
            },
            theme: 'powerbi'
        }
    )
}

async function fetchVulnerabilityViolationAnalytics() {
    const dateFrom = new Date()
    dateFrom.setDate(dateFrom.getDate() - 60)
    const dateTo = new Date()
    const resp = await graphqlClient.query({
        query: gql`
            query vulnerabilitiesViolationsOverTime($orgUuid: ID!, $dateFrom: DateTime!, $dateTo: DateTime!) {
                vulnerabilitiesViolationsOverTime(orgUuid: $orgUuid, dateFrom: $dateFrom, dateTo: $dateTo) {
                    createdDate
                    num
                    type
                }
            }
            `,
        variables: { 
            orgUuid: myorg.value.uuid,
            dateFrom,
            dateTo
        },
        fetchPolicy: 'no-cache'
    })
    resp.data.vulnerabilitiesViolationsOverTime.map((item: any) => {
        item.createdDate = item.createdDate.split('[')[0]
    })
    analyticsMetrics.value.data.values = resp.data.vulnerabilitiesViolationsOverTime
    vegaEmbed.default('#analyticsMetricsVisHome', toRaw(analyticsMetrics.value),
        {
            actions: {
                editor: false
            },
            theme: 'powerbi'
        }
    )
}

function displayActiveComponentType () {
    let displayComp
    switch (activeComponentsInput.value.componentType) {
        case 'BRANCH':
            displayComp = 'Branches'
            break
        case 'FEATURE_SET':
            displayComp = 'Feature Sets'
            break
        case 'PRODUCT':
            displayComp = 'Products'
            break
        default:
            displayComp = 'Components'
            break
    }
    return displayComp
}

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.charts {
    display: grid;
}
</style>
