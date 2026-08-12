<template>
    <div class="subscriptions-pane">
        <div class="tab-toolbar">
            <div class="tab-toolbar-info">
                Rules that pick which events fire on which channels. One subscription matches a set of event types, optionally narrows via a filter, and fans out to a destination gated by severity and perspectives.
            </div>
            <n-button
                v-if="canWrite"
                type="primary"
                size="small"
                @click="openCreateSubscription"
                data-testid="add-subscription"
            >
                <template #icon><n-icon><CirclePlus /></n-icon></template>
                Add subscription
            </n-button>
        </div>

        <n-alert
            v-if="subscriptionsDegraded"
            type="warning"
            :show-icon="false"
            style="margin-bottom: 12px;"
            data-testid="subscriptions-degraded-alert"
        >
            Some subscription details are unavailable on this server version, so a few fields (filter, routes, dedup/rate-limit) may be missing. Your subscriptions are shown below.
        </n-alert>

        <n-data-table
            :data="subscriptions"
            :columns="subscriptionColumns"
            :loading="subscriptionsLoading"
            :single-line="false"
            :bordered="false"
        />

        <!-- Subscription create/edit modal -->
        <n-modal v-model:show="showSubscriptionModal" preset="dialog" :show-icon="false">
            <n-card
                style="width: 760px"
                size="huge"
                :title="subForm.uuid ? `Edit subscription — ${subForm.name || ''}` : 'Add subscription'"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <n-form :model="subForm">
                    <n-space vertical size="large">
                        <n-grid :cols="2" :x-gap="12">
                            <n-gi>
                                <n-form-item label="Name">
                                    <n-input v-model:value="subForm.name" placeholder="e.g. critical-vuln-oncall" />
                                </n-form-item>
                            </n-gi>
                            <n-gi>
                                <n-form-item label="Status">
                                    <n-select v-model:value="subForm.status" :options="subscriptionStatusOptions" />
                                </n-form-item>
                            </n-gi>
                        </n-grid>

                        <n-form-item label="Event types">
                            <n-select
                                v-model:value="subForm.eventTypes"
                                :options="eventTypeOptionsForForm"
                                multiple
                                placeholder="Pick one or more event types"
                                data-testid="sub-eventtypes"
                            />
                        </n-form-item>
                        <div class="field-hint">This subscription fires only when a selected event type is actually emitted. "VEX state changed" is reserved and not emitted yet, so it isn't selectable (an existing selection can still be removed).</div>

                        <n-form-item label="Filter mode">
                            <n-radio-group v-model:value="subForm.filterMode">
                                <n-radio-button value="PRESET">Preset (match all selected event types)</n-radio-button>
                                <n-radio-button value="ADVANCED">Advanced (CEL)</n-radio-button>
                            </n-radio-group>
                        </n-form-item>
                        <n-form-item v-if="subForm.filterMode === 'ADVANCED'" label="CEL expression">
                            <n-input
                                v-model:value="subForm.celExpression"
                                type="textarea"
                                :autosize="{ minRows: 3, maxRows: 8 }"
                                style="font-family: monospace; font-size: 12px;"
                                placeholder='e.g. event.severity == "CRITICAL" && size(event.affectedReleases) > 0'
                            />
                        </n-form-item>
                        <div v-if="subForm.filterMode === 'ADVANCED'" class="field-hint">
                            A CEL boolean over the event. Examples:
                            <code>event.severity == "CRITICAL"</code>,
                            <code>event.kevListed == true</code>,
                            <code>size(event.affectedReleases) &gt; 0</code>.
                            Leave Preset mode to match on event type alone.
                        </div>

                        <!-- Severity and Perspectives live on the route in the
                             backend model, but they are filters, not
                             destinations, so they are grouped with the other
                             filters here and written back onto the single
                             route on save. -->
                        <n-grid :cols="2" :x-gap="12">
                            <n-gi v-if="severityApplies">
                                <n-form-item label="Minimum severity">
                                    <n-select
                                        v-model:value="subForm.routes[0].whenSeverityAtLeast"
                                        :options="severityOptions"
                                        placeholder="Any"
                                        clearable
                                        data-testid="route-severity"
                                    />
                                </n-form-item>
                            </n-gi>
                            <n-gi>
                                <n-form-item label="Perspectives">
                                    <n-select
                                        v-model:value="subForm.routes[0].perspectives"
                                        :options="perspectiveOptions"
                                        multiple
                                        clearable
                                        placeholder="All perspectives (no restriction)"
                                        data-testid="route-perspectives"
                                    />
                                </n-form-item>
                            </n-gi>
                        </n-grid>
                        <div class="field-hint">
                            Perspectives gate what this subscription <strong>delivers</strong> — they do
                            not affect the in-app inbox or the bell. Leave empty for no restriction.
                            They are resolved from the event's affected releases, so a route only
                            fires when an affected release's component belongs to one of the chosen
                            perspectives -- a component with no perspectives set matches none of them.
                        </div>
                        <!-- This hint used to say perspectives are carried by vulnerability events
                             only, so "release and approval events match nothing" with one set. That
                             is FALSE, and it was the stated reason to hide this control the way the
                             severity one is hidden. ReleaseChangeHookImpl (created / lifecycle /
                             bom-diff) and ApprovalEventNotifierImpl (requested / resolved) both
                             stamp ReleaseNotificationSupport.buildAffectedReleases onto the outbox
                             payload, and that helper copies the COMPONENT's perspectives onto each
                             AffectedRelease. So every event family carries them and this gate works
                             on all of them; what it needs is a component IN the perspective. The
                             same claim survives in perspectiveGateMatches' javadoc (rearm-saas) and
                             is tracked on the board. Severity is genuinely different -- no producer
                             resolves a severity outside the two vuln types -- which is why that one
                             is hidden and this one is not. -->

                        <div class="routes-section">
                            <div class="routes-title">Destination</div>
                            <div class="routes-hint">
                                Where matching events are delivered. Pick any combination — they are merged,
                                and a channel reached twice still gets one delivery.
                            </div>
                            <div class="route-row" data-testid="route-row-0">
                                <n-form-item label="Channels" :show-feedback="false">
                                    <n-select
                                        v-model:value="subForm.routes[0].channels"
                                        :options="channelOptions"
                                        multiple
                                        placeholder="Pick channels (and/or groups below)"
                                        data-testid="route-channels"
                                    />
                                </n-form-item>
                                <n-form-item label="Channel groups (optional)" :show-feedback="false">
                                    <n-select
                                        v-model:value="subForm.routes[0].channelGroups"
                                        :options="channelGroupOptions"
                                        multiple
                                        placeholder="(none)"
                                        clearable
                                        data-testid="route-channelgroups"
                                    />
                                </n-form-item>
                                <!-- Hidden when there is nothing to offer. Covers both "this org
                                     has no teams" and "this backend has no team channels" (a CE
                                     backend, where loadTeams soft-fails to []): an empty picker
                                     under copy promising a feature that cannot work is worse
                                     than no picker. teamOptions still keeps ghosts for teams
                                     already saved on the route, so those stay removable. -->
                                <template v-if="teamOptions.length">
                                    <n-form-item :show-feedback="false">
                                        <template #label>
                                            <span style="display: inline-flex; align-items: center; gap: 6px;">
                                                Teams (optional)
                                                <n-tooltip trigger="hover" style="max-width: 360px;">
                                                    <template #trigger>
                                                        <n-icon size="16" class="clickable"><InfoCircle /></n-icon>
                                                    </template>
                                                    Delivers to each team's own notification channels, resolved
                                                    when the event fires -- so if a team changes its channel,
                                                    this follows automatically.
                                                </n-tooltip>
                                            </span>
                                        </template>
                                        <n-select
                                            v-model:value="subForm.routes[0].teams"
                                            :options="teamOptions"
                                            multiple
                                            placeholder="(none)"
                                            clearable
                                            data-testid="route-teams"
                                        />
                                    </n-form-item>
                                </template>
                                <!-- T4a. Pro-only: notifyComponentOwner does not exist in the CE
                                     schema, and GraphQL input coercion rejects unknown keys
                                     outright, so letting a CE operator tick this would fail the
                                     WHOLE subscription save -- including edits that never touch
                                     it. Gated on the real edition signal rather than on
                                     teamOptions.length like the Teams picker above: that proxy
                                     is empty on a Pro org with no teams yet, and non-empty on CE
                                     whenever ghosts survive for a route's saved teams. -->
                                <template v-if="isPro">
                                    <n-form-item :show-feedback="false">
                                        <template #label>
                                            <span style="display: inline-flex; align-items: center; gap: 6px;">
                                                Notify Component Owner
                                                <n-tooltip trigger="hover" style="max-width: 360px;">
                                                    <template #trigger>
                                                        <n-icon size="16" class="clickable"><InfoCircle /></n-icon>
                                                    </template>
                                                    Resolved when the event fires, from the component's owner --
                                                    set directly or by an assignment rule. Unlike picking a team
                                                    above, this follows ownership changes on its own, so
                                                    reassigning a component never means editing this
                                                    subscription.
                                                </n-tooltip>
                                            </span>
                                        </template>
                                        <n-checkbox
                                            v-model:checked="subForm.routes[0].notifyComponentOwner"
                                            data-testid="route-notify-owner"
                                        >
                                            Also notify the team that owns the affected component
                                        </n-checkbox>
                                    </n-form-item>
                                    <!-- The description moved to the label's tooltip. This warning
                                         stays INLINE and unconditional-looking on purpose: it is not
                                         an explanation of the feature, it is a statement that ticking
                                         the box right now achieves nothing, and a caveat hidden
                                         behind a hover is a caveat nobody reads. -->
                                    <div v-if="!teamOptions.length" class="muted-12"
                                        style="margin-top: -6px; margin-bottom: 6px;">
                                        This org has no teams yet, and only a team can own a
                                        component, so this delivers nothing until one exists.
                                    </div>
                                </template>
                            </div>
                        </div>

                        <n-grid :cols="2" :x-gap="12">
                            <n-gi>
                                <n-form-item label="Dedup window (minutes, optional)">
                                    <n-input-number
                                        v-model:value="subForm.dedupWindowMinutes"
                                        :min="0"
                                        clearable
                                        placeholder="Default: 1440 (24h)"
                                        style="width: 100%;"
                                    />
                                </n-form-item>
                                <div class="field-hint">Suppresses repeat deliveries of the same event within the window. Leave empty for the 24h default; set 0 to deliver every matching event.</div>
                            </n-gi>
                            <!-- The rate-limit control is deliberately absent. `rateLimit` is
                                 parsed, stored and echoed back by the API, but NOTHING reads it:
                                 neither the fan-out nor the delivery worker consults it, so a
                                 rate limit set here has never suppressed anything. A field that
                                 does nothing is a trap even with a hint saying so, which is the
                                 same reason PREVIEW came out of the status list.

                                 The PLUMBING stays on purpose -- the form still loads the stored
                                 value in openEditSubscription and still sends it back on save --
                                 so an existing subscription's rate limit round-trips untouched
                                 rather than being silently cleared by an unrelated edit. When
                                 enforcement lands, the control comes back and no data was lost
                                 in the meantime. -->
                        </n-grid>

                        <n-alert v-if="subModalError" type="error" :show-icon="false">
                            {{ subModalError }}
                            <template v-if="isConflictError(subModalError)" #action>
                                <n-button size="small" type="primary" @click="loadSubscriptions">
                                    Reload from server
                                </n-button>
                            </template>
                        </n-alert>

                        <n-space>
                            <n-button
                                @click="saveSubscription"
                                type="primary"
                                :loading="savingSubscription"
                                :disabled="!subForm.name.trim() || subForm.eventTypes.length === 0 || subForm.routes.length === 0"
                                data-testid="save-subscription"
                            >
                                Save
                            </n-button>
                            <n-button @click="showSubscriptionModal = false">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted, onUnmounted, watch, Ref } from 'vue'
