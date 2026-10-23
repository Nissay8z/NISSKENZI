#!/usr/bin/env node
/**
 * Refreshes the embedded all-country VAVOO metadata catalogue.
 *
 * This intentionally stores only channel metadata and the VAVOO catalogue
 * reference (item.url). It does not resolve or proxy video streams. Android
 * playback remains owned by VavooProvider.resolveStream().
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = process.cwd();
const OUT = path.join(ROOT, 'morocco-tv/app/src/main/assets/vavoo_catalog.m3u');
const IPTV_OUT = path.join(ROOT, 'morocco-tv/app/src/main/assets/iptv_org_catalog.m3u');
const IPTV_PLAYLIST_URL = 'https://iptv-org.github.io/iptv/index.country.m3u';
const LANGUAGE = 'fr';
const REGION = 'US'; // Broad/global VAVOO catalog; do not hard-filter to France.
const CLIENT_VERSION = '3.0.2';
const MAX_PAGES = 300;
const PING_URLS = [
  'https://www.vavoo.tv/api/app/ping',
  'https://vavoo.tv/api/app/ping',
];
const BASE_URLS = [
  'https://vavoo.to',
  'https://kool.to',
  'https://vavoo.top',
  'https://vavoo.net',
];

async function postJson(url, payload, headers = {}, timeoutMs = 25000) {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Accept': 'application/json',
      'Connection': 'close',
      ...headers,
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const raw = await response.text();
  let json;
  try {
    json = JSON.parse(raw);
  } catch {
    throw new Error(`Réponse non JSON de ${new URL(url).host} (HTTP ${response.status}).`);
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} sur ${new URL(url).host}: ${raw.slice(0, 180)}`);
  }
  return json;
}

async function getAddonSignature() {
  const now = Date.now();
  const uniqueId = `nisskenzi-catalog-${crypto.randomUUID()}`;
  const payload = {
    reason: 'app-focus',
    locale: LANGUAGE,
    theme: 'dark',
    metadata: {
      device: { type: 'mobile', uniqueId },
      os: { name: 'android', version: 'unknown', abis: ['unknown'], host: 'github-actions' },
      app: { platform: 'android' },
      version: { package: 'tv.vavoo.app', binary: '3.1.8', js: '3.1.8' },
    },
    appFocusTime: 0,
    playerActive: false,
    playDuration: 0,
    devMode: false,
    hasAddon: true,
    castConnected: false,
    package: 'tv.vavoo.app',
    version: '3.1.8',
    process: 'app',
    firstAppStart: now,
    lastAppStart: now,
    ipLocation: null,
    adblockEnabled: true,
    proxy: { supported: ['ss'], engine: 'Mu', enabled: false, autoServer: true },
    iap: { supported: false },
  };

  let lastError;
  for (const url of PING_URLS) {
    try {
      const response = await postJson(url, payload, { 'User-Agent': 'VAVOO/2.6' });
      const signature = String(response?.addonSig || '').trim();
      if (signature) return signature;
      lastError = new Error('La réponse ping ne contient pas addonSig.');
    } catch (error) {
      lastError = error;
      console.warn(`Ping VAVOO en échec sur ${new URL(url).host}: ${error.message}`);
    }
  }
  throw lastError || new Error('Impossible d’obtenir la signature VAVOO.');
}

function getCountryFromGroup(group) {
  const value = String(group || '').trim();
  if (!value) return 'default';
  for (const separator of ['➾', '⟾', '->', '→', '»', '›']) {
    const index = value.indexOf(separator);
    if (index >= 0) return value.slice(0, index).trim() || 'default';
  }
  return value;
}

function clean(value) {
  return String(value || '').trim().replace(/\s+/g, ' ');
}

function stableId(name, country) {
  return crypto.createHash('sha1')
    .update(`${clean(country).toLowerCase()}|${clean(name).toLowerCase()}`, 'utf8')
    .digest('hex')
    .slice(0, 22);
}

async function loadCatalogFromBase(base, signature) {
  const channels = [];
  const seenUrls = new Set();
  const seenCursors = new Set();
  let cursor = null;

  for (let page = 0; page < MAX_PAGES; page++) {
    const response = await postJson(`${base}/mediahubmx-catalog.json`, {
      language: LANGUAGE,
      region: REGION,
      catalogId: 'iptv',
      id: 'iptv',
      adult: false,
      search: '',
      sort: '',
      filter: {},
      cursor,
      clientVersion: CLIENT_VERSION,
    }, {
      'User-Agent': 'MediaHubMX/2',
      'mediahubmx-signature': signature,
      'Accept-Language': LANGUAGE,
    }, 30000);

    const items = Array.isArray(response?.items) ? response.items : [];
    for (const item of items) {
      if (item?.type !== 'iptv' || !item?.url) continue;
      const url = clean(item.url);
      if (!url || seenUrls.has(url)) continue;
      seenUrls.add(url);

      const name = clean(item.name || 'Chaîne VAVOO');
      const group = clean(item.group || 'VAVOO Live');
      let country = clean(item.country || item.countryName || item.country_name || '');
      if (!country) country = getCountryFromGroup(group);
      const id = stableId(name, country);

      channels.push({
        id,
        name,
        url, // This is the catalog reference consumed by our existing stream resolver.
        logo: clean(item.logo || ''),
        group,
        country,
      });
    }

    const next = response?.nextCursor;
    if (next == null || String(next).trim() === '') {
      console.log(`${base}: ${channels.length} chaînes, ${page + 1} pages.`);
      return channels;
    }
    const nextKey = typeof next === 'string' ? next : JSON.stringify(next);
    if (seenCursors.has(nextKey)) throw new Error('Le curseur de pagination VAVOO se répète.');
    seenCursors.add(nextKey);
    cursor = next;
  }
  throw new Error(`Le catalogue dépasse ${MAX_PAGES} pages; la sauvegarde est refusée.`);
}

async function refreshIptvOrgCatalog() {
  const manifestPath = path.join(ROOT, 'morocco-tv/app/src/main/assets/world_playlist_sources.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const sources = (manifest.sources || [])
    .filter(source => source.enabled !== false)
    .sort((a, b) => Number(b.priority || 0) - Number(a.priority || 0));
  if (!sources.length) throw new Error('Aucune source mondiale configurée.');

  const blocked = new Set((manifest.blockedTerms || [])
    .map(value => normalizeSearch(value)).filter(Boolean));
  const maxChannels = Math.max(1000, Math.min(30000, Number(manifest.maxChannels || 20000)));
  const maxBytes = Math.max(1024 * 1024, Math.min(64 * 1024 * 1024,
    Number(manifest.maxPlaylistBytes || 40 * 1024 * 1024)));
  const minimumMerged = Math.max(100, Number(manifest.minimumMergedChannels || 1000));

  const attribute = (line, key) => {
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = line.match(new RegExp('(?:^|\\s)' + escaped + '\\s*=\\s*(?:"([^"]*)"|\\'([^\\']*)\\'|([^\\s,]+))', 'i'));
    return match ? String(match[1] ?? match[2] ?? match[3] ?? '').trim() : '';
  };
  const titleOf = line => {
    let quoted = false;
    let quote = '';
    for (let i = 0; i < line.length; i++) {
      if (quoted) {
        if (line[i] === quote && line[i - 1] !== '\\\\') quoted = false;
      } else if (line[i] === '"' || line[i] === "'") {
        quoted = true; quote = line[i];
      } else if (line[i] === ',') return line.slice(i + 1).trim();
    }
    return '';
  };
  const normalizeSearch = value => String(value || '').normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  const safe = value => String(value || '').replace(/[\r\n"]/g, ' ').replace(/\s+/g, ' ').trim();
  const parseRows = (raw, source, enforceMinimum = true) => {
    if (Buffer.byteLength(raw, 'utf8') > maxBytes) throw new Error(source.id + ' dépasse la taille maximale.');
    const lines = raw.replace(/^\uFEFF/, '').split(/\r?\n/);
    if (!lines.some(line => line.trim().toUpperCase() === '#EXTM3U')) {
      throw new Error(source.id + ' ne contient pas d’en-tête #EXTM3U.');
    }
    const rows = [];
    let extinf = '';
    let extras = [];
    for (const original of lines) {
      const line = original.trim();
      if (!line || /^#EXTM3U$/i.test(line)) continue;
      if (line.startsWith('#EXTINF:')) { extinf = line; extras = []; continue; }
      if (line.startsWith('#')) {
        if (extinf && /^#EXTVLCOPT:/i.test(line)) extras.push(line);
        continue;
      }
      if (!/^https?:\/\//i.test(line) || !extinf) { extinf = ''; extras = []; continue; }
      const name = safe(titleOf(extinf) || attribute(extinf, 'tvg-name') || 'Chaîne');
      const logo = safe(attribute(extinf, 'tvg-logo'));
      const tvgId = safe(attribute(extinf, 'tvg-id'));
      const group = safe(attribute(extinf, 'group-title') || source.name);
      let country = safe(attribute(extinf, 'tvg-country') || attribute(extinf, 'country'));
      if (!country && source.countryFromGroup) {
        country = group.split(/[|;›»→➾⟾]/)[0].trim();
      }
      const normalizedGroup = normalizeSearch(group);
      const haystack = normalizeSearch([name, group, line].join(' '));
      if (blocked.size && [...blocked].some(term => haystack.includes(term))) {
        extinf = ''; extras = []; continue;
      }
      rows.push({
        sourceId: source.id, priority: Number(source.priority || 0),
        name, logo, tvgId, country, group, url: line, extras: [...extras],
        originalExtinf: extinf
      });
      if (rows.length > maxChannels * 2) throw new Error(source.id + ' dépasse le nombre maximal d’entrées.');
      extinf = ''; extras = [];
    }
    const minimum = Math.max(1, Number(source.minimumChannels || 1));
    if (enforceMinimum && rows.length < minimum) {
      throw new Error(source.id + ' ne contient que ' + rows.length + ' chaînes valides (minimum ' + minimum + ').');
    }
    return rows;
  };

  // Keep the last known complete catalogue available if an optional upstream is down.
  let existingRows = [];
  try {
    const existing = fs.readFileSync(IPTV_OUT, 'utf8');
    if (existing.includes('#EXTM3U')) {
      const oldFallback = {
        id: 'iptv-org', name: 'IPTV-org mondial', priority: 50,
        minimumChannels: 1, enabled: true, required: true, countryFromGroup: true
      };
      const parsed = parseRows(existing, oldFallback, false);
      existingRows = parsed.map(row => {
        const declared = attribute(row.originalExtinf, 'nisskenzi-source') || 'iptv-org';
        const source = sources.find(item => item.id === declared);
        return { ...row, sourceId: source ? source.id : 'iptv-org',
          priority: source ? Number(source.priority || 0) : 50 };
      });
    }
  } catch { /* first generation or corrupt old asset: validate all fresh sources below */ }

  const loaded = new Map();
  const failures = [];
  for (const source of sources) {
    try {
      const response = await fetch(source.url, {
        headers: {
          'User-Agent': 'NISSKENZI+ worldwide playlist merger',
          'Accept': 'audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*',
        },
        signal: AbortSignal.timeout(60000),
      });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const raw = await response.text();
      const rows = parseRows(raw, source, true);
      loaded.set(source.id, rows);
      console.log(source.id + ': ' + rows.length + ' chaînes acceptées.');
    } catch (error) {
      const oldRows = existingRows.filter(row => row.sourceId === source.id);
      if (oldRows.length >= Math.max(1, Number(source.minimumChannels || 1))) {
        loaded.set(source.id, oldRows);
        console.warn(source.id + ' indisponible; conservation de ' + oldRows.length + ' entrées existantes.');
      } else if (source.required) {
        throw new Error('Source obligatoire ' + source.id + ' indisponible: ' + error.message);
      } else {
        failures.push(source.id + ': ' + error.message);
        console.warn('Source facultative ignorée ' + source.id + ': ' + error.message);
      }
    }
  }

  const requiredSources = sources.filter(source => source.required);
  for (const source of requiredSources) {
    if (!loaded.has(source.id)) throw new Error('La source obligatoire ' + source.id + ' n’a pas été chargée.');
  }

  const identity = row => {
    const id = normalizeSearch(row.tvgId);
    if (id) return 'id:' + id;
    return 'name:' + normalizeSearch(row.name) + '|' + normalizeSearch(row.country);
  };
  const merged = new Map();
  const byUrl = new Set();
  const selectedSources = sources.filter(source => loaded.has(source.id))
    .sort((a, b) => Number(b.priority || 0) - Number(a.priority || 0));
  for (const source of selectedSources) {
    const seenInSource = new Set();
    for (const row of loaded.get(source.id) || []) {
      const dedupeKey = identity(row);
      const urlKey = row.url.trim();
      if (!urlKey || seenInSource.has(urlKey)) continue;
      seenInSource.add(urlKey);
      const existing = merged.get(dedupeKey);
      if (!existing) {
        if (merged.size >= maxChannels) continue;
        merged.set(dedupeKey, row);
      } else if (row.priority > existing.priority) {
        merged.set(dedupeKey, row);
      }
    }
  }

  const rows = [...merged.values()];
  const countryCount = new Set(rows.map(row => row.country).filter(Boolean).map(normalizeSearch)).size;
  if (rows.length < minimumMerged) {
    throw new Error('Fusion refusée: seulement ' + rows.length + ' chaînes valides.');
  }
  if (countryCount < 8) {
    throw new Error('Fusion refusée: seulement ' + countryCount + ' pays identifiés.');
  }

  rows.sort((a, b) => (a.country || '').localeCompare(b.country || '')
    || a.name.localeCompare(b.name) || a.url.localeCompare(b.url));
  const escapeAttr = value => safe(value);
  const rowLines = rows.flatMap(row => [
    '#EXTINF:-1 nisskenzi-source="' + escapeAttr(row.sourceId)
      + '" tvg-id="' + escapeAttr(row.tvgId)
      + '" tvg-name="' + escapeAttr(row.name)
      + '" tvg-logo="' + escapeAttr(row.logo)
      + '" tvg-country="' + escapeAttr(row.country)
      + '" group-title="' + escapeAttr(row.group) + '",' + escapeAttr(row.name),
    ...row.extras,
    row.url,
  ]);
  const content = ['#EXTM3U', ...rowLines].join('\n') + '\n';
  const digest = crypto.createHash('sha256').update(content, 'utf8').digest('hex').slice(0, 24);
  const output = [
    '#EXTM3U',
    '#NISSKENZI-IPTV-CATALOG sha256="' + digest + '" channels="' + rows.length
      + '" countries="' + countryCount + '" sources="' + selectedSources.length + '"',
    ...rowLines,
    '',
  ].join('\n');

  let existing = '';
  try { existing = fs.readFileSync(IPTV_OUT, 'utf8'); } catch {}
  if (existing === output) {
    console.log('Catalogue mondial inchangé: ' + rows.length + ' chaînes / ' + countryCount + ' pays.');
  } else {
    fs.writeFileSync(IPTV_OUT, output, 'utf8');
    console.log('Catalogue mondial fusionné: ' + rows.length + ' chaînes / ' + countryCount + ' pays.');
  }
  if (failures.length) console.warn('Sources facultatives indisponibles: ' + failures.join('; '));
}

