<template>
    <div class="instanceHistoryBlock">
        <n-data-table :data="parsedHistory" :columns="historyFields" />
        <!-- <div class="historyHeader historyList">
            <div>
                Revision
                <n-icon @click="compareMode = !compareMode" size="20" class="icons clickable" title="Compare Instances"><LayoutColumns /></n-icon>
            </div>
            <div>Date</div>
            <div>Updated By</div>
            <div>What Changed</div>
            <div></div>
        </div>
        <div v-for="h in parsedHistory"
            class="historyList"
            :key="h.uuid">
            <div>
                <span v-if="compareMode">
                    <n-checkbox v-model:checked="comparisonCheckboxes[h.revision]" @click="handleComparison(h)" />
                </span>
                <span>{{ h.revision }}</span>
                <span v-if="h.instance === h.uuid">(live)</span>
            </div>
            <div>{{ h.date }}</div>
            <div>{{ h.updatedBy }}</div>
            <div>{{ h.type }}</div>
            <div>
                <a :href="'/api/manual/v1/instanceRevision/cyclonedxExport/' + h.instance + '/' + h.revision" target="_blank" rel="noopener noreferrer">
                    <n-icon class="clickable icons" title="Show as CycloneDX JSON" size="20"><Download /></n-icon>
                </a>
                <n-icon v-if="h.revision !== parsedHistory[parsedHistory.length - 1].revision" @click="showRevisionModal(h)" class="ml-2 clickable" title="View Side-by-Side Comparison With Previous Revision" size="20"><Eye /></n-icon>
            </div>
        </div> -->
        <n-modal
                :on-after-leave="resetComparison"
                v-model:show="showRevisionComparisonModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%;"
                :title="(revisionsToCompare && revisionsToCompare.length > 1) ? `Revision ${revisionsToCompare[1].revision} of ${instance.uri}, recorded on ${revisionsToCompare[1].date} Updated by: ${(revisionsToCompare[1].updatedBy ? revisionsToCompare[1].updatedBy : revisionsToCompare[1].type)}. Changes: ${(revisionsToCompare[1].type.length ? revisionsToCompare[1].type.toString() : 'Not Determined')}` : 'Loading'"
            >
            <side-by-side
                :instanceLeft="props.instanceUuid"
                :revisionLeft="(revisionsToCompare && revisionsToCompare.length) ? revisionsToCompare[0].revision : ''"
                :instanceRight="props.instanceUuid"
                :revisionRight="(revisionsToCompare && revisionsToCompare.length) ? revisionsToCompare[1].revision : ''"/>
        </n-modal>
    </div>
</template>

<script lang="ts">
export default {
    name: 'InstanceHistory'
}
</script>
<script lang="ts" setup>
import { computed, ComputedRef, ref, Ref, h } from 'vue'
import { useStore } from 'vuex'
import { NIcon, NCheckbox, NModal, NDataTable } from 'naive-ui'
import SideBySide from '@/components/SideBySide.vue'
import { Eye, Download, LayoutColumns } from '@vicons/tabler'
const props = defineProps<{
    instanceUuid: String,
    history: Array,
    orgProp: String
}>()
const store = useStore()

const compareMode = ref(false)
const comparisonCheckboxes: Ref<any> = ref({})
const instance: ComputedRef<any> = computed((): any => {
    return store.getters.instanceById(props.instanceUuid, -1)
})
const parsedHistory: Ref<any[]> = ref([])
const users: Ref<any[]> = ref([])
const revisionsToCompare: Ref<any[]> = ref([])

const showRevisionComparisonModal = ref(false)

const displayUser = function (userId: string) {
    let displayRes = userId
    let user = users.value.find(u => (u.uuid === userId))
    if (user) {
        displayRes = user.name
    }
    return displayRes
}

const handleComparison = function (revision: any) {
    if (revisionsToCompare.value.length > 1) revisionsToCompare.value = []
    // check if revision to compare already has this revision
    const existingRev = revisionsToCompare.value.find((x: any) => (x.revision === revision.revision - 1))
    if (!existingRev || existingRev.revision !== revision.revision) {
        revisionsToCompare.value.push(revision)
    }
    if (revisionsToCompare.value.length > 1) {
        revisionsToCompare.value.sort((a, b) => {
            if (a.revision < b.revision) {
                return -1
            } else if (a.revision > b.revision) {
                return 1
            } else {
                return 0
            }
        })

        showRevisionComparisonModal.value = true
    }
}