import { useStore } from 'vuex'
import {
    NDataTable, NButton, NIcon, NModal, NCard, NForm, NFormItem, NInput,
    NInputNumber, NSelect, NSpace, NAlert, NGrid, NGi, NTag, NDropdown, NTooltip,
    NRadioGroup, NRadioButton, NCheckbox, useDialog, useMessage
} from 'naive-ui'
import { InfoCircle, CirclePlus, Trash, Edit as EditIcon, Send, History } from '@vicons/tabler'
import { useRouter } from 'vue-router'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { buildChannelOptions, withGhosts } from '@/utils/channelOptions'
import { isSchemaDriftError } from '@/utils/graphqlDriftFallback'
import {
    ChannelRow, ChannelGroupRow, SubscriptionRow, TYPE_LABELS,
    subscriptionStatusOptions, eventTypeOptions, severityOptions,
    LIST_CHANNELS_QUERY, LIST_GROUPS_CORE_QUERY,
    LIST_SUBSCRIPTIONS_QUERY, LIST_SUBSCRIPTIONS_CORE_QUERY,
    extractError, isConflictError, templatesForEventTypes, buildNameMap, deliveryStatusTagType,
    buildNotificationFilterInput,
    buildNotificationRouteInput,
    routeHasTarget,
    classifySubscriptionTest,
    severityAppliesTo,
    clearInapplicableSeverity,
    noDeliveryExplanation,
    ownerRoutedSuccessCaveat,
    routeCount,
    hasUneditableMultiRoute,
    type SubscriptionTestOutcome
} from '@/utils/notificationsCommon'
import { loadWithSchemaDriftFallback } from '@/utils/graphqlDriftFallback'
import { isProEdition } from '@/utils/editionCapabilities'

