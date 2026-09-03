// stores/directorStore.js
import { defineStore } from 'pinia';
import { ref, computed, shallowRef } from 'vue';
import { v4 as uuidv4 } from 'uuid';
import { listVisScene, addVisScene, updateVisScene, delVisScene } from '@/api/game/visScene';
import { listVisWidget, addVisWidget, updateVisWidget, delVisWidget } from '@/api/game/visWidget';
import { moveWidgetLayer, reorderWidgets } from '@/api/game/visWidget';
import { projectSceneToScreen, clearScreenScene } from '@/api/game/screenControl';
import { getTournamentAuthKey } from '@/api/game/tournament';
import { subscribeScreenControl, unsubscribeScreenControl, unsubscribeAllScreens } from '@/utils/screenSse';

export const useDirectorStore = defineStore('director', () => {
  // ============================================
  // 核心状态 - 只管理当前赛事的数据
  // ============================================

  // 常量：每个赛事最多支持的屏幕数量
  const MAX_SCREENS_PER_TOURNAMENT = 5;
  // 常量：每个赛事最多支持的场景数量
  const MAX_SCENES_PER_TOURNAMENT = 10;
  // 常量：每个场景最多支持的组件数量
  const MAX_WIDGETS_PER_SCENE = 10;

  // 屏幕 - 不接入 API
  // 从 history state 恢复 screens，或创建默认屏幕
  const getStateScreens = () => {
    try {
      const stateScreens = history.state?.screens;
      if (stateScreens && Array.isArray(stateScreens) && stateScreens.length > 0) {
        console.log('✅ 从 history.state 恢复 screens:', stateScreens.length, '个屏幕');
        return stateScreens;
      }
    } catch (e) {
      console.warn('读取 history.state 失败:', e);
    }
    // 创建默认屏幕
    console.log('📝 history.state 中无 screens，创建默认屏幕');
    const defaultScreens = [
      {
        id: uuidv4(),
        name: '屏幕 1',
        status: 'ONLINE',
        currentSceneId: null
      }
    ];
    // 同步到 history.state
    try {
      history.replaceState({ screens: defaultScreens }, '');
    } catch (e) {
      console.error('同步默认屏幕到 history.state 失败:', e);
    }
    return defaultScreens;
  };

  const screens = ref(getStateScreens());

  // 场景 - 从 API 加载
  const scenes = ref([]);

  // 缩略图缓存 - 使用 shallowRef 存储,避免深度响应式
  const thumbnailCache = shallowRef(Object.create(null));

  // 当前选中的场景 ID
  const currentSceneId = ref(null);

  // 当前选中的组件 ID
  const selectedWidgetId = ref(null);
  /** 组件选中来源:'canvas'=画布点击 / 'layer'=图层列表 / ''=其他(不自动切换选项卡) */
  const widgetSelectSource = ref('');

  // 加载状态
  const loading = ref(false);

  // 当前赛事 ID
  let currentTournamentId = null;

  // 赛事导播专用凭证(屏幕控制 SSE/REST 均使用该凭证,不携带管理员 JWT)
  const directorAuthKey = ref('');

  // ============================================
  // 计算属性
  // ============================================

  const currentScene = computed(() => {
    if (!currentSceneId.value) return null;
    return scenes.value.find((s) => s.id == currentSceneId.value);
  });

  const selectedWidget = computed(() => {
    if (!currentScene.value || !selectedWidgetId.value) return null;
    return currentScene.value.widgets?.find((w) => w.id == selectedWidgetId.value);
  });

  // ============================================
  // 撤回/重做 (Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y)
  // ============================================
  const undoStack = ref([]);
  const redoStack = ref([]);
  const canUndo = computed(() => undoStack.value.length > 0);
  const canRedo = computed(() => redoStack.value.length > 0);
  const undoLabel = computed(() => undoStack.value[undoStack.value.length - 1]?.label || '');
  const redoLabel = computed(() => redoStack.value[redoStack.value.length - 1]?.label || '');
  const MAX_HISTORY = 60;

  // 删除后重建可能产生新 id:旧 id -> 当前实际 id 的映射,撤回/重做时统一解析
  const widgetIdMap = new Map();
  const sceneIdMap = new Map();
  // 拖拽/缩放开始时记录起始快照:拖动过程中 SceneRenderer 会直接改本地元素,
  // 若在 mouseup 才取 before,取到的已经是终点值,撤销会变成空操作
  const widgetTransformStart = new Map();
  const resolveWidgetId = (id) => widgetIdMap.get(String(id)) || String(id);
  const resolveSceneId = (id) => sceneIdMap.get(String(id)) || String(id);
  const registerWidgetId = (oldId, newId) => {
    if (oldId == null || newId == null || String(oldId) === String(newId)) return;
    widgetIdMap.set(String(oldId), String(newId));
  };
  const registerSceneId = (oldId, newId) => {
    if (oldId == null || newId == null || String(oldId) === String(newId)) return;
    sceneIdMap.set(String(oldId), String(newId));
  };

  const snapshotWidget = (w, sceneId) => {
    if (!w) return null;
    return {
      id: w.id,
      sceneId: sceneId ?? currentScene.value?.id ?? null,
      name: w.name ?? '',
      type: w.type,
      x: Number(w.x) || 0,
      y: Number(w.y) || 0,
      w: Number(w.w) || 0,
      h: Number(w.h) || 0,
      z: Number(w.z) || 1,
      visible: w.visible === 1 || w.visible === true,
      locked: w.locked === 1 || w.locked === true,
      layoutConfig: w.layoutConfig || '{}',
      dataConfig: w.dataConfig || '{}',
      renderConfig: w.renderConfig || '{}'
    };
  };

  const snapshotScene = (s) => {
    if (!s) return null;
    const sid = s.id;
    return {
      id: sid,
      name: s.name ?? '',
      width: Number(s.width) || 1920,
      height: Number(s.height) || 1080,
      bgColor: s.bgColor || '#000000',
      format: s.format || 'CUSTOM',
      isTemplate: s.isTemplate || 0,
      sortOrder: Number(s.sortOrder) ?? scenes.value.length,
      widgets: (s.widgets || []).map((w) => snapshotWidget(w, sid))
    };
  };

  /** 两个组件快照是否等价(用于跳过未产生实际变化的动作) */
  const widgetSnapshotsEqual = (a, b) => {
    if (!a || !b) return false;
    return ['name', 'x', 'y', 'w', 'h', 'z', 'visible', 'locked', 'layoutConfig', 'dataConfig', 'renderConfig'].every(
      (k) => String(a[k]) === String(b[k])
    );
  };

  const findSceneById = (sid) => scenes.value.find((s) => String(s.id) === String(sid)) || null;
  const findWidgetById = (wid) => {
    for (const scene of scenes.value) {
      const w = (scene.widgets || []).find((x) => String(x.id) === String(resolveWidgetId(wid)));
      if (w) return w;
    }
    return null;
  };

  /** 记录一条历史(undo 回到动作前,redo 重放动作后);不做时间合并,每次操作独立成步 */
  function commitHistory(label, key, undo, redo) {
    undoStack.value.push({ label, key, undo, redo });
    if (undoStack.value.length > MAX_HISTORY) undoStack.value.shift();
    redoStack.value = [];
  }

  /** 撤销上一步;失败时把记录放回栈并抛出(由 UI 提示) */
  async function undo() {
    const entry = undoStack.value.pop();
    if (!entry) return false;
    try {
      await entry.undo();
      redoStack.value.push(entry);
      return true;
    } catch (error) {
      undoStack.value.push(entry);
      console.error('❌ 撤销失败:', error);
      throw error;
    }
  }

  /** 重做 */
  async function redo() {
    const entry = redoStack.value.pop();
    if (!entry) return false;
    try {
      await entry.redo();
      undoStack.value.push(entry);
      return true;
    } catch (error) {
      redoStack.value.push(entry);
      console.error('❌ 重做失败:', error);
      throw error;
    }
  }

  function clearHistory() {
    undoStack.value = [];
    redoStack.value = [];
    widgetIdMap.clear();
    sceneIdMap.clear();
    widgetTransformStart.clear();
  }

  // ---- 原始(不带历史记录)底层操作,供动作与撤回共用 ----
  async function deleteWidgetRaw(id) {
    const actualId = resolveWidgetId(id);
    if (!currentTournamentId) return;
    widgetTransformStart.delete(String(actualId));
    await delVisWidget(actualId);
    for (const scene of scenes.value) {
      const index = (scene.widgets || []).findIndex((w) => String(w.id) === String(actualId));
      if (index > -1) {
        scene.widgets.splice(index, 1);
        if (selectedWidgetId.value === String(actualId)) selectedWidgetId.value = null;
        break;
      }
    }
  }

  /** 删除场景前先清理其组件(后端删场景不级联) */
  async function deleteWidgetsOfSceneRaw(sceneId) {
    const scene = findSceneById(resolveSceneId(sceneId));
    if (!scene) return;
    for (const w of [...(scene.widgets || [])]) {
      await delVisWidget(w.id);
    }
  }

  /** 用快照覆盖一个已存在组件(处理锁定/解锁顺序) */
  async function updateWidgetRaw(id, snap) {
    const actualId = resolveWidgetId(id);
    const current = findWidgetById(actualId);
    const sceneId = resolveSceneId(snap.sceneId);
    if (!sceneId) throw new Error('组件所在场景不存在');
    const base = {
      id: actualId,
      tournamentId: currentTournamentId,
      sceneId,
      name: snap.name,
      type: snap.type,
      x: snap.x,
      y: snap.y,
      w: snap.w,
      h: snap.h,
      zIndex: snap.z,
      visible: snap.visible ? 1 : 0,
      locked: snap.locked ? 1 : 0,
      layoutConfig: snap.layoutConfig,
      dataConfig: snap.dataConfig,
      renderConfig: snap.renderConfig
    };
    if (current?.locked && snap.locked) {
      // 已锁定且目标仍锁定:先随解锁一起恢复字段,再单独锁回
      await updateVisWidget({ ...base, locked: 0 });
      await updateVisWidget({ id: actualId, tournamentId: currentTournamentId, sceneId, locked: 1 });
    } else {
      await updateVisWidget(base);
    }
    if (current) {
      Object.assign(current, {
        name: snap.name,
        x: snap.x,
        y: snap.y,
        w: snap.w,
        h: snap.h,
        z: snap.z,
        visible: snap.visible,
        locked: snap.locked,
        layoutConfig: snap.layoutConfig,
        dataConfig: snap.dataConfig,
        renderConfig: snap.renderConfig
      });
    }
  }

  /** 重建一个被删除的组件(服务端可能分配新 id,登记别名) */
  async function restoreWidgetRaw(snap, sceneIdOverride) {
    const sceneId = resolveSceneId(sceneIdOverride ?? snap.sceneId);
    const scene = findSceneById(sceneId);
    if (!scene) throw new Error('组件所在场景不存在,无法恢复');
    const payload = {
      tournamentId: currentTournamentId,
      sceneId,
      name: snap.name,
      type: snap.type,
      x: snap.x,
      y: snap.y,
      w: snap.w,
      h: snap.h,
      visible: snap.visible ? 1 : 0,
      locked: 0, // 先以未锁定插入,便于恢复 z;随后再锁回
      layoutConfig: snap.layoutConfig,
      dataConfig: snap.dataConfig,
      renderConfig: snap.renderConfig
    };
    const response = await addVisWidget(payload);
    const data = response.data;
    if (!data) throw new Error('恢复组件失败');
    const newId = String(data.id);
    const widget = {
      id: newId,
      name: data.name,
      type: data.type,
      x: Number(data.x ?? snap.x),
      y: Number(data.y ?? snap.y),
      w: Number(data.w ?? snap.w),
      h: Number(data.h ?? snap.h),
      z: Number(data.zIndex ?? 1),
      visible: data.visible === 1,
      locked: data.locked === 1,
      layoutConfig: data.layoutConfig || snap.layoutConfig,
      dataConfig: data.dataConfig || snap.dataConfig,
      renderConfig: data.renderConfig || snap.renderConfig
    };
    scene.widgets.push(widget);
    registerWidgetId(snap.id, newId);
    if (Number(widget.z) !== Number(snap.z)) {
      await updateVisWidget({
        id: newId,
        tournamentId: currentTournamentId,
        sceneId,
        zIndex: snap.z
      });
      widget.z = snap.z;
    }
    if (snap.locked) {
      await updateVisWidget({
        id: newId,
        tournamentId: currentTournamentId,
        sceneId,
        locked: 1
      });
      widget.locked = true;
    }
    return newId;
  }

  /** 应用一个组件快照(空 = 删除) */
  async function applyWidgetSnapshot(snap) {
    if (!snap) {
      throw new Error('缺少组件快照');
    }
    const exists = findWidgetById(snap.id);
    if (exists) {
      await updateWidgetRaw(snap.id, snap);
    } else {
      await restoreWidgetRaw(snap);
    }
  }

  async function deleteSceneRaw(id) {
    const actualId = resolveSceneId(id);
    if (!currentTournamentId) return;
    await deleteWidgetsOfSceneRaw(actualId);
    await delVisScene(actualId);
    const index = scenes.value.findIndex((s) => String(s.id) === String(actualId));
    if (index > -1) {
      scenes.value.splice(index, 1);
      if (String(currentSceneId.value) === String(actualId)) {
        currentSceneId.value = scenes.value.length > 0 ? scenes.value[0].id : null;
      }
    }
  }

  /** 重建被删除的场景及其组件 */
  async function restoreSceneRaw(snap) {
    const payload = {
      id: snap.id,
      tournamentId: currentTournamentId,
      name: snap.name,
      designWidth: snap.width,
      designHeight: snap.height,
      bgColor: snap.bgColor,
      format: snap.format,
      isTemplate: snap.isTemplate || 0,
      sortOrder: snap.sortOrder ?? scenes.value.length
    };
    const response = await addVisScene(payload);
    const data = response.data;
    if (!data) throw new Error('恢复场景失败');
    const newSceneId = String(data.id);
    const scene = {
      id: newSceneId,
      name: data.name ?? snap.name,
      width: Number(data.designWidth ?? snap.width),
      height: Number(data.designHeight ?? snap.height),
      bgColor: data.bgColor ?? snap.bgColor,
      format: data.format ?? snap.format,
      isTemplate: data.isTemplate ?? 0,
      sortOrder: snap.sortOrder ?? scenes.value.length,
      widgets: [],
      thumbnailData: null
    };
    scenes.value.push(scene);
    registerSceneId(snap.id, newSceneId);
    // 删除前投射到该场景的屏幕,重建后指回新的场景 id
    for (const screen of screens.value) {
      if (screen.currentSceneId != null && String(screen.currentSceneId) === String(snap.id)) {
        screen.currentSceneId = newSceneId;
      }
    }
    for (const w of snap.widgets || []) {
      await restoreWidgetRaw(w, newSceneId);
    }
    return newSceneId;
  }

  // ---- 图层顺序:以「整场 z 顺序」作为快照,上移/下移/拖动共用 reorder API 撤销重做 ----
  const sceneZOrderIds = (scene) => {
    if (!scene) return [];
    return [...scene.widgets].sort((a, b) => (Number(b.z) || 1) - (Number(a.z) || 1)).map((w) => w.id);
  };

  /** 按指定顺序(上→下)调后端重排并更新本地 z(0 历史记录,供撤销/重做使用) */
  async function applyLayerOrderRaw(sceneId, ids) {
    const actualSceneId = resolveSceneId(sceneId);
    const scene = findSceneById(actualSceneId);
    if (!scene) return;
    const actualIds = (ids || []).map((id) => resolveWidgetId(id));
    await reorderWidgets(actualSceneId, actualIds);
    const n = actualIds.length;
    actualIds.forEach((aid, i) => {
      const w = scene.widgets.find((x) => String(x.id) === String(aid));
      if (w) w.z = n - i;
    });
    scene.widgets = [...scene.widgets];
  }

  // ============================================
  // 场景管理（接入 API）
  // ============================================

  // 从后端加载场景列表
  async function loadScenes(tournamentId) {
    if (!tournamentId) {
      console.warn('⚠️ tournamentId 为空，无法加载场景');
      return;
    }

    currentTournamentId = tournamentId;
    clearHistory();
    loading.value = true;

    try {
      console.log(`📂 开始加载赛事场景: ${tournamentId}`);

      // 查询该赛事的所有场景
      const response = await listVisScene({ tournamentId });

      if (response.data && response.data.length > 0) {
        // 转换为前端格式
        scenes.value = await Promise.all(
          response.data.map(async (scene) => {
            // 加载每个场景的 widgets
            const widgetsResponse = await listVisWidget({
              tournamentId,
              sceneId: scene.id
            });

            return {
              id: scene.id,
              name: scene.name,
              width: scene.designWidth || 1920,
              height: scene.designHeight || 1080,
              bgColor: scene.bgColor || '#000000',
              format: scene.format || 'DEFAULT',
              backgroundConfig: scene.backgroundConfig,
              isTemplate: scene.isTemplate,
              templateCategory: scene.templateCategory,
              sortOrder: scene.sortOrder,
              remark: scene.remark,
              widgets: (widgetsResponse.data || []).map((widget) => ({
                id: widget.id,
                name: widget.name,
                type: widget.type,
                x: widget.x || 0,
                y: widget.y || 0,
                w: widget.w || 100,
                h: widget.h || 100,
                z: widget.zIndex || 1,
                visible: widget.visible === 1,
                locked: widget.locked === 1,
                layoutConfig: widget.layoutConfig,
                dataConfig: widget.dataConfig,
                renderConfig: widget.renderConfig,
                remark: widget.remark
              })),
              thumbnailData: null
            };
          })
        );

        // 按 sortOrder 排序
        scenes.value.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));

        // 默认选中第一个场景
        if (scenes.value.length > 0 && !currentSceneId.value) {
          currentSceneId.value = scenes.value[0].id;
        }

        console.log(`✅ 成功加载 ${scenes.value.length} 个场景`);
      } else {
        scenes.value = [];
        currentSceneId.value = null;
        console.log('📭 该赛事暂无场景');
      }

      // 获取赛事导播专用凭证(屏幕控制通道鉴权用)
      await loadDirectorAuthKey();
      // 场景加载完成后，初始化所有屏幕的 SSE 订阅
      initializeScreensSubscriptions();
    } catch (error) {
      console.error('❌ 加载场景失败:', error);
      scenes.value = [];
      currentSceneId.value = null;
    } finally {
      loading.value = false;
    }
  }

  // 添加场景（调用 API）
  async function addScene(sceneData) {
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法添加场景');
      return;
    }

    // 检查场景数量限制
    if (scenes.value.length >= MAX_SCENES_PER_TOURNAMENT) {
      console.warn(`❌ 已达到最大场景数量限制 (${MAX_SCENES_PER_TOURNAMENT} 个)`);
      throw new Error(`每个赛事最多只能添加 ${MAX_SCENES_PER_TOURNAMENT} 个场景`);
    }

    try {
      console.log(`➕ 正在添加场景: ${sceneData.name}`);

      // 调用 API
      const response = await addVisScene({
        tournamentId: currentTournamentId,
        name: sceneData.name,
        designWidth: sceneData.width,
        designHeight: sceneData.height,
        bgColor: sceneData.bgColor || '#000000',
        format: sceneData.format || 'CUSTOM',
        isTemplate: 0,
        sortOrder: scenes.value.length
      });

      if (response.data) {
        // 后端返回的是完整的场景对象，需要提取 id
        const sceneId = response.data.id;
        const newScene = {
          id: sceneId,
          name: sceneData.name,
          width: sceneData.width,
          height: sceneData.height,
          bgColor: sceneData.bgColor || '#000000',
          format: sceneData.format || 'CUSTOM',
          isTemplate: 0,
          templateCategory: null,
          sortOrder: scenes.value.length,
          widgets: [],
          thumbnailData: null
        };

        scenes.value.push(newScene);
        const createdSceneSnap = snapshotScene(newScene);
        commitHistory(
          '新建场景',
          `scene-add-${sceneId}`,
          async () => {
            await deleteSceneRaw(sceneId);
          },
          async () => {
            await restoreSceneRaw(createdSceneSnap);
          }
        );
        console.log(`✅ 成功添加场景 [${sceneData.name}]`);
        return newScene;
      }
    } catch (error) {
      console.error('❌ 添加场景失败:', error);
      throw error;
    }
  }

  // 更新场景（调用 API）
  async function updateScene(sceneId, sceneData) {
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法更新场景');
      return;
    }

    try {
      console.log(`🔄 正在更新场景: ${sceneId}`);

      // 调用 API
      const response = await updateVisScene({
        id: sceneId,
        tournamentId: currentTournamentId,
        name: sceneData.name,
        designWidth: sceneData.width,
        designHeight: sceneData.height,
        bgColor: sceneData.bgColor,
        format: sceneData.format,
        backgroundConfig: sceneData.backgroundConfig
      });

      if (response.data) {
        const index = scenes.value.findIndex((s) => s.id == sceneId);
        if (index > -1) {
          // 更新本地数据（保留 widgets）
          const { widgets, thumbnailData, ...updateData } = sceneData;
          scenes.value[index] = {
            ...scenes.value[index],
            ...updateData
          };
        }
        console.log(`✅ 成功更新场景 [${sceneId}]`);
        return scenes.value[index];
      }
    } catch (error) {
      console.error('❌ 更新场景失败:', error);
      throw error;
    }
  }

  // 删除场景（调用 API）
  async function deleteScene(sceneId) {
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法删除场景');
      return;
    }

    try {
      console.log(`🗑️ 正在删除场景: ${sceneId}`);
      await deleteWidgetsOfSceneRaw(sceneId);
      await delVisScene(sceneId);

      const index = scenes.value.findIndex((s) => s.id == sceneId);
      if (index > -1) {
        const deletedScene = scenes.value[index];
        scenes.value.splice(index, 1);
        const deletedSceneSnap = snapshotScene(deletedScene);
        commitHistory(
          '删除场景',
          `scene-del-${sceneId}`,
          async () => {
            await restoreSceneRaw(deletedSceneSnap);
          },
          async () => {
            await deleteSceneRaw(deletedScene.id);
          }
        );

        // 如果删除的是当前场景，切换到其他场景
        if (currentSceneId.value == sceneId) {
          if (scenes.value.length > 0) {
            currentSceneId.value = scenes.value[0].id;
          } else {
            currentSceneId.value = null;
          }
        }

        console.log(`✅ 成功删除场景 [${deletedScene.name}]`);
      }
    } catch (error) {
      console.error('❌ 删除场景失败:', error);
      throw error;
    }
  }

  // 切换场景
  function switchScene(id) {
    currentSceneId.value = id;
    selectedWidgetId.value = null;
  }

  // 更新场景缩略图
  function updateSceneThumbnail(sceneId, thumbnailData) {
    // 只在缩略图数据真正改变时才更新到缓存
    if (thumbnailCache.value[sceneId] !== thumbnailData) {
      // 创建新的缓存对象以触发 shallowRef 的响应式更新
      thumbnailCache.value = {
        ...thumbnailCache.value,
        [sceneId]: thumbnailData
      };
      console.log(`[updateSceneThumbnail] 场景 ${sceneId} 缩略图已更新到缓存`);
    }
    // TODO: 可选 - 将缩略图保存到后端
  }

  // 获取场景缩略图 (优先从缓存读取)
  function getSceneThumbnail(sceneId) {
    return thumbnailCache.value[sceneId] || null;
  }

  function getSceneName(id) {
    const s = scenes.value.find((scene) => scene.id == id);
    return s ? s.name : 'Unknown';
  }

  // ============================================
  // 屏幕管理（本地管理，不接入 API）
  // ============================================

  // 获取赛事导播专用凭证
  async function loadDirectorAuthKey() {
    if (!currentTournamentId) {
      directorAuthKey.value = '';
      return '';
    }
    try {
      const resp = await getTournamentAuthKey(currentTournamentId);
      directorAuthKey.value = resp?.data?.authKey ?? resp?.data ?? '';
      if (!directorAuthKey.value) {
        console.warn('⚠️ 获取赛事导播凭证为空,屏幕控制功能不可用');
      }
      return directorAuthKey.value;
    } catch (error) {
      console.warn('⚠️ 获取赛事导播凭证失败,屏幕控制功能不可用:', error);
      directorAuthKey.value = '';
      return '';
    }
  }

  // 订阅单个屏幕的 SSE 控制通道
  function subscribeToScreenSSE(screenId) {
    if (!currentTournamentId) {
      console.warn(`[SSE] 未设置 tournamentId，无法订阅屏幕 ${screenId}`);
      return;
    }

    if (!directorAuthKey.value) {
      console.warn(`[SSE] 缺少赛事导播凭证,跳过屏幕 ${screenId} 控制通道订阅`);
      return;
    }

    subscribeScreenControl(screenId, currentTournamentId, directorAuthKey.value, (message) => {
      // 处理 SSE 消息
      handleScreenSSEMessage(screenId, message);
    });
  }

  // 初始化所有屏幕的 SSE 订阅
  function initializeScreensSubscriptions() {
    console.log(`[SSE] 初始化所有屏幕的 SSE 订阅，共 ${screens.value.length} 个屏幕`);
    screens.value.forEach((screen) => {
      subscribeToScreenSSE(screen.id);
    });
  }

  // 处理屏幕 SSE 消息
  function handleScreenSSEMessage(screenId, message) {
    console.log(`[SSE] 处理屏幕 ${screenId} 的消息:`, message);

    // 根据消息类型处理
    switch (message.type) {
      case 'SCREEN_ONLINE':
        // 屏幕上线
        setScreenOnline(screenId);
        break;
      case 'SCREEN_OFFLINE':
        // 屏幕离线
        setScreenOffline(screenId);
        break;
      case 'PROJECTION_CONFIRMED':
        // 投射确认
        console.log(`[SSE] 屏幕 ${screenId} 投射已确认`);
        break;
      default:
        console.warn(`[SSE] 未知消息类型: ${message.type}`);
    }
  }

  // 同步 screens 到 history state（使用 replaceState 避免创建历史记录）
  function syncScreensToState() {
    try {
      const currentState = history.state || {};
      const newState = {
        ...currentState,
        screens: JSON.parse(JSON.stringify(screens.value)) // 深拷贝避免引用问题
      };
      history.replaceState(newState, '');
      console.log('🔄 已同步 screens 到 history.state');
    } catch (e) {
      console.error('同步 screens 到 history.state 失败:', e);
    }
  }

  // 添加屏幕（本地）
  function addScreen() {
    // 检查屏幕数量限制
    if (screens.value.length >= MAX_SCREENS_PER_TOURNAMENT) {
      console.warn(`❌ 已达到最大屏幕数量限制 (${MAX_SCREENS_PER_TOURNAMENT} 块)`);
      throw new Error(`每个赛事最多只能添加 ${MAX_SCREENS_PER_TOURNAMENT} 块屏幕`);
    }

    const newId = uuidv4(); // 使用 UUID 替代 screen_${Date.now()}
    const screenNumber = screens.value.length + 1;
    const newScreen = {
      id: newId,
      name: `新屏幕 ${screenNumber}`,
      status: 'ONLINE',
      currentSceneId: null
    };
    screens.value.push(newScreen);
    syncScreensToState(); // 同步到 history state
    console.log(`➕ 已添加新屏幕 [${screenNumber}] (ID: ${newId})`);

    // 订阅该屏幕的 SSE 控制通道
    subscribeToScreenSSE(newId);
  }

  // 删除屏幕（本地）
  async function deleteScreen(screenId) {
    const screen = screens.value.find((s) => s.id === screenId);
    if (!screen) {
      console.warn(`❌ 屏幕 ${screenId} 不存在`);
      return;
    }

    try {
      // 如果屏幕有投射的场景，先清除投射（使用 projectSceneToScreen 并传递 sceneId: null）
      if (screen.currentSceneId) {
        if (!directorAuthKey.value) {
          await loadDirectorAuthKey();
        }
        console.log(`📤 删除屏幕前先清除投射: screenId=${screenId}, sceneId=null`);
        if (directorAuthKey.value) {
          await projectSceneToScreen(screenId, null, currentTournamentId, directorAuthKey.value);
        }
        console.log(`✅ 已清除屏幕 [${screen.name}] 的投射`);
      }

      // 取消该屏幕的 SSE 订阅
      unsubscribeScreenControl(screenId);

      // 删除屏幕
      const index = screens.value.findIndex((s) => s.id === screenId);
      if (index > -1) {
        screens.value.splice(index, 1);
        syncScreensToState(); // 同步到 history state
        console.log(`🗑️ 已删除屏幕 [${screen.name}]`);
      }
    } catch (error) {
      console.error(`❌ 删除屏幕失败:`, error);
      throw error;
    }
  }

  // 投射场景到屏幕
  async function projectScene(screenId, sceneId) {
    const screen = screens.value.find((s) => s.id === screenId);
    if (!screen) {
      console.warn(`❌ 屏幕 ${screenId} 不存在`);
      return;
    }

    try {
      if (!directorAuthKey.value) {
        await loadDirectorAuthKey();
      }
      if (!directorAuthKey.value) {
        throw new Error('缺少赛事导播凭证,无法投射场景');
      }
      // 调用后端 API
      console.log(`📤 向后端发送投射请求: screenId=${screenId}, sceneId=${sceneId}`);
      await projectSceneToScreen(screenId, sceneId, currentTournamentId, directorAuthKey.value);

      // 更新本地状态
      screen.currentSceneId = sceneId;
      syncScreensToState(); // 同步到 history state

      const statusText = screen.status === 'OFFLINE' ? '⏳ 预配置' : '🚀 已投射';
      console.log(`${statusText} 将场景 [${sceneId}] 投射到屏幕 [${screen.name}]${screen.status === 'OFFLINE' ? ' (等待上线)' : ''}`);

      // 如果屏幕在线，后端会通过 SSE 通知大屏端
      if (screen.status === 'ONLINE') {
        console.log(`  → 后端将通过 SSE 通知屏幕 [${screen.name}]`);
      }
    } catch (error) {
      console.error(`❌ 投射场景失败:`, error);
      throw error;
    }
  }

  // 清除屏幕投射
  async function clearScreenProjection(screenId) {
    const screen = screens.value.find((s) => s.id === screenId);
    if (!screen) {
      console.warn(`❌ 屏幕 ${screenId} 不存在`);
      return;
    }

    try {
      if (!directorAuthKey.value) {
        await loadDirectorAuthKey();
      }
      if (!directorAuthKey.value) {
        throw new Error('缺少赛事导播凭证,无法清除投射');
      }
      // 调用后端 API
      console.log(`📤 向后端发送清除投射请求: screenId=${screenId}`);
      await clearScreenScene(screenId, currentTournamentId, directorAuthKey.value);

      // 更新本地状态
      screen.currentSceneId = null;
      syncScreensToState(); // 同步到 history state
      console.log(`✅ 已清除屏幕 [${screen.name}] 的投射`);
    } catch (error) {
      console.error(`❌ 清除投射失败:`, error);
      throw error;
    }
  }

  function getScreenName(screenId) {
    const screen = screens.value.find((s) => s.id === screenId);
    return screen ? screen.name : 'Unknown';
  }

  // 设置屏幕在线（由 WebSocket 连接触发，不同步到 history state）
  function setScreenOnline(screenId) {
    const screen = screens.value.find((s) => s.id === screenId);
    if (screen) {
      const wasOffline = screen.status === 'OFFLINE';
      screen.status = 'ONLINE';
      // 注意：不同步到 history state，因为 online/offline 是临时的 WebSocket 连接状态

      console.log(`✅ 屏幕 [${screen.name}] 已上线`);

      if (wasOffline && screen.currentSceneId) {
        console.log(`  → 自动恢复投射场景 [${screen.currentSceneId}]`);
      }
    }
  }

  // 设置屏幕离线（由 WebSocket 断开触发，不同步到 history state）
  function setScreenOffline(screenId) {
    const screen = screens.value.find((s) => s.id === screenId);
    if (screen) {
      screen.status = 'OFFLINE';
      // 注意：不同步到 history state，因为 online/offline 是临时的 WebSocket 连接状态
      console.log(`⚫ 屏幕 [${screen.name}] 已离线${screen.currentSceneId ? ' (投射配置已保留)' : ''}`);
    }
  }

  // 关闭屏幕（手动控制，不同步到 history state）
  function turnOffScreen(screenId) {
    const screen = screens.value.find((s) => s.id === screenId);
    if (screen) {
      screen.status = 'OFFLINE';
      // 注意：不同步到 history state，因为 online/offline 是临时的 WebSocket 连接状态
      console.log(`⚫ 屏幕 [${screen.name}] 已离线${screen.currentSceneId ? ' (投射配置已保留)' : ''}`);
    }
  }

  // ============================================
  // 组件管理（接入 API）
  // ============================================

  // 添加组件（调用 API）
  async function addWidget(type) {
    if (!currentScene.value) return;
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法添加组件');
      return;
    }

    // 检查组件数量限制
    if (currentScene.value.widgets.length >= MAX_WIDGETS_PER_SCENE) {
      console.warn(`❌ 该场景已达到最大组件数量限制 (${MAX_WIDGETS_PER_SCENE} 个)`);
      throw new Error(`每个场景最多只能添加 ${MAX_WIDGETS_PER_SCENE} 个组件`);
    }

    const sceneWidth = currentScene.value.width || 1920;
    const sceneHeight = currentScene.value.height || 1080;

    let defaultName = `${type} ${currentScene.value.widgets.length + 1}`;
    let defaultWidth = 400;
    let defaultHeight = 200;
    let dataConfig = {};
    let renderConfig = {};

    if (type === 'Image' || type === 'VIDEO') {
      defaultWidth = sceneWidth;
      defaultHeight = sceneHeight;
      dataConfig = { mode: 'AUTO', targetId: null };
    }

    if (type === 'TEXT') {
      defaultName = `文本 ${currentScene.value.widgets.length + 1}`;
      dataConfig = {
        text: '请输入文本',
        fontSize: 24,
        color: '#ffffff',
        fontWeight: 'normal',
        textAlign: 'center'
      };
      defaultWidth = 300;
      defaultHeight = 100;
    }

    if (type === 'TIMER') {
      defaultName = `倒计时 ${currentScene.value.widgets.length + 1}`;
      dataConfig = {
        title: '倒计时',
        hours: 0,
        minutes: 5,
        seconds: 0,
        milliseconds: 0,
        fontSize: 48,
        color: '#ffffff',
        fontWeight: 'bold',
        textAlign: 'center',
        showTitle: false,
        showMilliseconds: false
      };
      defaultWidth = 400;
      defaultHeight = 150;
    }

    if (type === 'BRACKET') {
      defaultName = `对战树 ${currentScene.value.widgets.length + 1}`;
      dataConfig = { stageId: null, fontSize: 12, textColor: '#000000', borderColor: '', bgColor: '' };
      defaultWidth = 600;
      defaultHeight = 500;
    }

    if (type === 'MATCH_DETAIL') {
      defaultName = `当前场次 ${currentScene.value.widgets.length + 1}`;
      dataConfig = { bgImage: '' };
      defaultWidth = 800;
      defaultHeight = 300;
    }

    if (type === 'SCOREBOARD') {
      defaultName = `比分牌 ${currentScene.value.widgets.length + 1}`;
      dataConfig = { stageId: null, showScore: true, fontSize: 12 };
      defaultWidth = 900;
      defaultHeight = 500;
    }

    if (type === 'RANKING') {
      defaultName = `排名展示 ${currentScene.value.widgets.length + 1}`;
      // opacity = 遮罩不透明度(0-100),默认 85 与旧版 bg-neutral-950/85 视觉一致
      dataConfig = { stageId: null, opacity: 85 };
      defaultWidth = 800;
      defaultHeight = 600;
    }

    const newWidget = {
      tournamentId: currentTournamentId,
      sceneId: currentScene.value?.id, // 直接使用 id，应该已经是字符串或数字
      name: defaultName,
      type: type.toUpperCase(),
      x: type === 'IMAGE' || type === 'VIDEO' ? 0 : sceneWidth / 2 - defaultWidth / 2,
      y: type === 'IMAGE' || type === 'VIDEO' ? 0 : sceneHeight / 2 - defaultHeight / 2,
      w: defaultWidth,
      h: defaultHeight,
      // zIndex 由服务端在场景锁内按 max+1 分配,保证新控件在最上层且不重复
      visible: 1,
      locked: 0,
      layoutConfig: JSON.stringify({}),
      dataConfig: JSON.stringify(dataConfig),
      renderConfig: JSON.stringify(renderConfig)
    };

    console.log('[addWidget] currentScene.value:', currentScene.value);
    console.log('[addWidget] currentScene.value?.id:', currentScene.value?.id);
    console.log('[addWidget] sceneId 类型:', typeof currentScene.value?.id);

    try {
      console.log(`➕ 正在添加组件: ${newWidget}`);
      const response = await addVisWidget(newWidget);

      if (response.data) {
        // 后端返回 camelCase zIndex;兜底时取本地最大层级 + 1
        const zIndex = response.data.zIndex;
        const z = zIndex != null ? zIndex : Math.max(0, ...currentScene.value.widgets.map((w) => Number(w.z) || 0)) + 1;
        const widget = {
          id: response.data.id,
          name: response.data.name,
          type: response.data.type,
          x: response.data.x,
          y: response.data.y,
          w: response.data.w,
          h: response.data.h,
          z,
          visible: response.data.visible === 1,
          locked: response.data.locked === 1,
          layoutConfig: response.data.layoutConfig,
          dataConfig: response.data.dataConfig,
          renderConfig: response.data.renderConfig
        };

        currentScene.value.widgets.push(widget);
        selectedWidgetId.value = widget.id;
        const addedSnap = snapshotWidget(widget);
        commitHistory(
          '添加组件',
          `widget-add-${widget.id}`,
          async () => {
            await deleteWidgetRaw(widget.id);
          },
          async () => {
            await restoreWidgetRaw(addedSnap);
          }
        );
        console.log(`✅ 成功添加组件`);
      }
    } catch (error) {
      console.error('❌ 添加组件失败:', error);
      throw error;
    }
  }

  // 选中/取消选中
  function selectWidget(id, source = '') {
    console.log('[directorStore] selectWidget - id:', id, 'type:', typeof id);
    const value = id ? String(id) : null;
    console.log('[directorStore] selectWidget - converted to:', value, 'type:', typeof value);
    widgetSelectSource.value = source;
    selectedWidgetId.value = value;
    console.log('[directorStore] selectWidget - selectedWidgetId.value after set:', selectedWidgetId.value, 'type:', typeof selectedWidgetId.value);
  }

  /** 拖拽/缩放开始前调用:记录该组件起始快照,作为本次手势撤销的 before */
  function beginWidgetTransform(id) {
    const widget = currentScene.value?.widgets.find((w) => String(w.id) === String(id));
    if (widget) {
      widgetTransformStart.set(String(widget.id), snapshotWidget(widget));
    }
  }

  /** 图层上移/下移(整场顺序快照入撤销栈) */
  async function moveLayer(id, dir) {
    const scene = currentScene.value;
    if (!scene) return;
    const widget = scene.widgets.find((w) => String(w.id) === String(id));
    if (!widget) return;
    if (widget.locked === true || widget.locked === 1) {
      throw new Error('已锁定的控件不能调整图层顺序，请先解锁');
    }
    const before = sceneZOrderIds(scene);
    const idx = before.findIndex((wid) => String(wid) === String(id));
    if (idx < 0) return;
    const neighborIdx = dir === 'up' ? idx - 1 : idx + 1;
    if (neighborIdx < 0 || neighborIdx >= before.length) return;
    const neighborId = before[neighborIdx];
    const neighbor = scene.widgets.find((w) => String(w.id) === String(neighborId));
    if (neighbor && (neighbor.locked === true || neighbor.locked === 1)) {
      throw new Error('相邻控件已锁定，请先解锁后再调整图层顺序');
    }
    const after = before.slice();
    const [moved] = after.splice(idx, 1);
    after.splice(neighborIdx, 0, moved);
    await moveWidgetLayer(id, dir);
    const n = after.length;
    after.forEach((wid, i) => {
      const w = scene.widgets.find((x) => String(x.id) === String(wid));
      if (w) w.z = n - i;
    });
    scene.widgets = [...scene.widgets];
    commitHistory(
      '调整图层顺序',
      `layer-${scene.id}`,
      async () => {
        await applyLayerOrderRaw(scene.id, before);
      },
      async () => {
        await applyLayerOrderRaw(scene.id, after);
      }
    );
  }

  /** 图层拖拽排序:orderedIds 为拖拽后的顺序(上→下),整场顺序入撤销栈 */
  async function reorderLayers(orderedIds) {
    const scene = currentScene.value;
    if (!scene || !orderedIds || orderedIds.length === 0) return;
    const actualIds = orderedIds.map((id) => resolveWidgetId(id));
    const locked = actualIds.some((aid) => {
      const w = scene.widgets.find((x) => String(x.id) === String(aid));
      return w && (w.locked === true || w.locked === 1);
    });
    if (locked) {
      throw new Error('存在已锁定的控件，请先解锁后再拖动排序');
    }
    const before = sceneZOrderIds(scene);
    if (before.length === actualIds.length && before.every((id, i) => String(id) === String(actualIds[i]))) {
      return; // 顺序未变化
    }
    await reorderWidgets(scene.id, actualIds);
    const n = actualIds.length;
    actualIds.forEach((aid, i) => {
      const w = scene.widgets.find((x) => String(x.id) === String(aid));
      if (w) w.z = n - i;
    });
    scene.widgets = [...scene.widgets];
    const after = sceneZOrderIds(scene);
    commitHistory(
      '拖动排序',
      `layer-${scene.id}`,
      async () => {
        await applyLayerOrderRaw(scene.id, before);
      },
      async () => {
        await applyLayerOrderRaw(scene.id, after);
      }
    );
  }

  /** 读取并清除选中来源(PropertyPanel 用于判断是否自动跳到组件配置) */
  function consumeWidgetSelectSource() {
    const s = widgetSelectSource.value;
    widgetSelectSource.value = '';
    return s;
  }

  // 更新组件属性（调用 API）
  // 只负责更新组件的内部数据(dataConfig, name等)
  // 位置和大小由 SceneRenderer 负责
  async function updateWidget(id, payload) {
    const widget = currentScene.value?.widgets.find((w) => w.id === String(id));
    if (!widget) return;
    const beforeSnap = snapshotWidget(widget);
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法更新组件');
      widgetTransformStart.delete(String(widget.id));
      return;
    }

    // 局部更新:只提交实际变更的字段(id/tournamentId/sceneId 为必填)。
    // 后端对已锁定控件按"提交字段与库值不一致即拒绝"校验,
    // 全量提交会把本地缓存与数据库的格式漂移(如老数据 NULL 配置、名字本地改动)
    // 误判成编辑锁定控件,导致解锁/显隐报"控件已锁定"。
    const updateData = {
      id: String(id), // 确保是字符串
      tournamentId: currentTournamentId,
      sceneId: currentScene.value?.id // 直接使用 id，应该已经是字符串或数字
    };

    if (payload.name !== undefined) updateData.name = payload.name;
    if (payload.visible !== undefined) updateData.visible = payload.visible ? 1 : 0;
    if (payload.locked !== undefined) updateData.locked = payload.locked ? 1 : 0;
    if (payload.z !== undefined) updateData.zIndex = payload.z;

    // 配置类字段:与本地现有配置合并后提交(未涉及则不提交)
    let dataConfig = {};
    let renderConfig = {};
    let layoutConfig = {};

    try {
      dataConfig = widget.dataConfig ? JSON.parse(widget.dataConfig) : {};
      renderConfig = widget.renderConfig ? JSON.parse(widget.renderConfig) : {};
      layoutConfig = widget.layoutConfig ? JSON.parse(widget.layoutConfig) : {};
    } catch (e) {
      console.warn('解析配置失败:', e);
    }

    if (payload.dataConfig) {
      Object.assign(dataConfig, payload.dataConfig);
      updateData.dataConfig = JSON.stringify(dataConfig);
    }
    if (payload.style) {
      Object.assign(renderConfig, payload.style);
      updateData.renderConfig = JSON.stringify(renderConfig);
    }
    if (payload.layoutConfig) {
      Object.assign(layoutConfig, payload.layoutConfig);
      updateData.layoutConfig = JSON.stringify(layoutConfig);
    }

    try {
      console.log(`🔄 正在更新组件: ${id}`, updateData);
      const response = await updateVisWidget(updateData);

      if (response.data) {
        // 仅覆盖本次提交的字段,未涉及字段保留原值(避免把未提交字段重置为 undefined)
        const patch = {};
        if (updateData.name !== undefined) patch.name = updateData.name;
        if (updateData.visible !== undefined) patch.visible = updateData.visible === 1;
        if (updateData.locked !== undefined) patch.locked = updateData.locked === 1;
        if (updateData.zIndex !== undefined) patch.z = updateData.zIndex;
        if (updateData.dataConfig !== undefined) patch.dataConfig = updateData.dataConfig;
        if (updateData.renderConfig !== undefined) patch.renderConfig = updateData.renderConfig;
        if (updateData.layoutConfig !== undefined) patch.layoutConfig = updateData.layoutConfig;
        Object.assign(widget, patch);
        const afterSnap = snapshotWidget(widget);
        if (!widgetSnapshotsEqual(beforeSnap, afterSnap)) {
          commitHistory(
            '修改组件',
            `widget-prop-${widget.id}`,
            async () => {
              await applyWidgetSnapshot(beforeSnap);
            },
            async () => {
              await applyWidgetSnapshot(afterSnap);
            }
          );
        }
        console.log(`✅ 成功更新组件`);
      }
    } catch (error) {
      console.error('❌ 更新组件失败:', error);
      throw error;
    }
  }

  // 更新组件位置和大小（由 SceneRenderer 调用）
  async function updateWidgetPosition(id, x, y, w, h, z) {
    const widget = currentScene.value?.widgets.find((w) => w.id === String(id));
    if (!widget) return;
    // 手势开始时记录过起始快照则用起始值,避免 mouseup 时本地已被拖到终点
    const beforeSnap = widgetTransformStart.get(String(widget.id)) || snapshotWidget(widget);
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法更新组件');
      return;
    }

    // 准备更新数据
    const updateData = {
      id: String(id),
      tournamentId: currentTournamentId,
      sceneId: currentScene.value?.id,
      x: x !== undefined ? x : widget.x,
      y: y !== undefined ? y : widget.y,
      w: w !== undefined ? w : widget.w,
      h: h !== undefined ? h : widget.h,
      zIndex: z !== undefined ? z : widget.z
    };

    try {
      console.log(`🔄 正在更新组件位置: ${id}`, updateData);
      const response = await updateVisWidget(updateData);

      if (response.data) {
        // 更新本地数据
        Object.assign(widget, {
          x: updateData.x,
          y: updateData.y,
          w: updateData.w,
          h: updateData.h,
          z: updateData.zIndex
        });
        const afterSnap = snapshotWidget(widget);
        const moved = x !== undefined || y !== undefined || w !== undefined || h !== undefined;
        if (!widgetSnapshotsEqual(beforeSnap, afterSnap)) {
          commitHistory(
            moved ? '移动/缩放组件' : '调整组件层级',
            `widget-pos-${widget.id}`,
            async () => {
              await applyWidgetSnapshot(beforeSnap);
            },
            async () => {
              await applyWidgetSnapshot(afterSnap);
            }
          );
        }
        console.log(`✅ 成功更新组件位置`);
      }
    } catch (error) {
      console.error('❌ 更新组件位置失败:', error);
      throw error;
    } finally {
      widgetTransformStart.delete(String(widget.id));
    }
  }

  // 删除组件（调用 API）
  async function deleteWidget(id) {
    if (!currentScene.value) return;
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法删除组件');
      return;
    }

    try {
      widgetTransformStart.delete(String(id));
      const deletedWidget = currentScene.value.widgets.find((w) => w.id === String(id));
      const deletedSnap = snapshotWidget(deletedWidget);
      console.log(`🗑️ 正在删除组件: ${id}`);
      await delVisWidget(id);

      const index = currentScene.value.widgets.findIndex((w) => w.id === String(id));
      if (index > -1) {
        currentScene.value.widgets.splice(index, 1);
        if (selectedWidgetId.value === String(id)) {
          selectedWidgetId.value = null;
        }
        commitHistory(
          '删除组件',
          `widget-del-${id}`,
          async () => {
            await restoreWidgetRaw(deletedSnap);
          },
          async () => {
            await deleteWidgetRaw(deletedWidget.id);
          }
        );
        console.log(`✅ 成功删除组件`);
      }
    } catch (error) {
      console.error('❌ 删除组件失败:', error);
      throw error;
    }
  }

  return {
    // 状态
    screens,
    scenes,
    currentSceneId,
    currentScene,
    selectedWidgetId,
    selectedWidget,
    loading,
    // 撤回/重做
    canUndo,
    canRedo,
    undoLabel,
    redoLabel,
    undo,
    redo,

    // 场景方法（API）
    loadScenes,
    addScene,
    updateScene,
    deleteScene,
    switchScene,
    updateSceneThumbnail,
    getSceneThumbnail,
    getSceneName,

    // 屏幕方法（本地）
    addScreen,
    deleteScreen,
    projectScene,
    clearScreenProjection,
    getScreenName,
    setScreenOnline,
    setScreenOffline,
    turnOffScreen,

    // 组件方法（API）
    addWidget,
    selectWidget,
    beginWidgetTransform,
    consumeWidgetSelectSource,
    moveLayer,
    reorderLayers,
    updateWidget,
    updateWidgetPosition,
    deleteWidget
  };
});
