<template>
    <div class="componentView">
        <h4>Statistics for {{ myorg.name }}</h4>
        <div v-if="isLoading" data-testid="totals-loading">Loading...</div>
        <div v-else>
            <n-data-table
                :columns="columns"
                :data="analyticsData"
                :bordered="false"
                data-testid="totals-table"
            />
            <div v-if="countedAtText" class="countedAtNote" data-testid="totals-counted-at-note">
                Releases, artifacts, commits and deliverables are counted nightly; the counts shown are as of {{ countedAtText }}
            </div>
        </div>
    </div>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted, computed, h, Ref, ComputedRef } from 'vue';
import { useStore } from 'vuex';
import gql from 'graphql-tag';
import graphqlClient from '../utils/graphql';
import commonFunctions from '@/utils/commonFunctions';
import { NDataTable } from 'naive-ui';

/**
 * TPF-140: a newer backend counts these four totals once a night and serves each as a number or
 * null (pending before the first count), and dates them with one counted_at instant (ISO-8601 UTC
 * string or null). A backend without the counted_at key serves live numbers and the page renders
 * them as it always did. Nothing here requests the four counts another way.
 */
const CACHED_TOTALS_KEYS = ['releases', 'artifacts', 'commits', 'deliverables'];
const COUNTED_AT_KEY = 'counted_at';
const PENDING_TEXT = 'pending, counted tonight';

export default defineComponent({
    name: 'AnalyticsOfOrg',
    components: {
        NDataTable
    },
    setup() {
        const store = useStore();
        const isLoading = ref(true);
        const totalsAnalytics: Ref<Record<string, any>> = ref({});

        const myorg: ComputedRef<any> = computed(() => store.getters.myorg);

        const hasCountedAt: ComputedRef<boolean> = computed(() =>
            Object.prototype.hasOwnProperty.call(totalsAnalytics.value || {}, COUNTED_AT_KEY));

        const countedAtText: ComputedRef<string> = computed(() => {
            if (!hasCountedAt.value) return '';
            const raw = totalsAnalytics.value[COUNTED_AT_KEY];
            if (typeof raw !== 'string' || raw === '') return '';
            if (isNaN(Date.parse(raw))) return raw;
            return commonFunctions.dateDisplay(raw);
        });

        const analyticsData = computed(() => {
            if (!totalsAnalytics.value || Object.keys(totalsAnalytics.value).length === 0) {
                return [];
            }
            return Object.entries(totalsAnalytics.value)
                .filter(([key]) => key !== COUNTED_AT_KEY)
                .map(([key, value]) => ({
                    key,
                    metric: key === 'vcs' ? 'VCS Repositories'
                        : key === 'admin_and_write_users' ? 'Admin & Write Users'
                            : key === 'read_only_users' ? 'Read-Only Users'
                                : key.charAt(0).toUpperCase() + key.slice(1).replace(/_/g, ' '),
                    value
                }))
                .sort((a, b) => a.metric.localeCompare(b.metric));
        });

        const columns = [
            {
                title: 'Metric',
                key: 'metric'
            },
            {
                title: 'Count',
                key: 'value',
                render (row: any) {
                    if (hasCountedAt.value && CACHED_TOTALS_KEYS.includes(row.key) && row.value == null) {
                        return h('span', { 'data-testid': 'totals-pending-' + row.key }, PENDING_TEXT);
                    }
                    const text = row.value == null ? '' : String(row.value);
                    return h('span', { 'data-testid': 'totals-value-' + row.key }, text);
                }
            }
        ];

        async function fetchTotalsAnalytics(orgUuid: string) {
            try {
                const resp = await graphqlClient.query({
                    query: gql`
                        query totalsAnalytics($orgUuid: ID!) {
                            totalsAnalytics(orgUuid: $orgUuid)
                        }
                    `,
                    variables: { orgUuid },
                    fetchPolicy: 'no-cache'
                });
                totalsAnalytics.value = resp.data.totalsAnalytics;
            } catch (error) {
                console.error('Error fetching totals analytics:', error);
            }
        }

        onMounted(async () => {
            if (myorg.value && myorg.value.uuid) {
                await fetchTotalsAnalytics(myorg.value.uuid);
            }
            isLoading.value = false;
        });

        return {
            isLoading,
            analyticsData,
            columns,
            countedAtText,
            myorg
        };
    }
});
</script>

<style scoped>
.componentView {
    padding: 20px;
}
.countedAtNote {
    margin-top: 8px;
    font-size: 0.9em;
}
</style>
