<template>
    <div v-if="fetched">
        <li>
            <div class="releaseDetails">
                {{release.componentDetails.name}} - {{ release.version}} ({{ release.status.toUpperCase() }})
            </div>
            <span class="icon" v-if="release.sourceCodeEntryDetails">
                <n-tooltip trigger="hover" class="artifactTooltip">
                    <template #trigger>
                        <vue-feather type="git-commit" :id="'sce' + release.sourceCodeEntry + release.uuid" />
                    </template>
                    Repository: {{ release.sourceCodeEntryDetails.vcsRepository.uri }}, branch: {{ release.sourceCodeEntryDetails.vcsBranch }}, commit: {{ release.sourceCodeEntryDetails.commit }}
                </n-tooltip>
            </span>
            <span class="icon" v-if="release.artifactDetails && release.artifactDetails.length">
                <n-tooltip class="artifactTooltip" trigger="hover">
                    <template #trigger>
                        <vue-feather type="box" :id="'artifacts' + release.uuid" />
                    </template>
                    <p v-for="art in release.artifactDetails" :key=art.uuid>
                        {{ art.displayIdentifier + (art.digests.length ?  '@' + art.digests[0] : '') }}
                    </p>
                </n-tooltip>
            </span>
            <vue-feather type="eye" @click="openRelease(release.uuid)" class="clickable icon" title="Open Release" />
            <vue-feather v-if="props.updatable" @click="showEditReleaseModal=true" type="edit" class="icon clickable" title="Update Release" />
            <vue-feather v-if="props.deletable" @click="removeRelease" type="trash-2" class="icon clickable" title="Remove Release" />
            <ul v-if="showParents">
                <div  v-for="cr in release.parentReleases" :key="'parRel' + cr.release">
                    <release-el :uuid="cr.release" 
                                :org="release.org"
                                class="releaseElInList" 
                    />
                </div>
            </ul>
        
            <n-modal 
                style="width: 90%" 
                v-model:show="showEditReleaseModal"
                title="Update Release"
                preset="dialog"
                :show-icon="false"
            >
                <create-release
                    v-if="release"
                    :orgProp="release.org"
                    :attemptPickRelease="true"
                    :updateMode="true"
                    :inputType="release.componentDetails ? release.componentDetails.type : release.type"
                    :inputComponent="release.componentDetails ? release.componentDetails.uuid : ''"
                    @createdRelease="releaseUpdated"
                    createButtonText="Select Release"
                />
            </n-modal>
        </li>
   </div>
</template>

<script lang="ts">
export default {
    name: 'ReleaseEl'
}
</script>
<script lang="ts" setup>
import { ref, Ref } from 'vue'
import { useStore } from 'vuex'
import { NTooltip, NModal } from 'naive-ui'
import { useRouter } from 'vue-router'
import CreateRelease from './CreateRelease.vue'

const props = defineProps<{
    uuid: String,
    org: String,
    updatable: Boolean,
    deletable: Boolean,
    releaseProp: Object,
    showParents: Boolean
}>()

const emit = defineEmits(['removeRelease','releaseUpdated'])

const store = useStore()
const router = useRouter()
const fetched : Ref<boolean> = ref(false)
const release : Ref<any> = ref({})

let fetchRelease = false

if (props.releaseProp) {
    release.value = props.releaseProp
} else {
    release.value = store.getters.releaseById(props.uuid)
}
if (!release.value) {
    fetchRelease = true
} else {
    fetched.value = true
}
if (fetchRelease) {
    let params = {
        release: props.uuid,
        light: true,
        org: ''
    }
    if (props.org) {
        params.org = props.org
    }
    release.value = await store.dispatch('fetchReleaseById', params)
    fetched.value = true
}

if(props.showParents){
    let rlzUuidsToFetch: string[] = []
    // fetch parent and product releases
    if (release.value.parentReleases && release.value.parentReleases.length) {
        rlzUuidsToFetch = rlzUuidsToFetch.concat(release.value.parentReleases.map((rl: any) => rl.release))
    }
    if (release.value.inProducts && release.value.inProducts.length) {
        rlzUuidsToFetch = rlzUuidsToFetch.concat(release.value.inProducts.map((rl: any) => rl.uuid))
    }
    if (rlzUuidsToFetch.length) {
        let fetchRlzParams = {
            org: release.value.org,
            releases: rlzUuidsToFetch
        }
        await store.dispatch('fetchReleasesByOrgUuids', fetchRlzParams)
    } 
}

const showEditReleaseModal: Ref<boolean> = ref(false)

const openRelease = function (uuid: string) {
    // this.$router.push({ name: 'release', params: { uuid: uuid } })
    let routeData = router.resolve({ name: 'ReleaseView', params: { uuid: uuid } })
    window.open(routeData.href, '_blank')
}
const removeRelease = function () {
    emit('removeRelease', props.uuid)
}
const releaseUpdated = function(rlz: any) {
    let emitObj = {
        source: props.uuid,
        target: rlz.uuid
    }
    emit('releaseUpdated', emitObj)
    showEditReleaseModal.value = false
}

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.icon {
    margin-right: 5px;
    vertical-align: middle;
}

a {
    color: #42b983;
}
.releaseDetails {
    display:inline-block;
    padding-right: 7px;
}
</style>