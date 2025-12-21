<template>
    <div>
        <h4>Organization Settings</h4>
        <n-tabs type="line" :value="currentTab" @update:value="handleTabSwitch">
            <n-tab-pane name="integrations" tab="Integrations" v-if="isOrgAdmin">
                <div class="integrationsBlock mt-4">
                    <h5>Organization-Wide Integrations</h5>
                    <n-space vertical>
                        <div class="row">
                            <div v-if="configuredIntegrations.includes('SLACK')">Slack Integration Configured
                                <vue-feather @click="deleteIntegration('SLACK')" class="clickable" type="trash-2" />
                            </div>
                            <div v-else><n-button @click="showOrgSettingsSlackIntegrationModal = true">Add Slack Integration</n-button></div>
                            <n-modal 
                                v-model:show="showOrgSettingsSlackIntegrationModal"
                                preset="dialog"
                                :show-icon="false" >
                                <n-card style="width: 600px" size="huge" title="Add slack integration" :bordered="false"
                                    role="dialog" aria-modal="true">

                                    <n-form>
                                        <n-form-item id="org_settings_create_slack_integration_secret_group" label="Secret"
                                            label-for="org_settings_create_slack_integration_secret"
                                            description="Slack integration secret">
                                            <n-input type="password" id="org_settings_create_slack_integration_secret"
                                                v-model:value="createIntegrationObject.secret" required
                                                placeholder="Enter Slack integration secret" />
                                        </n-form-item>
                                        <n-button @click="onAddIntegration('SLACK')" type="success">Submit</n-button>
                                        <n-button type="error" @click="resetCreateIntegrationObject">Reset</n-button>
                                    </n-form>
                                </n-card>
                            </n-modal>
                        </div>
                        <div class="row pt-2">
                            <div v-if="configuredIntegrations.includes('MSTEAMS')">MS Teams integration configured
                                <vue-feather @click="deleteIntegration('MSTEAMS')" class="clickable" type="trash-2" />
                            </div>
                            <div v-else><n-button @click="showOrgSettingsMsteamsIntegrationModal = true">Add MS Teams Integration</n-button></div>
                            <n-modal
                                v-model:show="showOrgSettingsMsteamsIntegrationModal"
                                preset="dialog"
                                :show-icon="false" >
                                <n-card style="width: 600px" size="huge" title="Add MS Teams integration" :bordered="false"
                                    role="dialog" aria-modal="true">
                                    <n-form @submit="onAddIntegration('MSTEAMS')">
                                        <n-form-item id="org_settings_create_msteams_integration_secret_group" label="Secret"
                                            label-for="org_settings_create_msteams_integration_secret"
                                            description="MS Teams integration URI">
                                            <n-input type="password" id="org_settings_create_msteams_integration_secret"
                                                v-model:value="createIntegrationObject.secret" required
                                                placeholder="Enter MS Teams integration URI" />
                                        </n-form-item>
                                        <n-button @click="onAddIntegration('MSTEAMS')" type="success">Submit</n-button>
                                        <n-button type="error" @click="resetCreateIntegrationObject">Reset</n-button>
                                    </n-form>
                                </n-card>
                            </n-modal>
                        </div>
                        <div class="row pt-2">
                            <div v-if="configuredIntegrations.includes('DEPENDENCYTRACK')"> Dependency-Track integration configured
                                <vue-feather @click="deleteIntegration('DEPENDENCYTRACK')" class="clickable" type="trash-2" />
                            </div>
                            <div v-else><n-button @click="showOrgSettingsDependencyTrackIntegrationModal = true">Add Dependency-Track Integration</n-button></div>
                            <n-modal
                                v-model:show="showOrgSettingsDependencyTrackIntegrationModal"
                                preset="dialog"
                                :show-icon="false" >
                                <n-card style="width: 600px" size="huge" title="Add Dependency-Track Integration" :bordered="false"
                                    role="dialog" aria-modal="true">
                                    <n-form @submit="onAddIntegration('DEPENDENCYTRACK')">
                                        <n-form-item id="org_settings_create_dependency_track_integration_uri_group" label="Dependency-Track API Server URI"
                                            label-for="org_settings_create_dependency_track_integration_uri"
                                            description="Dependency-Track API Server URI">
                                            <n-input id="org_settings_create_dependency_track_integration_uri"
                                                v-model:value="createIntegrationObject.uri" required
                                                placeholder="Enter Dependency-Track API Server URI" />
                                        </n-form-item>
                                        <n-form-item id="org_settings_create_dependency_track_integration_frontenduri_group" label="Dependency-Track Frontend URI"
                                            label-for="org_settings_create_dependency_track_integration_frontenduri"
                                            description="Dependency-Track API Server Frontend URI">
                                            <n-input id="org_settings_create_dependency_track_integration_frontenduri"
                                                v-model:value="createIntegrationObject.frontendUri" required
                                                placeholder="Enter Dependency-Track Frontend URI" />
                                        </n-form-item>
                                        <n-form-item id="org_settings_create_dependency_track_integration_secret_group" label="API Key"
                                            label-for="org_settings_create_dependency_track_integration_secret"
                                            description="Dependency-Track API Key">
                                            <n-input type="password" id="org_settings_create_dependency_track_integration_secret"
                                                v-model:value="createIntegrationObject.secret" required
                                                placeholder="Enter Dependency-Track API Key" />
                                        </n-form-item>
                                        <n-button @click="onAddIntegration('DEPENDENCYTRACK')" type="success">Submit</n-button>
                                        <n-button @click="resetCreateIntegrationObject" type="error">Reset</n-button>
                                    </n-form>
                                </n-card>
                            </n-modal>
                        </div>
                    </n-space>
                    <h5>CI Integrations</h5>
                    <n-data-table :columns="ciIntegrationTableFields" :data="ciIntegrations" :row-key="dataTableRowKey"></n-data-table>
                    <n-button @click="showCIIntegrationModal=true">Add CI Integration</n-button>
                </div>
                <n-modal
                    v-model:show="showCIIntegrationModal"
                    preset="dialog"
                    :show-icon="false"
                    style="width: 90%"
                >
                    <n-form :model="createIntegrationObject">
                        <h2>Add or Update CI Integration</h2>
                        <n-space vertical size="large">
                            <n-form-item label="Description" path="note">
                                <n-input v-model:value="createIntegrationObject.note" required placeholder="Enter Description" />
                            </n-form-item>
                            <n-form-item label="CI Type" path="createIntegrationObject.type">
                                <n-radio-group v-model:value="createIntegrationObject.type" name="ciIntegrationType">
                                    <n-radio-button label="GitHub" value="GITHUB" />
                                    <n-radio-button label="GitLab" value="GITLAB" />
                                    <n-radio-button label="Jenkins" value="JENKINS" />
                                    <n-radio-button label="Azure DevOps" value="ADO" />
                                </n-radio-group>
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'GITHUB'" id="org_settings_create_github_integration_secret_group" label="GitHub Private Key DER Base64"
                                label-for="org_settings_create_github_integration_secret"
                                description="GitHub Private Key DER Base64">
                                <n-input type="textarea" id="org_settings_create_github_integration_secret"
                                    v-model:value="createIntegrationObject.secret" required
                                    placeholder="Enter GitHub Private Key Base64, use 'openssl pkcs8 -topk8 -inform PEM -outform DER -in private-key.pem -out key.der -nocrypt | base64 -w 0 key.der' to obtain" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'GITHUB'" id="org_settings_create_github_integration_appid_group" label="GitHub Application ID"
                                label-for="org_settings_create_github_integration_appid"
                                description="GitHub Application ID">
                                <n-input type="number" id="org_settings_create_github_integration_appid"
                                    v-model:value="createIntegrationObject.schedule" required
                                    placeholder="Enter GitHub Application ID" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'GITLAB'" label="GitLab Authentication Token" path="createIntegrationObject.secret">
                                <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter GitLab Authentication Token" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'INTEGRATION_TRIGGER' && createIntegrationObject.type === 'JENKINS'" label="Jenkins Token" path="createIntegrationObject.secret">
                                <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter Jenkins Token" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'JENKINS'" label="Jenkins URI" path="createIntegrationObject.uri">
                                <n-input v-model:value="createIntegrationObject.uri" required placeholder="Jenkins Home URI (i.e. https://jenkins.localhost)" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'JENKINS'" label="Jenkins Token" path="createIntegrationObject.secret">
                                <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter Jenkins Token" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Client ID" path="createIntegrationObject.client">
                                <n-input v-model:value="createIntegrationObject.client" required placeholder="Enter Client ID" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Client Secret" path="createIntegrationObject.secret">
                                <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter Client Secret" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Tenant ID" path="createIntegrationObject.tenant">
                                <n-input v-model:value="createIntegrationObject.tenant" required placeholder="Enter Tenant ID" />
                            </n-form-item>
                            <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Azure DevOps Organization Name" path="integrationObject.uri">
                                <n-input v-model:value="createIntegrationObject.uri" required placeholder="Enter Azure DevOps organization name" />
                            </n-form-item>
                            <n-button @click="addCiIntegration" type="success">
                                Save
                            </n-button>
                        </n-space>
                    </n-form>
                </n-modal>
            </n-tab-pane>

            <n-tab-pane name="users" tab="Users" v-if="isOrgAdmin">
                <div class="userBlock mt-4">
                    <h5>Users ({{ users.length }})</h5>
                    <n-data-table :columns="userFields" :data="users" class="table-hover">
                    </n-data-table>
                    <n-modal
                        v-model:show="showOrgRegistryTokenModal"
                        preset="dialog"
                        :show-icon="false" >
                        <n-card style="width: 600px" size="huge" title="Organization Registry Token" :bordered="false"
                            role="dialog" aria-modal="true">
                            <div><strong>Username: </strong><n-input type="textarea" disabled v-model:value="robotName"
                                    rows="1" /></div>
                            <div><strong>Token (only displayed once): </strong><n-input type="textarea" disabled
                                    v-model:value="botToken" /></div>
                        </n-card>
                    </n-modal>
                    <n-form v-if="isOrgAdmin" class="inviteUserForm" @submit="inviteUser">
                        <n-input-group class="mt-3">
                            <n-input id="settings-invite-user-email-input" v-model:value="invitee.email" required
                                placeholder="User Email" />
                            <n-select id="settings-invite-permissions-type-input" v-model:value="invitee.type" required
                                :options="permissionTypeSelections" />
                            <n-button :loading="processingMode" @click="inviteUser" type="info">Invite</n-button>
                        </n-input-group>
                    </n-form>
                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        style="width: 90%;" 
                        v-model:show="showOrgSettingsUserPermissionsModal" 
                        :title="'User Permissions for ' + selectedUser.email"
                    >
                        <n-flex vertical>
                            <n-space style="margin-top: 20px; margin-bottom: 20px;">
                                <n-h5>
                                    <n-text depth="1">
                                        Organization-Wide Permissions:
                                    </n-text>
                                </n-h5>
                                <n-radio-group v-model:value="selectedUser.type" :onUpdate:value ="(value: string) => {selectedUser.type = value}">
                                    <n-radio-button
                                        v-for="pt in permissionTypeswAdmin"
                                        :key="pt"
                                        :value="pt"
                                        :disabled="false"
                                        :label="translatePermissionName(pt)"
                                    />
                                </n-radio-group>
                                
                            </n-space>
                            <n-space style="margin-bottom: 20px;" v-if="selectedUserType !== 'ADMIN' && myorg.approvalRoles && myorg.approvalRoles.length">
                                <n-h5>
                                    <n-text depth="1">
                                        Approval Permissions:
                                    </n-text>
                                </n-h5>
                                <n-checkbox-group id="modal-org-settings-user-permissions-approval-checkboxes"
                                    v-model:value="selectedUser.approvals"
                                    :onUpdate:value ="(value: string) => {selectedUser.approvals = value}"
                                >
                                    <n-checkbox v-for="a in myorg.approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" ></n-checkbox>
                                </n-checkbox-group>

                            </n-space>
                        </n-flex>
                        <n-space>
                            <n-button type="success" @click="updateUserPermissions">Save Permissions</n-button>
                            <n-button type="warning" @click="editUser(selectedUser.email)">Reset Changes</n-button>
                        </n-space>
                        <n-flex v-if="false" v-show="selectedUserType !== 'ADMIN'"  >
                            <n-h5>
                                <n-text depth="1">
                                    Instance Permissions:
                                </n-text>
                            </n-h5>
                            <n-grid cols="12" item-responsive>
                                <n-gi :span="6">
                                    <n-input round clearable @input="filterInstances" :style="{ 'max-width': '90%' }" placeholder="Search" >
                                        <template #suffix>
                                            <vue-feather type="search"/>
                                        </template>
                                    </n-input>  
                                </n-gi>
                            </n-grid>
                                
                            <n-space vertical>
                                <n-data-table :columns="userInstancePermissionColumns" :data="instances"/>
                            </n-space>

                            <n-space vertical>
                                <n-data-table :columns="userClusterPermissionColumns" :data="clusters" children-key="instanceChildren" :default-expand-all="true"/>
                            </n-space>

                        </n-flex>
                    </n-modal>
                    <h6>Pending Invites</h6>
                    <n-data-table :columns="inviteeFields" :data="invitees" class="table-hover">
                    </n-data-table>
                </div>
            </n-tab-pane>

            <n-tab-pane v-if="isOrgAdmin" name="userGroups" tab="User Groups">
                <div class="userGroupBlock mt-4">
                    <h5>User Groups ({{ userGroups.length }})</h5>
                    <n-data-table :columns="userGroupFields" :data="userGroups" class="table-hover">
                    </n-data-table>
                    <n-form v-if="isOrgAdmin" class="createUserGroupForm" @submit="createUserGroup">
                        <n-input-group class="mt-3">
                            <n-input id="settings-create-user-group-name-input" v-model:value="newUserGroup.name" required
                                placeholder="User Group Name" />
                            <n-input id="settings-create-user-group-description-input" v-model:value="newUserGroup.description"
                                placeholder="Description (optional)" />
                            <n-button :loading="processingMode" @click="createUserGroup" type="info">Create Group</n-button>
                        </n-input-group>
                    </n-form>
                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        style="width: 90%;" 
                        v-model:show="showUserGroupPermissionsModal" 
                        :title="'User Group Settings for ' + selectedUserGroup.name"
                    >
                        <n-flex vertical>
                            <n-space style="margin-top: 20px; margin-bottom: 20px;">
                                <n-h5>
                                    <n-text depth="1">
                                        Basic Information:
                                    </n-text>
                                </n-h5>
                                <n-form-item label="Name">
                                    <n-input v-model:value="selectedUserGroup.name" placeholder="User Group Name" />
                                </n-form-item>
                                <n-form-item label="Description">
                                    <n-input v-model:value="selectedUserGroup.description" placeholder="Description" />
                                </n-form-item>
                            </n-space>
                            
                            <n-space style="margin-bottom: 20px;">
                                <n-h5>
                                    <n-text depth="1">
                                        Users in Group:
                                    </n-text>
                                </n-h5>
                                <n-select
                                    v-model:value="selectedUserGroup.users"
                                    multiple
                                    :options="userOptions"
                                    placeholder="Select users to add to group"
                                    style="width: 100%; min-width: 400px;"
                                />
                            </n-space>

                            <n-space style="margin-bottom: 20px;">
                                <n-h5>
                                    <n-text depth="1">
                                        SSO Connected Groups:
                                    </n-text>
                                </n-h5>
                                <n-dynamic-input
                                    v-model:value="selectedUserGroup.connectedSsoGroups"
                                    :on-create="onCreateSsoGroup"
                                    placeholder="Add SSO group name"
                                />
                            </n-space>

                            <n-space style="margin-bottom: 20px;">
                                <n-h5>
                                    <n-text depth="1">
                                        Organization-Wide Permissions:
                                    </n-text>
                                </n-h5>
                                <n-radio-group v-model:value="selectedUserGroup.orgPermissionType">
                                    <n-radio-button
                                        v-for="pt in permissionTypeswAdmin"
                                        :key="pt"
                                        :value="pt"
                                        :label="translatePermissionName(pt)"
                                    />
                                </n-radio-group>
                            </n-space>

                            <n-space style="margin-bottom: 20px;" v-if="selectedUserGroup.orgPermissionType !== 'ADMIN' && myorg.approvalRoles && myorg.approvalRoles.length">
                                <n-h5>
                                    <n-text depth="1">
                                        Approval Permissions:
                                    </n-text>
                                </n-h5>
                                <n-checkbox-group id="modal-org-settings-user-group-permissions-approval-checkboxes"
                                    v-model:value="selectedUserGroup.approvals"
                                >
                                    <n-checkbox v-for="a in myorg.approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" ></n-checkbox>
                                </n-checkbox-group>
                            </n-space>
                        </n-flex>
                        <n-space>
                            <n-button type="success" @click="updateUserGroup">Save Changes</n-button>
                            <n-button type="warning" @click="editUserGroup(selectedUserGroup.uuid)">Reset Changes</n-button>
                        </n-space>
                    </n-modal>
                </div>
            </n-tab-pane>

            <n-tab-pane name="programmaticAccess" tab="Programmatic Access" v-if="isOrgAdmin">
                <div class="programmaticAccessBlock mt-4">
                    <h5>Programmatic Access</h5>
                    <n-data-table :columns="programmaticAccessFields" :data="computedProgrammaticAccessKeys"
                        class="table-hover">
                    </n-data-table>
                    <vue-feather v-if="isOrgAdmin" class="clickable" type="plus-circle" @click="genApiKey"
                        title="Create Api Key" />
                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        style="width: 90%;"
                        v-model:show="showOrgSettingsProgPermissionsModal">
                        <n-card size="huge"
                            :title="'Set approval permissions for key: ' + selectedKey.uuid" :bordered="false" role="dialog"
                            aria-modal="true">

                            <n-form>
                                <n-form-item label='Approval Permissions:'>
                                    <n-checkbox-group id="modal-org-settings-programmatic-permissions-approval-checkboxes"
                                        v-model:value="selectedKey.approvals">
                                        <n-checkbox v-for="a in myorg.approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" ></n-checkbox>
                                    </n-checkbox-group>
                                </n-form-item>
                                <n-form-item label='Notes:'>
                                    <n-input
                                        v-model:value="selectedKey.notes"
                                        type="textarea"
                                        placeholder="Notes"
                                    />
                                </n-form-item>
                                <n-button @click="updateKeyPermissions" type="success">Submit</n-button>
                            </n-form>
                        </n-card>
                    </n-modal>
                </div>
            </n-tab-pane>

            <n-tab-pane name="approvalPolicies" tab="Approval Policies" v-if="myUser.installationType !== 'OSS'">
                <div class="programmaticAccessBlock mt-4">
                    <h4>Approval Roles:                        
                        <Icon v-if="isWritable" class="clickable addIcon" size="25" title="Create Approval Role" @click="showCreateApprovalRole = true">
                            <CirclePlus/>
                        </Icon>
                    </h4>
                    <n-data-table :columns="approvalRoleFields" :data="myorg.approvalRoles" class="table-hover"></n-data-table>
                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        style="width: 90%;" 
                        v-model:show="showCreateApprovalRole" 
                        title="Create Approval Role"
                    >
                        <n-form :model="newApprovalRole">
                            <n-form-item path="id" label="Role ID">
                                <n-input v-model:value="newApprovalRole.id" placeholder="Enter New Approval Role ID"
                                required />
                            </n-form-item>
                            <n-form-item path="displayView" label="Role Display Name">
                                <n-input v-model:value="newApprovalRole.displayView" required placeholder="Enter New Approval Role Display Name" />
                            </n-form-item>
                            <n-space>
                                <n-button type="success" @click="addApprovalRole">Create</n-button>
                                <n-button type="warning" @click="resetCreateApprovalRole">Reset</n-button>
                            </n-space>
                        </n-form>
                    </n-modal>

                    <h4>Approval Entries:
                        <Icon v-if="isWritable" class="clickable addIcon" size="25" title="Create Approval Entry" @click="showCreateApprovalEntry = true">
                            <CirclePlus/>
                        </Icon>
                    </h4>
                    <n-data-table :data="approvalEntryTableData" :columns="approvalEntryFields" :row-key="dataTableRowKey" />
                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        style="width: 90%;" 
                        v-model:show="showCreateApprovalEntry" 
                        title="Create Approval Entry"
                    >
                        <create-approval-entry 
                            :orgProp="orgResolved"
                            :isHideTitle="true" 
                            @approvalEntryCreated="approvalEntryCreated"/>
                    </n-modal>
                    <h4>Approval Policies:
                        <Icon v-if="isWritable" class="clickable addIcon" size="25" title="Create Approval Policy" @click="showCreateApprovalPolicy = true">
                            <CirclePlus/>
                        </Icon>
                    </h4>
                    <n-data-table :data="approvalPolicyTableData" :columns="approvalPolicyFields" :row-key="dataTableRowKey" />

                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        style="width: 90%;" 
                        v-model:show="showCreateApprovalPolicy" 
                        title="Create Approval Policy"
                    >
                        <create-approval-policy
                            :orgProp="orgResolved" 
                            :isHideTitle="true"
                            @approvalPolicyCreated="approvalPolicyCreated"/>
                    </n-modal>
                </div>
            </n-tab-pane>

            <n-tab-pane name="terminology" tab="Terminology" v-if="isOrgAdmin">
                <div class="terminologyBlock mt-4">
                    <h5>Custom Terminology</h5>
                    <p class="text-muted">Customize the labels used throughout the application for your organization.</p>
                    <n-form :model="terminologyForm" label-placement="left" label-width="200px" style="max-width: 500px;">
                        <n-form-item label="Feature Set Label" path="featureSetLabel">
                            <n-input 
                                v-model:value="terminologyForm.featureSetLabel" 
                                placeholder="Feature Set"
                                maxlength="50"
                                show-count
                            />
                        </n-form-item>
                        <n-space>
                            <n-button type="success" @click="saveTerminology" :loading="savingTerminology">Save</n-button>
                            <n-button type="warning" @click="resetTerminology">Reset to Default</n-button>
                        </n-space>
                    </n-form>
                </div>
            </n-tab-pane>

            <n-tab-pane v-if="false && myUser && myUser.installationType !== 'OSS'" name="protected environments" tab="Protected Environments">
                <div v-if="resourceGroups && resourceGroups.length" class="approvalMatrixBlock">
                    <h5>Protected Environments For Resource Group:
                        <n-dropdown trigger="hover" title="Select Resource Group"
                            :text="myapp.name" :options="resourceGroupOptions" @select="selectResourceGroup">
                            <n-button>{{ myapp.name }}</n-button>
                        </n-dropdown>
                    </h5>
                    <div class="container">
                        <n-form label-placement="left" :style="{maxWidth: '640px'}" size="medium">
                            <n-checkbox-group v-model:value="protectedEnvironments" :options="environmentOptions">
                                <n-checkbox v-for="b in environmentOptions" :key="b.value" :value="b.value" :label="b.label"></n-checkbox>
                            </n-checkbox-group>
                            <n-button attr-type="submit"  type="success" @click="saveProtectedEnvironments" :disabled="myapp.protectedEnvironments && myapp.protectedEnvironments.toString() === protectedEnvironments.toString()">Save</n-button>
                            <!-- <n-button attr-type="reset" @click="resetApprovals">Reset</n-button> -->
                        </n-form>
                    </div>
                </div>
            </n-tab-pane>
            <n-tab-pane name="perspectives" tab="Perspectives" v-if="myUser.installationType !== 'OSS'">
                <div class="perspectivesBlock mt-4">
                    <h5>Perspectives ({{ perspectives.length }})
                        <Icon v-if="isOrgAdmin" class="clickable addIcon" size="25" title="Create Perspective" @click="showCreatePerspectiveModal = true">
                            <CirclePlus />
                        </Icon>
                    </h5>
                    <n-data-table :columns="perspectiveFields" :data="perspectives" class="table-hover">
                    </n-data-table>
                    <n-modal
                        v-model:show="showCreatePerspectiveModal"
                        preset="dialog"
                        :show-icon="false"
                        style="width: 600px;">
                        <n-card size="huge" title="Create Perspective" :bordered="false"
                            role="dialog" aria-modal="true">
                            <n-form @submit.prevent="createPerspective">
                                <n-form-item label="Perspective Name" label-placement="top">
                                    <n-input
                                        v-model:value="newPerspective.name"
                                        placeholder="Enter perspective name"
                                        required />
                                </n-form-item>
                                <n-space>
                                    <n-button :loading="processingMode" @click="createPerspective" type="success">Create</n-button>
                                    <n-button type="error" @click="resetCreatePerspective">Cancel</n-button>
                                </n-space>
                            </n-form>
                        </n-card>
                    </n-modal>
                    <n-modal
                        preset="dialog"
                        :show-icon="false"
                        v-model:show="showPerspectiveComponentsModal"
                        style="width: 900px;">
                        <n-card size="huge" :title="'Components and Products of Perspective: ' + selectedPerspectiveName" :bordered="false"
                            role="dialog" aria-modal="true">
                            <n-data-table
                                v-if="perspectiveComponents && perspectiveComponents.length > 0"
                                :columns="perspectiveComponentColumns"
                                :data="perspectiveComponents"
                                class="table-hover" />
                            <div v-else>
                                No Components or Products Connected with this perspective
                            </div>
                        </n-card>
                    </n-modal>
                    <n-modal
                        v-model:show="showEditPerspectiveModal"
                        preset="dialog"
                        :show-icon="false"
                        style="width: 600px;">
                        <n-card size="huge" title="Edit Perspective" :bordered="false"
                            role="dialog" aria-modal="true">
                            <n-form @submit.prevent="updatePerspectiveName">
                                <n-form-item label="Perspective Name" label-placement="top">
                                    <n-input
                                        v-model:value="editingPerspective.name"
                                        placeholder="Enter perspective name"
                                        required />
                                </n-form-item>
                                <n-space>
                                    <n-button :loading="processingMode" @click="updatePerspectiveName" type="success">Save</n-button>
                                    <n-button type="error" @click="cancelEditPerspective">Cancel</n-button>
                                </n-space>
                            </n-form>
                        </n-card>
                    </n-modal>
                </div>
            </n-tab-pane>
            <n-tab-pane name="registry" tab="Registry" v-if="globalRegistryEnabled">
                <div class="mt-4">
                    <h5>Organization Registry</h5>
                    <div v-if="!orgRegistry">
                        <div v-if="isOrgAdmin">
                            Enable Organization Registry :
                            <vue-feather @click="enableRegistry()" class="clickable icons" type="package"
                                title="Enable Organization Registry" />
                        </div>
                    </div>
                    <div v-else>Organization Registry Commands:
                        <vue-feather @click="showRegistryCommands()" class="clickable icons" type="package"
                            title="Show Organization Registry Commands" />
                    </div>
                    <n-modal 
                        v-model:show="showComponentRegistryModal"
                        preset="dialog"
                        style="width: 90%;"
                        :show-icon="false" >
                        <n-card size="huge" title="Organization Registry Commands" :bordered="false"
                            role="dialog" aria-modal="true">
                            <!-- <div><strong>Username: </strong><n-input type="textarea" disabled v-model:value="robotName" rows="1"/></div>
                        <div><strong>Token (only displayed once): </strong><n-input type="textarea" disabled v-model:value="botToken" /></div> -->
                            <!-- <div><strong>Image Repository: </strong><n-input type="textarea" disabled v-model:value="imageRegistry" /></div> -->
                            <div><span v-html="imageRegistry" /></div>
                        </n-card>
                    </n-modal>
                </div>
            </n-tab-pane>
        </n-tabs>
        <n-modal 
            v-model:show="showCreateResourceGroupModal"
            preset="dialog"
            style="width: 90%; height: 130px;"
            :show-icon="false" >
            <n-input-group style="margin-top: 30px;">
                <n-input v-model:value="newappname" type="text" placeholder="Name of the resourceGroup" />
                <n-button type="success" @click="createApp">Create resourceGroup</n-button>
            </n-input-group>
        </n-modal>
        <n-modal
            preset="dialog"
            :show-icon="false"
            v-model:show="showOrgApiKeyModal">
            <n-card style="width: 600px" size="huge" title="Your Organization API Key (shown only once)" :bordered="false"
                role="dialog" aria-modal="true">

                <p>Please record these data as you will see API key only once (although you can re-generate it at any time):
                </p>
                <div><strong>API ID: </strong><n-input type="textarea" disabled v-model:value="apiKeyId" />
                </div>
                <div><strong>API Key: </strong><n-input type="textarea" disabled v-model:value="apiKey" />
                </div>
                <div><strong>Basic Authentication Header: </strong><n-input type="textarea" disabled
                        v-model:value="apiKeyHeader" rows="4" />
                </div>
            </n-card>
        </n-modal>
        <n-modal
            preset="dialog"
            :show-icon="false"
            v-model:show="showUserApiKeyModal" >
            <n-card style="width: 600px" size="huge" title="Your User API Key (shown only once)" :bordered="false"
                role="dialog" aria-modal="true">

                <p>Please record these data as you will see API key only once (although you can re-generate it at any time):
                </p>
                <div><strong>API ID: </strong><n-input type="textarea" disabled v-model:value="apiKeyId" />
                </div>
                <div><strong>API Key: </strong><n-input type="textarea" disabled v-model:value="apiKey" />
                </div>
                <div><strong>Basic Authentication Header: </strong><n-input type="textarea" disabled
                        v-model:value="apiKeyHeader" rows="5" />
                </div>
            </n-card>
        </n-modal>
    </div>
