<template>
  <div class="space-y-1">
    <label v-if="label" class="label">{{ label }}</label>
    <select :value="modelValue" @change="$emit('update:modelValue', $event.target.value)" class="input-base">
      <option v-for="option in options" :key="option.value" :value="option.value">
        {{ option.label }}
      </option>
    </select>
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

.input-base {
  @apply w-full bg-neutral-950 border border-neutral-800 rounded px-2 py-2 text-xs text-white focus:border-amber-500 focus:outline-none transition-colors;
}
</style>
