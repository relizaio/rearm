module.exports = {
    root: true,
    env: {
        node: true
    },
    'extends': [
        'plugin:vue/vue3-essential'
    ],
    plugins: ['@typescript-eslint'],
    rules: {
        'no-console': process.env.NODE_ENV === 'production' ? 'off' : 'off',
        'no-debugger': process.env.NODE_ENV === 'production' ? 'error' : 'off',
        'indent': ["error", 4],
        // "vue/html-indent": ["error", 4],
        "import/no-named-as-default": 0,
        "semi": "off",
        '@typescript-eslint/semi': 'off',
        'vue/no-v-model-argument': 'off',
        'vue/no-setup-props-destructure': 'off'
    },
    parserOptions: {
        parser: '@typescript-eslint/parser'
    }
}
