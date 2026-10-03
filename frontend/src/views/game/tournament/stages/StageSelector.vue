<template>
  <div class="w-full">
    <label v-if="label" class="text-xs text-neutral-500 mb-2 block">{{ label }}</label>
    <el-select
      :model-value="modelValue ?? null"
      class="w-full"
      placeholder="请选择赛段"
      :disabled="loading || stages.length === 0"
      @change="onChange"
    >
      <el-option
        v-for="s in filteredStages"
        :key="s.id"
        :label="`${s.name} · ${modeLabel(s.stageMode)} · ${statusLabel(s.status)}`"
        :value="s.id"
      />
    </el-select>
    <p v-if="!loading && filteredStages.length === 0" class="text-[10px] text-neutral-600 mt-1">
      {{ stages.length === 0 ? '该赛事暂无赛段' : `该赛事暂无${modeLabel(onlyMode) || '符合条件的'}赛段` }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { listStage } from '@/api/game/stage';

/**
 * 通用赛段选择器:下拉列出当前赛事的所有赛段(名称 · 赛制 · 状态)。
 * tournamentId 优先取 prop,否则从路由 query.id / query.tournamentId 推导(导播台场景)。
 */
const props = defineProps<{
  modelValue: string | number | null;
  label?: string;
  tournamentId?: string | number;
  /** 仅列出指定赛制(stageMode),如 KNOCKOUT */
  onlyMode?: string;
}>();
const emit = defineEmits<{ 'update:modelValue': [v: string | number | null] }>();

const route = useRoute();
const tid = computed(() => props.tournamentId || (route.query.id as string | number) || (route.query.tournamentId as string | number));

const loading = ref(false);
const stages = ref<any[]>([]);
const filteredStages = computed(() => (props.onlyMode ? stages.value.filter((s) => props.onlyMode!.split(',').includes(s.stageMode)) : stages.value));

const load = async () => {
  if (!tid.value) return;
  loading.value = true;
  try {
    const res: any = await listStage({ tournamentId: tid.value } as any);
    stages.value = res?.data?.data || res?.data || [];
  } catch (e) {
    console.warn('StageSelector 加载赛段失败', e);
    stages.value = [];
  } finally {
    loading.value = false;
  }
};

const onChange = (v: string | number | null) => {
  // 保留原始字符串:后端 Long/雪花ID 序列化为 string,Number 化会精度丢失导致回显与查询双双失败
  emit('update:modelValue', v ?? null);
};

const modeMap: Record<string, string> = {
  KNOCKOUT: '淘汰',
  GROUP: '小组',
  AUDITION: '选拔',
  ARENA: '擂台',
  RANK: '排名'
};
const statusMap: Record<string, string> = {
  DRAFT: '规划中',
  GAMING: '进行中',
  SETTLED: '已结束',
  DISCARD: '已取消'
};
const modeLabel = (m?: string) => (m && modeMap[m]) || m || '';
const statusLabel = (s?: string) => (s && statusMap[s]) || s || '';

onMounted(load);
watch(tid, load);
</script>
