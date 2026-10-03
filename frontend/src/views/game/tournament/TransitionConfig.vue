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
      <!-- 名单来源摘要(多来源组/自定义来源时显示;默认单来源组隐藏) -->
      <div
        v-if="rosterSummaryText"
        class="flex items-center gap-2 px-4 py-2 rounded-lg bg-amber-500/5 border border-amber-500/20 text-xs text-amber-400"
      >
        <span class="font-bold uppercase tracking-wider text-[10px] text-amber-500/80">名单来源</span>
        <span>{{ rosterSummaryText }}</span>
      </div>

      <!-- 入边来源(只读):来源组在「赛段配置 · 出口去向」维护,中间态只展示 -->
      <div v-if="!targetLocked" class="rounded-lg border border-neutral-800 bg-black/40 p-3 space-y-2">
        <div class="flex items-center justify-between">
          <span class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">选手来源 · {{ totalGroupCount }} 组</span>
          <span class="text-[10px] text-neutral-600">来源在「赛段配置 · 出口去向」中维护</span>
        </div>
        <div v-if="rosters.length === 0" class="text-[10px] text-neutral-600">暂无名单来源,刷新后仍为空请检查赛段创建</div>
        <div v-for="p in rosters" :key="String(p.id)" class="rounded bg-neutral-900/70 border border-neutral-800 px-2 py-1 text-[11px] space-y-1">
          <div class="flex items-center gap-2">
            <span class="text-neutral-500 font-mono">名单</span>
            <span class="ml-auto px-1.5 py-0.5 rounded text-[9px]" :class="rosterStateClass(p.state)">
              {{ rosterStateLabel(p.state) }}
            </span>
            <button
              v-if="p.state === 'READY' || p.state === 'WAIT_SOURCE'"
              @click="handleSkipRoster(p)"
              class="px-1.5 py-0.5 text-[9px] rounded border border-neutral-700 text-neutral-500 hover:text-neutral-300 transition-colors"
              title="跳过(0人/不需要)"
            >
              跳过
            </button>
          </div>
          <div v-for="(g, gi) in groupsOfRoster(p)" :key="'g' + gi" class="flex items-center gap-2">
            <span class="text-[10px] text-neutral-300 flex-1 min-w-0 truncate">{{ groupText(g) }}</span>
          </div>
        </div>
      </div>

      <!-- 本赛段名单:拖动排序、× 移出,加人走「＋ 加人」弹窗 -->
      <div v-if="rosters.length > 0" class="rounded-lg border border-neutral-800 bg-black/40 p-3 space-y-2">
        <div class="flex items-center justify-between">
          <span class="text-[12px] font-bold text-neutral-500 uppercase tracking-wider"> 本赛段名单 · {{ listItems.length }} 人 </span>
          <div class="flex items-center gap-2">
            <span v-if="overridePreview?.capacity" class="text-[11px] text-neutral-600"> 计划 {{ overridePreview.capacity }} 人 </span>
            <template v-if="targetMode === 'KNOCKOUT' && isSeedKnockoutTransition && !rosterReadonly">
              <button
                @click="clearRosterOrder"
                class="px-2 py-1 text-[12px] rounded border border-neutral-700 text-neutral-400 hover:text-neutral-200 transition-colors flex items-center gap-1"
                title="恢复为按来源名次排列的原始顺序"
              >
                <RotateCcw class="w-3 h-3" /> 恢复
              </button>
            </template>
            <button
              v-if="!rosterReadonly && targetMode !== 'AUDITION'"
              @click="openAddDialog"
              class="px-2 py-1 text-[12px] rounded border border-amber-500/30 text-amber-400 bg-amber-500/10 hover:bg-amber-500/20 transition-colors"
            >
              ＋ 加人
            </button>
          </div>
        </div>

        <div class="px-1 text-[11px]">
          <span v-if="overridePreview?.applied" class="text-green-500">名单已确认</span>
          <span v-else-if="overridePreview?.skipped" class="text-neutral-500">已跳过(本赛段不带人)</span>
          <span v-else-if="overridePreview?.ready === false" class="text-orange-400"> 来源赛段还没结束,以下是预期名单 </span>
          <span v-else class="text-neutral-500">
            {{
              targetMode === 'KNOCKOUT'
                ? '拖动选手:放到别人身上是交换,放到空位是搬过去(原位留空);× 移出;加人点右上角「＋ 加人」'
                : targetMode === 'ARENA'
                  ? '拖动可调整出场顺序(1 号位为擂主);× 移出;加人点右上角「＋ 加人」'
                  : targetMode === 'FREE_MATCH'
                    ? '自由对抗只要名单:不排序,点右上角「＋ 加人」补充,× 移出'
                    : '拖动可调整顺序,× 移出;加人点右上角「＋ 加人」'
            }}
          </span>
        </div>
        <p v-for="(w, wi) in overridePreview?.warnings || []" :key="'w' + wi" class="text-orange-400/90 px-1">⚠ {{ w }}</p>

        <!-- 已确认/已跳过/赛段已开始/上一赛段还没结束:只能看不能改 -->
        <div v-if="rosterReadonly" class="px-2 py-1.5 rounded bg-neutral-900/50 border border-neutral-800 text-[11px] text-neutral-500">
          {{
            rosterWaitingSource
              ? '上一赛段还没结束:名单只读,已晋级的选手会实时出现,等来源赛段结算后再调整。'
              : targetLocked
                ? '赛段已开始,名单已锁定,只能查看;如需调整请先重置赛段。'
                : '名单已定,如需调整请先在下方「重置赛段」。'
          }}
        </div>

        <!-- 待落位区:多入口汇合的人先到这里(没有座位号),拖到座位才算落位。
             已落位的人(含对战树里的)也能临时拖回来,取消座位号。
             赛段已开始/已结束(整页只读)时不再显示——那时既不能拖也另有下游名单接手。 -->
        <div
          v-if="!targetLocked"
          class="rounded border border-dashed p-1.5 transition-colors"
          :class="holdingHover ? 'border-amber-500/60 bg-amber-500/10' : 'border-amber-600/40 bg-amber-500/5'"
          @dragover.prevent="holdingHover = true"
          @dragleave="holdingHover = false"
          @drop.stop.prevent="onDropToHolding"
        >
          <div class="flex items-center justify-between gap-2 px-1 pb-1">
            <span class="text-[11px] font-bold text-amber-400/90">待落位 · {{ holdingItems.length }} 人</span>
          </div>
          <div v-if="holdingItems.length > 0" class="flex flex-wrap gap-1 px-1 pb-0.5">
            <div
              v-for="(it, idx) in holdingItems"
              :key="itemKeyOf(it)"
              :draggable="!rosterReadonly"
              @dragstart="onHoldingDragStart(idx, $event)"
              @dragend="onDragEnd"
              class="px-2 py-1 rounded bg-neutral-900 border border-neutral-700 text-[11px] text-neutral-300"
              :class="rosterReadonly ? '' : 'cursor-grab hover:border-amber-600/50'"
            >
              {{ it.name || '未命名' }}
              <span v-if="it.finalRank" class="text-[10px] text-neutral-600 ml-1">#{{ it.finalRank }}</span>
              <button
                v-if="!rosterReadonly"
                @click.stop="askRemoveItem(it)"
                title="从待落位区移出"
                class="ml-1.5 px-1 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors"
              >
                ×
              </button>
            </div>
          </div>
          <p v-else class="px-1 pb-0.5 text-[10px] text-neutral-500">空置位:把名单里的选手拖到这里可取消落位(这个人不再占座位号)。</p>
          <p class="px-1 text-[10px] text-neutral-500">
            多入口汇合不自动排座:把每个人拖到座位;有待落位的人时不能确认名单。
          </p>
        </div>

        <!-- 淘汰赛:左半区/右半区对战树(名单即对阵框架,空位照常占位) -->
        <div v-if="targetMode === 'KNOCKOUT'" class="grid grid-cols-2 gap-3">
          <div v-for="zone in BRACKET_ZONES" :key="zone.key" class="space-y-2">
            <div class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider text-center pb-1 border-b border-neutral-800">
              {{ zone.label }}
            </div>
            <div v-for="p in bracketPairsOf(zone.key)" :key="zone.key + p.position" class="rounded-lg bg-black border border-neutral-800 p-2">
              <div class="space-y-1.5">
                <template v-for="side in BRACKET_SIDES" :key="side">
                  <div
                    v-if="p[side]"
                    class="flex items-center gap-2 px-2 h-9 rounded bg-neutral-900/60 border transition-colors"
                    :class="[
                      bracketDragItem === p[side] ? 'border-amber-500/60 bg-amber-500/10 opacity-60' : 'border-neutral-800',
                      bracketDropKey === slotKeyOf(p, side) ? 'border-amber-500 ring-1 ring-amber-500/50' : '',
                      bracketEditable ? 'cursor-grab active:cursor-grabbing' : ''
                    ]"
                    :draggable="bracketEditable"
                    @dragstart="onBracketSlotDragStart(p[side], p, side, $event)"
                    @dragend="onBracketSlotDragEnd"
                    @dragover.prevent="onBracketSlotDragOver(p, side)"
                    @dragleave="onBracketSlotDragLeave(p, side)"
                    @drop.stop.prevent="onBracketSlotDrop(p, side)"
                    :title="bracketEditable ? '拖到另一个位置即可交换' : ''"
                  >
                    <span class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-400 flex-none">
                      {{ slotSeedAt(p, side) }}
                    </span>
                    <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ p[side].name || '未命名' }}</span>
                    <span v-if="entryTagLabel(p[side].entryTag)" class="text-[10px] px-1 py-0.5 rounded bg-neutral-800 text-neutral-400 flex-none">{{
                      entryTagLabel(p[side].entryTag)
                    }}</span>
                    <button
                      v-if="!rosterReadonly"
                      @click.stop="askRemoveItem(p[side])"
                      class="w-6 h-6 rounded border border-neutral-700 text-neutral-500 hover:text-red-500 hover:border-red-900/40 transition-colors flex items-center justify-center flex-none"
                      title="移出名单"
                    >
                      ×
                    </button>
                  </div>
                  <div
                    v-else
                    class="flex items-center gap-2 px-2 h-9 rounded bg-neutral-900/30 border border-dashed transition-colors"
                    :class="[bracketDropKey === slotKeyOf(p, side) ? 'border-amber-500 ring-1 ring-amber-500/50' : 'border-neutral-800']"
                    @dragover.prevent="onBracketSlotDragOver(p, side)"
                    @dragleave="onBracketSlotDragLeave(p, side)"
                    @drop.stop.prevent="onBracketSlotDrop(p, side)"
                  >
                    <!-- 结构对齐有人时的格子,保证两种状态高度一致 -->
                    <span
                      class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold flex-none"
                      :class="bracketDropKey === slotKeyOf(p, side) ? 'bg-amber-500/20 text-amber-400' : 'bg-neutral-800 text-neutral-500'"
                    >
                      {{ slotSeedAt(p, side) }}
                    </span>
                    <span
                      class="flex-1 min-w-0 text-sm truncate"
                      :class="bracketDropKey === slotKeyOf(p, side) ? 'text-amber-400' : 'text-neutral-600'"
                    >
                      {{ rosterWaitingSource ? '待定' : '轮空' }}
                    </span>
                    <span class="w-6 h-6 flex-none"></span>
                  </div>
                  <div v-if="side === 'left'" class="text-center text-[10px] text-neutral-700">VS</div>
                </template>
              </div>
            </div>
            <div v-if="bracketPairsOf(zone.key).length === 0" class="text-[11px] text-neutral-600 text-center py-4">
              赛段还没有设置参赛人数,无法预览对阵框架
            </div>
          </div>
        </div>

        <!-- 其他赛段模式:上下拖动即排序,× 移出;加人走右上角弹窗 -->
        <div v-else class="space-y-2">
          <div
            v-if="targetMode !== 'ARENA' && targetMode !== 'FREE_MATCH'"
            class="rounded bg-neutral-900/70 border border-neutral-800 overflow-y-auto custom-scrollbar max-h-96 p-1 pt-1 space-y-0.5"
          >
            <div v-if="seatItems.length === 0 && emptySlots.length === 0" class="text-[11px] text-neutral-600 text-center py-6">
              还没有人进来;点右上角「＋ 加人」添加
            </div>
            <div
              v-for="(it, idx) in seatItems"
              :key="'r' + idx"
              draggable="true"
              @dragstart="onDragStartItem(idx, $event)"
              @dragend="onDragEnd"
              @dragover.prevent="hoverIndex = idx"
              @drop.stop.prevent="onDropToRoster(idx)"
              class="flex items-center gap-2 px-1.5 h-7 rounded cursor-grab text-[12px]"
              :class="dragIndex !== null && hoverIndex === idx ? 'bg-amber-500/10 border-t border-amber-500/50' : 'hover:bg-neutral-800/60'"
            >
              <span class="text-neutral-600 font-mono w-5 flex-none">{{ it.seedRank }}</span>
              <span class="flex-1 min-w-0 truncate text-neutral-300">{{ it.name || '未命名' }}</span>
              <span class="text-[10px] text-neutral-600 flex-none">{{ entryTagLabel(it.entryTag) }}</span>
              <button
                v-if="!rosterReadonly"
                @click.stop="askRemoveItem(it)"
                class="px-1.5 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors flex-none"
              >
                ×
              </button>
            </div>
            <!-- 还没到齐时按计划人数补空位,位置和出场次序对得上 -->
            <div
              v-for="n in emptySlots"
              :key="'slot-' + n"
              class="flex items-center gap-2 px-1.5 h-7 rounded text-[12px] text-neutral-700"
              @dragover.prevent="hoverIndex = -n"
              @drop.stop.prevent="onDropHoldingToEmptySlot(n)"
              :class="hoverIndex === -n ? 'bg-amber-500/10 ring-1 ring-amber-500/40' : ''"
            >
              <span class="font-mono w-5 flex-none">{{ n }}</span>
              <span class="flex-1 min-w-0">{{ dragHoldingIndex != null ? '放到此位' : '空位' }}</span>
            </div>
          </div>

          <!-- 小组赛:蛇形分组预览 -->
          <div v-if="targetMode === 'GROUP'" class="grid grid-cols-2 md:grid-cols-4 gap-2">
            <div v-for="(g, gi) in groupPreview" :key="gi" class="rounded-lg bg-black border border-neutral-800 p-2">
              <div class="text-[10px] font-bold text-amber-500 mb-1">G{{ gi + 1 }}</div>
              <div v-for="c in g" :key="c.id" class="text-xs text-neutral-300 truncate py-0.5">
                {{ c.name }}<span v-if="entryTagLabel(c.entryTag)" class="text-neutral-500"> ·{{ entryTagLabel(c.entryTag) }}</span>
              </div>
              <div v-if="g.length === 0" class="text-[10px] text-neutral-600">空组</div>
            </div>
          </div>

          <!-- 擂台赛:出场队列(1 号位=擂主,拖动可调整出场顺序;只列进入擂台赛的人) -->
          <div v-else-if="targetMode === 'ARENA'" class="space-y-1">
            <div class="flex items-center justify-between px-1 pb-1">
              <span class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">出场顺序</span>
              <span class="text-[10px] text-neutral-600">1 号位为擂主,拖动可调整顺序</span>
            </div>
            <div
              v-for="(c, idx) in rosterRows"
              :key="itemKeyOf(c)"
              draggable="true"
              @dragstart="onArenaDragStart(idx, $event)"
              @dragend="onDragEnd"
              @dragover.prevent="arenaHoverIndex = idx"
              @drop.stop.prevent="onDropToArena(idx)"
              class="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-black border cursor-grab"
              :class="[
                idx === 0 ? 'border-amber-500/40' : 'border-neutral-800',
                arenaDragIndex !== null && arenaHoverIndex === idx ? 'border-amber-500 ring-1 ring-amber-500/50' : ''
              ]"
            >
              <span
                class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold flex-none"
                :class="idx === 0 ? 'bg-amber-500 text-neutral-900' : 'bg-neutral-800 text-neutral-400'"
                >{{ idx + 1 }}</span
              >
              <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ c.name || '未命名' }}</span>
              <span v-if="entryTagLabel(c.entryTag)" class="text-[10px] text-neutral-600 flex-none">{{ entryTagLabel(c.entryTag) }}</span>
              <button
                v-if="!rosterReadonly"
                @click.stop="askRemoveItem(c)"
                class="px-1.5 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors flex-none"
                title="移出擂台赛名单"
              >
                ×
              </button>
            </div>
            <!-- 还没到齐时按计划人数补空位,位置和出场次序对得上 -->
            <div
              v-for="n in emptySlots"
              :key="'arena-slot-' + n"
              class="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-black border border-dashed border-neutral-800 text-[12px] text-neutral-700"
              @dragover.prevent="arenaHoverIndex = -n"
              @drop.stop.prevent="onDropHoldingToEmptySlot(n)"
            >
              <span class="w-6 h-6 rounded flex items-center justify-center text-[10px] font-bold bg-neutral-800 text-neutral-500 flex-none">{{
                n
              }}</span>
              <span class="flex-1 min-w-0">空位</span>
            </div>
            <div v-if="rosterRows.length === 0" class="text-[11px] text-neutral-600 text-center py-2">还没有人进来;点右上角「＋ 加人」添加</div>
          </div>

          <!-- 自由对抗:纯名单,只加人/减人,不排顺序(对手由现场抽签/指认) -->
          <div v-else-if="targetMode === 'FREE_MATCH'" class="space-y-1">
            <div class="flex items-center justify-between px-1 pb-1">
              <span class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">参赛名单</span>
              <span class="text-[10px] text-neutral-600">不排序:对手由现场抽签 / 指认决定</span>
            </div>
            <div
              v-for="c in rosterRows"
              :key="itemKeyOf(c)"
              class="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-black border border-neutral-800"
            >
              <span class="flex-1 min-w-0 text-sm text-neutral-200 truncate">{{ c.name || '未命名' }}</span>
              <span v-if="entryTagLabel(c.entryTag)" class="text-[10px] text-neutral-600 flex-none">{{ entryTagLabel(c.entryTag) }}</span>
              <button
                v-if="!rosterReadonly"
                @click.stop="askRemoveItem(c)"
                class="px-1.5 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors flex-none"
                title="移出名单"
              >
                ×
              </button>
            </div>
            <div v-if="rosterRows.length === 0" class="text-[11px] text-neutral-600 text-center py-2">还没有人进来;点右上角「＋ 加人」添加</div>
          </div>

          <!-- 排名赛:圈分配预览 -->
          <div v-else-if="targetMode === 'RANK'" class="grid grid-cols-2 gap-2">
            <div v-for="(ccl, ci) in circlePreview" :key="ci" class="rounded-lg bg-black border border-neutral-800 p-2">
              <div class="text-[10px] font-bold text-amber-500 mb-1">ZONE-{{ ci + 1 }}</div>
              <div v-for="c in ccl" :key="c.id" class="text-xs text-neutral-300 truncate py-0.5">
                {{ c.name }}<span v-if="entryTagLabel(c.entryTag)" class="text-neutral-500"> ·{{ entryTagLabel(c.entryTag) }}</span>
              </div>
              <div v-if="ccl.length === 0" class="text-[10px] text-neutral-600">空圈</div>
            </div>
          </div>
        </div>

        <p class="text-[11px] text-neutral-600 leading-relaxed">这里的手工调整只影响本赛段名单,不会改动来源赛段结果;确认名单后名单锁定。</p>
      </div>

      <!-- 加人:直接输入姓名(外卡),或从其他赛段选人;落位可选 顶上/替换 -->
      <GameDialog
        v-model="addDialogVisible"
        width="520px"
        title="加人"
        :subtitle="`加入「${targetStageName}」名单`"
        :icon="UserPlus"
        :close-on-click-modal="false"
      >
        <div class="flex gap-2 mb-3">
          <button
            type="button"
            @click="addMode = 'name'"
            class="flex-1 py-1.5 text-[12px] rounded border transition-colors"
            :class="
              addMode === 'name' ? 'border-amber-500/60 text-amber-400 bg-amber-500/10' : 'border-neutral-700 text-neutral-400 hover:text-neutral-200'
            "
          >
            输入姓名
          </button>
          <button
            type="button"
            @click="switchToStageMode"
            class="flex-1 py-1.5 text-[12px] rounded border transition-colors"
            :class="
              addMode === 'stage'
                ? 'border-amber-500/60 text-amber-400 bg-amber-500/10'
                : 'border-neutral-700 text-neutral-400 hover:text-neutral-200'
            "
          >
            从其他赛段选人
          </button>
          <button
            type="button"
            @click="switchToPlayerMode"
            class="flex-1 py-1.5 text-[12px] rounded border transition-colors"
            :class="
              addMode === 'player'
                ? 'border-amber-500/60 text-amber-400 bg-amber-500/10'
                : 'border-neutral-700 text-neutral-400 hover:text-neutral-200'
            "
          >
            从选手库选人
          </button>
        </div>

        <div v-if="addMode === 'name'" class="space-y-2">
          <input v-model="addName" class="cfg-input w-full" placeholder="姓名或选手姓名" />
        </div>
        <div v-else-if="addMode === 'stage'" class="space-y-2">
          <el-select
            v-model="addStageId"
            class="w-full add-select"
            popper-class="add-select-popper"
            placeholder="选择赛段"
            filterable
            @change="loadAddStagePeople"
          >
            <el-option v-for="s in addStages" :key="String(s.id)" :label="s.name" :value="String(s.id)" />
          </el-select>
          <el-select
            v-model="addPersonId"
            class="w-full add-select"
            popper-class="add-select-popper"
            placeholder="输入姓名筛选，或展开选择"
            filterable
            :disabled="!addStageId"
          >
            <el-option
              v-for="c in addStagePeople"
              :key="String(c.id)"
              :label="`${c.name}${c.finalRank ? ' · 第' + c.finalRank + '名' : ''}`"
              :value="String(c.id)"
            />
          </el-select>
          <div v-if="addStageId && addStagePeople.length === 0" class="text-[11px] text-neutral-600">该赛段没有可加入的人员。</div>
        </div>
        <div v-else class="space-y-2">
          <el-select
            v-model="addPlayerId"
            class="w-full add-select"
            popper-class="add-select-popper"
            placeholder="输入姓名筛选，或展开选择"
            filterable
          >
            <el-option v-for="p in addPlayers" :key="String(p.id)" :label="playerOptionLabel(p)" :value="String(p.id)" />
          </el-select>
          <div v-if="addPlayers.length === 0" class="text-[11px] text-neutral-600">本赛事选手库为空，或所有人都已在名单里。</div>
          <div v-else class="text-[11px] text-neutral-600">已签到的选手按他的参赛记录加入；没签到的按外卡挂到该选手档案上。</div>
        </div>

        <!-- 落位方式 -->
        <div class="mt-4 pt-4 border-t border-neutral-800 space-y-2">
          <div class="text-[11px] text-neutral-500">加进来的位置</div>
          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              @click="addPlacement = 'INSERT'"
              class="px-3 py-1.5 text-[12px] rounded border transition-colors"
              :class="placementBtnClass('INSERT')"
              title="插到指定位置:第 1 位就是顶前,最后一位就是加到末尾;后面的人依次后移"
            >
              插入
            </button>
            <button
              type="button"
              @click="addPlacement = 'REPLACE'"
              class="px-3 py-1.5 text-[12px] rounded border transition-colors"
              :class="placementBtnClass('REPLACE')"
            >
              替换
            </button>
          </div>
          <p class="text-[11px] text-neutral-600 leading-relaxed">
            <template v-if="addPlacement === 'INSERT'">
              插到选中的人前面:他和他后面的人依次后移一位(插第 1 个之前 = 顶前); 名单已满时最后一名会被挤出去。
            </template>
            <template v-else>选择被替换的人(或一个空位),新人放到那个位置,其他人不动。</template>
          </p>
          <el-select
            v-if="addPlacement === 'REPLACE'"
            v-model="addReplaceKey"
            class="w-full add-select"
            popper-class="add-select-popper"
            placeholder="选择要替换的人或空位"
            filterable
          >
            <el-option v-for="it in listItems" :key="itemKeyOf(it)" :label="`#${it.seedRank ?? '-'} ${it.name || '未命名'}`" :value="itemKeyOf(it)" />
            <!-- 也可以直接落到空位上(空位不占人,只是把新人放到那个位置) -->
            <el-option v-for="n in emptySlots" :key="'slot-' + n" :label="`#${n} 空位`" :value="'slot:' + n" />
          </el-select>
          <el-select
            v-else-if="addPlacement === 'INSERT'"
            v-model="addReplaceKey"
            class="w-full add-select"
            popper-class="add-select-popper"
            placeholder="选择插到谁前面"
            filterable
          >
            <el-option
              v-for="it in listItems"
              :key="itemKeyOf(it)"
              :label="`#${it.seedRank ?? '-'} ${it.name || '未命名'} 之前`"
              :value="itemKeyOf(it)"
            />
          </el-select>
        </div>

        <template #footer>
          <div class="flex justify-end gap-2">
            <button
              class="px-3 py-1.5 text-[12px] rounded border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
              @click="addDialogVisible = false"
            >
              取消
            </button>
            <button
              class="px-3.5 py-1.5 text-[12px] rounded bg-amber-500 text-neutral-900 font-medium hover:bg-amber-400 disabled:opacity-40 transition-colors"
              :disabled="addSubmitting || (addPlacement === 'REPLACE' && !addReplaceKey)"
              @click="submitAdd"
            >
              {{ addSubmitting ? '提交中...' : '加入名单' }}
            </button>
          </div>
        </template>
      </GameDialog>

      <!-- 移出确认:顶上 or 留空位 -->
      <GameDialog v-model="removeDialogVisible" width="440px" title="移出名单" subtitle="选择移出后其他人的位置怎么处理" :icon="UserMinus">
        <div class="text-[13px] text-neutral-300 leading-relaxed">
          把
          <span class="text-amber-400 font-medium">{{ removeDialogItem?.name || '未命名' }}</span>
          <span v-if="removeDialogSeed"> （第 {{ removeDialogSeed }} 位）</span>
          移出「{{ targetStageName }}」名单?
        </div>
        <div class="mt-3 space-y-2 text-[12px]">
          <div class="rounded border border-neutral-800 bg-black/40 px-3 py-2 text-neutral-400">
            <span class="text-neutral-200">后续的人顶上</span>:他后面的人整体前移一位,名单保持连续。
          </div>
          <div class="rounded border border-neutral-800 bg-black/40 px-3 py-2 text-neutral-400">
            <span class="text-neutral-200">留下空位</span>:他的位置空着,后面的人不动(空位可稍后再补人)。
          </div>
        </div>
        <template #footer>
          <div class="flex justify-end gap-2">
            <button
              class="px-3 py-1.5 text-[12px] rounded border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
              @click="removeDialogVisible = false"
            >
              取消
            </button>
            <button
              class="px-3.5 py-1.5 text-[12px] rounded border border-neutral-600 text-neutral-200 hover:bg-neutral-800 transition-colors"
              @click="confirmRemoveItem(true)"
            >
              后续的人顶上
            </button>
            <button
              class="px-3.5 py-1.5 text-[12px] rounded bg-amber-500 text-neutral-900 font-medium hover:bg-amber-400 transition-colors"
              @click="confirmRemoveItem(false)"
            >
              留下空位
            </button>
          </div>
        </template>
      </GameDialog>

      <!-- 目标赛段已开始:整页锁定 -->
      <!-- 名单卡片会在锁定态显示同样的提示,这里只在没有名单卡片时兜底 -->
      <div
        v-if="targetLocked && rosters.length === 0"
        class="flex items-center gap-3 px-4 py-3 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400 text-sm"
      >
        <Lock class="w-4 h-4 flex-none" />
        <span>{{ targetStageName }} 已开始,中间态调整已锁定,如需调整请先重置该赛段。</span>
      </div>

      <!-- 晋级确认:所有流转必须经过中间态 -->
      <div class="space-y-3">
        <h4 class="text-sm font-bold text-neutral-300 uppercase tracking-wider">晋级确认 · {{ sourceStageName }} → {{ targetStageName }}</h4>
        <div class="rounded-lg bg-black border border-neutral-800 p-4 flex items-center justify-between gap-4">
          <div class="text-sm text-neutral-300">
            <template v-if="sourceStage?.status === 'SETTLED'">
              {{ sourceStageName }} 已结束
              <span :class="advancementConfirmed ? 'text-green-500' : 'text-amber-400'">
                {{ advancementConfirmed ? '· 晋级已确认' : '· 待确认晋级' }}
              </span>
            </template>
            <template v-else>等待 {{ sourceStageName }} 结算后,在此确认晋级到 {{ targetStageName }}</template>
          </div>
          <button
            v-if="sourceStage?.status === 'SETTLED' && !advancementConfirmed"
            @click="handleConfirmAdvancement"
            :disabled="confirmingAdvancement || targetLocked || holdingItems.length > 0"
            :title="holdingItems.length > 0 ? `还有 ${holdingItems.length} 人没落位` : ''"
            class="flex-none px-4 py-2 rounded-lg bg-amber-500 text-neutral-900 text-xs font-bold hover:bg-amber-400 disabled:opacity-40 transition-colors"
          >
            {{ confirmingAdvancement ? '提交中...' : holdingItems.length > 0 ? `待落位 ${holdingItems.length} 人` : '确认晋级' }}
          </button>
          <span v-else-if="advancementConfirmed" class="flex-none text-[10px] px-2 py-1 rounded bg-green-500/10 text-green-500">已确认</span>
        </div>
      </div>

      <div class="h-px bg-neutral-800 w-full"></div>

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
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowRight, SlidersHorizontal, Lock, RotateCcw, UserPlus, UserMinus } from 'lucide-vue-next';
import { ElMessage, ElMessageBox } from 'element-plus';
import GameDialog from '@/components/GameDialog/index.vue';
import { getStage, listStage, adjustStageAdvancement } from '@/api/game/stage';
import {
  applyStageRoster,
  getStageRoster,
  skipStageRoster,
  getRosterCandidates,
  addRosterOverride,
  deleteRosterOverride,
  deleteRosterOverrides,
  getRosterPreview,
  rebuildStageRoster,
  setRosterOrder
} from '@/api/game/stage/roster';
import { seedLayout } from '@/utils/seedLayout';
import { listCompetitor } from '@/api/game/competitor';
import { listPlayer } from '@/api/game/player';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