</template>
  
<script lang="ts" setup>
import { NSpace, NIcon, NCheckbox, NCheckboxGroup, NDropdown, NInput, NModal, NCard, NDataTable, NForm, NInputGroup, NButton, NFormItem, NSelect, NRadioGroup, NRadioButton, NTabs, NTabPane, NTooltip, NotificationType, useNotification, NFlex, NH5, NText, NGrid, NGi, DataTableColumns, NDynamicInput } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed, onMounted, reactive } from 'vue'
import type { SelectOption } from 'naive-ui'
import { useStore } from 'vuex'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Edit as EditIcon, Trash, LockOpen, CirclePlus, Eye } from '@vicons/tabler'
import { Info20Regular, Edit24Regular } from '@vicons/fluent'
import { Icon } from '@vicons/utils'
import commonFunctions, { SwalData } from '@/utils/commonFunctions'
import axios from '../utils/axios'
import Swal, { SweetAlertOptions } from 'sweetalert2'
import { Marked } from '@ts-stack/markdown'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import constants from '../utils/constants'
import CreateApprovalPolicy from './CreateApprovalPolicy.vue'
import CreateApprovalEntry from './CreateApprovalEntry.vue'
import { FetchPolicy } from '@apollo/client'
import {ApprovalEntry, ApprovalRole, ApprovalRequirement} from '@/utils/commonTypes'

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

