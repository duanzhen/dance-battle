<template>
  <div class="transition-config">
    <div class="flex items-center justify-between my-6">
      <div class="flex items-center gap-4 text-neutral-400">
        <span class="font-bold text-neutral-200">{{ sourceStageName }}</span>
        <ArrowRight class="w-4 h-4" />
        <div class="px-3 py-1 bg-amber-500/10 text-amber-500 border border-amber-500/20 rounded-lg text-sm font-bold flex items-center gap-2">
          <SlidersHorizontal class="w-4 h-4" /> 中间态调整
        </div>
        <ArrowRight class="w-4 h-4" />
        <span class="font-bold text-neutral-200">{{ targetStageName }}</span>
      </div>
    </div>

    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-8 space-y-8">
      <!-- 目标赛段已开始:整页锁定 -->
      <div
        v-if="targetLocked"
        class="flex items-center gap-3 px-4 py-3 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400 text-sm"
      >
        <Lock class="w-4 h-4 flex-none" />
        <span>{{ targetStageName }} 已开始,中间态调整已锁定,如需调整请先重置该赛段。</span>
      </div>

      <div class="space-y-4">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">晋级处理逻辑</h4>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div
            @click="!targetLocked && (config.mode = 'AUTO')"
            class="cursor-pointer border rounded-xl p-4 flex gap-4 transition-all"
            :class="[
              targetLocked ? 'opacity-50 cursor-not-allowed' : '',
              config.mode === 'AUTO' ? 'bg-amber-500/10 border-amber-500' : 'bg-black border-neutral-700 hover:border-neutral-500'
            ]"
          >
            <div class="mt-1"><Zap class="w-5 h-5" :class="config.mode === 'AUTO' ? 'text-amber-500' : 'text-neutral-500'" /></div>
            <div>
              <div class="font-bold text-sm text-neutral-200">自动晋级 (Auto Seeding)</div>
              <div class="text-xs text-neutral-500 mt-1">系统根据排名自动填充下一阶段名额，无需人工干预。</div>
            </div>
          </div>

          <div
            @click="!targetLocked && (config.mode = 'MANUAL')"
            class="cursor-pointer border rounded-xl p-4 flex gap-4 transition-all"
            :class="[
              targetLocked ? 'opacity-50 cursor-not-allowed' : '',
              config.mode === 'MANUAL' ? 'bg-amber-500/10 border-amber-500' : 'bg-black border-neutral-700 hover:border-neutral-500'
            ]"
          >
            <div class="mt-1"><Hand class="w-5 h-5" :class="config.mode === 'MANUAL' ? 'text-amber-500' : 'text-neutral-500'" /></div>
            <div>
              <div class="font-bold text-sm text-neutral-200">人工干预 (Manual Adjust)</div>
              <div class="text-xs text-neutral-500 mt-1">比赛暂停，管理员需手动确认对阵表、调整种子顺位后方可开始。</div>
            </div>
          </div>
        </div>
      </div>

      <div class="h-px bg-neutral-800 w-full"></div>

      <div v-if="config.mode === 'MANUAL'" class="space-y-4 animate-fade-in">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">手动操作项</h4>

        <label class="flex items-center justify-between p-3 rounded-lg bg-black border border-neutral-800 hover:border-neutral-600 cursor-pointer">
          <div>
            <div class="text-sm text-neutral-200">重新抽签 (Reshuffle Seeds)</div>
            <div class="text-xs text-neutral-500">打乱上一阶段排名，重新生成对阵</div>
          </div>
          <input type="checkbox" v-model="config.reshuffle" :disabled="targetLocked" class="accent-amber-500 w-4 h-4" />
        </label>

        <label class="flex items-center justify-between p-3 rounded-lg bg-black border border-neutral-800 hover:border-neutral-600 cursor-pointer">
          <div>
            <div class="text-sm text-neutral-200">允许替补更换</div>
            <div class="text-xs text-neutral-500">允许战队在此间歇期提交新的首发名单</div>
          </div>
          <input type="checkbox" v-model="config.allowSubstitutions" :disabled="targetLocked" class="accent-amber-500 w-4 h-4" />
        </label>
      </div>

      <!-- 预排参赛者手动调整 -->
      <div v-if="config.mode === 'MANUAL'" class="space-y-4">
        <div class="flex items-center justify-between">
          <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">预排参赛者调整</h4>
          <span class="text-xs text-neutral-500">调整后保存,开始{{ targetStageName }}时按新位置生成对阵</span>
        </div>

        <div v-if="preLoading" class="text-sm text-neutral-500 py-4 text-center">加载预排中...</div>
        <div v-else-if="preSeeds.length === 0" class="text-sm text-neutral-600 py-4 text-center">
          {{ preStatus === 'WAIT_PREV' ? '等待上一赛段结算,胜者产生后自动预排' : '暂无预排参赛者' }}
        </div>
        <div v-else class="space-y-1.5">
          <div
            v-for="(seed, i) in preSeeds"
            :key="seed.competitorId"
            class="flex items-center gap-3 px-3 py-2 rounded-lg bg-black border border-neutral-800"
          >
            <span class="w-8 h-8 rounded flex items-center justify-center text-xs font-bold bg-neutral-800 text-neutral-400 flex-none">
              {{ seed.seedRank }}
            </span>
            <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ seed.name }}</span>
            <span v-if="seed.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ seed.sourceMatchName }}</span>
            <div class="flex items-center gap-1 flex-none">
              <button
                @click="moveSeed(i, -1)"
                :disabled="targetLocked || i === 0"
                class="w-7 h-7 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                title="上移"
              >
                ↑
              </button>
              <button
                @click="moveSeed(i, 1)"
                :disabled="targetLocked || i === preSeeds.length - 1"
                class="w-7 h-7 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                title="下移"
              >
                ↓
              </button>
            </div>
          </div>
          <p class="text-[10px] text-neutral-600 pt-1">仅可调整已产生的晋级者;轮空/待定位置保持不变。</p>
        </div>
      </div>

      <!-- 排名赛同分待定晋级调整 -->
      <div v-if="isRankSource && pendingAdvancers.length > 0" class="space-y-4 border-t border-neutral-800 pt-6">
        <div class="flex items-center justify-between">
          <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">同分待定晋级调整</h4>
          <span class="text-xs text-neutral-500">晋级线同分并列,由导播台手动指定晋级者</span>
        </div>

        <div class="space-y-1.5">
          <div
            v-for="c in pendingAdvancers"
            :key="c.id"
            class="flex items-center gap-3 px-3 py-2 rounded-lg bg-black border border-neutral-800"
            :class="selectedAdvanceIds.includes(String(c.id)) ? 'border-amber-500/50' : ''"
          >
            <input
              type="checkbox"
              :checked="selectedAdvanceIds.includes(String(c.id))"
              :disabled="targetLocked"
              class="accent-amber-500 w-4 h-4"
              @change="toggleAdvance(c.id)"
            />
            <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ c.name }}</span>
            <span v-if="c.number" class="text-[10px] text-neutral-600 flex-none">#{{ c.number }}</span>
            <span class="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400 flex-none">待定</span>
          </div>
        </div>
        <p class="text-[10px] text-neutral-600">勾选即晋级;未勾选的待定者将标记淘汰。全部勾选 = 同分者全部晋级(名额可超限)。</p>

        <div class="flex justify-end">
          <button
            @click="saveAdvancement"
            :disabled="targetLocked || advSaving || selectedAdvanceIds.length === 0"
            class="px-4 py-2 rounded-lg bg-amber-500 text-neutral-900 text-xs font-bold hover:bg-amber-400 disabled:opacity-40 transition-colors"
          >
            {{ advSaving ? '保存中...' : '保存晋级调整' }}
          </button>
        </div>
      </div>

      <div class="space-y-4">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">间歇期设置</h4>
        <div class="flex items-center gap-4">
          <div class="flex-1">
            <label class="text-xs text-neutral-500 mb-1 block">预计休息时长 (小时)</label>
            <input
              type="number"
              v-model="config.breakDuration"
              class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none"
            />
          </div>
          <div class="flex-1">
            <label class="text-xs text-neutral-500 mb-1 block">下一阶段开始时间</label>
            <input
              type="datetime-local"
              class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none [color-scheme:dark]"
            />
          </div>
        </div>
      </div>

      <div class="flex justify-end pt-2">
        <button
          @click="saveConfig"
          :disabled="targetLocked || saving"
          class="px-6 py-2.5 rounded-lg bg-amber-500 text-neutral-900 text-sm font-bold hover:bg-amber-400 disabled:opacity-50 transition-colors"
        >
          {{ saving ? '保存中...' : '保存转场配置' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { ArrowRight, SlidersHorizontal, Zap, Hand, Lock } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { getStage, updateStage, getStagePreBracket, adjustStageAdvancement } from '@/api/game/stage';
import { listCompetitor } from '@/api/game/competitor';

// Props
const props = defineProps<{
  sourceStageId: string | number;
  sourceStageName: string;
  targetStageId: string | number;
  targetStageName: string;
  transitionIndex: number;
}>();

// 转场配置数据
const config = reactive({
  mode: 'AUTO' as 'AUTO' | 'MANUAL',
  reshuffle: false,
  allowSubstitutions: false,
  breakDuration: 24
});

// 预排参赛者与保存状态
const preLoading = ref(false);
const preStatus = ref('');
const preSeeds = ref<any[]>([]);
const saving = ref(false);
const advSaving = ref(false);
const pendingAdvancers = ref<any[]>([]);
const selectedAdvanceIds = ref<string[]>([]);
const sourceStage = ref<any>(null);
const targetStage = ref<any>(null);

/** 来源赛段是否为排名赛(同分待定晋级调整仅排名赛需要) */
const isRankSource = computed(() => sourceStage.value?.stageMode === 'RANK');

/** 目标赛段已开始(非 DRAFT/PENDING)时锁定整页中间态调整 */
const targetLocked = computed(() => {
  const status = targetStage.value?.status;
  return status != null && status !== 'DRAFT' && status !== 'PENDING';
});

const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);

const loadSourceConfig = async () => {
  try {
    const resp: any = await getStage(props.sourceStageId);
    sourceStage.value = resp.data;
    const rule = JSON.parse(sourceStage.value.ruleConfig || '{}');
    const t = rule.transition || {};
    config.mode = t.mode === 'MANUAL' ? 'MANUAL' : 'AUTO';
    config.reshuffle = !!t.reshuffle;
    config.allowSubstitutions = !!t.allowSubstitutions;
    await loadPendingAdvancers();
  } catch {
    console.warn('加载转场配置失败');
  }
};

/** 加载来源赛段中处于待定(同分)状态的参赛者 */
const loadPendingAdvancers = async () => {
  if (!sourceStage.value || sourceStage.value.stageMode !== 'RANK') {
    pendingAdvancers.value = [];
    selectedAdvanceIds.value = [];
    return;
  }
  try {
    const resp: any = await listCompetitor({
      stageId: sourceStage.value.id,
      pageNum: 1,
      pageSize: 1000
    });
    const rows: any[] = resp.data?.rows ?? resp.data?.data ?? [];
    pendingAdvancers.value = rows.filter((c) => c.outcomeStatus === 'PENDING');
    selectedAdvanceIds.value = [];
  } catch {
    pendingAdvancers.value = [];
  }
};

const toggleAdvance = (id: string | number) => {
  const key = String(id);
  const idx = selectedAdvanceIds.value.indexOf(key);
  if (idx >= 0) {
    selectedAdvanceIds.value.splice(idx, 1);
  } else {
    selectedAdvanceIds.value.push(key);
  }
};

const saveAdvancement = async () => {
  if (!sourceStage.value || selectedAdvanceIds.value.length === 0) return;
  advSaving.value = true;
  try {
    await adjustStageAdvancement(sourceStage.value.id, selectedAdvanceIds.value);
    ElMessage.success('同分晋级调整已保存');
    await loadPendingAdvancers();
    await loadPreBracket();
  } catch (e: any) {
    console.error('保存同分晋级调整失败:', e);
    ElMessage.error(e?.response?.data?.msg || '保存失败');
  } finally {
    advSaving.value = false;
  }
};

const loadPreBracket = async () => {
  preLoading.value = true;
  try {
    const resp: any = await getStagePreBracket(props.targetStageId);
    preStatus.value = resp.data?.status || '';
    preSeeds.value = (resp.data?.seededCompetitors || [])
      .slice()
      .sort((a: any, b: any) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER));
  } catch {
    preSeeds.value = [];
    preStatus.value = '';
  } finally {
    preLoading.value = false;
  }
};

