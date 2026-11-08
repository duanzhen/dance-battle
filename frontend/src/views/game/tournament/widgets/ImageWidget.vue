<template>
  <div class="w-full h-full">
    <!-- 空素材时透明占位(模板背景图未替换前不遮挡场景);有素材后按原样渲染 -->
    <div
      v-if="mode !== 'edit'"
      class="w-full h-full overflow-hidden select-none"
      :class="displayImages.length ? 'bg-neutral-700/30' : ''"
    >
      <div v-if="displayImages.length" class="relative w-full h-full">
        <img
          v-for="(url, index) in displayImages"
          :key="url + '-' + index"
          :src="url"
          alt="widget content"
          class="absolute inset-0 w-full h-full object-fill block transition-opacity duration-700 ease-in-out"
          :class="index === activeIndex ? 'opacity-100' : 'opacity-0'"
          draggable="false"
        />
      </div>
    </div>

    <div v-else class="image-editor space-y-4 px-2 py-4">
      <section>
        <span class="section-title">图片属性</span>
        <label class="mb-3 flex cursor-pointer items-center justify-between rounded-lg border border-neutral-800 bg-neutral-900/60 px-3 py-2.5">
          <span class="text-xs font-medium text-neutral-300">图片轮播</span>
          <input
            type="checkbox"
            :checked="carouselEnabled === true"
            class="h-4 w-4 accent-amber-500"
            @change="handleCarouselToggle(($event.target as HTMLInputElement).checked)"
          />
        </label>
        <p class="mb-3 text-[10px] text-neutral-600">开启后可上传多张图片；单图模式下继续使用原素材图片。</p>

        <MultiImageUpload
          v-if="carouselEnabled === true"
          :model-value="carouselImagesValue"
          label="轮播图片"
          @update:model-value="$emit('update:carouselImages', $event)"
        />
        <AssetUpload
          v-else
          label="素材图片"
          :model-value="src"
          :is-image="true"
          accept="image/*"
          :show-url-input="true"
          @update:model-value="$emit('update:src', $event)"
        />

        <div v-if="carouselEnabled === true" class="mt-3">
          <label class="mb-1 block text-[10px] font-bold uppercase tracking-wider text-neutral-500">轮播间隔（秒）</label>
          <input
            type="number"
            min="1"
            max="3600"
            step="1"
            :value="carouselIntervalValue"
            class="w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-xs text-white focus:border-amber-500 focus:outline-none"
            @change="handleIntervalChange(($event.target as HTMLInputElement).value)"
          />
          <p class="mt-1 text-[10px] text-neutral-600">范围 1～3600 秒；切换图片时使用 0.7 秒淡入淡出效果。</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import AssetUpload from './common/AssetUpload.vue';
import MultiImageUpload from './common/MultiImageUpload.vue';

const props = defineProps<{
  src: string;
  mode?: 'view' | 'edit';
  carouselEnabled?: boolean;
  carouselImages?: string[];
  carouselInterval?: number;
}>();

const emit = defineEmits<{
  'update:src': [value: string];
  'update:carouselEnabled': [value: boolean];
  'update:carouselImages': [value: string[]];
  'update:carouselInterval': [value: number];
}>();

const activeIndex = ref(0);
let carouselTimer: ReturnType<typeof setInterval> | null = null;

const carouselImagesValue = computed(() =>
  Array.isArray(props.carouselImages) ? props.carouselImages.filter(Boolean) : []
);
const carouselIntervalValue = computed(() => {
  const value = Number(props.carouselInterval);
  return Number.isFinite(value) ? Math.min(3600, Math.max(1, Math.round(value))) : 5;
});
const displayImages = computed(() => {
  if (!props.carouselEnabled) return props.src ? [props.src] : [];
  return carouselImagesValue.value.length
    ? carouselImagesValue.value
    : props.src ? [props.src] : [];
});

const stopCarousel = () => {
  if (carouselTimer) {
    clearInterval(carouselTimer);
    carouselTimer = null;
  }
};

const startCarousel = () => {
  stopCarousel();
  activeIndex.value = 0;
  if (props.mode === 'edit' || !props.carouselEnabled || displayImages.value.length < 2) return;
  carouselTimer = setInterval(() => {
    activeIndex.value = (activeIndex.value + 1) % displayImages.value.length;
  }, carouselIntervalValue.value * 1000);
};

const handleCarouselToggle = (enabled: boolean) => {
  emit('update:carouselEnabled', enabled);
  if (enabled && !carouselImagesValue.value.length && props.src) {
    emit('update:carouselImages', [props.src]);
  }
};

const handleIntervalChange = (value: string) => {
  const seconds = Number(value);
  emit('update:carouselInterval', Number.isFinite(seconds)
    ? Math.min(3600, Math.max(1, Math.round(seconds)))
    : 5);
};

watch(
  () => [props.mode, props.carouselEnabled, props.carouselImages, props.carouselInterval],
  startCarousel,
  { deep: true }
);
onMounted(startCarousel);
onUnmounted(stopCarousel);
</script>

<style scoped>
.section-title {
  @apply text-xs font-bold text-neutral-400 block mb-3;
}
</style>
