<template>
    <div class="org-integrations">
        <!-- Segmented sub-tab pill control. OSS hides Webhooks + PR Validation
             (same scope as the previous "CI Integrations" + Webhooks hide). -->
        <div class="subtab-bar">
            <button class="subtab-pill" :class="{ active: subTab === 'catalog' }" @click="switchSubTab('catalog')">
                <n-icon size="16" class="subtab-icon"><LayoutGrid /></n-icon>
                <span>Catalog</span>
                <span class="subtab-count" v-if="catalogActiveCount > 0">{{ catalogActiveCount }} active</span>
            </button>
            <button v-if="showCiFeatures" class="subtab-pill" :class="{ active: subTab === 'webhooks' }" @click="switchSubTab('webhooks')">
                <n-icon size="16" class="subtab-icon"><PlugConnected /></n-icon>
                <span>Webhooks</span>
            </button>
            <button v-if="showCiFeatures" class="subtab-pill" :class="{ active: subTab === 'pr-validation' }" @click="switchSubTab('pr-validation')">
                <n-icon size="16" class="subtab-icon"><ShieldCheck /></n-icon>
                <span>PR Validation</span>
            </button>
            <button v-if="showCiFeatures" class="subtab-pill" :class="{ active: subTab === 'subscriptions' }" @click="switchSubTab('subscriptions')">
                <n-icon size="16" class="subtab-icon"><Bell /></n-icon>
                <span>Subscriptions</span>
            </button>
            <button v-if="showCiFeatures" class="subtab-pill" :class="{ active: subTab === 'channel-groups' }" @click="switchSubTab('channel-groups')">
                <n-icon size="16" class="subtab-icon"><Users /></n-icon>
                <span>Channel groups</span>
            </button>
        </div>

        <!-- ============================== CATALOG ============================== -->
        <div v-if="subTab === 'catalog'" class="catalog">
            <div class="catalog-header">
                <h2 class="catalog-h2">Connected systems</h2>
                <div class="catalog-sub">Configure integrations that connect ReARM to your CI, source control, messaging and security tooling. You can add multiple instances of CI / source-control kinds.</div>
            </div>

            <template v-for="section in visibleSections" :key="section.id">
                <div class="catalog-section-header">
                    <span class="section-label">{{ section.label }}</span>
                    <span class="section-dot">·</span>
                    <span class="section-desc">{{ section.desc }}</span>
                </div>
                <div class="catalog-grid">
                    <div
                        v-for="card in section.cards"
                        :key="card.id"
                        class="card"
                        :class="{ 'card-configured': isCardConfigured(card), 'card-available': !isCardConfigured(card) }"
                    >
                        <!-- card header: logo + title + vendor + status pill -->
                        <div class="card-head">
                            <div class="logo" :style="{ background: card.logoBg }">
                                <n-icon v-if="card.iconComponent" size="22" color="#fff"><component :is="card.iconComponent" /></n-icon>
                                <span v-else class="logo-mark">{{ card.logoMark }}</span>
                            </div>
                            <div class="card-title-block">
                                <div class="card-name">{{ card.name }}</div>
                                <div class="card-vendor">{{ card.vendor }}</div>
                            </div>
                            <span class="pill" :class="cardStatusClass(card)">
                                <span class="ddot" />
                                {{ cardStatusLabel(card) }}
                            </span>
                        </div>

                        <!-- AVAILABLE state -->
                        <template v-if="!isCardConfigured(card)">
                            <div class="card-desc">{{ card.description }}</div>
                            <div class="card-foot">
                                <span class="muted-12">{{ cardFootHint(card) }}</span>
                                <n-button size="small" :disabled="card.proOnly && !showCiFeatures" @click="openAddForCard(card)">
                                    <template #icon><n-icon><CirclePlus /></n-icon></template>
                                    {{ card.externalConfig ? 'Configure' : 'Add' }}
                                </n-button>
                            </div>
                        </template>

                        <!-- CONFIGURED state — instance rows -->
                        <template v-else>
                            <div class="instance-list">
                                <!-- Notification channels (Email / Webhook / Sentinel) -->
                                <template v-if="isChannelCard(card)">
                                    <div v-for="ch in channelRowsForCard(card)" :key="ch.uuid" class="instance-row">
                                        <div class="instance-status"><span class="ddot" :class="ch.status === 'ENABLED' ? 'ok' : 'off'" /></div>
                                        <div class="instance-body">
                                            <div class="instance-name">{{ ch.name }}</div>
                                            <div v-if="card.id === 'EMAIL'" class="instance-meta">
                                                <span class="instance-scope">{{ emailRecipientsSummary(ch) }}</span>
                                            </div>
                                            <div v-if="card.id === 'EMAIL' || ch.status !== 'ENABLED'" class="caps">
                                                <span v-if="card.id === 'EMAIL'" class="cap-chip">{{ digestChipLabel(ch) }}</span>
                                                <span v-if="ch.status !== 'ENABLED'" class="cap-chip chip-muted">DISABLED</span>
                                            </div>
                                        </div>
                                        <div class="instance-actions">
                                            <n-icon
                                                class="instance-icon"
                                                size="20"
                                                :title="ch.status === 'ENABLED' ? 'Disable channel (pauses sending, keeps configuration)' : 'Enable channel'"
                                                @click="toggleChannelStatus(ch)"
                                            ><PlayerPause v-if="ch.status === 'ENABLED'" /><PlayerPlay v-else /></n-icon>
                                            <n-icon class="instance-icon" size="20" :title="`Edit ${card.name} channel`" @click="openEditChannelForCard(card, ch)"><EditIcon /></n-icon>
                                            <n-icon class="instance-icon danger" size="20" :title="`Delete ${card.name} channel`" @click="onDeleteChannel(card, ch)"><Trash /></n-icon>
                                        </div>
                                    </div>
                                </template>

                                <!-- single-instance kinds (Slack/Teams/DT/BEAR) -->
                                <template v-else-if="!card.multiInstance">
                                    <div class="instance-row">
                                        <div class="instance-status"><span class="ddot ok" /></div>
                                        <div class="instance-body">
                                            <div class="instance-name">{{ singleInstanceLabel(card) }}</div>
                                            <div v-if="card.id === 'BEAR' && bearIntegration?.uri" class="instance-meta">
                                                <span class="instance-scope">{{ bearIntegration.uri }}</span>
                                            </div>
                                        </div>
                                        <div class="instance-actions">
                                            <!-- DT admin actions — preserved from the old design. Role-gated. -->
                                            <template v-if="card.id === 'DEPENDENCYTRACK'">
                                                <n-icon v-if="isOrgAdmin" class="instance-icon" size="20" title="Synchronize D-Track Projects" @click="syncDtrackProjects"><Refresh /></n-icon>
                                                <n-icon v-if="isOrgAdmin" class="instance-icon" size="20" title="Force re-upload all data to D-Track (re-submits every project and re-analyses — heavy)" @click="forceReuploadDtrack"><CloudUpload /></n-icon>
                                            </template>
                                            <n-icon v-if="card.id === 'BEAR'" class="instance-icon" size="20" title="Edit BEAR Integration" @click="openBearEditModal"><EditIcon /></n-icon>
                                            <n-icon v-if="card.id === 'VULNCHECK_KEV'" class="instance-icon" size="20" title="Replace VulnCheck API token" @click="openVulncheckModal"><EditIcon /></n-icon>
                                            <n-icon
                                                class="instance-icon danger"
                                                size="20"
                                                :title="`Delete ${card.name} integration`"
                                                @click="onDeleteSingleInstance(card)"
                                            ><Trash /></n-icon>
                                        </div>
                                    </div>
                                </template>

                                <!-- multi-instance CI kinds (GitHub/GitLab/Jenkins/ADO) -->
                                <template v-else>
                                    <div v-for="inst in ciInstancesForCard(card)" :key="inst.uuid" class="instance-row">
                                        <div class="instance-status"><span class="ddot ok" /></div>
                                        <div class="instance-body">
                                            <div class="instance-name">{{ inst.note || '(unnamed)' }}</div>
                                            <div v-if="inst.capabilities && inst.capabilities.length" class="caps">
                                                <span v-for="c in inst.capabilities" :key="c" class="cap-chip">{{ c }}</span>
                                            </div>
                                        </div>
                                        <div class="instance-actions">
                                            <n-icon v-if="card.id === 'GITHUB'" class="instance-icon" size="20" title="Edit integration" @click="openEditCiIntegrationModal(inst)"><EditIcon /></n-icon>
                                            <n-icon class="instance-icon danger" size="20" title="Delete integration" @click="onDeleteCiInstance(inst)"><Trash /></n-icon>
                                        </div>
                                    </div>
                                </template>
                            </div>

                            <!-- + Add another for multi-instance only -->
                            <button v-if="card.multiInstance" class="add-another" @click="openAddForCard(card)">
                                <n-icon size="14"><CirclePlus /></n-icon>
                                <span>Add another {{ card.name }}</span>
                            </button>
                        </template>

                        <!-- Pro-only hint for messaging cards: security and
                             vulnerability alerts route through the
                             Subscriptions sub-tab (same Integrations surface)
                             rather than these base Slack/Teams credentials.
                             Gated on Pro so the OSS catalog stays the same. -->
                        <div
                            v-if="showCiFeatures && (card.id === 'SLACK' || card.id === 'MSTEAMS')"
                            class="card-pro-hint"
                        >
                            Security and vulnerability alerts use a separate destination —
                            manage in
                            <a class="pro-hint-link" @click="switchSubTab('subscriptions')">Subscriptions</a>
                            →
                        </div>
                        <div
                            v-if="showCiFeatures && isChannelCard(card) && isCardConfigured(card)"
                            class="card-pro-hint"
                        >
                            Events are delivered when a
                            <a class="pro-hint-link" @click="switchSubTab('subscriptions')">subscription</a>
                            routes them to this channel →
                        </div>
                    </div>
                </div>
            </template>
        </div>

        <!-- ============================== WEBHOOKS ============================== -->
        <div v-if="subTab === 'webhooks' && showCiFeatures" class="webhooks-pane">
            <div class="info-banner">
                <n-icon size="20"><ShieldCheck /></n-icon>
                <div>
                    <div class="info-banner-title">Inbound PR webhooks</div>
                    <div class="info-banner-body">
                        Inbound endpoints that receive pull-request events from your SCM — distinct from the outbound
                        Notification Webhook channel in the Catalog. Each is bound to a CI integration with the
                        <span class="cap-chip">WEBHOOK</span> capability.
                        <template v-if="webhookCapableInstances.length">
                            Available:
                            <strong v-for="(n, i) in webhookCapableInstances" :key="n">
                                <span v-if="i > 0">, </span>{{ n }}
                            </strong>.
                        </template>
                        <template v-else>
                            No webhook-capable CI integrations configured. Add one from the Catalog first.
                        </template>
                    </div>
                </div>
            </div>
            <WebhooksOfOrg :orguuid="orguuid" :ci-integrations="ciIntegrations" />
        </div>

        <!-- ============================== PR VALIDATION ============================== -->
        <div v-if="subTab === 'pr-validation' && showCiFeatures" class="pr-validation-pane">
            <div class="info-banner">
                <n-icon size="20"><ShieldCheck /></n-icon>
                <div>
                    <div class="info-banner-title">Global PR validation rules</div>
                    <div class="info-banner-body">
                        Route incoming pull-request events to a CI integration with the <span class="cap-chip">PR_VALIDATE</span> capability.
                        <template v-if="prValidateCapableInstances.length">
                            Available:
                            <strong v-for="(n, i) in prValidateCapableInstances" :key="n">
                                <span v-if="i > 0">, </span>{{ n }}
                            </strong>.
                        </template>
                        <template v-else>
                            No PR-validate-capable CI integrations configured. Add one from the Catalog first.
                        </template>
                    </div>
                </div>
            </div>
            <OrgGlobalPrValidationRules :orgUuid="orguuid" :isWritable="isWritable" />
        </div>

        <!-- ============================== SUBSCRIPTIONS ============================== -->
        <div v-if="subTab === 'subscriptions' && showCiFeatures" class="subscriptions-pane-wrap">
            <div class="info-banner">
                <n-icon size="20"><Bell /></n-icon>
                <div>
                    <div class="info-banner-title">Notification subscriptions</div>
                    <div class="info-banner-body">
                        Rules that route security and operational events to your messaging channels. Channels themselves are configured as Slack / Teams / Webhook / Microsoft Sentinel integrations in the Catalog.
                    </div>
                </div>
            </div>
            <SubscriptionsOfOrg :orguuid="orguuid" :isWritable="isWritable" />
        </div>

        <!-- ============================== CHANNEL GROUPS ============================== -->
        <div v-if="subTab === 'channel-groups' && showCiFeatures" class="channel-groups-pane-wrap">
            <div class="info-banner">
                <n-icon size="20"><Users /></n-icon>
                <div>
                    <div class="info-banner-title">Channel groups</div>
                    <div class="info-banner-body">
                        Named, cross-type collections of channels you can reference from a subscription route instead of repeating the same channel list.
                    </div>
                </div>
            </div>
            <ChannelGroupsOfOrg :orguuid="orguuid" :isWritable="isWritable" />
        </div>

        <!-- ================================= MODALS ================================= -->

        <!-- Slack -->
        <n-modal v-model:show="showSlackModal" preset="dialog" :show-icon="false">
            <n-card style="width: 600px" size="huge" title="Add Slack integration" :bordered="false" role="dialog" aria-modal="true">
                <n-form>
                    <n-form-item label="Secret" description="Slack integration secret">
                        <n-input type="password" v-model:value="createIntegrationObject.secret" required placeholder="Enter Slack integration secret" />
                    </n-form-item>
                    <n-space>
                        <n-button @click="onAddBaseIntegration('SLACK')" type="success">Submit</n-button>
                        <n-button @click="resetCreateIntegrationObject" type="error">Reset</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- MS Teams -->
        <n-modal v-model:show="showMsteamsModal" preset="dialog" :show-icon="false">
            <n-card style="width: 600px" size="huge" title="Add MS Teams integration" :bordered="false" role="dialog" aria-modal="true">
                <n-form>
                    <n-form-item label="Secret" description="MS Teams integration URI">
                        <n-input type="password" v-model:value="createIntegrationObject.secret" required placeholder="Enter MS Teams integration URI" />
                    </n-form-item>
                    <n-space>
                        <n-button @click="onAddBaseIntegration('MSTEAMS')" type="success">Submit</n-button>
                        <n-button @click="resetCreateIntegrationObject" type="error">Reset</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- Email notification channel ADD / EDIT -->
        <n-modal v-model:show="showEmailModal" preset="dialog" :show-icon="false">
            <n-card style="width: 640px" size="huge" :title="emailForm.uuid ? 'Edit Email channel' : 'Add Email channel'" :bordered="false" role="dialog" aria-modal="true">
                <n-form :model="emailForm">
                    <n-space vertical size="large">
                        <n-form-item label="Name" :show-feedback="false">
                            <n-input v-model:value="emailForm.name" required placeholder="e.g. Security team inbox" />
                        </n-form-item>
                        <n-form-item label="Recipients" :show-feedback="false">
                            <n-dynamic-input v-model:value="emailForm.recipients" placeholder="name@example.com" :min="1" />
                        </n-form-item>
                        <n-form-item label="Delivery mode" :show-feedback="false">
                            <n-radio-group v-model:value="emailForm.digestMode">
                                <n-space vertical>
                                    <n-radio value="ROLLING">
                                        Digest (recommended) — batch routine notifications into at most one email per interval
                                    </n-radio>
                                    <n-radio value="IMMEDIATE">
                                        Immediate — one email per event
                                    </n-radio>
                                </n-space>
                            </n-radio-group>
                        </n-form-item>
                        <n-form-item v-if="emailForm.digestMode === 'ROLLING'" label="Digest interval" :show-feedback="false">
                            <n-select v-model:value="emailForm.digestInterval" :options="digestIntervalOptions" />
                        </n-form-item>
                        <n-text depth="3" style="font-size: 12px;">
                            Actionable emails (approval requests and resolutions) always send immediately, regardless of digest settings.
                        </n-text>
                        <n-text depth="3" style="font-size: 12px;">
                            This channel only controls recipients and digest batching. The email provider that actually
                            delivers these messages (SMTP or SendGrid) is configured instance-wide by an administrator
                            in System Settings → Email Sending Configuration.
                        </n-text>

                        <n-alert v-if="emailModalError" type="error" :show-icon="false">
                            {{ emailModalError }}
                            <template v-if="isConflictError(emailModalError)" #action>
                                <n-button size="small" type="primary" @click="reloadEmailChannelFromServer">
                                    Reload from server
                                </n-button>
                            </template>
                        </n-alert>

                        <n-space>
                            <n-button :loading="emailSaveLoading" @click="saveEmailChannel" type="success">Save</n-button>
                            <n-button @click="showEmailModal = false" type="default">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- Webhook notification channel ADD / EDIT -->
        <n-modal v-model:show="showWebhookChModal" preset="dialog" :show-icon="false">
            <n-card style="width: 640px" size="huge" :title="webhookChForm.uuid ? 'Edit Notification Webhook channel' : 'Add Notification Webhook channel'" :bordered="false" role="dialog" aria-modal="true">
                <n-form :model="webhookChForm">
                    <n-space vertical size="large">
                        <n-form-item label="Name" :show-feedback="false">
                            <n-input v-model:value="webhookChForm.name" required placeholder="e.g. PagerDuty events" />
                        </n-form-item>
                        <n-form-item label="Endpoint URL" :show-feedback="false">
                            <n-input
                                v-model:value="webhookChForm.url"
                                :placeholder="webhookChForm.uuid ? '(unchanged — leave blank to keep the current endpoint and auth)' : 'https://example.com/hooks/rearm'"
                            />
                        </n-form-item>
                        <n-form-item label="Authentication" :show-feedback="false">
                            <n-radio-group v-model:value="webhookChForm.authScheme">
                                <n-space vertical>
                                    <n-radio v-for="o in webhookAuthOptions" :key="o.value" :value="o.value">{{ o.label }}</n-radio>
                                </n-space>
                            </n-radio-group>
                        </n-form-item>
                        <n-form-item v-if="webhookChForm.authScheme !== 'NONE'" :label="webhookChForm.authScheme === 'BEARER' ? 'Bearer token' : 'HMAC shared secret'" :show-feedback="false">
                            <n-input type="password" v-model:value="webhookChForm.authToken" :placeholder="webhookChForm.authScheme === 'BEARER' ? 'Enter bearer token' : 'Enter HMAC-SHA256 shared secret'" />
                        </n-form-item>
                        <n-text v-if="webhookChForm.uuid" depth="3" style="font-size: 12px;">
                            The stored endpoint and auth settings are encrypted and not displayed. They are stored together:
                            to change any of them, re-enter the endpoint URL and the auth settings.
                        </n-text>
                        <n-alert
                            v-if="webhookChForm.uuid && webhookChForm.url && webhookChForm.authScheme === 'NONE'"
                            type="warning" :show-icon="false"
                        >
                            Re-entering the URL replaces the stored endpoint and auth together — saving with
                            Authentication set to None removes any stored bearer token or HMAC secret.
                        </n-alert>

                        <n-alert v-if="webhookChModalError" type="error" :show-icon="false">
                            {{ webhookChModalError }}
                            <template v-if="isConflictError(webhookChModalError)" #action>
                                <n-button size="small" type="primary" @click="reloadWebhookChannelFromServer">
                                    Reload from server
                                </n-button>
                            </template>
                        </n-alert>

                        <n-space>
                            <n-button :loading="webhookChSaveLoading" @click="saveWebhookChannel" type="success">Save</n-button>
                            <n-button @click="showWebhookChModal = false" type="default">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- Microsoft Sentinel notification channel ADD / EDIT -->
        <n-modal v-model:show="showSentinelModal" preset="dialog" :show-icon="false">
            <n-card style="width: 640px" size="huge" :title="sentinelForm.uuid ? 'Edit Microsoft Sentinel channel' : 'Add Microsoft Sentinel channel'" :bordered="false" role="dialog" aria-modal="true">
                <n-form :model="sentinelForm">
                    <n-space vertical size="large">
                        <n-form-item label="Name" :show-feedback="false">
                            <n-input v-model:value="sentinelForm.name" required placeholder="e.g. SOC workspace" />
                        </n-form-item>
                        <n-text depth="3" style="font-size: 12px;">
                            Service-principal credentials (client-credentials OAuth) plus Data Collection Rule routing for the
                            Azure Logs Ingestion API.
                            <template v-if="sentinelForm.uuid">
                                Stored values are encrypted and not displayed — leave all six fields blank to keep them,
                                or re-enter all six to replace them.
                            </template>
                        </n-text>
                        <n-form-item label="Tenant ID" :show-feedback="false">
                            <n-input v-model:value="sentinelForm.tenantId" :placeholder="sentinelForm.uuid ? '(unchanged)' : 'Azure AD tenant ID'" />
                        </n-form-item>
                        <n-form-item label="Client ID" :show-feedback="false">
                            <n-input v-model:value="sentinelForm.clientId" :placeholder="sentinelForm.uuid ? '(unchanged)' : 'App registration (service principal) client ID'" />
                        </n-form-item>
                        <n-form-item label="Client secret" :show-feedback="false">
                            <n-input type="password" v-model:value="sentinelForm.clientSecret" :placeholder="sentinelForm.uuid ? '(unchanged)' : 'Service principal client secret'" />
                        </n-form-item>
                        <n-form-item label="DCR endpoint" :show-feedback="false">
                            <n-input v-model:value="sentinelForm.dcrEndpoint" :placeholder="sentinelForm.uuid ? '(unchanged)' : 'https://<dce>.<region>.ingest.monitor.azure.com'" />
                        </n-form-item>
                        <n-form-item label="DCR immutable ID" :show-feedback="false">
                            <n-input v-model:value="sentinelForm.dcrImmutableId" :placeholder="sentinelForm.uuid ? '(unchanged)' : 'dcr-…'" />
                        </n-form-item>
                        <n-form-item label="Stream name" :show-feedback="false">
                            <n-input v-model:value="sentinelForm.streamName" :placeholder="sentinelForm.uuid ? '(unchanged)' : 'Custom-…_CL'" />
                        </n-form-item>

                        <n-alert v-if="sentinelModalError" type="error" :show-icon="false">
                            {{ sentinelModalError }}
                            <template v-if="isConflictError(sentinelModalError)" #action>
                                <n-button size="small" type="primary" @click="reloadSentinelChannelFromServer">
                                    Reload from server
                                </n-button>
                            </template>
                        </n-alert>

                        <n-space>
                            <n-button :loading="sentinelSaveLoading" @click="saveSentinelChannel" type="success">Save</n-button>
                            <n-button @click="showSentinelModal = false" type="default">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- Dependency-Track -->
        <n-modal v-model:show="showDtModal" preset="dialog" :show-icon="false">
            <n-card style="width: 600px" size="huge" title="Add Dependency-Track Integration" :bordered="false" role="dialog" aria-modal="true">
                <n-form>
                    <n-form-item label="Dependency-Track API Server URI">
                        <n-input v-model:value="createIntegrationObject.uri" required placeholder="Enter Dependency-Track API Server URI" />
                    </n-form-item>
                    <n-form-item label="Dependency-Track Frontend URI">
                        <n-input v-model:value="createIntegrationObject.frontendUri" required placeholder="Enter Dependency-Track Frontend URI" />
                    </n-form-item>
                    <n-form-item label="API Key">
                        <n-input type="password" v-model:value="createIntegrationObject.secret" required placeholder="Enter Dependency-Track API Key" />
                    </n-form-item>
                    <n-space>
                        <n-button @click="onAddBaseIntegration('DEPENDENCYTRACK')" type="success">Submit</n-button>
                        <n-button @click="resetCreateIntegrationObject" type="error">Reset</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- BEAR -->
        <n-modal v-model:show="showBearModal" preset="dialog" :show-icon="false">
            <n-card style="width: 600px" size="huge" :title="bearIntegration && bearIntegration.configured ? 'Edit BEAR Integration' : 'Add BEAR Integration'" :bordered="false" role="dialog" aria-modal="true">
                <n-form>
                    <n-form-item v-if="bearIntegration && bearIntegration.configured" label="Update Mode">
                        <n-checkbox v-model:checked="bearForm.updateSkipPatternsOnly">
                            Update skip patterns only (don't modify URI/API Key)
                        </n-checkbox>
                    </n-form-item>
                    <n-form-item v-if="!bearForm.updateSkipPatternsOnly || !bearIntegration || !bearIntegration.configured" label="BEAR URI">
                        <n-input v-model:value="bearForm.uri" required placeholder="Enter BEAR URI" />
                    </n-form-item>
                    <n-form-item v-if="!bearForm.updateSkipPatternsOnly || !bearIntegration || !bearIntegration.configured" label="API Key">
                        <n-input type="password" v-model:value="bearForm.apiKey" required placeholder="Enter BEAR API Key" />
                    </n-form-item>
                    <n-form-item label="Skip Patterns">
                        <n-dynamic-input v-model:value="bearForm.skipPatterns" placeholder="Enter skip pattern" />
                    </n-form-item>
                    <n-space>
                        <n-button @click="onSetBearIntegration" type="success">Submit</n-button>
                        <n-button @click="resetBearForm" type="error">Reset</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- VulnCheck KEV token (per-org, ORG_ADMIN) -->
        <n-modal v-model:show="showVulncheckModal" preset="dialog" :show-icon="false">
            <n-card style="width: 600px" size="huge" :title="isVulnCheckKevConfigured ? 'Replace VulnCheck API token' : 'Configure VulnCheck KEV'" :bordered="false" role="dialog" aria-modal="true">
                <n-form>
                    <div class="muted-12" style="margin-bottom: 12px">
                        Paste a VulnCheck API token to enable the VulnCheck Known Exploited Vulnerabilities catalog as a per-org KEV source. Get a free token at
                        <a href="https://vulncheck.com" target="_blank" rel="noopener noreferrer">vulncheck.com</a>.
                        The token is stored encrypted on this organization's integration row.
                    </div>
                    <n-form-item label="VulnCheck API token">
                        <n-input type="password" v-model:value="vulncheckForm.token" show-password-on="click" placeholder="vulncheck_..." />
                    </n-form-item>
                    <n-space>
                        <n-button @click="onSetVulncheckToken" type="success" :disabled="!vulncheckForm.token">Submit</n-button>
                        <n-button @click="showVulncheckModal = false" type="error">Cancel</n-button>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- CI Integration ADD (GitHub / GitLab / Jenkins / ADO) -->
        <n-modal v-if="showCiFeatures" v-model:show="showCiAddModal" preset="dialog" :show-icon="false" style="width: 90%">
            <n-card style="max-width: 800px; width: 100%" size="huge" :title="addModalTitle" :bordered="false" role="dialog" aria-modal="true">
                <n-form :model="createIntegrationObject">
                    <n-space vertical size="large">
                        <!-- CI Type is pinned by the card the user opened
                             this modal from (set in openAddForCard). The
                             modal title already reflects the choice; a
                             switcher here would let the user accidentally
                             switch between, say, GitHub and Jenkins forms
                             mid-edit, which is what triggered this fix. -->
                        <n-form-item label="Description" path="note">
                            <n-input v-model:value="createIntegrationObject.note" required placeholder="Enter Description" />
                        </n-form-item>
                        <n-form-item
                            v-if="createIntegrationObject.type === 'GITHUB'"
                            label="Capabilities"
                            description="What this GitHub App is wired up to do. PR_VALIDATE: post check-runs / PR comments. WEBHOOK (PR Webhook): receive inbound pull_request events. WORKFLOW_DISPATCH: trigger repository_dispatch."
                        >
                            <n-checkbox-group v-model:value="createIntegrationObject.capabilities">
                                <n-space>
                                    <n-checkbox value="WORKFLOW_DISPATCH" label="Workflow Dispatch" />
                                    <n-checkbox value="PR_VALIDATE" label="PR Validate" />
                                    <n-checkbox value="WEBHOOK" label="PR Webhook (inbound)" />
                                </n-space>
                            </n-checkbox-group>
                        </n-form-item>
                        <n-form-item
                            v-if="createIntegrationObject.type === 'GITHUB'"
                            label="GitHub Private Key"
                            description="Paste the .pem GitHub provides, upload the file directly, or paste a pre-converted DER base64 blob. Backend normalizes all three."
                        >
                            <n-space vertical style="width: 100%;">
                                <n-radio-group v-model:value="secretInputMode" name="githubSecretInputMode">
                                    <n-radio-button label="Upload .pem" value="upload" />
                                    <n-radio-button label="Paste" value="paste" />
                                </n-radio-group>
                                <n-input
                                    v-if="secretInputMode === 'paste'"
                                    type="textarea"
                                    v-model:value="createIntegrationObject.secret"
                                    required
                                    :autosize="{ minRows: 6, maxRows: 18 }"
                                    style="width: 100%; font-family: monospace; font-size: 12px;"
                                    placeholder="Paste the contents of the .pem file (-----BEGIN RSA PRIVATE KEY----- ...) or DER base64."
                                />
                                <n-upload v-else :default-upload="false" :max="1" accept=".pem,.txt,.key,application/x-pem-file" :show-file-list="false" @change="onSecretFileChange">
                                    <n-upload-trigger #="{ handleClick }" abstract>
                                        <n-button @click="handleClick">Choose .pem file</n-button>
                                    </n-upload-trigger>
                                </n-upload>
                                <n-text v-if="secretInputMode === 'upload' && uploadedSecretFileName" depth="3" style="font-size: 12px;">
                                    Loaded: {{ uploadedSecretFileName }} ({{ createIntegrationObject.secret.length }} chars)
                                </n-text>
                            </n-space>
                        </n-form-item>
                        <n-form-item v-if="createIntegrationObject.type === 'GITHUB'" label="GitHub Application ID" description="GitHub Application ID">
                            <n-input type="number" v-model:value="createIntegrationObject.schedule" required placeholder="Enter GitHub Application ID" />
                        </n-form-item>

                        <n-form-item v-if="createIntegrationObject.type === 'GITLAB'" label="GitLab Authentication Token">
                            <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter GitLab Authentication Token" />
                        </n-form-item>

                        <n-form-item v-if="createIntegrationObject.type === 'JENKINS'" label="Jenkins URI">
                            <n-input v-model:value="createIntegrationObject.uri" required placeholder="Jenkins Home URI (i.e. https://jenkins.localhost)" />
                        </n-form-item>
                        <n-form-item v-if="createIntegrationObject.type === 'JENKINS'" label="Jenkins Token">
                            <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter Jenkins Token" />
                        </n-form-item>

                        <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Client ID">
                            <n-input v-model:value="createIntegrationObject.client" required placeholder="Enter Client ID" />
                        </n-form-item>
                        <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Client Secret">
                            <n-input v-model:value="createIntegrationObject.secret" required placeholder="Enter Client Secret" />
                        </n-form-item>
                        <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Tenant ID">
                            <n-input v-model:value="createIntegrationObject.tenant" required placeholder="Enter Tenant ID" />
                        </n-form-item>
                        <n-form-item v-if="createIntegrationObject.type === 'ADO'" label="Azure DevOps Organization Name">
                            <n-input v-model:value="createIntegrationObject.uri" required placeholder="Enter Azure DevOps organization name" />
                        </n-form-item>

                        <n-space>
                            <n-button @click="addCiIntegration" type="success">Save</n-button>
                            <n-button @click="showCiAddModal = false" type="default">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- CI Integration EDIT (GitHub only) -->
        <n-modal v-if="showCiFeatures" v-model:show="showCiEditModal" preset="dialog" :show-icon="false" style="width: 90%">
            <n-card
                style="width: 700px"
                size="huge"
                :title="'Edit CI Integration - ' + (editCiIntegrationObject.note || '')"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <n-form :model="editCiIntegrationObject">
                    <n-space vertical size="large">
                        <n-form-item label="Type">
                            <n-text>{{ editCiIntegrationObject.type }}</n-text>
                        </n-form-item>
                        <n-form-item label="Capabilities" description="Reduce or expand without re-creating the integration.">
                            <n-checkbox-group v-model:value="editCiIntegrationObject.capabilities">
                                <n-space>
                                    <n-checkbox value="WORKFLOW_DISPATCH" label="Workflow Dispatch" />
                                    <n-checkbox value="PR_VALIDATE" label="PR Validate" />
                                    <n-checkbox value="WEBHOOK" label="PR Webhook (inbound)" />
                                </n-space>
                            </n-checkbox-group>
                        </n-form-item>
                        <n-text depth="3" style="font-size: 13px;">
                            To add or modify the inbound webhook (slug, secret) attached to this integration,
                            use the Webhooks sub-tab.
                        </n-text>
                        <n-space>
                            <n-button :loading="editCiIntegrationLoading" @click="saveEditCiIntegration" type="success">Save</n-button>
                            <n-button @click="showCiEditModal = false" type="default">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, Ref, ComputedRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
    NIcon, NButton, NCard, NModal, NForm, NFormItem, NInput, NCheckbox, NCheckboxGroup,
    NRadioGroup, NRadioButton, NRadio, NSelect, NAlert, NSpace, NText, NDynamicInput,
    NUpload, NUploadTrigger
} from 'naive-ui'
import {
    Edit as EditIcon, Trash, Refresh, CirclePlus, CloudUpload,
    BrandGithub, BrandGitlab, BrandSlack, Mail, PlayerPlay, PlayerPause,
    PlugConnected, ShieldCheck, LayoutGrid, Bell, Users
} from '@vicons/tabler'
import gql from 'graphql-tag'
import { FetchPolicy } from '@apollo/client'
import Swal from 'sweetalert2'
import graphqlClient from '../utils/graphql'
import commonFunctions from '../utils/commonFunctions'
import { extractError, isConflictError, webhookAuthOptions } from '../utils/notificationsCommon'
import WebhooksOfOrg from './WebhooksOfOrg.vue'
import OrgGlobalPrValidationRules from './OrgGlobalPrValidationRules.vue'
import SubscriptionsOfOrg from './SubscriptionsOfOrg.vue'
import ChannelGroupsOfOrg from './ChannelGroupsOfOrg.vue'
import { useNotification, NotificationType } from 'naive-ui'

