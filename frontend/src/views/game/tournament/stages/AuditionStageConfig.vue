<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2">
        <Mic class="w-4 h-4" /> 海选赛配置
      </h3>

      <div class="space-y-6">
        <!-- 创建模式:海选为入口赛段,创建后再配置圈 -->
        <div v-if="currentMode === ConfigMode.CREATE" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
          <div class="text-xs text-neutral-500 mb-2">创建配置</div>
          <p class="text-sm text-neutral-300 leading-relaxed">
            海选为赛事入口赛段，<span class="text-amber-500 font-bold">不限制参赛人数</span>，按实际签到选手参与。
          </p>
          <p class="text-[11px] text-neutral-500 mt-2">圈配置（裁判/去向，晋级人数由去向名次推导）请在创建完成后新增</p>
        </div>

        <template v-else>
          <!-- 基础信息 -->
          <div class="grid grid-cols-2 lg:grid-cols-3 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">参赛人数</div>
              <div class="text-2xl font-bold text-white font-mono">不限</div>
              <div class="text-xs text-neutral-600 mt-1">按实际签到为准</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
              <input
                v-if="editable"
                v-model.number="config.advanceCount"
                type="number"
                :min="1"
                class="w-20 bg-black border border-neutral-700 rounded p-1.5 text-lg font-bold text-amber-500 font-mono focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <div v-else class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">名晋级</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">圈数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ circleCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">{{ circleCount === 0 ? '尚未配置' : '圈并行' }}</div>
            </div>
          </div>

          <!-- 圈配置:按圈展示(单圈同样按圈展示) -->
          <div>
            <div class="flex items-center justify-between mb-2">
              <label class="text-xs text-neutral-500">圈配置</label>
              <span class="text-[11px] text-neutral-600">合计晋级 {{ totalQuota }} / {{ config.advanceCount }}</span>
            </div>

            <div v-if="circleCount === 0" class="rounded-lg border border-dashed border-neutral-700 bg-black/30 px-4 py-6 text-center">
              <p class="text-sm text-neutral-400">尚未配置圈</p>
              <p class="text-[11px] text-neutral-600 mt-1">点击下方按钮新增第 1 圈（裁判 / 去向）</p>
            </div>

            <div v-else class="space-y-3">
              <div
                v-for="i in circleCount"
                :key="i"
                class="rounded-lg border border-neutral-800 bg-black/40 p-4 space-y-2"
              >
                <div class="flex items-center justify-between">
                  <span class="text-sm font-bold text-amber-500">第 {{ i }} 圈</span>
                  <div class="flex items-center gap-2">
                    <span class="text-[11px] text-neutral-500">
                      晋级 <span class="text-white font-mono">{{ circleQuota(i - 1) }}</span> 人
                      · 裁判 {{ circleRefereeText(i - 1) }}
                    </span>
                    <button
                      v-if="editable"
                      type="button"
                      class="px-2 py-1 text-[11px] rounded border border-amber-500/40 text-amber-400 bg-amber-500/10 hover:bg-amber-500/20 transition-colors flex-none"
                      @click="openEditCircle(i - 1)"
                    >
                      编辑
                    </button>
                  </div>
                </div>
                <div class="flex items-center justify-between text-[11px] text-neutral-500">
                  <span>参赛人数：{{ circlePlayerText(i - 1) }}</span>
                  <span>出口</span>
                </div>
                <div v-if="exitsOfCircle(i - 1).length === 0" class="text-[11px] text-neutral-600">
                  未配置出口（默认随下一赛段衔接）
                </div>
                <div v-else class="space-y-1">
                  <div
                    v-for="e in exitsOfCircle(i - 1)"
                    :key="String(e.targetStageId) + '-' + e.groupIndex"
                    class="flex items-center gap-2 rounded bg-neutral-900/70 border border-neutral-800 px-2 py-1"
                  >
                    <span class="text-[11px] text-amber-500/90 flex-none">{{ stageNameOf(e.targetStageId) }}</span>
                    <span class="text-[11px] text-neutral-300 flex-1 min-w-0 truncate">{{ exitRuleText(e.g) }}</span>
                    <button
                      v-if="editable"
                      class="px-1.5 py-1 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors flex-none"
                      @click="removeExit(e)"
                    >
                      移除
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <button
              v-if="editable"
              type="button"
              @click="openAddCircle"
              class="mt-3 w-full py-2 text-xs font-bold rounded-lg bg-amber-500/15 text-amber-500 border border-amber-500/40 hover:bg-amber-500/25 transition-colors"
            >
              ＋ 新增一圈
            </button>
            <p v-if="editable" class="text-[11px] text-neutral-600 mt-2">
              圈只能增加不能减少；新增圈为空场次，已签到选手与已有圈不受影响
            </p>
            <p v-if="editable && Number(localStage.isInitialized) === 1" class="text-[11px] text-amber-500/80 mt-1">
              赛段已生成对阵：修改圈配置后请重新点击「开始赛段/生成对阵」以按新配置重排
            </p>
          </div>

          <!-- 评分规则 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">评分规则</div>
            <div class="flex flex-wrap items-center gap-2">
              <span class="px-2.5 py-1 rounded text-xs border border-green-500/30 bg-green-500/10 text-green-400">打分制</span>
              <span class="text-xs text-neutral-400">按总分排名，取前 N 名晋级</span>
              <label class="text-xs text-neutral-500 flex items-center gap-2">
                满分
                <select
                  v-if="editable"
                  v-model.number="config.maxScore"
                  class="cfg-select w-32"
                  @change="handleUpdate"
                >
                  <option :value="10">10 分制</option>
                  <option :value="100">100 分制</option>
                </select>
                <span v-else class="text-neutral-300">{{ config.maxScore }} 分制</span>
              </label>
              <span class="text-[11px] text-neutral-600">支持 2 位小数（如 9.75 / 97.50）</span>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 新增圈向导:裁判 → 去向(晋级人数由去向名次自动推导) -->
    <Teleport to="body">
      <div
        v-if="wizardVisible"
        class="fixed inset-0 z-[70] flex items-center justify-center bg-black/60 backdrop-blur-sm px-4"
        @click.self="closeWizard"
      >
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-lg shadow-2xl overflow-hidden" @click.stop>
          <div class="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-white">
              {{ editingCircleIndex === null ? `新增第 ${wizardCircleNo} 圈` : `编辑第 ${editingCircleIndex + 1} 圈` }}
            </h3>
            <div class="flex items-center gap-2 text-[11px]">
              <span :class="wizardStep === 1 ? 'text-amber-400' : 'text-neutral-600'">1 裁判</span>
              <span class="text-neutral-700">›</span>
              <span :class="wizardStep === 2 ? 'text-amber-400' : 'text-neutral-600'">2 去向</span>
            </div>
          </div>

          <div class="p-5 space-y-4 text-xs text-neutral-300">
            <!-- Step 1 裁判 -->
            <template v-if="wizardStep === 1">
              <p class="text-neutral-400 leading-relaxed">选择该圈裁判（可多选；也可留空稍后补充）。</p>
              <div v-if="referees.length === 0" class="rounded-lg border border-neutral-800 bg-black/40 px-3 py-4 text-center">
                <p class="text-neutral-500">暂无裁判可选</p>
                <p class="text-[11px] text-neutral-600 mt-1">可先跳过，稍后在赛事裁判组中添加</p>
              </div>
              <div v-else class="grid grid-cols-2 gap-2 max-h-48 overflow-y-auto pr-1">
                <button
                  v-for="r in referees"
                  :key="String(r.id)"
                  class="rounded-lg border px-3 py-2 text-left transition-all"
                  :class="wizard.referees.includes(String(r.id))
                    ? 'border-amber-500 bg-amber-500/10'
                    : 'border-neutral-700 bg-neutral-900/40 hover:border-neutral-500'"
                  @click="toggleWizardReferee(r.id)"
                >
                  <div class="text-xs font-bold" :class="wizard.referees.includes(String(r.id)) ? 'text-amber-400' : 'text-neutral-200'">
                    {{ r.name }}
                  </div>
                </button>
              </div>
            </template>

            <!-- Step 2 去向 -->
            <template v-else>
              <p class="text-neutral-400 leading-relaxed">
                {{ editingCircleIndex === null
                  ? `该圈的出口：默认一条「第 1~${wizard.advance ?? wizard.exits[0]?.rankEnd ?? '?'} 名 → 下一赛段」，可添加更多出口（如第 9~24 名 → 复活赛）。`
                  : '该圈的出口（可修改名次段，也可追加新出口）。' }}
                海选只按圈内排名取人，名次决定晋级/落选，不需要单独选择结果。
              </p>

              <!-- 晋级人数(晋级线):同分加赛的边界之一 -->
              <div class="flex items-center gap-3 rounded-lg border border-neutral-800 bg-black/40 px-3 py-2.5">
                <div class="w-28 flex-none">
                  <label class="text-[11px] text-neutral-500 block mb-1">该圈晋级人数</label>
                  <input v-model.number="wizard.advance" type="number" min="0" class="cfg-input w-full" placeholder="如 8" />
                </div>
                <p class="text-[11px] text-neutral-600 leading-relaxed flex-1">
                  第 1~{{ wizard.advance ?? '?' }} 名晋级；这条线就是「晋级线」，界上同分会在本赛段内开加赛决出。
                  「第 1~N 名 → 下一赛段」这条默认出口会跟着这个人数走。
                </p>
              </div>

              <div v-if="targetOptions.length === 0" class="text-[11px] text-neutral-600">
                暂无可承接赛段（下游赛段需处于规划中且未初始化）
              </div>
              <div v-else class="space-y-2">
                <div
                  v-for="(ex, ei) in wizard.exits"
                  :key="ei"
                  class="rounded-lg border border-neutral-800 bg-black/40 p-2.5 space-y-2"
                >
                  <select v-model="ex.targetStageId" class="cfg-select w-full">
                    <option v-for="t in targetOptions" :key="String(t.id)" :value="t.id">{{ t.name }}</option>
                  </select>
                  <div class="grid grid-cols-[1fr_1fr_auto] gap-2 items-center">
                    <input v-model.number="ex.rankStart" type="number" min="1" class="cfg-input w-full" placeholder="名次起" />
                    <input v-model.number="ex.rankEnd" type="number" min="1" class="cfg-input w-full" placeholder="名次止(空=末)" />
                    <button
                      v-if="wizard.exits.length > 1"
                      class="px-2.5 py-1.5 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors"
                      @click="wizard.exits.splice(ei, 1)"
                    >
                      删除
                    </button>
                  </div>
                  <p class="text-[11px] text-neutral-600">取该圈圈内第 {{ ex.rankStart ?? '?' }}~{{ ex.rankEnd ?? '末' }} 名</p>
                </div>
                <button
                  class="w-full py-2 text-[11px] rounded border border-amber-500/30 text-amber-400 bg-amber-500/10 hover:bg-amber-500/20 transition-colors"
                  @click="addWizardExit"
                >
                  ＋ 增加出口
                </button>
              </div>
            </template>
          </div>

          <div class="px-5 py-4 border-t border-neutral-800 flex items-center justify-between">
            <button
              v-if="wizardStep > 1"
              class="px-3 py-2 text-xs rounded border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
              @click="wizardStep--"
            >
              上一步
            </button>
            <span v-else></span>
            <div class="flex gap-2">
              <button
                class="px-3 py-2 text-xs rounded border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
                @click="closeWizard"
              >
                取消
              </button>
              <button
                v-if="wizardStep < 2"
                class="px-4 py-2 text-xs font-bold rounded bg-amber-500 text-neutral-900 hover:bg-amber-400 transition-colors"
                @click="wizardStep++"
              >
                下一步
              </button>
              <button
                v-else
                :disabled="saving"
                class="px-4 py-2 text-xs font-bold rounded bg-amber-500 text-neutral-900 hover:bg-amber-400 disabled:opacity-50 transition-colors"
                @click="confirmAddCircle"
              >
                {{ saving ? '保存中...' : editingCircleIndex === null ? '确认新增' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Mic } from 'lucide-vue-next';
import { AuditionConfig, StageData, ConfigMode } from './types';
import { listReferee } from '@/api/game/referee';
import { listStage } from '@/api/game/stage';
import { listRostersBySource, addRosterGroups, removeRosterGroup, updateRosterGroup, getStageRoster } from '@/api/game/stage/roster';
import { listMatchReferee } from '@/api/game/matchReferee';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';

const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
}>();

