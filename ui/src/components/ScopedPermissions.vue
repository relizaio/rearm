<template>
    <n-flex vertical>
        <!-- Organization-Wide Permission -->
        <n-space style="margin-top: 20px; margin-bottom: 20px;">
            <n-h5>
                <n-text depth="1">
                    Organization-Wide Permissions:
                </n-text>
            </n-h5>
            <n-radio-group v-model:value="orgPermission.type" @update:value="onOrgPermissionTypeUpdate">
                <n-radio-button
                    v-for="pt in orgTypeOptions"
                    :key="pt"
                    :value="pt"
                    :disabled="(lockOrgType && pt !== orgPermission.type) || (!!maxOrgType && permissionTypesWithAdmin.indexOf(pt) > permissionTypesWithAdmin.indexOf(maxOrgType))"
                >
                    <span v-if="pt === 'ESSENTIAL_READ'" style="display: inline-flex; align-items: center;">
                        {{ translatePermissionName(pt) }}
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-icon size="16" style="margin-left: 4px;">
                                    <QuestionCircle20Regular />
                                </n-icon>
                            </template>
                            Essential Read grants minimal read access to core organization data (e.g., VCS repository listing/details, release tag keys, defined approval policies and entries).
                            <br /> Unlike "Read Only", Essential Read does not grant access to components or products.
                        </n-tooltip>
                    </span>
                    <span v-else>
                        {{ translatePermissionName(pt) }}
                    </span>
                </n-radio-button>
            </n-radio-group>
        </n-space>

        <!-- Organization-Wide Functions -->
        <n-space style="margin-bottom: 20px;" v-if="!externalKey && orgPermission.type !== 'ADMIN' && orgPermission.type !== 'NONE' && orgPermissionFunctions.length">
            <n-h5>
                <n-text depth="1">
                    Organization-Wide Functions:
                </n-text>
            </n-h5>
            <n-checkbox-group v-model:value="orgPermission.functions" @update:value="onOrgFunctionsUpdate">
                <n-checkbox v-for="f in orgPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                    <permission-function-label :f="f" />
                </n-checkbox>
            </n-checkbox-group>
        </n-space>

        <!-- Organization-Wide Approvals -->
        <n-space style="margin-bottom: 20px;" v-if="!externalKey && orgPermission.type !== 'NONE' && (orgPermission.type !== 'ADMIN' || showAdminApprovals) && approvalRoles && approvalRoles.length">
            <n-h5>
                <n-text depth="1">
                    Organization-Wide Approval Permissions:
                </n-text>
                <n-tooltip v-if="orgPermission.type === 'ADMIN'" trigger="hover" style="max-width: 360px;">
                    <template #trigger>
                        <n-icon size="16" style="margin-left: 4px; vertical-align: middle; cursor: help;">
                            <QuestionCircle20Regular />
                        </n-icon>
                    </template>
                    An organization admin can already vote with any approval role. The roles ticked here decide
                    which releases show up in their "Needs my approval" queue and which approval requests they receive.
                </n-tooltip>
            </n-h5>
            <n-checkbox-group v-model:value="orgPermission.approvals" @update:value="emitUpdate">
                <n-checkbox v-for="a in approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" :title="a.displayView" />
            </n-checkbox-group>
        </n-space>

        <!-- Per-Perspective Permissions -->
        <n-space style="margin-bottom: 10px;" v-if="installationType !== 'OSS' && orgPermission.type !== 'ADMIN' && perspectives.length">
            <n-h5>
                <n-text depth="1">
                    Per-Perspective Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="installationType !== 'OSS' && orgPermission.type !== 'ADMIN' && perspectives.length">
            <n-space vertical>
                <n-card v-for="sp in scopedPerspectivePermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('PERSPECTIVE', sp.objectId)"><CloseIcon /></n-icon>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center">
                        <n-text depth="3" style="font-size: 12px;">Permission:</n-text>
                        <n-text v-if="externalKey" data-testid="external-key-read-only">Read Only</n-text>
                        <n-radio-group v-else v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="!externalKey && sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                    <permission-function-label :f="f" />
                                </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="!externalKey && sp.type !== 'NONE' && approvalRoles && approvalRoles.length">
                        <n-text depth="3" style="font-size: 12px;">Approvals:</n-text>
                        <n-checkbox-group v-model:value="sp.approvals" @update:value="emitUpdate">
                            <n-checkbox v-for="a in approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" :title="a.displayView" />
                        </n-checkbox-group>
                    </n-space>
                </n-card>
                <n-space align="center">
                    <n-select
                        v-model:value="newPerspectiveId"
                        :options="availablePerspectiveOptions"
                        placeholder="Add perspective..."
                        style="min-width: 250px;"
                        clearable
                    />
                    <n-button size="small" type="primary" :disabled="!newPerspectiveId" @click="addScopedPermission('PERSPECTIVE')">Add</n-button>
                </n-space>
            </n-space>
        </div>

        <!-- Per-Board Permissions (scope BOARD, board-permissions.md §5; task 428b4a71). After the
             perspectives: the two scopes that hold other things sit together. A grant on a board the
             editor cannot list shows its uuid and stays, so a save keeps it. -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="!externalKey && orgPermission.type !== 'ADMIN' && (boardList.length || scopedBoardPermissions.length)">
            <n-h5>
                <n-text depth="1">
                    Per-Board Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="!externalKey && orgPermission.type !== 'ADMIN' && (boardList.length || scopedBoardPermissions.length)" data-testid="board-permissions">
            <n-space vertical>
                <n-card v-for="sp in scopedBoardPermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;"
                        data-testid="board-permission-card">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-space align="center" :size="6">
                            <n-text strong>{{ boardNameOf(boardList, sp.objectId) ?? sp.objectId }}</n-text>
                            <n-text v-if="!boardNameOf(boardList, sp.objectId)" depth="3" style="font-size: 12px;">board not found</n-text>
                        </n-space>
                        <n-icon class="clickable" size="18" data-testid="board-permission-remove"
                                @click="removeScopedPermission('BOARD', sp.objectId)"><CloseIcon /></n-icon>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center">
                        <n-text depth="3" style="font-size: 12px;">Permission:</n-text>
                        <n-radio-group v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in boardFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                <permission-function-label :f="f" />
                            </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                </n-card>
                <n-space align="center">
                    <n-select
                        v-model:value="newBoardId"
                        :options="availableBoardOptions"
                        placeholder="Add board..."
                        style="min-width: 250px;"
                        filterable
                        clearable
                        data-testid="board-permission-select"
                    />
                    <n-button size="small" type="primary" :disabled="!newBoardId" data-testid="board-permission-add"
                              @click="addScopedPermission('BOARD')">Add</n-button>
                </n-space>
            </n-space>
        </div>

        <!-- Per-Product Permissions -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="orgPermission.type !== 'ADMIN' && products.length">
            <n-h5>
                <n-text depth="1">
                    Per-Product Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="orgPermission.type !== 'ADMIN' && products.length">
            <n-space vertical>
                <n-card v-for="sp in scopedProductPermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('COMPONENT', sp.objectId)"><CloseIcon /></n-icon>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center">
                        <n-text depth="3" style="font-size: 12px;">Permission:</n-text>
                        <n-text v-if="externalKey" data-testid="external-key-read-only">Read Only</n-text>
                        <n-radio-group v-else v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="!externalKey && sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in componentScopedFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                    <permission-function-label :f="f" />
                                </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="!externalKey && sp.type !== 'NONE' && approvalRoles && approvalRoles.length">
                        <n-text depth="3" style="font-size: 12px;">Approvals:</n-text>
                        <n-checkbox-group v-model:value="sp.approvals" @update:value="emitUpdate">
                            <n-checkbox v-for="a in approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" :title="a.displayView" />
                        </n-checkbox-group>
                    </n-space>
                </n-card>
                <n-space align="center">
                    <n-select
                        v-model:value="newProductId"
                        :options="availableProductOptions"
                        placeholder="Add product..."
                        style="min-width: 250px;"
                        filterable
                        clearable
                    />
                    <n-button size="small" type="primary" :disabled="!newProductId" @click="addScopedPermission('PRODUCT')">Add</n-button>
                </n-space>
            </n-space>
        </div>

        <!-- Per-Component Permissions -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="orgPermission.type !== 'ADMIN' && components.length">
            <n-h5>
                <n-text depth="1">
                    Per-Component Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="orgPermission.type !== 'ADMIN' && components.length">
            <n-space vertical>
                <n-card v-for="sp in scopedComponentPermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('COMPONENT', sp.objectId)"><CloseIcon /></n-icon>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center">
                        <n-text depth="3" style="font-size: 12px;">Permission:</n-text>
                        <n-text v-if="externalKey" data-testid="external-key-read-only">Read Only</n-text>
                        <n-radio-group v-else v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="!externalKey && sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in componentScopedFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                    <permission-function-label :f="f" />
                                </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="!externalKey && sp.type !== 'NONE' && approvalRoles && approvalRoles.length">
                        <n-text depth="3" style="font-size: 12px;">Approvals:</n-text>
                        <n-checkbox-group v-model:value="sp.approvals" @update:value="emitUpdate">
                            <n-checkbox v-for="a in approvalRoles" :key="a.id" :value="a.id" :label="a.displayView" :title="a.displayView" />
                        </n-checkbox-group>
                    </n-space>
                </n-card>
                <n-space align="center">
                    <n-select
                        v-model:value="newComponentId"
                        :options="availableComponentOptions"
                        placeholder="Add component..."
                        style="min-width: 250px;"
                        filterable
                        clearable
                    />
                    <n-button size="small" type="primary" :disabled="!newComponentId" @click="addScopedPermission('COMPONENT')">Add</n-button>
                </n-space>
            </n-space>
        </div>

        <!-- Per-Cluster Permissions (scope=INSTANCE on a CLUSTER row). SAAS-only. -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="!externalKey && hasDevOps && orgPermission.type !== 'ADMIN' && clusters.length">
            <n-h5>
                <n-text depth="1">
                    Per-Cluster Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="!externalKey && hasDevOps && orgPermission.type !== 'ADMIN' && clusters.length">
            <n-space vertical>
                <n-card v-for="sp in scopedClusterPermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('INSTANCE', sp.objectId)"><CloseIcon /></n-icon>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center">
                        <n-text depth="3" style="font-size: 12px;">Permission:</n-text>
                        <n-radio-group :value="sp.type" size="small" @update:value="(t: string) => onInstancePermissionTypeUpdate(t, sp)">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in instanceFunctionsFor(sp.type)" :key="f" :value="f" :title="translateFunctionName(f)">
                                {{ translateFunctionName(f) }}
                            </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                </n-card>
                <n-space align="center">
                    <n-select
                        v-model:value="newClusterId"
                        :options="availableClusterOptions"
                        placeholder="Add cluster..."
                        style="min-width: 250px;"
                        filterable
                        clearable
                    />
                    <n-button size="small" type="primary" :disabled="!newClusterId" @click="addScopedPermission('CLUSTER')">Add</n-button>
                </n-space>
            </n-space>
        </div>

        <!-- Per-Instance Permissions (scope=INSTANCE on a STANDALONE_INSTANCE / CLUSTER_INSTANCE row). SAAS-only. -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="!externalKey && hasDevOps && orgPermission.type !== 'ADMIN' && instances.length">
            <n-h5>
                <n-text depth="1">
                    Per-Instance Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="!externalKey && hasDevOps && orgPermission.type !== 'ADMIN' && instances.length">
            <n-space vertical>
                <n-card v-for="sp in scopedInstancePermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('INSTANCE', sp.objectId)"><CloseIcon /></n-icon>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center">
                        <n-text depth="3" style="font-size: 12px;">Permission:</n-text>
                        <n-radio-group :value="sp.type" size="small" @update:value="(t: string) => onInstancePermissionTypeUpdate(t, sp)">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in instanceFunctionsFor(sp.type)" :key="f" :value="f" :title="translateFunctionName(f)">
                                {{ translateFunctionName(f) }}
                            </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                </n-card>
                <n-space align="center">
                    <n-select
                        v-model:value="newInstanceId"
                        :options="availableInstanceOptions"
                        placeholder="Add instance..."
                        style="min-width: 250px;"
                        filterable
                        clearable
                    />
                    <n-button size="small" type="primary" :disabled="!newInstanceId" @click="addScopedPermission('INSTANCE')">Add</n-button>
                </n-space>
            </n-space>
        </div>
    </n-flex>
