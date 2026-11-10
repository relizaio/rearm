<template>
    <div v-if="bannerText" class="license-over-limit-banner" data-testid="license-over-limit-banner">
        <n-alert type="warning" :show-icon="true">{{ bannerText }}</n-alert>
    </div>
</template>

<script setup lang="ts">
// The admin banner of an instance over its license (task TPF-168, TPF-165 D10): shown above the
// routed page when the license reports accounts outside the limit. The query runs only for a user
// who may read the license (a global administrator or an organization ADMIN); any refusal or
// failure shows nothing and logs nothing, since a non-default-org admin is refused by design.
import { computed, onMounted, ref, type Ref } from 'vue'
import { NAlert } from 'naive-ui'
import { useStore } from 'vuex'
import graphqlClient from '@/utils/graphql'
import { LICENSE_BANNER_QUERY, mayReadLicense, overLicenseBannerText } from '@/utils/licenseSeats'

const store = useStore()
const license: Ref<any> = ref(null)
const bannerText = computed((): string | null => overLicenseBannerText(license.value))

onMounted(async () => {
    if (!mayReadLicense(store.getters.myuser)) return
    try {
        const resp: any = await graphqlClient.query({
            query: LICENSE_BANNER_QUERY,
            fetchPolicy: 'no-cache',
            errorPolicy: 'all'
        })
        license.value = resp?.data?.license ?? null
    } catch {
        license.value = null
    }
})
</script>

<style scoped lang="scss">
.license-over-limit-banner {
    margin: 8px 16px;
}
</style>
