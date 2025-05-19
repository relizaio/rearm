<template>
    <div>
        <div class="giveUsAStar">
            <a href="https://github.com/relizaio/rebom"><vue-feather type="github"/></a>
        </div>
        <h1>Rebom - Catalog of Software Bills of Materials</h1>
        <div>
            <n-form 
                inline
                @submit="userSearch"
                >
                <n-input-group>
                    <n-input
                        class="leftText"
                        placeholder="BOM Search Query"
                        v-model:value="searchQuery"
                    />
                    <n-button
                        variant="contained-text"
                        @click="userSearch">
                        Find
                    </n-button>
                </n-input-group>
            </n-form>
        </div>
        <n-table style="margin-top: 30px;">
            <thead>
            <tr>
                <th class="text-left">
                Bom Version
                </th>
                <th class="text-left">
                Group
                </th>
                <th class="text-left">
                Name
                </th>
                <th class="text-left">
                Version
                </th>
                <th class="text-left">
                Actions
                </th>
            </tr>
            </thead>
            <tbody>
            <tr
                v-for="b in boms"
                :key="b.uuid"
            >
                <td class="text-left">{{ b.bomVersion }}</td>
                <td class="text-left">{{ b.group }}</td>
                <td class="text-left">{{ b.name }}</td>
                <td class="text-left">{{ b.version }}</td>
                <td class="text-left">
                    <a :href="'/restapi/bomById/' + b.uuid" target="_blank" rel="noopener noreferrer" title="Open Bom in New Tab">
                        <vue-feather type="eye"/>
                    </a>
                    <a :href="'/restapi/bomById/' + b.uuid + '?download=true'" target="_blank" rel="noopener noreferrer" title="Download Bom">
                        <vue-feather type="download"/>
                    </a>
                </td>
            </tr>
            </tbody>
        </n-table>
        <div style="margin-top: 10px; margin-left: 5px;" class="leftText" v-if="!boms || !boms.length">
            No BOMs found.
        </div>
    </div>
</template>

<script lang="ts">
import { NForm, NInput, NButton, NInputGroup, NTable } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import { ref } from 'vue';

export default {
    name: 'AppHome',
    components: {
      NForm, NInput, NButton, NInputGroup, NTable
    },
    props: { 
        queryValue: String
    },
    async setup(/*props : any, { emit } : any*/) {
        let bomSearchObj : BomSearch = {
            bomSearch: {
                serialNumber: '',
                version: '',
                componentVersion: '',
                componentGroup: '',
                componentName: '',
                singleQuery: ''
            }
        }
        let boms = ref(await searchBom(bomSearchObj))

        const headers = [
            {text: 'Bom Version', value: 'bomversion'}, 
            {text: 'Group', value: 'group'},
            {text: 'Name', value: 'name'}, 
            {text: 'Component Version', value: 'version'},
            {text: 'Actions', value: 'actions'}
        ]

        const bomsTest = [
            {
                bomversion: '1',
                group: '2',
                name: '3',
                version: '4',
                actions: '5'
            }
        ]

        const searchQuery = ref('')
        async function userSearch (e:any) {
            e.preventDefault()
            bomSearchObj = {
                bomSearch: {
                    serialNumber: '',
                    version: '',
                    componentVersion: '',
                    componentGroup: '',
                    componentName: '',
                    singleQuery: searchQuery.value
                }
            }
            boms.value = await searchBom(bomSearchObj)
        }

        return {
            boms,
            bomsTest,
            headers,
            userSearch,
            searchQuery
        }
    }
}

type BomSearch = {
  bomSearch: {
    serialNumber: string,
    version: string,
    componentVersion: string,
    componentGroup: string,
    componentName: string,
    singleQuery: string
  }
}

async function searchBom(bomSearch: BomSearch) {
    const response = await graphqlClient.query({
        query: gql`
            query findBom ($bomSearch: BomSearch) {
                findBom(bomSearch: $bomSearch) {
                    uuid
                    meta
                    version
                    bomVersion
                    group
                    name
                }
            }`,
        variables: bomSearch,
        fetchPolicy: 'no-cache'
    })
    return response.data.findBom
}

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
h3 {
  margin: 40px 0 0;
}
ul {
  list-style-type: none;
  padding: 0;
}
li {
  display: inline-block;
  margin: 0 10px;
}
a {
  color: #42b983;
  text-decoration: none;
}
.giveUsAStar {
    float: right;
    margin-right: 20px;
    display: flex;
}
.leftText {
    text-align: left;
}

.removeFloat {
    clear: both;
}
</style>
