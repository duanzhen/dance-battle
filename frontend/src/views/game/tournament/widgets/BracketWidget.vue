<template>
  <div class="w-full h-full" :style="bracketStyle">
    <!-- 查看模式:对阵树(按 displayZone 分左右两列) -->
    <div v-if="mode !== 'edit'" class="w-full h-full overflow-hidden flex">
      <div v-if="loading" class="w-full flex items-center justify-center text-neutral-500 text-xs">加载中...</div>
      <!-- 决赛:左半区晋级 | 冠军 | 右半区晋级 三框布局 -->
      <div v-else-if="isFinal" class="w-full h-full flex flex-col items-center justify-between gap-3 px-2 py-2">
        <!-- 冠军卡:顶部,窄宽度 -->
        <div class="final-card champion-card" :class="{ 'final-win': !!champion }">
          <span class="name">{{ champion?.name || '' }}</span>
        </div>
        <!-- 左右半区:下方 -->
        <div class="flex items-center justify-between min-h-0 w-full">
          <div class="final-card" :class="{ 'final-win': champion && champion.competitorId === finalists.left?.competitorId }">
            <span class="name" :title="finalists.left?.name || ''">{{ finalists.left?.name || '' }}</span>
          </div>
          <div class="final-card" :class="{ 'final-win': champion && champion.competitorId === finalists.right?.competitorId }">
            <span class="name" :title="finalists.right?.name || ''">{{ finalists.right?.name || '' }}</span>
          </div>
        </div>
      </div>
      <!-- 半决赛(4人2场):四个参赛者放在四角 -->
      <div v-else-if="isSemi" class="w-full h-full flex items-stretch justify-between gap-2 px-2 py-2">
        <div class="flex-none flex flex-col justify-between items-center min-h-0">
          <div class="final-card" :class="{ 'final-win': semi.left?.top?.win }">
            <span class="name" :title="semi.left?.top?.name || ''">{{ semi.left?.top?.name || '' }}</span>
          </div>
          <div class="final-card" :class="{ 'final-win': semi.left?.bottom?.win }">
            <span class="name" :title="semi.left?.bottom?.name || ''">{{ semi.left?.bottom?.name || '' }}</span>
          </div>
        </div>
        <div style="flex: 1; min-width: 0;"></div>
        <div class="flex-none flex flex-col justify-between items-center min-h-0">
          <div class="final-card" :class="{ 'final-win': semi.right?.top?.win }">
            <span class="name" :title="semi.right?.top?.name || ''">{{ semi.right?.top?.name || '' }}</span>
          </div>
          <div class="final-card" :class="{ 'final-win': semi.right?.bottom?.win }">
            <span class="name" :title="semi.right?.bottom?.name || ''">{{ semi.right?.bottom?.name || '' }}</span>
          </div>
        </div>
      </div>
      <div v-else-if="stageModeError" class="w-full flex items-center justify-center text-neutral-600 text-xs text-center px-2">
        对战树仅支持淘汰赛赛段,请在编辑模式重新绑定
      </div>
      <div v-else-if="leftSlots.length === 0 && rightSlots.length === 0" class="w-full flex items-center justify-center text-neutral-600 text-xs text-center px-2">
        {{ emptyHint }}
      </div>
      <div v-else class="w-full h-full flex gap-1.5 px-2 py-2">
        <!-- 左列 -->
        <div
          class="bracket-col flex-none flex flex-col gap-3 min-h-0 overflow-y-auto custom-scrollbar-y items-center"
          :class="leftSlots.length === 1 ? 'justify-center' : 'justify-between'"
        >
          <template v-for="(s, i) in leftSlots" :key="'l' + i">
            <div class="final-card" :class="{ 'final-win': s.leftWin, 'final-bye': s.leftBye }">
              <span class="name" :title="s.leftSrc ? s.leftName + ' · ' + s.leftSrc : s.leftName">{{ s.leftName || '' }}</span>
            </div>
            <div class="final-card" :class="{ 'final-win': s.rightWin, 'final-bye': s.rightBye }">
              <span class="name" :title="s.rightSrc ? s.rightName + ' · ' + s.rightSrc : s.rightName">{{ s.rightName || '' }}</span>
            </div>
          </template>
        </div>
        <!-- 中间留白(透明):供后续赛段的对战树 widget 叠放衔接 -->
        <div style="flex: 1; min-width: 0;"></div>
        <!-- 右列 -->
        <div
          class="bracket-col flex-none flex flex-col gap-3 min-h-0 overflow-y-auto custom-scrollbar-y items-center"
          :class="rightSlots.length === 1 ? 'justify-center' : 'justify-between'"
        >
          <template v-for="(s, i) in rightSlots" :key="'r' + i">
            <div class="final-card" :class="{ 'final-win': s.leftWin, 'final-bye': s.leftBye }">
              <span class="name" :title="s.leftSrc ? s.leftName + ' · ' + s.leftSrc : s.leftName">{{ s.leftName || '' }}</span>
            </div>
            <div class="final-card" :class="{ 'final-win': s.rightWin, 'final-bye': s.rightBye }">
              <span class="name" :title="s.rightSrc ? s.rightName + ' · ' + s.rightSrc : s.rightName">{{ s.rightName || '' }}</span>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 编辑模式:配置赛段绑定 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">对战树属性</span>
        <StageSelector
          label="绑定赛段"
          :model-value="(stageId as any) ?? null"
          only-mode="KNOCKOUT,ARENA"
          @update:model-value="$emit('update:stageId', $event)"
        />
        <p class="text-[10px] text-neutral-600 mt-2">对战树绑定淘汰赛赛段为标准对战树(配对按 SEED 标准种子对位或 SEQUENTIAL 相邻配对,已生成场次胜者高亮);绑定擂台赛段时展示该赛段 8 强名单(标准种子摆位,仅供展示)。</p>
      </section>
      <section>
        <span class="section-title">样式配置</span>
        <div class="space-y-3 mt-2">
          <ColorInput label="文字颜色" :model-value="textColor || '#ffffff'" @update:model-value="$emit('update:textColor', $event)" />
          <ColorInput label="边框颜色" :model-value="borderColor || '#404040'" @update:model-value="$emit('update:borderColor', $event)" />
          <div>
            <div class="flex items-center justify-between mb-1">
              <span class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">背景颜色</span>
              <button
                v-if="bgColor"
                class="text-[10px] text-neutral-500 hover:text-amber-400"
                @click="$emit('update:bgColor', '')"
              >设为透明</button>
            </div>
            <ColorInput :model-value="bgColor || '#000000'" @update:model-value="$emit('update:bgColor', $event)" />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import StageSelector from '../stages/StageSelector.vue';
