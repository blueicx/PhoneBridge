'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { StageHabitatController, normalizeHabitatLayout, decorationGlyph, browserStageHabitatScript } = require('./web-stage-habitat');
const { DECORATIONS, ITEMS } = require('./reality-engine');

test('web layout projection migrates legacy IDs and preserves normalized shared coordinates', () => {
  const result = normalizeHabitatLayout(DECORATIONS, {
    decorations: ['decor_02', 'unknown', 'decor_01', 'decor_02'],
    layout: [{ id: 'decor_02', x: 0.61, y: 0.44, rotation: -15 }, { id: 'decor_01', x: 2, y: -1 }],
  });
  assert.equal(result.layoutVersion, 2);
  assert.deepEqual(result.decorations, ['decor_02', 'decor_01']);
  assert.deepEqual(result.layout[0], { id: 'decor_02', x: 0.61, y: 0.44, rotation: -15 });
  assert.deepEqual(result.layout[1], { id: 'decor_01', x: 0.385, y: 0.79, rotation: 0 });
  assert.equal(result.items[0].visualType, 'moss-stone');
});

test('each explicit decoration visual has a distinct safe glyph fallback', () => {
  const glyphs = DECORATIONS.map(item => decorationGlyph(item.visualType));
  assert.ok(glyphs.every(value => typeof value === 'string' && value.length > 0));
  assert.equal(decorationGlyph('untrusted-type'), '✦');
  assert.equal(new Set(glyphs.slice(0, 10)).size, 10);
});

test('browser layout and glyph helpers are the same code as the Node projection', () => {
  const sandbox = { window: {} };
  vm.runInNewContext(browserStageHabitatScript(), sandbox);
  assert.deepEqual(
    JSON.parse(JSON.stringify(sandbox.window.normalizeHabitatLayout(DECORATIONS, { decorations: ['decor_01'] }))),
    normalizeHabitatLayout(DECORATIONS, { decorations: ['decor_01'] }),
  );
  assert.equal(sandbox.window.habitatDecorationGlyph('wind-chime'), decorationGlyph('wind-chime'));
  assert.equal(typeof sandbox.window.StageHabitatController, 'function');
});

class FakeElement {
  constructor(tag = 'div') {
    this.tagName = tag.toUpperCase(); this.children = []; this.listeners = new Map();
    this.dataset = {}; this.style = {}; this.attributes = {}; this.value = '';
    this.textContent = ''; this.disabled = false; this.hidden = false; this.className = '';
  }
  addEventListener(name, callback) { const callbacks = this.listeners.get(name) || []; callbacks.push(callback); this.listeners.set(name, callbacks); }
  emit(name, event = {}) { for (const callback of this.listeners.get(name) || []) callback({ preventDefault() {}, target: this, currentTarget: this, ...event }); }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  append(...items) { for (const item of items) this.children.push(...(item?.isFragment ? item.children : [item])); }
  appendChild(item) { this.append(item); return item; }
  replaceChildren(...items) { this.children = []; this.append(...items); }
  setPointerCapture() {}
  getBoundingClientRect() { return { left: 0, top: 0, width: 1000, height: 600 }; }
}

function createHabitatDom() {
  const elements = new Map(['stageHabitat', 'habitatCatalogSelect', 'habitatItemList', 'habitatStatus', 'habitatAddButton', 'habitatEditButton', 'habitatResetButton', 'habitatInventory', 'habitatCrafting'].map(id => [id, new FakeElement(id.includes('Button') ? 'button' : id.includes('Select') ? 'select' : 'div')]));
  return {
    elements,
    document: {
      getElementById: id => elements.get(id) || null,
      createElement: tag => new FakeElement(tag),
      createDocumentFragment: () => Object.assign(new FakeElement('#fragment'), { isFragment: true }),
    },
  };
}

