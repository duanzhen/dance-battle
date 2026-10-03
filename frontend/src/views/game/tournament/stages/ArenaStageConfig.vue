<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Target class="w-4 h-4" /> 擂台赛配置</h3>

      <div class="space-y-6">
        <!-- STARTED 模式: 创建+初始配置只读展示 -->
        <template v-if="currentMode === ConfigMode.STARTED">
          <!-- 选手信息 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">进入总人数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.scale }}</div>
              <div class="text-xs text-neutral-600 mt-1">人(擂主 1 + 攻擂 {{ challengerCount }})</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">攻擂人数</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ challengerCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">人</div>
            </div>
          </div>

          <!-- 比赛格式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛设置</div>
            <div class="text-lg font-medium text-white mb-1">{{ config.format }}</div>
          </div>

          <!-- 判罚方式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">判罚方式</div>
            <div class="text-sm text-neutral-200">
              {{
                config.publishMode === 'DIRECTOR'
                  ? '导播台直接判定（裁判端不显示场次，MC 在手机导播台选胜负/平局）'
                  : '裁判判罚（裁判端录入，判完即结算）'
              }}
            </div>
          </div>

          <!-- 平局计分 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">平局计分</div>
            <div class="text-sm text-neutral-200">
              {{ config.drawBothScore === false ? '平局不加分（只有胜场记 1 分）' : '平局双方各 +1 分' }}
            </div>
          </div>

          <!-- 赛制概览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">共 {{ config.scale }} 人进入擂台赛（擂主 1 人 + 攻擂 {{ challengerCount }} 人）</div>
            <div class="text-sm text-neutral-300 mt-1">单场判胜负,胜利记 1 分</div>
          </div>

          <StageExitConfig :stage="localStage" />
        </template>

        <!-- CREATE/INIT 模式: 可编辑 -->
        <template v-else>
          <!-- 创建配置(INIT 模式只读展示:创建后锁定) -->
          <div v-if="currentMode === ConfigMode.INIT" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-3">创建配置（锁定）</div>
            <div class="grid grid-cols-2 gap-4">
              <div>
                <div class="text-xs text-neutral-500 mb-1">进入总人数</div>
                <div class="text-2xl font-bold text-white font-mono">{{ config.scale }}</div>
              </div>
              <div>
                <div class="text-xs text-neutral-500 mb-1">攻擂人数</div>
                <div class="text-2xl font-bold text-white font-mono">{{ challengerCount }}</div>
              </div>
            </div>
          </div>

          <!-- 选手设置 (创建配置,创建后锁定) -->
          <div v-if="currentMode === ConfigMode.CREATE">
            <label class="text-xs text-neutral-500 mb-2 block">选手设置</label>
            <div>
              <label class="text-xs text-neutral-600 mb-1 block">进入擂台赛总人数</label>
              <input
                type="number"
                v-model.number="config.scale"
                :min="2"
                :max="512"
                class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                @input="handleUpdate"
              />
            </div>
            <p class="text-[11px] text-neutral-500 mt-2">
              配置进入擂台赛的总人数(擂主 1 人 + 攻擂 {{ challengerCount }} 人);擂主为当前守擂方,无需手动指定
            </p>
          </div>

          <!-- 初始配置:比赛格式(仅非创建模式显示) -->
          <template v-if="currentMode !== ConfigMode.CREATE">
            <!-- 基本信息 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">比赛格式</label>
              <el-select v-model="config.format" class="w-full" @change="handleUpdate">
                <el-option value="BO1" label="BO1 (单局决胜)" />
                <el-option value="BO3" label="BO3 (三局两胜)" />
                <el-option value="BO5" label="BO5 (五局三胜)" />
              </el-select>
            </div>

            <!-- 判罚方式:与淘汰赛同一套 publishMode 机制 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">判罚方式</label>
              <el-select v-model="config.publishMode" class="w-full" @change="handleUpdate">
                <el-option value="AUTO" label="裁判判罚（裁判端录入，判完即结算）" />
                <el-option value="DIRECTOR" label="导播台直接判定（MC 在导播台选胜负/平局）" />
              </el-select>
              <p class="text-[11px] text-neutral-500 mt-2">
                导播台判定：擂台赛裁判端不再显示场次，每场由 MC 在手机导播台点「左胜 / 平 / 右胜」直接判定；判完再点「开始下一场」，
                胜者守擂、败者排到队尾。
              </p>
            </div>

            <!-- 积分规则 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">平局计分</label>
              <el-select v-model="config.drawBothScore" class="w-full" @change="handleUpdate">
                <el-option :value="true" label="平局双方各 +1 分（默认）" />
                <el-option :value="false" label="平局不加分（只有胜场记 1 分）" />
              </el-select>
              <p class="text-[11px] text-neutral-500 mt-2">
                平局时擂主与挑战者都要下场排队尾,本项只决定是否给双方各记 1 分
              </p>
            </div>

            <!-- 预览 -->
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
              <div class="text-sm text-neutral-300">共 {{ config.scale }} 人进入擂台赛（擂主 1 人 + 攻擂 {{ challengerCount }} 人）</div>
              <div class="text-sm text-neutral-300 mt-1">单场判胜负,胜利记 1 分</div>
            </div>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Target } from 'lucide-vue-next';
import { ArenaConfig, StageData, ConfigMode } from './types';
import StageExitConfig from './StageExitConfig.vue';

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
const currentMode = computed(() => props.mode || ConfigMode.INIT);

// 配置对象
const config = ref<any>({
  scale: 8,
  format: 'BO1',
  // 平局计分:默认「双方各 +1 分」;显式设 false 才回到"平局都不加分"
  drawBothScore: true,
  // 判罚方式:AUTO=裁判判罚 / DIRECTOR=导播台直接判定(与淘汰赛同一套 publishMode)
  publishMode: 'AUTO'
});

// 攻擂人数 = 进入总人数 - 1(擂主)
const challengerCount = computed(() => Math.max(0, (Number(config.value.scale) || 0) - 1));

// 解析配置
const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
      // 兼容旧配置:只有攻擂选手数时,推导进入总人数
      if (config.value.scale == null && config.value.challengerCount != null) {
        config.value.scale = Number(config.value.challengerCount) + 1;
      }
      delete config.value.challengerCount;
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
  localStage.value.teamCountStart = Number(config.value.scale) || 0;
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
</style>
