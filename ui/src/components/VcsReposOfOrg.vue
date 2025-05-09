<template>
    <div>
        <h4>VCS Repositories</h4>
        <Icon @click="modalVcsOfOrgAddVcsRepo=true" v-if="userPermission !== 'READ_ONLY' && userPermission !== ''" size="30" class="clickable" title="Add New VCS Repository" ><CirclePlus/></Icon>  

        <n-data-table 
            :columns="vcsRepoFields"
            :data="repos"
        />

        <n-modal
            preset="dialog"
            :show-icon="false"
            v-model:show="modalVcsOfOrgAddVcsRepo">
            <n-card 
                style="width: 600px"
                size="huge"
                title="Create VCS Repository"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
            <CreateVcsRepository  @createdVcsRepo="createdVcsRepo" :orguuid="orguuid"></CreateVcsRepository>
            </n-card>
        </n-modal>
        <n-modal
            preset="dialog"
            :show-icon="false"
            v-model:show="modalVcsOfOrgViewConnectedComponents">
            <n-card 
                style="width: 600px"
                size="huge"
                title="Components of Vcs Repository"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <div v-if="connectedComponents !== undefined && connectedComponents.length >0">
                    <div v-for="component in connectedComponents" :key="component.uuid">
                        <li><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link></li>
                    </div>
                </div>
                <div v-else>
                    No Components Connected with this repo
                </div>
            </n-card>
        </n-modal>
    </div>
</template>
  
<script lang="ts" setup>
import { NInput, NModal, NCard, NDataTable, useNotification, NotificationType, NIcon, NTooltip  } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed, Component } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { CirclePlus, Edit as EditIcon, ExternalLink, Eye, Check, X } from '@vicons/tabler'
import { Icon } from '@vicons/utils'
import CreateVcsRepository from '@/components/CreateVcsRepository.vue'
import commonFunctions from '@/utils/commonFunctions'
import { Info20Regular, Copy20Regular } from '@vicons/fluent'

const route = useRoute()
const store = useStore()

const myorg: ComputedRef<string> = computed((): any => store.getters.myorg)
const orguuid : Ref<string> = ref('')
if (route.params.orguuid) {
    orguuid.value = route.params.orguuid.toString()
} else {
    orguuid.value = myorg.value
}

const notification = useNotification()

