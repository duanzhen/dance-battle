<template>
  <div class="w-full h-full">
    <!-- 查看模式:按 Redis 推送目标展示「指标行 × 裁判列」评分矩阵,不预先绑定实体 -->
    <div v-if="mode !== 'edit'" ref="viewRef" class="w-full h-full">
      <div v-if="!target" class="w-full h-full flex items-center justify-center">
        <span class="text-white/25 font-medium tracking-wider" :style="fz(14)">暂无推送</span>
      </div>
      <div v-else class="w-full h-full relative flex flex-col justify-center px-[3%] py-[4%]">
        <!-- 上:头像(可关) + 号牌/姓名/总分;下:评分矩阵 -->
        <div class="flex flex-col items-center flex-none mb-[2%] min-w-0">
          <div v-if="showAvatar" class="overflow-hidden mb-[1.5%]" :style="avatarBoxStyle">
            <img v-if="avatar && !avatarBroken" :src="avatar" class="w-full h-full object-contain" @error="onImgError" />
          </div>
          <div class="flex items-baseline justify-center gap-3 min-w-0 max-w-full">
            <span
              v-if="competitorNumber"
              class="font-mono text-amber-400/90 flex-none"
              :style="fz(16)"
            >
              #{{ competitorNumber }}
            </span>
            <span
              v-if="showPlayerName"
              class="font-bold text-white drop-shadow truncate min-w-0"
              :style="fz(28)"
              :title="competitorName"
            >
              {{ competitorName || '待定' }}
            </span>
          </div>
          <div class="flex items-baseline justify-center gap-2 mt-1">
            <span class="text-white/50" :style="fz(13)">总分</span>
            <span class="font-black font-mono tabular-nums text-amber-300 drop-shadow" :style="fz(26)">
              {{ fmtScore(totalScore) }}
            </span>
          </div>
        </div>

        <!-- 评分矩阵:一行一个指标,一列一个裁判 -->
        <div class="flex-1 min-h-0 rounded-xl bg-black/60 border border-white/10 backdrop-blur px-3 py-2 overflow-auto">
          <table v-if="referees.length" class="w-full border-collapse">
            <thead>
              <tr class="text-white/50">
                <th class="py-1 pr-2 text-left font-bold whitespace-nowrap" :style="fz(13)">指标</th>
                <th
                  v-for="ref in referees"
                  :key="'h-' + ref.key"
                  class="py-1 px-2 text-center font-bold whitespace-nowrap"
                  :style="fz(13)"
                >
                  {{ ref.name }}
                </th>
                <th class="py-1 pl-2 text-right font-bold whitespace-nowrap" :style="fz(13)">合计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in matrixRows" :key="row.key" class="border-t border-white/10">
                <td class="py-1.5 pr-2 text-white/80 font-bold whitespace-nowrap" :style="fz(14)">{{ row.name }}</td>
                <td
                  v-for="ref in referees"
                  :key="row.key + '-' + ref.key"
                  class="py-1.5 px-2 text-center font-mono tabular-nums text-white"
                  :style="fz(16)"
                >
                  <span
                    v-if="row.scored[ref.key] && row.scores[ref.key] == null"
                    class="inline-flex items-center justify-center text-green-400 font-black leading-none"
                    title="裁判已打分"
                  >✓</span>
                  <template v-else>{{ fmtScore(row.scores[ref.key]) }}</template>
                </td>
                <td class="py-1.5 pl-2 text-right font-mono tabular-nums font-black text-amber-300" :style="fz(16)">
                  {{ fmtScore(row.total) }}
                </td>
              </tr>
              <tr v-if="!matrixRows.length">
                <td :colspan="referees.length + 2" class="py-3 text-center text-white/40" :style="fz(13)">暂无评分</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="w-full h-full flex items-center justify-center text-white/40" :style="fz(13)">暂无裁判评分</div>
        </div>
      </div>
    </div>

    <!-- 编辑模式:只提供显示选项,不绑定场次/选手 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">评分详情属性</span>
        <div class="p-3 rounded-lg bg-neutral-800/60 border border-neutral-700 mb-3">
          <p class="text-[10px] text-neutral-400 leading-relaxed">
            本组件不预先绑定场次或选手。导播台点击选手后,选择要推送到的组件即可;推送标记只存在现场缓存里,不写入组件配置。
          </p>
          <p v-if="widgetId" class="text-[10px] text-neutral-500 mt-2 font-mono break-all">组件 ID：{{ widgetId }}</p>
        </div>
        <CheckboxGroup
          label="显示选项"
          :model-value="{ showAvatar, showPlayerName, roundAvatar: roundAvatarEnabled }"
          :options="[
            { key: 'showAvatar', label: '显示头像' },
            { key: 'showPlayerName', label: '显示选手名' },
            { key: 'roundAvatar', label: '圆形头像框' }
          ]"
          @update:item="handleOptionUpdate"
        />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue';
