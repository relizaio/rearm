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
                                <span class="muted-12">Not configured</span>
                                <n-button size="small" @click="openAddForCard(card)">
                                    <template #icon><n-icon><CirclePlus /></n-icon></template>
                                    Add
                                </n-button>
                            </div>
                        </template>

                        <!-- CONFIGURED state — instance rows -->
                        <template v-else>
                            <div class="instance-list">
                                <!-- single-instance kinds (Slack/Teams/DT/BEAR) -->
                                <template v-if="!card.multiInstance">
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
                                                <n-icon v-if="isGlobalAdmin" class="instance-icon" size="20" title="Re-upload D-Track Projects" @click="refreshDtrackProjects"><ArrowUpload24Regular /></n-icon>
                                                <n-icon v-if="isGlobalAdmin" class="instance-icon" size="20" title="Cleanup D-Track Projects" @click="cleanupDtrackProjects"><Clean /></n-icon>
                                                <n-icon v-if="isGlobalAdmin" class="instance-icon" size="20" title="Re-cleanup D-Track Projects" @click="recleanupDtrackProjects"><DeleteDismiss24Regular /></n-icon>
                                            </template>
                                            <n-icon v-if="card.id === 'BEAR'" class="instance-icon" size="20" title="Edit BEAR Integration" @click="openBearEditModal"><EditIcon /></n-icon>
                                            <n-icon
                                                class="instance-icon danger"
                                                size="20"
                                                :title="`Delete ${card.name} integration`"
                                                @click="card.id === 'BEAR' ? deleteBearIntegration() : onDeleteBaseInstance(card)"
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
                    </div>
                </div>
            </template>
        </div>

        <!-- ============================== WEBHOOKS ============================== -->
        <div v-if="subTab === 'webhooks' && showCiFeatures" class="webhooks-pane">
            <div class="info-banner">
                <n-icon size="20"><ShieldCheck /></n-icon>
                <div>
                    <div class="info-banner-title">Inbound webhooks</div>
                    <div class="info-banner-body">
                        Each webhook is bound to a CI integration with the <span class="cap-chip">WEBHOOK</span> capability.
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
            <WebhooksOfOrg :orguuid="orguuid" />
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

        <!-- CI Integration ADD (GitHub / GitLab / Jenkins / ADO) -->
        <n-modal v-if="showCiFeatures" v-model:show="showCiAddModal" preset="dialog" :show-icon="false" style="width: 90%">
            <n-card style="max-width: 800px; width: 100%" size="huge" :title="addModalTitle" :bordered="false" role="dialog" aria-modal="true">
                <n-form :model="createIntegrationObject">
                    <n-space vertical size="large">
                        <n-form-item label="Description" path="note">
                            <n-input v-model:value="createIntegrationObject.note" required placeholder="Enter Description" />
                        </n-form-item>
                        <n-form-item label="CI Type" path="createIntegrationObject.type">
                            <n-radio-group v-model:value="createIntegrationObject.type" name="ciIntegrationType">
                                <n-radio-button label="GitHub" value="GITHUB" />
                                <n-radio-button label="GitLab" value="GITLAB" />
                                <n-radio-button label="Jenkins" value="JENKINS" />
                                <n-radio-button label="Azure DevOps" value="ADO" />
                            </n-radio-group>
                        </n-form-item>
                        <n-form-item
                            v-if="createIntegrationObject.type === 'GITHUB'"
                            label="Capabilities"
                            description="What this GitHub App is wired up to do. PR_VALIDATE: post check-runs / PR comments. WEBHOOK: receive inbound pull_request events. WORKFLOW_DISPATCH: trigger repository_dispatch."
                        >
                            <n-checkbox-group v-model:value="createIntegrationObject.capabilities">
                                <n-space>
                                    <n-checkbox value="WORKFLOW_DISPATCH" label="Workflow Dispatch" />
                                    <n-checkbox value="PR_VALIDATE" label="PR Validate" />
                                    <n-checkbox value="WEBHOOK" label="Webhook (inbound)" />
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
                                    <n-checkbox value="WEBHOOK" label="Webhook (inbound)" />
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
    NRadioGroup, NRadioButton, NSpace, NText, NDynamicInput, NUpload, NUploadTrigger
} from 'naive-ui'
import {
    Edit as EditIcon, Trash, Refresh, CirclePlus,
    BrandGithub, BrandGitlab, BrandSlack,
    PlugConnected, ShieldCheck, LayoutGrid
} from '@vicons/tabler'
import { Clean } from '@vicons/carbon'
import { ArrowUpload24Regular, DeleteDismiss24Regular } from '@vicons/fluent'
import gql from 'graphql-tag'
import { FetchPolicy } from '@apollo/client'
import Swal from 'sweetalert2'
import graphqlClient from '../utils/graphql'
import commonFunctions from '../utils/commonFunctions'
import WebhooksOfOrg from './WebhooksOfOrg.vue'
import OrgGlobalPrValidationRules from './OrgGlobalPrValidationRules.vue'
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
const isGlobalAdmin = computed(() => props.isGlobalAdmin)
const isWritable = computed(() => props.isWritable)

// ---- Sub-tab state, URL-synced ---------------------------------------------
type SubTab = 'catalog' | 'webhooks' | 'pr-validation'
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

// ---- Card config -----------------------------------------------------------
// We keep the brand color + icon mapping co-located so it's easy to add
// kinds later. iconComponent wins over logoMark — falls back to a monogram
// when @vicons doesn't have a brand icon for the kind.
type Category = 'messaging' | 'ci' | 'security'
interface CardConfig {
    id: 'SLACK' | 'MSTEAMS' | 'GITHUB' | 'GITLAB' | 'JENKINS' | 'ADO' | 'DEPENDENCYTRACK' | 'BEAR'
    name: string
    vendor: string
    category: Category
    description: string
    logoBg: string
    logoMark?: string
    iconComponent?: any
    multiInstance: boolean
}