const props = defineProps<{
    orguuid: string
    isWritable: boolean
    isOrgAdmin: boolean
    isGlobalAdmin: boolean
    installationType: string
}>()

const route = useRoute()
const router = useRouter()
const notification = useNotification()
const notify = (type: NotificationType, title: string, content: string) => {
    notification[type]({ content, meta: title, duration: 3500, keepAliveOnHover: true })
}

const showCiFeatures = computed(() => props.installationType !== 'OSS')
const orguuid = computed(() => props.orguuid)
const isOrgAdmin = computed(() => props.isOrgAdmin)
const isWritable = computed(() => props.isWritable)

// ---- Sub-tab state, URL-synced ---------------------------------------------
type SubTab = 'catalog' | 'webhooks' | 'pr-validation' | 'subscriptions' | 'channel-groups'
const subTab = ref<SubTab>((route.query.integrationsTab as SubTab) || 'catalog')

async function switchSubTab(t: SubTab) {
    if (t !== 'catalog' && !showCiFeatures.value) return
    subTab.value = t
    await router.push({ query: { ...route.query, integrationsTab: t } })
}

// ---- Data refs lifted from OrgSettings -------------------------------------
const configuredIntegrations: Ref<string[]> = ref([])
const ciIntegrations: Ref<any[]> = ref([])
const bearIntegration: Ref<any> = ref(null)

