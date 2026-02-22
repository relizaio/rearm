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
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :label="translateFunctionName(f)" :title="translateFunctionName(f)" />
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
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :label="translateFunctionName(f)" :title="translateFunctionName(f)" />
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
                            <n-checkbox v-for="f in scopedPermissionFunctions" :key="f" :value="f" :label="translateFunctionName(f)" :title="translateFunctionName(f)" />
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
    modelValue: {
        orgPermission: OrgPermission
        scopedPermissions: ScopedPermission[]
    }
}

const props = defineProps<Props>()
const emit = defineEmits(['update:modelValue'])
const store = useStore()
const installationType = computed(() => store.getters.myuser?.installationType)

const permissionTypesWithAdmin: string[] = constants.PermissionTypesWithAdmin
const permissionTypes: string[] = constants.PermissionTypes
const permissionFunctions: string[] = constants.PermissionFunctions
const orgPermissionFunctions = computed(() => permissionFunctions.filter(f => f !== 'RESOURCE'))
const scopedPermissionFunctions = computed(() => permissionFunctions.filter(f => f !== 'RESOURCE' && f !== 'FINDING_ANALYSIS_WRITE'))

const newPerspectiveId = ref<string | null>(null)
const newProductId = ref<string | null>(null)
const newComponentId = ref<string | null>(null)

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
    } else {
        id = newComponentId.value
        source = props.components
    }
    if (!id) return

    const obj = source.find((o: any) => o.uuid === id)
    if (!obj) return

    scopedPermissions.value.push({
        scope: scope === 'PRODUCT' ? 'COMPONENT' : scope,
        objectId: id,
        objectName: obj.name,
        type: 'READ_ONLY',
        functions: [],
        approvals: []
    })

    if (scope === 'PERSPECTIVE') {
        newPerspectiveId.value = null
    } else if (scope === 'PRODUCT') {
        newProductId.value = null
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