</template>

<script lang="ts">
export default {
    name: 'ScopedPermissions'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch } from 'vue'
import { useStore } from 'vuex'
import { NFlex, NSpace, NH5, NText, NRadioGroup, NRadioButton, NCheckboxGroup, NCheckbox, NSelect, NButton, NCard, NIcon, NTooltip } from 'naive-ui'
import { X as CloseIcon } from '@vicons/tabler'
import { QuestionCircle20Regular } from '@vicons/fluent'
import constants from '@/utils/constants'
import commonFunctions from '@/utils/commonFunctions'
import PermissionFunctionLabel from '@/components/PermissionFunctionLabel.vue'
import { boardNameOf, boardScopeFunctions, isBoardFunction } from '@/utils/boardPermissions'
import { editionPermissionFunctions } from '@/utils/teaProfile'

interface ApprovalRole {
    id: string
    displayView: string
}

interface ScopedPermission {
    scope: string
    objectId: string
    objectName: string
    type: string
    functions: string[]
    approvals: string[]
}

interface OrgPermission {
    type: string
    functions: string[]
    approvals: string[]
}

interface Props {
    orgUuid: string
    approvalRoles: ApprovalRole[]
    perspectives: any[]
    products: any[]
    components: any[]
    /**
     * STANDALONE_INSTANCE + CLUSTER_INSTANCE rows. Only used for the
     * "Per-Instance Permissions" section. Pass an empty array to hide it.
     */
    instances?: any[]
    /**
     * CLUSTER rows. Only used for the "Per-Cluster Permissions" section.
     * Granting on a cluster cascades to every CLUSTER_INSTANCE under it
     * (server-side, via SaasAuthorizationService.isUserAuthorizedForInstance
     * cluster→child fallback).
     */
    clusters?: any[]
    showSbomProbing?: boolean
    /** highest organization-wide level offered; radios above it are disabled (a personal key is capped by its owner) */
    maxOrgType?: string
    /** keep the organization-wide level as it is -- an admin editing their own permissions must not demote themselves */
    lockOrgType?: boolean
    /**
     * offer approval roles on an organization-wide ADMIN permission. For a user they populate "Needs my
     * approval"; a key has no such queue, so the key editors leave this off.
     */
    showAdminApprovals?: boolean
    /** when set, only these functions are offered anywhere in the editor (the owner's own functions) */
    allowedFunctions?: string[]
    /**
     * The org's boards ({ uuid, name }) for the Per-Board section. Left out, the editor reads them
     * itself (agentBoardsOfOrg, which an org admin sees whole).
     */
    boards?: any[]
    /**
     * The grants of an EXTERNAL key (task TEA-3): read-only access to what is published on TEA at
     * the organization, a perspective, a product or a component. Organization-wide NONE or Read Only,
     * read-only cards, and no functions, approvals, boards, instances or clusters: the server forces
     * every grant to READ_ONLY with TEA_READ.
     */
    externalKey?: boolean
    modelValue: {
        orgPermission: OrgPermission
        scopedPermissions: ScopedPermission[]
    }
}

