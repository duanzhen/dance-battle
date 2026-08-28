<template>
  <div class="fixed inset-0 bg-neutral-950 text-neutral-200 font-sans flex flex-col overflow-hidden select-none">
    <header class="h-12 bg-neutral-900 border-b border-neutral-800 flex items-center px-4 shrink-0 z-20">
      <div class="flex-1 flex items-center gap-2 min-w-0">
        <img :src="logo" class="w-6 h-6 rounded object-contain shrink-0" alt="logo" />
        <span class="font-bold text-xs text-neutral-300 truncate">{{ tournamentName || '手机导播台' }}</span>
      </div>
      <!-- 中间秒表 -->
      <div class="flex-none flex items-center gap-0.5">
        <button
          @click="toggleStopwatch"
          class="flex items-center gap-1.5 font-mono text-base font-black text-amber-400 tabular-nums leading-none bg-transparent border-0 cursor-pointer active:opacity-60 transition-opacity"
        >
          <span class="w-1.5 h-1.5 rounded-full" :class="stopwatchRunning ? 'bg-red-500 animate-pulse' : 'bg-neutral-600'"></span>
          {{ stopwatchText }}
        </button>
        <button
          @click="resetStopwatch"
          title="清空计时"
          class="w-8 h-8 flex items-center justify-center rounded text-neutral-600 hover:text-neutral-400 hover:bg-neutral-800/70 active:opacity-50 transition-colors bg-transparent border-0 cursor-pointer"
        >
          <el-icon :size="16"><TimerReset /></el-icon>
        </button>
      </div>
      <div class="flex-1 flex items-center justify-end gap-3">
        <div
          class="flex items-center gap-1.5 px-2 py-1 rounded text-[10px] font-bold"
          :class="isLive ? 'bg-red-600/20 text-red-500 border border-red-600/30' : 'bg-neutral-800 text-neutral-500 border border-neutral-700'"
        >
          <span class="w-1.5 h-1.5 rounded-full" :class="isLive ? 'bg-red-500 animate-pulse' : 'bg-neutral-500'"></span>
          {{ isLive ? 'LIVE' : 'STANDBY' }}
        </div>
      </div>
    </header>

    <div v-if="authError" class="flex-1 flex items-center justify-center">
      <div class="text-center px-6">
        <div class="w-12 h-12 rounded-full bg-red-600/20 border border-red-600/30 flex items-center justify-center mx-auto mb-3">
          <svg class="w-6 h-6 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
          </svg>
        </div>
        <p class="text-sm text-red-400 font-bold mb-1">认证失败</p>
        <p class="text-xs text-neutral-500">{{ authError }}</p>
      </div>
    </div>

    <div v-else class="flex-1 flex flex-col overflow-hidden">
      <div class="flex-none px-4 pt-3 pb-2">
        <h3 class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider flex items-center gap-2">
          <Layers class="w-3.5 h-3.5" /> 赛段流程
        </h3>
      </div>

      <div class="flex-none px-2 overflow-x-auto custom-scrollbar-x">
        <div class="flex items-center gap-2 pb-2 min-w-max">
          <button
            v-for="(stage, index) in stages"
            :key="stage.id"
            @click="selectStage(stage.id)"
            class="flex-none w-32 py-3 px-3 rounded-xl border-2 transition-all duration-200 text-left"
            :class="[
              selectedStageId === stage.id ? 'border-amber-500 bg-amber-500/5' : 'border-neutral-800 bg-neutral-900/50',
              stage.status === 'GAMING' && selectedStageId !== stage.id ? 'border-green-500/30' : ''
            ]"
          >
            <div class="flex items-center justify-between mb-1">
              <span class="text-[9px] font-mono text-neutral-500">赛段 {{ index + 1 }}</span>
              <span
                class="text-[8px] font-bold px-1.5 py-0.5 rounded-full"
                :class="{
                  'bg-blue-600/20 text-blue-400': stage.status === 'DRAFT',
                  'bg-neutral-700 text-neutral-400': stage.status === 'PENDING',
                  'bg-green-600/20 text-green-400': stage.status === 'GAMING',
                  'bg-amber-600/20 text-amber-400': stage.status === 'SETTLED',
                  'bg-red-600/20 text-red-400': stage.status === 'DISCARD'
                }"
              >
                {{ getStatusText(stage.status) }}
              </span>
            </div>
            <h4 class="text-xs font-bold truncate" :class="selectedStageId === stage.id ? 'text-white' : 'text-neutral-300'">
              {{ stage.name }}
            </h4>
            <div class="flex items-center gap-1 mt-1 text-[9px] text-neutral-500">
              <span>{{ stage.teamCountStart }}</span>
              <ChevronRight class="w-2.5 h-2.5" />
              <span class="text-amber-500">{{ stage.teamCountEnd }}</span>
            </div>
          </button>
        </div>
      </div>

      <div class="flex-1 overflow-y-auto px-4 pt-2 pb-4" v-if="currentStage">
        <div class="bg-neutral-900 rounded-xl border border-neutral-800 overflow-hidden">
          <div class="px-4 py-3 border-b border-neutral-800 flex items-center justify-between">
            <div>
              <h4 class="text-sm font-bold text-white">{{ currentStage.name }}</h4>
            </div>
            <span
              class="text-[10px] font-bold px-2 py-1 rounded"
              :class="{
                'bg-blue-600/20 text-blue-400': currentStage.status === 'DRAFT',
                'bg-neutral-700 text-neutral-400': currentStage.status === 'PENDING',
                'bg-green-600/20 text-green-400': currentStage.status === 'GAMING',
                'bg-amber-600/20 text-amber-400': currentStage.status === 'SETTLED',
                'bg-red-600/20 text-red-400': currentStage.status === 'DISCARD'
              }"
            >
              {{ getStatusText(currentStage.status) }}
            </span>
          </div>

          <div class="p-4">
            <div class="grid grid-cols-2 gap-2">
              <button
                @click="handleStartStage"
                :disabled="!canStart"
                class="py-2.5 px-3 rounded-lg text-xs font-bold transition-all duration-200 flex items-center justify-center gap-1.5"
                :class="
                  canStart
                    ? 'bg-green-600/20 text-green-400 border border-green-600/30 active:bg-green-600/30'
                    : 'bg-neutral-800 text-neutral-600 border border-neutral-800 cursor-not-allowed'
                "
              >
                <Play class="w-3.5 h-3.5" /> 开始赛段
              </button>
              <button
                @click="handleComplete"
                :disabled="currentStage.status !== 'GAMING'"
                class="py-2.5 px-3 rounded-lg text-xs font-bold transition-all duration-200 flex items-center justify-center gap-1.5"
                :class="
                  canComplete
                    ? 'bg-amber-600/20 text-amber-400 border border-amber-600/30 active:bg-amber-600/30'
                    : 'bg-neutral-800 text-neutral-600 border border-neutral-800 cursor-not-allowed'
                "
              >
                <CircleCheck class="w-3.5 h-3.5" /> 完成赛段
              </button>
              <button
                v-if="currentStage.stageMode === 'ARENA' && currentStage.status === 'GAMING' && !hasGamingMatch"
                @click="handleArenaNext"
                class="py-2.5 px-3 rounded-lg text-xs font-bold transition-all duration-200 flex items-center justify-center gap-1.5 bg-amber-600/15 text-amber-400 border border-amber-600/30 active:bg-amber-600/30"
              >
                <Play class="w-3.5 h-3.5" /> 开始下一场
              </button>
            </div>

            <p
              v-if="currentStage && !canStart && (currentStage.status === 'DRAFT' || currentStage.status === 'PENDING')"
              class="mt-3 text-[10px] text-neutral-500 leading-relaxed"
            >
              <template v-if="currentStage.awaitingAdvancement">
                上一赛段已结束，晋级选手需先在管理端「中间态」确认晋级；确认后「开始赛段」将自动可用。
              </template>
              <template v-else>
                上一赛段「{{ prevStage?.name || '未知' }}」尚未结束，结束后方可开始本赛段。
              </template>
            </p>

            <div
              v-if="currentStage.status === 'DRAFT' || currentStage.status === 'PENDING'"
              class="mt-3 p-3 rounded-lg bg-blue-500/5 border border-blue-500/10"
            >
              <p v-if="currentStage.stageMode === 'ARENA'" class="text-[10px] text-blue-400/70">
                点击「开始赛段」将自动创建第一场对决；之后每场判完点「开始下一场」，胜者守擂、败者排到队尾，平局时擂主与挑战者均排到队尾。
              </p>
              <p v-else class="text-[10px] text-blue-400/70">
                晋级选手需先在管理端「中间态」确认；确认后点击「开始赛段」将自动初始化并生成对阵，淘汰赛场次保持待开始、逐场点「开始」。
              </p>
            </div>
            <div v-if="currentStage.status === 'GAMING'" class="mt-3 p-3 rounded-lg bg-green-500/5 border border-green-500/10">
              <p class="text-[10px] text-green-400/70">赛段正在进行中，裁判可录入比分。完成后点击"完成赛段"结算排名。</p>
            </div>
            <div v-if="currentStage.status === 'SETTLED'" class="mt-3 p-3 rounded-lg bg-amber-500/5 border border-amber-500/10">
              <p class="text-[10px] text-amber-400/70">
                赛段已结算。晋级选手需在管理端「中间态」确认晋级后写入下一赛段，确认前下一赛段无法开始。
              </p>
            </div>
          </div>
        </div>

        <div v-if="matches.length > 0" class="mt-3">

          <h4 class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-2 px-1">
            {{ currentStage?.stageMode === 'AUDITION' ? '海选评分' : '场次' }}
          </h4>
          <!-- 淘汰赛:左红右蓝指示点,与下方选手名字列对齐 -->
          <div v-if="currentStage?.stageMode !== 'AUDITION'" class="flex items-center gap-2 mb-1.5 px-1">
            <div class="flex-1 flex justify-center">
              <span class="w-2 h-2 rounded-full bg-red-500"></span>
            </div>
            <span class="w-14"></span>
            <div class="flex-1 flex justify-center">
              <span class="w-2 h-2 rounded-full bg-blue-500"></span>
            </div>
          </div>
          <!-- 海选赛:圈(match) → 轮次(round) → 评分(支持分圈) -->
          <div v-if="currentStage?.stageMode === 'AUDITION'" class="space-y-2">
            <div v-for="match in matches" :key="match.id" class="bg-neutral-900 rounded-lg border border-neutral-800 p-3">
              <div class="flex items-center justify-between mb-2">
                <span class="text-[9px] text-neutral-500">{{ match.name }}</span>
                <span
                  class="text-[8px] px-1.5 py-0.5 rounded"
                  :class="{
                    'bg-amber-600/20 text-amber-400': match.status === 'SETTLED',
                    'bg-green-600/20 text-green-400': match.status === 'GAMING',
                    'bg-neutral-700 text-neutral-400': match.status === 'PENDING'
                  }"
                >
                  {{ match.status === 'SETTLED' ? '已结束' : match.status === 'GAMING' ? '进行中' : '待开始' }}
                </span>
                <span
                  v-if="match.status === 'GAMING' && auditionAllScored(match)"
                  class="text-[8px] px-1.5 py-0.5 rounded bg-green-500/20 text-green-400 border border-green-500/30"
                >
                  已全部评完
                </span>
              </div>

              <div class="space-y-1">
                <div v-for="r in match.roundScores" :key="r.roundId" class="py-1.5 border-b border-neutral-800/60 last:border-0">
                  <div class="flex items-center justify-between gap-2">
                    <span class="text-[9px] text-neutral-500 w-12 flex-none">第{{ r.roundSequence }}轮</span>
                    <span class="text-xs font-bold text-neutral-300 flex-1 truncate">{{ r.competitorName || '待定' }}</span>
                    <span
                      v-if="r.outcomeStatus === 'ADVANCE'"
                      class="text-[8px] px-1 py-0.5 rounded bg-green-500/15 text-green-400 border border-green-500/30 flex-none"
                    >
                      晋级
                    </span>
                    <span
                      v-else-if="r.outcomeStatus === 'ELIMINATED'"
                      class="text-[8px] px-1 py-0.5 rounded bg-red-500/10 text-red-400 border border-red-500/20 flex-none"
                    >
                      淘汰
                    </span>
                    <span class="text-xs font-bold text-amber-400">{{ r.score ?? '-' }}</span>
                  </div>
                  <div v-if="r.refereeScores && r.refereeScores.length" class="flex justify-end gap-2 mt-0.5">
                    <span v-for="rs in r.refereeScores" :key="rs.refereeId" class="text-[9px] text-neutral-600"
                      >{{ rs.refereeName || '裁判' }} {{ rs.score }}</span
                    >
                  </div>
                </div>
                <p v-if="!match.roundScores || match.roundScores.length === 0" class="text-[10px] text-neutral-600 py-1">该圈暂无轮次/评分</p>
              </div>

              <div class="flex justify-end gap-2 mt-2">
                <button
                  v-if="match.status === 'PENDING'"
                  @click="handleStartMatch(match)"
                  class="px-3 py-1.5 rounded-lg text-[10px] font-bold bg-green-600/15 text-green-400 border border-green-600/30 active:scale-95 transition-transform"
                >
                  开始
                </button>
                <!-- 默认收缩:点开下拉显示重启(仅赛段进行中) -->
                <div v-if="match.status === 'SETTLED' && currentStage?.status === 'GAMING'" class="relative">
                  <button
                    @click="toggleMore(match.id)"
                    class="w-6 h-6 rounded bg-transparent text-sm leading-none text-neutral-500 hover:text-neutral-300 active:opacity-60 transition-colors"
                  >
                    ⋯
                  </button>
                  <div
                    v-if="moreMatchId === match.id"
                    class="more-menu absolute right-0 top-full mt-1 z-20 rounded-lg border border-neutral-700 bg-neutral-900 shadow-lg overflow-hidden"
                  >
                    <button
                      @click="
                        handleRestartMatch(match);
                        toggleMore(match.id);
                      "
                      class="block w-full px-3 py-2 text-[10px] text-neutral-300 hover:bg-neutral-800 whitespace-nowrap bg-transparent border-0"
                    >
                      重启
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 其他赛制:对阵卡片 -->
          <div v-else class="space-y-2">
            <div
              v-for="match in matches"
              :key="match.id"
              class="rounded-lg border p-3 transition-all"
              :class="[
                match.status === 'GAMING'
                  ? 'border-2 border-green-400 bg-green-500/10'
                  : match.status === 'SETTLED'
                    ? 'bg-neutral-900 border-neutral-800'
                    : 'bg-neutral-900 border-neutral-800 opacity-80',
                expandedMatchId === match.id ? 'ring-1 ring-amber-500/40 shadow-lg shadow-black/40' : ''
              ]"
            >
              <div
                v-if="match.status === 'GAMING'"
                class="-mx-3 -mt-3 mb-2 rounded-t-lg bg-green-500 text-neutral-900 text-[10px] font-black py-1 flex items-center justify-center gap-1.5"
              >
                <span class="w-1.5 h-1.5 rounded-full bg-neutral-900 animate-pulse"></span> 进行中
              </div>
              <div class="flex items-center justify-between mb-1">
                <span class="text-[9px] text-neutral-500">{{ match.name }}</span>
                <span
                  class="text-[8px] px-1.5 py-0.5 rounded"
                  :class="{
                    'bg-amber-600/20 text-amber-400': match.status === 'SETTLED',
                    'bg-green-500 text-neutral-900 font-black flex items-center gap-1': match.status === 'GAMING',
                    'bg-neutral-700 text-neutral-400': match.status === 'PENDING'
                  }"
                >
                  <template v-if="match.status === 'GAMING'"> <span class="w-1 h-1 rounded-full bg-neutral-900 animate-pulse"></span> LIVE </template>
                  <template v-else>{{ match.status === 'SETTLED' ? '已结束' : '待开始' }}</template>
                </span>
              </div>
              <div class="flex items-center gap-2">
                <div class="flex-1 min-w-0 text-center">
                  <div v-if="match.leftWin" class="mb-1 flex justify-center" title="胜者">
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      width="18"
                      height="18"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="#f59e0b"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    >
                      <path
                        d="M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a2 2 0 0 1-1.943 1.484H6.796a2 2 0 0 1-1.943-1.484L2.019 6.02a.5.5 0 0 1 .798-.519l4.277 3.664a1 1 0 0 0 1.516-.294z"
                      />
                      <path d="M5 21h14" />
                    </svg>
                  </div>
                  <div class="text-xs font-bold truncate" :class="match.leftWin ? 'text-amber-400' : 'text-neutral-300'">
                    {{ match.leftName || '待定' }}
                  </div>
                </div>
                <span class="w-14 text-center text-[10px] text-neutral-600">VS</span>
                <div class="flex-1 min-w-0 text-center">
                  <div v-if="match.rightWin" class="mb-1 flex justify-center" title="胜者">
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      width="18"
                      height="18"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="#f59e0b"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    >
                      <path
                        d="M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a2 2 0 0 1-1.943 1.484H6.796a2 2 0 0 1-1.943-1.484L2.019 6.02a.5.5 0 0 1 .798-.519l4.277 3.664a1 1 0 0 0 1.516-.294z"
                      />
                      <path d="M5 21h14" />
                    </svg>
                  </div>
                  <div class="text-xs font-bold truncate" :class="match.rightWin ? 'text-amber-400' : 'text-neutral-300'">
                    {{ match.rightName || '待定' }}
                  </div>
                </div>
              </div>

              <!-- 实时判罚:一行一个裁判,行内按判定落位(判左→行左,判右→行右,判平→行中加横线,未判→行中) -->
              <div v-if="match.status === 'GAMING' && match.refereeVotes && match.refereeVotes.length" class="mt-2 space-y-1">
                <div v-for="rv in match.refereeVotes" :key="rv.refereeId" class="flex items-center gap-2 text-[9px]">
                  <div class="flex-1 min-w-0 text-center">
                    <span v-if="rv.vote === 'LEFT'" class="text-red-400 font-bold">{{ rv.refereeName || '裁判' }}</span>
                  </div>
                  <div class="w-14 text-center">
                    <span v-if="rv.vote === 'DRAW'" class="text-neutral-400 line-through decoration-neutral-500">{{ rv.refereeName || '裁判' }}</span>
                    <span v-else-if="!rv.vote" class="text-neutral-600">{{ rv.refereeName || '裁判' }}</span>
                  </div>
                  <div class="flex-1 min-w-0 text-center">
                    <span v-if="rv.vote === 'RIGHT'" class="text-blue-400 font-bold">{{ rv.refereeName || '裁判' }}</span>
                  </div>
                </div>
              </div>
              <div v-if="match.status === 'GAMING' && match.pendingPublish" class="mt-2 text-center">
                <span class="text-[9px] font-bold text-amber-400">裁判已判完，等待公布结果</span>
              </div>

              <!-- 导播台判定模式:直接选择谁赢 -->
              <div v-if="match.status === 'GAMING' && match.publishMode === 'DIRECTOR'" class="mt-2 grid grid-cols-3 gap-2">
                <button
                  @click="handleDirectorJudge(match, 'LEFT')"
                  :disabled="actionLoading"
                  class="py-2 rounded-lg text-[10px] font-bold bg-red-600/15 text-red-400 border border-red-600/30 active:bg-red-600/30 transition-colors disabled:opacity-40"
                >
                  红胜
                </button>
                <button
                  @click="handleDirectorJudge(match, 'DRAW')"
                  :disabled="actionLoading"
                  class="py-2 rounded-lg text-[10px] font-bold bg-neutral-800 text-neutral-300 border border-neutral-700 active:bg-neutral-700 transition-colors disabled:opacity-40"
                >
                  平
                </button>
                <button
                  @click="handleDirectorJudge(match, 'RIGHT')"
                  :disabled="actionLoading"
                  class="py-2 rounded-lg text-[10px] font-bold bg-blue-600/15 text-blue-400 border border-blue-600/30 active:bg-blue-600/30 transition-colors disabled:opacity-40"
                >
                  蓝胜
                </button>
              </div>

              <div class="flex justify-end gap-2 mt-2">
                <button
                  v-if="match.status === 'PENDING'"
                  @click="handleStartMatch(match)"
                  class="px-3 py-1.5 rounded-lg text-[10px] font-bold bg-green-600/15 text-green-400 border border-green-600/30 active:scale-95 transition-transform"
                >
                  开始
                </button>
                <button
                  v-if="match.status === 'GAMING' && match.pendingPublish && match.publishMode === 'MANUAL'"
                  @click="handlePublish(match)"
                  class="px-3 py-1.5 rounded-lg text-[10px] font-bold bg-amber-600/20 text-amber-400 border border-amber-600/30 active:scale-95 transition-transform"
                >
                  公布结果
                </button>
                <!-- 展开/收起:仅已结束场次查看各轮判罚明细与重启;进行中已实时展示无需展开 -->
                <button
                  v-if="match.status === 'SETTLED'"
                  @click="toggleExpand(match.id)"
                  class="w-7 h-7 rounded-lg flex items-center justify-center text-lg leading-none transition-colors"
                  :class="
                    expandedMatchId === match.id
                      ? 'text-amber-400 bg-amber-500/10'
                      : 'text-neutral-500 hover:text-neutral-300 active:opacity-60 bg-transparent'
                  "
                  title="展开判罚明细"
                >
                  ⋯
                </button>
              </div>

              <!-- 展开卡片:每轮参赛者 + 每个裁判判罚结果 + 重启(仅已结束) -->
              <div v-if="expandedMatchId === match.id" class="mt-3 rounded-xl border border-amber-500/25 bg-neutral-950/70 p-2.5 space-y-2">
                <div class="flex items-center justify-between px-0.5">
                  <span class="text-[9px] font-bold text-neutral-500 uppercase tracking-wider">轮次判罚明细</span>
                  <span class="text-[9px] text-neutral-600">{{ (match.roundVotes || []).length }} 轮</span>
                </div>
                <div v-for="r in match.roundVotes" :key="r.roundId" class="rounded-lg bg-neutral-900 border border-neutral-800 p-2">
                  <div class="flex items-center justify-between mb-1.5">
                    <span class="text-[10px] font-bold text-neutral-300">第{{ r.roundSequence }}轮</span>
                    <span
                      class="text-[8px] px-1.5 py-0.5 rounded"
                      :class="
                        r.outcome === 'DRAW'
                          ? 'bg-amber-500/10 text-amber-400'
                          : r.status === 'GAMING'
                            ? 'bg-green-500/10 text-green-400'
                            : 'bg-neutral-800 text-neutral-500'
                      "
                    >
                      {{ r.outcome === 'DRAW' ? '平局' : r.status === 'GAMING' ? '进行中' : '已结束' }}
                    </span>
                  </div>
                  <div class="flex items-center gap-2 text-[10px] mb-1.5">
                    <span class="flex-1 min-w-0 text-center font-bold truncate text-neutral-200">{{ r.leftName || match.leftName || '待定' }}</span>
                    <span class="w-10 flex-none text-center text-neutral-600">VS</span>
                    <span class="flex-1 min-w-0 text-center font-bold truncate text-neutral-200">{{ r.rightName || match.rightName || '待定' }}</span>
                  </div>
                  <div v-if="r.refereeVotes && r.refereeVotes.length" class="space-y-1">
                    <div v-for="rv in r.refereeVotes" :key="rv.refereeId" class="flex items-center gap-2 text-[9px]">
                      <div class="flex-1 min-w-0 text-center">
                        <span v-if="rv.vote === 'LEFT'" class="text-red-400 font-bold">{{ rv.refereeName || '裁判' }}</span>
                      </div>
                      <div class="w-14 flex-none text-center">
                        <span v-if="rv.vote === 'DRAW'" class="text-neutral-400 line-through decoration-neutral-500">{{
                          rv.refereeName || '裁判'
                        }}</span>
                        <span v-else-if="!rv.vote" class="text-neutral-600">{{ rv.refereeName || '裁判' }}</span>
                      </div>
                      <div class="flex-1 min-w-0 text-center">
                        <span v-if="rv.vote === 'RIGHT'" class="text-blue-400 font-bold">{{ rv.refereeName || '裁判' }}</span>
                      </div>
                    </div>
                  </div>
                  <p v-else class="text-[9px] text-neutral-600 text-center py-0.5">本轮暂无裁判判罚</p>
                </div>
                <p v-if="!match.roundVotes || match.roundVotes.length === 0" class="text-[10px] text-neutral-600 text-center py-2">
                  该场暂无轮次数据
                </p>

                <!-- 重启:仅已结束场次且赛段仍进行中,展开时可见 -->
                <div v-if="match.status === 'SETTLED' && currentStage?.status === 'GAMING'" class="flex justify-end pt-1.5 border-t border-neutral-800">
                  <button
                    @click="
                      handleRestartMatch(match);
                      toggleExpand(match.id);
                    "
                    class="px-3 py-1.5 rounded-lg text-[10px] font-bold bg-neutral-950 border border-neutral-600 text-neutral-300 hover:bg-neutral-800 active:scale-95 transition-all"
                  >
                    重启
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-else-if="!loading" class="flex-1 flex items-center justify-center">
        <p class="text-neutral-600 text-xs">暂无赛段数据</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Layers, Play, CircleCheck, ChevronRight, TimerReset } from 'lucide-vue-next';
