'use strict';

const MAX_DECORATIONS = 12;
const DEFAULT_LAYOUT = Object.freeze({ layoutVersion: 2, decorations: [], layout: [], items: [] });

function normalizeHabitatLayout(catalog = [], habitat = {}) {
  const definitions = new Map((Array.isArray(catalog) ? catalog : [])
    .filter(item => item && typeof item.id === 'string' && item.id.trim())
    .map(item => [item.id, item]));
  const requested = Array.isArray(habitat?.decorations) ? habitat.decorations : [];
  const decorations = [...new Set(requested.map(String).filter(id => definitions.has(id)))].slice(0, MAX_DECORATIONS);
  const saved = new Map((Array.isArray(habitat?.layout) ? habitat.layout : [])
    .filter(item => item && definitions.has(String(item.id)))
    .map(item => [String(item.id), item]));
  const layout = decorations.map((id, slot) => {
    const position = saved.get(id);
    const x = Number(position?.x);
    const y = Number(position?.y);
    const rotation = Number(position?.rotation);
    return {
      id,
      x: Number.isFinite(x) && x >= 0 && x <= 1 ? Number(x.toFixed(3)) : Number((0.16 + (slot % 4) * 0.225).toFixed(3)),
      y: Number.isFinite(y) && y >= 0 && y <= 1 ? Number(y.toFixed(3)) : Number((0.79 - Math.floor(slot / 4) * 0.16).toFixed(3)),
      rotation: Number.isFinite(rotation) && rotation >= -180 && rotation <= 180 ? Math.round(rotation) : 0,
    };
  });
  return {
    layoutVersion: 2,
    decorations,
    layout,
    items: decorations.map(id => definitions.get(id)),
  };
}

function decorationGlyph(visualType) {
  return ({
    'star-lamp': '✦',
    'moss-stone': '◍',
    'cloud-platform': '☁',
    'wind-chime': '♬',
    'moon-pool': '◉',
    'flower-rack': '✿',
    'crystal-cluster': '◇',
    'warm-hearth': '♨',
    'sand-dune': '⌁',
    'feather-curtain': '〰',
  })[String(visualType || '')] || '✦';
}

function decorationColor(visualType) {
  return ({
    'star-lamp': '#f7cf78', 'moss-stone': '#80bd8f', 'cloud-platform': '#a5cfec',
    'wind-chime': '#b6a2f4', 'moon-pool': '#83d8e8', 'flower-rack': '#f19bc0',
    'crystal-cluster': '#93a9ff', 'warm-hearth': '#ff9d6f', 'sand-dune': '#d4b477',
    'feather-curtain': '#9dbbe6',
  })[String(visualType || '')] || '#a5cfec';
}

class StageHabitatController {
  constructor({ document, api, storage = null, onFeedback = () => {}, onChange = () => {} } = {}) {
    this.document = document;
    this.api = api;
    this.storage = storage;
    this.onFeedback = onFeedback;
    this.onChange = onChange;
    this.stage = document?.getElementById('stageHabitat');
    this.select = document?.getElementById('habitatCatalogSelect');
    this.list = document?.getElementById('habitatItemList');
    this.status = document?.getElementById('habitatStatus');
    this.addButton = document?.getElementById('habitatAddButton');
    this.editButton = document?.getElementById('habitatEditButton');
    this.resetButton = document?.getElementById('habitatResetButton');
    this.inventoryNode = document?.getElementById('habitatInventory');
    this.craftingNode = document?.getElementById('habitatCrafting');
    this.growthNode = document?.getElementById('habitatGrowthLog');
    this.catalog = [];
    this.itemCatalog = [];
    this.recipes = [];
    this.habitat = { ...DEFAULT_LAYOUT };
    this.realityState = { inventory: {}, loadout: [], activeEffects: [], habitat: this.habitat };
    this.editing = false;
    this.drag = null;
    this.loading = null;
    this.pendingUses = new Map();
    this.pendingCrafts = new Map();
    this.ready = false;
    this._bindControls();
  }

