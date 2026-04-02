<template>
    <div class="most-recent-releases-page">
        <div class="page-header">
            <n-space>
                <n-date-picker
                    v-model:value="startDateValue"
                    type="date"
                    placeholder="Start Date"
                />
                <n-date-picker
                    v-model:value="endDateValue"
                    type="date"
                    placeholder="End Date"
                />
            </n-space>
        </div>
        <div class="widget-container">
            <most-recent-releases-widget
                v-if="orgUuid"
                :org-uuid="orgUuid"
                :perspective-uuid="perspectiveUuid"
                :show-full-page-icon="false"
                :max-releases="50"
                :start-date="dateFrom"
                :end-date="dateTo"
            />
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'MostRecentReleasesPage'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useStore } from 'vuex'
import { NSpace, NDatePicker } from 'naive-ui'
import MostRecentReleasesWidget from './MostRecentReleasesWidget.vue'

const route = useRoute()
const store = useStore()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => route.params.orguuid as string || myorg.value?.uuid || '')
const myperspective = computed(() => store.getters.myperspective)
const perspectiveUuid = computed(() => myperspective.value && myperspective.value !== 'default' ? myperspective.value : undefined)

const now = new Date()
const defaultEndDate = now.getTime()
const defaultStartDate = new Date(now.getTime() - 24 * 60 * 60 * 1000).getTime()

const startDateValue = ref<number | null>(defaultStartDate)
const endDateValue = ref<number | null>(defaultEndDate)

const dateFrom = computed(() => startDateValue.value ? new Date(startDateValue.value) : undefined)
const dateTo = computed(() => endDateValue.value ? new Date(endDateValue.value) : undefined)

onMounted(() => {
    if (route.query.fromDate) {
        const fromDate = new Date(route.query.fromDate as string)
        if (!isNaN(fromDate.getTime())) startDateValue.value = fromDate.getTime()
    }
    if (route.query.toDate) {
        const toDate = new Date(route.query.toDate as string)
        if (!isNaN(toDate.getTime())) endDateValue.value = toDate.getTime()
    }
})

watch([startDateValue, endDateValue], ([newStart, newEnd]) => {
    const params = new URLSearchParams(window.location.search)
    if (newStart) params.set('fromDate', new Date(newStart).toISOString().split('T')[0])
    if (newEnd) params.set('toDate', new Date(newEnd).toISOString().split('T')[0])
    window.history.replaceState({}, '', `${window.location.pathname}?${params.toString()}`)
})
</script>

<style scoped lang="scss">
.most-recent-releases-page {
    padding: 20px;
    max-width: 1400px;
    margin: 0 auto;
}

.page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
}

.widget-container {
    background: white;
    border-radius: 8px;
    padding: 20px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}
</style>
