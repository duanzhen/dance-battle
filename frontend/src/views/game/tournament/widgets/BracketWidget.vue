<template>
  <div class="w-full h-full" :style="bracketStyle">
    <!-- 查看模式:对阵树(按 displayZone 分左右两列) -->
    <div v-if="mode !== 'edit'" class="w-full h-full overflow-hidden flex">
      <div v-if="loading" class="w-full flex items-center justify-center text-neutral-500 text-xs">加载中...</div>
      <!-- 列表模式(优先于决赛/半决赛特殊布局):按场次顺序一列展示,两名选手在同一张长条卡片里 -->
      <div
        v-else-if="listMode"
        class="w-full h-full overflow-y-auto custom-scrollbar-y px-2 py-2 flex flex-col justify-between gap-1.5"
      >
        <div v-if="listRows.length === 0" class="w-full flex items-center justify-center text-neutral-600 text-xs text-center px-2">
          {{ emptyHint }}
        </div>
        <div v-for="row in listRows" :key="row.match.id" class="bracket-list-row">
          <div class="bracket-list-card">
            <span class="bracket-list-side" :class="{ 'winner-gold': showWinner && isWinner(row.left) }">
              <img
                v-if="showAvatar && row.left && avatarOf(row.left.competitorId)"
                :src="avatarOf(row.left.competitorId)"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :style="fz(1)" :title="listName(row.left)">{{ listName(row.left) || BLANK_NAME }}</span>
            </span>
            <span class="bracket-list-vs" :style="fz(0.8)">VS</span>
            <span class="bracket-list-side" :class="{ 'winner-gold': showWinner && isWinner(row.right) }">
              <img
                v-if="showAvatar && row.right && avatarOf(row.right.competitorId)"
                :src="avatarOf(row.right.competitorId)"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :style="fz(1)" :title="listName(row.right)">{{ listName(row.right) || BLANK_NAME }}</span>
            </span>
          </div>
        </div>
      </div>
      <!-- 决赛:左半区晋级 | 冠军 | 右半区晋级 三框布局 -->
      <div v-else-if="isFinal" class="w-full h-full flex flex-col items-center justify-between gap-3 px-2 py-2">
        <!-- 冠军卡:顶部,窄宽度 -->
        <div class="final-card champion-card" :class="{ 'final-win': !!champion }">
          <img
            v-if="showAvatar && champion?.avatar"
            :src="champion?.avatar"
            class="bracket-avatar"
            :style="avatarStyle"
            alt=""
            @error="onAvatarError"
          />
          <span class="name" :class="{ 'winner-gold': showWinner && !!champion }" :style="fz(1)">{{ champion?.name || '' }}</span>
        </div>
        <!-- 左右半区:下方 -->
        <div class="flex items-center justify-between min-h-0 w-full">
          <div class="final-card" :class="{ 'final-win': champion && champion.competitorId === finalists.left?.competitorId }">
            <img
              v-if="showAvatar && finalists.left?.avatar"
              :src="finalists.left?.avatar"
              class="bracket-avatar"
              :style="avatarStyle"
              alt=""
              @error="onAvatarError"
            />
            <span class="name" :class="{ 'winner-gold': showWinner && champion && champion.competitorId === finalists.left?.competitorId }" :style="fz(1)" :title="finalists.left?.name || ''">{{ finalists.left?.name || '' }}</span>
          </div>
          <div class="final-card" :class="{ 'final-win': champion && champion.competitorId === finalists.right?.competitorId }">
            <img
              v-if="showAvatar && finalists.right?.avatar"
              :src="finalists.right?.avatar"
              class="bracket-avatar"
              :style="avatarStyle"
              alt=""
              @error="onAvatarError"
            />
            <span class="name" :class="{ 'winner-gold': showWinner && champion && champion.competitorId === finalists.right?.competitorId }" :style="fz(1)" :title="finalists.right?.name || ''">{{ finalists.right?.name || '' }}</span>
          </div>
        </div>
      </div>
      <!-- 半决赛(4人2场):四个参赛者放在四角;开启季军赛时底部中间显示季军 -->
      <div v-else-if="isSemi" class="w-full h-full flex flex-col gap-2 px-2 py-2">
        <div class="flex-1 flex items-stretch justify-between gap-2 min-h-0">
          <div class="flex-none flex flex-col justify-between items-center min-h-0">
            <div class="final-card" :class="{ 'final-win': semi.left?.top?.win }">
              <img
                v-if="showAvatar && semi.left?.top?.avatar"
                :src="semi.left?.top?.avatar"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :class="{ 'winner-gold': showWinner && semi.left?.top?.win }" :style="fz(1)" :title="semi.left?.top?.name || ''">{{ semi.left?.top?.name || '' }}</span>
            </div>
            <div class="final-card" :class="{ 'final-win': semi.left?.bottom?.win }">
              <img
                v-if="showAvatar && semi.left?.bottom?.avatar"
                :src="semi.left?.bottom?.avatar"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :class="{ 'winner-gold': showWinner && semi.left?.bottom?.win }" :style="fz(1)" :title="semi.left?.bottom?.name || ''">{{ semi.left?.bottom?.name || '' }}</span>
            </div>
          </div>
          <div style="flex: 1; min-width: 0"></div>
          <div class="flex-none flex flex-col justify-between items-center min-h-0">
            <div class="final-card" :class="{ 'final-win': semi.right?.top?.win }">
              <img
                v-if="showAvatar && semi.right?.top?.avatar"
                :src="semi.right?.top?.avatar"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :class="{ 'winner-gold': showWinner && semi.right?.top?.win }" :style="fz(1)" :title="semi.right?.top?.name || ''">{{ semi.right?.top?.name || '' }}</span>
            </div>
            <div class="final-card" :class="{ 'final-win': semi.right?.bottom?.win }">
              <img
                v-if="showAvatar && semi.right?.bottom?.avatar"
                :src="semi.right?.bottom?.avatar"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :class="{ 'winner-gold': showWinner && semi.right?.bottom?.win }" :style="fz(1)" :title="semi.right?.bottom?.name || ''">{{ semi.right?.bottom?.name || '' }}</span>
            </div>
          </div>
        </div>
        <!-- 季军赛框:底部中间,显示季军(胜者) -->
        <div v-if="thirdPlaceEnabled" class="flex-none flex justify-center min-h-0">
          <div class="final-card third-place-card" :class="{ 'final-win': !!semi.third?.winner }">
            <span class="third-place-label" :style="fz(0.65)">季军</span>
            <span class="name" :style="fz(1)" :title="thirdPlaceText">{{ thirdPlaceText }}</span>
          </div>
        </div>
      </div>
      <div v-else-if="stageModeError" class="w-full flex items-center justify-center text-neutral-600 text-xs text-center px-2">
        对战树仅支持淘汰赛赛段,请在编辑模式重新绑定
      </div>
      <div
        v-else-if="leftSlots.length === 0 && rightSlots.length === 0"
        class="w-full flex items-center justify-center text-neutral-600 text-xs text-center px-2"
      >
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
              <img v-if="showAvatar && s.leftAvatar" :src="s.leftAvatar" class="bracket-avatar" :style="avatarStyle" alt="" @error="onAvatarError" />
              <span class="name" :class="{ 'winner-gold': showWinner && s.leftWin }" :style="fz(1)" :title="s.leftSrc ? s.leftName + ' · ' + s.leftSrc : s.leftName">{{ s.leftName || '' }}</span>
            </div>
            <div class="final-card" :class="{ 'final-win': s.rightWin, 'final-bye': s.rightBye }">
              <img
                v-if="showAvatar && s.rightAvatar"
                :src="s.rightAvatar"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
                <span class="name" :class="{ 'winner-gold': showWinner && s.rightWin }" :style="fz(1)" :title="s.rightSrc ? s.rightName + ' · ' + s.rightSrc : s.rightName">{{ s.rightName || '' }}</span>
            </div>
          </template>
        </div>
        <!-- 中间留白(透明):供后续赛段的对战树 widget 叠放衔接 -->
        <div style="flex: 1; min-width: 0"></div>
        <!-- 右列 -->
        <div
          class="bracket-col flex-none flex flex-col gap-3 min-h-0 overflow-y-auto custom-scrollbar-y items-center"
          :class="rightSlots.length === 1 ? 'justify-center' : 'justify-between'"
        >
          <template v-for="(s, i) in rightSlots" :key="'r' + i">
            <div class="final-card" :class="{ 'final-win': s.leftWin, 'final-bye': s.leftBye }">
              <img v-if="showAvatar && s.leftAvatar" :src="s.leftAvatar" class="bracket-avatar" :style="avatarStyle" alt="" @error="onAvatarError" />
              <span class="name" :style="fz(1)" :title="s.leftSrc ? s.leftName + ' · ' + s.leftSrc : s.leftName">{{ s.leftName || '' }}</span>
            </div>
            <div class="final-card" :class="{ 'final-win': s.rightWin, 'final-bye': s.rightBye }">
              <img
                v-if="showAvatar && s.rightAvatar"
                :src="s.rightAvatar"
                class="bracket-avatar"
                :style="avatarStyle"
                alt=""
                @error="onAvatarError"
              />
              <span class="name" :style="fz(1)" :title="s.rightSrc ? s.rightName + ' · ' + s.rightSrc : s.rightName">{{ s.rightName || '' }}</span>
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
        <!-- <p class="text-[10px] text-neutral-600 mt-2">对战树绑定淘汰赛赛段为标准对战树(配对按 SEED 标准种子对位或 SEQUENTIAL 相邻配对,已生成场次胜者高亮);绑定擂台赛段时展示该赛段 8 强名单(标准种子摆位,仅供展示)。</p> -->
      </section>
      <section>
        <span class="section-title">显示选项</span>
        <label class="flex items-center justify-between mt-2 cursor-pointer">
          <span class="text-xs text-neutral-300">显示选手头像</span>
          <input
            type="checkbox"
            :checked="showAvatar"
            class="accent-amber-500 w-4 h-4"
            @change="$emit('update:showAvatar', ($event.target as HTMLInputElement).checked)"
          />
        </label>
        <label class="flex items-center justify-between mt-2 cursor-pointer">
          <span class="text-xs text-neutral-300">展示胜利者(名字金色)</span>
          <input
            type="checkbox"
            :checked="showWinner"
            class="accent-amber-500 w-4 h-4"
            @change="$emit('update:showWinner', ($event.target as HTMLInputElement).checked)"
          />
        </label>
        <label class="flex items-center justify-between mt-2 cursor-pointer">
          <span class="text-xs text-neutral-300">列表模式</span>
          <input
            type="checkbox"
            :checked="listMode"
            class="accent-amber-500 w-4 h-4"
            @change="$emit('update:listMode', ($event.target as HTMLInputElement).checked)"
          />
        </label>
      </section>
      <section>
        <span class="section-title">样式配置</span>
        <div class="space-y-3 mt-2">
          <ColorInput label="文字颜色" :model-value="textColor || '#000000'" @update:model-value="$emit('update:textColor', $event)" />
          <TextInput label="字号(px)" :model-value="String(fontSize ?? 24)" placeholder="24" @update:model-value="handleFontSizeUpdate" />
          <div>
            <span class="block text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-1">边框颜色</span>
            <div class="flex items-center gap-1.5">
              <div class="flex-1 min-w-0">
                <ColorInput :model-value="borderColor ?? ''" @update:model-value="$emit('update:borderColor', $event)" />
              </div>
              <button
                class="shrink-0 w-7 h-7 rounded flex items-center justify-center transition-colors"
                :class="borderColor === '' ? 'text-amber-400 bg-amber-500/10' : 'text-neutral-500 hover:text-amber-400 hover:bg-neutral-800'"
                title="设为透明"
                @click="$emit('update:borderColor', '')"
              >
                <Ban class="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
          <div>
            <span class="block text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-1">背景颜色</span>
            <div class="flex items-center gap-1.5">
              <div class="flex-1 min-w-0">
                <ColorInput :model-value="bgColor ?? ''" @update:model-value="$emit('update:bgColor', $event)" />
              </div>
              <button
                class="shrink-0 w-7 h-7 rounded flex items-center justify-center transition-colors"
                :class="bgColor === '' ? 'text-amber-400 bg-amber-500/10' : 'text-neutral-500 hover:text-amber-400 hover:bg-neutral-800'"
                title="设为透明"
                @click="$emit('update:bgColor', '')"
              >
                <Ban class="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import { Ban } from 'lucide-vue-next';