const createIntegrationObject: Ref<any> = ref({
    org: orguuid.value,
    uri: '', frontendUri: '', identifier: 'base',
    secret: '', type: '', note: '',
    tenant: '', client: '', schedule: '',
    capabilities: []
})

const secretInputMode = ref<'paste' | 'upload'>('upload')
const uploadedSecretFileName = ref('')

const bearForm = ref({
    uri: '',
    apiKey: '',
    skipPatterns: [] as string[],
    updateSkipPatternsOnly: true
})

const showVulncheckModal = ref(false)
const vulncheckForm = ref({ token: '' })

const showSlackModal = ref(false)
const showMsteamsModal = ref(false)
const showDtModal = ref(false)
const showBearModal = ref(false)
const showCiAddModal = ref(false)
const showCiEditModal = ref(false)

const editCiIntegrationObject: Ref<any> = ref({
    uuid: '', type: '', note: '', capabilities: [] as string[]
})
const editCiIntegrationLoading = ref(false)

// ---- Notification channels (EMAIL / WEBHOOK / SENTINEL cards) ---------------
// Unlike the base Slack/Teams integrations above, these destinations are
// notification channels (the same entities the Subscriptions and Channel
// groups sub-tabs reference), so they go through the channel CRUD mutations
// rather than createIntegration. The read surface only exposes config for
// EMAIL (recipients + digest policy); webhook/sentinel credentials stay
// encrypted server-side, so their instance rows show name + status only.
interface ChannelCatalogRow {
    uuid: string
    name: string
    type: string
    status: string
    revision: number
    digestMode: string | null
    digestInterval: string | null
    emailRecipients: string[] | null
}
const notificationChannelRows: Ref<ChannelCatalogRow[]> = ref([])
const emailChannels = computed(() => notificationChannelRows.value.filter(c => c.type === 'EMAIL'))
const webhookChannels = computed(() => notificationChannelRows.value.filter(c => c.type === 'WEBHOOK'))
const sentinelChannels = computed(() => notificationChannelRows.value.filter(c => c.type === 'SENTINEL'))