onMounted(async () => {
    store.dispatch('fetchComponents', orgResolved.value)
    store.dispatch('fetchResourceGroups', orgResolved.value)
    if (false && myUser.value.installationType !== 'OSS') initializeResourceGroup()
    loadConfiguredIntegrations(true)
    loadCiIntegrations(true)
    isWritable.value = commonFunctions.isWritable(orgResolved.value, myUser.value, 'ORG')
    
    // Load data for the current tab (from URL or default) without router update
    const tabName = currentTab.value
    loadTabSpecificData(tabName)
})

async function loadTabSpecificData (tabName: string) {
    if (tabName === "users") {
        await loadUsers()
        loadInvitedUsers(true)
    } else if (tabName === "userGroups") {
        await loadUsers() // Load users for the user selection dropdown
        loadUserGroups()
    } else if (tabName === "programmaticAccess") {
        await loadUsers()
        loadProgrammaticAccessKeys(true)
    } else if (tabName === "approvalPolicies") {
        fetchApprovalEntries()
        fetchApprovalPolicies()
    } else if (tabName === "perspectives") {
        loadPerspectives()
    }
}

const showOrgApiKeyModal = ref(false)

const showOrgSettingsProgPermissionsModal = ref(false)

const showOrgSettingsUserPermissionsModal = ref(false)

const showUserGroupPermissionsModal = ref(false)

const showUserApiKeyModal = ref(false)

const showOrgRegistryTokenModal = ref(false)

const showOrgSettingsSlackIntegrationModal = ref(false)

const showOrgSettingsMsteamsIntegrationModal = ref(false)

const showOrgSettingsDependencyTrackIntegrationModal = ref(false)

const showOrgSettingsGitHubIntegrationModal = ref(false)

const showComponentRegistryModal = ref(false)

const showCreateResourceGroupModal = ref(false)

const showCreateApprovalPolicy = ref(false)
const showCreateApprovalEntry = ref(false)
const showCreateApprovalRole = ref(false)

const showCIIntegrationModal = ref(false)

const showCreatePerspectiveModal = ref(false)
const showEditPerspectiveModal = ref(false)

const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)

const orgResolved: Ref<string> = ref('')
const myorg: ComputedRef<any> = computed((): any => store.getters.orgById(orgResolved.value))
if (route.params.orguuid) {
    orgResolved.value = route.params.orguuid.toString()
} else {
    orgResolved.value = myorg.value.uuid
}
const isOrgAdmin: ComputedRef<boolean> = computed((): any => {
    let isOrgAdmin = false
    if (myUser.value && myUser.value.permissions) {
        let orgPermission = myUser.value.permissions.permissions.find((p: any) => (p.org === orgResolved.value && p.object === orgResolved.value && p.scope === 'ORGANIZATION'))
        if (orgPermission && orgPermission.type === 'ADMIN') {
            isOrgAdmin = true
        }
    }
    return isOrgAdmin
})

// Tab management with router integration
const defaultTab = isOrgAdmin.value ? 'integrations' : 'approvalPolicies'
const currentTab = ref(route.query.tab as string || defaultTab)

const approvalRoleFields: any[] = [
    {
        key: 'id',
        title: 'Role ID'
    },
    {
        key: 'displayView',
        title: 'Display Name'
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (isWritable) {
                const deleteEl = h(NIcon, {
                        title: 'Delete Approval Role',
                        class: 'icons clickable',
                        size: 20,
                        onClick: () => {
                            deleteApprovalRole(row.id)
                        }
                    }, 
                    { 
                        default: () => h(Trash) 
                    }
                )
                els.push(deleteEl)
            }
            if (!els.length) els = [h('div'), row.status]
            return els
        }
    }
]

const apiKey: Ref<string> = ref('')
const apiKeyId: Ref<string> = ref('')
const apiKeyHeader: Ref<string> = ref('')

const newApprovalRole: Ref<any> = ref({
    id: '',
    displayView: ''
})

// Terminology settings
const DEFAULT_FEATURE_SET_LABEL = 'Feature Set'
const terminologyForm = ref({
    featureSetLabel: myorg.value?.terminology?.featureSetLabel || DEFAULT_FEATURE_SET_LABEL
})
const savingTerminology = ref(false)

async function saveTerminology() {
    savingTerminology.value = true
    try {
        const response: any = await graphqlClient.mutate({
            mutation: gql`
                mutation updateOrganizationTerminology($orgUuid: ID!, $terminology: TerminologyInput!) {
                    updateOrganizationTerminology(orgUuid: $orgUuid, terminology: $terminology) {
                        uuid
                        name
                        terminology {
                            featureSetLabel
                        }
                    }
                }
            `,
            variables: {
                orgUuid: orgResolved.value,
                terminology: {
                    featureSetLabel: terminologyForm.value.featureSetLabel || DEFAULT_FEATURE_SET_LABEL
                }
            },
            fetchPolicy: 'no-cache'
        })
        if (response.data?.updateOrganizationTerminology) {
            store.commit('UPDATE_ORGANIZATION', response.data.updateOrganizationTerminology)
            notify('success', 'Success', 'Terminology settings saved successfully')
        }
    } catch (err: any) {
        console.error('Error saving terminology:', err)
        notify('error', 'Error', 'Failed to save terminology settings')
    } finally {
        savingTerminology.value = false
    }
}

