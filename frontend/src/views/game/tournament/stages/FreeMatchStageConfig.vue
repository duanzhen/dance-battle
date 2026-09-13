<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2">
        <Swords class="w-4 h-4" /> 自由对抗配置
      </h3>

      <div class="space-y-6">
        <!-- 关键数字 -->
        <div class="grid grid-cols-2 gap-4">
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-1">参赛选手</div>
            <div class="text-2xl font-bold text-white font-mono">{{ competitorCount }}</div>
            <div class="text-xs text-neutral-600 mt-1">人(名单由中间态确定)</div>
          </div>
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-1">已记录对战</div>
            <div class="text-2xl font-bold text-amber-500 font-mono">{{ matchCount }}</div>
            <div class="text-xs text-neutral-600 mt-1">场(导播台手动添加)</div>
          </div>
        </div>

        <!-- 比赛格式:创建时可选,创建后锁定 -->
        <div v-if="currentMode === ConfigMode.CREATE" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
          <label class="text-xs text-neutral-500 mb-2 block">比赛格式</label>
          <select v-model="config.format" class="cfg-select w-full" @change="handleUpdate">
            <option value="BO1">BO1(单局决胜)</option>
            <option value="BO3">BO3(三局两胜)</option>
            <option value="BO5">BO5(五局三胜)</option>
          </select>
        </div>
        <div v-else class="bg-black/50 border border-neutral-800 rounded-lg p-4">
          <div class="text-xs text-neutral-500 mb-2">比赛格式</div>
          <div class="text-lg font-medium text-white">{{ config.format }}</div>
        </div>

        <!-- 赛制说明 -->
        <div class="bg-black/50 border border-neutral-800 rounded-lg p-4 space-y-2">
          <div class="text-xs text-neutral-500 mb-1">赛制说明</div>
          <p class="text-sm text-neutral-300 leading-relaxed">
            纯手动赛制:对手由线下抽签 / 指认确定,系统不自动生成对阵。
          </p>
          <ul class="text-[12px] text-neutral-400 leading-relaxed list-disc pl-5 space-y-1">
            <li>比赛中在<b class="text-neutral-200">手机导播台</b>点「添加对战」,选择两名选手生成一场比赛</li>
            <li>导播台点「开始」后,裁判按胜负平正常判罚,系统记录每场的对战选手与结果</li>
            <li>赛段结束前,导播台点「选择晋级」手动勾选晋级者,<b class="text-neutral-200">人数不限</b></li>
            <li>未勾选的选手标记为淘汰;勾选顺序即下一赛段的种子顺序</li>
          </ul>
        </div>

        <StageExitConfig v-if="currentMode !== ConfigMode.CREATE" :stage="localStage" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Swords } from 'lucide-vue-next';
import { listCompetitor } from '@/api/game/competitor';
import { listMatch } from '@/api/game/match';
import { FreeMatchConfig, StageData, ConfigMode } from './types';
import StageExitConfig from './StageExitConfig.vue';

const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
}>();

const emit = defineEmits<{
  update: [stage: StageData];
}>();

const localStage = ref<StageData>({ ...props.stage });
const currentMode = computed(() => props.mode || ConfigMode.INIT);
const config = ref<FreeMatchConfig>({ format: 'BO1' });

/** 参赛人数(本赛段名单)与已记录对战数,只读展示 */
const competitorCount = ref(0);
const matchCount = ref(0);

const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { format: parsed.format || 'BO1' };
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

const loadCounts = async () => {
  const sid = props.stage?.id;
  if (sid == null) return;
  try {
    const cr: any = await listCompetitor({ stageId: sid, pageNum: 1, pageSize: 999 } as any);
    competitorCount.value = (cr?.data?.data || cr?.data || []).length;
  } catch {
    competitorCount.value = 0;
  }
  try {
    const mr: any = await listMatch({ stageId: sid, pageNum: 1, pageSize: 999 } as any);
    matchCount.value = (mr?.data?.data || mr?.data || []).length;
  } catch {
    matchCount.value = 0;
  }
};

const handleUpdate = () => {
  const prev: any = (() => {
    try {
      return JSON.parse(props.stage.ruleConfig || '{}');
    } catch {
      return {};
    }
  })();
  localStage.value.ruleConfig = JSON.stringify({
    ...prev,
    mode: 'FREE_MATCH',
    format: config.value.format,
    scoring: prev.scoring || { type: 'WIN_LOSS_DRAW', matchMode: 'STANDARD' }
  });
  localStage.value.teamCountStart = competitorCount.value;
  // 晋级人数不限:0 表示不做限制,由导播台手动勾选
  localStage.value.teamCountEnd = 0;
  emit('update', localStage.value);
};

watch(
  () => props.stage,
  () => {
    localStage.value = { ...props.stage };
    parseConfig();
    loadCounts();
  },
  { deep: true }
);

onMounted(() => {
  parseConfig();
  loadCounts();
});
</script>

<style scoped>
.stage-config {
  max-width: 900px;
  margin: 0 auto;
}
.cfg-select {
  background: #000;
  border: 1px solid #404040;
  border-radius: 6px;
  padding: 8px 10px;
  color: #fff;
  font-size: 13px;
}
</style>
