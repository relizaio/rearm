<template>
    <div class="container">
        <h1 v-if="isRouterLink">Changelog</h1>
        <!-- Date-based component changelog -->
        <div v-if="componentType === 'COMPONENT' && changelog && props.iscomponentchangelog">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid }}">{{ changelog.name }}</router-link>
                <span> - Component-wide Changes</span>
            </h2>
            
            <!-- Date Range Picker -->
            <div style="margin-top: 16px; margin-bottom: 16px;">
                <n-space align="center">
                    <span style="font-weight: 500;">Date Range:</span>
                    <n-date-picker
                        v-model:value="dateRange"
                        type="daterange"
                        clearable
                        style="width: 300px;"
                    />
                    <n-button type="primary" @click="getAggregatedChangelog">Apply</n-button>
                </n-space>
            </div>
            
            <!-- Aggregation Toggle (Top Level - applies to all tabs) -->
            <div v-if="changelog.branches && changelog.branches.length > 0" style="margin-bottom: 20px; padding: 16px; background: #f5f5f5; border-radius: 4px;">
                <div style="display: flex; align-items: center; gap: 16px;">
                    <span style="font-weight: 600;">Aggregation:</span>
                    <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup">
                        <n-radio-button
                            v-for="abtn in aggregationTypes"
                            :key="abtn.value"
                            :value="abtn.value"
                            :label="abtn.key"
                        />
                    </n-radio-group>
                    <span style="color: #666; font-size: 0.9em; font-style: italic;">
                        Applies to Code, SBOM, and Finding changes
                    </span>
                </div>
            </div>
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <!-- Code Changes tab (if branches data available) -->
                <n-tab-pane name="code" tab="📝 Code Changes">
                    <div style="margin-bottom: 16px; max-width: 30%;">
                        <n-form-item label="Filter By Severity:">
                            <n-select v-model:value="selectedSeverity" :options="severityTypes" />
                        </n-form-item>
                    </div>
                    <!-- AGGREGATED view for component-wide -->
                    <div v-if="aggregationType === 'AGGREGATED'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all active branches</p>
                        <div v-if="changelog.branches && changelog.branches.length > 0">
                            <div v-for="branch in changelog.branches" :key="branch.uuid">
                                <h3>{{ branch.name }}</h3>
                                <CodeChangesDisplay 
                                    :changes="branch.changes" 
                                    :selected-severity="selectedSeverity"
                                />
                            </div>
                        </div>
                        <div v-else style="padding: 20px; color: #999; font-style: italic;">
                            No branches data available for aggregated view
                        </div>
                    </div>
                    
                    <!-- NONE aggregation: per-release view -->
                    <div v-else-if="aggregationType === 'NONE'">
                        <div v-for="branch in changelog.branches" :key="branch.uuid">
                            <h3>{{ branch.name }}</h3>
                            <div v-for="release in branch.releases" :key="release.uuid">
                                <ReleaseHeader 
                                    :uuid="release.uuid"
                                    :version="release.version"
                                    :lifecycle="release.lifecycle"
                                />
                                <CodeChangesDisplay 
                                    :changes="release.changes" 
                                    :selected-severity="selectedSeverity"
                                />
                            </div>
                        </div>
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <!-- NONE mode: Show per-release SBOM changes -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="branch in changelog.branches" :key="branch.uuid">
                            <h3>{{ branch.name }}</h3>
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
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all active branches</p>
                        <SbomChangesDisplay :sbom-changes="changelog.sbomChanges" />
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                    <!-- NONE mode: Show per-release Finding changes -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="branch in changelog.branches" :key="branch.uuid">
                            <h3>{{ branch.name }}</h3>
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
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <p style="margin-bottom: 10px; font-style: italic;">Aggregated across all active branches</p>
                        <FindingChangesDisplay :finding-changes="changelog.findingChanges" />
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
        
        <!-- Branch-based changelog (existing) -->
        <div v-else-if="componentType === 'COMPONENT' && changelog && changelog.branches && changelog.branches.length === 1">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid, branchuuid: changelog.branches[0].uuid }}">{{ changelog.name + '(' + changelog.branches[0].name + ')' }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            
            <!-- Aggregation Toggle (Top Level - applies to all tabs) -->
            <div style="margin-top: 20px; margin-bottom: 20px; padding: 16px; background: #f5f5f5; border-radius: 4px;">
                <div style="display: flex; align-items: center; gap: 16px;">
                    <span style="font-weight: 600;">Aggregation:</span>
                    <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup">
                        <n-radio-button
                            v-for="abtn in aggregationTypes"
                            :key="abtn.value"
                            :value="abtn.value"
                            :label="abtn.key"
                        />
                    </n-radio-group>
                    <span style="color: #666; font-size: 0.9em; font-style: italic;">
                        Applies to Code, SBOM, and Finding changes
                    </span>
                </div>
            </div>
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="code" tab="📝 Code Changes">
                    <div style="margin-bottom: 16px; max-width: 30%;">
                        <n-form-item label="Filter By Severity:">
                            <n-select v-model:value="selectedSeverity" :options="severityTypes" />
                        </n-form-item>
                    </div>
                    
                    <!-- NONE aggregation: per-release view -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.uuid">
                            <ReleaseHeader 
                                :uuid="release.uuid"
                                :version="release.version"
                                :lifecycle="release.lifecycle"
                            />
                            <CodeChangesDisplay 
                                :changes="release.changes" 
                                :selected-severity="selectedSeverity"
                            />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED: combined view -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <CodeChangesDisplay 
                            :changes="changelog.branches[0].changes" 
                            :selected-severity="selectedSeverity"
                        />
                    </div>

                </n-tab-pane>
                
                <n-tab-pane name="sbom" tab="📦 SBOM Changes">
                    <!-- NONE mode: Show per-release SBOM changes -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.uuid">
                            <ReleaseHeader 
                                :uuid="release.uuid"
                                :version="release.version"
                                :lifecycle="release.lifecycle"
                            />
                            <SbomChangesDisplay :sbom-changes="release.sbomChanges" />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <SbomChangesDisplay 
                            :sbom-changes="changelog.sbomChanges" 
                            :show-updated="true"
                        />
                    </div>
                </n-tab-pane>
                
                <n-tab-pane name="vulnerabilities" tab="🔒 Finding Changes">
                    <!-- NONE mode: Show per-release Finding changes -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="release in changelog.branches[0].releases" :key="release.uuid">
                            <ReleaseHeader 
                                :uuid="release.uuid"
                                :version="release.version"
                                :lifecycle="release.lifecycle"
                            />
                            <FindingChangesDisplay :finding-changes="release.findingChanges" />
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <FindingChangesDisplay :finding-changes="changelog.findingChanges" />
                    </div>
                </n-tab-pane>
            </n-tabs>
        </div>
        <div v-if="componentType === 'PRODUCT' && changelog && props.iscomponentchangelog">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid }}">{{ changelog.name }}</router-link>
                <span> - Product-wide Changes</span>
            </h2>
            
            <!-- Date Range Picker -->
            <div style="margin-top: 16px; margin-bottom: 16px;">
                <n-space align="center">
                    <span style="font-weight: 500;">Date Range:</span>
                    <n-date-picker
                        v-model:value="dateRange"
                        type="daterange"
                        clearable
                        style="width: 300px;"
                    />
                    <n-button type="primary" @click="getAggregatedChangelog">Apply</n-button>
                </n-space>
            </div>
            
            <!-- Aggregation Toggle (Top Level - applies to all tabs) -->
            <div style="margin-top: 20px; margin-bottom: 20px; padding: 16px; background: #f5f5f5; border-radius: 4px;">
                <div style="display: flex; align-items: center; gap: 16px;">
                    <span style="font-weight: 600;">Aggregation:</span>
                    <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup">
                        <n-radio-button
                            v-for="abtn in aggregationTypes"
                            :key="abtn.value"
                            :value="abtn.value"
                            :label="abtn.key"
                        />
                    </n-radio-group>
                    <span style="color: #666; font-size: 0.9em; font-style: italic;">
                        Applies to Component, SBOM, and Finding changes
                    </span>
                </div>
            </div>
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="components" tab="📝 Component Changes">
                    <div style="margin-bottom: 16px; max-width: 30%;">
                        <n-form-item label="Filter By Severity:">
                            <n-select v-model:value="selectedSeverity" :options="severityTypes" />
                        </n-form-item>
                    </div>
                    
                    <!-- NONE aggregation: per-component, per-release view -->
                    <div v-if="aggregationType === 'NONE'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <ComponentHeader 
                                :org-uuid="component.org"
                                :component-uuid="component.uuid"
                                :name="component.name"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                            />
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <h4><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link></h4>
                                    <ul>
                                        <li v-for="release in branch.releases" :key="release.uuid">
                                            <h4><router-link :to="{ name: 'ReleaseView', params: {uuid: release.uuid}}">{{release.version}}</router-link></h4>
                                            <CodeChangesDisplay 
                                                :changes="release.changes" 
                                                :selected-severity="selectedSeverity"
                                            />
                                        </li>
                                    </ul>
                                </li>
                            </ul>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED: combined view -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <ComponentHeader 
                                :org-uuid="component.org"
                                :component-uuid="component.uuid"
                                :name="component.name"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                                :branch-count="component.branches?.length"
                            />
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link>
                                    <CodeChangesDisplay 
                                        :changes="branch.changes" 
                                        :selected-severity="selectedSeverity"
                                    />
                                </li>
                            </ul>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED_BY_TICKET: grouped by ticket -->
                    <div v-else-if="aggregationType === 'AGGREGATED_BY_TICKET'">
                        <div v-for="component in changelog.components" :key="component.uuid">
                            <ComponentHeader 
                                :org-uuid="component.org"
                                :component-uuid="component.uuid"
                                :name="component.name"
                                :first-release="component.firstRelease"
                                :last-release="component.lastRelease"
                                :branch-count="component.branches?.length"
                            />
                            <ul>
                                <li v-for="branch in component.branches" :key="branch.uuid">
                                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link>
                                    <div v-for="ticket in branch.tickets" :key="ticket.ticketSubject">
                                        <h4>{{ticket.ticketSubject}}</h4>
                                        <CodeChangesDisplay 
                                            :changes="ticket.changes" 
                                            :selected-severity="selectedSeverity"
                                        />
                                    </div>
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
        
        <!-- Product Release Comparison View (between two specific releases) -->
        <div v-if="componentType === 'PRODUCT' && changelog && !props.iscomponentchangelog">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid }}">{{ changelog.name }}</router-link>
                <span>&nbsp;</span>
                <router-link v-if="changelog.firstRelease" :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link v-if="changelog.lastRelease" :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            
            <!-- Aggregation Toggle (Top Level - applies to all tabs) -->
            <div style="margin-top: 20px; margin-bottom: 20px; padding: 16px; background: #f5f5f5; border-radius: 4px;">
                <div style="display: flex; align-items: center; gap: 16px;">
                    <span style="font-weight: 600;">Aggregation:</span>
                    <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup">
                        <n-radio-button
                            v-for="abtn in aggregationTypes"
                            :key="abtn.value"
                            :value="abtn.value"
                            :label="abtn.key"
                        />
                    </n-radio-group>
                    <span style="color: #666; font-size: 0.9em; font-style: italic;">
                        Applies to Component, SBOM, and Finding changes
                    </span>
                </div>
            </div>
            
            <n-tabs type="line" animated style="margin-top: 20px;">
                <n-tab-pane name="components" tab="📝 Component Changes">
                    <div style="margin-bottom: 16px; max-width: 30%;">
                        <n-form-item label="Filter By Severity:">
                            <n-select v-model:value="selectedSeverity" :options="severityTypes" />
                        </n-form-item>
                    </div>
                    
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
                            <h3>
                                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                                <span v-if="component.firstRelease && component.lastRelease">&nbsp;</span>
                                <router-link v-if="component.firstRelease" :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                                <span v-if="component.firstRelease && component.lastRelease"> - </span>
                                <router-link v-if="component.lastRelease" :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                            </h3>
                            <div v-if="component.sbomChanges && (component.sbomChanges.added?.length > 0 || component.sbomChanges.removed?.length > 0)">
                                <div v-if="component.sbomChanges.added?.length > 0">
                                    <h5 style="color: #18a058;">✓ Added Components ({{ component.sbomChanges.added.length }})</h5>
                                    <ul>
                                        <li v-for="comp in component.sbomChanges.added" :key="comp.purl">
                                            <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                                        </li>
                                    </ul>
                                </div>
                                <div v-if="component.sbomChanges.removed?.length > 0">
                                    <h5 style="color: #d03050;">✗ Removed Components ({{ component.sbomChanges.removed.length }})</h5>
                                    <ul>
                                        <li v-for="comp in component.sbomChanges.removed" :key="comp.purl">
                                            <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                                        </li>
                                    </ul>
                                </div>
                            </div>
                            <div v-else style="padding: 10px; color: #999; font-style: italic;">
                                No SBOM changes in this component
                            </div>
                        </div>
                    </div>
                    
                    <!-- AGGREGATED mode: Show top-level aggregated changes -->
                    <div v-else-if="aggregationType === 'AGGREGATED'">
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
    SbomChangesDisplay, 
    CodeChangesDisplay, 
    ReleaseHeader,
    ComponentHeader 
} from './changelog'