function resetTerminology() {
    terminologyForm.value.featureSetLabel = DEFAULT_FEATURE_SET_LABEL
    saveTerminology()
}

const botToken: Ref<string> = ref('')
const environmentTypes: Ref<string[]> = ref([])
const globalRegistryEnabled: Ref<boolean> = ref(false)
const instancePermissions: Ref<any> = ref({})
const invitee = ref({
    org: orgResolved.value,
    email: '',
    type: ''
})

function resetInvitee () {
    invitee.value = {
        org: orgResolved.value,
        email: '',
        type: ''
    }
}
const myapp: Ref<any> = ref({})
const protectedEnvironments: Ref<any[]> = ref([])

const resourceGroups: ComputedRef<any> = computed((): any => store.getters.allResourceGroups)
const resourceGroupOptions: ComputedRef<any> = computed((): any => {
    const apps = store.getters.allResourceGroups.map((app: any) => {
        return {
            label: app.name,
            key: app.uuid
        }
    })
    apps.push({label: 'Create New', key: 'create_new'})
    return apps
})
function selectResourceGroup(key: string) {
    if (key === 'create_new') {
        showCreateResourceGroupModal.value = true
    } else {
        myapp.value = resourceGroups.value.find((app: any) => app.uuid === key)
        protectedEnvironments.value = myapp.value.protectedEnvironments
    }
}

const newappname: Ref<string> = ref('')
const permissionTypes: string[] = ['NONE', 'READ_ONLY', 'READ_WRITE']
const permissionTypeSelections: ComputedRef<any[]> = computed((): any => {

    if (permissionTypes.length) {
        let retSelection: any[] = []
        permissionTypes.forEach((el: string) => {
            const retObj = {
                label: translatePermissionName(el),
                value: el
            }
            retSelection.push(retObj)
        })
        return retSelection
    } else {
        return []
    }
})
const permissionTypeswAdmin: string[] = ['NONE', 'READ_ONLY', 'READ_WRITE', 'ADMIN']

const programmaticAccessFields: Ref<any> = ref([
    {
        key: 'uuid',
        title: 'Internal ID'
    },
    {
        key: 'apiId',
        title: 'API ID',
        render: (row: any) => {
            let keyId = row.type + "__" + row.object
            if (row.keyOrder) keyId += "__ord__" + row.keyOrder
            const els: any[] = [
                h(NTooltip, {
                        trigger: 'hover'
                    }, {trigger: () => h(NIcon,
                            {
                                // title: keyId,
                                class: 'icons',
                                size: 25,
                            }, { default: () => h(Info20Regular) }),
                            default: () =>  keyId
                        }
                )
            ]
            return h('div', els)
        }
    },
    {
        key: 'createdDate',
        title: 'Created'
    },
    {
        key: 'accessDate',
        title: 'Last Accessed'
    },
    {
        key: 'updatedByName',
        title: 'Updated By'
    },
    {
        key: 'object',
        title: 'Object',
        render: (row: any) => {
            let el = h('div')
            if (row.type === 'COMPONENT') {
                el = h(
                    RouterLink,
                    {
                        to: {
                            name: 'ComponentsOfOrg',
                            params: {
                                orguuid: row.org,
                                compuuid: row.object
                            }
                        }
                    },
                    { default: () => row.object_val }
                )
            }
            return el
        }
    },
    {
        key: 'type',
        title: 'Type'
    },
    {
        key: 'resolvedApprovals',
        title: 'Approvals',
        render: (row: any) => {
            let el = h('div')
            if(row.type === 'REGISTRY_USER'){
                el = row.registryRobotLogin.includes('-private') ? h('div', 'Private') : h('div', 'Public')
            }else{
                let approval = resolveApprovals(row.permissions)
                el = h('div', approval)
            }
            return el
        }
    },
    {
        key: 'notes',
        title: 'Notes'
    },
    {
        key: 'controls',
        title: 'Manage',
        render: (row: any) => {
            let el = h('div')
            let els: any[] = []
            if (isOrgAdmin.value) {
                if (row.type !== 'REGISTRY_USER' && myUser.value.installationType !== 'OSS') {
                    els.push(
                        h(
                            NIcon,
                            {
                                title: 'Set Approvals For Key',
                                class: 'icons clickable',
                                size: 25,
                                onClick: () => editKey(row.uuid)
                            }, { default: () => h(EditIcon) }
                        )
                    )
                }                    
                els.push(h(
                    NIcon,
                    {
                        title: 'Delete Key',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => deleteKey(row.uuid)
                    }, { default: () => h(Trash) }
                ))
            }
        
            el = h('div', els)
        
            return el

        }
    }
])
const programmaticAccessKeys: Ref<any[]> = ref([])
const registryHost: Ref<string> = ref('')
const robotName: Ref<string> = ref('')
const selectedKey: Ref<any> = ref({})
const selectedUser: Ref<any> = ref({})
const selectedUserType: Ref<string> = ref('')
const configuredIntegrations: Ref<any[]> = ref([])
const ciIntegrations: Ref<any[]> = ref([])
const createIntegrationObject: Ref<any> = ref({
    org: orgResolved.value,
    uri: '',
    frontendUri: '',
    identifier: 'base',
    secret: '',
    type: '',
    note: '',
    tenant: '',
    client: '',
    schedule: ''
})

function resetCreateIntegrationObject() {
    createIntegrationObject.value = {
        org: orgResolved.value,
        uri: '',
        frontendUri: '',
        identifier: 'base',
        secret: '',
        type: '',
        note: '',
        tenant: '',
        client: '',
        schedule: ''
    }
}


const users: Ref<any[]> = ref([])
async function loadUsers() {
    users.value = await store.dispatch('fetchUsers', orgResolved.value)
    userEmailColumnReactive.sortOrder = 'ascend'
}

// User Groups
const userGroups: Ref<any[]> = ref([])
const selectedUserGroup: Ref<any> = ref({})
const newUserGroup: Ref<any> = ref({
    name: '',
    description: '',
    org: orgResolved.value
})

function resetNewUserGroup() {
    newUserGroup.value = {
        name: '',
        description: '',
        org: orgResolved.value
    }
}

