<template>
  <div class="transition-config">
    <div class="flex items-center justify-between my-6">
      <div class="flex items-center gap-4 text-neutral-400">
        <span class="font-bold text-neutral-200">{{ sourceStageName }}</span>
        <ArrowRight class="w-4 h-4" />
        <div class="px-3 py-1 bg-amber-500/10 text-amber-500 border border-amber-500/20 rounded-lg text-sm font-bold flex items-center gap-2">
          <SlidersHorizontal class="w-4 h-4" /> 中间态调整
        </div>
        <ArrowRight class="w-4 h-4" />
        <span class="font-bold text-neutral-200">{{ targetStageName }}</span>
      </div>
    </div>

    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-8 space-y-8">
      <!-- 目标赛段已开始:整页锁定 -->
      <div v-if="targetLocked" class="flex items-center gap-3 px-4 py-3 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400 text-sm">
        <Lock class="w-4 h-4 flex-none" />
        <span>{{ targetStageName }} 已开始,中间态调整已锁定,如需调整请先重置该赛段。</span>
      </div>

      <div class="space-y-4">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">晋级处理逻辑</h4>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div
            @click="!targetLocked && (config.mode = 'AUTO')"
            class="cursor-pointer border rounded-xl p-4 flex gap-4 transition-all"
            :class="[
              targetLocked ? 'opacity-50 cursor-not-allowed' : '',
              config.mode === 'AUTO' ? 'bg-amber-500/10 border-amber-500' : 'bg-black border-neutral-700 hover:border-neutral-500'
            ]"
          >
            <div class="mt-1"><Zap class="w-5 h-5" :class="config.mode === 'AUTO' ? 'text-amber-500' : 'text-neutral-500'" /></div>
            <div>
              <div class="font-bold text-sm text-neutral-200">自动晋级 (Auto Seeding)</div>
              <div class="text-xs text-neutral-500 mt-1">系统根据排名自动填充下一阶段名额，无需人工干预。</div>
            </div>
          </div>

          <div
            @click="!targetLocked && (config.mode = 'MANUAL')"
            class="cursor-pointer border rounded-xl p-4 flex gap-4 transition-all"
            :class="[
              targetLocked ? 'opacity-50 cursor-not-allowed' : '',
              config.mode === 'MANUAL' ? 'bg-amber-500/10 border-amber-500' : 'bg-black border-neutral-700 hover:border-neutral-500'
            ]"
          >
            <div class="mt-1"><Hand class="w-5 h-5" :class="config.mode === 'MANUAL' ? 'text-amber-500' : 'text-neutral-500'" /></div>
            <div>
              <div class="font-bold text-sm text-neutral-200">人工干预 (Manual Adjust)</div>
              <div class="text-xs text-neutral-500 mt-1">比赛暂停，管理员需手动确认对阵表、调整种子顺位后方可开始。</div>
            </div>
          </div>
        </div>
      </div>

      <div class="h-px bg-neutral-800 w-full"></div>

      <div v-if="config.mode === 'MANUAL'" class="space-y-4 animate-fade-in">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">手动操作项</h4>

        <label class="flex items-center justify-between p-3 rounded-lg bg-black border border-neutral-800 hover:border-neutral-600 cursor-pointer">
          <div>
            <div class="text-sm text-neutral-200">重新抽签 (Reshuffle Seeds)</div>
            <div class="text-xs text-neutral-500">打乱上一阶段排名，重新生成对阵</div>
          </div>
          <input type="checkbox" v-model="config.reshuffle" :disabled="targetLocked" class="accent-amber-500 w-4 h-4" />
        </label>

        <label class="flex items-center justify-between p-3 rounded-lg bg-black border border-neutral-800 hover:border-neutral-600 cursor-pointer">
          <div>
            <div class="text-sm text-neutral-200">允许替补更换</div>
            <div class="text-xs text-neutral-500">允许战队在此间歇期提交新的首发名单</div>
          </div>
          <input type="checkbox" v-model="config.allowSubstitutions" :disabled="targetLocked" class="accent-amber-500 w-4 h-4" />
        </label>
      </div>

      <!-- 参赛者预排调整:上一赛段 → 下一赛段的晋级者预排 -->
      <div v-if="prebracketVisible" class="space-y-4 border-t border-neutral-800 pt-6">
        <div class="flex items-center justify-between">
          <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">参赛者预排调整 · {{ sourceStageName }} → {{ targetStageName }}</h4>
          <span class="text-[10px] px-2 py-0.5 rounded bg-neutral-800 text-neutral-400 flex-none">{{ targetModeLabel }}</span>
        </div>
        <p class="text-xs text-neutral-500">
          {{ canPrebracketAdjust ? '人工干预模式:可移动晋级者位置,保存后按新顺序写入下一赛段' : '自动晋级模式:以下为进入下一赛段的预排(只读)' }}
        </p>

        <div v-if="preLoading" class="text-sm text-neutral-500 py-4 text-center">加载预排中...</div>
        <div v-else-if="preSeeds.length === 0" class="text-sm text-neutral-600 py-4 text-center">
          {{ preStatus === 'WAIT_PREV' ? '等待上一赛段结算,胜者产生后自动预排' : '暂无预排参赛者' }}
        </div>
        <template v-else-if="canPrebracketAdjust">
          <!-- 两列对战树视图:同列上下移动、同场左右互换,保存后按新种子生成对阵 -->
          <div v-if="prePairs.length > 0" class="space-y-3">
            <div class="grid grid-cols-2 gap-4">
              <div class="space-y-2">
                <div class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider text-center pb-1 border-b border-neutral-800">左半区</div>
                <div v-for="p in leftPairs" :key="p.position" class="rounded-lg bg-black border border-neutral-800 p-2">
                  <div class="text-[10px] text-neutral-600 font-mono mb-1 px-1">#{{ p.position }}</div>
                  <div class="space-y-1.5">
                    <div v-if="p.left" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.left.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.left.name }}</span>
                      <span v-if="p.left.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ p.left.sourceMatchName }}</span>
                      <div class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveBracketSeed(p, 'left', -1)"
                          :disabled="targetLocked || !canMoveUp(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveBracketSeed(p, 'left', 1)"
                          :disabled="targetLocked || !canMoveDown(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.right"
                          @click="swapWithinPair(p)"
                          :disabled="targetLocked"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      {{ pairStatusText(p.leftStatus) || '空位' }}
                    </div>
                    <div class="text-center text-[10px] text-neutral-700">VS</div>
                    <div v-if="p.right" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.right.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.right.name }}</span>
                      <span v-if="p.right.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ p.right.sourceMatchName }}</span>
                      <div class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveBracketSeed(p, 'right', -1)"
                          :disabled="targetLocked || !canMoveUp(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveBracketSeed(p, 'right', 1)"
                          :disabled="targetLocked || !canMoveDown(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.left"
                          @click="swapWithinPair(p)"
                          :disabled="targetLocked"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      {{ pairStatusText(p.rightStatus) || '空位' }}
                    </div>
                  </div>
                </div>
              </div>
              <div class="space-y-2">
                <div class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider text-center pb-1 border-b border-neutral-800">右半区</div>
                <div v-for="p in rightPairs" :key="p.position" class="rounded-lg bg-black border border-neutral-800 p-2">
                  <div class="text-[10px] text-neutral-600 font-mono mb-1 px-1">#{{ p.position }}</div>
                  <div class="space-y-1.5">
                    <div v-if="p.left" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.left.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.left.name }}</span>
                      <span v-if="p.left.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ p.left.sourceMatchName }}</span>
                      <div class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveBracketSeed(p, 'left', -1)"
                          :disabled="targetLocked || !canMoveUp(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveBracketSeed(p, 'left', 1)"
                          :disabled="targetLocked || !canMoveDown(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.right"
                          @click="swapWithinPair(p)"
                          :disabled="targetLocked"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      {{ pairStatusText(p.leftStatus) || '空位' }}
                    </div>
                    <div class="text-center text-[10px] text-neutral-700">VS</div>
                    <div v-if="p.right" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.right.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.right.name }}</span>
                      <span v-if="p.right.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ p.right.sourceMatchName }}</span>
                      <div class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveBracketSeed(p, 'right', -1)"
                          :disabled="targetLocked || !canMoveUp(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveBracketSeed(p, 'right', 1)"
                          :disabled="targetLocked || !canMoveDown(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.left"
                          @click="swapWithinPair(p)"
                          :disabled="targetLocked"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      {{ pairStatusText(p.rightStatus) || '空位' }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <p class="text-[10px] text-neutral-600 pt-1">
              同列上下移动、同场左右互换;空位/待定位置保持不变。调整后保存,开始{{ targetStageName }}时按新种子生成对阵。
            </p>
          </div>
          <!-- 无配对时的兜底:扁平种子列表 -->
          <div v-else class="space-y-1.5">
            <div
              v-for="(seed, i) in preSeeds"
              :key="seed.competitorId"
              class="flex items-center gap-3 px-3 py-2 rounded-lg bg-black border border-neutral-800"
            >
              <span class="w-8 h-8 rounded flex items-center justify-center text-xs font-bold bg-neutral-800 text-neutral-400 flex-none">
                {{ seed.seedRank }}
              </span>
              <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ seed.name }}</span>
              <span v-if="seed.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ seed.sourceMatchName }}</span>
              <div class="flex items-center gap-1 flex-none">
                <button
                  @click="moveSeed(i, -1)"
                  :disabled="targetLocked || i === 0"
                  class="w-7 h-7 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  title="上移"
                >
                  ↑
                </button>
                <button
                  @click="moveSeed(i, 1)"
                  :disabled="targetLocked || i === preSeeds.length - 1"
                  class="w-7 h-7 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  title="下移"
                >
                  ↓
                </button>
              </div>
            </div>
            <p class="text-[10px] text-neutral-600 pt-1">仅可调整已产生的晋级者;轮空/待定位置保持不变。</p>
          </div>
        </template>
        <!-- 自动晋级模式:只读预排 -->
        <template v-else>
          <div v-if="prePairs.length > 0" class="grid grid-cols-1 md:grid-cols-2 gap-2">
            <div v-for="p in prePairs" :key="p.position" class="rounded-lg bg-black border border-neutral-800 p-3">
              <div class="text-[10px] text-neutral-500 mb-1 font-mono">{{ p.zone || '场次' }} #{{ p.position }}</div>
              <div class="space-y-1">
                <div class="flex items-center justify-between text-sm gap-2">
                  <span class="text-neutral-200 truncate">{{ p.left?.name || '待定' }}</span>
                  <span class="text-[10px] text-neutral-500 flex-none">{{ pairStatusText(p.leftStatus) }}</span>
                </div>
                <div class="text-[10px] text-neutral-600 text-center">VS</div>
                <div class="flex items-center justify-between text-sm gap-2">
                  <span class="text-neutral-200 truncate">{{ p.right?.name || '待定' }}</span>
                  <span class="text-[10px] text-neutral-500 flex-none">{{ pairStatusText(p.rightStatus) }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="space-y-1.5">
            <div
              v-for="seed in preSeeds"
              :key="seed.competitorId"
              class="flex items-center gap-3 px-3 py-2 rounded-lg bg-black border border-neutral-800"
            >
              <span class="w-8 h-8 rounded flex items-center justify-center text-xs font-bold bg-neutral-800 text-neutral-400 flex-none">{{
                seed.seedRank
              }}</span>
              <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ seed.name }}</span>
              <span v-if="seed.sourceMatchName" class="text-[10px] text-neutral-600 flex-none">{{ seed.sourceMatchName }}</span>
            </div>
          </div>
        </template>
      </div>

      <!-- 排名赛同分待定晋级调整 -->
      <div v-if="isRankSource && pendingAdvancers.length > 0" class="space-y-4 border-t border-neutral-800 pt-6">
        <div class="flex items-center justify-between">
          <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">同分待定晋级调整</h4>
          <span class="text-xs text-neutral-500">晋级线同分并列,由导播台手动指定晋级者</span>
        </div>

        <div class="space-y-1.5">
          <div
            v-for="c in pendingAdvancers"
            :key="c.id"
            class="flex items-center gap-3 px-3 py-2 rounded-lg bg-black border border-neutral-800"
            :class="selectedAdvanceIds.includes(String(c.id)) ? 'border-amber-500/50' : ''"
          >
            <input
              type="checkbox"
              :checked="selectedAdvanceIds.includes(String(c.id))"
              :disabled="targetLocked"
              class="accent-amber-500 w-4 h-4"
              @change="toggleAdvance(c.id)"
            />
            <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ c.name }}</span>
            <span v-if="c.number" class="text-[10px] text-neutral-600 flex-none">#{{ c.number }}</span>
            <span class="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400 flex-none">待定</span>
          </div>
        </div>
        <p class="text-[10px] text-neutral-600">勾选即晋级;未勾选的待定者将标记淘汰。全部勾选 = 同分者全部晋级(名额可超限)。</p>

        <div class="flex justify-end">
          <button
            @click="saveAdvancement"
            :disabled="targetLocked || advSaving || selectedAdvanceIds.length === 0"
            class="px-4 py-2 rounded-lg bg-amber-500 text-neutral-900 text-xs font-bold hover:bg-amber-400 disabled:opacity-40 transition-colors"
          >
            {{ advSaving ? '保存中...' : '保存晋级调整' }}
          </button>
        </div>
      </div>

      <!-- 下一赛段参赛方:上一赛段晋级者与新增 GUEST 统一落位(与预排逻辑一致) -->
      <div class="space-y-4 border-t border-neutral-800 pt-6">
        <div class="flex items-center justify-between">
          <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">下一赛段参赛方 · {{ targetStageName }}</h4>
          <span class="text-[10px] px-2 py-0.5 rounded bg-neutral-800 text-neutral-400 flex-none">{{ targetModeLabel }}</span>
        </div>

        <!-- 参赛方调整:晋级者与 GUEST 统一按赛制预排落位 -->
        <div class="space-y-3">
          <p v-if="targetMode !== 'AUDITION'" class="text-xs text-neutral-500 leading-relaxed">
            上一赛段晋级者与新增 GUEST 在此统一调整,与上方预排逻辑一致,按{{ targetModeLabel }}落位:
            <template v-if="targetMode === 'GROUP'">蛇形分组进入各小组</template>
            <template v-else-if="targetMode === 'ARENA'">按种子顺序进入擂台轮转队列</template>
            <template v-else-if="targetMode === 'RANK'">按种子顺序均分到各圈</template>
            <template v-else>
              进入淘汰赛对战树<template v-if="directPairingMode === 'SEED'">;首尾交叉模式下 GUEST 自动顶替前几名种子位,原参赛者顺延</template>
            </template>
            ,换位即调整预排顺序;GUEST 可直接添加。
          </p>

          <!-- 添加 GUEST 表单 -->
          <div class="flex items-end gap-2">
            <div class="flex-1">
              <label class="text-xs text-neutral-500 mb-1 block">GUEST 名称</label>
              <input
                v-model="directForm.name"
                type="text"
                maxlength="50"
                :disabled="!canDirectAdd"
                class="w-full bg-black border border-neutral-700 rounded-lg p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50"
                placeholder="GUEST 名称"
              />
            </div>
            <div class="w-24">
              <label class="text-xs text-neutral-500 mb-1 block">类型</label>
              <select
                v-model="directForm.type"
                :disabled="!canDirectAdd"
                class="w-full bg-black border border-neutral-700 rounded-lg p-2 text-sm text-white focus:border-amber-500 focus:outline-none appearance-none disabled:opacity-50"
              >
                <option :value="0">个人</option>
                <option :value="1">队伍</option>
              </select>
            </div>
            <div class="w-28">
              <label class="text-xs text-neutral-500 mb-1 block">选手号</label>
              <input
                v-model="directForm.number"
                type="text"
                maxlength="20"
                :disabled="!canDirectAdd"
                class="w-full bg-black border border-neutral-700 rounded-lg p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50"
                placeholder="留空自动生成"
              />
            </div>
            <button
              @click="addDirectGuest"
              :disabled="!canDirectAdd || directAdding"
              class="h-[38px] px-4 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400 text-xs font-bold hover:bg-amber-500/20 disabled:opacity-40 transition-colors flex items-center gap-1"
            >
              <UserPlus class="w-3.5 h-3.5" /> {{ directAdding ? '添加中...' : '添加 GUEST' }}
            </button>
          </div>

          <div v-if="directLoading" class="text-sm text-neutral-500 py-3 text-center">加载参赛方中...</div>
          <div v-else-if="sortedDirect.length === 0" class="text-sm text-neutral-600 py-3 text-center">
            目标赛段暂无参赛方,添加 GUEST 后自动成为 1 号种子
          </div>
          <template v-else>
            <!-- 淘汰赛目标:两列对战树换位 -->
            <div v-if="targetMode === 'KNOCKOUT'" class="grid grid-cols-2 gap-4">
              <div class="space-y-2">
                <div class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider text-center pb-1 border-b border-neutral-800">左半区</div>
                <div v-for="p in directLeftPairs" :key="'DL' + p.position" class="rounded-lg bg-black border border-neutral-800 p-2">
                  <div class="text-[10px] text-neutral-600 font-mono mb-1 px-1">#{{ p.position }}</div>
                  <div class="space-y-1.5">
                    <div v-if="p.left" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.left.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.left.name }}</span>
                      <span
                        v-if="isGuestComp(p.left)"
                        class="text-[10px] px-1 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex-none"
                        >GUEST</span
                      >
                      <button
                        v-if="isGuestComp(p.left) && canDirectAdd"
                        @click.stop="removeDirectGuest(p.left)"
                        :disabled="removingId === String(p.left.id)"
                        class="w-6 h-6 rounded border border-neutral-700 text-neutral-500 hover:text-red-500 hover:border-red-900/40 disabled:opacity-40 transition-colors flex items-center justify-center flex-none"
                        title="移除 GUEST"
                      >
                        <Trash2 class="w-3 h-3" />
                      </button>
                      <div v-if="canDirectAdd" class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveDirectBracketSeed(p, 'left', -1)"
                          :disabled="!canDirectMoveUp(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveDirectBracketSeed(p, 'left', 1)"
                          :disabled="!canDirectMoveDown(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.right"
                          @click="swapDirectPair(p)"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      空位
                    </div>
                    <div class="text-center text-[10px] text-neutral-700">VS</div>
                    <div v-if="p.right" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.right.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.right.name }}</span>
                      <span
                        v-if="isGuestComp(p.right)"
                        class="text-[10px] px-1 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex-none"
                        >GUEST</span
                      >
                      <button
                        v-if="isGuestComp(p.right) && canDirectAdd"
                        @click.stop="removeDirectGuest(p.right)"
                        :disabled="removingId === String(p.right.id)"
                        class="w-6 h-6 rounded border border-neutral-700 text-neutral-500 hover:text-red-500 hover:border-red-900/40 disabled:opacity-40 transition-colors flex items-center justify-center flex-none"
                        title="移除 GUEST"
                      >
                        <Trash2 class="w-3 h-3" />
                      </button>
                      <div v-if="canDirectAdd" class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveDirectBracketSeed(p, 'right', -1)"
                          :disabled="!canDirectMoveUp(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveDirectBracketSeed(p, 'right', 1)"
                          :disabled="!canDirectMoveDown(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.left"
                          @click="swapDirectPair(p)"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      空位
                    </div>
                  </div>
                </div>
              </div>
              <div class="space-y-2">
                <div class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider text-center pb-1 border-b border-neutral-800">右半区</div>
                <div v-for="p in directRightPairs" :key="'DR' + p.position" class="rounded-lg bg-black border border-neutral-800 p-2">
                  <div class="text-[10px] text-neutral-600 font-mono mb-1 px-1">#{{ p.position }}</div>
                  <div class="space-y-1.5">
                    <div v-if="p.left" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.left.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.left.name }}</span>
                      <span
                        v-if="isGuestComp(p.left)"
                        class="text-[10px] px-1 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex-none"
                        >GUEST</span
                      >
                      <button
                        v-if="isGuestComp(p.left) && canDirectAdd"
                        @click.stop="removeDirectGuest(p.left)"
                        :disabled="removingId === String(p.left.id)"
                        class="w-6 h-6 rounded border border-neutral-700 text-neutral-500 hover:text-red-500 hover:border-red-900/40 disabled:opacity-40 transition-colors flex items-center justify-center flex-none"
                        title="移除 GUEST"
                      >
                        <Trash2 class="w-3 h-3" />
                      </button>
                      <div v-if="canDirectAdd" class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveDirectBracketSeed(p, 'left', -1)"
                          :disabled="!canDirectMoveUp(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveDirectBracketSeed(p, 'left', 1)"
                          :disabled="!canDirectMoveDown(p, 'left')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.right"
                          @click="swapDirectPair(p)"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      空位
                    </div>
                    <div class="text-center text-[10px] text-neutral-700">VS</div>
                    <div v-if="p.right" class="flex items-center gap-2 px-2 py-1.5 rounded bg-neutral-900/60 border border-neutral-800">
                      <span
                        class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none"
                        >{{ p.right.seedRank }}</span
                      >
                      <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p.right.name }}</span>
                      <span
                        v-if="isGuestComp(p.right)"
                        class="text-[10px] px-1 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex-none"
                        >GUEST</span
                      >
                      <button
                        v-if="isGuestComp(p.right) && canDirectAdd"
                        @click.stop="removeDirectGuest(p.right)"
                        :disabled="removingId === String(p.right.id)"
                        class="w-6 h-6 rounded border border-neutral-700 text-neutral-500 hover:text-red-500 hover:border-red-900/40 disabled:opacity-40 transition-colors flex items-center justify-center flex-none"
                        title="移除 GUEST"
                      >
                        <Trash2 class="w-3 h-3" />
                      </button>
                      <div v-if="canDirectAdd" class="flex items-center gap-0.5 flex-none">
                        <button
                          @click="moveDirectBracketSeed(p, 'right', -1)"
                          :disabled="!canDirectMoveUp(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列上移"
                        >
                          ↑
                        </button>
                        <button
                          @click="moveDirectBracketSeed(p, 'right', 1)"
                          :disabled="!canDirectMoveDown(p, 'right')"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="同列下移"
                        >
                          ↓
                        </button>
                        <button
                          v-if="p.left"
                          @click="swapDirectPair(p)"
                          class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                          title="左右互换"
                        >
                          ⇄
                        </button>
                      </div>
                    </div>
                    <div v-else class="px-2 py-1.5 rounded bg-neutral-900/30 border border-dashed border-neutral-800 text-[10px] text-neutral-600">
                      空位
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 小组/擂台/排名目标:扁平列表换位 + 模式专属落位预览 -->
            <div v-else class="space-y-3">
              <div class="space-y-1">
                <div
                  v-for="(c, i) in sortedDirect"
                  :key="c.id"
                  class="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-black border border-neutral-800"
                >
                  <span class="w-7 h-7 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none">{{
                    c.seedRank || '-'
                  }}</span>
                  <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ c.name }}</span>
                  <span v-if="c.number" class="text-[10px] text-neutral-600 flex-none">#{{ c.number }}</span>
                  <span
                    v-if="isGuestComp(c)"
                    class="text-[10px] px-1 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex-none"
                    >GUEST</span
                  >
                  <button
                    v-if="isGuestComp(c) && canDirectAdd"
                    @click.stop="removeDirectGuest(c)"
                    :disabled="removingId === String(c.id)"
                    class="w-6 h-6 rounded border border-neutral-700 text-neutral-500 hover:text-red-500 hover:border-red-900/40 disabled:opacity-40 transition-colors flex items-center justify-center flex-none"
                    title="移除 GUEST"
                  >
                    <Trash2 class="w-3 h-3" />
                  </button>
                  <div v-if="canDirectAdd" class="flex items-center gap-0.5 flex-none">
                    <button
                      @click="moveDirectSeed(i, -1)"
                      :disabled="i === 0"
                      class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                      title="上移"
                    >
                      ↑
                    </button>
                    <button
                      @click="moveDirectSeed(i, 1)"
                      :disabled="i === sortedDirect.length - 1"
                      class="w-6 h-6 rounded border border-neutral-700 text-neutral-400 hover:text-amber-500 hover:border-amber-500/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                      title="下移"
                    >
                      ↓
                    </button>
                  </div>
                </div>
              </div>

              <!-- 小组赛:蛇形分组预览 -->
              <div v-if="targetMode === 'GROUP'" class="grid grid-cols-2 md:grid-cols-4 gap-2">
                <div v-for="(g, gi) in groupPreview" :key="gi" class="rounded-lg bg-black border border-neutral-800 p-2">
                  <div class="text-[10px] font-bold text-amber-500 mb-1">G{{ gi + 1 }}</div>
                  <div v-for="c in g" :key="c.id" class="text-xs text-neutral-300 truncate py-0.5">
                    {{ c.name }}<span v-if="isGuestComp(c)" class="text-amber-400"> ·GUEST</span>
                  </div>
                  <div v-if="g.length === 0" class="text-[10px] text-neutral-600">空组</div>
                </div>
              </div>

              <!-- 擂台赛:队列预览 -->
              <div v-else-if="targetMode === 'ARENA'" class="space-y-1">
                <div
                  v-for="c in arenaQueuePreview"
                  :key="c.id"
                  class="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-black border border-neutral-800"
                  :class="c.queueIndex === 1 ? 'border-amber-500/30' : ''"
                >
                  <span class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none">{{
                    c.queueIndex
                  }}</span>
                  <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ c.name }}</span>
                  <span
                    v-if="isGuestComp(c)"
                    class="text-[10px] px-1 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30 flex-none"
                    >GUEST</span
                  >
                </div>
              </div>

              <!-- 排名赛:圈分配预览 -->
              <div v-else-if="targetMode === 'RANK'" class="grid grid-cols-2 gap-2">
                <div v-for="(ccl, ci) in circlePreview" :key="ci" class="rounded-lg bg-black border border-neutral-800 p-2">
                  <div class="text-[10px] font-bold text-amber-500 mb-1">ZONE-{{ ci + 1 }}</div>
                  <div v-for="c in ccl" :key="c.id" class="text-xs text-neutral-300 truncate py-0.5">
                    {{ c.name }}<span v-if="isGuestComp(c)" class="text-amber-400"> ·GUEST</span>
                  </div>
                  <div v-if="ccl.length === 0" class="text-[10px] text-neutral-600">空圈</div>
                </div>
              </div>
            </div>
          </template>

          <!-- 海选目标:不支持 GUEST 加入 -->
          <p v-if="targetMode === 'AUDITION'" class="text-xs text-amber-400/80 leading-relaxed">
            海选赛段不支持添加 GUEST,GUEST 选手走签到/补签到流程。
          </p>
        </div>
      </div>

      <div class="space-y-4">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">间歇期设置</h4>
        <div class="flex items-center gap-4">
          <div class="flex-1">
            <label class="text-xs text-neutral-500 mb-1 block">预计休息时长 (小时)</label>
            <input
              type="number"
              v-model="config.breakDuration"
              class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none"
            />
          </div>
          <div class="flex-1">
            <label class="text-xs text-neutral-500 mb-1 block">下一阶段开始时间</label>
            <input
              type="datetime-local"
              class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none [color-scheme:dark]"
            />
          </div>
        </div>
      </div>

      <div class="flex justify-end pt-2">
        <button
          @click="saveConfig"
          :disabled="targetLocked || saving"
          class="px-6 py-2.5 rounded-lg bg-amber-500 text-neutral-900 text-sm font-bold hover:bg-amber-400 disabled:opacity-50 transition-colors"
        >
          {{ saving ? '保存中...' : '保存转场配置' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { ArrowRight, SlidersHorizontal, Zap, Hand, Lock, UserPlus, Trash2 } from 'lucide-vue-next';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getStage, updateStage, getStagePreBracket, adjustStageAdvancement, addStageGuest, setStageSeedOrder } from '@/api/game/stage';
import { listCompetitor, delCompetitor } from '@/api/game/competitor';

// Props
const props = defineProps<{
  sourceStageId: string | number;
  sourceStageName: string;
  targetStageId: string | number;
  targetStageName: string;
  transitionIndex: number;
}>();

// 转场配置数据
const config = reactive({
  mode: 'AUTO' as 'AUTO' | 'MANUAL',
  reshuffle: false,
  allowSubstitutions: false,
  breakDuration: 24
});

// 预排参赛者与保存状态
const preLoading = ref(false);
const preStatus = ref('');
const preSeeds = ref<any[]>([]);
const prePairs = ref<any[]>([]);
const saving = ref(false);
const advSaving = ref(false);
const pendingAdvancers = ref<any[]>([]);
const selectedAdvanceIds = ref<string[]>([]);
const sourceStage = ref<any>(null);
const targetStage = ref<any>(null);

const removingId = ref<string | null>(null);

/** 来源赛段是否为排名赛(同分待定晋级调整仅排名赛需要) */
const isRankSource = computed(() => sourceStage.value?.stageMode === 'RANK');

const modeLabelMap: Record<string, string> = {
  AUDITION: '海选赛',
  KNOCKOUT: '淘汰赛',
  GROUP: '小组赛',
  ARENA: '擂台赛',
  RANK: '排名赛'
};

/** 胜者预排调整仅在 MANUAL 且后端支持预排(淘汰/海选/排名 → 淘汰)时显示 */
const canPrebracketAdjust = computed(
  () => config.mode === 'MANUAL' && !!preStatus.value && preStatus.value !== 'UNSUPPORTED' && preStatus.value !== 'NO_PREV'
);

/** 预排可见:后端支持预排的任意状态(等待/预览/已生成) */
/** 预排可见:仅"待写入"状态(等待胜者/预排中),已生成后由下一赛段参赛方区接管,避免重复 */
const prebracketVisible = computed(() => preStatus.value === 'PREVIEW' || preStatus.value === 'WAIT_PREV');

const isGuestComp = (c: any) => c?.remark === 'GUEST';

/** 目标赛段已开始(非 DRAFT/PENDING)时锁定整页中间态调整 */
const targetLocked = computed(() => {
  const status = targetStage.value?.status;
  return status != null && status !== 'DRAFT' && status !== 'PENDING';
});

const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);