const CARDS: CardConfig[] = [
    // Messaging
    { id: 'SLACK', name: 'Slack', vendor: 'Slack Technologies', category: 'messaging', description: 'Push release notifications and approval-state changes to a Slack channel.', logoBg: '#4A154B', iconComponent: BrandSlack, multiInstance: false },
    { id: 'MSTEAMS', name: 'Microsoft Teams', vendor: 'Microsoft', category: 'messaging', description: 'Push release notifications to a Microsoft Teams channel.', logoBg: '#4B53BC', logoMark: 'T', multiInstance: false },
    // CI/CD & Source Control
    { id: 'GITHUB', name: 'GitHub', vendor: 'GitHub', category: 'ci', description: 'GitHub App for PR validation, inbound webhooks, and repository_dispatch.', logoBg: '#1F2328', iconComponent: BrandGithub, multiInstance: true },
    { id: 'GITLAB', name: 'GitLab', vendor: 'GitLab', category: 'ci', description: 'GitLab project access for pipeline triggers and SCM links.', logoBg: '#FC6D26', iconComponent: BrandGitlab, multiInstance: true },
    { id: 'JENKINS', name: 'Jenkins', vendor: 'Jenkins', category: 'ci', description: 'Trigger Jenkins jobs and ingest pipeline state.', logoBg: '#D33833', logoMark: 'J', multiInstance: true },
    { id: 'ADO', name: 'Azure DevOps', vendor: 'Microsoft', category: 'ci', description: 'Trigger Azure DevOps pipelines and ingest pipeline state.', logoBg: '#0078D4', logoMark: 'AZ', multiInstance: true },
    // Security & Compliance
    { id: 'DEPENDENCYTRACK', name: 'Dependency-Track', vendor: 'OWASP', category: 'security', description: 'Push SBOMs and pull vulnerability + policy findings.', logoBg: '#1B4E72', logoMark: 'DT', multiInstance: false },
    { id: 'BEAR', name: 'BEAR', vendor: 'Reliza', category: 'security', description: 'BOM enrichment and risk-signal service.', logoBg: '#22332B', logoMark: 'BR', multiInstance: false }
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
})

// ---- Card status helpers ---------------------------------------------------
function isCardConfigured(card: CardConfig): boolean {
    if (card.id === 'BEAR') return !!(bearIntegration.value && bearIntegration.value.configured)
    if (card.multiInstance) return ciInstancesForCard(card).length > 0
    return configuredIntegrations.value.includes(card.id)
}

function ciInstancesForCard(card: CardConfig): any[] {
    return ciIntegrations.value.filter((i: any) => i.type === card.id)
}

function cardStatusLabel(card: CardConfig): string {
    if (!isCardConfigured(card)) return 'Available'
    if (card.multiInstance) {
        const n = ciInstancesForCard(card).length
        return n > 1 ? `${n} active` : 'Active'
    }
    return 'Active'
}

function cardStatusClass(card: CardConfig): string {
    return isCardConfigured(card) ? 'pill-success' : 'pill-neutral'
}

function singleInstanceLabel(card: CardConfig): string {
    if (card.id === 'BEAR') return 'Configured'
    return 'Configured'
}

const catalogActiveCount = computed(() => {
    let n = 0
    for (const c of CARDS) {
        if (c.multiInstance) n += ciInstancesForCard(c).length
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
    if (card.id === 'SLACK') { showSlackModal.value = true; return }
    if (card.id === 'MSTEAMS') { showMsteamsModal.value = true; return }
    if (card.id === 'DEPENDENCYTRACK') { showDtModal.value = true; return }
    if (card.id === 'BEAR') { openBearAddModal(); return }
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

async function refreshDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation refreshDtrackProjects($orgUuid: ID!) { refreshDtrackProjects(orgUuid: $orgUuid) }`,
            variables: { orgUuid: orguuid.value },
            fetchPolicy: 'no-cache'
        })
        if (resp.data?.refreshDtrackProjects) {
            notify('success', 'D-Track Projects Refresh', 'Successfully refreshed.')
        } else {
            notify('warning', 'D-Track Projects Refresh', 'Completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'Refresh Failed', err.message || 'Failed to refresh.')
    }
}

async function cleanupDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation cleanupDtrackProjects($orgUuid: ID!) { cleanupDtrackProjects(orgUuid: $orgUuid) }`,
            variables: { orgUuid: orguuid.value },
            fetchPolicy: 'no-cache'
        })
        if (resp.data?.cleanupDtrackProjects) {
            notify('success', 'D-Track Projects Cleanup', 'Successfully cleaned up.')
        } else {
            notify('warning', 'D-Track Projects Cleanup', 'Completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'Cleanup Failed', err.message || 'Failed to cleanup.')
    }
}

async function recleanupDtrackProjects() {
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation recleanupDtrackProjects($orgUuid: ID!) { recleanupDtrackProjects(orgUuid: $orgUuid) }`,
            variables: { orgUuid: orguuid.value },
            fetchPolicy: 'no-cache'
        })
        if (resp.data?.recleanupDtrackProjects) {
            notify('success', 'D-Track Projects Re-cleanup', 'Successfully re-cleaned up.')
        } else {
            notify('warning', 'D-Track Projects Re-cleanup', 'Completed but returned false.')
        }
    } catch (err: any) {
        notify('error', 'Re-cleanup Failed', err.message || 'Failed to re-cleanup.')
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
        loadBearIntegration()
    ])
})

watch(() => props.orguuid, async () => {
    await Promise.all([
        loadConfiguredIntegrations(false),
        loadCiIntegrations(false),
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
