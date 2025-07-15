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
                        <a href="#" @click="downloadArtifact(b)" title="Download Bom">
                            <vue-feather type="download"/>
                        </a>
                        <a href="#" @click="downloadCSV(b)" title="Download Bom CSV">
                            <vue-feather type="table"/>
                        </a>
                        <a href="#" @click="downloadExcel(b)" title="Download Bom Excel">
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

<script setup lang="ts">
import { NForm, NInput, NButton, NInputGroup, NTable } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import { ref } from 'vue';

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

const props = defineProps({
  queryValue: String
})

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

async function downloadArtifact(bom: any) {
    try {
        // Using GraphQL bomById query
        const response = await graphqlClient.query({
            query: gql`
                query getBomById($id: ID, $org: ID) {
                    bomById(id: $id, org: $org)
                }
            `,
            variables: { id: 'c76dad06-aa79-47f0-8e21-030e61f652e7', org: '00000000-0000-0000-0000-000000000001' },
            fetchPolicy: 'no-cache'
        });
        
        // Extract the BOM content
        const bomContent = response.data.bomById;
        const bomName = response.data.bomById.metadata.component.name || 'bom';
        const bomVersion = response.data.bomById.metadata.component.version || '';
        const fileName = `${bomName}-${bomVersion}.json`;
        
        // Create a download link
        const blob = new Blob([JSON.stringify(bomContent)], { type: 'application/json' });
        const link = document.createElement('a');
        link.href = window.URL.createObjectURL(blob);
        link.download = fileName;
        link.click();
    } catch (error) {
        console.error('Error downloading BOM:', error);
    }
}

async function downloadCSV(bom: any) {
    try {
        // Using GraphQL bomById query
        const response = await graphqlClient.query({
            query: gql`
                query getBomByIdCsv($id: ID, $org: ID) {
                    bomByIdCsv(id: $id, org: $org)
                }
            `,
            variables: { id: 'c76dad06-aa79-47f0-8e21-030e61f652e7', org: '00000000-0000-0000-0000-000000000001' },
            fetchPolicy: 'no-cache'
        });
        
        // Extract the BOM content
        const bomContent = response.data.bomByIdCsv;
        // const bomName = response.data.bomByIdCsv.metadata.component.name || 'bom';
        // const bomVersion = response.data.bomByIdCsv.metadata.component.version || '';
        const fileName = '1.csv';
        
        // Create a download link
        const blob = new Blob([bomContent], { type: 'text/csv' });
        const link = document.createElement('a');
        link.href = window.URL.createObjectURL(blob);
        link.download = fileName;
        link.click();
    } catch (error) {
        console.error('Error downloading BOM:', error);
    }
}

async function downloadExcel(bom: any) {
    try {
        // Using GraphQL bomById query
        const response = await graphqlClient.query({
            query: gql`
                query getBomByIdExcel($id: ID, $org: ID) {
                    bomByIdExcel(id: $id, org: $org)
                }
            `,
            variables: { id: 'c76dad06-aa79-47f0-8e21-030e61f652e7', org: '00000000-0000-0000-0000-000000000001' },
            fetchPolicy: 'no-cache'
        });
        
        // Extract the BOM content
        const bomContent = response.data.bomByIdExcel;
        // const bomName = response.data.bomByIdCsv.metadata.component.name || 'bom';
        // const bomVersion = response.data.bomByIdCsv.metadata.component.version || '';
        const fileName = '1.xlsx';
        
        // Create a download link
        const binary = atob(bomContent);
        const array = Uint8Array.from(binary, c => c.charCodeAt(0));
        const blob = new Blob([array], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
        const link = document.createElement('a');
        link.href = window.URL.createObjectURL(blob);
        link.download = fileName;
        link.click();
    } catch (error) {
        console.error('Error downloading BOM:', error);
    }
}

const searchQuery = ref('')
const boms = ref<any[]>([])

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

async function userSearch(e: Event) {
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

// Initialize data
boms.value = await searchBom(bomSearchObj)

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
