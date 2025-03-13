<template>
    <div>
        <n-modal
            v-model:show="showPostSignupModal"
            title="Finalize Creation of Your Account"
            class="signupModals"
            :close-on-esc="false"
            :mask-closable="false"
        >
            <div>
                <h3>Finalize Creation of Your Account</h3>
                <n-form
                    ref="postSignupForm"
                    :model="userPostSignup"
                    :rules="postSignupFormRules">
                    <n-form-item    
                                path="tosAccepted"
                                label="Terms Of Service">
                        <n-checkbox
                            v-if="!myUser.policiesAccepted"
                            v-model:checked="userPostSignup.tosAccepted"
                        />
                        <span style="margin-left:7px;">Accept <a target="_blank" rel="noopener noreferrer" href="https://relizahub.com/tos.html">Terms Of Service</a> and
                            <a target="_blank" rel="noopener noreferrer" href="https://relizahub.com/privacy.html">Privacy Policy</a>.
                        </span>
                    </n-form-item>
                    <n-form-item
                                v-if="myUser.email === 'noemail'"
                                path="email"
                                label="Your email:">
                        <n-input v-model:value="userPostSignup.email" placeholder="Enter your email"/>
                    </n-form-item>
                    <n-form-item    
                                path="marketingAccepted"
                                label="News and Promotions">
                        <n-checkbox
                            v-model:checked="userPostSignup.marketingAccepted"
                        >
                            Agree to receive news and promotions from Reliza by email (Optional).
                        </n-checkbox>
                    </n-form-item>
                    <n-button :loading="emailVerificationCallPending" @click="postSignupAccept" type="success">
                        <span v-if="emailVerificationCallPending">Processing...</span>
                        <span v-else>Submit</span>
                    </n-button>
                </n-form>
            </div>
        </n-modal>
        <n-modal
            v-model:show="showEmailVerificationModal"
            title="Verify Email"
            class="signupModals"
            :close-on-esc="false"
            :mask-closable="false"
        >
            <div>
                <p>We require an additional email verification of your email address <strong>{{ myUser.email }}</strong> to proceed with ReARM.</p>
                <p>If you need a new verification link, please click the button below.</p>
                <n-button :loading="emailVerificationCallPending" @click="resendVerificationLink" :disabled="emailVerificationCallPending">
                        <span v-if="emailVerificationCallPending">Processing...</span>
                        <span v-else>Resend Verification Link</span>
                </n-button>
            </div>
        </n-modal>
        <n-modal
            v-model:show="showCreateFirstOrgModal"
            title="Create First Organization"
            class="signupModals"
            :close-on-esc="false"
            :mask-closable="false"
        >
            <div>
                Start by creating your first organization:
                <n-form
                    ref="orgCreateForm"
                    :model="orgCreate"
                    :rules="orgCreateFormRules">
                    <n-form-item
                        path="name">
                        <n-input v-model:value="orgCreate.name" required placeholder="Name of your organization, i.e. Reliza"/>
                    </n-form-item>
                    <n-button type="success" @click="handleFirstOrgOk">Create</n-button>
                </n-form>
            </div>
        </n-modal>
    </div>
</template>
<script lang="ts">
export default {
    name: 'SignUpFlow'
}
</script>
<script lang="ts" setup>
import { Ref, ref, ComputedRef, computed } from 'vue'
import { useNotification, NotificationType, FormInst, FormRules, FormItemRule, NButton, NModal, NForm, NFormItem, NInput, NCheckbox } from 'naive-ui'
import { useStore } from 'vuex'
import constants from '@/utils/constants'
import Swal from 'sweetalert2'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '../utils/commonFunctions'

const notification = useNotification()
const store = useStore()
await store.dispatch('fetchMyUser')
const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)

const showCreateFirstOrgModal = ref(false)
const showPostSignupModal = ref(false)
const showEmailVerificationModal = ref(false)

