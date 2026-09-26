import js from '@eslint/js'
import eslintConfigPrettier from 'eslint-config-prettier'
import vue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

const sharedGlobals = {
  clearInterval: 'readonly',
  clearTimeout: 'readonly',
  console: 'readonly',
  setInterval: 'readonly',
  setTimeout: 'readonly',
}

const browserGlobals = {
  ...sharedGlobals,
  AbortController: 'readonly',
  DOMException: 'readonly',
  Event: 'readonly',
  HTMLCanvasElement: 'readonly',
  HTMLInputElement: 'readonly',
  HTMLSelectElement: 'readonly',
  HTMLTextAreaElement: 'readonly',
  MouseEvent: 'readonly',
  PetAction: 'readonly',
  PetState: 'readonly',
  PointerEvent: 'readonly',
  TextDecoder: 'readonly',
  URL: 'readonly',
  URLSearchParams: 'readonly',
  document: 'readonly',
  fetch: 'readonly',
  performance: 'readonly',
  requestAnimationFrame: 'readonly',
  cancelAnimationFrame: 'readonly',
  window: 'readonly',
}

const nodeGlobals = {
  ...sharedGlobals,
  Buffer: 'readonly',
  NodeJS: 'readonly',
  process: 'readonly',
}

export default [
  {
    ignores: ['dist/**', 'dist-electron/**', 'node_modules/**', 'release/**', 'coverage/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs['flat/recommended'],
  eslintConfigPrettier,
  {
    files: ['src/**/*.{ts,vue}', 'electron/**/*.ts'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
      },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
        },
      ],
      'vue/multi-word-component-names': 'off',
      'vue/no-v-html': 'off',
    },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: {
      globals: browserGlobals,
    },
  },
  {
    files: ['electron/**/*.ts', '*.config.ts', '*.config.js'],
    languageOptions: {
      globals: nodeGlobals,
    },
  },
]
