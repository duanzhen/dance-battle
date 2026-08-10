<template>
  <div class="fixed inset-0 bg-neutral-950 text-neutral-200 font-sans flex flex-col overflow-hidden select-none">
    <div v-if="loading" class="flex-1 flex items-center justify-center">
      <div class="flex flex-col items-center gap-3">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-500"></div>
        <p class="text-xs text-neutral-500">正在加载裁判信息...</p>
      </div>
    </div>

    <div v-else-if="error" class="flex-1 flex items-center justify-center p-4">
      <div class="text-center">
        <div class="w-12 h-12 rounded-full bg-red-600/20 border border-red-600/30 flex items-center justify-center mx-auto mb-3">
          <svg class="w-6 h-6 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
          </svg>
        </div>
        <p class="text-sm text-red-400 font-bold mb-1">认证失败</p>
        <p class="text-xs text-neutral-500">{{ error }}</p>
      </div>
    </div>

    <template v-else>
      <header class="h-12 bg-neutral-900 border-b border-neutral-800 flex items-center justify-between px-4 shrink-0 z-20">
        <div class="flex items-center gap-2 min-w-0">
          <div class="w-5 h-5 bg-amber-500 rounded flex items-center justify-center font-bold text-[10px] text-neutral-900 shrink-0">R</div>
          <span class="text-xs text-neutral-400 truncate">{{ refereeName }}</span>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-[10px] text-neutral-500 hidden md:inline">{{ stageName }}</span>
          <button
            @click="loadData(stageId ?? undefined, matchId ?? undefined)"
            class="w-7 h-7 rounded-lg flex items-center justify-center text-neutral-400 hover:text-amber-400 hover:bg-neutral-800 transition-colors"
            title="刷新"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h5M20 20v-5h-5M4.5 9A8 8 0 0 1 19 6.5M19.5 15A8 8 0 0 1 5 17.5" />
            </svg>
          </button>
          <span class="text-[8px] font-bold px-1.5 py-0.5 rounded bg-neutral-800 text-neutral-400 border border-neutral-700">
            {{ modeLabel }}
          </span>
          <span class="text-[8px] font-bold px-1.5 py-0.5 rounded bg-green-600/20 text-green-400 border border-green-600/30">LIVE</span>
        </div>
      </header>

      <!-- 多赛段并行切换(默认自动跟随当前进行中赛段) -->
      <div v-if="stages.length > 1" class="flex-none px-4 py-2 overflow-x-auto custom-scrollbar-x bg-neutral-900/50 border-b border-neutral-800">
        <div class="flex items-center gap-2 min-w-max">
          <span class="text-[9px] text-neutral-500 font-bold uppercase tracking-wider">赛段</span>
          <button
            v-for="s in stages"
            :key="s.id"
            @click="switchStage(s.id)"
            class="px-3 py-1.5 rounded-lg text-[10px] font-bold border transition-colors"
            :class="s.id === stageId ? 'bg-amber-500/15 border-amber-500/40 text-amber-400' : 'bg-neutral-800 border-neutral-700 text-neutral-400'"
          >
            {{ s.name }}
          </button>
        </div>
      </div>

      <!-- 比赛列表:顶部横向展示当前赛段全部场次 -->
      <div v-if="stageMatches.length > 0" class="flex-none px-4 py-2 overflow-x-auto no-scrollbar bg-neutral-900/50 border-b border-neutral-800">
        <div class="flex items-center gap-2 min-w-max">
          <span class="text-[9px] text-neutral-500 font-bold uppercase tracking-wider">比赛</span>
          <button
            v-for="m in stageMatches"
            :key="m.id"
            @click="m.status === 'GAMING' && switchMatch(m.id)"
            :disabled="m.status !== 'GAMING'"
            class="flex-none px-3 py-1.5 rounded-lg text-[10px] font-bold border transition-colors"
            :class="[
              m.id === selectedMatchId ? 'bg-amber-500/15 border-amber-500/40 text-amber-400' : 'bg-neutral-800 border-neutral-700 text-neutral-400',
              m.status === 'GAMING' && m.id !== selectedMatchId ? 'border-green-500/30 text-green-400/80' : '',
              m.status !== 'GAMING' ? 'opacity-50 cursor-not-allowed' : ''
            ]"
          >
            {{ m.name }}
            <span class="ml-1 opacity-70">{{ m.status === 'GAMING' ? '进行中' : m.status === 'SETTLED' ? '已结束' : '待开始' }}</span>
          </button>
        </div>
      </div>

      <div class="flex-1 overflow-y-auto">
        <div class="p-4">
          <!-- 淘汰赛/擂台赛:中部判罚区,按选中场次状态展示(参考海选布局) -->
          <div v-if="isKnockoutMode || isArenaMode" class="space-y-4">
            <template v-if="selectedStageMatch">
              <!-- 进行中:可判罚 -->
              <template v-if="selectedStageMatch.status === 'GAMING'">
                <div class="flex gap-2 overflow-x-auto no-scrollbar pt-1 pb-2">
                  <div
                    v-for="r in rounds"
                    :key="r.id"
                    class="flex-none px-3 py-1.5 rounded-lg border text-[10px] font-bold"
                    :class="r.id === currentRoundId
                      ? 'border-amber-500/50 bg-amber-500/10 text-amber-400'
                      : r.outcome === 'DRAW' ? 'border-neutral-700 bg-neutral-800/60 text-neutral-400'
                      : 'border-neutral-800 bg-neutral-900 text-neutral-500'"
                  >
                    第{{ r.roundSequence }}轮 · {{ r.id === currentRoundId ? '当前' : r.outcome === 'DRAW' ? '平局' : '已结算' }}
                  </div>
                </div>

                <div class="bg-neutral-900 rounded-xl border border-neutral-800 p-4">
                  <div class="text-center mb-3">
                    <div class="text-[10px] text-neutral-500">本场判罚 · {{ selectedStageMatch.name }}</div>
                    <div class="text-[9px] text-neutral-600 mt-0.5">
                      <template v-if="isArenaMode">擂台对决：选择左方胜 / 右方胜</template>
                      <template v-else>第 {{ roundSeq }} 轮：选择左方胜 / 平局 / 右方胜</template>
                    </div>
                    <div v-if="voteProgress" class="text-[9px] text-amber-400/80 mt-1 font-bold">裁判已判 {{ voteProgress }}</div>
                    <div v-if="pendingPublish" class="text-[9px] text-amber-400 mt-1 font-bold">已判完，等待导播台公布结果</div>
                  </div>
                  <div
                    class="grid gap-2 items-stretch"
                    :class="isArenaMode ? 'grid-cols-2' : 'grid-cols-[1fr_auto_1fr]'"
                  >
                    <button
                      @click="submitKnockout('LEFT')"
                      :disabled="submitting"
                      class="py-5 rounded-xl text-sm font-bold bg-red-600/15 text-red-400 border border-red-600/30 active:bg-red-600/30 transition-colors disabled:opacity-40"
                    >
                      <span class="block text-[9px] text-red-500/70 mb-1">左方胜</span>
                      <span class="truncate block max-w-[110px] mx-auto">{{ leftParticipant?.competitorName || '待定' }}</span>
                    </button>
                    <button
                      v-if="!isArenaMode"
                      @click="submitKnockout('DRAW')"
                      :disabled="submitting"
                      class="px-8 py-5 rounded-xl text-lg font-black bg-neutral-800 text-neutral-200 border-2 border-neutral-600 active:bg-neutral-700 transition-colors disabled:opacity-40"
                    >
                      平
                    </button>
                    <button
                      @click="submitKnockout('RIGHT')"
                      :disabled="submitting"
                      class="py-5 rounded-xl text-sm font-bold bg-blue-600/15 text-blue-400 border border-blue-600/30 active:bg-blue-600/30 transition-colors disabled:opacity-40"
                    >
                      <span class="block text-[9px] text-blue-500/70 mb-1">右方胜</span>
                      <span class="truncate block max-w-[110px] mx-auto">{{ rightParticipant?.competitorName || '待定' }}</span>
                    </button>
                  </div>
                  <p v-if="!isArenaMode" class="text-[9px] text-neutral-600 mt-2 text-center">判平局将自动新增一轮加赛</p>
                  <p v-else class="text-[9px] text-neutral-600 mt-2 text-center">不允许判平；裁判意见持平时重新投票，直至分出胜负</p>
                </div>
              </template>

              <!-- 待开始:等待导播台开启 -->
              <div v-else-if="selectedStageMatch.status === 'PENDING'" class="text-center py-16">
                <div class="w-12 h-12 mx-auto mb-3 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center">
                  <span class="text-neutral-400 font-bold text-lg">⏸</span>
                </div>
                <p class="text-sm text-neutral-200 font-bold mb-1">{{ selectedStageMatch.name }} 尚未开始</p>
                <p class="text-xs text-neutral-500">等待导播台开始该场比赛后方可判罚</p>
              </div>

              <!-- 已结束:结果 -->
              <div v-else class="bg-neutral-900 rounded-xl border border-neutral-800 p-3">
                <div class="flex items-center justify-between mb-2">
                  <span class="text-xs font-bold text-neutral-200">{{ selectedStageMatch.name }}</span>
                  <span class="text-[9px] px-1.5 py-0.5 rounded bg-amber-600/20 text-amber-400">已结束</span>
                </div>
                <div class="flex flex-wrap gap-1.5">
                  <span
                    v-for="r in (selectedOverview?.rounds || [])"
                    :key="r.id"
                    class="px-2 py-0.5 rounded text-[9px] font-bold bg-neutral-800 text-neutral-500"
                  >
                    第{{ r.roundSequence }}轮{{ r.outcome === 'DRAW' ? '·平局' : '·已结算' }}
                  </span>
                  <span
                    v-if="selectedOverview?.winnerName"
                    class="px-2 py-0.5 rounded text-[9px] font-bold bg-amber-500/10 text-amber-400"
                  >
                    胜者 {{ selectedOverview.winnerName }}
                  </span>
                </div>
              </div>
            </template>
            <!-- 擂台赛:无进行中对决时等待导播台开启下一场 -->
            <template v-else-if="isArenaMode">
              <div class="text-center py-16">
                <div class="w-12 h-12 mx-auto mb-3 rounded-full bg-neutral-800 border border-neutral-700 flex items-center justify-center">
                  <span class="text-neutral-400 font-bold text-lg">⏸</span>
                </div>
                <p class="text-sm text-neutral-200 font-bold mb-1">当前对决已判完</p>
                <p class="text-xs text-neutral-500">等待导播台开始下一场对决</p>
              </div>
            </template>
          </div>

          <!-- 非淘汰赛:无进行中场次时展示赛段总览 -->
          <div v-else-if="!matchId" class="space-y-3">
            <div class="text-center mb-2">
              <p class="text-sm font-bold text-white">{{ stageName }}</p>
              <p class="text-[10px] text-neutral-500 mt-0.5">当前赛段进行中，等待导播台开始下一场比赛</p>
            </div>
            <div
              v-for="m in stageOverview"
              :key="m.id"
              class="bg-neutral-900 rounded-xl border border-neutral-800 p-3"
            >
              <div class="flex items-center justify-between mb-2">
                <span class="text-xs font-bold text-neutral-200">{{ m.name }}</span>
                <span
                  class="text-[9px] px-1.5 py-0.5 rounded"
                  :class="{
                    'bg-green-600/20 text-green-400': m.status === 'GAMING',
                    'bg-amber-600/20 text-amber-400': m.status === 'SETTLED',
                    'bg-neutral-700 text-neutral-400': m.status === 'PENDING'
                  }"
                >
                  {{ m.status === 'GAMING' ? '进行中' : m.status === 'SETTLED' ? '已结束' : '待开始' }}
                </span>
              </div>
              <div class="flex flex-wrap gap-1.5">
                <span
                  v-for="r in m.rounds"
                  :key="r.id"
                  class="px-2 py-0.5 rounded text-[9px] font-bold"
                  :class="r.outcome === 'DRAW'
                    ? 'bg-neutral-800 text-neutral-400'
                    : r.status === 'GAMING' ? 'bg-green-500/10 text-green-400' : 'bg-neutral-800 text-neutral-500'"
                >
                  第{{ r.roundSequence }}轮{{ r.outcome === 'DRAW' ? '·平局' : r.status === 'GAMING' ? '·进行中' : '·已结算' }}
                </span>
                <span
                  v-if="m.winnerName"
                  class="px-2 py-0.5 rounded text-[9px] font-bold bg-amber-500/10 text-amber-400"
                >
                  胜者 {{ m.winnerName }}
                </span>
              </div>
            </div>
            <p v-if="stageOverview.length === 0" class="text-center text-xs text-neutral-600 py-8">当前赛段暂无场次</p>
          </div>

          <template v-else>
          <div class="text-center mb-4">
            <p class="text-[10px] text-neutral-500 mb-1">{{ matchName || ('场次 #' + matchId) }}</p>
            <p class="text-[9px] text-neutral-600">
              {{ matchMode }} / Round {{ roundSeq }}
              <span v-if="roundSeq > 1" class="text-amber-400 font-bold">· 平局加赛轮</span>
            </p>
          </div>

          <div v-if="participants.length === 0" class="text-center py-8">
            <p class="text-xs text-neutral-600">当前场次暂无参赛方信息，等待对阵生成/开赛后自动出现</p>
          </div>

          <div v-if="isAudition" class="space-y-4">
            <!-- 顶部横向选手列表 -->
            <div class="no-scrollbar flex gap-2 overflow-x-auto pt-3 pb-2 pl-[calc(50%-40px)] pr-[calc(50%-40px)]">
              <button
                v-for="p in participants"
                :key="p.competitorId"
                :ref="setChipRef(p.competitorId)"
                @click="selectParticipant(p)"
                class="flex-none min-w-[80px] px-3 py-2 rounded-xl border-2 transition-all text-left"
                :class="keypadTarget?.competitorId === p.competitorId
                  ? 'border-amber-400 bg-amber-500 text-neutral-900 scale-105 shadow-lg shadow-amber-500/30'
                  : 'border-neutral-800 bg-neutral-900'"
              >
                <div class="text-[9px]" :class="keypadTarget?.competitorId === p.competitorId ? 'text-neutral-800' : 'text-neutral-500'">
                  #{{ p.displaySlotIndex }}
                </div>
                <div
                  class="text-xs font-bold truncate max-w-[88px]"
                  :class="keypadTarget?.competitorId === p.competitorId ? 'text-neutral-900' : 'text-neutral-200'"
                >
                  {{ p.competitorName }}
                </div>
                <div
                  class="text-sm font-black font-mono mt-0.5"
                  :class="keypadTarget?.competitorId === p.competitorId
                    ? 'text-neutral-900'
                    : myTotal(p.competitorId!) > 0 ? 'text-amber-400' : 'text-neutral-600'"
                >
                  {{
                    myTotal(p.competitorId!) > 0
                      ? myTotal(p.competitorId!)
                      : keypadTarget?.competitorId === p.competitorId ? '当前' : '未评'
                  }}
                </div>
              </button>
            </div>

            <!-- 中部打分器 -->
            <div class="bg-neutral-900 rounded-xl border border-neutral-800 p-4">
              <div class="flex items-center justify-between mb-3">
                <div class="min-w-0">
                  <div class="text-[10px] text-neutral-500">正在为以下选手评分</div>
                  <div class="text-base font-bold text-white truncate">{{ keypadTarget?.competitorName || '选择选手' }}</div>
                </div>
                <div class="text-right">
                  <div class="text-[10px] text-neutral-600">我的评分</div>
                  <div class="text-2xl font-black font-mono text-amber-400 tabular-nums">{{ keypadValue || '0' }}</div>
                </div>
              </div>

              <div class="grid grid-cols-3 gap-2">
                <button
                  v-for="k in keypadKeys"
                  :key="k"
                  @click="pressKey(k)"
                  class="py-3 rounded-xl text-lg font-bold bg-neutral-800 border border-neutral-700 text-neutral-200 active:bg-neutral-700 transition-colors"
                  :class="k === '⌫' ? 'text-neutral-400' : ''"
                >
                  {{ k }}
                </button>
              </div>

              <div class="grid grid-cols-2 gap-2 mt-2">
                <button
                  @click="clearKeypad"
                  class="py-3 rounded-xl text-xs font-bold bg-neutral-800 text-neutral-400 border border-neutral-700 active:bg-neutral-700 transition-colors"
                >
                  清空
                </button>
                <button
                  @click="confirmKeypad"
                  :disabled="keypadSubmitting || !keypadTarget"
                  class="py-3 rounded-xl text-xs font-bold bg-amber-600 hover:bg-amber-500 text-neutral-900 transition-colors disabled:opacity-40"
                >
                  {{ keypadSubmitting ? '提交中...' : '提交并下一个' }}
                </button>
              </div>
              <p class="text-[9px] text-neutral-600 mt-2 text-center">提交后自动跳转下一位，已评分选手可点顶部重新修改</p>
            </div>
          </div>

          <div v-else-if="isKnockoutJudging" class="space-y-4">
            <!-- 轮次历史:平局加赛产生的历史轮判罚 -->
            <div class="flex gap-2 overflow-x-auto no-scrollbar pt-1 pb-2">
              <div
                v-for="r in rounds"
                :key="r.id"
                class="flex-none px-3 py-1.5 rounded-lg border text-[10px] font-bold"
                :class="r.id === currentRoundId
                  ? 'border-amber-500/50 bg-amber-500/10 text-amber-400'
                  : r.outcome === 'DRAW' ? 'border-neutral-700 bg-neutral-800/60 text-neutral-400'
                  : 'border-neutral-800 bg-neutral-900 text-neutral-500'"
              >
                第{{ r.roundSequence }}轮 · {{ r.id === currentRoundId ? '当前' : r.outcome === 'DRAW' ? '平局' : '已结算' }}
              </div>
            </div>

            <!-- 三按钮判罚:左(红) / 平 / 右(蓝) -->
            <div class="bg-neutral-900 rounded-xl border border-neutral-800 p-4">
              <div class="text-center mb-3">
                <div class="text-[10px] text-neutral-500">本场判罚</div>
                <div class="text-[9px] text-neutral-600 mt-0.5">第 {{ roundSeq }} 轮：选择左方胜 / 平局 / 右方胜</div>
                <div v-if="voteProgress" class="text-[9px] text-amber-400/80 mt-1 font-bold">裁判已判 {{ voteProgress }}</div>
              </div>
              <div class="grid grid-cols-[1fr_auto_1fr] gap-2 items-stretch">
                <button
                  @click="submitKnockout('LEFT')"
                  :disabled="submitting"
                  class="py-5 rounded-xl text-sm font-bold bg-red-600/15 text-red-400 border border-red-600/30 active:bg-red-600/30 transition-colors disabled:opacity-40"
                >
                  <span class="block text-[9px] text-red-500/70 mb-1">左方胜</span>
                  <span class="truncate block max-w-[110px] mx-auto">{{ leftParticipant?.competitorName || '待定' }}</span>
                </button>
                <button
                  @click="submitKnockout('DRAW')"
                  :disabled="submitting"
                  class="px-8 py-5 rounded-xl text-lg font-black bg-neutral-800 text-neutral-200 border-2 border-neutral-600 active:bg-neutral-700 transition-colors disabled:opacity-40"
                >
                  平
                </button>
                <button
                  @click="submitKnockout('RIGHT')"
                  :disabled="submitting"
                  class="py-5 rounded-xl text-sm font-bold bg-blue-600/15 text-blue-400 border border-blue-600/30 active:bg-blue-600/30 transition-colors disabled:opacity-40"
                >
                  <span class="block text-[9px] text-blue-500/70 mb-1">右方胜</span>
                  <span class="truncate block max-w-[110px] mx-auto">{{ rightParticipant?.competitorName || '待定' }}</span>
                </button>
              </div>
              <p class="text-[9px] text-neutral-600 mt-2 text-center">判平局将自动新增一轮加赛</p>
            </div>
          </div>

          <div v-else class="space-y-4">
            <div
              v-for="p in participants"
              :key="p.competitorId"
              class="bg-neutral-900 rounded-xl border border-neutral-800 overflow-hidden"
            >
              <div class="px-4 py-3 flex items-center justify-between border-b border-neutral-800">
                <h3 class="text-sm font-bold text-white">{{ p.competitorName }}</h3>
                <div class="flex items-center gap-2">
                  <span v-if="p.rankInMatch" class="text-[9px] font-mono text-amber-400">#{{ p.rankInMatch }}</span>
                  <span class="text-xs font-mono text-neutral-500">#{{ p.displaySlotIndex }}</span>
                </div>
              </div>

              <!-- STANDARD: 胜平负 -->
              <div v-if="isStandard" class="p-4">
                <div class="grid grid-cols-3 gap-3">
                  <button
                    v-for="opt in outcomeOptions"
                    :key="opt.value"
                    @click="selectOutcome(p.competitorId!, opt.value)"
                    class="py-4 rounded-xl border-2 text-sm font-bold transition-all"
                    :class="outcomes[p.competitorId!] === opt.value
                      ? opt.className + ' border-current'
                      : 'bg-neutral-800 border-neutral-700 text-neutral-400'"
                  >
                    {{ opt.label }}
                  </button>
                </div>
                <p class="text-[9px] text-neutral-600 mt-3 text-center">选择该选手本场判定结果</p>
              </div>

              <!-- RANKING: 多维度打分 -->
              <div v-else-if="isRanking" class="p-4 space-y-4">
                <div
                  v-for="dim in dimensions"
                  :key="dim.key"
                  class="flex items-center justify-between gap-3"
                >
                  <div class="min-w-[72px]">
                    <div class="text-xs font-bold text-neutral-300">{{ dim.name || dim.key }}</div>
                    <div v-if="dim.maxScore" class="text-[9px] text-neutral-600">满分 {{ dim.maxScore }}</div>
                  </div>
                  <div class="flex items-center gap-2">
                    <button
                      @click="adjustDim(p.competitorId!, dim.key, -1)"
                      class="w-10 h-10 rounded-lg bg-neutral-800 border border-neutral-700 text-neutral-400 active:bg-red-900/30 active:scale-90 transition-transform"
                    >
                      <Minus class="w-4 h-4 mx-auto" />
                    </button>
                    <input
                      type="number"
                      :value="getDimScore(p.competitorId!, dim.key)"
                      @input="onDimInput(p.competitorId!, dim.key, $event)"
                      class="w-20 h-10 bg-black text-center text-xl font-black font-mono text-amber-400 rounded-lg border border-neutral-700 focus:border-amber-500 outline-none tabular-nums"
                    />
                    <button
                      @click="adjustDim(p.competitorId!, dim.key, 1)"
                      class="w-10 h-10 rounded-lg bg-neutral-800 border border-neutral-700 text-neutral-400 active:bg-green-900/30 active:scale-90 transition-transform"
                    >
                      <Plus class="w-4 h-4 mx-auto" />
                    </button>
                  </div>
                </div>
                <p v-if="myTotal(p.competitorId!) > 0" class="text-[9px] text-neutral-500 text-center">
                  我的总分 {{ myTotal(p.competitorId!) }} · 当前累计 {{ p.currentScore || 0 }}
                </p>
              </div>

              <!-- VOTING(非海选): 单分加减 -->
              <div v-else class="p-4">
                <div class="flex items-center justify-center gap-3">
                  <button
                    @click="adjustDim(p.competitorId!, 'MAIN', -1)"
                    class="w-12 h-12 rounded-xl bg-neutral-800 border border-neutral-700 text-neutral-400 flex items-center justify-center active:scale-90 transition-transform active:bg-red-900/30"
                  >
                    <Minus class="w-5 h-5" />
                  </button>

                  <div class="text-center min-w-[80px]">
                    <div class="text-4xl font-black font-mono text-amber-400 tabular-nums leading-none">
                      {{ getDimScore(p.competitorId!, 'MAIN') }}
                    </div>
                    <div class="text-[9px] text-neutral-600 mt-1">我的打分</div>
                    <div v-if="p.currentScore > 0" class="text-[9px] text-neutral-500 mt-0.5">
                      当前累计: {{ p.currentScore }}
                    </div>
                  </div>

                  <button
                    @click="adjustDim(p.competitorId!, 'MAIN', 1)"
                    class="w-12 h-12 rounded-xl bg-neutral-800 border border-neutral-700 text-neutral-400 flex items-center justify-center active:scale-90 transition-transform active:bg-green-900/30"
                  >
                    <Plus class="w-5 h-5" />
                  </button>
                </div>

                <div class="flex justify-center gap-3 mt-3">
                  <button
                    v-for="quick in [1, 5, 10]"
                    :key="quick"
                    @click="adjustDim(p.competitorId!, 'MAIN', quick)"
                    class="px-3 py-1 text-[10px] font-bold rounded-lg transition-colors bg-neutral-800 text-neutral-400 border border-neutral-700 active:bg-amber-500/20 active:text-amber-400 active:border-amber-500/30"
                  >
                    +{{ quick }}
                  </button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="!isAudition && participants.length > 0" class="mt-6 px-1">
            <button
              @click="handleSubmit"
              :disabled="submitting || !hasChanges"
              class="w-full py-3.5 bg-amber-600 hover:bg-amber-500 text-neutral-900 font-bold rounded-xl transition-colors disabled:opacity-40 disabled:cursor-not-allowed text-sm"
            >
              {{ submitting ? '提交中...' : '提交判罚结果' }}
            </button>
            <p v-if="submitted" class="text-center text-xs text-green-400 mt-2">判罚已提交</p>
          </div>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import { Plus, Minus } from 'lucide-vue-next';
