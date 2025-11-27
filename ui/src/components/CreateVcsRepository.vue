<template>
    <div class="createVcsRepository">
      <n-form ref="formRef" :model="vcsrepo" :rules="rules">
          <n-form-item id="vcsrepo_create_magic_input_group"
                        label="Paste your repository URI"
                        label-for="vcsrepo_create_magic_input"
                        path="uri">
              <n-input
                      id ="vcsrepo_create_magic_input"
                      v-model:value="magicInput"
                      v-on:paste="parseMagicInput(100)"
                      v-on:blur="parseMagicInput(0)"
                      placeholder="VCS Repository URI" />
          </n-form-item>
          <n-form-item
                      v-if="magicInput"
                      id="vcsrepo_create_name_group"
                      label="Name"
                      label-for="vcsrepo_create_name"
                      path="name">
              <n-input
                      id ="vcsrepo_create_name"
                      v-model:value="vcsrepo.name"
                      placeholder="Enter VCS repository name" />
          </n-form-item>
          <n-form-item
                      v-if="!props.orguuid"
                      label="Parent Organization"
                      label-for="artifact_create_org"
                      path="org">
              <n-select
                      v-model:value="vcsrepo.org"
                      :options="orgs" />
          </n-form-item>
          <n-form-item
                      v-if="magicInput"
                      id="vcsrepo_create_uri_group"
                      label="URI"
                      label-for="vcsrepo_create_uri"
                      path="uri">
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
                      path="type">
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
import { NInput, NForm, NButton, NFormItem, NSelect, FormInst, FormRules } from 'naive-ui'

const store = useStore()
const emit = defineEmits(['createdVcsRepo'])
const props = defineProps<{
    orguuid?: string
}>()

const vcsTypes = ref([])

const formRef = ref<FormInst | null>(null)
const magicInput = ref('')
const vcsuuid = ref('')
const vcsrepo = ref({
    name: '',
    org: props.orguuid,
    uri: '',
    type: ''
})

const rules: FormRules = {
    name: {
        required: true,
        message: 'Name is required',
        trigger: ['blur', 'input']
    },
    org: {
        required: true,
        message: 'Organization is required',
        trigger: ['blur', 'change']
    },
    uri: {
        required: true,
        message: 'URI is required',
        trigger: ['blur', 'input']
    },
    type: {
        required: true,
        message: 'VCS Type is required',
        trigger: ['blur', 'change']
    }
}

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
    formRef.value?.validate((errors) => {
        if (errors) {
            return
        }
        store.dispatch('createVcsRepo', vcsrepo.value).then(response => {
            vcsuuid.value = response.uuid
            vcsrepo.value.name = response.name
            vcsrepo.value.type = response.type
            emit('createdVcsRepo', vcsuuid.value)
        })
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
        // Normalize SSH-like URLs (e.g., git@github.com:owner/repo) to https for parsing
        let input = magicInput.value.trim()
        const sshLike = /^(?:git@|ssh:\/\/git@)([^:]+):(.+)$/.exec(input)
        if (sshLike) {
            const host = sshLike[1]
            const path = sshLike[2]
            input = `https://${host}/${path}`
        }
        // Ensure a scheme for URL parsing
        if (!/^https?:\/\//i.test(input)) {
            input = `https://${input}`
        }
        try {
            const url = new URL(input)
            // Clean provider-specific path suffixes
            let cleanedPath = url.pathname
            if (url.hostname === 'bitbucket.org') {
                const idx = cleanedPath.indexOf('/src/')
                if (idx !== -1) cleanedPath = cleanedPath.substring(0, idx)
                vcsrepo.value.type = 'Git'
                vcsrepo.value.uri = `${url.origin}${cleanedPath}`
                vcsrepo.value.name = cleanedPath.replace(/^\//, '')
            } else if (url.hostname === 'github.com') {
                const idx = cleanedPath.indexOf('/tree/')
                if (idx !== -1) cleanedPath = cleanedPath.substring(0, idx)
                vcsrepo.value.type = 'Git'
                vcsrepo.value.uri = `${url.origin}${cleanedPath}`
                vcsrepo.value.name = cleanedPath.replace(/^\//, '')
            } else if (url.hostname === 'gitlab.com') {
                const idx = cleanedPath.indexOf('/-/')
                if (idx !== -1) cleanedPath = cleanedPath.substring(0, idx)
                vcsrepo.value.type = 'Git'
                vcsrepo.value.uri = `${url.origin}${cleanedPath}`
                vcsrepo.value.name = cleanedPath.replace(/^\//, '')
            } else if (url.hostname === 'dev.azure.com' || url.hostname.endsWith('.visualstudio.com')) {
                vcsrepo.value.type = 'Git'
                vcsrepo.value.uri = `${url.origin}${cleanedPath}`
                vcsrepo.value.name = cleanedPath.replace(/^\//, '')
            } else {
                console.log(url)
                vcsrepo.value.uri = `${url.origin}${cleanedPath}`
            }
            // Update magicInput to normalized canonical URL for UX consistency
            magicInput.value = vcsrepo.value.uri || magicInput.value
        } catch (e) {
            // If URL parsing fails, leave values unchanged
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
  