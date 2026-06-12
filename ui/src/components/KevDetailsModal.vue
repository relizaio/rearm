<template>
    <n-modal
        v-model:show="isVisible"
        preset="dialog"
        :show-icon="false"
        style="width: 640px;"
    >
        <template #header>
            <n-space align="center" :size="8">
                <n-tag type="error" size="small" :bordered="false">KEV</n-tag>
                <span>CISA Known Exploited Vulnerability: {{ cveId }}</span>
            </n-space>
        </template>
        <n-spin :show="loading">
            <div v-if="record">
                <n-descriptions label-placement="left" :column="1" bordered size="small">
                    <n-descriptions-item v-if="record.vulnerabilityName" label="Name">
                        {{ record.vulnerabilityName }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.vendorProject" label="Vendor / Project">
                        {{ record.vendorProject }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.product" label="Product">
                        {{ record.product }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.dateAdded" label="Added to KEV">
                        {{ record.dateAdded }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.dueDate" label="Remediation due (FCEB)">
                        {{ record.dueDate }}
                    </n-descriptions-item>
                    <n-descriptions-item label="Ransomware campaign use">
                        <n-tag :type="record.knownRansomwareCampaignUse ? 'error' : 'default'" size="small" :bordered="false">
                            {{ record.knownRansomwareCampaignUse ? 'Known' : 'Unknown' }}
                        </n-tag>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.cwes && record.cwes.length > 0" label="CWEs">
                        <n-space :size="4">
                            <template v-for="cwe in record.cwes" :key="cwe">
                                <a
                                    v-if="getFindingUrl(cwe)"
                                    :href="getFindingUrl(cwe)!"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    @click.prevent="openExternalLink(getFindingUrl(cwe)!)"
                                >{{ cwe }}</a>
                                <span v-else>{{ cwe }}</span>
                            </template>
                        </n-space>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.shortDescription" label="Description">
                        {{ record.shortDescription }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.requiredAction" label="Required action">
                        {{ record.requiredAction }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="record.notes" label="Notes">
                        {{ record.notes }}
                    </n-descriptions-item>
                </n-descriptions>
                <n-space style="margin-top: 12px;" :size="16">
                    <a
                        :href="cisaCatalogUrl"
                        target="_blank"
                        rel="noopener noreferrer"
                        @click.prevent="openExternalLink(cisaCatalogUrl)"
                    >View in CISA KEV catalog</a>
                    <a
                        v-if="osvUrl"
                        :href="osvUrl"
                        target="_blank"
                        rel="noopener noreferrer"
                        @click.prevent="openExternalLink(osvUrl!)"
                    >View on osv.dev</a>
                </n-space>
            </div>
            <n-empty
                v-else-if="!loading"
                description="This vulnerability is not currently listed in the CISA KEV catalog."
                style="padding: 24px 0;"
            />
        </n-spin>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'KevDetailsModal'
}
</script>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NModal, NSpin, NSpace, NTag, NDescriptions, NDescriptionsItem, NEmpty } from 'naive-ui'
import { fetchKevRecordDetails, KevRecordDetails } from '@/utils/kevService'
import { getFindingUrl, openExternalLink } from '@/utils/findingUtils'

interface Props {
    show: boolean
    orgUuid: string
    cveId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
    'update:show': [value: boolean]
}>()

const isVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value)
})

const loading = ref(false)
const record = ref<KevRecordDetails | null>(null)

const cisaCatalogUrl = computed(() =>
    `https://www.cisa.gov/known-exploited-vulnerabilities-catalog?search_api_fulltext=${encodeURIComponent(props.cveId)}`)

const osvUrl = computed(() => getFindingUrl(props.cveId))

watch(() => props.show, async (shown) => {
    if (!shown) return
    record.value = null
    if (!props.orgUuid || !props.cveId) return
    loading.value = true
    try {
        record.value = await fetchKevRecordDetails(props.orgUuid, props.cveId)
    } finally {
        loading.value = false
    }
})
</script>