import logo from '@/assets/logo/logo.png';
import {
  setDirectorAuthKey,
  getDirectorTournament,
  listDirectorStages,
  listDirectorMatches,
  directorStartStage,
  directorCompleteStage,
  directorArenaNext,
  directorStartMatch,
  directorResetMatch,
  directorSubmitResult,
  directorPublishResult
} from '@/api/game/director';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const route = useRoute();

const authError = ref('');
const tournamentId = ref<string>('');
const tournamentName = ref('');
const loading = ref(false);
const isLive = ref(false);

interface Stage {
  id: string;
  name: string;
  stageMode: string;
  format: string;
  teamCountStart: number;
  teamCountEnd: number;
  status: string;
  isInitialized: boolean;
  awaitingAdvancement?: boolean;
  prevStageId: string | null;
  nextStageId: string | null;
}

interface MatchInfo {
  id: string;
  name: string;
  status: string;
  leftName: string;
  rightName: string;
  leftCompetitorId?: string | number | null;
  rightCompetitorId?: string | number | null;
  leftWin?: boolean;
  rightWin?: boolean;
  winnerName?: string;
  publishMode?: string;
  pendingPublish?: boolean;
  leftVotes?: number;
  rightVotes?: number;
  drawVotes?: number;
  votedReferees?: number;
  totalReferees?: number;
  refereeVotes?: { refereeId?: string | number; refereeName?: string; vote?: string | null }[];
  roundScores?: MatchRoundScore[];
  roundVotes?: MatchRoundVote[];
}

