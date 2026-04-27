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
                    v-for="pt in permissionTypesWithAdmin"
                    :key="pt"
                    :value="pt"
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
        <n-space style="margin-bottom: 20px;" v-if="orgPermission.type !== 'ADMIN' && orgPermission.type !== 'NONE' && orgPermission.type !== 'ESSENTIAL_READ'">
            <n-h5>
                <n-text depth="1">
                    Organization-Wide Functions:
                </n-text>
            </n-h5>
            <n-checkbox-group v-model:value="orgPermission.functions" @update:value="onOrgFunctionsUpdate">
                <n-checkbox v-for="f in orgPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                    <span v-if="f === 'FINDING_ANALYSIS_WRITE'" style="display: inline-flex; align-items: center;">
                        {{ translateFunctionName(f) }}
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-icon size="16" style="margin-left: 4px;">
                                    <QuestionCircle20Regular />
                                </n-icon>
                            </template>
                            Requires "Finding Analysis Read" function to be able to view existing finding records.
                        </n-tooltip>
                    </span>
                    <span v-else-if="f === 'LIFECYCLE_UPDATE'" style="display: inline-flex; align-items: center;">
                        {{ translateFunctionName(f) }}
                        <n-tooltip trigger="hover">
                            <template #trigger>
                                <n-icon size="16" style="margin-left: 4px;">
                                    <QuestionCircle20Regular />
                                </n-icon>
                            </template>
                            Requires Read &amp; Write permission to take effect - granting this function to a Read Only user will not allow them to change lifecycle.
                        </n-tooltip>
                    </span>
                    <span v-else>
                        {{ translateFunctionName(f) }}
                    </span>
                </n-checkbox>
            </n-checkbox-group>
        </n-space>

        <!-- Organization-Wide Approvals -->
        <n-space style="margin-bottom: 20px;" v-if="orgPermission.type !== 'ADMIN' && orgPermission.type !== 'NONE' && approvalRoles && approvalRoles.length">
            <n-h5>
                <n-text depth="1">
                    Organization-Wide Approval Permissions:
                </n-text>
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
                        <n-radio-group v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                    <span v-if="f === 'LIFECYCLE_UPDATE'" style="display: inline-flex; align-items: center;">
                                        {{ translateFunctionName(f) }}
                                        <n-tooltip trigger="hover">
                                            <template #trigger>
                                                <n-icon size="16" style="margin-left: 4px;">
                                                    <QuestionCircle20Regular />
                                                </n-icon>
                                            </template>
                                            Requires Read &amp; Write permission to take effect - granting this function to a Read Only user will not allow them to change lifecycle.
                                        </n-tooltip>
                                    </span>
                                    <span v-else>{{ translateFunctionName(f) }}</span>
                                </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE' && approvalRoles && approvalRoles.length">
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
                        <n-radio-group v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                    <span v-if="f === 'LIFECYCLE_UPDATE'" style="display: inline-flex; align-items: center;">
                                        {{ translateFunctionName(f) }}
                                        <n-tooltip trigger="hover">
                                            <template #trigger>
                                                <n-icon size="16" style="margin-left: 4px;">
                                                    <QuestionCircle20Regular />
                                                </n-icon>
                                            </template>
                                            Requires Read &amp; Write permission to take effect - granting this function to a Read Only user will not allow them to change lifecycle.
                                        </n-tooltip>
                                    </span>
                                    <span v-else>{{ translateFunctionName(f) }}</span>
                                </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE' && approvalRoles && approvalRoles.length">
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
                        <n-radio-group v-model:value="sp.type" size="small" @update:value="emitUpdate">
                            <n-radio-button v-for="pt in permissionTypes" :key="pt" :value="pt" :label="translatePermissionName(pt)" />
                        </n-radio-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE'">
                        <n-text depth="3" style="font-size: 12px;">Functions:</n-text>
                        <n-checkbox-group v-model:value="sp.functions" @update:value="onFunctionsUpdate($event, sp)">
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
                                    <span v-if="f === 'LIFECYCLE_UPDATE'" style="display: inline-flex; align-items: center;">
                                        {{ translateFunctionName(f) }}
                                        <n-tooltip trigger="hover">
                                            <template #trigger>
                                                <n-icon size="16" style="margin-left: 4px;">
                                                    <QuestionCircle20Regular />
                                                </n-icon>
                                            </template>
                                            Requires Read &amp; Write permission to take effect - granting this function to a Read Only user will not allow them to change lifecycle.
                                        </n-tooltip>
                                    </span>
                                    <span v-else>{{ translateFunctionName(f) }}</span>
                                </n-checkbox>
                        </n-checkbox-group>
                    </n-space>
                    <n-space style="margin-top: 8px;" align="center" v-if="sp.type !== 'NONE' && approvalRoles && approvalRoles.length">
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

        <!-- Per-Cluster Permissions (scope=INSTANCE on a CLUSTER row) -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="orgPermission.type !== 'ADMIN' && clusters.length">
            <n-h5>
                <n-text depth="1">
                    Per-Cluster Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="orgPermission.type !== 'ADMIN' && clusters.length">
            <n-space vertical>
                <n-card v-for="sp in scopedClusterPermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('INSTANCE', sp.objectId)"><CloseIcon /></n-icon>
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
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
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

        <!-- Per-Instance Permissions (scope=INSTANCE on a STANDALONE_INSTANCE / CLUSTER_INSTANCE row) -->
        <n-space style="margin-top: 20px; margin-bottom: 10px;" v-if="orgPermission.type !== 'ADMIN' && instances.length">
            <n-h5>
                <n-text depth="1">
                    Per-Instance Permissions:
                </n-text>
            </n-h5>
        </n-space>
        <div v-if="orgPermission.type !== 'ADMIN' && instances.length">
            <n-space vertical>
                <n-card v-for="sp in scopedInstancePermissions" :key="sp.objectId" size="small" style="margin-bottom: 8px;">
                    <n-space align="center" justify="space-between" style="width: 100%;">
                        <n-text strong>{{ sp.objectName }}</n-text>
                        <n-icon class="clickable" size="18" @click="removeScopedPermission('INSTANCE', sp.objectId)"><CloseIcon /></n-icon>
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
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :title="translateFunctionName(f)">
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
const permissionFunctions: string[] = constants.PermissionFunctions
const orgPermissionFunctions = computed(() => permissionFunctions.filter(f => f !== 'RESOURCE' && (props.showSbomProbing || f !== 'SBOM_PROBING') && (!props.showSbomProbing || f !== 'LIFECYCLE_UPDATE')))
const scopedPermissionFunctions = computed(() => permissionFunctions.filter(f => f !== 'RESOURCE' && f !== 'FINDING_ANALYSIS_WRITE' && (props.showSbomProbing || f !== 'SBOM_PROBING') && (!props.showSbomProbing || f !== 'LIFECYCLE_UPDATE')))

const newPerspectiveId = ref<string | null>(null)
const newProductId = ref<string | null>(null)
const newComponentId = ref<string | null>(null)
const newInstanceId = ref<string | null>(null)
const newClusterId = ref<string | null>(null)

const orgPermission = ref<OrgPermission>({
    type: 'NONE',
    functions: [],
    approvals: []
})
const orgFunctionPermissionTypes = ['READ_ONLY', 'READ_WRITE']
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
    }

    previousOrgPermissionType.value = type
    emitUpdate()
}

function onFunctionsUpdate(val: string[], sp: ScopedPermission) {
    sp.functions = val
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

    scopedPermissions.value.push({
        scope: wireScope,
        objectId: id,
        objectName: obj.name || obj.uri || obj.uuid,
        type: 'READ_ONLY',
        functions: [],
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
