<template>
    <div>
        <h5 style="display: inline-block;">Marketing Releases</h5>
        <Icon v-if="isWritable" @click="modalMrktReleasesAddMrktRelease = true" size="24" class="clickable" title="Add Marketing Release" style="margin-left: 10px; vertical-align: middle;"><CirclePlus/></Icon>  

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
            preset="dialog"
            :show-icon="false" >
            <marketing-release-view :uuidprop="showMarketingReleaseUuid" @closeMarketingRelease="showMarketingReleaseModal=false" />
        </n-modal>
        <n-modal 
            style="min-height: 95vh; background-color: white;" 
            v-model:show="showReleaseModal"
            preset="dialog"
            :show-icon="false"
            :on-after-leave="closeReleaseModal">
            <release-view :uuidprop="showReleaseUuid" @closeRelease="closeReleaseModal" />
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
import { NInput, NModal, NCard, NDataTable, DataTableBaseColumn, DataTableColumns, NIcon, NTooltip  } from 'naive-ui'
import { ComputedRef, h, ref, Ref, computed, Component, toRefs } from 'vue'
import { useStore } from 'vuex'
import { useRoute, RouterLink } from 'vue-router'
import { CirclePlus, Edit as EditIcon, ExternalLink, Eye, Check, X, QuestionMark } from '@vicons/tabler'
import { Icon } from '@vicons/utils'
import CreateMarketingRelease from '@/components/CreateMarketingRelease.vue'
import MarketingReleaseView from '@/components/MarketingReleaseView.vue'
import ReleaseView from '@/components/ReleaseView.vue'
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

const mrktReleases: Ref<any[]> = ref([])
const marketingReleaseLifecycles: Ref<any[]> = ref([])

const modalMrktReleasesAddMrktRelease = ref(false)
const showMarketingReleaseModal = ref(false)
const showMarketingReleaseUuid = ref('')
const showReleaseModal = ref(false)
const showReleaseUuid = ref('')

function showMarketingRelease(uuid: string) {
    showMarketingReleaseUuid.value = uuid
    showMarketingReleaseModal.value = true
}

function showRelease(uuid: string) {
    showReleaseUuid.value = uuid
    showReleaseModal.value = true
}

function closeReleaseModal() {
    showReleaseModal.value = false
    showReleaseUuid.value = ''
}

async function fetchLifecycles() {
    const resp = await graphqlClient.query({
        query: gql`
            query marketingReleaseLifecycles {
                marketingReleaseLifecycles {
                    lifecycle
                    suffix
                    prettyName
                    ordinal
                }
            }
            `
    })
    marketingReleaseLifecycles.value = resp.data.marketingReleaseLifecycles
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
                    events {
                        release
                        releaseDetails {
                            version
                            marketingVersion
                        }
                        date
                    }
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
        title: 'Lifecycle',
        render: (row: any) => {
            const lifecycle = marketingReleaseLifecycles.value.find((l: any) => l.lifecycle === row.lifecycle)
            return lifecycle?.prettyName || row.lifecycle
        }
    },
    {
        key: 'marketingVersion',
        title: () => {
            return h('div', { style: 'display: flex; align-items: center; gap: 4px;' }, [
                h('span', 'Last Released Version'),
                h(
                    NTooltip,
                    {},
                    {
                        trigger: () => h(
                            NIcon,
                            {
                                size: 16,
                                style: 'cursor: help;'
                            },
                            () => h(QuestionMark)
                        ),
                        default: () => 'Marketing version from the most recent release event'
                    }
                )
            ])
        },
        render: (row: any) => {
            if (!row.events || row.events.length === 0) {
                return 'N/A'
            }
            const mostRecentEvent = row.events[row.events.length - 1]
            return mostRecentEvent.releaseDetails?.marketingVersion || 'N/A'
        }
    },
    {
        key: 'devReleaseVersion',
        title: () => {
            return h('div', { style: 'display: flex; align-items: center; gap: 4px;' }, [
                h('span', 'Last Released Dev Version'),
                h(
                    NTooltip,
                    {},
                    {
                        trigger: () => h(
                            NIcon,
                            {
                                size: 16,
                                style: 'cursor: help;'
                            },
                            () => h(QuestionMark)
                        ),
                        default: () => 'Development release version from the most recent release event'
                    }
                )
            ])
        },
        render: (row: any) => {
            if (!row.events || row.events.length === 0) {
                return 'N/A'
            }
            const mostRecentEvent = row.events[row.events.length - 1]
            if (!mostRecentEvent.release || !mostRecentEvent.releaseDetails?.version) {
                return 'N/A'
            }
            return h('a', {
                onClick: (e: Event) => {
                    e.preventDefault()
                    showRelease(mostRecentEvent.release)
                },
                href: '#',
                style: 'cursor: pointer;'
            }, mostRecentEvent.releaseDetails.version)
        }
    }
]

const isWritable: ComputedRef<boolean> = computed((): any => (userPermission.value === 'READ_WRITE' || userPermission.value === 'ADMIN'))

fetchLifecycles()
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
  