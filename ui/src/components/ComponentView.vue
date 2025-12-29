<template>
    <div class="componentOuterWrapper">
        <n-grid x-gap="8" cols="10">
            <n-gi span="10">
                <n-grid x-gap="12" cols="2">
                    <n-gi>
                        <releases-per-day-chart
                            :type="selectedBranchUuid ? 'BRANCH' : 'COMPONENT'"
                            :component-uuid="selectedBranchUuid ? undefined : componentData?.uuid"
                            :branch-uuid="selectedBranchUuid || undefined"
                            :org-uuid="myorg?.uuid"
                            :days-back="120"
                        />
                    </n-gi>
                    <n-gi>
                        <findings-over-time-chart
                            :type="selectedBranchUuid ? 'BRANCH' : 'COMPONENT'"
                            :component-uuid="componentData?.uuid"
                            :branch-uuid="selectedBranchUuid || undefined"
                            :component-name="componentData?.name"
                            :component-type="componentData?.type"
                            :branch-name="selectedBranchUuid ? (branches.find((b: any) => b.uuid === selectedBranchUuid)?.name || '') : ''"
                            :org-uuid="myorg?.uuid"
                            :days-back="120"
                        />
                    </n-gi>
                </n-grid>
            </n-gi>
            <n-gi span="3">
                <div class="componentTop">
                    <div class="componentSummary">
                        <h5 v-if="componentData">{{ words.componentFirstUpper }}: {{componentData.name}}</h5>
                        <div class="componentIconsAndSettings">
                            <n-space v-cloak>
                                <vue-feather v-if="componentData && componentData.type === 'COMPONENT' && isWritable" @click="genApiKey('rlz')" class="clickable icons" type="unlock" title="Generate Component API Key" />
                                <vue-feather v-if="words.componentFirstUpper" class="clickable icons" :title="words.componentFirstUpper + ' Settings'" @click="openComponentSettings" type="tool" />
                                <vue-feather v-if="words.componentFirstUpper" type="list" class="clickable icons" :title="words.componentFirstUpper + ' Changelog'" @click="showComponentChangelogModal = true" />
                                <n-tooltip trigger="hover" v-if="componentData && componentData.uuid">
                                    <template #trigger>
                                        <vue-feather class="icons" type="info" />
                                    </template>
                                    <strong>{{ words.componentFirstUpper }} UUID:</strong> {{ componentData.uuid }}
                                    <vue-feather class="clickable icons" @click="copyToClipboard(componentData.uuid)" type="clipboard"/>
                                </n-tooltip>
                                <n-tooltip trigger="hover" v-if="updatedComponent && updatedComponent.vcsRepositoryDetails && updatedComponent.vcsRepositoryDetails.uri">
                                    <template #trigger>
                                        <vue-feather class="icons" type="git-merge" />
                                    </template>
                                    <strong>VCS Repository:</strong>
                                    {{ updatedComponent.vcsRepositoryDetails.uri }}
                                    <a :href="'https://' + updatedComponent.vcsRepositoryDetails.uri" rel="noopener noreferrer"
                                    target="_blank"><vue-feather type="external-link" class="clickable icons" title="Open VCS Repository URI in New Window" /></a>
                                </n-tooltip>

                                <n-icon v-if="componentData" @click="navigateToVulnAnalysis" class="clickable icons" size="24" :title="'Open ' + words.componentFirstUpper + ' Finding Analysis'">
                                    <bug-outlined />
                                </n-icon>
                                <vue-feather v-if="isWritable" @click="archiveComponent" class="clickable" type="trash-2" :title="'Archive ' + words.componentFirstUpper" />
                            </n-space>
                            <n-modal
                                v-model:show="showComponentAnalyticsModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                            >
                                <component-analytics
                                    :componentUuidProp="componentData.uuid"
                                    :componentTypeProp="componentData.type"
                                />
                            </n-modal>
                            <n-modal
                                v-model:show="showComponentChangelogModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                            >
                                <changelog-view
                                            :componentprop="componentData.uuid"
                                            :branchprop="selectedBranchUuid"
                                            :orgprop="componentData.org"
                                            :componenttypeprop="componentData.type"
                                            :iscomponentchangelog="true"
                                />
                            </n-modal>
                            <n-modal
                                v-model:show="showAddBranchModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                            >
                                <h2>Create {{ words.branchFirstUpper }}</h2>
                                <n-form ref="createBranchForm" :model="createBranchObject" :rules="createBranchRules">
                                    <n-form-item label="Name" path="name">
                                        <n-input v-model:value="createBranchObject.name" required :placeholder="'Enter ' + words.branchFirstUpper + ' name'" />
                                    </n-form-item>
                                    <n-form-item label="Version Schema">
                                        <n-select
                                            v-model:value="createBranchObject.versionSchema"
                                            tag
                                            filterable
                                            :placeholder="'Select version schema for ' + words.branchFirstUpper"
                                            :options="constants.BranchVersionTypes" />
                                        <n-input
                                            v-if="createBranchObject.versionSchema === 'custom_version'"
                                            v-model:value="customBranchVersionSchema"
                                            placeholder="Custom Version Schema" />
                                    </n-form-item>
                                    <n-form-item>
                                        <n-button @click="onCreateBranchSubmit" type="success">
                                            Create
                                        </n-button>
                                        <n-button @click="onCreateBranchReset" type="warning">
                                            Reset
                                        </n-button>
                                    </n-form-item>
                                </n-form>
                            </n-modal>
                            <n-modal
                                v-model:show="showComponentSettingsModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                                :on-after-leave="closeComponentSettings"
                            >
                                <h3>{{ words.componentFirstUpper }} Settings for {{ componentData?.name }}</h3>
                                <n-tabs
                                    class="card-tabs"
                                    size="large"
                                    animated
                                    style="margin: 0 -4px"
                                    pane-style="padding-left: 4px; padding-right: 4px; box-sizing: border-box;"
                                    @update:value="handleTabSwitch"
                                >
                                    <n-tab-pane name="Core Settings">
                                        <div class="componentNameBlock" v-if="updatedComponent && componentData">
                                            <label id="componentNameLabel" for="componentName">{{ words.componentFirstUpper }} Name</label>
                                            <n-input v-if="isWritable" v-model:value="updatedComponent.name" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.name" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData">
                                            <label id="componentVersionSchemaLabel" for="componentVersionSchema">Version Schema</label>
                                            <n-select
                                                v-model:value="updatedComponent.versionSchema"
                                                placeholder="Choose or enter version schema"
                                                filterable
                                                tag
                                                :options="constants.VersionTypes" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.versionSchema" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData && myUser.installationType !== 'OSS'">
                                            <label id="componentVersionSchemaLabel" for="componentVersionSchema">Marketing Version</label>
                                            Enabled:  <n-switch v-model:value="marketingVersionEnabled"  @update:value="toggleMarketingVersion"/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="marketingVersionEnabled && myUser.installationType !== 'OSS'">
                                            <label>Marketing Version Schema</label>
                                            <n-select
                                                v-model:value="updatedComponent.marketingVersionSchema"
                                                placeholder="Choose or enter marketing version schema"
                                                filterable
                                                tag
                                                :options="constants.VersionTypes" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.marketingVersionSchema" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock featureBranchVersioning" v-if="updatedComponent && componentData && updatedComponent.type === 'COMPONENT'">
                                            <label id="componentFeatureBranchVersionSchemaLabel" for="componentFeatureBranchVersionSchema">Feature Branch Versioning Schema</label>
                                            <n-input v-if="isWritable" v-model:value="updatedComponent.featureBranchVersioning" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.featureBranchVersioning" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="false && updatedComponent && componentData && (componentData.type === 'COMPONENT')">
                                            <label  id="componentKindLabel" for="componentKind">Component Kind</label>
                                            <n-select v-if="isWritable" v-on:update:value="updateComponentKind" :options="[{label: 'Generic', value: 'GENERIC'}, {label: 'Helm', value: 'HELM'}]" v-model:value="updatedComponent.kind" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.kind" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="(updatedComponent && componentData && updatedComponent.kind === 'HELM' && updatedComponent.authentication)">
                                            <label id="componentAuthTypeLabel" for="componentAuthType">Component Authentication Type</label>
                                            <n-select v-if="isWritable" :options="componentAuthTypes" v-model:value="updatedComponent.authentication.type" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.authentication.type" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="(updatedComponent && componentData && updatedComponent.kind === 'HELM' && updatedComponent.authentication && updatedComponent.authentication.type !== 'NOCREDS' && isWritable)">
                                            <label id="componentAuthLoginLabel" for="componentAuthLogin">Component Authentication Login Secret</label>
                                            <n-select :options="secrets" v-model:value="updatedComponent.authentication.login" />
                                        </div>
                                        <div class="versionSchemaBlock" v-if="(updatedComponent && componentData && updatedComponent.kind === 'HELM' && updatedComponent.authentication && updatedComponent.authentication.type !== 'NOCREDS' && isWritable)">
                                            <label id="componentAuthPasswordLabel" for="componentAuthPassword">Component Authentication Password Secret</label>
                                            <n-select :options="secrets" v-model:value="updatedComponent.authentication.password" />
                                        </div>
                                        <div class="versionSchemaBlock" v-if="(updatedComponent && componentData && updatedComponent.kind === 'HELM' && isWritable)">
                                            <label>Helm Values File for Ephemerals</label>
                                            <n-input v-if="isWritable" v-model:value="updatedComponent.defaultConfig" />
                                            <n-input v-if="!isWritable" type="text" :value="updatedComponent.defaultConfig" readonly/>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData && componentData.type === 'COMPONENT'">
                                            <label>VCS Repository</label>
                                            <n-select v-if="isWritable" :options="vcsRepos" v-model:value="updatedComponent.vcs" />
                                            <span v-if="!isWritable && updatedComponent.vcsRepositoryDetails">{{ updatedComponent.vcsRepositoryDetails.uri }}</span>
                                            <span v-if="!isWritable && !updatedComponent.vcsRepositoryDetails">Not Set</span>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData && componentData.type === 'COMPONENT' && updatedComponent.vcsRepositoryDetails">
                                            <label>Repository Path</label>
                                            <n-input 
                                                v-if="isWritable" 
                                                v-model:value="updatedComponent.repoPath" 
                                                placeholder="e.g., services/auth, frontend/web" />
                                            <n-input 
                                                v-if="!isWritable" 
                                                type="text" 
                                                :value="updatedComponent.repoPath || 'Not Set'" 
                                                readonly />
                                        </div>
                                        <div class="identifierBlock" v-if="updatedComponent && componentData">
                                            <label>Identifiers</label>
                                            <n-dynamic-input v-if="isWritable" v-model:value="updatedComponent.identifiers" :on-create="onCreateIdentifier">
                                                <template #create-button-default>
                                                    Add Identifier
                                                </template>
                                                <template #default="{ value }">
                                                    <n-select style="width: 200px;" v-model:value="value.idType"
                                                        :options="[{label: 'PURL', value: 'PURL'}, {label: 'TEI', value: 'TEI'}, {label: 'CPE', value: 'CPE'}]" />
                                                    <n-input type="text" minlength="100" v-model:value="value.idValue" />
                                                </template>
                                            </n-dynamic-input>
                                            <!-- div v-else>{{ resolvedVisibilityLabel }}</div -->
                                            <n-button type="warning" style="margin-top:10px;" v-if="isWritable && updatedComponent.identifiers" @click="populateMissingComponentReleaseIdentifiers">Propagate To Releases With Missing Identifiers</n-button>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData && myUser.installationType !== 'OSS'">
                                            <label>Approval Policy</label>
                                            <n-select
                                                v-if="isWritable"
                                                :options="approvalPolicies" v-model:value="updatedComponent.approvalPolicy" />
                                            <div v-else>{{ resolvedVisibilityLabel }}</div>
                                        </div>
                                        <div class="coreSettingsActions" v-if="hasCoreSettingsChanges && isWritable" style="margin-top: 20px;">
                                            <n-space>
                                                <n-button type="success" @click="save">
                                                    <template #icon>
                                                        <vue-feather type="check" />
                                                    </template>
                                                    Save Changes
                                                </n-button>
                                                <n-button type="warning" @click="resetCoreSettings">
                                                    <template #icon>
                                                        <vue-feather type="x" />
                                                    </template>
                                                    Reset Changes
                                                </n-button>
                                            </n-space>
                                        </div>
                                    </n-tab-pane>
                                    <n-tab-pane name="outputTriggers" tab="Output Triggers" v-if="myUser.installationType !== 'OSS'">
                                        <n-data-table :data="updatedComponent.outputTriggers ? updatedComponent.outputTriggers : []" :columns="outputTriggerTableFields" :row-key="dataTableUuidRowKey" />
                                        <Icon v-if="isWritable" class="clickable" size="25" title="Add Output Trigger" @click="showCreateOutputTriggerModal = true">
                                            <CirclePlus />
                                        </Icon>
                                        <n-modal
                                            v-model:show="showCreateOutputTriggerModal"
                                            preset="dialog"
                                            :show-icon="false"
                                            style="width: 90%"
                                        >
                                            <n-form :model="outputTrigger">
                                                <h2>Add or Update Output Trigger</h2>
                                                <n-space vertical size="large">
                                                    <n-form-item label="Name" path="name">
                                                        <n-input v-model:value="outputTrigger.name" required placeholder="Enter name" />
                                                    </n-form-item>
                                                    <n-form-item label="Type" path="type">
                                                        <n-select v-model:value="outputTrigger.type" required 
                                                            v-on:update:value="value => {if (value === 'EMAIL_NOTIFICATION') loadUsers()}"
                                                            :options="outputTriggerTypeOptions" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'RELEASE_LIFECYCLE_CHANGE'" label="Lifecycle To Change To" path="toReleaseLifecycle">
                                                        <n-select v-model:value="outputTrigger.toReleaseLifecycle" required :options="outputTriggerLifecycleOptions" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER'" label="Choose CI Integration" path="integration">
                                                        <n-select
                                                            v-model:value="outputTrigger.integration"
                                                            placeholder="Select Integration"
                                                            :options="ciIntegrationsForSelect" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'GITHUB'" label="Installation ID" path="schedule">
                                                        <n-input v-model:value="outputTrigger.schedule" required placeholder="Enter GitHub Installation ID" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'GITHUB'" label="Name of GitHub Actions Event" path="eventType">
                                                        <n-input v-model:value="outputTrigger.eventType" placeholder="Enter Name of GitHub Actions Event" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'GITHUB'" label="Optional Client Payload JSON" path="clientPayload">
                                                        <n-input v-model:value="outputTrigger.clientPayload" required placeholder="Enter Additional Optional Client Payload JSON" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'GITLAB'" label="GitLab Schedule Id" path="schedule">
                                                        <n-input type="number" v-model:value="outputTrigger.schedule" required placeholder="Enter numeric GitLab Schedule Id" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && (selectedCiIntegration.type === 'GITHUB' || selectedCiIntegration.type === 'GITLAB')" label="CI Repository" path="vcs">
                                                        <span v-if="!selectNewIntegrationRepo && outputTrigger.vcs">{{ getVcsRepoObjById(outputTrigger.vcs).uri }} </span>
                                                        <span v-if="!selectNewIntegrationRepo && !outputTrigger.vcs">Not Set</span>
                                                        <n-select v-if="selectNewIntegrationRepo" :options="vcsRepos" required v-model:value="outputTrigger.vcs" />
                                                        <vue-feather v-if="!selectNewIntegrationRepo" type="edit" class="clickable" @click="async () => {selectNewIntegrationRepo = true;}" title="Select New Integration Repository" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'JENKINS'" label="Jenkins Job Name" path="schedule">
                                                        <n-input v-model:value="outputTrigger.schedule" required placeholder="Jenkins Job Name" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'ADO'" label="Azure DevOps Project Name" path="eventType">
                                                        <n-input v-model:value="outputTrigger.eventType" required placeholder="Enter Azure DevOps project name" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'ADO'" label="Pipeline Definition ID" path="schedule">
                                                        <n-input v-model:value="outputTrigger.schedule" required placeholder="Enter Pipeline Definition ID" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'INTEGRATION_TRIGGER' && selectedCiIntegration && selectedCiIntegration.type === 'ADO'" label="Optional Parameters" path="clientPayload">
                                                        <n-input v-model:value="outputTrigger.clientPayload" placeholder="Enter Optional Parameters (JSON)" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'EMAIL_NOTIFICATION'" label="Users to notify" path="users">
                                                        <n-select v-model:value="outputTrigger.users" tag multiple required :options="users" />
                                                    </n-form-item>
                                                    <n-form-item v-if="outputTrigger.type === 'EMAIL_NOTIFICATION'" label="Email Message Contents" path="notificationMessage">
                                                        <n-input v-model:value="outputTrigger.notificationMessage" placeholder="Email Message Contents (i.e. 'Release ready to ship.')" />
                                                    </n-form-item>
                                                    <n-button @click="addOutputTrigger" type="success">
                                                        Save
                                                    </n-button>
                                                </n-space>
                                            </n-form>
                                        </n-modal>
                                    </n-tab-pane>
                                    <n-tab-pane name="Trigger Events" v-if="myUser.installationType !== 'OSS'">
                                        <n-data-table :data="updatedComponent.releaseInputTriggers ? updatedComponent.releaseInputTriggers : []" :columns="inputTriggerTableFields" :row-key="dataTableUuidRowKey" />
                                        <Icon v-if="isWritable" class="clickable" size="25" title="Add Trigger Event" @click="showCreateInputTriggerModal = true">
                                            <CirclePlus />
                                        </Icon>
                                        <n-modal
                                            v-model:show="showCreateInputTriggerModal"
                                            preset="dialog"
                                            :show-icon="false"
                                            style="width: 90%"
                                        >
                                            <n-form :model="inputTrigger">
                                                <h2>Add or Update Trigger Event</h2>
                                                <n-space vertical size="large">
                                                    <n-form-item label="Name" path="name">
                                                        <n-input v-model:value="inputTrigger.name" required placeholder="Enter name" />
                                                    </n-form-item>
                                                    <n-form-item label="Condition Matching Between Groups">
                                                        <n-select v-model:value="inputTrigger.conditionGroup.matchOperator" :options="[{label: 'Require All Groups To Match', value: 'AND'}, {label: 'Require Any Group To Match', value: 'OR'}]" />
                                                    </n-form-item>
                                                    <n-form-item path="inputTrigger.conditionGroup">
                                                        <n-dynamic-input v-model:value="inputTrigger.conditionGroup.conditionGroups" :on-create="onCreateInputTriggerConditionGroup">
                                                            <template #create-button-default>
                                                                Add Condition Group
                                                            </template>
                                                            <template #default="{ value: value1, index }">
                                                                <div style="width: 100%;">
                                                                    <h5>Trigger Group #{{ index + 1 }}</h5>
                                                                    <n-form-item label="Condition Matching Within The Group">
                                                                        <n-select v-model:value="value1.matchOperator" :options="[{label: 'Require All Conditions', value: 'AND'}, {label: 'Require Any Condition', value: 'OR'}]" />
                                                                    </n-form-item>
                                                                    <n-dynamic-input v-model:value="value1.conditions" :on-create="onCreateInputTriggerCondition">
                                                                        <template #create-button-default>
                                                                            Add Trigger Condition
                                                                        </template>
                                                                        <template #default="{ value, index }">
                                                                            <n-select style="width: 400px;" v-model:value="value.type"
                                                                                v-on:update:value="onConditionTypeUpdate(value1, index)"
                                                                                :options="[{label: 'Approval Entry', value: 'APPROVAL_ENTRY'}, {label: 'Possible Lifecycles', value: 'LIFECYCLE'}, {label: 'Possible Branch Types', value: 'BRANCH_TYPE'}, {label: 'Metrics', value: 'METRICS'}]" />
                                                                            <n-select v-if="value.type === 'APPROVAL_ENTRY'"
                                                                                v-model:value="value.approvalEntry"
                                                                                :options="approvalEntryOptionsForTriggers"
                                                                            />
                                                                            <n-select v-if="value.type === 'APPROVAL_ENTRY'" style="width:300px;" v-model:value="value.approvalState"
                                                                                :options="[{label: 'Approved', value: 'APPROVED'}, {label: 'Disapproved', value: 'DISAPPROVED'}]" />
                                                                            <n-select v-if="value.type === 'LIFECYCLE'" v-model:value="value.possibleLifecycles" 
                                                                                :options="lifecycleOptions" tag multiple />
                                                                            <n-select v-if="value.type === 'BRANCH_TYPE'" v-model:value="value.possibleBranchTypes" tag multiple
                                                                                :options="[{label: 'Main', value: 'BASE'}, {label: 'Feature', value: 'FEATURE'}, {label: 'Regular', value: 'REGULAR'}, {label: 'Release', value: 'RELEASE'}, {label: 'Develop', value: 'DEVELOP'}, {label: 'Hotfix', value: 'HOTFIX'}]" />
                                                                            <n-select v-if="value.type === 'METRICS'" style="width:70%;" v-model:value="value.metricsType" 
                                                                                :options="[{label: 'Critical Vulnerabilities', value: 'CRITICAL_VULNS'}, {label: 'High Vulnerabilities', value: 'HIGH_VULNS'}, {label: 'Medium Vulnerabilities', value: 'MEDIUM_VULNS'}, {label: 'Low Vulnerabilities', value: 'LOW_VULNS'}, {label: 'Unassigned Vulnerabilities', value: 'UNASSIGNED_VULNS'}, {label: 'Security Violations', value: 'SECURITY_VIOLATIONS'}, {label: 'Operational Violations', value: 'OPERATIONAL_VIOLATIONS'}, {label: 'License Violations', value: 'LICENSE_VIOLATIONS'}]" />
                                                                            <n-select v-if="value.type === 'METRICS'" style="width:300px;" v-model:value="value.comparisonSign" 
                                                                                :options="[{label: '=', value: 'EQUALS'}, {label: '>', value: 'GREATER'}, {label: '<', value: 'LOWER'}, {label: '>=', value: 'GREATER_OR_EQUALS'}, {label: '<=', value: 'LOWER_OR_EQUALS'}]" />
                                                                            <n-input-number v-if="value.type === 'METRICS'" v-model:value="value.metricsValue" />
                                                                        </template>
                                                                    </n-dynamic-input>
                                                                </div>
                                                            </template>
                                                        </n-dynamic-input>
                                                    </n-form-item>
                                                    <n-form-item label="Output Triggers" path="inputTrigger.outputEvents">
                                                        <n-select v-model:value="inputTrigger.outputEvents" 
                                                        :options="outputTriggersForInputForm" multiple />
                                                    </n-form-item>
                                                    <n-button @click="addInputTrigger" type="success">
                                                        Save
                                                    </n-button>
                                                </n-space>
                                            </n-form>
                                        </n-modal>
                                    </n-tab-pane>
                                    <n-tab-pane v-if="false" name="Environment Mapping">
                                        <div v-if="isWritable" class="envBranchMapBlock">
                                            <h6><strong>What {{ words.branch }} to use for which environment for invidual deployment?</strong></h6>
                                            <div>
                                                <div v-for="et in environmentTypes" :key="et">
                                                    <div class="etName">{{ et }}</div>
                                                    <n-select v-on:update:value="value => {updatedComponent.envBranchMap[et] = value; save()}" :options="branchesForEnvMapping" v-model:value="updatedComponent.envBranchMap[et]" />
                                                </div>
                                            </div>
                                        </div>
                                    </n-tab-pane>
                                    <n-tab-pane v-if="false" name="Admin Zone">
                                        <div class="versionSchemaBlock" v-if="false && resourceGroups && updatedComponent && componentData && updatedComponent.resourceGroup">
                                            <label id="resourceGroupLabel">Resource Group</label>
                                            <n-select
                                                v-if="isAdmin"
                                                v-on:update:value="value => {updatedComponent.resourceGroup = value; updateComponentResourceGroup()}" :options="resourceGroups" v-model:value="updatedComponent.resourceGroup" />
                                            <div v-else>{{ resourceGroupMap[updatedComponent.resourceGroup] }} </div>
                                        </div>
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData">
                                            <label id="visibilityLabel">Visibility</label>
                                            <n-select
                                                v-if="isAdmin"
                                                @update:value="value => setComponentVisibility(value)" :options="visibilities" :value="updatedComponent.visibilitySetting" />
                                            <div v-else>{{ resolvedVisibilityLabel }}</div>
                                        </div>
                                    </n-tab-pane>
                                    <n-tab-pane v-if="isAdmin && myUser.installationType !== 'OSS'" name="Admin Settings">
                                        <div class="versionSchemaBlock" v-if="updatedComponent && componentData">
                                            <label>Perspectives</label>
                                            <n-select
                                                v-model:value="selectedPerspectives"
                                                :options="perspectiveOptions"
                                                multiple
                                                placeholder="Select perspectives" />
                                        </div>
                                        <div class="coreSettingsActions" v-if="hasPerspectiveChanges" style="margin-top: 20px;">
                                            <n-space>
                                                <n-button type="success" @click="savePerspectives">
                                                    <template #icon>
                                                        <vue-feather type="check" />
                                                    </template>
                                                    Save Changes
                                                </n-button>
                                                <n-button type="warning" @click="resetPerspectives">
                                                    <template #icon>
                                                        <vue-feather type="x" />
                                                    </template>
                                                    Reset Changes
                                                </n-button>
                                            </n-space>
                                        </div>
                                    </n-tab-pane>
                                </n-tabs>
                            </n-modal>
                            <n-modal
                                v-model:show="showCloneBranchModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                                :title="'Clone ' + words.branchFirstUpper + ' from ' + cloneBrProps.originalBranch.name"
                                :hide-footer="true"
                                >
                                <n-form
                                    :model="cloneBrProps">
                                    <n-space vertical size="large">
                                        <label>Name of new {{ words.branchFirstUpper }}</label>
                                        <n-input
                                            v-model:value="cloneBrProps.name"
                                            required
                                            :placeholder="'Enter cloned ' + words.branchFirstUpper + ' name'" />
                                            <label>Version Pin of new {{ words.branchFirstUpper }} (Defaults to Version Schema: {{ componentData.featureBranchVersioniong }} )"</label>
                                        <n-input
                                            v-model:value="cloneBrProps.schema"
                                            required
                                            placeholder="Enter Version Pin" />
                                        <label>Type of new {{ words.branchFirstUpper }}</label>
                                        <n-select
                                            placeholder="Please choose type"
                                            v-model:value="cloneBrProps.type"
                                            :options="branchTypes"
                                            required />
                                        <div class="buttonBlock">
                                            <n-button type="success" @click="cloneBranchSubmit">Submit</n-button>
                                            <n-button type="warning" @click="cloneBranchReset">Reset</n-button>
                                        </div>
                                    </n-space>
                                </n-form>
                            </n-modal>
                        </div>
                    </div>
                </div>
                <div class="componentDetails">
                    <n-tabs v-if="componentData && componentData.type === 'COMPONENT'" v-model:value="selectedTab" type="line" @update:value="handleTabChange">
                        <n-tab-pane name="branches" tab="Branches">
                            <n-data-table :data="branches" :columns="branchFields" :row-props="rowProps" :row-class-name="branchRowClassName" :row-key="branchTableRowKey" />
                        </n-tab-pane>
                        <n-tab-pane name="pull-requests" tab="Pull Requests">
                            <n-data-table :data="pullRequests" :columns="pullRequestFields" :row-props="rowProps" :row-class-name="branchRowClassName" :row-key="branchTableRowKey" />
                        </n-tab-pane>
                        <n-tab-pane name="tags" tab="Tags">
                            <n-data-table :data="tags" :columns="tagFields" :row-props="rowProps" :row-class-name="branchRowClassName" :row-key="branchTableRowKey" />
                        </n-tab-pane>
                    </n-tabs>
                    <n-data-table v-else :data="branches" :columns="branchFields" :row-props="rowProps" :row-class-name="branchRowClassName" :row-key="branchTableRowKey" />
                </div>
            </n-gi>
            <n-gi span="7">
                <div v-if="marketingVersionEnabled" class="marketingReleases">
                    <mrkt-releases-of-component :component="updatedComponent.uuid" />
                </div>
                <div class="branchDetails">
                    <branch-view :branchUuidProp="selectedBranchUuid" :prnumberprop="routePrnumber" v-if="selectedBranchUuid" />
                </div>
            </n-gi>
        </n-grid>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ComponentView',
}
</script>

