<template>
    <div class="licenseSeatUsersBlock" data-testid="seat-users-block">
        <h4 class="mt-4">License Seats: </h4>
        <div v-if="counts" class="seat-counts" data-testid="seat-counts">
            <span data-testid="seat-counts-write"><strong>Write:</strong> {{ counts.writeUsers ?? 0 }} / {{ limitText(maxWrite) }}</span>
            <span data-testid="seat-counts-read"><strong>Read:</strong> {{ counts.readUsers ?? 0 }} / {{ limitText(maxRead) }}</span>
            <span data-testid="seat-counts-norole"><strong>No role (not counted):</strong> {{ counts.noRoleUsers ?? 0 }}</span>
            <span data-testid="seat-counts-outside"><strong>Outside the limit:</strong> {{ counts.outsideLimit ?? 0 }}</span>
            <span v-if="counts.countedAt" class="text-muted" data-testid="seat-counts-at">counted {{ dateText(counts.countedAt) }}</span>
        </div>
        <n-space :size="8" style="margin: 8px 0;">
            <span data-testid="seat-users-refresh"><n-button size="small" :disabled="busy" @click="load">Refresh</n-button></span>
            <span data-testid="seat-users-remove-no-role"><n-button size="small" type="warning" :disabled="busy" @click="removeNoRole">Remove accounts with no role</n-button></span>
        </n-space>
        <div v-if="loadError" class="text-danger" data-testid="seat-users-error">{{ loadError }}</div>
        <div v-else-if="loading && !rows.length" data-testid="seat-users-loading">Loading seat users...</div>
        <table v-else class="table seat-users-table" data-testid="seat-users-table">
            <thead><tr><th>Email</th><th>Type</th><th>Created</th><th>Last login</th><th>Outside limit</th></tr></thead>
            <tbody>
                <tr v-for="r in rows" :key="r.email" :data-testid="'seat-user-row-' + r.email">
                    <td>
                        <div>{{ r.email }}</div>
                        <div v-if="r.name" class="text-muted small">{{ r.name }}</div>
                        <n-tag v-if="r.globalAdmin" size="small" type="info" :data-testid="'seat-user-global-admin-' + r.email">Global administrator</n-tag>
                    </td>
                    <td :data-testid="'seat-user-type-' + r.email">{{ r.seatType }}</td>
                    <td :data-testid="'seat-user-created-' + r.email">{{ dateText(r.createdDate) }}</td>
                    <td :data-testid="'seat-user-last-login-' + r.email">{{ r.lastLoginDate ? dateText(r.lastLoginDate) : 'never' }}</td>
                    <td :data-testid="'seat-user-outside-' + r.email">
                        <n-tag v-if="r.admission === SeatAdmission.OUTSIDE" size="small" type="error">outside the limit</n-tag>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script setup lang="ts">
// The license seat view (task TPF-168, TPF-165 D10): every seat holder of the instance, one row per
// email (type READ/WRITE/NONE, created, last login, outside-limit flag), the counts header, Refresh
// and "Remove accounts with no role". Rendered by SystemSettings.vue for global administrators on
// Pro. Every read is a fresh count on the server; the client never caches it. Every number and flag
// is the server's field as given: the outside-limit flag reads `admission` alone and the
// Global administrator marker reads `globalAdmin` alone (no UI rule combines them). Server texts go
// to Swal as `text`, never as the positional shorthand, whose second argument is HTML.
import { computed, onMounted, ref, type Ref } from 'vue'
import { NButton, NSpace, NTag } from 'naive-ui'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import {
    LICENSE_SEAT_USERS_QUERY, REMOVE_ACCOUNTS_WITHOUT_ROLE, SeatAdmission, removeNoRoleConfirmText
} from '@/utils/licenseSeats'

const emit = defineEmits<{ (e: 'license', license: any): void }>()

const rows: Ref<any[]> = ref([])
const license: Ref<any> = ref(null)
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')

const counts = computed(() => license.value?.globalUserCounts ?? null)
const maxWrite = computed(() => license.value?.maxWriteUsers)
const maxRead = computed(() => license.value?.maxReadUsers)

const limitText = (n: any): string => (n === null || n === undefined) ? 'unlimited' : String(n)
const dateText = (d: string): string => d ? new Date(d).toLocaleString('en-CA') : ''

async function load () {
    loading.value = true
    busy.value = true
    try {
        const resp: any = await graphqlClient.query({ query: LICENSE_SEAT_USERS_QUERY, fetchPolicy: 'no-cache' })
        rows.value = resp?.data?.licenseSeatUsers ?? []
        license.value = resp?.data?.license ?? null
        loadError.value = ''
        if (license.value) emit('license', license.value)
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
        text: removeNoRoleConfirmText(counts.value?.noRoleUsers),
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
        Swal.fire({ title: 'Done', text: `${n} account${n === 1 ? '' : 's'} deactivated.`, icon: 'success' })
    } catch (err: any) {
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    } finally {
        busy.value = false
    }
    await load()
}

onMounted(load)
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
