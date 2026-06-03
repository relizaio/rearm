<template>
    <div class="container">
        <h1 v-if="isRouterLink">Changelog</h1>
        
        <!-- ===== HEADER: varies per view mode ===== -->
        
        <!-- Date-based component header -->
        <div v-if="componentType === 'COMPONENT' && props.iscomponentchangelog">
            <h2 v-if="changelog && changelog.orgUuid">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.orgUuid, compuuid: changelog.componentUuid }}">{{ changelog.componentName }}</router-link>
                <span v-if="props.branchprop && changelog.branches && changelog.branches.length === 1"> ({{ changelog.branches[0].branchName }}) - Changes</span>
                <span v-else> - Component-wide Changes</span>
            </h2>
            <ChangelogControls
                v-model:dateRange="dateRange"
                v-model:aggregationType="aggregationType"
                :show-aggregation="!!(changelog && changelog.branches && changelog.branches.length > 0)"
                @apply="getAggregatedChangelog"
            />
        </div>
        
        <!-- Branch-based component header (release comparison) -->
        <div v-else-if="componentType === 'COMPONENT' && !props.iscomponentchangelog">
            <h2 v-if="changelog && changelog.orgUuid && changelog.branches && changelog.branches.length >= 1">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.orgUuid, compuuid: changelog.componentUuid, branchuuid: changelog.branches[0].branchUuid }}">{{ changelog.componentName + '(' + changelog.branches[0].branchName + ')' }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            <ChangelogControls
                v-model:aggregationType="aggregationType"
                :show-date-picker="false"
            />
        </div>
        
        <!-- Product date-based header -->
        <div v-else-if="componentType === 'PRODUCT' && props.iscomponentchangelog">
            <h2 v-if="changelog && changelog.orgUuid">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.orgUuid, compuuid: changelog.componentUuid }}">{{ changelog.componentName }}</router-link>
                <span> - Product-wide Changes</span>
            </h2>
            <ChangelogControls
                v-model:dateRange="dateRange"
                v-model:aggregationType="aggregationType"
                :show-aggregation="true"
                aggregation-hint="Applies to Component, SBOM, and Finding changes"
                @apply="getAggregatedChangelog"
            />
        </div>
        
        <!-- Product release comparison header -->
        <div v-else-if="componentType === 'PRODUCT' && !props.iscomponentchangelog">
            <h2 v-if="changelog && changelog.orgUuid">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.orgUuid, compuuid: changelog.componentUuid }}">{{ changelog.componentName }}</router-link>
                <span>&nbsp;</span>
                <router-link v-if="changelog.firstRelease" :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link v-if="changelog.lastRelease" :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            <ChangelogControls
                v-model:aggregationType="aggregationType"
                :show-date-picker="false"
                :show-aggregation="true"
                aggregation-hint="Applies to Component, SBOM, and Finding changes"
            />
        </div>
        
        <!-- ===== EMPTY STATE: no releases in date range ===== -->
        <div v-if="!changelog" style="padding: 40px; text-align: center; color: #999;">
            <p style="font-size: 16px; margin-bottom: 10px;">No changelog data available{{ isDateBased ? ' for the selected date range' : '' }}</p>
            <p v-if="isDateBased" style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
        </div>
        
        <!-- ===== UNIFIED TABS: shared across all view modes ===== -->
        
        <div v-if="changelog" style="display: flex; justify-content: flex-end; margin-top: 16px;">
            <n-button type="success" @click="handleExportPdf">
                📄 Export PDF
            </n-button>
        </div>
        
        <n-tabs v-if="changelog" v-model:value="activeTab" type="segment" animated style="margin-top: 4px;">
            <!-- Code / Component Changes tab -->
            <n-tab-pane name="code" :tab="codeTabLabel">
                <SeverityFilter v-model:selectedSeverity="selectedSeverity" />
                
                <div v-if="!hasData" style="padding: 40px; text-align: center; color: #999;">
                    <p style="font-size: 16px; margin-bottom: 10px;">No {{ isProduct ? 'component' : 'code' }} changes available{{ isDateBased ? ' for the selected date range' : '' }}</p>
                    <p v-if="isDateBased" style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
                </div>
                
                <!-- AGGREGATED view -->
                <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                    <p v-if="isDateBased && displayBranches.length > 1" style="margin-bottom: 10px; font-style: italic;">{{ aggregatedDescription }}</p>
                    <div v-for="branch in displayBranches" :key="branch.branchUuid">
                        <h3 v-if="showBranchHeadings && branch.componentName && branch.componentUuid">
                            <router-link :to="{ name: 'ComponentsOfOrg', params: { orguuid: changelog.orgUuid, compuuid: branch.componentUuid }}">{{ branch.componentName }}</router-link>
                            <span> / </span>
                            <router-link :to="{ name: 'ComponentsOfOrg', params: { orguuid: changelog.orgUuid, compuuid: branch.componentUuid, branchuuid: branch.branchUuid }}">{{ branch.branchName }}</router-link>
                            <span v-if="branch.firstVersion && branch.lastVersion && branch.firstReleaseUuid && branch.lastReleaseUuid">
                                (<router-link :to="{ name: 'ReleaseView', params: { uuid: branch.firstReleaseUuid }}">{{ branch.firstVersion }}</router-link>
                                <span> - </span>
                                <router-link :to="{ name: 'ReleaseView', params: { uuid: branch.lastReleaseUuid }}">{{ branch.lastVersion }}</router-link>)
                            </span>
                            <n-tag v-if="branch.changeType === 'ADDED'" type="success" size="small" style="margin-left: 8px;">New Component</n-tag>
                            <n-tag v-else-if="branch.changeType === 'REMOVED'" type="error" size="small" style="margin-left: 8px;">Component Removed</n-tag>
                        </h3>
                        <h3 v-else-if="showBranchHeadings">
                            <router-link :to="{ name: 'ComponentsOfOrg', params: { orguuid: changelog.orgUuid, compuuid: changelog.componentUuid, branchuuid: branch.branchUuid }}">{{ branch.branchName }}</router-link>
                            <span v-if="branch.firstVersion && branch.lastVersion && branch.firstReleaseUuid && branch.lastReleaseUuid">
                                (<router-link :to="{ name: 'ReleaseView', params: { uuid: branch.firstReleaseUuid }}">{{ branch.firstVersion }}</router-link>
                                <span> - </span>
                                <router-link :to="{ name: 'ReleaseView', params: { uuid: branch.lastReleaseUuid }}">{{ branch.lastVersion }}</router-link>)
                            </span>
                            <n-tag v-if="branch.changeType === 'ADDED'" type="success" size="small" style="margin-left: 8px;">New Component</n-tag>
                            <n-tag v-else-if="branch.changeType === 'REMOVED'" type="error" size="small" style="margin-left: 8px;">Component Removed</n-tag>
                        </h3>
                        <CodeChangesDisplay 
                            v-if="!isProduct || (branch.commitsByType && branch.commitsByType.length > 0)"
                            :changes="branch.commitsByType" 
                            :selected-severity="selectedSeverity"
                        />
                    </div>
                </div>
                
                <!-- NONE aggregation: flat chronological view -->
                <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                    <div v-for="entry in flattenedReleases" :key="entry.release.releaseUuid">
                        <ReleaseHeader
                            :uuid="entry.release.releaseUuid"
                            :version="entry.release.version"
                            :lifecycle="entry.release.lifecycle"
                            :org-uuid="changelog.orgUuid"
                            :component-uuid="entry.branch.componentUuid || changelog.componentUuid"
                            :component-name="entry.branch.componentName"
                            :branch-uuid="entry.branch.branchUuid"
                            :branch-name="entry.branch.branchName"
                            :branch-change-type="entry.showBranchTag ? entry.branch.changeType : undefined"
                        />
                        <CodeChangesDisplay
                            :changes="formatCodeChanges(entry.release)"
                            :selected-severity="selectedSeverity"
                        />
                    </div>
                </div>

                <!-- NONE aggregation, PRODUCT: grouped per product release -->
                <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneProductChangelog'">
                    <div v-for="group in displayProductReleases" :key="group.releaseUuid" class="product-release-group">
                        <ProductReleaseHeader
                            :uuid="group.releaseUuid"
                            :version="group.version"
                            :lifecycle="group.lifecycle"
                        />
                        <template v-for="branch in group.branches" :key="branch.branchUuid">
                            <div v-for="release in branch.releases" :key="release.releaseUuid">
                                <ReleaseHeader
                                    :uuid="release.releaseUuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                    :org-uuid="changelog.orgUuid"
                                    :component-uuid="branch.componentUuid || changelog.componentUuid"
                                    :component-name="branch.componentName"
                                    :branch-uuid="branch.branchUuid"
                                    :branch-name="branch.branchName"
                                    :branch-change-type="branch.changeType"
                                />
                                <CodeChangesDisplay
                                    :changes="formatCodeChanges(release)"
                                    :selected-severity="selectedSeverity"
                                />
                            </div>
                        </template>
                    </div>
                </div>
            </n-tab-pane>

            <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                <div v-if="!hasData" style="padding: 40px; text-align: center; color: #999;">
                    <p style="font-size: 16px; margin-bottom: 10px;">No SBOM changes available{{ isDateBased ? ' for the selected date range' : '' }}</p>
                    <p v-if="isDateBased" style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
                </div>
                
                <!-- NONE mode: flat chronological per-release SBOM changes -->
                <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                    <div v-for="entry in flattenedReleases" :key="entry.release.releaseUuid">
                        <ReleaseHeader
                            :uuid="entry.release.releaseUuid"
                            :version="entry.release.version"
                            :lifecycle="entry.release.lifecycle"
                            :org-uuid="changelog.orgUuid"
                            :component-uuid="entry.branch.componentUuid || changelog.componentUuid"
                            :component-name="entry.branch.componentName"
                            :branch-uuid="entry.branch.branchUuid"
                            :branch-name="entry.branch.branchName"
                        />
                        <SbomChangesDisplay :sbom-changes="entry.release.sbomChanges" />
                    </div>
                </div>

                <!-- NONE mode, PRODUCT: grouped per product release -->
                <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneProductChangelog'">
                    <div v-for="group in displayProductReleases" :key="group.releaseUuid" class="product-release-group">
                        <ProductReleaseHeader
                            :uuid="group.releaseUuid"
                            :version="group.version"
                            :lifecycle="group.lifecycle"
                        />
                        <template v-for="branch in group.branches" :key="branch.branchUuid">
                            <div v-for="release in branch.releases" :key="release.releaseUuid">
                                <ReleaseHeader
                                    :uuid="release.releaseUuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                    :org-uuid="changelog.orgUuid"
                                    :component-uuid="branch.componentUuid || changelog.componentUuid"
                                    :component-name="branch.componentName"
                                    :branch-uuid="branch.branchUuid"
                                    :branch-name="branch.branchName"
                                />
                                <SbomChangesDisplay :sbom-changes="release.sbomChanges" />
                            </div>
                        </template>
                    </div>
                </div>

                <!-- AGGREGATED mode: Show top-level aggregated changes -->
                <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                    <p style="margin-bottom: 10px; font-style: italic;">{{ aggregatedDescription }}</p>
                    <SbomChangesDisplay :sbom-changes="changelog.sbomChanges" :show-attribution="true" />
                </div>
            </n-tab-pane>
            
            <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                <div v-if="!hasData">
                    <FindingChangesDisplayWithAttribution />
                </div>
                
                <!-- NONE mode: flat chronological per-release Finding changes -->
                <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                    <div v-for="entry in flattenedReleases" :key="entry.release.releaseUuid">
                        <ReleaseHeader
                            :uuid="entry.release.releaseUuid"
                            :version="entry.release.version"
                            :lifecycle="entry.release.lifecycle"
                            :org-uuid="changelog.orgUuid"
                            :component-uuid="entry.branch.componentUuid || changelog.componentUuid"
                            :component-name="entry.branch.componentName"
                            :branch-uuid="entry.branch.branchUuid"
                            :branch-name="entry.branch.branchName"
                        />
                        <FindingChangesDisplay :finding-changes="entry.release.findingChanges" />
                    </div>
                </div>

                <!-- NONE mode, PRODUCT: grouped per product release -->
                <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneProductChangelog'">
                    <div v-for="group in displayProductReleases" :key="group.releaseUuid" class="product-release-group">
                        <ProductReleaseHeader
                            :uuid="group.releaseUuid"
                            :version="group.version"
                            :lifecycle="group.lifecycle"
                        />
                        <template v-for="branch in group.branches" :key="branch.branchUuid">
                            <div v-for="release in branch.releases" :key="release.releaseUuid">
                                <ReleaseHeader
                                    :uuid="release.releaseUuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                    :org-uuid="changelog.orgUuid"
                                    :component-uuid="branch.componentUuid || changelog.componentUuid"
                                    :component-name="branch.componentName"
                                    :branch-uuid="branch.branchUuid"
                                    :branch-name="branch.branchName"
                                />
                                <FindingChangesDisplay :finding-changes="release.findingChanges" />
                            </div>
                        </template>
                    </div>
                </div>

                <!-- AGGREGATED mode: Show top-level aggregated changes -->
                <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                    <p style="margin-bottom: 10px; font-style: italic;">{{ aggregatedDescription }}</p>
                    <FindingChangesDisplayWithAttribution :finding-changes="changelog.findingChanges" :show-attribution="true" />
                </div>
            </n-tab-pane>
        </n-tabs>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ChangelogView'
}
</script>
<script lang="ts" setup>
import { Ref, ref, watch, computed } from 'vue'
import { NTabs, NTabPane, NButton, useNotification } from 'naive-ui'
import { useStore } from 'vuex'
import {
    FindingChangesDisplay,
    FindingChangesDisplayWithAttribution,
    SbomChangesDisplay,
    CodeChangesDisplay,
    ReleaseHeader,
    ProductReleaseHeader,
    ChangelogControls,
    SeverityFilter
} from './changelog'
import {
    fetchComponentChangelogByDate,
    fetchComponentChangelog
} from '../utils/changelogQueries'
import type { ComponentChangelog, NoneReleaseChanges, CodeCommit, ProductReleaseChanges } from '../types/changelog-sealed'
import { exportChangelogToPdf } from '../utils/changelogPdfExport'
import type { ChangelogTab } from '../utils/changelogPdfExport'

