<template>
    <div v-if="task.questionStack?.length" class="dsec">
        <div class="dsec__h">Waiting on</div>
        <div class="qstack">
            <div v-for="(f, i) in task.questionStack" :key="i" class="qstack__row">
                <span class="qstack__depth">{{ i + 1 }}</span>
                <span>{{ roleName(roles, f.askingRole) }} asked {{ roleName(roles, f.answeringRole) || 'nobody yet' }}</span>
                <a v-if="f.questionsRelease" :href="`/release/${f.questionsRelease}`" class="qstack__link">questions</a>
                <span class="qstack__time">{{ ts(f.askedAt) }}</span>
            </div>
        </div>
        <div v-if="!task.questionStack[task.questionStack.length - 1].answeringRole"
             class="qstack__note">
            The board found no role that produces what the newest question is about, so
            it is with the coordinator to name one or escalate.
        </div>

        <!--
            Answering is a round of the QUESTIONS index, not a note: that is what the
            asking agent reads as a pinned input when the task comes back to it. A
            note would be prose it cannot pin, and the loop would ask again.
        -->
        <div v-if="answerable.length" class="qans">
            <div class="dsec__h" style="margin-top: 4px">Answer</div>
            <div v-for="f in answerable" :key="f.id" class="qans__row">
                <div class="qans__id">
                    <span class="qans__tag">{{ f.id }}</span>
                    <span class="qans__title">{{ f.title }}</span>
                </div>
                <n-input v-model:value="answers[f.id]" size="small" type="textarea"
                         :autosize="{ minRows: 1, maxRows: 4 }"
                         :placeholder="`Answer to ${f.id}`"/>
                <n-checkbox v-model:checked="withdrawn[f.id]" size="small">
                    does not apply (say why above)
                </n-checkbox>
            </div>
            <n-input v-model:value="answerAll" size="small" type="textarea"
                     :autosize="{ minRows: 1, maxRows: 4 }"
                     placeholder="Same answer to all of them"
                     style="margin-top: 8px"/>
            <n-space style="margin-top: 8px">
                <n-button size="small" type="primary" :disabled="!canAnswer"
                          @click="emit('answer', answerPayload)">
                    {{ task.hold ? 'Answer and release' : 'Answer' }}
                </n-button>
            </n-space>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The question stack the task is waiting on, and a person's answer to it.
import { computed, ref } from 'vue'
import { NButton, NCheckbox, NInput, NSpace } from 'naive-ui'
import { roleName, ts } from '@/utils/agentTaskFormat'
import { AnswerPayload, answerPayloadOf, answerableQuestions } from '@/utils/agentTaskQuestions'

const props = defineProps<{ task: any, roles?: any[] }>()
const emit = defineEmits<{ (e: 'answer', p: AnswerPayload): void }>()

const answers = ref<Record<string, string>>({})
const withdrawn = ref<Record<string, boolean>>({})
const answerAll = ref('')

const answerable = computed(() => answerableQuestions(props.task))
const answerPayload = computed(() => answerPayloadOf(props.task, answerable.value, answers.value,
    withdrawn.value, answerAll.value))
const canAnswer = computed(() =>
    answerPayload.value.answers.length > 0 || !!answerPayload.value.answerAll)
</script>

<style scoped lang="scss">
@use './taskSections';
</style>