const emit = defineEmits<{
  update: [stage: StageData];
}>();

const localStage = ref<StageData>({ ...props.stage });
const currentMode = computed(() => props.mode || ConfigMode.INIT);

const config = ref<any>({
  advanceCondition: 'score',
  advanceCount: 16,
  circles: 0,
  maxScore: 10,
  circleAdvanceCounts: [],
  circleRefereeIds: []
});

/** 赛段未开始(DRAFT/PENDING)前,圈配置可反复调整;开始/结束后锁定 */
const editable = computed(() =>
  currentMode.value !== ConfigMode.STARTED
  && (localStage.value.status === 'DRAFT' || localStage.value.status === 'PENDING')
);

const referees = ref<{ id: string | number; name: string }[]>([]);
const actualCircleReferees = ref<string[]>([]);
const actualCirclePlayers = ref<(number | null)[]>([]);

interface ExitEntry {
  targetStageId: string | number;
  groupIndex: number;
  g: any;
}
const exits = ref<ExitEntry[]>([]);
const stageOptions = ref<StageData[]>([]);

const circleCount = computed(() => Math.max(0, Number(config.value.circles) || 0));
const circleQuota = (i: number) => Number(config.value.circleAdvanceCounts?.[i]) || 0;
const configuredQuota = computed(() =>
  Array.from({ length: circleCount.value }, (_, i) => circleQuota(i)).reduce((s, n) => s + n, 0)
);
const totalQuota = computed(() => (circleCount.value === 0 ? 0 : configuredQuota.value));
const circlePlayerText = (i: number) => {
  const n = actualCirclePlayers.value[i];
  return n == null ? '按实际签到' : `${n} 人`;
};