// Props
const props = defineProps<{
  sourceStageId: string | number;
  sourceStageName: string;
  targetStageId: string | number;
  targetStageName: string;
  transitionIndex: number;
  /** 目标赛段入边池摘要(池化;StageFlow 从 stage.incoming 传入) */
  incoming?: any[];
  /** 赛段名映射(用于展示非相邻来源名称) */
  stageNames?: Record<string, string>;
}>();

const emit = defineEmits<{
  /** 晋级确认成功(参数:目标赛段ID),用于导播台自动跳转到下一赛段 */
  confirmed: [targetStageId: string | number];
}>();

// 晋级确认状态
const advSaving = ref(false);
const confirmingAdvancement = ref(false);
const pendingAdvancers = ref<any[]>([]);
const selectedAdvanceIds = ref<string[]>([]);
const sourceStage = ref<any>(null);
const targetStage = ref<any>(null);

/** 来源赛段是否为排名赛(同分待定晋级调整仅排名赛需要) */
const isRankSource = computed(() => sourceStage.value?.stageMode === 'RANK');

const modeLabelMap: Record<string, string> = {
  AUDITION: '海选赛',
  KNOCKOUT: '淘汰赛',
  GROUP: '小组赛',
  ARENA: '擂台赛',
  RANK: '排名赛',
  FREE_MATCH: '自由对抗'
};