import ColorInput from './common/ColorInput.vue';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import { listCompetitor } from '@/api/game/competitor';
import { getStage, getStagePreBracket } from '@/api/game/stage';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  textColor?: string;
  borderColor?: string;
  bgColor?: string;
}>();
defineEmits<{
  'update:stageId': [value: string | number | null];
  'update:textColor': [value: string];
  'update:borderColor': [value: string];
  'update:bgColor': [value: string];
}>();

// 样式配置:通过 CSS 变量作用于全部对战卡;未配置时回退默认样式(白字/灰边/透明底)
const bracketStyle = computed(() => ({
  '--bracket-text': props.textColor || undefined,
  '--bracket-border': props.borderColor || undefined,
  '--bracket-bg': props.bgColor || undefined
}));

const loading = ref(false);
const loadedOnce = ref(false);
const competitors = ref<any[]>([]);
const matches = ref<any[]>([]);
const participantsByMatch = ref<Record<string, any[]>>({});
const preStatus = ref('');
const preSeeds = ref<any[]>([]);
const prePairs = ref<any[]>([]);
const isFinal = ref(false);
const isSemi = ref(false);
const stageModeError = ref(false);
const stageMode = ref('');
/** 上一赛段ID:预排/决赛来源解析依赖上一赛段,收到其事件时也需刷新 */
const prevStageId = ref<string | number | null>(null);
const prevZoneMap = ref<Record<string, string>>({});
const stagePairingMode = ref('');
const stageTeamCountStart = ref(0);