import { setRefereeAuthKey, getRefereeMyMatch, submitRefereeScore } from '@/api/game/referee/scoring';
import { subscribeChannel } from '@/utils/sseChannel';

const route = useRoute();

const loading = ref(true);
const error = ref('');
const tournamentId = ref<number | string | null>(null);
const submitting = ref(false);
const submitted = ref(false);

const refereeName = ref('');
const stageId = ref<number | null>(null);
const stageName = ref('');
const stageMode = ref('');
const scoreType = ref('');
const matchId = ref<number | null>(null);
const matchName = ref('');
const matchMode = ref('');
const roundSeq = ref(0);

interface Dimension {
  key: string;
  name?: string;
  weight?: number;
  maxScore?: number;
}

interface MatchInfo {
  id: number;
  name: string;
  status: string;
  matchMode: string;
}

interface StageInfo {
  id: number;
  name: string;
  stageMode: string;
  status: string;
}

interface RoundInfo {
  id: number;
  roundSequence: number;
  status: string;
  outcome?: string;
}

interface Participant {
  competitorId: number | null;
  competitorName: string;
  displaySlotIndex: number;
  currentScore: number;
  myScore?: number;
  rankInMatch?: number;
}

interface MyScore {
  competitorId: number;
  dimension: string;
  score: number;
}