import StageSelector from '../stages/StageSelector.vue';
import ColorInput from './common/ColorInput.vue';
import TextInput from './common/TextInput.vue';
import {
  listMatch,
  listMatchParticipant,
  listParticipantsByStage,
  listCompetitor,
  getStage,
  getStagePreBracket,
  getTournament
} from '@/api/game/screen';
import { parseTournamentColorConfig, DEFAULT_TOURNAMENT_COLOR_CONFIG, TournamentColorConfig } from '@/utils/tournamentColorConfig';
import { seedLayout } from '@/utils/seedLayout';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

/**
 * 空位显示用的占位符:不断行空格。
 *
 * <p>用空字符串会让名字那一行没有行盒,卡片高度塌下去被压扁(同一排里有人/没人高度不一致);
 * 用一个不可见的空格既能"什么都不显示",又能把行高撑住。</p>
 */
const BLANK_NAME = '\u00A0';

const props = defineProps<{
  stageId?: string | number;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  textColor?: string;
  borderColor?: string;
  bgColor?: string;
  fontSize?: number;
  /** 是否在名字前显示选手头像(默认不显示) */
  showAvatar?: boolean;
  /** 展示胜利者:胜者名字显示为金色 */
  showWinner?: boolean;
  /** 列表模式:按场次顺序一列展示,每场两名选手在同一张长条卡片里 */
  listMode?: boolean;
}>();
const emit = defineEmits<{
  'update:stageId': [value: string | number | null];
  'update:textColor': [value: string];
  'update:borderColor': [value: string];
  'update:bgColor': [value: string];
  'update:fontSize': [value: number];
  'update:showAvatar': [value: boolean];
  'update:showWinner': [value: boolean];
  'update:listMode': [value: boolean];
}>();