async function loadUserGroups() {
    try {
        const response = await graphqlClient.query({
            query: gql`
                query getUserGroups($org: ID!) {
                    getUserGroups(org: $org) {
                        uuid
                        name
                        description
                        status
                        users
                        userDetails {
                            uuid
                            name
                            email
                        }
                        permissions {
                            permissions {
                                org
                                scope
                                object
                                type
                                meta
                                approvals
                            }
                        }
                        connectedSsoGroups
                        createdDate
                        lastUpdatedBy
                    }
                }`,
            variables: {
                org: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        userGroups.value = response.data.getUserGroups || []
    } catch (error: any) {
        console.error('Error loading user groups:', error)
        notify('error', 'Error', 'Failed to load user groups')
    }
}

// Perspectives
const perspectives: Ref<any[]> = ref([])
const newPerspective: Ref<any> = ref({
    name: ''
})
const showPerspectiveComponentsModal = ref(false)
const selectedPerspectiveUuid: Ref<string> = ref('')
const selectedPerspectiveName: Ref<string> = ref('')
const perspectiveComponents: Ref<any[]> = ref([])
const editingPerspective: Ref<any> = ref({
    uuid: '',
    name: ''
})

const perspectiveComponentColumns = [
    {
        key: 'name',
        title: 'Name',
        render(row: any) {
            return h(
                RouterLink,
                {
                    to: { name: 'ComponentsOfOrg', params: { orguuid: row.org, compuuid: row.uuid } }
                },
                () => row.name
            )
        }
    },
    {
        key: 'type',
        title: 'Type',
        render(row: any) {
            return h('div', row.type === 'COMPONENT' ? 'Component' : 'Product')
        }
    }
]

const perspectiveFields = [
    {
        key: 'name',
        title: 'Perspective Name'
    },
    {
        key: 'createdDate',
        title: 'Created Date',
        render(row: any) {
            return h('div', row.createdDate ? new Date(row.createdDate).toLocaleString() : '')
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render(row: any) {
            const actions = [
                h(
                    NIcon,
                    {
                        title: 'View Connected Components',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => showPerspectiveComponentsModalFn(row.uuid, row.name)
                    },
                    () => h(Eye)
                )
            ]
            
            // Add edit icon only for admin users
            if (isOrgAdmin.value) {
                actions.push(
                    h(
                        NIcon,
                        {
                            title: 'Edit Perspective',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => editPerspective(row)
                        },
                        () => h(EditIcon)
                    )
                )
            }
            
            return h('div', actions)
        }
    }
]

async function loadPerspectives() {
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
                org: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        perspectives.value = response.data.perspectives || []
    } catch (error: any) {
        console.error('Error loading perspectives:', error)
        notify('error', 'Error', 'Failed to load perspectives')
    }
}

async function createPerspective() {
    if (!newPerspective.value.name || !newPerspective.value.name.trim()) {
        notify('error', 'Error', 'Perspective name is required')
        return
    }
    
    processingMode.value = true
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation createPerspective($org: ID!, $name: String!) {
                    createPerspective(org: $org, name: $name) {
                        uuid
                        name
                        org
                        createdDate
                    }
                }`,
            variables: {
                org: orgResolved.value,
                name: newPerspective.value.name.trim()
            }
        })
        
        if (response.data && response.data.createPerspective) {
            perspectives.value.push(response.data.createPerspective)
            notify('success', 'Success', 'Perspective created successfully')
            resetCreatePerspective()
        }
    } catch (error: any) {
        console.error('Error creating perspective:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.toString()))
    } finally {
        processingMode.value = false
    }
}

function resetCreatePerspective() {
    newPerspective.value = {
        name: ''
    }
    showCreatePerspectiveModal.value = false
}

async function showPerspectiveComponentsModalFn(perspectiveUuid: string, perspectiveName: string) {
    selectedPerspectiveUuid.value = perspectiveUuid
    selectedPerspectiveName.value = perspectiveName
    showPerspectiveComponentsModal.value = true
    
    try {
        const response = await graphqlClient.query({
            query: gql`
                query componentsOfPerspective($perspectiveUuid: ID!) {
                    componentsOfPerspective(perspectiveUuid: $perspectiveUuid) {
                        uuid
                        name
                        org
                        type
                    }
                }`,
            variables: {
                perspectiveUuid: perspectiveUuid
            },
            fetchPolicy: 'no-cache'
        })
        perspectiveComponents.value = response.data.componentsOfPerspective || []
    } catch (error: any) {
        console.error('Error loading perspective components:', error)
        notify('error', 'Error', 'Failed to load components for this perspective')
        perspectiveComponents.value = []
    }
}

function editPerspective(perspective: any) {
    editingPerspective.value = {
        uuid: perspective.uuid,
        name: perspective.name
    }
    showEditPerspectiveModal.value = true
}

async function updatePerspectiveName() {
    if (!editingPerspective.value.name || !editingPerspective.value.name.trim()) {
        notify('error', 'Error', 'Perspective name is required')
        return
    }
    
    processingMode.value = true
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation updatePerspective($uuid: ID!, $name: String!) {
                    updatePerspective(uuid: $uuid, name: $name) {
                        uuid
                        name
                        org
                        createdDate
                    }
                }`,
            variables: {
                uuid: editingPerspective.value.uuid,
                name: editingPerspective.value.name.trim()
            }
        })
        
        if (response.data && response.data.updatePerspective) {
            // Update the perspective in the list
            const index = perspectives.value.findIndex(p => p.uuid === editingPerspective.value.uuid)
            if (index !== -1) {
                perspectives.value[index] = response.data.updatePerspective
            }
            notify('success', 'Success', 'Perspective updated successfully')
            cancelEditPerspective()
        }
    } catch (error: any) {
        console.error('Error updating perspective:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.toString()))
    } finally {
        processingMode.value = false
    }
}

function cancelEditPerspective() {
    editingPerspective.value = {
        uuid: '',
        name: ''
    }
    showEditPerspectiveModal.value = false
}


const userEmailColumn = 
    {
        key: 'email',
        title: 'Email',
        sortOrder: ''
    }
const userEmailColumnReactive = reactive(userEmailColumn)

const userFields = [
    userEmailColumn,
    {
        key: 'name',
        title: 'Name'
    },
    {
        key: 'permission',
        title: 'Org Wide Permissions',
        render(row: any) {
            return h('div',
                extractOrgWidePermission(row)
            )
        }
    },
    {
        key: 'approvals',
        title: 'Approvals',
        render(row: any) {
            let approvalContent = ''
            const permArray = row.permissions.permissions.filter((up: any) =>
                (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value)
            )
            if (permArray && permArray.length) {
                const orgWidePerm = permArray[0]
                if (orgWidePerm.type === 'ADMIN') {
                    approvalContent = 'Any (as Admin)'
                } else if (orgWidePerm.approvals && orgWidePerm.approvals.length) {
                    approvalContent = orgWidePerm.approvals.toString()
                } else {
                    approvalContent = 'Not Granted'
                }
            }
            return h('div', approvalContent)
        }
    },
    {
        key: 'controls',
        title: 'Manage',
        render(row: any) {
            let el = h('div')
            let els: any[] = []
            if (isOrgAdmin.value) {
                if (row.uuid !== myUser.value.uuid) {
                    els = [
                        h(
                            NIcon,
                            {
                                title: 'Modify user permissions',
                                class: 'icons clickable',
                                size: 25,
                                onClick: () => editUser(row.email)
                            }, { default: () => h(EditIcon) }
                        ),
                        h(
                            NIcon,
                            {
                                title: 'Remove User From Organization',
                                class: 'icons clickable',
                                size: 25,
                                onClick: () => removeUser(row.uuid)
                            }, { default: () => h(Trash) }
                        )
                    ]
                }
            }
            if(row.uuid === myUser.value.uuid){
                els.push(
                    h(
                        NIcon,
                        {
                            title: 'Generate User API Key',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => genUserApiKey()
                        }, { default: () => h(LockOpen) }
                    )
                )
            }
            el = h('div', els)
        
            return el
        }
    }
]
const invitees: Ref<any[]> = ref([])

const inviteeFields = [
    {
        key: 'email',
        title: 'Email'
    },
    {
        key: 'type',
        title: 'Org Wide Permissions'
    },
    {
        key: 'challengeExpiry',
        title: 'Invitation Expiration'
    },
    {
        key: 'controls',
        title: 'Manage',
        render(row: any) {
            return h('div', [
                h(
                    NIcon,
                    {
                        title: 'Cancel Invitation',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => cancelInvite(row.email)
                    }, { default: () => h(Trash) }
                )
            ])
        }
    }
]

// User Group Fields
const userGroupFields = [
    {
        key: 'name',
        title: 'Group Name',
        render(row: any) {
            if (row.description && row.description.trim()) {
                return h('div', [
                    h('span', row.name),
                    h(
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
                                        size: 20,
                                        style: 'margin-left: 8px;'
                                    }, { default: () => h(Info20Regular) }
                                )
                            },
                            default: () => row.description
                        }
                    )
                ])
            } else {
                return h('div', row.name)
            }
        }
    },
    {
        key: 'userCount',
        title: 'Users',
        render(row: any) {
            const userCount = row.userDetails ? row.userDetails.length : 0
            if (userCount > 0 && row.userDetails) {
                const userList = row.userDetails.map((u: any) => `${u.name} (${u.email})`).join('\n')
                return h('div', [
                    h('span', `${userCount} ${userCount === 1 ? 'user' : 'users'}`),
                    h(
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
                                        size: 20,
                                        style: 'margin-left: 8px;'
                                    }, { default: () => h(Info20Regular) }
                                )
                            },
                            default: () => h('div', { style: 'white-space: pre-line;' }, userList)
                        }
                    )
                ])
            } else {
                return h('div', `${userCount} users`)
            }
        }
    },
    {
        key: 'permission',
        title: 'Org Wide Permissions',
        render(row: any) {
            if (row.permissions && row.permissions.permissions && row.permissions.permissions.length) {
                const orgPermissions = row.permissions.permissions.filter((p: any) => 
                    p.scope === 'ORGANIZATION' && p.org === orgResolved.value && p.object === orgResolved.value
                )
                if (orgPermissions.length > 0) {
                    return h('div', translatePermissionName(orgPermissions[0].type))
                }
            }
            return h('div', 'None')
        }
    },
    {
        key: 'approvals',
        title: 'Approvals',
        render(row: any) {
            let approvalContent = ''
            if (row.permissions && row.permissions.permissions && row.permissions.permissions.length) {
                const orgPermissions = row.permissions.permissions.filter((p: any) => 
                    p.scope === 'ORGANIZATION' && p.org === orgResolved.value && p.object === orgResolved.value
                )
                if (orgPermissions.length > 0) {
                    const orgPerm = orgPermissions[0]
                    if (orgPerm.type === 'ADMIN') {
                        approvalContent = 'Any (as Admin)'
                    } else if (orgPerm.approvals && orgPerm.approvals.length) {
                        approvalContent = orgPerm.approvals.toString()
                    } else {
                        approvalContent = 'Not Granted'
                    }
                } else {
                    approvalContent = 'Not Granted'
                }
            } else {
                approvalContent = 'Not Granted'
            }
            return h('div', approvalContent)
        }
    },
    {
        key: 'connectedSsoGroups',
        title: 'SSO Groups',
        render(row: any) {
            const ssoGroups = row.connectedSsoGroups || []
            return h('div', ssoGroups.length > 0 ? ssoGroups.join(', ') : 'None')
        }
    },
    {
        key: 'controls',
        title: 'Manage',
        render(row: any) {
            let els: any[] = []
            if (isOrgAdmin.value) {
                els = [
                    h(
                        NIcon,
                        {
                            title: 'Edit User Group',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => editUserGroup(row.uuid)
                        }, { default: () => h(EditIcon) }
                    ),
                    h(
                        NIcon,
                        {
                            title: 'Delete User Group',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => deleteUserGroup(row.uuid)
                        }, { default: () => h(Trash) }
                    )
                ]
            }
            return h('div', els)
        }
    }
]

const ciIntegrationTableFields = [
    {
        key: 'note',
        title: 'Description'
    },
    {
        key: 'type',
        title: 'Type'
    }
]

const props = defineProps<{
    orguuid?: string
}>()

const processingMode = ref(false)

// User Group Options
const userGroupStatusOptions = [
    { label: 'Active', value: 'ACTIVE' },
    { label: 'Inactive', value: 'INACTIVE' }
]

const userOptions: ComputedRef<any[]> = computed((): any => {
    return users.value.map((u: any) => ({
        label: `${u.name} (${u.email})`,
        value: u.uuid
    }))
})

function onCreateSsoGroup() {
    return '' // Return empty string for new SSO group entry
}

async function addApprovalRole () {
    if (newApprovalRole.value.id) {
        const updObj = {
            orgUuid: orgResolved.value,
            approvalRole: newApprovalRole.value
        }
        await store.dispatch('addApprovalRole', updObj)
        showCreateApprovalRole.value = false
        resetCreateApprovalRole()
    }
}

function resetCreateApprovalRole () {
    newApprovalRole.value = {
        id: '',
        displayView: ''
    }
}

async function deleteApprovalRole (approvalRoleId: string) {
    const updObj = {
        orgUuid: orgResolved.value,
        approvalRoleId       
    }
    try {
        const org = await store.dispatch('deleteApprovalRole', updObj)
        if (org && org.uuid) {
            notify('success', 'Deleted', 'Successfully deleted approval role ' + approvalRoleId)
        } else {
            notify('error', 'Failed to Delete', 'There was an error deleting approval role')
        }
    } catch (err: any) {
        notify('error', 'Failed to Delete', commonFunctions.parseGraphQLError(err.message))
    }
}

async function deleteApprovalPolicy (policyUuid: string) {
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation archiveApprovalPolicy($approvalPolicyUuid: ID!) {
                    archiveApprovalPolicy(approvalPolicyUuid: $approvalPolicyUuid) {
                        uuid
                        status
                        policyName
                    }
                }`,
            variables: {
                approvalPolicyUuid: policyUuid
            },
            fetchPolicy: 'no-cache'
        })
        if (response.data && response.data.archiveApprovalPolicy && response.data.archiveApprovalPolicy.status === 'ARCHIVED') {
            notify('success', 'Archived', 'Successfully archived approval policy ' + response.data.archiveApprovalPolicy.policyName)
            fetchApprovalPolicies()
        } else {
            notify('error', 'Failed to Archive', 'There was an error archiving approval policy')
        }
    } catch (err: any) {
        notify('error', 'Failed to Archive', commonFunctions.parseGraphQLError(err.message))
    }
}

async function deleteApprovalEntry (approvalEntryUuid: string) {
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation archiveApprovalEntry($approvalEntryUuid: ID!) {
                    archiveApprovalEntry(approvalEntryUuid: $approvalEntryUuid) {
                        uuid
                        status
                        approvalName
                    }
                }`,
            variables: {
                approvalEntryUuid
            },
            fetchPolicy: 'no-cache'
        })
        if (response.data && response.data.archiveApprovalEntry && response.data.archiveApprovalEntry.status === 'ARCHIVED') {
            notify('success', 'Archived', 'Successfully archived approval entry ' + response.data.archiveApprovalEntry.approvalName)
            fetchApprovalEntries()
        } else {
            notify('error', 'Failed to Archive', 'There was an error archiving approval entry')
        }
    } catch (err: any) {
        notify('error', 'Failed to Archive', commonFunctions.parseGraphQLError(err.message))
    }
}

async function createApp() {
    if (newappname.value) {
        let appObj = {
            name: newappname.value,
            org: orgResolved.value
        }
        await store.dispatch('createResourceGroup', appObj)
        newappname.value = ''
        showCreateResourceGroupModal.value = false
        notify('success', 'Created', 'Successfully created resourceGroup ' + appObj.name)
    }
}

async function deleteIntegration(type: string) {
    await graphqlClient.mutate({
        mutation: gql`
                mutation deleteBaseIntegration($org: ID!, $type: IntegrationType!) {
                    deleteBaseIntegration(org: $org, type: $type)
                }`,
        variables: {
            'org': orgResolved.value,
            type
        }
    })
    loadConfiguredIntegrations(false)
}