const props = defineProps<{
    orguuid: string
    isWritable: boolean
    // T4a: needed for the owner-routing control, which is a Pro-only route
    // field. Unlike the Teams picker next to it, teamOptions.length is NOT a
    // usable proxy here -- it is empty on a Pro org that simply has no teams
    // yet, and non-empty on CE when ghosts are kept for a route's saved teams.
    // Sending a Pro-only key to a CE backend fails the WHOLE mutation, so this
    // gate uses the real edition signal. See editionCapabilities.ts.
    installationType: string
}>()

const dialog = useDialog()
const message = useMessage()
const store = useStore()
const router = useRouter()

// Deep-link into the Delivery History audit surface, pre-filtered to this
// subscription, so a user can go from "this subscription" straight to "what did
// it actually deliver (and why did some fail)". Mirrors the inbox/channel
// "View delivery log" deep-links.
function viewSubscriptionDeliveries (row: SubscriptionRow): void {
    router.push({
        name: 'OrgSettings',
        params: { orguuid: orgUuid.value },
        query: { tab: 'audit', auditTab: 'deliveryHistory', historySubscription: row.uuid },
    })
}

const orgUuid = computed<string>(() => props.orguuid)
const canWrite = computed<boolean>(() => props.isWritable)

interface SubscriptionRoute {
    whenSeverityAtLeast: string | null
    channels: string[]
    channelGroups: string[]
    teams: string[]
    // T4a: deliver to whatever team OWNS each affected component, resolved at
    // fan-out. Distinct from `teams` above, which is a fixed list the operator
    // must keep in step with ownership by hand.
    notifyComponentOwner: boolean
    // Delivery filter: restricts which events this route's channels deliver
    // to the listed perspectives. NOT the inbox/bell visibility gate. Empty
    // = no restriction (all perspectives).
    perspectives: string[]
    // Carries the as-loaded route object on edit so fields the UI still
    // doesn't model (andEnvIn, andLifecycleIn) survive an Edit -> Save
    // round-trip instead of being silently stripped. channels +
    // channelGroups + perspectives overlay this last and win. Empty on Create.
    _raw?: Record<string, any>
}

interface SubscriptionForm {
    uuid: string | null
    expectedRevision: number | null
    name: string
    status: string
    eventTypes: string[]
    filterMode: string
    celExpression: string
    routes: SubscriptionRoute[]
    dedupWindowMinutes: number | null
    rateLimitMaxPerWindow: number | null
    rateLimitWindowMinutes: number | null
    // Carries the as-loaded filter object on edit so `presetConfigJson`
    // (and any other field the UI doesn't model yet) survives an
    // Edit -> Save round-trip. Empty on Create.
    _rawFilter?: Record<string, any>
    // Same idea for the rate limit, and load-bearing now that the control is
    // hidden: the modelled pair drops a partial limit, and nothing on screen
    // would show the operator what was lost. Empty on Create.
    _rawRateLimit?: Record<string, any>
}

function freshRoute (): SubscriptionRoute {
    return { whenSeverityAtLeast: null, channels: [], channelGroups: [], teams: [],
        notifyComponentOwner: false, perspectives: [] }
}

function freshSubscriptionForm (): SubscriptionForm {
    return {
        uuid: null,
        expectedRevision: null,
        name: '',
        status: 'ACTIVE',
        eventTypes: [],
        filterMode: 'PRESET',
        celExpression: '',
        routes: [freshRoute()],
        dedupWindowMinutes: null,
        rateLimitMaxPerWindow: null,
        rateLimitWindowMinutes: null,
    }
}

const channels = ref<ChannelRow[]>([])
const channelGroups = ref<ChannelGroupRow[]>([])
const subscriptions = ref<SubscriptionRow[]>([])
const subscriptionsLoading = ref<boolean>(false)
// True when the backend rejected the full subscription selection and we fell
// back to core fields (config columns may be absent) -- see PR #169 pattern.
const subscriptionsDegraded = ref<boolean>(false)
const showSubscriptionModal = ref<boolean>(false)
const savingSubscription = ref<boolean>(false)
const subModalError = ref<string>('')
const subForm = ref<SubscriptionForm>(freshSubscriptionForm())

// Hide the minimum-severity control when no selected event type can carry a
// severity. The predicate and the clearing live in notificationsCommon so the
// spec suite can pin them -- there is no component test environment here, and
// review found the first cut of this shipped its only behavioural rule inside
// the .vue where nothing could reach it. See severityAppliesTo /
// clearInapplicableSeverity for WHY a stale gate is a trap rather than a no-op.
const severityApplies = computed<boolean>(() => severityAppliesTo(subForm.value.eventTypes))

// Clears while the modal is OPEN, i.e. when the last vuln event type is
// removed. The load and save paths clear too, and must: a watcher fires on
// CHANGE, so opening a subscription that was ALREADY in the bad state never
// runs this one.
watch(severityApplies, () => {
    clearInapplicableSeverity(subForm.value.routes, subForm.value.eventTypes)
})

// Event-type options for THIS form. The shared eventTypeOptions marks
// VEX_STATE_CHANGED disabled (no backend producer yet), which also makes
// naive-ui suppress its tag "x" (closable: !disabled). That would trap a
// legacy subscription that already selected VEX -- it could never be
// removed via the form. So keep VEX disabled only while it is NOT already
// selected: unselectable for new subs, still removable when editing an
// existing VEX subscription (after removal it re-disables, no re-add).
const eventTypeOptionsForForm = computed(() =>
    eventTypeOptions.map(o =>
        o.value === 'VEX_STATE_CHANGED'
            ? { ...o, disabled: !subForm.value.eventTypes.includes('VEX_STATE_CHANGED') }
            : o)
)

