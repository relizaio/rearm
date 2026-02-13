<template>
    <div class="linkVcsToBranch">
        <n-form>
            <n-form-item
                            v-if="!isCreateNewRepo && !submitted"
                            label="Select VCS Repo">
                <n-select
                            id="branch_select_vcs"
                            @v-on:update:value="value=>setNewVcs(value)"
                            v-model:value="vcs"
                            :options="vcsRepos" />
            </n-form-item>
            <div v-if="submitted"><b>Repository to add: </b> {{ vcsRepositoryName }} <a :href="'http://' + vcsRepositoryName" target="_blank" rel="noopener noreferrer">
                    <n-icon title="Open repository In New Tab" class="clickable icons" size="20"><ExternalLink /></n-icon>
                </a>
            </div>
            <n-form-item>
                <create-vcs-repository
                                        id="link_vcs_create_repo"
                                        v-if="isCreateNewRepo"
                                        @createdVcsRepo="createdVcsRepo"
                                        :orguuid="props.orgprop" />
            </n-form-item>
            <n-form-item
                            v-show="vcs"
                            label="VCS Branch Name"
                            label-for="link_vcs_repo_branch"
                            description="Branch name or URI of VCS Repo">
                <n-input
                            v-model:value="vcsBranch"
                            required
                            placeholder="Enter VCS branch name" />
            </n-form-item>
            <n-button v-if="!hideButtons" type="success" @click="onSubmit">Submit</n-button>
            <n-button v-if="!hideButtons" type="warning" @click="onReset">Reset</n-button>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'LinkVcs'
}
</script>
<script lang="ts" setup>
import { ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { NForm, NFormItem, NInput, NButton, NSelect, NIcon } from 'naive-ui'
import { ExternalLink } from '@vicons/tabler'
import CreateVcsRepository from './CreateVcsRepository.vue'
import commonFunctions from '@/utils/commonFunctions'
const props = defineProps<{
    branchUuid: String,
    hideButtons?: Boolean,
    orgprop: String
}>()

const emit = defineEmits(['linkVcsRepo'])
const store = useStore()

const vcsBranch = ref('')
const vcs = ref('')
const vcsRepositoryName = ref('')
const vcsRepositoryURI = ref('')
const modifiedBranch = ref({})
const isCreateNewRepo = ref(false)
const submitted = ref(false)


const vcsRepos: ComputedRef<any> = computed((): any => {
    const storeVcs = store.getters.vcsReposByOrg(props.orgprop)
    const retMap = storeVcs.map((repo: any) => {
        const repoObj = {
            label: repo.name,
            value: repo.uuid
        }
        return repoObj
    })
    retMap.push({
        label: 'Add new repository',
        value: 'add_new_repo'
    })
    return retMap
})

const setNewVcs = function (value: string) {
    if (value === 'add_new_repo') {
        isCreateNewRepo.value = true
    } else {
        vcs.value = value
        vcsRepositoryName.value = store.getters.vcsRepoById(value)['name']
        vcsRepositoryURI.value = store.getters.vcsRepoById(value)['uri']
    }
}

const onSubmit = function () {
    const linkObj = {
        repo: vcs.value,
        branch: vcsBranch.value
    }
    emit('linkVcsRepo', linkObj)
}

const onReset = function () {
    vcsBranch.value = ''
    vcs.value = ''
    vcsRepositoryName.value = ''
    vcsRepositoryURI.value = ''
}

const createdVcsRepo = function (repoId: string) {
    isCreateNewRepo.value = false
    vcs.value = repoId
    vcsRepositoryName.value = store.getters.vcsRepoById(repoId)['name']
    vcsRepositoryURI.value = store.getters.vcsRepoById(repoId)['uri']
    submitted.value = true
}

await store.dispatch('fetchVcsRepos', { org: props.orgprop, forceRefresh: true })
if (props.branchUuid) {
    await store.dispatch('fetchBranch', props.branchUuid)
    const br = store.getters.branchById(props.branchUuid)
    vcsBranch.value = br.name
    modifiedBranch.value = commonFunctions.deepCopy(br)
    const componentData = await store.dispatch('fetchComponent', br.component)
    store.dispatch('fetchComponents', componentData.org)
}

</script>

<style scoped lang="scss">
</style>