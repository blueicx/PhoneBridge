'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MOTE_PROFILES } = require('./mote-profiles');
const { MOTE_STAGE_PRESETS, renderMoteSvg, browserRendererScript } = require('./web-stage');

test('every current Mote visualPreset renders a distinct accessible vector silhouette', () => {
  const silhouettes = new Set();
  for (const profile of MOTE_PROFILES) {
    const svg = renderMoteSvg(profile, { haloColor: profile.colors.primary, gaze: 'settled', motionIntensity: 0.5 });
    assert.match(svg, new RegExp(`data-preset="${profile.visualPreset}"`));
    assert.match(svg, /aria-hidden="true"/);
    const silhouette = svg.match(/data-silhouette="([^"]+)"/)?.[1];
    assert.ok(silhouette, `${profile.id} should have a named silhouette`);
    silhouettes.add(silhouette);
  }
  assert.equal(silhouettes.size, MOTE_PROFILES.length);
  assert.equal(Object.keys(MOTE_STAGE_PRESETS).length, MOTE_PROFILES.length);
});

test('Mote SVG uses validated profile colors and behavior without accepting markup', () => {
  const svg = renderMoteSvg({
    id: 'unknown', name: '<img src=x>', visualPreset: 'not-a-preset',
    colors: { primary: 'url(javascript:alert(1))', secondary: '#fff' },
  }, { haloColor: 'red; background:url(javascript:alert(1))', gaze: 'unknown', motionIntensity: 9 });
  assert.match(svg, /data-preset="star-core"/);
  assert.match(svg, /fill="#8ea7ff"/);
  assert.doesNotMatch(svg, /javascript:|<img|url\(javascript:/i);
  assert.match(svg, /data-gaze="settled"/);
  assert.match(svg, /aria-hidden="true"/);
});

test('browser renderer is the same deterministic renderer used by Node tests', () => {
  const script = browserRendererScript();
  assert.match(script, /window\.renderMoteStageSvg/);
  assert.match(script, /star-core/);
  assert.match(script, /const FACE_BY_PRESET=/);
  assert.match(script, /const VALID_GAZES=new Set/);
  assert.match(script, /const DEFAULT_COLORS=/);
});