async function deleteKey(uuid: string) {
    const onSwalConfirm = async function () {
        let isSuccess = false
        try {
            const resp = await graphqlClient.mutate({
                mutation: gql`
                        mutation deleteApiKey($apiKeyUuid: ID!) {
                            deleteApiKey(apiKeyUuid: $apiKeyUuid)
                        }`,
                variables: {
                    'apiKeyUuid': uuid
                }
            })
            if (resp.data && resp.data.deleteApiKey) isSuccess = true
        } catch (error: any) {
            console.error(error)
        }
        if (!isSuccess) {
            Swal.fire(
                'Error!',
                'Error when deleting api key.',
                'error'
            )
        }
        loadProgrammaticAccessKeys(false)
    }
    const swalData: SwalData = {
        questionText: `Are you sure you want to delete the API Key with Internal ID ${uuid}?`,
        successTitle: 'Deleted!',
        successText: `The API Key with Internal ID ${uuid} has been deleted.`,
        dismissText: 'The API Key remains active.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData)
}
function editKey(uuid: string) {
    const key = programmaticAccessKeys.value.filter((k: any) => (k.uuid === uuid))
    selectedKey.value = commonFunctions.deepCopy(key[0])
    // locate permission for approvals
    const perm = selectedKey.value.permissions.permissions.filter((up: any) =>
        (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value)
    )
    if (perm && perm.length) {
        selectedKey.value.approvals = perm[0].approvals
    } else {
        selectedKey.value.approvals = []
    }
    showOrgSettingsProgPermissionsModal.value = true
}
function editUser(email: string) {
    const user = users.value.filter(u => (u.email === email))
    selectedUser.value = commonFunctions.deepCopy(user[0])
    // locate permission for approvals and instance permissions
    let perm: any
    instancePermissions.value = {}
    selectedUser.value.permissions.permissions.forEach((up: any) => {
        if (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value) {
            perm = up
        } else if (up.scope === 'INSTANCE' && up.org === orgResolved.value) {
            instancePermissions.value[up.object] = up.type
        }
    })

    selectedUser.value.permissions.permissions.filter((up: any) =>
        (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value)
    )
    selectedUser.value.approvals = commonFunctions.deepCopy(perm.approvals)
    selectedUser.value.type = perm.type
    selectedUserType.value = perm.type

    showOrgSettingsUserPermissionsModal.value = true
}
function enableRegistry() {
    if (!orgRegistry.value) {
        axios.post('/api/manual/v1/organization/registry/' + orgResolved.value).then(resp => {
            store.dispatch('fetchMyOrganizations')
            showRegistryCommands()
        })
    }
}
function translatePermissionName(type: string) {
    switch (type) {
        case 'NONE': return 'None'
        case 'READ_ONLY': return 'Read Only'
        case 'READ_WRITE': return 'Read & Write'
        case 'ADMIN': return 'Administrator'
        default: return type
    }
}
function extractOrgWidePermission(user: any) {
    const perm = user.permissions.permissions.filter((up: any) =>
        (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value)
    )
    return translatePermissionName(perm[0].type)
}
async function genApiKey() {
    let swalObject: SweetAlertOptions = {
        title: 'Pick Key Type',
        text: 'Choose Type For Your New Api Key',
        icon: 'warning',
        html:
            '<textarea id="swal-input-notes" placeholder="Notes" class="swal2-input">' ,
        input: 'select',
        inputOptions: ['Org-wide Read Only', 'Org-wide Read-Write', 'Approval and Artifact Upload'],
        inputPlaceholder: 'Select Key Type',
        preConfirm: (value: any) => {
            if(!value){
                Swal.showValidationMessage('You need to select key type!')
            }
        },
        showCancelButton: true,
        confirmButtonText: 'Generate it!',
        cancelButtonText: 'Cancel'
    }
    if (orgRegistry.value && globalRegistryEnabled.value) {
        swalObject.inputOptions = ['Org-wide Read Only', 'Org-wide Read-Write', 'Approval and Artifact Upload', 'Private Registry', 'Public Registry']
    }
    const swalResult = await Swal.fire(swalObject)
    if (swalResult.dismiss === Swal.DismissReason.cancel) {
        Swal.fire(
            'Cancelled',
            'Aborted API Key Generation',
            'error'
        )
    } else {
        const setKeyPayload = {
            orgUuid: orgResolved.value,
            keyOrder: '',
            apiType: '',
            notes: (<HTMLInputElement>document.getElementById('swal-input-notes'))!.value
        }
        if (swalResult.value === '0') {
            setKeyPayload.keyOrder = commonFunctions.genUuid()
            setKeyPayload.apiType = 'ORGANIZATION'
        } else if (swalResult.value === '1') {
            setKeyPayload.keyOrder = commonFunctions.genUuid()
            setKeyPayload.apiType = 'ORGANIZATION_RW'
        } else if (swalResult.value === '2') {
            setKeyPayload.apiType = 'APPROVAL'
        } else if (swalResult.value === '3') {
            genUserRegistryToken('PRIVATE', setKeyPayload.notes)
            return
        } else if (swalResult.value === '4') {
            genUserRegistryToken('PUBLIC', setKeyPayload.notes)
            return
        }
        const keyResp = await graphqlClient.mutate({
            mutation: gql`
                mutation setOrgApiKey($orgUuid: ID!, $apiType: ApiTypeEnum!, $keyOrder: String, $notes: String) {
                    setOrgApiKey(orgUuid: $orgUuid, apiType: $apiType, keyOrder: $keyOrder, notes: $notes) {
                        id
                        apiKey
                        authorizationHeader
                    }
                }`,
            variables: setKeyPayload,
            fetchPolicy: 'no-cache'
        })
        const newKeyMessage = commonFunctions.getGeneratedApiKeyHTML(keyResp.data.setOrgApiKey)
        loadProgrammaticAccessKeys(false)
        Swal.fire({
            title: 'Generated!',
            customClass: {popup: 'swal-wide'},
            html: newKeyMessage,
            icon: 'success'
        })
    }
    
}
async function genUserApiKey() {
    let swalObject: SweetAlertOptions = {
        title: 'Are you sure?',
        text: 'A new API Key will be generated, any existing integrations with previous API Key (if exist) will stop working.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, generate it!',
        cancelButtonText: 'No, cancel it'
    }
    const swalResult = await Swal.fire(swalObject)
    if (swalResult.value) {
        const keyResp = await graphqlClient.mutate({
            mutation: gql`
                mutation setUserOrgApiKey($orgUuid: ID!) {
                    setUserOrgApiKey(orgUuid: $orgUuid) {
                        id
                        apiKey
                        authorizationHeader
                    }
                }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        const newKeyMessage = commonFunctions.getGeneratedApiKeyHTML(keyResp.data.setUserOrgApiKey)      
        Swal.fire({
            title: 'Generated!',
            customClass: {popup: 'swal-wide'},
            html: newKeyMessage,
            icon: 'success'
        })
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        Swal.fire(
            'Cancelled',
            'Your existing API Key is safe',
            'error'
        )
    }
}

function getGeneratedRegistryTokenHTML(responseData: any) {
    return `
            <div style="text-align: left;">
            <p>Please record these data as you will see the Organization Registry Token only once (although you can re-generate it at any time):</p>
                <table style="width: 95%;">
                    <tr>
                        <td>
                            <strong>Username:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${responseData.name}</textarea>
                        </td>
                    </tr>
                    <tr>
                        <td>
                            <strong>Token:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${responseData.secret}</textarea>
                        </td>
                    </tr>
                </table>
            </div>
        `
}

async function genUserRegistryToken(type: string, notes: string) {

    let gqlResponse = await graphqlClient.mutate({
        mutation: gql`
                mutation setRegistryKey {
                    setRegistryKey(orgUuid: "${orgResolved.value}",
                        type: "${type}",
                        notes: "${notes}"
                    ) {
                        secret
                        id
                        name
                        disabled
                    }
                }`
    })
    loadProgrammaticAccessKeys(false)
    let newKeyMessage = getGeneratedRegistryTokenHTML(gqlResponse.data.setRegistryKey)      
    Swal.fire({
        title: 'Organization Registry Token',
        customClass: {popup: 'swal-wide'},
        html: newKeyMessage,
        icon: 'success'
    })
}

async function inviteUser() {
    processingMode.value = true
    let isError = false
    try {
        const resp = await graphqlClient.mutate({
                mutation: gql`
                            mutation inviteUser($invitationProperties: InviteUserInput!) {
                                inviteUser(invitationProperties: $invitationProperties) {
                                    uuid
                                }
                            }`,
                variables: {
                    invitationProperties: invitee.value
                }
            })
        if (resp.data.inviteUser && resp.data.inviteUser.uuid) {
            notify('success', 'Invited', 'Successfully invited ' + invitee.value.email)
            resetInvitee()
            loadInvitedUsers(false)
        } else {
            isError = true
        }
    } catch (error: any) {
        console.error(error)
        isError = true
    }
    processingMode.value = false
    if (isError) {
        notify('error', 'Failed to Invite', 'There was an error inviting ' + invitee.value.email)
    }
    //    store.dispatch('fetchMyOrganizations')
}

// User Group Functions
async function createUserGroup() {
    if (!newUserGroup.value.name.trim()) {
        notify('error', 'Validation Error', 'User group name is required')
        return
    }
    
    processingMode.value = true
    try {
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation createUserGroup($userGroup: CreateUserGroupInput!) {
                    createUserGroup(userGroup: $userGroup) {
                        uuid
                        name
                        description
                        status
                    }
                }`,
            variables: {
                userGroup: newUserGroup.value
            }
        })
        
        if (response.data && response.data.createUserGroup) {
            notify('success', 'Created', `Successfully created user group "${newUserGroup.value.name}"`)
            resetNewUserGroup()
            loadUserGroups()
        }
    } catch (error: any) {
        console.error('Error creating user group:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.message))
    }
    processingMode.value = false
}

async function updateUserGroup() {
    if (!selectedUserGroup.value.uuid) return
    
    try {
        const permissions = []
        
        // Add organization-wide permission with approvals
        if (selectedUserGroup.value.orgPermissionType && selectedUserGroup.value.orgPermissionType !== 'NONE') {
            permissions.push({
                scope: 'ORGANIZATION',
                objectId: orgResolved.value,
                type: selectedUserGroup.value.orgPermissionType,
                approvals: []
            })
        }
        
        const updateInput = {
            groupId: selectedUserGroup.value.uuid,
            name: selectedUserGroup.value.name,
            description: selectedUserGroup.value.description,
            users: selectedUserGroup.value.users || [],
            status: selectedUserGroup.value.status,
            connectedSsoGroups: selectedUserGroup.value.connectedSsoGroups || [],
            permissions,
            approvals: selectedUserGroup.value.orgPermissionType !== 'ADMIN' ? 
                (selectedUserGroup.value.approvals || []) : []
        }
        
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation updateUserGroup($userGroup: UpdateUserGroupInput!) {
                    updateUserGroup(userGroup: $userGroup) {
                        uuid
                        name
                        description
                        status
                        users
                        connectedSsoGroups
                    }
                }`,
            variables: {
                userGroup: updateInput
            }
        })
        
        if (response.data && response.data.updateUserGroup) {
            notify('success', 'Updated', `Successfully updated user group "${selectedUserGroup.value.name}"`)
            showUserGroupPermissionsModal.value = false
            loadUserGroups()
        }
    } catch (error: any) {
        console.error('Error updating user group:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.message))
    }
}

function editUserGroup(groupUuid: string) {
    const group = userGroups.value.find(g => g.uuid === groupUuid)
    if (group) {
        selectedUserGroup.value = commonFunctions.deepCopy(group)
        
        // Extract organization-wide permission type and approvals from permissions (handle nested structure)
        if (group.permissions && group.permissions.permissions && group.permissions.permissions.length) {
            const orgPermissions = group.permissions.permissions.filter((p: any) => 
                p.scope === 'ORGANIZATION' && p.org === orgResolved.value && p.object === orgResolved.value
            )
            
            if (orgPermissions.length > 0) {
                const orgPerm = orgPermissions[0]
                selectedUserGroup.value.orgPermissionType = orgPerm.type || 'NONE'
                selectedUserGroup.value.approvals = orgPerm.approvals || []
            } else {
                selectedUserGroup.value.orgPermissionType = 'NONE'
                selectedUserGroup.value.approvals = []
            }
        } else {
            selectedUserGroup.value.orgPermissionType = 'NONE'
            selectedUserGroup.value.approvals = []
        }
        
        // Ensure arrays are initialized
        selectedUserGroup.value.users = selectedUserGroup.value.users || []
        selectedUserGroup.value.connectedSsoGroups = selectedUserGroup.value.connectedSsoGroups || []
        showUserGroupPermissionsModal.value = true
    }
}

async function deleteUserGroup(groupUuid: string) {
    const group = userGroups.value.find(g => g.uuid === groupUuid)
    if (!group) return
    
    const onSwalConfirm = async function () {
        try {
            // Note: The GraphQL schema doesn't show a delete mutation, so we'll update status to INACTIVE
            const response = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateUserGroup($userGroup: UpdateUserGroupInput!) {
                        updateUserGroup(userGroup: $userGroup) {
                            uuid
                            name
                            status
                        }
                    }`,
                variables: {
                    userGroup: {
                        groupId: groupUuid,
                        status: 'INACTIVE'
                    }
                }
            })
            
            if (response.data && response.data.updateUserGroup) {
                notify('success', 'Deactivated', `Successfully deactivated user group "${group.name}"`)
                loadUserGroups()
            }
        } catch (error: any) {
            console.error('Error deactivating user group:', error)
            notify('error', 'Error', commonFunctions.parseGraphQLError(error.message))
        }
    }
    
    const swalData: SwalData = {
        questionText: `Are you sure you want to deactivate the user group "${group.name}"?`,
        successTitle: 'Deactivated!',
        successText: `The user group "${group.name}" has been deactivated.`,
        dismissText: 'The user group remains active.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData)
}

