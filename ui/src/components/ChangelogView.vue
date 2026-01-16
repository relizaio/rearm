<template>
    <div class="container">
        <h1 v-if="isRouterLink">Changelog</h1>
        <div v-if="componentType === 'COMPONENT' && changelog && changelog.branches && changelog.branches.length === 1">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid, branchuuid: changelog.branches[0].uuid }}">{{ changelog.name + '(' + changelog.branches[0].name + ')' }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            
            <n-tabs type="line" animated style="margin-top: 20px;" @update:value="handleTabChange">
                <n-tab-pane name="code" tab="📝 Code Changes">
                    <div style="margin-bottom: 16px;">
                        Aggregation:
                        <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup" style="margin-left: 8px;">
                            <n-radio-button
                                v-for="abtn in aggregationTypes"
                                :key="abtn.value"
                                :value="abtn.value"
                                :label="abtn.key"
                                @change="getAggregatedChangelog"
                            />
                        </n-radio-group>
                    </div>
                    <div style="margin-bottom: 16px; max-width: 30%;">
                        <n-form-item label="Filter By Severity:">
                            <n-select v-model:value="selectedSeverity" :options="severityTypes" />
                        </n-form-item>
                    </div>
                    
                    <!-- NONE aggregation: per-release view -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.uuid">
                            <h3>
                                <router-link :to="{ name: 'ReleaseView', params: {uuid: release.uuid}}">{{release.version}}</router-link>
                                <n-tag v-if="release.lifecycle === 'REJECTED'" type="error" size="small" style="margin-left: 8px;">REJECTED</n-tag>
                                <n-tag v-else-if="release.lifecycle === 'PENDING'" type="warning" size="small" style="margin-left: 8px;">PENDING</n-tag>
                            </h3>
                            <ul>
                                <div v-for="change in release.changes" :key="change.changeType">
                                    <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                        <h4>{{change.changeType}}</h4>
                                        <ul>
                                            <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                                <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                                <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                    <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                                </span>
                                            </li>
                                        </ul>
                                    </li>
                                </div>
                            </ul>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED: combined view -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <ul>
                            <div v-for="change in changelog.branches[0].changes" :key="change.changeType">
                                <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                    <h4>{{change.changeType}}</h4>
                                    <ul>
                                        <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                            <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                            <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                            </span>
                                        </li>
                                    </ul>
                                </li>
                            </div>
                        </ul>
                    </div>

                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <div v-if="loadingSbom" style="padding: 40px; text-align: center;">
                        <n-spin size="large" />
                        <p style="margin-top: 16px; color: #666;">Loading SBOM changes...</p>
                    </div>
                    <div v-else-if="changelog.sbomChanges && (processedSbomChanges.added.length > 0 || processedSbomChanges.removed.length > 0 || processedSbomChanges.updated.length > 0)">
                        <div v-if="processedSbomChanges.updated.length > 0">
                            <h4 style="color: #f0a020;">🔄 Updated Components ({{ processedSbomChanges.updated.length }})</h4>
                            <ul>
                                <li v-for="comp in processedSbomChanges.updated" :key="comp.purl">
                                    <strong>{{ comp.purl }}</strong>: {{ comp.oldVersion }} → {{ comp.newVersion }}
                                </li>
                            </ul>
                        </div>
                        <div v-if="processedSbomChanges.added.length > 0">
                            <h4 style="color: #18a058;">✓ Added Components ({{ processedSbomChanges.added.length }})</h4>
                            <ul>
                                <li v-for="comp in processedSbomChanges.added" :key="comp.purl">
                                    <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                                </li>
                            </ul>
                        </div>
                        <div v-if="processedSbomChanges.removed.length > 0">
                            <h4 style="color: #d03050;">✗ Removed Components ({{ processedSbomChanges.removed.length }})</h4>
                            <ul>
                                <li v-for="comp in processedSbomChanges.removed" :key="comp.purl">
                                    <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                                </li>
                            </ul>
                        </div>
                    </div>
                    <div v-else-if="!loadingSbom" style="padding: 20px; text-align: center; color: #999;">
                        No SBOM changes detected
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Vulnerability Changes">
                    <div v-if="loadingVulnerabilities" style="padding: 40px; text-align: center;">
                        <n-spin size="large" />
                        <p style="margin-top: 16px; color: #666;">Loading vulnerability changes...</p>
                    </div>
                    <div v-else-if="changelog.vulnerabilityChanges && changelog.vulnerabilityChanges.summary">
                        <div style="margin-bottom: 10px;">
                            <n-tag type="error" size="small">{{ changelog.vulnerabilityChanges.summary.totalAppeared }} Appeared</n-tag>
                            <n-tag type="success" size="small" style="margin-left: 8px;">{{ changelog.vulnerabilityChanges.summary.totalResolved }} Resolved</n-tag>
                            <n-tag type="warning" size="small" style="margin-left: 8px;">{{ changelog.vulnerabilityChanges.summary.totalSeverityChanged }} Severity Changed</n-tag>
                            <n-tag :type="changelog.vulnerabilityChanges.summary.netChange > 0 ? 'error' : 'success'" size="small" style="margin-left: 8px;">
                                Net: {{ changelog.vulnerabilityChanges.summary.netChange > 0 ? '+' : '' }}{{ changelog.vulnerabilityChanges.summary.netChange }}
                            </n-tag>
                        </div>
                        
                        <div v-if="changelog.vulnerabilityChanges.appeared?.length > 0">
                            <h4 style="color: #d03050;">⚠️ New Vulnerabilities ({{ changelog.vulnerabilityChanges.appeared.length }})</h4>
                            <ul>
                                <li v-for="vuln in changelog.vulnerabilityChanges.appeared" :key="vuln.findingId + vuln.affectedComponent">
                                    <n-tag :type="getSeverityTagType(vuln.severity)" size="small">{{ vuln.severity }}</n-tag>
                                    <strong>{{ vuln.findingId }}</strong>
                                    <span v-if="vuln.aliases?.length > 0"> ({{ vuln.aliases.join(', ') }})</span>
                                    in <code>{{ vuln.affectedComponent }}</code>
                                </li>
                            </ul>
                        </div>

                        <div v-if="changelog.vulnerabilityChanges.resolved?.length > 0">
                            <h4 style="color: #18a058;">✓ Resolved Vulnerabilities ({{ changelog.vulnerabilityChanges.resolved.length }})</h4>
                            <ul>
                                <li v-for="vuln in changelog.vulnerabilityChanges.resolved" :key="vuln.findingId + vuln.affectedComponent">
                                    <n-tag :type="getSeverityTagType(vuln.severity)" size="small">{{ vuln.severity }}</n-tag>
                                    <strong>{{ vuln.findingId }}</strong>
                                    <span v-if="vuln.aliases?.length > 0"> ({{ vuln.aliases.join(', ') }})</span>
                                    in <code>{{ vuln.affectedComponent }}</code>
                                </li>
                            </ul>
                        </div>

                        <div v-if="changelog.vulnerabilityChanges.severityChanged?.length > 0">
                            <h4 style="color: #f0a020;">⚡ Severity Changed ({{ changelog.vulnerabilityChanges.severityChanged.length }})</h4>
                            <ul>
                                <li v-for="vuln in changelog.vulnerabilityChanges.severityChanged" :key="vuln.findingId + vuln.affectedComponent">
                                    <strong>{{ vuln.findingId }}</strong>
                                    <span v-if="vuln.aliases?.length > 0"> ({{ vuln.aliases.join(', ') }})</span>:
                                    <n-tag :type="getSeverityTagType(vuln.previousSeverity)" size="small">{{ vuln.previousSeverity }}</n-tag>
                                    →
                                    <n-tag :type="getSeverityTagType(vuln.severity)" size="small">{{ vuln.severity }}</n-tag>
                                    in <code>{{ vuln.affectedComponent }}</code>
                                </li>
                            </ul>
                        </div>
                    </div>
                    <div v-else style="padding: 20px; text-align: center; color: #999;">
                        No vulnerability changes detected
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
        <div v-if="componentType === 'PRODUCT'">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid }}">{{ changelog.name }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="components" tab="📝 Component Changes">
                    <div style="margin-bottom: 16px;">
                        Aggregation:
                        <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup" style="margin-left: 8px;">
                            <n-radio-button
                                v-for="abtn in aggregationTypes"
                                :key="abtn.value"
                                :value="abtn.value"
                                :label="abtn.key"
                                @change="getAggregatedChangelog"
                            />
                        </n-radio-group>
                    </div>
                    <div style="margin-bottom: 16px; max-width: 30%;">
                        <n-form-item label="Filter By Severity:">
                            <n-select v-model:value="selectedSeverity" :options="severityTypes" />
                        </n-form-item>
                    </div>
                    
                    <!-- NONE aggregation: per-product-release view -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="product in changelog.components" :key="product.uuid">
                            <h3><router-link :to="{ name: 'ReleaseView', params: {uuid: product.uuid}}">{{product.name}}</router-link></h3>
                            <ul>
                                <li v-for="component in product.components" :key="component.uuid">
                                    <h4><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: component.branches[0].uuid }}">{{ component.name + '(' + component.branches[0].name + ')' }}</router-link></h4>
                                    <ul>
                                        <li v-for="release in component.branches[0].releases" :key="release.uuid">
                                            <h4><router-link :to="{ name: 'ReleaseView', params: {uuid: release.uuid}}">{{release.version}}</router-link></h4>
                                            <ul>
                                                <div v-for="change in release.changes" :key="change.changeType">
                                                    <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                                        <h4>{{change.changeType}}</h4>
                                                        <ul>
                                                            <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                                                <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                                                <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                                    <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                                                </span>
                                                            </li>
                                                        </ul>
                                                    </li>
                                                </div>
                                            </ul>
                                        </li>
                                    </ul>
                                </li>
                            </ul>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED: combined view -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <h3 v-if="component.org">
                                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                                <span>&nbsp;</span>
                                <router-link :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                                <span> - </span>
                                <router-link :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                            </h3>
                            <h6 v-if="component.branches && component.branches.length > 1">* branch changed between product releases</h6>
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link>
                                    <ul>
                                        <div v-for="change in branch.changes" :key="change.changeType">
                                            <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                                <h4>{{change.changeType}}</h4>
                                                <ul>
                                                    <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                                        <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                                        <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                            <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                                        </span>
                                                    </li>
                                                </ul>
                                            </li>
                                        </div>
                                    </ul>
                                </li>
                            </ul>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED_BY_TICKET: grouped by ticket -->
                    <div v-else-if="aggregationType === 'AGGREGATED_BY_TICKET'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <h3 v-if="component.org">
                                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                                <span>&nbsp;</span>
                                <router-link :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                                <span> - </span>
                                <router-link :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                            </h3>
                            <h6 v-if="component.branches && component.branches.length > 1">* branch changed between product releases</h6>
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link>
                                    <div v-for="ticket in branch.tickets" :key="ticket.ticketSubject">
                                        <h4>{{ticket.ticketSubject}}</h4>
                                        <ul>
                                            <div v-for="change in ticket.changes" :key="change.changeType">
                                                <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                                    <h4>{{change.changeType}}</h4>
                                                    <ul>
                                                        <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                                            <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                                            <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                                <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                                            </span>
                                                        </li>
                                                    </ul>
                                                </li>
                                            </div>
                                        </ul>
                                    </div>
                                </li>
                            </ul>
                        </div>
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <div v-if="changelog.sbomChanges && (changelog.sbomChanges.added?.length > 0 || changelog.sbomChanges.removed?.length > 0)">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all components in the product</p>
                        <div v-if="changelog.sbomChanges.added?.length > 0">
                            <h4 style="color: #18a058;">✓ Added Components ({{ changelog.sbomChanges.added.length }})</h4>
                            <ul>
                                <li v-for="comp in changelog.sbomChanges.added" :key="comp.purl">
                                    <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                                </li>
                            </ul>
                        </div>
                        <div v-if="changelog.sbomChanges.removed?.length > 0">
                            <h4 style="color: #d03050;">✗ Removed Components ({{ changelog.sbomChanges.removed.length }})</h4>
                            <ul>
                                <li v-for="comp in changelog.sbomChanges.removed" :key="comp.purl">
                                    <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                                </li>
                            </ul>
                        </div>
                    </div>
                    <div v-else style="padding: 20px; text-align: center; color: #999;">
                        No SBOM changes detected
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Vulnerability Changes">
                    <div v-if="changelog.vulnerabilityChanges && changelog.vulnerabilityChanges.summary">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all components in the product</p>
                        <div style="margin-bottom: 10px;">
                            <n-tag type="error" size="small">{{ changelog.vulnerabilityChanges.summary.totalAppeared }} Total Vulnerabilities</n-tag>
                            <n-tag type="success" size="small" style="margin-left: 8px;">{{ changelog.vulnerabilityChanges.summary.totalResolved }} Resolved</n-tag>
                            <n-tag type="warning" size="small" style="margin-left: 8px;">{{ changelog.vulnerabilityChanges.summary.totalSeverityChanged }} Severity Changed</n-tag>
                        </div>
                        
                        <div v-if="changelog.vulnerabilityChanges.appeared?.length > 0">
                            <h4 style="color: #d03050;">⚠️ Current Vulnerabilities ({{ changelog.vulnerabilityChanges.appeared.length }})</h4>
                            <ul>
                                <li v-for="vuln in changelog.vulnerabilityChanges.appeared.slice(0, 20)" :key="vuln.findingId + vuln.affectedComponent">
                                    <n-tag :type="getSeverityTagType(vuln.severity)" size="small">{{ vuln.severity }}</n-tag>
                                    <strong>{{ vuln.findingId }}</strong>
                                    <span v-if="vuln.aliases?.length > 0"> ({{ vuln.aliases.join(', ') }})</span>
                                    in <code>{{ vuln.affectedComponent }}</code>
                                </li>
                            </ul>
                            <p v-if="changelog.vulnerabilityChanges.appeared.length > 20" style="font-style: italic;">
                                ... and {{ changelog.vulnerabilityChanges.appeared.length - 20 }} more
                            </p>
                        </div>
                    </div>
                    <div v-else-if="!loadingVulnerabilities" style="padding: 20px; text-align: center; color: #999;">
                        No vulnerability changes detected
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ChangelogView'
}
</script>
<script lang="ts" setup>
import commonFunctions from '../utils/commonFunctions'
import { Ref, ref, watch, computed } from 'vue'
import { NRadioGroup, NRadioButton, NSelect, NFormItem, NTabs, NTabPane, NTag, NSpin } from 'naive-ui'

