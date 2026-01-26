<template>
    <div class="severity-filter">
        <n-form-item label="Filter By Severity:">
            <n-select 
                :value="selectedSeverity" 
                @update:value="handleUpdate"
                :options="severityOptions" 
            />
        </n-form-item>
    </div>
</template>

<script lang="ts" setup>
import { NFormItem, NSelect } from 'naive-ui'

interface Props {
    selectedSeverity?: string
}

const props = withDefaults(defineProps<Props>(), {
    selectedSeverity: 'ALL'
})

const emit = defineEmits<{
    'update:selectedSeverity': [value: string]
}>()

const severityOptions = [
    { label: 'ALL', value: 'ALL' },
    { label: 'BREAKING CHANGES', value: 'BREAKING CHANGES' },
    { label: 'Bug Fixes', value: 'Bug Fixes' },
    { label: 'Features', value: 'Features' },
    { label: 'Performance Improvements', value: 'Performance Improvements' },
    { label: 'Reverts', value: 'Reverts' },
    { label: 'Code Refactoring', value: 'Code Refactoring' },
    { label: 'Builds', value: 'Builds' },
    { label: 'Tests', value: 'Tests' },
    { label: 'Chores', value: 'Chores' },
    { label: 'Continuous Integration', value: 'Continuous Integration' },
    { label: 'Styles', value: 'Styles' },
    { label: 'Others', value: 'Others' }
]

const handleUpdate = (value: string) => {
    emit('update:selectedSeverity', value)
}
</script>

<style scoped lang="scss">
.severity-filter {
    margin-bottom: 16px;
    max-width: 30%;
}
</style>
