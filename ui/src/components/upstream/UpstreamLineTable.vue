<template>
    <div class="upstream-line-table">
        <n-table size="small" :single-line="false" data-testid="upstream-line-table">
            <thead>
                <tr>
                    <th>Branch</th>
                    <th>Releases</th>
                    <th>Versions</th>
                    <th>Newest</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="line in lines" :key="line.lineKey" data-testid="upstream-line-row">
                    <td>{{ line.branchName }}</td>
                    <td>{{ line.releases }}</td>
                    <td>{{ line.lowestVersion }} to {{ line.highestVersion }}</td>
                    <td>{{ line.newestDate ? dateOf(line.newestDate) : '' }}</td>
                </tr>
            </tbody>
        </n-table>
        <div v-if="truncated" class="upstream-line-truncated" data-testid="upstream-lines-truncated">more lines exist than are listed</div>
    </div>
</template>

<script lang="ts" setup>
// The version lines a depth or a starting point yields (ReARM Pro, TPF-185 design 3.8): branch
// name, releases, lowest to highest version and the newest date, as the backend computed them.
// Every value is text.
import { NTable } from 'naive-ui'
import commonFunctions from '@/utils/commonFunctions'

defineProps<{
    /** UpstreamLineProposal rows. */
    lines: any[]
    truncated?: boolean
}>()

function dateOf (d: string): string {
    return commonFunctions.dateDisplay(d)
}
</script>

<style scoped>
.upstream-line-truncated {
    font-size: 0.9em;
    color: #777;
    margin-top: 4px;
}
</style>
