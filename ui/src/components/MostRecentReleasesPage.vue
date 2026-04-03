<template>
    <div class="most-recent-releases-page">
        <div class="page-header">
            <n-space align="center">
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
                <span>Limit:</span>
                <n-select
                    v-model:value="limitPreset"
                    :options="limitOptions"
                    style="width: 100px;"
                    @update:value="onLimitPresetChange"
                />
                <n-input-number
                    v-if="limitPreset === 'custom'"
                    v-model:value="customLimit"
                    :min="1"
                    style="width: 100px;"
                    placeholder="Custom"
                />
            </n-space>
        </div>
        <div class="widget-container">
            <most-recent-releases-widget
                v-if="orgUuid"
                :org-uuid="orgUuid"
                :perspective-uuid="perspectiveUuid"
                :show-full-page-icon="false"
                :limit="effectiveLimit"
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
import { useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { NSpace, NDatePicker, NSelect, NInputNumber } from 'naive-ui'
import MostRecentReleasesWidget from './MostRecentReleasesWidget.vue'

const route = useRoute()
const router = useRouter()
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

const limitOptions = [
    { label: '10', value: 10 },
    { label: '30', value: 30 },
    { label: '50', value: 50 },
    { label: '100', value: 100 },
    { label: 'Custom', value: 'custom' }
]

const PRESET_VALUES = [10, 30, 50, 100]

const limitPreset = ref<number | 'custom'>(10)
const customLimit = ref<number>(10)

const effectiveLimit = computed(() => limitPreset.value === 'custom' ? customLimit.value : limitPreset.value as number)

function onLimitPresetChange(val: number | 'custom') {
    if (val !== 'custom') customLimit.value = val as number
}

function syncUrl() {
    const q: Record<string, string> = { ...route.query as Record<string, string> }
    if (startDateValue.value) q.fromDate = new Date(startDateValue.value).toISOString().split('T')[0]
    if (endDateValue.value) q.toDate = new Date(endDateValue.value).toISOString().split('T')[0]
    q.limit = String(effectiveLimit.value)
    router.replace({ query: q })
}

onMounted(() => {
    if (route.query.fromDate) {
        const fromDate = new Date(route.query.fromDate as string)
        if (!isNaN(fromDate.getTime())) startDateValue.value = fromDate.getTime()
    }
    if (route.query.toDate) {
        const toDate = new Date(route.query.toDate as string)
        if (!isNaN(toDate.getTime())) endDateValue.value = toDate.getTime()
    }
    if (route.query.limit) {
        const urlLimit = parseInt(route.query.limit as string, 10)
        if (!isNaN(urlLimit) && urlLimit > 0) {
            if (PRESET_VALUES.includes(urlLimit)) {
                limitPreset.value = urlLimit
            } else {
                limitPreset.value = 'custom'
                customLimit.value = urlLimit
            }
        }
    }
})

watch([startDateValue, endDateValue, effectiveLimit], syncUrl)
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
