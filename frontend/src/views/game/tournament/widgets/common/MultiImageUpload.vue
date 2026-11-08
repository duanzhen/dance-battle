<template>
  <div class="space-y-2">
    <label v-if="label" class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider block">{{ label }}</label>

    <div v-if="modelValue.length" class="grid grid-cols-3 gap-2">
      <div
        v-for="(url, index) in modelValue"
        :key="url + '-' + index"
        class="relative aspect-video overflow-hidden rounded border border-neutral-700 bg-black"
      >
        <img :src="url" class="h-full w-full object-cover" />
        <button
          class="absolute right-1 top-1 flex h-5 w-5 items-center justify-center rounded-full bg-black/75 text-xs text-white hover:bg-red-600"
          title="移除图片"
          @click="removeAt(index)"
        >
          ×
        </button>
        <span class="absolute bottom-1 left-1 rounded bg-black/65 px-1 text-[9px] text-white/75">{{ index + 1 }}</span>
      </div>
    </div>
    <div v-else class="rounded-lg border border-dashed border-neutral-700 py-6 text-center text-xs text-neutral-600">
      暂无轮播图片
    </div>

    <div class="flex items-center gap-2">
      <label class="flex-1 cursor-pointer">
        <input ref="fileInputRef" type="file" accept="image/*" multiple class="hidden" @change="handleFileChange" />
        <span class="block rounded-lg border border-neutral-700 bg-neutral-800 py-2 text-center text-xs font-medium text-neutral-300 transition-colors hover:bg-neutral-700">
          {{ uploading ? '上传中...' : '添加图片' }}
        </span>
      </label>
      <button
        v-if="modelValue.length"
        class="shrink-0 rounded-lg border border-neutral-700 px-3 py-2 text-xs text-neutral-400 transition-colors hover:border-red-500/30 hover:text-red-400"
        @click="$emit('update:modelValue', [])"
      >
        清空
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import request from '@/utils/request';

const props = withDefaults(defineProps<{
  modelValue?: string[];
  label?: string;
}>(), {
  modelValue: () => []
});

const emit = defineEmits<{
  'update:modelValue': [value: string[]];
}>();

const fileInputRef = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

const uploadOne = async (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  const res = await request({
    url: '/resource/oss/upload',
    method: 'post',
    data: formData
  });
  if (res?.code === 200 && res.data?.url) {
    return String(res.data.url);
  }
  throw new Error(res?.msg || '上传失败');
};

const handleFileChange = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (!files.length) return;

  uploading.value = true;
  try {
    const results = await Promise.allSettled(files.map(uploadOne));
    const urls = results
      .filter((result): result is PromiseFulfilledResult<string> => result.status === 'fulfilled')
      .map((result) => result.value);
    if (urls.length) {
      emit('update:modelValue', [...props.modelValue, ...urls]);
    }
    if (urls.length < files.length) {
      ElMessage.warning(`${files.length - urls.length} 张图片上传失败`);
    }
  } catch (e: any) {
    notifyError(e, '图片上传失败');
  } finally {
    uploading.value = false;
    if (fileInputRef.value) {
      fileInputRef.value.value = '';
    }
  }
};

const removeAt = (index: number) => {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index));
};
</script>
