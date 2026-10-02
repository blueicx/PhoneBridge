'use strict';

const MOTE_STAGE_PRESETS = Object.freeze({
  'star-core': { silhouette: 'star-orbit', shape: '<circle cx="240" cy="178" r="67"/><path d="M240 82l16 31 35-9-9 35 31 16-31 16 9 35-35-9-16 31-16-31-35 9 9-35-31-16 31-16-9-35 35 9z" opacity=".36"/>' },
  'leaf-fox': { silhouette: 'leaf-tailed-fox', shape: '<path d="M188 143l-30-53 57 27 25-17 25 17 57-27-30 53q15 23 11 54l48-15q17 24-4 43-28 24-64-12-21 29-63 23-42-6-51-42-37 23-61-1-19-19 1-42z"/><path d="M306 205q42-36 68-1-5 28-41 28" fill="none" stroke="@S@" stroke-width="13" stroke-linecap="round"/>' },
  'mist-cat': { silhouette: 'mist-eared-cat', shape: '<path d="M184 139l-9-55 51 32q14-7 28-7t28 7l51-32-9 55q17 22 13 52-5 42-55 49-50 7-83-22-31-27-15-79z"/><path d="M169 224q-42 11-25 42 9 16 30 8" fill="none" stroke="@S@" stroke-width="10" stroke-linecap="round" opacity=".7"/>' },
  'mecha-beast': { silhouette: 'armored-hex-beast', shape: '<path d="M187 119l26-24 20 26 20-26 26 24 22 8 9 39-11 44-34 28h-60l-34-28-11-44 9-39z"/><path d="M195 161h28m34 0h28M214 196h52M186 130l-23-18v38m131-20 23-18v38" fill="none" stroke="@S@" stroke-width="7" stroke-linecap="round"/>' },
  'cloud-whale': { silhouette: 'cloud-whale', shape: '<path d="M155 177q0-50 61-55 27-37 62-7 59-1 61 49 38 30 1 62-25 21-66 5-41 31-85 5-32 0-34-33z"/><path d="M338 188q55-38 75 1-20 16-20 38-31-9-55-39zM191 224l-20 25m52-17-6 28"/><circle cx="317" cy="165" r="5" fill="@S@"/>' },
  rimuru: { silhouette: 'adaptive-droplet', shape: '<path d="M240 88q19 39 54 68 37 32 30 69-7 47-84 50-77-3-84-50-7-37 30-69 35-29 54-68z"/><path d="M195 232q45 26 90 0" fill="none" stroke="@S@" stroke-width="8" stroke-linecap="round" opacity=".8"/>' },
  'ember-sprig': { silhouette: 'flame-sprig', shape: '<path d="M245 89q30 39 8 62 31-12 42-44 47 46 35 92-11 47-83 60-72-13-83-60-12-46 35-92 11 32 42 44-22-23 4-62z"/><path d="M240 151q19 24 7 39 23-8 26-23 24 27 16 49-8 22-49 27-41-5-49-27-8-22 16-49 3 15 26 23-12-15 7-39z" fill="@S@" opacity=".7"/>' },
  'prism-moth': { silhouette: 'prismatic-moth', shape: '<path d="M225 158Q166 83 137 133q-20 42 57 70-75 22-46 66 39 25 90-48M255 158q59-75 88-25 20 42-57 70 75 22 46 66-39 25-90-48"/><path d="M240 137l18 42-18 57-18-57zM230 140q-27-42-47-39m67 39q27-42 47-39" fill="none" stroke="@S@" stroke-width="8" stroke-linecap="round"/>' },
  'moss-tortoise': { silhouette: 'moss-shell-tortoise', shape: '<path d="M163 190q5-63 77-72 72 9 77 72-4 46-77 49-73-3-77-49z"/><path d="M188 183q9-44 52-48 43 4 52 48-15 20-52 21-37-1-52-21z" fill="none" stroke="@S@" stroke-width="8"/><circle cx="326" cy="190" r="28"/><path d="M181 224l-12 20m53-5-4 18m49-18 4 18m39-33 12 20" stroke="@S@" stroke-width="10" stroke-linecap="round"/><circle cx="335" cy="184" r="4" fill="#101010"/>' },
  'orbit-raven': { silhouette: 'scanning-raven', shape: '<path d="M177 142q39-45 88-21 32 16 29 52l54 15-54 14q-2 38-45 46-49 8-70-32-19-36-2-74z"/><path d="M285 167l59 6-52 24M213 146q-17-37-47-39 7 35 36 55M198 219q47-3 78 28"/><circle cx="279" cy="163" r="6" fill="@S@"/>' },
  'tide-otter': { silhouette: 'wave-rolling-otter', shape: '<path d="M165 188q15-49 83-51 59 1 69 48 2 31-35 48-31 14-72 5-44-10-45-50z"/><path d="M168 205q-47 24-30 48 24 17 61-5M190 168l-19-14m38 8-11-22m105 44 42 8m-39 5 41 14" fill="none" stroke="@S@" stroke-width="9" stroke-linecap="round"/><circle cx="284" cy="177" r="5" fill="#101010"/>' },
  'moon-deer': { silhouette: 'crescent-antler-deer', shape: '<path d="M240 137q59 0 68 49 8 48-68 55-76-7-68-55 9-49 68-49z"/><path d="M207 143q-26-31-50-18m50 18q-12-27-8-49m8 49q-34-13-42-43m128 43q26-31 50-18m-50 18q12-27 8-49m-8 49q34-13 42-43M214 232l-8 28m60-28 8 28" fill="none" stroke="@S@" stroke-width="8" stroke-linecap="round"/><path d="M190 186q-18-12-26 0m126 0q18-12 26 0" fill="none" stroke="@S@" stroke-width="7"/>' },
  'stone-mole': { silhouette: 'burrowing-stone-mole', shape: '<path d="M167 202q5-68 73-75 68 7 73 75-10 42-73 48-63-6-73-48z"/><path d="M224 198q16-24 32 0 8 14-16 21-24-7-16-21z" fill="@S@"/><path d="M192 238l-19 20m115-20 19 20M187 177l-19-11m140 11 19-11" fill="none" stroke="@S@" stroke-width="10" stroke-linecap="round"/><circle cx="205" cy="177" r="5" fill="#101010"/><circle cx="275" cy="177" r="5" fill="#101010"/>' },
  'wind-marten': { silhouette: 'wind-ribbon-marten', shape: '<path d="M168 195q20-50 81-44 51 8 56 49-4 35-55 43-58 2-82-23z"/><path d="M180 213q-62 29-53-10 7-27 40-35-9 35 31 34m104-17 36-20-13 31 28 15-42 8" fill="none" stroke="@S@" stroke-width="11" stroke-linecap="round" stroke-linejoin="round"/><path d="M195 164l-8-26 29 17m72 17 23-21 2 33"/>' },
  'volt-sparrow': { silhouette: 'pulse-crested-sparrow', shape: '<path d="M173 206q3-58 62-62 47-4 67 38l47 23-50 9q-17 39-61 37-66-2-65-45z"/><path d="M209 150q-11-34 13-52 6 27 30 35m-55 68q28-26 56 1-22 27-56-1z" fill="@S@"/><path d="M345 201l29 8-29 8"/><circle cx="282" cy="183" r="5" fill="#101010"/>' },
  'frost-hare': { silhouette: 'frost-long-eared-hare', shape: '<path d="M198 155q-15-89 12-95 29 2 25 92m18 4q-4-90 25-92 27 8 7 97"/><path d="M176 204q3-49 64-51 61 2 64 51-6 47-64 49-58-2-64-49z"/><path d="M216 232q24 13 48 0" fill="none" stroke="@S@" stroke-width="7" stroke-linecap="round"/>' },
  'bloom-sprite': { silhouette: 'petal-crowned-sprite', shape: '<g transform="translate(240 180)"><ellipse cy="-67" rx="25" ry="48"/><ellipse cy="67" rx="25" ry="48"/><ellipse cx="-67" rx="48" ry="25"/><ellipse cx="67" rx="48" ry="25"/><ellipse cx="-48" cy="-48" rx="24" ry="41" transform="rotate(-45 -48 -48)"/><ellipse cx="48" cy="-48" rx="24" ry="41" transform="rotate(45 48 -48)"/></g><circle cx="240" cy="180" r="43" fill="@S@"/>' },
  'crystal-lizard': { silhouette: 'faceted-crystal-lizard', shape: '<path d="M173 191l39-46 51-14 44 36-10 43-51 31-47-7z"/><path d="M296 174l47-28 20 9-34 31 23 26-58-2M210 219l-28 26m90-19 22 28M226 160l31 50 18-39" fill="none" stroke="@S@" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/><circle cx="281" cy="171" r="5" fill="#101010"/>' },
  'dune-fox': { silhouette: 'sandswept-fox', shape: '<path d="M187 151l-15-45 51 22q17-8 34 0l51-22-15 45q17 25 6 53-14 37-60 40-46-3-60-40-11-28 8-53z"/><path d="M296 212q48 18 57 53-37 19-70-13-17-17-21-36" fill="none" stroke="@S@" stroke-width="13" stroke-linecap="round"/><path d="M166 252q-23 6-34-9m193 11q23 6 34-9" stroke="@S@" stroke-width="6" fill="none"/>' },
  'shadow-moth': { silhouette: 'night-shadow-moth', shape: '<path d="M226 165q-56-84-96-28-27 43 69 74-67 39-28 74 34 17 69-57M254 165q56-84 96-28 27 43-69 74 67 39 28 74-34 17-69-57"/><path d="M240 144v108m-8-99q-20-47-48-48m64 48q20-47 48-48" fill="none" stroke="@S@" stroke-width="8" stroke-linecap="round"/><path d="M161 157q29-10 51 29m107-29q-29-10-51 29M170 255q35-13 54 4m86-4q-35-13-54 4" fill="none" stroke="@P@" stroke-width="5" opacity=".8"/>' },
});

