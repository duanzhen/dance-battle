<template>
  <div class="space-y-2">
    <label v-if="label" class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider block">{{ label }}</label>

    <div v-if="currentUrl" class="relative rounded overflow-hidden bg-black mb-2" :class="previewClass">
      <img v-if="isImage" :src="currentUrl" class="w-full h-full object-cover" />
      <video v-else-if="isVideo" :src="currentUrl" class="w-full h-full object-cover" controls />
      <div v-else class="flex items-center justify-center h-16 text-[10px] text-neutral-500 break-all px-2">{{ currentUrl }}</div>
    </div>

    <div class="flex items-center gap-2">
      <label class="flex-1 cursor-pointer">
        <input
          type="file"
          :accept="accept"
          class="hidden"
          @change="handleFileChange"
          ref="fileInputRef"
        />
        <span
          class="block text-center py-2 text-xs font-medium rounded-lg transition-colors border bg-neutral-800 hover:bg-neutral-700 text-neutral-300 border-neutral-700"
        >
          {{ uploading ? '上传中...' : '选择文件' }}
        </span>
      </label>
      <button
        v-if="currentUrl"
        @click="handleClear"
        class="px-3 py-2 text-xs text-neutral-400 hover:text-red-400 rounded-lg border border-neutral-700 hover:border-red-500/30 transition-colors shrink-0"
      >
        清除
      </button>
    </div>

    <!-- <input
      v-if="showUrlInput"
      :value="currentUrl"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      class="w-full bg-black border border-neutral-700 rounded p-2 text-xs text-white focus:border-amber-500 focus:outline-none placeholder-neutral-600"
      :placeholder="urlPlaceholder"
    /> -->
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import request from '@/utils/request';
import { ElMessage } from 'element-plus';

const props = withDefaults(
  defineProps<{
    modelValue: string;
    label?: string;
    accept?: string;
    isImage?: boolean;
    isVideo?: boolean;
    previewClass?: string;
    showUrlInput?: boolean;
    urlPlaceholder?: string;
  }>(),
  {
    accept: 'image/*,video/*',
    isImage: true,
    isVideo: false,
    showUrlInput: false,
    previewClass: 'h-24',
    urlPlaceholder: 'https://...'
  }
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const fileInputRef = ref<HTMLInputElement | null>(null);
const uploading = ref(false);
const currentUrl = computed(() => props.modelValue || '');

const handleFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement;
  if (!input.files || input.files.length === 0) return;

  const file = input.files[0];
  const formData = new FormData();
  formData.append('file', file);

  uploading.value = true;
  try {
    const res = await request({
      url: '/resource/oss/upload',
      method: 'post',
      data: formData
    });
    if (res.code === 200 && res.data?.url) {
      emit('update:modelValue', res.data.url);
    } else {
      ElMessage.error(res?.msg || '上传失败');
    }
  } catch (e: any) {
    ElMessage.error('上传失败');
  } finally {
    uploading.value = false;
    if (fileInputRef.value) {
      fileInputRef.value.value = '';
    }
  }
};

const handleClear = () => {
  emit('update:modelValue', '');
};
</script>
