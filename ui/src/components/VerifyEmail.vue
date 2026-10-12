<template>
    <div class="verifyEmail">
    </div>
</template>
<script lang="ts">
export default {
    name: 'VerifyEmail'
}
</script>
<script lang="ts" setup>
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import { useRoute } from 'vue-router'
import Swal from 'sweetalert2'
import { brandingState, applySignUpBackground } from '@/utils/branding'

applySignUpBackground()

const route = useRoute()

let isError = false

try {
    const response = await graphqlClient.mutate({
        mutation: gql`
            mutation verifyEmail($secret: String!) {
                verifyEmail(secret: $secret) {
                    uuid
                }
            }`,
        variables: {
            'secret': route.params.secret.toString()
        },
        fetchPolicy: 'no-cache'
    })
    if (!response.data || !response.data.verifyEmail || !response.data.verifyEmail.uuid) isError = true
} catch (error: any) {
    console.log(error)
    isError = true
}

let swalResp: any = ''

if (!isError) {
    swalResp = await Swal.fire({
        title: 'Success!',
        text: 'You have successfully verified your email!',
        icon: 'info',
        confirmButtonText: 'OK'
    })
} else {
    swalResp = await Swal.fire({
        title: 'Error!',
        text: `Error verifying email, please retry or contact support at ${brandingState.supportEmail}`,
        icon: 'warning',
        confirmButtonText: 'OK'
    })
}
if (swalResp) window.location.href = '/'

                        


</script>