const loadSourceConfig = async () => {
  try {
    const resp: any = await getStage(props.sourceStageId);
    sourceStage.value = resp.data;
    const rule = JSON.parse(sourceStage.value.ruleConfig || '{}');
    const t = rule.transition || {};
    config.mode = t.mode === 'MANUAL' ? 'MANUAL' : 'AUTO';
    config.reshuffle = !!t.reshuffle;
    config.allowSubstitutions = !!t.allowSubstitutions;
    await loadPendingAdvancers();
  } catch {
    console.warn('加载转场配置失败');
  }
};

/** 加载来源赛段中处于待定(同分)状态的参赛者 */
const loadPendingAdvancers = async () => {
  if (!sourceStage.value || sourceStage.value.stageMode !== 'RANK') {
    pendingAdvancers.value = [];
    selectedAdvanceIds.value = [];
    return;
  }
  try {
    const resp: any = await listCompetitor({
      stageId: sourceStage.value.id,
      pageNum: 1,
      pageSize: 1000
    });
    const rows: any[] = resp.data?.rows ?? resp.data?.data ?? [];
    pendingAdvancers.value = rows.filter((c) => c.outcomeStatus === 'PENDING');
    selectedAdvanceIds.value = [];
  } catch {
    pendingAdvancers.value = [];
  }
};