  _bindControls() {
    this.addButton?.addEventListener('click', () => this.add(this.select?.value));
    this.editButton?.addEventListener('click', () => this.setEditing(!this.editing));
    this.resetButton?.addEventListener('click', () => this.reset());
  }

  _cacheKey() { return 'phonebridge:web:habitat:v2'; }

  _saveCache() {
    try {
      this.storage?.setItem(this._cacheKey(), JSON.stringify({
        catalog: this.catalog,
        itemCatalog: this.itemCatalog,
        recipes: this.recipes,
        state: this.realityState,
        habitat: this.habitat,
        pendingUses: Object.fromEntries(this.pendingUses),
        pendingCrafts: Object.fromEntries(this.pendingCrafts),
      }));
    } catch (_) {}
  }

  _loadCache() {
    try {
      const saved = JSON.parse(this.storage?.getItem(this._cacheKey()) || 'null');
      if (!saved || !Array.isArray(saved.catalog) || !saved.habitat) return false;
      this.catalog = saved.catalog;
      this.itemCatalog = Array.isArray(saved.itemCatalog) ? saved.itemCatalog : [];
      this.recipes = Array.isArray(saved.recipes) ? saved.recipes : [];
      this.realityState = saved.state && typeof saved.state === 'object' ? saved.state : this.realityState;
      this.pendingUses = new Map(Object.entries(saved.pendingUses || {}));
      this.pendingCrafts = new Map(Object.entries(saved.pendingCrafts || {}));
      this.habitat = normalizeHabitatLayout(this.catalog, saved.habitat);
      this.ready = true;
      return true;
    } catch (_) { return false; }
  }

  async load() {
    if (this.loading) return this.loading;
    this.loading = (async () => {
      if (!this.ready) this._loadCache();
      this.render();
      try {
        const [catalogResult, stateResult, craftingResult] = await Promise.all([
          this.api('/api/reality/catalog'),
          this.api('/api/reality/state'),
          this.api('/api/reality/crafting'),
        ]);
        this.catalog = Array.isArray(catalogResult?.catalog?.decorations) ? catalogResult.catalog.decorations : [];
        this.itemCatalog = Array.isArray(catalogResult?.catalog?.items) ? catalogResult.catalog.items : [];
        this.recipes = Array.isArray(craftingResult?.recipes) ? craftingResult.recipes : [];
        this._mergeState(stateResult?.state);
        this.habitat = normalizeHabitatLayout(this.catalog, this.realityState?.habitat || {});
        this.realityState.habitat = this.habitat;
        this.ready = true;
        this._saveCache();
        this._setStatus('布局已同步 · ' + this.habitat.decorations.length + '/' + MAX_DECORATIONS);
      } catch (_) {
        if (!this.ready) this._loadCache();
        this._setStatus(this.ready ? '节点离线 · 显示本机缓存布局' : '家园暂不可用 · 连接节点后重试');
      } finally {
        this.render();
        this.loading = null;
      }
      return this.habitat;
    })();
    return this.loading;
  }

  _setStatus(message) { if (this.status) this.status.textContent = String(message || ''); }

  updateFromState(state) {
    if (!state || !state.habitat) return false;
    if (!this.catalog.length) this._loadCache();
    if (!this._mergeState(state)) return false;
    this.habitat = normalizeHabitatLayout(this.catalog, state.habitat);
    this.realityState.habitat = this.habitat;
    this.ready = true;
    this._saveCache();
    this._setStatus('布局已同步 · ' + this.habitat.decorations.length + '/' + MAX_DECORATIONS);
    this.render();
    return true;
  }

  _mergeState(state) {
    if (!state || typeof state !== 'object') return false;
    const currentAt = Number(this.realityState?.updatedAt) || 0;
    const incomingAt = Number(state.updatedAt) || 0;
    if (currentAt > 0 && incomingAt > 0 && incomingAt < currentAt) return false;
    this.realityState = { ...this.realityState, ...state, catalogItems: this.itemCatalog };
    return true;
  }