const dimensions = ref<Dimension[]>([]);
const stages = ref<StageInfo[]>([]);
const matches = ref<MatchInfo[]>([]);
const stageMatches = ref<MatchInfo[]>([]);
const stageOverview = ref<any[]>([]);
const voteProgress = ref('');
const publishMode = ref('AUTO');
const pendingPublish = ref(false);
const rounds = ref<RoundInfo[]>([]);
const currentRoundId = ref<number | null>(null);
const participants = ref<Participant[]>([]);

// 本裁判已提交的明细(仅用于回显自己的分,绝不用其他裁判的累计分当自己的)
const myScores = ref<MyScore[]>([]);
// 编辑态:competitorId -> dimension -> score
const edits = ref<Record<string, Record<string, number>>>({});
// 编辑态:competitorId -> WIN/LOSS/DRAW
const outcomes = ref<Record<string, string>>({});
const touched = ref<Set<string>>(new Set());

const isStandard = computed(() => matchMode.value === 'STANDARD' || scoreType.value === 'WIN_LOSS_DRAW');
const isRanking = computed(() => scoreType.value === 'MULTI_DIM' || dimensions.value.length > 0);
const isAudition = computed(() => stageMode.value === 'AUDITION');
const isArenaMode = computed(() => stageMode.value === 'ARENA');
/** 淘汰赛判罚视图:STANDARD 且恰好两名参赛方(左/右) */
const isKnockoutJudging = computed(() => {
  return isStandard.value && !isAudition.value && participants.value.length === 2;
});
const isKnockoutMode = computed(() => stageMode.value === 'KNOCKOUT' && isStandard.value);
const sortedParticipants = computed(() =>
  participants.value.slice().sort((a, b) => (a.displaySlotIndex || 0) - (b.displaySlotIndex || 0))
);
const leftParticipant = computed(() => sortedParticipants.value[0] || null);
const rightParticipant = computed(() => sortedParticipants.value[1] || null);