const toggleAdvance = (id: string | number) => {
  const key = String(id);
  const idx = selectedAdvanceIds.value.indexOf(key);
  if (idx >= 0) {
    selectedAdvanceIds.value.splice(idx, 1);
  } else {
    selectedAdvanceIds.value.push(key);
  }
};

const saveAdvancement = async () => {
  if (!sourceStage.value || selectedAdvanceIds.value.length === 0) return;
  advSaving.value = true;
  try {
    await adjustStageAdvancement(sourceStage.value.id, selectedAdvanceIds.value);
    ElMessage.success('同分晋级调整已保存');
    await loadPendingAdvancers();
    await loadPreBracket();
  } catch (e: any) {
    console.error('保存同分晋级调整失败:', e);
    ElMessage.error(e?.response?.data?.msg || '保存失败');
  } finally {
    advSaving.value = false;
  }
};

const loadPreBracket = async () => {
  preLoading.value = true;
  try {
    const resp: any = await getStagePreBracket(props.targetStageId);
    preStatus.value = resp.data?.status || '';
    prePairs.value = resp.data?.pairs || [];
    preSeeds.value = (resp.data?.seededCompetitors || [])
      .slice()
      .sort((a: any, b: any) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER));
  } catch {
    preSeeds.value = [];
    prePairs.value = [];
    preStatus.value = '';
  } finally {
    preLoading.value = false;
  }
};

