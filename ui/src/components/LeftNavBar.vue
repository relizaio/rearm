<template>
    <div>
        <n-space id="vertNav" vertical>
            <n-layout has-sider>
                <n-layout-sider
                    bordered
                    collapse-mode="width"
                    :collapsed-width="64"
                    :width="240"
                    :collapsed="collapsed"
                    @collapse="collapsed = true"
                    @expand="collapsed = false"
                >
                    <n-menu
            v-model:value="activeKey"
            :collapsed="collapsed"
            :collapsed-width="64"
            :collapsed-icon-size="22"
            :options="computedMenuOptions"
            default-value="home"
            />
                </n-layout-sider>
                <n-layout>
                    <router-view style="margin-left: 5px; margin-right: 5px;"
                        class="nofloat viewWrapper"
                        @routerViewEvent="routerViewEventHandle"
                        :key="$route.fullPath"
                    />
                </n-layout>
            </n-layout>
        </n-space>
    </div>
</template>

<script lang="ts">
export default {
    name: 'LeftNavBar'
}
</script>
<script lang="ts" setup>
import { NSpace, NLayout, NLayoutSider, NMenu, NIcon } from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import { ref, h, Component, ComputedRef, computed, Ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useStore } from 'vuex'
import { HomeOutlined as HomeIcon, CloudServerOutlined, BugOutlined } from '@vicons/antd'
import { Adjustments, Folder, Stack2, BrandGit, Key, ChartBar } from '@vicons/tabler'


function renderIcon (icon: Component) {
    return () => h(NIcon, null, { default: () => h(icon) })
}


const menuOptions = function (org : string, myuser: any) : MenuOption[] {
    const baseOptions =  [
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'home',
                            params: {}
                        }
                    },
                    { default: () => 'Home' }
                ),
            key: 'home',
            icon: renderIcon(HomeIcon)
        },
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'ComponentsOfOrg',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Components' }
                ),
            key: 'components',
            icon: renderIcon(Folder)
        },
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'ProductsOfOrg',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Products' }
                ),
            key: 'products',
            icon: renderIcon(Stack2)
        },
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'VcsReposOfOrg',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'VCS' }
                ),
            key: 'vcsRepos',
            icon: renderIcon(BrandGit)
        },

    ]

    const nonOssOptions = [
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'InstancesOfOrg',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Instances' }
                ),
            key: 'instances',
            icon: renderIcon(CloudServerOutlined)
        },
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'SecretsOfOrg',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Secrets' }
                ),
            key: 'secrets',
            icon: renderIcon(Key)
        },
    ]

    const settingsOptions = [
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'AnalyticsOfOrg',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Statistics' }
                ),
            key: 'analytics',
            icon: renderIcon(ChartBar)
        },
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'VulnerabilityAnalysis',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Vulnerability Analysis' }
                ),
            key: 'vulnerabilityAnalysis',
            icon: renderIcon(BugOutlined)
        },
        {
            label: () =>
                h(
                    RouterLink,
                    {
                        to: {
                            name: 'OrgSettings',
                            params: {orguuid: org}
                        }
                    },
                    { default: () => 'Organization Settings' }
                ),
            key: 'orgsettings',
            icon: renderIcon(Adjustments)
        },
    ]

    const topOptions = (true || myuser.installationType === 'OSS') ? baseOptions : baseOptions.concat(nonOssOptions)
    return topOptions.concat(settingsOptions)
}


const store = useStore()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)

const isShowSecrets : boolean = false
const myUser : any = store.getters.myuser
const routerViewEventHandle = function (event : any) {
    console.log(event)
}

const computedMenuOptions : ComputedRef<MenuOption[]> = computed((): MenuOption[] => menuOptions(myorg.value.uuid, myUser))
const activeKey: Ref<string> =  ref<string>('home')
const collapsed: Ref<boolean> =  ref(true)

// Map route names to menu keys
const routeToMenuKey: Record<string, string> = {
    'home': 'home',
    'ComponentsOfOrg': 'components',
    'ProductsOfOrg': 'products',
    'VcsReposOfOrg': 'vcsRepos',
    'InstancesOfOrg': 'instances',
    'Instance': 'instances',
    'SecretsOfOrg': 'secrets',
    'AnalyticsOfOrg': 'analytics',
    'VulnerabilityAnalysis': 'vulnerabilityAnalysis',
    'OrgSettings': 'orgsettings'
}

// Watch route changes to update active menu key
const route = useRoute()
watch(() => route.name, (newRouteName) => {
    if (newRouteName && typeof newRouteName === 'string') {
        const menuKey = routeToMenuKey[newRouteName]
        if (menuKey) {
            activeKey.value = menuKey
        }
    }
}, { immediate: true })


</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">

</style>