// 顶部 match 列表选中态:进行中场次自动选中;无进行中时自动跳到下一场(待开始,不可判罚)
const selectedMatchId = ref<number | string | null>(null);
const selectedStageMatch = computed(() => stageMatches.value.find((m) => m.id === selectedMatchId.value) || null);
const selectedOverview = computed(() => stageOverview.value.find((m) => m.id === selectedMatchId.value) || null);

const modeLabel = computed(() => {
  if (isArenaMode.value) return '擂台判罚';
  if (isStandard.value) return '胜平负判定';
  if (isRanking.value) return '多维度打分';
  return '总分打分';
});

const outcomeOptions = [
  { value: 'WIN', label: '胜', className: 'bg-green-600/15 text-green-400' },
  { value: 'DRAW', label: '平', className: 'bg-neutral-600/20 text-neutral-300' },
  { value: 'LOSS', label: '负', className: 'bg-red-600/15 text-red-400' }
];

const hasChanges = computed(() => touched.value.size > 0);

// --- 海选软键盘评分器 ---
const keypadKeys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '⌫'];
const keypadTarget = ref<Participant | null>(null);
const keypadValue = ref('');
const keypadSubmitting = ref(false);

/** 选手卡片 DOM 引用(用于选中时自动居中滚动) */
const chipEls = new Map<number, HTMLElement>();
const setChipRef = (cid: number | null) => (el: unknown) => {
  if (cid == null) return;
  if (el) {
    chipEls.set(cid, el as HTMLElement);
  } else {
    chipEls.delete(cid);
  }
};