const showEmailModal = ref(false)
const emailSaveLoading = ref(false)
const emailModalError = ref('')
const emailForm = ref({
    uuid: '',
    expectedRevision: null as number | null,
    name: '',
    recipients: [] as string[],
    digestMode: 'ROLLING',
    digestInterval: 'PT24H'
})

// Backend accepts any ISO-8601 duration in PT5M..P7D; the UI offers presets.
const digestIntervalOptions = [
    { label: 'Every 5 minutes', value: 'PT5M' },
    { label: 'Every 15 minutes', value: 'PT15M' },
    { label: 'Hourly', value: 'PT1H' },
    { label: 'Every 6 hours', value: 'PT6H' },
    { label: 'Every 12 hours', value: 'PT12H' },
    { label: 'Daily', value: 'PT24H' },
    { label: 'Every 3 days', value: 'P3D' },
    { label: 'Weekly', value: 'P7D' }
]

const DIGEST_INTERVAL_SHORT: Record<string, string> = {
    PT5M: '5m', PT15M: '15m', PT1H: '1h', PT6H: '6h', PT12H: '12h', PT24H: '24h', P3D: '3d', P7D: '7d'
}

function digestChipLabel(ch: ChannelCatalogRow): string {
    if (ch.digestMode === 'IMMEDIATE') return 'IMMEDIATE'
    const iv = ch.digestInterval || 'PT24H'
    return `DIGEST · ${DIGEST_INTERVAL_SHORT[iv] || iv}`
}

function emailRecipientsSummary(ch: ChannelCatalogRow): string {
    const r = ch.emailRecipients || []
    if (!r.length) return 'No recipients'
    if (r.length <= 2) return r.join(', ')
    return `${r[0]}, ${r[1]} +${r.length - 2} more`
}

// ---- Card config -----------------------------------------------------------
// We keep the brand color + icon mapping co-located so it's easy to add
// kinds later. iconComponent wins over logoMark — falls back to a monogram
// when @vicons doesn't have a brand icon for the kind.
type Category = 'messaging' | 'ci' | 'security'
interface CardConfig {
    id: 'SLACK' | 'MSTEAMS' | 'EMAIL' | 'SENDGRID' | 'WEBHOOK' | 'SENTINEL' | 'GITHUB' | 'GITLAB' | 'JENKINS' | 'ADO' | 'DEPENDENCYTRACK' | 'BEAR' | 'CISA_KEV' | 'VULNCHECK_KEV'
    name: string
    vendor: string
    category: Category
    description: string
    logoBg: string
    logoMark?: string
    iconComponent?: any
    multiInstance: boolean
    // Pro-only kinds still show in the OSS catalog, but with a "Pro"
    // pill and a disabled Add button instead of being hidden.
    proOnly?: boolean
    // Instance-wide config that lives outside this per-org surface (e.g.
    // SendGrid, configured in System Settings). The card's action routes
    // to that page instead of opening a per-org modal, and its footer
    // hint says where it is configured rather than "Not configured".
    externalConfig?: boolean
}

const CARDS: CardConfig[] = [
    // Messaging
    { id: 'SLACK', name: 'Slack', vendor: 'Slack Technologies', category: 'messaging', description: 'Push release notifications to a Slack channel.', logoBg: '#4A154B', iconComponent: BrandSlack, multiInstance: false },
    { id: 'MSTEAMS', name: 'Microsoft Teams', vendor: 'Microsoft', category: 'messaging', description: 'Push release notifications to a Microsoft Teams channel.', logoBg: '#4B53BC', logoMark: 'T', multiInstance: false },
    { id: 'EMAIL', name: 'Email', vendor: 'Reliza', category: 'messaging', description: 'Send security and operational notifications to a list of email recipients, batched into periodic digest emails.', logoBg: '#2D8F4E', iconComponent: Mail, multiInstance: true, proOnly: true },
    // SendGrid is not a per-org notification channel — it is the
    // instance-wide email delivery provider (EmailSendType.SENDGRID),
    // configured in System Settings → Email Sending Configuration. The
    // card surfaces it in the catalog for discoverability; its Configure
    // action routes to that system page instead of opening a per-org
    // channel modal. The Email card above is the per-org recipient/digest
    // channel that rides on top of whichever provider this configures.
    { id: 'SENDGRID', name: 'SendGrid', vendor: 'Twilio SendGrid', category: 'messaging', description: 'Instance-wide email delivery provider used to send Email channel notifications and account emails. Configured in System Settings.', logoBg: '#1A82E2', logoMark: 'SG', multiInstance: false, externalConfig: true },
    { id: 'WEBHOOK', name: 'Notification Webhook', vendor: 'Reliza', category: 'messaging', description: 'POST notification events to any HTTPS endpoint — PagerDuty, Opsgenie, Splunk, or in-house receivers — with optional bearer-token or HMAC-SHA256 signing.', logoBg: '#37474F', iconComponent: PlugConnected, multiInstance: true, proOnly: true },
    { id: 'SENTINEL', name: 'Microsoft Sentinel', vendor: 'Microsoft', category: 'messaging', description: 'Stream notification events to Microsoft Sentinel (Azure Log Analytics) via the Logs Ingestion API.', logoBg: '#0078D4', iconComponent: ShieldCheck, multiInstance: true, proOnly: true },
    // CI/CD & Source Control
    { id: 'GITHUB', name: 'GitHub', vendor: 'GitHub', category: 'ci', description: 'GitHub App for PR validation, inbound webhooks, and repository_dispatch.', logoBg: '#1F2328', iconComponent: BrandGithub, multiInstance: true },
    { id: 'GITLAB', name: 'GitLab', vendor: 'GitLab', category: 'ci', description: 'Trigger GitLab pipelines.', logoBg: '#FC6D26', iconComponent: BrandGitlab, multiInstance: true },
    { id: 'JENKINS', name: 'Jenkins', vendor: 'Jenkins', category: 'ci', description: 'Trigger Jenkins jobs.', logoBg: '#D33833', logoMark: 'J', multiInstance: true },
    { id: 'ADO', name: 'Azure DevOps', vendor: 'Microsoft', category: 'ci', description: 'Trigger Azure DevOps pipelines.', logoBg: '#0078D4', logoMark: 'AZ', multiInstance: true },
    // Security & Compliance
    { id: 'DEPENDENCYTRACK', name: 'Dependency-Track', vendor: 'OWASP', category: 'security', description: 'Push SBOMs and pull vulnerability + policy findings.', logoBg: '#1B4E72', logoMark: 'DT', multiInstance: false },
    { id: 'BEAR', name: 'BEAR', vendor: 'Reliza', category: 'security', description: 'BOM enrichment service.', logoBg: '#22332B', logoMark: 'BR', multiInstance: false },
    { id: 'CISA_KEV', name: 'CISA KEV', vendor: 'CISA', category: 'security', description: 'CISA Known Exploited Vulnerabilities catalog. Public feed, no credential needed. Enabled by default; toggle off to stop syncing.', logoBg: '#0B5394', logoMark: 'CK', multiInstance: false },
    { id: 'VULNCHECK_KEV', name: 'VulnCheck KEV', vendor: 'VulnCheck', category: 'security', description: 'Add VulnCheck\'s Known Exploited Vulnerabilities catalog (a CISA-plus KEV source) as a per-org signal. Requires a free VulnCheck API token (per org).', logoBg: '#5B2A86', logoMark: 'VC', multiInstance: false }
]

