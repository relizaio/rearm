<template>
    <div>
        <h4>Secrets</h4>
        <vue-feather id="addSecret" v-if="userPermission === 'ADMIN' || userPermission === 'READ_WRITE'"
            @click="showCreateSecretModal = true" class="clickable" type="plus-circle" title="Add New Secret" />
        <n-data-table 
            :data="secrets"
            :columns="secretsFields"
            :single-line="false"
        />

        <n-modal
            v-model:show="showCreateSecretModal"
            preset="dialog"
            :show-icon="false">
            <n-card style="width: 600px" size="huge" title="Create Secret" :borderd="false" role="dialog" aria-modal="true">
                <n-form >
                    <n-form-item id="secret_create_name_group" label="Name" label-for="secret_create_name"
                        description="Unique name of the secret">
                        <n-input id="secret_create_name" v-model:value="secretToCreate.name" required
                            placeholder="Enter unique secret name" />
                    </n-form-item>
                    <n-form-item id="secret_create_description_group" label="Description"
                        label-for="secret_create_description" description="Description of the secret">
                        <n-input id="secret_create_description" v-model:value="secretToCreate.description"
                            placeholder="Enter secret description" />
                    </n-form-item>
                    <n-form-item id="secret_create_pt_group" label="Secret" label-for="secret_create_secret"
                        description="Secret itself">
                        <n-input id="secret_create_secret" v-model:value="secretToCreate.ptSecret" type="password" required
                            placeholder="Enter secret itself" />
                    </n-form-item>
                    <n-button @click="createSecret" type="success">Submit</n-button>
                    <n-button @click="onCreateSecretReset" type="error">Reset</n-button>
                </n-form>
            </n-card>
        </n-modal>
        <n-modal
            v-model:show="showEditSecretModal"
            preset="dialog"
            :show-icon="false" >
            <n-card style="width: 600px" size="huge" :title="'Edit Secret - ' + secretToEdit.name" :borderd="false"
                role="dialog" aria-modal="true">
                <n-form  @reset="onEditSecretReset">
                    <n-form-item id="secret_edit_description_group" label="Description" label-for="secret_edit_description"
                        description="Description of the secret">
                        <n-input id="secret_edit_description" v-model:value="secretToEdit.description"
                            placeholder="Enter secret description" />
                    </n-form-item>
                    <n-form-item id="secret_edit_pt_group" label="Secret" label-for="secret_edit_secret"
                        description="Secret itself">
                        <n-input id="secret_edit_secret" v-model:value="secretToEdit.ptSecret" type="password" placeholder="Not Changed" />
                    </n-form-item>
                    <n-button @click="updateSecret" type="success">Submit</n-button>
                    <n-button @click="onEditSecretReset" type="error">Reset</n-button>
                </n-form>
            </n-card>
        </n-modal>
        <n-modal
            v-model:show="showEditSecretDistribution"
            :hide-footer="true"
            preset="dialog"
            :show-icon="false">
            <n-card :title="'Secret Distribution Permissions for ' + selectedSecret.name" style="width: 600px" size="huge"
                :borderd="false" role="dialog" aria-modal="true">
                <n-form >
                    <n-grid :x-gap="12" :cols="2">
                        <n-gi>
                            <span>Instance Permissions</span>
                            <div v-for="i in instances" :key="i.uuid">
                                <n-form-item :span="12" id="update_secret_distribution_instance_group" :label="i.uri">
                                    <n-select :id="'update_secret_distribution_instance_select_' + i.uuid"
                                    v-model:value="instancePermissions[i.uuid]" :options="permissionTypes" />
                                </n-form-item>
                            </div>
                        </n-gi>
                        <n-gi>
                            <span>Cluster Permissions</span>
                            <div v-for="i in clusters" :key="i.uuid">
                                <n-form-item :span="12" id="update_secret_distribution_instance_group" :label="i.name">
                                    <n-select :id="'update_secret_distribution_instance_select_' + i.uuid"
                                    v-model:value="instancePermissions[i.uuid]" :options="permissionTypes" />
                                </n-form-item>
                            </div>
                        </n-gi>
                    </n-grid>  
                    <n-button @click="updateSecretDistribution" type="success">Submit</n-button>
                    <n-button type="error">Reset</n-button>
                </n-form>
            </n-card>
        </n-modal>

    </div>
</template>
  
<script lang="ts" setup>
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import Swal from 'sweetalert2'
import { NInput, NModal, NCard, NForm, NButton, NFormItem, NSelect, NotificationType, useNotification, NDataTable, NIcon, NGrid, NGi } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { VectorBezier, Edit as EditIcon, Trash } from '@vicons/tabler'
import { Icon } from '@vicons/utils'
import commonFunctions from '@/utils/commonFunctions'
import constants from '../utils/constants'


const route = useRoute()
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

type Permissions = {
    permissions: Permission[]
}
type Permission = {
    org?: string,
    scope?: string,
    object?: string,
    type?: string,
    meta?: string
}
type Secret = {
    uuid: string,
    name: string,
    description: string,
    permissions?: Permissions,
    ptSecret: string
}

