<template>
  <div class="w-full h-full">
    <!-- 查看模式:排名赛排行榜,按圈分列展示全部选手排名 -->
    <div v-if="mode !== 'edit'" class="w-full h-full bg-neutral-950/85 rounded-lg overflow-hidden relative">
      <div v-if="loading" class="w-full h-full flex items-center justify-center text-white/50 text-sm">加载中...</div>
      <div v-else-if="error" class="w-full h-full flex items-center justify-center text-white/40 text-xs px-4 text-center">{{ error }}</div>
      <div v-else-if="!stageId" class="w-full h-full flex items-center justify-center text-white/40 text-xs">未绑定排名赛赛段</div>
      <div v-else class="w-full h-full flex flex-col">
        <!-- 标题栏 -->
        <div class="px-3 py-2 border-b border-white/10 flex items-center justify-between flex-none">
          <span class="text-white font-bold text-sm truncate">{{ stageName || '排名展示' }}</span>
          <span class="text-white/50 text-[10px] flex-none">{{ publishScope === 'TOP_N' ? `前 ${advanceCount} 名` : '全部排名' }}</span>
        </div>

        <!-- 多圈多列:每圈一列并排,展示全部选手按排名排序 -->
        <div class="flex-1 min-h-0 flex gap-2 p-2 items-stretch">
          <div
            v-for="col in columns"
            :key="col.zone"
            class="flex-1 min-w-0 border border-white/10 rounded-lg flex flex-col min-h-0 overflow-hidden"
          >
            <div class="px-2.5 py-1.5 border-b border-white/10 flex-none">
              <span class="text-white/60 text-[11px] font-bold tracking-wider truncate">{{ col.title }}</span>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto p-1.5 space-y-1 scrollbar-hide">
              <div v-for="p in col.ranked" :key="p.competitorId" class="px-1 py-1 rounded">
                <div class="flex items-center gap-1.5 text-[11px] leading-tight">
                  <span class="w-5 h-5 flex-none rounded flex items-center justify-center text-[10px] font-black font-mono"
                    :class="p.rankInMatch == null ? 'bg-neutral-800 text-neutral-500' : rankBadgeClass(p.rankInMatch)">
                    {{ p.rankInMatch ?? '–' }}
                  </span>
                  <span class="flex-1 truncate text-white">{{ name(p) }}</span>
                  <span v-if="stageShowScore && scoreDisplay !== 'DETAIL'" class="text-white/60 font-mono flex-none">{{ score(p) }}</span>
                  <span class="text-white/45 font-mono flex-none">No.{{ number(p) }}</span>
                </div>
                <!-- 维度模式:总分 + 各维度分(赛段配置 scoreDisplay=DETAIL) -->
                <div v-if="stageShowScore && scoreDisplay === 'DETAIL'" class="flex flex-wrap gap-1 pl-6 pt-0.5">
                  <span
                    v-for="d in dimsOf(p)"
                    :key="d.key"
                    class="text-[9px] px-1.5 py-0.5 rounded bg-white/5 text-white/60 font-mono"
                  >
                    {{ d.name || d.key }} {{ d.score == null ? '–' : Number(d.score).toFixed(1) }}
                  </span>
                  <span class="text-[9px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400 font-mono font-bold">
                    总分 {{ score(p) }}
                  </span>
                </div>
              </div>
              <div v-if="!col.ranked.length" class="text-white/30 text-[10px] text-center py-2">待定</div>
            </div>
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
        <p class="text-[10px] text-neutral-600 mt-2">
          展示排名赛(多维度打分)的选手排名,分圈时每圈一列;是否显示分数由赛段配置决定,手动/批量公布模式下公布前自动隐藏。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue';
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
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = () => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null;

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');
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

/** 前三名徽章配色 */
const rankBadgeClass = (rank: number) => {
  if (rank === 1) return 'bg-amber-500 text-neutral-900';
  if (rank === 2) return 'bg-neutral-300 text-neutral-900';
  if (rank === 3) return 'bg-orange-700/80 text-white';
  return 'bg-neutral-800 text-neutral-400';
};

onMounted(() => {
  loadData();
  subscribeTournamentEvents(tournamentId(), handleTournamentEvent);
});
onUnmounted(() => {
  unsubscribeTournamentEvents(tournamentId(), handleTournamentEvent);
});

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
