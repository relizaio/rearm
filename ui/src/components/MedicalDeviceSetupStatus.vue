<template>
    <div class="medical-device-setup-status">
        <!-- C5: where the four FDA texts are written, while question 2 is answered Yes. The tab does not
             read the texts, so it does not say which are missing (FDAW-16's readiness row). -->
        <div v-if="fdaPremarket === 'ENABLED'" data-testid="device-fda-texts" class="text-muted"
            style="font-size: 12px; margin-top: 6px; max-width: 720px;">
            FDA texts: the assessment justification and the three labeling statements (patches may cease at end of
            support, risk-transfer process reference, risk increases over time) are written once for the organization,
            under <router-link data-testid="device-fda-texts-link" :to="fdaTextsRoute">{{ PROSE_SETTINGS_LOCATION }}</router-link>.
            An organization administrator edits them.
        </div>
        <!-- The organization steps as the status reads them; the bar follows the caller's own pending steps
             only (FDAW-18 F-10), these lines follow the store. -->
        <div data-testid="device-setup-status" style="font-size: 12px; margin-top: 10px; max-width: 720px;">
            <div v-if="anyNotDone" data-testid="device-setup-intro">{{ DEVICE_SETUP_INTRO }}</div>
            <div v-for="l in lines" :key="l.step" :data-testid="'device-setup-line-' + l.step">{{ l.done ? '\u2713 ' + l.text : 'Not done yet: ' + l.todo }}</div>
            <n-alert v-if="status?.mustFix?.length" type="warning" :show-icon="true"
                data-testid="device-setup-must-fix" style="margin-top: 6px;">
                <div v-for="m in status.mustFix" :key="m">{{ m }}</div>
                <router-link data-testid="device-setup-must-fix-link" :to="sbomProfilesRoute">{{ SBOM_PROFILES_LINK_TEXT }}</router-link>
            </n-alert>
            <div v-for="l in stepNotes" :key="l.step" :data-testid="'device-step-not-done-' + l.step"
                class="text-muted" style="margin-top: 4px;">Still to do: {{ l.text }}</div>
        </div>
    </div>
</template>

<script setup lang="ts">
// The Medical device tab's setup status (FDAW-78 architecture 3.6, L10, C1, C5), out of
// ComponentView.vue so a spec can mount it: the intro sentence, one line per organization step
// (done or what is still to do), the must-fix alert and its link, the steps the last Save could not
// make (each distinct server sentence once), and where the four FDA texts are written. Display only:
// it holds no control, reads nothing and does not guess who the caller is (FDAW-18 3.5).
import { computed } from 'vue'
import { NAlert } from 'naive-ui'
import { DEVICE_SETUP_INTRO, SBOM_PROFILES_LINK_TEXT, setupStatusLines, type SetupStepLine } from '@/utils/medicalDeviceSetup'
import { PROSE_SETTINGS_LOCATION } from '@/utils/deviceSupportStatement'

const props = withDefaults(defineProps<{
    /** medicalDeviceSetupStatus as read, or null. */
    status?: any
    /** The steps the last Save could not make (setupStepLines). */
    stepNotes?: SetupStepLine[]
    /** The component's organization: the links' route parameter. */
    orgUuid?: string | null
    /** Question 2's answer as edited: ENABLED shows where the FDA texts are. */
    fdaPremarket?: string | null
}>(), {
    status: null,
    stepNotes: () => [],
    orgUuid: null,
    fdaPremarket: null,
})

const lines = computed(() => setupStatusLines(props.status))
const anyNotDone = computed(() => lines.value.some(l => !l.done))
const fdaTextsRoute = computed(() => ({ name: 'OrgSettings', params: { orguuid: props.orgUuid },
    query: { tab: 'adminSettings', adminTab: 'general' }, hash: '#submission-labeling-text' }))
const sbomProfilesRoute = computed(() => ({ name: 'OrgSettings', params: { orguuid: props.orgUuid },
    query: { tab: 'adminSettings', adminTab: 'sbomProfiles' } }))
</script>
