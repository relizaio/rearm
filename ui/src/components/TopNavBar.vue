<template>
    <div id="app">
        <div class="topNavBar">
            <div><router-link to="/"><img id="relizaLogo" src="/logo_svg_no_tag_3.svg" /></router-link></div>
            <div class="publicSupport">
                <span v-if="version" class="version">Version: {{version}} </span>
            </div>
            <div class="horizontalNavElement horizontalNavElementOrg">
                <span v-if="organizations && organizations.length > 1">
                    <span><strong>Organization: </strong></span>
                    <n-dropdown id="app-org-dropdown" trigger="hover" :options="orgsDropdownOptions" @select="setMyOrg">
                        <span>
                            <span style="font-size: 16px;" v-if="myorg && myorg.name">{{ myorg.name }}</span>
                            <span v-else>Select Organization</span>
                            <Icon><CaretDownFilled/></Icon>
                        </span>
                           
                    </n-dropdown>
                    
                </span>
                <span v-if="organizations && organizations.length === 1 && myUser.installationType !== 'OSS'"><b>{{ myorg.name }}</b></span>
                <span v-if="organizations && organizations.length === 1 && myUser.installationType === 'OSS'"><b>ReARM</b></span>
            </div>
            <div class="horizontalNavIcons" v-if="myorg && !isPlayground" >
                <span>
                    <router-link :to="{ name: 'profile'}"><vue-feather class="clickable" type="user" title="Profile" /></router-link>
                </span>
                <span v-if="myUser.isGlobalAdmin && myUser.installationType !== 'OSS'">
                    <router-link :to="{ name: 'systemSettings'}"><vue-feather class="clickable" type="settings" title="Global Admin Settings" /></router-link>
                </span>
                <span>
                    <a href="https://docs.rearmhq.com" rel="noopener noreferrer" target="_blank">
                        <vue-feather class="clickable" type="help-circle" title="Documentation" />
                    </a>
                </span>
                <span>
                    <span v-if="myUser"><vue-feather class="clickable" @click="logout" type="log-out" title="Sign Out" /></span>
                </span>
            </div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'TopNavBar'
}
</script>
<script lang="ts" setup>
import axios from '../utils/axios'
import { NDropdown } from 'naive-ui'
import { useStore } from 'vuex'
import { ComputedRef, computed } from 'vue'
import { useRouter } from 'vue-router'
import { CaretDownFilled } from '@vicons/antd'
import { Icon } from '@vicons/utils'
import constants from '@/utils/constants'

const isPlayground : boolean = false
let version : string = ""

const store = useStore()
const router = useRouter()
const myUser = store.getters.myuser

const getVersion : any = async function () {
    let versionUri
    if (window.location.host === 'app.relizahub.com') {
        versionUri = 'https://app.relizahub.com/api/public/v1/instance/productVersion/8a68025d-c5a4-4cec-877c-0082f8c85f5f'
    } else if (window.location.host === 'test.relizahub.com') {
        versionUri = 'https://app.relizahub.com/api/public/v1/instance/productVersion/91165f6a-d05b-408f-925b-491ae5d8cd2a'
    }
    if (versionUri) {
        let versionResp = await axios.get(versionUri)
        version = versionResp.data
    }
}

await getVersion()

let organizations = store.getters.allOrganizations
const orgsDropdownOptions: ComputedRef<any[]> = computed((): any => store.getters.allOrganizations.map((org : any) => {
    return {
        label: org.name,
        key: org.uuid
    }
}))

const setMyOrg : any = async function (orgUuid : string) {
    store.dispatch('updateMyOrg', orgUuid)
    router.push({
        name: 'home'
    })
}

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)

function logout () {
    window.location.href = window.location.origin + '/kauth/realms/Reliza/protocol/openid-connect/logout'
}

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">

body {
    overflow: scroll;
}
#app {
    font-family: 'Avenir', Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
    color: #2c3e50;
}

#relizaLogo {
    width: 130px;
    margin-left: -52px;
}
.topNavBar {
    border-bottom: solid;
    border-bottom-width: thin;
    border-bottom-color: #dfe4e5;
    display: grid;
    grid-template-columns: 0.1fr 0.7fr 0.5fr 110px;
    a {
        font-weight: bold;
        color: rgb(25, 25, 25);
        &.router-link-exact-active {
          color: rgb(255, 148, 130);
        }
    }
    div.publicSupport {
        padding-top: 15px;
        color: red;
        font-style: italic;
        font-weight: bold;
        text-align: center;
    }
    span.version {
        color: rgb(25, 25, 25);
        text-align: center;
        font-style: italic;
        padding-top: 15px;
        font-weight: lighter;
        margin-right: 20px;
    }
}
.horizontalNavElement{
    padding-top: 15px;
}

.horizontalNavIcons i {
    padding-top: 15px;
}

</style>