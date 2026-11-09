<template>
    <div class="licenseSeatUsersBlock" data-testid="seat-users-block">
        <h4 class="mt-4">License Seats: </h4>
        <div v-if="counts" class="seat-counts" data-testid="seat-counts">
            <span data-testid="seat-counts-write"><strong>Write:</strong> {{ counts.writeUsers ?? 0 }} / {{ limitText(maxWrite) }}</span>
            <span data-testid="seat-counts-read"><strong>Read:</strong> {{ counts.readUsers ?? 0 }} / {{ limitText(maxRead) }}</span>
            <span data-testid="seat-counts-total"><strong>Total:</strong> {{ counts.totalUsers ?? 0 }} / {{ totalLimitText }}</span>
            <span data-testid="seat-counts-norole"><strong>Users without a role (not counted):</strong> {{ counts.noRoleUsers ?? 0 }}</span>
            <span data-testid="seat-counts-outside"><strong>Outside the limit:</strong> {{ counts.outsideLimit ?? 0 }}</span>
            <span v-if="counts.countedAt" class="text-muted" data-testid="seat-counts-at">counted {{ dateText(counts.countedAt) }}</span>
        </div>
        <n-space :size="8" style="margin: 8px 0;">
            <span data-testid="seat-users-refresh"><n-button size="small" :disabled="busy" @click="load">Refresh</n-button></span>
            <span data-testid="seat-users-remove-no-role"><n-button size="small" type="warning" :disabled="busy" @click="removeNoRole">Remove accounts with no role</n-button></span>
        </n-space>
        <div v-if="loadError" class="text-danger" data-testid="seat-users-error">{{ loadError }}</div>
        <div v-else-if="loading && !rows.length" data-testid="seat-users-loading">Loading seat users...</div>
        <n-data-table
            v-else
            class="seat-users-table"
            data-testid="seat-users-table"
            :columns="columns"
            :data="sortedRows"
            :row-key="rowKey"
            :pagination="pagination"
            size="small"
        />
    </div>
</template>

<script setup lang="ts">
// The license seat view (task TPF-168, TPF-165 D10): every seat holder of the instance, one row per
// email (type READ/WRITE/NONE, created, last login, outside-limit flag), the counts header, Refresh
// and "Remove accounts with no role". Rendered by SystemSettings.vue for global administrators on
// Pro; its counts header is the page's only copy of the instance seat figures (task TPF-166 D2). Every read is a fresh count on the server; the client never caches it. Every number and flag
// is the server's field as given: the outside-limit flag reads `admission` alone and the
// Global administrator marker reads `globalAdmin` alone (no UI rule combines them). The table
// sorts and pages UI side; the no-role confirmation names the accounts the server will deactivate,
// from the loaded rows under the server's rule (noRoleDeactivationEmails). Server texts go
// to Swal as `text`, never as the positional shorthand, whose second argument is HTML.
import { computed, h, onMounted, reactive, ref, type Ref } from 'vue'
import { NButton, NDataTable, NSpace, NTag, type DataTableColumns } from 'naive-ui'
import { useStore } from 'vuex'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import {
    LICENSE_SEAT_USERS_QUERY, REMOVE_ACCOUNTS_WITHOUT_ROLE, SeatAdmission, countText, noRoleDeactivationEmails,
    removeNoRoleConfirmText, seatUserCompare, sortSeatUsersDefault
} from '@/utils/licenseSeats'

const store = useStore()

const rows: Ref<any[]> = ref([])
const license: Ref<any> = ref(null)
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')

const counts = computed(() => license.value?.globalUserCounts ?? null)
const maxWrite = computed(() => license.value?.maxWriteUsers)
const maxRead = computed(() => license.value?.maxReadUsers)

const limitText = (n: any): string => (n === null || n === undefined) ? 'unlimited' : String(n)
// the shared capacity (TPF-165 D2: read may use spare write capacity): maxWrite + maxRead, unlimited when either is
const totalLimitText = computed(() => {
    const w = maxWrite.value
    const r = maxRead.value
    return (w === null || w === undefined || r === null || r === undefined) ? 'unlimited' : String(w + r)
})
const dateText = (d: string): string => d ? new Date(d).toLocaleString('en-CA') : ''

