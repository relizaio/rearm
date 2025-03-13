<template>
    <div>
        <h5>Marketing Releases</h5>
        <Icon v-if="userPermission !== 'READ_ONLY' && userPermission !== ''" @click="modalMrktReleasesAddMrktRelease = true" size="30" class="clickable" title="Add Marketing Release"><CirclePlus/></Icon>  

        <n-data-table 
            :columns="mrktReleasesFields"
            :data="mrktReleases"
        />

        <n-modal
            preset="dialog"
            :show-icon="false"
            v-model:show="modalMrktReleasesAddMrktRelease">
            <n-card 
                style="width: 600px"
                size="huge"
                title="Create Marketing Release"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <create-marketing-release @marketingReleaseCreated="marketingReleaseCreated" :orgProp="orguuid" :componentProp="component" />
            </n-card>
        </n-modal>
        <n-modal 
            style="min-height: 95vh; background-color: white;" 
            v-model:show="showMarketingReleaseModal" 
            title="Marketing Release View"
            preset="dialog"
            :show-icon="false" >
            <marketing-release-view :uuidprop="showMarketingReleaseUuid" @closeMarketingRelease="showMarketingReleaseModal=false" />
        </n-modal>
        <!-- n-modal
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
                        <li><router-link :to="{ name: 'Component', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link></li>
                    </div>
                </div>
                <div v-else>
                    No Components Connected with this repo
                </div>
            </n-card>
        </n-modal -->
    </div>
</template>

<script lang="ts">
export default {
    name: 'MrktReleasesOfComponent'
}
</script>

<script lang="ts" setup>
import { NInput, NModal, NCard, NDataTable, DataTableBaseColumn, DataTableColumns, NIcon  } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed, Component, toRefs } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { CirclePlus, Edit as EditIcon, ExternalLink, Eye, Check, X } from '@vicons/tabler'
import { Icon } from '@vicons/utils'
import CreateMarketingRelease from '@/components/CreateMarketingRelease.vue'
import MarketingReleaseView from '@/components/MarketingReleaseView.vue'
import commonFunctions from '@/utils/commonFunctions'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'

const props = defineProps({
    component: String
})

const {component} = toRefs(props)

const route = useRoute()
const store = useStore()

const myorg: ComputedRef<string> = computed((): any => store.getters.myorg)

const orguuid : Ref<string> = ref('')
if (route.params.orguuid) {
    orguuid.value = route.params.orguuid.toString()
} else {
    orguuid.value = myorg.value
}

const organization = store.getters.orgById(orguuid.value)
const mrktReleases: Ref<any[]> = ref([])

const modalMrktReleasesAddMrktRelease = ref(false)
const showMarketingReleaseModal: Ref<boolean> = ref(false)
const showMarketingReleaseUuid: Ref<string> = ref('')
async function showMarketingRelease(uuid: string) {
    showMarketingReleaseUuid.value = uuid
    showMarketingReleaseModal.value = true
}

async function loadMarketingReleases() {
    const componentUuid = component ? component.value : ''
    const mrktReleaseResponse = await graphqlClient.query({
        query: gql`
            query marketingReleases($componentUuid: ID!) {
                marketingReleases(componentUuid: $componentUuid) {
                    uuid
                    version
                    lifecycle
                }
            }
            `,
        variables: {
            componentUuid
        },
        fetchPolicy: 'no-cache'
    })
    mrktReleases.value = mrktReleaseResponse.data.marketingReleases
}

function marketingReleaseCreated () {
    loadMarketingReleases()
    modalMrktReleasesAddMrktRelease.value = false
}

const userPermission = ref('')
userPermission.value = commonFunctions.getUserPermission(orguuid.value, store.getters.myuser).org
function renderIcon (icon: Component) {
    return () => h(NIcon, null, { default: () => h(icon) })
}
const mrktReleasesFields: any[] = [
    {
        key: 'version',
        title: 'Version',
        render: (row: any) => {
            return h('div', [h('a', {
                onClick: (e: Event) => {
                    e.preventDefault()
                    showMarketingRelease(row.uuid)
                },
                href: '#'
            }, [h('span', row.version)]
            )])
        }
    },
    {
        key: 'lifecycle',
        title: 'Lifecycle'
    }
]

loadMarketingReleases()
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
  