<script lang="ts" setup>
import { ComputedRef, ref, Ref, computed, h, Component, onMounted } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NModal, NTabs, NTabPane, NForm, NFormItem, NInput, NInputNumber, NButton, NSelect, NSpace, NRadio, NRadioGroup, NDataTable, NotificationType, useNotification, NCheckbox, NCheckboxGroup, NSwitch, NTooltip, DataTableColumns, NDynamicInput, NGrid, NGi, FormInst, FormRules } from 'naive-ui'
import commonFunctions from '../utils/commonFunctions'
import ComponentAnalytics from './ComponentAnalytics.vue'
import ChangelogView from './ChangelogView.vue'
import BranchView from './BranchView.vue'
import MrktReleasesOfComponent from './MrktReleasesOfComponent.vue'
import FindingsOverTimeChart from './FindingsOverTimeChart.vue'
import ReleasesPerDayChart from './ReleasesPerDayChart.vue'
import Swal from 'sweetalert2'
import { SwalData } from '@/utils/commonFunctions'
import axios from '../utils/axios'
import { Link as LinkIcon, Copy, CirclePlus, Trash, Edit } from '@vicons/tabler'
import { Info20Regular } from '@vicons/fluent'
import { Icon } from '@vicons/utils'
import { BugOutlined } from '@vicons/antd'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import constants from '@/utils/constants'

