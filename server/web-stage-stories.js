'use strict';

function activeMoteStory(stories, activeMoteId) {
  const id = String(activeMoteId || '').trim();
  if (!id || !Array.isArray(stories)) return null;
  return stories.find(story => story?.exclusive === true && String(story.moteId || '') === id) || null;
}

function storyStepEventId(storyId, stepId) {
  return `story-step:${String(storyId || '').trim()}:${String(stepId || '').trim()}`;
}

class MoteStoryController {
  constructor({ document, api, activeMoteId = '', storage = null } = {}) {
    this.document = document;
    this.api = api;
    this.activeMoteId = String(activeMoteId || '');
    this.storage = storage;
    this.root = document?.getElementById('moteStoryPanel');
    this.statusNode = document?.getElementById('moteStoryStatus');
    this.stories = [];
    this.inFlight = false;
    this.loading = null;
    this._loadCache();
    this.render();
  }

  _setStatus(text, isError = false) {
    if (!this.statusNode) return;
    this.statusNode.textContent = String(text || '');
    if (this.statusNode.dataset) this.statusNode.dataset.error = isError ? 'true' : 'false';
    else this.statusNode.setAttribute?.('data-error', isError ? 'true' : 'false');
  }

  _loadCache() {
    try {
      const value = JSON.parse(this.storage?.getItem('phonebridge:web:mote-stories:v1') || 'null');
      if (Array.isArray(value?.stories)) this.stories = value.stories;
    } catch (_) {}
  }

  _saveCache() {
    try { this.storage?.setItem('phonebridge:web:mote-stories:v1', JSON.stringify({ stories: this.stories })); } catch (_) {}
  }

  setActiveMote(id) {
    const next = String(id || '');
    if (next === this.activeMoteId) return;
    this.activeMoteId = next;
    this.render();
  }

  update(stories) {
    if (!Array.isArray(stories)) return false;
    this.stories = stories.filter(item => item && typeof item.id === 'string');
    this._saveCache();
    this.render();
    return true;
  }

  async load() {
    if (this.loading) return this.loading;
    this.loading = (async () => {
      try {
        const result = await this.api('/api/motes/story');
        if (!Array.isArray(result?.story)) throw new Error('剧情状态格式无效');
        this.update(result.story);
        this._setStatus('剧情进度已同步');
        return this.stories;
      } catch (error) {
        this._setStatus(this.stories.length ? `节点离线 · 显示缓存剧情：${error.message}` : `剧情暂不可用：${error.message}`, true);
        return this.stories;
      } finally { this.loading = null; }
    })();
    return this.loading;
  }

  async _submit(path, payload, successText) {
    if (this.inFlight) return false;
    this.inFlight = true;
    this._setStatus('正在保存剧情进度…');
    try {
      const result = await this.api(path, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!Array.isArray(result?.story)) throw new Error('服务端未确认剧情进度');
      this.update(result.story);
      this._setStatus(successText);
      return true;
    } catch (error) {
      this._setStatus(`进度未确认 · 可安全重试：${error.message}`, true);
      return false;
    } finally {
      this.inFlight = false;
      this.render();
    }
  }

  _button(label, action) {
    const button = this.document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.addEventListener('click', action);
    return button;
  }

  render() {
    if (!this.root || !this.document) return;
    const story = activeMoteStory(this.stories, this.activeMoteId);
    this.root.replaceChildren();
    if (!story) {
      const empty = this.document.createElement('p');
      empty.className = 'sub';
      empty.textContent = '当前伙伴的专属剧情将在对应互动后开启。';
      this.root.appendChild(empty);
      return;
    }
    const title = this.document.createElement('h3');
    title.textContent = `${story.moteName || 'Mote'} · ${story.title || '专属剧情'}`;
    this.root.appendChild(title);
    const description = this.document.createElement('p');
    description.className = 'sub';
    description.textContent = story.description || '';
    this.root.appendChild(description);

    if (!story.started && !story.completed) {
      const requirement = this.document.createElement('p');
      requirement.className = 'sub';
      requirement.textContent = `开启条件：${story.completion || '完成一次伙伴互动'}`;
      this.root.appendChild(requirement);
      return;
    }

    const steps = Array.isArray(story.steps) ? story.steps.slice(0, 3) : [];
    const progress = this.document.createElement('p');
    progress.className = 'sub';
    progress.textContent = story.claimed ? '三章完成 · 奖励已领取' : story.completed ? '三章完成 · 选择结局并领取奖励' : `章节进度 ${Math.max(0, Number(story.stepIndex) || 0)}/${steps.length || 3}`;
    this.root.appendChild(progress);

    const list = this.document.createElement('ol');
    list.setAttribute('aria-label', '专属剧情章节');
    steps.forEach((step, index) => {
      const item = this.document.createElement('li');
      const done = index < (Number(story.stepIndex) || 0) || story.completed;
      item.textContent = `${done ? '✓' : index === story.stepIndex && story.started ? '›' : '·'} ${step.title || `第 ${index + 1} 章`}${step.description ? ` · ${step.description}` : ''}`;
      list.appendChild(item);
    });
    this.root.appendChild(list);

    if (story.started && !story.completed && story.currentStep) {
      const step = story.currentStep;
      const button = this._button(`继续剧情 · ${Number(story.stepIndex) + 1}/3`, () => this._submit(
        `/api/motes/story/${encodeURIComponent(story.id)}/advance`,
        { eventId: storyStepEventId(story.id, step.id), stepId: step.id },
        '章节已保存',
      ));
      button.disabled = this.inFlight;
      button.setAttribute('aria-label', `继续${story.moteName || 'Mote'}剧情第${Number(story.stepIndex) + 1}章`);
      this.root.appendChild(button);
    }

    if (story.completed && story.exclusive && !story.claimed) {
      if (!story.branchChoiceId) {
        for (const branch of Array.isArray(story.branches) ? story.branches.slice(0, 2) : []) {
          const button = this._button(`${branch.title} · ${branch.bonusXp > 0 ? `+${branch.bonusXp} XP` : '留存回忆'}`, () => this._submit(
            `/api/motes/story/${encodeURIComponent(story.id)}/branch`,
            { choiceId: branch.id },
            '结局已保存',
          ));
          button.disabled = this.inFlight;
          this.root.appendChild(button);
        }
      } else {
        const outcome = this.document.createElement('p');
        outcome.textContent = `结局：${story.branchOutcome || '已选择'}`;
        this.root.appendChild(outcome);
        const button = this._button(`领取 +${story.reward?.xp || story.rewardXp || 0} XP`, () => this._submit(
          `/api/motes/story/${encodeURIComponent(story.id)}/claim`,
          { claimId: `story-claim:${story.id}` },
          '剧情奖励已领取',
        ));
        button.disabled = this.inFlight;
        this.root.appendChild(button);
      }
    }
    if (story.branchOutcome) {
      const outcome = this.document.createElement('p');
      outcome.textContent = `结局回顾：${story.branchOutcome}`;
      this.root.appendChild(outcome);
    }
  }
}

function browserMoteStoriesScript() {
  return `window.activeMoteStory=${activeMoteStory.toString()};window.storyStepEventId=${storyStepEventId.toString()};window.MoteStoryController=${MoteStoryController.toString()};`;
}

module.exports = { MoteStoryController, activeMoteStory, browserMoteStoriesScript, storyStepEventId };