const circleRefereeText = (i: number) => {
  const actual = actualCircleReferees.value[i];
  if (actual) return actual;
  const ids: any[] = config.value.circleRefereeIds?.[i] || [];
  if (!ids.length) return '未指定';
  const nameById: Record<string, string> = {};
  referees.value.forEach((r) => {
    nameById[String(r.id)] = r.name;
  });
  return ids.map((id: any) => nameById[String(id)] || String(id)).join(' / ');
};

const stageNameOf = (id: string | number | null | undefined): string =>
  stageOptions.value.find((s) => String(s.id) === String(id))?.name || (id == null ? '未指定' : '赛段 #' + id);

/** 可选去向:沿 next 链位于本赛段之后,且仍为规划中未初始化 */
const targetOptions = computed(() => {
  const list = stageOptions.value;
  if (!list.length) return [] as StageData[];
  const byId = new Map(list.map((s) => [String(s.id), s]));
  const out: StageData[] = [];
  let cur = byId.get(String(props.stage.id));
  const visited = new Set<string>();
  while (cur && cur.nextStageId != null && !visited.has(String(cur.nextStageId))) {
    visited.add(String(cur.nextStageId));
    const next = byId.get(String(cur.nextStageId));
    if (!next) break;
    if ((next.status === 'DRAFT' || next.status === 'PENDING') && Number(next.isInitialized) !== 1) {
      out.push(next);
    }
    cur = next;
  }
  return out;
});