// 样式配置:通过 CSS 变量作用于全部对战卡;未配置时回退默认样式(白字/灰边/透明底)
const bracketStyle = computed(() => ({
  '--bracket-text': props.textColor || undefined,
  // 空字符串 = 用户点击「设为透明」,显式映射为 transparent,
  // 否则空值会被 || 回退成默认色(灰边/黑底),达不到透明效果
  '--bracket-border': props.borderColor === '' ? 'transparent' : props.borderColor || undefined,
  '--bracket-bg': props.bgColor === '' ? 'transparent' : props.bgColor || undefined
}));

/** 字号缩放:以 fontSize(默认 24px)为基准,名字按比例联动,限制在 6~80px */
const fz = (ratio: number) => {
  const base = Math.max(6, Math.min(80, Number(props.fontSize) || 24));
  return {
    fontSize: `${Math.round(base * ratio)}px`,
    lineHeight: `${Math.round(base * ratio * 1.3)}px`
  };
};

const handleFontSizeUpdate = (v: string) => {
  const n = Number(v);
  emit('update:fontSize', Number.isFinite(n) && n > 0 ? n : 24);
};

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
const thirdPlaceEnabled = ref(false);
const stageModeError = ref(false);
const stageMode = ref('');
const stagePairingMode = ref('');
const stageTeamCountStart = ref(0);
/** 赛事级红蓝配色(所有下属淘汰赛共享) */
const colorConfig = ref<TournamentColorConfig>({ ...DEFAULT_TOURNAMENT_COLOR_CONFIG });

