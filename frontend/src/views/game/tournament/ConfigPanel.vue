<template>
  <div class="h-full overflow-y-auto custom-scrollbar bg-neutral-950 p-5 space-y-5">
    <!-- 配置面板头部 -->
    <div class="flex items-center justify-between">
      <h2 class="text-lg font-bold text-neutral-100 flex items-center gap-2">
        <Settings2 class="w-5 h-5 text-amber-500" /> 赛事配置
      </h2>
    </div>

    <!-- 基础信息 -->
    <GameInfo v-model="matchInfo">
      <template #actions>
        <button
          @click="showEdit = true"
          class="px-3 py-1.5 text-xs font-bold text-neutral-300 hover:text-amber-400 bg-neutral-800 hover:bg-neutral-700 rounded-lg transition-colors flex items-center gap-1.5"
        >
          <Pencil class="w-3.5 h-3.5" /> 编辑
        </button>
      </template>
      <!-- 裁判横排一行,放在基础概况卡片底部 -->
      <template #footer>
        <RefereeEditor :tournament-id="tournamentId" />
      </template>
    </GameInfo>

    <!-- 参赛选手:下方全宽 -->
    <PlayerEditor :tournament-id="tournamentId" />

    <!-- 编辑赛事弹窗:仅封面/名称/状态 -->
    <TournamentForm
      v-model="showEdit"
      :tournament="tournament"
      simple
      @submit-success="onTournamentEdited"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Settings2, Pencil } from 'lucide-vue-next';
import GameInfo, { type MatchInfo } from './../list/GameInfo.vue';
import PlayerEditor from './../list/Player.vue';
import RefereeEditor from './../list/Referee.vue';
import TournamentForm from './../list/TournamentForm.vue';
import { getTournament } from '@/api/game/tournament';
import { TournamentVO } from '@/api/game/tournament/types';

const props = defineProps<{
  tournamentId?: string | number | null;
}>();

// 当前赛事完整数据(编辑弹窗回填用)
const tournament = ref<TournamentVO | null>(null);
const showEdit = ref(false);

// --- 赛事基础信息 ---
const matchInfo = ref<MatchInfo>({
  title: '',
  startTime: '',
  location: '',
  stats: {
    prizePool: '0',
    attendance: 0,
    onlineHeat: '0',
    progress: 0
  }
});

// --- 加载赛事数据 ---
const loadTournamentData = async () => {
  if (!props.tournamentId) return;
  try {
    const response = await getTournament(props.tournamentId);
    const data: TournamentVO = response.data;
    tournament.value = data;
    matchInfo.value = {
      title: data.name,
      startTime: '',
      location: '',
      coverImage: data.coverImage || '',
      status: data.status ?? 0,
      remark: data.remark || '',
      logicalWidth: data.logicalWidth ? Number(data.logicalWidth) : 1920,
      logicalHeight: data.logicalHeight ? Number(data.logicalHeight) : 1080,
      stats: {
        prizePool: '0',
        attendance: 0,
        onlineHeat: '0',
        progress: data.status === 0 ? 0 : data.status === 1 ? 50 : 100
      }
    };
  } catch (error) {
    console.error('加载赛事数据失败:', error);
  }
};

// 编辑成功后的回调:刷新基础信息
const onTournamentEdited = async () => {
  await loadTournamentData();
};

onMounted(() => {
  loadTournamentData();
});
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: #171717;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #404040;
  border-radius: 3px;
}
</style>
