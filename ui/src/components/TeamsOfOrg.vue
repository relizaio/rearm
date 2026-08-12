<template>
    <div class="teams-pane">
        <div class="tab-toolbar">
            <div class="tab-toolbar-info">
                A team is who you address, not what anyone can do — membership here grants no access.
                Teams hold people directly or through user groups, and carry the channels they are
                reachable on.
            </div>
            <n-button
                v-if="canWrite && teamsSupported"
                type="primary"
                size="small"
                data-testid="add-team"
                @click="openCreateTeam"
            >
                <template #icon><n-icon><CirclePlus /></n-icon></template>
                Add team
            </n-button>
        </div>

        <n-alert
            v-if="!teamsSupported"
            type="info"
            :show-icon="false"
            data-testid="teams-unsupported"
        >
            Teams are not available on this server version. Everything else on this page still works.
        </n-alert>

        <n-data-table
            v-else
            :data="teams"
            :columns="teamColumns"
            :loading="teamsLoading"
            :single-line="false"
            :bordered="false"
            data-testid="teams-table"
        />

        <!-- Assignment rules pick an owner TEAM by component-name pattern, so they
             belong beside the teams themselves -- and this is where the real team
             list lives. They previously sat under User Groups and were handed the
             org's user groups, which stopped being teams in Phase 2a. -->
        <OrgTeamAssignmentRules
            v-if="teamsSupported"
            :orgUuid="orgUuid"
            :isWritable="canWrite"
            :teams="teams"
            :components="props.components || []" />

        <n-modal v-model:show="showTeamModal" preset="dialog" :show-icon="false">
            <n-card
                style="width: 640px"
                size="huge"
                :title="teamForm.uuid ? `Edit team — ${teamForm.name || ''}` : 'Add team'"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <n-form :model="teamForm">
                    <n-space vertical size="large">
                        <n-form-item label="Name">
                            <n-input v-model:value="teamForm.name" placeholder="e.g. Platform" data-testid="team-name" />
                        </n-form-item>
                        <n-form-item label="Description">
                            <n-input v-model:value="teamForm.description" placeholder="Optional" />
                        </n-form-item>

                        <template v-if="teamForm.uuid">
                            <n-form-item label="Members">
                                <n-select
                                    v-model:value="teamForm.members"
                                    :options="userOptions"
                                    multiple
                                    filterable
                                    placeholder="People on this team"
                                    data-testid="team-members"
                                />
                            </n-form-item>
                            <n-form-item label="User groups">
                                <n-select
                                    v-model:value="teamForm.userGroups"
                                    :options="groupOptions"
                                    multiple
                                    filterable
                                    placeholder="Everyone in these groups is also on the team"
                                    data-testid="team-groups"
                                />
                            </n-form-item>
                            <n-form-item label="Notification channels">
                                <n-select
                                    v-model:value="teamForm.notificationChannels"
                                    :options="channelOptions"
                                    multiple
                                    filterable
                                    placeholder="Where this team is reachable"
                                    data-testid="team-channels"
                                />
                            </n-form-item>
                            <n-form-item label="Leads">
                                <n-select
                                    v-model:value="teamForm.leads"
                                    :options="leadOptions"
                                    multiple
                                    filterable
                                    placeholder="Must already be on the team"
                                    data-testid="team-leads"
                                />
                            </n-form-item>
                            <n-text depth="3" style="font-size: 12px;">
                                Leads are recorded now; they do not yet grant any ability to edit the
                                team. Only people on the roster — directly or through a selected user
                                group — can be picked.
                            </n-text>
                        </template>
                        <n-text v-else depth="3" style="font-size: 12px;">
                            Members, groups, channels and leads can be set once the team exists.
                        </n-text>

                        <n-alert v-if="teamModalError" type="error" :show-icon="false" data-testid="team-modal-error">
                            {{ teamModalError }}
                        </n-alert>

                        <n-space>
                            <n-button
                                type="primary"
                                :loading="savingTeam"
                                :disabled="!teamForm.name.trim()"
                                data-testid="save-team"
                                @click="saveTeam"
                            >
                                Save
                            </n-button>
                            <n-button @click="showTeamModal = false">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted, watch } from 'vue'
import {
    NDataTable, NButton, NIcon, NModal, NCard, NForm, NFormItem, NInput,
    NSelect, NSpace, NAlert, NTag, NText, useDialog, useMessage
} from 'naive-ui'
import { CirclePlus, Edit as EditIcon, Archive, ArrowBackUp } from '@vicons/tabler'
import { useStore } from 'vuex'
import graphqlClient from '@/utils/graphql'
import { LIST_CHANNELS_QUERY, TYPE_LABELS, extractError } from '@/utils/notificationsCommon'
import { isSchemaDriftError } from '@/utils/graphqlDriftFallback'
import gql from 'graphql-tag'
import OrgTeamAssignmentRules from './OrgTeamAssignmentRules.vue'

const props = defineProps<{
    orguuid: string
    isWritable: boolean
    /** Passed down for the assignment rules' match preview; the parent has them loaded. */
    components?: any[]
}>()

