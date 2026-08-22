<template>
  <el-dialog v-model="dialogVisible" title="选择赛段类型" width="700px" :close-on-click-modal="false" @close="handleClose">
    <div class="stage-selector">
      <!-- 步骤指示 -->
      <div class="steps mb-6">
        <div class="step" :class="{ active: step === 1, completed: step > 1 }">
          <div class="step-number">1</div>
          <div class="step-label">选择类型</div>
        </div>
        <div class="step-line"></div>
        <div class="step" :class="{ active: step === 2 }">
          <div class="step-number">2</div>
          <div class="step-label">配置参数</div>
        </div>
      </div>

      <!-- 步骤1: 选择赛段类型 -->
      <div v-if="step === 1" class="stage-types-grid">
        <div
          v-for="type in stageTypes"
          :key="type.mode"
          class="stage-type-card"
          :class="{ selected: selectedStageMode === type.mode }"
          @click="selectStageType(type.mode)"
        >
          <div class="card-icon">
            <component :is="type.icon" />
          </div>
          <div class="card-title">{{ type.label }}</div>
          <div class="card-desc">{{ type.description }}</div>
        </div>
      </div>

      <!-- 步骤2: 配置参数 -->
      <div v-if="step === 2" class="config-panel">
        <!-- 淘汰赛模板选择 -->
        <div v-if="selectedStageMode === StageMode.KNOCKOUT" class="config-section">
          <h4 class="section-title">选择淘汰赛模板</h4>
          <div class="template-grid">
            <div
              v-for="template in knockoutTemplates"
              :key="template.value"
              class="template-card"
              :class="{ selected: config.template === template.value }"
              @click="selectTemplate(template.value)"
            >
              <div class="template-name">{{ template.label }}</div>
              <div class="template-desc">{{ template.description }}</div>
            </div>
          </div>
          <div v-if="config.template === KnockoutTemplate.CUSTOM" class="custom-config mt-4">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm text-neutral-400 mb-1">参赛队伍数</label>
                <el-input-number v-model="config.teamsCount" :min="2" :max="256" class="w-full" />
              </div>
              <div>
                <label class="block text-sm text-neutral-400 mb-1">晋级队伍数</label>
                <el-input-number v-model="config.advanceCount" :min="1" :max="config.teamsCount - 1" class="w-full" />
              </div>
            </div>
          </div>
        </div>

        <!-- 小组赛配置 -->
        <div v-if="selectedStageMode === StageMode.GROUP" class="config-section">
          <h4 class="section-title">小组赛配置</h4>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-neutral-400 mb-1">分组数量</label>
              <el-input-number v-model="config.groupCount" :min="2" :max="16" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">每组队伍数</label>
              <el-input-number v-model="config.teamsPerGroup" :min="2" :max="16" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">每组晋级数</label>
              <el-input-number v-model="config.advancePerGroup" :min="1" :max="config.teamsPerGroup" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">比赛格式</label>
              <el-select v-model="config.format" class="w-full">
                <el-option label="BO1" value="BO1" />
                <el-option label="BO3" value="BO3" />
              </el-select>
            </div>
          </div>
        </div>

        <!-- 排名赛配置 -->
        <div v-if="selectedStageMode === StageMode.RANK" class="config-section">
          <h4 class="section-title">排名赛配置</h4>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-neutral-400 mb-1">参赛人数</label>
              <el-input-number v-model="config.scale" :min="2" :max="512" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">分圈数</label>
              <el-input-number v-model="config.circles" :min="1" :max="config.scale" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">晋级名额</label>
              <el-input-number v-model="config.advanceCount" :min="1" :max="config.scale - 1" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">比赛格式</label>
              <el-select v-model="config.format" class="w-full">
                <el-option label="BO1" value="BO1" />
                <el-option label="BO3" value="BO3" />
              </el-select>
            </div>
          </div>
        </div>

        <!-- 选拔赛配置 -->
        <div v-if="selectedStageMode === StageMode.AUDITION" class="config-section">
          <h4 class="section-title">选拔赛配置</h4>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-neutral-400 mb-1">海选规模</label>
              <el-input-number v-model="config.scale" :min="8" :max="512" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">晋级名额</label>
              <el-input-number v-model="config.advanceCount" :min="1" :max="config.scale - 1" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">比赛格式</label>
              <el-select v-model="config.format" class="w-full">
                <el-option label="BO1" value="BO1" />
                <el-option label="BO3" value="BO3" />
              </el-select>
            </div>
          </div>
        </div>

        <!-- 擂台赛配置 -->
        <div v-if="selectedStageMode === StageMode.ARENA" class="config-section">
          <h4 class="section-title">擂台赛配置</h4>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm text-neutral-400 mb-1">攻擂队伍数</label>
              <el-input-number v-model="config.challengerCount" :min="2" :max="32" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">最大挑战场次</label>
              <el-input-number v-model="config.maxChallenges" :min="1" :max="10" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">连胜奖励积分</label>
              <el-input-number v-model="config.winStreakBonus" :min="0" :max="100" class="w-full" />
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">挑战顺序</label>
              <el-select v-model="config.challengeOrder" class="w-full">
                <el-option label="随机" value="RANDOM" />
                <el-option label="按排名" value="RANKED" />
                <el-option label="手动" value="MANUAL" />
              </el-select>
            </div>
            <div>
              <label class="block text-sm text-neutral-400 mb-1">比赛格式</label>
              <el-select v-model="config.format" class="w-full">
                <el-option label="BO1" value="BO1" />
                <el-option label="BO3" value="BO3" />
                <el-option label="BO5" value="BO5" />
              </el-select>
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="handleClose">取消</el-button>
        <el-button v-if="step === 1" type="primary" @click="nextStep" :disabled="!selectedStageMode"> 下一步 </el-button>
        <el-button v-if="step === 2" @click="prevStep">上一步</el-button>
        <el-button v-if="step === 2" type="primary" @click="handleConfirm"> 确定 </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { Trophy, Mic, Target } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import {
  StageMode,
  KnockoutTemplate,
  KnockoutConfig,
  GroupConfig,
  AuditionConfig,
  ArenaConfig,
  RankingConfig,
  StageTypeSelectEvent
} from './types';