const SECTIONS = [
    { id: 'messaging', label: 'Messaging & Notifications', desc: 'Push release events to your team chat' },
    { id: 'ci', label: 'CI/CD & Source Control', desc: 'PR validation, status checks, and pipeline integration' },
    { id: 'security', label: 'Security & Compliance', desc: 'SBOMs, vulnerability findings, and risk signals' }
]

const visibleSections = computed(() => {
    return SECTIONS
        .filter(s => s.id !== 'ci' || showCiFeatures.value)
        .map(s => ({ ...s, cards: CARDS.filter(c => c.category === s.id) }))
        .filter(s => s.cards.length > 0)
})

// ---- Card status helpers ---------------------------------------------------
function isChannelCard(card: CardConfig): boolean {
    return card.id === 'EMAIL' || card.id === 'WEBHOOK' || card.id === 'SENTINEL'
}

function channelRowsForCard(card: CardConfig): ChannelCatalogRow[] {
    return notificationChannelRows.value.filter(c => c.type === card.id)
}

function isCardConfigured(card: CardConfig): boolean {
    // KEV cards: a configuredBaseIntegrations row in enabled state means
    // "active". CISA is default-on (migration backfills every org), so it
    // is normally true. VulnCheck flips true once setVulnCheckKevTokenForOrg
    // saves a token + creates / enables the per-org integration row.
    if (card.id === 'BEAR') return !!(bearIntegration.value && bearIntegration.value.configured)
    if (isChannelCard(card)) return channelRowsForCard(card).length > 0
    if (card.multiInstance) return ciInstancesForCard(card).length > 0
    return configuredIntegrations.value.includes(card.id)
}

// Convenience derived flag — used by the VulnCheck modal title (replace vs configure).
const isVulnCheckKevConfigured = computed(() => configuredIntegrations.value.includes('VULNCHECK_KEV'))

function ciInstancesForCard(card: CardConfig): any[] {
    return ciIntegrations.value.filter((i: any) => i.type === card.id)
}

function cardStatusLabel(card: CardConfig): string {
    if (card.proOnly && !showCiFeatures.value) return 'Pro'
    if (!isCardConfigured(card)) return 'Available'
    if (isChannelCard(card)) {
        const n = channelRowsForCard(card).length
        return n > 1 ? `${n} active` : 'Active'
    }
    if (card.multiInstance) {
        const n = ciInstancesForCard(card).length
        return n > 1 ? `${n} active` : 'Active'
    }
    return 'Active'
}

function cardStatusClass(card: CardConfig): string {
    return isCardConfigured(card) ? 'pill-success' : 'pill-neutral'
}

// Footer hint for the AVAILABLE state. externalConfig kinds (SendGrid) are
// configured on another page, so "Not configured" would be misleading —
// point to where the setting actually lives instead.
function cardFootHint(card: CardConfig): string {
    if (card.externalConfig) return 'Set in System Settings'
    if (card.proOnly && !showCiFeatures.value) return 'Available in ReARM Pro'
    return 'Not configured'
}

function singleInstanceLabel(card: CardConfig): string {
    if (card.id === 'BEAR') return 'Configured'
    return 'Configured'
}

const catalogActiveCount = computed(() => {
    let n = 0
    for (const c of CARDS) {
        if (isChannelCard(c)) n += channelRowsForCard(c).length
        else if (c.multiInstance) n += ciInstancesForCard(c).length
        else if (isCardConfigured(c)) n += 1
    }
    return n
})

// ---- Capability-aware info banner data -------------------------------------
const webhookCapableInstances = computed(() =>
    ciIntegrations.value
        .filter((i: any) => (i.capabilities || []).includes('WEBHOOK'))
        .map((i: any) => `${kindLabel(i.type)} · ${i.note || '(unnamed)'}`)
)

const prValidateCapableInstances = computed(() =>
    ciIntegrations.value
        .filter((i: any) => (i.capabilities || []).includes('PR_VALIDATE'))
        .map((i: any) => `${kindLabel(i.type)} · ${i.note || '(unnamed)'}`)
)

function kindLabel(t: string): string {
    const map: Record<string, string> = {
        GITHUB: 'GitHub', GITLAB: 'GitLab', JENKINS: 'Jenkins', ADO: 'Azure DevOps'
    }
    return map[t] || t
}

// ---- Modal openers ---------------------------------------------------------
function openAddForCard(card: CardConfig) {
    if (card.proOnly && !showCiFeatures.value) return
    if (card.id === 'SLACK') { showSlackModal.value = true; return }
    if (card.id === 'EMAIL') { openAddEmailChannel(); return }
    // SendGrid is instance-wide config, not a per-org channel — send the
    // user to System Settings → Email Sending Configuration (global-admin
    // gated on that page) rather than opening a local modal.
    if (card.id === 'SENDGRID') { router.push({ name: 'systemSettings' }); return }
    if (card.id === 'WEBHOOK') { openAddWebhookChannel(); return }
    if (card.id === 'SENTINEL') { openAddSentinelChannel(); return }
    if (card.id === 'MSTEAMS') { showMsteamsModal.value = true; return }
    if (card.id === 'DEPENDENCYTRACK') { showDtModal.value = true; return }
    if (card.id === 'BEAR') { openBearAddModal(); return }
    if (card.id === 'CISA_KEV') { confirmToggleCisaKev(); return }
    if (card.id === 'VULNCHECK_KEV') { openVulncheckModal(); return }
    // CI types
    resetCreateIntegrationObject()
    createIntegrationObject.value.type = card.id
    showCiAddModal.value = true
}

function openBearAddModal() {
    bearForm.value = { uri: '', apiKey: '', skipPatterns: [], updateSkipPatternsOnly: false }
    showBearModal.value = true
}

function openBearEditModal() {
    bearForm.value = {
        uri: bearIntegration.value?.uri || '',
        apiKey: '',
        skipPatterns: bearIntegration.value?.skipPatterns || [],
        updateSkipPatternsOnly: true
    }
    showBearModal.value = true
}

function openEditCiIntegrationModal(row: any) {
    editCiIntegrationObject.value = {
        uuid: row.uuid, type: row.type, note: row.note,
        capabilities: [...(row.capabilities || [])]
    }
    showCiEditModal.value = true
}

const addModalTitle = computed(() => {
    const t = createIntegrationObject.value.type
    if (!t) return 'Add CI Integration'
    return `Add ${kindLabel(t)} Integration`
})

// ---- Mutations -------------------------------------------------------------
function resetCreateIntegrationObject() {
    createIntegrationObject.value = {
        org: orguuid.value,
        uri: '', frontendUri: '', identifier: 'base',
        secret: '', type: '', note: '',
        tenant: '', client: '', schedule: '',
        capabilities: []
    }
    uploadedSecretFileName.value = ''
}

function resetBearForm() {
    bearForm.value = {
        uri: bearIntegration.value?.uri || '',
        apiKey: '',
        skipPatterns: bearIntegration.value?.skipPatterns || [],
        updateSkipPatternsOnly: true
    }
}

async function onSecretFileChange(options: any) {
    const fileInfo = options.file
    if (fileInfo && fileInfo.file) {
        createIntegrationObject.value.secret = await fileInfo.file.text()
        uploadedSecretFileName.value = fileInfo.file.name || fileInfo.name || ''
    }
}

async function onAddBaseIntegration(type: string) {
    createIntegrationObject.value.type = type
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation createIntegration($integration: IntegrationInput!) {
                    createIntegration(integration: $integration) { uuid }
                }`,
            variables: { integration: createIntegrationObject.value }
        })
        if (resp.data?.createIntegration?.uuid) await loadConfiguredIntegrations(false)
        notify('success', 'Integration Added', `${kindLabel(type) || type} integration added.`)
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        resetCreateIntegrationObject()
        showSlackModal.value = false
        showMsteamsModal.value = false
        showDtModal.value = false
    }
}

async function addCiIntegration() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation createTriggerIntegration($integration: IntegrationInput!) {
                    createTriggerIntegration(integration: $integration) { uuid }
                }`,
            variables: { integration: createIntegrationObject.value }
        })
        if (resp.data?.createTriggerIntegration?.uuid) await loadCiIntegrations(false)
        notify('success', 'Integration Added', `${kindLabel(createIntegrationObject.value.type)} integration added.`)
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        resetCreateIntegrationObject()
        showCiAddModal.value = false
    }
}