async function getComponentChangelog (org: string, aggregationType: string, component?: string,
    branch?: string) {
    let changelog = {}
    if (component && branch) {
        let fetchRlzParams = {
            componentId: component,
            orgId: org,
            branchId: branch,
            aggregated: aggregationType,
        }
        changelog = await commonFunctions.fetchComponentChangeLog(fetchRlzParams)
    }
    return changelog
}

async function getChangelog (org: string, aggregationType: string, release1?: string, release2?: string, includeSbomAndVuln: boolean = false) {
    let changelog = {}
    if (release1 && release2) {
        let fetchRlzParams = {
            release1: release1,
            release2: release2,
            org: org,
            aggregated:aggregationType,
            includeSbomAndVuln: includeSbomAndVuln
        }
        changelog = await commonFunctions.fetchChangelogBetweenReleases(fetchRlzParams)
    }
    return changelog
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

const aggregationType : Ref<string> = ref('NONE')
const aggregationTypes = [
    { key: 'NONE', value: 'NONE' },
    { key: 'AGGREGATED', value: 'AGGREGATED' }
]

const changelog : Ref<any> = ref({})
const loadingSbom = ref(false)
const loadingVulnerabilities = ref(false)
const sbomLoaded = ref(false)
const vulnerabilitiesLoaded = ref(false)

const isRouterLink = props.isrouterlink
const componentType = props.componenttypeprop

// Process SBOM changes to detect version updates
const processedSbomChanges = computed(() => {
    if (!changelog.value.sbomChanges) {
        return { added: [], removed: [], updated: [] }
    }

    const added = changelog.value.sbomChanges.added || []
    const removed = changelog.value.sbomChanges.removed || []
    
    // Extract package names without versions from purls
    const getPackageName = (purl: string) => {
        // Extract package name from purl (e.g., pkg:npm/lodash@4.17.21 -> pkg:npm/lodash)
        const atIndex = purl.lastIndexOf('@')
        return atIndex > 0 ? purl.substring(0, atIndex) : purl
    }
    
    // Build maps of package names to components
    const addedMap = new Map()
    const removedMap = new Map()
    
    added.forEach((comp: any) => {
        const pkgName = getPackageName(comp.purl)
        addedMap.set(pkgName, comp)
    })
    
    removed.forEach((comp: any) => {
        const pkgName = getPackageName(comp.purl)
        removedMap.set(pkgName, comp)
    })
    
    // Find version updates (packages in both added and removed)
    const updated: any[] = []
    const trulyAdded: any[] = []
    const trulyRemoved: any[] = []
    
    addedMap.forEach((newComp, pkgName) => {
        if (removedMap.has(pkgName)) {
            const oldComp = removedMap.get(pkgName)
            updated.push({
                purl: pkgName,
                oldVersion: oldComp.version,
                newVersion: newComp.version
            })
        } else {
            trulyAdded.push(newComp)
        }
    })
    
    removedMap.forEach((comp, pkgName) => {
        if (!addedMap.has(pkgName)) {
            trulyRemoved.push(comp)
        }
    })
    
    return {
        added: trulyAdded,
        removed: trulyRemoved,
        updated: updated
    }
})

const getAggregatedChangelog = async function () {
    // Reset lazy-loaded data flags when fetching new changelog
    sbomLoaded.value = false
    vulnerabilitiesLoaded.value = false
    
    if (props.iscomponentchangelog) {
        changelog.value = await getComponentChangelog(props.orgprop, aggregationType.value, props.componentprop, props.branchprop)
    } else {
        // Initial load: only fetch code changes, not SBOM/vulnerabilities
        changelog.value = await getChangelog(props.orgprop, aggregationType.value, props.release1prop, props.release2prop, false)
    }
}

const loadSbomChanges = async function () {
    if (sbomLoaded.value || loadingSbom.value) return
    
    loadingSbom.value = true
    try {
        const fullChangelog: any = await getChangelog(props.orgprop, aggregationType.value, props.release1prop, props.release2prop, true)
        // Create a new object to trigger Vue reactivity
        changelog.value = {
            ...changelog.value,
            sbomChanges: fullChangelog.sbomChanges
        }
        sbomLoaded.value = true
    } finally {
        loadingSbom.value = false
    }
}

const loadVulnerabilityChanges = async function () {
    if (vulnerabilitiesLoaded.value || loadingVulnerabilities.value) return
    
    loadingVulnerabilities.value = true
    try {
        const fullChangelog: any = await getChangelog(props.orgprop, aggregationType.value, props.release1prop, props.release2prop, true)
        // Create a new object to trigger Vue reactivity
        changelog.value = {
            ...changelog.value,
            vulnerabilityChanges: fullChangelog.vulnerabilityChanges
        }
        vulnerabilitiesLoaded.value = true
    } finally {
        loadingVulnerabilities.value = false
    }
}

const handleTabChange = async function (tabName: string) {
    if (tabName === 'sbom' && !sbomLoaded.value) {
        await loadSbomChanges()
    } else if (tabName === 'vulnerabilities' && !vulnerabilitiesLoaded.value) {
        await loadVulnerabilityChanges()
    }
}

watch(props, async() => {
    await getAggregatedChangelog()
}, { immediate: true })

const selectedSeverity : Ref<string> = ref('ALL')
const severityTypes = [
    { label: 'ALL', value: 'ALL' },
    { label: 'BREAKING CHANGES', value: 'BREAKING CHANGES' },
    { label: 'Bug Fixes', value: 'Bug Fixes' },
    { label: 'Features', value: 'Features' },
    { label: 'Performance Improvements', value: 'Performance Improvements' },
    { label: 'Reverts', value: 'Reverts' },
    { label: 'Code Refactoring', value: 'Code Refactoring' },
    { label: 'Builds', value: 'Builds' },
    { label: 'Tests', value: 'Tests' },
    { label: 'Chores', value: 'Chores' },
    { label: 'Continuous Integration', value: 'Continuous Integration' },
    { label: 'Styles', value: 'Styles' },
    { label: 'Others', value: 'Others' },
]

function getSeverityTagType(severity: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (severity) {
        case 'CRITICAL':
            return 'error'
        case 'HIGH':
            return 'error'
        case 'MEDIUM':
            return 'warning'
        case 'LOW':
            return 'info'
        case 'UNASSIGNED':
            return 'default'
        default:
            return 'default'
    }
}
</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
</style>