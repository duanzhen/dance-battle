<template>
  <div class="w-full h-full">
    <!-- 查看模式:排名赛排行榜,按圈分列展示全部选手排名 -->
    <div v-if="mode !== 'edit'" ref="viewRef" class="w-full h-full rounded-lg overflow-hidden relative">
      <!-- 遮罩层:透明度仅作用于深色底,文字与表格内容保持不透明 -->
      <div class="absolute inset-0 rounded-lg bg-neutral-950" :style="maskStyle"></div>
      <div v-if="loading" class="relative w-full h-full flex items-center justify-center text-white/50" :style="fz(15)">加载中...</div>
      <div v-else-if="error" class="relative w-full h-full flex items-center justify-center text-white/40 px-4 text-center" :style="fz(13)">{{ error }}</div>
      <div v-else-if="!stageId" class="relative w-full h-full flex items-center justify-center text-white/40" :style="fz(13)">未绑定排名赛赛段</div>
      <div v-else class="relative w-full h-full flex flex-col">
        <!-- 标题栏 -->
        <div class="px-3 py-2 border-b border-white/10 flex items-center justify-between flex-none">
          <span class="text-white font-bold truncate" :style="fz(15)">{{ stageName || '排名展示' }}</span>
          <span class="text-white/50 flex-none" :style="fz(11)">{{ publishScope === 'TOP_N' ? `前 ${advanceCount} 名` : '全部排名' }}</span>
        </div>

        <!-- 表格展示:每圈一张表,列为 名次/选手/各维度/总分/号码 -->
        <div class="flex-1 min-h-0 overflow-y-auto p-2 scrollbar-hide">
          <div v-for="col in columns" :key="col.zone" class="mb-3">
            <div v-if="columns.length > 1" class="text-white/60 font-bold mb-1" :style="fz(12)">{{ col.title }}</div>
            <table class="w-full border-collapse" :style="fz(12)">
              <thead>
                <tr class="text-left text-white/50 border-b border-white/10">
                  <th class="py-1.5 px-2 font-bold text-left">号码</th>
                  <th class="py-1.5 px-2 font-bold">选手</th>
                  <th
                    v-if="stageShowScore && scoreDisplay === 'DETAIL' && tableDims.length"
                    v-for="d in tableDims"
                    :key="'h-' + d.key"
                    class="py-1.5 px-2 font-bold"
                  >
                    {{ d.name || d.key }}
                  </th>
                  <th v-if="stageShowScore" class="py-1.5 px-2 font-bold text-right">总分</th>
                  <th class="py-1.5 px-2 font-bold text-right">名次</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in col.ranked" :key="p.competitorId" class="border-b border-white/5">
                  <td class="py-1.5 px-2 text-left text-white/45 font-mono">No.{{ number(p) }}</td>
                  <td class="py-1.5 px-2 text-white truncate max-w-[160px]">{{ name(p) }}</td>
                  <td
                    v-if="stageShowScore && scoreDisplay === 'DETAIL' && tableDims.length"
                    v-for="d in tableDims"
                    :key="'v-' + p.competitorId + '-' + d.key"
                    class="py-1.5 px-2 font-mono text-white/70"
                  >
                    {{ dimScore(p, d.key) }}
                  </td>
                  <td v-if="stageShowScore" class="py-1.5 px-2 text-right font-mono font-bold text-amber-400">{{ score(p) }}</td>
                  <td class="py-1.5 px-2 text-right">
                    <span class="font-black font-mono" :class="rankClass(p.rankInMatch)">{{ p.rankInMatch ?? '–' }}</span>
                  </td>
                </tr>
                <tr v-if="!col.ranked.length">
                  <td colspan="99" class="py-3 text-center text-white/30">待定</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑模式:绑定排名赛赛段 + 显示选项 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">排名展示属性</span>
        <StageSelector
          label="绑定排名赛赛段"
          :model-value="(stageId as any) ?? null"
          only-mode="RANK"
          @update:model-value="$emit('update:stageId', $event)"
        />
        <div class="mt-3">
          <label class="block text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-1">遮罩透明度</label>
          <div class="flex items-center gap-3">
            <input
              type="range"
              min="0"
              max="100"
              step="5"
              :value="maskTransparency"
              class="flex-1 accent-amber-500"
              @change="$emit('update:opacity', 100 - Number(($event.target as HTMLInputElement).value))"
            />
            <span class="text-xs text-neutral-400 font-mono w-10 text-right flex-none">{{ maskTransparency }}%</span>
          </div>
        </div>
        <p class="text-[10px] text-neutral-600 mt-2">
          展示排名赛(多维度打分)的选手排名,以表格展示,分圈时每圈一张表;是否显示分数由赛段配置决定,手动/批量公布模式下公布前自动隐藏。遮罩透明度仅作用于深色背景,不影响文字与表格内容。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import StageSelector from '../stages/StageSelector.vue';