// Props
const props = defineProps<{
  visible: boolean;
}>();

// Emits
const emit = defineEmits<{
  'update:visible': [value: boolean];
  'select': [event: StageTypeSelectEvent];
}>();

// 数据
const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
});

const step = ref(1);
const selectedStageMode = ref<StageMode | null>(null);

// 赛段类型列表
const stageTypes = [
  {
    mode: StageMode.KNOCKOUT,
    label: '淘汰赛',
    description: '单败淘汰制',
    icon: Trophy
  },
  {
    mode: StageMode.AUDITION,
    label: '选拔赛',
    description: '海选晋级',
    icon: Mic
  },
  {
    mode: StageMode.ARENA,
    label: '擂台赛',
    description: 'SEVEN TO SMOKE',
    icon: Target
  },
  {
    mode: StageMode.RANK,
    label: '排名赛',
    description: '多维度打分排名',
    icon: Trophy
  }
];

// 淘汰赛模板列表
const knockoutTemplates = [
  { value: KnockoutTemplate.FINAL, label: '决赛', description: '冠军争夺战' },
  { value: KnockoutTemplate.SEMI_FINAL, label: '半决赛', description: '4进2' },
  { value: KnockoutTemplate.QUARTER_FINAL, label: '1/4决赛', description: '8进4' },
  { value: KnockoutTemplate.ROUND_16, label: '16进8', description: '16支队伍' },
  { value: KnockoutTemplate.ROUND_32, label: '32进16', description: '32支队伍' },
  { value: KnockoutTemplate.ROUND_64, label: '64进32', description: '64支队伍' },
  { value: KnockoutTemplate.CUSTOM, label: '自定义', description: '自定义参赛和晋级数' }
];

// 配置对象（使用联合类型）
const config = ref<any>({});

// 初始化配置
const initConfig = () => {
  switch (selectedStageMode.value) {
    case StageMode.KNOCKOUT:
      config.value = {
        template: KnockoutTemplate.ROUND_32,
        format: 'BO3',
        teamsCount: 32,
        advanceCount: 16
      } as KnockoutConfig;
      break;
    case StageMode.GROUP:
      config.value = {
        groupCount: 4,
        teamsPerGroup: 4,
        winPoints: 3,
        drawPoints: 1,
        lossPoints: 0,
        advancePerGroup: 2,
        format: 'BO1'
      } as GroupConfig;
      break;
    case StageMode.RANK:
      config.value = {
        mode: 'RANK',
        scale: 32,
        advanceCount: 16,
        circles: 1,
        format: 'BO1',
        publishMode: 'AUTO',
        publishScope: 'ALL',
        scoring: {
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
        }
      } as RankingConfig;
      break;
    case StageMode.AUDITION:
      config.value = {
        scale: 64,
        advanceCondition: 'score',
        advanceCount: 16,
        format: 'BO1'
      } as AuditionConfig;
      break;
    case StageMode.ARENA:
      config.value = {
        defenderTeamId: '',
        challengerCount: 8,
        winStreakBonus: 10,
        challengeOrder: 'RANDOM',
        maxChallenges: 3,
        format: 'BO1'
      } as ArenaConfig;
      break;
  }
};

