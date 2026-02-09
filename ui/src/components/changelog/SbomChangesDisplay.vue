<template>
    <div class="sbom-changes">
        <div v-if="hasChanges">
            <!-- Net Added Artifacts -->
            <div v-if="netAddedArtifacts.length > 0">
                <h4 class="sbom-added">✓ Net Added ({{ netAddedArtifacts.length }})</h4>
                <ul>
                    <li v-for="artifact in netAddedArtifacts" :key="`added-${artifact.purl}`">
                        <strong>{{ artifact.name }}</strong> @ {{ artifact.version }}
                        <code class="purl">{{ artifact.purl }}</code>
                        
                        <div v-if="showAttribution && artifact.addedIn.length > 0" class="attribution">
                            <span class="attribution-label">Added in:</span>
                            <n-tag v-for="attr in artifact.addedIn" :key="attr.releaseUuid" size="small" class="attr-tag">
                                {{ attr.componentName }} @ {{ attr.releaseVersion }}
                                <span v-if="attr.branchName"> ({{ attr.branchName }})</span>
                            </n-tag>
                        </div>
                    </li>
                </ul>
            </div>
            
            <!-- Net Removed Artifacts -->
            <div v-if="netRemovedArtifacts.length > 0">
                <h4 class="sbom-removed">✗ Net Removed ({{ netRemovedArtifacts.length }})</h4>
                <ul>
                    <li v-for="artifact in netRemovedArtifacts" :key="`removed-${artifact.purl}`">
                        <strong>{{ artifact.name }}</strong> @ {{ artifact.version }}
                        <code class="purl">{{ artifact.purl }}</code>
                        
                        <div v-if="showAttribution && artifact.removedIn.length > 0" class="attribution">
                            <span class="attribution-label">Removed in:</span>
                            <n-tag v-for="attr in artifact.removedIn" :key="attr.releaseUuid" size="small" class="attr-tag">
                                {{ attr.componentName }} @ {{ attr.releaseVersion }}
                                <span v-if="attr.branchName"> ({{ attr.branchName }})</span>
                            </n-tag>
                        </div>
                    </li>
                </ul>
            </div>
            
            <!-- Still Present (Version Changed) -->
            <div v-if="stillPresentArtifacts.length > 0">
                <h4 class="sbom-updated">🔄 Version Changed ({{ stillPresentArtifacts.length }})</h4>
                <ul>
                    <li v-for="artifact in stillPresentArtifacts" :key="`present-${artifact.purl}`">
                        <strong>{{ artifact.name }}</strong> @ {{ artifact.version }}
                        <code class="purl">{{ artifact.purl }}</code>
                        
                        <div v-if="showAttribution" class="attribution">
                            <div v-if="artifact.addedIn.length > 0">
                                <span class="attribution-label">Added in:</span>
                                <n-tag v-for="attr in artifact.addedIn" :key="attr.releaseUuid" size="small" class="attr-tag">
                                    {{ attr.componentName }} @ {{ attr.releaseVersion }}
                                </n-tag>
                            </div>
                            <div v-if="artifact.removedIn.length > 0">
                                <span class="attribution-label">Removed in:</span>
                                <n-tag v-for="attr in artifact.removedIn" :key="attr.releaseUuid" size="small" class="attr-tag">
                                    {{ attr.componentName }} @ {{ attr.releaseVersion }}
                                </n-tag>
                            </div>
                        </div>
                    </li>
                </ul>
            </div>
        </div>
        <div v-else class="empty-state">
            No SBOM changes detected
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
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
        // Convert string[] to artifact-like objects for display
        return none.addedArtifacts.map(purl => ({
            purl,
            name: purl.split('/').pop()?.split('@')[0] || purl,
            version: purl.split('@')[1] || '',
            isNetAdded: true,
            isNetRemoved: false,
            isStillPresent: false,
            addedIn: [],
            removedIn: []
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
        // Convert string[] to artifact-like objects for display
        return none.removedArtifacts.map(purl => ({
            purl,
            name: purl.split('/').pop()?.split('@')[0] || purl,
            version: purl.split('@')[1] || '',
            isNetAdded: false,
            isNetRemoved: true,
            isStillPresent: false,
            addedIn: [],
            removedIn: []
        }))
    }
    return []
})

const stillPresentArtifacts = computed(() => {
    if (!props.sbomChanges) return []
    
    if (isAggregatedMode.value) {
        const aggregated = props.sbomChanges as SbomChangesWithAttribution
        return aggregated.artifacts.filter(a => a.isStillPresent && !a.isNetAdded && !a.isNetRemoved)
    }
    // NONE mode doesn't track "still present" artifacts
    return []
})

const hasChanges = computed(() => {
    return netAddedArtifacts.value.length > 0 ||
           netRemovedArtifacts.value.length > 0 ||
           stillPresentArtifacts.value.length > 0
})
</script>

<style scoped lang="scss">
.sbom-changes {
    .sbom-updated {
        color: #f0a020;
        margin-top: 10px;
    }
    
    .sbom-added {
        color: #18a058;
        margin-top: 10px;
    }
    
    .sbom-removed {
        color: #d03050;
        margin-top: 10px;
    }
    
    .empty-state {
        padding: 10px;
        color: #999;
        font-style: italic;
    }
    
    ul {
        margin-top: 8px;
        list-style: none;
        padding-left: 0;
    }
    
    li {
        margin-bottom: 12px;
        padding: 8px;
        background: #fafafa;
        border-radius: 4px;
    }
    
    code.purl {
        background: #f5f5f5;
        padding: 2px 6px;
        border-radius: 3px;
        font-size: 0.85em;
        color: #666;
        margin-left: 8px;
    }
    
    .attribution {
        margin-top: 6px;
        padding-left: 16px;
        font-size: 0.9em;
    }
    
    .attribution-label {
        color: #666;
        font-size: 0.85em;
        margin-right: 6px;
    }
    
    .attr-tag {
        margin-right: 4px;
        margin-bottom: 4px;
    }
}
</style>
