<template>
  <div class="w-full h-full">
    <!-- 查看模式:海选比分牌,按圈分列 -->
    <div v-if="mode !== 'edit'" class="w-full h-full bg-neutral-950/85 rounded-lg overflow-hidden relative">
      <div v-if="loading" class="w-full h-full flex items-center justify-center text-white/50 text-sm">加载中...</div>
      <div v-else-if="error" class="w-full h-full flex items-center justify-center text-white/40 text-xs px-4 text-center">{{ error }}</div>
      <div v-else-if="!stageId" class="w-full h-full flex items-center justify-center text-white/40 text-xs">未绑定海选赛段</div>
      <div v-else class="w-full h-full flex flex-col">
        <!-- 标题栏 -->
        <div class="px-3 py-2 border-b border-white/10 flex items-center justify-between flex-none">
          <span class="text-white font-bold text-sm truncate">{{ stageName || '海选比分牌' }}</span>
          <span class="text-white/50 text-[10px] flex-none">晋级 {{ advanceCount }} 名</span>
        </div>

        <!-- 多圈多列:每圈一列并排,只展示晋级选手,按签到号码排序 -->
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
              <div
                v-for="p in col.advancers"
                :key="p.competitorId"
                class="flex items-center gap-1.5 px-1 py-1 text-[11px] leading-tight"
              >
                <span class="flex-1 truncate text-white">{{ name(p) }}</span>
                <span v-if="showScore" class="text-white/60 font-mono flex-none">{{ score(p) }}</span>
                <span class="text-white/45 font-mono flex-none">No.{{ number(p) }}</span>
              </div>
              <div v-if="!col.advancers.length" class="text-white/30 text-[10px] text-center py-2">待定</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑模式:绑定海选赛段 + 显示选项 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">比分牌属性</span>
        <StageSelector
          label="绑定海选赛段"
          :model-value="(stageId as any) ?? null"
          only-mode="AUDITION"
          @update:model-value="$emit('update:stageId', $event)"
        />
        <div class="mt-3">
          <CheckboxGroup
            label="显示选项"
            :model-value="{ showScore }"
            :options="[{ key: 'showScore', label: '显示分数' }]"
            @update:item="handleOptionUpdate"
          />
        </div>
        <p class="text-[10px] text-neutral-600 mt-2">展示海选(海选赛)晋级结果,分圈时每圈一列并排显示;开启"显示分数"后展示各参赛方总分。</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import StageSelector from '../stages/StageSelector.vue';
import CheckboxGroup from './common/CheckboxGroup.vue';
import { getStage } from '@/api/game/stage';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import { listCompetitor } from '@/api/game/competitor';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number | null;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  showScore?: boolean;
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
  'update:showScore': [v: boolean];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = () => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null;

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');
const stageName = ref('');
const advanceCount = ref(0);
const compMap = ref<Record<string, any>>({});
const columns = ref<{ zone: string; title: string; advancers: any[] }[]>([]);

const handleOptionUpdate = (key: string, value: boolean) => {
  if (key === 'showScore') emit('update:showScore', value);
};

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
    if (stage.stageMode !== 'AUDITION') {
      error.value = '比分牌仅支持海选(选拔)赛段,请在编辑模式重新绑定';
      columns.value = [];
      return;
    }
    stageName.value = stage.name || '';
    advanceCount.value = Number(stage.teamCountEnd) || 0;

    // 参赛方映射(名称/号码)
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
          const pr: any = await listMatchParticipant({ matchId: m.id, pageNum: 1, pageSize: 99 } as any);
          const parts = (pr?.data?.data || pr?.data || [])
            .slice()
            .sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));
          return { ...m, participants: parts };
        } catch {
          return { ...m, participants: [] };
        }
      })
    );

    // 按圈(displayZone)分组,多圈多列
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
    // 主赛分映射:二海(同分加赛)只决定谁晋级,展示分数/名次仍用原海选分
    const normalScoreByCid: Record<string, number | undefined> = {};
    withParts.forEach((m: any) => {
      if (String(m.remark || '').startsWith('同分加赛')) return;
      (m.participants || []).forEach((p: any) => {
        if (p.competitorId != null && p.scoreValue != null) {
          normalScoreByCid[String(p.competitorId)] = Number(p.scoreValue);
        }
      });
    });
    columns.value = order.map((zone, idx) => {
      // 只取晋级选手,按签到号码升序
      const advancers = (grouped.get(zone) || [])
        .flatMap((m: any) => (m.participants || []).filter((p: any) => p.outcomeStatus === 'ADVANCE' && p.competitorId != null))
        .map((p: any) => ({
          ...p,
          // 二海选手沿用原海选分,不因二海高分改变排名展示
          scoreValue: normalScoreByCid[String(p.competitorId)] ?? p.scoreValue
        }))
        .sort((a: any, b: any) => numOf(a) - numOf(b));
      return {
        zone,
        title: order.length > 1 ? `第${idx + 1}圈` : '海选',
        advancers
      };
    });
  } catch (e) {
    console.error('ScoreboardWidget 加载失败', e);
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