const selectParticipant = (p: Participant) => {
  keypadTarget.value = p;
  const cur = p.competitorId != null ? myTotal(p.competitorId) : 0;
  keypadValue.value = cur > 0 ? String(cur) : '';
  // 自动横向滚动,让当前选手卡片居中
  if (p.competitorId != null) {
    nextTick(() => {
      chipEls.get(p.competitorId as number)?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
    });
  }
};

/** 自动选中下一位:优先 after 之后第一个未评选手,全部已评时停在当前/第一个 */
const autoSelectNext = (after?: Participant | null) => {
  const list = participants.value;
  if (list.length === 0) {
    keypadTarget.value = null;
    keypadValue.value = '';
    return;
  }
  const startIdx = after ? list.findIndex((p) => p.competitorId === after.competitorId) : -1;
  for (let i = startIdx + 1; i < list.length; i++) {
    const p = list[i];
    if (p.competitorId != null && myTotal(p.competitorId) <= 0) {
      selectParticipant(p);
      return;
    }
  }
  for (let i = 0; i <= startIdx; i++) {
    const p = list[i];
    if (p.competitorId != null && myTotal(p.competitorId) <= 0) {
      selectParticipant(p);
      return;
    }
  }
  // 全部已评:停在当前选手,否则选第一个
  const stay = after && list.some((p) => p.competitorId === after.competitorId) ? after : list[0];
  selectParticipant(stay);
};

