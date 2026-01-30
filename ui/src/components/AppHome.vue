<template>
    <div class="home">
        <div class="dashboardBlock">
            <n-grid x-gap="24" cols="2">
                <n-gi>
                    <releases-per-day-chart
                        :type="releaseChartType"
                        :org-uuid="releaseChartProps.orgUuid"
                        :perspective-uuid="releaseChartProps.perspectiveUuid"
                        :perspective-name="releaseChartProps.perspectiveName"
                    />
                </n-gi>
                <n-gi>
                    <div>
                        <n-input-number style="display:inline-block; width:80px;" 
                            v-model:value="activeComponentsInput.maxComponents" />
                        <span>Most Active </span>
                        <n-dropdown title="Select Type" trigger="hover"
                            :options="[{label: 'Components', key: 'COMPONENT'}, {label: 'Products', key: 'PRODUCT'}, {label: 'Branches', key: 'BRANCH'}, {label: featureSetLabelPlural, key: 'FEATURE_SET'}]"
                            @select="$key => {activeComponentsInput.componentType = $key ? $key: 'COMPONENT';}">
                            <span>
                                <span>{{ displayActiveComponentType() }}</span>
                                <Icon><CaretDownFilled/></Icon>
                            </span>
                        </n-dropdown>
                        <span>since </span>
                        <n-date-picker style="display:inline-block; width:130px;"
                            v-model:value="activeComponentsInput.cutOffDate" />
                    </div>
                    <most-active-chart
                        :component-type="activeComponentsInput.componentType"
                        :max-components="activeComponentsInput.maxComponents"
                        :cut-off-date="activeComponentsInput.cutOffDate"
                        :org-uuid="myorg?.uuid"
                        :perspective-uuid="myperspective"
                        :feature-set-label="featureSetLabel"
                    />
                </n-gi>
                <n-gi span="2"><n-divider /></n-gi>
                <n-gi>
                    <findings-over-time-chart
                        :type="releaseChartType"
                        :org-uuid="myorg?.uuid"
                        :perspective-uuid="releaseChartProps.perspectiveUuid"
                    />
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
                            @update:value="handleTabChange"
                            >
                                <n-tab-pane name="searchreleasesbytext" tab="By Version, Digest, Commit">
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
                                <n-tab-pane name="searchreleasesbytags" tab="By Tags">
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
                                <n-tab-pane name="searchreleasesbydsbom" tab="By SBOM Components">
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
                                <n-tab-pane name="searchreleasesbyfinding" tab="By Findings">
                                    <h5>Search For Releases By Finding ID (CVE, CWE, GHSA, etc.)</h5>
                                    <n-form
                                        inline
                                        @submit="searchByFinding">
                                        <n-input-group>
                                            <n-auto-complete
                                                placeholder="Enter Finding ID (e.g., CVE-2024-1234, CWE-79)"
                                                v-model:value="findingSearchQuery"
                                                :options="findingIdOptions"
                                                :loading="findingIdsLoading"
                                                @update:value="handleFindingSearchInput"
                                                clearable
                                            />
                                            <n-button
                                                variant="contained-text"
                                                attr-type="submit">
                                                Find
                                            </n-button>
                                        </n-input-group>
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
                            style="width: 100%"
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
                                <n-grid x-gap="3" cols="3">
                                    <n-gi span="1">
                                        <n-data-table
                                            :data="dtrackSearchResults"
                                            :columns="dtrackSearchResultRows"
                                            :pagination="pagination"
                                            :loading="dtrackSearchLoading"
                                        />
                                    </n-gi>
                                    <n-gi span="2">
                                        <component-branches-table
                                            :data="dtrackSearchReleases"
                                            :org-uuid="myorg?.uuid"
                                            :loading="dtrackReleasesLoading"
                                            :feature-set-label="featureSetLabel"
                                            :show-is-latest-column="true"
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
                        
                        <releases-by-cve
                            v-model:show="showReleasesByCveModal"
                            :cve-id="findingSearchQuery"
                            :org-uuid="myorg?.uuid"
                            :perspective-uuid="currentPerspectiveUuid"
                            :perspective-name="currentPerspectiveName"
                            :feature-set-label="featureSetLabel"
                            :show-is-latest-column="true"
                        />
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
import { NTabs, NTabPane, NInputGroup, NInput, NInputNumber, NButton, NDropdown, NForm, NModal, NDataTable, NSelect, NFormItem, NDatePicker, NTooltip, DataTableColumns, NIcon, NGrid, NGi, NDivider, NRadioButton, NRadioGroup, NProgress, NSpin, NTag, NSpace, NAutoComplete, useNotification, NotificationType } from 'naive-ui'
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
import Swal from 'sweetalert2'
import commonFunctions from '@/utils/commonFunctions'
import constants from '@/utils/constants'
import FindingsOverTimeChart from './FindingsOverTimeChart.vue'
import ReleasesPerDayChart from './ReleasesPerDayChart.vue'
import MostActiveChart from './MostActiveChart.vue'
import ReleasesByCve from './ReleasesByCve.vue'
import ComponentBranchesTable from './ComponentBranchesTable.vue'

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
const myperspective: ComputedRef<string> = computed((): string => store.getters.myperspective)

