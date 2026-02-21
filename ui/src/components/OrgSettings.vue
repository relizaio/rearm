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
                                <n-icon @click="deleteIntegration('SLACK')" class="clickable" size="20"><Trash /></n-icon>
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
                                <n-icon @click="deleteIntegration('MSTEAMS')" class="clickable" size="20"><Trash /></n-icon>
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
                            <div v-if="configuredIntegrations.includes('DEPENDENCYTRACK')">
                                <div>
                                    Dependency-Track integration configured
                                    <n-icon v-if="isOrgAdmin" @click="deleteIntegration('DEPENDENCYTRACK')" class="clickable" size="20"><Trash /></n-icon>
                                    <n-icon v-if="isOrgAdmin" class="clickable" size="24" title="Synchronize D-Track Projects" @click="syncDtrackProjects" style="margin-left: 8px; ">
                                        <Refresh />
                                    </n-icon>
                                    <n-icon v-if="isGlobalAdmin" class="clickable" size="24" title="Re-upload D-Track Projects" @click="refreshDtrackProjects" style="margin-left: 8px; ">
                                        <ArrowUpload24Regular />
                                    </n-icon>
                                    <n-icon v-if="isGlobalAdmin" class="clickable" size="24" title="Cleanup D-Track Projects" @click="cleanupDtrackProjects" style="margin-left: 8px; ">
                                        <Clean />
                                    </n-icon>
                                    <n-icon v-if="isGlobalAdmin" class="clickable" size="24" title="Re-cleanup D-Track Projects" @click="recleanupDtrackProjects" style="margin-left: 8px; ">
                                        <DeleteDismiss24Regular />
                                    </n-icon>
                                </div>
                                <div v-if="false" style="margin-top: 8px;">
                                    <n-button @click="syncDtrackStatus" :loading="syncingDtrackStatus" type="primary" size="small">
                                        Sync Dependency-Track Status for All Artifacts
                                    </n-button>
                                </div>
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
                        @after-enter="blurActiveElement"
                    >
                        <ScopedPermissions
                            v-model="userScopedPermissions"
                            :org-uuid="orgResolved"
                            :approval-roles="myorg.approvalRoles || []"
                            :perspectives="perspectives"
                            :products="orgProducts"
                            :components="orgComponents"
                        />
                        <n-space style="margin-top: 20px;">
                            <n-button type="success" @click="updateUserPermissions">Save Permissions</n-button>
                            <n-button type="warning" @click="editUser(selectedUser.email)">Reset Changes</n-button>
                        </n-space>
                    </n-modal>
                    <h6>Pending Invites</h6>
                    <n-data-table :columns="inviteeFields" :data="invitees" class="table-hover">
                    </n-data-table>
                </div>
            </n-tab-pane>

            <n-tab-pane v-if="isOrgAdmin" name="userGroups" tab="User Groups">
                <div class="userGroupBlock mt-4">
                    <h5>User Groups ({{ filteredUserGroups.length }})</h5>
                    <n-space align="center" style="margin-bottom: 12px;">
                        <n-switch v-model:value="showInactiveGroups" />
                        <span>Show Inactive Groups</span>
                    </n-space>
                    <n-data-table :columns="userGroupFields" :data="filteredUserGroups" :row-class-name="userGroupRowClassName" class="table-hover">
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
                        :title="(restoreMode ? 'Restore User Group: ' : 'User Group Settings for ') + selectedUserGroup.name"
                        @after-enter="blurActiveElement"
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
                                    v-model:value="selectedUserGroup.manualUsers"
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

                            <ScopedPermissions
                                v-model="userGroupScopedPermissions"
                                :org-uuid="orgResolved"
                                :approval-roles="myorg.approvalRoles || []"
                                :perspectives="perspectives"
                                :products="orgProducts"
                                :components="orgComponents"
                            />
                        </n-flex>
                        <n-space style="margin-top: 20px;">
                            <n-button v-if="restoreMode" type="success" @click="confirmRestoreUserGroup">Restore Group</n-button>
                            <n-button v-else type="success" @click="updateUserGroup">Save Changes</n-button>
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
                    <n-icon v-if="isOrgAdmin" class="clickable" @click="genApiKey"
                        title="Create Api Key" size="24"><CirclePlus /></n-icon>
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
                        <n-card size="huge" :bordered="false"
                            role="dialog" aria-modal="true">
                            <template #header>
                                <div style="display: flex; align-items: center; gap: 10px;">
                                    <span>Components and Products of Perspective: {{ selectedPerspectiveName }}</span>
                                    <n-icon v-if="isOrgAdmin && selectedPerspectiveType !== 'PRODUCT'" class="clickable"
                                        @click="showAddComponentToPerspectiveModal = true" title="Add Component" size="24"><CirclePlus /></n-icon>
                                    <n-icon v-if="isOrgAdmin && selectedPerspectiveType !== 'PRODUCT'" class="clickable"
                                        @click="showAddProductToPerspectiveModal = true" title="Add Product" size="24"><FolderPlus /></n-icon>
                                </div>
                            </template>
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
                        v-model:show="showAddComponentToPerspectiveModal"
                        preset="dialog"
                        :show-icon="false"
                        style="width: 600px;">
                        <n-card size="huge" title="Add Component to Perspective" :bordered="false"
                            role="dialog" aria-modal="true">
                            <n-form>
                                <n-form-item label="Select Component" label-placement="top">
                                    <n-select
                                        v-model:value="selectedComponentToAdd"
                                        :options="availableComponentsOptions"
                                        placeholder="Select a component"
                                        filterable />
                                </n-form-item>
                                <n-space>
                                    <n-button :loading="processingMode" @click="addComponentToPerspective" type="success">Add</n-button>
                                    <n-button type="error" @click="showAddComponentToPerspectiveModal = false">Cancel</n-button>
                                </n-space>
                            </n-form>
                        </n-card>
                    </n-modal>
                    <n-modal
                        v-model:show="showAddProductToPerspectiveModal"
                        preset="dialog"
                        :show-icon="false"
                        style="width: 600px;">
                        <n-card size="huge" title="Add Product to Perspective" :bordered="false"
                            role="dialog" aria-modal="true">
                            <n-form>
                                <n-form-item label="Select Product" label-placement="top">
                                    <n-select
                                        v-model:value="selectedProductToAdd"
                                        :options="availableProductsOptions"
                                        placeholder="Select a product"
                                        filterable />
                                </n-form-item>
                                <n-space>
                                    <n-button :loading="processingMode" @click="addProductToPerspective" type="success">Add</n-button>
                                    <n-button type="error" @click="showAddProductToPerspectiveModal = false">Cancel</n-button>
                                </n-space>
                            </n-form>
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
            <n-tab-pane name="adminSettings" tab="Admin Settings" v-if="isOrgAdmin">
                <div class="adminSettingsBlock mt-4">
                    <h5>Violation Ignore Regexes</h5>
                    <p class="text-muted">Configure regex patterns to ignore specific violations. Matching violations will be excluded from reports.</p>
                    
                    <n-form>
                        <n-form-item label="License Violation Ignore Patterns">
                            <n-dynamic-input
                                v-model:value="ignoreViolation.licenseViolationRegexIgnore"
                                placeholder="Enter regex pattern"
                                :min="0"
                            />
                        </n-form-item>
                        
                        <n-form-item label="Security Violation Ignore Patterns">
                            <n-dynamic-input
                                v-model:value="ignoreViolation.securityViolationRegexIgnore"
                                placeholder="Enter regex pattern"
                                :min="0"
                            />
                        </n-form-item>
                        
                        <n-form-item label="Operational Violation Ignore Patterns">
                            <n-dynamic-input
                                v-model:value="ignoreViolation.operationalViolationRegexIgnore"
                                placeholder="Enter regex pattern"
                                :min="0"
                            />
                        </n-form-item>
                        
                        <n-space>
                            <n-button type="primary" @click="saveIgnoreViolation" :loading="savingIgnoreViolation">
                                Save Ignore Patterns
                            </n-button>
                        </n-space>
                    </n-form>
                </div>
                <div class="adminSettingsBlock mt-4">
                    <h5>Finding Analysis Settings</h5>
                    <p class="text-muted">Configure requirements for vulnerability finding analysis creation.</p>
                    
                    <n-form>
                        <n-form-item label="Justification Mandatory">
                            <n-switch v-model:value="orgSettings.justificationMandatory" />
                            <span class="ml-2 text-muted">{{ orgSettings.justificationMandatory ? 'Justification is required when creating finding analysis' : 'Justification is optional when creating finding analysis' }}</span>
                        </n-form-item>
                        
                        <n-space>
                            <n-button type="primary" @click="saveOrgSettings" :loading="savingOrgSettings">
                                Save Settings
                            </n-button>
                        </n-space>
                    </n-form>
                </div>
            </n-tab-pane>
            <n-tab-pane name="registry" tab="Registry" v-if="globalRegistryEnabled">
                <div class="mt-4">
                    <h5>Organization Registry</h5>
                    <div v-if="!orgRegistry">
                        <div v-if="isOrgAdmin">
                            Enable Organization Registry :
                            <n-icon @click="enableRegistry()" class="clickable icons"
                                title="Enable Organization Registry" size="24"><Package /></n-icon>
                        </div>
                    </div>
                    <div v-else>Organization Registry Commands:
                        <n-icon @click="showRegistryCommands()" class="clickable icons"
                            title="Show Organization Registry Commands" size="24"><Package /></n-icon>
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
import { NSpace, NIcon, NCheckbox, NCheckboxGroup, NDropdown, NInput, NModal, NCard, NDataTable, NForm, NInputGroup, NButton, NFormItem, NSelect, NRadioGroup, NRadioButton, NTabs, NTabPane, NTooltip, NotificationType, useNotification, NFlex, NH5, NText, NGrid, NGi, DataTableColumns, NDynamicInput, NSwitch } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed, onMounted, reactive } from 'vue'
import type { SelectOption } from 'naive-ui'
import { useStore } from 'vuex'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Edit as EditIcon, Trash, LockOpen, CirclePlus, Eye, QuestionMark, Refresh, Search, FolderPlus, Package } from '@vicons/tabler'
import { Clean } from '@vicons/carbon'
import { Info20Regular, Edit24Regular, DeleteDismiss24Regular, ArrowUpload24Regular } from '@vicons/fluent'
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
import ScopedPermissions from './ScopedPermissions.vue'
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
    } else if (tabName === "adminSettings") {
        loadIgnoreViolation()
        loadOrgSettings()
    }
}