/** 目标赛段已开始(非 DRAFT)时锁定整页中间态调整 */
const targetLocked = computed(() => {
  const status = targetStage.value?.status;
  return status != null && status !== 'DRAFT';
});

/** 目标赛段名单摘要:优先用接口实时数据,接口失败时回退到 StageFlow 传入的摘要 */
const rosterItems = ref<any[] | null>(null);
const rosters = computed<any[]>(() => rosterItems.value ?? props.incoming ?? []);

/** 名单行的来源组:后端始终以 config_json.groups 返回,不做旧列回退 */
const groupsOfRoster = (p: any): any[] => p?.groups ?? [];

const totalGroupCount = computed(() => rosters.value.reduce((sum, p) => sum + groupsOfRoster(p).length, 0));

/** 是否仅"上一赛段 ADVANCE AUTO"单一来源组:是则保持简单交互(不弹来源摘要) */
const singleDefaultRoster = computed(() => {
  const list = rosters.value;
  if (list.length === 0) return false;
  if (list.length > 1) return false;
  const p = list[0];
  const groups = groupsOfRoster(p);
  if (groups.length !== 1) return false;
  const g = groups[0];
  if (g.sourceStageId == null) return false;
  if (sourceStage.value && String(g.sourceStageId) !== String(sourceStage.value.id)) return false;
  return (g.fillMode || 'AUTO') === 'AUTO' && (g.resultFilter || 'ADVANCE') === 'ADVANCE';
});