async function main() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  // Update the direct IPTV-org playlist independently; keep the last good asset if its CDN is down.
  try {
    await refreshIptvOrgCatalog();
  } catch (error) {
    console.warn(`IPTV-org catalogue inchangé (actualisation en échec): ${error.message}`);
  }
  const signature = await getAddonSignature();
  let channels = [];
  let lastError;

  for (const base of BASE_URLS) {
    try {
      channels = await loadCatalogFromBase(base, signature);
      if (channels.length > 0) break;
    } catch (error) {
      lastError = error;
      console.warn(`Catalogue en échec sur ${base}: ${error.message}`);
    }
  }

  const countries = [...new Set(channels.map(c => c.country).filter(Boolean))];
  // Do not replace a previously generated global catalogue with a tiny/partial response.
  if (channels.length < 500 || countries.length < 8) {
    throw new Error(
      `Catalogue VAVOO incomplet/refusé: ${channels.length} chaînes, ${countries.length} pays. ${lastError?.message || ''}`
    );
  }

  channels.sort((a, b) =>
    a.country.localeCompare(b.country) || a.name.localeCompare(b.name) || a.url.localeCompare(b.url)
  );

  const generatedAt = new Date().toISOString();
  const escapeAttr = value => clean(value).replace(/["\r\n]/g, ' ');
  const m3u = [
    '#EXTM3U',
    `#NISSKENZI-CATALOG generatedAt="${generatedAt}" channels="${channels.length}" countries="${countries.length}" region="${REGION}"`,
    ...channels.flatMap(channel => [
      `#EXTINF:-1 tvg-id="${escapeAttr(channel.id)}" tvg-name="${escapeAttr(channel.name)}" tvg-logo="${escapeAttr(channel.logo)}" group-title="${escapeAttr(channel.group)}" tvg-country="${escapeAttr(channel.country)}",${escapeAttr(channel.name)}`,
      `vavoo-catalog://${Buffer.from(channel.url, 'utf8').toString('base64url')}`,
    ]),
    '',
  ].join('\n');

  let existing = '';
  try { existing = fs.readFileSync(OUT, 'utf8'); } catch {}
  const stripGeneratedHeader = text => text.replace(
    /^#NISSKENZI-CATALOG generatedAt="[^"]*" channels="[^"]*" countries="[^"]*" region="[^"]*"$/m,
    '#NISSKENZI-CATALOG'
  ).trim();
  if (existing && stripGeneratedHeader(existing) === stripGeneratedHeader(m3u)) {
    console.log(`Catalogue VAVOO M3U inchangé: ${channels.length} chaînes de ${countries.length} pays.`);
  } else {
    fs.writeFileSync(OUT, m3u, 'utf8');
    console.log(`Catalogue VAVOO M3U actualisé: ${channels.length} chaînes de ${countries.length} pays. Fichier: ${OUT}`);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