interface MatchRoundVote {
  roundId: string | number;
  roundSequence: number;
  status: string;
  outcome?: string | null;
  leftName?: string;
  rightName?: string;
  refereeVotes?: { refereeId?: string | number; refereeName?: string; vote?: string | null }[];
}

interface MatchRoundScore {
  roundId: string | number;
  roundSequence: number;
  competitorId?: string | number | null;
  competitorName?: string;
  score?: number | null;
  outcomeStatus?: string | null;
  refereeScores?: { refereeId?: string | number; refereeName?: string; score?: number }[];
}

const stages = ref<Stage[]>([]);
const selectedStageId = ref<string>('');
const matches = ref<MatchInfo[]>([]);

const currentStage = computed(() => stages.value.find((s) => s.id === selectedStageId.value));

// 上一赛段:由有序赛段列表反查(流程规范:上一赛段未结束不可开始下一赛段)
const prevStage = computed(() => {
  if (!currentStage.value?.prevStageId) return null;
  return stages.value.find((s) => s.id === currentStage.value!.prevStageId) || null;
});

const canStart = computed(() => {
  if (!currentStage.value) return false;
  if (currentStage.value.status !== 'DRAFT' && currentStage.value.status !== 'PENDING') return false;
  // 上一赛段已结束但晋级者尚未在管理端中间态确认:禁止开始
  if (currentStage.value.awaitingAdvancement) return false;
  return !prevStage.value || prevStage.value.status === 'SETTLED';
});