const updatedComponent: Ref<any> = ref({})

onMounted(async () => {
    await initLoad()
})

function renderIcon (icon: Component) {
    return () => h(NIcon, null, () => h(icon))
}

function isMain (branch : any) {
    if (branch.name === 'main' || branch.name === 'Base Feature Set') {
        return 2
    } else if (branch.name === 'master') {
        return 1
    } else {
        return 0
    }
}

async function genApiKeyRoutine (type : string, compUuid : string) {
    const swalResult = await Swal.fire({
        title: 'Are you sure?',
        text: 'A new API Key will be generated, any existing integrations with previous API Key (if exist) will stop working.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, generate it!',
        cancelButtonText: 'No, cancel it'
    })

    if (swalResult.value) {
        const keyResp = await graphqlClient.mutate({
            mutation: gql`
                mutation setComponentApiKey($componentUuid: ID!) {
                    setComponentApiKey(componentUuid: $componentUuid) {
                        id
                        apiKey
                        authorizationHeader
                    }
                }`,
            variables: {
                componentUuid: compUuid
            },
            fetchPolicy: 'no-cache'
        })
        const newKeyMessage = commonFunctions.getGeneratedApiKeyHTML(keyResp.data.setComponentApiKey)

        Swal.fire({
            title: 'Generated!',
            customClass: {popup: 'swal-wide'},
            html: newKeyMessage,
            icon: 'success'
        })
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        notify('error', 'Cancelled', 'Your existing API Key (if present) is safe')
    }
}

