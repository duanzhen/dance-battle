<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2">
        <Trophy class="w-4 h-4" /> 排名赛配置
      </h3>

      <div class="space-y-6">
        <!-- 规模/晋级/分圈:参赛人数与分圈仅初始化时可编辑;晋级名额在未结束前可改 -->
        <div class="grid grid-cols-3 gap-4">
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-1">参赛人数</div>
            <template v-if="currentMode === ConfigMode.INIT">
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.scale"
                  :min="2"
                  :max="512"
                  class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                  @input="handleUpdate"
                />
                <div class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-neutral-600">人</div>
              </div>
            </template>
            <template v-else>
              <div class="text-2xl font-bold text-white font-mono">{{ config.scale }}</div>
            </template>
          </div>

          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
            <template v-if="canEditAdvance">
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.advanceCount"
                  :min="1"
                  :max="Math.max(1, (config.scale || 1) - 1)"
                  class="w-full bg-black border border-amber-500/40 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                  @input="handleUpdate"
                />
                <div class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-neutral-600">人</div>
              </div>
            </template>
            <template v-else>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
            </template>
            <p v-if="currentMode === ConfigMode.INIT && (config.circles || 1) > 1" class="text-[10px] text-neutral-500 mt-2">
              分 {{ config.circles }} 圈并行,每圈晋级 {{ Math.floor(config.advanceCount / config.circles) }} 人(请确保名额可被圈数整除)
            </p>
          </div>

          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-1">分圈</div>
            <template v-if="currentMode === ConfigMode.INIT">
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.circles"
                  :min="1"
                  :max="config.scale"
                  class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                  @input="handleUpdate"
                />
                <div class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-neutral-600">圈</div>
              </div>
            </template>
            <template v-else>
              <div class="text-2xl font-bold text-white font-mono">{{ config.circles || 1 }}</div>
            </template>
          </div>
        </div>

        <!-- 结果公布:始终可编辑 -->
        <div class="bg-black/50 border border-neutral-800 rounded-lg p-4 space-y-3">
          <div class="text-xs text-neutral-500">结果公布</div>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="text-xs text-neutral-600 mb-1 block">公布模式</label>
              <select
                v-model="config.publishMode"
                class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @change="handleUpdate"
              >
                <option value="AUTO">实时公布(边打边显示)</option>
                <option value="MANUAL">手动公布(导播台确认后公布)</option>
                <option value="BATCH">全部完成后一次性公布</option>
              </select>
            </div>
            <div v-if="config.publishMode === 'BATCH'">
              <label class="text-xs text-neutral-600 mb-1 block">公布范围</label>
              <select
                v-model="config.publishScope"
                class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @change="handleUpdate"
              >
                <option value="ALL">公布全部排名</option>
                <option value="TOP_N">只公布前 {{ config.advanceCount }} 名晋级名单</option>
              </select>
            </div>
          </div>
          <label class="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              v-model="config.showScore"
              class="accent-amber-500 w-4 h-4"
              @change="handleUpdate"
            />
            <span class="text-sm text-neutral-300">排名展示显示分数(赛段级配置)</span>
          </label>
          <div v-if="config.showScore">
            <label class="text-xs text-neutral-600 mb-1 block">分数显示方式</label>
            <select
              v-model="config.scoreDisplay"
              class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
              @change="handleUpdate"
            >
              <option value="TOTAL">只显示总分</option>
              <option value="DETAIL">总分 + 维度分</option>
            </select>
          </div>
        </div>

        <!-- 评分维度:赛段开始前可编辑,开始后只读展示 -->
        <div>
          <div class="flex items-center justify-between mb-2">
            <label class="text-xs text-neutral-500">评分维度(自定义)</label>
            <button v-if="canEditDimensions" @click="addDim" class="text-xs text-amber-500 hover:text-amber-400">+ 添加维度</button>
            <span v-else class="text-[10px] text-neutral-600">赛段已开始,维度锁定不可编辑</span>
          </div>
          <div v-if="scoring.dimensions.length === 0" class="text-xs text-neutral-600">未配置维度(将按单一总分处理)</div>
          <div class="grid grid-cols-12 gap-2 mb-1 text-[10px] text-neutral-500 font-bold uppercase tracking-wider">
            <span class="col-span-3">标识 (Key)</span>
            <span class="col-span-3">名称</span>
            <span class="col-span-2">权重</span>
            <span class="col-span-2">满分</span>
            <span class="col-span-2 text-right">操作</span>
          </div>
          <div v-for="(d, i) in scoring.dimensions" :key="i" class="grid grid-cols-12 gap-2 mb-2 items-center">
            <template v-if="canEditDimensions">
              <input
                v-model="d.key"
                placeholder="标识(TECH)"
                class="col-span-3 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <input
                v-model="d.name"
                placeholder="名称(技术)"
                class="col-span-3 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <input
                v-model.number="d.weight"
                type="number"
                step="0.1"
                min="0"
                placeholder="权重"
                class="col-span-2 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <input
                v-model.number="d.maxScore"
                type="number"
                min="1"
                max="1000"
                placeholder="满分"
                class="col-span-2 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <button @click="removeDim(i)" class="col-span-2 text-xs text-red-400 hover:text-red-300">删除</button>
            </template>
            <template v-else>
              <span class="col-span-3 text-sm text-neutral-300 font-mono">{{ d.key }}</span>
              <span class="col-span-3 text-sm text-neutral-200">{{ d.name }}</span>
              <span class="col-span-2 text-sm text-neutral-400 font-mono">{{ d.weight }}</span>
              <span class="col-span-2 text-sm text-neutral-400 font-mono">{{ d.maxScore }}</span>
              <span class="col-span-2"></span>
            </template>
          </div>
          <div v-if="scoring.dimensions.length > 0" class="grid grid-cols-2 gap-4 mt-3">
            <div>
              <label class="text-xs text-neutral-600 mb-1 block">裁判间汇总</label>
              <select
                v-if="canEditDimensions"
                v-model="scoring.refereeAggregateRule"
                class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @change="handleUpdate"
              >
                <option value="AVG">平均</option>
                <option value="SUM">求和</option>
                <option value="TRIMMED_MEAN">去极值平均</option>
              </select>
              <div v-else class="bg-black border border-neutral-800 rounded p-2 text-sm text-neutral-300">
                {{ aggregateLabel(scoring.refereeAggregateRule) }}
              </div>
            </div>
            <div>
              <label class="text-xs text-neutral-600 mb-1 block">维度间汇总</label>
              <select
                v-if="canEditDimensions"
                v-model="scoring.aggregateRule"
                class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @change="handleUpdate"
              >
                <option value="SUM">求和</option>
                <option value="AVG">平均</option>
                <option value="WEIGHTED">加权(按维度权重)</option>
                <option value="TRIMMED_MEAN">去极值平均</option>
              </select>
              <div v-else class="bg-black border border-neutral-800 rounded p-2 text-sm text-neutral-300">
                {{ aggregateLabel(scoring.aggregateRule) }}
              </div>
            </div>
          </div>
        </div>

        <!-- 预览 -->
        <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
          <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
          <div class="text-sm text-neutral-300">{{ config.scale }} 名选手参加排名赛</div>
          <div class="text-sm text-neutral-300 mt-1">按 {{ scoring.dimensions.length || '单一' }} 个维度打分,前 {{ config.advanceCount }} 名晋级</div>
          <div class="text-xs text-neutral-500 mt-2">晋级率:{{ ((config.advanceCount / config.scale) * 100).toFixed(1) }}%</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Trophy } from 'lucide-vue-next';