const showOrgApiKeyModal = ref(false)

const showOrgSettingsProgPermissionsModal = ref(false)

const showOrgSettingsUserPermissionsModal = ref(false)

const showUserGroupPermissionsModal = ref(false)

function blurActiveElement() {
    if (document.activeElement instanceof HTMLElement) {
        document.activeElement.blur()
    }
}

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
const showAddComponentToPerspectiveModal = ref(false)
const showAddProductToPerspectiveModal = ref(false)
const selectedComponentToAdd: Ref<string> = ref('')
const selectedProductToAdd: Ref<string> = ref('')

// Admin Settings - Ignore Violation Regexes
const ignoreViolation = reactive({
    licenseViolationRegexIgnore: [] as string[],
    securityViolationRegexIgnore: [] as string[],
    operationalViolationRegexIgnore: [] as string[]
})
const savingIgnoreViolation = ref(false)

// Admin Settings - Organization Settings
const orgSettings = reactive({
    justificationMandatory: false
})
const savingOrgSettings = ref(false)

const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)
const isGlobalAdmin = computed(() => myUser.value?.isGlobalAdmin === true)

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
const permissionTypes: string[] = constants.PermissionTypes
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
const permissionTypeswAdmin: string[] = constants.PermissionTypesWithAdmin

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
const syncingDtrackStatus: Ref<boolean> = ref(false)
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
const showInactiveGroups = ref(false)
const userGroups: Ref<any[]> = ref([])
const filteredUserGroups = computed(() => {
    if (showInactiveGroups.value) {
        return userGroups.value
    }
    return userGroups.value.filter((g: any) => g.status === 'ACTIVE')
})
const selectedUserGroup: Ref<any> = ref({})
const restoreMode = ref(false)

