<template>
    <div class="organization-changelog">
        <h2>Organization Changelog</h2>
        
        <ChangelogControls
            v-model:dateRange="dateRange"
            v-model:aggregationType="aggregationType"
            :aggregation-hint="'Applies to SBOM and Finding changes'"
            @apply="fetchChangelog"
        />
        
        <n-spin :show="loading">
            <div v-if="changelog">
                <n-tabs type="line" animated style="margin-top: 20px;">
                    <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                        <div v-if="aggregationType === 'NONE'">
                            <div v-for="component in changelog.components" :key="component.uuid">
                                <ComponentHeader
                                    :org-uuid="orgUuid"
                                    :component-uuid="component.uuid"
                                    :name="component.name"
                                />
                                <div v-for="branch in component.branches" :key="branch.uuid">
                                    <h4 class="branch-name">{{ branch.name }}</h4>
                                    <div v-for="release in branch.releases" :key="release.uuid">
                                        <ReleaseHeader
                                            :uuid="release.uuid"
                                            :version="release.version"
                                            :lifecycle="release.lifecycle"
                                        />
                                        <SbomChangesDisplay :sbom-changes="release.sbomChanges" />
                                    </div>
                                </div>
                            </div>
                        </div>
                        
                        <div v-else-if="aggregationType === 'AGGREGATED'">
                            <p class="aggregation-note">Aggregated across all components</p>
                            <SbomChangesDisplay :sbom-changes="changelog.sbomChanges" />
                        </div>
                    </n-tab-pane>
                    
                    <n-tab-pane name="findings" tab="🔒 Finding Changes">
                        <div v-if="aggregationType === 'NONE'">
                            <div v-for="component in changelog.components" :key="component.uuid">
                                <ComponentHeader
                                    :org-uuid="orgUuid"
                                    :component-uuid="component.uuid"
                                    :name="component.name"
                                />
                                <div v-for="branch in component.branches" :key="branch.uuid">
                                    <h4 class="branch-name">{{ branch.name }}</h4>
                                    <div v-for="release in branch.releases" :key="release.uuid">
                                        <ReleaseHeader
                                            :uuid="release.uuid"
                                            :version="release.version"
                                            :lifecycle="release.lifecycle"
                                        />
                                        <FindingChangesDisplay :finding-changes="release.findingChanges" />
                                    </div>
                                </div>
                            </div>
                        </div>
                        
                        <div v-else-if="aggregationType === 'AGGREGATED'">
                            <p class="aggregation-note">Aggregated across all components</p>
                            <FindingChangesDisplay :finding-changes="changelog.findingChanges" />
                        </div>
                    </n-tab-pane>
                </n-tabs>
            </div>
            
            <div v-else-if="!loading" class="no-data">
                <n-empty description="No changelog data available for the selected date range" />
            </div>
        </n-spin>
    </div>
</template>

<script lang="ts">
export default {
    name: 'OrganizationChangelogView'
}
</script>

<script lang="ts" setup>
import { ref, onMounted, watch, Ref } from 'vue'
import { NTabs, NTabPane, NSpin, NEmpty } from 'naive-ui'
import {
    ChangelogControls,
    FindingChangesDisplay,
    SbomChangesDisplay,
    ComponentHeader,
    ReleaseHeader
} from './changelog'
import commonFunctions from '../utils/commonFunctions'

interface Props {
    orgUuid: string
    perspectiveUuid?: string
}

const props = defineProps<Props>()

const dateRange: Ref<[number, number]> = ref([
    Date.now() - 7 * 24 * 60 * 60 * 1000,
    Date.now()
])

const aggregationType: Ref<string> = ref('AGGREGATED')
const loading: Ref<boolean> = ref(false)
const changelog: Ref<any> = ref(null)

const fetchChangelog = async () => {
    loading.value = true
    try {
        const dateFrom = new Date(dateRange.value[0]).toISOString()
        const dateTo = new Date(dateRange.value[1]).toISOString()
        
        const params = {
            orgUuid: props.orgUuid,
            perspectiveUuid: props.perspectiveUuid,
            dateFrom,
            dateTo,
            aggregated: aggregationType.value,
            timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
        }
                
        const result = await commonFunctions.fetchOrganizationChangelog(params)
        changelog.value = result
        
    } catch (error) {
        console.error('Error fetching organization changelog:', error)
        changelog.value = null
    } finally {
        loading.value = false
    }
}

onMounted(() => {
    fetchChangelog()
})

watch(aggregationType, () => {
    fetchChangelog()
})
</script>

<style scoped lang="scss">
.organization-changelog {
    padding: 16px;
    
    h2 {
        margin-bottom: 16px;
    }
    
    .branch-name {
        margin-top: 16px;
        margin-bottom: 8px;
        color: #666;
        font-weight: 600;
    }
    
    .aggregation-note {
        margin-bottom: 16px;
        font-style: italic;
        color: #666;
    }
    
    .no-data {
        padding: 40px;
        text-align: center;
    }
}
</style>