test('stage habitat controller loads cached/online state, exposes safe interactions and persists drag edits', async () => {
  const { document, elements } = createHabitatDom();
  const calls = [];
  const feedback = [];
  const storageMap = new Map();
  const storage = { getItem: key => storageMap.get(key) || null, setItem: (key, value) => storageMap.set(key, value) };
  let state = { habitat: { layoutVersion: 2, decorations: ['decor_01'], layout: [{ id: 'decor_01', x: 0.16, y: 0.79, rotation: 0 }] } };
  const api = async (path, options = {}) => {
    calls.push({ path, options });
    if (path === '/api/reality/catalog') return { catalog: { decorations: DECORATIONS, items: ITEMS } };
    if (path === '/api/reality/state') return { state };
    if (path === '/api/reality/crafting') return { recipes: [] };
    if (options.method === 'PATCH') {
      const payload = JSON.parse(options.body);
      if (payload.action === 'move') state = { habitat: { decorations: ['decor_01'], layout: [{ id: 'decor_01', x: payload.x, y: payload.y, rotation: payload.rotation }] } };
      return { ok: true, state };
    }
    throw new Error(`unexpected request ${path}`);
  };
  const controller = new StageHabitatController({ document, api, storage, onFeedback: message => feedback.push(message) });
  await controller.load();
  assert.equal(elements.get('stageHabitat').children.length, 1);
  const decoration = elements.get('stageHabitat').children[0];
  assert.equal(decoration.textContent, '✦');
  assert.equal(decoration.attributes['aria-label'], '互动：星灯');
  decoration.emit('click');
  assert.match(feedback[0], /星灯亮起/);
  assert.equal(JSON.parse(storageMap.get('phonebridge:web:habitat:v2')).habitat.layoutVersion, 2);

  elements.get('habitatEditButton').emit('click');
  const editable = elements.get('stageHabitat').children[0];
  editable.emit('pointerdown', { button: 0, pointerId: 7 });
  editable.emit('pointermove', { pointerId: 7, clientX: 620, clientY: 390 });
  editable.emit('pointerup', { pointerId: 7 });
  editable.emit('click');
  await new Promise(resolve => setImmediate(resolve));
  const moveCall = calls.find(call => call.path === '/api/reality/habitat' && call.options.method === 'PATCH');
  assert.deepEqual(JSON.parse(moveCall.options.body), { action: 'move', decorationId: 'decor_01', x: 0.62, y: 0.65, rotation: 0 });
  assert.equal(controller.habitat.layout[0].x, 0.62);
  assert.equal(controller.editing, true);
  assert.equal(feedback.length, 1);
});

test('stage habitat controller uses local cache on offline startup and reports retryable edits', async () => {
  const { document, elements } = createHabitatDom();
  const cache = JSON.stringify({ catalog: DECORATIONS, habitat: { decorations: ['decor_02'] } });
  const feedback = [];
  const controller = new StageHabitatController({
    document,
    storage: { getItem: () => cache, setItem() {} },
    api: async () => { throw new Error('offline'); },
    onFeedback: message => feedback.push(message),
  });
  await controller.load();
  assert.equal(elements.get('stageHabitat').children.length, 1);
  assert.match(elements.get('habitatStatus').textContent, /离线/);
  assert.equal(await controller.move('decor_02', 0.5, 0.5), false);
  assert.match(feedback[0], /未同步/);
});

test('stage inventory renders owned effects and recipe gaps, and item use updates the confirmed mirror', async () => {
  const { document, elements } = createHabitatDom();
  let state = {
    habitat: { decorations: [] }, inventory: { item_21: 1, item_31: 1 }, loadout: [], activeEffects: [],
    equipmentEffects: { xpBonus: 0, lightXpBonus: 0, extraItem: 0, homeComfort: 0 },
  };
  const requests = [];
  const api = async (path, options = {}) => {
    requests.push({ path, options });
    if (path === '/api/reality/catalog') return { catalog: { decorations: DECORATIONS, items: ITEMS } };
    if (path === '/api/reality/state') return { state };
    if (path === '/api/reality/crafting') return { recipes: [{ recipe: { id: 'recipe_01', name: '安定香' }, ingredients: [{ name: '潮汐玻璃', owned: 0, required: 1, missing: 1, sources: ['地点线索'] }], missing: ['item_01'], canCraft: false, output: ITEMS[20] }] };
    if (path === '/api/reality/items/use') {
      const payload = JSON.parse(options.body);
      assert.equal(payload.itemId, 'item_21');
      state = { ...state, inventory: { ...state.inventory, item_21: 0 }, activeEffects: [{ itemId: 'item_21', label: '专注增益', expiresAt: Date.now() + 60_000 }] };
      return { ok: true, state, receipt: { effect: { description: '30 分钟内探索经验提高 50%。' } } };
    }
    throw new Error(`unexpected request ${path}`);
  };
  const controller = new StageHabitatController({ document, api });
  await controller.load();
  assert.match(elements.get('habitatInventory').children[0].children[0].textContent, /晨露 ×1.*30 分钟/);
  assert.match(elements.get('habitatCrafting').children[0].children[0].textContent, /缺少 潮汐玻璃.*来源：地点线索/);
  elements.get('habitatInventory').children[0].children[1].emit('click');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.filter(item => item.path === '/api/reality/items/use').length, 1);
  assert.equal(controller.realityState.inventory.item_21, 0);
  assert.equal(controller.activeEffectSummary().startsWith('生效中：专注增益'), true);
});