/** 预排配对状态文案 */
const pairStatusText = (status?: string) => {
  const map: Record<string, string> = { WINNER: '已定', TBD: '待定', BYE: '轮空' };
  return (status && map[status]) || status || '';
};

const loadTargetStage = async () => {
  try {
    const resp: any = await getStage(props.targetStageId);
    targetStage.value = resp.data;
    await loadDirectCompetitors();
  } catch {
    targetStage.value = null;
  }
};

// ---- 种子排位算法:与引擎一致的 SEED 查表 + 递归回退 ----
const SEED_LAYOUT_TABLE: Record<number, number[]> = {
  8: [1, 8, 4, 5, 3, 6, 2, 7],
  16: [1, 16, 8, 9, 5, 12, 4, 13, 3, 14, 6, 11, 7, 10, 2, 15],
  32: [1, 32, 9, 24, 16, 17, 8, 25, 5, 28, 13, 20, 12, 21, 4, 29, 3, 30, 11, 22, 14, 19, 6, 27, 7, 26, 15, 18, 10, 23, 2, 31]
};
const nextPowerOfTwo = (v: number) => {
  let p = 1;
  while (p < v) p <<= 1;
  return p;
};
const seedPositions = (n: number): number[] => {
  if (n <= 1) return [1];
  const prev = seedPositions(n / 2);
  const res: number[] = new Array(n);
  for (let i = 0; i < prev.length; i++) {
    res[2 * i] = prev[i];
    res[2 * i + 1] = n + 1 - prev[i];
  }
  return res;
};
const seedLayout = (n: number) => SEED_LAYOUT_TABLE[n] || seedPositions(n);