// 标准种子摆位(与后端 KnockoutGenerator 一致):8/16/32 查表,其余递归
const SEED_LAYOUT: Record<number, number[]> = {
  8: [1, 8, 4, 5, 3, 6, 2, 7],
  16: [1, 16, 8, 9, 5, 12, 4, 13, 3, 14, 6, 11, 7, 10, 2, 15],
  32: [1, 32, 9, 24, 16, 17, 8, 25, 5, 28, 13, 20, 12, 21, 4, 29, 3, 30, 11, 22, 14, 19, 6, 27, 7, 26, 15, 18, 10, 23, 2, 31]
};
const seedPositions = (n: number): number[] => {
  if (n <= 1) return [1];
  const prev = seedPositions(Math.floor(n / 2));
  const res: number[] = [];
  for (let i = 0; i < prev.length; i++) {
    res.push(prev[i], n + 1 - prev[i]);
  }
  return res;
};
const seedLayout = (n: number): number[] => SEED_LAYOUT[n] || seedPositions(n);
const nextPow2 = (v: number): number => {
  let p = 1;
  while (p < v) p <<= 1;
  return p;
};

const compById = computed(() => {
  const m: Record<string, any> = {};
  competitors.value.forEach((c) => { m[c.id] = c; });
  return m;
});

const loadData = async () => {
  if (!props.stageId) {
    competitors.value = [];
    matches.value = [];
    stageMode.value = '';
    prevStageId.value = null;
    return;
  }
  if (!loadedOnce.value) {
    loading.value = true;
  }
  try {
    // 读赛段配置:赛制/配对模式(SEED/SEQUENTIAL)
    let stageInfo: any = null;
    try {
      stageInfo = await getStage(props.stageId);
      prevStageId.value = stageInfo?.data?.prevStageId ?? null;
      stagePairingMode.value =
        JSON.parse(stageInfo?.data?.ruleConfig || '{}')?.knockout?.pairingMode || '';
      stageTeamCountStart.value = Number(stageInfo?.data?.teamCountStart) || 0;
    } catch (e) {
      prevStageId.value = null;
      stagePairingMode.value = '';
      stageTeamCountStart.value = 0;
    }
    // 对战树支持淘汰赛(标准对战树)与擂台赛(8 强名单展示);其他赛制提示不支持
    const boundMode = stageInfo?.data?.stageMode;
    stageMode.value = boundMode || '';
    if (boundMode && boundMode !== 'KNOCKOUT' && boundMode !== 'ARENA') {
      stageModeError.value = true;
      competitors.value = [];
      matches.value = [];
      participantsByMatch.value = {};
      preStatus.value = '';
      preSeeds.value = [];
      prePairs.value = [];
      isFinal.value = false;
      isSemi.value = false;
      stageMode.value = '';
      loadedOnce.value = true;
      return;
    }
    stageModeError.value = false;
    // 拉该赛段参赛方(按种子顺位)—— 即使未生成对阵也能按种子预排配对
    try {
      const cr: any = await listCompetitor({ stageId: props.stageId, pageNum: 1, pageSize: 999 } as any);
      competitors.value = ((cr?.data?.data || cr?.data) || [])
        .slice()
        .sort((a: any, b: any) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER));
    } catch (e) {
      competitors.value = [];
    }
    // 拉场次(已生成时含比分/排名/结果)
    const res: any = await listMatch({ stageId: props.stageId, pageNum: 1, pageSize: 999 } as any);
    matches.value = res?.data?.data || res?.data || [];
    participantsByMatch.value = {};
    await Promise.all(
      matches.value.map(async (m: any) => {
        try {
          const pr: any = await listMatchParticipant({ matchId: m.id, pageNum: 1, pageSize: 99 } as any);
          participantsByMatch.value[m.id] = pr?.data?.data || pr?.data || [];
        } catch {
          participantsByMatch.value[m.id] = [];
        }
      })
    );
    // 本赛段未生成(无参赛方也无场次)时:拉取上一赛段胜者的预排对阵
    if (matches.value.length === 0 && competitors.value.length === 0) {
      try {
        const pb: any = await getStagePreBracket(props.stageId);
        const data = pb.data || {};
        preStatus.value = data.status || '';
        preSeeds.value = data.seededCompetitors || [];
        prePairs.value = data.pairs || [];
      } catch (e) {
        preStatus.value = '';
        preSeeds.value = [];
        prePairs.value = [];
      }
    } else {
      preStatus.value = '';
      preSeeds.value = [];
      prePairs.value = [];
    }
    // 阶段判定直接读赛段配置(不用数场次/预排数量):
    // 决赛 = KNOCKOUT 且晋级 1 人(单场三框);半决赛 = KNOCKOUT 且 4 人 2 场(四角)
    let rc: any = {};
    try {
      rc = JSON.parse(stageInfo?.data?.ruleConfig || '{}');
    } catch (e) {
      rc = {};
    }
    const ko = rc?.knockout || {};
    const startN = Number(stageInfo?.data?.teamCountStart ?? ko.teamsCount);
    const endN = Number(stageInfo?.data?.teamCountEnd ?? ko.advanceCount);
    const isKnockout = stageInfo?.data?.stageMode === 'KNOCKOUT';
    isFinal.value = isKnockout && endN === 1;
    isSemi.value = isKnockout && startN === 4;
    prevZoneMap.value = {};
    if (isFinal.value && matches.value.length === 1) {
      try {
        // 上一赛段(半决赛)按 displayZone 记录各参赛方来源:LEFT/RIGHT
        if (prevStageId.value) {
          const pm: any = await listMatch({ stageId: prevStageId.value, pageNum: 1, pageSize: 999 } as any);
          const prevMatches = pm?.data?.data || pm?.data || [];
          for (const m of prevMatches) {
            try {
              const pr: any = await listMatchParticipant({ matchId: m.id, pageNum: 1, pageSize: 99 } as any);
              const parts = pr?.data?.data || pr?.data || [];
              parts.forEach((p: any) => {
                if (p.competitorId != null) {
                  prevZoneMap.value[p.competitorId] = m.displayZone || 'LEFT';
                }
              });
            } catch (e) {
              // 忽略单个场次来源解析失败
            }
          }
        }
      } catch (e) {
        // 决赛来源解析失败则按槽位兜底
      }
    }
    loadedOnce.value = true;
  } catch (e) {
    console.error('BracketWidget 加载失败', e);
  } finally {
    loading.value = false;
  }
};

