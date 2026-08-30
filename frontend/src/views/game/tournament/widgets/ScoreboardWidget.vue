<template>
  <div class="w-full h-full">
    <!-- 查看模式:海选比分牌,按圈分列 -->
    <div v-if="mode !== 'edit'" class="w-full h-full overflow-hidden relative">
      <div v-if="loading" class="w-full h-full flex items-center justify-center text-white/50" :style="fz(1)">加载中...</div>
      <div v-else-if="error" class="w-full h-full flex items-center justify-center text-white/40 px-4 text-center" :style="fz(0.85)">{{ error }}</div>
      <div v-else-if="!stageId" class="w-full h-full flex items-center justify-center text-white/40" :style="fz(0.85)">未绑定海选赛段</div>
      <div v-else class="w-full h-full flex flex-col">
        <!-- 标题栏 -->
        <div class="px-3 py-2 flex items-center justify-between flex-none">
          <span class="text-white font-bold truncate" :style="fz(1.2)">{{ stageName || '海选比分牌' }}</span>
          <span class="text-white/50 flex-none" :style="fz(0.85)">晋级 {{ advanceCount }} 名</span>
        </div>

        <!-- 多圈多列:每圈一列并排,只展示晋级选手,按签到号码排序 -->
        <div class="flex-1 min-h-0 flex gap-2 p-2 items-stretch">
          <div v-for="col in columns" :key="col.zone" class="flex-1 min-w-0 flex flex-col min-h-0 overflow-hidden">
            <div class="px-2.5 py-1.5 flex-none">
              <span v-if="columns.length > 1 && refereeNames.length" class="block text-white/35 truncate" :style="fz(0.7)"
                >裁判: {{ colRefereeText(col) }}</span
              >
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto p-1.5 space-y-1 scrollbar-hide">
              <div v-for="p in col.advancers" :key="p.competitorId" class="flex items-center gap-1.5 px-1 py-1" :style="fz(1)">
                <span class="text-white/45 font-mono flex-none">No.{{ number(p) }}</span>
                <span class="flex-1 truncate text-white">{{ name(p) }}</span>
                <span v-if="showScore" class="text-white/60 font-mono flex-none">{{ score(p) }}</span>
              </div>
              <div v-if="!col.advancers.length" class="text-white/30 text-center py-2" :style="fz(0.85)">待定</div>
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
        <div class="mt-3">
          <TextInput label="字号(px)" :model-value="String(fontSize ?? 12)" placeholder="12" @update:model-value="handleFontSizeUpdate" />
        </div>
        <p class="text-[10px] text-neutral-600 mt-2">
          展示海选(海选赛)晋级结果,分圈时每圈一列并排显示;开启"显示分数"后展示各参赛方总分。字号按"晋级名单"整体缩放,大屏/人数多时可调大或调小。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import StageSelector from '../stages/StageSelector.vue';
import CheckboxGroup from './common/CheckboxGroup.vue';
import TextInput from './common/TextInput.vue';
import { getStage } from '@/api/game/stage';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import { listCompetitor } from '@/api/game/competitor';
import { getStageRefereeIds } from '@/api/game/refereeStage';
import { listReferee } from '@/api/game/referee';
import { listMatchReferee } from '@/api/game/matchReferee';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number | null;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  showScore?: boolean;
  fontSize?: number;
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
  'update:showScore': [v: boolean];
  'update:fontSize': [v: number];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = () => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null;

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');
const stageName = ref('');
const advanceCount = ref(0);
const refereeNames = ref<string[]>([]);
const circleReferees = ref<Record<string, string[]>>({});
const compMap = ref<Record<string, any>>({});
const columns = ref<{ zone: string; title: string; matchId: string | number | null; advancers: any[] }[]>([]);

/** 列(圈)的裁判文本:优先按圈绑定,未绑定时回退到赛段裁判名单 */
const colRefereeText = (col: any) => {
  if (col?.matchId != null) {
    const names = circleReferees.value[String(col.matchId)];
    if (names && names.length) return names.join(' / ');
  }
  return refereeNames.value.length ? refereeNames.value.join(' / ') : '';
};

const handleOptionUpdate = (key: string, value: boolean) => {
  if (key === 'showScore') emit('update:showScore', value);
};

/** 字号缩放:以 fontSize(默认 12px)为基准,标题/列表按比例联动,限制在 6~80px */
const fz = (ratio: number) => {
  const base = Math.max(6, Math.min(80, Number(props.fontSize) || 12));
  return {
    fontSize: `${Math.round(base * ratio)}px`,
    lineHeight: `${Math.round(base * ratio * 1.4)}px`
  };
};

const handleFontSizeUpdate = (v: string) => {
  const n = Number(v);
  emit('update:fontSize', Number.isFinite(n) && n > 0 ? n : 12);
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
    // 分圈展示用:该赛段已分配的裁判名单(裁判为赛段级分配,各圈一致)
    try {
      const ridResp: any = await getStageRefereeIds(props.stageId);
      const ids = (ridResp?.data ?? []) as (string | number)[];
      if (ids.length) {
        const refResp: any = await listReferee({
          tournamentId: tournamentId(),
          pageNum: 1,
          pageSize: 99
        });
        const refs = refResp?.data ?? [];
        refereeNames.value = ids
          .map((id) => refs.find((r: any) => String(r.id) === String(id)))
          .filter(Boolean)
          .map((r: any) => r.name);
      } else {
        refereeNames.value = [];
      }
    } catch (e) {
      console.error('ScoreboardWidget 加载裁判失败', e);
      refereeNames.value = [];
    }
    // 按圈绑定的裁判(海选分圈时每圈可一个/多个裁判)
    try {
      const mrResp: any = await listMatchReferee(props.stageId);
      const rows = mrResp?.data ?? [];
      const map: Record<string, string[]> = {};
      rows.forEach((r: any) => {
        if (r.matchId == null || !r.refereeName) return;
        const k = String(r.matchId);
        (map[k] = map[k] || []).push(r.refereeName);
      });
      circleReferees.value = map;
    } catch (e) {
      console.error('ScoreboardWidget 加载按圈裁判失败', e);
      circleReferees.value = {};
    }

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
          const parts = (pr?.data?.data || pr?.data || []).slice().sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));
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
      const zoneMatches = grouped.get(zone) || [];
      // 只取晋级选手,按签到号码升序
      const advancers = zoneMatches
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
        matchId: zoneMatches[0]?.id ?? null,
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
const score = (p: any) => (p?.scoreValue == null ? '–' : Number(p.scoreValue).toFixed(2));

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