/** 通用两列对战树:按种子号排布为 LEFT/RIGHT 两半区场次(与引擎种子算法一致) */
const computePairs = (list: any[], pairingMode: string) => {
  const sorted = [...list].sort((a, b) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER));
  const n = sorted.length;
  if (!n) return [];
  const out: any[] = [];
  if (pairingMode === 'SEED') {
    const bs = nextPowerOfTwo(Math.max(n, 2));
    const layout = seedLayout(bs);
    const pairCount = Math.max(1, bs / 2);
    const half = Math.ceil(pairCount / 2);
    for (let i = 0; i < pairCount; i++) {
      const l = layout[2 * i];
      const r = layout[2 * i + 1];
      out.push({
        position: i + 1,
        zone: i < half ? 'LEFT' : 'RIGHT',
        left: l <= n ? sorted[l - 1] : null,
        right: r <= n ? sorted[r - 1] : null,
        leftStatus: l <= n ? 'WINNER' : 'BYE',
        rightStatus: r <= n ? 'WINNER' : 'BYE'
      });
    }
  } else {
    const pairs = Math.max(1, Math.ceil(n / 2));
    const half = Math.ceil(pairs / 2);
    for (let i = 0; i < pairs; i++) {
      out.push({
        position: i + 1,
        zone: i < half ? 'LEFT' : 'RIGHT',
        left: sorted[2 * i] || null,
        right: sorted[2 * i + 1] || null,
        leftStatus: sorted[2 * i] ? 'WINNER' : 'BYE',
        rightStatus: sorted[2 * i + 1] ? 'WINNER' : 'BYE'
      });
    }
  }
  return out;
};

