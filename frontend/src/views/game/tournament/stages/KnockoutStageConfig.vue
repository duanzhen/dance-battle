<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Trophy class="w-4 h-4" /> 淘汰赛配置</h3>

      <div class="space-y-6">
        <!-- STARTED 模式: 创建+初始配置只读展示 -->
        <template v-if="currentMode === ConfigMode.STARTED">
          <!-- 模板信息 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">淘汰赛模板</div>
            <div class="text-lg font-medium text-amber-500">{{ getTemplateLabel(config.template) }}</div>
            <div class="text-xs text-neutral-500 mt-1">{{ getTemplateDetail(config.template) }}</div>
          </div>

          <!-- 选手数量 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">参赛选手数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.teamsCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">名选手</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级选手数</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">名晋级</div>
            </div>
          </div>

          <!-- 比赛格式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛格式</div>
            <div class="text-lg font-medium text-white">{{ config.format }}</div>
          </div>

          <!-- 首轮配对方式(单轮模式) -->
          <div v-if="config.singleRound" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">首轮配对</div>
            <div class="text-sm font-medium text-white">{{ pairingLabel }}</div>
          </div>

          <!-- 结果公布模式:赛段开始后仍可修改(自动/手动/导播台判定) -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <label class="text-xs text-neutral-500 mb-2 block">结果公布模式</label>
            <select
              v-model="config.publishMode"
              class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none"
              @change="handleUpdate"
            >
              <option value="AUTO">自动公布(裁判判完即公布)</option>
              <option value="MANUAL">手动公布(导播台确认后公布)</option>
              <option value="DIRECTOR">导播台判定(裁判不判罚,导播台选胜负)</option>
            </select>
            <p class="text-[10px] text-neutral-600 mt-1.5">赛段开始后仍可修改,新场次按新模式生效</p>
          </div>

          <!-- 赛制预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">{{ config.teamsCount }} 名选手 → 单败淘汰 → {{ config.advanceCount }} 名选手晋级</div>
            <div class="text-xs text-neutral-500 mt-1">共需 {{ Math.ceil(Math.log2(config.teamsCount)) }} 轮比赛</div>
          </div>
        </template>

        <!-- CREATE/INIT 模式: 可编辑 -->
        <template v-else>
          <!-- 创建配置(INIT 模式只读展示:创建后锁定) -->
          <div v-if="currentMode === ConfigMode.INIT" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-3">创建配置（锁定）</div>
            <div class="grid grid-cols-3 gap-4">
              <div>
                <div class="text-xs text-neutral-500 mb-1">淘汰赛模板</div>
                <div class="text-lg font-medium text-amber-500">{{ getTemplateLabel(config.template) }}</div>
                <div class="text-[10px] text-neutral-600">{{ getTemplateDetail(config.template) }}</div>
              </div>
              <div>
                <div class="text-xs text-neutral-500 mb-1">参赛选手数</div>
                <div class="text-2xl font-bold text-white font-mono">{{ config.teamsCount }}</div>
              </div>
              <div>
                <div class="text-xs text-neutral-500 mb-1">晋级选手数</div>
                <div class="text-2xl font-bold text-white font-mono">{{ config.advanceCount }}</div>
              </div>
            </div>
            <!-- 首轮配对方式:创建配置的一部分,INIT 只读展示 -->
            <div v-if="config.singleRound" class="mt-3 pt-3 border-t border-neutral-800 flex items-center justify-between">
              <div>
                <div class="text-xs text-neutral-500">首轮配对方式</div>
                <div class="text-[10px] text-neutral-600 mt-0.5">{{ pairingHint }}</div>
              </div>
              <div class="text-sm font-bold text-amber-500">{{ pairingLabel }}</div>
            </div>
          </div>

          <!-- 模板选择 (创建配置,创建后锁定) -->
          <div v-if="currentMode === ConfigMode.CREATE" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-3">创建配置</div>
            <label class="text-xs text-neutral-500 mb-2 block">淘汰赛模板</label>
            <div class="grid grid-cols-4 gap-3">
              <div
                v-for="template in templates"
                :key="template.value"
                class="template-option"
                :class="{ selected: config.template === template.value }"
                @click="selectTemplate(template.value)"
              >
                <div class="template-label">{{ template.label }}</div>
                <div class="template-detail">{{ template.detail }}</div>
              </div>
            </div>
          </div>

          <!-- 选手数量 (创建配置,创建后锁定) -->
          <div v-if="currentMode === ConfigMode.CREATE" class="grid grid-cols-2 gap-6">
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">参赛选手数</label>
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.teamsCount"
                  :disabled="config.template !== KnockoutTemplate.CUSTOM"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  @input="handleUpdate"
                />
                <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">名选手</div>
              </div>
            </div>
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">晋级选手数</label>
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.advanceCount"
                  :disabled="config.template !== KnockoutTemplate.CUSTOM"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  @input="handleUpdate"
                />
                <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">名晋级</div>
              </div>
            </div>
          </div>

          <!-- 首轮配对方式 (创建配置,创建后锁定) -->
          <div v-if="currentMode === ConfigMode.CREATE" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="flex items-center justify-between mb-2">
              <span class="text-xs text-neutral-500">首轮配对方式</span>
              <span class="text-[10px] text-neutral-600">仅单轮模式(每轮一赛段)下生效</span>
            </div>
            <div class="grid grid-cols-2 gap-2">
              <button
                type="button"
                @click="selectPairingMode('SEQUENTIAL')"
                class="rounded-lg border px-3 py-2 text-left transition-all"
                :class="
                  (config.pairingMode || defaultPairing) === 'SEQUENTIAL'
                    ? 'border-amber-500 bg-amber-500/10'
                    : 'border-neutral-700 bg-neutral-900/40 hover:border-neutral-500'
                "
              >
                <div
                  class="text-xs font-bold"
                  :class="(config.pairingMode || defaultPairing) === 'SEQUENTIAL' ? 'text-amber-400' : 'text-neutral-200'"
                >
                  顺序配对
                </div>
                <div class="text-[10px] text-neutral-500 mt-0.5">1-2、3-4…按名单顺序相邻</div>
              </button>
              <button
                type="button"
                @click="selectPairingMode('SEED')"
                class="rounded-lg border px-3 py-2 text-left transition-all"
                :class="
                  (config.pairingMode || defaultPairing) === 'SEED'
                    ? 'border-amber-500 bg-amber-500/10'
                    : 'border-neutral-700 bg-neutral-900/40 hover:border-neutral-500'
                "
              >
                <div class="text-xs font-bold" :class="(config.pairingMode || defaultPairing) === 'SEED' ? 'text-amber-400' : 'text-neutral-200'">
                  种子交叉(头尾)
                </div>
                <div class="text-[10px] text-neutral-500 mt-0.5">1-N、2-(N-1)…强种子分散</div>
              </button>
            </div>
            <p class="text-[10px] text-neutral-600 mt-1.5">{{ pairingHint }}</p>
          </div>

          <!-- INIT 模式: 创建配置只读 + 初始配置可编辑 -->
          <template v-if="currentMode !== ConfigMode.CREATE">
            <!-- 基本信息 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">比赛格式</label>
              <select
                v-model="config.format"
                class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                @change="handleUpdate"
              >
                <option value="BO1">BO1 (单局决胜)</option>
                <option value="BO3">BO3 (三局两胜)</option>
                <option value="BO5">BO5 (五局三胜)</option>
              </select>
            </div>

            <!-- 晋级规则 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">晋级规则</label>
              <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
                <!-- 季军仅在半决赛(4 队)出现;GUEST/种子相关配置在中间态处理 -->
                <label v-if="config.teamsCount === 4" class="flex items-center justify-between">
                  <span class="text-sm text-neutral-300">季军赛</span>
                  <input type="checkbox" v-model="config.playThirdPlace" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
                </label>
                <label class="flex items-center justify-between">
                  <span class="text-sm text-neutral-300">单轮模式(每轮一赛段,胜者全晋级)</span>
                  <input type="checkbox" v-model="config.singleRound" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
                </label>
                <div>
                  <span class="text-sm text-neutral-300 block mb-2">结果公布模式</span>
                  <select
                    v-model="config.publishMode"
                    class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none"
                    @change="handleUpdate"
                  >
                    <option value="AUTO">自动公布(裁判判完即公布)</option>
                    <option value="MANUAL">手动公布(导播台确认后公布)</option>
                    <option value="DIRECTOR">导播台判定(裁判不判罚,导播台选胜负)</option>
                  </select>
                </div>
              </div>
            </div>

            <!-- 打分与转场配置 -->
            <ScoringTransitionConfig
              :scoring="config.scoring"
              @update:scoring="
                (v) => {
                  config.scoring = v;
                  handleUpdate();
                }
              "
            />

            <!-- 预览 -->
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
              <div class="text-sm text-neutral-300">{{ config.teamsCount }} 名选手 → 单败淘汰 → {{ config.advanceCount }} 名选手晋级</div>
              <div class="text-xs text-neutral-500 mt-1">共需 {{ Math.ceil(Math.log2(config.teamsCount)) }} 轮比赛</div>
            </div>

            <!-- 出口去向:胜者/败者送到哪个下游赛段(不配则默认胜者进下一赛段) -->
            <StageExitConfig :stage="localStage" />
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Trophy } from 'lucide-vue-next';
import { KnockoutTemplate, KnockoutConfig, StageData, ConfigMode } from './types';
import ScoringTransitionConfig from './ScoringTransitionConfig.vue';
import StageExitConfig from './StageExitConfig.vue';

