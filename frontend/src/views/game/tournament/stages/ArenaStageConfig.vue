<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Target class="w-4 h-4" /> 擂台赛配置</h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <!-- 队伍信息 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">攻擂队伍数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.challengerCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支队伍</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">最大挑战场次</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.maxChallenges }}</div>
              <div class="text-xs text-neutral-600 mt-1">场</div>
            </div>
          </div>

          <!-- 比赛格式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛设置</div>
            <div class="text-lg font-medium text-white mb-1">{{ config.format }}</div>
            <div class="text-sm text-neutral-300">挑战顺序：{{ getChallengeOrderText(config.challengeOrder) }}</div>
          </div>

          <!-- 赛制概览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">{{ config.challengerCount }} 支攻擂队伍挑战守擂方</div>
            <div class="text-sm text-neutral-300 mt-1">最多进行 {{ config.maxChallenges }} 场比赛</div>
          </div>
        </template>

        <!-- INIT/NORMAL 模式: 可编辑 -->
        <template v-else>
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

          <!-- 队伍设置 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">队伍设置</label>
            <div class="grid grid-cols-2 gap-6">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">攻擂队伍数</label>
                <input
                  type="number"
                  v-model.number="config.challengerCount"
                  :min="2"
                  :max="32"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">守擂方</label>
                <select
                  v-model="config.defenderTeamId"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @change="handleUpdate"
                >
                  <option value="">请选择守擂方</option>
                  <option v-for="team in mockTeams" :key="team.id" :value="team.id">
                    {{ team.name }}
                  </option>
                </select>
              </div>
            </div>
          </div>

          <!-- 挑战规则 (INIT-ONLY: maxChallenges) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">挑战规则</label>
            <div class="grid grid-cols-2 gap-6">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">最大挑战场次</label>
                <input
                  type="number"
                  v-model.number="config.maxChallenges"
                  :min="1"
                  :max="10"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">挑战顺序</label>
                <select
                  v-model="config.challengeOrder"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @change="handleUpdate"
                >
                  <option value="RANDOM">随机</option>
                  <option value="RANKED">按排名</option>
                  <option value="MANUAL">手动</option>
                </select>
              </div>
            </div>
          </div>

          <!-- 奖励机制 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">奖励机制</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4">
              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">连胜奖励积分</label>
                  <input
                    type="number"
                    v-model.number="config.winStreakBonus"
                    :min="0"
                    :max="100"
                    class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                </div>
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">卫冕成功奖励</label>
                  <input
                    type="number"
                    v-model.number="config.defenseBonus"
                    :min="0"
                    :max="100"
                    class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                </div>
              </div>
            </div>
          </div>

          <!-- 特殊规则 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">特殊规则</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">允许守擂方休息</span>
                <input type="checkbox" v-model="config.allowDefenderRest" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">攻擂失败可再挑战</span>
                <input type="checkbox" v-model="config.allowRechallenge" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">挑战超时换人</span>
                <input type="checkbox" v-model="config.timeoutReplacement" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
            </div>
          </div>

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">{{ config.challengerCount }} 支攻擂队伍挑战守擂方</div>
            <div class="text-sm text-neutral-300 mt-1">最多进行 {{ config.maxChallenges }} 场比赛</div>
            <div class="text-xs text-neutral-500 mt-2">挑战顺序：{{ getChallengeOrderText(config.challengeOrder) }}</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Target } from 'lucide-vue-next';
import { ArenaConfig, StageData, ChallengeOrder, ConfigMode } from './types';

// Props
const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
}>();

// Emits
const emit = defineEmits<{
  update: [stage: StageData];
}>();

// 模拟队伍数据（实际应从API获取）
const mockTeams = ref([
  { id: '1', name: 'Team Alpha' },
  { id: '2', name: 'Team Beta' },
  { id: '3', name: 'Team Gamma' }
]);

// 本地赛段数据
const localStage = ref<StageData>({ ...props.stage });

// 当前模式
const currentMode = computed(() => props.mode || ConfigMode.NORMAL);

// 配置对象
const config = ref<any>({
  defenderTeamId: '',
  challengerCount: 8,
  winStreakBonus: 10,
  challengeOrder: ChallengeOrder.RANDOM,
  maxChallenges: 3,
  format: 'BO1',
  defenseBonus: 5,
  allowDefenderRest: true,
  allowRechallenge: false,
  timeoutReplacement: true
});

// 获取挑战顺序文本
const getChallengeOrderText = (order: string) => {
  const map = {
    'RANDOM': '随机',
    'RANKED': '按排名',
    'MANUAL': '手动指定'
  };
  return map[order] || order;
};

// 解析配置
const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

// 序列化配置
const serializeConfig = () => {
  return JSON.stringify(config.value);
};

// 更新处理
const handleUpdate = () => {
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.challengerCount + 1;
  localStage.value.teamCountEnd = 1; // 擂台赛最终决出1个胜者
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
