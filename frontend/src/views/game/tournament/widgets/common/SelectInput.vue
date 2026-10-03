<template>
  <div class="space-y-1">
    <label v-if="label" class="label">{{ label }}</label>
    <el-select
      :model-value="modelValue"
      class="w-full"
      @change="(value: string) => $emit('update:modelValue', value)"
    >
      <el-option v-for="option in options" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
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
