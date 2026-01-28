<template>
    <div class="findings-over-time-page">
        <div class="page-header">
            <n-space>
                <n-date-picker
                    v-model:value="startDateValue"
                    type="date"
                    placeholder="Start Date"
                    :disabled="isLoading"
                />
                <n-date-picker
                    v-model:value="endDateValue"
                    type="date"
                    placeholder="End Date"
                    :disabled="isLoading"
                />
            </n-space>
        </div>
        <div class="chart-container">
            <findings-over-time-chart
                v-if="orgUuid"
                :type="chartType"
                :org-uuid="orgUuid"
                :perspective-uuid="perspectiveUuid"
                :date-from="dateFrom"
                :date-to="dateTo"
                :days-back="60"
                :chart-height="500"
                :show-full-page-icon="false"
            />
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'FindingsOverTimePage'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useStore } from 'vuex'
import { NSpace, NDatePicker } from 'naive-ui'
import FindingsOverTimeChart from './FindingsOverTimeChart.vue'

const route = useRoute()
const store = useStore()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => route.params.orguuid as string || myorg.value?.uuid || '')
const myperspective = computed(() => store.getters.myperspective)
const perspectiveUuid = computed(() => myperspective.value && myperspective.value !== 'default' ? myperspective.value : '')
const chartType = computed(() => perspectiveUuid.value ? 'PERSPECTIVE' : 'ORGANIZATION')

const isLoading = ref(false)

// Date picker values (timestamps)
const now = new Date()
const defaultEndDate = now.getTime()
const defaultStartDate = new Date(now.getTime() - 60 * 24 * 60 * 60 * 1000).getTime() // 60 days back

const startDateValue = ref<number | null>(defaultStartDate)
const endDateValue = ref<number | null>(defaultEndDate)

// Computed dates for the chart
const dateFrom = computed(() => startDateValue.value ? new Date(startDateValue.value) : undefined)
const dateTo = computed(() => endDateValue.value ? new Date(endDateValue.value) : undefined)

// Initialize from URL query params if present
onMounted(() => {
    if (route.query.fromDate) {
        const fromDate = new Date(route.query.fromDate as string)
        if (!isNaN(fromDate.getTime())) {
            startDateValue.value = fromDate.getTime()
        }
    }
    if (route.query.toDate) {
        const toDate = new Date(route.query.toDate as string)
        if (!isNaN(toDate.getTime())) {
            endDateValue.value = toDate.getTime()
        }
    }
})

// Update URL when dates change
watch([startDateValue, endDateValue], ([newStart, newEnd]) => {
    const params = new URLSearchParams(window.location.search)
    if (newStart) {
        params.set('fromDate', new Date(newStart).toISOString().split('T')[0])
    }
    if (newEnd) {
        params.set('toDate', new Date(newEnd).toISOString().split('T')[0])
    }
    const newUrl = `${window.location.pathname}?${params.toString()}`
    window.history.replaceState({}, '', newUrl)
})
</script>

<style scoped lang="scss">
.findings-over-time-page {
    padding: 20px;
    max-width: 1400px;
    margin: 0 auto;
}

.page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    
    h2 {
        margin: 0;
    }
}

.chart-container {
    background: white;
    border-radius: 8px;
    padding: 20px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}
</style>