const props = withDefaults(defineProps<Props>(), {
    instances: () => [],
    clusters: () => [],
})
const emit = defineEmits(['update:modelValue'])
const store = useStore()
const installationType = computed(() => store.getters.myuser?.installationType)

const permissionTypesWithAdmin: string[] = constants.PermissionTypesWithAdmin
const permissionTypes: string[] = constants.PermissionTypes
const externalKeyOrgTypes: string[] = constants.ExternalKeyOrgPermissionTypes
// The TEA functions only where the backend serves TEA (editionPermissionFunctions).
const permissionFunctions = computed((): string[] => editionPermissionFunctions(constants.PermissionFunctions, installationType.value))
const essentialReadPermissionFunctions: string[] = constants.EssentialReadPermissionFunctions
const hasDevOps = computed(() => installationType.value !== 'OSS')
// An EXTERNAL key reads or reads nothing: the organization-wide radio offers only these two.
const orgTypeOptions = computed(() => props.externalKey ? externalKeyOrgTypes : permissionTypesWithAdmin)

const orgPermission = ref<OrgPermission>({
    type: 'NONE',
    functions: [],
    approvals: []
})

// DevOps Read/Write gate the instance / cluster surface, which exists on every
// non-OSS installation (SaaS, managed service, on-prem Pro); OSS hides them. At ESSENTIAL_READ we
// only expose the functions that explicitly support it (currently AGENT) —
// most org-wide functions are paired with READ_ONLY / READ_WRITE.
const orgPermissionFunctions = computed(() => permissionFunctions.value.filter(f =>
    f !== 'RESOURCE' &&
    (!props.allowedFunctions || props.allowedFunctions.includes(f)) &&
    (props.showSbomProbing || f !== 'SBOM_PROBING') &&
    (!props.showSbomProbing || f !== 'LIFECYCLE_UPDATE') &&
    (hasDevOps.value || (f !== 'DEVOPS_READ' && f !== 'DEVOPS_WRITE')) &&
    (orgPermission.value.type !== 'ESSENTIAL_READ' || essentialReadPermissionFunctions.includes(f))
))

