'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MoteStoryController, activeMoteStory, storyStepEventId } = require('./web-stage-stories');

class FakeElement {
  constructor(tagName = 'div') { this.tagName = tagName.toUpperCase(); this.children = []; this.listeners = new Map(); this.textContent = ''; this.hidden = false; this.disabled = false; this.attributes = {}; }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren(...children) { this.children = children; }
  addEventListener(name, listener) { const listeners = this.listeners.get(name) || []; listeners.push(listener); this.listeners.set(name, listeners); }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  async click() { for (const listener of this.listeners.get('click') || []) await listener({ preventDefault() {} }); }
}

function fakeDocument() {
  const nodes = new Map(['moteStoryPanel', 'moteStoryStatus'].map(id => [id, new FakeElement()]));
  return { getElementById: id => nodes.get(id) || null, createElement: tagName => new FakeElement(tagName), nodes };
}

const story = (stepIndex = 0) => ({
  id: 'exclusive-mote', moteId: 'mote', moteName: '星核', title: '星核的第一份推演',
  exclusive: true, started: true, completed: false, claimed: false, stepIndex,
  completion: '完成三步并选择结局', branchChoiceId: '', branchOutcome: '',
  steps: [
    { id: 'chapter-1', title: '铺开星图', description: '第一章' },
    { id: 'chapter-2', title: '验证假设', description: '第二章' },
    { id: 'chapter-3', title: '留下推演', description: '第三章' },
  ],
  currentStep: stepIndex < 3 ? { id: `chapter-${stepIndex + 1}`, title: ['铺开星图', '验证假设', '留下推演'][stepIndex], description: `第${stepIndex + 1}章` } : null,
  branches: [
    { id: 'go-further', title: '继续行动', outcome: '继续', bonusXp: 3 },
    { id: 'keep-at-home', title: '留在家园', outcome: '安放', bonusXp: 0 },
  ],
});

test('active story projection never shows a different Mote story', () => {
  const entries = [story(), { ...story(), id: 'exclusive-sprite', moteId: 'sprite', moteName: '叶狐' }];
  assert.equal(activeMoteStory(entries, 'mote')?.id, 'exclusive-mote');
  assert.equal(activeMoteStory(entries, 'sprite')?.id, 'exclusive-sprite');
  assert.equal(activeMoteStory(entries, 'unknown'), null);
});

test('step event ids are stable for retries and change only when the story step changes', () => {
  assert.equal(storyStepEventId('exclusive-mote', 'chapter-1'), 'story-step:exclusive-mote:chapter-1');
  assert.notEqual(storyStepEventId('exclusive-mote', 'chapter-1'), storyStepEventId('exclusive-mote', 'chapter-2'));
});

test('web story controller safely retries an uncertain step with the same event id', async () => {
  const document = fakeDocument();
  const calls = [];
  let attempts = 0;
  const api = async (path, options = {}) => {
    calls.push({ path, options: JSON.parse(options.body || '{}') });
    attempts += 1;
    if (attempts === 1) throw new Error('connection reset');
    return { story: [story(1)] };
  };
  const controller = new MoteStoryController({ document, api, activeMoteId: 'mote' });
  controller.update([story()]);
  const firstButton = document.nodes.get('moteStoryPanel').children.find(node => node.tagName === 'BUTTON');
  assert.ok(firstButton);
  await firstButton.click();
  assert.match(document.nodes.get('moteStoryStatus').textContent, /重试|未确认|失败/i);
  const retryButton = document.nodes.get('moteStoryPanel').children.find(node => node.tagName === 'BUTTON');
  await retryButton.click();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].path, '/api/motes/story/exclusive-mote/advance');
  assert.equal(calls[0].options.eventId, calls[1].options.eventId);
  assert.equal(calls[0].options.stepId, 'chapter-1');
  assert.equal(controller.stories[0].stepIndex, 1);
});