const repos: ComputedRef<[VCS]> = computed((): [VCS] => {
    let storeRepos = store.getters.vcsReposOfOrg(orguuid.value)
    if (storeRepos && storeRepos.length) {
        // sort - TODO make sort configurable
        storeRepos.sort((a: any, b: any) => {
            if (a.name.toLowerCase() < b.name.toLowerCase()) {
                return -1
            } else if (a.name.toLowerCase() > b.name.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    return storeRepos
})
if (repos.value.length < 1) {
    store.dispatch('fetchVcsRepos', orguuid.value)
}

const vcs : Ref<string> = ref('')
const modalVcsOfOrgViewConnectedComponents = ref(false)
function showConnectedComponentsModal(vcsUuid: string){
    vcs.value = vcsUuid
    modalVcsOfOrgViewConnectedComponents.value = true
}

const connectedComponents: ComputedRef<any> = computed((): any => {
    const storeComponents = store.getters.componentsOfOrg(orguuid.value).filter((proj: any) => (proj.vcs === vcs.value))
    if (storeComponents && storeComponents.length) {
        // sort - TODO make sort configurable
        storeComponents.sort((a: any, b: any) => {
            if (a.name.toLowerCase() < b.name.toLowerCase()) {
                return -1
            } else if (a.name.toLowerCase() > b.name.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    return storeComponents
})
if (connectedComponents.value.length < 1) {
    store.dispatch('fetchComponents', orguuid.value)
}

const modalVcsOfOrgAddVcsRepo = ref(false)
const userPermission = ref('')
userPermission.value = commonFunctions.getUserPermission(orguuid.value, store.getters.myuser).org
type VCS = {uuid: string, name: string, uri: string}
type SelectedVcs = {name: boolean, uri: boolean}
const selectedVcs = ref(new Map<string, SelectedVcs>())
const updatedName:Ref<string> = ref('')
const updatedUri:Ref<string> = ref('')
function selectVcs(repo: VCS, isName: boolean){
    
    if(isName){
        selectedVcs.value.set(repo.uuid, {name: true, uri: false})
    }else {
        selectedVcs.value.set(repo.uuid, {name: false, uri: true})
    }
    updatedName.value = repo.name
    updatedUri.value = repo.uri   
}
async function updateVcsRepository (uuid: string, isName: boolean) {
   
    selectedVcs.value.set(uuid, {name: false, uri: false})
    
    let updateVcsRepoParams = {
        uuid: uuid,
        name: updatedName.value,
        uri: updatedUri.value
    }
    await store.dispatch('updateVcsRepo', updateVcsRepoParams)
}
function resetSelectVcs (uuid: string, isName: boolean) {
    selectedVcs.value.set(uuid, {name: false, uri: false})
    updatedName.value = ''
    updatedUri.value = ''
}
function createdVcsRepo () {
    modalVcsOfOrgAddVcsRepo.value=false
    store.dispatch('fetchVcsRepos', orguuid.value)
}

const vcsRepoFields: any[] = [
    {
        key: 'name',
        title: 'Name',
        render(row: any){
            let els: any[] = []
            let selected: boolean = selectedVcs.value.get(row.uuid)?.name ?? false
            els.push(h('label', selected ? "" : row.name))
            if(selected){
                
                els.push(h(NInput,{
                    type: 'text',
                    defaultValue: updatedName.value,
                    'on-update:value': (value: string) => updatedName.value = value
                }))
            }

            if(userPermission.value && userPermission.value !== 'READ_ONLY'){
                if(!selected){
                    els.push(h(NIcon,{
                        title: 'Select New VCS Repository',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => selectVcs(row, true)
                    }, { default: () => h(EditIcon) }))
                }else{

                    els.push(h(NIcon,{
                        title: 'Save New VCS Repository',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => updateVcsRepository(row.uuid, true)
                    },{ default: () => h(Check) }))
                    
                    els.push(h(NIcon,{
                        title: 'Discard VCS Repository Change',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => resetSelectVcs(row.uuid, true)
                    },{ default: () => h(X) }))
                }
            }
            return h('div', {style: 'display: flex;'}, els)
        }
    },
    {
        key: 'uri',
        title: 'URI',
        render(row: any){
            let els: any[] = []
            let selected: boolean = selectedVcs.value.get(row.uuid)?.uri ?? false
            els.push(h('label', selected ? "" : row.uri))
            if(selected){
                
                els.push(h(NInput,{
                    type: 'text',
                    defaultValue: updatedUri.value,
                    'on-update:value': (value: string) => updatedUri.value = value
                }))
            }

            if(userPermission.value && userPermission.value !== 'READ_ONLY'){
                if(!selected){
                    els.push(h(NIcon,{
                        title: 'Select New VCS Repository',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => selectVcs(row, false)
                    }, { default: () => h(EditIcon) }))
                }else{

                    els.push(h(NIcon,{
                        title: 'Save New VCS Repository',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => updateVcsRepository(row.uuid, false)
                    },{ default: () => h(Check) }))
                    
                    els.push(h(NIcon,{
                        title: 'Discard VCS Repository Change',
                        class: 'icons clickable',
                        size: 25,
                        onClick: () => resetSelectVcs(row.uuid, false)
                    },{ default: () => h(X) }))
                }
            }
            return h('div', {style: 'display: flex;'}, els)
        }        
    },
    {
        key: 'actions',
        title: 'Actions',
        render(row: any) {
            const uuidCopy = [h('span', `UUID: ${row.uuid}`), h(
                NIcon,
                {
                    title: 'Copy UUID to Clipboard',
                    class: 'icons clickable',
                    size: 25,
                    onClick: () => copyToClipboard(row.uuid)
                },
                { default: () => h(Copy20Regular)}
            )]
            return  h(
                'div',
                [
                    h('a', {
                        href: 'https://' + row.uri,
                        rel: 'noopener noreferrer',
                        target: '_blank'
                    },
                    [
                        h(
                            NIcon,  
                            {
                                title: 'Open VCS Repository URI in New Window',
                                class: 'icons',
                                size: 25
                            }, 
                            { default: () => h(ExternalLink) }
                        )
                    ]),
                    h(
                        NIcon, 
                        {
                            title: 'View Connected Components',
                            class: 'icons clickable',
                            size: 25,
                            onClick: () => showConnectedComponentsModal(row.uuid)
                        }, 
                        { default: () => h(Eye) }),
                    h(
                        NTooltip, {
                            trigger: 'hover'
                        }, {trigger: () => h(NIcon,
                            {
                                class: 'icons',
                                size: 25,
                            }, { default: () => h(Info20Regular) }),
                        default: () =>  uuidCopy
                        }
                    )
                ]
            )
        }
    }
]

async function copyToClipboard (text: string) {
    try {
        navigator.clipboard.writeText(text);
        notify('info', 'Copied', `VCS Repository UUID Copied: ${text}`)
    } catch (error) {
        console.error(error)
    }
}

async function notify (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

</script>
  
  <style scoped lang="scss">
  .n-card {
    max-width: 70%;
    }
  .vcsRepoList {
      display: grid;
      grid-template-columns: 70px 1fr 2fr;
      border-radius: 9px;
      div {
          border-style: solid;
          border-width: thin;
          border-color: #edf2f3;
          padding-left: 8px;
      }
  }
  .vcsRepoList:hover {
      background-color: #d9eef3;
  }
  .vcsRepoHeader {
      background-color: #f9dddd;
      font-weight: bold;
  }
  
  .vcsName, .vcsUri {
      display: flex;
  }
  </style>
  