const releaseChartType: ComputedRef<'ORGANIZATION' | 'PERSPECTIVE'> = computed(() => {
    return myperspective.value && myperspective.value !== 'default' ? 'PERSPECTIVE' : 'ORGANIZATION'
})

const releaseChartProps: ComputedRef<any> = computed(() => {
    if (releaseChartType.value === 'PERSPECTIVE') {
        return {
            type: 'PERSPECTIVE',
            perspectiveUuid: myperspective.value,
            perspectiveName: currentPerspectiveName.value
        }
    } else {
        return {
            type: 'ORGANIZATION',
            orgUuid: myorg.value?.uuid
        }
    }
})

const featureSetLabel = computed(() => myorg.value?.terminology?.featureSetLabel || 'Feature Set')
const featureSetLabelPlural = computed(() => featureSetLabel.value + 's')

// Get perspectives for ReleasesByCve modal
const perspectives: ComputedRef<any[]> = computed((): any => store.getters.perspectivesOfOrg(myorg.value?.uuid || ''))

const currentPerspectiveUuid = computed(() => {
    return myperspective.value !== 'default' ? myperspective.value : undefined
})

const currentPerspectiveName = computed(() => {
    if (myperspective.value === 'default') {
        return undefined
    }
    const perspective = perspectives.value.find((p: any) => p.uuid === myperspective.value)
    return perspective ? perspective.name : undefined
})

const hashSearchQuery = ref('')
const findingSearchQuery = ref('')
const showReleasesByCveModal = ref(false)
const findingIds: Ref<string[]> = ref([])
const findingIdsLoading = ref(false)
const filteredFindingIds: Ref<string[]> = ref([])

const findingIdOptions = computed(() => {
    return filteredFindingIds.value.map(id => ({ label: id, value: id }))
})

const handleFindingSearchInput = (value: string) => {
    if (!value || value.trim() === '') {
        filteredFindingIds.value = findingIds.value
    } else {
        const searchTerm = value.toLowerCase()
        filteredFindingIds.value = findingIds.value.filter(id => 
            id.toLowerCase().includes(searchTerm)
        )
    }
}
const sbomSearchQuery = ref('')
const sbomSearchVersion = ref('')
const sbomSearchMode = ref('simple')
const sbomSearchJson = ref('')
const searchProgress = ref(0)
const showSearchProgress = ref(false)
const searchFailed = ref(false)
const selectedPurl = ref('')

onMounted(() => {
    if (myorg.value) 
        initLoad()
})
watch(myorg, (currentValue, oldValue) => {
    activeComponentsInput.value.organization = myorg.value.uuid
    initLoad()
});


const releaseTagKeys: Ref<any[]> = ref([])
const releaseKeySearchObj: Ref<any> = ref({
    value: '',
    key: ''
})

const handleTabChange = (value: string) => {
    if (value === 'searchreleasesbytags') {
        fetchReleaseKeys(myorg.value.uuid)
    } else if (value === 'searchreleasesbyfinding') {
        fetchFindingIds()
    }
}

const searchByFinding = (e: Event) => {
    e.preventDefault()
    if (!findingSearchQuery.value.trim()) {
        notify('warning', 'Warning', 'Please enter a finding ID')
        return
    }
    showReleasesByCveModal.value = true
}