const resetComparison = function () {
    revisionsToCompare.value = []
    // uncheck checkboxes
    Object.keys(comparisonCheckboxes.value).forEach(checkbox => {
        comparisonCheckboxes.value[checkbox] = false
    })
}

const showRevisionModal = function (h: any) {
    // locate previous revision
    let prevRevision = parsedHistory.value[parsedHistory.value.findIndex(x => (x.revision === h.revision)) + 1]
    if (prevRevision && (prevRevision.revision || prevRevision.revision === 0)) {
        revisionsToCompare.value = [prevRevision, h]
        showRevisionComparisonModal.value = true
    }
}

const onCreate = async function () {
    // get users
    users.value = store.getters.allUsers
    // parse history
    props.history.forEach((h: any) => {
        const parsedHist = {
            instance: h.instance,
            uuid: h.uuid,
            revision: h.revision,
            date: (new Date(h.dateActual)).toLocaleString('en-CA'),
            updatedBy: h.lastUpdatedBy ? displayUser(h.lastUpdatedBy) : h.updateType,
            type: ''
        }
        let type = h.type
        if (!type) {
            type = 'Not Determined'
        } else if (type.toString().includes('PRODUCT_RELEASE') || type.toString().includes('TARGET_RELEASE')) {
            type = type.toString().replace('TARGET_RELEASE', 'Target Release')
            type = type.toString().replace('PRODUCT_RELEASE', 'Product Release')
        } else {
            type = h.type.toString()
        }
        parsedHist['type'] = type
        parsedHistory.value.push(parsedHist)
    })
}
const historyFields: any = [
    {
        key: 'revision',
        title: () => {
            let els: any = []
            els.push('Revision')
            els.push(h(NIcon, {
                title: 'Compare Instances',
                class: 'icons clickable',
                size: 20,
                onClick: () => compareMode.value = !compareMode.value
            }, {default: () => h(LayoutColumns)}))
            return els
        },
        render: (row: any) => {
            let els: any = []
            if(compareMode.value){
                els.push(h(NCheckbox, {
                    checked: comparisonCheckboxes.value[row.revision],
                    onClick: () => handleComparison(row),
                    'on-update:checked': () => comparisonCheckboxes.value[row.revision] = !comparisonCheckboxes.value[row.revision]
                }))
            }
            els.push(row.revision)
            if(row.instance === row.uuid){
                els.push('(live)')
            }
            return els
        }
    },
    {
        key: 'date',
        title: 'Date',
        render: (row: any) => row.date
    },
    {
        key: 'updated_by',
        title: 'Updated By',
        render: (row: any) => row.updatedBy
    },
    {
        key: 'type',
        title: 'What Changed',
        render: (row: any) => row.type
    },
    {
        key: 'controls',
        title: '',
        render: (row: any) => {
            let els: any = []

            els.push(h('a', {
                href: '/api/manual/v1/instanceRevision/cyclonedxExport/' + row.instance + '/' + row.revision,
                rel: 'noopener noreferrer',
                target: '_blank'
            },
            [
                h(
                    NIcon,
                    {
                        title: 'Show as CycloneDX JSON',
                        class: 'icons clickable',
                        size: 24
                    },
                    { default: () => h(Download) }
                )
            ]))
            if(row.revision !== parsedHistory.value[parsedHistory.value.length - 1].revision){
                els.push(
                    h(
                        NIcon,
                        {
                            title: 'View Side-by-Side Comparison With Previous Revision',
                            class: 'icons clickable',
                            size: 24,
                            onClick: () => showRevisionModal(row)
                        },
                        { default: () => h(Eye) }
                    )
                )
            }
            
            
            return els
        }
    },
]

await onCreate()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.envBlock {
    margin-bottom: 10px;
    h6 {
        display: inline;
        margin-right: 10px;
    }
}
.icons {
    margin-left: 10px;
}

.historyList {
    display: grid;
    grid-template-columns: 100px repeat(3, 1fr) 70px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}

.historyList:hover {
    background-color: #d9eef3;
}
.listHeaderText, .releaseHeader, .propertyHeader, .historyHeader, .productHeader {
    background-color: #f9dddd;
    font-weight: bold;
}

.listHeaderText {
    margin-top: 0.7rem;
    text-align: center;
}
</style>