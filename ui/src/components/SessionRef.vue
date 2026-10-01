<template>
    <!-- A session, named so two sessions on one key differ, linked to its page (RD2-11). -->
    <router-link v-if="session.uuid" :to="sessionPath(session.uuid)" class="sessionref" :title="session.uuid ?? undefined"
                 data-testid="session-ref" @click.stop>{{ sessionLabel(session) }}</router-link>
    <span v-else class="sessionref">{{ sessionLabel(session) }}</span>
</template>

<script lang="ts" setup>
import { SessionLabelInput, sessionLabel, sessionPath } from '@/utils/agentSessionLabel'

defineProps<{ session: SessionLabelInput }>()
</script>

<style scoped>
.sessionref { font-family: inherit; }
</style>
