<template>
    <div class="home">
        <h2>User Profile</h2>

        <div v-if="myUser" class="nameBlock mt-4">
            <n-form-item path="username" label="Your Display Name:">
                <n-input id="userName" class="w-25" v-model:value="userName" />
            </n-form-item>

            <vue-feather class="clickable versionIcon reject" v-if="userName !== myUser.name" type="x"
                @click="userName = myUser.name" title="Discard Name Change" />
            <vue-feather class="clickable versionIcon accept" v-if="userName !== myUser.name" type="check"
                @click="updateUserName" title="Save Name" />
        </div>
        <div class="mt-3">
            <a href="/kauth/realms/Reliza/account" target="_blank" rel="noopener">
                <n-button type="primary">Change Authentication Details</n-button>
            </a>
        </div>
        <div class="emailsBlock">
            <h4 class="mt-4">Emails</h4>
            <n-data-table v-if="myUser && myUser.allEmails" :columns="emailFields" :data="Object.values(myUser.allEmails)">
            </n-data-table>
            <n-modal v-model:show="showProfileUpdateEmailModal" preset="dialog" :show-icon="false" style="width: 90%"
                :title="'Update email address ' + updateEmailObj.oldEmail" :hide-footer="true">
                <n-form>
                    <n-form-item label='New email:'>
                        <n-input id="profile-update-email-email" email v-model:value="updateEmailObj.newEmail" required
                            placeholder="New Email Address" />
                    </n-form-item>
                    <n-form-item label='Set as primary?'>
                        <n-checkbox :disabled="updateEmailObj.isPrimary" id="profile-update-email-primary"
                            v-model:checked="updateEmailObj.isPrimary" />
                    </n-form-item>
                    <n-form-item label='Receive Reliza News and Promotions?'>
                        <n-checkbox id="profile-update-email-marketing"
                            v-model:checked="updateEmailObj.isAcceptMarketing" />
                    </n-form-item>
                    <n-button type="success" @click="updateUserEmail">Submit</n-button>
                    <n-button type="error">Reset</n-button>
                </n-form>
            </n-modal>
        </div>
        <h4>Your Organizations
            <vue-feather v-if="!(myUser.installationType === 'OSS' || (myUser.installationType === 'MANAGED_SERVICE' && myUser.permissions.permissions[0].type !== 'ADMIN'))" @click="showCreateOrgModal = true" class="clickable icons" type="plus-circle"
                title="Create New Organization" style="margin-left: 8px; vertical-align: middle;" />
        </h4>
        <ul>
            <li v-for="organization in organizations" :key="organization.uuid">
                {{ organization.name }}
                &nbsp;
                <vue-feather v-if="false" @click="genUserApiKey(organization.uuid)" class="clickable icons" type="unlock"
                    title="Generate User API Key" />
                <vue-feather v-if="false" @click="archiveOrganization(organization.uuid)" class="clickable icons" type="trash-2"
                    title="Archive Organiztion" />
                <a v-if="false" :href="'/api/manual/v1/backup/' + organization.uuid" target="_blank"
                    rel="noopener noreferrer"><vue-feather class="clickable icons" type="arrow-down"
                        title="Backup Organiztion" /></a>
            </li>
        </ul>
        <div v-if="false" class="createOrg">
            Resotre Organization
            <vue-feather @click="showRestoreOrgModal = true" class="clickable icons" type="arrow-up"
                title="Restore Organiztion" />
        </div>

        <div v-if="false" style="margin-top: 4%;">
            <h4> SSH KEY
                <NIcon>
                    <Key />
                </NIcon>
            </h4>
            <span v-if="myUser.publicSshKeys && myUser.publicSshKeys.length">
                SSH Key is set. <vue-feather class="clickable" type="trash" title="Delete SSH Key" @click="deleteSshKey" />
            </span>
            <span v-else>
                <n-button type="success" @click="showAddSshKeyModal = true">Add Public SSH Key</n-button>
            </span>
        </div>
        <n-modal v-model:show="showAddSshKeyModal" preset="dialog" :show-icon="false" style="width: 70%">
            <n-input v-model:value="sshKey" type="textarea" placeholder="Paste SSH Key Here" />
            <n-button type="success" @click="addSshKey">Add Key</n-button>
        </n-modal>
        <n-modal v-model:show="showCreateOrgModal" preset="dialog" :show-icon="false" style="width: 50%"
            title="Create New Organization">
            <n-form>
                <n-form-item label="Organization Name:">
                    <n-input v-model:value="orgname" type="text" placeholder="Name of the organization" />
                </n-form-item>
                <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px;">
                    <n-button @click="showCreateOrgModal = false">Cancel</n-button>
                    <n-button type="primary" @click="createOrgFromModal">Create Organization</n-button>
                </div>
            </n-form>
        </n-modal>

        <n-modal v-model:show="showRestoreOrgModal" preset="dialog" :show-icon="false" style="width: 70%"
            title="Restore Organization">
            <n-form-item label="Restore Organization" path="/api/manual/v1/restore">
                <n-upload v-model:value="restoreFile" @change="handleFileChange">
                    <n-button>Select file</n-button>
                </n-upload>
            </n-form-item>
            <n-button @click="submitFile">Restore</n-button>
        </n-modal>

    </div>