const pressKey = (k: string) => {
  if (k === '⌫') {
    keypadValue.value = keypadValue.value.slice(0, -1);
    return;
  }
  if (k === '.') {
    if (keypadValue.value.includes('.')) return;
    keypadValue.value = keypadValue.value === '' ? '0.' : keypadValue.value + '.';
    return;
  }
  // 百分制:最多一位小数、不超过 100
  if (keypadValue.value.includes('.')) {
    const dec = keypadValue.value.split('.')[1] || '';
    if (dec.length >= 1) return;
  }
  const next = keypadValue.value === '0' ? k : keypadValue.value + k;
  if (Number(next) > 100) return;
  keypadValue.value = next;
};

const clearKeypad = () => {
  keypadValue.value = '';
};

const confirmKeypad = async () => {
  const target = keypadTarget.value;
  if (!target || target.competitorId == null) return;
  const score = Number(keypadValue.value);
  if (Number.isNaN(score) || score < 0 || score > 100) {
    alert('请输入 0-100 的有效分数（最多一位小数）');
    return;
  }
  keypadSubmitting.value = true;
  try {
    await submitRefereeScore(matchId.value, {
      scores: [{ competitorId: target.competitorId, dimension: 'MAIN', action: 'SCORE', score }]
    });
    // 提交成功后刷新,回显最新分数
    const prev = keypadTarget.value;
    // 静默刷新,刷新后由 loadData 统一选中下一位未评选手,避免二次滚动闪烁
    await loadData(stageId.value ?? undefined, matchId.value ?? undefined, prev, true);
  } catch (e: any) {
    console.error('提交打分失败:', e);
    alert(e?.response?.data?.msg || e?.message || '提交失败');
  } finally {
    keypadSubmitting.value = false;
  }
};

/** 淘汰赛三按钮判罚:左胜 / 平 / 右胜,点击立即提交 */
const submitKnockout = async (side: 'LEFT' | 'DRAW' | 'RIGHT') => {
  if (submitting.value) return;
  const left = leftParticipant.value;
  const right = rightParticipant.value;
  if (!left || !right || left.competitorId == null || right.competitorId == null) return;
  const outcomes: Record<string, string> = {};
  if (side === 'LEFT') {
    outcomes[String(left.competitorId)] = 'WIN';
    outcomes[String(right.competitorId)] = 'LOSS';
  } else if (side === 'RIGHT') {
    outcomes[String(left.competitorId)] = 'LOSS';
    outcomes[String(right.competitorId)] = 'WIN';
  } else {
    outcomes[String(left.competitorId)] = 'DRAW';
    outcomes[String(right.competitorId)] = 'DRAW';
  }
  submitting.value = true;
  try {
    await submitRefereeScore(matchId.value, { outcomes });
    // 静默刷新:平局自动进入加赛轮;分出胜负后随下一场/赛段切换
    await loadData(stageId.value ?? undefined, matchId.value ?? undefined, undefined, true);
  } catch (e: any) {
    console.error('提交判罚失败:', e);
    alert(e?.response?.data?.msg || e?.message || '提交失败');
  } finally {
    submitting.value = false;
  }
};

const getDimScore = (competitorId: number, dim: string): number => {
  return edits.value[competitorId]?.[dim] ?? 0;
};

const setDimScore = (competitorId: number, dim: string, raw: string) => {
  const v = Math.max(0, Number(raw) || 0);
  if (!edits.value[competitorId]) edits.value[competitorId] = {};
  edits.value[competitorId][dim] = v;
  touched.value.add(`${competitorId}:${dim}`);
  submitted.value = false;
};

const onDimInput = (competitorId: number, dim: string, event: Event) => {
  setDimScore(competitorId, dim, (event.target as HTMLInputElement).value);
};

const adjustDim = (competitorId: number, dim: string, delta: number) => {
  const current = getDimScore(competitorId, dim);
  const next = Math.max(0, current + delta);
  if (next === current) return;
  if (!edits.value[competitorId]) edits.value[competitorId] = {};
  edits.value[competitorId][dim] = next;
  touched.value.add(`${competitorId}:${dim}`);
  submitted.value = false;
};

const selectOutcome = (competitorId: number, outcome: string) => {
  outcomes.value[competitorId] = outcome;
  touched.value.add(`${competitorId}:outcome`);
  submitted.value = false;
};

const myTotal = (competitorId: number): number => {
  return myScores.value
    .filter((s) => s.competitorId === competitorId)
    .reduce((sum, s) => sum + (s.score || 0), 0);
};