function initializeResourceGroup() {
    if (!myapp.value.uuid) {
        myapp.value = resourceGroups.value.find((app: any) => app.uuid === '00000000-0000-0000-0000-000000000000')
        protectedEnvironments.value = myapp.value.protectedEnvironments
    }
}

async function loadConfiguredIntegrations(useCache: boolean) {
    let cachePolicy: FetchPolicy = "network-only"
    if (useCache) cachePolicy = "cache-first"
    try {
        const resp = await graphqlClient.query({
            query: gql`
                          query configuredBaseIntegrations($org: ID!) {
                              configuredBaseIntegrations(org: $org)
                          }`,
            variables: {
                org: orgResolved.value
            },
            fetchPolicy: cachePolicy
        })
        if (resp.data && resp.data.configuredBaseIntegrations) {
            configuredIntegrations.value = resp.data.configuredBaseIntegrations
        }
    } catch (err) { 
        console.error(err)
    }
}

async function loadCiIntegrations(useCache: boolean) {
    if (myUser.value && myUser.value.installationType !== 'OSS') {
        let cachePolicy: FetchPolicy = "network-only"
        if (useCache) cachePolicy = "cache-first"
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
                org: orgResolved.value
            },
            fetchPolicy: cachePolicy
            })
            if (resp.data && resp.data.ciIntegrations) {
                ciIntegrations.value = resp.data.ciIntegrations
            }
        } catch (err) { 
            console.error(err)
        }
    }
}

async function loadProgrammaticAccessKeys(useCache: boolean) {
    let cachePolicy: FetchPolicy = "network-only"
    if (useCache) cachePolicy = "cache-first"
    const resp = await graphqlClient.query({
            query: gql`
                          query apiKeys($orgUuid: ID!) {
                                 apiKeys(orgUuid: $orgUuid) {
                                    uuid
                                    object
                                    type
                                    keyOrder
                                    lastUpdatedBy
                                    accessDate
                                    createdDate
                                    notes
                                    permissions {
                                        permissions {
                                            org
                                            scope
                                            object
                                            type
                                            meta
                                            approvals
                                        }
                                    }
                                }
                            }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: cachePolicy
        })
    if (users.value.length && resp.data.apiKeys.length) {
        programmaticAccessKeys.value = resp.data.apiKeys.map((key: any) => formatValuesForApiKeys(key))
    } else if (resp.data.apiKeys.length) {
        programmaticAccessKeys.value = resp.data.apiKeys
    }
}

async function handleTabSwitch(tabName: string) {
    // Update current tab
    currentTab.value = tabName
    
    // Update router query parameter
    await router.push({
        query: { ...route.query, tab: tabName }
    })
    
    loadTabSpecificData(tabName)
}

async function loadInvitedUsers(useCache: boolean) {
    let cachePolicy: FetchPolicy = "network-only"
    if (useCache) cachePolicy = "cache-first"
    const resp = await graphqlClient.query({
            query: gql`
                          query adminOrganization($org: ID!) {
                                 adminOrganization(org: $org) {
                                    uuid
                                    invitees {
                                        email
                                        type
                                        challengeExpiry
                                    }
                                }
                            }`,
            variables: {
                org: orgResolved.value
            },
            fetchPolicy: cachePolicy
        })
    invitees.value = resp.data.adminOrganization.invitees
}

function formatValuesForApiKeys (apiKeyEntry: any) {
    const updEntry = Object.assign({}, apiKeyEntry)
    updEntry['createdDate'] = (new Date(apiKeyEntry['createdDate'])).toLocaleString('en-CA')
    updEntry['accessDate'] = apiKeyEntry['accessDate']? (new Date(apiKeyEntry['accessDate'])).toLocaleString('en-CA') : 'Never'
    if (apiKeyEntry['lastUpdatedBy'] && users.value.find((user) => user.uuid === apiKeyEntry['lastUpdatedBy'])) {
        updEntry['updatedByName'] = users.value.find((user) => user.uuid === apiKeyEntry['lastUpdatedBy'])['name']
    } else {
        updEntry['updatedByName'] = ''
    }
    return updEntry
}

async function onAddIntegration(type: string) {
    createIntegrationObject.value.type = type
    const resp = await graphqlClient.mutate({
        mutation: gql`
                      mutation createIntegration($integration: IntegrationInput!) {
                          createIntegration(integration: $integration) {
                              uuid
                          }
                      }`,
        variables: {
            'integration': createIntegrationObject.value
        }
    })
    
    if (resp.data.createIntegration && resp.data.createIntegration.uuid) await loadConfiguredIntegrations(false)

    resetCreateIntegrationObject()

    showOrgSettingsSlackIntegrationModal.value = false
    showOrgSettingsGitHubIntegrationModal.value = false
    showOrgSettingsMsteamsIntegrationModal.value = false
    showOrgSettingsDependencyTrackIntegrationModal.value = false
}

async function addCiIntegration() {
    const resp = await graphqlClient.mutate({
        mutation: gql`
                      mutation createTriggerIntegration($integration: IntegrationInput!) {
                          createTriggerIntegration(integration: $integration) {
                              uuid
                          }
                      }`,
        variables: {
            'integration': createIntegrationObject.value
        }
    })
    
    if (resp.data.createTriggerIntegration && resp.data.createTriggerIntegration.uuid) await loadCiIntegrations(false)

    resetCreateIntegrationObject()

    showCIIntegrationModal.value = false
}

async function removeUser(userUuid: string) {
    let userDisplay = users.value.find(u => (u.uuid === userUuid)).name
    if (!userDisplay) userDisplay = users.value.find(u => (u.uuid === userUuid)).email
    const swalResult = await Swal.fire({
        title: 'Are you sure you want to remove the user ' + userDisplay + '?',
        text: 'If you proceed, this user will not be able to access this organization, until reinvited.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, remove!',
        cancelButtonText: 'No, cancel it'
    })

    if (swalResult.value) {
        try {
            const resp = await graphqlClient.mutate({
                mutation: gql`
                            mutation removeUser($org: ID!, $user: ID!) {
                                removeUser(org: $org, user: $user)
                            }`,
                variables: {
                    user: userUuid,
                    org: orgResolved.value
                }
            })
            if (resp.data.removeUser) {
                notify('success', 'Removed', `Removed the user ${userDisplay} from the organization successfully!`)
            } else {
                notify('error', 'Error', `Error when removing the user ${userDisplay} from the organization!`)
            }
            loadUsers()
        } catch (e: any) {
            console.error(e)
            notify('error', 'Error', `Error when removing the user ${userDisplay} from the organization!`)
        }
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        Swal.fire(
            'Cancelled',
            'User removal cancelled.',
            'error'
        )
    }
}

async function cancelInvite(email: string) {
    let isSuccess = false
    try {
        const resp = await graphqlClient.mutate({
                mutation: gql`
                            mutation cancelInvite($org: ID!, $userEmail: String!) {
                                    cancelInvite(org: $org, userEmail: $userEmail) {
                                        uuid
                                    }
                                }`,
                variables: {
                    userEmail: email,
                    org: orgResolved.value
                }
            })
        if (resp.data.cancelInvite && resp.data.cancelInvite.uuid) isSuccess = true
        invitees.value = resp.data.cancelInvite.invitees
    } catch (e: any) {
        console.error(e)
    }

    if (isSuccess) {
        loadInvitedUsers(false)
        notify('success', 'Cancelled', `Invitation for the user ${email} cancelled successfully!`)
    } else {
        notify('error', 'Error', `Error when cancelling invitation for the user ${email}!`)
    }
    
}

function resolveApprovals(permissions: any) {
    let approvals: any
    let perm = permissions.permissions.filter((up: any) =>
        (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value)
    )
    if (perm && perm.length) {
        approvals = ''
        perm[0].approvals.forEach((ap: any) => {
            approvals += ap + ' '
        })
    }
    return approvals
}

function showRegistryCommands() {
    showComponentRegistryModal.value = true
}

async function updateKeyPermissions() {
    try {
        const response = await graphqlClient.query({
            query: gql`
                mutation setApprovalsOnApiKey($apiKeyUuid: ID!, $approvals: [String], $notes: String) {
                    setApprovalsOnApiKey(apiKeyUuid: $apiKeyUuid, approvals: $approvals, notes: $notes) {
                        uuid
                        object
                        type
                        keyOrder
                        lastUpdatedBy
                        accessDate
                        createdDate
                        notes
                        permissions {
                            permissions {
                                org
                                scope
                                object
                                type
                                meta
                                approvals
                            }
                        }
                    }
                }`
            ,
            variables: { 
                apiKeyUuid: selectedKey.value.uuid,
                approvals: selectedKey.value.approvals,
                notes: selectedKey.value.notes
            },
            fetchPolicy: 'no-cache'
        })

        const updatedApiKey = response.data.setApprovalsOnApiKey
        formatValuesForApiKeys(updatedApiKey)
        let updated: boolean = false
        for (let i = 0; i < programmaticAccessKeys.value.length && !updated; i++) {
            if (programmaticAccessKeys.value[i].uuid === updatedApiKey.uuid) {
                programmaticAccessKeys.value[i] = updatedApiKey
                updated = true
            }
        }
        showOrgSettingsProgPermissionsModal.value = false
        notify('success', 'Created', 'Saved key permissions successfully!')
    } catch (e: any) {
        notify('error', 'Error', e)
    }
}

async function updateUserPermissions() {
    const permissions: any[] = []
    if (instancePermissions.value && Object.keys(instancePermissions.value).length) {
        Object.keys(instancePermissions.value).forEach(inst => {
            const perm = {
                org: orgResolved.value,
                scope: 'INSTANCE',
                type: instancePermissions.value[inst],
                object: inst
            }
            permissions.push(perm)
        })
    }

    let isSuccess = true

    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                    mutation updateUserPermissions($permissions: [PermissionInput], $approvals: [String]) {
                        updateUserPermissions(orgUuid: "${orgResolved.value}", userUuid: "${selectedUser.value.uuid}",
                            permissionType: ${selectedUser.value.type}, permissions: $permissions, approvals: $approvals) {
                            uuid
                        }
                    }`,
            variables: {
                'permissions': permissions,
                'approvals': selectedUser.value.approvals
            }
        })
        if (!resp.data.updateUserPermissions || !resp.data.updateUserPermissions.uuid) isSuccess = false
    } catch (error: any) {
        console.error(error)
        isSuccess = false
    }

    if (isSuccess) {
        notify('success', 'Saved', `Saved user ${selectedUser.value.email} permissions successfully!`)
        await loadUsers()
    } else {
        notify('error', 'Error', `Failed to save user ${selectedUser.value.email} permissions. Please retry or contact support.`)
    }

    showOrgSettingsUserPermissionsModal.value = false
    selectedUser.value = {}
}