/** 多来源组/自定义来源模式:显示来源摘要,确认动作切换为整单装配 */
const sourceGroupMode = computed(() => {
  const list = rosters.value;
  return list.length > 0 && !singleDefaultRoster.value;
});

/** 名单是否全部可装配(无 WAIT_SOURCE) */
const rostersReadyForApply = computed(() => {
  const list = rosters.value;
  if (list.length === 0) return false;
  return list.every((p) => p.state !== 'WAIT_SOURCE');
});

const sourceNameOf = (id: string | number | null | undefined): string => {
  if (id == null) return '外部/签到';
  if (props.stageNames && props.stageNames[String(id)]) return props.stageNames[String(id)];
  if (String(id) === String(props.sourceStageId)) return props.sourceStageName;
  return '赛段 #' + id;
};

const rankTextOf = (g: any): string => {
  if (g.rankStart == null && g.rankEnd == null) return '';
  const range = `${g.rankStart ?? ''}~${g.rankEnd ?? '末'}名`;
  return g.rankByZone ? `圈内${range}` : `全场${range}`;
};

/** ZONE-2 → 第2圈 */
const zoneTextOf = (zone?: string | null): string => {
  const m = /^ZONE-(\d+)$/.exec(zone || '');
  return m ? `第${m[1]}圈` : zone || '';
};

const groupText = (g: any): string => {
  const quota = g.quota && g.quota > 0 ? ` 前${g.quota}` : '';
  const zone = g.zone ? `·${zoneTextOf(g.zone)}` : '';
  return `${sourceNameOf(g.sourceStageId)}${zone}·${resultLabelOf(g.resultFilter)}${quota}·${fillLabelOf(g.fillMode)}${
    rankTextOf(g) ? `·${rankTextOf(g)}` : ''
  }`;
};

const resultLabelOf = (filter?: string): string => {
  if (filter === 'ADVANCE') return '晋级';
  if (filter === 'ELIMINATED') return '落选';
  if (filter === 'ANY') return '不限';
  return filter || '不限';
};

const fillLabelOf = (fill?: string): string => {
  if (fill === 'MANUAL') return '手动';
  if (fill === 'STREAM') return '流式';
  return '自动';
};

const rosterSummaryText = computed(() => {
  if (!sourceGroupMode.value) return '';
  return rosters.value.flatMap((p) => groupsOfRoster(p).map((g) => groupText(g))).join(' + ');
});

const rosterStateLabel = (state?: string): string => {
  const map: Record<string, string> = {
    WAIT_SOURCE: '等来源结算',
    READY: '就绪',
    CONFIRMED: '已带入',
    SKIPPED: '跳过'
  };
  return (state && map[state]) || state || '—';
};

const rosterStateClass = (state?: string): string => {
  const map: Record<string, string> = {
    WAIT_SOURCE: 'bg-neutral-800 text-neutral-400 border border-neutral-700',
    READY: 'bg-amber-500/10 text-amber-400 border border-amber-500/30',
    CONFIRMED: 'bg-green-500/10 text-green-400 border border-green-500/30',
    SKIPPED: 'bg-neutral-800 text-neutral-500 border border-neutral-700'
  };
  return map[state || ''] || 'bg-neutral-800 text-neutral-400 border border-neutral-700';
};

const loadRosters = async () => {
  try {
    const resp: any = await getStageRoster(props.targetStageId);
    rosterItems.value = resp?.data ? [resp.data] : [];
  } catch {
    rosterItems.value = props.incoming || [];
  }
  await refreshOverrideTargets();
};