// Perspective / Product / Component scopes never expose DEVOPS_*: those grants
// belong on cluster / instance scopes.
const scopedPermissionFunctions = computed(() => permissionFunctions.value.filter(f =>
    f !== 'RESOURCE' &&
    (!props.allowedFunctions || props.allowedFunctions.includes(f)) &&
    f !== 'FINDING_ANALYSIS_WRITE' &&
    f !== 'DEVOPS_READ' &&
    f !== 'DEVOPS_WRITE' &&
    (props.showSbomProbing || f !== 'SBOM_PROBING') &&
    (!props.showSbomProbing || f !== 'LIFECYCLE_UPDATE')
))

// Products and components never carry the board functions: a grant there means nothing to a board.
// A perspective does -- it covers the boards hanging off it -- so it keeps the list above.
const componentScopedFunctions = computed(() => scopedPermissionFunctions.value.filter(f => !isBoardFunction(f)))

// A board card offers the board functions and the configuration pair a board file needs.
const boardFunctions = computed(() => boardScopeFunctions(props.allowedFunctions))

// The boards to grant on: the prop when given, else the org's boards read here.
const loadedBoards = ref<any[]>([])
const boardList = computed(() => props.boards ?? loadedBoards.value)
watch(() => props.orgUuid, async (org: string) => {
    if (props.boards || props.externalKey || !org) return
    try {
        loadedBoards.value = await store.dispatch('fetchAgentBoardNamesOfOrg', org) ?? []
    } catch {
        // the picker is empty; a grant already there still shows, by uuid
        loadedBoards.value = []
    }
}, { immediate: true })