async function getComponentChangelog (org: string, aggregationType: string, component?: string,
    branch?: string): Promise<ComponentChangelog | null> {
    if (component) {
        const dateTo = new Date(dateRange.value[1]).toISOString()
        const dateFrom = new Date(dateRange.value[0]).toISOString()
        
        return await fetchComponentChangelogByDate({
            componentUuid: component,
            branchUuid: branch,
            org: org,
            aggregated: aggregationType as 'NONE' | 'AGGREGATED',
            dateFrom: dateFrom,
            dateTo: dateTo
        })
    }
    return null
}

async function getChangelog (org: string, aggregationType: string, release1?: string, release2?: string): Promise<ComponentChangelog | null> {
    if (release1 && release2) {
        return await fetchComponentChangelog({
            release1: release1,
            release2: release2,
            org: org,
            aggregated: aggregationType as 'NONE' | 'AGGREGATED'
        })
    }
    return null
}

const props = defineProps<{
    release1prop?: string,
    release2prop?: string,
    componentprop?: string,
    orgprop: string,
    branchprop?: string,
    componenttypeprop: string,
    isrouterlink?: boolean,
    iscomponentchangelog: boolean
}>()

const aggregationType : Ref<string> = ref('AGGREGATED')

// Date range state (default: last 7 days)
const dateRange = ref<[number, number]>([
    Date.now() - 7 * 24 * 60 * 60 * 1000,
    Date.now()
])