const canComplete = computed(() => {
  if (!currentStage.value) return false;
  return currentStage.value.status === 'GAMING';
});
const hasGamingMatch = computed(() => matches.value.some((m) => m.status === 'GAMING'));

const getStatusText = (status: string) => {
  const map: Record<string, string> = {
    'DRAFT': '规划中',
    'PENDING': '待开始',
    'GAMING': '进行中',
    'SETTLED': '已结束',
    'DISCARD': '已取消'
  };
  return map[status] || status;
};

/** 海选场次是否已全部评完(所有轮次都有分数;二海/加赛同样适用) */
const auditionAllScored = (match: any): boolean => {
  const rs = match?.roundScores || [];
  return rs.length > 0 && rs.every((r: any) => r.score != null);
};

const safeId = (id: any): string | null => {
  if (!id || typeof id === 'object') return null;
  return String(id);
};

const loadStages = async () => {
  const id = route.query.id;
  if (!id || Array.isArray(id)) return;
  tournamentId.value = String(id);
  loading.value = true;

  try {
    const resp = await listDirectorStages(tournamentId.value);
    const data = resp.data?.data ?? resp.data;
    const loadedStages: Stage[] = (data || []).map((item: any) => ({
      id: String(item.id),
      name: item.name,
      stageMode: item.stageMode,
      format: item.format,
      teamCountStart: item.teamCountStart,
      teamCountEnd: item.teamCountEnd,
      status: item.status,
      isInitialized: item.isInitialized === 1,
      awaitingAdvancement: !!item.awaitingAdvancement,
      prevStageId: safeId(item.prevStageId),
      nextStageId: safeId(item.nextStageId)
    }));

    const stageMap = new Map<string, Stage>();
    loadedStages.forEach((s) => stageMap.set(s.id, s));

    let headStage: Stage | null = loadedStages.find((s) => s.prevStageId === null) || null;
    if (!headStage) headStage = loadedStages[0] || null;

    const sorted: Stage[] = [];
    let cur: Stage | null = headStage;
    const visited = new Set<string>();

    while (cur && !visited.has(cur.id)) {
      sorted.push(cur);
      visited.add(cur.id);
      cur = cur.nextStageId ? stageMap.get(cur.nextStageId) || null : null;
    }

    for (const s of loadedStages) {
      if (!visited.has(s.id)) sorted.push(s);
    }

    stages.value = sorted;

    if (sorted.length > 0) {
      selectedStageId.value = sorted[0].id;
      const gamingStage = sorted.find((s) => s.status === 'GAMING');
      if (gamingStage) {
        selectedStageId.value = gamingStage.id;
        isLive.value = true;
      }
    }
  } catch (e) {
    console.error('加载赛段失败:', e);
  } finally {
    loading.value = false;
  }
};

