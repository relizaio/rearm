<template>
    <div class="changelog-controls">
        <div v-if="showDatePicker" class="control-row">
            <n-space align="center">
                <span class="control-label">Date Range:</span>
                <n-select
                    v-model:value="selectedPreset"
                    :options="presetOptions"
                    @update:value="handlePresetChange"
                    style="width: 180px;"
                />
                <template v-if="selectedPreset === 'custom'">
                    <n-date-picker
                        :value="dateRange"
                        @update:value="handleDateRangeUpdate"
                        type="daterange"
                        clearable
                        style="width: 300px;"
                    />
                    <n-button type="primary" @click="$emit('apply')">Apply</n-button>
                </template>
            </n-space>
        </div>
        
        <div v-if="showAggregation" class="control-row aggregation-control">
            <div class="aggregation-wrapper">
                <span class="control-label">Aggregation:</span>
                <n-radio-group 
                    :value="aggregationType" 
                    @update:value="handleAggregationUpdate"
                    name="aggregatetyperadiogroup"
                >
                    <n-radio-button
                        v-for="option in aggregationOptions"
                        :key="option.value"
                        :value="option.value"
                        :label="option.label"
                    />
                </n-radio-group>
                <span v-if="aggregationHint" class="aggregation-hint">
                    {{ aggregationHint }}
                </span>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { ref, watch } from 'vue'
import { NSpace, NDatePicker, NButton, NRadioGroup, NRadioButton, NSelect } from 'naive-ui'

interface Props {
    dateRange?: [number, number] | null
    aggregationType?: string
    showDatePicker?: boolean
    showAggregation?: boolean
    aggregationHint?: string
}

const props = withDefaults(defineProps<Props>(), {
    showDatePicker: true,
    showAggregation: true,
    aggregationType: 'NONE',
    aggregationHint: 'Applies to Code, SBOM, and Finding changes'
})

const emit = defineEmits<{
    'update:dateRange': [value: [number, number] | null]
    'update:aggregationType': [value: string]
    'apply': []
}>()

const selectedPreset = ref('last7days')

const presetOptions = [
    { label: 'Last 24 hours', value: 'last24hours' },
    { label: 'Last 3 days', value: 'last3days' },
    { label: 'Last 7 days', value: 'last7days' },
    { label: 'Last 14 days', value: 'last14days' },
    { label: 'Last 30 days', value: 'last30days' },
    { label: 'Last 90 days', value: 'last90days' },
    { label: 'Custom', value: 'custom' }
]

const aggregationOptions = [
    { label: 'NONE', value: 'NONE' },
    { label: 'AGGREGATED', value: 'AGGREGATED' }
]

const computeDateRangeFromPreset = (preset: string): [number, number] | null => {
    if (preset === 'custom') {
        return props.dateRange || null
    }
    
    const now = Date.now()
    const msPerDay = 24 * 60 * 60 * 1000
    
    const presetMap: Record<string, number> = {
        'last24hours': 1,
        'last3days': 3,
        'last7days': 7,
        'last14days': 14,
        'last30days': 30,
        'last90days': 90
    }
    
    const days = presetMap[preset] || 7
    return [now - days * msPerDay, now]
}

const handlePresetChange = (value: string) => {
    selectedPreset.value = value
    
    if (value !== 'custom') {
        const newRange = computeDateRangeFromPreset(value)
        if (newRange) {
            emit('update:dateRange', newRange)
            emit('apply')
        }
    }
}

const handleDateRangeUpdate = (value: [number, number] | null) => {
    emit('update:dateRange', value)
}

const handleAggregationUpdate = (value: string) => {
    emit('update:aggregationType', value)
}

// Initialize with default preset
watch(() => props.dateRange, (newRange) => {
    // If date range is updated externally and doesn't match current preset, switch to custom
    if (newRange && selectedPreset.value !== 'custom') {
        const presetRange = computeDateRangeFromPreset(selectedPreset.value)
        if (presetRange && (Math.abs(newRange[0] - presetRange[0]) > 60000 || Math.abs(newRange[1] - presetRange[1]) > 60000)) {
            selectedPreset.value = 'custom'
        }
    }
}, { immediate: false })
</script>

<style scoped lang="scss">
.changelog-controls {
    margin-bottom: 20px;
    
    .control-row {
        margin-bottom: 16px;
        
        &:last-child {
            margin-bottom: 0;
        }
    }
    
    .control-label {
        font-weight: 500;
        min-width: 90px;
    }
    
    .aggregation-control {
        padding: 16px;
        background: #f5f5f5;
        border-radius: 4px;
    }
    
    .aggregation-wrapper {
        display: flex;
        align-items: center;
        gap: 16px;
        flex-wrap: wrap;
    }
    
    .aggregation-hint {
        color: #666;
        font-size: 0.9em;
        font-style: italic;
    }
}
</style>