const nextPow2 = (v: number): number => {
  let p = 1;
  while (p < v) p <<= 1;
  return p;
};

const compById = computed(() => {
  const m: Record<string, any> = {};
  competitors.value.forEach((c) => {
    m[c.id] = c;
  });
  return m;
});

/**
 * 一次取回某赛段的全部参赛方,再按场次分组(共用 api 层包装)。
 *
 * 对战树此前按场次逐个请求(N 场 = N 个 HTTP,决赛来源解析那段还是 for + await 串行),
 * 浏览器并发上限下要排好几轮,表现就是"加载名单很慢"。这里只是本组件取自己赛段的数据,
 * 不涉及其它 widget 的加载方式。
 */
const loadParticipantsByStage = async (stageId: any): Promise<Record<string, any[]>> => {
  try {
    return await listParticipantsByStage(stageId);
  } catch (e) {
    console.warn('参赛方批量加载失败,按空名单渲染:', e);
    return {};
  }
};

const loadData = async () => {
  if (!props.stageId) {
    competitors.value = [];
    matches.value = [];
    stageMode.value = '';
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
      stagePairingMode.value = JSON.parse(stageInfo?.data?.ruleConfig || '{}')?.knockout?.pairingMode || '';
      stageTeamCountStart.value = Number(stageInfo?.data?.teamCountStart) || 0;
    } catch (e) {
      stagePairingMode.value = '';
      stageTeamCountStart.value = 0;
    }
    // 赛事级红蓝配色:从 tournament.themeConfig 读取(所有淘汰赛共享)
    colorConfig.value = { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
    if (stageInfo?.data?.tournamentId) {
      try {
        const tr: any = await getTournament(stageInfo.data.tournamentId);
        colorConfig.value = parseTournamentColorConfig(tr?.data?.themeConfig);
      } catch (e) {
        colorConfig.value = { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
      }
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
      competitors.value = (cr?.data?.data || cr?.data || [])
        .slice()
        .sort((a: any, b: any) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER));
    } catch (e) {
      competitors.value = [];
    }
    // 拉场次(已生成时含比分/排名/结果)
    const res: any = await listMatch({ stageId: props.stageId, pageNum: 1, pageSize: 999 } as any);
    matches.value = res?.data?.data || res?.data || [];
    // 参赛方按赛段一次取回再按场次分组:此前每场一个请求(16 强 = 16 个 HTTP),
    // 浏览器并发上限下要排好几轮,对战树就"卡在加载名单"。
    participantsByMatch.value = await loadParticipantsByStage(props.stageId);
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
    thirdPlaceEnabled.value = !!ko.thirdPlaceMatch;
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
  leftAvatar: string;
  rightAvatar: string;
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

/** 是否显示头像:配置项默认关闭 */
const showAvatar = computed(() => props.showAvatar === true);
/** 是否高亮胜者名字为金色 */
const showWinner = computed(() => props.showWinner === true);
/** 列表模式:按场次顺序一列展示 */
const listMode = computed(() => props.listMode === true);

/** 参赛方头像:取该参赛方关联选手中第一个有头像的(与当前场次/大屏组件同一口径) */
const avatarOf = (cid: any): string => {
  if (cid == null) return '';
  const list = compById.value[cid]?.playerList;
  if (!Array.isArray(list)) return '';
  const hit = list.find((p: any) => !!p?.avatar);
  return hit?.avatar || '';
};

/** 头像尺寸随字号联动,保持与名字同一视觉比例 */
const avatarStyle = computed(() => {
  const base = Math.max(6, Math.min(80, Number(props.fontSize) || 24));
  const box = Math.round(base * 1.5);
  return { width: `${box}px`, height: `${box}px` };
});

/** 头像加载失败(未传头像/资源 404)时隐藏,避免出现裂图方块 */
const onAvatarError = (e: Event) => {
  (e.target as HTMLImageElement).style.display = 'none';
};

const isWinner = (p: any) => p?.outcomeStatus === 'WIN' || (p?.rankInMatch === 1 && p?.scoreValue != null);

/**
 * 按槽位取场次左右两名参赛方。
 *
 * <p>轮空位不落 t_match_participant 行(competitor_id NOT NULL),因此参赛方数组的
 * 下标 ≠ 槽位下标:直接用 ss[0]/ss[1] 当左右,会把「左槽轮空、右槽有人」的选手
 * 画到左边,与中间态预排(按种子位显示)不一致。这里严格用 displaySlotIndex 定位。</p>
 */
const participantsBySlot = (matchId: any): { left: any; right: any } => {
  const list = participantsByMatch.value[matchId] || [];
  const at = (slot: number) => list.find((p: any) => Number(p.displaySlotIndex) === slot) ?? null;
  const left = at(0);
  const right = at(1);
  // 兜底:历史/异常数据没有 displaySlotIndex(全为 null)时退回原顺序
  if (left == null && right == null && list.length > 0) {
    const sorted = [...list].sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));
    return { left: sorted[0] ?? null, right: sorted[1] ?? null };
  }
  return { left, right };
};