const handleSkipRoster = async (roster: any) => {
  try {
    await ElMessageBox.confirm('确认跳过本赛段的全部名单来源?本赛段将不带入任何人。', '跳过名单', {
      type: 'warning',
      confirmButtonText: '跳过',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await skipStageRoster(roster.id);
    ElMessage.success('已跳过名单');
    await loadRosters();
  } catch (e: any) {
    notifyError(e, '跳过失败');
  }
};

// ---- 本赛段名单:手工增删改(两列拖动) ----
const firstRoster = computed<any>(() => rosters.value[0] || null);
const overridesOf = (roster: any): any[] => roster?.overrides ?? [];
const overridePreview = ref<any>(null);
const listItems = ref<any[]>([]);
const dragIndex = ref<number | null>(null);
const hoverIndex = ref<number | null>(null);
/** 待落位区拖动:index 是 holdingItems 里的下标 */
const dragHoldingIndex = ref<number | null>(null);
const holdingHover = ref(false);
/** 擂台赛出场队列的拖动状态(1 号位=擂主) */
const arenaDragIndex = ref<number | null>(null);
const arenaHoverIndex = ref<number | null>(null);

/** 名单已确认/已跳过:只能看不能改 */
const rosterSealed = computed(() => !!overridePreview.value?.applied || !!overridePreview.value?.skipped);

/**
 * 上一赛段还没结束:中间态只读。
 *
 * <p>此时列表里是"打到这里为止已晋级的人"(后端每判完一场就实时落座),加人/拖位没有意义 ——
 * 后面每场都会按上游名次覆盖对应座位。等来源赛段全部结算后才放开调整。</p>
 */
const rosterWaitingSource = computed(() => !rosterSealed.value && overridePreview.value != null && overridePreview.value.ready === false);

/** 中间态是否只读(已确认/已跳过、赛段已开赛、上一赛段还没结束) */
const rosterReadonly = computed(() => rosterSealed.value || targetLocked.value || rosterWaitingSource.value);

/**
 * 已落位的人(有座位号)。
 * 多入口汇合时人不自动排座,全部先落在「待落位区」(没有座位号),
 * 由导播拖到座位;单入口仍然是自动排座,不会有待落位的人。
 */
const seatItems = computed<any[]>(() => listItems.value.filter((i) => Number(i.seedRank) > 0));

/** 待落位的人(没有座位号):确认名单前必须全部拖到座位上 */
const holdingItems = computed<any[]>(() => listItems.value.filter((i) => !(Number(i.seedRank) > 0)));

/** 名单还差多少人到计划规模:多出来的位置显示为空位 */
const emptySlots = computed(() => {
  const cap = Number(overridePreview.value?.capacity || 0);
  if (cap <= 0) return [];
  // 空位 = 计划范围内没有任何人占用的种子位(删人后原来的位置保持空白)
  const used = new Set(seatItems.value.map((i) => Number(i.seedRank)).filter((n) => Number.isFinite(n) && n > 0));
  const out: number[] = [];
  for (let n = 1; n <= cap; n++) {
    if (!used.has(n)) out.push(n);
  }
  return out;
});

/** 名单按出场次序排列(只含已落位的人;落位预览、分组预览、对战树共用) */
const rosterRows = computed<any[]>(() => [...seatItems.value].sort((a, b) => Number(a.seedRank ?? 0) - Number(b.seedRank ?? 0)));

const onDragStartItem = (idx: number, e?: DragEvent) => {
  dragIndex.value = idx;
  e?.dataTransfer?.setData('text/plain', String(seatItems.value[idx]?.sourceCompetitorId ?? idx));
  if (e?.dataTransfer) e.dataTransfer.effectAllowed = 'move';
};

const onHoldingDragStart = (idx: number, e?: DragEvent) => {
  dragHoldingIndex.value = idx;
  e?.dataTransfer?.setData('text/plain', String(holdingItems.value[idx]?.sourceCompetitorId ?? idx));
  if (e?.dataTransfer) e.dataTransfer.effectAllowed = 'move';
};

const onDragEnd = () => {
  dragIndex.value = null;
  hoverIndex.value = null;
  dragHoldingIndex.value = null;
  holdingHover.value = false;
  arenaDragIndex.value = null;
  arenaHoverIndex.value = null;
  bracketDragItem.value = null;
  bracketDropKey.value = '';
};

/** 保存当前顺序:位置即出场次序 */
const persistOrder = async () => {
  const p = firstRoster.value;
  if (!p) return;
  try {
    await setRosterOrder(
      p.id,
      listItems.value.map((i) => ({
        sourceCompetitorId: i.sourceCompetitorId ?? undefined,
        overrideId: i.overrideId ?? undefined,
        // 显式带种子位:删掉的人原来的位置留空,后面的不顶上
        seedRank: i.seedRank ?? undefined,
        // 待落位的人没有座位号:必须显式告诉后端"留在待落位区",否则会被补到最小空闲位
        holding: Number(i.seedRank) > 0 ? undefined : true
      }))
    );
  } catch (e: any) {
    const status = e?.response?.status;
    if (status === 404 || status === 405) {
      ElMessage.error('保存顺序失败:后端未更新该接口,请重启后端服务后重试');
    } else {
      notifyError(e, '顺序保存失败');
    }
  }
};

/** 移出名单:人工补入/外卡=撤销该条调整,来源带入=记一条移出 */
/** 移出确认弹窗:可选「后续的人顶上」或「留下空位」 */
const removeDialogVisible = ref(false);
const removeDialogItem = ref<any>(null);
/** 被移出者原来的种子位(供弹窗说明) */
const removeDialogSeed = computed(() => Number(removeDialogItem.value?.seedRank) || 0);

const askRemoveItem = async (item: any) => {
  if (!item || rosterReadonly.value) return;
  // 待落位的人没有座位号,"后续的人顶上 / 留下空位"都无从谈起:直接确认移出,不再弹两个选项
  if (!(Number(item.seedRank) > 0)) {
    try {
      await ElMessageBox.confirm(`把「${item.name || '未命名'}」从待落位区移出?这个人不会再进本赛段名单。`, '移出名单', {
        type: 'warning',
        confirmButtonText: '移出',
        cancelButtonText: '取消'
      });
    } catch {
      return;
    }
    await removeItem(item);
    return;
  }
  removeDialogItem.value = item;
  removeDialogVisible.value = true;
};

/**
 * 执行移出。
 *
 * @param fillGap true=后面的人整体顶上一位;false=原地留空位(后面的不动)。
 *                只对"有座位号"的人生效:没有座位号的人本来就占不到座位。
 */
const removeItem = async (item: any, fillGap = false) => {
  const p = firstRoster.value;
  if (!p || !item) return;
  const seed = Number(item.seedRank) || 0;
  const rest = listItems.value.filter((x) => x !== item);
  listItems.value =
    fillGap && seed > 0
      ? rest.map((x) => {
          const s = Number(x.seedRank) || 0;
          return s > seed ? { ...x, seedRank: s - 1 } : x;
        })
      : rest;
  try {
    if (item.overrideId) {
      await deleteRosterOverride(p.id, item.overrideId);
    } else {
      await addRosterOverride(p.id, { op: 'REMOVE', sourceCompetitorId: item.sourceCompetitorId });
    }
    await persistOrder();
    await loadRosters();
  } catch (e: any) {
    notifyError(e, '移出失败');
    await loadRosters();
  }
};

const confirmRemoveItem = async (fillGap: boolean) => {
  const item = removeDialogItem.value;
  removeDialogVisible.value = false;
  removeDialogItem.value = null;
  await removeItem(item, fillGap);
};

/** 落到座位列表:既支持列表内排序,也支持从待落位区拖进来(插到该位置) */
const onDropToRoster = async (idx: number) => {
  if (rosterReadonly.value) return;
  if (dragHoldingIndex.value != null) {
    await dropHoldingIntoSeat(idx);
    return;
  }
  if (dragIndex.value != null) {
    const from = dragIndex.value;
    onDragEnd();
    if (from === idx) return;
    const arr = [...seatItems.value];
    const [moved] = arr.splice(from, 1);
    arr.splice(idx > from ? idx - 1 : idx, 0, moved);
    // 列表拖动 = 重新排序:按新的行序重新编号(1..N)
    listItems.value = [...arr.map((it, i) => ({ ...it, seedRank: i + 1 })), ...holdingItems.value.map((it) => ({ ...it, seedRank: null }))];
    await persistOrder();
    await loadRosters();
  }
};

/** 把待落位的人放到指定座位号:原占位者回待落位区,其他人的座位号保持不变。 */
const placeHoldingAtSeed = async (moved: any, seed: number) => {
  if (!moved || !(seed > 0)) return;
  listItems.value = listItems.value.map((it) => {
    if (it === moved) return { ...it, seedRank: seed };
    if (Number(it.seedRank) === seed) return { ...it, seedRank: null };
    return it;
  });
  await persistOrder();
  await loadRosters();
};

/** 待落位的人拖到座位列表的某一行 → 落到该行的座位号 */
const dropHoldingIntoSeat = async (idx: number) => {
  const from = dragHoldingIndex.value;
  const moved = from == null ? null : holdingItems.value[from];
  const seed = Number(seatItems.value[idx]?.seedRank) || 0;
  onDragEnd();
  if (!moved || seed <= 0) return;
  await placeHoldingAtSeed(moved, seed);
};

/** 待落位的人拖到空位行 → 直接落到该座位号 */
const onDropHoldingToEmptySlot = async (seed: number) => {
  if (rosterReadonly.value) return;
  const from = dragHoldingIndex.value;
  const moved = from == null ? null : holdingItems.value[from];
  onDragEnd();
  if (!moved) return;
  await placeHoldingAtSeed(moved, Number(seed));
};

/** 名单里的人(座位列表 / 擂台赛出场队列 / 对战树格子)拖回待落位区(没有座位号) */
const onDropToHolding = async () => {
  holdingHover.value = false;
  if (rosterReadonly.value) return;
  const fromSeat = dragIndex.value;
  const fromArena = arenaDragIndex.value;
  const moved = fromSeat != null ? seatItems.value[fromSeat] : fromArena != null ? rosterRows.value[fromArena] : bracketDragItem.value;
  onDragEnd();
  if (!moved) return;
  // 只摘掉被拖这个人的座位号,其他人的座位号保持不变(空出来的座位由后端补成空位行);
  // 否则每拖一个人回待落位区,后面的人都会被整体往前顶一位,打乱已经排好的顺序。
  listItems.value = listItems.value.map((it) => (it === moved ? { ...it, seedRank: null } : it));
  await persistOrder();
  await loadRosters();
};

// ---- 擂台赛出场队列:拖动调整出场顺序(1 号位=擂主) ----
const onArenaDragStart = (idx: number, e?: DragEvent) => {
  arenaDragIndex.value = idx;
  e?.dataTransfer?.setData('text/plain', String(rosterRows.value[idx]?.sourceCompetitorId ?? idx));
  if (e?.dataTransfer) e.dataTransfer.effectAllowed = 'move';
};

/** 落到某一行 = 插到该位置;队列位置即出场顺序,拖动后按 1..N 重新编号 */
const onDropToArena = async (idx: number) => {
  if (dragHoldingIndex.value != null) {
    // 待落位的人放到擂台出场队列的某一位置:落到该位置的座位号,原占位者回待落位区
    const moved = holdingItems.value[dragHoldingIndex.value];
    const seed = Number(rosterRows.value[idx]?.seedRank) || 0;
    onDragEnd();
    if (!moved || seed <= 0) return;
    await placeHoldingAtSeed(moved, seed);
    return;
  }
  const from = arenaDragIndex.value;
  arenaDragIndex.value = null;
  arenaHoverIndex.value = null;
  if (from == null || from === idx || rosterReadonly.value) {
    return;
  }
  const arr = [...rosterRows.value];
  const [moved] = arr.splice(from, 1);
  arr.splice(idx > from ? idx - 1 : idx, 0, moved);
  listItems.value = [...arr.map((it, i) => ({ ...it, seedRank: i + 1 })), ...holdingItems.value.map((it) => ({ ...it, seedRank: null }))];
  await persistOrder();
  await loadRosters();
};

// ---- 加人弹窗:输入姓名(外卡) / 从其他赛段选人 ----
const addDialogVisible = ref(false);
const addMode = ref<'name' | 'stage' | 'player'>('name');
const addName = ref('');
const addStageId = ref('');
const addPersonId = ref('');
const addStagePeople = ref<any[]>([]);
const addStages = ref<any[]>([]);
const addPlayerId = ref('');
const addPlayers = ref<any[]>([]);
const addSubmitting = ref(false);
/** 加入位置:INSERT 插入到指定位置(第 1 位=顶前,末位=加到末尾)/ REPLACE 替换指定的人或空位 */
const addPlacement = ref<'INSERT' | 'REPLACE'>('INSERT');
const addReplaceKey = ref('');

/** 名单项唯一键(源选手或人工新增行) */
const itemKeyOf = (it: any): string => (it?.overrideId != null ? 'o' + it.overrideId : 'c' + (it?.sourceCompetitorId ?? ''));
/** 名单是否已满(满了则不能直接加到末尾) */
const rosterFull = computed(() => {
  const cap = Number(overridePreview.value?.capacity || 0);
  return cap > 0 && listItems.value.length >= cap;
});
const placementBtnClass = (mode: 'INSERT' | 'REPLACE') =>
  addPlacement.value === mode ? 'border-amber-500/60 text-amber-400 bg-amber-500/10' : 'border-neutral-700 text-neutral-400 hover:text-neutral-200';

const openAddDialog = () => {
  addMode.value = 'name';
  addName.value = '';
  addStageId.value = '';
  addPersonId.value = '';
  addStagePeople.value = [];
  addPlayerId.value = '';
  addPlayers.value = [];
  // 默认:满员时插到第 1 位(等于原「顶前」),未满时插到最后一个之前
  const lastItem = listItems.value[listItems.value.length - 1];
  addReplaceKey.value = rosterFull.value ? (listItems.value.length > 0 ? itemKeyOf(listItems.value[0]) : '') : lastItem ? itemKeyOf(lastItem) : '';
  addPlacement.value = 'INSERT';
  addDialogVisible.value = true;
};

/** 本赛事推进链上位于目标赛段之前的赛段(可从中取人) */
const loadAddStages = async () => {
  if (addStages.value.length > 0) return;
  const target = targetStage.value;
  if (!target) return;
  try {
    const resp: any = await listStage({ tournamentId: target.tournamentId, pageNum: 1, pageSize: 200 } as any);
    const all: any[] = resp?.data || [];
    const byId = new Map(all.map((s) => [String(s.id), s]));
    const out: any[] = [];
    const seen = new Set<string>();
    let cur: any = byId.get(String(target.prevStageId));
    while (cur && !seen.has(String(cur.id))) {
      seen.add(String(cur.id));
      out.unshift(cur);
      cur = byId.get(String(cur.prevStageId));
    }
    addStages.value = out;
  } catch {
    addStages.value = [];
  }
};

const switchToStageMode = async () => {
  addMode.value = 'stage';
  await loadAddStages();
};

const loadAddStagePeople = async () => {
  addPersonId.value = '';
  addStagePeople.value = [];
  if (!addStageId.value) return;
  const inList = new Set(listItems.value.map((i) => String(i.sourceCompetitorId)));
  try {
    const resp: any = await listCompetitor({ stageId: addStageId.value, pageNum: 1, pageSize: 1000 } as any);
    const rows: any[] = resp.data?.rows ?? resp.data?.data ?? resp.data ?? [];
    addStagePeople.value = rows
      .filter((c) => !inList.has(String(c.id)) && c.outcomeStatus !== 'WITHDRAWN')
      // 擂台赛只收「晋级进来的人」:淘汰/落选的不出现在可选列表里
      .filter((c) => targetMode.value !== 'ARENA' || c.outcomeStatus === 'ADVANCE')
      .sort((a, b) => (a.finalRank || 9999) - (b.finalRank || 9999));
  } catch {
    addStagePeople.value = [];
  }
};

/** 选手库选项文案:名字 + 是否已有参赛记录(已签到的按参赛记录加,否则按外卡挂到选手档案) */
const playerOptionLabel = (p: any): string => `${p.name}${p.competitorId != null ? ' · 已有参赛记录' : ''}`;

/** 本次选中的选手库选手 */
const selectedPlayer = computed<any>(() => addPlayers.value.find((p) => String(p.id) === String(addPlayerId.value)) || null);

const switchToPlayerMode = async () => {
  addMode.value = 'player';
  await loadAddPlayers();
};

/** 本赛事选手库:去掉已经在名单里的人(按选手ID或他的参赛方ID判重) */
const loadAddPlayers = async () => {
  addPlayerId.value = '';
  const target = targetStage.value;
  if (!target?.tournamentId) {
    addPlayers.value = [];
    return;
  }
  const usedPlayerIds = new Set(listItems.value.filter((i) => i.playerId != null).map((i) => String(i.playerId)));
  const usedCompetitorIds = new Set(listItems.value.filter((i) => i.sourceCompetitorId != null).map((i) => String(i.sourceCompetitorId)));
  try {
    const resp: any = await listPlayer({ tournamentId: target.tournamentId, pageNum: 1, pageSize: 1000 } as any);
    const rows: any[] = resp?.data?.rows ?? resp?.data?.data ?? resp?.data ?? [];
    addPlayers.value = rows
      .filter((p) => !usedPlayerIds.has(String(p.id)))
      .filter((p) => p.competitorId == null || !usedCompetitorIds.has(String(p.competitorId)))
      .filter((p) => p.competitorVo?.outcomeStatus !== 'WITHDRAWN')
      .sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN'));
  } catch {
    addPlayers.value = [];
  }
};

const submitAdd = async () => {
  const p = firstRoster.value;
  if (!p) return;
  if (addMode.value === 'name' && !addName.value.trim()) {
    ElMessage.warning('请填写姓名');
    return;
  }
  if (addMode.value === 'stage' && !addPersonId.value) {
    ElMessage.warning('请选择要加入的人员');
    return;
  }
  if (addMode.value === 'player' && !addPlayerId.value) {
    ElMessage.warning('请选择要加入的选手');
    return;
  }
  if (addPlacement.value === 'REPLACE' && !addReplaceKey.value) {
    ElMessage.warning('请选择要替换的人或空位');
    return;
  }
  if (addPlacement.value === 'INSERT' && !addReplaceKey.value) {
    ElMessage.warning('请选择插到谁前面');
    return;
  }
  addSubmitting.value = true;
  try {
    // 1) 先腾位置
    //  - 替换:移出被替换的那个人(也可以选空位,那就谁都不动)
    //  - 插入且名单已满:挤出最后一名(插到第 1 位时等价于"顶前,最后一名出去")
    const replaceToSlot = addPlacement.value === 'REPLACE' && addReplaceKey.value.startsWith('slot:');
    const replacedIndex = replaceToSlot ? -1 : listItems.value.findIndex((it) => itemKeyOf(it) === addReplaceKey.value);
    const bySeedDesc = [...listItems.value].sort((a, b) => (Number(b.seedRank) || 0) - (Number(a.seedRank) || 0));
    const replacedSeed = replaceToSlot
      ? Number(addReplaceKey.value.slice(5)) || 0
      : replacedIndex >= 0
        ? Number(listItems.value[replacedIndex]?.seedRank) || 0
        : 0;
    // 插入:新人占被选中者的位置,他和后面所有人 +1
    if (addPlacement.value === 'INSERT') {
      if (replacedSeed <= 0) {
        ElMessage.warning('请选择插到谁前面');
        return;
      }
    }
    const outItem =
      addPlacement.value === 'REPLACE'
        ? replacedIndex >= 0
          ? listItems.value[replacedIndex]
          : null
        : addPlacement.value === 'INSERT' && rosterFull.value
          ? bySeedDesc[0]
          : null;
    if (outItem) {
      if (outItem.overrideId) {
        await deleteRosterOverride(p.id, outItem.overrideId);
      } else {
        await addRosterOverride(p.id, { op: 'REMOVE', sourceCompetitorId: outItem.sourceCompetitorId });
      }
    }

    // 2) 加人
    let newOverrideId: string | number | null = null;
    if (addMode.value === 'name') {
      const created: any = await addRosterOverride(p.id, {
        // 不再区分个人/选手:选手就是一个参赛方,选手只是多几个成员
        op: 'ADD_GUEST',
        guestName: addName.value.trim(),
        guestType: 0
      });
      newOverrideId = created?.data?.id ?? created?.id ?? null;
    } else {
      // 从其他赛段选人:直接引用那个参赛方。
      // 从选手库选人:该选手有参赛记录(=签到过)就按参赛方加,否则按外卡挂到他的选手档案上。
      const picked = selectedPlayer.value;
      const sourceCompetitorId = addMode.value === 'stage' ? addPersonId.value : (picked?.competitorId ?? '');
      if (sourceCompetitorId) {
        // 之前被手工移出过的人:加回来 = 撤销那条移出
        const removed = overridesOf(p).filter(
          (o) => o.op === 'REMOVE' && o.sourceCompetitorId != null && String(o.sourceCompetitorId) === String(sourceCompetitorId)
        );
        if (removed.length > 0) {
          // 一次批量撤销,替代逐条 DELETE
          await deleteRosterOverrides(p.id, removed.map((o) => o.id));
        } else {
          const created: any = await addRosterOverride(p.id, {
            op: 'ADD_SOURCE',
            sourceCompetitorId
          });
          newOverrideId = created?.data?.id ?? created?.id ?? null;
        }
      } else {
        const created: any = await addRosterOverride(p.id, {
          op: 'ADD_GUEST',
          playerId: picked?.id ?? null,
          guestName: picked?.name ?? '',
          guestType: 0
        });
        newOverrideId = created?.data?.id ?? created?.id ?? null;
      }
    }

    // 3) 落位:按显式种子位摆放(删人/换位后原来的位置保持空白,不自动顶上)
    await loadRosters();
    {
      const arr = [...listItems.value];
      let idx = newOverrideId != null ? arr.findIndex((it) => String(it.overrideId) === String(newOverrideId)) : -1;
      if (idx < 0) {
        idx = arr.length - 1;
      }
      if (idx >= 0) {
        const newItem = arr[idx];
        const others = arr.filter((_, i) => i !== idx);
        const bySeed = (a: any, b: any) => (Number(a.seedRank) || 0) - (Number(b.seedRank) || 0);
        if (addPlacement.value === 'INSERT') {
          // 插入:新人占目标位,目标位及其后所有人 +1
          const shifted = others.map((it) => {
            const s = Number(it.seedRank) || 0;
            return s >= replacedSeed ? { ...it, seedRank: s + 1 } : it;
          });
          newItem.seedRank = replacedSeed;
          listItems.value = [...shifted, newItem].sort(bySeed);
        } else {
          // 替换:新人顶替被替换者原来的位置
          newItem.seedRank = replacedSeed || Number(newItem.seedRank) || 0;
          listItems.value = [...others, newItem].sort(bySeed);
        }
        await persistOrder();
        await loadRosters();
      }
    }
    ElMessage.success(addPlacement.value === 'INSERT' ? `已插入第 ${replacedSeed} 位,后面的人依次后移` : '已替换入名单');
    addDialogVisible.value = false;
  } catch (e: any) {
    notifyError(e, '加入名单失败');
  } finally {
    addSubmitting.value = false;
  }
};

const entryTagLabel = (tag?: string): string => {
  const map: Record<string, string> = {
    ADVANCE: '晋级',
    REVIVE: '复活',
    GUEST: '外卡'
  };
  return (tag && map[tag]) || tag || '';
};

const refreshOverrideTargets = async () => {
  const p = firstRoster.value;
  if (!p) {
    overridePreview.value = null;
    listItems.value = [];
    return;
  }
  try {
    const resp: any = await getRosterPreview(p.id);
    overridePreview.value = resp?.data || null;
    listItems.value = (resp?.data?.items || []).map((i: any) => ({ ...i }));
  } catch {
    listItems.value = [];
  }
};

const modeLabel = (mode?: string): string => (mode && modeLabelMap[mode]) || mode || '';

const statusLabel = (status?: string): string => {
  const map: Record<string, string> = {
    DRAFT: '规划中',
    GAMING: '进行中',
    SETTLED: '已结束',
    DISCARD: '已取消'
  };
  return (status && map[status]) || status || '';
};

const loadSourceConfig = async () => {
  try {
    const resp: any = await getStage(props.sourceStageId);
    sourceStage.value = resp.data;
    const rule = JSON.parse(sourceStage.value.ruleConfig || '{}');
    const t = rule.transition || {};
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
    await loadRosters();
  } catch (e: any) {
    console.error('保存同分晋级调整失败:', e);
    notifyError(e, '保存失败');
  } finally {
    advSaving.value = false;
  }
};

const loadTargetStage = async () => {
  try {
    const resp: any = await getStage(props.targetStageId);
    targetStage.value = resp.data;
  } catch {
    targetStage.value = null;
  }
};

const nextPowerOfTwo = (v: number) => {
  let p = 1;
  while (p < v) p <<= 1;
  return p;
};

/** 对战树槽位的固定种子序号:SEED 按标准布局表,SEQUENTIAL 相邻配对(2i+1, 2i+2);空位同样编号 */
const slotSeedAt = (pair: any, side: 'left' | 'right') => {
  const i = pair.position - 1;
  if (directPairingMode.value === 'SEED') {
    const bs = nextPowerOfTwo(Math.max(Number(targetStage.value?.teamCountStart) || 0, 2));
    const layout = seedLayout(bs);
    return layout[2 * i + (side === 'left' ? 0 : 1)];
  }
  return 2 * i + (side === 'left' ? 1 : 2);
};

/** 通用两列对战树:按种子号排布为 LEFT/RIGHT 两半区场次(与引擎种子算法一致);
 *  plannedSize 为赛段计划规模(teamCountStart),人数不足时仍铺满完整 bracket,缺位显示空位 */
const computePairs = (list: any[], pairingMode: string, plannedSize = 0) => {
  const sorted = [...list].sort((a, b) => (a.seedRank ?? Number.MAX_SAFE_INTEGER) - (b.seedRank ?? Number.MAX_SAFE_INTEGER));
  const n = sorted.length;
  const size = Math.max(n, plannedSize || 0);
  // 名单还空着时也要按赛段计划铺满空位,让导播提前看到整个对阵框架
  if (n === 0 && (plannedSize || 0) < 2) return [];
  // 按"种子位"取人(不是按数组下标):删人留空位时,空位照常显示为空
  const bySeed = new Map<number, any>();
  sorted.forEach((it) => {
    if (it.seedRank != null) {
      bySeed.set(Number(it.seedRank), it);
    }
  });
  const at = (seedNo: number) => bySeed.get(seedNo) ?? null;
  const out: any[] = [];
  if (pairingMode === 'SEED') {
    const bs = nextPowerOfTwo(Math.max(size, 2));
    const layout = seedLayout(bs);
    const pairCount = Math.max(1, bs / 2);
    const half = Math.ceil(pairCount / 2);
    for (let i = 0; i < pairCount; i++) {
      // SEED 标准种子摆位(与后端 SEED_LAYOUT 一致):第 i 场 = layout[2i] vs layout[2i+1]
      const l = layout[2 * i];
      const r = layout[2 * i + 1];
      out.push({
        position: i + 1,
        zone: i < half ? 'LEFT' : 'RIGHT',
        left: at(l),
        right: at(r),
        leftStatus: at(l) ? 'WINNER' : 'BYE',
        rightStatus: at(r) ? 'WINNER' : 'BYE'
      });
    }
  } else {
    // SEQUENTIAL 同样按向上取整的签表规模铺满(与后端生成/大屏一致,避免非 2 的幂时场次数对不上)
    const pairs = Math.max(1, nextPowerOfTwo(Math.max(size, 2)) / 2);
    const half = Math.ceil(pairs / 2);
    for (let i = 0; i < pairs; i++) {
      out.push({
        position: i + 1,
        zone: i < half ? 'LEFT' : 'RIGHT',
        left: at(2 * i + 1),
        right: at(2 * i + 2),
        leftStatus: at(2 * i + 1) ? 'WINNER' : 'BYE',
        rightStatus: at(2 * i + 2) ? 'WINNER' : 'BYE'
      });
    }
  }
  return out;
};

// ---- 本赛段名单的两列对战树:由名单预览(规则 + 人工调整)驱动 ----
const BRACKET_ZONES = [
  { key: 'LEFT', label: '左半区' },
  { key: 'RIGHT', label: '右半区' }
] as const;
const BRACKET_SIDES = ['left', 'right'] as const;

const bracketPairs = computed<any[]>(() =>
  // 只按已落位的人排对战树:待落位的人还没座位,不能硬塞进签表
  computePairs(seatItems.value, directPairingMode.value, Number(overridePreview.value?.capacity || targetStage.value?.teamCountStart) || 0)
);

const bracketPairsOf = (zone: string) => bracketPairs.value.filter((p) => p.zone === zone);

/** 对战树换位:拖一个位置到另一个位置即交换(名单锁定后不可拖) */
const bracketEditable = computed(() => !rosterReadonly.value);
const bracketDragItem = ref<any>(null);
const bracketDropKey = ref('');

const slotKeyOf = (pair: any, side: 'left' | 'right') => `${pair.zone}-${pair.position}-${side}`;

const onBracketSlotDragStart = (item: any, pair: any, side: 'left' | 'right', e: DragEvent) => {
  if (!bracketEditable.value || !item) {
    e.preventDefault();
    return;
  }
  bracketDragItem.value = item;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(item.sourceCompetitorId ?? item.name ?? ''));
  }
};