// Scoped permissions state (shared model for ScopedPermissions component)
const userScopedPermissions: Ref<any> = ref({
    orgPermission: { type: 'NONE', functions: ['RESOURCE'], approvals: [] },
    scopedPermissions: []
})
const userGroupScopedPermissions: Ref<any> = ref({
    orgPermission: { type: 'NONE', functions: ['RESOURCE'], approvals: [] },
    scopedPermissions: []
})

const orgComponents = computed(() => store.getters.componentsOfOrg(orgResolved.value) || [])
const orgProducts = computed(() => store.getters.productsOfOrg(orgResolved.value) || [])
const allComponents = computed(() => [...orgComponents.value, ...orgProducts.value])
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
                        manualUsers
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
                                functions
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

function userGroupRowClassName(row: any) {
    return row.status === 'INACTIVE' ? 'inactive-row' : ''
}

// Perspectives
const perspectives: Ref<any[]> = ref([])
const newPerspective: Ref<any> = ref({
    name: ''
})
const showPerspectiveComponentsModal = ref(false)
const selectedPerspectiveUuid: Ref<string> = ref('')
const selectedPerspectiveName: Ref<string> = ref('')
const selectedPerspectiveType: Ref<string> = ref('')
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
    },
    {
        key: 'source',
        title: () => {
            return h('div', { style: 'display: flex; align-items: center; gap: 4px;' }, [
                h('span', 'Source'),
                h(
                    NTooltip,
                    {},
                    {
                        trigger: () => h(
                            NIcon,
                            {
                                size: 16,
                                style: 'cursor: help;'
                            },
                            () => h(QuestionMark)
                        ),
                        default: () => 'Manual: Component was added manually to this perspective. Transitive: Component was added as a transitive dependency of a product.'
                    }
                )
            ])
        },
        render(row: any) {
            const isManual = row.perspectiveDetails?.some((pd: any) => pd.uuid === selectedPerspectiveUuid.value)
            return h('div', isManual ? 'Manual' : 'Transitive')
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
        key: 'source',
        title: () => {
            return h('div', { style: 'display: flex; align-items: center; gap: 4px;' }, [
                h('span', 'Source'),
                h(
                    NTooltip,
                    {},
                    {
                        trigger: () => h(
                            NIcon,
                            {
                                size: 16,
                                style: 'cursor: help;'
                            },
                            () => h(QuestionMark)
                        ),
                        default: () => 'Auto perspectives are created automatically per each Product. They cannot be edited.'
                    }
                )
            ])
        },
        render(row: any) {
             return h('div', row.type === 'PERSPECTIVE' ? 'Manual' : 'Auto')
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
                        onClick: () => showPerspectiveComponentsModalFn(row.uuid, row.name, row.type)
                    },
                    () => h(Eye)
                )
            ]
            
            // Add edit and delete icons only for admin users AND if not PRODUCT type
            if (isOrgAdmin.value && row.type !== 'PRODUCT') {
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
                actions.push(
                    h(
                        NIcon,
                        {
                            title: 'Delete Perspective',
                            class: 'icons clickable',
                            size: 25,
                            style: 'color: #d03050;',
                            onClick: () => deletePerspective(row)
                        },
                        () => h(Trash)
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
                        type
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

async function showPerspectiveComponentsModalFn(perspectiveUuid: string, perspectiveName: string, perspectiveType: string = 'PERSPECTIVE') {
    selectedPerspectiveUuid.value = perspectiveUuid
    selectedPerspectiveName.value = perspectiveName
    selectedPerspectiveType.value = perspectiveType
    showPerspectiveComponentsModal.value = true
    
    // Load components and products for the org
    await store.dispatch('fetchComponents', orgResolved.value)
    await store.dispatch('fetchProducts', orgResolved.value)
    
    try {
        const response = await graphqlClient.query({
            query: gql`
                query componentsOfPerspective($perspectiveUuid: ID!) {
                    componentsOfPerspective(perspectiveUuid: $perspectiveUuid) {
                        uuid
                        name
                        org
                        type
                        perspectiveDetails {
                            uuid
                        }
                    }
                }`,
            variables: {
                perspectiveUuid: perspectiveUuid
            },
            fetchPolicy: 'no-cache'
        })
        const components = response.data.componentsOfPerspective || []
        perspectiveComponents.value = components.sort((a: any, b: any) => {
            // 1. Sort by Type (Product before Component)
            if (a.type !== b.type) {
                // If a is PRODUCT, it comes first (-1). If a is COMPONENT (and b is PRODUCT), a comes second (1).
                return a.type === 'PRODUCT' ? -1 : 1
            }
            
            // 2. Sort by Source (Manual before Transitive)
            const aManual = a.perspectiveDetails?.some((pd: any) => pd.uuid === perspectiveUuid)
            const bManual = b.perspectiveDetails?.some((pd: any) => pd.uuid === perspectiveUuid)
            
            if (aManual !== bManual) {
                // If a is Manual (true), it comes first (-1). If a is Transitive (false), it comes second (1).
                return aManual ? -1 : 1
            }
            
            // 3. Optional: Sort by Name alphabetically as a tie-breaker
            return (a.name || '').localeCompare(b.name || '')
        })
    } catch (error: any) {
        console.error('Error loading perspective components:', error)
        notify('error', 'Error', 'Failed to load components for this perspective')
        perspectiveComponents.value = []
    }
}

const availableComponentsOptions: ComputedRef<SelectOption[]> = computed(() => {
    const allComponents = store.getters.componentsOfOrg(orgResolved.value)
    const perspectiveComponentUuids = perspectiveComponents.value.map(c => c.uuid)
    return allComponents
        .filter((c: any) => !perspectiveComponentUuids.includes(c.uuid))
        .map((c: any) => ({
            label: c.name,
            value: c.uuid
        }))
})

const availableProductsOptions: ComputedRef<SelectOption[]> = computed(() => {
    const allProducts = store.getters.productsOfOrg(orgResolved.value)
    const perspectiveComponentUuids = perspectiveComponents.value.map(c => c.uuid)
    return allProducts
        .filter((p: any) => !perspectiveComponentUuids.includes(p.uuid))
        .map((p: any) => ({
            label: p.name,
            value: p.uuid
        }))
})

async function addComponentToPerspective() {
    if (!selectedComponentToAdd.value) {
        notify('error', 'Error', 'Please select a component')
        return
    }
    
    processingMode.value = true
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
                componentUuid: selectedComponentToAdd.value,
                perspectiveUuids: [selectedPerspectiveUuid.value]
            }
        })
        
        if (response.data && response.data.setPerspectivesOnComponent) {
            notify('success', 'Success', 'Component added to perspective successfully')
            showAddComponentToPerspectiveModal.value = false
            selectedComponentToAdd.value = ''
            // Reload perspective components
            await showPerspectiveComponentsModalFn(selectedPerspectiveUuid.value, selectedPerspectiveName.value, selectedPerspectiveType.value)
        }
    } catch (error: any) {
        console.error('Error adding component to perspective:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.toString()))
    } finally {
        processingMode.value = false
    }
}