async function saveEditCiIntegration() {
    editCiIntegrationLoading.value = true
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation updateIntegrationCapabilities($uuid: ID!, $capabilities: [IntegrationCapability!]!) {
                    updateIntegrationCapabilities(uuid: $uuid, capabilities: $capabilities) { uuid capabilities }
                }`,
            variables: {
                uuid: editCiIntegrationObject.value.uuid,
                capabilities: editCiIntegrationObject.value.capabilities
            },
            fetchPolicy: 'no-cache'
        })
        showCiEditModal.value = false
        await loadCiIntegrations(false)
        notify('success', 'Integration Updated', 'Capabilities saved.')
    } catch (e: any) {
        notify('error', 'Failed to save integration', e?.message || String(e))
    } finally {
        editCiIntegrationLoading.value = false
    }
}

// CI integration delete. Server-side hard-block: deleteCiIntegration
// refuses when webhooks, PR validation rules, per-VCS overrides, or
// approval-policy actions still reference the integration. The error
// message carries a per-category breakdown — surface it verbatim so
// the user knows exactly where to clean up.
async function onDeleteCiInstance(inst: any) {
    const label = `${kindLabel(inst.type)}${inst.note ? ` (${inst.note})` : ''}`
    const confirm = await Swal.fire({
        title: `Delete ${label}?`,
        text: 'This integration will only be removed if nothing references it (webhooks, PR validation rules, per-VCS overrides, approval-policy actions).',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, delete',
        cancelButtonText: 'Cancel'
    })
    if (!confirm.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation deleteCiIntegration($uuid: ID!) {
                    deleteCiIntegration(uuid: $uuid)
                }`,
            variables: { uuid: inst.uuid },
            fetchPolicy: 'no-cache'
        })
        await loadCiIntegrations(false)
        notify('success', 'Deleted', `${label} removed.`)
    } catch (err: any) {
        const msg = commonFunctions.parseGraphQLError(err.message) || err?.message || String(err)
        await Swal.fire({
            title: 'Cannot delete',
            text: msg,
            icon: 'warning',
            confirmButtonText: 'OK'
        })
    }
}

async function onDeleteBaseInstance(card: CardConfig) {
    const result = await Swal.fire({
        title: `Delete ${card.name} integration?`,
        text: 'This will remove the integration configuration.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, delete'
    })
    if (!result.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation deleteBaseIntegration($org: ID!, $type: IntegrationType!) {
                    deleteBaseIntegration(org: $org, type: $type)
                }`,
            variables: { org: orguuid.value, type: card.id }
        })
        await loadConfiguredIntegrations(false)
        notify('success', 'Deleted', `${card.name} integration removed.`)
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

async function onSetBearIntegration() {
    try {
        let resp
        if (bearForm.value.updateSkipPatternsOnly && bearIntegration.value?.configured) {
            resp = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateBearSkipPatterns($org: ID!, $skipPatterns: [String]) {
                        updateBearSkipPatterns(org: $org, skipPatterns: $skipPatterns) {
                            uri configured skipPatterns
                        }
                    }`,
                variables: { org: orguuid.value, skipPatterns: bearForm.value.skipPatterns }
            })
            if (resp.data?.updateBearSkipPatterns) bearIntegration.value = resp.data.updateBearSkipPatterns
            notify('success', 'Success', 'BEAR skip patterns updated.')
        } else {
            resp = await graphqlClient.mutate({
                mutation: gql`
                    mutation setBearIntegration($org: ID!, $uri: String!, $apiKey: String!, $skipPatterns: [String]) {
                        setBearIntegration(org: $org, uri: $uri, apiKey: $apiKey, skipPatterns: $skipPatterns) {
                            uri configured skipPatterns
                        }
                    }`,
                variables: {
                    org: orguuid.value,
                    uri: bearForm.value.uri,
                    apiKey: bearForm.value.apiKey,
                    skipPatterns: bearForm.value.skipPatterns
                }
            })
            if (resp.data?.setBearIntegration) bearIntegration.value = resp.data.setBearIntegration
            notify('success', 'Success', 'BEAR integration configured.')
        }
        showBearModal.value = false
        bearForm.value.apiKey = ''
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

// BEAR isn't part of the IntegrationType enum (it has its own
// setBearIntegration / deleteBearIntegration mutations), so the BEAR row's
// trash icon routes here instead of onDeleteBaseInstance.
async function deleteBearIntegration() {
    const confirm = await Swal.fire({
        title: 'Delete BEAR Integration?',
        text: 'This will remove the BEAR integration configuration.',
        icon: 'warning', showCancelButton: true, confirmButtonText: 'Yes, delete it'
    })
    if (!confirm.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation deleteBearIntegration($org: ID!) { deleteBearIntegration(org: $org) }`,
            variables: { org: orguuid.value }
        })
        bearIntegration.value = null
        bearForm.value = { uri: '', apiKey: '', skipPatterns: [], updateSkipPatternsOnly: true }
        notify('success', 'Deleted', 'BEAR integration removed.')
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

// ---- KEV catalog sources (per-org, ORG_ADMIN) -------------------------------
// VulnCheck KEV is a token-bearing integration: setVulnCheckKevTokenForOrg
// creates / updates the per-org row; passing an empty string clears the token
// and disables the row (backend contract). CISA KEV needs no credential, so
// it uses the simpler enable/disableKevSource mutations.
function openVulncheckModal() {
    vulncheckForm.value = { token: '' }
    showVulncheckModal.value = true
}

async function onSetVulncheckToken() {
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation setVulnCheckKevTokenForOrg($org: ID!, $token: String) {
                    setVulnCheckKevTokenForOrg(orgUuid: $org, token: $token)
                }`,
            variables: { org: orguuid.value, token: vulncheckForm.value.token }
        })
        showVulncheckModal.value = false
        vulncheckForm.value.token = ''
        await loadConfiguredIntegrations(false)
        notify('success', 'Success', 'VulnCheck KEV token saved. First sync will run shortly.')
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

async function deleteVulncheckToken() {
    const confirm = await Swal.fire({
        title: 'Remove VulnCheck KEV token?',
        text: 'This disables the VulnCheck KEV source for this organization.',
        icon: 'warning', showCancelButton: true, confirmButtonText: 'Yes, remove it'
    })
    if (!confirm.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation setVulnCheckKevTokenForOrg($org: ID!, $token: String) {
                    setVulnCheckKevTokenForOrg(orgUuid: $org, token: $token)
                }`,
            variables: { org: orguuid.value, token: '' }
        })
        await loadConfiguredIntegrations(false)
        notify('success', 'Deleted', 'VulnCheck KEV token removed.')
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

async function confirmToggleCisaKev() {
    const enabled = configuredIntegrations.value.includes('CISA_KEV')
    const confirm = await Swal.fire({
        title: enabled ? 'Disable CISA KEV for this organization?' : 'Enable CISA KEV for this organization?',
        text: enabled
            ? 'KEV assertions sourced from CISA will stop syncing. Existing data is removed.'
            : 'CISA KEV will start syncing for this organization. The first sync runs in the background.',
        icon: 'question', showCancelButton: true,
        confirmButtonText: enabled ? 'Disable' : 'Enable'
    })
    if (!confirm.isConfirmed) return
    await toggleKevSource('CISA_KEV', !enabled)
}

async function toggleKevSource(source: 'CISA_KEV' | 'VULNCHECK_KEV', enable: boolean) {
    try {
        await graphqlClient.mutate({
            mutation: enable
                ? gql`
                    mutation enableKevSource($org: ID!, $source: IntegrationType!) {
                        enableKevSource(orgUuid: $org, source: $source)
                    }`
                : gql`
                    mutation disableKevSource($org: ID!, $source: IntegrationType!) {
                        disableKevSource(orgUuid: $org, source: $source)
                    }`,
            variables: { org: orguuid.value, source }
        })
        await loadConfiguredIntegrations(false)
        notify('success', enable ? 'Enabled' : 'Disabled', `${source === 'CISA_KEV' ? 'CISA KEV' : 'VulnCheck KEV'} ${enable ? 'enabled' : 'disabled'} for this organization.`)
    } catch (err: any) {
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    }
}

// Trash-icon dispatcher for the single-instance row. Routes BEAR + the two
// KEV cards through their dedicated mutations; everything else falls back to
// the generic deleteBaseIntegration path.
function onDeleteSingleInstance(card: CardConfig) {
    if (card.id === 'BEAR') return deleteBearIntegration()
    if (card.id === 'VULNCHECK_KEV') return deleteVulncheckToken()
    if (card.id === 'CISA_KEV') return confirmToggleCisaKev()
    return onDeleteBaseInstance(card)
}

// ---- Email channel CRUD -----------------------------------------------------
function openAddEmailChannel() {
    // Seed one empty recipient row — n-dynamic-input renders a bare
    // "+ Create" button for an empty array, hiding the input field.
    emailForm.value = {
        uuid: '', expectedRevision: null, name: '', recipients: [''],
        digestMode: 'ROLLING', digestInterval: 'PT24H'
    }
    emailModalError.value = ''
    showEmailModal.value = true
}

function openEditEmailChannel(ch: ChannelCatalogRow) {
    emailForm.value = {
        uuid: ch.uuid,
        expectedRevision: ch.revision,
        name: ch.name,
        recipients: ch.emailRecipients?.length ? [...ch.emailRecipients] : [''],
        digestMode: ch.digestMode || 'ROLLING',
        digestInterval: ch.digestInterval || 'PT24H'
    }
    emailModalError.value = ''
    showEmailModal.value = true
}

async function saveEmailChannel() {
    emailModalError.value = ''
    const name = (emailForm.value.name || '').trim()
    if (!name) {
        emailModalError.value = 'Channel name is required.'
        return
    }
    const recipients = emailForm.value.recipients
        .map(r => (r || '').trim())
        .filter(r => r.length > 0)
    if (!recipients.length) {
        emailModalError.value = 'At least one recipient address is required.'
        return
    }
    const isEdit = !!emailForm.value.uuid
    emailSaveLoading.value = true
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation upsertNotificationChannel($input: NotificationChannelInput!) {
                    upsertNotificationChannel(input: $input) { uuid }
                }`,
            variables: {
                input: {
                    uuid: isEdit ? emailForm.value.uuid : null,
                    expectedRevision: isEdit ? emailForm.value.expectedRevision : null,
                    org: orguuid.value,
                    name,
                    type: 'EMAIL',
                    emailConfig: {
                        recipients,
                        digestMode: emailForm.value.digestMode,
                        // Null preserves the stored interval when switching to
                        // IMMEDIATE, so it re-surfaces on a later flip back to
                        // digest mode.
                        digestInterval: emailForm.value.digestMode === 'ROLLING'
                            ? emailForm.value.digestInterval : null
                    }
                }
            },
            fetchPolicy: 'no-cache'
        })
        showEmailModal.value = false
        await loadNotificationChannels(false)
        notify('success', isEdit ? 'Channel Updated' : 'Channel Added', `Email channel "${name}" saved.`)
    } catch (err: any) {
        emailModalError.value = extractError(err)
    } finally {
        emailSaveLoading.value = false
    }
}

// Conflict recovery: re-seed the open form (incl. expectedRevision) from
// the refreshed row, otherwise the next Save just conflicts again.
async function reloadEmailChannelFromServer() {
    await loadNotificationChannels(false)
    const fresh = emailChannels.value.find(c => c.uuid === emailForm.value.uuid)
    if (fresh) {
        openEditEmailChannel(fresh)
    } else {
        showEmailModal.value = false
        notify('warning', 'Channel Removed', 'This channel no longer exists on the server.')
    }
}