interface BracketSlot {
  matchId?: string;
  zone: string;
  order: number;
  name: string;
  status: string;
  leftName: string;
  rightName: string;
  leftScore: string;
  rightScore: string;
  leftWin: boolean;
  rightWin: boolean;
  leftBye?: boolean;
  rightBye: boolean;
  leftSrc?: string;
  rightSrc?: string;
}

const nameOf = (cid: any) => {
  if (cid == null) return '';
  const c = compById.value[cid];
  return c?.name || `#${cid}`;
};

const isWinner = (p: any) => p?.outcomeStatus === 'WIN' || (p?.rankInMatch === 1 && p?.scoreValue != null);

// 擂台赛段展示:8 强参赛者按标准种子摆位排成 4 对(左 2 右 2),仅供展示,不对应对决场次
const arenaSlots = computed<BracketSlot[]>(() => {
  const count = competitors.value.length;
  // 名单尚未生成(16 强未结算/未开始擂台赛段):只搭空骨架,不显示"轮空"
  // (轮空只对真实对阵有意义,开赛前不可能预知轮空)
  if (count === 0) {
    const bracketSize = Math.max(2, nextPow2(stageTeamCountStart.value || 8));
    const pairCount = Math.max(1, bracketSize / 2);
    const half = Math.ceil(pairCount / 2);
    return Array.from({ length: pairCount }, (_, i) => ({
      zone: i < half ? 'LEFT' : 'RIGHT',
      order: i % half,
      name: '8强',
      status: 'PENDING',
      leftName: '',
      rightName: '',
      leftScore: '',
      rightScore: '',
      leftWin: false,
      rightWin: false,
      rightBye: false,
      leftSrc: '',
      rightSrc: ''
    } as BracketSlot));
  }
  const bracketSize = Math.max(2, nextPow2(count || stageTeamCountStart.value || 8));
  const pairCount = Math.max(1, bracketSize / 2);
  const half = Math.ceil(pairCount / 2);
  const layout = seedLayout(bracketSize);
  return Array.from({ length: pairCount }, (_, i) => {
    const leftIdx = layout[2 * i] - 1;
    const rightIdx = layout[2 * i + 1] - 1;
    const left = leftIdx < count ? competitors.value[leftIdx] : null;
    const right = rightIdx < count ? competitors.value[rightIdx] : null;
    return {
      zone: i < half ? 'LEFT' : 'RIGHT',
      order: i % half,
      name: '8强',
      status: 'PENDING',
      leftName: left?.name || '',
      rightName: right?.name || '',
      leftScore: '',
      rightScore: '',
      leftWin: false,
      rightWin: false,
      rightBye: false,
      leftSrc: '',
      rightSrc: ''
    } as BracketSlot;
  });
});

