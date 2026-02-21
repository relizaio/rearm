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
            <div class="horizontalNavElement horizontalNavElementPerspective" v-if="myUser.installationType !== 'OSS'">
                <span v-if="perspectiveOptions.length > 1">
                    <span><strong>Perspective: </strong></span>
                    <n-dropdown id="app-perspective-dropdown" trigger="hover" :options="perspectiveOptions" @select="setMyPerspective">
                        <span>
                            <span style="font-size: 16px;">{{ currentPerspectiveName }}</span>
                            <Icon><CaretDownFilled/></Icon>
                        </span>
                    </n-dropdown>
                </span>
                <span v-else><strong>Perspective: </strong><span style="font-size: 16px;">{{ currentPerspectiveName }}</span></span>
            </div>
            <div class="horizontalNavIcons" v-if="myorg && !isPlayground" >
                <span>
                    <router-link :to="{ name: 'profile'}"><n-icon class="clickable" title="Profile" size="20"><User /></n-icon></router-link>
                </span>
                <span v-if="myUser.isGlobalAdmin && myUser.installationType !== 'OSS'">
                    <router-link :to="{ name: 'systemSettings'}"><n-icon class="clickable" title="Global Admin Settings" size="20"><Settings /></n-icon></router-link>
                </span>
                <span>
                    <span v-if="myUser"><n-icon class="clickable" @click="logout" title="Sign Out" size="20"><Logout /></n-icon></span>
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
import { NDropdown, NIcon } from 'naive-ui'
import { User, Settings, Logout } from '@vicons/tabler'
import { useStore } from 'vuex'
import { ComputedRef, computed, h, watch } from 'vue'
import { useRouter } from 'vue-router'
import { CaretDownFilled } from '@vicons/antd'
import { Icon } from '@vicons/utils'
import constants from '@/utils/constants'

const isPlayground : boolean = false
let version : string = ""

const store = useStore()
const router = useRouter()
const myUser = store.getters.myuser

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)

// Perspectives
const perspectives: ComputedRef<any[]> = computed((): any => store.getters.perspectivesOfOrg(myorg.value?.uuid || ''))
const myperspective: ComputedRef<string> = computed((): string => store.getters.myperspective)

// True when user has ESSENTIAL_READ org-wide but has explicit scoped PERSPECTIVE permissions
// In this case they should not see the Default perspective
const hasOnlyPerspectiveAccess: ComputedRef<boolean> = computed((): boolean => {
    const user = store.getters.myuser
    if (!user?.permissions?.permissions) return false
    const orgUuid = myorg.value?.uuid
    if (!orgUuid) return false
    const orgWidePerm = user.permissions.permissions.find((p: any) =>
        p.scope === 'ORGANIZATION' && p.org === orgUuid
    )
    if (!orgWidePerm || orgWidePerm.type !== 'ESSENTIAL_READ') return false
    return user.permissions.permissions.some((p: any) =>
        p.scope === 'PERSPECTIVE' && p.org === orgUuid && (p.type === 'READ_ONLY' || p.type === 'READ_WRITE' || p.type === 'ADMIN')
    )
})

const perspectiveOptions: ComputedRef<any[]> = computed((): any => {
    const options: any[] = hasOnlyPerspectiveAccess.value ? [] : [{
        label: () => h('b', 'Default'),
        key: 'default'
    }]

    if (perspectives.value.length > 0) {
        if (!hasOnlyPerspectiveAccess.value) {
            options.push({
                type: 'divider',
                key: 'd1'
            })
        }
        // Sort perspectives: PERSPECTIVE type first, then by name
        const sortedPerspectives = [...perspectives.value].sort((a: any, b: any) => {
            const typeA = a.type || ''
            const typeB = b.type || ''
            
            // If types are different, prioritize PERSPECTIVE
            if (typeA === 'PERSPECTIVE' && typeB !== 'PERSPECTIVE') return -1
            if (typeA !== 'PERSPECTIVE' && typeB === 'PERSPECTIVE') return 1
            
            // Otherwise sort by name
            return (a.name || '').localeCompare(b.name || '')
        })

        let addedDivider = false
        const hasPerspectives = sortedPerspectives.some((p: any) => p.type === 'PERSPECTIVE')

        sortedPerspectives.forEach((p: any) => {
            if (hasPerspectives && !addedDivider && p.type !== 'PERSPECTIVE') {
                options.push({
                    type: 'divider',
                    key: 'd2'
                })
                addedDivider = true
            }

            options.push({
                label: p.type === 'PERSPECTIVE' ? () => h('b', p.name) : p.name,
                key: p.uuid
            })
        })
    }
    
    return options
})

const currentPerspectiveName: ComputedRef<string> = computed((): string => {
    const perspective = perspectives.value.find((p: any) => p.uuid === myperspective.value)
    if (perspective) return perspective.name
    if (hasOnlyPerspectiveAccess.value && perspectives.value.length > 0) {
        return perspectives.value[0].name
    }
    return 'Default'
})

const setMyPerspective = function (perspectiveUuid: string) {
    store.dispatch('updateMyPerspective', perspectiveUuid)
}

// Auto-select first available perspective when user has only perspective-scoped access
watch([hasOnlyPerspectiveAccess, perspectives, myorg, myperspective], () => {
    if (!hasOnlyPerspectiveAccess.value || perspectives.value.length === 0) return

    const hasCurrentPerspective = perspectives.value.some((p: any) => p.uuid === myperspective.value)
    if (myperspective.value === 'default' || !hasCurrentPerspective) {
        store.dispatch('updateMyPerspective', perspectives.value[0].uuid)
    }
}, { immediate: true })

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
    grid-template-columns: 0.1fr 0.5fr 0.35fr 0.35fr 110px;
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
    padding-left: 7%;
}

</style>