const loadMatchesForStage = async (stageId: string) => {
  try {
    const resp = await listDirectorMatches(stageId);
    const list = resp.data?.data ?? resp.data;
    matches.value = (list || []).map((m: any) => ({
      id: String(m.id),
      name: m.name || `场次 #${m.id}`,
      status: m.status || 'PENDING',
      leftName: m.leftName || m.teamA || '',
      rightName: m.rightName || m.teamB || '',
      leftCompetitorId: m.leftCompetitorId ?? null,
      rightCompetitorId: m.rightCompetitorId ?? null,
      leftWin: !!m.leftWin,
      rightWin: !!m.rightWin,
      winnerName: m.winnerName || '',
      publishMode: m.publishMode || 'AUTO',
      pendingPublish: !!m.pendingPublish,
      leftVotes: m.leftVotes ?? 0,
      rightVotes: m.rightVotes ?? 0,
      drawVotes: m.drawVotes ?? 0,
      votedReferees: m.votedReferees ?? 0,
      totalReferees: m.totalReferees ?? 0,
      refereeVotes: m.refereeVotes || [],
      roundScores: m.roundScores || [],
      roundVotes: m.roundVotes || []
    }));
  } catch (e) {
    console.error('加载场次失败:', e);
    matches.value = [];
  }
};