async function getComponentChangelog (org: string, aggregationType: string, component?: string,
    branch?: string) {
    let changelog = {}
    if (component) {
        // For component changelog, use date-based comparison across all branches
        const dateTo = new Date(dateRange.value[1]).toISOString() // Use selected date range
        const dateFrom = new Date(dateRange.value[0]).toISOString()
        
        let fetchRlzParams = {
            componentId: component,
            orgId: org,
            branchId: branch, // Optional: if provided, will use branch-specific view
            aggregated: aggregationType,
            dateFrom: dateFrom,
            dateTo: dateTo
        }
        changelog = await commonFunctions.fetchComponentChangeLog(fetchRlzParams)
    }
    return changelog
}

async function getChangelog (org: string, aggregationType: string, release1?: string, release2?: string) {
    let changelog = {}
    if (release1 && release2) {
        let fetchRlzParams = {
            release1: release1,
            release2: release2,
            org: org,
            aggregated:aggregationType
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

// Date range state (default: last 7 days)
const dateRange = ref<[number, number]>([
    Date.now() - 7 * 24 * 60 * 60 * 1000,
    Date.now()
])

const changelog : Ref<any> = ref({})

const isRouterLink = props.isrouterlink
const componentType = props.componenttypeprop


const getAggregatedChangelog = async function () {
    if (props.iscomponentchangelog) {
        changelog.value = await getComponentChangelog(props.orgprop, aggregationType.value, props.componentprop, props.branchprop)
    } else {
        changelog.value = await getChangelog(props.orgprop, aggregationType.value, props.release1prop, props.release2prop)
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