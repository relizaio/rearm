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

<script lang="ts">
import { Options, Vue } from 'vue-class-component'
import AppWrapper from '@/components/AppWrapper.vue'

@Options({
    components: {
        AppWrapper
    },
})
export default class App extends Vue {
    async setup(/*props : any, { emit } : any*/) {}
}
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
</style>