const channelOptions = computed(() => {
    // Ghost handling for already-referenced DISABLED/DELETED channels lives in
    // the shared builder (BUG 2) -- the Team editor needs the identical
    // behaviour, and a second copy is how that fix silently rots.
    const referenced = new Set<string>()
    for (const r of subForm.value.routes) for (const ch of (r.channels || [])) referenced.add(ch)
    return buildChannelOptions(channels.value, referenced, TYPE_LABELS)
})

const teams = ref<any[]>([])

/**
 * The name of the team that owns a managed row.
 *
 * Falls back to the uuid rather than to nothing: this string is the operator's
 * only route to the control that actually changes the subscription, so a team
 * that failed to load must still leave them something to search for.
 */
/**
 * Why every control on this row is withheld, or null when it is an ordinary
 * subscription.
 *
 * One string for all three buttons: the operator does not care which control
 * they reached for, they care where the switch actually is.
 */
function managedRowExplanation (row: SubscriptionRow): string | null {
    if (!row.managedByTeam) return null
    return `${managedTeamName(row)} owns this subscription -- it exists because that team asked to`
        + ' hear about the components it owns. Change it on the team: Organization Settings ->'
        + ' Teams.'
}

function managedTeamName (row: SubscriptionRow): string {
    const team = teams.value.find((t: any) => t.uuid === row.managedByTeam)
    return team?.name || String(row.managedByTeam)
}

// T4a: owner routing is a Pro-only route field. The control, the client-side
// "route has a target" check and the error copy all gate on this one flag, so
// a CE operator can neither author an owner-only route nor be told to.
const isPro = computed(() => isProEdition(props.installationType))

const teamOptions = computed(() => {
    const selectable = teams.value
        .filter((t: any) => t.status !== 'INACTIVE')
        .map((t: any) => ({
            // Surface the channel count: a team with none delivers nothing, and
            // that is invisible otherwise.
            label: `${t.name} (${(t.notificationChannels || []).length} ch)`,
            value: t.uuid,
        }))
    const referenced = new Set<string>()
    for (const r of subForm.value.routes) for (const t of (r.teams || [])) referenced.add(t)
    // Same shared builder as the channel picker, so the "keep dangling refs
    // visible and removable" behaviour cannot drift between the two.
    //
    // "(archived)" rather than "(deactivated)": the Teams tab calls the action
    // Archive and reports "Team archived", and the assignment-rule picker
    // already says archived. Three words for one state across three pickers is
    // how an operator ends up wondering whether they mean different things.
    return withGhosts(selectable, teams.value, referenced,
        (t: any, uuid) => t ? `${t.name} (archived)` : `(deleted team) ${String(uuid).slice(0, 8)}`)
})

const channelGroupOptions = computed(() =>
    channelGroups.value.map(g => ({
        label: `${g.name} (${g.channels.length} ch)`,
        value: g.uuid,
    }))
)

const perspectiveOptions = computed(() =>
    (store.getters.perspectivesOfOrg(orgUuid.value) || []).map((p: any) => ({
        label: p.name,
        value: p.uuid,
    }))
)

const UPSERT_SUBSCRIPTION_MUTATION = gql`
    mutation upsertNotificationSubscription($input: NotificationSubscriptionInput!) {
        upsertNotificationSubscription(input: $input) {
            uuid org resourceGroup name status eventTypes
            filter routes dedupWindowMinutes rateLimit revision
        }
    }
`

const SET_SUBSCRIPTION_STATUS_MUTATION = gql`
    mutation setNotificationSubscriptionStatus($uuid: ID!, $status: NotificationSubscriptionStatusEnum!) {
        setNotificationSubscriptionStatus(uuid: $uuid, status: $status) {
            uuid status
        }
    }
`

const DELETE_SUBSCRIPTION_MUTATION = gql`
    mutation deleteNotificationSubscription($uuid: ID!) {
        deleteNotificationSubscription(uuid: $uuid)
    }
`

const INJECT_SYNTHETIC_EVENT_MUTATION = gql`
    mutation injectSyntheticEvent($orgUuid: ID!, $template: SyntheticEventTemplateEnum!) {
        injectSyntheticEvent(orgUuid: $orgUuid, template: $template) {
            uuid status
        }
    }
`

// Fan-out status for the injected event. Polled alongside the deliveries so a
// test that produces NO delivery can be reported the moment fan-out finishes,
// instead of waiting out the full poll budget and calling a settled event
// "still processing".
const OUTBOX_EVENT_STATUS_QUERY = gql`
    query notificationOutboxEventForSubscriptionTest($uuid: ID!) {
        notificationOutboxEvent(uuid: $uuid) {
            uuid status
        }
    }
`

const DELIVERIES_FOR_EVENT_QUERY = gql`
    query notificationDeliveriesForSubscriptionTest($orgUuid: ID!, $eventUuid: ID!) {
        notificationDeliveries(orgUuid: $orgUuid, eventUuid: $eventUuid, limit: 200) {
            items { uuid subscriptionUuid channelUuid status lastError }
        }
    }
`