import { getStage, getStageRankDetail } from '@/api/game/stage';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import { listCompetitor } from '@/api/game/competitor';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number | null;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  opacity?: number;
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
  'update:opacity': [v: number];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = () => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null;

/** 查看模式容器:用于测量实际渲染尺寸 */
const viewRef = ref<HTMLElement | null>(null);
let resizeObserver: ResizeObserver | null = null;

/** 字号缩放:以默认组件尺寸 800x600 为基准,大组件放大、小组件缩小,限制在 0.6~2.5 */
const scale = ref(1);
const fz = (base: number) => ({
  fontSize: `${Math.round(base * scale.value)}px`,
  lineHeight: `${Math.round(base * scale.value * 1.4)}px`
});

const startObserve = () => {
  resizeObserver?.disconnect();
  if (!viewRef.value) return;
  resizeObserver = new ResizeObserver((entries) => {
    const r = entries[0]?.contentRect;
    if (!r) return;
    const s = Math.min(r.width / 800, r.height / 600);
    scale.value = Math.max(0.6, Math.min(2.5, s));
  });
  resizeObserver.observe(viewRef.value);
};

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');

/** 遮罩不透明度 0-100(dataConfig.opacity),默认 85 与旧版 bg-neutral-950/85 视觉一致 */
const maskOpacity = computed(() => {
  const v = Number.isFinite(Number(props.opacity)) ? Number(props.opacity) : 85;
  return Math.max(0, Math.min(100, v));
});

/** 滑块方向:遮罩透明度 = 100 - 不透明度,右滑更透明 */
const maskTransparency = computed(() => Math.round(100 - maskOpacity.value));

/** 遮罩层样式:只作用于深色背景,内容保持不透明 */
const maskStyle = computed(() => ({ opacity: maskOpacity.value / 100 }));

const stageName = ref('');
const advanceCount = ref(0);
const stageShowScore = ref(true);
const publishScope = ref('ALL');
const scoreDisplay = ref('TOTAL');
const dimsByComp = ref<Record<string, any[]>>({});
const compMap = ref<Record<string, any>>({});
const columns = ref<{ zone: string; title: string; ranked: any[] }[]>([]);

const loadData = async () => {
  if (!props.stageId) {
    columns.value = [];
    error.value = '';
    return;
  }
  if (!loadedOnce.value) {
    loading.value = true;
  }
  error.value = '';
  try {
    const sg: any = await getStage(props.stageId);
    const stage = sg?.data;
    if (!stage) {
      throw new Error('赛段不存在');
    }
    if (stage.stageMode !== 'RANK') {
      error.value = '排名展示仅支持排名赛赛段,请在编辑模式重新绑定';
      columns.value = [];
      return;
    }
    stageName.value = stage.name || '';
    advanceCount.value = Number(stage.teamCountEnd) || 0;
    // 是否显示分数/公布范围是赛段级配置:由 widget 读取赛段 ruleConfig,不随 widget 保存
    try {
      const rule = JSON.parse(stage.ruleConfig || '{}');
      stageShowScore.value = rule.showScore !== false;
      publishScope.value = rule.publishScope || 'ALL';
      scoreDisplay.value = rule.scoreDisplay === 'DETAIL' ? 'DETAIL' : 'TOTAL';
    } catch {
      stageShowScore.value = true;
      publishScope.value = 'ALL';
      scoreDisplay.value = 'TOTAL';
    }
    // 维度模式:拉取排名明细(各维度聚合分),未公布时后端隐藏分数
    if (scoreDisplay.value === 'DETAIL') {
      try {
        const rd: any = await getStageRankDetail(props.stageId);
        const circlesData = rd?.data?.circles || [];
        const map: Record<string, any[]> = {};
        circlesData.forEach((c: any) => {
          (c.competitors || []).forEach((cc: any) => {
            if (cc.competitorId != null) map[String(cc.competitorId)] = cc.dimensions || [];
          });
        });
        dimsByComp.value = map;
      } catch {
        dimsByComp.value = {};
      }
    } else {
      dimsByComp.value = {};
    }

    try {
      const cr: any = await listCompetitor({ stageId: props.stageId, pageNum: 1, pageSize: 999 } as any);
      const data = cr?.data?.data || cr?.data || [];
      const map: Record<string, any> = {};
      data.forEach((c: any) => {
        map[c.id] = c;
      });
      compMap.value = map;
    } catch {
      compMap.value = {};
    }

    const mr: any = await listMatch({ stageId: props.stageId, pageNum: 1, pageSize: 999 } as any);
    const matches = (mr?.data?.data || mr?.data || [])
      .slice()
      .sort((a: any, b: any) => (a.displayRow ?? 0) - (b.displayRow ?? 0) || String(a.id).localeCompare(String(b.id)));

    const withParts = await Promise.all(
      matches.map(async (m: any) => {
        try {
          const pr: any = await listMatchParticipant({ matchId: m.id, pageNum: 1, pageSize: 999 } as any);
          const parts = (pr?.data?.data || pr?.data || [])
            .filter((p: any) => p.competitorId != null)
            .slice();
          return { ...m, participants: parts };
        } catch {
          return { ...m, participants: [] };
        }
      })
    );

    // 按圈(displayZone)分组,每圈一列,组内按排名排序(无排名按分数降序、再按号码)
    const order: string[] = [];
    const grouped = new Map<string, any[]>();
    withParts.forEach((m: any) => {
      const zone = m.displayZone || 'CENTER';
      if (!grouped.has(zone)) {
        grouped.set(zone, []);
        order.push(zone);
      }
      grouped.get(zone)!.push(m);
    });
    const circles = Math.max(1, order.length);
    const perCircle = circles > 1 ? Math.floor(advanceCount.value / circles) : advanceCount.value;
    columns.value = order.map((zone, idx) => {
      let ranked = (grouped.get(zone) || [])
        .flatMap((m: any) => m.participants || [])
        .slice()
        .sort((a: any, b: any) => {
          const ra = a.rankInMatch == null ? Number.MAX_SAFE_INTEGER : Number(a.rankInMatch);
          const rb = b.rankInMatch == null ? Number.MAX_SAFE_INTEGER : Number(b.rankInMatch);
          if (ra !== rb) return ra - rb;
          const sa = Number(a.scoreValue) || 0;
          const sb = Number(b.scoreValue) || 0;
          if (sa !== sb) return sb - sa;
          return numOf(a) - numOf(b);
        })
        .map((p: any) => ({ ...p, _perCircle: perCircle }));
      // 公布范围 TOP_N:只展示每圈晋级线内的前 N 名
      if (publishScope.value === 'TOP_N') {
        ranked = ranked.filter((p: any) => p.rankInMatch != null && p.rankInMatch <= perCircle);
      }
      return {
        zone,
        title: order.length > 1 ? `第${idx + 1}圈` : '排名',
        ranked
      };
    });
  } catch (e) {
    console.error('RankingWidget 加载失败', e);
    error.value = '加载失败,请检查赛段绑定';
    columns.value = [];
  } finally {
    loading.value = false;
    loadedOnce.value = true;
  }
};