const buildPayload = () => {
  if (isStandard.value) {
    const outcomesMap: Record<string, string> = {};
    participants.value.forEach((p) => {
      if (p.competitorId !== null && touched.value.has(`${p.competitorId}:outcome`)) {
        outcomesMap[String(p.competitorId)] = outcomes.value[p.competitorId];
      }
    });
    return { outcomes: outcomesMap };
  }
  const scores: any[] = [];
  participants.value.forEach((p) => {
    if (p.competitorId === null) return;
    const dims = edits.value[p.competitorId] || {};
    Object.entries(dims).forEach(([dim, score]) => {
      if (touched.value.has(`${p.competitorId}:${dim}`) && score > 0) {
        scores.push({ competitorId: p.competitorId, dimension: dim, score, action: 'SCORE' });
      }
    });
  });
  return { scores };
};

const handleSubmit = async () => {
  if (!matchId.value) return;
  const payload = buildPayload();
  if ((payload.scores && payload.scores.length === 0) && (!payload.outcomes || Object.keys(payload.outcomes).length === 0)) {
    return;
  }
  submitting.value = true;
  try {
    await submitRefereeScore(matchId.value, payload);
    submitted.value = true;
    touched.value.clear();
    // 提交成功后刷新一次,回显自己刚提交的分
    await loadData(stageId.value ?? undefined, matchId.value ?? undefined);
  } catch (e: any) {
    console.error('提交打分失败:', e);
    alert(e?.response?.data?.msg || e?.message || '提交失败');
  } finally {
    submitting.value = false;
  }
};

const switchMatch = async (id: number) => {
  selectedMatchId.value = id;
  await loadData(stageId.value ?? undefined, id);
};

const switchStage = async (id: number) => {
  await loadData(id, undefined);
};

/** 完整应用一份 my-match 数据(切换赛段/场次/模式时调用,会重置编辑态) */
const applyData = (data: any) => {
  error.value = '';
  tournamentId.value = data?.tournamentId ?? null;
  refereeName.value = data?.refereeName || '';
  stageId.value = data?.stage?.id;
  stageName.value = data?.stage?.name || '';
  stageMode.value = data?.stage?.stageMode || '';
  scoreType.value = data?.scoreType || '';
  matchId.value = data?.match?.id;
  matchName.value = data?.match?.name || '';
  matchMode.value = data?.match?.matchMode || 'STANDARD';
  roundSeq.value = data?.currentRound?.roundSequence || 1;
  currentRoundId.value = data?.currentRound?.id ?? null;

  stages.value = (data?.stages || []).map((s: any) => ({
    id: s.id,
    name: s.name,
    stageMode: s.stageMode,
    status: s.status
  }));
  matches.value = (data?.matches || []).map((m: any) => ({
    id: m.id,
    name: m.name,
    status: m.status,
    matchMode: m.matchMode
  }));
  stageMatches.value = (data?.stageMatches || data?.matches || []).map((m: any) => ({
    id: m.id,
    name: m.name,
    status: m.status,
    matchMode: m.matchMode
  }));
  stageOverview.value = (data?.stageOverview || []).map((m: any) => ({
    id: m.id,
    name: m.name,
    status: m.status,
    matchMode: m.matchMode,
    rounds: m.rounds || [],
    participants: m.participants || [],
    winnerName: m.winnerName || ''
  }));
  voteProgress.value = data?.voteProgress || '';
  publishMode.value = data?.publishMode || 'AUTO';
  pendingPublish.value = !!data?.pendingPublish;
  rounds.value = (data?.rounds || []).map((r: any) => ({
    id: r.id,
    roundSequence: r.roundSequence,
    status: r.status,
    outcome: r.outcome
  }));
  dimensions.value = (data?.dimensions || []).map((d: any) => ({
    key: d.key,
    name: d.name,
    weight: d.weight,
    maxScore: d.maxScore
  }));
  myScores.value = (data?.myScores || []).map((s: any) => ({
    competitorId: s.competitorId,
    dimension: s.dimension || 'MAIN',
    score: Number(s.score) || 0
  }));

  participants.value = ((data?.participants || []) as any[])
    .map((p) => ({
      competitorId: p.competitorId,
      competitorName: p.competitorName,
      displaySlotIndex: p.displaySlotIndex,
      currentScore: Number(p.currentScore) || 0,
      myScore: p.myScore,
      rankInMatch: p.rankInMatch
    }))
    .sort((a, b) => (a.displaySlotIndex || 0) - (b.displaySlotIndex || 0));

  // 只用自己的分回显,不拿累计分顶替
  edits.value = {};
  participants.value.forEach((p) => {
    if (p.competitorId === null) return;
    const dims: Record<string, number> = {};
    myScores.value.forEach((s) => {
      if (s.competitorId === p.competitorId) {
        dims[s.dimension] = s.score;
      }
    });
    if (Object.keys(dims).length > 0) {
      edits.value[p.competitorId] = dims;
    }
  });
  outcomes.value = {};
  touched.value.clear();
  submitted.value = false;
  // 淘汰赛/擂台赛:自动跟随进行中场次;淘汰赛无进行中时自动选中下一场(待开始,等待导播台开启)
  if (isKnockoutMode.value || isArenaMode.value) {
    if (matchId.value != null) {
      selectedMatchId.value = matchId.value;
    } else if (isArenaMode.value) {
      // 擂台赛:无进行中对决时进入等待态,不自动选中已结束场次
      selectedMatchId.value = null;
    } else {
      const list = stageMatches.value;
      if (list.length > 0) {
        const cur = selectedMatchId.value
          ? list.find((m) => m.id === selectedMatchId.value)
          : null;
        // 当前选中的场次仍待开始(未结束):保持不变,避免 SSE/轮询刷新导致轮流跳动
        if (cur && cur.status !== 'SETTLED') {
          // keep current selection
        } else {
          // 未选中,或当前选中场次已结束:自动跳到下一场(待开始,等待导播台开启)
          const prevIdx = cur ? list.findIndex((m) => m.id === cur.id) : -1;
          let next: any = null;
          for (let i = prevIdx + 1; i < list.length; i++) {
            if (list[i].status !== 'SETTLED') {
              next = list[i];
              break;
            }
          }
          if (!next) {
            for (let i = 0; i < list.length; i++) {
              if (list[i].status !== 'SETTLED') {
                next = list[i];
                break;
              }
            }
          }
          selectedMatchId.value = (next || list[list.length - 1])?.id ?? null;
        }
      } else {
        selectedMatchId.value = null;
      }
    }
  } else {
    selectedMatchId.value = matchId.value ?? null;
  }
};

