<template>
    <div class="home">
        <h2>System Settings</h2>

        <div v-if="myUser && myUser.isGlobalAdmin" class="nameBlock mt-4">
           
        
            <div v-if="false" class="awsBlock">
                <h4 class="mt-4">AWS Credentials: </h4>
                
                <div v-if="systemInfoIsSet && systemInfoIsSet.awsCredentialsIsSet">Aws Credentials Configured
                    <vue-feather @click="showAwsCredentialsModal  = true" class="clickable" type="edit" title="Change AWS Credentials"/>
                </div>
                <div v-else><n-button @click="showAwsCredentialsModal = true">Add Aws Credentials</n-button></div>
                
                <n-modal v-model:show="showAwsCredentialsModal" preset="dialog" :show-icon="false" style="width: 90%"
                    title='Add Aws Credentials' :hide-footer="true">
                    <n-form>
                        <n-form-item label='Access Key Id:'>
                            <n-input type="password" id="aws-access-key-id" password v-model:value="awsCredentials.accessKeyId" required
                                placeholder="Access Key Id" />
                        </n-form-item>
                        <n-form-item label='Secret Access Key:'>
                            <n-input type="password" id="aws-secret-access-key" password v-model:value="awsCredentials.secretAccessKey" required
                                placeholder="Secret Access Key" />
                        </n-form-item>
                        <n-button type="success" @click="setAwsCredentials">Submit</n-button>
                        <n-button type="error">Reset</n-button>
                    </n-form>
                </n-modal>
            </div>
            <div v-if="false" class="azureBlock">
                <h4 class="mt-4">Azure Credentials: </h4>
                
                <div v-if="systemInfoIsSet && systemInfoIsSet.azureCredentialsIsSet">Azure Credentials Configured
                    <vue-feather @click="showAzureCredentialsModal  = true" class="clickable" type="edit" title="Change AWS Credentials"/>
                </div>
                <div v-else><n-button @click="showAzureCredentialsModal = true">Add Azure Credentials</n-button></div>
                
                <n-modal v-model:show="showAzureCredentialsModal" preset="dialog" :show-icon="false" style="width: 90%"
                    title='Add Azure Credentials' :hide-footer="true">
                    <n-form>
                        <n-form-item label='Client Id:'>
                            <n-input type="password" id="azure-client-id" password v-model:value="azureCredentials.clientId" required
                                placeholder="Client Id" />
                        </n-form-item>
                        <n-form-item label='Client Secret:'>
                            <n-input type="password" id="azure-client-secret" password v-model:value="azureCredentials.clientSecret" required
                                placeholder="Client Secret" />
                        </n-form-item>
                        <n-form-item label='Tenant Id:'>
                            <n-input type="password" id="azure-tenant-id" password v-model:value="azureCredentials.tenantId" required
                                placeholder="Tenant Id" />
                        </n-form-item>
                        <n-form-item label='Vault Name:'>
                            <n-input type="password" id="azure-vault-name" password v-model:value="azureCredentials.vaultName" required
                                placeholder="Vault Name" />
                        </n-form-item>
                        <n-button type="success" @click="setAzureCredentials">Submit</n-button>
                        <n-button type="error">Reset</n-button>
                    </n-form>
                </n-modal>
            </div>
            <div class="emailConfigurationBlock">
                <h4 class="mt-4">Email Sending Configuration: </h4>
                
                <div v-if="systemInfoIsSet && systemInfoIsSet.emailDetailsSet">Email Sending Configured
                    <vue-feather @click="showEmailPropsModal = true" class="clickable" type="edit" title="Change Email Sending Configuration"/>
                </div>
                <div v-else><n-button @click="showEmailPropsModal = true">Configure Sending Emails</n-button></div>
                
                <n-modal v-model:show="showEmailPropsModal" preset="dialog" :show-icon="false" style="width: 90%"
                    title='Configure Sending Emails' :hide-footer="true">
                    <n-form>
                        <n-form-item label='Email Provider:'>
                            <n-select v-model:value="emailProps.emailSendType"
                               :options="emailSendTypeOptions" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType !== 'UNSET'" label='From Email Address:'>
                            <n-input v-model:value="emailProps.fromEmail"
                                placeholder="From email" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SENDGRID'" label='SendGrid Key:'>
                            <n-input type="password" id="sendgridKey" password v-model:value="emailProps.sendGridKey"
                                placeholder="SendGrid Key" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SMTP'" label='SMTP User Name:'>
                            <n-input v-model:value="emailProps.smtpProps.userName"
                                placeholder="SMTP User Name" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SMTP'" label='SMTP Password:'>
                            <n-input type="password" password v-model:value="emailProps.smtpProps.password"
                                placeholder="SMTP Password" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SMTP'" label='SMTP Hostname:'>
                            <n-input v-model:value="emailProps.smtpProps.smtpHost"
                                placeholder="SMTP Host" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SMTP'" label='SMTP Hostname:'>
                            <n-input number clearable v-model:value.number="emailProps.smtpProps.port" placeholder="SMTP Port" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SMTP'" label='Require STARTTLS:'>
                            <n-select v-model:value="emailProps.smtpProps.isStarttls"
                               :options="[{label: 'Yes', value: true}, {label: 'No', value: false}]" />
                        </n-form-item>
                        <n-form-item v-if="emailProps.emailSendType === 'SMTP'" label='SSL on Connection:'>
                            <n-select v-model:value="emailProps.smtpProps.isSsl"
                               :options="[{label: 'Yes', value: true}, {label: 'No', value: false}]" />
                        </n-form-item>
                        <n-button type="success" @click="setEmailProps">Submit</n-button>
                        <n-button type="error">Reset</n-button>
                    </n-form>
                </n-modal>
            </div>
            <div class="defaultOrgBlock">
                <h4 class="mt-4">Default Organization: </h4>
                
                <div v-if="systemInfoIsSet && systemInfoIsSet.defaultOrgDetails && systemInfoIsSet.defaultOrgDetails.name"> {{ systemInfoIsSet.defaultOrgDetails.name }}
                    <vue-feather @click="fetchAllOrganizations(); showDefaultOrgModal = true" class="clickable" type="edit" title="Change Default Organization"/>
                </div>
                <div v-else><n-button @click="fetchAllOrganizations(); showDefaultOrgModal = true">Set Default Organization</n-button></div>
                
                <n-modal v-model:show="showDefaultOrgModal" preset="dialog" :show-icon="false" style="width: 90%"
                    title='Set Default Organization' :hide-footer="true">
                    <n-form>
                        <n-form-item label='Default Organization:'>
                            <n-select :options="allOrganizations" v-model:value="defaultOrganization" />
                        </n-form-item>
                        <n-button type="success" @click="setDefaultOrganization">Submit</n-button>
                    </n-form>
                </n-modal>
            </div>
            <div v-if="false" class="reefBlock">
                <h4 class="mt-4">Reef Details: </h4>
                
                <div v-if="systemInfoIsSet && systemInfoIsSet.reefTemplateId && systemInfoIsSet.reefSiloId">Reef Details Configured
                    <vue-feather @click="showReefModal = true" class="clickable" type="edit" title="Change Reef Details"/>
                </div>
                <div v-else><n-button @click="showReefModal = true">Add Reef Details</n-button></div>
                
                <n-modal v-model:show="showReefModal" preset="dialog" :show-icon="false" style="width: 90%"
                    title='Set Reef Details' :hide-footer="true">
                    <n-form>
                        <n-form-item label='Silo:'>
                            <n-input id="reef-silo-id" password v-model:value="reefSiloId" required
                                placeholder="Reef Silo Id" />
                        </n-form-item>
                        <n-form-item label='Template:'>
                            <n-input id="azure-client-secret" password v-model:value="reefTemplateId" required
                                placeholder="Reef Template Id" />
                        </n-form-item>
                        <n-button type="success" @click="setReefDetails">Submit</n-button>
                        <n-button type="error">Reset</n-button>
                    </n-form>
                </n-modal>
            </div>
        </div>

    </div>
