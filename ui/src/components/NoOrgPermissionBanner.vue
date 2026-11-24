<template>
    <div v-if="show" class="no-org-permission-banner" data-testid="no-org-permission-banner">
        <n-alert type="warning" :show-icon="true">{{ NO_ORG_PERMISSION_TEXT }}</n-alert>
    </div>
</template>

<script setup lang="ts">
// The banner of a signed-in user with no organization-wide permission above NONE in the current
// organization (license seats review follow-up of TPF-168). The rule reads the stored myuser, whose
// permissions the `user` query returns combined (direct and through user groups) for every
// organization of the user; a global administrator never sees it. No query of its own.
import { computed } from 'vue'
import { NAlert } from 'naive-ui'
import { useStore } from 'vuex'
import { NO_ORG_PERMISSION_TEXT, hasNoOrgPermission } from '@/utils/licenseSeats'

const store = useStore()
const show = computed((): boolean => hasNoOrgPermission(store.getters.myuser, store.getters.myorg?.uuid))
</script>

<style scoped lang="scss">
.no-org-permission-banner {
    margin: 8px 16px;
}
</style>