// ---- GUEST 直入目标赛段:按目标赛段模式的专属落位逻辑 ----
const directForm = reactive({ name: '', type: 0, number: '' });
const directCompetitors = ref<any[]>([]);
const directLoading = ref(false);
const directAdding = ref(false);
const directSaving = ref(false);

/** 目标赛段模式:决定 GUEST 直入的落位方式 */
const targetMode = computed(() => targetStage.value?.stageMode || '');
const targetModeLabel = computed(() => modeLabelMap[targetMode.value] || targetMode.value || '—');

const targetRuleConfig = computed(() => {
  try {
    return JSON.parse(targetStage.value?.ruleConfig || '{}');
  } catch {
    return {};
  }
});
/** 目标淘汰赛配对模式:优先取配置;未配置时按与后端 generateMatches 相同的推断(海选/排名后=SEED,其余=SEQUENTIAL) */
const directPairingMode = computed(() => {
  const m = targetRuleConfig.value?.knockout?.pairingMode;
  if (m) return String(m).toUpperCase();
  const prevMode = sourceStage.value?.stageMode;
  return prevMode === 'AUDITION' || prevMode === 'RANK' ? 'SEED' : 'SEQUENTIAL';
});
const directGroupCount = computed(() => Number(targetRuleConfig.value?.group?.groupCount) || 1);
const directCircles = computed(() => Number(targetRuleConfig.value?.circles) || 1);

