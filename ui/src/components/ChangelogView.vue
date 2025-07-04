<template>
    <div class="container">
        <h1 v-if="isRouterLink">Changelog</h1>
        <div>
            Aggregation:
            <n-radio-group v-model:value="aggregationType" name="aggregatetyperadiogroup">
                <n-radio-button
                    v-for="abtn in aggregationTypes"
                    :key="abtn.value"
                    :value="abtn.value"
                    :label="abtn.key"
                    @change="getAggregatedChangelog"
                />
            </n-radio-group>
        </div>
        <div style="margin-top: 4px; max-width: 30%;">
            <n-form-item>
                <vue-feather size="20" class="icons" type="filter" title="Filter By Severity:" />  <n-select v-model:value="selectedSeverity" :options="severityTypes" />
            </n-form-item>
            
        </div>
        <div v-if="aggregationType === 'NONE' && componentType === 'COMPONENT' && changelog && changelog.branches && changelog.branches.length === 1">
            <div v-for="release in changelog.branches[0].releases" :key="release.uuid">
                <h3><router-link :to="{ name: 'ReleaseView', params: {uuid: release.uuid}}">{{release.version}}</router-link></h3>
                <ul>
                    <div v-for="change in release.changes" :key="change.changeType">
                        <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                            <h4>{{change.changeType}}</h4>
                            <ul>
                                <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                    <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                    <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                        <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                    </span>
                                </li>
                            </ul>
                        </li>
                    </div>
                </ul>
            </div>
        </div>
        <div v-if="aggregationType === 'AGGREGATED' && componentType === 'COMPONENT' && changelog && changelog.branches &&  changelog.branches.length === 1">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid, branchuuid: changelog.branches[0].uuid }}">{{ changelog.name + '(' + changelog.branches[0].name + ')' }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            <ul>
                <div v-for="change in changelog.branches[0].changes" :key="change.changeType">
                    <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                        <h4>{{change.changeType}}</h4>
                        <ul>
                            <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                    <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                </span>
                            </li>
                        </ul>
                    </li>
                </div>
            </ul>
        </div>
        <div v-if="aggregationType === 'AGGREGATED_BY_TICKET' && componentType === 'COMPONENT'&& changelog && changelog.branches && changelog.branches.length === 1">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid, branchuuid: changelog.branches[0].uuid }}">{{ changelog.name + '(' + changelog.branches[0].name + ')' }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            <div v-for="ticket in changelog.branches[0].tickets" :key="ticket.ticketSubject">
                <h4>{{ticket.ticketSubject}}</h4>
                <ul>
                    <div v-for="change in ticket.changes" :key="change.changeType">
                        <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                            <h4>{{change.changeType}}</h4>
                            <ul>
                                <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                    <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                    <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                        <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                    </span>
                                </li>
                            </ul>
                        </li>
                    </div>
                </ul>
            </div>
        </div>
        <div v-if="aggregationType === 'NONE' && componentType === 'PRODUCT'">
            <div v-for="product in changelog.components" :key="product.uuid">
                <h3><router-link :to="{ name: 'ReleaseView', params: {uuid: product.uuid}}">{{product.name}}</router-link></h3>
                <ul>
                    <li v-for="component in product.components" :key="component.uuid">
                        <h4><router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: component.branches[0].uuid }}">{{ component.name + '(' + component.branches[0].name + ')' }}</router-link></h4>
                        <ul>
                            <li v-for="release in component.branches[0].releases" :key="release.uuid">
                                <h4><router-link :to="{ name: 'ReleaseView', params: {uuid: release.uuid}}">{{release.version}}</router-link></h4>
                                <ul>
                                    <div v-for="change in release.changes" :key="change.changeType">
                                        <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                            <h4>{{change.changeType}}</h4>
                                            <ul>
                                                <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                                    <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                                    <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                        <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                                    </span>
                                                </li>
                                            </ul>
                                        </li>
                                    </div>
                                </ul>
                            </li>
                        </ul>
                    </li>
                </ul>
            </div>
        </div>
        <div v-if="aggregationType === 'AGGREGATED' && componentType === 'PRODUCT'">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid }}">{{ changelog.name }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            <div v-for="component in changelog.components" :key="component.uuid">
                <h3 v-if="component.org">
                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                    <span>&nbsp;</span>
                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                    <span> - </span>
                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                </h3>
                <h5 v-if="component.branches && component.branches.length > 1">* branch changed between product releases</h5>
                <ul>
                    <li v-for="branch in component.branches" :key="branch.uuid">
                        <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link>
                        <ul>
                            <div v-for="change in branch.changes" :key="change.changeType">
                                <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                    <h4>{{change.changeType}}</h4>
                                    <ul>
                                        <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                            <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                            <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                            </span>
                                        </li>
                                    </ul>
                                </li>
                            </div>
                        </ul>
                    </li>
                        
                </ul>
            </div>
        </div>
        <div v-if="aggregationType === 'AGGREGATED_BY_TICKET' && componentType === 'PRODUCT'">
            <h2 v-if="changelog.org">
                <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: changelog.org, compuuid: changelog.uuid }}">{{ changelog.name }}</router-link>
                <span>&nbsp;</span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.firstRelease.uuid}}">{{changelog.firstRelease.version}}</router-link>
                <span> - </span>
                <router-link :to="{ name: 'ReleaseView', params: {uuid: changelog.lastRelease.uuid}}">{{changelog.lastRelease.version}}</router-link>
            </h2>
            <div v-for="component in changelog.components" :key="component.uuid">
                <h3 v-if="component.org">
                    <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid }}">{{ component.name }}</router-link>
                    <span>&nbsp;</span>
                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.firstRelease.uuid}}">{{component.firstRelease.version}}</router-link>
                    <span> - </span>
                    <router-link :to="{ name: 'ReleaseView', params: {uuid: component.lastRelease.uuid}}">{{component.lastRelease.version}}</router-link>
                </h3>
                <h6 v-if="component.branches && component.branches.length > 1">* branch changed between product releases</h6>
                <ul>
                    <li v-for="branch in component.branches" :key="branch.uuid">
                        <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: component.org, compuuid: component.uuid, branchuuid: branch.uuid }}">{{ branch.name }}</router-link>
                        <div v-for="ticket in branch.tickets" :key="ticket.ticketSubject">
                            <h4>{{ticket.ticketSubject}}</h4>
                            <ul>
                                <div v-for="change in ticket.changes" :key="change.changeType">
                                    <li v-if="selectedSeverity === 'ALL' || selectedSeverity === change.changeType">
                                        <h4>{{change.changeType}}</h4>
                                        <ul>
                                            <li v-for="commitRecord in change.commitRecords" :key="commitRecord.linkifiedText">
                                                <a :href="commitRecord.linkifiedText" rel="noopener noreferrer" target="_blank">{{commitRecord.rawText}}</a>
                                                <span v-if="commitRecord.commitAuthor && commitRecord.commitEmail && commitRecord.commitAuthor !== '' && commitRecord.commitEmail !== ''">, by
                                                    <a :href="('mailto:' + commitRecord.commitEmail)" rel="noopener noreferrer" target="_blank">{{commitRecord.commitAuthor}}</a>
                                                </span>
                                            </li>
                                        </ul>
                                    </li>
                                </div>
                            </ul>
                        </div>
                    </li>
                </ul>
               
            </div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ChangelogView'
}
</script>
<script lang="ts" setup>
import commonFunctions from '../utils/commonFunctions'
import { Ref, ref, watch } from 'vue'
import { NRadioGroup, NRadioButton, NSelect, NFormItem } from 'naive-ui'

