<template>
    <div class="instanceSideBySide">
        <release-revision v-if="comparisonTypeLeft === 'release'"
            :releaseUuid="instanceLeft"
            :otherRevisionType="comparisonTypeRight"
            :otherInstanceUuid="instanceRight"
            :otherRevision="revisionRight"/>
        <instance-revision v-if="comparisonTypeLeft === 'instance'"
            :instanceUuid="instanceLeft"
            :revision="revisionLeft"
            :namespace="namespaceLeft"
            :otherInstanceUuid="instanceRight"
            :otherRevision="revisionRight"
            :otherRevisionType="comparisonTypeRight"
             />
        <release-revision v-if="comparisonTypeRight === 'release'"
            :releaseUuid="instanceRight"
            :otherRevisionType="comparisonTypeLeft"
            :otherInstanceUuid="instanceLeft"
            :otherRevision="revisionLeft"/>
        <instance-revision v-if="comparisonTypeRight === 'instance'"
            :instanceUuid="instanceRight"
            :revision="revisionRight"
            :namespace="namespaceRight"
            :otherInstanceUuid="instanceLeft"
            :otherRevision="revisionLeft"
            :otherRevisionType="comparisonTypeLeft" />
            
    </div>
</template>

<script lang="ts">
export default {
    name: 'SideBySide'
}
</script>
<script lang="ts" setup>
import InstanceRevision from '@/components/InstanceRevision.vue'
import ReleaseRevision from '@/components/ReleaseRevision.vue'
const props = defineProps<{
    instanceLeft: String,
    revisionLeft: Number,
    instanceRight: String,
    revisionRight: Number,
    comparisonTypeLeftIn: String,
    comparisonTypeRightIn: String,
    namespaceLeft: String,
    namespaceRight: String
}>()

const comparisonTypeLeft = (props.comparisonTypeLeftIn) ? props.comparisonTypeLeftIn : 'instance'
const comparisonTypeRight = (props.comparisonTypeRightIn) ? props.comparisonTypeRightIn : 'instance'

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.instanceSideBySide {
    display: grid;
    grid-template-columns: 1fr 1fr;
}
</style>