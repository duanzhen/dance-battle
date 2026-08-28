<template>
  <div class="space-y-1">
    <label v-if="label" class="label">{{ label }}</label>
    <div class="flex items-center gap-2">
      <div class="relative w-8 h-8 shrink-0">
        <!-- 透明状态:棋盘底纹 + 禁止图标,点击仍可打开取色器重新选色 -->
        <div v-if="isTransparent" class="transparent-checker absolute inset-0 rounded border border-neutral-700"></div>
        <input
          type="color"
          :value="isTransparent ? '#000000' : modelValue || '#000000'"
          @input="$emit('update:modelValue', $event.target.value)"
          class="w-8 h-8 rounded cursor-pointer border-0 relative"
          :class="isTransparent ? 'opacity-0' : ''"
        />
        <Ban v-if="isTransparent" class="absolute inset-0 m-auto w-4 h-4 text-neutral-400 pointer-events-none" />
      </div>
      <input
        type="text"
        :value="isTransparent ? '透明' : modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
        class="flex-1 input-base font-mono"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Ban } from 'lucide-vue-next';

const props = defineProps<{
  modelValue: string;
  label?: string;
}>();

defineEmits<{
  'update:modelValue': [value: string];
}>();

// 空字符串 = 透明(由 BracketWidget 的「设为透明」写入)
const isTransparent = computed(() => props.modelValue === '');
</script>

<style scoped>
.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}

.transparent-checker {
  background: repeating-conic-gradient(#3f3f46 0% 25%, #171717 0% 50%) 50% / 8px 8px;
}

.input-base {
  @apply w-full bg-neutral-950 border border-neutral-800 rounded px-2 py-2 text-xs text-white focus:border-amber-500 focus:outline-none transition-colors;
}
</style>
