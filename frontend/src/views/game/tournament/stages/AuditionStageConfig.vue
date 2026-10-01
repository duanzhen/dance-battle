<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Mic class="w-4 h-4" /> 海选赛配置</h3>

      <div class="space-y-6">
        <!-- 创建模式:海选为入口赛段,创建后再配置圈 -->
        <div v-if="currentMode === ConfigMode.CREATE" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
          <div class="text-xs text-neutral-500 mb-2">创建配置</div>
          <p class="text-sm text-neutral-300 leading-relaxed">
            海选为赛事入口赛段，<span class="text-amber-500 font-bold">不限制参赛人数</span>，按实际签到选手参与。
          </p>
          <p class="text-[11px] text-neutral-500 mt-2">圈配置请在创建完成后新增：每圈填一个晋级人数，它就是该圈「默认晋级出口」的名次止</p>
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
              <div class="text-xs text-neutral-500 mb-1">合计晋级</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">
                {{ circleCount === 0 ? '—' : totalQuota }}
              </div>
              <div class="text-xs text-neutral-600 mt-1">
                {{ circleCount === 0 ? '随各圈晋级人数自动汇总' : '各圈默认晋级出口之和' }}
              </div>
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
              <span class="text-[11px] text-neutral-600">每圈：第 1~N 名 → 默认去向</span>
            </div>

            <div v-if="circleCount === 0" class="rounded-lg border border-dashed border-neutral-700 bg-black/30 px-4 py-6 text-center">
              <p class="text-sm text-neutral-400">尚未配置圈</p>
              <p class="text-[11px] text-neutral-600 mt-1">点击下方按钮新增第 1 圈（裁判 / 去向）</p>
            </div>

            <div v-else class="space-y-3">
              <div v-for="i in circleCount" :key="i" class="rounded-lg border border-neutral-800 bg-black/40 p-4 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-sm font-bold text-amber-500">第 {{ i }} 圈</span>
                  <div class="flex items-center gap-2">
                    <span class="text-[11px] text-neutral-500">
                      晋级 <span class="text-white font-mono">{{ circleQuota(i - 1) }}</span> 人 · 裁判 {{ circleRefereeText(i - 1) }}
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
                <div class="space-y-1">
                  <!-- 默认晋级出口:名次段由该圈晋级人数决定,不单独配置 -->
                  <div v-if="circleQuota(i - 1) > 0" class="flex items-center gap-2 rounded bg-neutral-900/70 border border-amber-500/20 px-2 py-1">
                    <span class="text-[10px] text-amber-500/80 flex-none">默认晋级</span>
                    <span class="text-[11px] text-amber-500/90 flex-none">{{ defaultExitTargetName(i - 1) }}</span>
                    <span class="text-[11px] text-neutral-300 flex-1 min-w-0 truncate"> 第 1~{{ defaultExitRankEnd(i - 1) }} 名 </span>
                  </div>
                  <div
                    v-for="e in circleOtherExits(i - 1)"
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
                  <div v-if="circleQuota(i - 1) <= 0 && circleOtherExits(i - 1).length === 0" class="text-[11px] text-neutral-600">尚未配置出口</div>
                  <div v-else-if="circleOtherExits(i - 1).length === 0" class="text-[11px] text-neutral-600">
                    其他名次暂无去向（点「编辑」可追加）
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
            <p v-if="editable" class="text-[11px] text-neutral-600 mt-2">圈只能增加不能减少；新增圈为空场次，已签到选手与已有圈不受影响</p>
            <p v-if="editable && hasGeneratedMatches" class="text-[11px] text-amber-500/80 mt-1">
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
                <select v-if="editable" v-model.number="config.maxScore" class="cfg-select w-32" @change="handleUpdate">
                  <option :value="10">10 分制</option>
                  <option :value="100">100 分制</option>
                </select>
                <span v-else class="text-neutral-300">{{ config.maxScore }} 分制</span>
              </label>
              <!-- <span class="text-[11px] text-neutral-600">支持 2 位小数（如 9.75 / 97.50）</span> -->
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 新增圈向导:裁判 → 去向(每圈一个默认晋级出口,名次段由该圈晋级人数决定) -->
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
              <p class="text-neutral-400 leading-relaxed">
                选择该圈裁判（可多选；也可留空稍后补充）。海选的裁判只看这里——没有「赛段通用裁判组」一说， 下面列出的是本赛事全部裁判。
              </p>
              <div v-if="referees.length === 0" class="rounded-lg border border-neutral-800 bg-black/40 px-3 py-4 text-center">
                <p class="text-neutral-500">暂无裁判可选</p>
                <p class="text-[11px] text-neutral-600 mt-1">可先跳过，稍后在「裁判组」里添加裁判</p>
              </div>
              <template v-else>
                <div class="flex items-center justify-between">
                  <span class="text-[11px] text-neutral-500">
                    全部裁判 {{ referees.length }} 人 · 已选 <span class="text-white font-mono">{{ wizard.referees.length }}</span> 人
                  </span>
                  <div class="flex items-center gap-2">
                    <button
                      class="px-2 py-1 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:text-neutral-200 transition-colors"
                      @click="selectAllWizardReferees"
                    >
                      全选
                    </button>
                    <button
                      class="px-2 py-1 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:text-neutral-200 transition-colors"
                      @click="clearWizardReferees"
                    >
                      清空
                    </button>
                  </div>
                </div>
                <div class="grid grid-cols-2 gap-2 max-h-48 overflow-y-auto pr-1">
                  <button
                    v-for="r in referees"
                    :key="String(r.id)"
                    class="rounded-lg border px-3 py-2 text-left transition-all"
                    :class="
                      wizard.referees.includes(String(r.id))
                        ? 'border-amber-500 bg-amber-500/10'
                        : 'border-neutral-700 bg-neutral-900/40 hover:border-neutral-500'
                    "
                    @click="toggleWizardReferee(r.id)"
                  >
                    <div class="text-xs font-bold" :class="wizard.referees.includes(String(r.id)) ? 'text-amber-400' : 'text-neutral-200'">
                      {{ r.name }}
                    </div>
                  </button>
                </div>
              </template>
            </template>

            <!-- Step 2 去向 -->
            <template v-else>
              <p class="text-neutral-400 leading-relaxed">
                每圈先有一条<span class="text-amber-400">默认晋级出口</span>：该圈第 1~N 名进下一赛段， N
                就是该圈晋级人数。还有别的名次要去其他赛段（如第 9~24 名 → 复活赛），再加「其他出口」。
              </p>

              <!-- 默认晋级出口:晋级人数就是它的名次止,不再单独填"去向人数" -->
              <div class="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-[11px] font-bold text-amber-400">默认晋级出口</span>
                  <span class="text-[11px] text-neutral-600">名次段由晋级人数决定</span>
                </div>
                <div class="flex items-center gap-2">
                  <label class="text-[11px] text-neutral-500 flex-none">该圈晋级人数</label>
                  <input v-model.number="wizard.advance" type="number" min="1" class="cfg-input w-24" placeholder="如 8" />
                  <span class="text-[11px] text-neutral-500 flex-none">人</span>
                </div>
                <div class="flex items-center gap-2">
                  <span class="text-[11px] text-neutral-400 flex-none">第 1~{{ wizard.advance || '?' }} 名 →</span>
                  <select v-model="wizard.defaultTargetId" class="cfg-select flex-1 min-w-0">
                    <option :value="null">请选择去向</option>
                    <option v-for="t in wizardTargetOptions" :key="'d' + String(t.id)" :value="t.id">{{ t.name }}</option>
                  </select>
                </div>
                <p class="text-[11px] text-neutral-600 leading-relaxed">
                  这条线就是「晋级线」，界上同分会在本赛段内开加赛决出；各圈晋级人数会自动汇总成「合计晋级」。
                </p>
              </div>

              <!-- 其他出口:只填默认晋级段之外的名次 -->
              <div class="space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-[11px] text-neutral-400">其他出口（可选）</span>
                  <span class="text-[11px] text-neutral-600">名次从第 {{ (wizard.advance || 0) + 1 }} 名起</span>
                </div>
                <div v-if="targetOptions.length === 0" class="text-[11px] text-neutral-600">
                  暂无可承接赛段（下游赛段需处于规划中，且名单尚未确认/跳过）
                </div>
                <div v-for="(ex, ei) in wizard.exits" :key="ei" class="rounded-lg border border-neutral-800 bg-black/40 p-2.5 space-y-2">
                  <select v-model="ex.targetStageId" class="cfg-select w-full">
                    <option :value="null">请选择去向</option>
                    <option v-for="t in wizardTargetOptions" :key="String(t.id)" :value="t.id">{{ t.name }}</option>
                  </select>
                  <div class="grid grid-cols-[1fr_1fr_auto] gap-2 items-center">
                    <input
                      v-model.number="ex.rankStart"
                      type="number"
                      :min="(wizard.advance || 0) + 1"
                      class="cfg-input w-full"
                      placeholder="名次起"
                    />
                    <input
                      v-model.number="ex.rankEnd"
                      type="number"
                      :min="(wizard.advance || 0) + 1"
                      class="cfg-input w-full"
                      placeholder="名次止(空=末)"
                    />
                    <button
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
import { listMatchParticipant, listParticipantsByStage } from '@/api/game/matchParticipant';

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