async function loadSecrets (orgUuid: string) {
    const response = await graphqlClient.query({
        query: gql`
            query secrets($orgUuid: ID!) {
                secrets(orgUuid: $orgUuid) {
                    uuid,
                    name
                }
            }`,
        variables: {
            orgUuid
        },
        fetchPolicy: 'no-cache'
    })
    // transform to match options format
    const secrets = response.data.secrets.map((x: any) => {
        return {label: x.name, value: x.uuid}
    })
    return secrets
}

async function populateMissingComponentReleaseIdentifiers () {
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation updateComponentReleasesIdentifiers($componentUuid: ID!) {
                    updateComponentReleasesIdentifiers(componentUuid: $componentUuid)
                }`,
            variables: {
                componentUuid: updatedComponent.value.uuid
            }
        })
        notify('info', 'Propagated', `Identifier Data Propagated to Releases`)
    } catch (error) {
        notify('error', 'Error', `Error on Propagating Identifier Data to Releases`)
        console.error(error)
    }
}


const route = useRoute()
const router = useRouter()
const store = useStore()
const notification = useNotification()

async function copyToClipboard (text: string) {
    try {
        navigator.clipboard.writeText(text);
        notify('info', 'Copied', `{words.value.componentFirstUpper} uuid copied: ${text}`)
    } catch (error) {
        console.error(error)
    }
}

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const orguuid : Ref<string> = ref('')
if (route.params.orguuid) {
    orguuid.value = route.params.orguuid.toString()
} else {
    orguuid.value = myorg.value
}

const componentUuid: string = route.params.compuuid.toString()

const componentData: ComputedRef<any> = computed((): any => {
    return store.getters.componentById(componentUuid)
})

const isComponent : Ref<boolean> = ref(true)

const myUser = store.getters.myuser

const isAdmin : boolean = commonFunctions.isAdmin(orguuid.value, myUser)
const isWritable : ComputedRef<boolean> = computed(() => commonFunctions.isWritable(orguuid.value, myUser, 'COMPONENT'))

const words: Ref<any> = ref({})

const genApiKey = function (type : string) {
    genApiKeyRoutine(type, componentUuid)
}

const ciIntegrations: Ref<any[]> = ref([])
const ciIntegrationsForSelect: ComputedRef<any[]> = computed((): any => {
    return ciIntegrations.value.map((x: any) => {return {label: x.note, value: x.uuid}})
})
const selectedCiIntegration: ComputedRef<any> = computed((): any => {
    return ciIntegrations.value.find((x: any) => x.uuid === outputTrigger.value.integration)
})

const showCloneBranchModal : Ref<boolean> = ref(false)
const showComponentAnalyticsModal : Ref<boolean> = ref(false)
const showComponentChangelogModal : Ref<boolean> = ref(false)
const showAddBranchModal : Ref<boolean> = ref(false)
const showComponentSettingsModal: Ref<boolean> = ref(false)

// Initialize component settings modal from URL query parameter
if (route.query.componentSettingsView === 'true') {
    showComponentSettingsModal.value = true
}
const showCreateOutputTriggerModal: Ref<boolean> = ref(false)
const showCreateInputTriggerModal: Ref<boolean> = ref(false)
const branchRouteId = route.params.branchuuid ? route.params.branchuuid.toString() : ''
const routePrnumber = route.params.prnumber ? route.params.prnumber.toString() : ''
const selectedBranchUuid : Ref<string> = ref(branchRouteId)
const selectedTab: Ref<string> = ref((route.query.tab as string) || 'branches')
const branchCollapseState: Ref<any> = ref({})
const selectedPullRequest: Ref<string> = routePrnumber !== '' ? ref(branchRouteId + '-pr-' + routePrnumber) : ref('')
branchCollapseState.value['branchCollapse' + branchRouteId] = true
const resourceGroups: ComputedRef<any> = computed((): any => {
    const storeApps = store.getters.allResourceGroups
    return storeApps.map((a: any) => {
        return {
            label: a.name,
            value: a.uuid
        }
    })
})

const approvalPolicies : Ref<any[]> = ref([])

async function fetchApprovalPolicies () {
    if (myUser.installationType !== 'OSS') {
        const response = await graphqlClient.query({
            query: gql`
                query approvalPoliciesOfOrg($orgUuid: ID!) {
                    approvalPoliciesOfOrg(orgUuid: $orgUuid) {
                        uuid
                        policyName
                    }
                }`,
            variables: {
                'orgUuid': orguuid.value
            },
            fetchPolicy: 'no-cache'
        })
        approvalPolicies.value = response.data.approvalPoliciesOfOrg.map((ap: any) => {
            return {
                label: ap.policyName,
                value: ap.uuid
            }
        })
    }
}

const openComponentSettings = async function() {
    await fetchApprovalPolicies()
    showComponentSettingsModal.value = true
    
    // Update router query parameter
    await router.push({
        query: { ...route.query, componentSettingsView: 'true' }
    })
}

const closeComponentSettings = async function() {
    showComponentSettingsModal.value = false
    
    // Remove componentSettingsView query parameter from URL
    const { componentSettingsView, ...queryWithoutSettings } = route.query
    await router.push({
        query: queryWithoutSettings
    })
}

// Perspectives management
const orgPerspectives: Ref<any[]> = ref([])
const selectedPerspectives: Ref<string[]> = ref([])
const originalPerspectives: Ref<string[]> = ref([])

const perspectiveOptions: ComputedRef<any[]> = computed(() => {
    return orgPerspectives.value.map((p: any) => {
        return {
            label: p.name,
            value: p.uuid
        }
    })
})

const hasPerspectiveChanges: ComputedRef<boolean> = computed(() => {
    if (selectedPerspectives.value.length !== originalPerspectives.value.length) return true
    const sorted1 = [...selectedPerspectives.value].sort()
    const sorted2 = [...originalPerspectives.value].sort()
    return !sorted1.every((val, index) => val === sorted2[index])
})

async function fetchPerspectives() {
    if (isAdmin && myUser.installationType !== 'OSS') {
        try {
            const response = await graphqlClient.query({
                query: gql`
                    query perspectives($org: ID!) {
                        perspectives(org: $org) {
                            uuid
                            name
                            org
                            createdDate
                        }
                    }`,
                variables: {
                    org: orguuid.value
                },
                fetchPolicy: 'no-cache'
            })
            orgPerspectives.value = response.data.perspectives || []
            
            // Set selected perspectives from component data
            if (updatedComponent.value && updatedComponent.value.perspectiveDetails) {
                selectedPerspectives.value = updatedComponent.value.perspectiveDetails.map((p: any) => p.uuid)
                originalPerspectives.value = [...selectedPerspectives.value]
            }
        } catch (err) {
            console.error('Error fetching perspectives:', err)
        }
    }
}

async function savePerspectives() {
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation setPerspectivesOnComponent($componentUuid: ID!, $perspectiveUuids: [ID!]!) {
                    setPerspectivesOnComponent(componentUuid: $componentUuid, perspectiveUuids: $perspectiveUuids) {
                        uuid
                        perspectiveDetails {
                            uuid
                            name
                            org
                            createdDate
                        }
                    }
                }`,
            variables: {
                componentUuid: componentUuid,
                perspectiveUuids: selectedPerspectives.value
            }
        })
        
        if (response.data && response.data.setPerspectivesOnComponent) {
            updatedComponent.value.perspectiveDetails = response.data.setPerspectivesOnComponent.perspectiveDetails
            originalPerspectives.value = [...selectedPerspectives.value]
            notify('success', 'Success', 'Perspectives updated successfully')
        }
    } catch (err: any) {
        console.error('Error saving perspectives:', err)
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.toString()))
    }
}

function resetPerspectives() {
    selectedPerspectives.value = [...originalPerspectives.value]
}


async function fetchCiIntegrations() {
    try {
        const resp = await graphqlClient.query({
            query: gql`
                          query ciIntegrations($org: ID!) {
                                ciIntegrations(org: $org) {
                                	uuid
                                    identifier
                                    org
                                    isEnabled
                                    type
                                    note
                              }
                          }`,
            variables: {
                org: orguuid.value
            },
            fetchPolicy: "network-only"
        })
        if (resp.data && resp.data.ciIntegrations) {
            ciIntegrations.value = resp.data.ciIntegrations
        }
    } catch (err) { 
        console.error(err)
    }
}


const visibilities = [
    {
        value: 'ORG_INTERNAL',
        label: 'Organization-Wide (default)'
    },
    // {
    //     value: 'ADMIN_INVITATION',
    //     label: 'Organization Admin Only'
    // },
    {
        value: 'PUBLIC',
        label: 'Public'
    }

]

const resolvedVisibilityLabel: ComputedRef<string | undefined> = computed((): string | undefined => {
    let vis: string | undefined = ""
    if (updatedComponent.value && updatedComponent.value.visibilitySetting) {
        let i = 0
        while (i < visibilities.length && !vis) {
            if (visibilities[i].value === updatedComponent.value.visibilitySetting) {
                vis = visibilities[i].label
            }
        }
    }
    return vis
})