const orgRegistry: ComputedRef<any> = computed((): any => {
    let orgReg = false
    if (store.getters.myorg && store.getters.myorg.registryComponents) {
        orgReg = store.getters.myorg.registryComponents.length
    }
    return orgReg
})
const InstanceType = constants.InstanceType
const instances: ComputedRef<any> = computed((): any => {
    let instances = store.getters.instancesOfOrg(orgResolved.value)
    if (instances && instances.length) {
        instances = instances.filter((x: any) => x.revision === -1 && x.instanceType === InstanceType.STANDALONE_INSTANCE)
        if(instanceSearchString.value != ''){
            instances = instances.filter((x: any) => x.uri.toLowerCase().includes(instanceSearchString.value) )
        }
        // sort - TODO make sort configurable
        instances.sort((a: any, b: any) => {
            if (a.uri.toLowerCase() < b.uri.toLowerCase()) {
                return -1
            } else if (a.uri.toLowerCase() > b.uri.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    let spawnInstanceObj = {
        uri: 'Spawn Instances',
        uuid: '00000000-0000-0000-0000-000000000002',
        org: orgResolved.value
    }
    instances.push(spawnInstanceObj)
    return instances
})
const clusters: ComputedRef<any> = computed((): any => {
    let instances = store.getters.instancesOfOrg(orgResolved.value)
    if (instances && instances.length) {
        instances = instances.filter((x: any) => x.revision === -1 && x.instanceType === InstanceType.CLUSTER)
       
        // sort - TODO make sort configurable
        instances.sort((a: any, b: any) => {
            if (a.uri.toLowerCase() < b.uri.toLowerCase()) {
                return -1
            } else if (a.uri.toLowerCase() > b.uri.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
        instances.forEach((inst: any) => {
            let instanceChildren: any[] = []
            if(inst.instances && inst.instances.length)
                instanceChildren = store.getters.instancesOfOrg(orgResolved.value)
                    .filter((x: any) => inst.instances.includes(x.uuid))
                    .sort((a: { uuid: any }, b: { uuid: any }) => inst.instances.indexOf(a.uuid) - inst.instances.indexOf(b.uuid));
            inst.instanceChildren = instanceChildren

            return inst
        })
    }
    // instances = [{ name: 'All Clusters', uuid: '00000000-0000-0000-0000-000000000003', revision: -1 }, ...instances]
    return instances

})
const instanceSearchString: Ref<string> = ref('')
const filterInstances = async function(value: string){
    instanceSearchString.value = value
    console.log('instanceSearchString', instanceSearchString)
}
const userInstancePermissionColumns = [
    {
        key: 'instance',
        title: 'Instance',
        render: (row: any) => {
            return row.uri
        }
    },
    {
        key: 'permission',
        title: 'Permission',
        render: (row: any) => {
            let els: any[] = []
            permissionTypeSelections.value.forEach(pt => {
                els.push(h(NRadioButton, {value: pt.value}, {default: () => pt.value != 'NONE' ? pt.value : 'USER DEFAULT'}))
            })
            
            return h(NRadioGroup, {
                value: instancePermissions.value[row.uuid] === '' || !instancePermissions.value[row.uuid] ? 'NONE' : instancePermissions.value[row.uuid],
                'onUpdate:value': (value: string) => {
                    instancePermissions.value[row.uuid] = value
                    updateUserPermissions()
                }
            }, {
                default: () => els
            })
            
        }
    }

]
const userClusterPermissionColumns = [
    {
        key: 'cluster',
        title: 'Cluster',
        render: (row: any) => {
            return row.uri && row.uri != '' ? row.uri : row.name
        }
    },
    {
        key: 'ns',
        title: 'ns',
        render: (row: any) => {
            return row.namespace
        }
    },
    {
        key: 'permission',
        title: 'Permission',
        render: (row: any) => {
            let els: any[] = []
            let disabled = false
            if(row.instanceType === InstanceType.CLUSTER_INSTANCE){
                let cluster = store.getters.instancesOfOrg(orgResolved.value).find((x: any) => x.revision === -1 && x.instanceType === InstanceType.CLUSTER && x.instances.includes(row.uuid))
                if(cluster && cluster.uuid){
                    disabled = instancePermissions.value[cluster.uuid] === 'READ_WRITE'
                }
            }
            permissionTypeSelections.value.forEach(pt => {
                els.push(h(NRadioButton, {value: pt.value}, {default: () => pt.value != 'NONE' ? pt.value : 'USER DEFAULT'}))
            })
          
            return h(NRadioGroup, {
                value: disabled ? 'READ_WRITE' : instancePermissions.value[row.uuid] === '' || !instancePermissions.value[row.uuid] ? 'NONE' : instancePermissions.value[row.uuid],
                disabled: disabled,
                'onUpdate:value': (value: string) => {
                    instancePermissions.value[row.uuid] = value
                    updateUserPermissions()
                }
            }, {
                default: () => els
            })
            
        }
    }

]
const jiraIntegrationData: ComputedRef<any> = computed((): any => {
    if (store.getters.myorg && store.getters.myorg.jiraIntegrationData) {
        return store.getters.myorg.jiraIntegrationData
    }
    return false
})
const computedProgrammaticAccessKeys: ComputedRef<any> = computed((): any => {
    return programmaticAccessKeys.value.map((accesKey: any) => {
        if (accesKey.type === 'ORGANIZATION_RW' || accesKey.type === 'ORGANIZATION') {
            accesKey.object_val = store.getters.orgById(accesKey.object).name
        } else if (accesKey.type === 'COMPONENT') {
            var proj = store.getters.componentById(accesKey.object)
            if (proj) {
                accesKey.object_val = proj.name
                accesKey.object_org = proj.org
            } else {
                accesKey.object_val = 'Archived Component'
            }
        } else if (accesKey.type === 'INSTANCE') {
            var inst = store.getters.instanceById(accesKey.object, -1)
            if (inst) {
                accesKey.object_val = inst.uri
                accesKey.object_org = inst.org
                accesKey.object_val = store.getters.instanceById(accesKey.object, -1).uri
            } else {
                accesKey.object_val = 'Archived Instance'
            }
        } else if (accesKey.type === 'USER' || accesKey.type === 'REGISTRY_USER') {
            accesKey.object_val = accesKey.updatedByName
        }
        return accesKey
    })
})
const imageRegistry: ComputedRef<any> = computed((): any => {
    let content = '### OCI Container Images (Suitable for Docker and Helm):\n'
    content += '##### Image Namespaces: \n'
    content = content + '```bash\n'
    content += `${registryHost.value}/${orgResolved.value}-private\n`
    content += `${registryHost.value}/${orgResolved.value}-public\n`
    content += '```\n'
    content += '##### Login To OCI Registry with Docker: \n'
    content = content + '```bash\n'
    content += 'docker login ' + registryHost.value + ' -u \'<username>\' -p \'<token>\'\n'
    content += '```\n'
    content += '##### Push Image \n'
    content = content + '```bash\n'
    content += 'docker push ' + registryHost.value + '/' + orgResolved.value + '-private/<image_name>:<version>\n'
    content += 'docker push ' + registryHost.value + '/' + orgResolved.value + '-public/<image_name>:<version>\n'
    content += '```\n'
    content += '##### Pull Image \n'
    content = content + '```bash\n'
    content += 'docker pull ' + registryHost.value + '/' + orgResolved.value + '-private/<image_name>:<version>\n'
    content += 'docker pull ' + registryHost.value + '/' + orgResolved.value + '-public/<image_name>:<version>\n'
    content += '```\n'
    content += '##### Push Helm Chart\n'
    content = content + '```bash\n'
    content += `helm registry login -u '<username>' -p 'token' ${registryHost.value}\n`
    content += `helm package <chartdir>\n`
    content += 'helm push <chart.tgz> oci://' + registryHost.value + '/' + orgResolved.value + '-private\n'
    content += 'helm push <chart.tgz> oci://' + registryHost.value + '/' + orgResolved.value + '-public\n'
    content += '```\n'
    content += '##### Pull Helm Chart\n'
    content = content + '```bash\n'
    content += `helm registry login -u '<username>' -p 'token' ${registryHost.value}\n`
    content += 'helm pull oci://' + registryHost.value + '/' + orgResolved.value + '-private/<my-chart> --version <my-version>\n'
    content += 'helm pull oci://' + registryHost.value + '/' + orgResolved.value + '-public/<my-chart> --version <my-version>\n'
    content += '```\n'

    return Marked.parse(content)
})

const isWritable: Ref<boolean> = ref(false)
const userPermission: ComputedRef<any> = computed((): any => commonFunctions.getUserPermission(orgResolved.value, store.getters.myuser).org)
const environmentOptions: ComputedRef<any[]> = computed((): any => {
    return environmentTypes.value.map((et: string) => {
        return { 'label': et, 'value': et }
    })
})
async function saveProtectedEnvironments() {

    if (myapp.value) {
        const gqlResponse = await store.dispatch('saveProtectedEnvironments', {
            org: myapp.value.org,
            uuid: myapp.value.uuid,
            protectedEnvironments: protectedEnvironments.value
        })
        notify('success', 'Updated', 'Successfully updated Protected Environments')            
    }
}

const dataTableRowKey = (row: any) => row.uuid

const approvalEntryFields: DataTableColumns<any> = [
    {
        key: 'approvalName',
        title: 'Approval Name'
    },
    {
        key: 'approvalRoles',
        title: 'Required Approvals'
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (isWritable) {
                const deleteEl = h(NIcon, {
                        title: 'Delete Approval Entry',
                        class: 'icons clickable',
                        size: 20,
                        onClick: () => {
                            deleteApprovalEntry(row.uuid)
                        }
                    }, 
                    { 
                        default: () => h(Trash) 
                    }
                )
                els.push(deleteEl)
            }
            if (!els.length) els = [h('div'), row.status]
            return els
        }
    }
]

const orgApprovalEntries: Ref<ApprovalEntry[]> = ref([])

const approvalEntryTableData: ComputedRef<any[]> = computed((): any => {
    const data = orgApprovalEntries.value.map(oae => {
        const approvalRoles = oae.approvalRequirements.map(oaear => oaear.allowedApprovalRoleIdExpanded[0].displayView)
        return {
            uuid: oae.uuid,
            approvalName: oae.approvalName,
            approvalRoles: approvalRoles.toString()
        }
    })
    return data
})

async function fetchApprovalEntries () {
    const response = await graphqlClient.query({
        query: gql`
            query approvalEntriesOfOrg($orgUuid: ID!) {
                approvalEntriesOfOrg(orgUuid: $orgUuid) {
                    uuid
                    approvalName
                    approvalRequirements {
                        allowedApprovalRoleIdExpanded {
                            id
                            displayView
                        }
                    }
                }
            }`,
        variables: {
            'orgUuid': orgResolved.value
        },
        fetchPolicy: 'no-cache'
    })

    orgApprovalEntries.value = response.data.approvalEntriesOfOrg
}

function approvalEntryCreated () {
    fetchApprovalEntries()
    showCreateApprovalEntry.value = false
}

function approvalPolicyCreated () {
    fetchApprovalPolicies()
    showCreateApprovalPolicy.value = false
}

const approvalPolicyFields: DataTableColumns<any> = [
    {
        key: 'policyName',
        title: 'Policy Name'
    },
    {
        key: 'approvalNames',
        title: 'Approval Names'
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (isWritable) {
                const deleteEl = h(NIcon, {
                        title: 'Delete Approval Policy',
                        class: 'icons clickable',
                        size: 20,
                        onClick: () => {
                            deleteApprovalPolicy(row.uuid)
                        }
                    }, 
                    { 
                        default: () => h(Trash) 
                    }
                )
                els.push(deleteEl)
            }
            if (!els.length) els = [h('div'), row.status]
            return els
        }
    }
]

const approvalPolicyTableData: Ref<any[]> = ref([])

async function fetchApprovalPolicies () {
    const response = await graphqlClient.query({
        query: gql`
            query approvalPoliciesOfOrg($orgUuid: ID!) {
                approvalPoliciesOfOrg(orgUuid: $orgUuid) {
                    uuid
                    policyName
                    approvalEntryDetails {
                        approvalName
                    }
                }
            }`,
        variables: {
            'orgUuid': orgResolved.value
        },
        fetchPolicy: 'no-cache'
    })
    const approvalPolicyResp = response.data.approvalPoliciesOfOrg
    if (approvalPolicyResp && approvalPolicyResp.length) {
        approvalPolicyTableData.value = approvalPolicyResp.map((x: any) => {
            let approvalNames = ''
            if (x.approvalEntryDetails && x.approvalEntryDetails.length) {
                x.approvalEntryDetails.forEach((aed: any) => {
                    approvalNames += aed.approvalName + ', '
                })
                approvalNames = approvalNames.substring(0, approvalNames.length - 2)
            }
            return {
                uuid: x.uuid,
                policyName: x.policyName,
                approvalNames
            }
        })
    }
}

</script>
  
<style scoped lang="scss">
.approvalRow:hover {
    background-color: #d9eef3;
}

.approvalTypeHeader {
    writing-mode: vertical-lr;
    
}
.approvalTypeCB {
    width: 24px;
    display: block;
}

.approvalMatrixContainer {
    max-width: 50%;
}

.removeFloat {
    clear: both;
}

.inviteUserForm {
    margin-bottom: 10px;
}

.createUserGroupForm {
    margin-bottom: 10px;
}
</style>
  