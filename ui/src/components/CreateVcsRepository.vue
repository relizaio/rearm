<template>
    <div class="createVcsRepository">
      <n-form>
          <n-form-item id="vcsrepo_create_magic_input_group"
                        label="Paste your repository URI"
                        label-for="vcsrepo_create_magic_input"
                        description="URI of your VCS repository">
              <n-input
                      id ="vcsrepo_create_magic_input"
                      v-model:value="magicInput"
                      v-on:paste="parseMagicInput(100)"
                      v-on:blur="parseMagicInput(0)"
                      required
                      placeholder="VCS Repository URI" />
          </n-form-item>
          <n-form-item
                      v-if="magicInput"
                      id="vcsrepo_create_name_group"
                      label="Name"
                      label-for="vcsrepo_create_name"
                      description="Enter name of your vcs repository">
              <n-input
                      id ="vcsrepo_create_name"
                      v-model:value="vcsrepo.name"
                      required
                      placeholder="Enter VCS repository name" />
          </n-form-item>
          <n-form-item
                      v-if="!props.orguuid"
                      label="Parent Organization"
                      label-for="artifact_create_org"
                      description="Select organization">
              <n-select
                      v-model:value="vcsrepo.org"
                      :options="orgs" />
          </n-form-item>
          <n-form-item
                      v-if="magicInput"
                      id="vcsrepo_create_uri_group"
                      label="URI"
                      label-for="vcsrepo_create_uri"
                      description="Enter URI of your vcs repository">
              <n-input
                      id ="vcsrepo_create_uri"
                      v-model:value="vcsrepo.uri"
                      placeholder="Enter VCS repository URI" />
          </n-form-item>
          <n-form-item
                      v-if="magicInput"
                      id="vcsrepo_create_type_group"
                      label="VCS Type"
                      label-for="vcsrepo_create_type"
                      description="Enter type of your vcs repository">
              <n-select
                      id ="vcsrepo_create_type"
                      v-model:value="vcsrepo.type"
                      placeholder="Enter VCS repository type"
                      :options="typeSelection" />
          </n-form-item>
          <n-button type="primary" @click="onSubmit()">Create VCS Repository</n-button>
          <n-button type="error" @click="resetForm()">Reset VCS Repository Input</n-button>
      </n-form>
  
    </div>
</template>
  
<script lang="ts">
export default {
    name: 'CreateVcsRepository'
}
</script>

<script lang="ts" setup>
import { ComputedRef, ref, computed } from 'vue'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import { NInput, NForm, NButton, NFormItem, NSelect  } from 'naive-ui'

const store = useStore()
const emit = defineEmits(['createdVcsRepo'])
const props = defineProps<{
    orguuid?: string
}>()

const vcsTypes = ref([])

const magicInput = ref('')
const vcsuuid = ref('')
const vcsrepo = ref({
    name: '',
    org: props.orguuid,
    uri: '',
    type: ''
})

async function getTypes(){
    const response = await graphqlClient.query({
        query: gql`
            query vcsRepositoryTypes {
                vcsRepositoryTypes
            }`
    })
    vcsTypes.value = response.data.vcsRepositoryTypes
}

function onSubmit(){
    store.dispatch('createVcsRepo', vcsrepo.value).then(response => {
        vcsuuid.value = response.uuid
        vcsrepo.value.name = response.name
        vcsrepo.value.type = response.type
        emit('createdVcsRepo', vcsuuid.value)
    })
}
function resetForm(){
    vcsrepo.value.name = ''
    vcsrepo.value.org = props.orguuid
    vcsrepo.value.uri = ''
    vcsrepo.value.type = ''
    magicInput.value = ''
}
function parseMagicInput(timeout: number){
    setTimeout(() => {
    // strip git ending
        let gitEnding = /\.git$/
        magicInput.value = magicInput.value.replace(gitEnding, '')
        if (magicInput.value.includes('bitbucket.org/')) {
            vcsrepo.value.type = 'Git'
            magicInput.value = magicInput.value.split('/src/')[0]
            vcsrepo.value.uri = magicInput.value
            vcsrepo.value.name = magicInput.value.split('bitbucket.org/')[1]
        } else if (magicInput.value.includes('github.com/')) {
            vcsrepo.value.type = 'Git'
            magicInput.value = magicInput.value.split('/tree/')[0]
            vcsrepo.value.uri = magicInput.value
            vcsrepo.value.name = magicInput.value.split('github.com/')[1]
        } else if (magicInput.value.includes('gitlab.com/')) {
            vcsrepo.value.type = 'Git'
            magicInput.value = magicInput.value.split('/-/')[0]
            vcsrepo.value.uri = magicInput.value
            vcsrepo.value.name = magicInput.value.split('gitlab.com/')[1]
        } else if (magicInput.value.includes('dev.azure.com/')) {
            vcsrepo.value.type = 'Git'
            vcsrepo.value.uri = magicInput.value
            vcsrepo.value.name = magicInput.value.split('dev.azure.com/')[1]
        }
    }, timeout)
}

const orgs: ComputedRef<any> = computed((): any => {
    let storeOrgs = store.getters.allOrganizations
    return storeOrgs.map(function (org: { name: string; uuid: string; }) {
        let orgObj = {
            text: org.name,
            value: org.uuid
        }
        return orgObj
    })
})
const typeSelection: ComputedRef<any> = computed((): any => {
    if (!vcsTypes.value.length) {
        getTypes()
    }
    if (vcsTypes.value.length) {
        let retSelection: { label: string; value: string; }[] = []
        vcsTypes.value.forEach(el => {
            let retObj = {
                label: el,
                value: el
            }
            retSelection.push(retObj)
        })
        return retSelection
    } else {
        return []
    }
})

</script>
  
<style scoped lang="scss">
.createVcsRepository {
    width: 500px;
    margin-left: 20px;
}
</style>
  