test('uncertain item-use retries preserve the same eventId across a page reload', async () => {
  const storageMap = new Map();
  const storage = { getItem: key => storageMap.get(key) || null, setItem: (key, value) => storageMap.set(key, value) };
  const initialState = { habitat: { decorations: [] }, inventory: { item_21: 1 }, loadout: [], activeEffects: [] };
  const createApi = (mutation) => async (path, options = {}) => {
    if (path === '/api/reality/catalog') return { catalog: { decorations: DECORATIONS, items: ITEMS } };
    if (path === '/api/reality/state') return { state: initialState };
    if (path === '/api/reality/crafting') return { recipes: [] };
    if (path === '/api/reality/items/use') return mutation(options);
    throw new Error(`unexpected request ${path}`);
  };
  const firstDom = createHabitatDom();
  const first = new StageHabitatController({ document: firstDom.document, api: createApi(async () => { throw new Error('response lost'); }), storage });
  await first.load();
  assert.equal(await first.useItem('item_21'), false);
  assert.equal(firstDom.elements.get('habitatInventory').children[0].children[1].textContent, '重试确认');
  const cachedAfterFailure = JSON.parse(storageMap.get('phonebridge:web:habitat:v2'));
  const pendingId = cachedAfterFailure.pendingUses.item_21;
  assert.match(pendingId, /^web-use-/);

  let retriedId = '';
  const second = new StageHabitatController({
    document: createHabitatDom().document,
    storage,
    api: createApi(async options => {
      retriedId = JSON.parse(options.body).eventId;
      return { ok: true, state: { ...initialState, inventory: { item_21: 0 } }, receipt: { effect: { description: '已确认' } } };
    }),
  });
  await second.load();
  assert.equal(await second.useItem('item_21'), true);
  assert.equal(retriedId, pendingId);
  assert.equal(JSON.parse(storageMap.get('phonebridge:web:habitat:v2')).pendingUses.item_21, undefined);
});

test('an already-consumed item remains retryable when its successful response was lost', async () => {
  const storageMap = new Map();
  const storage = { getItem: key => storageMap.get(key) || null, setItem: (key, value) => storageMap.set(key, value) };
  const catalog = { catalog: { decorations: DECORATIONS, items: ITEMS } };
  let serverState = { habitat: { decorations: [] }, inventory: { item_21: 1 }, loadout: [], activeEffects: [] };
  const makeApi = (use) => async (path, options = {}) => {
    if (path === '/api/reality/catalog') return catalog;
    if (path === '/api/reality/state') return { state: serverState };
    if (path === '/api/reality/crafting') return { recipes: [] };
    if (path === '/api/reality/items/use') return use(options);
    throw new Error(`unexpected request ${path}`);
  };

  const firstDom = createHabitatDom();
  const first = new StageHabitatController({
    document: firstDom.document, api: makeApi(async () => {
      serverState = { ...serverState, inventory: { item_21: 0 }, activeEffects: [{ itemId: 'item_21', label: '专注增益', expiresAt: Date.now() + 60_000 }] };
      throw new Error('response lost after server commit');
    }), storage,
  });
  await first.load();
  assert.equal(await first.useItem('item_21'), false);
  const pendingId = JSON.parse(storageMap.get('phonebridge:web:habitat:v2')).pendingUses.item_21;

  let retriedId = '';
  const dom = createHabitatDom();
  const second = new StageHabitatController({
    document: dom.document, storage,
    api: makeApi(async options => {
      retriedId = JSON.parse(options.body).eventId;
      return { ok: true, duplicate: true, state: serverState, receipt: { effect: { description: '已由节点确认' } } };
    }),
  });
  await second.load();
  const pendingRow = dom.elements.get('habitatInventory').children.find(row => row.children?.[0]?.textContent.includes('待确认'));
  assert.ok(pendingRow, 'pending item must remain visible after inventory reaches zero');
  pendingRow.children[1].emit('click');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(retriedId, pendingId);
  assert.equal(JSON.parse(storageMap.get('phonebridge:web:habitat:v2')).pendingUses.item_21, undefined);
});