// Cluster / instance grants are DevOps-only. The visible function set tracks
// the radio's permission type: READ_ONLY exposes (and pre-selects) DEVOPS_READ,
// READ_WRITE exposes (and pre-selects) both. NONE hides the section.
function instanceFunctionsFor (type: string): string[] {
    if (type === 'READ_ONLY') return ['DEVOPS_READ']
    if (type === 'READ_WRITE') return ['DEVOPS_READ', 'DEVOPS_WRITE']
    return []
}

const newPerspectiveId = ref<string | null>(null)
const newProductId = ref<string | null>(null)
const newComponentId = ref<string | null>(null)
const newInstanceId = ref<string | null>(null)
const newClusterId = ref<string | null>(null)
const newBoardId = ref<string | null>(null)
const orgFunctionPermissionTypes = ['ESSENTIAL_READ', 'READ_ONLY', 'READ_WRITE']
const previousOrgPermissionType = ref<string>(orgPermission.value.type)

const scopedPermissions = ref<ScopedPermission[]>([])

// Guard to prevent infinite loop: watch sets local state, emitUpdate sets parent state
let isUpdatingFromParent = false

// Initialize from modelValue
watch(() => props.modelValue, (val: Props['modelValue']) => {
    if (val && !isUpdatingFromParent) {
        isUpdatingFromParent = true
        orgPermission.value = { ...val.orgPermission }
        previousOrgPermissionType.value = orgPermission.value.type
        scopedPermissions.value = val.scopedPermissions.map(sp => ({ ...sp }))
        isUpdatingFromParent = false
    }
}, { immediate: true, deep: true })

