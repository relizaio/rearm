<template>
    <div class="sbom-changes">
        <div v-if="hasChanges">
            <div v-if="showUpdated && processedChanges.updated.length > 0">
                <h4 class="sbom-updated">🔄 Updated Components ({{ processedChanges.updated.length }})</h4>
                <ul>
                    <li v-for="comp in processedChanges.updated" :key="`updated-${comp.purl}`">
                        <strong>{{ comp.purl }}</strong>: {{ comp.oldVersion }} → {{ comp.newVersion }}
                    </li>
                </ul>
            </div>
            
            <div v-if="processedChanges.added.length > 0">
                <h4 class="sbom-added">✓ Added Components ({{ processedChanges.added.length }})</h4>
                <ul>
                    <li v-for="comp in processedChanges.added" :key="`added-${comp.purl}`">
                        <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
                    </li>
                </ul>
            </div>
            
            <div v-if="processedChanges.removed.length > 0">
                <h4 class="sbom-removed">✗ Removed Components ({{ processedChanges.removed.length }})</h4>
                <ul>
                    <li v-for="comp in processedChanges.removed" :key="`removed-${comp.purl}`">
                        <strong>{{ comp.purl }}</strong> @ {{ comp.version }}
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

interface SbomComponent {
    purl: string
    version: string
}

interface SbomChanges {
    added?: SbomComponent[]
    removed?: SbomComponent[]
}

interface ProcessedChanges {
    added: SbomComponent[]
    removed: SbomComponent[]
    updated: Array<{
        purl: string
        oldVersion: string
        newVersion: string
    }>
}

interface Props {
    sbomChanges?: SbomChanges
    showUpdated?: boolean
}

const props = withDefaults(defineProps<Props>(), {
    showUpdated: false
})

const getPackageName = (purl: string): string => {
    const atIndex = purl.lastIndexOf('@')
    return atIndex > 0 ? purl.substring(0, atIndex) : purl
}

const processedChanges = computed<ProcessedChanges>(() => {
    if (!props.sbomChanges) {
        return { added: [], removed: [], updated: [] }
    }

    const added = props.sbomChanges.added || []
    const removed = props.sbomChanges.removed || []
    
    if (!props.showUpdated) {
        return { added, removed, updated: [] }
    }
    
    const addedMap = new Map<string, SbomComponent>()
    const removedMap = new Map<string, SbomComponent>()
    
    added.forEach(comp => {
        const pkgName = getPackageName(comp.purl)
        addedMap.set(pkgName, comp)
    })
    
    removed.forEach(comp => {
        const pkgName = getPackageName(comp.purl)
        removedMap.set(pkgName, comp)
    })
    
    const updated: Array<{ purl: string; oldVersion: string; newVersion: string }> = []
    const trulyAdded: SbomComponent[] = []
    const trulyRemoved: SbomComponent[] = []
    
    addedMap.forEach((newComp, pkgName) => {
        if (removedMap.has(pkgName)) {
            const oldComp = removedMap.get(pkgName)!
            updated.push({
                purl: pkgName,
                oldVersion: oldComp.version,
                newVersion: newComp.version
            })
        } else {
            trulyAdded.push(newComp)
        }
    })
    
    removedMap.forEach((comp, pkgName) => {
        if (!addedMap.has(pkgName)) {
            trulyRemoved.push(comp)
        }
    })
    
    return {
        added: trulyAdded,
        removed: trulyRemoved,
        updated: updated
    }
})

const hasChanges = computed(() => {
    return processedChanges.value.added.length > 0 ||
           processedChanges.value.removed.length > 0 ||
           processedChanges.value.updated.length > 0
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
    }
    
    code {
        background: #f5f5f5;
        padding: 2px 6px;
        border-radius: 3px;
        font-size: 0.9em;
    }
}
</style>
