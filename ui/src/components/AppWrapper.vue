<template>
    <div class="app-wrapper">
        <n-config-provider>
            <n-notification-provider placement="bottom-right">
                <div class="main-content" v-if="!showSignUpFlow">
                    <div class="content-area">
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
                    </div>
                    <div class="footer-container">
                        <n-divider />
                        <div class="footer-content">
                            <div class="footer-text">
                                {{ footerVersionText }}
                            </div>
                            <div class="footer-text">
                                © Reliza Incorporated, 2019-2025
                            </div>
                            <div class="footer-links">
                                <a href="https://docs.rearmhq.com" rel="noopener noreferrer" target="_blank" class="footer-link">Documentation</a>
                                <span class="footer-separator">•</span>
                                <a href="mailto:info@reliza.io" class="footer-link">Support</a>
                            </div>
                        </div>
                    </div>
                </div>
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
import { NConfigProvider, NLayout, NSpace, NLayoutContent, NNotificationProvider, NDivider } from 'naive-ui'
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
const rearmProductVersion: string = '54ab89bb-f1f1-459c-afbf-e4d78655b298'
const footerVersionText: ComputedRef<string> = computed((): string => {
    const flavor = myUser.value?.installationType === 'OSS' ? 'ReARM CE' : 'ReARM Pro'
    const rearmProductVersionComparisonString: string = '54ab89bb-f1f1-459c' + '-afbf-e4d78655b298'
    if (rearmProductVersion !== rearmProductVersionComparisonString) {
        return `${flavor} v${rearmProductVersion}`
    } else {
        return flavor
    }
})

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
.app-wrapper {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.main-content {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.content-area {
  flex: 1;
}

.footer-container {
  margin-top: auto;
  padding: 0 20px;
}

.footer-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  color: #666;
  font-size: 14px;
  
  @media (max-width: 768px) {
    flex-direction: column;
    gap: 12px;
    text-align: center;
  }
}

.footer-text {
  font-weight: 500;
}

.footer-links {
  display: flex;
  align-items: center;
  gap: 8px;
}

a {
  color: inherit;
  text-decoration: none;
}
</style>
