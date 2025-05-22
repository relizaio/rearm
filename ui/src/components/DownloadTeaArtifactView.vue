<template>
    <div>
        <h2>Your artifact is being downloaded.</h2>
    </div>
</template>
    
<script lang="ts">
export default {
    name: 'DownloadTeaArtifactView'
}
</script>
<script lang="ts" setup>
import {onMounted} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from '../utils/axios'
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import Swal from 'sweetalert2'

const route = useRoute()
const router = useRouter()

async function getArtifact () {
    const resp = await graphqlClient.query({
        query: gql`
            query artifact($artifactUuid: ID!) {
                artifact(artifactUuid: $artifactUuid) {
                    tags {
                        key
                        value
                    }
                    
                }
            }`,
            variables: {artifactUuid: route.params.artuuid.toString()}
        })
    return resp.data.artifact
}

async function downloadArtifact () {
    let pathSuffix = ""
    if (route.params.arttype.toString() === "raw") {
        pathSuffix = "/rawdownload"
    } else if (route.params.arttype.toString() === "augmented") {
        pathSuffix = "/download"
    }
    if (pathSuffix) {
        try {
            const art = await getArtifact()
            const axiosResp = await axios({
                method: 'get',
                url: '/api/manual/v1/artifact/' +  route.params.artuuid.toString() + pathSuffix,
                responseType: 'arraybuffer',
            })
            const artType = art.tags.find((tag: any) => tag.key === 'mediaType')?.value
            const fileName = art.tags.find((tag: any) => tag.key === 'fileName')?.value
            const blob = new Blob([axiosResp.data], { type: artType })
            const link = document.createElement('a')
            link.href = window.URL.createObjectURL(blob)
            link.download = fileName
            link.click()
            postDownloadNotification()
        } catch (err: any) {
            console.error(err)
            errorNotification()
        }
    } else {
        errorNotification()
    }
}

async function postDownloadNotification () {
    const swalResult = await Swal.fire({
        title: 'Downloaded',
        text: 'Your Artifact should be downloaded.',
        confirmButtonText: 'Proceed to ReARM Home'
    })

    if (swalResult.value) {
        router.push({
        name: 'home'
    })
    }
}

async function errorNotification () {
    Swal.fire(
        'Error on Downloading',
        'Your artifact could not be donwloaded. Please contact your administrator or Reliza Support.',
        'error'
    )
}

onMounted(async () => {
    downloadArtifact()
})

</script>
    
<style scoped lang="scss">

</style>