  async refreshCrafting() {
    try {
      const result = await this.api('/api/reality/crafting');
      if (!Array.isArray(result?.recipes)) return false;
      this.recipes = result.recipes;
      this._saveCache();
      this.renderPanel();
      return true;
    } catch (_) { return false; }
  }

  render() {
    if (!this.document || !this.stage) return;
    const byId = new Map(this.catalog.map(item => [item.id, item]));
    const fragment = this.document.createDocumentFragment();
    for (const placement of this.habitat.layout) {
      const item = byId.get(placement.id);
      if (!item) continue;
      const button = this.document.createElement('button');
      button.type = 'button';
      button.className = 'stage-decoration';
      button.dataset.decorationId = item.id;
      button.dataset.editing = String(this.editing);
      button.textContent = decorationGlyph(item.visualType);
      button.setAttribute('aria-label', this.editing ? `移动摆件：${item.name}` : `互动：${item.name}`);
      button.title = this.editing ? `${item.name} · 拖动调整位置` : `${item.name} · 点击互动`;
      button.style.left = `${placement.x * 100}%`;
      button.style.top = `${placement.y * 100}%`;
      button.style.transform = `translate(-50%, -50%) rotate(${placement.rotation}deg)`;
      button.style.color = decorationColor(item.visualType);
      button.style.pointerEvents = 'auto';
      button.addEventListener('pointerdown', event => this._pointerDown(event, item, placement, button));
      button.addEventListener('pointermove', event => this._pointerMove(event, button));
      button.addEventListener('pointerup', event => this._pointerUp(event, button));
      button.addEventListener('pointercancel', event => this._pointerUp(event, button));
      button.addEventListener('click', event => {
        if (this.suppressClick) {
          this.suppressClick = false;
          clearTimeout(this.suppressClickTimer);
          this.suppressClickTimer = null;
          event.preventDefault(); return;
        }
        if (!this.editing) this.onFeedback(this._interactionText(item));
      });
      button.addEventListener('keydown', event => {
        if (this.editing && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
          event.preventDefault();
          const delta = event.shiftKey ? 0.04 : 0.015;
          const next = { ...placement };
          if (event.key === 'ArrowLeft') next.x -= delta;
          if (event.key === 'ArrowRight') next.x += delta;
          if (event.key === 'ArrowUp') next.y -= delta;
          if (event.key === 'ArrowDown') next.y += delta;
          this.move(item.id, next.x, next.y, next.rotation);
        }
      });
      fragment.appendChild(button);
    }
    this.stage.replaceChildren(fragment);
    this.stage.dataset.editing = String(this.editing);
    this.renderPanel();
  }

  _interactionText(item) {
    const responses = {
      'tap-glow': `${item.name}亮起柔和星光，Mote 好奇地抬头看了一眼。`,
      'tap-soothe': `你和 Mote 一起摸了摸${item.name}，呼吸慢了下来。`,
      'tap-sit': `Mote 在${item.name}旁停靠了一会儿。`,
      'tap-chime': `${item.name}发出轻响，Mote 跟着节奏摇了摇。`,
      'tap-ripple': `水面漾开一圈微光，Mote 凑近观察。`,
      'tap-bloom': `${item.name}舒展花瓣，Mote 显得很开心。`,
      'tap-pulse': `${item.name}与 Mote 的光环短暂共鸣。`,
      'tap-warm': `暖意从${item.name}散开，Mote 放松地靠近。`,
      'tap-rest': `Mote 在${item.name}旁安静休息了一会儿。`,
      'tap-wave': `${item.name}轻轻摆动，Mote 转身追着它看。`,
    };
    return responses[item.interaction] || `${item.name}回应了你的触碰。`;
  }

