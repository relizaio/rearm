<template>
    <div class="vcsRepoView">
        <h1 v-if="vcsRepo">Vcs Repository - {{ vcsRepo.name }}</h1>
        <div v-if="vcsRepo">URI: {{ vcsRepo.uri }}</div>

        <h5>Branches using this repository:</h5>
    </div>
</template>

<script>

export default {
    name: 'VcsRepository',
    components: {
    },
    data () {
        return {
            vcsRepoUuid: this.$route.params.uuid
        }
    },
    props: {

    },
    methods: {

    },
    created () {
        this.$store.dispatch('fetchVcsRepo', this.vcsRepoUuid)
    },
    computed: {
        vcsRepo () {
            return this.$store.getters.vcsRepoById(this.vcsRepoUuid)
        }
    }
}

</script>

<style scoped lang="scss">

</style>