// Sorting and pagination are UI side, over the rows of one read. The rows arrive in the default
// order (outside the limit first, then by creation date); a column sorter reorders them, and
// clearing it returns to the default order.
const sortedRows = computed(() => sortSeatUsersDefault(rows.value))
const rowKey = (r: any) => r.email
const pagination = reactive({
    page: 1,
    pageSize: 20,
    showSizePicker: true,
    pageSizes: [10, 20, 50, 100],
    onUpdatePage: (page: number) => { pagination.page = page },
    onUpdatePageSize: (size: number) => { pagination.pageSize = size; pagination.page = 1 }
})
const testid = (prefix: string, r: any) => prefix + r.email
// The admission cell reads `admission` alone and the Global administrator marker `globalAdmin` alone.
const outsideCell = (r: any) => h('span', { 'data-testid': testid('seat-user-outside-', r) },
    r.admission === SeatAdmission.OUTSIDE ? [h(NTag, { size: 'small', type: 'error' }, { default: () => 'outside the limit' })] : [])
const globalAdminMarker = (r: any) => r.globalAdmin
    ? h(NTag, { size: 'small', type: 'info', 'data-testid': testid('seat-user-global-admin-', r) }, { default: () => 'Global administrator' })
    : null
const columns: DataTableColumns<any> = [
    { key: 'email', title: 'Email', sorter: seatUserCompare.email,
        render: (r: any) => h('div', { 'data-testid': testid('seat-user-email-', r) }, [h('div', r.email), globalAdminMarker(r)]) },
    { key: 'name', title: 'Name', sorter: seatUserCompare.name,
        render: (r: any) => h('span', { 'data-testid': testid('seat-user-name-', r) }, r.name || '') },
    { key: 'seatType', title: 'Type', sorter: seatUserCompare.seatType,
        render: (r: any) => h('span', { 'data-testid': testid('seat-user-type-', r) }, r.seatType) },
    { key: 'createdDate', title: 'Created', sorter: seatUserCompare.createdDate,
        render: (r: any) => h('span', { 'data-testid': testid('seat-user-created-', r) }, dateText(r.createdDate)) },
    { key: 'lastLoginDate', title: 'Last login', sorter: seatUserCompare.lastLoginDate,
        render: (r: any) => h('span', { 'data-testid': testid('seat-user-last-login-', r) }, r.lastLoginDate ? dateText(r.lastLoginDate) : 'never') },
    { key: 'admission', title: 'Outside limit', sorter: seatUserCompare.admission, render: outsideCell }
]

async function load () {
    loading.value = true
    busy.value = true
    try {
        const resp: any = await graphqlClient.query({ query: LICENSE_SEAT_USERS_QUERY, fetchPolicy: 'no-cache' })
        rows.value = resp?.data?.licenseSeatUsers ?? []
        pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(rows.value.length / pagination.pageSize)))
        license.value = resp?.data?.license ?? null
        loadError.value = ''
    } catch (err: any) {
        loadError.value = graphqlRefusalText(err)
    } finally {
        loading.value = false
        busy.value = false
    }
}

async function removeNoRole () {
    const confirm = await Swal.fire({
        title: 'Remove accounts with no role',
        text: removeNoRoleConfirmText(noRoleDeactivationEmails(rows.value, store?.getters?.myuser)),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Deactivate',
        cancelButtonText: 'Cancel'
    })
    if (!confirm?.isConfirmed) return
    busy.value = true
    try {
        const resp: any = await graphqlClient.mutate({ mutation: REMOVE_ACCOUNTS_WITHOUT_ROLE, fetchPolicy: 'no-cache' })
        const n = Number(resp?.data?.removeAccountsWithoutRole) || 0
        Swal.fire({ title: 'Done', text: `${countText(n, 'account')} deactivated.`, icon: 'success' })
    } catch (err: any) {
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    } finally {
        busy.value = false
    }
    await load()
}

onMounted(load)

// SystemSettings.vue reloads the header after a license upload, through the Refresh path
defineExpose({ reload: load })
</script>

<style scoped lang="scss">
.seat-counts {
    display: flex;
    flex-wrap: wrap;
    gap: 16px;
    margin: 4px 0;
}
.seat-users-table {
    width: 100%;
}
</style>
