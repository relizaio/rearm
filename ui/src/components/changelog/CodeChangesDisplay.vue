<template>
    <div class="code-changes">
        <div v-if="hasChanges">
            <div v-for="change in filteredChanges" :key="change.changeType">
                <h4>{{ change.changeType }}</h4>
                <ul>
                    <li v-for="commitRecord in change.commitRecords" :key="`commit-${commitRecord.linkifiedText}-${commitRecord.rawText}`">
                        <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{ commitRecord.rawText }}</a>
                        <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                            <a :href="`mailto:${commitRecord.commitEmail}`" rel="noopener noreferrer" target="_blank">{{ commitRecord.commitAuthor }}</a>
                        </span>
                    </li>
                </ul>
            </div>
        </div>
        <div v-else class="empty-state">
            No code changes for selected severity
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

interface CommitRecord {
    linkifiedText: string
    rawText: string
    commitAuthor?: string
    commitEmail?: string
}

interface Change {
    changeType: string
    commitRecords: CommitRecord[]
}

interface Props {
    changes?: Change[]
    selectedSeverity?: string
}

const props = withDefaults(defineProps<Props>(), {
    selectedSeverity: 'ALL'
})

const filteredChanges = computed(() => {
    if (!props.changes) return []
    
    if (props.selectedSeverity === 'ALL') {
        return props.changes
    }
    
    return props.changes.filter(change => change.changeType === props.selectedSeverity)
})

const hasChanges = computed(() => {
    return filteredChanges.value.length > 0 && 
           filteredChanges.value.some(change => change.commitRecords && change.commitRecords.length > 0)
})
</script>

<style scoped lang="scss">
.code-changes {
    h4 {
        margin-top: 12px;
        margin-bottom: 8px;
    }
    
    ul {
        margin-top: 8px;
    }
    
    .empty-state {
        padding: 10px;
        color: #999;
        font-style: italic;
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