const onBracketSlotDragOver = (pair: any, side: 'left' | 'right') => {
  if (bracketDragItem.value || dragHoldingIndex.value != null) {
    bracketDropKey.value = slotKeyOf(pair, side);
  }
};

const onBracketSlotDragLeave = (pair: any, side: 'left' | 'right') => {
  if (bracketDropKey.value === slotKeyOf(pair, side)) {
    bracketDropKey.value = '';
  }
};

const onBracketSlotDragEnd = () => {
  bracketDragItem.value = null;
  bracketDropKey.value = '';
};

/** 搬到指定种子位(原位置空出来,后面的不顶上) */
const moveRosterSeed = async (item: any, seed: number) => {
  if (!item || !Number.isFinite(seed) || seed <= 0) return;
  const cap = Number(overridePreview.value?.capacity || 0);
  if (seed > cap && cap > 0) {
    ElMessage.warning(`第 ${seed} 位超出赛段计划人数(${cap})`);
    return;
  }
  item.seedRank = seed;
  listItems.value = [...listItems.value].sort((x, y) => (Number(x.seedRank) || 0) - (Number(y.seedRank) || 0));
  await persistOrder();
  await loadRosters();
};

/** 放到另一个选手上:交换两人的出场位置;放到空位上:搬过去(原位置空着) */
const onBracketSlotDrop = async (pair: any, side: 'left' | 'right') => {
  if (rosterReadonly.value) return;
  if (dragHoldingIndex.value != null) {
    await dropHoldingIntoBracketSlot(pair, side);
    return;
  }
  const from = bracketDragItem.value;
  const to = pair[side];
  const targetSeed = Number(slotSeedAt(pair, side));
  onBracketSlotDragEnd();
  if (!from) {
    return;
  }
  if (to) {
    if (from === to) return;
    await swapRosterSeeds(from, to);
    return;
  }
  await moveRosterSeed(from, targetSeed);
};