const selectStage = (id: string) => {
  selectedStageId.value = id;
  expandedMatchId.value = null;
};

const handleStartStage = async () => {
  const stage = currentStage.value;
  if (!stage) return;
  if (!confirm(`确认开始赛段「${stage.name}」？将自动初始化并生成对阵。`)) return;
  try {
    await directorStartStage(stage.id);
    isLive.value = true;
    await loadStages();
    await loadMatchesForStage(stage.id);
  } catch (e: any) {
    console.error('开始赛段失败:', e);
    alert(e?.message || '开始赛段失败');
  }
};

const handleComplete = async () => {
  const stage = currentStage.value;
  if (!stage) return;
  if (!confirm(`确认完成赛段「${stage.name}」？所有比赛将被结算。`)) return;
  try {
    const res: any = await directorCompleteStage(stage.id);
    await loadStages();
    await loadMatchesForStage(stage.id);

    const stillGaming = stages.value.some((s) => s.status === 'GAMING');
    isLive.value = stillGaming;
    if (res?.data?.data?.status && res.data.data.status !== 'SETTLED') {
      alert(stage.stageMode === 'AUDITION' ? '海选产生二海(同分加赛),完成二海判罚后才能结束赛段' : '赛段仍有未完成场次,完成全部判罚后才能结束赛段');
    }
  } catch (e: any) {
    console.error('完成赛段失败:', e);
    alert(e?.message || '完成赛段失败');
  }
};