</template>

<script lang="ts" setup>
// @ is an alias to /src
import { NIcon, NCheckbox, NInput, NModal, NDataTable, NForm, NFormItem, NInputGroup, NButton, NotificationType, useNotification, NUpload } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed, onMounted } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { Edit as EditIcon, Key } from '@vicons/tabler'
import commonFunctions from '@/utils/commonFunctions'
import axios from '../utils/axios'
import Swal from 'sweetalert2'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import { OnChange } from 'naive-ui/es/upload/src/interface'


const route = useRoute()
const router = useRouter()
const store = useStore()
const notification = useNotification()
const notify = async function (type: NotificationType, title: string, content: string, duration: number = 3500) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

onMounted(async () => {
    // handle email verified
    if (route.query.emailVerified) {
        notify('success', 'Success', 'Email address verified successfully!', 8000)
    }
    await initLoad()
})

const apiKey = ref('')
const apiKeyId = ref('')
const apiKeyHeader = ref('')
const orgname = ref('')
const emailFields: Ref<any> = ref([
    {
        key: 'email',
        title: 'Email'
    },
    {
        key: 'isPrimary',
        title: 'Primary?',
        render(row: any) {
            return h('div', row.isPrimary.toString())
        }
    },
    {
        key: 'isVerified',
        title: 'Verified?',
        render(row: any) {
            return h('div', row.isVerified.toString())
        }
    },
    {
        key: 'isAcceptMarketing',
        title: 'Receive Reliza Info?',
        render(row: any) {
            const isMarketingAccepting = row.isAcceptMarketing ? "true" : "false";
            return h('div', isMarketingAccepting)
        }
    },
    // {
    //     // <template v-slot:cell(controls)="data">
    //     //             <vue-feather @click="updateEmailModal(data.item.email)" class="clickable" title="Update Email" type="edit" />
    //     //         </template>
    //     key: 'controls',
    //     title: 'Manage',
    //     render(row: any) {
    //         return h('div', [
    //             h(
    //                 NIcon,
    //                 {
    //                     title: 'Update Email',
    //                     class: 'icons clickable',
    //                     size: 25,
    //                     onClick: () => updateEmailModal(row.email)
    //                 }, { default: () => h(EditIcon) }
    //             )
    //         ])
    //     }
    // }
])
const updateEmailObj: Ref<any> = ref({})
const userName = ref('')

async function initLoad() {
    store.dispatch('fetchMyOrganizations')
    // reload my user
    const userResp = await store.dispatch('fetchMyUser')
    userName.value = userResp.name
}

function createOrg() {
    if (orgname.value) {
        store.dispatch('createOrganization', orgname.value).then(() => {
            store.dispatch('fetchMyUser')
        })
    }
    orgname.value = ''
}

const createOrgFromModal = () => {
    if (!orgname.value.trim()) {
        notify('error', 'Error', 'Please enter an organization name')
        return
    }
    store.dispatch('createOrganization', orgname.value).then(() => {
        notify('success', 'Success', 'Organization created successfully!')
        orgname.value = ''
        showCreateOrgModal.value = false
    }).catch((error: any) => {
        notify('error', 'Error', 'Failed to create organization: ' + error.message)
    })
}

const showCreateOrgModal: Ref<boolean> = ref(false)