const resourceGroupMap: ComputedRef<any> = computed((): any => {
    const amap: any = {}
    const storeApps = store.getters.allResourceGroups
    storeApps.forEach((a: any) => {
        amap[a.uuid] = a.name
    })
    return amap
})

const branches: ComputedRef<any> = computed((): any => {
    const storeBranches = store.getters.branchesOfComponent(componentUuid).filter((b: any) => b.type !== 'PULL_REQUEST' && b.type !== 'TAG').map((b: any) => ({...b, key: b.uuid}))
    if (storeBranches && storeBranches.length) {
        // sort - TODO make sort configurable
        storeBranches.sort((a: any, b: any) => {
            if (isMain(a) > isMain(b)) {
                return -1
            } else if (isMain(a) < isMain(b)) {
                return 1
            } else if (a.name < b.name) {
                return -1
            } else if (a.name > b.name) {
                return 1
            } else {
                return 0
            }
        })
    }
    return storeBranches
})

const mainBranch: ComputedRef<string> = computed((): any => {
    let mainBranch
    if (branches.value && branches.value.length) {
        for (let i = 0; i < branches.value.length; i++) {
            if (isMain(branches.value[i])) {
                mainBranch = branches.value[i].uuid
            }
        }
        // default to 1st branch
        if (!mainBranch) mainBranch = branches.value[0].uuid
    }
    return mainBranch
})

const pullRequests: ComputedRef<any> = computed((): any => {
    const storeBranches = store.getters.branchesOfComponent(componentUuid).filter((b: any) => b.type === 'PULL_REQUEST').map((b: any) => ({...b, key: b.uuid}))
    if (storeBranches && storeBranches.length) {
        // Sort by creation date in descending order (newest first)
        storeBranches.sort((a: any, b: any) => {
            const dateA = a.createdDate ? new Date(a.createdDate).getTime() : 0
            const dateB = b.createdDate ? new Date(b.createdDate).getTime() : 0
            return dateB - dateA
        })
    }
    return storeBranches
})

const tags: ComputedRef<any> = computed((): any => {
    const storeBranches = store.getters.branchesOfComponent(componentUuid).filter((b: any) => b.type === 'TAG').map((b: any) => ({...b, key: b.uuid}))
    if (storeBranches && storeBranches.length) {
        // Sort by creation date in descending order (newest first)
        storeBranches.sort((a: any, b: any) => {
            const dateA = a.createdDate ? new Date(a.createdDate).getTime() : 0
            const dateB = b.createdDate ? new Date(b.createdDate).getTime() : 0
            return dateB - dateA
        })
    }
    return storeBranches
})

const branchesForEnvMapping: ComputedRef<any> = computed((): any => {
    const envMappingBranches: any = []
    if (branches.value && branches.value.length) {
        branches.value.forEach((br: any) => {
            let retObj = {
                label: br.name,
                value: br.uuid
            }
            envMappingBranches.push(retObj)
        })
    }
    return envMappingBranches
})

function selectBranch (uuid: string) {
    selectedBranchUuid.value = uuid
    branchCollapseState.value['branchCollapse' + uuid] = true
    
    // Only navigate if not already on this branch to avoid hijacking double-click
    if (route.params.branchuuid !== uuid) {
        router.push({
            name: isComponent.value ? 'ComponentsOfOrg' : 'ProductsOfOrg',
            params: {
                orguuid: route.params.orguuid,
                compuuid: componentUuid,
                branchuuid: selectedBranchUuid.value
            },
            query: route.query
        })
    }
}

function handleTabChange (tabName: string) {
    // Clear selected branch when switching tabs
    selectedBranchUuid.value = ''
    
    router.push({
        name: isComponent.value ? 'ComponentsOfOrg' : 'ProductsOfOrg',
        params: {
            orguuid: route.params.orguuid,
            compuuid: componentUuid
        },
        query: { ...route.query, tab: tabName }
    })
}

const createBranchForm = ref<FormInst | null>(null)

const createBranchRules: FormRules = {
    name: {
        required: true,
        message: 'Branch name is required',
        trigger: ['blur', 'input'],
        validator: (rule: any, value: string) => {
            if (!value || value.trim() === '') {
                return new Error('Branch name cannot be empty')
            }
            return true
        }
    }
}

const createBranchObject: Ref<any> = ref({
    name: '',
    versionSchema: updatedComponent.value.featureBranchVersioning ? updatedComponent.value.featureBranchVersioning : 'Branch.Micro',
    component: componentUuid
})

const customBranchVersionSchema = ref('')

const outputTrigger = ref({
    uuid: '',
    name: '',
    type: '',
    toReleaseLifecycle: null,
    integration: '',
    users: [],
    notificationMessage: '',
    vcs: '',
    eventType: '',
    clientPayload: '',
    schedule: ''
})

function resetOutputTrigger () {
    outputTrigger.value = {
        uuid: '',
        name: '',
        type: '',
        toReleaseLifecycle: null,
        integration: '',
        users: [],
        notificationMessage: '',
        vcs: '',
        eventType: '',
        clientPayload: '',
        schedule: ''
    }
}

interface Condition {
    type: string;
    approvalEntry?: string;
    approvalState?: string;
    possibleLifecycles?: string[];
    possibleBranchTypes?: string[];
    metricsType?: string;
    comparisonSign?: string;
    metricsValue?: number;
}

class UninitializedCondition implements Condition {
    type = ''
}

class LifecycleCondition implements Condition {
    type = 'LIFECYCLE'
    possibleLifecycles = []
}

class BranchTypeCondition implements Condition {
    type = 'BRANCH_TYPE'
    possibleBranchTypes = []
}

class ApprovalEntryCondition implements Condition {
    type = 'APPROVAL_ENTRY'
    approvalEntry = ''
    approvalState = ''
}

class MetricsCondition implements Condition {
    type = 'METRICS'
    metricsType = ''
    comparisonSign = ''
    metricsValue = 0
}

type ConditionGroup = {
    conditionGroups: ConditionGroup[];
    matchOperator: string;
    conditions: Condition[];
}

type InputTrigger = {
    uuid: string;
    name: string;
    conditionGroup: ConditionGroup;
    outputEvents: string[];
}

const inputTrigger: Ref<InputTrigger> = ref({
    uuid: '',
    name: '',
    conditionGroup: {
        conditionGroups: [],
        matchOperator: '',
        conditions: []
    },
    outputEvents: []
})

function resetInputTrigger () {
    inputTrigger.value = {
        uuid: '',
        name: '',
        conditionGroup: {
            conditionGroups: [],
            matchOperator: '',
            conditions: []
        },
        outputEvents: []           
    }
}

const outputTriggersForInputForm = computed((): any => {
    let outputTriggers: any[] = []
    if (updatedComponent && updatedComponent.value && updatedComponent.value.outputTriggers) {
        outputTriggers = updatedComponent.value.outputTriggers.map((ot: any) => {
            return {label: ot.name, value: ot.uuid}
        })
    }
    return outputTriggers
})

const approvalEntryOptionsForTriggers = computed((): any => {
    let options = []
    if (updatedComponent.value && updatedComponent.value.approvalPolicyDetails && updatedComponent.value.approvalPolicyDetails.approvalEntryDetails) {
        options = updatedComponent.value.approvalPolicyDetails.approvalEntryDetails.map((aed: any) => {
            return {label: aed.approvalName, value: aed.uuid}
        })
    }
    return options
})

function onCreateInputTriggerCondition () {
    return new UninitializedCondition()
}

function onConditionTypeUpdate (conditionGroup: ConditionGroup, index: number) {
    const newType: string = conditionGroup.conditions[index].type
    switch (newType) {
    case 'APPROVAL_ENTRY':
        conditionGroup.conditions[index] = new ApprovalEntryCondition()
        break
    case 'LIFECYCLE':
        conditionGroup.conditions[index] = new LifecycleCondition()
        break
    case 'BRANCH_TYPE':
        conditionGroup.conditions[index] = new BranchTypeCondition()
        break
    case 'METRICS':
        conditionGroup.conditions[index] = new MetricsCondition()
        break
    default:
        break
    }
    console.log(newType)
    console.log(index)
}

function onCreateInputTriggerConditionGroup () {
    return {
        conditionGroups: [],
        conditions: [],
        matchOperator: ''
    }
}

function onCreateIdentifier () {
    return {
        idType: '',
        idValue: ''
    }
}

const lifecycleOptions = constants.LifecycleOptions.map((lo: any) => {return {label: lo.label, value: lo.key}})

const outputTriggerLifecycleOptions = [
    {label: 'Draft', value: 'DRAFT'}, {label: 'Assembled', value: 'ASSEMBLED'},
    {label: 'Shipped', value: 'GENERAL_AVAILABILITY'}, {label: 'Rejected', value: 'REJECTED'},
    {label: 'End of Support', value: 'END_OF_SUPPORT'}
]

const outputTriggerTypeOptions = [
    {label: 'Release Lifecycle Change', value: 'RELEASE_LIFECYCLE_CHANGE'},
    {label: 'Marketing Release Lifecycle Change', value: 'MARKETING_RELEASE_LIFECYCLE_CHANGE'},
    {label: 'External Integration', value: 'INTEGRATION_TRIGGER'},
    {label: 'Email Notification', value: 'EMAIL_NOTIFICATION'}
]

const defaultCloneBrProps = {
    name: '',
    type: '',
    schema: '',
    originalBranch: {
        name: 'loading',
        versionSchema: 'none'
    }
}

const cloneBrProps: Ref<any> = ref(commonFunctions.deepCopy(defaultCloneBrProps))

const cloneBranchReset = function () {
    cloneBrProps.value.name = ''
    cloneBrProps.value.type = ''
    cloneBrProps.value.schema = componentData.value.featureBranchVersioning
}

const cloneBranchSubmit = async function () {
    let brProps = {
        name: cloneBrProps.value.name,
        branchUuid: cloneBrProps.value.originalBranch.uuid,
        versionSchema: cloneBrProps.value.schema,
        branchType: cloneBrProps.value.type
    }
    const response: any = store.dispatch('cloneBranch', brProps)
    selectBranch(response.uuid)
    cloneBranchReset()
    showCloneBranchModal.value = false
}

