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
                <span>Known Exploited Vulnerability: {{ cveId }}</span>
            </n-space>
        </template>
        <n-spin :show="loading">
            <div v-if="record && primary">
                <n-descriptions label-placement="left" :column="1" bordered size="small">
                    <n-descriptions-item v-if="primary.vulnerabilityName" label="Name">
                        {{ primary.vulnerabilityName }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.vendorProject" label="Vendor / Project">
                        {{ primary.vendorProject }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.product" label="Product">
                        {{ primary.product }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.dateAdded" label="Added to KEV">
                        {{ primary.dateAdded }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.dueDate" label="Remediation due (FCEB)">
                        {{ primary.dueDate }}
                    </n-descriptions-item>
                    <n-descriptions-item label="Ransomware campaign use">
                        <n-space align="center" :size="6">
                            <n-tag :type="ransomware.type" size="small" :bordered="false">{{ ransomware.label }}</n-tag>
                            <template v-for="c in campaigns" :key="c.name">
                                <a
                                    v-if="c.url"
                                    :href="c.url"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    @click.prevent="openExternalLink(c.url)"
                                >{{ c.name }}</a>
                                <span v-else>{{ c.name }}</span>
                            </template>
                        </n-space>
                    </n-descriptions-item>
                    <n-descriptions-item label="Reported by">
                        <n-space :size="6" align="center">
                            <template v-for="a in record.assertions" :key="a.source">
                                <n-tag
                                    :type="a.revokedDate ? 'warning' : 'success'"
                                    size="small"
                                    :bordered="false"
                                >
                                    {{ a.source }}{{ a.revokedDate ? ` · revoked ${formatDate(a.revokedDate)}` : '' }}
                                </n-tag>
                            </template>
                        </n-space>
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.cwes && primary.cwes.length > 0" label="CWEs">
                        <n-space :size="4">
                            <template v-for="cwe in primary.cwes" :key="cwe">
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
                    <n-descriptions-item v-if="primary.shortDescription" label="Description">
                        {{ primary.shortDescription }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.requiredAction" label="Required action">
                        {{ primary.requiredAction }}
                    </n-descriptions-item>
                    <n-descriptions-item v-if="primary.notes" label="Notes">
                        {{ primary.notes }}
                    </n-descriptions-item>
                </n-descriptions>
                <n-space style="margin-top: 12px;" :size="16">
                    <a
                        v-if="hasCisa"
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
                v-else-if="loadError && !loading"
                description="Failed to load KEV catalog details. Please try again."
                style="padding: 24px 0;"
            />
            <n-empty
                v-else-if="!loading"
                description="This vulnerability is not currently listed as known-exploited by any source."
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
import { fetchKevRecordDetails, KevRecordDetails, KevSourceAssertion } from '@/utils/kevService'
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
const loadError = ref(false)
const record = ref<KevRecordDetails | null>(null)

// Descriptive fields come from the first active assertion (else the first
// of any), since several sources may describe the same CVE.
const primary = computed<KevSourceAssertion | null>(() => {
    const list = record.value?.assertions || []
    if (list.length === 0) return null
    return list.find(a => !a.revokedDate) || list[0]
})

const ransomware = computed(() => {
    switch (record.value?.ransomwareStatus) {
    case 'KNOWN': return { type: 'error' as const, label: 'Known' }
    case 'UNKNOWN': return { type: 'default' as const, label: 'Not known' }
    default: return { type: 'default' as const, label: 'Unknown' }
    }
})

const campaigns = computed(() => primary.value?.ransomwareCampaigns || [])

const hasCisa = computed(() => (record.value?.assertions || []).some(a => a.source === 'CISA'))

const cisaCatalogUrl = computed(() =>
    `https://www.cisa.gov/known-exploited-vulnerabilities-catalog?search_api_fulltext=${encodeURIComponent(props.cveId)}`)

const osvUrl = computed(() => getFindingUrl(props.cveId))

function formatDate(iso: string): string {
    return iso.length >= 10 ? iso.slice(0, 10) : iso
}

// Token guards against a stale in-flight response landing after the
// modal was closed and reopened for a different CVE.
let fetchToken = 0

watch([() => props.show, () => props.cveId], async ([shown]) => {
    if (!shown) return
    record.value = null
    loadError.value = false
    if (!props.orgUuid || !props.cveId) return
    const token = ++fetchToken
    loading.value = true
    try {
        const result = await fetchKevRecordDetails(props.orgUuid, props.cveId)
        if (token === fetchToken) record.value = result
    } catch (err) {
        console.error('Error fetching KEV record details:', err)
        if (token === fetchToken) loadError.value = true
    } finally {
        if (token === fetchToken) loading.value = false
    }
})
</script>
