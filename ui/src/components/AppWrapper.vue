<template>
    <div>
        <n-config-provider>
            <n-notification-provider>
                <n-space vertical v-if="!showSignUpFlow">
                    <n-layout>
                        <n-layout-content >
                            <top-nav-bar />
                        </n-layout-content>
                    </n-layout>
                    <n-layout>
                        <n-layout-content >
                            <left-nav-bar />
                        </n-layout-content>
                    </n-layout>
                </n-space>
                <sign-up-flow v-if="showSignUpFlow === 'signup'" />
                <verify-email v-if="showSignUpFlow === 'verifyEmail'" />
                <join-organization v-if="showSignUpFlow === 'joinOrganization'" />
            </n-notification-provider>
        </n-config-provider>
    </div>
</template>
<script lang="ts">
export default {
    name: 'AppWrapper'
}
</script>
<script lang="ts" setup>
import { ref, ComputedRef, computed } from 'vue'
import { NConfigProvider, NLayout, NSpace, NLayoutContent, NNotificationProvider } from 'naive-ui'
import TopNavBar from './TopNavBar.vue'
import LeftNavBar from './LeftNavBar.vue'
import { useStore } from 'vuex'
import constants from '@/utils/constants'
import SignUpFlow from '@/components/SignUpFlow.vue'
import VerifyEmail from '@/components/VerifyEmail.vue'
import JoinOrganization from '@/components/JoinOrganization.vue'
import axios from '../utils/axios'
import Swal from 'sweetalert2'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '../utils/commonFunctions'


const store = useStore()
await fetchCsrfToken()
await store.dispatch('fetchMyUser')
const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)

const showSignUpFlow = ref('')
async function fetchCsrfToken() {
    const response = await axios.get('/api/manual/v1/fetchCsrf');
    const csrfToken = response.data.token;
    window.localStorage.setItem('csrf', csrfToken);
}

const onCreate = async function () {
    if (window.location.href.includes('/verifyEmail/')) {
        showSignUpFlow.value = 'verifyEmail'
    } else if (window.location.href.includes('/joinOrganization/')) {
        showSignUpFlow.value = 'joinOrganization'
    } else if (!myUser.value.policiesAccepted || !myUser.value.email.length || 
        !myUser.value.allEmails.find((a: any) => a.email === myUser.value.email && a.isVerified) ||
        !myUser.value.organizations.length) {
        showSignUpFlow.value = 'signup'
    } else {
        // resolve redirect if any
        const requestedRedirect = window.localStorage.getItem(constants.RelizaRedirectLocalStorage)
        if (requestedRedirect) {
            console.log('redirecting to pre-authorized location')
            window.localStorage.removeItem(constants.RelizaRedirectLocalStorage)
            window.location.href = requestedRedirect
        }
        await store.dispatch('fetchMyOrganizations')
    }
    if(myUser.value.systemSealed && !showSignUpFlow.value){
        Swal.fire({
            title: 'Submit your installation secret to unseal the system',
            input: 'text',
            inputAttributes: {
                autocapitalize: 'off'
            },
            showCancelButton: false,
            confirmButtonText: 'UnSeal',
            showLoaderOnConfirm: true,
            preConfirm: async (secret) => {
                try{
                    await graphqlClient.query({
                        query: gql`
                            mutation unSealSystem($secret: String) {
                                unSealSystem(secret: $secret)
                            }`,
                        variables: {secret: secret},
                        fetchPolicy: 'no-cache'
                    })
                }   catch (err: any) {
                    Swal.showValidationMessage(
                        `Failed!: ${commonFunctions.parseGraphQLError(err.message)}`
                    )
                }
            },
            allowOutsideClick: () => false
        }).then(async(result) => {
            if (result.isConfirmed) {
                
                Swal.fire(
                    "Success!",
                    "System Unsealed Successfully",
                    "success"
                ).then(() => {
                    window.location.href = '/'
                })
            
            }
        })
    }
}

await onCreate()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
</style>
