<template>
    <div class="componentView">
        <h4>Statistics for {{ myorg.name }}</h4>
        <div v-if="isLoading">Loading...</div>
        <div v-else>
            <n-data-table
                :columns="columns"
                :data="analyticsData"
                :bordered="false"
            />
        </div>
    </div>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted, computed, Ref, ComputedRef } from 'vue';
import { useStore } from 'vuex';
import gql from 'graphql-tag';
import graphqlClient from '../utils/graphql';
import { NDataTable } from 'naive-ui';

export default defineComponent({
    name: 'AnalyticsOfOrg',
    components: {
        NDataTable
    },
    setup() {
        const store = useStore();
        const isLoading = ref(true);
        const totalsAnalytics = ref({});

        const myorg: ComputedRef<any> = computed(() => store.getters.myorg);

        const analyticsData = computed(() => {
            if (!totalsAnalytics.value || Object.keys(totalsAnalytics.value).length === 0) {
                return [];
            }
            return Object.entries(totalsAnalytics.value)
                .map(([key, value]) => ({
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
                key: 'value'
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
            myorg
        };
    }
});
</script>

<style scoped>
.componentView {
    padding: 20px;
}
</style>