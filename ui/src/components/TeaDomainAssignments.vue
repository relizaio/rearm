<template>
    <div class="teaDomainsBlock" data-testid="tea-domains-block">
        <h4 class="mt-4">TEA Custom Domains: </h4>
        <p class="text-muted" data-testid="tea-domains-help">{{ TEA_DOMAINS_HELP }}</p>
        <div v-if="loading" data-testid="tea-domains-loading">Loading TEA domains...</div>
        <div v-else-if="loadError" data-testid="tea-domains-load-error" class="text-danger">{{ loadError }}</div>
        <div v-else-if="!rows.length" data-testid="tea-domains-empty" class="text-muted">{{ TEA_DOMAINS_EMPTY }}</div>
        <table v-else class="table" data-testid="tea-domains-table">
            <thead><tr><th>Host</th><th>Organization</th><th>Action</th></tr></thead>
            <tbody>
                <tr v-for="r in rows" :key="r.host" :data-testid="'tea-domain-row-' + r.host">
                    <td>
                        <code>{{ r.host }}</code>
                        <span v-if="r.isBaseuriHost" :data-testid="'tea-domain-rearm-host-' + r.host"><n-tag size="small" type="info">ReARM host</n-tag></span>
                        <span v-if="!r.inAllowList" :data-testid="'tea-domain-unlisted-' + r.host"><n-tag size="small" type="warning">not in the allow-list</n-tag></span>
                        <div v-if="r.isBaseuriHost" class="text-muted" data-testid="tea-domain-rearm-host-note">{{ TEA_REARM_HOST_NOTE }}</div>
                    </td>
                    <td :data-testid="'tea-domain-org-' + r.host">
                        <template v-if="r.org">{{ r.orgName ?? r.org }}
                            <div class="text-muted" :data-testid="'tea-domain-serving-' + r.host">TEA serving: {{ servingLabel(r.teaServing) }}</div>
                        </template>
                        <span v-else class="text-muted">Unassigned</span>
                    </td>
                    <td>
                        <template v-if="r.org">
                            <span :data-testid="'tea-domain-unassign-' + r.host"><n-button size="small" :disabled="busy" @click="unassign(r)">Unassign</n-button></span>
                        </template>
                        <template v-else>
                            <div :data-testid="'tea-domain-org-select-' + r.host"><n-select filterable size="small" :options="orgOptions" :value="chosen[r.host] ?? null" placeholder="Pick an organization" @update:value="(v: string) => chosen[r.host] = v" /></div>
                            <span :data-testid="'tea-domain-assign-' + r.host"><n-button size="small" type="primary" :disabled="busy || !chosen[r.host]" @click="assign(r)">Assign</n-button></span>
                        </template>
                        <div v-if="rowErrors[r.host]" class="text-danger" :data-testid="'tea-domain-error-' + r.host">{{ rowErrors[r.host] }}</div>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script setup lang="ts">
// TEA custom domains (task TPF-144): the hosts a global administrator may assign to one organization
// each, as the server lists them, with assign and unassign per row and the server's refusal text shown
// under the row that failed. Rendered by SystemSettings.vue for global administrators on Pro only.
import { onMounted, reactive, ref } from 'vue'
import { NButton, NSelect, NTag } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import { TEA_SERVING_LABELS, TeaServing } from '@/utils/teaProfile'

const TEA_DOMAINS_HELP = 'Assign a domain to one organization. Its administrator then chooses, in the organization TEA settings, whether TEA is served on it.'
const TEA_DOMAINS_EMPTY = 'No TEA domain can be assigned: the TEA custom domain allow-list is empty and the instance host is not a DNS name.'
const TEA_REARM_HOST_NOTE = "The ReARM host is unassigned by default. Assigning it exposes the ReARM host's /.well-known/tea and /tea/v1.0.0/ for that organization once the organization serves TEA on its domain."

const rows = ref<any[]>([])
const orgOptions = ref<{ label: string, value: string }[]>([])
const chosen = reactive<Record<string, string | null>>({})
const rowErrors = reactive<Record<string, string>>({})
const loading = ref(true)
const loadError = ref('')
const busy = ref(false)

function servingLabel (s: string | null | undefined): string {
    return s ? TEA_SERVING_LABELS[s as TeaServing] : ''
}

async function load () {
    try {
        const [hosts, orgs]: any[] = await Promise.all([
            graphqlClient.query({ query: graphqlQueries.TeaHostAssignmentsGql, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: graphqlQueries.AllOrganizationsGql, fetchPolicy: 'no-cache' }),
        ])
        // The server's order (custom hosts, the instance host, stale entries) is kept.
        rows.value = hosts.data.teaHostAssignments ?? []
        orgOptions.value = (orgs.data.allOrganizations ?? []).filter((o: any) => o?.uuid)
            .map((o: any) => ({ label: o.name ?? o.uuid, value: o.uuid }))
            .sort((a: { label: string }, b: { label: string }) => a.label.localeCompare(b.label))
    } catch (err: any) {
        console.error(err)
        loadError.value = graphqlRefusalText(err)
    } finally {
        loading.value = false
    }
}

async function assign (r: any) {
    busy.value = true
    delete rowErrors[r.host]
    try {
        const resp: any = await graphqlClient.mutate({ mutation: graphqlQueries.AssignTeaHostGql,
            variables: { host: r.host, org: chosen[r.host] } })
        const i = rows.value.findIndex((x: any) => x.host === r.host)
        if (i >= 0) rows.value.splice(i, 1, resp.data.assignTeaHost)
        delete chosen[r.host]
    } catch (err: any) {
        console.error(err)
        rowErrors[r.host] = graphqlRefusalText(err)
    } finally {
        busy.value = false
    }
}

async function unassign (r: any) {
    busy.value = true
    delete rowErrors[r.host]
    try {
        const resp: any = await graphqlClient.mutate({ mutation: graphqlQueries.UnassignTeaHostGql,
            variables: { host: r.host } })
        if (resp.data.unassignTeaHost !== true) throw new Error('Unassign did not complete')
        if (r.inAllowList) {
            const i = rows.value.findIndex((x: any) => x.host === r.host)
            if (i >= 0) rows.value.splice(i, 1, { ...r, org: null, orgName: null, teaServing: null })
        } else {
            // The server lists a host off the allow-list only while it is assigned.
            rows.value = rows.value.filter((x: any) => x.host !== r.host)
            delete chosen[r.host]
            delete rowErrors[r.host]
        }
    } catch (err: any) {
        console.error(err)
        rowErrors[r.host] = graphqlRefusalText(err)
    } finally {
        busy.value = false
    }
}

onMounted(load)
</script>