// ---- Webhook channel CRUD ----------------------------------------------------
// The endpoint URL + auth settings are stored encrypted server-side and
// never read back. On edit, leaving the URL blank sends webhookConfig: null,
// which preserves the stored endpoint + auth as a whole ("rename without
// re-typing"); entering a URL replaces the whole blob, so scheme + token
// must be (re-)supplied alongside it.
const showWebhookChModal = ref(false)
const webhookChSaveLoading = ref(false)
const webhookChModalError = ref('')
const webhookChForm = ref({
    uuid: '',
    expectedRevision: null as number | null,
    name: '',
    url: '',
    authScheme: 'NONE',
    authToken: ''
})

function openAddWebhookChannel() {
    webhookChForm.value = { uuid: '', expectedRevision: null, name: '', url: '', authScheme: 'NONE', authToken: '' }
    webhookChModalError.value = ''
    showWebhookChModal.value = true
}

function openEditWebhookChannel(ch: ChannelCatalogRow) {
    webhookChForm.value = {
        uuid: ch.uuid, expectedRevision: ch.revision, name: ch.name,
        url: '', authScheme: 'NONE', authToken: ''
    }
    webhookChModalError.value = ''
    showWebhookChModal.value = true
}

async function saveWebhookChannel() {
    webhookChModalError.value = ''
    const name = (webhookChForm.value.name || '').trim()
    if (!name) {
        webhookChModalError.value = 'Channel name is required.'
        return
    }
    const isEdit = !!webhookChForm.value.uuid
    const url = (webhookChForm.value.url || '').trim()
    const scheme = webhookChForm.value.authScheme
    // Token sent verbatim — secrets may be whitespace-significant; trim
    // only for the blank-check.
    const token = webhookChForm.value.authToken || ''
    let webhookConfig: any = null
    if (url) {
        if (!url.toLowerCase().startsWith('https://')) {
            webhookChModalError.value = 'Webhook URL must be HTTPS.'
            return
        }
        if (scheme !== 'NONE' && !token.trim()) {
            webhookChModalError.value = `Auth scheme ${scheme} requires a token.`
            return
        }
        webhookConfig = { url, authScheme: scheme, authToken: scheme !== 'NONE' ? token : null }
    } else if (!isEdit) {
        webhookChModalError.value = 'Endpoint URL is required.'
        return
    } else if (scheme !== 'NONE') {
        webhookChModalError.value = 'To change the auth settings, re-enter the endpoint URL as well — endpoint and auth are stored (and replaced) together.'
        return
    }
    webhookChSaveLoading.value = true
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation upsertNotificationChannel($input: NotificationChannelInput!) {
                    upsertNotificationChannel(input: $input) { uuid }
                }`,
            variables: {
                input: {
                    uuid: isEdit ? webhookChForm.value.uuid : null,
                    expectedRevision: isEdit ? webhookChForm.value.expectedRevision : null,
                    org: orguuid.value,
                    name,
                    type: 'WEBHOOK',
                    webhookConfig
                }
            },
            fetchPolicy: 'no-cache'
        })
        showWebhookChModal.value = false
        await loadNotificationChannels(false)
        notify('success', isEdit ? 'Channel Updated' : 'Channel Added', `Webhook channel "${name}" saved.`)
    } catch (err: any) {
        webhookChModalError.value = extractError(err)
    } finally {
        webhookChSaveLoading.value = false
    }
}

async function reloadWebhookChannelFromServer() {
    await loadNotificationChannels(false)
    const fresh = webhookChannels.value.find(c => c.uuid === webhookChForm.value.uuid)
    if (fresh) {
        openEditWebhookChannel(fresh)
    } else {
        showWebhookChModal.value = false
        notify('warning', 'Channel Removed', 'This channel no longer exists on the server.')
    }
}

// ---- Sentinel channel CRUD ---------------------------------------------------
// All six fields are encrypted together as one blob server-side. On edit,
// leaving all six blank preserves the stored credentials; the backend
// rejects partial re-entry (all six or none).
const showSentinelModal = ref(false)
const sentinelSaveLoading = ref(false)
const sentinelModalError = ref('')
const emptySentinelFields = () => ({
    tenantId: '', clientId: '', clientSecret: '',
    dcrEndpoint: '', dcrImmutableId: '', streamName: ''
})
const sentinelForm = ref({
    uuid: '',
    expectedRevision: null as number | null,
    name: '',
    ...emptySentinelFields()
})

function openAddSentinelChannel() {
    sentinelForm.value = { uuid: '', expectedRevision: null, name: '', ...emptySentinelFields() }
    sentinelModalError.value = ''
    showSentinelModal.value = true
}

function openEditSentinelChannel(ch: ChannelCatalogRow) {
    sentinelForm.value = { uuid: ch.uuid, expectedRevision: ch.revision, name: ch.name, ...emptySentinelFields() }
    sentinelModalError.value = ''
    showSentinelModal.value = true
}

async function saveSentinelChannel() {
    sentinelModalError.value = ''
    const name = (sentinelForm.value.name || '').trim()
    if (!name) {
        sentinelModalError.value = 'Channel name is required.'
        return
    }
    const isEdit = !!sentinelForm.value.uuid
    const f = sentinelForm.value
    const fields = {
        tenantId: (f.tenantId || '').trim(),
        clientId: (f.clientId || '').trim(),
        // Sent verbatim — secrets may be whitespace-significant.
        clientSecret: f.clientSecret || '',
        dcrEndpoint: (f.dcrEndpoint || '').trim(),
        dcrImmutableId: (f.dcrImmutableId || '').trim(),
        streamName: (f.streamName || '').trim()
    }
    const populated = Object.values(fields).filter(v => v.trim().length > 0).length
    let sentinelConfig: any = null
    if (populated === 6) {
        if (!fields.dcrEndpoint.toLowerCase().startsWith('https://')) {
            sentinelModalError.value = 'DCR endpoint must be HTTPS.'
            return
        }
        sentinelConfig = fields
    } else if (populated > 0) {
        sentinelModalError.value = 'Credentials are stored (and replaced) together — fill in all six fields, or leave all six blank to keep the current values.'
        return
    } else if (!isEdit) {
        sentinelModalError.value = 'All six Sentinel connection fields are required.'
        return
    }
    sentinelSaveLoading.value = true
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation upsertNotificationChannel($input: NotificationChannelInput!) {
                    upsertNotificationChannel(input: $input) { uuid }
                }`,
            variables: {
                input: {
                    uuid: isEdit ? sentinelForm.value.uuid : null,
                    expectedRevision: isEdit ? sentinelForm.value.expectedRevision : null,
                    org: orguuid.value,
                    name,
                    type: 'SENTINEL',
                    sentinelConfig
                }
            },
            fetchPolicy: 'no-cache'
        })
        showSentinelModal.value = false
        await loadNotificationChannels(false)
        notify('success', isEdit ? 'Channel Updated' : 'Channel Added', `Microsoft Sentinel channel "${name}" saved.`)
    } catch (err: any) {
        sentinelModalError.value = extractError(err)
    } finally {
        sentinelSaveLoading.value = false
    }
}

async function reloadSentinelChannelFromServer() {
    await loadNotificationChannels(false)
    const fresh = sentinelChannels.value.find(c => c.uuid === sentinelForm.value.uuid)
    if (fresh) {
        openEditSentinelChannel(fresh)
    } else {
        showSentinelModal.value = false
        notify('warning', 'Channel Removed', 'This channel no longer exists on the server.')
    }
}

// ---- Shared channel actions (all three channel cards) ------------------------
function openEditChannelForCard(card: CardConfig, ch: ChannelCatalogRow) {
    if (card.id === 'EMAIL') openEditEmailChannel(ch)
    else if (card.id === 'WEBHOOK') openEditWebhookChannel(ch)
    else if (card.id === 'SENTINEL') openEditSentinelChannel(ch)
}

async function toggleChannelStatus(ch: ChannelCatalogRow) {
    const next = ch.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation setNotificationChannelStatus($uuid: ID!, $status: NotificationChannelStatusEnum!) {
                    setNotificationChannelStatus(uuid: $uuid, status: $status) { uuid status }
                }`,
            variables: { uuid: ch.uuid, status: next },
            fetchPolicy: 'no-cache'
        })
        await loadNotificationChannels(false)
        notify('success', next === 'ENABLED' ? 'Channel Enabled' : 'Channel Disabled',
            `"${ch.name}" is now ${next === 'ENABLED' ? 'enabled' : 'disabled'}.`)
    } catch (err: any) {
        notify('error', 'Error', extractError(err))
    }
}

async function onDeleteChannel(card: CardConfig, ch: ChannelCatalogRow) {
    const confirm = await Swal.fire({
        title: `Delete ${card.name} channel "${ch.name}"?`,
        text: 'Subscriptions and channel groups referencing this channel will stop delivering to it. To pause sending without removing the channel, disable it instead.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, delete',
        cancelButtonText: 'Cancel'
    })
    if (!confirm.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: gql`
                mutation deleteNotificationChannel($uuid: ID!) {
                    deleteNotificationChannel(uuid: $uuid)
                }`,
            variables: { uuid: ch.uuid },
            fetchPolicy: 'no-cache'
        })
        await loadNotificationChannels(false)
        notify('success', 'Deleted', `${card.name} channel "${ch.name}" removed.`)
    } catch (err: any) {
        notify('error', 'Error', extractError(err))
    }
}

// ---- DT admin actions (preserved from old design) --------------------------
async function syncDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation syncDtrackProjects($orgUuid: ID!) { syncDtrackProjects(orgUuid: $orgUuid) }`,
            variables: { orgUuid: orguuid.value },
            fetchPolicy: 'no-cache'
        })
        if (resp.data?.syncDtrackProjects) {
            notify('success', 'D-Track Projects Sync', 'Successfully synchronized.')
        } else {
            notify('warning', 'D-Track Projects Sync', 'Completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'Sync Failed', err.message || 'Failed to sync.')
    }
}

async function forceReuploadDtrack() {
    if (!window.confirm('Force re-upload ALL synthetic data to Dependency-Track? This re-submits every project and triggers a full re-analysis — heavier than a sync. Use for recovery only.')) {
        return
    }
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation forceReuploadDtrackData($orgUuid: ID!) { forceReuploadDtrackData(orgUuid: $orgUuid) }`,
            variables: { orgUuid: orguuid.value },
            fetchPolicy: 'no-cache'
        })
        if (resp.data?.forceReuploadDtrackData) {
            notify('success', 'D-Track Force Re-upload', 'Re-upload started; re-analysis runs in the background.')
        } else {
            notify('warning', 'D-Track Force Re-upload', 'Completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'Force Re-upload Failed', err.message || 'Failed to start re-upload.')
    }
}

// ---- Data loaders ----------------------------------------------------------
async function loadConfiguredIntegrations(useCache: boolean) {
    const cachePolicy: FetchPolicy = useCache ? 'cache-first' : 'network-only'
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query configuredBaseIntegrations($org: ID!) {
                    configuredBaseIntegrations(org: $org)
                }`,
            variables: { org: orguuid.value },
            fetchPolicy: cachePolicy
        })
        if (resp.data?.configuredBaseIntegrations) {
            configuredIntegrations.value = resp.data.configuredBaseIntegrations
        }
    } catch (err) {
        console.error(err)
    }
}