test('a consumed recipe remains retryable with its original eventId after response loss', async () => {
  const storageMap = new Map();
  const storage = { getItem: key => storageMap.get(key) || null, setItem: (key, value) => storageMap.set(key, value) };
  const catalog = { catalog: { decorations: DECORATIONS, items: ITEMS } };
  const recipe = { recipe: { id: 'recipe_01', name: '配方一', input: ['item_01', 'item_08'], output: 'item_21' }, ingredients: [], missing: [], canCraft: true, output: ITEMS[20] };
  let serverState = { habitat: { decorations: [] }, inventory: { item_01: 1, item_08: 1 }, loadout: [], activeEffects: [] };
  let canCraft = true;
  const makeApi = (craft) => async (path, options = {}) => {
    if (path === '/api/reality/catalog') return catalog;
    if (path === '/api/reality/state') return { state: serverState };
    if (path === '/api/reality/crafting' && !options.method) return { recipes: [{ ...recipe, canCraft, missing: canCraft ? [] : ['item_01', 'item_08'] }] };
    if (path === '/api/reality/crafting') return craft(options);
    throw new Error(`unexpected request ${path}`);
  };

  const firstDom = createHabitatDom();
  const first = new StageHabitatController({
    document: firstDom.document, api: makeApi(async () => {
      serverState = { ...serverState, inventory: { item_21: 1 } }; canCraft = false;
      throw new Error('response lost after craft commit');
    }), storage,
  });
  await first.load();
  assert.equal(await first.craft('recipe_01'), false);
  assert.equal(firstDom.elements.get('habitatCrafting').children[0].children[1].textContent, '重试确认');
  const pendingId = JSON.parse(storageMap.get('phonebridge:web:habitat:v2')).pendingCrafts.recipe_01;

  let retriedId = '';
  const dom = createHabitatDom();
  const second = new StageHabitatController({
    document: dom.document, storage,
    api: makeApi(async options => {
      retriedId = JSON.parse(options.body).eventId;
      return { ok: true, duplicate: true, state: serverState, receipt: { recipe: { name: '配方一' } } };
    }),
  });
  await second.load();
  const row = dom.elements.get('habitatCrafting').children.find(item => item.children?.[0]?.textContent.includes('配方一'));
  assert.ok(row?.children[1] && !row.children[1].disabled, 'pending craft confirmation must be enabled despite missing materials');
  assert.match(row.children[1].textContent, /重试/);
  row.children[1].emit('click');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(retriedId, pendingId);
  assert.equal(JSON.parse(storageMap.get('phonebridge:web:habitat:v2')).pendingCrafts.recipe_01, undefined);
});

test('transient HTTP errors retain receipts while a definite item rejection permits a new eventId', async () => {
  const storageMap = new Map();
  const storage = { getItem: key => storageMap.get(key) || null, setItem: (key, value) => storageMap.set(key, value) };
  const dom = createHabitatDom();
  const eventIds = [];
  let statusCode = 429;
  const controller = new StageHabitatController({
    document: dom.document, storage,
    api: async (path, options = {}) => {
      if (path === '/api/reality/catalog') return { catalog: { decorations: DECORATIONS, items: ITEMS } };
      if (path === '/api/reality/state') return { state: { habitat: { decorations: [] }, inventory: { item_21: 1 }, loadout: [], activeEffects: [] } };
      if (path === '/api/reality/crafting') return { recipes: [] };
      if (path === '/api/reality/items/use') {
        eventIds.push(JSON.parse(options.body).eventId);
        throw Object.assign(new Error(`HTTP ${statusCode}: item is not owned`), { status: statusCode });
      }
      throw new Error(`unexpected request ${path}`);
    },
  });
  await controller.load();
  assert.equal(await controller.useItem('item_21'), false);
  assert.equal(controller.pendingUses.has('item_21'), true);
  assert.equal(await controller.useItem('item_21'), false);
  assert.equal(eventIds[0], eventIds[1]);
  statusCode = 400;
  assert.equal(await controller.useItem('item_21'), false);
  assert.equal(controller.pendingUses.has('item_21'), false);
  assert.match(dom.elements.get('habitatStatus').textContent, /明确拒绝/);
  assert.equal(await controller.useItem('item_21'), false);
  assert.equal(eventIds.length, 4);
  assert.notEqual(eventIds[0], eventIds[3]);
});