  renderPanel() {
    if (!this.document) return;
    if (this.select) {
      const current = this.select.value;
      const fragment = this.document.createDocumentFragment();
      for (const item of this.catalog) {
        const option = this.document.createElement('option');
        option.value = item.id;
        option.textContent = `${item.name} · ${item.comfort || 0}舒适`;
        option.disabled = this.habitat.decorations.includes(item.id);
        fragment.appendChild(option);
      }
      this.select.replaceChildren(fragment);
      if (this.catalog.some(item => item.id === current && !this.habitat.decorations.includes(item.id))) this.select.value = current;
    }
    if (this.addButton) this.addButton.disabled = this.habitat.decorations.length >= MAX_DECORATIONS || !this.catalog.some(item => item.id === this.select?.value && !this.habitat.decorations.includes(item.id));
    if (this.editButton) {
      this.editButton.textContent = this.editing ? '完成布置' : '编辑舞台';
      this.editButton.setAttribute('aria-pressed', String(this.editing));
    }
    if (!this.list) return;
    const fragment = this.document.createDocumentFragment();
    for (const placement of this.habitat.layout) {
      const item = this.catalog.find(entry => entry.id === placement.id);
      if (!item) continue;
      const row = this.document.createElement('div');
      row.className = 'habitat-item-row';
      row.setAttribute('role', 'listitem');
      const label = this.document.createElement('span');
      label.textContent = `${item.name} · ${Math.round(placement.x * 100)}%, ${Math.round(placement.y * 100)}%`;
      const moveLabel = this.document.createElement('span');
      moveLabel.className = 'habitat-item-actions';
      for (const [direction, dx, dy] of [['上', 0, -0.04], ['左', -0.04, 0], ['右', 0.04, 0], ['下', 0, 0.04]]) {
        const move = this.document.createElement('button');
        move.type = 'button'; move.textContent = direction; move.title = `${item.name}向${direction}移动`;
        move.setAttribute('aria-label', `${item.name}向${direction}移动`);
        move.addEventListener('click', () => this.move(item.id, placement.x + dx, placement.y + dy, placement.rotation));
        moveLabel.appendChild(move);
      }
      const remove = this.document.createElement('button');
      remove.type = 'button'; remove.textContent = '移除';
      remove.addEventListener('click', () => this.remove(item.id));
      moveLabel.appendChild(remove);
      row.append(label, moveLabel); fragment.appendChild(row);
    }
    this.list.replaceChildren(fragment);
    this.renderInventory();
    this.renderRecipes();
    this.renderGrowth();
  }