/** 赛段未开始(DRAFT)前,圈配置可反复调整;开始/结束后锁定 */
const editable = computed(() => currentMode.value !== ConfigMode.STARTED && localStage.value.status === 'DRAFT');

const referees = ref<{ id: string | number; name: string }[]>([]);
const actualCircleReferees = ref<string[]>([]);
const actualCirclePlayers = ref<(number | null)[]>([]);
/** 本赛段是否已生成过对阵(决定"改圈后需重排"的提示,不能用 isInitialized 代替) */
const hasGeneratedMatches = ref(false);

interface ExitEntry {
  targetStageId: string | number;
  groupIndex: number;
  g: any;
}
const exits = ref<ExitEntry[]>([]);
const stageOptions = ref<StageData[]>([]);

const circleCount = computed(() => Math.max(0, Number(config.value.circles) || 0));
const circleQuota = (i: number) => Number(config.value.circleAdvanceCounts?.[i]) || 0;
const configuredQuota = computed(() => Array.from({ length: circleCount.value }, (_, i) => circleQuota(i)).reduce((s, n) => s + n, 0));
const totalQuota = computed(() => (circleCount.value === 0 ? 0 : configuredQuota.value));
const circlePlayerText = (i: number) => {
  const n = actualCirclePlayers.value[i];
  return n == null ? '按实际签到' : `${n} 人`;
};