async function addProductToPerspective() {
    if (!selectedProductToAdd.value) {
        notify('error', 'Error', 'Please select a product')
        return
    }
    
    processingMode.value = true
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
                componentUuid: selectedProductToAdd.value,
                perspectiveUuids: [selectedPerspectiveUuid.value]
            }
        })
        
        if (response.data && response.data.setPerspectivesOnComponent) {
            notify('success', 'Success', 'Product added to perspective successfully')
            showAddProductToPerspectiveModal.value = false
            selectedProductToAdd.value = ''
            // Reload perspective components
            await showPerspectiveComponentsModalFn(selectedPerspectiveUuid.value, selectedPerspectiveName.value, selectedPerspectiveType.value)
        }
    } catch (error: any) {
        console.error('Error adding product to perspective:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.toString()))
    } finally {
        processingMode.value = false
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

async function deletePerspective(perspective: any) {
    const swalResult = await Swal.fire({
        title: `Are you sure you want to archive perspective "${perspective.name}"?`,
        text: 'If you proceed, the perspective will be archived.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, archive!',
        cancelButtonText: 'No, cancel'
    })

    if (swalResult.value) {
        try {
            const response = await graphqlClient.mutate({
                mutation: gql`
                    mutation deletePerspective($uuid: ID!) {
                        deletePerspective(uuid: $uuid) {
                            uuid
                            name
                        }
                    }`,
                variables: {
                    uuid: perspective.uuid
                }
            })
            
            if (response.data && response.data.deletePerspective) {
                // Remove the perspective from the list
                const index = perspectives.value.findIndex(p => p.uuid === perspective.uuid)
                if (index !== -1) {
                    perspectives.value.splice(index, 1)
                }
                notify('success', 'Archived!', `Perspective "${perspective.name}" has been archived successfully.`)
            }
        } catch (error: any) {
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(error.message),
                'error'
            )
        }
    } else if (swalResult.dismiss === Swal.DismissReason.cancel) {
        notify('info', 'Cancelled', 'Archiving perspective cancelled.')
    }
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
        key: 'status',
        title: 'Status',
        render(row: any) {
            if (row.status === 'INACTIVE') {
                return h('span', { style: 'color: #999; font-style: italic;' }, 'Inactive')
            }
            return h('span', { style: 'color: #18a058;' }, 'Active')
        }
    },
    {
        key: 'controls',
        title: 'Manage',
        render(row: any) {
            let els: any[] = []
            if (isOrgAdmin.value) {
                if (row.status === 'INACTIVE') {
                    els = [
                        h(
                            NButton,
                            {
                                type: 'success',
                                size: 'small',
                                onClick: () => openRestoreModal(row.uuid)
                            }, { default: () => 'Restore' }
                        )
                    ]
                } else {
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

async function loadIgnoreViolation() {
    if (!myorg.value?.ignoreViolation) return
    const iv = myorg.value.ignoreViolation
    ignoreViolation.licenseViolationRegexIgnore = iv.licenseViolationRegexIgnore || []
    ignoreViolation.securityViolationRegexIgnore = iv.securityViolationRegexIgnore || []
    ignoreViolation.operationalViolationRegexIgnore = iv.operationalViolationRegexIgnore || []
}

async function saveIgnoreViolation() {
    savingIgnoreViolation.value = true
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation updateOrganizationIgnoreViolation($orgUuid: ID!, $ignoreViolation: IgnoreViolationInput!) {
                    updateOrganizationIgnoreViolation(orgUuid: $orgUuid, ignoreViolation: $ignoreViolation) {
                        uuid
                        ignoreViolation {
                            licenseViolationRegexIgnore
                            securityViolationRegexIgnore
                            operationalViolationRegexIgnore
                        }
                    }
                }`,
            variables: {
                orgUuid: orgResolved.value,
                ignoreViolation: {
                    licenseViolationRegexIgnore: ignoreViolation.licenseViolationRegexIgnore,
                    securityViolationRegexIgnore: ignoreViolation.securityViolationRegexIgnore,
                    operationalViolationRegexIgnore: ignoreViolation.operationalViolationRegexIgnore
                }
            },
            fetchPolicy: 'no-cache'
        })
        
        const result = (resp.data as any)?.updateOrganizationIgnoreViolation
        if (result) {
            notify('success', 'Ignore Patterns Saved', 'Violation ignore patterns updated successfully.')
        } else {
            notify('warning', 'Save Warning', 'Save completed but no response received.')
        }
    } catch (err: any) {
        notify('error', 'Save Failed', err.message || 'Failed to save violation ignore patterns.')
    } finally {
        savingIgnoreViolation.value = false
    }
}

async function loadOrgSettings() {
    const s = myorg.value?.settings
    orgSettings.justificationMandatory = s?.justificationMandatory || false
}

async function saveOrgSettings() {
    savingOrgSettings.value = true
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation updateOrganizationSettings($orgUuid: ID!, $settings: SettingsInput!) {
                    updateOrganizationSettings(orgUuid: $orgUuid, settings: $settings) {
                        uuid
                        name
                        terminology {
                            featureSetLabel
                        }
                        ignoreViolation {
                            licenseViolationRegexIgnore
                            securityViolationRegexIgnore
                            operationalViolationRegexIgnore
                        }
                        settings {
                            justificationMandatory
                        }
                    }
                }`,
            variables: {
                orgUuid: orgResolved.value,
                settings: {
                    justificationMandatory: orgSettings.justificationMandatory
                }
            },
            fetchPolicy: 'no-cache'
        })
        
        const result = (resp.data as any)?.updateOrganizationSettings
        if (result) {
            store.commit('UPDATE_ORGANIZATION', result)
            notify('success', 'Settings Saved', 'Organization settings updated successfully.')
        } else {
            notify('warning', 'Save Warning', 'Save completed but no response received.')
        }
    } catch (err: any) {
        notify('error', 'Save Failed', err.message || 'Failed to save organization settings.')
    } finally {
        savingOrgSettings.value = false
    }
}

async function refreshDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation refreshDtrackProjects($orgUuid: ID!) {
                    refreshDtrackProjects(orgUuid: $orgUuid)
                }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        
        const result = (resp.data as any)?.refreshDtrackProjects
        if (result) {
            notify('success', 'D-Track Projects Refresh', 'Successfully refreshed Dependency-Track projects.')
        } else {
            notify('warning', 'D-Track Projects Refresh', 'Refresh completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'D-Track Projects Refresh Failed', err.message || 'Failed to refresh Dependency-Track projects.')
    }
}

async function cleanupDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation cleanupDtrackProjects($orgUuid: ID!) {
                    cleanupDtrackProjects(orgUuid: $orgUuid)
                }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        
        const result = (resp.data as any)?.cleanupDtrackProjects
        if (result) {
            notify('success', 'D-Track Projects Cleanup', 'Successfully cleaned up Dependency-Track projects.')
        } else {
            notify('warning', 'D-Track Projects Cleanup', 'Cleanup completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'D-Track Projects Cleanup Failed', err.message || 'Failed to cleanup Dependency-Track projects.')
    }
}

async function recleanupDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation recleanupDtrackProjects($orgUuid: ID!) {
                    recleanupDtrackProjects(orgUuid: $orgUuid)
                }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        
        const result = (resp.data as any)?.recleanupDtrackProjects
        if (result) {
            notify('success', 'D-Track Projects Re-cleanup', 'Successfully re-cleaned up Dependency-Track projects.')
        } else {
            notify('warning', 'D-Track Projects Re-cleanup', 'Re-cleanup completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'D-Track Projects Re-cleanup Failed', err.message || 'Failed to re-cleanup Dependency-Track projects.')
    }
}

async function syncDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation syncDtrackProjects($orgUuid: ID!) {
                    syncDtrackProjects(orgUuid: $orgUuid)
                }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        
        const result = (resp.data as any)?.syncDtrackProjects
        if (result) {
            notify('success', 'D-Track Projects Sync', 'Successfully synchronized Dependency-Track projects.')
        } else {
            notify('warning', 'D-Track Projects Sync', 'Sync completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'D-Track Projects Sync Failed', err.message || 'Failed to synchronize Dependency-Track projects.')
    }
}

async function syncDtrackStatus() {
    syncingDtrackStatus.value = true
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation syncDtrackStatus($orgUuid: ID!) {
                    syncDtrackStatus(orgUuid: $orgUuid) {
                        successCount
                        failedArtifactUuids
                    }
                }`,
            variables: {
                orgUuid: orgResolved.value
            },
            fetchPolicy: 'no-cache'
        })
        
        if (resp.data && resp.data.syncDtrackStatus) {
            const result = resp.data.syncDtrackStatus
            const failedCount = result.failedArtifactUuids ? result.failedArtifactUuids.length : 0
            
            if (failedCount === 0) {
                notification.success({
                    title: 'Sync Complete',
                    content: `Successfully synced ${result.successCount} artifact(s) with Dependency-Track.`,
                    duration: 5000
                })
            } else {
                notification.warning({
                    title: 'Sync Partially Complete',
                    content: `Synced ${result.successCount} artifact(s). Failed to sync ${failedCount} artifact(s).`,
                    duration: 7000
                })
                
                // Show SweetAlert with failed artifact UUIDs
                const failedUuidsHtml = result.failedArtifactUuids
                    .map((uuid: string) => `<div style="text-align: left; font-family: monospace; padding: 2px 0;">${uuid}</div>`)
                    .join('')
                
                await Swal.fire({
                    icon: 'warning',
                    title: 'Partial Sync Failure',
                    html: `<div style="margin-bottom: 10px;">Failed to sync ${failedCount} artifact(s):</div><div style="max-height: 300px; overflow-y: auto; border: 1px solid #ddd; padding: 10px; border-radius: 4px;">${failedUuidsHtml}</div>`,
                    confirmButtonText: 'OK'
                })
            }
        }
    } catch (err: any) {
        notification.error({
            title: 'Sync Failed',
            content: err.message || 'Failed to sync Dependency-Track status. Please try again later.',
            duration: 5000
        })
        
        // Show SweetAlert with error details
        await Swal.fire({
            icon: 'error',
            title: 'Sync Failed',
            text: err.message || 'Failed to sync Dependency-Track status. Please try again later or contact support.',
            confirmButtonText: 'OK'
        })
    } finally {
        syncingDtrackStatus.value = false
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
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
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
async function editUser(email: string) {
    const user = users.value.filter(u => (u.email === email))
    selectedUser.value = commonFunctions.deepCopy(user[0])
    await Promise.all([
        loadPerspectives(),
        store.dispatch('fetchComponents', orgResolved.value),
        store.dispatch('fetchProducts', orgResolved.value)
    ])
    // locate permission for approvals and instance permissions
    let perm: any
    instancePermissions.value = {}
    const scopedPerms: any[] = []
    selectedUser.value.permissions.permissions.forEach((up: any) => {
        if (up.scope === 'ORGANIZATION' && up.org === up.object && up.org === orgResolved.value) {
            perm = up
        } else if (up.scope === 'INSTANCE' && up.org === orgResolved.value) {
            instancePermissions.value[up.object] = up.type
        } else if ((up.scope === 'PERSPECTIVE' || up.scope === 'COMPONENT') && up.org === orgResolved.value) {
            const source = up.scope === 'PERSPECTIVE' ? perspectives.value : allComponents.value
            const obj = source.find((o: any) => o.uuid === up.object)
            scopedPerms.push({
                scope: up.scope,
                objectId: up.object,
                objectName: obj ? obj.name : up.object,
                type: up.type,
                functions: readResourceFunction(up.functions),
                approvals: up.approvals || []
            })
        }
    })

    selectedUser.value.approvals = commonFunctions.deepCopy(perm.approvals)
    selectedUser.value.type = perm.type
    selectedUserType.value = perm.type

    userScopedPermissions.value = {
        orgPermission: {
            type: perm.type || 'NONE',
            functions: readResourceFunction(perm.functions),
            approvals: commonFunctions.deepCopy(perm.approvals) || []
        },
        scopedPermissions: scopedPerms
    }

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
function readResourceFunction(fns: string[] | null | undefined): string[] {
    if (!fns || !fns.length) return ['RESOURCE']
    if (!fns.includes('RESOURCE')) return ['RESOURCE', ...fns]
    return fns
}

function writeResourceFunction(fns: string[] | null | undefined): string[] | null {
    if (!fns || !fns.length || !fns.includes('RESOURCE')) {
        notify('error', 'Error', 'RESOURCE function is required and cannot be removed.')
        return null
    }
    return fns
}

function translatePermissionName(type: string) {
    switch (type) {
        case 'NONE': return 'None'
        case 'ESSENTIAL_READ': return 'Essential Read'
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
        notify('error', 'Cancelled', 'Your existing API Key is safe')
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
        const scopedData = userGroupScopedPermissions.value
        const orgPermType = scopedData.orgPermission.type
        const orgApprovals = scopedData.orgPermission.approvals || []
        const orgFunctions = writeResourceFunction(scopedData.orgPermission.functions)
        if (!orgFunctions) return
        
        const permissions: any[] = []
        
        // Add organization-wide permission
        if (orgPermType && orgPermType !== 'NONE') {
            permissions.push({
                scope: 'ORGANIZATION',
                objectId: orgResolved.value,
                type: orgPermType,
                functions: orgFunctions,
                approvals: orgApprovals
            })
        }
        
        // Add per-scope permissions
        if (scopedData.scopedPermissions && scopedData.scopedPermissions.length) {
            for (const sp of scopedData.scopedPermissions) {
                if (sp.type && sp.type !== 'NONE') {
                    const spFunctions = writeResourceFunction(sp.functions)
                    if (!spFunctions) return
                    permissions.push({
                        scope: sp.scope,
                        objectId: sp.objectId,
                        type: sp.type,
                        functions: spFunctions,
                        approvals: sp.approvals || []
                    })
                }
            }
        }
        
        const updateInput = {
            groupId: selectedUserGroup.value.uuid,
            name: selectedUserGroup.value.name,
            description: selectedUserGroup.value.description,
            manualUsers: selectedUserGroup.value.manualUsers || [],
            status: selectedUserGroup.value.status,
            connectedSsoGroups: selectedUserGroup.value.connectedSsoGroups || [],
            permissions
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

async function editUserGroup(groupUuid: string) {
    const group = userGroups.value.find(g => g.uuid === groupUuid)
    if (group) {
        selectedUserGroup.value = commonFunctions.deepCopy(group)
        await Promise.all([
            loadPerspectives(),
            store.dispatch('fetchComponents', orgResolved.value),
            store.dispatch('fetchProducts', orgResolved.value)
        ])
        
        let orgPerm: any = { type: 'NONE', functions: ['RESOURCE'], approvals: [] }
        const scopedPerms: any[] = []
        
        // Extract all permissions from the nested structure
        if (group.permissions && group.permissions.permissions && group.permissions.permissions.length) {
            group.permissions.permissions.forEach((p: any) => {
                if (p.scope === 'ORGANIZATION' && p.org === orgResolved.value && p.object === orgResolved.value) {
                    orgPerm = {
                        type: p.type || 'NONE',
                        functions: readResourceFunction(p.functions),
                        approvals: p.approvals || []
                    }
                } else if ((p.scope === 'PERSPECTIVE' || p.scope === 'COMPONENT') && p.org === orgResolved.value) {
                    const source = p.scope === 'PERSPECTIVE' ? perspectives.value : allComponents.value
                    const obj = source.find((o: any) => o.uuid === p.object)
                    scopedPerms.push({
                        scope: p.scope,
                        objectId: p.object,
                        objectName: obj ? obj.name : p.object,
                        type: p.type,
                        functions: readResourceFunction(p.functions),
                        approvals: p.approvals || []
                    })
                }
            })
        }
        
        selectedUserGroup.value.orgPermissionType = orgPerm.type
        selectedUserGroup.value.approvals = orgPerm.approvals
        
        userGroupScopedPermissions.value = {
            orgPermission: commonFunctions.deepCopy(orgPerm),
            scopedPermissions: scopedPerms
        }
        
        // Ensure arrays are initialized
        selectedUserGroup.value.users = selectedUserGroup.value.users || []
        selectedUserGroup.value.manualUsers = selectedUserGroup.value.manualUsers || []
        selectedUserGroup.value.connectedSsoGroups = selectedUserGroup.value.connectedSsoGroups || []
        restoreMode.value = false
        showUserGroupPermissionsModal.value = true
    }
}

async function deleteUserGroup(groupUuid: string) {
    const group = userGroups.value.find(g => g.uuid === groupUuid)
    if (!group) return
    
    const swalResp = await Swal.fire({
        title: 'Are you sure?',
        text: `Are you sure you want to deactivate the user group "${group.name}"?`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes!',
        cancelButtonText: 'No!'
    })
    
    if (swalResp.value) {
        try {
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
                notify('success', 'Deactivated!', `The user group "${group.name}" has been deactivated.`)
                loadUserGroups()
            }
        } catch (error: any) {
            console.error('Error deactivating user group:', error)
            notify('error', 'Error', commonFunctions.parseGraphQLError(error.message))
        }
    } else if (swalResp.dismiss === Swal.DismissReason.cancel) {
        notify('error', 'Cancelled', 'The user group remains active.')
    }
}

function openRestoreModal(groupUuid: string) {
    const group = userGroups.value.find(g => g.uuid === groupUuid)
    if (!group) return
    editUserGroup(groupUuid)
    restoreMode.value = true
}

async function confirmRestoreUserGroup() {
    if (!selectedUserGroup.value.uuid) return
    
    try {
        const scopedData = userGroupScopedPermissions.value
        const orgPermType = scopedData.orgPermission.type
        const orgApprovals = scopedData.orgPermission.approvals || []
        const orgFunctions = writeResourceFunction(scopedData.orgPermission.functions)
        if (!orgFunctions) return
        
        const permissions: any[] = []
        
        if (orgPermType && orgPermType !== 'NONE') {
            permissions.push({
                scope: 'ORGANIZATION',
                objectId: orgResolved.value,
                type: orgPermType,
                functions: orgFunctions,
                approvals: orgApprovals
            })
        }
        
        // Add per-scope permissions
        if (scopedData.scopedPermissions && scopedData.scopedPermissions.length) {
            for (const sp of scopedData.scopedPermissions) {
                if (sp.type && sp.type !== 'NONE') {
                    const spFunctions = writeResourceFunction(sp.functions)
                    if (!spFunctions) return
                    permissions.push({
                        scope: sp.scope,
                        objectId: sp.objectId,
                        type: sp.type,
                        functions: spFunctions,
                        approvals: sp.approvals || []
                    })
                }
            }
        }
        
        const updateInput = {
            groupId: selectedUserGroup.value.uuid,
            name: selectedUserGroup.value.name,
            description: selectedUserGroup.value.description,
            manualUsers: selectedUserGroup.value.manualUsers || [],
            status: 'ACTIVE',
            connectedSsoGroups: selectedUserGroup.value.connectedSsoGroups || [],
            permissions
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
            notify('success', 'Restored!', `The user group "${selectedUserGroup.value.name}" has been restored.`)
            restoreMode.value = false
            showUserGroupPermissionsModal.value = false
            loadUserGroups()
        }
    } catch (error: any) {
        console.error('Error restoring user group:', error)
        notify('error', 'Error', commonFunctions.parseGraphQLError(error.message))
    }
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
        notify('error', 'Cancelled', 'User removal cancelled.')
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
    
    // Add instance permissions (legacy)
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
    
    // Add scoped permissions (perspective, component) from ScopedPermissions component
    const scopedData = userScopedPermissions.value
    const orgPermType = scopedData.orgPermission.type
    const orgApprovals = scopedData.orgPermission.approvals || []
    const orgFunctions = writeResourceFunction(scopedData.orgPermission.functions)
    if (!orgFunctions) return
    
    // Add org-level permission with functions and approvals
    if (orgPermType && orgPermType !== 'NONE') {
        permissions.push({
            org: orgResolved.value,
            scope: 'ORGANIZATION',
            type: orgPermType,
            object: orgResolved.value,
            functions: orgFunctions,
            approvals: orgApprovals
        })
    }
    
    // Add per-scope permissions
    if (scopedData.scopedPermissions && scopedData.scopedPermissions.length) {
        for (const sp of scopedData.scopedPermissions) {
            if (sp.type && sp.type !== 'NONE') {
                const spFunctions = writeResourceFunction(sp.functions)
                if (!spFunctions) return
                permissions.push({
                    org: orgResolved.value,
                    scope: sp.scope,
                    type: sp.type,
                    object: sp.objectId,
                    functions: spFunctions,
                    approvals: sp.approvals || []
                })
            }
        }
    }

    let isSuccess = true

    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                    mutation updateUserPermissions($permissions: [PermissionInput]) {
                        updateUserPermissions(orgUuid: "${orgResolved.value}", userUuid: "${selectedUser.value.uuid}",
                            permissionType: ${orgPermType}, permissions: $permissions) {
                            uuid
                        }
                    }`,
            variables: {
                'permissions': permissions
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

:deep(.inactive-row td) {
    opacity: 0.55;
    background-color: #f5f5f5 !important;
}
</style>
  