  renderInventory() {
    if (!this.inventoryNode) return;
    const fragment = this.document.createDocumentFragment();
    const inventory = this.realityState?.inventory && typeof this.realityState.inventory === 'object' ? this.realityState.inventory : {};
    const loadout = new Set(Array.isArray(this.realityState?.loadout) ? this.realityState.loadout : []);
    const owned = this.itemCatalog.filter(item => Number(inventory[item.id]) > 0);
    for (const [itemId] of this.pendingUses) {
      if (Number(inventory[itemId]) > 0) continue;
      const item = this.itemCatalog.find(candidate => candidate.id === itemId);
      const row = this.document.createElement('div'); row.className = 'habitat-item-row';
      const detail = this.document.createElement('span');
      detail.textContent = `使用结果待确认：${item?.name || itemId}。重试会复用同一事件，不会重复扣除。`;
      row.appendChild(detail);
      const retry = this.document.createElement('button'); retry.type = 'button'; retry.textContent = '重试确认';
      retry.addEventListener('click', () => this.useItem(itemId)); row.appendChild(retry);
      fragment.appendChild(row);
    }
    if (!owned.length) {
      const empty = this.document.createElement('p'); empty.className = 'sub';
      if (!this.pendingUses.size) empty.textContent = '背包还是空的；完成现实线索或遭遇可以收集材料。';
      else empty.textContent = '背包为空；上方有尚未确认的道具操作。';
      fragment.appendChild(empty);
    }
    for (const item of owned) {
      const row = this.document.createElement('div'); row.className = 'habitat-item-row';
      const detail = this.document.createElement('span');
      detail.textContent = `${item.name} ×${Number(inventory[item.id])}${loadout.has(item.id) ? ' · 已装备' : ''}${item.effectDescription ? ` · ${item.effectDescription}` : ''}`;
      row.appendChild(detail);
      if (item.useEffect) {
        const pending = this.pendingUses.has(item.id);
        const use = this.document.createElement('button'); use.type = 'button'; use.textContent = pending ? '重试确认' : '使用';
        use.title = pending ? '重试将复用同一事件，不会重复扣除' : (item.effectDescription || item.effectLabel);
        use.addEventListener('click', () => this.useItem(item.id)); row.appendChild(use);
      }
      if (item.kind === 'equipment') {
        const equip = this.document.createElement('button'); equip.type = 'button'; equip.textContent = loadout.has(item.id) ? '卸下' : '装备';
        equip.addEventListener('click', () => this.toggleEquipment(item.id)); row.appendChild(equip);
      }
      fragment.appendChild(row);
    }
    const summary = this.document.createElement('p'); summary.className = 'sub';
    summary.textContent = `装备效果：${this.equipmentSummary()}。${this.activeEffectSummary()}`;
    fragment.appendChild(summary);
    this.inventoryNode.replaceChildren(fragment);
  }

  equipmentSummary() {
    const effects = this.realityState?.equipmentEffects || {};
    const parts = [];
    if (Number(effects.xpBonus)) parts.push(`探索 +${effects.xpBonus} XP`);
    if (Number(effects.lightXpBonus)) parts.push(`光线 +${effects.lightXpBonus} XP`);
    if (Number(effects.extraItem)) parts.push(`材料 +${effects.extraItem}`);
    if (Number(effects.homeComfort)) parts.push(`舒适度 +${effects.homeComfort}`);
    return parts.length ? parts.join('、') : '暂无';
  }

  activeEffectSummary() {
    const active = (Array.isArray(this.realityState?.activeEffects) ? this.realityState.activeEffects : [])
      .filter(item => Number(item.expiresAt) > Date.now())
      .map(item => `${item.label || '增益'}剩余 ${Math.max(1, Math.ceil((Number(item.expiresAt) - Date.now()) / 60000))} 分钟`);
    return active.length ? `生效中：${active.join('、')}` : '';
  }

  renderRecipes() {
    if (!this.craftingNode) return;
    const fragment = this.document.createDocumentFragment();
    for (const preview of this.recipes) {
      const recipe = preview.recipe;
      if (!recipe) continue;
      const row = this.document.createElement('div'); row.className = 'habitat-item-row';
      const detail = this.document.createElement('span');
      const ingredients = preview.ingredients.map(item => `${item.name} ${item.owned}/${item.required}`).join(' + ');
      const sources = [...new Set(preview.ingredients.flatMap(item => item.sources || []))].join('、');
      const missing = preview.missing.length ? `缺少 ${preview.ingredients.filter(item => Number(item.missing) > 0).map(item => item.name).join('、')}` : '材料齐全';
      detail.textContent = `${recipe.name}：${ingredients} → ${preview.output?.name || recipe.output}；${missing}；来源：${sources}`;
      row.appendChild(detail);
      const pending = this.pendingCrafts.has(recipe.id);
      const craft = this.document.createElement('button'); craft.type = 'button'; craft.textContent = pending ? '重试确认' : '制作'; craft.disabled = !preview.canCraft && !pending;
      craft.addEventListener('click', () => this.craft(recipe.id)); row.appendChild(craft);
      fragment.appendChild(row);
    }
    if (!this.recipes.length) {
      const empty = this.document.createElement('p'); empty.className = 'sub'; empty.textContent = '配方暂不可用。'; fragment.appendChild(empty);
    }
    this.craftingNode.replaceChildren(fragment);
  }