/** GUEST 直入窗口:目标赛段 DRAFT/PENDING 且未初始化 */
const canDirectAdd = computed(() => {
  const s = targetStage.value;
  if (!s) return false;
  return !s.isInitialized && (s.status === 'DRAFT' || s.status === 'PENDING');
});

/** 目标赛段当前参赛方(按种子号排序) */
const sortedDirect = computed(() =>
  [...directCompetitors.value].sort((a, b) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER))
);

const loadDirectCompetitors = async () => {
  if (!targetStage.value || targetMode.value === 'AUDITION') {
    directCompetitors.value = [];
    return;
  }
  directLoading.value = true;
  try {
    const resp: any = await listCompetitor({
      stageId: targetStage.value.id,
      pageNum: 1,
      pageSize: 1000
    } as any);
    directCompetitors.value = resp.data || [];
  } catch {
    directCompetitors.value = [];
  } finally {
    directLoading.value = false;
  }
};

const addDirectGuest = async () => {
  if (!targetStage.value) return;
  if (!directForm.name.trim()) {
    ElMessage.warning('请输入 GUEST 名称');
    return;
  }
  directAdding.value = true;
  try {
    await addStageGuest(targetStage.value.id, {
      name: directForm.name.trim(),
      type: directForm.type,
      number: directForm.number.trim() || undefined
    });
    ElMessage.success(`GUEST 已加入${targetStage.value.name}`);
    directForm.name = '';
    directForm.number = '';
    await loadDirectCompetitors();
  } catch (e: any) {
    console.error('添加 GUEST 失败:', e);
    ElMessage.error(e?.response?.data?.msg || '添加 GUEST 失败');
  } finally {
    directAdding.value = false;
  }
};

const saveDirectSeedOrder = async () => {
  if (!targetStage.value || directSaving.value) return;
  directSaving.value = true;
  try {
    await setStageSeedOrder(
      targetStage.value.id,
      sortedDirect.value.map((c) => c.id)
    );
    ElMessage.success('已按抽签结果保存顺序');
  } catch (e: any) {
    console.error('保存种子顺序失败:', e);
    ElMessage.error(e?.response?.data?.msg || '保存顺序失败');
  } finally {
    directSaving.value = false;
  }
};

/** 交换两个槽位的种子号(选手随新种子换位),随后自动保存 */
const swapDirectSeeds = (a: any, b: any) => {
  const rankA = a.seedRank;
  a.seedRank = b.seedRank;
  b.seedRank = rankA;
  saveDirectSeedOrder();
};

/** 扁平列表上移/下移(小组/擂台/排名共用) */
const moveDirectSeed = (index: number, dir: -1 | 1) => {
  const list = sortedDirect.value;
  const target = index + dir;
  if (target < 0 || target >= list.length) return;
  swapDirectSeeds(list[index], list[target]);
};