/** 开始指定场次:跳过其他场次,先开始这一场 */
const handleStartMatch = async (match: MatchInfo) => {
  if (!confirm(`确认开始场次？其他未开始的场次保持待开始。`)) return;
  try {
    await directorStartMatch(match.id);
    await refreshMatches();
    await loadStages();
  } catch (e: any) {
    console.error('开始场次失败:', e);
    alert(e?.message || '开始场次失败');
  }
};

/** 擂台赛:创建并开始下一场对决(胜者守擂、败者排到队尾;平局时擂主与挑战者均排到队尾) */
const handleArenaNext = async () => {
  const stage = currentStage.value;
  if (!stage) return;
  if (!confirm(`确认开始下一场对决？将按轮转队列创建：擂主 vs 下一位挑战者。`)) return;
  try {
    await directorArenaNext(stage.id);
    await refreshMatches();
    await loadStages();
  } catch (e: any) {
    console.error('开始下一场对决失败:', e);
    alert(e?.response?.data?.msg || e?.message || '开始下一场对决失败');
  }
};

/** 重启场次:清空该场已提交的分数/结果,回到进行中 */
const handleRestartMatch = async (match: MatchInfo) => {
  if (!confirm(`确认重启场次 #${match.id}？将清空该场已提交的分数与结果。`)) return;
  try {
    await directorResetMatch(match.id);
    await refreshMatches();
  } catch (e: any) {
    console.error('重启场次失败:', e);
    alert(e?.message || '重启场次失败');
  }
};

const refreshMatches = async () => {
  if (selectedStageId.value) {
    await loadMatchesForStage(selectedStageId.value);
  }
};

/** SSE 赛事事件回调:赛段/场次/打分变化自动刷新 */
/** 事件回调:按事件上下文决定刷新范围,避免无关事件全量拉取 */
const handleTournamentEvent = (data: any) => {
  if (!data) {
    // 重连补偿:赛段与场次都刷新一次
    loadStages();
    if (selectedStageId.value) {
      loadMatchesForStage(selectedStageId.value);
    }
    return;
  }
  // 赛段级事件(开始/完成/晋级):刷新赛段列表与当前赛段场次
  if (data.type === 'stage') {
    loadStages();
    if (selectedStageId.value) {
      loadMatchesForStage(selectedStageId.value);
    }
    return;
  }
  // 场次级事件(打分/结算/重置/平局):仅当前选中赛段相关才刷新场次
  if (selectedStageId.value && data.stageId != null && String(data.stageId) === String(selectedStageId.value)) {
    loadMatchesForStage(selectedStageId.value);
  }
};