// 每个对战卡严格对应一个 t_match(按场次渲染,含比分/胜者);未生成对阵时按种子预排
const bracketSlots = computed<BracketSlot[]>(() => {
  // 擂台赛段:8 强名单展示
  if (stageMode.value === 'ARENA') {
    return arenaSlots.value;
  }
  if (matches.value.length > 0) {
    return matches.value.map((m: any) => {
      const ss = (participantsByMatch.value[m.id] || [])
        .slice()
        .sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));
      const left = ss[0];
      const right = ss[1];
      return {
        matchId: String(m.id),
        zone: m.displayZone || 'LEFT',
        order: m.displayRow ?? 0,
        name: m.name || '',
        status: m.status || 'PENDING',
        leftName: left?.competitorId == null ? '轮空' : (nameOf(left.competitorId) || ''),
        rightName: right?.competitorId == null ? '轮空' : (nameOf(right.competitorId) || ''),
        leftScore: left?.scoreValue == null ? '' : String(left.scoreValue),
        rightScore: right?.scoreValue == null ? '' : String(right.scoreValue),
        leftWin: !!left && isWinner(left),
        rightWin: !!right && isWinner(right),
        leftBye: left?.competitorId == null,
        rightBye: right?.competitorId == null,
        leftSrc: '',
        rightSrc: ''
      } as BracketSlot;
    });
  }

  // 下一赛段预排:上一赛段胜者已按种子放入对应位置(未最终确认也可显示)
  if (prePairs.value.length > 0) {
    return prePairs.value.map((p: any) => ({
      zone: p.zone || 'LEFT',
      order: p.position - 1,
      name: `预排 ${p.position}`,
      status: 'PENDING',
      // 对应场次未打完(TBD)留空,模板回退显示「待定」;无对应场次/无参赛方=轮空
      leftName: p.left?.name || (p.leftStatus === 'BYE' ? '轮空' : ''),
      rightName: p.right?.name || (p.rightStatus === 'BYE' ? '轮空' : ''),
      leftScore: '',
      rightScore: '',
      leftWin: false,
      rightWin: false,
      leftBye: p.leftStatus === 'BYE',
      rightBye: p.rightStatus === 'BYE',
      leftSrc: p.left?.sourceMatchName || '',
      rightSrc: p.right?.sourceMatchName || ''
    } as BracketSlot));
  }

  // 尚未生成对阵:按种子顺位预排(前半 LEFT、后半 RIGHT)
  const count = competitors.value.length;
  if (count === 0) {
    // 无参赛方也无预排(如上一赛段未结算):按赛段人数生成待定骨架,
    // 保证 32/16/8 等对战树在层叠场景里也有可见的卡片结构
    const slotCount = Math.max(2, stageTeamCountStart.value);
    const pairs = Math.floor(slotCount / 2);
    if (pairs > 0) {
      const half = Math.ceil(pairs / 2);
      return Array.from({ length: pairs }, (_, i) => ({
        zone: i < half ? 'LEFT' : 'RIGHT',
        order: i % half,
        name: '待对阵',
        status: 'PENDING',
        leftName: '',
        rightName: '',
        leftScore: '',
        rightScore: '',
        leftWin: false,
        rightWin: false,
        rightBye: false,
        leftSrc: '',
        rightSrc: ''
      } as BracketSlot));
    }
    return [];
  }
  if (stagePairingMode.value === 'SEED') {
    // SEED:标准种子对位(1-16、2-15…),与后端生成一致
    const bracketSize = Math.max(2, nextPow2(count));
    const layout = seedLayout(bracketSize);
    const pairCount = Math.max(1, bracketSize / 2);
    const half = Math.ceil(pairCount / 2);
    return Array.from({ length: pairCount }, (_, i) => {
      const leftIdx = layout[2 * i] - 1;
      const rightIdx = layout[2 * i + 1] - 1;
      const left = leftIdx < count ? competitors.value[leftIdx] : null;
      const right = rightIdx < count ? competitors.value[rightIdx] : null;
      return {
        zone: i < half ? 'LEFT' : 'RIGHT',
        order: i % half,
        name: '待对阵',
        status: 'PENDING',
        leftName: left?.name || '轮空',
        rightName: right?.name || '轮空',
        leftScore: '',
        rightScore: '',
        leftWin: false,
        rightWin: false,
        leftBye: !left,
        rightBye: !right,
        leftSrc: '',
        rightSrc: ''
      } as BracketSlot;
    });
  }
  // 默认 SEQUENTIAL:1-2、3-4…
  const pairs = Math.max(1, Math.ceil(count / 2));
  const half = Math.ceil(pairs / 2);
  return Array.from({ length: pairs }, (_, i) => {
    const left = competitors.value[i * 2];
    const right = competitors.value[i * 2 + 1];
    return {
      zone: i < half ? 'LEFT' : 'RIGHT',
      order: i % half,
      name: '待对阵',
      status: 'PENDING',
      leftName: left?.name || '',
      rightName: right?.name || '轮空',
      leftScore: '',
      rightScore: '',
      leftWin: false,
      rightWin: false,
      leftBye: false,
      rightBye: !right,
      leftSrc: '',
      rightSrc: ''
    } as BracketSlot;
  });
});