// Props
const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
  /** 上一赛段类型(用于默认配对方式:海选/排名后默认 SEED) */
  prevStageMode?: string;
}>();

// Emits
const emit = defineEmits<{
  update: [stage: StageData];
}>();

// 本地赛段数据
const localStage = ref<StageData>({ ...props.stage });

// 当前模式
const currentMode = computed(() => props.mode || ConfigMode.INIT);

// 配置对象
const config = ref<
  KnockoutConfig & {
    playThirdPlace?: boolean;
    singleRound?: boolean;
    pairingMode?: string;
    publishMode?: string;
    scoring?: any;
    transition?: any;
  }
>({
  template: KnockoutTemplate.ROUND_32,
  format: 'BO3',
  teamsCount: 32,
  advanceCount: 16,
  playThirdPlace: false,
  singleRound: false,
  pairingMode: '',
  publishMode: 'AUTO',
  scoring: {
    type: 'WIN_LOSS_DRAW',
    matchMode: 'STANDARD',
    aggregateRule: 'SUM',
    refereeAggregateRule: 'AVG',
    trimRatio: 0.1,
    dimensions: [],
    outcomeRules: { winScore: 1, drawScore: 0.5, lossScore: 0 }
  },
  transition: {}
});

// 模板列表
const templates = [
  { value: KnockoutTemplate.FINAL, label: '决赛', detail: '2进1' },
  { value: KnockoutTemplate.SEMI_FINAL, label: '半决赛', detail: '4进2' },
  { value: KnockoutTemplate.QUARTER_FINAL, label: '1/4决赛', detail: '8进4' },
  { value: KnockoutTemplate.ROUND_16, label: '16进8', detail: '16名选手' },
  { value: KnockoutTemplate.ROUND_32, label: '32进16', detail: '32名选手' },
  { value: KnockoutTemplate.ROUND_64, label: '64进32', detail: '64名选手' },
  { value: KnockoutTemplate.CUSTOM, label: '自定义', detail: '手动设置' }
];