/** 裁判 ID -> 裁判名 */
const refereeNameById = computed(() => {
  const map: Record<string, string> = {};
  referees.value.forEach((r) => {
    map[String(r.id)] = r.name;
  });
  return map;
});

const circleRefereeText = (i: number) => {
  const actual = actualCircleReferees.value[i];
  if (actual) return actual;
  const ids: any[] = config.value.circleRefereeIds?.[i] || [];
  if (!ids.length) return '未指定';
  return ids.map((id: any) => refereeNameById.value[String(id)] || String(id)).join(' / ');
};

const stageNameOf = (id: string | number | null | undefined): string =>
  stageOptions.value.find((s) => String(s.id) === String(id))?.name || (id == null ? '未指定' : '赛段 #' + id);

/**
 * 名单是否已被物化锁定(已确认带入或已跳过)。
 * 只有这种情况下游赛段才不能再承接新的出口来源;单看 is_initialized 会误杀
 * 历史数据里被创建流程提前置 1 的空赛段(它们既无对阵也无参赛方)。
 */
const rosterLocked = (s: StageData): boolean =>
  Array.isArray((s as any).incoming) && (s as any).incoming.some((r: any) => r?.state === 'CONFIRMED' || r?.state === 'SKIPPED');

/** 可选去向:沿 next 链位于本赛段之后,且仍为规划中、名单未锁定的赛段 */
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
    if (next.status === 'DRAFT' && !rosterLocked(next)) {
      out.push(next);
    }
    cur = next;
  }
  return out;
});

const exitsOfCircle = (i: number): ExitEntry[] => exits.value.filter((e) => String(e.g?.zone || '') === 'ZONE-' + (i + 1));

const exitRuleText = (g: any): string => {
  // 海选出口只按圈内排名表达(名次本身决定晋级/落选)
  const rank = g.rankStart != null || g.rankEnd != null ? `第${g.rankStart ?? ''}~${g.rankEnd ?? '末'}名` : '全部名次';
  return rank;
};

/** 默认晋级出口 = 覆盖第 1 名的那条出口(名次止由该圈晋级人数决定) */
const circleDefaultExit = (i: number): ExitEntry | null => {
  const list = exitsOfCircle(i).filter((e) => e.g?.rankStart == null || Number(e.g?.rankStart) <= 1);
  return list.length > 0 ? list[0] : null;
};

/** 其他出口 = 该圈除默认晋级出口之外的出口 */
const circleOtherExits = (i: number): ExitEntry[] => exitsOfCircle(i).filter((e) => e !== circleDefaultExit(i));

