<template>
  <div class="space-y-6">
    <!-- 打分配置 -->
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-4 flex items-center gap-2">
        <Target class="w-4 h-4" /> 打分配置
      </h3>
      <div class="space-y-4">
        <div>
          <label class="text-xs text-neutral-500 mb-2 block">打分机制</label>
          <select v-model="localScoring.type" @change="onScoringChange" class="cfg-select">
            <option value="WIN_LOSS_DRAW">判胜负平(足球/棋类/对战)</option>
            <option value="TOTAL_SCORE">总分制(投票/计票)</option>
            <option value="MULTI_DIM">多维度评判(才艺/选秀,多裁判)</option>
          </select>
        </div>

        <!-- 判胜负平 -->
        <template v-if="localScoring.type === 'WIN_LOSS_DRAW'">
          <div class="grid grid-cols-3 gap-3">
            <div>
              <label class="text-xs text-neutral-500 mb-1 block">胜积分</label>
              <input type="number" step="0.5" v-model.number="localScoring.outcomeRules.winScore" @input="onScoringChange" class="cfg-input" />
            </div>
            <div>
              <label class="text-xs text-neutral-500 mb-1 block">平积分</label>
              <input type="number" step="0.5" v-model.number="localScoring.outcomeRules.drawScore" @input="onScoringChange" class="cfg-input" />
            </div>
            <div>
              <label class="text-xs text-neutral-500 mb-1 block">负积分</label>
              <input type="number" step="0.5" v-model.number="localScoring.outcomeRules.lossScore" @input="onScoringChange" class="cfg-input" />
            </div>
          </div>
        </template>

        <!-- 总分制 -->
        <template v-else-if="localScoring.type === 'TOTAL_SCORE'">
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">汇总规则</label>
            <select v-model="localScoring.aggregateRule" @change="onScoringChange" class="cfg-select">
              <option value="SUM">求和</option>
              <option value="AVG">平均值</option>
              <option value="TRIMMED_MEAN">去极值平均</option>
            </select>
          </div>
        </template>

        <!-- 多维度评判 -->
        <template v-else-if="localScoring.type === 'MULTI_DIM'">
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="text-xs text-neutral-500 mb-1 block">裁判间汇总</label>
              <select v-model="localScoring.refereeAggregateRule" @change="onScoringChange" class="cfg-select">
                <option value="AVG">平均</option>
                <option value="SUM">求和</option>
                <option value="TRIMMED_MEAN">去极值平均</option>
              </select>
            </div>
            <div>
              <label class="text-xs text-neutral-500 mb-1 block">维度间汇总</label>
              <select v-model="localScoring.aggregateRule" @change="onScoringChange" class="cfg-select">
                <option value="SUM">求和</option>
                <option value="AVG">平均</option>
                <option value="WEIGHTED">加权(按维度权重)</option>
                <option value="TRIMMED_MEAN">去极值平均</option>
              </select>
            </div>
          </div>
          <div v-if="useTrimRatio" class="grid grid-cols-1">
            <label class="text-xs text-neutral-500 mb-1 block">去极值比例(0~0.5)</label>
            <input type="number" step="0.05" v-model.number="localScoring.trimRatio" @input="onScoringChange" class="cfg-input" />
          </div>
          <div>
            <div class="flex items-center justify-between mb-2">
              <label class="text-xs text-neutral-500">评分维度</label>
              <button @click="addDim" class="text-xs text-amber-500 hover:text-amber-400">+ 添加维度</button>
            </div>
            <div v-if="!localScoring.dimensions || localScoring.dimensions.length === 0" class="text-xs text-neutral-600">未配置维度(将按单一总分处理)</div>
            <div v-for="(d, i) in localScoring.dimensions" :key="i" class="grid grid-cols-12 gap-2 mb-2">
              <input v-model="d.key" placeholder="标识(TECH)" class="cfg-input col-span-3" @input="onScoringChange" />
              <input v-model="d.name" placeholder="名称(技术)" class="cfg-input col-span-4" @input="onScoringChange" />
              <input type="number" step="0.1" v-model.number="d.weight" placeholder="权重" :disabled="localScoring.aggregateRule !== 'WEIGHTED'" class="cfg-input col-span-3 disabled:opacity-40" @input="onScoringChange" />
              <button @click="removeDim(i)" class="col-span-2 text-xs text-red-500 hover:text-red-400 border border-red-900/30 rounded">删除</button>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 转场配置 -->
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-4">转场配置(晋级触发)</h3>
      <div class="space-y-3">
        <div>
          <label class="text-xs text-neutral-500 mb-2 block">晋级模式</label>
          <select v-model="localTransition.mode" @change="onTransitionChange" class="cfg-select">
            <option value="AUTO">自动(赛段结算后自动晋级)</option>
            <option value="MANUAL">手动(需管理员确认)</option>
          </select>
        </div>
        <label class="flex items-center justify-between bg-black border border-neutral-700 rounded-lg p-3">
          <span class="text-sm text-neutral-300">重新抽签(否则按上赛段排名作种子)</span>
          <input type="checkbox" v-model="localTransition.reshuffle" class="accent-amber-500 w-4 h-4" @change="onTransitionChange" />
        </label>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { Target } from 'lucide-vue-next';