const emptySecret: Secret = {
    uuid: '',
    name: '',
    description: '',
    ptSecret: ''
}

const secrets = ref<Secret[]>()
const showCreateSecretModal = ref(false)
const showEditSecretModal = ref(false)
const showEditSecretDistribution = ref(false)
const instancePermissions: Ref<any> = ref({})
const orgResolved: Ref<string> = ref('')
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
if (route.params.orguuid) {
    orgResolved.value = route.params.orguuid.toString()
} else {
    orgResolved.value = myorg.value.uuid
}
const permissionTypes: ComputedRef<any> = computed((): any => {
    let ptArray = ['NONE', 'READ_ONLY']
    let retSelection: { label: string; value: string; }[] = []
    ptArray.forEach(el => {
        let retObj = {
            label: el,
            value: el
        }
        retSelection.push(retObj)
    })
    return retSelection
})
if (secrets.value === undefined || secrets.value.length < 1) {
    loadSecrets()
    store.dispatch('fetchInstances', orgResolved.value)
}
const secretToCreate = ref<Secret>(commonFunctions.deepCopy(emptySecret))
const secretToEdit = ref<Secret>(commonFunctions.deepCopy(emptySecret))
const selectedSecret = ref<Secret>(commonFunctions.deepCopy(emptySecret))

const props = defineProps<{
    orguuid?: string
}>()

const secretsFields: any[] = [
    {
        key: 'name',
        title: 'Name'
    },
    {
        key: 'description',
        title: 'Description'
    },
    {
        key: 'controls',
        title: 'Actions',
        render: (row: any) => {
            return  h(
                'div',
                [
                    h(
                        NIcon, 
                        {
                            title: 'Edit Secret',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => editSecret(row.uuid)
                        }, 
                        () => h(EditIcon)
                    ),
                    h(
                        NIcon, 
                        {
                            title: 'Secret Distribution',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => editSecretDistribution(row.uuid)
                        }, 
                        () => h(VectorBezier)
                    ),
                    h(
                        NIcon, 
                        {
                            title: 'Delete Secret',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => deleteSecret(row.uuid)
                        }, 
                        () => h(Trash)
                    ),
                    
                ]
            )

        }
    },
]

async function createSecret() {
    let secretCreateObj = {
        org: orgResolved.value,
        name: secretToCreate.value?.name,
        description: secretToCreate.value?.description,
        ptSecret: window.btoa(secretToCreate.value?.ptSecret ?? '')
    }

    await graphqlClient.query({
        query: gql`
            mutation createSecret($secret: SecretInput!) {
                createSecret(secret: $secret) {
                    uuid
                }
        }`,
        variables: {
            secret: secretCreateObj
        },
        fetchPolicy: 'no-cache'
    })
    showCreateSecretModal.value = false
    onCreateSecretReset()
    loadSecrets()
}
function editSecretDistribution(secretId: string) {
    instancePermissions.value = {}
    const secret = secrets.value?.find(s => (s.uuid === secretId))
    if(secret && secret.uuid) selectedSecret.value = secret
    selectedSecret.value?.permissions?.permissions?.forEach(up => {
        if (up.scope === 'INSTANCE' && up.org === orgResolved.value && up.object !== undefined && up.type !== undefined) {
            instancePermissions.value[up.object] = up.type
        }
    })
    showEditSecretDistribution.value = true
}
function editSecret(secretId: string) {
    // locate secret

    let secret = secrets.value?.filter(s => (s.uuid === secretId))
    // selectedSecret = secret[0]
    // locate instance permissions
    secretToEdit.value.uuid = secret === undefined ? '' : secret[0].uuid
    secretToEdit.value.name = secret === undefined ? '' : secret[0].name
    secretToEdit.value.description = secret === undefined ? '' : secret[0].description
    secretToEdit.value.ptSecret = ''
    showEditSecretModal.value = true
}
async function updateSecret() {
    let secretupdateObj = {
        org: orgResolved.value,
        description: secretToEdit.value?.description,
        ptSecret: secretToEdit.value?.ptSecret === '' || secretToEdit.value?.ptSecret === null || secretToEdit.value?.ptSecret === undefined ? null : window.btoa(secretToEdit.value.ptSecret)
    }
    await graphqlClient.mutate({
        mutation: gql`
                mutation updateSecretDetails($secret: SecretUpdateInput!) {
                    updateSecretDetails(secretUuid: "${secretToEdit.value?.uuid}", secret: $secret) {
                        uuid
                    }
                }`,
        variables: {
            secret: secretupdateObj
        },
        fetchPolicy: 'no-cache'
    })
    showEditSecretModal.value = false
    onEditSecretReset()
    loadSecrets()
}
async function deleteSecret(secretId: string) {
    // locate secret
    let secret = secrets.value?.filter(s => (s.uuid === secretId))
    selectedSecret.value = secret === undefined ? commonFunctions.deepCopy(emptySecret) : secret[0]
    Swal.fire({
        title: `Are you sure you want to delete the ${selectedSecret.value.name} secret?`,
        text: `If you proceed, the secret will be deleted and you will not have access to its data.`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, delete!',
        cancelButtonText: 'No, cancel'
    }).then(async result => {
        if (result.value) {
            await graphqlClient.mutate({
                mutation: gql`
                        mutation {
                            deleteSecret(secretUuid: "${selectedSecret.value?.uuid}", orgUuid: "${orgResolved.value}")
                        }`,
                fetchPolicy: 'no-cache'
            })
            loadSecrets()
        } else if (result.dismiss === Swal.DismissReason.cancel) {
            Swal.fire(
                'Cancelled',
                `Delete cancelled. Your secret is still active.`,
                'info'
            )
        }
    })
}
function onEditSecretReset() {
    secretToEdit.value = {
        uuid: '',
        name: '',
        description: '',
        ptSecret: ''
    }
}