/** 待落位的人拖到对战树格子:落到该格的种子位;原占位者回待落位区,其他人的座位不动。 */
const dropHoldingIntoBracketSlot = async (pair: any, side: 'left' | 'right') => {
  const from = dragHoldingIndex.value;
  const moved = from == null ? null : holdingItems.value[from];
  const seed = Number(slotSeedAt(pair, side)) || 0;
  const cap = Number(overridePreview.value?.capacity || 0);
  onDragEnd();
  if (!moved || seed <= 0) return;
  if (cap > 0 && seed > cap) {
    ElMessage.warning(`第 ${seed} 位超出赛段计划人数(${cap})`);
    return;
  }
  await placeHoldingAtSeed(moved, seed);
};

/** 交换两人的出场位置:交换次序后整份顺序落库(位置即出场次序) */
const swapRosterSeeds = async (a: any, b: any) => {
  if (!a || !b || a.seedRank == null || b.seedRank == null) return;
  const t = a.seedRank;
  a.seedRank = b.seedRank;
  b.seedRank = t;
  listItems.value = [...listItems.value].sort((x, y) => (x.seedRank ?? Number.MAX_SAFE_INTEGER) - (y.seedRank ?? Number.MAX_SAFE_INTEGER));
  await persistOrder();
  await loadRosters();
};

