<template>
    <div class="footer-container">
        <n-divider />
        <div class="footer-content" v-if="brandingState.isDefault">
            <div class="footer-text" data-testid="footer-product">
                {{ footerVersionText }}
            </div>
            <div class="footer-text" data-testid="footer-copyright">
                © Reliza Incorporated, 2019-2026
            </div>
            <div class="footer-links">
                <a href="https://docs.rearmhq.com" rel="noopener noreferrer" target="_blank" class="footer-link" data-testid="footer-docs">Documentation</a>
                <span class="footer-separator">•</span>
                <a href="mailto:info@reliza.io" class="footer-link" data-testid="footer-support">Support</a>
            </div>
        </div>
        <div class="footer-content" v-else>
            <div class="footer-text" data-testid="footer-product">
                {{ brandedProductText }}
            </div>
            <div class="footer-text" data-testid="footer-copyright">
                Built on ReARM™ · © 2019–2026 Reliza Incorporated.
            </div>
            <div class="footer-links" data-testid="footer-links">
                <a :href="brandingState.documentationUrl" rel="noopener noreferrer" target="_blank" class="footer-link" data-testid="footer-docs">Documentation</a>
                <span class="footer-separator">•</span>
                <a :href="'mailto:' + brandingState.supportEmail" class="footer-link" data-testid="footer-support">Support</a>
                <span class="footer-separator">•</span>
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <a :href="brandingState.legalUrl" rel="noopener noreferrer" target="_blank" class="footer-link"
                            :title="legalTooltipText()" data-testid="footer-legal">Legal</a>
                    </template>
                    <span data-testid="footer-legal-tooltip">{{ legalTooltipText() }}</span>
                </n-tooltip>
            </div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'AppFooter'
}
</script>
<script lang="ts" setup>
// The app footer (task WL-3, moved out of AppWrapper). The default branding keeps the Reliza footer
// exactly as it was; a preset shows its product name, the Built on ReARM line, its links and a Legal
// link whose hover text names who offers the product.
import { Ref, ref, ComputedRef, computed } from 'vue'
import { NDivider, NTooltip } from 'naive-ui'
import { useStore } from 'vuex'
import { brandingState, legalTooltipText } from '@/utils/branding'

const store = useStore()
const myUser: ComputedRef<any> = computed((): any => store.getters.myuser)
// Replaced with the product version by nginx_start.sh at container start; the comparison string is
// split so that replacement leaves it alone.
const rearmProductVersion: Ref<string> = ref('54ab89bb-f1f1-459c-afbf-e4d78655b298')

function withVersion (product: string): string {
    const rearmProductVersionComparisonString: string = '54ab89bb-f1f1-459c' + '-afbf-e4d78655b298'
    if (rearmProductVersion.value !== rearmProductVersionComparisonString) {
        return `${product} v${rearmProductVersion.value}`
    } else {
        return product
    }
}

const footerVersionText: ComputedRef<string> = computed((): string =>
    withVersion(myUser.value?.installationType === 'OSS' ? 'ReARM CE' : 'ReARM Pro'))
const brandedProductText: ComputedRef<string> = computed((): string => withVersion(brandingState.titleText))
</script>

<style scoped lang="scss">
.footer-container {
    margin-top: auto;
    padding: 0 20px;
}

.footer-content {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 16px 0;
    color: #666;
    font-size: 14px;

    @media (max-width: 768px) {
        flex-direction: column;
        gap: 12px;
        text-align: center;
    }
}

.footer-text {
    font-weight: 500;
}

.footer-links {
    display: flex;
    align-items: center;
    gap: 8px;
}

a {
    color: inherit;
    text-decoration: none;
}
</style>