</template>

<script lang="ts" setup>
// @ is an alias to /src
import { NInput, NSelect, NModal, NForm, NFormItem, NButton } from 'naive-ui'
import { ComputedRef, ref, Ref, computed, onMounted } from 'vue'
import { useStore } from 'vuex'
import commonFunctions from '@/utils/commonFunctions'
import Swal, { SweetAlertResult } from 'sweetalert2'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'


const store = useStore()

onMounted(async () => {
    await initLoad()
})

const systemInfoIsSet: Ref<any> = ref({})
const defaultOrganization = ref('')

async function initLoad() {
    // reload my user
    const userResp = await store.dispatch('fetchMyUser')
    const gqlResp = await graphqlClient.query({
        query: gql`
            query getSystemInfoIsSet {
                getSystemInfoIsSet {
                    emailDetailsSet
                    defaultOrg
                    defaultOrgDetails {
                        uuid
                        name
                    }
                }
            }
            `,
        fetchPolicy: 'no-cache'
    })
    systemInfoIsSet.value = gqlResp.data.getSystemInfoIsSet
    defaultOrganization.value = gqlResp.data.getSystemInfoIsSet.defaultOrg
}

const allOrganizations: Ref<any[]> = ref([])

async function fetchAllOrganizations () {
    const gqlResp = await graphqlClient.query({
        query: gql`
            query allOrganizations {
                allOrganizations {
                    uuid
                    name
                }
            }
            `
    })
    allOrganizations.value = gqlResp.data.allOrganizations.map((x: any) => { return {value: x.uuid, label: x.name}})
}