import { StageData, ConfigMode } from './types';

// Props
const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
}>();

// Emits
const emit = defineEmits<{
  update: [stage: StageData];
}>();

// 本地赛段数据
const localStage = ref<StageData>({ ...props.stage });

// 当前模式
const currentMode = computed(() => props.mode || ConfigMode.NORMAL);

// 配置对象
const config = ref<any>({
  mode: 'RANK',
  scale: 32,
  advanceCount: 16,
  circles: 1,
  publishMode: 'AUTO',
  publishScope: 'ALL',
  showScore: true,
  scoreDisplay: 'TOTAL'
});

// 打分配置(独立维护,序列化时并入 ruleConfig.scoring)
const scoring = ref<any>({
  type: 'MULTI_DIM',
  matchMode: 'RANKING',
  refereeAggregateRule: 'AVG',
  aggregateRule: 'SUM',
  trimRatio: 0.1,
  dimensions: [
    { key: 'TECH', name: '技术', weight: 0.4, maxScore: 100 },
    { key: 'SHOW', name: '表现力', weight: 0.3, maxScore: 100 },
    { key: 'CREAT', name: '创意', weight: 0.3, maxScore: 100 }
  ]
});

// 晋级名额:未结束/未取消前可编辑(未开始 PENDING、进行中 GAMING、规划 DRAFT 均可)
const canEditAdvance = computed(() => {
  const s = props.stage?.status;
  return s !== 'SETTLED' && s !== 'DISCARD';
});

// 评分维度:赛段开始前(DRAFT/PENDING)可编辑,开始后锁定只读
const canEditDimensions = computed(() => {
  const s = props.stage?.status;
  return !s || s === 'DRAFT' || s === 'PENDING';
});

const aggregateLabels: Record<string, string> = {
  SUM: '求和',
  AVG: '平均',
  WEIGHTED: '加权',
  TRIMMED_MEAN: '去极值平均'
};
const aggregateLabel = (v?: string) => aggregateLabels[v || ''] || v || '—';

// 解析配置:兼容旧数据(顶层扁平结构或嵌套 scoring)
const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
      if (parsed.scoring) {
        scoring.value = {
          ...scoring.value,
          ...parsed.scoring,
          dimensions: parsed.scoring.dimensions?.length ? parsed.scoring.dimensions : scoring.value.dimensions
        };
      }
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

// 序列化配置
const serializeConfig = () => {
  return JSON.stringify({
    ...config.value,
    scoring: scoring.value
  });
};

const addDim = () => {
  scoring.value.dimensions.push({ key: '', name: '', weight: 0, maxScore: 100 });
  handleUpdate();
};

const removeDim = (i: number) => {
  scoring.value.dimensions.splice(i, 1);
  handleUpdate();
};

// 更新处理
const handleUpdate = () => {
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.scale;
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

input[type='number']::-webkit-inner-spin-button,
input[type='number']::-webkit-outer-spin-button {
  opacity: 1;
}
</style>