const FACE_BY_PRESET = Object.freeze({
  'cloud-whale': [289, 168], 'tide-otter': [284, 177], 'orbit-raven': [279, 163],
  'volt-sparrow': [282, 183], 'crystal-lizard': [281, 171], 'mecha-beast': [265, 165],
  'stone-mole': [275, 177],
});
const VALID_GAZES = new Set(['settled', 'attentive', 'protective', 'distant-scan', 'tracking', 'reflective', 'scanning', 'ambient']);
const DEFAULT_COLORS = Object.freeze({ primary: '#8ea7ff', secondary: '#d8e2ff' });

function renderMoteSvg(profile = {}, behavior = {}) {
  const presets = MOTE_STAGE_PRESETS;
  const requestedPreset = String(profile.visualPreset || '');
  const presetKey = Object.hasOwn(presets, requestedPreset) ? requestedPreset : 'star-core';
  const preset = presets[presetKey];
  const safeHex = (value, fallback) => /^#[\da-f]{6}$/i.test(String(value || '')) ? String(value) : fallback;
  const primary = safeHex(profile.colors?.primary, DEFAULT_COLORS.primary);
  const secondary = safeHex(profile.colors?.secondary, DEFAULT_COLORS.secondary);
  const halo = safeHex(behavior.haloColor, primary);
  const gaze = VALID_GAZES.has(String(behavior.gaze || '')) ? behavior.gaze : 'settled';
  const intensity = Math.max(0, Math.min(1, Number(behavior.motionIntensity) || 0));
  const face = FACE_BY_PRESET[presetKey] || [240, 178];
  const shape = preset.shape.replaceAll('@P@', primary).replaceAll('@S@', secondary);
  const motion = intensity >= .7 ? 'active' : intensity >= .35 ? 'steady' : 'soft';
  const eyes = `<circle cx="${face[0] - 15}" cy="${face[1]}" r="5" fill="#111820"/><circle cx="${face[0] + 15}" cy="${face[1]}" r="5" fill="#111820"/><path d="M${face[0] - 10} ${face[1] + 20}q10 8 20 0" fill="none" stroke="#17202A" stroke-width="4" stroke-linecap="round" opacity=".72"/>`;
  return `<svg class="mote-svg" viewBox="0 0 480 340" role="presentation" aria-hidden="true" data-preset="${presetKey}" data-silhouette="${preset.silhouette}" data-gaze="${gaze}" style="--mote-primary:${primary};--mote-secondary:${secondary};--mote-halo:${halo}"><defs><radialGradient id="mote-core-light"><stop stop-color="${secondary}" stop-opacity=".78"/><stop offset="1" stop-color="${primary}" stop-opacity=".16"/></radialGradient><filter id="mote-soft-glow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="15"/></filter></defs><ellipse cx="240" cy="264" rx="91" ry="17" fill="${halo}" opacity=".14" filter="url(#mote-soft-glow)"/><circle cx="240" cy="180" r="91" fill="url(#mote-core-light)" opacity=".22"/><g class="mote-silhouette" data-motion="${motion}" fill="${primary}" stroke="${secondary}" stroke-width="4" stroke-linejoin="round" stroke-linecap="round">${shape}${eyes}</g><circle cx="240" cy="180" r="102" fill="none" stroke="${halo}" stroke-opacity=".27" stroke-width="1.5" stroke-dasharray="3 13"/></svg>`;
}

function browserRendererScript() {
  return `const MOTE_STAGE_PRESETS=${JSON.stringify(MOTE_STAGE_PRESETS)};const FACE_BY_PRESET=${JSON.stringify(FACE_BY_PRESET)};const VALID_GAZES=new Set(${JSON.stringify([...VALID_GAZES])});const DEFAULT_COLORS=${JSON.stringify(DEFAULT_COLORS)};window.renderMoteStageSvg=${renderMoteSvg.toString()};`;
}

module.exports = { MOTE_STAGE_PRESETS, browserRendererScript, renderMoteSvg };