/** 默认晋级出口的去向:已写入就显示实际目标,还没写入就显示将要写入的下游 */
const defaultExitTargetName = (i: number): string => {
  const def = circleDefaultExit(i);
  if (def) return stageNameOf(def.targetStageId);
  const next = targetOptions.value[0];
  return next ? `${next.name}（保存后写入）` : '未指定去向';
};

/** 默认晋级出口实际写着的名次止(还没写入时按该圈晋级人数展示) */
const defaultExitRankEnd = (i: number): number => {
  const def = circleDefaultExit(i);
  return def?.g?.rankEnd != null ? Number(def.g.rankEnd) : circleQuota(i);
};

// ---------------- 新增圈向导 ----------------
const wizardVisible = ref(false);
const wizardStep = ref(1);
const saving = ref(false);
const wizard = reactive({
  referees: [] as string[],
  /** 该圈晋级人数(晋级线)= 默认晋级出口的名次止,唯一的数字输入 */
  advance: null as number | null,
  /** 默认晋级出口的去向赛段(默认取链上最近的可写入下游) */
  defaultTargetId: null as string | number | null,
  /** 编辑已有圈时,默认出口那条组的组下标(新增为空) */
  defaultGroupIndex: null as number | null,
  /** 其他出口:只填默认晋级段之外的名次 */
  exits: [] as {
    targetStageId: string | number | null;
    rankStart: number | null;
    rankEnd: number | null;
    /** 编辑已有出口时携带其组下标(新增出口为空) */
    groupIndex?: number;
  }[],
  /** 编辑时该圈现有的全部出口组,用于保存时对账删除 */
  originalExits: [] as { targetStageId: string | number; groupIndex: number }[]
});
const wizardCircleNo = computed(() => circleCount.value + 1);
/** 编辑中的圈下标(null = 新增圈) */
const editingCircleIndex = ref<number | null>(null);

/** 向导下拉可选去向:链上可写入的下游 + 已选中的(可能已锁定的)目标,避免下拉显示空白 */
const wizardTargetOptions = computed<StageData[]>(() => {
  const out = [...targetOptions.value];
  const has = new Set(out.map((t) => String(t.id)));
  const push = (id: string | number | null | undefined) => {
    if (id == null || has.has(String(id))) return;
    const found = stageOptions.value.find((s) => String(s.id) === String(id));
    if (found) {
      out.push(found);
      has.add(String(id));
    }
  };
  push(wizard.defaultTargetId);
  wizard.exits.forEach((e) => push(e.targetStageId));
  return out;
});

const nearestTargetId = (): string | number | null => targetOptions.value[0]?.id ?? null;

const openAddCircle = () => {
  const remaining = Math.max(1, (config.value.advanceCount || 0) - configuredQuota.value);
  editingCircleIndex.value = null;
  wizardStep.value = 1;
  wizard.referees = [];
  wizard.advance = remaining;
  wizard.defaultTargetId = nearestTargetId();
  wizard.defaultGroupIndex = null;
  wizard.exits = [];
  wizard.originalExits = [];
  wizardVisible.value = true;
};