const changelog : Ref<ComponentChangelog | null> = ref(null)

const isRouterLink = props.isrouterlink
const componentType = props.componenttypeprop

const isProduct = computed(() => componentType === 'PRODUCT')
const isDateBased = computed(() => props.iscomponentchangelog)
const hasData = computed(() => {
    const cl = changelog.value
    if (!cl) return false
    if (cl.__typename === 'NoneProductChangelog') {
        return !!(cl.productReleases && cl.productReleases.length > 0)
    }
    return !!(cl.branches && cl.branches.length > 0)
})

const displayBranches = computed((): any[] => {
    if (!hasData.value || !changelog.value) return []
    if (changelog.value.__typename === 'NoneProductChangelog') return []
    return changelog.value.branches
})

// Product NONE changelog: groups sorted newest product release first, and each
// group's child releases sorted newest first. Shallow copies avoid mutating source.
const displayProductReleases = computed<ProductReleaseChanges[]>(() => {
    if (!changelog.value || changelog.value.__typename !== 'NoneProductChangelog') return []
    const byCreatedDesc = (a: { createdDate?: string }, b: { createdDate?: string }) => {
        const aDate = a.createdDate ? new Date(a.createdDate).getTime() : 0
        const bDate = b.createdDate ? new Date(b.createdDate).getTime() : 0
        return bDate - aDate
    }
    return [...changelog.value.productReleases]
        .sort(byCreatedDesc)
        .map(group => ({
            ...group,
            branches: (group.branches || []).map(branch => ({
                ...branch,
                releases: [...(branch.releases || [])].sort(byCreatedDesc)
            }))
        }))
})