async function loadSecrets() {
    const response = await graphqlClient.query({
        query: gql`
                query secrets($orgUuid: ID!) {
                    secrets(orgUuid: $orgUuid) {
                        uuid
                        name
                        description
                        permissions {
                            permissions {
                                org
                                scope
                                object
                                type
                                meta
                            }
                        }
                    }
                }`,
        variables: {
            orgUuid: orgResolved.value
        },
        fetchPolicy: 'no-cache'
    })
    let sortedSecrets = response.data.secrets
    sortedSecrets.sort((a: Secret, b: Secret) => {
        if (a.name < b.name) {
            return -1
        } else {
            return 1
        }
    })
    secrets.value = sortedSecrets
}

function onCreateSecretReset() {
    secretToCreate.value = commonFunctions.deepCopy(emptySecret)
}
async function updateSecretDistribution() {
    let permissions: any = []
    if (instancePermissions.value && Object.keys(instancePermissions.value).length) {
        Object.keys(instancePermissions.value).forEach(inst => {
            let perm = {
                org: orgResolved.value,
                scope: 'INSTANCE',
                type: instancePermissions.value[inst],
                object: inst
            }
            permissions.push(perm)
        })
    }
    try {
        const gqlResp = await graphqlClient.mutate({
            mutation: gql`
                mutation updateSecretDistribution($permissions: [PermissionInput]) {
                    updateSecretDistribution(orgUuid: "${orgResolved.value}", secretUuid: "${selectedSecret.value?.uuid}",
                        permissions: $permissions) {
                        uuid
                        name
                        description
                        permissions {
                            permissions {
                                org
                                scope
                                object
                                type
                                meta
                            }
                        }
                    }
                }`,
            variables: {
                'permissions': permissions
            }
        })
        // update secret with the one from response
        await loadSecrets()
        showEditSecretDistribution.value = false
        notify('success', 'Saved', 'Saved Secret Distribution Successfully!')
    } catch (error) {
        notify('error', 'Error', error.response.data.message)
    }
    
}

const userPermission = ref('')
userPermission.value = commonFunctions.getUserPermission(orgResolved.value, store.getters.myuser).org
const InstanceType = constants.InstanceType
type Instance = { uri: string, name: string, uuid: string, revision: number, instanceType: string }
const instances: ComputedRef<[Instance]> = computed((): [Instance] => {
    let storeInstances = store.getters.instancesOfOrg(orgResolved.value)
    // add all instances and all spawned instances ticker
    storeInstances.push({ uri: 'All Instances', uuid: '00000000-0000-0000-0000-000000000000', revision: -1 })
    storeInstances.push({ uri: 'All Reliza Spawned Instances', uuid: '00000000-0000-0000-0000-000000000001', revision: -1 })
    if (storeInstances && storeInstances.length) {
        storeInstances = storeInstances.filter((x: Instance) => x.revision === -1 && x.instanceType !== InstanceType.CLUSTER)
        // sort - TODO make sort configurable
        storeInstances.sort((a: Instance, b: Instance) => {
            if (a.uri.toLowerCase() < b.uri.toLowerCase()) {
                return -1
            } else if (a.uri.toLowerCase() > b.uri.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    return storeInstances

})
const clusters: ComputedRef<[Instance]> = computed((): [Instance] => {
    let storeInstances = store.getters.instancesOfOrg(orgResolved.value)
    // add all instances and all spawned instances ticker
    if (storeInstances && storeInstances.length) {
        storeInstances = storeInstances.filter((x: Instance) => x.revision === -1 && x.instanceType === InstanceType.CLUSTER)
        // sort - TODO make sort configurable
        storeInstances.sort((a: Instance, b: Instance) => {
            if (a.uri.toLowerCase() < b.uri.toLowerCase()) {
                return -1
            } else if (a.uri.toLowerCase() > b.uri.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    storeInstances = [{ name: 'All Clusters', uuid: '00000000-0000-0000-0000-000000000003', revision: -1 }, ...storeInstances]
    return storeInstances

})

</script>
  
<style scoped lang="scss">
.secretList {
    display: grid;
    grid-template-columns: 1fr 2fr 120px;
    border-radius: 9px;

    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 8px;
    }
}

.secretList:hover {
    background-color: #d9eef3;
}

.secretHeader {
    background-color: #f9dddd;
    font-weight: bold;
}
</style>
  