/**
 * 恢复:按来源组规则重建中间层,丢弃本赛段名单上的全部人工调整,
 * 回到"按来源名次自动排列"的原始顺序(位置就是数据,没有单独的 SEED 记录可删)
 */
const clearRosterOrder = async () => {
  const p = firstRoster.value;
  if (!p || rosterReadonly.value) return;
  try {
    await ElMessageBox.confirm('恢复为按来源名次自动排列的原始顺序?这会丢弃本赛段名单上全部人工调整(换位/加人/外卡/剔除)', '恢复顺序', {
      type: 'warning',
      confirmButtonText: '恢复',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    const res: any = await rebuildStageRoster(p.id);
    if (res?.data === false || res === false) {
      ElMessage.warning('当前名单不可重建(赛段已开赛或来源未结算)');
      return;
    }
    ElMessage.success('已恢复自动顺序');
    await loadRosters();
  } catch (e: any) {
    notifyError(e, '恢复失败');
  }
};

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
/** 目标淘汰赛配对模式:与后端 generateMatches 口径一致——
 *  承接上一淘汰赛胜者时强制 SEQUENTIAL(覆盖本赛段配置的 SEED);
 *  否则优先取配置,未配置时按上一赛段推断(海选/排名后=SEED,其余=SEQUENTIAL) */
/** 名单来源赛段(去重) */
const sourceStageIdsOfRoster = computed<string[]>(() => {
  const p = rosters.value[0];
  if (!p) return [];
  return Array.from(
    new Set(
      groupsOfRoster(p)
        .map((g) => g.sourceStageId)
        .filter((id) => id != null)
        .map((id) => String(id))
    )
  );
});

/**
 * 首轮配对方式:只看本赛段自己的配置(与后端生成对阵、预排同一口径)。
 * 未配置 = 顺序相邻;不按来源赛制、也不按链上前一段推断。
 */
const directPairingMode = computed(() => {
  const m = targetRuleConfig.value?.knockout?.pairingMode;
  if (m && String(m).trim()) {
    return String(m).trim().toUpperCase() === 'SEED' ? 'SEED' : 'SEQUENTIAL';
  }
  // 配对方式只看本赛段配置;未配置 = 顺序相邻。不按来源赛制/链上前一段推断。
  return 'SEQUENTIAL';
});
const directGroupCount = computed(() => Number(targetRuleConfig.value?.group?.groupCount) || 1);
const directCircles = computed(() => Number(targetRuleConfig.value?.circles) || 1);

/** 晋级是否已确认:目标赛段名单快照已物化(唯一口径) */
const advancementConfirmed = computed(() => !!overridePreview.value?.applied || rosters.value.some((p) => p.state === 'CONFIRMED'));

/** 确认名单:规则 + 覆盖合并后整单装配为下一赛段参赛行(唯一流转路径) */
const handleConfirmAdvancement = async () => {
  if (!rostersReadyForApply.value) {
    ElMessage.error('仍有来源未就绪(等待来源赛段结算),暂不能确认名单');
    return;
  }
  if (holdingItems.value.length > 0) {
    ElMessage.error(`还有 ${holdingItems.value.length} 人没落位,请先在中间态把人拖到座位上`);
    return;
  }
  confirmingAdvancement.value = true;
  try {
    await applyStageRoster(props.targetStageId, {
      // 来源(含"选哪些人进来")一律在出口方向配置,中间态只做落位与确认,不再传人工点选
      manualSelections: {}
    });
    ElMessage.success(`已确认名单到「${props.targetStageName}」`);
    emit('confirmed', props.targetStageId);
    await loadAll();
  } catch (e: any) {
    console.error('名单装配失败:', e);
    notifyError(e, '名单装配失败');
  } finally {
    confirmingAdvancement.value = false;
  }
};

/** 海选→首个淘汰赛(SEED 模式)的中间态随机交换入口 */
const isSeedKnockoutTransition = computed(() => targetMode.value === 'KNOCKOUT' && directPairingMode.value === 'SEED');

/** 小组赛落位预览:蛇形分组(与 GroupGenerator.snakeSplit 一致) */
const groupPreview = computed(() => {
  if (targetMode.value !== 'GROUP') return [];
  const gc = Math.max(1, directGroupCount.value);
  const groups: any[][] = Array.from({ length: gc }, () => []);
  rosterRows.value.forEach((c, i) => {
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
  const rows = rosterRows.value;
  const n = rows.length;
  const circles = Math.max(1, directCircles.value);
  const base = Math.floor(n / circles);
  const rem = n % circles;
  const out: any[][] = [];
  let cursor = 0;
  for (let c = 0; c < circles; c++) {
    const count = base + (c < rem ? 1 : 0);
    out.push(rows.slice(cursor, cursor + count));
    cursor += count;
  }
  return out;
});

const loadAll = () => {
  loadSourceConfig();
  loadTargetStage();
  loadRosters();
};

// 切换转场(来源/目标赛段变化)时整体刷新
watch([() => props.sourceStageId, () => props.targetStageId], () => {
  rosterItems.value = null;
  loadAll();
});

const route = useRoute();
const transitionTid = computed(() => {
  const id = route.query.id ?? route.query.tournamentId;
  return id && !Array.isArray(id) ? id : null;
});

/** 赛事事件回调:与本转场(source/target)相关或赛段级事件时刷新,晋级名单/预排实时同步 */
const handleTournamentEvent = (data: any) => {
  if (!data) {
    loadAll();
    return;
  }
  const sid = data.stageId != null ? String(data.stageId) : null;
  const relevant =
    data.type === 'stage' ||
    (sourceStage.value != null && sid === String(sourceStage.value.id)) ||
    (targetStage.value != null && sid === String(targetStage.value.id));
  if (relevant) {
    loadAll();
  }
};

onMounted(() => {
  loadAll();
  if (transitionTid.value != null) {
    subscribeTournamentEvents(transitionTid.value, handleTournamentEvent);
  }
});

onUnmounted(() => {
  if (transitionTid.value != null) {
    unsubscribeTournamentEvents(transitionTid.value, handleTournamentEvent);
  }
});
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