const onCreateBranchSubmit = async function() {
    createBranchForm.value?.validate(async (errors) => {
        if (errors) {
            return
        }
        try {
            if (createBranchObject.value.versionSchema === 'custom_version') {
                createBranchObject.value.versionSchema = customBranchVersionSchema.value
            }
            const createBranchResp = await store.dispatch('createBranch', createBranchObject.value)
            await store.dispatch('fetchBranches', { componentId: componentUuid, forceRefresh: true })
            onCreateBranchReset()
            showAddBranchModal.value = false
            selectBranch(createBranchResp.uuid)
        } catch (error: any) {
            alert('ERROR: ' + error.response.data.messag)
        }
    })
}

const onCreateBranchReset = async function() {
    createBranchObject.value = {
        name: '',
        versionSchema: updatedComponent.value.featureBranchVersioning ? updatedComponent.value.featureBranchVersioning : 'Branch.Micro',
        component: componentUuid
    }
    customBranchVersionSchema.value = ''
}

async function notify (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const selectNewVcsRepo = ref(false)
const selectNewIntegrationRepo = ref(false)


const vcsRepos : Ref<any[]> = ref([])

const fetchVcsRepos = async function () : Promise<any[]> {
    let fetchedRepos = store.getters.vcsReposOfOrg(componentData.value.org)
    if (!fetchedRepos || !fetchedRepos.length) {
        fetchedRepos = await store.dispatch('fetchVcsRepos', componentData.value.org)
    }
    vcsRepos.value = fetchedRepos.map((repo: any) => {
        let repoObj = {
            label: repo.name + ' - ' + repo.uri,
            value: repo.uuid,
            uri: repo.uri
        }
        return repoObj
    })
    return vcsRepos.value
}

async function save () {
    updatedComponent.value = commonFunctions.deepCopy(await store.dispatch('updateComponent', updatedComponent.value))
}

const hasCoreSettingsChanges: ComputedRef<boolean> = computed((): boolean => {
    if (!updatedComponent.value || !componentData.value) return false
    
    // Check authentication changes
    const authChanged = updatedComponent.value.authentication && componentData.value.authentication &&
        (updatedComponent.value.authentication.type !== componentData.value.authentication.type ||
         updatedComponent.value.authentication.login !== componentData.value.authentication.login ||
         updatedComponent.value.authentication.password !== componentData.value.authentication.password)
    
    return updatedComponent.value.name !== componentData.value.name ||
        updatedComponent.value.versionSchema !== componentData.value.versionSchema ||
        updatedComponent.value.marketingVersionSchema !== componentData.value.marketingVersionSchema ||
        updatedComponent.value.featureBranchVersioning !== componentData.value.featureBranchVersioning ||
        updatedComponent.value.defaultConfig !== componentData.value.defaultConfig ||
        updatedComponent.value.repoPath !== componentData.value.repoPath ||
        updatedComponent.value.vcs !== componentData.value.vcs ||
        updatedComponent.value.approvalPolicy !== componentData.value.approvalPolicy ||
        authChanged ||
        JSON.stringify(updatedComponent.value.identifiers) !== JSON.stringify(componentData.value.identifiers)
})

function resetCoreSettings() {
    if (!componentData.value) return
    updatedComponent.value.name = componentData.value.name
    updatedComponent.value.versionSchema = componentData.value.versionSchema
    updatedComponent.value.marketingVersionSchema = componentData.value.marketingVersionSchema
    updatedComponent.value.featureBranchVersioning = componentData.value.featureBranchVersioning
    updatedComponent.value.defaultConfig = componentData.value.defaultConfig
    updatedComponent.value.repoPath = componentData.value.repoPath
    updatedComponent.value.vcs = componentData.value.vcs
    updatedComponent.value.approvalPolicy = componentData.value.approvalPolicy
    updatedComponent.value.identifiers = commonFunctions.deepCopy(componentData.value.identifiers)
    
    // Reset authentication if it exists
    if (componentData.value.authentication && updatedComponent.value.authentication) {
        updatedComponent.value.authentication = commonFunctions.deepCopy(componentData.value.authentication)
    }
}

const updateComponentResourceGroup = async function () {
    updatedComponent.value = commonFunctions.deepCopy(await store.dispatch('updateComponentResourceGroup', updatedComponent.value))
}

const setComponentVisibility = async function (newVisValue: string) {
    const onSwalConfirm = async function () {
        try {
            updatedComponent.value.visibilitySetting = newVisValue
            updatedComponent.value = commonFunctions.deepCopy(await store.dispatch('setComponentVisibility', updatedComponent.value))
        } catch (err: any) {
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(err.message),
                'error'
            )
        }
    }
    const swalData: SwalData = {
        questionText: `Are you sure you want to change the Visibility of the ${updatedComponent.value.name} ${words.value.component} to "${newVisValue}"?`,
        successTitle: 'Saved!',
        successText: `The visibility of the ${updatedComponent.value.name} ${words.value.component} has been modified to "${newVisValue}".`,
        dismissText: `Visibility Modification has been cancelled. The visibility remains as "${componentData.value.visibilitySetting}".`
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)

    
}

const triggerIntegration = function (integrationUuid: string) {
    axios.put('/api/manual/v1/component/triggerIntegration/' + componentData.value.uuid + '/' + integrationUuid).then((response) => {
        if (response.data.successful) {
            notify('success', 'Triggered', `Successfully sent trigger built event to CI, code = ${response.data.code}`)
        } else {
            notify('error', 'Error', `Error on CI build, code = ${response.data.code}. Please check your CI configuration.`)
        }
    })
}

const deployedToFields = [
    {
        key: 'instance',
        title: 'Instance',
        render(row: any) {
            return h('div',[
                h('a', {
                    href: `/instancesOfOrg/${updatedComponent.value.org}/${row.instanceUuid}`
                },
                [
                    h('span', row.instanceUri)
                ]
                ),
                
            ])
        }
    },
    {
        key: 'namespace',
        title: 'Namespace'
    },
    {
        key: 'environment',
        title: 'Environment'
    },
    {
        key: 'branch',
        title: 'Branch',
        render(row: any) {
            return h('span',row.branch.name)
        }
    },
    {
        key: 'releaseVersion',
        title: 'Version',
        render(row: any) {
            let el = h('span', 'Not Matched')
            if(row.releaseVersion){
                el = h('div', [
                    h('a', {
                        onClick: (e: Event) => { 
                            e.preventDefault() 
                            showReleaseModal(row.releaseUuid)
                        },
                        href: '#'
                    },
                    [h('span', row.releaseVersion)]
                    )
                    
                ])
            }
            return el
        }
    }
]

const deleteIntegration = async function (integrationUuid: string) {
    let deleteObj = {
        componentUuid: updatedComponent.value.uuid,
        integrationUuid
    }
    updatedComponent.value = commonFunctions.deepCopy(await store.dispatch('deleteComponentIntegration', deleteObj))
}

const getVcsRepoObjById = function (repoId: string) {
    let myVcsRepo : any
    for (let i=0; i < vcsRepos.value.length && !myVcsRepo; i++) {
        if (vcsRepos.value[i].value === repoId) {
            myVcsRepo = vcsRepos.value[i]
        }
    }
    return myVcsRepo
}

const navigateToVulnAnalysis = function () {
    if (!componentData.value) return
    
    const query: Record<string, string> = {
        componentProduct: componentData.value.uuid,
        type: componentData.value.type
    }
    
    router.push({
        name: 'VulnerabilityAnalysis',
        params: { orguuid: componentData.value.org },
        query
    })
}

const archiveComponent = async function () {
    const swalResult = await Swal.fire({
        title: `Are you sure you want to archive the ${updatedComponent.value.name} ${words.value.component}?`,
        text: `If you proceed, the ${words.value.component} will be archived and you will not have access to its data.`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, archive!',
        cancelButtonText: 'No, cancel'
    })

    if (swalResult.value) {
        const orgUuid = componentData.value.org
        const componentName = updatedComponent.value.name
        const isComponent = words.value.componentFirstUpper === 'Component'
        let archiveComponentParams = {
            componentUuid: componentData.value.uuid,
            orgUuid: orgUuid
        }
        try {
            let archived = await store.dispatch('archiveComponent', archiveComponentParams)
            if (archived) {
                Swal.fire(
                    'Archived!',
                    `${words.value.componentFirstUpper} "${componentName}" has been archived successfully.`,
                    'success'
                )
                if (isComponent) {
                    router.push({ name: 'ComponentsOfOrg', params: { orguuid: orgUuid } })
                } else {
                    router.push({ name: 'ProductsOfOrg', params: { orguuid: orgUuid } })
                }
            }
        } catch (err: any) {
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(err.message),
                'error'
            )
        }
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        notify('info', 'Cancelled', `${words.value.componentFirstUpper} archiving cancelled. Your ${words.value.component} is still active.`)
    }
}

const updateComponentKind = function (updKind: string) {
    updatedComponent.value.kind = updKind
    if (updatedComponent.value.kind === 'HELM' && !updatedComponent.value.authentication) {
        updatedComponent.value.authentication = {
            type: 'NOCREDS',
            login: '',
            password: ''
        }
    }
    save()
}

const componentAuthTypes = [
    { value: 'NOCREDS', label: 'No Credentials'},
    { value: 'CREDS', label: 'Helm Credentials'},
    { value: 'ECR', label: 'ECR Credentials'}
]

const secrets = ref([])

const fetchSecretsIfAllowed = async function() {
    if (isWritable) {
        secrets.value = await loadSecrets(componentData.value.org)
    }
}

const environmentTypes = ref([])

const loadEnvTypes = async function() {
    axios.get('/api/manual/v1/instance/environmentTypes/' + updatedComponent.value.org).then(envTypeResp => {
        envTypeResp.data.forEach((et: any) => {
            if (!updatedComponent.value.envBranchMap[et]) {
                updatedComponent.value.envBranchMap[et] = branches.value.filter((br: any) => br.type === 'BASE')[0].uuid
            }
        })
        environmentTypes.value = envTypeResp.data
    })
}

