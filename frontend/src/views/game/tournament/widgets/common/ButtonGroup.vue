<template>
  <div class="space-y-1">
    <label v-if="label" class="label">{{ label }}</label>
    <div class="flex gap-2">
      <button
        v-for="option in options"
        :key="option.value"
        @click="$emit('update:modelValue', option.value)"
        :class="{
          'bg-amber-500 text-white': modelValue === option.value,
          'bg-neutral-800 text-neutral-400 hover:bg-neutral-700': modelValue !== option.value
        }"
        class="flex-1 py-1.5 text-xs rounded border border-neutral-700 transition-colors"
      >
        {{ option.label }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string;
  label?: string;
  options: Array<{ value: string; label: string }>;
}>();

defineEmits<{
  'update:modelValue': [value: string];
}>();
</script>

<style scoped>
.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}
</style>