/**
 * 列表模式行数据:按场次顺序一列展示(后端返回即 id 升序,与 MC 导播台场次列表同一顺序),
 * 每场两名选手放进同一张长条卡片里。
 */
const listRows = computed(() =>
  matches.value.map((m: any) => {
    const { left, right } = participantsBySlot(m.id);
    return { match: m, left, right };
  })
);

/** 列表模式:选手名(BYE/空位返回空) */
const listName = (p: any) => (p?.competitorId != null ? nameOf(p.competitorId) : '');

/**
 * 擂台赛段展示:按「座位」列出进入擂台赛的人,按出场顺序两两成对(1-2 / 3-4 …)排成 4 对(左 2 右 2),仅供展示。
 *
 * <p>· 名单已写入(上一赛段结算并确认晋级):按 seedRank 坐回座位,空位不写文案(留空)。
 *   绝不能用数组下标占位——那等于把名单压到 1..n,和中间态看到的出场顺序对不上。
 * · 名单还没写入(上一赛段未结算/未确认晋级):用预排晋级者占位,开赛前就能看到谁要进擂台赛。
 * · <b>擂台赛是出场队列,不是淘汰签表</b>:席位按顺序配对(1-2/3-4…),不能套淘汰赛的
 *   头尾交叉摆位(1-8/4-5…),否则大屏上的出场顺序和中间态/实际出场顺序对不上。</p>
 */