// 选择赛段类型
const selectStageType = (mode: StageMode) => {
  selectedStageMode.value = mode;
  initConfig();
};

// 选择淘汰赛模板
const selectTemplate = (template: KnockoutTemplate) => {
  config.value.template = template;

  // 根据模板自动设置队伍数
  const templateMap: Record<KnockoutTemplate, { teams: number; advance: number }> = {
    [KnockoutTemplate.FINAL]: { teams: 2, advance: 1 },
    [KnockoutTemplate.SEMI_FINAL]: { teams: 4, advance: 2 },
    [KnockoutTemplate.QUARTER_FINAL]: { teams: 8, advance: 4 },
    [KnockoutTemplate.ROUND_16]: { teams: 16, advance: 8 },
    [KnockoutTemplate.ROUND_32]: { teams: 32, advance: 16 },
    [KnockoutTemplate.ROUND_64]: { teams: 64, advance: 32 },
    [KnockoutTemplate.CUSTOM]: { teams: 32, advance: 16 }
  };

  if (template !== KnockoutTemplate.CUSTOM) {
    config.value.teamsCount = templateMap[template].teams;
    config.value.advanceCount = templateMap[template].advance;
  }
};

// 下一步
const nextStep = () => {
  step.value = 2;
};

// 上一步
const prevStep = () => {
  step.value = 1;
};

// 确认选择
const handleConfirm = () => {
  if (!selectedStageMode.value) {
    ElMessage.warning('请选择赛段类型');
    return;
  }

  // 获取赛段默认名称
  const stageType = stageTypes.find((t) => t.mode === selectedStageMode.value);
  const defaultName = `新${stageType?.label || '赛段'}`;

  emit('select', {
    stageMode: selectedStageMode.value,
    ruleConfig: config.value,
    defaultName
  });

  handleClose();
};

// 关闭对话框
const handleClose = () => {
  step.value = 1;
  selectedStageMode.value = null;
  config.value = {};
  dialogVisible.value = false;
};

// 监听淘汰赛模板变化，同步队伍数
watch(
  () => config.value.template,
  (newTemplate) => {
    if (newTemplate && newTemplate !== KnockoutTemplate.CUSTOM) {
      selectTemplate(newTemplate);
    }
  }
);
</script>

<style scoped>
.stage-selector {
  padding: 20px 0;
}

.steps {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.step-number {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #262626;
  border: 2px solid #404040;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  color: #737373;
  transition: all 0.3s;
}

.step.active .step-number {
  background: #f59e0b;
  border-color: #f59e0b;
  color: white;
}

.step.completed .step-number {
  background: #22c55e;
  border-color: #22c55e;
  color: white;
}

.step-label {
  font-size: 12px;
  color: #737373;
}

.step.active .step-label {
  color: #f59e0b;
  font-weight: 500;
}

.step-line {
  width: 60px;
  height: 2px;
  background: #404040;
}

.stage-types-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.stage-type-card {
  background: #171717;
  border: 2px solid #262626;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 8px;
}

.stage-type-card:hover {
  border-color: #404040;
  transform: translateY(-2px);
}

.stage-type-card.selected {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.card-icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #262626;
  border-radius: 12px;
  color: #a3a3a3;
}

.stage-type-card.selected .card-icon {
  background: #f59e0b;
  color: white;
}

.card-title {
  font-weight: 600;
  color: #e5e5e5;
}

.card-desc {
  font-size: 12px;
  color: #737373;
}

.config-panel {
  padding: 20px 0;
}

.config-section {
  background: #171717;
  border-radius: 12px;
  padding: 24px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #e5e5e5;
  margin-bottom: 16px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.template-card {
  background: #0a0a0a;
  border: 2px solid #262626;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.template-card:hover {
  border-color: #404040;
}

.template-card.selected {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.template-name {
  font-weight: 600;
  color: #e5e5e5;
  font-size: 14px;
}

.template-desc {
  font-size: 12px;
  color: #737373;
  margin-top: 4px;
}

.custom-config {
  padding: 16px;
  background: #0a0a0a;
  border-radius: 8px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