const emptyHint = computed(() => {
  if (stageMode.value === 'ARENA') {
    return '等待 16 强结算,晋级 8 强名单生成后展示';
  }
  if (preStatus.value === 'WAIT_PREV') {
    return '等待上一赛段结算,胜者产生后自动预排';
  }
  if (preStatus.value === 'NO_PREV') {
    return '未关联上一赛段,暂无可预排对阵';
  }
  if (preStatus.value === 'UNSUPPORTED') {
    return '仅淘汰赛之间支持预排';
  }
  return props.stageId ? '该赛段暂无参赛方' : '未绑定赛段(编辑里选赛段)';
});

// 决赛三框:左/右半区晋级选手(按来源 displayZone 归位,兜底按槽位)
const finalists = computed(() => {
  const out: any = { left: null, right: null };
  if (!isFinal.value) {
    return out;
  }
  const m = matches.value[0];
  if (!m) {
    // 决赛未生成场次:直接用预排对阵放左右(上一赛段胜者按位置落位,待定留空)
    const pair = prePairs.value[0];
    if (pair) {
      const toPreCard = (s: any, st: string) => {
        if (s) {
          return { competitorId: s.competitorId, name: s.name || '', score: '' };
        }
        return { competitorId: null, name: st === 'BYE' ? '轮空' : '\u00A0', score: '' };
      };
      out.left = toPreCard(pair.left, pair.leftStatus);
      out.right = toPreCard(pair.right, pair.rightStatus);
    }
    return out;
  }
  const ss = (participantsByMatch.value[m.id] || [])
    .slice()
    .sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));
  const sourceOf: Record<string, string> = {};
  competitors.value.forEach((c: any) => {
    if (c.sourceCompetitorId) {
      sourceOf[c.id] = c.sourceCompetitorId;
    }
  });
  const toCard = (p: any) => ({
    competitorId: p.competitorId,
    name: nameOf(p.competitorId),
    score: p.scoreValue == null ? '' : String(p.scoreValue)
  });
  const a = ss[0];
  const b = ss[1];
  if (a) {
    const side = prevZoneMap.value[sourceOf[a.competitorId]] || 'LEFT';
    out[side === 'RIGHT' ? 'right' : 'left'] = toCard(a);
  }
  if (b) {
    const side = prevZoneMap.value[sourceOf[b.competitorId]] || 'RIGHT';
    out[side === 'RIGHT' ? 'right' : 'left'] = toCard(b);
  }
  return out;
});

// 决赛冠军:决赛场次结算后的胜者
const champion = computed(() => {
  const m = matches.value[0];
  if (!m || !isFinal.value || m.status !== 'SETTLED') {
    return null;
  }
  const ss = participantsByMatch.value[m.id] || [];
  const w = ss.find((p: any) => p.outcomeStatus === 'WIN' || (p.rankInMatch === 1 && p.scoreValue != null));
  return w
    ? { competitorId: w.competitorId, name: nameOf(w.competitorId), score: w.scoreValue == null ? '' : String(w.scoreValue) }
    : null;
});