/** 编辑已有圈:预填裁判/晋级人数/出口(赛段未开始时可用) */
const openEditCircle = (i: number) => {
  editingCircleIndex.value = i;
  wizardStep.value = 1;
  wizard.referees = (config.value.circleRefereeIds?.[i] || []).map((x: any) => String(x));
  const circleExits = exitsOfCircle(i);
  const def = circleDefaultExit(i);
  wizard.advance = circleQuota(i) || (def ? Number(def.g?.rankEnd) || null : null) || 1;
  wizard.defaultTargetId = def?.targetStageId ?? nearestTargetId();
  wizard.defaultGroupIndex = def ? def.groupIndex : null;
  // 覆盖第 1 名的那条就是默认晋级出口,其余都是"其他出口"
  wizard.exits = circleExits
    .filter((e) => e !== def)
    .map((e) => ({
      targetStageId: e.targetStageId,
      rankStart: e.g?.rankStart ?? null,
      rankEnd: e.g?.rankEnd ?? null,
      groupIndex: e.groupIndex
    }));
  wizard.originalExits = circleExits.map((e) => ({ targetStageId: e.targetStageId, groupIndex: e.groupIndex }));
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

/** 全选本赛事全部裁判 / 清空(海选裁判只看圈配置,常用一键全上) */
const selectAllWizardReferees = () => {
  wizard.referees = referees.value.map((r) => String(r.id));
};

const clearWizardReferees = () => {
  wizard.referees = [];
};

const addWizardExit = () => {
  wizard.exits.push({
    targetStageId: nearestTargetId(),
    rankStart: (Number(wizard.advance) || 1) + 1,
    rankEnd: null
  });
};

/** 海选出口规则:只按圈内名次取人(结果过滤不限,entry_tag 由源行结算结果决定) */
const buildExitRule = (idx: number, rankStart: number | null, rankEnd: number | null) => ({
  sourceStageId: props.stage.id,
  resultFilter: 'ANY',
  zone: 'ZONE-' + (idx + 1),
  rankByZone: true,
  rankStart,
  rankEnd,
  fillMode: 'AUTO',
  quota: 0
});

/**
 * 该圈默认晋级出口已精确到名次段,摘掉目标赛段上同源的「整单晋级」默认组,
 * 避免出口列表里同时出现"全部名次"和"第 1~N 名"两条。
 */
const dropGenericAdvanceGroup = async (targetStageId: string | number) => {
  try {
    const resp: any = await getStageRoster(targetStageId);
    const groups: any[] = resp?.data?.groups || [];
    if (groups.length <= 1) return;
    const defaultIdx = groups.findIndex(
      (g) =>
        String(g.sourceStageId) === String(props.stage.id) &&
        !g.zone &&
        (g.resultFilter || 'ADVANCE') === 'ADVANCE' &&
        (g.fillMode || 'AUTO') === 'AUTO'
    );
    if (defaultIdx >= 0) {
      await removeRosterGroup(targetStageId, defaultIdx);
    }
  } catch {
    // 目标赛段已锁定/无权限时忽略,不阻断出口保存
  }
};

/**
 * 出口对账落库:把该圈在目标赛段上的出口组对齐到「默认晋级出口 + 其他出口」。
 * 保留的原地更新、缺的新增、多余的删除;删除按同目标内下标倒序,避免下标位移。
 */
const syncCircleExits = async (idx: number, advance: number) => {
  interface Desired {
    targetStageId: string | number;
    rankStart: number | null;
    rankEnd: number | null;
    /** 对应已有组的原下标(新增为 null) */
    keepIndex: number | null;
  }
  const desired: Desired[] = [];
  if (wizard.defaultTargetId != null) {
    desired.push({
      targetStageId: wizard.defaultTargetId,
      rankStart: 1,
      rankEnd: advance,
      keepIndex: wizard.defaultGroupIndex
    });
  }
  wizard.exits.forEach((ex) => {
    if (ex.targetStageId == null) return;
    desired.push({
      targetStageId: ex.targetStageId,
      rankStart: ex.rankStart ?? null,
      rankEnd: ex.rankEnd ?? null,
      keepIndex: ex.groupIndex ?? null
    });
  });

  const origByTarget = new Map<string, number[]>();
  wizard.originalExits.forEach((o) => {
    const key = String(o.targetStageId);
    if (!origByTarget.has(key)) origByTarget.set(key, []);
    origByTarget.get(key)!.push(o.groupIndex);
  });
  const keepByTarget = new Map<string, Set<number>>();
  desired.forEach((d) => {
    if (d.keepIndex == null) return;
    const key = String(d.targetStageId);
    if (!keepByTarget.has(key)) keepByTarget.set(key, new Set());
    keepByTarget.get(key)!.add(d.keepIndex);
  });

  // 1) 删除本圈多余/改到别处的出口(倒序,避免同目标内下标位移)
  const shiftedIndex = new Map<string, number>();
  for (const [target, origIdxList] of origByTarget) {
    const keep = keepByTarget.get(target) || new Set<number>();
    const drop = origIdxList.filter((i) => !keep.has(i));
    for (const i of [...drop].sort((a, b) => b - a)) {
      await removeRosterGroup(target, i);
    }
    const dropAsc = [...drop].sort((a, b) => a - b);
    origIdxList.forEach((old) => {
      if (!keep.has(old)) return;
      shiftedIndex.set(target + '|' + old, old - dropAsc.filter((d) => d < old).length);
    });
  }

  // 2) 保留的原地更新,缺的新增
  for (const d of desired) {
    const target = String(d.targetStageId);
    const rule = buildExitRule(idx, d.rankStart, d.rankEnd);
    const newIndex = d.keepIndex == null ? undefined : shiftedIndex.get(target + '|' + d.keepIndex);
    if (newIndex === undefined) {
      await addRosterGroups(d.targetStageId, {
        sourceStageId: props.stage.id,
        resultFilter: 'ANY',
        fillMode: 'AUTO',
        quota: 0,
        groups: [rule] as any
      });
    } else {
      await updateRosterGroup(d.targetStageId, newIndex, rule as any);
    }
  }
};

/** 其他出口的名次段必须落在默认晋级段之后,且彼此不重叠 */
const validateCustomExits = (advance: number): string | null => {
  const seen: { start: number; end: number }[] = [];
  for (const ex of wizard.exits) {
    if (ex.targetStageId == null) return '每条「其他出口」都要选择去向赛段';
    const start = Number(ex.rankStart);
    if (!Number.isFinite(start) || start < 1) return '其他出口的「名次起」必须是不小于 1 的整数';
    if (start <= advance) {
      return `其他出口的第 ${start} 名与默认晋级段（第 1~${advance} 名）重叠，请从第 ${advance + 1} 名起`;
    }
    const end = ex.rankEnd == null ? Number.MAX_SAFE_INTEGER : Number(ex.rankEnd);
    if (!Number.isFinite(end) || end < start) return '其他出口的「名次止」不能小于「名次起」';
    for (const o of seen) {
      if (start <= o.end && o.start <= end) return '两条「其他出口」的名次段重叠了，请检查名次范围';
    }
    seen.push({ start, end });
  }
  return null;
};

const confirmAddCircle = async () => {
  const editing = editingCircleIndex.value !== null;
  const idx = editing ? (editingCircleIndex.value as number) : circleCount.value;
  const advance = Number(wizard.advance);
  if (!Number.isFinite(advance) || advance < 1) {
    ElMessage.error('请填写该圈晋级人数（至少 1 人）');
    return;
  }
  const invalid = validateCustomExits(advance);
  if (invalid) {
    ElMessage.error(invalid);
    return;
  }
  if (wizard.defaultTargetId == null && wizardTargetOptions.value.length > 0) {
    ElMessage.error('请选择「默认晋级出口」的去向赛段');
    return;
  }
  const canSyncExits = wizard.defaultTargetId != null;
  saving.value = true;
  try {
    // 1) 规则:每圈晋级人数(= 该圈默认晋级出口的名次止) + 裁判
    const quotas = Array.isArray(config.value.circleAdvanceCounts) ? [...config.value.circleAdvanceCounts] : [];
    quotas[idx] = advance;
    const refs = Array.isArray(config.value.circleRefereeIds) ? [...config.value.circleRefereeIds] : [];
    refs[idx] = [...wizard.referees];
    config.value.circleAdvanceCounts = quotas;
    config.value.circleRefereeIds = refs;
    if (!editing) {
      config.value.circles = idx + 1;
    }
    // 总晋级名额不再单独填:各圈晋级人数之和就是它
    config.value.advanceCount = quotas.slice(0, Math.max(circleCount.value, idx + 1)).reduce((sum: number, n: any) => sum + (Number(n) || 0), 0);
    handleUpdate();

    // 2) 出口落库:默认晋级出口 + 其他出口(对账增删改)
    if (!canSyncExits) {
      ElMessage.warning('该圈晋级人数已保存；当前没有可承接赛段，默认晋级出口未写入名单');
    } else {
      await syncCircleExits(idx, advance);
      await dropGenericAdvanceGroup(wizard.defaultTargetId as string | number);
      ElMessage.success(editing ? `第 ${idx + 1} 圈已更新` : `第 ${idx + 1} 圈已新增`);
    }
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
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消'
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
  hasGeneratedMatches.value = false;
  const sid = props.stage?.id;
  if (sid == null || !/^\d+$/.test(String(sid))) return;
  try {
    const [mrResp, matchResp]: any = await Promise.all([listMatchReferee(sid), listMatch({ stageId: sid, pageNum: 1, pageSize: 99 } as any)]);
    const rows = mrResp?.data ?? [];
    const matches = (matchResp?.data?.data || matchResp?.data || [])
      .slice()
      .sort((a: any, b: any) => (a.displayRow ?? 0) - (b.displayRow ?? 0) || String(a.id).localeCompare(String(b.id)));
    hasGeneratedMatches.value = matches.length > 0;
    // 每圈人数按赛段一次取回再分组(此前逐圈一次请求)
    let partsByMatch: Record<string, any[]> = {};
    try {
      partsByMatch = await listParticipantsByStage(sid);
    } catch {
      partsByMatch = {};
    }
    const counts: (number | null)[] = matches.map((m: any) => {
      const parts = partsByMatch[String(m.id)];
      return Array.isArray(parts) ? parts.length : null;
    });
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