const scopedPerspectivePermissions = computed(() =>
    scopedPermissions.value.filter(sp => sp.scope === 'PERSPECTIVE')
)

const scopedBoardPermissions = computed(() =>
    scopedPermissions.value.filter(sp => sp.scope === 'BOARD')
)

const availableBoardOptions = computed(() => {
    const usedIds = new Set(scopedBoardPermissions.value.map(sp => sp.objectId))
    return boardList.value
        .filter((b: any) => !usedIds.has(b.uuid))
        .map((b: any) => ({ label: b.name, value: b.uuid }))
})

const productIds = computed(() => new Set(props.products.map((p: any) => p.uuid)))

const scopedProductPermissions = computed(() =>
    scopedPermissions.value.filter(sp => sp.scope === 'COMPONENT' && productIds.value.has(sp.objectId))
)

const scopedComponentPermissions = computed(() =>
    scopedPermissions.value.filter(sp => sp.scope === 'COMPONENT' && !productIds.value.has(sp.objectId))
)

// INSTANCE-scoped grants split into two visual buckets keyed off the
// underlying object's instanceType: per-instance (STANDALONE_INSTANCE,
// CLUSTER_INSTANCE) and per-cluster (CLUSTER). Both are scope=INSTANCE
// on the wire.
const clusterIds = computed(() => new Set(props.clusters.map((c: any) => c.uuid)))

const scopedInstancePermissions = computed(() =>
    scopedPermissions.value.filter(sp => sp.scope === 'INSTANCE' && !clusterIds.value.has(sp.objectId))
)

const scopedClusterPermissions = computed(() =>
    scopedPermissions.value.filter(sp => sp.scope === 'INSTANCE' && clusterIds.value.has(sp.objectId))
)

const availablePerspectiveOptions = computed(() => {
    const usedIds = new Set(scopedPerspectivePermissions.value.map(sp => sp.objectId))
    return props.perspectives
        .filter(p => !usedIds.has(p.uuid))
        .map(p => ({ label: p.name, value: p.uuid }))
})

const availableProductOptions = computed(() => {
    const usedIds = new Set(scopedProductPermissions.value.map(sp => sp.objectId))
    return props.products
        .filter(p => !usedIds.has(p.uuid))
        .map(p => ({ label: p.name, value: p.uuid }))
})

const availableComponentOptions = computed(() => {
    const usedIds = new Set(scopedComponentPermissions.value.map(sp => sp.objectId))
    return props.components
        .filter(c => !usedIds.has(c.uuid))
        .map(c => ({ label: c.name, value: c.uuid }))
})

const availableInstanceOptions = computed(() => {
    const usedIds = new Set(scopedInstancePermissions.value.map(sp => sp.objectId))
    return props.instances
        .filter(i => !usedIds.has(i.uuid))
        .map(i => ({
            label: instanceLabel(i),
            value: i.uuid,
        }))
})

const availableClusterOptions = computed(() => {
    const usedIds = new Set(scopedClusterPermissions.value.map(sp => sp.objectId))
    return props.clusters
        .filter(c => !usedIds.has(c.uuid))
        .map(c => ({ label: c.name || c.uri || c.uuid, value: c.uuid }))
})

function instanceLabel(inst: any): string {
    // Show a human-readable label. STANDALONE_INSTANCE has a uri,
    // CLUSTER_INSTANCE has a parent cluster (we look it up by walking
    // the clusters prop since the row itself doesn't carry the cluster
    // name) and a namespace.
    if (inst.instanceType === 'CLUSTER_INSTANCE') {
        const parent = props.clusters.find((c: any) => Array.isArray(c.instances) && c.instances.includes(inst.uuid))
        const parentName = parent ? parent.name : '(cluster)'
        return `${parentName} / ${inst.namespace || inst.uuid}`
    }
    return inst.uri || inst.name || inst.uuid
}

function translatePermissionName(type: string): string {
    return commonFunctions.translatePermissionName(type)
}