const arenaSlots = computed<BracketSlot[]>(() => {
  const plan = Math.max(competitors.value.length, stageTeamCountStart.value || 0, 2);
  const bracketSize = Math.max(2, nextPow2(plan));
  const pairCount = Math.max(1, bracketSize / 2);
  const half = Math.ceil(pairCount / 2);
  const bySeat = new Map<number, any>();
  const put = (seat: number, v: any) => {
    if (seat >= 1 && seat <= bracketSize && !bySeat.has(seat)) {
      bySeat.set(seat, v);
    }
  };
  // 只有带座位号的人参与排布;没有座位号(未落位/异常)不参与——绝不按下标占位
  competitors.value.forEach((c: any) => {
    const seat = Number(c.seedRank);
    if (Number.isFinite(seat) && seat > 0) {
      put(seat, c);
    }
  });
  if (competitors.value.length === 0) {
    // 擂台赛名单还没写入:用上一赛段的预排晋级者占位(开赛前就能看到谁要进来)
    preSeeds.value.forEach((p: any) => {
      const seat = Number(p?.seedRank);
      if (Number.isFinite(seat) && seat > 0) {
        put(seat, p);
      }
    });
  }
  const atSeat = (seatNo: number) => bySeat.get(seatNo) ?? null;
  const idOf = (v: any) => (v == null ? null : (v.competitorId ?? v.id ?? null));
  return Array.from({ length: pairCount }, (_, i) => {
    // 顺序模式:第 i 对 = 座位 2i+1 vs 2i+2
    const left = atSeat(2 * i + 1);
    const right = atSeat(2 * i + 2);
    return {
      zone: i < half ? 'LEFT' : 'RIGHT',
      order: i % half,
      name: '8强',
      status: 'PENDING',
      // 擂台赛空座位什么都不显示(不写「待定」也不写「轮空」):队列里空着就是个空位。
      // 用不断行空格而不是空串,否则这一行没有行盒、卡片会被压扁。
      leftName: left?.name || BLANK_NAME,
      rightName: right?.name || BLANK_NAME,
      leftAvatar: avatarOf(idOf(left)),
      rightAvatar: avatarOf(idOf(right)),
      leftScore: '',
      rightScore: '',
      leftWin: false,
      rightWin: false,
      leftBye: false,
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
      const { left, right } = participantsBySlot(m.id);
      // 座位已实体化:competitorId 为空时按 slotKind 区分轮空/待定(老数据缺 slotKind 时按轮空显示)
      const slotLabel = (s: any) => (s?.slotKind === 'PENDING' ? '待定' : '轮空');
      return {
        matchId: String(m.id),
        zone: m.displayZone || 'LEFT',
        order: m.displayRow ?? 0,
        name: m.name || '',
        status: m.status || 'PENDING',
        leftName: left?.competitorId == null ? slotLabel(left) : nameOf(left.competitorId) || BLANK_NAME,
        rightName: right?.competitorId == null ? slotLabel(right) : nameOf(right.competitorId) || BLANK_NAME,
        leftAvatar: avatarOf(left?.competitorId),
        rightAvatar: avatarOf(right?.competitorId),
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
    return prePairs.value.map(
      (p: any) =>
        ({
          zone: p.zone || 'LEFT',
          order: p.position - 1,
          name: `预排 ${p.position}`,
          status: 'PENDING',
          // 对应场次未打完(TBD)留空不写字;无对应场次/无参赛方=轮空
          leftName: p.left?.name || (p.leftStatus === 'BYE' ? '轮空' : ''),
          rightName: p.right?.name || (p.rightStatus === 'BYE' ? '轮空' : ''),
          leftAvatar: avatarOf(p.left?.competitorId),
          rightAvatar: avatarOf(p.right?.competitorId),
          leftScore: '',
          rightScore: '',
          leftWin: false,
          rightWin: false,
          leftBye: p.leftStatus === 'BYE',
          rightBye: p.rightStatus === 'BYE',
          leftSrc: p.left?.sourceMatchName || '',
          rightSrc: p.right?.sourceMatchName || ''
        }) as BracketSlot
    );
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
      return Array.from(
        { length: pairs },
        (_, i) =>
          ({
            zone: i < half ? 'LEFT' : 'RIGHT',
            order: i % half,
            name: '待对阵',
            status: 'PENDING',
            leftName: BLANK_NAME,
            rightName: BLANK_NAME,
            leftAvatar: '',
            rightAvatar: '',
            leftScore: '',
            rightScore: '',
            leftWin: false,
            rightWin: false,
            rightBye: false,
            leftSrc: '',
            rightSrc: ''
          }) as BracketSlot
      );
    }
    return [];
  }
  // 尚未生成对阵:按「座位」预排,与中间态、后端生成同一口径。
  // 座位空着就是轮空——绝不能用数组下标占位(那等于把名单整体压到 1..n,和中间态对不上)。
  const plan = Math.max(count, stageTeamCountStart.value || 0);
  const bySeat = new Map<number, any>();
  // 只有带座位号的人参与排布;没有座位号的不参与(绝不按下标占位)
  competitors.value.forEach((c: any) => {
    const seat = Number(c.seedRank);
    if (Number.isFinite(seat) && seat > 0) {
      bySeat.set(seat, c);
    }
  });
  const atSeat = (seatNo: number) => bySeat.get(seatNo) ?? null;
  const seedMode = stagePairingMode.value ? String(stagePairingMode.value).toUpperCase() === 'SEED' : false;
  const bracketSize = Math.max(2, nextPow2(plan));
  const layout = seedMode ? seedLayout(bracketSize) : null;
  const pairCount = Math.max(1, bracketSize / 2);
  const half = Math.ceil(pairCount / 2);
  const seatNoOf = (i: number, side: 'left' | 'right') => (seedMode ? layout![2 * i + (side === 'left' ? 0 : 1)] : 2 * i + (side === 'left' ? 1 : 2));
  return Array.from({ length: pairCount }, (_, i) => {
    const left = atSeat(seatNoOf(i, 'left'));
    const right = atSeat(seatNoOf(i, 'right'));
    return {
      zone: i < half ? 'LEFT' : 'RIGHT',
      order: i % half,
      name: '待对阵',
      status: 'PENDING',
      leftName: left?.name || BLANK_NAME,
      rightName: right?.name || '轮空',
      leftAvatar: avatarOf(left?.id),
      rightAvatar: avatarOf(right?.id),
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
    return '等待名单来源结算,晋级名单生成后展示';
  }
  if (preStatus.value === 'WAIT_PREV') {
    return '等待名单来源结算,晋级者产生后自动预排';
  }
  if (preStatus.value === 'NO_PREV') {
    return '暂无可预排对阵';
  }
  if (preStatus.value === 'UNSUPPORTED') {
    return '仅淘汰赛之间支持预排';
  }
  return props.stageId ? '该赛段暂无参赛方' : '未绑定赛段(编辑里选赛段)';
});

// 决赛三框:左/右两位决赛选手按本场槽位(座位号 1/2)归位
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
          return { competitorId: s.competitorId, name: s.name || '', avatar: avatarOf(s.competitorId), score: '' };
        }
        return { competitorId: null, name: st === 'BYE' ? '轮空' : '\u00A0', avatar: '', score: '' };
      };
      out.left = toPreCard(pair.left, pair.leftStatus);
      out.right = toPreCard(pair.right, pair.rightStatus);
    }
    return out;
  }
  const toCard = (p: any) => ({
    competitorId: p.competitorId,
    name: nameOf(p.competitorId),
    avatar: avatarOf(p.competitorId),
    score: p.scoreValue == null ? '' : String(p.scoreValue)
  });
  // 按槽位定位:轮空一侧不落 participant 行,不能用数组下标代替左右槽
  // 左右由决赛自己的槽位(座位号)直接决定:1 号位 = 左,2 号位 = 右。
  // 不再按"上一赛段那场半决赛在第几半区"去推断 —— 位置号已经把答案写死了。
  const { left: a, right: b } = participantsBySlot(m.id);
  if (a) {
    out.left = toCard(a);
  }
  if (b) {
    out.right = toCard(b);
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
    ? {
        competitorId: w.competitorId,
        name: nameOf(w.competitorId),
        avatar: avatarOf(w.competitorId),
        score: w.scoreValue == null ? '' : String(w.scoreValue)
      }
    : null;
});

