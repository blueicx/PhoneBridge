'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  DEFAULT_THEME_ID,
  UI_THEMES,
  UI_THEME_TOKENS,
  buildThemeCss,
  contrastRatio,
  compositeColor,
  parseColor,
} = require('./ui-themes');

test('shared UI theme catalog has complete semantic tokens and Night Core default', () => {
  assert.equal(DEFAULT_THEME_ID, 'noir');
  assert.deepEqual(UI_THEMES.map(theme => theme.id), ['glass', 'liquid', 'noir']);
  for (const theme of UI_THEMES) {
    assert.ok(theme.label);
    for (const token of UI_THEME_TOKENS) {
      assert.match(theme.tokens[token], /^#[\da-f]{6}(?:[\da-f]{2})?$/i, `${theme.id}.${token}`);
    }
  }
});

test('theme text and controls retain WCAG AA contrast over every surface', () => {
  for (const theme of UI_THEMES) {
    for (const backgroundToken of ['backgroundTop', 'backgroundBottom']) {
      const background = theme.tokens[backgroundToken];
      for (const surfaceToken of ['panel', 'card', 'input', 'buttonFill']) {
        const surface = compositeColor(theme.tokens[surfaceToken], background);
        for (const textToken of ['textPrimary', 'textSecondary', 'buttonText']) {
          assert.ok(
            contrastRatio(theme.tokens[textToken], surface) >= 4.5,
            `${theme.id}.${textToken} must contrast with ${surfaceToken} on ${backgroundToken}`,
          );
        }
      }
    }
  }
});

test('web theme CSS is generated from shared tokens and is scoped by selected theme', () => {
  const css = buildThemeCss();
  assert.match(css, /:root\[data-theme="noir"\]/);
  assert.match(css, /--background-top:/);
  assert.match(css, /--focus:/);
  for (const theme of UI_THEMES) assert.ok(css.includes(`[data-theme="${theme.id}"]`));
});

test('shared eight-digit colors use Android-compatible AARRGGBB ordering', () => {
  assert.deepEqual(parseColor('#F2122344'), { r: 18, g: 35, b: 68, a: 242 / 255 });
  assert.deepEqual(parseColor('#070B16'), { r: 7, g: 11, b: 22, a: 1 });
  assert.ok(buildThemeCss().includes('--panel:rgba(18, 35, 68, 0.949)'));
});