const store = useStore()
const dialog = useDialog()
const message = useMessage()

const orgUuid = computed<string>(() => props.orguuid)
const canWrite = computed<boolean>(() => props.isWritable)

interface TeamRow {
    uuid: string
    name: string
    description: string | null
    status: string
    members: string[]
    userGroups: string[]
    notificationChannels: string[]
    leads: string[]
}

interface TeamForm {
    uuid: string | null
    name: string
    description: string
    members: string[]
    userGroups: string[]
    notificationChannels: string[]
    leads: string[]
}

function freshTeamForm (): TeamForm {
    return {
        uuid: null, name: '', description: '',
        members: [], userGroups: [], notificationChannels: [], leads: [],
    }
}

// The field list is repeated rather than interpolated from a shared constant on
// purpose: scripts/validate-graphql.mjs SKIPS any gql template containing
// ${...}, because it cannot statically parse one. Factoring these three
// selections into a constant would quietly opt every Team operation out of the
// only check that catches schema drift -- which is the exact failure this
// surface is most exposed to, being Pro-ahead of the CE mirror.
const LIST_TEAMS_QUERY = gql`
    query getTeams($org: ID!) {
        getTeams(org: $org) {
            uuid name description org status members userGroups notificationChannels leads
        }
    }`
const CREATE_TEAM_MUTATION = gql`
    mutation createTeam($team: CreateTeamInput!) {
        createTeam(team: $team) {
            uuid name description org status members userGroups notificationChannels leads
        }
    }`
const UPDATE_TEAM_MUTATION = gql`
    mutation updateTeam($team: UpdateTeamInput!) {
        updateTeam(team: $team) {
            uuid name description org status members userGroups notificationChannels leads
        }
    }`
const LIST_GROUPS_WITH_ROSTER_QUERY = gql`
    query getUserGroups($org: ID!) {
        getUserGroups(org: $org) { uuid name status users manualUsers }
    }`

const teams = ref<TeamRow[]>([])
const teamsLoading = ref<boolean>(false)
// Teams is a Pro-ahead surface, so a backend that predates it must cost only
// THIS pane rather than the whole page -- the same isolation the team-member
// fields use in OrgSettings. Null until probed.
const teamsSupported = ref<boolean>(true)
const users = ref<any[]>([])
const groups = ref<any[]>([])
const channels = ref<any[]>([])
const showTeamModal = ref<boolean>(false)
const savingTeam = ref<boolean>(false)
const teamModalError = ref<string>('')
const teamForm = ref<TeamForm>(freshTeamForm())

const userOptions = computed(() => users.value.map((u: any) => ({
    label: u.name ? `${u.name} (${u.email})` : u.email, value: u.uuid,
})))

const groupOptions = computed(() => groups.value
    .filter((g: any) => g.status !== 'INACTIVE')
    .map((g: any) => ({ label: g.name, value: g.uuid })))

const channelOptions = computed(() => channels.value
    .filter((c: any) => c.status === 'ENABLED')
    .map((c: any) => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid })))

/**
 * The team's effective roster: direct members plus everyone in a selected user
 * group. Mirrors TeamService.resolveRoster so the picker cannot offer a lead
 * the backend will reject -- the same pre-submit-mirror approach the team-role
 * editor uses, and the reason a rejected save here would be a UI bug rather
 * than operator error.
 */
const rosterUuids = computed<Set<string>>(() => {
    const roster = new Set<string>(teamForm.value.members)
    for (const groupUuid of teamForm.value.userGroups) {
        const g = groups.value.find((x: any) => x.uuid === groupUuid)
        if (!g) continue
        for (const u of [...(g.users || []), ...(g.manualUsers || [])]) roster.add(u)
    }
    return roster
})

const leadOptions = computed(() => userOptions.value.filter(o => rosterUuids.value.has(o.value)))

// Dropping someone from the roster must drop their leadership with it, or the
// form offers a save the backend rejects with no visible cause.
watch(rosterUuids, (roster) => {
    teamForm.value.leads = teamForm.value.leads.filter(l => roster.has(l))
})

function memberCount (row: TeamRow): string {
    const direct = (row.members || []).length
    const viaGroups = (row.userGroups || []).length
    return viaGroups > 0
        ? `${direct} direct + ${viaGroups} group(s)`
        : `${direct} direct`
}

