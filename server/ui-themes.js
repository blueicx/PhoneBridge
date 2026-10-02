'use strict';

const fs = require('node:fs');
const path = require('node:path');

const UI_THEME_TOKENS = Object.freeze([
  'backgroundTop', 'backgroundBottom', 'panel', 'card', 'input', 'bubble', 'stroke',
  'accent', 'secondary', 'textPrimary', 'textSecondary', 'buttonText', 'buttonFill',
  'buttonPressed', 'success', 'warning', 'danger', 'focus',
]);
const LEGACY_THEME_IDS = Object.freeze({
  AURORA_GLASS: 'glass',
  LIQUID_MOTION: 'liquid',
  CIRCUIT_NOIR: 'noir',
});

function parseColor(value) {
  const match = /^#([\da-f]{6}|[\da-f]{8})$/i.exec(String(value || ''));
  if (!match) throw new TypeError(`Invalid theme color: ${String(value)}`);
  const hex = match[1];
  const offset = hex.length === 8 ? 2 : 0;
  return {
    r: Number.parseInt(hex.slice(offset, offset + 2), 16),
    g: Number.parseInt(hex.slice(offset + 2, offset + 4), 16),
    b: Number.parseInt(hex.slice(offset + 4, offset + 6), 16),
    a: offset ? Number.parseInt(hex.slice(0, 2), 16) / 255 : 1,
  };
}

function colorHex({ r, g, b }) {
  return `#${[r, g, b].map(value => Math.round(value).toString(16).padStart(2, '0')).join('')}`;
}

function compositeColor(foreground, background) {
  const fg = parseColor(foreground);
  const bg = parseColor(background);
  const alpha = fg.a + bg.a * (1 - fg.a);
  if (alpha <= 0) return '#000000';
  return colorHex({
    r: (fg.r * fg.a + bg.r * bg.a * (1 - fg.a)) / alpha,
    g: (fg.g * fg.a + bg.g * bg.a * (1 - fg.a)) / alpha,
    b: (fg.b * fg.a + bg.b * bg.a * (1 - fg.a)) / alpha,
  });
}

function luminance(value) {
  const color = parseColor(value);
  const channel = component => {
    const normalized = component / 255;
    return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
}

function contrastRatio(foreground, background) {
  const first = luminance(foreground);
  const second = luminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

function validateCatalog(catalog) {
  if (!catalog || catalog.version !== 1 || !Array.isArray(catalog.themes) || !catalog.themes.length) {
    throw new TypeError('UI theme catalog must contain version 1 themes');
  }
  const ids = new Set();
  for (const theme of catalog.themes) {
    if (!theme || !/^[a-z0-9-]+$/.test(theme.id || '') || !String(theme.label || '').trim() || ids.has(theme.id)) {
      throw new TypeError('UI theme id and label must be valid and unique');
    }
    ids.add(theme.id);
    for (const token of UI_THEME_TOKENS) parseColor(theme.tokens?.[token]);
  }
  if (!ids.has(catalog.defaultTheme)) throw new TypeError('Default UI theme must exist in the catalog');
  return catalog;
}

const UI_THEME_CATALOG = validateCatalog(JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', 'shared', 'ui-themes.json'), 'utf8'),
));
const DEFAULT_THEME_ID = UI_THEME_CATALOG.defaultTheme;
const UI_THEMES = Object.freeze(UI_THEME_CATALOG.themes.map(theme => Object.freeze({
  ...theme,
  tokens: Object.freeze({ ...theme.tokens }),
})));

function cssColor(value) {
  const color = parseColor(value);
  if (color.a >= 0.999) return colorHex(color);
  return `rgba(${color.r}, ${color.g}, ${color.b}, ${Number(color.a.toFixed(3))})`;
}

function cssTokenName(token) {
  return token.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);
}

function buildThemeCss(themes = UI_THEMES) {
  return themes.map(theme => {
    const declarations = Object.entries(theme.tokens)
      .map(([token, value]) => `--${cssTokenName(token)}:${cssColor(value)}`)
      .join(';');
    return `:root[data-theme="${theme.id}"]{${declarations}}`;
  }).join('\n');
}

module.exports = {
  DEFAULT_THEME_ID,
  LEGACY_THEME_IDS,
  UI_THEMES,
  UI_THEME_CATALOG,
  UI_THEME_TOKENS,
  buildThemeCss,
  compositeColor,
  contrastRatio,
  validateCatalog,
  parseColor,
};