const name = (p: any) => {
  const c = compMap.value[p?.competitorId];
  return c?.name || p?.competitorName || `#${p?.competitorId}`;
};
const number = (p: any) => {
  const c = compMap.value[p?.competitorId];
  return c?.number != null ? String(c.number) : '';
};
const numOf = (p: any) => {
  const n = parseInt(number(p), 10);
  return Number.isNaN(n) ? Number.MAX_SAFE_INTEGER : n;
};
const score = (p: any) => (p?.scoreValue == null ? '–' : Number(p.scoreValue).toFixed(1));
const dimsOf = (p: any) => dimsByComp.value[String(p.competitorId)] || [];

/** 表格维度列头:取第一个有维度分的选手配置(同赛段各选手维度一致) */
const tableDims = computed(() => {
  for (const col of columns.value) {
    for (const p of col.ranked) {
      const ds = dimsOf(p);
      if (ds.length) return ds;
    }
  }
  return [];
});

/** 某选手某维度分 */
const dimScore = (p: any, key: string) => {
  const d = dimsOf(p).find((x: any) => x.key === key);
  return d == null || d.score == null ? '–' : Number(d.score).toFixed(1);
};

/** 名次配色:前三名高亮 */
const rankClass = (rank: number | null) => {
  if (rank === 1) return 'text-amber-400';
  if (rank === 2) return 'text-neutral-300';
  if (rank === 3) return 'text-orange-400';
  return 'text-neutral-500';
};

onMounted(() => {
  loadData();
  startObserve();
  subscribeTournamentEvents(tournamentId(), handleTournamentEvent);
});
onUnmounted(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  unsubscribeTournamentEvents(tournamentId(), handleTournamentEvent);
});

// 编辑/查看模式切换时重新测量(编辑模式无 viewRef)
watch(
  () => props.mode,
  () => startObserve()
);

watch(
  () => [props.stageId, props.tournamentId],
  () => {
    loadData();
  }
);

/** 事件回调:重连补偿(null)或事件属于本赛段时才刷新 */
const handleTournamentEvent = (data: any) => {
  if (!data || data.stageId == null || String(data.stageId) === String(props.stageId)) {
    loadData();
  }
};
</script>

<style scoped>
.scrollbar-hide {
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.scrollbar-hide::-webkit-scrollbar {
  display: none;
}
</style>
