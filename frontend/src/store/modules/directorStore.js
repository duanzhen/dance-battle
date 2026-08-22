// stores/directorStore.js
import { defineStore } from 'pinia';
import { ref, computed, shallowRef } from 'vue';
import { v4 as uuidv4 } from 'uuid';
import { listVisScene, addVisScene, updateVisScene, delVisScene } from '@/api/game/visScene';
import { listVisWidget, addVisWidget, updateVisWidget, delVisWidget } from '@/api/game/visWidget';
import { projectSceneToScreen, clearScreenScene } from '@/api/game/screenControl';
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

  // 加载状态
  const loading = ref(false);

  // 当前赛事 ID
  let currentTournamentId = null;

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
  // 场景管理（接入 API）
  // ============================================

  // 从后端加载场景列表
  async function loadScenes(tournamentId) {
    if (!tournamentId) {
      console.warn('⚠️ tournamentId 为空，无法加载场景');
      return;
    }

    currentTournamentId = tournamentId;
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
      await delVisScene(sceneId);

      const index = scenes.value.findIndex((s) => s.id == sceneId);
      if (index > -1) {
        const deletedScene = scenes.value[index];
        scenes.value.splice(index, 1);

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

  // 订阅单个屏幕的 SSE 控制通道
  function subscribeToScreenSSE(screenId) {
    if (!currentTournamentId) {
      console.warn(`[SSE] 未设置 tournamentId，无法订阅屏幕 ${screenId}`);
      return;
    }

    subscribeScreenControl(screenId, currentTournamentId, (message) => {
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
        console.log(`📤 删除屏幕前先清除投射: screenId=${screenId}, sceneId=null`);
        await projectSceneToScreen(screenId, null);
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
      // 调用后端 API
      console.log(`📤 向后端发送投射请求: screenId=${screenId}, sceneId=${sceneId}`);
      await projectSceneToScreen(screenId, sceneId);

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
      // 调用后端 API
      console.log(`📤 向后端发送清除投射请求: screenId=${screenId}`);
      await clearScreenScene(screenId);

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
      dataConfig = { stageId: null };
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
      dataConfig = { stageId: null, showScore: true };
      defaultWidth = 900;
      defaultHeight = 500;
    }

    if (type === 'RANKING') {
      defaultName = `排名展示 ${currentScene.value.widgets.length + 1}`;
      dataConfig = { stageId: null };
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
      zIndex: currentScene.value.widgets.length + 1,
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
        const widget = {
          id: response.data.id,
          name: response.data.name,
          type: response.data.type,
          x: response.data.x,
          y: response.data.y,
          w: response.data.w,
          h: response.data.h,
          z: response.data.zindex,
          visible: response.data.visible === 1,
          locked: response.data.locked === 1,
          layoutConfig: response.data.layoutConfig,
          dataConfig: response.data.dataConfig,
          renderConfig: response.data.renderConfig
        };

        currentScene.value.widgets.push(widget);
        selectedWidgetId.value = widget.id;
        console.log(`✅ 成功添加组件`);
      }
    } catch (error) {
      console.error('❌ 添加组件失败:', error);
      throw error;
    }
  }

  // 选中/取消选中
  function selectWidget(id) {
    console.log('[directorStore] selectWidget - id:', id, 'type:', typeof id);
    const value = id ? String(id) : null;
    console.log('[directorStore] selectWidget - converted to:', value, 'type:', typeof value);
    selectedWidgetId.value = value;
    console.log('[directorStore] selectWidget - selectedWidgetId.value after set:', selectedWidgetId.value, 'type:', typeof selectedWidgetId.value);
  }

  // 更新组件属性（调用 API）
  // 只负责更新组件的内部数据(dataConfig, name等)
  // 位置和大小由 SceneRenderer 负责
  async function updateWidget(id, payload) {
    const widget = currentScene.value?.widgets.find((w) => w.id === String(id));
    if (!widget) return;
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法更新组件');
      return;
    }

    // 准备更新数据
    const updateData = {
      id: String(id), // 确保是字符串
      tournamentId: currentTournamentId,
      sceneId: currentScene.value?.id, // 直接使用 id，应该已经是字符串或数字
      name: widget.name,
      type: widget.type,
      layoutConfig: widget.layoutConfig,
      dataConfig: widget.dataConfig,
      renderConfig: widget.renderConfig
    };

    // 只在 payload 中明确指定时才更新属性
    if (payload.name !== undefined) updateData.name = payload.name;
    if (payload.visible !== undefined) updateData.visible = payload.visible ? 1 : 0;
    if (payload.locked !== undefined) updateData.locked = payload.locked ? 1 : 0;
    if (payload.z !== undefined) updateData.zIndex = payload.z;

    // 更新配置
    let layoutConfig = {};
    let dataConfig = {};
    let renderConfig = {};

    try {
      layoutConfig = widget.layoutConfig ? JSON.parse(widget.layoutConfig) : {};
      dataConfig = widget.dataConfig ? JSON.parse(widget.dataConfig) : {};
      renderConfig = widget.renderConfig ? JSON.parse(widget.renderConfig) : {};
    } catch (e) {
      console.warn('解析配置失败:', e);
    }

    if (payload.style) {
      Object.assign(renderConfig, payload.style);
    }
    if (payload.dataConfig) {
      Object.assign(dataConfig, payload.dataConfig);
    }

    updateData.layoutConfig = JSON.stringify(layoutConfig);
    updateData.dataConfig = JSON.stringify(dataConfig);
    updateData.renderConfig = JSON.stringify(renderConfig);

    try {
      console.log(`🔄 正在更新组件: ${id}`, updateData);
      const response = await updateVisWidget(updateData);

      if (response.data) {
        // 更新本地数据(仅覆盖本次实际提交的字段,未涉及字段保留原值,避免误重置 visible/locked/z)
        const patch = {
          name: updateData.name,
          layoutConfig: updateData.layoutConfig,
          dataConfig: updateData.dataConfig,
          renderConfig: updateData.renderConfig
        };
        if (updateData.visible !== undefined) patch.visible = updateData.visible === 1;
        if (updateData.locked !== undefined) patch.locked = updateData.locked === 1;
        if (updateData.zIndex !== undefined) patch.z = updateData.zIndex;
        Object.assign(widget, patch);
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
    if (!currentTournamentId) {
      console.error('❌ 未设置 tournamentId，无法更新组件');
      return;
    }

    // 准备更新数据
    const updateData = {
      id: String(id),
      tournamentId: currentTournamentId,
      sceneId: currentScene.value?.id,
      name: widget.name,
      type: widget.type,
      layoutConfig: widget.layoutConfig,
      dataConfig: widget.dataConfig,
      renderConfig: widget.renderConfig,
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
        console.log(`✅ 成功更新组件位置`);
      }
    } catch (error) {
      console.error('❌ 更新组件位置失败:', error);
      throw error;
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
      console.log(`🗑️ 正在删除组件: ${id}`);
      await delVisWidget(id);

      const index = currentScene.value.widgets.findIndex((w) => w.id === String(id));
      if (index > -1) {
        currentScene.value.widgets.splice(index, 1);
        if (selectedWidgetId.value === String(id)) {
          selectedWidgetId.value = null;
        }
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
    updateWidget,
    updateWidgetPosition,
    deleteWidget
  };
});