const showBranchHeadings = computed(() => {
    return displayBranches.value.length > 1 || isProduct.value
})

const codeTabLabel = computed(() => isProduct.value ? '📝 Component Changes' : '📝 Code Changes')

const aggregatedDescription = computed(() => {
    return isProduct.value
        ? 'Aggregated across all components in the product'
        : 'Aggregated across all active branches'
})

// Stable display order for change-type buckets, mirroring AGGREGATED commitsByType.
const CHANGE_TYPE_ORDER = ['feat', 'fix', 'perf', 'refactor', 'revert', 'build', 'test', 'docs', 'chore', 'ci', 'style']

function formatCodeChanges(release: NoneReleaseChanges): any[] {
    if (!release.commits || release.commits.length === 0) return []
    const groups = new Map<string, CodeCommit[]>()
    for (const commit of release.commits) {
        const ct = commit.changeType || 'other'
        const bucket = groups.get(ct)
        if (bucket) {
            bucket.push(commit)
        } else {
            groups.set(ct, [commit])
        }
    }
    const ordered: { changeType: string, commits: CodeCommit[] }[] = []
    for (const ct of CHANGE_TYPE_ORDER) {
        const commits = groups.get(ct)
        if (commits) {
            ordered.push({ changeType: ct, commits })
            groups.delete(ct)
        }
    }
    // Append any remaining buckets (unknown types, then 'other') in alphabetical order, with 'other' last.
    const remaining = Array.from(groups.entries()).sort(([a], [b]) => {
        if (a === 'other') return 1
        if (b === 'other') return -1
        return a.localeCompare(b)
    })
    for (const [changeType, commits] of remaining) {
        ordered.push({ changeType, commits })
    }
    return ordered
}