async function loadTeams (): Promise<void> {
    teamsLoading.value = true
    try {
        const res = await graphqlClient.query({
            query: LIST_TEAMS_QUERY,
            variables: { org: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        teams.value = res.data?.getTeams || []
        teamsSupported.value = true
    } catch (e: any) {
        if (isSchemaDriftError(e)) {
            teamsSupported.value = false
        } else {
            message.error(`Failed to load teams: ${extractError(e)}`)
        }
    } finally {
        teamsLoading.value = false
    }
}

async function loadPickers (): Promise<void> {
    try {
        users.value = await store.dispatch('fetchUsers', { orgUuid: orgUuid.value, includeInactive: false })
    } catch (e: any) {
        message.error(`Failed to load users: ${extractError(e)}`)
    }
    try {
        const res = await graphqlClient.query({
            query: LIST_GROUPS_WITH_ROSTER_QUERY,
            variables: { org: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        groups.value = res.data?.getUserGroups || []
    } catch (e: any) {
        message.error(`Failed to load user groups: ${extractError(e)}`)
    }
    try {
        const res = await graphqlClient.query({
            query: LIST_CHANNELS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channels.value = res.data?.notificationChannels || []
    } catch (e: any) {
        // A missing channel list must not block renaming a team or editing its
        // roster, so this degrades to an empty picker rather than a hard error.
        channels.value = []
    }
}

function openCreateTeam (): void {
    teamForm.value = freshTeamForm()
    teamModalError.value = ''
    showTeamModal.value = true
}

function openEditTeam (row: TeamRow): void {
    teamForm.value = {
        uuid: row.uuid,
        name: row.name,
        description: row.description || '',
        members: [...(row.members || [])],
        userGroups: [...(row.userGroups || [])],
        notificationChannels: [...(row.notificationChannels || [])],
        leads: [...(row.leads || [])],
    }
    teamModalError.value = ''
    showTeamModal.value = true
}

async function saveTeam (): Promise<void> {
    teamModalError.value = ''
    const f = teamForm.value
    if (!f.name.trim()) {
        teamModalError.value = 'Name is required.'
        return
    }
    savingTeam.value = true
    try {
        if (f.uuid) {
            await graphqlClient.mutate({
                mutation: UPDATE_TEAM_MUTATION,
                variables: {
                    team: {
                        teamId: f.uuid,
                        name: f.name.trim(),
                        description: f.description,
                        members: f.members,
                        userGroups: f.userGroups,
                        notificationChannels: f.notificationChannels,
                        leads: f.leads,
                    },
                },
            })
        } else {
            await graphqlClient.mutate({
                mutation: CREATE_TEAM_MUTATION,
                variables: {
                    team: { name: f.name.trim(), description: f.description, org: orgUuid.value },
                },
            })
        }
        showTeamModal.value = false
        message.success(f.uuid ? 'Team updated' : 'Team created')
        await loadTeams()
    } catch (e: any) {
        teamModalError.value = extractError(e)
    } finally {
        savingTeam.value = false
    }
}

/**
 * Archive / restore rather than delete. A team is a reference target, so
 * removing the row would leave dangling pointers behind; archiving keeps the
 * roster and the name reserved, and is reversible.
 */
function confirmSetStatus (row: TeamRow, status: 'ACTIVE' | 'INACTIVE'): void {
    const archiving = status === 'INACTIVE'
    dialog.warning({
        title: archiving ? `Archive team "${row.name}"?` : `Restore team "${row.name}"?`,
        content: archiving
            ? 'The team keeps its roster and channels, and its name stays reserved. You can restore it later.'
            : 'The team becomes selectable again.',
        positiveText: archiving ? 'Archive' : 'Restore',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({
                    mutation: UPDATE_TEAM_MUTATION,
                    variables: { team: { teamId: row.uuid, status } },
                })
                message.success(archiving ? 'Team archived' : 'Team restored')
                await loadTeams()
            } catch (e: any) {
                message.error(`Failed: ${extractError(e)}`)
            }
        },
    })
}

const teamColumns = computed(() => [
    { title: 'Name', key: 'name' },
    { title: 'Description', key: 'description' },
    { title: 'Members', key: 'members', render: (row: TeamRow) => memberCount(row) },
    {
        title: 'Channels', key: 'notificationChannels',
        render: (row: TeamRow) => `${(row.notificationChannels || []).length}`,
    },
    { title: 'Leads', key: 'leads', render: (row: TeamRow) => `${(row.leads || []).length}` },
    {
        title: 'Status', key: 'status',
        render: (row: TeamRow) => h(NTag, {
            size: 'small', type: row.status === 'ACTIVE' ? 'success' : 'default',
        }, { default: () => row.status }),
    },
    {
        title: 'Actions', key: 'actions',
        render: (row: TeamRow) => h(NSpace, { size: 'small' }, {
            default: () => [
                h(NButton, {
                    size: 'tiny', secondary: true, disabled: !canWrite.value,
                    'data-testid': 'edit-team',
                    onClick: () => openEditTeam(row),
                }, { icon: () => h(NIcon, null, { default: () => h(EditIcon) }) }),
                h(NButton, {
                    size: 'tiny', secondary: true, disabled: !canWrite.value,
                    'data-testid': row.status === 'ACTIVE' ? 'archive-team' : 'restore-team',
                    onClick: () => confirmSetStatus(row, row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
                }, {
                    icon: () => h(NIcon, null, {
                        default: () => h(row.status === 'ACTIVE' ? Archive : ArrowBackUp),
                    }),
                }),
            ],
        }),
    },
])

onMounted(async () => {
    await Promise.all([loadTeams(), loadPickers()])
})
</script>

<style scoped>
.tab-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0 16px;
}
.tab-toolbar-info { font-size: 12.5px; color: var(--n-text-color-3, #888); }
</style>
