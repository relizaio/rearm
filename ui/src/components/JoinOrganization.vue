<template>
    <div class="joinOrganization">
    </div>
</template>
<script lang="ts">
export default {
    name: 'JoinOrganization'
}
</script>
<script lang="ts" setup>
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import { useRoute } from 'vue-router'
import Swal from 'sweetalert2'
import commonFunctions from '@/utils/commonFunctions'
import { brandingState, applySignUpBackground } from '@/utils/branding'

applySignUpBackground()

const route = useRoute()

let isError = false
let errMsg = ''

try {
    const response = await graphqlClient.mutate({
        mutation: gql`
            mutation joinUserToOrganization($secret: String!) {
                joinUserToOrganization(secret: $secret) {
                    uuid
                }
            }`,
        variables: {
            'secret': route.params.secret.toString()
        },
        fetchPolicy: 'no-cache'
    })
    if (!response.data || !response.data.joinUserToOrganization || !response.data.joinUserToOrganization.uuid) isError = true
} catch (error: any) {
    console.log(error)
    errMsg = error.message
    isError = true
}

let swalResp: any = ''

if (!isError) {
    swalResp = await Swal.fire({
        title: 'Success!',
        text: 'You have successfully joined the organization!',
        icon: 'info',
        confirmButtonText: 'OK'
    })
} else {
    const errText = errMsg ? commonFunctions.parseGraphQLError(errMsg) : `Error joining organization, please retry or contact support at ${brandingState.supportEmail}`
    swalResp = await Swal.fire({
        title: 'Error!',
        text: errText,
        icon: 'warning',
        confirmButtonText: 'OK'
    })
}
if (swalResp) window.location.href = '/'

                        


</script>
