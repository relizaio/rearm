<template>
    <router-link to="/" class="navBrand" data-testid="nav-brand">
        <img v-if="brandingState.isDefault" id="relizaLogo" src="/logo_svg_no_tag_3.svg" data-testid="nav-logo" />
        <template v-else>
            <img class="brandLogoCustom" :src="brandingState.logoUrl" :alt="brandingState.titleText"
                data-testid="nav-logo" @load="onLogoLoad" />
            <span v-if="navLogoLayout === 'stacked'" class="brandNavTitle" data-testid="nav-title">{{ brandingState.titleText }}</span>
        </template>
    </router-link>
</template>

<script lang="ts">
export default {
    name: 'NavBrand'
}
</script>
<script lang="ts" setup>
// The nav logo (task WL-3). On the default branding, the Reliza logo exactly as before. On a preset,
// the preset's logo: alone when it is wide (it carries its own wordmark), or as a mark with the product
// name beside it when it is stacked or square, which would be unreadable at the bar's height. Decided
// from the loaded image, so a logo swap in the backend's preset changes the nav without a UI change.
import { brandingState, navLogoLayout, navLogoLayoutFor } from '@/utils/branding'

function onLogoLoad (e: Event) {
    const img = e.target as HTMLImageElement
    navLogoLayout.value = navLogoLayoutFor(img.naturalWidth, img.naturalHeight)
}
</script>

<style scoped lang="scss">
.navBrand {
    display: inline-flex;
    align-items: center;
    // The product name beside a stacked logo is part of the link: no underline.
    text-decoration: none;
}
#relizaLogo {
    width: 130px;
    margin-left: -52px;
}
// 44 px plus 6 px of padding above and below fills the 56 px the Reliza logo gives the bar. Padding, not
// margin: the lockup's baseline is the logo's bottom border edge, as the Reliza logo's is, so the bar keeps
// its height on a preset.
.brandLogoCustom {
    height: 44px;
    width: auto;
    max-width: 180px;
    padding: 6px 0;
    margin-left: 12px;
    vertical-align: middle;
}
.brandNavTitle {
    font-weight: bold;
    font-size: 18px;
    margin-left: 10px;
    color: rgb(25, 25, 25);
    white-space: nowrap;
    vertical-align: middle;
}
</style>