const branchTypes = [{label: 'Feature', value: 'FEATURE'}, {label: 'Release', value: 'RELEASE'}]
const selectedReleaseUuid: Ref<string> = ref('')
const showEditReleaseModal: Ref<boolean> = ref(false)
const showReleaseModal = function (rluuid: string) {
    selectedReleaseUuid.value = rluuid
    showEditReleaseModal.value = true
    // this.$refs['modal-component-edit-release'].show()
}

const marketingVersionEnabled = ref(updatedComponent.value.versionType === 'MARKETING')

const toggleMarketingVersion = async function(value: boolean){
    if(value){
        marketingVersionEnabled.value = true
        updatedComponent.value.versionType = 'MARKETING'
    }
    else{
        updatedComponent.value.versionType = 'DEV'
        marketingVersionEnabled.value = false
    }
        
    save()
}
let clickTimer: ReturnType<typeof setTimeout> | null = null
let preventSingleClick = false

const rowProps = (row: any) => {
    return {
        style: 'cursor: pointer;',
        onDblclick: () => {
            // Clear the single-click timer and prevent single-click action
            if (clickTimer) {
                clearTimeout(clickTimer)
                clickTimer = null
            }
            preventSingleClick = true
            
            router.push({
                name: isComponent.value ? 'ComponentsOfOrg' : 'ProductsOfOrg',
                params: {
                    orguuid: route.params.orguuid,
                    compuuid: componentUuid,
                    branchuuid: row.uuid
                },
                query: {
                    branchSettingsView: 'true'
                }
            })
        },
        onClick: () => {
            // Delay single-click action to detect if it's part of a double-click
            if (clickTimer) {
                clearTimeout(clickTimer)
            }
            
            clickTimer = setTimeout(() => {
                if (!preventSingleClick) {
                    selectBranch(row.uuid)
                }
                preventSingleClick = false
                clickTimer = null
            }, 250) // 250ms delay to detect double-click
        }
    }
}
const branchRowClassName = (row: any) => {
    return selectedBranchUuid.value === row.uuid ? 'selectedRow' : ''
}
const branchFields: any[] = [
    {
        title: () => {
            return h('div', { style: 'display: flex; align-items: center; gap: 8px;' }, [
                h('span', words.value.branchFirstUpper),
                isWritable.value ? h(
                    Icon,
                    {
                        class: 'clickable',
                        size: 20,
                        title: 'Create ' + words.value.branchFirstUpper,
                        onClick: () => { showAddBranchModal.value = true }
                    },
                    () => h(CirclePlus)
                ) : null
            ])
        },
        key: 'name'
    },
    {
        title: 'Version Schema',
        key: 'versionSchema',
        render: (row: any) => row.versionSchema ? row.versionSchema : 'Not set'
    },]

if (!isComponent.value && isWritable){
    branchFields.push({
        title: '',
        key: 'manage',
        render: (row: any) => {
            return h(
                NIcon, 
                {
                    title: 'Clone ' + words.value.branchFirstUpper,
                    class: 'icons clickable',
                    size: 25,
                    onClick: () => {cloneBrProps.value.originalBranch = row; cloneBrProps.value.schema = componentData.value.featureBranchVersioning; showCloneBranchModal.value = true}
                }, 
                () => h(Copy)
            )
        }
    })
}

const branchTableRowKey = (row: any) => row.uuid

// Pull Request fields - same as branch fields but with "Pull Request" header
const pullRequestFields: any[] = [
    {
        title: 'Pull Request',
        key: 'name'
    },
    {
        title: 'Version Schema',
        key: 'versionSchema',
        render: (row: any) => row.versionSchema ? row.versionSchema : 'Not set'
    }
]

// Tag fields - same as branch fields but with "Tag" header
const tagFields: any[] = [
    {
        title: 'Tag',
        key: 'name'
    },
    {
        title: 'Version Schema',
        key: 'versionSchema',
        render: (row: any) => row.versionSchema ? row.versionSchema : 'Not set'
    }
]

async function addOutputTrigger () {
    if (!updatedComponent.value.outputTriggers) {
        updatedComponent.value.outputTriggers = []
    }
    const outputTriggerToPush = commonFunctions.deepCopy(outputTrigger.value)
    
    if (outputTriggerToPush.uuid) {
        // Check if trigger with this UUID already exists
        const existingIndex = updatedComponent.value.outputTriggers.findIndex((ot: any) => ot.uuid === outputTriggerToPush.uuid)
        if (existingIndex > -1) {
            // Replace existing trigger
            updatedComponent.value.outputTriggers[existingIndex] = outputTriggerToPush
        } else {
            // Add new trigger
            updatedComponent.value.outputTriggers.push(outputTriggerToPush)
        }
    } else {
        // Add new trigger (no UUID means it's a new trigger)
        updatedComponent.value.outputTriggers.push(outputTriggerToPush)
    }
    
    save()
    resetOutputTrigger()
    showCreateOutputTriggerModal.value = false
}

function editOutputTrigger (trigger: any) {
    outputTrigger.value = commonFunctions.deepCopy(trigger)
    showCreateOutputTriggerModal.value = true
}

async function deleteOutputTrigger (uuid: string) {
    let inputTriggerIndex = -1
    if (updatedComponent.value.releaseInputTriggers && updatedComponent.value.releaseInputTriggers.length) { 
        inputTriggerIndex = updatedComponent.value.releaseInputTriggers.findIndex((rit: any) => rit.outputEvents.includes(uuid))
    }
    if (inputTriggerIndex > -1) {
        notify('error', 'Error', `Unable to delete because this trigger is used in one or more input triggers!`)
    } else {
        const triggerIndex = updatedComponent.value.outputTriggers.findIndex((ot: any) => ot.uuid === uuid)
        if (triggerIndex > -1) {
            updatedComponent.value.outputTriggers.splice(triggerIndex, 1)
            save()
            notify('success', 'Deleted', `Successfully deleted trigger.`)
        } else {
            notify('error', 'Error', `Error when deleting trigger!`)
        }
    }
}


function editInputTrigger (trigger: any) {
    inputTrigger.value = commonFunctions.deepCopy(trigger)
    showCreateInputTriggerModal.value = true
}

async function addInputTrigger () {
    if (!updatedComponent.value.releaseInputTriggers) {
        updatedComponent.value.releaseInputTriggers = []
    }
    const inputTriggerToPush = commonFunctions.deepCopy(inputTrigger.value)
    if (inputTriggerToPush.uuid) {
        // Check if trigger with this UUID already exists
        const existingIndex = updatedComponent.value.releaseInputTriggers.findIndex((ot: any) => ot.uuid === inputTriggerToPush.uuid)
        if (existingIndex > -1) {
            // Replace existing trigger
            updatedComponent.value.releaseInputTriggers[existingIndex] = inputTriggerToPush
        } else {
            // Add new trigger
            updatedComponent.value.releaseInputTriggers.push(inputTriggerToPush)
        }
    } else {
        // Add new trigger (no UUID means it's a new trigger)
        updatedComponent.value.releaseInputTriggers.push(inputTriggerToPush)
    }
    save()
    resetInputTrigger()
    showCreateInputTriggerModal.value = false
}

async function deleteInputTrigger (uuid: string) {
    const triggerIndex = updatedComponent.value.releaseInputTriggers.findIndex((rit: any) => rit.uuid === uuid)
    if (triggerIndex > -1) {
        updatedComponent.value.releaseInputTriggers.splice(triggerIndex, 1)
        save()
        notify('success', 'Deleted', `Successfully deleted trigger.`)
    } else {
        notify('error', 'Error', `Error when deleting trigger!`)
    }
}

const dataTableUuidRowKey = (row: any) => row.uuid

const outputTriggerTableFields: DataTableColumns<any> = [
    {
        key: 'name',
        title: 'Name'
    },
    {
        key: 'type',
        title: 'Type',
        render: (row: any) => {
            const option = outputTriggerTypeOptions.find(opt => opt.value === row.type)
            return option ? option.label : row.type
        }
    },
    {
        key: 'toReleaseLifecycle',
        title: 'Lifecycle to Change To',
        render: (row: any) => {
            const option = outputTriggerLifecycleOptions.find(opt => opt.value === row.toReleaseLifecycle)
            return option ? option.label : row.toReleaseLifecycle
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (isWritable) {
                const editEl = h(NIcon, {
                    title: 'Edit Trigger',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        editOutputTrigger(row)
                    }
                }, 
                () => h(Edit)
                )
                const deleteEl = h(NIcon, {
                    title: 'Delete Trigger',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        deleteOutputTrigger(row.uuid)
                    }
                }, 
                () => h(Trash)
                )
                els.push(editEl, deleteEl)
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    }
]

