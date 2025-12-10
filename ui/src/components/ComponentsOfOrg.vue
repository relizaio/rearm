<template>
    <div>
        <div class="componentDisplayWrapper">
            <div class="componentListColumn">
                <h4 v-if="organization">{{ componentProductWords.componentsFirstUpper }} of {{organization.name}}</h4>
                <vue-feather
                    :style="{visibility: isWritable ? 'visible' : 'hidden'}"
                    class="clickable"
                    type="plus-circle"
                    @click="showAddComponentModal = true"
                    :title="'Add ' + componentProductWords.componentFirstUpper"
                />
                <vue-feather
                    class="clickable"
                    type="search"
                    @click="showSearchInput= !showSearchInput"
                    :title="'Search ' + componentProductWords.componentFirstUpper"
                />
            
                <n-collapse-transition :show="showSearchInput">
                    <n-input round clearable @clear="showSearchInput=false" autofocus @input="filterComponents" :style="{ 'max-width': '90%' }" :placeholder="'Search ' + componentProductWords.componentsFirstUpper" >
                            <template #suffix>
                                <vue-feather type="search"/>
                            </template>
                    </n-input>
                </n-collapse-transition>
                        
             
                <n-modal
                    v-model:show="showAddComponentModal"
                    preset="dialog"
                    :show-icon="false"
                    style="width: 90%"
                    :title="'Add New ' + componentProductWords.componentFirstUpper"
                >
                    <create-component
                                class="addComponent"
                                v-if="orguuid"
                                :orgProp="orguuid"
                                :isProduct="isProduct"
                                :isHideTitle="true"
                                @componentCreated="componentCreated" />
                </n-modal>
                <n-data-table
                    :columns="componentFields"
                    :data="components"
                    :pagination="componentPagination"
                    :row-props="rowProps"
                    :row-class-name="rowClassName"
                    size="small"
                    @update:filters="handleUpdateFilter"
                    @update:page="handlePageChange"
                />
            </div>
            <div class="componentDetailColumn">
                <component-view v-if="selectedProjUuid"/>
            </div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ComponentOfOrg'
}
</script>
<script lang="ts" setup>import { ComputedRef, ref, Ref, computed, reactive, h, watch, onMounted } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NModal, NDataTable, DataTableBaseColumn, DataTableColumns, DataTableFilterState, NCollapseTransition, NInput } from 'naive-ui'
import commonFunctions from '@/utils/commonFunctions'
import CreateComponent from './CreateComponent.vue'
import ComponentView from './ComponentView.vue'



const route = useRoute()
const router = useRouter()
const store = useStore()

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
onMounted(() => {
    if (myorg.value) 
        initLoad(false)
})
watch(myorg, (currentValue, oldValue) => {
    initLoad(true)
});
const orguuid : Ref<string> = ref('')
if (route.params.orguuid) {
    orguuid.value = route.params.orguuid.toString()
} else {
    orguuid.value = myorg.value
}

const myUser = store.getters.myuser
const isWritable : boolean = commonFunctions.isWritable(orguuid.value, myUser, 'COMPONENT')
const showAddComponentModal = ref(false)

const selectedProjUuid : Ref<string> = ref('')
if (route.params.compuuid) {
    selectedProjUuid.value = route.params.compuuid.toString()
}

// Keep selectedProjUuid in sync with route params
watch(() => route.params.compuuid, (newCompuuid) => {
    if (newCompuuid) {
        selectedProjUuid.value = newCompuuid.toString()
    } else {
        selectedProjUuid.value = ''
    }
})

const isProduct : boolean = (route.name === 'ProductsOfOrg')

const organization = store.getters.orgById(orguuid.value)

const componentProductWords : any = {
    componentFirstUpper: (!isProduct) ? 'Component' : 'Product',
    componentsFirstUpper: (!isProduct) ? 'Components' : 'Products'
}

const components: ComputedRef<any[]> = computed((): any => (isProduct ? store.getters.productsOfOrg(orguuid.value) : store.getters.componentsOfOrg(orguuid.value)))

async function initLoad (force: boolean) {
    if (force || !components.value || !components.value.length || components.value.length < 2) {
        if (isProduct) {
            await store.dispatch('fetchProducts', orguuid.value)
        } else {
            await store.dispatch('fetchComponents', orguuid.value)
        }
    }
}

async function componentCreated (uuid: string) {
    showAddComponentModal.value = false
    await initLoad(true)
    selectComponent(uuid)
}

async function selectComponent (uuid: string) {
    router.push({
        name: (!isProduct) ? 'ComponentsOfOrg' : 'ProductsOfOrg',
        params: {
            orguuid: orguuid.value,
            compuuid: uuid
        }
    })
}

const nameField = reactive<DataTableBaseColumn<any>>({
    key: 'name',
    title: componentProductWords.componentFirstUpper + ' Name',
    filterMultiple: false,
    filterOptionValue: null,
    sorter: false,
    filter (value, row) {
        return !!~row.name.toLowerCase().indexOf(value.toString().toLocaleLowerCase())
    }
})

const componentFields: DataTableColumns<any> = [
    nameField
]

const rowProps = (row: any) => {
    return {
        style: 'cursor: pointer;',
        onClick: () => {
            selectComponent(row.uuid)
        }
    }
}
const rowClassName = (row: any) => {
    if(selectedProjUuid.value === row.uuid)
        return 'selectedRow'
    else
        return ''
}

const filterComponents = async function(value: string){
    nameField.filterOptionValue = value
}
const handleUpdateFilter = async function (
    filters: DataTableFilterState,
    sourceColumn: DataTableBaseColumn
) {
    nameField.filterOptionValue = filters[sourceColumn.key] as string
}
const showSearchInput: Ref<boolean> = ref(false)

const componentPagination = reactive({
    page: 1,
    pageSize: 10
})

const handlePageChange = (page: number) => {
    componentPagination.page = page
}

// Set initial page based on selected component (for deep links)
if (selectedProjUuid.value && components.value && components.value.length) {
    const indexOfComponent = components.value.findIndex(component => component.uuid === selectedProjUuid.value)
    if (indexOfComponent >= 0) {
        componentPagination.page = Math.ceil((indexOfComponent + 1) / componentPagination.pageSize)
    }
}

// Also set page when components load after initial mount
watch(components, (newComponents) => {
    if (selectedProjUuid.value && newComponents && newComponents.length && componentPagination.page === 1) {
        const indexOfComponent = newComponents.findIndex(component => component.uuid === selectedProjUuid.value)
        if (indexOfComponent >= 0) {
            const targetPage = Math.ceil((indexOfComponent + 1) / componentPagination.pageSize)
            if (targetPage !== componentPagination.page) {
                componentPagination.page = targetPage
            }
        }
    }
}, { once: true })

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.componentDisplayWrapper {
    display: grid;
    grid-template-columns: 0.9fr 4fr;
    grid-gap: 10px;
}
.componentList {
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 8px;
    }
}
.componentList:hover {
    background-color: #d9eef3;
}
.componentHeader {
    background-color: #f9dddd;
    font-weight: bold;
}
.selectedComponent {
    background-color: #c4c4c4;
}
:deep(.selectedRow td){
    background-color: #f1f1f1 !important;
}
</style>