interface FlattenedReleaseEntry {
    branch: any
    release: NoneReleaseChanges
    showBranchTag: boolean
}

const flattenedReleases = computed<FlattenedReleaseEntry[]>(() => {
    if (!changelog.value || changelog.value.__typename !== 'NoneChangelog') return []
    const entries: FlattenedReleaseEntry[] = []
    const seenBranches = new Set<string>()
    for (const branch of displayBranches.value) {
        if (!branch.releases) continue
        for (const release of branch.releases) {
            const showBranchTag = !seenBranches.has(branch.branchUuid)
            seenBranches.add(branch.branchUuid)
            entries.push({ branch, release, showBranchTag })
        }
    }
    entries.sort((a, b) => {
        const aDate = a.release.createdDate ? new Date(a.release.createdDate).getTime() : 0
        const bDate = b.release.createdDate ? new Date(b.release.createdDate).getTime() : 0
        return bDate - aDate
    })
    return entries
})

const getAggregatedChangelog = async function () {
    try {
        if (props.iscomponentchangelog) {
            changelog.value = await getComponentChangelog(props.orgprop, aggregationType.value, props.componentprop, props.branchprop)
        } else {
            changelog.value = await getChangelog(props.orgprop, aggregationType.value, props.release1prop, props.release2prop)
        }
    } catch (error: any) {
        const isNetworkError = error?.networkError || error?.message?.includes('Network')
        if (isNetworkError) {
            console.error('Changelog fetch failed due to network error:', error)
        } else {
            console.warn('Changelog query returned no data:', error.message || error)
        }
        changelog.value = null
    }
}


// Watch for prop changes that require refetching
watch(() => [props.release1prop, props.release2prop, props.componentprop, props.branchprop, props.orgprop], async() => {
    await getAggregatedChangelog()
}, { immediate: true })

// Watch aggregationType changes and re-fetch data
watch(aggregationType, async() => {
    await getAggregatedChangelog()
})

const selectedSeverity : Ref<string> = ref('ALL')

const activeTab = ref<string>('code')
const store = useStore()
const notification = useNotification()
const myorg = computed(() => store.getters.myorg)

function handleExportPdf() {
    if (!changelog.value) return
    
    let pdfTitle = ''
    if (changelog.value.componentName) {
        pdfTitle = `${changelog.value.componentName} - Changelog`
    } else {
        pdfTitle = 'Component Changelog'
    }
    
    let dateRangeStr = ''
    if (isDateBased.value) {
        const from = new Date(dateRange.value[0]).toLocaleDateString('en-CA')
        const to = new Date(dateRange.value[1]).toLocaleDateString('en-CA')
        dateRangeStr = `${from} to ${to}`
    } else if (changelog.value.firstRelease && changelog.value.lastRelease) {
        dateRangeStr = `${changelog.value.firstRelease.version} \u2192 ${changelog.value.lastRelease.version}`
    }
    
    const result = exportChangelogToPdf({
        title: pdfTitle,
        orgName: myorg.value?.name || 'Unknown',
        dateRange: dateRangeStr,
        aggregationType: aggregationType.value as 'NONE' | 'AGGREGATED',
        activeTab: activeTab.value as ChangelogTab,
        changelog: changelog.value,
        filenamePrefix: `changelog-${(changelog.value.componentName || 'component').toLowerCase().replace(/\s+/g, '-')}`
    })
    
    if (!result.success) {
        notification.warning({ content: result.message || 'Export failed', duration: 3000 })
    }
}

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.product-release-group {
    margin-bottom: 20px;
}
</style>