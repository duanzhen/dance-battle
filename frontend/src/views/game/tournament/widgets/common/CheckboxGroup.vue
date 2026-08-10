<template>
  <div class="space-y-2">
    <label v-if="label" class="label">{{ label }}</label>
    <div class="flex flex-wrap gap-x-4 gap-y-2">
      <label v-for="option in options" :key="option.key" class="flex items-center gap-2 text-xs text-neutral-400 cursor-pointer">
        <input type="checkbox" :checked="modelValue?.[option.key]" @change="$emit('update:item', option.key, $event.target.checked)" />
        {{ option.label }}
      </label>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue?: Record<string, boolean>;
  label?: string;
  options: Array<{ key: string; label: string }>;
}>();

defineEmits<{
  'update:item': [key: string, value: boolean];
}>();
</script>

<style scoped>
.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}
</style>
