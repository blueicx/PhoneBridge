'use strict';

const crypto = require('node:crypto');
const { MOTE_PROFILES } = require('./mote-profiles');

const ACTIONS = Object.freeze(['observe', 'soothe', 'dodge', 'skill']);
const ACTION_LABELS = Object.freeze({ observe: '仔细观察', soothe: '与 Mote 共鸣', dodge: '绕开干扰', skill: '使用 Mote 特长' });
const PROFILE_BY_ID = new Map(MOTE_PROFILES.map(profile => [profile.id, profile]));

const EXPLORATION_ENCOUNTERS = Object.freeze([
  { id: 'echo-beacon', title: '回声信标', prompt: '断续的脉冲正从边缘传来，先判断它在回应什么。', preferredAction: 'skill', outcomes: { observe: '你辨认出信号来自间歇闪烁的边缘。', soothe: '你用平稳节奏回应，急促信号渐渐柔和。', dodge: '你绕开杂讯区，找到了更清晰的接收角度。', skill: '叶狐捕捉到节拍，把散乱信号排成了序列。' } },
  { id: 'wind-trace', title: '逆风脚印', prompt: '一串浅浅的痕迹在风里断开了，看看它们通向哪里。', preferredAction: 'observe', outcomes: { observe: '你比较脚印间距，发现它们正沿着避风处延伸。', soothe: '你放慢脚步，风声里浮出一段轻柔的方向提示。', dodge: '你避开松动地面，绕到了脚印汇合的位置。', skill: '你借 Mote 的敏捷追上了最后一缕风痕。' } },
  { id: 'drifting-shadow', title: '漂移的影子', prompt: '影子总比光线慢一步，轻轻回应或换个角度再看。', preferredAction: 'soothe', outcomes: { observe: '你留意到影子总在光线变化后迟半拍移动。', soothe: '你保持安静，影子终于不再躲闪。', dodge: '你让开晃动的光斑，从另一侧看清了轮廓。', skill: 'Mote 稳住呼吸，替影子留出了安全的空间。' } },
  { id: 'glass-garden', title: '折光花圃', prompt: '碎光像花瓣一样散开，找出能让它们重新相遇的方法。', preferredAction: 'skill', outcomes: { observe: '你找到了颜色最稳定的那片折射面。', soothe: '你轻轻回应，花瓣状光斑重新聚拢。', dodge: '你避开刺眼反光，从柔和角度看见了纹路。', skill: 'Mote 将几束碎光拼成一朵完整的光花。' } },
  { id: 'quiet-bridge', title: '静音桥面', prompt: '桥中央的回声忽然变空了，找到安全通过的节奏。', preferredAction: 'soothe', outcomes: { observe: '你听出桥面回声在中段变得更空。', soothe: '你用熟悉的节奏回应，回声稳稳落在脚边。', dodge: '你绕过松动处，找到了安稳的落脚点。', skill: 'Mote 沿着桥缘标出一条安静又安全的路线。' } },
  { id: 'orbit-courier', title: '绕行信使', prompt: '一位信使正在反复绕圈，判断怎样回应才不会惊扰它。', preferredAction: 'dodge', outcomes: { observe: '你读懂信使反复绕行是在确认周围是否安全。', soothe: '你给出温和回应，信使停下片刻交换了消息。', dodge: '你预判它的弧线，在不惊扰它的情况下让出通路。', skill: 'Mote 用自己的节奏接住了信使递来的信号。' } },
  { id: 'prism-rain', title: '棱镜微雨', prompt: '细雨里藏着几种颜色，选一处清晰的折光开始观察。', preferredAction: 'observe', outcomes: { observe: '你捕捉到雨丝里最清晰的一道青紫折光。', soothe: '你安静等待，细碎光点落成了柔和的环。', dodge: '你避开密集反光，找到一处仍能看见全景的位置。', skill: 'Mote 把散开的光点串成一小段方向标记。' } },
  { id: 'moss-shelter', title: '苔痕庇护所', prompt: '苔痕围出了小小的避风边界，试着守住这份安静。', preferredAction: 'soothe', outcomes: { observe: '你发现苔痕围出了一个避风的小小边界。', soothe: '你放轻动作，守护的微光在边界上慢慢亮起。', dodge: '你从脆弱的苔面旁绕行，没有留下新的扰动。', skill: 'Mote 找到稳固的石面，让这处庇护更容易辨认。' } },
  { id: 'stone-echo', title: '石缝回响', prompt: '石缝里传回不同层次的声音，找到最稳妥的入口。', preferredAction: 'skill', outcomes: { observe: '你沿着回声强弱，定位到藏在石缝里的空腔。', soothe: '你轻声回应，空腔回送了一段温和的低音。', dodge: '你避开碎石区，从安全一侧听清了回响。', skill: 'Mote 分辨出回声的层次，标记了最稳的入口。' } },
  { id: 'tide-marker', title: '潮汐刻度', prompt: '水纹的间隔正在改变，留心它转向前留下的刻度。', preferredAction: 'observe', outcomes: { observe: '你比较水纹间隔，读出潮汐正缓慢转向。', soothe: '你顺着水声放慢呼吸，波纹恢复了平稳节奏。', dodge: '你退到干燥边缘，仍看清了水纹留下的刻度。', skill: 'Mote 把潮声节拍换算成了简单的方向提示。' } },
  { id: 'lost-signal', title: '迷途信号', prompt: '信号像是被遮住而非消失，试着为它找回节拍。', preferredAction: 'skill', outcomes: { observe: '你分辨出信号并未消失，只是被弱回波遮住。', soothe: '你连续发出温和回应，远处传来迟到的答复。', dodge: '你绕开干扰源，信号强度重新稳定下来。', skill: 'Mote 重新排列脉冲，帮信号找回了原本的节奏。' } },
  { id: 'cloud-seed', title: '云端种子', prompt: '一枚轻盈的种子正寻找平缓气流，帮它避开湍流。', preferredAction: 'dodge', outcomes: { observe: '你看出种子会随气流转向较平缓的一侧。', soothe: '你用安稳节奏陪它停靠，种子落在柔软云絮上。', dodge: '你避开湍流，为种子让出一条轻缓的漂行路径。', skill: 'Mote 记下种子的轨迹，留下了下次可循的标记。' } },
]);