const loadTargetStage = async () => {
  try {
    const resp: any = await getStage(props.targetStageId);
    targetStage.value = resp.data;
  } catch {
    targetStage.value = null;
  }
};

/** 上移/下移:交换相邻参赛者的种子位,保持空位体系不变 */
const moveSeed = (index: number, dir: -1 | 1) => {
  const target = index + dir;
  if (target < 0 || target >= preSeeds.value.length) return;
  const a = preSeeds.value[index];
  const b = preSeeds.value[target];
  const tmp = a.seedRank;
  a.seedRank = b.seedRank;
  b.seedRank = tmp;
  preSeeds.value.splice(index, 1);
  preSeeds.value.splice(target, 0, a);
};

const buildOverrides = () => {
  const map: Record<string, number> = {};
  preSeeds.value.forEach((s) => {
    if (s.competitorId != null && s.seedRank != null) {
      map[String(s.competitorId)] = Number(s.seedRank);
    }
  });
  return map;
};

const saveConfig = async () => {
  if (!sourceStage.value) {
    ElMessage.warning('转场配置尚未加载完成');
    return;
  }
  saving.value = true;
  try {
    const s = sourceStage.value;
    const rule = JSON.parse(s.ruleConfig || '{}');
    rule.transition = {
      ...(rule.transition || {}),
      mode: config.mode,
      reshuffle: config.reshuffle,
      allowSubstitutions: config.allowSubstitutions,
      seedOverrides: buildOverrides()
    };
    const formData: any = {
      id: s.id,
      tournamentId: s.tournamentId,
      name: s.name,
      stageMode: s.stageMode,
      format: s.format || '',
      teamCountStart: s.teamCountStart,
      teamCountEnd: s.teamCountEnd,
      status: s.status,
      ruleConfig: JSON.stringify(rule),
      isInitialized: s.isInitialized
    };
    if (qid(s.prevStageId) != null) formData.prevStageId = qid(s.prevStageId);
    if (qid(s.nextStageId) != null) formData.nextStageId = qid(s.nextStageId);
    await updateStage(formData);
    ElMessage.success('转场配置已保存');
  } catch (e: any) {
    console.error('保存转场配置失败:', e);
    ElMessage.error(e?.response?.data?.msg || '保存失败');
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  loadSourceConfig();
  loadPreBracket();
  loadTargetStage();
});
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.3s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
