<template>
    <Suspense>
        <template #default>
            <app-wrapper />
        </template>
        <template #fallback>
            <div>Loading...</div>
        </template>
    </Suspense>
</template>

<script lang="ts" setup>
import AppWrapper from '@/components/AppWrapper.vue'
</script>

<style lang="scss">
.circle {
    border-radius: 50%;
    color: #fff;
    background: grey;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 1.8em;
    width: 1.8em;
}

.n-dialog.n-modal {
    width: 90%;
}

.iconInTooltip {
    height: 25px;
}

.clickable {
    cursor: pointer;
}

.clickable:hover {
    color: cornflowerblue;
}

.hidden {
    display: none;
}
.error {
    color: red;
}
.artifactTooltip {
    min-width: 500px;
    max-width: 1100px;
    div.tooltip-inner {
        min-width: 500px;
        max-width: 800px;
    }
    a {
        color: white;
    }
}
.swal-wide {
    width: 90% !important;
}

.swal2-container {
    z-index: 9999;
}

.n-tooltip a {
  color: #eee !important;
  text-decoration: underline;
}

.n-tooltip a:hover {
  color: #ddd !important; /* slightly lighter on hover */
}
</style>