/** 定时跟随:上下文不变时只更新累计分数/排名,不打断编辑 */
const mergeLive = (data: any) => {
  stages.value = (data?.stages || []).map((s: any) => ({
    id: s.id,
    name: s.name,
    stageMode: s.stageMode,
    status: s.status
  }));
  matches.value = (data?.matches || []).map((m: any) => ({
    id: m.id,
    name: m.name,
    status: m.status,
    matchMode: m.matchMode
  }));
  stageMatches.value = (data?.stageMatches || data?.matches || []).map((m: any) => ({
    id: m.id,
    name: m.name,
    status: m.status,
    matchMode: m.matchMode
  }));
  stageOverview.value = (data?.stageOverview || []).map((m: any) => ({
    id: m.id,
    name: m.name,
    status: m.status,
    matchMode: m.matchMode,
    rounds: m.rounds || [],
    participants: m.participants || [],
    winnerName: m.winnerName || ''
  }));
  voteProgress.value = data?.voteProgress || '';
  publishMode.value = data?.publishMode || 'AUTO';
  pendingPublish.value = !!data?.pendingPublish;
  const liveParts = new Map<number, any>((data?.participants || []).map((p: any) => [p.competitorId, p]));
  participants.value.forEach((p) => {
    const lp = p.competitorId !== null ? liveParts.get(p.competitorId) : undefined;
    if (lp) {
      p.currentScore = Number(lp.currentScore) || 0;
      p.rankInMatch = lp.rankInMatch;
    }
  });
};

/** 轮询跟随:当前 赛段/场次/轮次 变化时自动切换(可能改变判罚模式),否则只刷新累计 */
const refresh = async () => {
  if (submitting.value) return;
  try {
    const resp = await getRefereeMyMatch(stageId.value ?? undefined, matchId.value ?? undefined);
    // 原生 axios 无响应拦截器,resp.data 为 R 信封,需取 data.data
    const data = resp.data?.data ?? resp.data;
    const nextStageId = data?.stage?.id;
    const nextMatchId = data?.match?.id;
    const nextRoundId = data?.currentRound?.id ?? null;
    const contextChanged =
      !stageId.value || !matchId.value ||
      nextStageId !== stageId.value ||
      nextMatchId !== matchId.value ||
      nextRoundId !== currentRoundId.value;
    // 上下文变化,或本地为空但服务端已有参赛方时,整页应用(避免残留空态)
    const serverHasParticipants = (data?.participants || []).length > 0;
    if (contextChanged || (participants.value.length === 0 && serverHasParticipants)) {
      applyData(data);
    } else {
      mergeLive(data);
    }
  } catch (e) {
    // 轮询失败保持现状(如当前赛段已全部结束,等管理员开启下一赛段)
  }
};

const loadData = async (
  stageIdParam?: number,
  matchIdParam?: number,
  selectAfter?: Participant | null,
  silent = false
) => {
  // 静默刷新(如提交打分后)不切换全页 loading,避免内容卸载重建导致滚动位置丢失/闪烁
  if (!silent) {
    loading.value = true;
  }
  error.value = '';
  try {
    const resp = await getRefereeMyMatch(stageIdParam, matchIdParam);
    // 原生 axios 无响应拦截器,resp.data 为 R 信封,需取 data.data
    applyData(resp.data?.data ?? resp.data);
    // 海选:刷新后按需选中选手(默认第一个未评;提交后跳下一位),只滚动一次
    if (isAudition.value) {
      autoSelectNext(selectAfter ?? null);
    }
  } catch (e: any) {
    console.error('加载失败:', e);
    error.value = e?.response?.data?.msg || '认证失败，请确认二维码是否有效';
  } finally {
    if (!silent) {
      loading.value = false;
    }
  }
};

let unsubSse: (() => void) | null = null;

/** 裁判 SSE 长连接:复用赛事事件通道,按 authKey 身份订阅,只收命中自己的定向事件 */
const connectRefereeSse = (authKey: string, tid: number | string) => {
  unsubSse?.();
  const baseUrl = (import.meta.env.VITE_APP_BASE_API as string) || '';
  const clientId = (import.meta.env.VITE_APP_CLIENT_ID as string) || '';
  unsubSse = subscribeChannel({
    key: `referee:${tid}:${authKey}`,
    buildUrl: () =>
      `${baseUrl}/tournament/event/sse?tournamentId=${encodeURIComponent(String(tid))}&authKey=${encodeURIComponent(authKey)}&clientid=${clientId}`,
    onMessage: (data: any) => {
      // 事件与当前赛段相关(或重连补偿 null)才刷新,避免无关事件全量拉取
      if (!data || data.stageId == null || String(data.stageId) === String(stageId.value)) {
        refresh();
      }
    },
    // 断线重连成功后全量刷新,补回错过的事件
    onRefresh: () => refresh()
  });
};

onMounted(async () => {
  const authKey = route.query.authKey;
  if (!authKey || Array.isArray(authKey)) {
    error.value = '缺少认证密钥，请扫描二维码进入';
    loading.value = false;
    return;
  }
  setRefereeAuthKey(authKey as string);
  await loadData();
  // SSE 实时推送 + 断线重连补偿,无需轮询
  if (tournamentId.value) {
    connectRefereeSse(authKey as string, tournamentId.value);
  } else {
    console.warn('未获取到赛事ID,跳过SSE连接,仅依赖手动刷新');
  }
});

onUnmounted(() => {
  unsubSse?.();
  unsubSse = null;
});
</script>

<style scoped>
/* 隐藏横向选手列表滚动条 */
.no-scrollbar::-webkit-scrollbar {
  display: none;
}
.no-scrollbar {
  scrollbar-width: none;
  -ms-overflow-style: none;
}
</style>
