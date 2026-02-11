<template>
    <div class="sbom-changes">
        <div v-if="hasChanges">
            <div class="summary-tags">
                <n-tag type="success" size="small">{{ netAddedArtifacts.length }} Added</n-tag>
                <n-tag type="error" size="small" class="tag-spacing">{{ netRemovedArtifacts.length }} Removed</n-tag>
                <n-tag v-if="redistributedArtifacts.length > 0" type="info" size="small" class="tag-spacing">{{ redistributedArtifacts.length }} Redistributed</n-tag>
                <n-tag v-if="transientArtifacts.length > 0" type="default" size="small" class="tag-spacing">{{ transientArtifacts.length }} Transient</n-tag>
            </div>

            <!-- Net Added Artifacts -->
            <div v-if="netAddedArtifacts.length > 0">
                <h5 class="sbom-added">Added ({{ netAddedArtifacts.length }})</h5>
                <ul>
                    <li v-for="artifact in netAddedArtifacts" :key="`added-${artifact.purl}`">
                        <span class="artifact-row">
                            <n-tag type="success" size="small" :bordered="false">+</n-tag>
                            <strong>{{ artifact.name }}</strong>
                            <span class="version-label">@ {{ artifact.version }}</span>
                            <code class="purl">{{ artifact.purl }}</code>
                        </span>
                        <div v-if="showAttribution && artifact.addedIn.length > 0" class="attribution">
                            <span class="attribution-context">Added in <template v-for="(attr, i) in artifact.addedIn" :key="attr.releaseUuid"><span v-if="i > 0">, </span><router-link :to="{ name: 'ReleaseView', params: { uuid: attr.releaseUuid } }" class="release-link">{{ attr.componentName }}@{{ attr.releaseVersion }}</router-link><span v-if="attr.branchName" class="branch-hint"> ({{ attr.branchName }})</span></template></span>
                        </div>
                    </li>
                </ul>
            </div>
            
            <!-- Net Removed Artifacts -->
            <div v-if="netRemovedArtifacts.length > 0">
                <h5 class="sbom-removed">Removed ({{ netRemovedArtifacts.length }})</h5>
                <ul>
                    <li v-for="artifact in netRemovedArtifacts" :key="`removed-${artifact.purl}`">
                        <span class="artifact-row">
                            <n-tag type="error" size="small" :bordered="false">−</n-tag>
                            <strong>{{ artifact.name }}</strong>
                            <span class="version-label">@ {{ artifact.version }}</span>
                            <code class="purl">{{ artifact.purl }}</code>
                        </span>
                        <div v-if="showAttribution && artifact.removedIn.length > 0" class="attribution">
                            <span class="attribution-context">Removed in <template v-for="(attr, i) in artifact.removedIn" :key="attr.releaseUuid"><span v-if="i > 0">, </span><router-link :to="{ name: 'ReleaseView', params: { uuid: attr.releaseUuid } }" class="release-link">{{ attr.componentName }}@{{ attr.releaseVersion }}</router-link><span v-if="attr.branchName" class="branch-hint"> ({{ attr.branchName }})</span></template></span>
                        </div>
                    </li>
                </ul>
            </div>
            
            <!-- Redistributed (moved between components) -->
            <div v-if="redistributedArtifacts.length > 0">
                <h5 class="sbom-redistributed collapsible" @click="showRedistributed = !showRedistributed">
                    <span class="collapse-icon">{{ showRedistributed ? '▾' : '▸' }}</span>
                    Redistributed ({{ redistributedArtifacts.length }})
                </h5>
                <ul v-show="showRedistributed">
                    <li v-for="artifact in redistributedArtifacts" :key="`redist-${artifact.purl}`">
                        <span class="artifact-row">
                            <n-tag type="info" size="small" :bordered="false">↔</n-tag>
                            <strong>{{ artifact.name }}</strong>
                            <span class="version-label">@ {{ artifact.version }}</span>
                            <code class="purl">{{ artifact.purl }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-if="artifact.addedIn.length > 0" class="attribution-context">Added in <template v-for="(attr, i) in artifact.addedIn" :key="attr.releaseUuid"><span v-if="i > 0">, </span><router-link :to="{ name: 'ReleaseView', params: { uuid: attr.releaseUuid } }" class="release-link">{{ attr.componentName }}@{{ attr.releaseVersion }}</router-link><span v-if="attr.branchName" class="branch-hint"> ({{ attr.branchName }})</span></template></span>
                            <span v-if="artifact.addedIn.length > 0 && artifact.removedIn.length > 0" class="attribution-context"> · </span>
                            <span v-if="artifact.removedIn.length > 0" class="attribution-context">Removed in <template v-for="(attr, i) in artifact.removedIn" :key="attr.releaseUuid"><span v-if="i > 0">, </span><router-link :to="{ name: 'ReleaseView', params: { uuid: attr.releaseUuid } }" class="release-link">{{ attr.componentName }}@{{ attr.releaseVersion }}</router-link><span v-if="attr.branchName" class="branch-hint"> ({{ attr.branchName }})</span></template></span>
                        </div>
                    </li>
                </ul>
            </div>
            
            <!-- Transient (appeared and removed within same component) -->
            <div v-if="transientArtifacts.length > 0">
                <h5 class="sbom-transient collapsible" @click="showTransient = !showTransient">
                    <span class="collapse-icon">{{ showTransient ? '▾' : '▸' }}</span>
                    Transient ({{ transientArtifacts.length }})
                </h5>
                <ul v-show="showTransient">
                    <li v-for="artifact in transientArtifacts" :key="`transient-${artifact.purl}`">
                        <span class="artifact-row">
                            <n-tag type="default" size="small" :bordered="false">~</n-tag>
                            <strong>{{ artifact.name }}</strong>
                            <span class="version-label">@ {{ artifact.version }}</span>
                            <code class="purl">{{ artifact.purl }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-if="artifact.addedIn.length > 0" class="attribution-context">Added in <template v-for="(attr, i) in artifact.addedIn" :key="attr.releaseUuid"><span v-if="i > 0">, </span><router-link :to="{ name: 'ReleaseView', params: { uuid: attr.releaseUuid } }" class="release-link">{{ attr.componentName }}@{{ attr.releaseVersion }}</router-link><span v-if="attr.branchName" class="branch-hint"> ({{ attr.branchName }})</span></template></span>
                            <span v-if="artifact.addedIn.length > 0 && artifact.removedIn.length > 0" class="attribution-context"> · </span>
                            <span v-if="artifact.removedIn.length > 0" class="attribution-context">Removed in <template v-for="(attr, i) in artifact.removedIn" :key="attr.releaseUuid"><span v-if="i > 0">, </span><router-link :to="{ name: 'ReleaseView', params: { uuid: attr.releaseUuid } }" class="release-link">{{ attr.componentName }}@{{ attr.releaseVersion }}</router-link><span v-if="attr.branchName" class="branch-hint"> ({{ attr.branchName }})</span></template></span>
                        </div>
                    </li>
                </ul>
            </div>
        </div>
        <div v-else class="empty-state">
            <div class="summary-tags">
                <n-tag type="success" size="small">0 Added</n-tag>
                <n-tag type="error" size="small" class="tag-spacing">0 Removed</n-tag>
                <n-tag type="default" size="small" class="tag-spacing">Net: 0</n-tag>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NTag } from 'naive-ui'
import type { SbomChangesWithAttribution } from '../../types/changelog-attribution'
import type { ReleaseSbomChanges } from '../../types/changelog-sealed'

interface Props {
    sbomChanges?: SbomChangesWithAttribution | ReleaseSbomChanges
    showAttribution?: boolean
}

const props = withDefaults(defineProps<Props>(), {
    showAttribution: true
})

const showTransient = ref(false)
const showRedistributed = ref(false)

// Type guard to check if it's SbomChangesWithAttribution (AGGREGATED mode)
const isAggregatedMode = computed(() => {
    return props.sbomChanges && 'artifacts' in props.sbomChanges
})

// Type guard to check if it's ReleaseSbomChanges (NONE mode)
const isNoneMode = computed(() => {
    return props.sbomChanges && 'addedArtifacts' in props.sbomChanges
})

const netAddedArtifacts = computed(() => {
    if (!props.sbomChanges) return []
    
    if (isAggregatedMode.value) {
        const aggregated = props.sbomChanges as SbomChangesWithAttribution
        return aggregated.artifacts.filter(a => a.isNetAdded)
    } else if (isNoneMode.value) {
        const none = props.sbomChanges as ReleaseSbomChanges
        return none.addedArtifacts.map(a => ({
            purl: a.purl,
            name: a.name || a.purl,
            version: a.version || '',
            isNetAdded: true,
            isNetRemoved: false,
            isStillPresent: false,
            addedIn: [] as any[],
            removedIn: [] as any[]
        }))
    }
    return []
})

const netRemovedArtifacts = computed(() => {
    if (!props.sbomChanges) return []
    
    if (isAggregatedMode.value) {
        const aggregated = props.sbomChanges as SbomChangesWithAttribution
        return aggregated.artifacts.filter(a => a.isNetRemoved)
    } else if (isNoneMode.value) {
        const none = props.sbomChanges as ReleaseSbomChanges
        return none.removedArtifacts.map(a => ({
            purl: a.purl,
            name: a.name || a.purl,
            version: a.version || '',
            isNetAdded: false,
            isNetRemoved: true,
            isStillPresent: false,
            addedIn: [] as any[],
            removedIn: [] as any[]
        }))
    }
    return []
})

// Helper: check if addedIn and removedIn reference different components
function isCrossComponent(artifact: { addedIn: { componentUuid: string }[], removedIn: { componentUuid: string }[] }): boolean {
    const addedComps = new Set(artifact.addedIn.map(a => a.componentUuid))
    const removedComps = new Set(artifact.removedIn.map(a => a.componentUuid))
    // Cross-component if any added component differs from any removed component
    for (const c of addedComps) {
        if (!removedComps.has(c)) return true
    }
    for (const c of removedComps) {
        if (!addedComps.has(c)) return true
    }
    return false
}

// Redistributed: moved between different components (cross-component)
const redistributedArtifacts = computed(() => {
    if (!props.sbomChanges) return []
    
    if (isAggregatedMode.value) {
        const aggregated = props.sbomChanges as SbomChangesWithAttribution
        return aggregated.artifacts.filter(a => 
            !a.isNetAdded && !a.isNetRemoved && 
            a.addedIn.length > 0 && a.removedIn.length > 0 &&
            isCrossComponent(a)
        )
    }
    return []
})

// Transient: appeared and removed within same component(s)
const transientArtifacts = computed(() => {
    if (!props.sbomChanges) return []
    
    if (isAggregatedMode.value) {
        const aggregated = props.sbomChanges as SbomChangesWithAttribution
        return aggregated.artifacts.filter(a => 
            !a.isNetAdded && !a.isNetRemoved && 
            (a.addedIn.length > 0 || a.removedIn.length > 0) &&
            !isCrossComponent(a)
        )
    }
    // NONE mode doesn't track transient artifacts
    return []
})

const hasChanges = computed(() => {
    return netAddedArtifacts.value.length > 0 ||
           netRemovedArtifacts.value.length > 0 ||
           redistributedArtifacts.value.length > 0 ||
           transientArtifacts.value.length > 0
})
</script>

<style scoped lang="scss">
.sbom-changes {
    .summary-tags {
        margin-bottom: 10px;
    }
    
    .tag-spacing {
        margin-left: 8px;
    }
    
    .sbom-redistributed {
        color: #2080f0;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .sbom-transient {
        color: #909090;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .collapsible {
        cursor: pointer;
        user-select: none;
        
        &:hover {
            opacity: 0.8;
        }
    }
    
    .collapse-icon {
        display: inline-block;
        width: 14px;
        font-size: 0.85em;
    }
    
    .sbom-added {
        color: #18a058;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .sbom-removed {
        color: #d03050;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .empty-state {
        padding: 20px;
        text-align: center;
        color: #999;
    }
    
    ul {
        list-style: none;
        padding-left: 0;
        margin-top: 4px;
    }
    
    li {
        padding: 4px 0;
        border-bottom: 1px solid #f0f0f0;
        
        &:last-child {
            border-bottom: none;
        }
    }
    
    .artifact-row {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
    }
    
    .version-label {
        color: #888;
        font-size: 0.9em;
    }
    
    code.purl {
        background: #f5f5f5;
        padding: 1px 5px;
        border-radius: 3px;
        font-size: 0.82em;
        color: #666;
    }
    
    .attribution {
        padding-left: 16px;
        font-size: 0.85em;
        line-height: 1.4;
    }
    
    .attribution-context {
        color: #888;
        font-style: italic;
    }
    
    .release-link {
        color: #2080f0;
        text-decoration: none;
        font-style: italic;
        
        &:hover {
            text-decoration: underline;
        }
    }
    
    .branch-hint {
        color: #aaa;
        font-size: 0.9em;
    }
}
</style>