const exitsOfCircle = (i: number): ExitEntry[] =>
  exits.value.filter((e) => String(e.g?.zone || '') === 'ZONE-' + (i + 1));

const exitRuleText = (g: any): string => {
  // 海选出口只按圈内排名表达(名次本身决定晋级/落选)
  const rank = g.rankStart != null || g.rankEnd != null
    ? `第${g.rankStart ?? ''}~${g.rankEnd ?? '末'}名`
    : '全部名次';
  return rank;
};

// ---------------- 新增圈向导 ----------------
const wizardVisible = ref(false);
const wizardStep = ref(1);
const saving = ref(false);
const wizard = reactive({
  referees: [] as string[],
  /** 该圈晋级人数(晋级线):显式配置,不再只靠出口名次反推 */
  advance: null as number | null,
  exits: [] as {
    targetStageId: string | number | null;
    rankStart: number | null;
    rankEnd: number | null;
    /** 编辑已有出口时携带其组下标(新增出口为空) */
    groupIndex?: number;
  }[]
});
const wizardCircleNo = computed(() => circleCount.value + 1);
/** 编辑中的圈下标(null = 新增圈) */
const editingCircleIndex = ref<number | null>(null);

const openAddCircle = () => {
  const remaining = Math.max(1, (config.value.advanceCount || 0) - configuredQuota.value);
  editingCircleIndex.value = null;
  wizardStep.value = 1;
  wizard.referees = [];
  wizard.advance = remaining;
  wizard.exits = [{
    targetStageId: targetOptions.value[0]?.id ?? null,
    rankStart: 1,
    rankEnd: remaining
  }];
  wizardVisible.value = true;
};