async function getComponentChangelog (org: string, component: string,
    branch: string, aggregationType: string) {
    let changelog = {}
    if (component && branch) {
        let fetchRlzParams = {
            componentId: component,
            orgId: org,
            branchId: branch,
            aggregated: aggregationType,
        }
        changelog = await commonFunctions.fetchComponentChangeLog(fetchRlzParams)
    }
    return changelog
}

async function getChangelog (org: string, release1: string, release2: string, aggregationType: string) {
    let changelog = {}
    if (release1 && release2) {
        let fetchRlzParams = {
            release1: release1,
            release2: release2,
            org: org,
            aggregated:aggregationType
        }
        changelog = await commonFunctions.fetchChangelogBetweenReleases(fetchRlzParams)
    }
    return changelog
}

const props = defineProps<{
    release1prop?: String,
    release2prop?: String,
    componentprop: String,
    orgprop: String,
    branchprop?: String,
    componenttypeprop: String,
    isrouterlink?: Boolean,
    iscomponentchangelog: Boolean
}>()

const aggregationType : Ref<string> = ref('NONE')
const aggregationTypes = [
    { key: 'NONE', value: 'NONE' },
    { key: 'AGGREGATED', value: 'AGGREGATED' }
]

const changelog : Ref<any> = ref({})

const isRouterLink = props.isrouterlink
const componentType = props.componenttypeprop

const getAggregatedChangelog = async function () {
    if (props.iscomponentchangelog) {
        changelog.value = await getComponentChangelog(props.orgprop, props.componentprop, props.branchprop, aggregationType.value)
    } else {
        changelog.value = await getChangelog(props.orgprop, props.release1prop, props.release2prop, aggregationType.value)
    }
}

getAggregatedChangelog()
watch(props, async() => {
    await getAggregatedChangelog()
})

const selectedSeverity : Ref<string> = ref('ALL')
const severityTypes = [
    { label: 'ALL', value: 'ALL' },
    { label: 'BREAKING CHANGES', value: 'BREAKING CHANGES' },
    { label: 'Bug Fixes', value: 'Bug Fixes' },
    { label: 'Features', value: 'Features' },
    { label: 'Performance Improvements', value: 'Performance Improvements' },
    { label: 'Reverts', value: 'Reverts' },
    { label: 'Code Refactoring', value: 'Code Refactoring' },
    { label: 'Builds', value: 'Builds' },
    { label: 'Tests', value: 'Tests' },
    { label: 'Chores', value: 'Chores' },
    { label: 'Continuous Integration', value: 'Continuous Integration' },
    { label: 'Styles', value: 'Styles' },
    { label: 'Others', value: 'Others' },
]
</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
</style>