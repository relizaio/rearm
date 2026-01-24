<template>
    <h3 class="release-header">
        <router-link :to="releaseLink">{{ version }}</router-link>
        <n-tag v-if="lifecycle === 'REJECTED'" type="error" size="small" class="lifecycle-tag">REJECTED</n-tag>
        <n-tag v-else-if="lifecycle === 'PENDING'" type="warning" size="small" class="lifecycle-tag">PENDING</n-tag>
    </h3>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NTag } from 'naive-ui'

interface Props {
    uuid: string
    version: string
    lifecycle?: string
}

const props = defineProps<Props>()

const releaseLink = computed(() => ({
    name: 'ReleaseView',
    params: { uuid: props.uuid }
}))
</script>

<style scoped lang="scss">
.release-header {
    margin-top: 12px;
    margin-bottom: 8px;
    
    .lifecycle-tag {
        margin-left: 8px;
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