/** 编辑已有圈:预填裁判/晋级人数/出口(赛段未开始时可用) */
const openEditCircle = (i: number) => {
  editingCircleIndex.value = i;
  wizardStep.value = 1;
  wizard.referees = (config.value.circleRefereeIds?.[i] || []).map((x: any) => String(x));
  const circleExits = exitsOfCircle(i);
  const derived = deriveCircleQuota(circleExits.map((e) => ({ rankStart: e.g?.rankStart ?? null, rankEnd: e.g?.rankEnd ?? null })));
  wizard.advance = circleQuota(i) || derived || 1;
  wizard.exits = circleExits.length > 0
    ? circleExits.map((e) => ({
        targetStageId: e.targetStageId,
        rankStart: e.g?.rankStart ?? null,
        rankEnd: e.g?.rankEnd ?? null,
        groupIndex: e.groupIndex
      }))
    : [{
        targetStageId: targetOptions.value[0]?.id ?? null,
        rankStart: 1,
        rankEnd: circleQuota(i) || 1
      }];
  wizardVisible.value = true;
};

const closeWizard = () => {
  wizardVisible.value = false;
  editingCircleIndex.value = null;
};

const toggleWizardReferee = (id: string | number) => {
  const key = String(id);
  const idx = wizard.referees.findIndex((x) => x === key);
  if (idx >= 0) {
    wizard.referees.splice(idx, 1);
  } else {
    wizard.referees.push(key);
  }
};

const addWizardExit = () => {
  wizard.exits.push({
    targetStageId: targetOptions.value[0]?.id ?? null,
    rankStart: null,
    rankEnd: null
  });
};

/**
 * 由去向推导该圈晋级人数:
 * 1) 有覆盖第 1 名的出口 → 取其名次止(如 1~8 → 8);
 * 2) 只有"第 N 名之后"的出口 → 取最靠前的名次起减 1(如 9~24 → 8);
 * 3) 无出口/无法推导 → 返回 null(调用方沿用原值或剩余名额)。
 */
const deriveCircleQuota = (
  exits: { rankStart: number | null; rankEnd: number | null }[]
): number | null => {
  const covering = exits.filter((e) => e.rankStart == null || Number(e.rankStart) <= 1);
  const withEnd = covering.filter((e) => e.rankEnd != null);
  if (withEnd.length > 0) {
    return Math.max(...withEnd.map((e) => Number(e.rankEnd)));
  }
  const starts = exits
    .map((e) => (e.rankStart == null ? null : Number(e.rankStart)))
    .filter((v): v is number => v != null);
  if (starts.length > 0) {
    return Math.max(1, Math.min(...starts) - 1);
  }
  return null;
};

