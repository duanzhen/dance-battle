<template>
  <div class="w-full h-full">
    <!-- 查看模式: 显示文本 -->
    <div v-if="mode !== 'edit'" class="w-full h-full overflow-hidden select-none bg-neutral-700/30">
      <div :style="textStyle" class="w-full h-full flex items-center justify-center">
        {{ text }}
      </div>
    </div>

    <!-- 编辑模式: 显示属性表单 -->
    <div v-else class="text-editor space-y-4 px-2 py-4">
      <section>
        <span class="section-title">文本属性</span>

        <div class="space-y-1">
          <label class="label">内容</label>
          <textarea
            :value="text"
            @input="$emit('update:text', ($event.target as HTMLTextAreaElement).value)"
            class="input-base h-20 resize-none"
            placeholder="请输入文本内容"
          />
        </div>

        <TextInput label="字体大小" :model-value="fontSize" @update:model-value="$emit('update:fontSize', $event)" placeholder="16px" />

        <ColorInput label="文字颜色" :model-value="color" @update:model-value="$emit('update:color', $event)" />

        <SelectInput
          label="字体粗细"
          :model-value="String(fontWeight || 'normal')"
          @update:model-value="$emit('update:fontWeight', $event)"
          :options="[
            { value: 'normal', label: '正常' },
            { value: 'bold', label: '粗体' },
            { value: '100', label: 'Thin' },
            { value: '300', label: 'Light' },
            { value: '500', label: 'Medium' },
            { value: '700', label: 'Bold' },
            { value: '900', label: 'Black' }
          ]"
        />

        <ButtonGroup
          label="对齐方式"
          :model-value="textAlign || 'center'"
          @update:model-value="$emit('update:textAlign', $event)"
          :options="[
            { value: 'left', label: '左对齐' },
            { value: 'center', label: '居中' },
            { value: 'right', label: '右对齐' }
          ]"
        />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import TextInput from './common/TextInput.vue';
import ColorInput from './common/ColorInput.vue';
import SelectInput from './common/SelectInput.vue';
import ButtonGroup from './common/ButtonGroup.vue';

const props = defineProps<{
  text: string;
  fontSize?: string;
  color?: string;
  fontWeight?: string | number;
  textAlign?: 'left' | 'center' | 'right';
  mode?: 'view' | 'edit';
}>();

defineEmits<{
  'update:text': [value: string];
  'update:fontSize': [value: string];
  'update:color': [value: string];
  'update:fontWeight': [value: string | number];
  'update:textAlign': [value: 'left' | 'center' | 'right'];
}>();

// 计算文本样式 (仅在查看模式使用)
const textStyle = computed(() => ({
  fontSize: props.fontSize || '16px',
  color: props.color || '#ffffff',
  fontWeight: props.fontWeight || 'normal',
  textAlign: props.textAlign || 'center',
  padding: '8px',
  wordBreak: 'break-word' as const,
  overflowWrap: 'break-word' as const
}));
</script>

<style scoped>
.section-title {
  @apply text-xs font-bold text-neutral-400 block mb-3;
}

.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}

.input-base {
  @apply w-full bg-neutral-950 border border-neutral-800 rounded px-2 py-2 text-xs text-white focus:border-amber-500 focus:outline-none transition-colors;
}
</style>