// 半决赛四角:两场各两名参赛者,左上/左下、右上/右下
const semi = computed(() => {
  const out: any = { left: null, right: null };
  if (!isSemi.value) {
    return out;
  }
  if (matches.value.length === 2) {
    const ms = matches.value.slice().sort((a: any, b: any) => (a.displayRow ?? 0) - (b.displayRow ?? 0));
    const build = (m: any) => {
      const ss = (participantsByMatch.value[m.id] || [])
        .slice()
        .sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));
      return {
        top: ss[0]
          ? { competitorId: ss[0].competitorId, name: nameOf(ss[0].competitorId), win: isWinner(ss[0]) }
          : null,
        bottom: ss[1]
          ? { competitorId: ss[1].competitorId, name: nameOf(ss[1].competitorId), win: isWinner(ss[1]) }
          : null
      };
    };
    out.left = ms[0] ? build(ms[0]) : null;
    out.right = ms[1] ? build(ms[1]) : null;
    return out;
  }
  // 未生成正式场次(预排 2 对 / 种子 4 人):两列各一对 → 左上左下、右上右下
  const toCard = (name: string, win: boolean) => ({ competitorId: null, name, win });
  const lp = leftSlots.value[0];
  const rp = rightSlots.value[0];
  out.left = lp ? { top: toCard(lp.leftName, lp.leftWin), bottom: toCard(lp.rightName, lp.rightWin) } : null;
  out.right = rp ? { top: toCard(rp.leftName, rp.leftWin), bottom: toCard(rp.rightName, rp.rightWin) } : null;
  return out;
});

const leftSlots = computed(() =>
  bracketSlots.value.filter((s) => s.zone === 'LEFT').sort((a, b) => a.order - b.order)
);
const rightSlots = computed(() =>
  bracketSlots.value.filter((s) => s.zone === 'RIGHT' || s.zone === 'CENTER').sort((a, b) => a.order - b.order)
);

onMounted(() => {
  loadData();
  // 赛事事件 SSE 实时推送为主
  subscribeTournamentEvents(props.tournamentId, handleTournamentEvent);
});

onUnmounted(() => {
  unsubscribeTournamentEvents(props.tournamentId, handleTournamentEvent);
});

watch(
  () => [props.stageId, props.tournamentId],
  ([, newTid], [, oldTid]) => {
    if (oldTid !== newTid) {
      unsubscribeTournamentEvents(oldTid, handleTournamentEvent);
      subscribeTournamentEvents(newTid, handleTournamentEvent);
    }
    loadedOnce.value = false;
    loadData();
  }
);

/** 事件回调:重连补偿(null)或事件属于本赛段/上一赛段(预排来源)时才刷新,避免无关事件触发全量拉取 */
const handleTournamentEvent = (data: any) => {
  if (!data || data.stageId == null) {
    loadData();
    return;
  }
  const sid = String(data.stageId);
  if (sid === String(props.stageId)) {
    loadData();
    return;
  }
  // 上一赛段事件改变预排结果:结算/重置/赛段推进等;单纯的分数(scores)不影响胜者名单,跳过
  if (prevStageId.value != null && sid === String(prevStageId.value) && data.type !== 'scores') {
    loadData();
  }
};
</script>

<style scoped>
.slot {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 6px 8px;
  width: 9em;
  max-width: 9em;
  border: 1px solid #404040;
  border-bottom: none;
  font-size: clamp(9px, 1.1vw, 16px);
  line-height: 1.3;
}
.slot.pair-top {
  border-radius: 6px 6px 0 0;
}
.slot.pair-bottom {
  border-bottom: 1px solid #404040;
  border-radius: 0 0 6px 6px;
}
.slot.win {
  background: rgba(245, 158, 11, 0.18);
}
.slot.bye {
  opacity: 0.35;
}
.slot .name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  max-width: 100%;
}
.final-card {
  border: 1px solid var(--bracket-border, #404040);
  background: var(--bracket-bg, transparent);
  color: var(--bracket-text, inherit);
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  font-size: clamp(10px, 1.2vw, 18px);
  min-height: 2.6em;
  width: 9em;
  max-width: 9em;
}
.final-card .name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  max-width: 100%;
}
.final-win {
  background: var(--bracket-bg, transparent);
  border-color: var(--bracket-border, #404040);
}
.final-bye {
  opacity: 0.35;
}
.champion-card {
  border-width: 2px;
  border-color: var(--bracket-border, #404040);
  color: var(--bracket-text, inherit);
  padding: 12px 14px;
  font-size: clamp(12px, 1.5vw, 24px);
  width: 9.5em;
  max-width: 9.5em;
}
.custom-scrollbar-y::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar-y::-webkit-scrollbar-thumb {
  background: #404040;
  border-radius: 4px;
}
</style>