// 解析配置(兼容包裹式 RuleConfigHolder 与旧扁平结构)
const parseConfig = () => {
  try {
    if (!props.stage.ruleConfig) {
      // 无 ruleConfig:用赛段权威字段兜底
      if (props.stage.teamCountStart) config.value.teamsCount = props.stage.teamCountStart;
      if (props.stage.teamCountEnd) config.value.advanceCount = props.stage.teamCountEnd;
      config.value.template = inferTemplate(config.value.teamsCount, config.value.advanceCount);
      return;
    }
    const parsed = JSON.parse(props.stage.ruleConfig);
    if (parsed.format) config.value.format = parsed.format;
    const ko = parsed.knockout || parsed;
    if (ko.template !== undefined) config.value.template = ko.template;
    if (ko.teamsCount !== undefined) config.value.teamsCount = ko.teamsCount;
    else if (props.stage.teamCountStart) config.value.teamsCount = props.stage.teamCountStart;
    if (ko.advanceCount !== undefined) config.value.advanceCount = ko.advanceCount;
    else if (props.stage.teamCountEnd) config.value.advanceCount = props.stage.teamCountEnd;
    // 老数据 ruleConfig 未写 template 时,按参赛/晋级人数反推模版,避免残留默认「32进16」
    if (ko.template === undefined) {
      config.value.template = inferTemplate(config.value.teamsCount, config.value.advanceCount);
    }
    if (ko.singleRound !== undefined) config.value.singleRound = ko.singleRound;
    config.value.pairingMode = ko.pairingMode !== undefined ? ko.pairingMode : '';
    if (ko.publishMode !== undefined) config.value.publishMode = ko.publishMode;
    if (ko.thirdPlaceMatch !== undefined) config.value.playThirdPlace = ko.thirdPlaceMatch;
    if (parsed.scoring) config.value.scoring = parsed.scoring;
    if (parsed.transition) config.value.transition = parsed.transition;
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

/** 按参赛/晋级人数反推模版(ruleConfig 未显式写 template 时使用) */
const inferTemplate = (teams: number, advance: number): KnockoutTemplate => {
  if (teams === 2 && advance === 1) return KnockoutTemplate.FINAL;
  if (teams === 4 && advance === 2) return KnockoutTemplate.SEMI_FINAL;
  if (teams === 8 && advance === 4) return KnockoutTemplate.QUARTER_FINAL;
  if (teams === 16 && advance === 8) return KnockoutTemplate.ROUND_16;
  if (teams === 32 && advance === 16) return KnockoutTemplate.ROUND_32;
  if (teams === 64 && advance === 32) return KnockoutTemplate.ROUND_64;
  return KnockoutTemplate.CUSTOM;
};

// 序列化为包裹式 RuleConfigHolder(对齐后端)
const serializeConfig = () => {
  return JSON.stringify({
    mode: 'KNOCKOUT',
    format: config.value.format,
    knockout: {
      template: config.value.template,
      teamsCount: config.value.teamsCount,
      advanceCount: config.value.advanceCount,
      singleRound: config.value.singleRound,
      pairingMode: config.value.pairingMode || defaultPairing.value,
      publishMode: config.value.publishMode,
      thirdPlaceMatch: config.value.playThirdPlace
    },
    scoring: config.value.scoring,
    transition: config.value.transition
  });
};

// 默认配对方式:上一赛段为海选/排名时种子交叉(SEED),否则顺序相邻(SEQUENTIAL)
const defaultPairing = computed(() => (props.prevStageMode === 'AUDITION' || props.prevStageMode === 'RANK' ? 'SEED' : 'SEQUENTIAL'));

// 生效配对方式:显式配置优先,未配置用默认
const effectivePairingMode = computed(() => config.value.pairingMode || defaultPairing.value);

const pairingLabel = computed(() => (effectivePairingMode.value === 'SEED' ? '种子交叉(头尾)' : '顺序配对'));

const pairingHint = computed(() => {
  if (props.prevStageMode === 'AUDITION' || props.prevStageMode === 'RANK') {
    return `上一赛段为${props.prevStageMode === 'AUDITION' ? '海选赛' : '排名赛'},默认种子交叉(头尾);按抽签顺序相邻则切换为顺序配对`;
  }
  return '没有前置海选时按名单/抽签顺序默认顺序配对;需要头尾交叉(如 1 对 16)时请选择种子交叉';
});

const selectPairingMode = (mode: 'SEQUENTIAL' | 'SEED') => {
  config.value.pairingMode = mode;
  handleUpdate();
};

// 选择模板
const selectTemplate = (template: KnockoutTemplate) => {
  config.value.template = template;

  // 根据模板自动设置选手数
  const templateMap: Record<KnockoutTemplate, { teams: number; advance: number }> = {
    [KnockoutTemplate.FINAL]: { teams: 2, advance: 1 },
    [KnockoutTemplate.SEMI_FINAL]: { teams: 4, advance: 2 },
    [KnockoutTemplate.QUARTER_FINAL]: { teams: 8, advance: 4 },
    [KnockoutTemplate.ROUND_16]: { teams: 16, advance: 8 },
    [KnockoutTemplate.ROUND_32]: { teams: 32, advance: 16 },
    [KnockoutTemplate.ROUND_64]: { teams: 64, advance: 32 },
    [KnockoutTemplate.CUSTOM]: { teams: config.value.teamsCount, advance: config.value.advanceCount }
  };

  if (template !== KnockoutTemplate.CUSTOM) {
    config.value.teamsCount = templateMap[template].teams;
    config.value.advanceCount = templateMap[template].advance;
  }

  handleUpdate();
};

// 更新处理
const handleUpdate = () => {
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.teamsCount;
  localStage.value.teamCountEnd = config.value.advanceCount;
  emit('update', localStage.value);
};

// 监听 props 变化
watch(
  () => props.stage,
  () => {
    localStage.value = { ...props.stage };
    parseConfig();
  },
  { deep: true }
);

// 获取模板标签
const getTemplateLabel = (template: KnockoutTemplate) => {
  const t = templates.find((item) => item.value === template);
  return t?.label || template;
};

// 获取模板详情
const getTemplateDetail = (template: KnockoutTemplate) => {
  const t = templates.find((item) => item.value === template);
  return t?.detail || '';
};

// 初始化
onMounted(() => {
  parseConfig();
});
</script>

<style scoped>
.stage-config {
  max-width: 900px;
  margin: 0 auto;
}

.template-option {
  background: #0a0a0a;
  border: 2px solid #262626;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
}

.template-option:hover {
  border-color: #404040;
}

.template-option.selected {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.template-label {
  font-weight: 600;
  color: #e5e5e5;
  font-size: 13px;
}

.template-detail {
  font-size: 11px;
  color: #737373;
  margin-top: 2px;
}
</style>