  renderGrowth() {
    if (!this.growthNode) return;
    const records = (Array.isArray(this.realityState?.growthRecords) ? this.realityState.growthRecords : []).slice(-12).reverse();
    const fragment = this.document.createDocumentFragment();
    if (!records.length) {
      const empty = this.document.createElement('p'); empty.className = 'sub'; empty.textContent = '完成专注、散步观察或睡前回顾后，会在这里说明经验与关系变化。中断活动不扣成长。'; fragment.appendChild(empty);
    }
    for (const record of records) {
      const row = this.document.createElement('p'); row.className = 'sub';
      const time = Number(record.completedAt) ? new Date(Number(record.completedAt)).toLocaleString() : '';
      row.textContent = `${record.title || '伙伴活动'} · +${Number(record.xpDelta) || 0} XP · 关系 +${Number(record.relationshipDelta) || 0} · 来源：${record.source === 'daily_activity' ? '日常完成' : '现实探索'}${time ? ` · ${time}` : ''}`;
      fragment.appendChild(row);
    }
    this.growthNode.replaceChildren(fragment);
  }

  setEditing(enabled) { this.editing = Boolean(enabled); this.render(); }

  _pointerDown(event, item, placement, button) {
    if (!this.editing || event.button !== 0) return;
    event.preventDefault();
    this.drag = { id: item.id, x: placement.x, y: placement.y, rotation: placement.rotation, pointerId: event.pointerId, moved: false, button };
    button.setPointerCapture?.(event.pointerId);
  }

  _pointerMove(event, button) {
    if (!this.drag || this.drag.pointerId !== event.pointerId) return;
    const bounds = this.stage.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    const x = Math.max(0.04, Math.min(0.96, (event.clientX - bounds.left) / bounds.width));
    const y = Math.max(0.08, Math.min(0.94, (event.clientY - bounds.top) / bounds.height));
    if (Math.abs(x - this.drag.x) + Math.abs(y - this.drag.y) > 0.002) this.drag.moved = true;
    this.drag.x = x; this.drag.y = y;
    button.style.left = `${x * 100}%`; button.style.top = `${y * 100}%`;
  }

  _pointerUp(event) {
    const drag = this.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.drag = null;
    if (drag.moved) {
      this.suppressClick = true;
      clearTimeout(this.suppressClickTimer);
      this.suppressClickTimer = setTimeout(() => { this.suppressClick = false; this.suppressClickTimer = null; }, 500);
      this.move(drag.id, drag.x, drag.y, drag.rotation);
    }
  }