const showAwsCredentialsModal: Ref<boolean> = ref(false)
const awsCredentials: Ref<any> = ref({})
async function setAwsCredentials(){
    try{
        await graphqlClient.mutate({
            mutation: gql`
                mutation setAwsCredentials($accessKeyId: String, $secretAccessKey: String) {
                    setAwsCredentials(accessKeyId: $accessKeyId, secretAccessKey: $secretAccessKey)
                }`,
            variables: {
                accessKeyId: awsCredentials.value.accessKeyId,
                secretAccessKey: awsCredentials.value.secretAccessKey
            }
        })
    }   catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    await initLoad()
    showAwsCredentialsModal.value = false
    awsCredentials.value = {}
}   

const showAzureCredentialsModal: Ref<boolean> = ref(false)
const azureCredentials: Ref<any> = ref({})
async function setAzureCredentials(){
    try{
        await graphqlClient.mutate({
            mutation: gql`
                mutation setAzureCredentials($clientId: String, $clientSecret: String, $tenantId: String, $vaultName: String) {
                    setAzureCredentials(clientId: $clientId, clientSecret: $clientSecret, tenantId: $tenantId, vaultName: $vaultName)
                }`,
            variables: {
                clientId: azureCredentials.value.clientId,
                clientSecret: azureCredentials.value.clientSecret,
                tenantId: azureCredentials.value.tenantId,
                vaultName: azureCredentials.value.vaultName
            }
        })
    }   catch (err: any) {
        showAzureCredentialsModal.value = false
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    await initLoad()
    showAzureCredentialsModal.value = false
    azureCredentials.value = {}
}   

const showEmailPropsModal: Ref<boolean> = ref(false)
const emailProps = ref({
    sendGridKey: '',
    smtpProps: {
        userName: '',
        password: '',
        smtpHost: '',
        port: 587,
        isStarttls: true,
        isSsl: true
    },
    emailSendType: 'UNSET',
    fromEmail: ''
})
const emailSendTypeOptions = [{label: 'UNSET', value: 'UNSET'}, {label: 'SMTP', value: 'SMTP'}, {label: 'SENDGRID', value: 'SENDGRID'}]

const sendGridKey: Ref<string> = ref('')

function resetEmailProps () {
    emailProps.value = {
        sendGridKey: '',
        smtpProps: {
            userName: '',
            password: '',
            smtpHost: '',
            port: 587,
            isStarttls: true,
            isSsl: true
        },
        emailSendType: 'UNSET',
        fromEmail: ''
    }
}

async function setEmailProps(){
    let isSuccess = true
    let errMsg = ''
    try{
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation setEmailProperties($emailProperties: EmailPropertiesInput!) {
                    setEmailProperties(emailProperties: $emailProperties)
                }`,
            variables: {
                emailProperties: emailProps.value
            }
        })
        isSuccess = resp.data.setEmailProperties
    }   catch (err: any) {
        console.error(err)
        isSuccess = false
        errMsg = err.message
    }
    let swalResp: SweetAlertResult<any>
    showEmailPropsModal.value = false
    if (!isSuccess) {
        swalResp = await Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(errMsg),
            'error'
        )
    } else {
        swalResp = await Swal.fire(
            'Success!',
            'Email sending configuration is now set.',
            'info'
        )
    }
    if (swalResp) {
        await initLoad()
        resetEmailProps()
    }
    
}

const showReefModal: Ref<boolean> = ref(false)
const showDefaultOrgModal: Ref<boolean> = ref(false)
const reefSiloId: Ref<string> = ref('')
const reefTemplateId: Ref<string> = ref('')
async function setReefDetails(){
    try{
        await graphqlClient.mutate({
            mutation: gql`
                mutation setReefDetails($templateId: ID!, $siloId: ID!) {
                    setReefDetails(templateId: $templateId, siloId: $siloId)
                }`,
            variables: {
                siloId: reefSiloId.value,
                templateId: reefTemplateId.value
            }
        })
    }   catch (err: any) {
        showReefModal.value = false
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    await initLoad()
    showReefModal.value = false
    reefSiloId.value = ''
    reefTemplateId.value = ''
}

async function setDefaultOrganization(){
    try{
        await graphqlClient.mutate({
            mutation: gql`
                mutation setDefaultOrganization($organization: ID!) {
                    setDefaultOrganization(organization: $organization)
                }`,
            variables: {
                organization: defaultOrganization.value
            }
        })
    } catch (err: any) {
        showDefaultOrgModal.value = false
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    await initLoad()
    showDefaultOrgModal.value = false
}

const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)

</script>

<style lang="scss">
.organization {
    width: 350px;
    height: 50px;
    border-style: solid;
    border-width: 1px;
}

.nameBlock {
    padding-top: 15px;
}

.inline {
    display: inline;
}

.nameBlock {
    padding-bottom: 15px;

    label {
        display: block;
        font-weight: bold;
    }

    input {
        display: inline-block;
        width: 85%;
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
</style>