const inputTriggerTableFields: DataTableColumns<any> = [
    {
        key: 'name',
        title: 'Name'
    },
    {
        key: 'outputTriggers',
        title: 'Output Triggers',
        render: (row: any) => {
            let outTriggers = ''
            if (row.outputEvents && row.outputEvents.length) {
                row.outputEvents.forEach((oe: string) => {
                    const outTrigger = updatedComponent.value.outputTriggers.find((x: any) => x.uuid === oe)
                    if (outTrigger && outTrigger.uuid) {
                        outTriggers += outTrigger.name + ', '
                    }
                })
                if (outTriggers) outTriggers = outTriggers.substring(0, outTriggers.length - 2)
            }
            return h('div', outTriggers)
        }
    },
    {
        key: 'facts',
        title: 'Facts',
        render: (row: any) => {
            console.log(row)
            const factContent: any[] = []
            factContent.push(h('div', `UUID: ${row.uuid}`))
            factContent.push(h('h5', `Condition Groups:`))
            if (row.matchOperator === 'AND') {
                factContent.push(h('div', `Require ALL condition groups below`))
            } else {
                factContent.push(h('div', `Require ANY condition group below`))
            }
            const groupEls: any[] = []
            const conditionGroups: ConditionGroup[] = row.conditionGroup.conditionGroups
            for (const cg of conditionGroups) {
                const inGroupEls: any = parseConditionGroupToDisplayElements(cg)
                groupEls.push(inGroupEls)
            }
            factContent.push(h('ol', groupEls))
            const els: any[] = [
                h(NTooltip, {
                    trigger: 'hover'
                }, {trigger: () => h(NIcon,
                    {
                        class: 'icons',
                        size: 25,
                    }, () => h(Info20Regular)),
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
            if (isWritable) {
                const editEl = h(NIcon, {
                    title: 'Edit Trigger',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        editInputTrigger(row)
                    }
                }, 
                () => h(Edit)
                )
                const deleteEl = h(NIcon, {
                    title: 'Delete Trigger',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        deleteInputTrigger(row.uuid)
                    }
                }, 
                () => h(Trash)
                )
                els.push(editEl, deleteEl)
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    }
]


// 
// if (row.maxCriticalVulnerabilityCount > -1) factContent.push(h('li', `Maximum Critical Vulnerabilities: ${row.maxCriticalVulnerabilityCount}`))
// if (row.maxHighVulnerabilityCount > -1) factContent.push(h('li', `Maximum High Vulnerabilities: ${row.maxHighVulnerabilityCount}`))
// if (row.maxMediumVulnerabilityCount > -1) factContent.push(h('li', `Maximum Medium Vulnerabilities: ${row.maxMediumVulnerabilityCount}`))
// if (row.maxLowVulnerabilityCount > -1) factContent.push(h('li', `Maximum Low Vulnerabilities: ${row.maxLowVulnerabilityCount}`))
// if (row.maxUnassignedVulnerabilityCount > -1) factContent.push(h('li', `Maximum Unassigned Vulnerabilities: ${row.maxUnassignedVulnerabilityCount}`))
// if (row.maxLicensePolicyViolations > -1) factContent.push(h('li', `Maximum Licensing Policy Violaitons: ${row.maxLicensePolicyViolations}`))
// if (row.maxOperationalPolicyViolations > -1) factContent.push(h('li', `Maximum Operational Policy Violaitons: ${row.maxOperationalPolicyViolations}`))
// if (row.maxSecurityPolicyViolations > -1) factContent.push(h('li', `Maximum Security Policy Violaitons: ${row.maxSecurityPolicyViolations}`))


function parseConditionGroupToDisplayElements (cg : ConditionGroup): any {
    const inGroupEls: any[] = []
    if (cg.matchOperator === 'AND') {
        inGroupEls.push(h('div', `Require ALL conditions below`))
    } else {
        inGroupEls.push(h('div', `Require ANY condition below`))
    }
    for (const c of cg.conditions) {
        if (c.type === 'LIFECYCLE' && c.possibleLifecycles) {
            inGroupEls.push(h('li', `Possible Lifecycles: ${c.possibleLifecycles.toString()}`))
        } else if (c.type === 'BRANCH_TYPE' && c.possibleBranchTypes) {
            inGroupEls.push(h('li', `Possible Branch Types: ${c.possibleBranchTypes.toString()}`))
        } else if (c.type === 'APPROVAL_ENTRY') {
            const approvalEntry = updatedComponent.value.approvalPolicyDetails.approvalEntryDetails.find((aed: any) => aed.uuid === c.approvalEntry)
            if (approvalEntry) {
                inGroupEls.push(h('li', `${approvalEntry.approvalName}: ${c.approvalState}`))
            } else {
                inGroupEls.push(h('li', `Required approval does NOT belong to the approval policy. Please recreate trigger!`))
            }
        } else if (c.type === 'METRICS') {
            let metricsTypeName = ''
            if (c.metricsType === 'CRITICAL_VULNS') metricsTypeName = 'Critical vulnerabilities'
            else if (c.metricsType === 'HIGH_VULNS') metricsTypeName = 'High vulnerabilities'
            else if (c.metricsType === 'MEDIUM_VULNS') metricsTypeName = 'Medium vulnerabilities'
            else if (c.metricsType === 'LOW_VULNS') metricsTypeName = 'Low vulnerabilities'
            else if (c.metricsType === 'UNASSIGNED_VULNS') metricsTypeName = 'Unassigned vulnerabilities'
            else if (c.metricsType === 'SECURITY_VIOLATIONS') metricsTypeName = 'Security violations'
            else if (c.metricsType === 'OPERATIONAL_VIOLATIONS') metricsTypeName = 'Operational violations'
            else if (c.metricsType === 'LICENSE_VIOLATIONS') metricsTypeName = 'License violations'
            let compSign = ''
            if (c.comparisonSign === 'GREATER') compSign = '>'
            else if (c.comparisonSign === 'GREATER_OR_EQUALS') compSign = '>='
            else if (c.comparisonSign === 'LOWER_OR_EQUALS') compSign = '<='
            else if (c.comparisonSign === 'LOWER') compSign = '<'
            else if (c.comparisonSign === 'EQUALS') compSign = '='
            inGroupEls.push(h('li', `${metricsTypeName} ${compSign} ${c.metricsValue}`))
        }
    }
    return h('ul', inGroupEls)
}

const users: Ref<any[]> = ref([])
async function loadUsers() {
    const usersRaw = await store.dispatch('fetchUsers', orguuid.value)
    users.value = usersRaw.map((ur: any) => {return {label: ur.name, value: ur.uuid}} )
}

async function initLoad() {
    const compUuid = route.params.compuuid.toString()
    await store.dispatch('fetchBranches', compUuid)
    let storeComponent = store.getters.componentById(compUuid)
    if (!storeComponent || !storeComponent.versionType) {
        storeComponent = await store.dispatch('fetchComponentFull', compUuid)
    }
    updatedComponent.value = commonFunctions.deepCopy(storeComponent)
    isComponent.value = updatedComponent.value.type === 'COMPONENT'
    const orgTerminology = myorg.value?.terminology
    const resolvedWords = commonFunctions.resolveWords(!isComponent.value ? false : true, orgTerminology)
    words.value = {
        branchFirstUpper: resolvedWords.branchFirstUpper,
        branchFirstUpperPlural: isComponent.value ? 'Branches' : (resolvedWords.branchFirstUpper + 's'),
        branch: resolvedWords.branch,
        componentFirstUpper: resolvedWords.componentFirstUpper,
        component: resolvedWords.component,
        componentsFirstUpper: resolvedWords.componentsFirstUpper
    }
    fetchVcsRepos()
    
    // Auto-select tab based on branch type if branchuuid is provided
    if (branchRouteId && !route.query.tab) {
        const allBranches = store.getters.branchesOfComponent(compUuid)
        const selectedBranch = allBranches.find((b: any) => b.uuid === branchRouteId)
        if (selectedBranch) {
            if (selectedBranch.type === 'PULL_REQUEST') {
                selectedTab.value = 'pull-requests'
            } else if (selectedBranch.type === 'TAG') {
                selectedTab.value = 'tags'
            }
            // For 'BRANCH' type or undefined, keep default 'branches'
        }
    }
}

async function handleTabSwitch(tabName: string) {
    if (tabName === "outputTriggers") {
        await fetchCiIntegrations()
    } else if (tabName === "Admin Settings") {
        await fetchPerspectives()
    }
}

</script>

<style scoped lang="scss">
.icons {
    margin-left: 10px;
}
.apiKeyButtons {
    display: grid;
    grid-template-columns: 1fr 1fr;
    padding-top: 10px;
    padding-bottom: 10px;
}
.componentWrapper {
    display: grid;
    grid-template-columns: 0.9fr 1fr;
    grid-gap: 10px;
}
.branchList {
    display: grid;
    grid-template-columns: 4fr 2fr 24px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
    .branchDetails {
        background-color: #f7f7f7;
        grid-column: 1/5;
        overflow-wrap: break-word;
        cursor: default;
        .branchDetailContent div {
           border: none;
        }
        .editReleaseIcon {
            float: right;
        }
        .branchDetailsInnerHeader {
            margin-top: 7px;
            font-weight: bold;
        }
    }
}
.prList {
    display: grid;
    grid-template-columns: 4fr 2fr;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.branchPrList {
    display: grid;
    grid-template-columns: 24px 4fr 2fr 3fr 24px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}

.branchList:hover {
    background-color: #d9eef3;
}
.branchHeader {
    background-color: #f9dddd;
    font-weight: bold;
}
.prHeader {
    background-color: #F9EBDD;
    font-weight: bold;
}
.etName {
    display: inline-block;
    width: 180px;
}

.inline {
    display: inline;
}
.coreSettingsActions {
    padding: 15px;
    background-color: #f5f5f5;
    border-radius: 8px;
    margin-bottom: 15px;
}
.versionSchemaBlock, .componentNameBlock {
    padding-top: 15px;
    padding-bottom: 15px;
    display: flex;
    flex-wrap: nowrap;
    align-items: center;
    gap: 12px;
    
    label {
        font-weight: bold;
        min-width: 250px;
        flex-shrink: 0;
    }
    input {
        flex: 1;
        min-width: 0;
    }
    .n-input {
        flex: 1;
        min-width: 200px;
        
        :deep(.n-input-wrapper) {
            padding-left: 12px;
            padding-right: 12px;
        }
        :deep(.n-input__input-el) {
            padding: 0;
        }
    }
    .n-select {
        flex: 1;
        min-width: 200px;
    }
    select {
        flex: 1;
        min-width: 200px;
    }
    span {
        flex: 1;
    }
    .versionIcon {
        flex-shrink: 0;
    }
    .accept {
        color: green;
    }
    .accept:hover {
        color: darkgreen;
    }
    .reject {
        color: red;
    }
    .reject:hover {
        color: darkred;
    }
}

.identifierBlock {
    padding-top: 15px;
    padding-bottom: 15px;
    label {
        display: block;
        font-weight: bold;
        width: 60%
    }
    .n-select {
        display: inline-block;
        width: 60%;
    }
    select {
        display: inline-block;
        width: 60%;
    }
    .accept {
        color: green;
    }
    .accept:hover {
        color: darkgreen;
    }
    .reject {
        color: red;
    }
    .reject:hover {
        color: darkred;
    }
}

.selectedBranch {
    background-color: #c4c4c4;
}
:deep(.selectedRow td){
    background-color: #f1f1f1 !important;
}
.selectedPr {
    background-color: #dedede;
}
.linkedVcsRepoBlock {
    margin-bottom: 10px;
}
projSettingsCollapse {
    margin-bottom: 105px;
}

.componentIconsAndSettings {
    margin-bottom: 5px;
}

</style>