const confirmAddCircle = async () => {
  const editing = editingCircleIndex.value !== null;
  const idx = editing ? (editingCircleIndex.value as number) : circleCount.value;
  const zone = 'ZONE-' + (idx + 1);
  saving.value = true;
  try {
    const quotas = Array.isArray(config.value.circleAdvanceCounts) ? [...config.value.circleAdvanceCounts] : [];
    // 优先用显式配置的晋级人数;未填时回退到"由去向名次推导"的旧口径
    const derived = deriveCircleQuota(wizard.exits);
    quotas[idx] = wizard.advance != null && Number(wizard.advance) >= 0
      ? Number(wizard.advance)
      : derived != null
        ? derived
        : Math.max(1, editing ? (circleQuota(idx) || 1) : (config.value.advanceCount || 0) - configuredQuota.value);
    const refs = Array.isArray(config.value.circleRefereeIds) ? [...config.value.circleRefereeIds] : [];
    refs[idx] = [...wizard.referees];
    config.value.circleAdvanceCounts = quotas;
    config.value.circleRefereeIds = refs;
    // 晋级人数即晋级线:覆盖第 1 名的那条出口名次止跟随它,避免两处口径打架
    const quota = quotas[idx];
    for (const ex of wizard.exits) {
      if (ex.rankStart == null || Number(ex.rankStart) <= 1) {
        ex.rankEnd = quota;
        break;
      }
    }
    if (!editing) {
      config.value.circles = idx + 1;
    }
    handleUpdate();

    for (const ex of wizard.exits) {
      if (!ex.targetStageId) continue;
      // 海选只按名次取人:结果过滤设为不限,entry_tag 由源行结算结果自动决定
      const rule = {
        sourceStageId: props.stage.id,
        resultFilter: 'ANY',
        zone,
        rankByZone: true,
        rankStart: ex.rankStart ?? null,
        rankEnd: ex.rankEnd ?? null,
        fillMode: 'AUTO',
        quota: 0
      };
      if (editing && ex.groupIndex != null) {
        // 已有出口:更新名次段
        await updateRosterGroup(ex.targetStageId, ex.groupIndex, rule as any);
      } else {
        await addRosterGroups(ex.targetStageId, {
          sourceStageId: props.stage.id,
          resultFilter: 'ANY',
          fillMode: 'AUTO',
          quota: 0,
          groups: [rule] as any
        });
      }
    }

    // 按圈出口会精确取人;若覆盖到该圈第 1 名,移除目标赛段里同源的"整单晋级"默认组,避免两者并存重复取人
    const coversTop = wizard.exits.some(
      (ex) => ex.targetStageId && (ex.rankStart == null || Number(ex.rankStart) <= 1)
    );
    if (coversTop) {
      const targets = [...new Set(wizard.exits.map((ex) => ex.targetStageId).filter((x) => x != null))];
      for (const targetId of targets) {
        try {
          const resp: any = await getStageRoster(targetId as string | number);
          const groups: any[] = resp?.data?.groups || [];
          if (groups.length <= 1) continue;
          const defaultIdx = groups.findIndex((g) =>
            String(g.sourceStageId) === String(props.stage.id)
            && !g.zone
            && (g.resultFilter || 'ADVANCE') === 'ADVANCE'
            && (g.fillMode || 'AUTO') === 'AUTO');
          if (defaultIdx >= 0) {
            await removeRosterGroup(targetId as string | number, defaultIdx);
          }
        } catch {
          // 目标赛段已锁定/无权限时忽略,不阻断出口保存
        }
      }
    }
    ElMessage.success(editing ? `第 ${idx + 1} 圈已更新` : `第 ${idx + 1} 圈已新增`);
    closeWizard();
    await loadExits();
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.msg || e?.message || (editing ? '更新圈失败' : '新增圈失败'));
  } finally {
    saving.value = false;
  }
};

// ---------------- 数据加载 ----------------
const loadReferees = async () => {
  if (props.stage.tournamentId == null) {
    referees.value = [];
    return;
  }
  try {
    const resp: any = await listReferee({ tournamentId: props.stage.tournamentId, pageNum: 1, pageSize: 99 });
    referees.value = resp?.data ?? [];
  } catch {
    referees.value = [];
  }
};