const removeDirectGuest = async (c: any) => {
  try {
    await ElMessageBox.confirm(`移除 GUEST「${c.name}」?该操作不可恢复。`, '移除 GUEST', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  removingId.value = String(c.id);
  try {
    await delCompetitor(c.id);
    ElMessage.success('GUEST 已移除');
    await loadDirectCompetitors();
  } catch (e: any) {
    console.error('移除 GUEST 失败:', e);
    ElMessage.error(e?.response?.data?.msg || '移除 GUEST 失败');
  } finally {
    removingId.value = null;
  }
};

/** 淘汰赛目标:两列对战树 */
const directPairs = computed(() => (targetMode.value === 'KNOCKOUT' ? computePairs(sortedDirect.value, directPairingMode.value) : []));
const directLeftPairs = computed(() => directPairs.value.filter((p) => p.zone === 'LEFT'));
const directRightPairs = computed(() => directPairs.value.filter((p) => p.zone === 'RIGHT'));

const directColumnSlots = (zone: string) => {
  const list: { pair: any; side: 'left' | 'right'; seed: any }[] = [];
  for (const p of directPairs.value) {
    if (p.zone !== zone) continue;
    if (p.left?.seedRank != null) list.push({ pair: p, side: 'left', seed: p.left });
    if (p.right?.seedRank != null) list.push({ pair: p, side: 'right', seed: p.right });
  }
  return list;
};

const canDirectMoveUp = (pair: any, side: 'left' | 'right') => {
  const slots = directColumnSlots(pair.zone);
  const idx = slots.findIndex((s) => s.pair === pair && s.side === side);
  return idx > 0;
};
const canDirectMoveDown = (pair: any, side: 'left' | 'right') => {
  const slots = directColumnSlots(pair.zone);
  const idx = slots.findIndex((s) => s.pair === pair && s.side === side);
  return idx >= 0 && idx < slots.length - 1;
};

const moveDirectBracketSeed = (pair: any, side: 'left' | 'right', dir: -1 | 1) => {
  const slots = directColumnSlots(pair.zone);
  const idx = slots.findIndex((s) => s.pair === pair && s.side === side);
  if (idx < 0) return;
  const target = slots[idx + dir];
  if (!target) return;
  const a = pair[side];
  const b = target.pair[target.side];
  if (!a || !b || a.seedRank == null || b.seedRank == null) return;
  swapDirectSeeds(a, b);
};

const swapDirectPair = (pair: any) => {
  if (!pair.left || !pair.right || pair.left.seedRank == null || pair.right.seedRank == null) return;
  swapDirectSeeds(pair.left, pair.right);
};

/** 小组赛落位预览:蛇形分组(与 GroupGenerator.snakeSplit 一致) */
const groupPreview = computed(() => {
  if (targetMode.value !== 'GROUP') return [];
  const gc = Math.max(1, directGroupCount.value);
  const groups: any[][] = Array.from({ length: gc }, () => []);
  sortedDirect.value.forEach((c, i) => {
    const row = Math.floor(i / gc);
    const col = i % gc;
    const g = row % 2 === 0 ? col : gc - 1 - col;
    groups[g].push(c);
  });
  return groups;
});

/** 排名赛落位预览:非随机分圈时按种子顺序均分(与 RankGenerator 一致) */
const circlePreview = computed(() => {
  if (targetMode.value !== 'RANK') return [];
  const n = sortedDirect.value.length;
  const circles = Math.max(1, directCircles.value);
  const base = Math.floor(n / circles);
  const rem = n % circles;
  const out: any[][] = [];
  let cursor = 0;
  for (let c = 0; c < circles; c++) {
    const count = base + (c < rem ? 1 : 0);
    out.push(sortedDirect.value.slice(cursor, cursor + count));
    cursor += count;
  }
  return out;
});

/** 擂台赛落位预览:初始队列 = 种子顺序(与 computeArenaQueue 一致) */
const arenaQueuePreview = computed(() => sortedDirect.value.map((c, i) => ({ ...c, queueIndex: i + 1 })));

const buildOverrides = () => {
  const map: Record<string, number> = {};
  preSeeds.value.forEach((s) => {
    if (s.competitorId != null && s.seedRank != null) {
      map[String(s.competitorId)] = Number(s.seedRank);
    }
  });
  return map;
};

const saveConfig = async () => {
  if (!sourceStage.value) {
    ElMessage.warning('转场配置尚未加载完成');
    return;
  }
  saving.value = true;
  try {
    const s = sourceStage.value;
    const rule = JSON.parse(s.ruleConfig || '{}');
    rule.transition = {
      ...(rule.transition || {}),
      mode: config.mode,
      reshuffle: config.reshuffle,
      allowSubstitutions: config.allowSubstitutions,
      seedOverrides: buildOverrides()
    };
    const formData: any = {
      id: s.id,
      tournamentId: s.tournamentId,
      name: s.name,
      stageMode: s.stageMode,
      format: s.format || '',
      teamCountStart: s.teamCountStart,
      teamCountEnd: s.teamCountEnd,
      status: s.status,
      ruleConfig: JSON.stringify(rule),
      isInitialized: s.isInitialized
    };
    if (qid(s.prevStageId) != null) formData.prevStageId = qid(s.prevStageId);
    if (qid(s.nextStageId) != null) formData.nextStageId = qid(s.nextStageId);
    await updateStage(formData);
    ElMessage.success('转场配置已保存');
  } catch (e: any) {
    console.error('保存转场配置失败:', e);
    ElMessage.error(e?.response?.data?.msg || '保存失败');
  } finally {
    saving.value = false;
  }
};

const loadAll = () => {
  loadSourceConfig();
  loadPreBracket();
  loadTargetStage();
};

// 切换转场(来源/目标赛段变化)时整体刷新
watch([() => props.sourceStageId, () => props.targetStageId], () => {
  directForm.name = '';
  directForm.type = 0;
  directForm.number = '';
  directCompetitors.value = [];
  loadAll();
});

onMounted(loadAll);
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.3s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
