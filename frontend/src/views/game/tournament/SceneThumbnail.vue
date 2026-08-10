<template>
  <div
    v-if="thumbnailData"
    class="bg-cover bg-center"
    :style="{
      backgroundImage: `url(${thumbnailData})`,
      width: '100%',
      aspectRatio: width && height ? `${width} / ${height}` : undefined
    }"
  ></div>
</template>

<script setup lang="ts">
// 纯展示组件，不使用任何响应式逻辑，避免建立 store 订阅
defineProps<{
  thumbnailData?: string | null;
  width?: number;
  height?: number;
}>();
</script>
