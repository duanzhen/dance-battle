<template>
  <div class="space-y-1">
    <label v-if="label" class="label">{{ label }}</label>
    <input type="text" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" :placeholder="placeholder" class="input-base" />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string;
  label?: string;
  placeholder?: string;
}>();

defineEmits<{
  'update:modelValue': [value: string];
}>();
</script>

<style scoped>
.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}

.input-base {
  @apply w-full bg-neutral-950 border border-neutral-800 rounded px-2 py-2 text-xs text-white focus:border-amber-500 focus:outline-none transition-colors;
}
</style>