async function loadChannels (): Promise<void> {
    try {
        const res = await graphqlClient.query({
            query: LIST_CHANNELS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channels.value = res.data?.notificationChannels || []
    } catch (e: any) {
        message.error(`Failed to load channels: ${extractError(e)}`)
    }
}

async function loadChannelGroups (): Promise<void> {
    try {
        // Only names + channels are needed here (route group multiselect), so
        // use the CORE query -- it can't drift on the Pro-ahead group fields.
        const res = await graphqlClient.query({
            query: LIST_GROUPS_CORE_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channelGroups.value = res.data?.notificationChannelGroups || []
    } catch (e: any) {
        message.error(`Failed to load channel groups: ${extractError(e)}`)
    }
}

async function loadTeams (): Promise<void> {
    // Isolated query + soft failure: teams-with-channels is a newer field, and a
    // backend predating it must cost only the Teams route target, not the whole
    // subscription editor.
    try {
        const res = await graphqlClient.query({
            query: gql`
                query getTeamsForRoutes($org: ID!) {
                    getTeams(org: $org) { uuid name status notificationChannels }
                }`,
            variables: { org: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        teams.value = res.data?.getTeams || []
    } catch (e: any) {
        // Only schema drift means "this backend is older". A 401/5xx must not
        // blank the list: saved team targets have no ghost source then, and the
        // route would render bare UUIDs.
        if (isSchemaDriftError(e)) {
            console.warn('Team route targets unavailable on this backend', e?.message)
            teams.value = []
        } else {
            message.error(`Failed to load teams: ${extractError(e)}`)
        }
    }
}

async function loadSubscriptions (): Promise<void> {
    subscriptionsLoading.value = true
    try {
        const { data, degraded } = await loadWithSchemaDriftFallback(graphqlClient, {
            fullQuery: LIST_SUBSCRIPTIONS_QUERY,
            coreQuery: LIST_SUBSCRIPTIONS_CORE_QUERY,
            variables: { orgUuid: orgUuid.value },
            extractPath: (d: any) => d?.notificationSubscriptions,
        })
        subscriptions.value = data || []
        subscriptionsDegraded.value = degraded
    } catch (e: any) {
        subscriptionsDegraded.value = false
        message.error(`Failed to load subscriptions: ${extractError(e)}`)
    } finally {
        subscriptionsLoading.value = false
    }
}

function openCreateSubscription (): void {
    subForm.value = freshSubscriptionForm()
    subModalError.value = ''
    showSubscriptionModal.value = true
}

function openEditSubscription (row: SubscriptionRow): void {
    const f = freshSubscriptionForm()
    f.uuid = row.uuid
    f.expectedRevision = row.revision
    f.name = row.name
    f.status = row.status
    f.eventTypes = [...(row.eventTypes || [])]
    f.dedupWindowMinutes = row.dedupWindowMinutes ?? null
    // Filter / routes / rateLimit ride as JSON-stringified blobs over the
    // wire. Parse them back into the structured form shape AND stash the
    // original blob on _raw / _rawFilter so unmodelled fields survive an
    // Edit -> Save round-trip.
    try {
        const filter = row.filter ? JSON.parse(row.filter) : null
        if (filter) {
            f.filterMode = filter.mode || 'PRESET'
            f.celExpression = filter.celExpression || ''
            f._rawFilter = filter
        }
    } catch { /* fall back to PRESET defaults */ }
    try {
        const routes = row.routes ? JSON.parse(row.routes) : []
        if (Array.isArray(routes) && routes.length > 0) {
            f.routes = routes.map((r: any) => ({
                whenSeverityAtLeast: r.whenSeverityAtLeast || null,
                channels: Array.isArray(r.channels) ? [...r.channels] : [],
                channelGroups: Array.isArray(r.channelGroups) ? [...r.channelGroups] : [],
                teams: Array.isArray(r.teams) ? [...r.teams] : [],
                notifyComponentOwner: r.notifyComponentOwner === true,
                perspectives: Array.isArray(r.perspectives) ? [...r.perspectives] : [],
                _raw: r,
            }))
        }
    } catch { /* fall back to one empty route */ }
    try {
        const rl = row.rateLimit ? JSON.parse(row.rateLimit) : null
        if (rl) {
            f.rateLimitMaxPerWindow = rl.maxPerWindow ?? null
            f.rateLimitWindowMinutes = rl.windowMinutes ?? null
            // Stash the blob for the same reason filter and routes stash theirs:
            // the modelled pair cannot express a PARTIAL limit ({maxPerWindow}
            // with no window, which the API accepts), and with the control gone
            // there is no longer a field where an operator could even see the
            // orphaned half before an unrelated save dropped it.
            f._rawRateLimit = rl
        }
    } catch { /* skip */ }
    // A gate that can never match is dropped HERE, not left to the watcher: the
    // watcher is change-driven, and the common path -- open a subscription that
    // was already release-only with a stored gate -- is not a change. Verified
    // live before the fix: edit + save re-persisted whenSeverityAtLeast=HIGH on
    // a RELEASE_CREATED subscription, with the control hidden.
    clearInapplicableSeverity(f.routes, f.eventTypes)
    subForm.value = f
    subModalError.value = ''
    showSubscriptionModal.value = true
}

async function saveSubscription (): Promise<void> {
    subModalError.value = ''
    const f = subForm.value
    if (!f.name.trim() || f.eventTypes.length === 0 || f.routes.length === 0) {
        subModalError.value = 'Name, at least one event type, and at least one route are required.'
        return
    }
    // Every route needs at least one target; the backend rejects an empty route
    // anyway, but catch it client-side for a cleaner error path. Teams and the
    // owner flag both count: naming a team -- or nobody at all, and letting
    // ownership decide -- INSTEAD of a channel is the point, since the actual
    // channel is resolved at fan-out. The owner flag counts ONLY on Pro: on CE
    // the control is hidden and the key would fail the mutation, so accepting
    // it as the sole target would wave through a route guaranteed to 400.
    const emptyRouteIdx = f.routes.findIndex(r => !routeHasTarget(r, isPro.value))
    if (emptyRouteIdx >= 0) {
        // Don't name a target the operator has no way to pick: the Teams picker
        // is hidden without team channels and the owner checkbox is hidden on
        // CE, so naming either would send them hunting for a control that
        // isn't there.
        const targets = ['channels', 'groups']
        if (teamOptions.value.length) targets.push('teams')
        if (isPro.value) targets.push('the component owner')
        const targetList = `${targets.slice(0, -1).join(', ')} or ${targets[targets.length - 1]}`
        subModalError.value = `The destination has no ${targetList} — pick at least one.`
        return
    }
    // Build the filter input from ONLY the fields NotificationFilterInput
    // accepts (mode / presetConfigJson / celExpression); the as-loaded blob
    // carries an unmodelled `presetConfig` object that must not leak into the
    // input (it 400s the mutation). See buildNotificationFilterInput.
    const filterInput = buildNotificationFilterInput(f._rawFilter, f.filterMode, f.celExpression)
    // Belt and braces on the way out. openEditSubscription already clears an
    // inapplicable gate, but this is the only choke point EVERY save passes
    // through, and the failure it prevents is invisible: a gate that matches
    // nothing, on a control that is no longer rendered.
    clearInapplicableSeverity(f.routes, f.eventTypes)
    const input: any = {
        uuid: f.uuid || undefined,
        expectedRevision: f.expectedRevision,
        org: orgUuid.value,
        name: f.name.trim(),
        status: f.status,
        eventTypes: f.eventTypes,
        filter: filterInput,
        // Preserves the route's unmodelled fields and drops the Pro-only ones a
        // CE backend would reject. See buildNotificationRouteInput.
        routes: f.routes.map(r => buildNotificationRouteInput(r)),
        dedupWindowMinutes: f.dedupWindowMinutes,
    }
    if (f.rateLimitMaxPerWindow && f.rateLimitWindowMinutes) {
        input.rateLimit = {
            maxPerWindow: f.rateLimitMaxPerWindow,
            windowMinutes: f.rateLimitWindowMinutes,
        }
    } else if (f._rawRateLimit) {
        // A limit that the modelled pair cannot represent -- a partial one, or
        // one carrying keys this form does not know about -- rides back
        // verbatim. The upsert REPLACES rateLimit wholesale, so omitting it here
        // is not "leave it alone", it is a silent delete on the next edit of any
        // unrelated field. Same reasoning as _rawFilter and route._raw.
        input.rateLimit = f._rawRateLimit
    }
    savingSubscription.value = true
    try {
        await graphqlClient.mutate({
            mutation: UPSERT_SUBSCRIPTION_MUTATION,
            variables: { input },
        })
        showSubscriptionModal.value = false
        message.success(f.uuid ? 'Subscription updated' : 'Subscription created')
        await loadSubscriptions()
    } catch (e: any) {
        subModalError.value = extractError(e)
    } finally {
        savingSubscription.value = false
    }
}

async function toggleSubscriptionStatus (row: SubscriptionRow): Promise<void> {
    const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    try {
        await graphqlClient.mutate({
            mutation: SET_SUBSCRIPTION_STATUS_MUTATION,
            variables: { uuid: row.uuid, status: next },
        })
        message.success(`Subscription ${next.toLowerCase()}`)
        await loadSubscriptions()
    } catch (e: any) {
        message.error(`Status change failed: ${extractError(e)}`)
    }
}

function confirmDeleteSubscription (row: SubscriptionRow): void {
    dialog.warning({
        title: `Delete subscription "${row.name}"?`,
        content: 'This is permanent. The matching events stop dispatching immediately; History rows for past deliveries are retained.',
        positiveText: 'Delete',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({
                    mutation: DELETE_SUBSCRIPTION_MUTATION,
                    variables: { uuid: row.uuid },
                })
                message.success('Subscription deleted')
                await loadSubscriptions()
            } catch (e: any) {
                message.error(`Delete failed: ${extractError(e)}`)
            }
        },
    })
}

const testingSubscriptions: Ref<Set<string>> = ref(new Set())

// This pane is rendered behind v-if in OrgIntegrations.vue, so switching
// sub-tabs mid-poll unmounts it outright; the org-switch guard alone
// doesn't catch that (props just freeze at their last value). Without
// this, an in-flight test's result dialog can pop up over a tab the user
// already navigated away from.
let isUnmounted = false
onUnmounted(() => { isUnmounted = true })

// Templates whose eventType this subscription actually listens for. Empty
// when eventTypes is limited to kinds with no synthetic template yet
// (RELEASE_CREATED / RELEASE_LIFECYCLE_CHANGED / RELEASE_BOM_DIFF /
// APPROVAL_REQUESTED / APPROVAL_RESOLVED) -- see notificationsCommon.ts.
function matchingTemplates (row: SubscriptionRow): Array<{ label: string, value: string }> {
    return templatesForEventTypes(row.eventTypes || [])
}

// Null when the Test control should be enabled; otherwise the tooltip text
// explaining why it's disabled for this row. Takes the already-computed
// matchingTemplates(row) so callers don't filter syntheticEventTemplates twice.
function testDisabledReason (row: SubscriptionRow, templates: Array<{ label: string, value: string }>): string | null {
    if (row.status === 'DISABLED') {
        return 'Subscription is disabled -- enable it first to test.'
    }
    if (templates.length === 0) {
        return `No synthetic template exists yet for this subscription's event type(s) (${(row.eventTypes || []).join(', ') || 'none selected'}).`
    }
    return null
}

// injectSyntheticEvent is org-wide, not subscription-scoped: it writes one
// outbox event that the fan-out worker matches against every ACTIVE
// subscription in the org, not just this one -- other subscriptions on the
// same event type fire too, delivering to their own real channels (Slack,
// PagerDuty, email...). Confirm before injecting, since this can surprise
// another team's channel, not just the one being tested. (PREVIEW subs are
// NOT matched: fan-out queries status='ACTIVE' only.)
function confirmSubscriptionTest (row: SubscriptionRow, template: string): void {
    // Belt-and-suspenders: the Test control is already disabled while a test
    // is in flight (see testingSubscriptions), but that's a reactive prop on
    // a dropdown trigger -- guard the entry point too so a stray double-fire
    // (e.g. a second confirm dialog opened before the first one's disabled
    // state painted) can't launch two org-wide synthetic events at once.
    if (testingSubscriptions.value.has(row.uuid)) return
    dialog.warning({
        title: `Test "${row.name}"?`,
        content: "This injects a real synthetic event for the whole org, not just this subscription -- any other ACTIVE subscription matching the same event type will also fire and deliver to its own channels (Slack, email, etc.).",
        positiveText: 'Send test event',
        negativeText: 'Cancel',
        onPositiveClick: () => runSubscriptionTest(row, template),
    })
}

async function runSubscriptionTest (row: SubscriptionRow, template: string): Promise<void> {
    if (testingSubscriptions.value.has(row.uuid)) return
    testingSubscriptions.value.add(row.uuid)
    const testOrgUuid = orgUuid.value
    // Immediate, unmissable feedback the moment the confirm dialog closes --
    // the button's own loading spinner is easy to miss on a tiny icon
    // button, and there's a real gap (mutation + first 1.5s poll) before
    // the result dialog can appear.
    const loadingHandle = message.loading(`Sending test event for "${row.name}"...`, { duration: 0 })
    try {
        const res = await graphqlClient.mutate({
            mutation: INJECT_SYNTHETIC_EVENT_MUTATION,
            variables: { orgUuid: testOrgUuid, template },
            fetchPolicy: 'no-cache',
        })
        const eventUuid = res.data?.injectSyntheticEvent?.uuid
        if (!eventUuid) {
            message.error(`No test event was created for "${row.name}".`)
            return
        }
        const channelNameById = buildNameMap(channels.value)
        // Last non-empty delivery set seen, so a timeout can report what it
        // actually observed rather than an empty list.
        let lastSeenItems: Array<any> = []
        const maxAttempts = 40
        for (let attempt = 0; attempt < maxAttempts; attempt++) {
            await new Promise(resolve => setTimeout(resolve, 1500))
            if (isUnmounted || orgUuid.value !== testOrgUuid) return
            // ORDER MATTERS -- event status FIRST. Fan-out writes the delivery
            // rows and flips the event terminal in ONE transaction. Reading
            // deliveries first leaves a one-round-trip window in which that
            // commit lands between the two queries, so we would see an empty
            // set alongside a terminal status and confidently report "produced
            // nothing" for a subscription that had just delivered. Reading the
            // status first makes terminal imply the rows are already visible.
            const evtRes = await graphqlClient.query({
                query: OUTBOX_EVENT_STATUS_QUERY,
                variables: { uuid: eventUuid },
                fetchPolicy: 'no-cache',
            })
            const eventStatus = evtRes.data?.notificationOutboxEvent?.status ?? null
            const pollRes = await graphqlClient.query({
                query: DELIVERIES_FOR_EVENT_QUERY,
                variables: { orgUuid: testOrgUuid, eventUuid },
                fetchPolicy: 'no-cache',
            })
            const items = (pollRes.data?.notificationDeliveries?.items || [])
                .filter((d: any) => d.subscriptionUuid === row.uuid)
            if (items.length > 0) lastSeenItems = items
            const outcome = classifySubscriptionTest(eventStatus, items)
            if (outcome !== 'IN_FLIGHT') {
                if (!isUnmounted && orgUuid.value === testOrgUuid) {
                    reportSubscriptionTestResult(row, items, channelNameById, false, outcome)
                }
                return
            }
        }
        if (!isUnmounted && orgUuid.value === testOrgUuid) {
            // Hand over whatever we last saw. A timeout with rows already
            // present is the "fan-out done, dispatch still queued" case, and
            // showing those rows is the single most useful thing we know.
            reportSubscriptionTestResult(row, lastSeenItems, channelNameById, true)
        }
    } catch (err: any) {
        if (!isUnmounted) message.error(`Test failed: ${extractError(err)}`)
    } finally {
        loadingHandle.destroy()
        testingSubscriptions.value.delete(row.uuid)
    }
}


function reportSubscriptionTestResult (
    row: SubscriptionRow,
    items: Array<{ channelUuid: string | null, status: string, lastError: string | null }>,
    channelNameById: Record<string, string>,
    timedOut = false,
    outcome: SubscriptionTestOutcome | null = null,
): void {
    if (timedOut && items.length > 0) {
        // Fan-out finished and produced rows; the channel worker just has not
        // dispatched them yet (webhook backoff, a slow endpoint). Saying "the
        // event had not finished fanning out" here would be false, and the
        // pending rows are the most useful thing we know.
        dialog.info({
            title: `Test result -- ${row.name}`,
            content: `Still waiting on delivery after 60s. ${items.length} row(s) were created and are`
                + ' queued for dispatch -- check Notification History for the eventual status.',
        })
        return
    }
    if (items.length === 0) {
        dialog.info({
            title: `Test result -- ${row.name}`,
            // Three distinct endings, deliberately not collapsed: fan-out
            // errored, fan-out finished and chose to send nothing, or we gave
            // up while it was still running. The old code said "may still be
            // processing" for all of them.
            content: outcome === 'FANOUT_FAILED'
                ? 'Fan-out FAILED for this test event, so nothing was delivered. This is a backend'
                    + " error, not a problem with the subscription's filter or routes -- check"
                    + ' Notification History and the server logs before changing this subscription.'
                : timedOut
                    ? 'Gave up waiting after 60s -- the event had not finished fanning out.'
                        + ' Check Notification History for the eventual result.'
                    : noDeliveryExplanation(row.routes),
        })
        return
    }
    const lines = items.map(d => {
        const chName = d.channelUuid ? (channelNameById[d.channelUuid] || d.channelUuid) : '(no channel)'
        const detail = d.lastError ? `: ${d.lastError}` : ''
        return `${chName} -> ${d.status}${detail}`
    })
    // Reuse the same status->tag classification as History/Inbox so this
    // dialog can't call something an unqualified "success" that isn't --
    // e.g. BATCHED (queued in an email digest, not actually sent yet) would
    // otherwise fall through a FAILED-only check and render green.
    const tagTypes = items.map(d => deliveryStatusTagType(d.status))
    const dialogType = tagTypes.includes('error') ? 'warning' : (tagTypes.every(t => t === 'success') ? 'success' : 'info')
    // A green owner-routed test proves less than it looks: injection picks a
    // component that HAS an owner on purpose. Say so here rather than let the
    // result imply production routing is covered.
    const caveat = ownerRoutedSuccessCaveat(row.routes)
    dialog[dialogType]({
        title: `Test result -- ${row.name}`,
        content: () => h('div', [
            ...lines.map((l, i) => h('div', { key: i }, l)),
            ...(caveat ? [h('div', { style: 'margin-top: 10px; font-size: 12px; color: var(--n-text-color-3, #888);' }, caveat)] : []),
        ]),
    })
}


const subscriptionColumns = computed(() => [
    {
        title: 'Name', key: 'name',
        render: (row: SubscriptionRow) => {
            if (!row.managedByTeam) return row.name
            // Badged rather than hidden. A row that delivers but does not appear
            // here is the failure this whole subsystem keeps producing; the
            // honest version is visible, labelled, and pointing at the only
            // place it can be changed.
            return h('div', null, [
                h('div', null, row.name),
                h(NTag, { size: 'small', type: 'info', style: 'margin-top: 4px;' },
                    { default: () => `Managed by ${managedTeamName(row)}` }),
            ])
        },
    },
    {
        title: 'Status', key: 'status',
        render: (row: SubscriptionRow) => h(
            NTag,
            {
                // Legacy PREVIEW rows tag as 'warning' alongside DISABLED, not
                // 'info': fan-out matches ACTIVE only, so a PREVIEW
                // subscription delivers nothing. A calm blue badge would imply
                // it is doing something benign-but-active. It isn't.
                type: row.status === 'ACTIVE' ? 'success' : 'warning',
                size: 'small',
            },
            { default: () => row.status },
        ),
    },
    {
        title: 'Event types', key: 'eventTypes',
        render: (row: SubscriptionRow) => `${(row.eventTypes || []).length} type(s)`,
    },
    {
        title: 'Routes', key: 'routes',
        render: (row: SubscriptionRow) => `${routeCount(row.routes)} route(s)`,
    },
    {
        title: 'Actions', key: 'actions',
        render: (row: SubscriptionRow) => {
            const templates = matchingTemplates(row)
            const disabledReason = testDisabledReason(row, templates)
            const isTesting = testingSubscriptions.value.has(row.uuid)
            const testButtonDisabled = !canWrite.value || !!disabledReason || isTesting
            const testButton = h(NDropdown, {
                trigger: 'click',
                // NDropdown options key off `key` (not NSelect's `value`) --
                // onSelect hands back that `key`.
                options: templates.map(t => ({ label: t.label, key: t.value })),
                disabled: testButtonDisabled,
                onSelect: (key: string) => confirmSubscriptionTest(row, key),
            }, {
                default: () => h(NButton, {
                    size: 'tiny', secondary: true,
                    disabled: testButtonDisabled,
                    loading: isTesting,
                    'data-testid': 'test-subscription',
                }, { icon: () => h(NIcon, null, { default: () => h(Send) }) }),
            })
            return h(NSpace, { size: 'small' }, {
                default: () => [
                    // Disabled on a DEGRADED load. The core query omits filter /
                    // routes / dedupWindowMinutes / rateLimit, so the editor
                    // would open showing defaults for all of them, and saving
                    // REPLACES the subscription wholesale -- silently wiping the
                    // CEL expression, every route but one, and the rate limit.
                    // The banner alone is not enough: the destructive action has
                    // to be unavailable, not merely discouraged. Add is fine.
                    // Also disabled when the subscription carries more than one
                    // route: the editor shows one, so the rest would be invisible
                    // rather than lost. These are not only API-made -- the UI
                    // offered "Add route" from #130 until this change -- so the
                    // tooltip has to explain itself. See hasUneditableMultiRoute.
                    h(NTooltip, { trigger: 'hover' }, {
                        // The trigger is a SPAN, not the button. A disabled
                        // <button> receives no mouse events, so a tooltip bound
                        // directly to it never opens -- which would hide this
                        // explanation in exactly the case it exists for.
                        trigger: () => h('span', { style: 'display: inline-flex;' }, [
                            h(NButton, {
                                size: 'tiny', secondary: true,
                                onClick: () => openEditSubscription(row),
                                disabled: !canWrite.value || subscriptionsDegraded.value
                                    || hasUneditableMultiRoute(row.routes)
                                    // The backend refuses an edit to a
                                    // team-managed row. Leaving the control
                                    // enabled would hand the operator an error
                                    // where a pointer belongs.
                                    || !!row.managedByTeam,
                                'data-testid': 'edit-subscription',
                            }, { icon: () => h(NIcon, null, { default: () => h(EditIcon) }) }),
                        ]),
                        default: () => managedRowExplanation(row)
                            || (hasUneditableMultiRoute(row.routes)
                                ? `This subscription has ${routeCount(row.routes)} routes and this editor`
                                + ' shows one. Editing here would leave the others in place but hidden,'
                                + ' so they are better changed through the API -- or collapse the'
                                + ' subscription to a single route.'
                                : 'Edit subscription'),
                    }),
                    h(NTooltip, { trigger: 'hover' }, {
                        // Span, not the button: a disabled <button> receives no
                        // mouse events, so a tooltip bound to it never opens --
                        // in exactly the case it exists to explain.
                        trigger: () => h('span', { style: 'display: inline-flex;' }, [
                            h(NButton, {
                                size: 'tiny', secondary: true,
                                onClick: () => toggleSubscriptionStatus(row),
                                // A status flip is an edit, and the backend
                                // refuses one on a managed row -- the team's
                                // toggle is the switch. Also withheld on a
                                // degraded load, where managedByTeam is absent
                                // from the CORE query and every row would look
                                // operator-owned.
                                disabled: !canWrite.value || subscriptionsDegraded.value
                                    || !!row.managedByTeam,
                                'data-testid': 'toggle-subscription',
                            }, { default: () => row.status === 'ACTIVE' ? 'Disable' : 'Enable' }),
                        ]),
                        default: () => managedRowExplanation(row)
                            || (subscriptionsDegraded.value
                                ? 'Some fields could not be loaded from this server, so changes are'
                                    + ' withheld until the full record is available.'
                                : (row.status === 'ACTIVE' ? 'Stop delivering' : 'Start delivering')),
                    }),
                    h(NTooltip, { trigger: 'hover' }, {
                        trigger: () => testButton,
                        default: () => disabledReason || "Send a synthetic test event through this subscription's matching path (also exercises other subscriptions on the same event type -- asks for confirmation first).",
                    }),
                    h(NTooltip, { trigger: 'hover' }, {
                        trigger: () => h(NButton, {
                            size: 'tiny', secondary: true,
                            onClick: () => viewSubscriptionDeliveries(row),
                            'data-testid': 'view-subscription-deliveries',
                        }, { icon: () => h(NIcon, null, { default: () => h(History) }) }),
                        default: () => 'View delivery history for this subscription',
                    }),
                    h(NTooltip, { trigger: 'hover' }, {
                        trigger: () => h('span', { style: 'display: inline-flex;' }, [
                            h(NButton, {
                                size: 'tiny', secondary: true, type: 'error',
                                onClick: () => confirmDeleteSubscription(row),
                                // Deleting would leave the team's toggle claiming
                                // a subscription that no longer exists; the
                                // backend refuses, so the button does too.
                                disabled: !canWrite.value || subscriptionsDegraded.value
                                    || !!row.managedByTeam,
                                'data-testid': 'delete-subscription',
                            }, { icon: () => h(NIcon, null, { default: () => h(Trash) }) }),
                        ]),
                        default: () => managedRowExplanation(row) || 'Delete subscription',
                    }),
                ],
            })
        },
    },
])

onMounted(async () => {
    await Promise.all([
        loadChannels(),
        loadChannelGroups(),
        loadTeams(),
        loadSubscriptions(),
        store.dispatch('fetchPerspectives', orgUuid.value),
    ])
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
.routes-section {
    border: 1px solid var(--n-border-color, #eee);
    border-radius: 6px;
    padding: 12px 14px;
    background: var(--n-color-embedded, transparent);
}
.routes-title { font-size: 13px; font-weight: 600; }
.routes-hint { font-size: 12px; color: var(--n-text-color-3, #888); margin-bottom: 10px; }
/* Inline honesty/help hints under form fields -- matches routes-hint tone but
   sits directly beneath a field, with a touch of negative top margin to pull
   it up against n-form-item's bottom padding. */
.field-hint {
    font-size: 12px;
    color: var(--n-text-color-3, #888);
    margin: -6px 0 12px;
    line-height: 1.45;
}
.field-hint code {
    font-family: monospace;
    font-size: 11px;
    background: var(--n-color-embedded, rgba(128, 128, 128, 0.12));
    padding: 1px 4px;
    border-radius: 3px;
}
</style>