async function loadCiIntegrations(useCache: boolean) {
    if (!showCiFeatures.value) return
    const cachePolicy: FetchPolicy = useCache ? 'cache-first' : 'network-only'
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query ciIntegrations($org: ID!) {
                    ciIntegrations(org: $org) {
                        uuid identifier org isEnabled type note capabilities
                    }
                }`,
            variables: { org: orguuid.value },
            fetchPolicy: cachePolicy
        })
        if (resp.data?.ciIntegrations) ciIntegrations.value = resp.data.ciIntegrations
    } catch (err) {
        console.error(err)
    }
}

async function loadNotificationChannels(useCache: boolean) {
    // Channel queries are a Pro surface — skip on OSS, where the
    // EMAIL / WEBHOOK / SENTINEL cards render in their "Pro" state instead.
    if (!showCiFeatures.value) return
    const cachePolicy: FetchPolicy = useCache ? 'cache-first' : 'network-only'
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query notificationChannelsForCatalog($orgUuid: ID!) {
                    notificationChannels(orgUuid: $orgUuid) {
                        uuid name type status revision digestMode digestInterval emailRecipients
                    }
                }`,
            variables: { orgUuid: orguuid.value },
            fetchPolicy: cachePolicy
        })
        if (resp.data?.notificationChannels) {
            notificationChannelRows.value = resp.data.notificationChannels
        }
    } catch (err) {
        console.error(err)
    }
}

async function loadBearIntegration() {
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query getBearIntegration($org: ID!) {
                    getBearIntegration(org: $org) { uri configured skipPatterns }
                }`,
            variables: { org: orguuid.value },
            fetchPolicy: 'network-only'
        })
        if (resp.data?.getBearIntegration) {
            bearIntegration.value = resp.data.getBearIntegration
            if (bearIntegration.value.configured) {
                bearForm.value.uri = bearIntegration.value.uri || ''
                bearForm.value.skipPatterns = bearIntegration.value.skipPatterns || []
            }
        }
    } catch (err) {
        console.error(err)
    }
}

onMounted(async () => {
    await Promise.all([
        loadConfiguredIntegrations(true),
        loadCiIntegrations(true),
        loadNotificationChannels(true),
        loadBearIntegration()
    ])
})

watch(() => props.orguuid, async () => {
    await Promise.all([
        loadConfiguredIntegrations(false),
        loadCiIntegrations(false),
        loadNotificationChannels(false),
        loadBearIntegration()
    ])
})
</script>

<style scoped>
/* ============================================================================
   Design tokens scoped to this component. Mirror /tmp/design tokens but only
   keep the ones we actually reference in the markup.
   ========================================================================== */
.org-integrations {
    --green:        #2D8F4E;
    --green-soft:   #E6F4EA;
    --green-softer: #F1F9F3;
    --green-border: #DCEEDF;
    --ink:          #1F2328;
    --ink-2:        #41464C;
    --muted:        #6B7280;
    --muted-2:      #9AA0A6;
    --line:         #E5E7EB;
    --line-2:       #EEF0F2;
    --bg-tint:      #FAFBFB;
    --chip:         #F3F4F6;
    --danger:       #C0392B;
    color: var(--ink);
}

/* ---- sub-tab bar -------------------------------------------------------- */
.subtab-bar {
    display: inline-flex;
    gap: 4px;
    background: #F3F5F4;
    padding: 4px;
    border-radius: 10px;
    margin: 12px 0 24px;
}
.subtab-pill {
    display: inline-flex; align-items: center; gap: 8px;
    padding: 6px 14px;
    border: none; background: transparent;
    border-radius: 7px;
    font-size: 13px; font-weight: 500;
    color: var(--ink-2); cursor: pointer;
    transition: background .12s, color .12s;
}
.subtab-pill:hover { color: var(--ink); }
.subtab-pill.active {
    background: #FFFFFF;
    color: var(--ink);
    box-shadow: 0 1px 2px rgba(16,24,40,.04);
    font-weight: 600;
}
.subtab-icon { display: inline-flex; }
.subtab-count {
    font-size: 11px; font-weight: 600;
    background: var(--green-softer); color: var(--green);
    padding: 2px 8px;
    border-radius: 999px;
    border: 1px solid var(--green-border);
}

/* ---- catalog header ----------------------------------------------------- */
.catalog-header { margin-bottom: 20px; }
.catalog-h2 { font-size: 17px; font-weight: 600; margin: 0 0 6px; color: var(--ink); }
.catalog-sub { font-size: 13px; color: var(--muted); max-width: 720px; }

.catalog-section-header {
    margin: 26px 0 12px;
    display: flex; align-items: center; gap: 8px;
    font-size: 11.5px;
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--muted);
}
.section-label { font-weight: 600; }
.section-dot { color: var(--muted-2); }
.section-desc { font-weight: 400; text-transform: none; letter-spacing: 0; font-size: 12px; }

/* ---- card grid ---------------------------------------------------------- */
.catalog-grid {
    display: grid;
    gap: 14px;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
}
.card {
    background: #FFFFFF;
    border: 1px solid var(--line);
    border-radius: 12px;
    padding: 18px;
    transition: border-color .12s, box-shadow .12s;
}
.card:hover {
    border-color: #CDD3D9;
    box-shadow: 0 4px 14px rgba(16,24,40,.06), 0 1px 2px rgba(16,24,40,.04);
}
.card-configured { background: #FFFFFF; }

.card-head {
    display: flex; align-items: center; gap: 12px;
    margin-bottom: 8px;
}
.logo {
    width: 40px; height: 40px;
    flex: 0 0 40px;
    border-radius: 9px;
    display: inline-flex; align-items: center; justify-content: center;
    color: #FFFFFF;
}
.logo-mark {
    font-weight: 700; font-size: 14px;
    letter-spacing: -.02em;
    font-family: Inter, sans-serif;
}
.card-title-block { flex: 1; min-width: 0; }
.card-name { font-size: 14.5px; font-weight: 600; color: var(--ink); }
.card-vendor { font-size: 12px; color: var(--muted); }

.pill {
    display: inline-flex; align-items: center; gap: 6px;
    font-size: 11px; font-weight: 500;
    padding: 3px 9px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--chip);
    color: var(--ink-2);
    flex-shrink: 0;
}
.pill .ddot {
    width: 6px; height: 6px; border-radius: 50%;
    background: var(--muted-2);
}
.pill-success {
    background: var(--green-softer);
    border-color: var(--green-border);
    color: var(--green);
}
.pill-success .ddot { background: var(--green); }

.card-desc {
    font-size: 12.5px; color: var(--ink-2);
    margin: 6px 0 14px;
    line-height: 1.5;
}
.card-foot {
    display: flex; align-items: center; justify-content: space-between;
    gap: 8px;
    padding-top: 10px;
    border-top: 1px dashed var(--line);
}
.muted-12 { font-size: 12px; color: var(--muted); }

.card-pro-hint {
    margin-top: 10px;
    font-size: 11.5px;
    color: var(--muted);
    line-height: 1.4;
}
.pro-hint-link {
    color: var(--n-color-primary, #2080f0);
    cursor: pointer;
    text-decoration: none;
}
.pro-hint-link:hover { text-decoration: underline; }

/* ---- instance rows ------------------------------------------------------ */
.instance-list {
    display: flex; flex-direction: column;
    gap: 6px;
    margin-top: 10px;
}
.instance-row {
    display: flex; align-items: center; gap: 10px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--bg-tint);
    transition: background .12s, border-color .12s;
}
.instance-row:hover { background: #FFFFFF; border-color: #CDD3D9; }
.instance-status { flex-shrink: 0; }
.ddot.ok {
    width: 8px; height: 8px; border-radius: 50%;
    background: var(--green);
    display: inline-block;
    box-shadow: 0 0 0 3px var(--green-softer);
}
.ddot.off {
    width: 8px; height: 8px; border-radius: 50%;
    background: var(--muted-2);
    display: inline-block;
    box-shadow: 0 0 0 3px var(--chip);
}
.instance-body { flex: 1; min-width: 0; }
.instance-name {
    font-size: 13.5px; font-weight: 500;
    color: var(--ink);
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.instance-meta {
    font-size: 11.5px; color: var(--muted);
    margin-top: 2px;
}
.instance-scope { font-family: 'JetBrains Mono', ui-monospace, Menlo, monospace; }
.caps { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.cap-chip {
    font-family: 'JetBrains Mono', ui-monospace, Menlo, monospace;
    font-size: 10.5px; font-weight: 500;
    background: var(--green-softer);
    border: 1px solid var(--green-border);
    color: var(--green);
    padding: 2px 6px;
    border-radius: 4px;
}
.cap-chip.chip-muted {
    background: var(--chip);
    border-color: var(--line);
    color: var(--muted);
}
.instance-actions { display: flex; gap: 6px; flex-shrink: 0; }
.instance-icon {
    cursor: pointer; color: var(--muted-2);
    padding: 4px;
    border-radius: 6px;
    transition: color .12s, background .12s;
}
.instance-icon:hover { color: var(--ink); background: var(--bg-tint); }
.instance-icon.danger:hover { color: var(--danger); background: #FCEEEC; }

/* ---- + Add another ------------------------------------------------------ */
.add-another {
    margin-top: 10px;
    width: 100%;
    padding: 10px;
    background: transparent;
    border: 1px dashed var(--line);
    border-radius: 8px;
    color: var(--muted);
    font-size: 12.5px; font-weight: 500;
    display: inline-flex; align-items: center; justify-content: center; gap: 6px;
    cursor: pointer;
    transition: color .12s, border-color .12s;
}
.add-another:hover { color: var(--green); border-color: var(--green); }

/* ---- info banner -------------------------------------------------------- */
.info-banner {
    display: flex; gap: 12px; align-items: flex-start;
    padding: 14px 16px;
    background: var(--green-softer);
    border: 1px solid var(--green-border);
    color: var(--ink);
    border-radius: 12px;
    margin-bottom: 18px;
}
.info-banner > .n-icon { color: var(--green); flex-shrink: 0; }
.info-banner-title { font-weight: 600; font-size: 13.5px; margin-bottom: 2px; }
.info-banner-body { font-size: 12.5px; color: var(--ink-2); line-height: 1.5; }

.webhooks-pane, .pr-validation-pane { padding-top: 4px; }
</style>