// 半决赛四角:两场各两名参赛者,左上/左下、右上/右下;
// 开启季军赛时识别第三场(季军赛),供底部中间季军框展示
const semi = computed(() => {
  const out: any = { left: null, right: null, third: null };
  if (!isSemi.value) {
    return out;
  }
  const build = (m: any) => {
    const { left, right } = participantsBySlot(m.id);
    return {
      top: left
        ? { competitorId: left.competitorId, name: nameOf(left.competitorId), avatar: avatarOf(left.competitorId), win: isWinner(left) }
        : null,
      bottom: right
        ? { competitorId: right.competitorId, name: nameOf(right.competitorId), avatar: avatarOf(right.competitorId), win: isWinner(right) }
        : null
    };
  };
  if (matches.value.length >= 2) {
    const semis = matches.value
      .filter((m: any) => m.name !== '季军赛')
      .slice()
      .sort((a: any, b: any) => (a.displayRow ?? 0) - (b.displayRow ?? 0));
    if (semis.length >= 2) {
      out.left = build(semis[0]);
      out.right = build(semis[1]);
    }
    // 季军赛:底部中间框展示对阵与胜者
    const third = matches.value.find((m: any) => m.name === '季军赛') || null;
    if (third) {
      const { left, right } = participantsBySlot(third.id);
      const winner = [left, right].find((p: any) => isWinner(p)) || null;
      out.third = {
        left: left ? { competitorId: left.competitorId, name: nameOf(left.competitorId), avatar: avatarOf(left.competitorId) } : null,
        right: right ? { competitorId: right.competitorId, name: nameOf(right.competitorId), avatar: avatarOf(right.competitorId) } : null,
        winner: winner ? { competitorId: winner.competitorId, name: nameOf(winner.competitorId), avatar: avatarOf(winner.competitorId) } : null,
        status: third.status || 'PENDING'
      };
    }
    return out;
  }
  // 未生成正式场次(预排 2 对 / 种子 4 人):两列各一对 → 左上左下、右上右下
  const toCard = (name: string, avatar: string, win: boolean) => ({ competitorId: null, name, avatar, win });
  const lp = leftSlots.value[0];
  const rp = rightSlots.value[0];
  out.left = lp ? { top: toCard(lp.leftName, lp.leftAvatar, lp.leftWin), bottom: toCard(lp.rightName, lp.rightAvatar, lp.rightWin) } : null;
  out.right = rp ? { top: toCard(rp.leftName, rp.leftAvatar, rp.leftWin), bottom: toCard(rp.rightName, rp.rightAvatar, rp.rightWin) } : null;
  return out;
});

