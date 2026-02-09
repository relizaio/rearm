<template>
    <div class="container">
        <h1 v-if="isRouterLink">Changelog</h1>
        <!-- Date-based component changelog -->
        <div v-if="componentType === 'COMPONENT' && props.iscomponentchangelog">
            <h2 v-if="changelog && changelog.orgUuid">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.orgUuid, compuuid: changelog.componentUuid }}">{{ changelog.componentName }}</router-link>
                <span> - Component-wide Changes</span>
            </h2>
            
            <ChangelogControls
                v-model:dateRange="dateRange"
                v-model:aggregationType="aggregationType"
                :show-aggregation="changelog && changelog.branches && changelog.branches.length > 0"
                @apply="getAggregatedChangelog"
            />
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <!-- Code Changes tab -->
                <n-tab-pane name="code" tab="📝 Code Changes">
                    <SeverityFilter v-model:selectedSeverity="selectedSeverity" />
                    
                    <div v-if="!changelog || !changelog.branches || changelog.branches.length === 0" style="padding: 40px; text-align: center; color: #999;">
                        <p style="font-size: 16px; margin-bottom: 10px;">No code changes available for the selected date range</p>
                        <p style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
                    </div>
                    
                    <!-- AGGREGATED view for component-wide -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all active branches</p>
                        <div v-for="branch in changelog.branches" :key="branch.branchUuid">
                            <h3>{{ branch.branchName }}</h3>
                            <CodeChangesDisplay 
                                :changes="branch.commitsByType" 
                                :selected-severity="selectedSeverity"
                            />
                        </div>
                    </div>
                    
                    <!-- NONE aggregation: per-release view -->
                    <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                        <div v-for="branch in changelog.branches" :key="branch.branchUuid">
                            <h3>{{ branch.branchName }}</h3>
                            <div v-for="release in branch.releases" :key="release.releaseUuid">
                                <ReleaseHeader 
                                    :uuid="release.releaseUuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                />
                                <CodeChangesDisplay 
                                    :changes="[{changeType: 'all', commits: release.commits}]" 
                                    :selected-severity="selectedSeverity"
                                />
                            </div>
                        </div>
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <div v-if="!changelog || !changelog.branches || changelog.branches.length === 0" style="padding: 40px; text-align: center; color: #999;">
                        <p style="font-size: 16px; margin-bottom: 10px;">No SBOM changes available for the selected date range</p>
                        <p style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
                    </div>
                    
                    <!-- NONE mode: Show per-release SBOM changes -->
                    <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                        <div v-for="branch in changelog.branches" :key="branch.branchUuid">
                            <h3>{{ branch.branchName }}</h3>
                            <div v-for="release in branch.releases" :key="release.releaseUuid">
                                <ReleaseHeader 
                                    :uuid="release.releaseUuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                />
                                <SbomChangesDisplay :sbom-changes="changelog.sbomChanges.find(s => s.releaseUuid === release.releaseUuid)" />
                            </div>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all active branches</p>
                        <SbomChangesDisplay :sbom-changes="changelog.sbomChanges" :show-attribution="true" />
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                    <div v-if="!changelog || !changelog.branches || changelog.branches.length === 0">
                        <FindingChangesDisplayWithAttribution />
                    </div>
                    
                    <!-- NONE mode: Show per-release Finding changes -->
                    <div v-else-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                        <div v-for="branch in changelog.branches" :key="branch.branchUuid">
                            <h3>{{ branch.branchName }}</h3>
                            <div v-for="release in branch.releases" :key="release.releaseUuid">
                                <ReleaseHeader 
                                    :uuid="release.releaseUuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                />
                                <FindingChangesDisplay :finding-changes="changelog.findingChanges.find(f => f.releaseUuid === release.releaseUuid)" />
                            </div>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all active branches</p>
                        <FindingChangesDisplayWithAttribution :finding-changes="changelog.findingChanges" :show-attribution="true" />
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
        
        <!-- Branch-based changelog (between two releases) -->
        <div v-else-if="componentType === 'COMPONENT' && changelog && changelog.branches && changelog.branches.length === 1">
            <h2 v-if="changelog.orgUuid">
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
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="code" tab="📝 Code Changes">
                    <SeverityFilter v-model:selectedSeverity="selectedSeverity" />
                    
                    <!-- NONE aggregation: per-release view -->
                    <div v-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.releaseUuid">
                            <ReleaseHeader 
                                :uuid="release.releaseUuid"
                                :version="release.version"
                                :lifecycle="release.lifecycle"
                            />
                            <CodeChangesDisplay 
                                :changes="[{changeType: 'all', commits: release.commits}]" 
                                :selected-severity="selectedSeverity"
                            />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED: combined view -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                        <CodeChangesDisplay 
                            :changes="changelog.branches[0].commitsByType" 
                            :selected-severity="selectedSeverity"
                        />
                    </div>

                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <!-- NONE mode: Show per-release SBOM changes -->
                    <div v-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.releaseUuid">
                            <ReleaseHeader 
                                :uuid="release.releaseUuid"
                                :version="release.version"
                                :lifecycle="release.lifecycle"
                            />
                            <SbomChangesDisplay :sbom-changes="changelog.sbomChanges.find(s => s.releaseUuid === release.releaseUuid)" />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                        <SbomChangesDisplay 
                            :sbom-changes="changelog.sbomChanges" 
                            :show-attribution="true"
                        />
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                    <!-- NONE mode: Show per-release Finding changes -->
                    <div v-if="aggregationType === 'NONE' && changelog.__typename === 'NoneChangelog'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.releaseUuid">
                            <ReleaseHeader 
                                :uuid="release.releaseUuid"
                                :version="release.version"
                                :lifecycle="release.lifecycle"
                            />
                            <FindingChangesDisplay :finding-changes="changelog.findingChanges.find(f => f.releaseUuid === release.releaseUuid)" />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog.__typename === 'AggregatedChangelog'">
                        <FindingChangesDisplayWithAttribution :finding-changes="changelog.findingChanges" :show-attribution="true" />
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
        <div v-if="componentType === 'PRODUCT' && props.iscomponentchangelog">
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
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="components" tab="📝 Component Changes">
                    <SeverityFilter v-model:selectedSeverity="selectedSeverity" />
                    
                    <!-- NONE aggregation: per-component, per-release view -->
                    <div v-if="aggregationType === 'NONE' && changelog && changelog.__typename === 'NoneChangelog' && changelog.components">
                        <div v-for="component in changelog.components" :key="component.componentUuid">
                            <ComponentHeader 
                                :org-uuid="component.orgUuid"
                                :component-uuid="component.componentUuid"
                                :name="component.componentName"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                            />
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.branchUuid">
                                    <h4><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.orgUuid, compuuid: component.componentUuid, branchuuid: branch.branchUuid }}">{{ branch.branchName }}</router-link></h4>
                                    <ul>
                                        <li v-for="release in branch.releases" :key="release.releaseUuid">
                                            <h4><router-link :to="{ name: 'ReleaseView', params: {uuid: release.releaseUuid}}">{{release.version}}</router-link></h4>
                                            <CodeChangesDisplay 
                                                :changes="release.commits ? [{ changeType: 'others', commits: release.commits }] : []" 
                                                :selected-severity="selectedSeverity"
                                            />
                                        </li>
                                    </ul>
                                </li>
                            </ul>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED: combined view for single component or product -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog && changelog.__typename === 'AggregatedChangelog'">
                        <!-- Single component: branches are directly on changelog -->
                        <ul v-if="changelog.branches && changelog.branches.length > 0">
                            <li v-for="branch in changelog.branches" :key="branch.branchUuid">
                                <h4>{{ branch.branchName }}</h4>
                                <CodeChangesDisplay 
                                    v-if="branch.commitsByType && branch.commitsByType.length > 0"
                                    :changes="branch.commitsByType" 
                                    :selected-severity="selectedSeverity"
                                />
                            </li>
                        </ul>
                        <!-- Product with multiple components -->
                        <div v-else-if="changelog.components" v-for="component in changelog.components" :key="component.componentUuid">
                            <ComponentHeader 
                                :org-uuid="component.orgUuid"
                                :component-uuid="component.componentUuid"
                                :name="component.componentName"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                                :branch-count="component.branches?.length"
                            />
                            <ul v-if="component.branches && component.branches.length > 0">
                                <li v-for="branch in component.branches" :key="branch.branchUuid">
                                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.orgUuid, compuuid: component.componentUuid, branchuuid: branch.branchUuid }}">{{ branch.branchName }}</router-link>
                                    <CodeChangesDisplay 
                                        v-if="branch.commitsByType && branch.commitsByType.length > 0"
                                        :changes="branch.commitsByType" 
                                        :selected-severity="selectedSeverity"
                                    />
                                </li>
                            </ul>
                        </div>
                    </div>
                    
                    <div v-else style="padding: 40px; text-align: center; color: #999;">
                        <p style="font-size: 16px; margin-bottom: 10px;">No component changes available for the selected date range</p>
                        <p style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <!-- NONE mode: Show per-component SBOM changes -->
                    <div v-if="aggregationType === 'NONE' && changelog && changelog.__typename === 'NoneChangelog' && changelog.components">
                        <div v-for="component in changelog.components" :key="component.componentUuid">
                            <ComponentHeader 
                                :org-uuid="component.orgUuid"
                                :component-uuid="component.componentUuid"
                                :name="component.componentName"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                            />
                            <div v-for="branch in component.branches" :key="branch.branchUuid">
                                <h4 class="branch-name">{{ branch.branchName }}</h4>
                                <div v-for="release in branch.releases" :key="release.releaseUuid">
                                    <ReleaseHeader
                                        :uuid="release.releaseUuid"
                                        :version="release.version"
                                        :lifecycle="release.lifecycle"
                                    />
                                    <SbomChangesDisplay :sbom-changes="component.sbomChanges.find((s: any) => s.releaseUuid === release.releaseUuid)" />
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog && changelog.__typename === 'AggregatedChangelog'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all components in the product</p>
                        <SbomChangesDisplay :sbom-changes="changelog.sbomChanges" :show-attribution="true" />
                    </div>
                    
                    <div v-else style="padding: 40px; text-align: center; color: #999;">
                        <p style="font-size: 16px; margin-bottom: 10px;">No SBOM changes available for the selected date range</p>
                        <p style="font-size: 14px;">Try selecting a different date range or check if there are any releases in this period</p>
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                    <!-- NONE mode: Show per-component Finding changes -->
                    <div v-if="aggregationType === 'NONE' && changelog && changelog.__typename === 'NoneChangelog' && changelog.components">
                        <div v-for="component in changelog.components" :key="component.componentUuid">
                            <ComponentHeader 
                                :org-uuid="component.orgUuid"
                                :component-uuid="component.componentUuid"
                                :name="component.componentName"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                            />
                            <div v-for="branch in component.branches" :key="branch.branchUuid">
                                <h4 class="branch-name">{{ branch.branchName }}</h4>
                                <div v-for="release in branch.releases" :key="release.releaseUuid">
                                    <ReleaseHeader
                                        :uuid="release.releaseUuid"
                                        :version="release.version"
                                        :lifecycle="release.lifecycle"
                                    />
                                    <FindingChangesDisplay :finding-changes="component.findingChanges.find((f: any) => f.releaseUuid === release.releaseUuid)" />
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED' && changelog && changelog.__typename === 'AggregatedChangelog'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all components in the product</p>
                        <FindingChangesDisplayWithAttribution :finding-changes="changelog.findingChanges" :show-attribution="true" />
                    </div>
                    
                    <div v-else>
                        <FindingChangesDisplayWithAttribution />
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
        
        <!-- Product Release Comparison View (between two specific releases) -->
        <div v-if="componentType === 'PRODUCT' && changelog && !props.iscomponentchangelog">
            <h2 v-if="changelog.orgUuid">
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
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="components" tab="📝 Component Changes">
                    <SeverityFilter v-model:selectedSeverity="selectedSeverity" />
                    
                    <!-- NONE aggregation: per-component, per-release view -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <h3 v-if="component.org">
                                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                                <span v-if="component.firstRelease && component.lastRelease">
                                    <span>&nbsp;</span>
                                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                                    <span> - </span>
                                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                                </span>
                            </h3>
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <h4><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link></h4>
                                    <ul>
                                        <li v-for="release in branch.releases" :key="release.uuid">
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
                    
                    <!-- AGGREGATED view: component-level aggregation -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <h3 v-if="component.org">
                                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                                <span v-if="component.firstRelease && component.lastRelease">
                                    <span>&nbsp;</span>
                                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                                    <span> - </span>
                                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                                </span>
                            </h3>
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <h4><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link></h4>
                                    <ul>
                                        <div v-for="change in branch.changes" :key="change.changeType">
                                            <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                                <h5>{{change.changeType}}</h5>
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
                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <!-- NONE mode: Show per-component SBOM changes -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <ComponentHeader 
                                :org-uuid="component.org"
                                :component-uuid="component.uuid"
                                :name="component.name"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                            />
                            <SbomChangesDisplay :sbom-changes="component.sbomChanges" />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all components in the product</p>
                        <SbomChangesDisplay :sbom-changes="changelog.sbomChanges" />
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                    <!-- NONE mode: Show per-component Finding changes -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <ComponentHeader 
                                :org-uuid="component.org"
                                :component-uuid="component.uuid"
                                :name="component.name"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                            />
                            <FindingChangesDisplay :finding-changes="component.findingChanges" />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all components in the product</p>
                        <FindingChangesDisplay :finding-changes="changelog.findingChanges" />
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
import { NRadioGroup, NRadioButton, NSelect, NFormItem, NTabs, NTabPane, NTag, NSpin, NDatePicker, NSpace, NButton } from 'naive-ui'
import { 
    FindingChangesDisplay,
    FindingChangesDisplayWithAttribution,
    SbomChangesDisplay, 
    CodeChangesDisplay, 
    ReleaseHeader,
    ComponentHeader,
    ChangelogControls,
    SeverityFilter
} from './changelog'
import { 
    fetchComponentChangelogByDate, 
    fetchComponentChangelog 
} from '../utils/changelogQueries'
import type { ComponentChangelog } from '../types/changelog-sealed'

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
const aggregationTypes = [
    { key: 'NONE', value: 'NONE' },
    { key: 'AGGREGATED', value: 'AGGREGATED' }
]

// Date range state (default: last 7 days)
const dateRange = ref<[number, number]>([
    Date.now() - 7 * 24 * 60 * 60 * 1000,
    Date.now()
])

const changelog : Ref<any> = ref({})

const isRouterLink = props.isrouterlink
const componentType = props.componenttypeprop


const getAggregatedChangelog = async function () {
    try {
        if (props.iscomponentchangelog) {
            changelog.value = await getComponentChangelog(props.orgprop, aggregationType.value, props.componentprop, props.branchprop)
        } else {
            changelog.value = await getChangelog(props.orgprop, aggregationType.value, props.release1prop, props.release2prop)
        }
    } catch (error: any) {
        console.warn('Changelog query returned no data:', error.message || error)
        // Set changelog to null so the "no data" message displays
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