function translateFunctionName(fn: string): string {
    return commonFunctions.translateFunctionName(fn)
}

function onOrgFunctionsUpdate(val: string[]) {
    orgPermission.value.functions = val
    emitUpdate()
}

function onOrgPermissionTypeUpdate(type: string) {
    const wasFunctionType = orgFunctionPermissionTypes.includes(previousOrgPermissionType.value)
    const isFunctionType = orgFunctionPermissionTypes.includes(type)

    orgPermission.value.type = type
    if (wasFunctionType && !isFunctionType) {
        orgPermission.value.functions = []
    } else if (type === 'ESSENTIAL_READ') {
        // ESSENTIAL_READ exposes a narrower function set than READ_ONLY /
        // READ_WRITE. Drop any previously-checked function that isn't valid
        // at this tier so the saved permission matches what the UI shows.
        orgPermission.value.functions = orgPermission.value.functions.filter(
            f => essentialReadPermissionFunctions.includes(f)
        )
    }

    previousOrgPermissionType.value = type
    emitUpdate()
}

function onFunctionsUpdate(val: string[], sp: ScopedPermission) {
    sp.functions = val
    emitUpdate()
}

// Cluster / instance grants auto-track DevOps functions to the chosen
// permission type. NONE clears them; READ_ONLY pre-selects DEVOPS_READ;
// READ_WRITE pre-selects DEVOPS_READ + DEVOPS_WRITE.
function onInstancePermissionTypeUpdate(type: string, sp: ScopedPermission) {
    sp.type = type
    sp.functions = instanceFunctionsFor(type)
    emitUpdate()
}

function addScopedPermission(scope: string) {
    let id: string | null = null
    let source: any[] = []
    if (scope === 'PERSPECTIVE') {
        id = newPerspectiveId.value
        source = props.perspectives
    } else if (scope === 'PRODUCT') {
        id = newProductId.value
        source = props.products
    } else if (scope === 'INSTANCE') {
        id = newInstanceId.value
        source = props.instances
    } else if (scope === 'CLUSTER') {
        id = newClusterId.value
        source = props.clusters
    } else if (scope === 'BOARD') {
        id = newBoardId.value
        source = boardList.value
    } else {
        id = newComponentId.value
        source = props.components
    }
    if (!id) return

    const obj = source.find((o: any) => o.uuid === id)
    if (!obj) return

    // PRODUCT and INSTANCE/CLUSTER are visual buckets; on the wire
    // PRODUCT goes through scope=COMPONENT and CLUSTER goes through
    // scope=INSTANCE (with the cluster's UUID). The grouping computed
    // refs split them back out by checking the underlying object pool.
    let wireScope = scope
    if (scope === 'PRODUCT') wireScope = 'COMPONENT'
    else if (scope === 'CLUSTER') wireScope = 'INSTANCE'

    // Cluster / instance grants seed DEVOPS_READ at creation so the radio's
    // initial READ_ONLY state matches what the user sees in the function row.
    const initialFunctions = wireScope === 'INSTANCE' ? instanceFunctionsFor('READ_ONLY') : []

    scopedPermissions.value.push({
        scope: wireScope,
        objectId: id,
        objectName: obj.name || obj.uri || obj.uuid,
        type: 'READ_ONLY',
        functions: initialFunctions,
        approvals: []
    })

    if (scope === 'PERSPECTIVE') {
        newPerspectiveId.value = null
    } else if (scope === 'PRODUCT') {
        newProductId.value = null
    } else if (scope === 'INSTANCE') {
        newInstanceId.value = null
    } else if (scope === 'CLUSTER') {
        newClusterId.value = null
    } else if (scope === 'BOARD') {
        newBoardId.value = null
    } else {
        newComponentId.value = null
    }
    emitUpdate()
}

function removeScopedPermission(scope: string, objectId: string) {
    scopedPermissions.value = scopedPermissions.value.filter(
        sp => !(sp.scope === scope && sp.objectId === objectId)
    )
    emitUpdate()
}

function emitUpdate() {
    emit('update:modelValue', {
        orgPermission: { ...orgPermission.value },
        scopedPermissions: scopedPermissions.value.map(sp => ({ ...sp }))
    })
}
</script>