// 季军框文案:已决出显示胜者,未决出显示双方对阵或待定
const thirdPlaceText = computed(() => {
  const third = semi.value?.third;
  if (!third) return '待定';
  if (third.winner) return third.winner.name || '待定';
  const left = third.left?.name || '';
  const right = third.right?.name || '';
  if (left && right) return left + ' VS ' + right;
  return left || right || '待定';
});

const leftSlots = computed(() => bracketSlots.value.filter((s) => s.zone === 'LEFT').sort((a, b) => a.order - b.order));
const rightSlots = computed(() => bracketSlots.value.filter((s) => s.zone === 'RIGHT').sort((a, b) => a.order - b.order));

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

/**
 * 事件回调:本赛事内任何"赛段级"变化都刷新。
 *
 * <p>名单来源可能是多个赛段(如「海选 1~8 名 + 复活赛 9~24 名」),只认直接前驱会漏刷新;
 * 单纯的分数(scores)不影响名单构成,跳过。300ms 节流,避免中间态连续拖动时把大屏拉爆。</p>
 */
let reloadTimer: ReturnType<typeof setTimeout> | null = null;
const scheduleReload = () => {
  if (reloadTimer != null) {
    return;
  }
  reloadTimer = setTimeout(() => {
    reloadTimer = null;
    loadData();
  }, 300);
};

const handleTournamentEvent = (data: any) => {
  if (!data || data.stageId == null) {
    scheduleReload(); // 重连补偿:整页重拉
    return;
  }
  if (data.type === 'scores') {
    return; // 打分不改变名单构成
  }
  scheduleReload();
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
  /* 空名字(空位)时那一行没有行盒,不加这个卡片会塌成只有内边距、被压扁 */
  min-height: calc(1.3em + 12px);
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
  border: 1px solid var(--bracket-border, transparent);
  background: var(--bracket-bg, transparent);
  color: var(--bracket-text, #000000);
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
/* 选手头像:圆形裁剪 + 描边,尺寸随字号由 avatarStyle 注入 */
.bracket-avatar {
  flex: none;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid currentColor;
  background: rgba(255, 255, 255, 0.08);
}
.final-win {
  background: var(--bracket-bg, transparent);
  border-color: var(--bracket-border, transparent);
}
.final-bye {
  opacity: 0.35;
}
.champion-card {
  border-width: 2px;
  border-color: var(--bracket-border, transparent);
  color: var(--bracket-text, #000000);
  padding: 12px 14px;
  font-size: clamp(12px, 1.5vw, 24px);
  width: 9.5em;
  max-width: 9.5em;
}
.third-place-card {
  flex-direction: column;
  gap: 2px;
  width: 12em;
  max-width: 12em;
  min-height: 2.8em;
  padding: 4px 10px 6px;
}
.third-place-label {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  opacity: 0.7;
}
/* 展示胜利者:胜者名字金色 */
.winner-gold {
  color: #f59e0b;
  font-weight: 700;
}
/* 列表模式:一场一张长条卡片,两名选手在同一卡片内 */
.bracket-list-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bracket-list-card {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--bracket-border, #404040);
  background: var(--bracket-bg, transparent);
  color: var(--bracket-text, #000000);
  border-radius: 8px;
}
.bracket-list-side {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.bracket-list-side:last-child {
  justify-content: flex-end;
  text-align: right;
}
.bracket-list-side .name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bracket-list-vs {
  flex: none;
  opacity: 0.45;
  font-weight: 700;
}
.custom-scrollbar-y::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar-y::-webkit-scrollbar-thumb {
  background: #404040;
  border-radius: 4px;
}
</style>