  async _mutate(payload) {
    try {
      const result = await this.api('/api/reality/habitat', { method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) });
      if (!result?.ok || !result.state?.habitat) throw new Error('服务端没有确认家园修改');
      this.updateFromState(result.state); this.onChange(result.state);
      return true;
    } catch (error) {
      this._setStatus(`修改未同步：${error.message || '请求失败'}。可重试。`);
      this.onFeedback('家园修改未同步，请检查连接后重试。');
      return false;
    }
  }

  async _inventoryMutation(path, payload, method = 'POST') {
    try {
      const result = await this.api(path, { method, headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) });
      if (!result?.ok || !result.state) throw new Error('服务端没有确认背包修改');
      this.realityState = { ...this.realityState, ...result.state, catalogItems: this.itemCatalog };
      if (result.equipmentEffects) this.realityState.equipmentEffects = result.equipmentEffects;
      this.habitat = normalizeHabitatLayout(this.catalog, result.state.habitat || this.habitat);
      this.realityState.habitat = this.habitat;
      if (payload.itemId) this.pendingUses.delete(payload.itemId);
      if (payload.recipeId) this.pendingCrafts.delete(payload.recipeId);
      const previewRefreshFailed = !(await this.refreshCrafting());
      this._saveCache(); this.render();
      this._setStatus(previewRefreshFailed ? '操作已由服务端确认；配方预览暂未刷新。' : result.duplicate ? '操作已确认（重复请求未重复结算）' : '背包与成长状态已同步');
      this.onFeedback(result.receipt?.effect?.description || (result.recipe ? `完成制作：${result.recipe.name}` : '装备效果已更新。'));
      this.onChange(result.state);
      return true;
    } catch (error) {
      const statusCode = Number(error.status);
      const definitiveRejection = statusCode >= 400 && statusCode < 500 && statusCode !== 408 && statusCode !== 429;
      if (definitiveRejection) {
        if (payload.itemId) this.pendingUses.delete(payload.itemId);
        if (payload.recipeId) this.pendingCrafts.delete(payload.recipeId);
        this._saveCache();
      }
      this.render();
      this._setStatus(definitiveRejection
        ? `节点已明确拒绝操作：${error.message || '请刷新状态后重试。'}`
        : `背包操作未同步：${error.message || '请求失败'}。可用同一事件重试。`);
      this.onFeedback(definitiveRejection ? '节点未提交这次操作；刷新状态后可重新操作。' : '结果仍待确认，重试会复用原事件 ID。');
      return false;
    }
  }

  async useItem(itemId) {
    if (!this.pendingUses.has(itemId)) {
      const nonce = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      this.pendingUses.set(itemId, `web-use-${nonce}`);
      this._saveCache();
    }
    return this._inventoryMutation('/api/reality/items/use', { eventId: this.pendingUses.get(itemId), itemId });
  }

  async craft(recipeId) {
    if (!this.pendingCrafts.has(recipeId)) {
      const nonce = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      this.pendingCrafts.set(recipeId, `web-craft-${nonce}`);
      this._saveCache();
    }
    return this._inventoryMutation('/api/reality/crafting', { recipeId, eventId: this.pendingCrafts.get(recipeId) });
  }

  async toggleEquipment(itemId) {
    const current = new Set(Array.isArray(this.realityState?.loadout) ? this.realityState.loadout : []);
    if (current.has(itemId)) current.delete(itemId);
    else {
      if (current.size >= 4) { this._setStatus('最多同时装备 4 件道具。'); return false; }
      current.add(itemId);
    }
    return this._inventoryMutation('/api/reality/loadout', { items: [...current] }, 'PATCH');
  }

  async add(decorationId) {
    if (!decorationId || this.habitat.decorations.includes(String(decorationId))) return false;
    if (this.habitat.decorations.length >= MAX_DECORATIONS) { this._setStatus('最多布置 12 件摆件。'); return false; }
    return this._mutate({ decorationId: String(decorationId) });
  }
  async move(decorationId, x, y, rotation = 0) {
    const safeX = Math.max(0.04, Math.min(0.96, Number(x)));
    const safeY = Math.max(0.08, Math.min(0.94, Number(y)));
    return this._mutate({ action: 'move', decorationId: String(decorationId), x: Number(safeX.toFixed(3)), y: Number(safeY.toFixed(3)), rotation });
  }
  async remove(decorationId) { return this._mutate({ action: 'remove', decorationId: String(decorationId) }); }
  async reset() { return this._mutate({ action: 'reset' }); }
}

function browserStageHabitatScript() {
  return `const MAX_DECORATIONS=${MAX_DECORATIONS};const DEFAULT_LAYOUT=${JSON.stringify(DEFAULT_LAYOUT)};const decorationGlyph=${decorationGlyph.toString()};const decorationColor=${decorationColor.toString()};window.normalizeHabitatLayout=${normalizeHabitatLayout.toString()};window.habitatDecorationGlyph=decorationGlyph;window.StageHabitatController=${StageHabitatController.toString()};`;
}

module.exports = { StageHabitatController, browserStageHabitatScript, decorationColor, decorationGlyph, normalizeHabitatLayout };
