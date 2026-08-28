<template>
  <div class="w-full h-full">
    <!-- 空素材时透明占位(模板背景图未替换前不遮挡场景);有素材后按原样渲染 -->
    <div
      v-if="mode !== 'edit'"
      class="w-full h-full overflow-hidden select-none"
      :class="src ? 'bg-neutral-700/30' : ''"
    >
      <img
        v-if="src"
        :src="src"
        alt="widget content"
        class="w-full h-full object-fill block"
        draggable="false"
      />
    </div>

    <div v-else class="image-editor space-y-4 px-2 py-4">
      <section>
        <span class="section-title">图片属性</span>
        <AssetUpload
          label="素材图片"
          :model-value="src"
          :is-image="true"
          accept="image/*"
          :show-url-input="true"
          @update:model-value="$emit('update:src', $event)"
        />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import AssetUpload from './common/AssetUpload.vue';

defineProps<{
  src: string;
  mode?: 'view' | 'edit';
}>();

defineEmits<{
  'update:src': [value: string];
}>();
</script>

<style scoped>
.section-title {
  @apply text-xs font-bold text-neutral-400 block mb-3;
}
</style>