import { useRoute } from 'vue-router';
import CheckboxGroup from './common/CheckboxGroup.vue';
import { getWidgetScoreDetail, getMatch, listCompetitor, listPlayer } from '@/api/game/screen';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  /** 场景里本组件自己的 ID,用来读取 Redis 推送目标;不绑定任何场次/选手 */
  widgetId?: string | number | null;
  /** 是否显示选手姓名,默认显示 */
  showPlayerName?: boolean;
  /** 是否展示头像,默认展示 */
  showAvatar?: boolean;
  /** 是否圆形头像框,默认 true */
  roundAvatar?: boolean;
}>();

const emit = defineEmits<{
  'update:showPlayerName': [v: boolean];
  'update:showAvatar': [v: boolean];
  'update:roundAvatar': [v: boolean];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = computed(() => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null);

const showPlayerName = computed(() => props.showPlayerName !== false);
const showAvatar = computed(() => props.showAvatar !== false);
const roundAvatarEnabled = computed(() => props.roundAvatar !== false);

const handleOptionUpdate = (key: string, value: boolean) => {
  if (key === 'showPlayerName') emit('update:showPlayerName', value);
  if (key === 'showAvatar') emit('update:showAvatar', value);
  if (key === 'roundAvatar') emit('update:roundAvatar', value);
};

type Target = { matchId: string | number; competitorId: string | number };
type RefCol = { key: string; name: string };
type MatrixRow = { key: string; name: string; scores: Record<string, number | null>; scored: Record<string, boolean>; total: number | null };

const target = ref<Target | null>(null);
const competitorName = ref('');
const competitorNumber = ref('');
const totalScore = ref<number | null>(null);
const avatar = ref('');
const referees = ref<RefCol[]>([]);
const matrixRows = ref<MatrixRow[]>([]);
const avatarBroken = ref(false);

const fmtScore = (v: any): string => {
  if (v == null || v === '') return '—';
  const n = Number(v);
  return Number.isNaN(n) ? '—' : n.toFixed(2);
};

const onImgError = () => {
  avatarBroken.value = true;
};

const viewRef = ref<HTMLElement | null>(null);
const scale = ref(1);
const fz = (base: number) => ({
  fontSize: `${Math.round(base * scale.value)}px`,
  lineHeight: `${Math.round(base * scale.value * 1.25)}px`
});

const avatarBoxStyle = computed(() => ({
  width: `${Math.round(120 * scale.value)}px`,
  height: `${Math.round(120 * scale.value)}px`,
  borderRadius: roundAvatarEnabled.value ? '50%' : '12px',
  background: 'rgba(255,255,255,0.06)',
  border: '1px solid rgba(255,255,255,0.12)'
}));

watch(avatar, () => {
  avatarBroken.value = false;
});

const resetView = () => {
  target.value = null;
  competitorName.value = '';
  competitorNumber.value = '';
  totalScore.value = null;
  avatar.value = '';
  referees.value = [];
  matrixRows.value = [];
};

const refKey = (rs: any): string => {
  if (rs?.refereeId != null && rs.refereeId !== '') return String(rs.refereeId);
  if (rs?.refereeName) return 'n:' + String(rs.refereeName);
  return '';
};

const collectReferees = (row: any, dims: any[]): RefCol[] => {
  const seen = new Map<string, string>();
  const take = (list: any[] | undefined) => {
    (list || []).forEach((rs) => {
      const k = refKey(rs);
      if (!k || seen.has(k)) return;
      seen.set(k, rs.refereeName || '裁判');
    });
  };
  take(row?.refereeScores);
  dims.forEach((d) => take(d?.refereeScores));
  return Array.from(seen, ([key, name]) => ({ key, name }));
};

const buildMatrix = (row: any) => {
  const dims = Array.isArray(row?.dimensions) ? row.dimensions.filter((d: any) => d && (d.key != null || d.name)) : [];
  const cols = collectReferees(row, dims);
  referees.value = cols;

  if (dims.length) {
    matrixRows.value = dims.map((d: any) => {
      const scores: Record<string, number | null> = {};
      const scored: Record<string, boolean> = {};
      let sum: number | null = d.score != null && d.score !== '' && Number.isFinite(Number(d.score)) ? Number(d.score) : null;
      (d.refereeScores || []).forEach((rs: any) => {
        const k = refKey(rs);
        if (!k) return;
        const v = rs.score == null || rs.score === '' ? null : Number(rs.score);
        scores[k] = v != null && Number.isFinite(v) ? v : null;
        scored[k] = rs.scored === true || scores[k] != null;
      });
      return {
        key: String(d.key ?? d.name ?? 'dim'),
        name: d.name || d.key || '指标',
        scores,
        scored,
        total: sum
      };
    });
    return;
  }

  // 海选等单分制:没有维度时用一行「得分」,各列是各裁判的分
  const scores: Record<string, number | null> = {};
  const scored: Record<string, boolean> = {};
  let rawSum: number | null = null;
  (row?.refereeScores || []).forEach((rs: any) => {
    const k = refKey(rs);
    if (!k) return;
    const v = rs.score == null || rs.score === '' ? null : Number(rs.score);
    scores[k] = v != null && Number.isFinite(v) ? v : null;
    scored[k] = rs.scored === true || scores[k] != null;
    if (scores[k] != null) rawSum = (rawSum ?? 0) + (scores[k] as number);
  });
  matrixRows.value = cols.length
    ? [
        {
          key: 'score',
          name: '得分',
          scores,
          scored,
          total: row?.score != null && row.score !== '' && Number.isFinite(Number(row.score)) ? Number(row.score) : rawSum
        }
      ]
    : [];
};

let loadSeq = 0;
const load = async () => {
  if (props.mode === 'edit') return;
  const wid = props.widgetId;
  if (wid == null || wid === '') {
    resetView();
    return;
  }
  const seq = ++loadSeq;
  try {
    const tr: any = await getWidgetScoreDetail(wid);
    if (seq !== loadSeq) return;
    const t = tr?.data ?? tr ?? null;
    const matchId = t?.matchId;
    const competitorId = t?.competitorId;
    if (matchId == null || matchId === '' || competitorId == null || competitorId === '') {
      resetView();
      return;
    }
    target.value = { matchId, competitorId };

    const tid = tournamentId.value;
    const [mr, cr, plr] = await Promise.all([
      getMatch(matchId),
      tid != null ? listCompetitor({ tournamentId: tid, pageNum: 1, pageSize: 999 } as any).catch(() => null) : Promise.resolve(null),
      tid != null ? listPlayer({ tournamentId: tid, pageNum: 1, pageSize: 999 } as any).catch(() => null) : Promise.resolve(null)
    ]);
    if (seq !== loadSeq) return;
    const match: any = (mr as any)?.data ?? mr ?? null;
    const row = (match?.roundScores || []).find((r: any) => String(r.competitorId) === String(competitorId));
    competitorName.value = row?.competitorName || '';
    competitorNumber.value = row?.competitorNumber || '';
    totalScore.value = row?.score != null && row.score !== '' && Number.isFinite(Number(row.score)) ? Number(row.score) : null;
    buildMatrix(row || {});

    const unpack = (resp: any): any[] => {
      const list = resp?.data?.data || resp?.data || resp?.rows || [];
      return Array.isArray(list) ? list : [];
    };
    const hit = unpack(cr).find((c: any) => String(c.id) === String(competitorId));
    const player = unpack(plr).find((p: any) => String(p.competitorId) === String(competitorId));
    avatar.value = player?.avatar || hit?.avatar || hit?.playerList?.[0]?.avatar || '';
    if (!competitorName.value) competitorName.value = hit?.name || '';
    if (!competitorNumber.value) competitorNumber.value = hit?.number || '';
  } catch (e) {
    if (seq !== loadSeq) return;
    console.error('加载评分详情失败:', e);
    resetView();
  }
};

const handleTournamentEvent = () => {
  load();
};

const applyScale = () => {
  const el = viewRef.value;
  if (!el) return;
  const s = Math.min(el.clientWidth / 800, el.clientHeight / 400);
  scale.value = Math.max(0.55, Math.min(2.4, s || 1));
};

let resizeObserver: ResizeObserver | null = null;
const startObserve = () => {
  nextTick(() => {
    resizeObserver?.disconnect();
    if (!viewRef.value) return;
    resizeObserver = new ResizeObserver(() => applyScale());
    resizeObserver.observe(viewRef.value);
    applyScale();
  });
};

onMounted(() => {
  startObserve();
  load();
  const tid = tournamentId.value;
  if (tid != null) subscribeTournamentEvents(tid, handleTournamentEvent);
});

onUnmounted(() => {
  const tid = tournamentId.value;
  if (tid != null) unsubscribeTournamentEvents(tid, handleTournamentEvent);
  resizeObserver?.disconnect();
});

watch(
  () => [props.widgetId, props.mode, tournamentId.value] as const,
  (curr, prev) => {
    if (curr[2] !== prev?.[2]) {
      if (prev?.[2] != null) unsubscribeTournamentEvents(prev[2], handleTournamentEvent);
      if (curr[2] != null) subscribeTournamentEvents(curr[2], handleTournamentEvent);
    }
    if (curr[1] !== 'edit') {
      startObserve();
    }
    load();
  }
);
</script>
