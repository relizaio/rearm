<template>
    <h3 class="component-header">
        <router-link :to="componentLink">{{ name }}</router-link>
        <span v-if="firstRelease && lastRelease">
            <span>&nbsp;</span>
            <router-link :to="{ name: 'ReleaseView', params: { uuid: firstRelease.uuid }}">{{ firstRelease.version }}</router-link>
            <span> - </span>
            <router-link :to="{ name: 'ReleaseView', params: { uuid: lastRelease.uuid }}">{{ lastRelease.version }}</router-link>
        </span>
        <h6 v-if="showBranchChangeWarning" class="branch-warning">* branch changed between product releases</h6>
    </h3>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

interface Release {
    uuid: string
    version: string
}

interface Props {
    orgUuid: string
    componentUuid: string
    name: string
    firstRelease?: Release
    lastRelease?: Release
    branchCount?: number
}

const props = defineProps<Props>()

const componentLink = computed(() => ({
    name: 'ComponentsOfOrg',
    params: {
        orguuid: props.orgUuid,
        compuuid: props.componentUuid
    }
}))

const showBranchChangeWarning = computed(() => {
    return props.branchCount && props.branchCount > 1
})
</script>

<style scoped lang="scss">
.component-header {
    margin-top: 16px;
    margin-bottom: 8px;
    
    .branch-warning {
        color: #f0a020;
        font-size: 0.85em;
        font-weight: normal;
        margin-top: 4px;
    }
    
    a {
        color: #18a058;
        text-decoration: none;
        
        &:hover {
            text-decoration: underline;
        }
    }
}
</style>