const props = defineProps<{ scoring?: any; transition?: any }>();
const emit = defineEmits<{ 'update:scoring': [any]; 'update:transition': [any] }>();

const TYPE_TO_MODE: Record<string, string> = {
  WIN_LOSS_DRAW: 'STANDARD',
  TOTAL_SCORE: 'VOTING',
  MULTI_DIM: 'RANKING'
};

const clone = (v: any) => JSON.parse(JSON.stringify(v));

const defaultScoring = () => ({
  type: 'WIN_LOSS_DRAW',
  matchMode: 'STANDARD',
  aggregateRule: 'SUM',
  refereeAggregateRule: 'AVG',
  trimRatio: 0.1,
  dimensions: [] as any[],
  outcomeRules: { winScore: 1, drawScore: 0.5, lossScore: 0 }
});

const localScoring = ref<any>(props.scoring ? clone(props.scoring) : defaultScoring());
const localTransition = ref<any>(props.transition ? clone(props.transition) : { mode: 'AUTO', reshuffle: false });

const useTrimRatio = computed(
  () => localScoring.value.aggregateRule === 'TRIMMED_MEAN' || localScoring.value.refereeAggregateRule === 'TRIMMED_MEAN'
);

const onScoringChange = () => {
  localScoring.value.matchMode = TYPE_TO_MODE[localScoring.value.type] || 'STANDARD';
  if (!localScoring.value.outcomeRules) localScoring.value.outcomeRules = { winScore: 1, drawScore: 0.5, lossScore: 0 };
  emit('update:scoring', clone(localScoring.value));
};
const onTransitionChange = () => emit('update:transition', clone(localTransition.value));

const addDim = () => {
  localScoring.value.dimensions = localScoring.value.dimensions || [];
  localScoring.value.dimensions.push({ key: '', name: '', weight: 0, maxScore: 100 });
  onScoringChange();
};
const removeDim = (i: number) => {
  localScoring.value.dimensions.splice(i, 1);
  onScoringChange();
};

watch(() => props.scoring, (v) => { if (v) localScoring.value = clone(v); });
watch(() => props.transition, (v) => { if (v) localTransition.value = clone(v); });
</script>

<style scoped>
.cfg-select {
  width: 100%;
  background: #000;
  border: 1px solid #404040;
  border-radius: 4px;
  padding: 10px;
  font-size: 13px;
  color: #fff;
}
.cfg-input {
  width: 100%;
  background: #000;
  border: 1px solid #404040;
  border-radius: 4px;
  padding: 8px 10px;
  font-size: 13px;
  color: #fff;
}
</style>