const ENCOUNTER_BY_ID = new Map(EXPLORATION_ENCOUNTERS.map((item, index) => [item.id, index]));

function normalizeMoteId(value) {
  const id = String(value || '');
  return PROFILE_BY_ID.has(id) ? id : 'mote';
}

function encounterIndex({ eventId, encounterId, id } = {}) {
  const authoredId = String(id || encounterId || '');
  if (ENCOUNTER_BY_ID.has(authoredId)) return ENCOUNTER_BY_ID.get(authoredId);
  const legacyMatch = /^encounter_(\d{2})$/.exec(authoredId);
  if (legacyMatch) {
    const number = Number(legacyMatch[1]);
    if (number >= 1 && number <= 20) return (number - 1) % EXPLORATION_ENCOUNTERS.length;
  }
  const normalizedEventId = String(eventId || '').trim();
  if (!normalizedEventId || normalizedEventId.length > 180) throw new Error('eventId is required for encounter selection');
  const digest = crypto.createHash('sha256').update(normalizedEventId).digest();
  return digest.readUInt32BE(0) % EXPLORATION_ENCOUNTERS.length;
}

function resolveExplorationEncounter({ eventId, clueType, actions, action, encounterId, id, moteId } = {}) {
  const encounter = EXPLORATION_ENCOUNTERS[encounterIndex({ eventId, encounterId, id })];
  const selected = [...new Set((Array.isArray(actions) ? actions : [action])
    .map(value => String(value || ''))
    .filter(value => ACTIONS.includes(value)))][0] || 'observe';
  const profileId = normalizeMoteId(moteId);
  const profile = PROFILE_BY_ID.get(profileId);
  const preferred = selected === encounter.preferredAction;
  return {
    id: encounter.id,
    title: encounter.title,
    prompt: encounter.prompt,
    action: selected,
    actionLabel: ACTION_LABELS[selected],
    quality: preferred ? '默契契合' : '别样发现',
    outcome: encounter.outcomes[selected],
    moteId: profile.id,
    moteName: profile.name,
    xpBonus: preferred ? 2 : 1,
  };
}

function describeExplorationEncounter({ eventId, encounterId, id, moteId } = {}) {
  const encounter = EXPLORATION_ENCOUNTERS[encounterIndex({ eventId, encounterId, id })];
  const profile = PROFILE_BY_ID.get(normalizeMoteId(moteId));
  return { id: encounter.id, title: encounter.title, prompt: encounter.prompt, moteId: profile.id, moteName: profile.name };
}

module.exports = {
  ACTIONS,
  ACTION_LABELS,
  EXPLORATION_ENCOUNTERS,
  describeExplorationEncounter,
  normalizeMoteId,
  resolveExplorationEncounter,
};