const loadStages = async () => {
  if (props.stage.tournamentId == null) return;
  try {
    const resp: any = await listStage({ tournamentId: props.stage.tournamentId, pageNum: 1, pageSize: 200 });
    stageOptions.value = (resp?.data || resp || []) as StageData[];
  } catch {
    stageOptions.value = [];
  }
};

const loadExits = async () => {
  const sid = props.stage?.id;
  if (sid == null || !/^\d+$/.test(String(sid))) {
    exits.value = [];
    return;
  }
  try {
    const resp: any = await listRostersBySource(sid);
    const rosters: any[] = resp?.data || [];
    const list: ExitEntry[] = [];
    for (const r of rosters) {
      (r.groups || []).forEach((g: any, gi: number) => {
        if (String(g.sourceStageId) === String(sid)) {
          list.push({
            targetStageId: r.targetStageId ?? r.id,
            groupIndex: gi,
            g
          });
        }
      });
    }
    exits.value = list;
  } catch {
    exits.value = [];
  }
};

const removeExit = async (e: ExitEntry) => {
  try {
    await ElMessageBox.confirm(`确认移除「${stageNameOf(e.targetStageId)} · ${exitRuleText(e.g)}」这条出口？`, '移除出口', {
      type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await removeRosterGroup(e.targetStageId, e.groupIndex);
    ElMessage.success('出口已移除');
    await loadExits();
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.msg || err?.message || '移除失败');
  }
};

const loadActualCircleReferees = async () => {
  actualCircleReferees.value = [];
  actualCirclePlayers.value = [];
  const sid = props.stage?.id;
  if (sid == null || !/^\d+$/.test(String(sid))) return;
  try {
    const [mrResp, matchResp]: any = await Promise.all([
      listMatchReferee(sid),
      listMatch({ stageId: sid, pageNum: 1, pageSize: 99 } as any)
    ]);
    const rows = mrResp?.data ?? [];
    const matches = (matchResp?.data?.data || matchResp?.data || [])
      .slice()
      .sort((a: any, b: any) => (a.displayRow ?? 0) - (b.displayRow ?? 0) || String(a.id).localeCompare(String(b.id)));
    const counts: (number | null)[] = await Promise.all(matches.map(async (m: any) => {
      try {
        const pResp: any = await listMatchParticipant({ matchId: m.id } as any);
        const parts = pResp?.data ?? [];
        return Array.isArray(parts) ? parts.length : null;
      } catch {
        return null;
      }
    }));
    actualCirclePlayers.value = counts;
    const byMatch: Record<string, string> = {};
    rows.forEach((r: any) => {
      if (r.matchId == null || !r.refereeName) return;
      const k = String(r.matchId);
      byMatch[k] = byMatch[k] ? byMatch[k] + ' / ' + r.refereeName : r.refereeName;
    });
    actualCircleReferees.value = matches.map((m: any) => byMatch[String(m.id)] || '');
  } catch {
    actualCircleReferees.value = [];
  }
};

// ---------------- 保存 ----------------
const serializeConfig = () => JSON.stringify(config.value);

const handleUpdate = () => {
  localStage.value.teamCountEnd = config.value.advanceCount;
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = 0;
  emit('update', localStage.value);
};

const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
      config.value.circles = Math.max(0, Number(config.value.circles) || 0);
      if (!Array.isArray(config.value.circleAdvanceCounts)) config.value.circleAdvanceCounts = [];
      if (!Array.isArray(config.value.circleRefereeIds)) config.value.circleRefereeIds = [];
      if (config.value.scale !== undefined) delete config.value.scale;
      // 海选为打分制,无 BO1/BO3 概念:清理历史字段,避免误展示/误保存
      if (config.value.format !== undefined) delete config.value.format;
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

watch(
  () => props.stage,
  () => {
    localStage.value = { ...props.stage };
    parseConfig();
    loadStages();
    loadExits();
    loadActualCircleReferees();
  },
  { deep: true }
);

onMounted(() => {
  parseConfig();
  loadReferees();
  loadStages();
  loadExits();
  loadActualCircleReferees();
});
</script>

<style scoped>
.stage-config {
  max-width: 900px;
  margin: 0 auto;
}
</style>