async function genUserApiKey(orgUUID: string) {
    const swalResult = await Swal.fire({
        title: 'Are you sure?',
        text: 'A new API Key will be generated, any existing integrations with previous API Key (if exist) will stop working.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, generate it!',
        cancelButtonText: 'No, cancel it'
    })
    if (swalResult.value) {
        let path = '/v1/manual/user/setOrgReadApiKey/' + orgUUID
        const axiosResp = await axios.put(path)
        let newKeyMessage = getGeneratedApiKeyHTML(axiosResp.data)
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
function getGeneratedApiKeyHTML(responseData: any) {
    return `
            <div style="text-align: left;">
            <p>Please record these data as you will see API key only once (although you can re-generate it at any time):</p>
                <table style="width: 95%;">
                    <tr>
                        <td>
                            <strong>API ID:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${responseData.id}</textarea>
                        </td>
                    </tr>
                        <td>
                            <strong>API Key:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${responseData.apiKey}</textarea>
                        </td>
                    <tr>
                        <td>
                            <strong>Header:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled rows="4">${responseData.authorizationHeader}</textarea>
                        </td>
                    </tr>
                </table>
            </div>
        `
}
async function archiveOrganization(orgUUID: string) {
    let orgToArchive = organizations.value.find((o: any) => o.uuid === orgUUID)
    Swal.fire({
        title: `Are you sure you want to archive the ${orgToArchive.name} Organization?`,
        text: `If you proceed, the organization will be archived and you will not have access to its data. The organization will stay archived for 30 days after which it would be permanently deleted.`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, archive!',
        cancelButtonText: 'No, cancel'
    }).then(async result => {
        if (result.value) {
            let archiveOrgParams = {
                orgUuid: orgUUID
            }
            try {
                let archived = await store.dispatch('archiveOrganization', archiveOrgParams)
                if (archived) {
                    store.dispatch('fetchMyOrganizations')
                }
            } catch (err: any) {
                Swal.fire(
                    'Error!',
                    commonFunctions.parseGraphQLError(err.message),
                    'error'
                )
            }
        } else if (result.dismiss === Swal.DismissReason.cancel) {
            notify('info', 'Cancelled', `Organization archiving cancelled. Your organization is still active.`)
        }
    })
}

const showRestoreOrgModal: Ref<boolean> = ref(false)
const restoreFile: Ref<File | null> = ref(null);
const handleFileChange = (data: OnChange): void => {
    restoreFile.value = data.file.file;
};

const submitFile = async () => {
    console.log("submit file called", restoreFile.value)
    if (restoreFile.value) {
        const formData = new FormData();
        formData.append('file', restoreFile.value);

        try {
            const response = await axios.post('/api/manual/v1/restore', formData, {
                headers: {
                    'Content-Type': 'multipart/form-data'
                }
            });
            showRestoreOrgModal.value = false
            let htmlResp = parseBackupResponse(response.data)
            Swal.fire({
                title: 'Backup Success!',
                customClass: {popup: 'swal-wide'},
                html: htmlResp,
                icon: 'success'
            })
        } catch (error: any) {
            // Handle the error
            showRestoreOrgModal.value = false
            Swal.fire(
                'Error',
                'Error Restoring ',
                'error'
            )
        }
    }
};

function parseBackupResponse(jsonObject: any) {
    let htmlString = '<p>The following data was already found in the organization </p><ul>';
    console.log('data', jsonObject)
    for (const key in jsonObject) {
        if (jsonObject.hasOwnProperty(key)) {
            htmlString += '<li>' + key + ' : ';
            htmlString += jsonObject[key].length
            htmlString += '</li>';
        }
    }

    htmlString += '</ul>';
    console.log('htmlString', htmlString)
    return htmlString
}


function keyVisibleHandler(isVisible: boolean) {
    if (!isVisible) {
        // reset key
        apiKey.value = ''
        apiKeyId.value = ''
        apiKeyHeader.value = ''
    }
}
function openOrgPage(uuid: string) {
    router.push({ name: 'organization', params: { uuid: uuid } })
}
const showProfileUpdateEmailModal: Ref<boolean> = ref(false)
function updateEmailModal(email: string) {
    // locate email
    let selectedEmail = myUser.value.allEmails[email]
    updateEmailObj.value = {
        oldEmail: selectedEmail.email,
        newEmail: selectedEmail.email,
        isPrimary: selectedEmail.isPrimary,
        isAcceptMarketing: selectedEmail.isAcceptMarketing ? selectedEmail.isAcceptMarketing : false
    }
    showProfileUpdateEmailModal.value = true
}
function updateUserEmail() {
    // e.preventDefault()
    axios.put('/api/manual/v1/user/updateEmailAddress', updateEmailObj.value).then(response => {
        if (response.data) {
            if (updateEmailObj.value.newEmail !== updateEmailObj.value.oldEmail) {
                notify('error', 'Error', 'Please verify your new email address for it to become active!', 5000)
            } else {
                notify('success', 'Success', 'Email address properties updated!', 5000)
            }
            // reload my user
            store.dispatch('fetchMyUser').then(response => {
                userName.value = response.name
            })
            showProfileUpdateEmailModal.value = false
        }
    })
}
function updateUserName() {
    store.dispatch('updateUserName', userName.value)
}

// const myUser: Ref<any> = ref({})
const organizations: ComputedRef<any> = computed((): any => store.getters.allOrganizations)
const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)

const showAddSshKeyModal: Ref<boolean> = ref(false)
const sshKey: Ref<string> = ref('')
async function addSshKey() {
    let name = 'default'
    await graphqlClient.mutate({
        mutation: gql`
            mutation addSshKey {
                addSshKey(name: "${name}", key: "${sshKey.value}") 
            }`
    })
    await store.dispatch('fetchMyUser')
    showAddSshKeyModal.value = false
    sshKey.value = ''
}
async function deleteSshKey() {
    const keyId = myUser.value.publicSshKeys[0].uuid
    await graphqlClient.mutate({
        mutation: gql`
            mutation removeSshKey($uuid: ID!) {
                removeSshKey(uuid: $uuid) 
            }`,
        variables: {
            'uuid': keyId
        }
    })
    await store.dispatch('fetchMyUser')
}
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