const fetchFindingIds = async () => {
    if (!myorg.value?.uuid) return
    
    findingIdsLoading.value = true
    try {
        const today = new Date()
        const dateToUse = today.toISOString().split('T')[0]
        
        let response
        
        if (myperspective.value && myperspective.value !== 'default') {
            // Fetch by perspective
            response = await graphqlClient.query({
                query: gql`
                    query findingsPerDayByPerspective($perspectiveUuid: ID!, $date: String!) {
                        findingsPerDayByPerspective(perspectiveUuid: $perspectiveUuid, date: $date) {
                            vulnerabilityDetails {
                                vulnId
                            }
                            weaknessDetails {
                                cweId
                            }
                        }
                    }
                `,
                variables: {
                    perspectiveUuid: myperspective.value,
                    date: dateToUse
                }
            })
            
            if (response.data.findingsPerDayByPerspective) {
                const vulnIds = (response.data.findingsPerDayByPerspective.vulnerabilityDetails || [])
                    .map((v: any) => v.vulnId)
                    .filter((id: string) => id)
                const cweIds = (response.data.findingsPerDayByPerspective.weaknessDetails || [])
                    .map((w: any) => w.cweId)
                    .filter((id: string) => id)
                findingIds.value = [...new Set([...vulnIds, ...cweIds])].sort().reverse()
                filteredFindingIds.value = findingIds.value
            }
        } else {
            // Fetch by organization
            response = await graphqlClient.query({
                query: gql`
                    query findingsPerDay($orgUuid: ID!, $date: String!) {
                        findingsPerDay(orgUuid: $orgUuid, date: $date) {
                            vulnerabilityDetails {
                                vulnId
                            }
                            weaknessDetails {
                                cweId
                            }
                        }
                    }
                `,
                variables: {
                    orgUuid: myorg.value.uuid,
                    date: dateToUse
                }
            })
            
            if (response.data.findingsPerDay) {
                const vulnIds = (response.data.findingsPerDay.vulnerabilityDetails || [])
                    .map((v: any) => v.vulnId)
                    .filter((id: string) => id)
                const cweIds = (response.data.findingsPerDay.weaknessDetails || [])
                    .map((w: any) => w.cweId)
                    .filter((id: string) => id)
                findingIds.value = [...new Set([...vulnIds, ...cweIds])].sort().reverse()
                filteredFindingIds.value = findingIds.value
            }
        }
    } catch (error) {
        console.error('Error fetching finding IDs:', error)
        findingIds.value = []
    } finally {
        findingIdsLoading.value = false
    }
}

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
        const response = await graphqlClient.query({
            query: gql`
                query releasesByDtrackProjects($orgUuid: ID!, $dtrackProjects: [ID]) {
                    releasesByDtrackProjects(orgUuid: $orgUuid, dtrackProjects: $dtrackProjects) {
                        uuid
                        name
                        type
                        versionSchema
                        branches {
                            uuid
                            name
                            status
                            versionSchema
                            latestReleaseVersion
                            releases {
                                uuid
                                version
                                createdDate
                                lifecycle
                            }
                        }
                    }
                }
            `,
            variables: { 
                orgUuid: myorg.value.uuid, 
                dtrackProjects 
            },
            fetchPolicy: 'no-cache'
        })
        dtrackSearchReleases.value = (response.data as any).releasesByDtrackProjects || []
    } catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
        dtrackSearchReleases.value = []
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
        title: 'Branch / ' + featureSetLabel.value,
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

// Removed dtrackSearchReleaseRows - now using ComponentBranchesTable component

const activeComponentsInputDate = ref(new Date())
const activeComponentsInput = ref({
    organization: myorg.value.uuid,
    cutOffDate: activeComponentsInputDate.value.getTime(),
    componentType: 'COMPONENT',
    maxComponents: 3
})

function displayActiveComponentType () {
    let displayComp
    switch (activeComponentsInput.value.componentType) {
        case 'BRANCH':
            displayComp = 'Branches'
            break
        case 'FEATURE_SET':
            displayComp = featureSetLabelPlural.value
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
