<template>
    <div>
        Your artifact should be downloaded.
    </div>
</template>
    
<script lang="ts">
export default {
    name: 'DownloadTeaArtifactView'
}
</script>
<script lang="ts" setup>
import {onMounted} from 'vue'
import { useRoute } from 'vue-router'
import axios from '../utils/axios'
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'

const route = useRoute()

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

const downloadArtifact = async () => {
    const art = await getArtifact()
    axios({
        method: 'get',
        url: '/api/manual/v1/artifact/' + route.params.artuuid.toString() + '/download',
        responseType: 'arraybuffer',
    }).then(function (response) {
        const artType = art.tags.find((tag: any) => tag.key === 'mediaType')?.value
        const fileName = art.tags.find((tag: any) => tag.key === 'fileName')?.value
        const blob = new Blob([response.data], { type: artType })
        const link = document.createElement('a')
        link.href = window.URL.createObjectURL(blob)
        link.download = fileName
        link.click()
    })
}

const downloadRawArtifact = async () => {
    const art = await getArtifact()
    axios({
        method: 'get',
        url: '/api/manual/v1/artifact/' +  route.params.artuuid.toString() + '/rawdownload',
        responseType: 'arraybuffer',
    }).then(function (response) {
        const artType = art.tags.find((tag: any) => tag.key === 'mediaType')?.value
        const fileName = art.tags.find((tag: any) => tag.key === 'fileName')?.value
        const blob = new Blob([response.data], { type: artType })
        const link = document.createElement('a')
        link.href = window.URL.createObjectURL(blob)
        link.download = fileName
        link.click()
    })
}

onMounted(async () => {
    downloadRawArtifact()
})

</script>
    
<style scoped lang="scss">

</style>