const postSignupForm = ref<FormInst | null>(null)
const orgCreateForm = ref<FormInst | null>(null)

const userPostSignup = ref({
    tosAccepted: myUser.value.policiesAccepted,
    marketingAccepted: false,
    email: myUser.value.email
})

const orgCreate = ref({
    name: ''
})

const emailVerificationCallPending: Ref<boolean> = ref(false)

const handleFirstOrgOk = async function () {
    orgCreateForm.value?.validate(async (errors) => {
        if (!errors) {
            await store.dispatch('createOrganization', orgCreate.value.name)
            window.location.href = '/'
            // store.dispatch('fetchMyUser')
            // store.dispatch('fetchMyOrganizations')
        }
    })
}

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const postSignupFormRules: FormRules = {
    tosAccepted: {
        required: true,
        validator (rule: FormItemRule, value: boolean) {
            if (!value) {
                return new Error('Acceptance of Terms Of Service and Privacy Policy is required to proceed.')
            }
            return true
        },
        message: 'Acceptance of Terms Of Service and Privacy Policy is required to proceed.'
    },
    email: {
        validator (rule: FormItemRule, value: string) {
            if (!value) {
                return new Error('Email is required to proceed.')
            }
            const re = /\S+@\S+\.\S+/;
            if (!re.test(value)) {
                return new Error('Valid email is required to proceed.')
            }
            return true
        }
    }
}

const orgCreateFormRules = {
    name: {
        required: true,
        message: 'Organization name is required'
    }
}

const postSignupAccept = async function () {
    postSignupForm.value?.validate(async (errors) => {
        if (!errors) {
            emailVerificationCallPending.value = true
            try {
                await store.dispatch('acceptMyUserPolicies', userPostSignup.value)
                showPostSignupModal.value = false
                emailVerificationCallPending.value = false
                window.location.href = '/'
            } catch (error: any) {
                emailVerificationCallPending.value = false
                showPostSignupModal.value = false
                console.error(error)
                const swalResp = await Swal.fire({
                    title: 'Error!',
                    text: commonFunctions.parseGraphQLError(error.message),
                    icon: 'warning',
                    confirmButtonText: 'OK'
                })
                if (swalResp) window.location.href = '/'
            }
        }
    })
}

const resendVerificationLink = async function () {
    emailVerificationCallPending.value = true
    try {
        const data = await graphqlClient.mutate({
            mutation: gql`
                mutation resendEmailVerification {
                    resendEmailVerification
                }`
        })
        emailVerificationCallPending.value = false
        if (data.data.resendEmailVerification) {
            notify('info', 'Sent', `New verification email sent. Please check your email.`)
        } else {
            notify('error', 'Error', `Error sending verification email.`)
        }
    } catch (err: any) {
        emailVerificationCallPending.value = false
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
}

const onCreate = async function () {
    if (!myUser.value || (!myUser.value.oauthId && !myUser.value.githubId)) {
        window.localStorage.setItem(constants.RelizaRedirectLocalStorage, window.location.href)
    } else if (!myUser.value.policiesAccepted || myUser.value.email === 'noemail') {
        showPostSignupModal.value = true
    } else if (!myUser.value.email.length || !myUser.value.allEmails.find((a: any) => a.email === myUser.value.email && a.isVerified)) {
        showEmailVerificationModal.value = true
    } else if (!myUser.value.organizations.length) {
        showCreateFirstOrgModal.value = true
    } else {
        window.location.href = '/'
    }
    if (document) {
        const bodyel = document.querySelector('body')
        if (bodyel) {
            bodyel.style.background = "DimGrey none"
            bodyel.style.backgroundImage = 'url("/reliza_in_sand_right_corner.jpg")'
            bodyel.style.backgroundSize = 'cover'
        }
    }
    
}

await onCreate()


</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.signupModals {
    width: 90%;
    padding: 20px;
    background-color: white;
    opacity: 0.9;
}

</style>