const actionLoading = ref(false);
const moreMatchId = ref<string | null>(null);
const toggleMore = (id: string) => {
  moreMatchId.value = moreMatchId.value === id ? null : id;
};
/** 淘汰赛场次卡片展开态:展开后显示各轮判罚明细与重启 */
const expandedMatchId = ref<string | null>(null);
const toggleExpand = (id: string) => {
  expandedMatchId.value = expandedMatchId.value === id ? null : id;
};

// --- 顶部秒表 ---
const stopwatchMs = ref(0);
const stopwatchRunning = ref(false);
let stopwatchTimer: ReturnType<typeof setInterval> | null = null;

const stopwatchText = computed(() => {
  const total = Math.floor(stopwatchMs.value / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
});

const toggleStopwatch = () => {
  if (stopwatchRunning.value) {
    stopwatchRunning.value = false;
    if (stopwatchTimer) {
      clearInterval(stopwatchTimer);
      stopwatchTimer = null;
    }
    return;
  }
  stopwatchRunning.value = true;
  const startAt = Date.now() - stopwatchMs.value;
  stopwatchTimer = setInterval(() => {
    stopwatchMs.value = Date.now() - startAt;
  }, 1000);
};

const resetStopwatch = () => {
  stopwatchMs.value = 0;
  // 计时中清空:从 0 秒继续计时,不暂停
  if (stopwatchRunning.value) {
    if (stopwatchTimer) {
      clearInterval(stopwatchTimer);
      stopwatchTimer = null;
    }
    const startAt = Date.now();
    stopwatchTimer = setInterval(() => {
      stopwatchMs.value = Date.now() - startAt;
    }, 1000);
  }
};

/** 导播台判定模式(DIRECTOR):导播台直接选择谁赢 */
const handleDirectorJudge = async (match: MatchInfo, side: 'LEFT' | 'DRAW' | 'RIGHT') => {
  if (actionLoading.value || !match.leftCompetitorId || !match.rightCompetitorId) return;
  const outcomes: Record<string, string> = {};
  if (side === 'LEFT') {
    outcomes[String(match.leftCompetitorId)] = 'WIN';
    outcomes[String(match.rightCompetitorId)] = 'LOSS';
  } else if (side === 'RIGHT') {
    outcomes[String(match.leftCompetitorId)] = 'LOSS';
    outcomes[String(match.rightCompetitorId)] = 'WIN';
  } else {
    outcomes[String(match.leftCompetitorId)] = 'DRAW';
    outcomes[String(match.rightCompetitorId)] = 'DRAW';
  }
  actionLoading.value = true;
  try {
    await directorSubmitResult(match.id, { outcomes });
    await refreshMatches();
    await loadStages();
  } catch (e: any) {
    console.error('导播台判定失败:', e);
    alert(e?.response?.data?.msg || e?.message || '判定失败');
  } finally {
    actionLoading.value = false;
  }
};

/** 手动公布模式(MANUAL):裁判判完确认后公布结果 */
const handlePublish = async (match: MatchInfo) => {
  if (!confirm(`确认公布场次 #${match.id} 的结果？`)) return;
  try {
    await directorPublishResult(match.id);
    await refreshMatches();
    await loadStages();
  } catch (e: any) {
    console.error('公布结果失败:', e);
    alert(e?.response?.data?.msg || e?.message || '公布失败');
  }
};

watch(selectedStageId, (newId) => {
  if (newId) {
    loadMatchesForStage(newId);
  } else {
    matches.value = [];
  }
});

onMounted(async () => {
  const key = route.query.authKey;
  if (!key || Array.isArray(key)) {
    authError.value = '缺少认证密钥，请扫描二维码进入';
    return;
  }
  setDirectorAuthKey(key);
  try {
    const resp = await getDirectorTournament();
    const t = resp.data?.data ?? resp.data;
    tournamentName.value = t?.name || '';
  } catch (e: any) {
    if (e?.response?.status === 401) {
      authError.value = '认证凭证无效，请扫描最新二维码进入';
      return;
    }
    console.error('加载赛事信息失败:', e);
  }
  await loadStages();
  if (selectedStageId.value) {
    await loadMatchesForStage(selectedStageId.value);
  }
  // SSE 实时订阅:赛段/场次/打分变化自动刷新,替代轮询
  subscribeTournamentEvents(tournamentId.value, handleTournamentEvent);
});

onUnmounted(() => {
  unsubscribeTournamentEvents(tournamentId.value, handleTournamentEvent);
  if (stopwatchTimer) {
    clearInterval(stopwatchTimer);
    stopwatchTimer = null;
  }
});
</script>

<style scoped>
/* 不显示任何滚动条(仍可滚动) */
:deep(*) {
  scrollbar-width: none;
  -ms-overflow-style: none;
}
:deep(*)::-webkit-scrollbar {
  display: none;
}
</style>
