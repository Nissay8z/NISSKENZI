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
  const response = await fetch(IPTV_PLAYLIST_URL, {
    headers: { 'User-Agent': 'NISSKENZI+ catalog snapshot updater', 'Accept': 'audio/x-mpegurl, application/vnd.apple.mpegurl, */*' },
    signal: AbortSignal.timeout(60000),
  });
  if (!response.ok) throw new Error(`IPTV-org playlist HTTP ${response.status}`);
  const raw = await response.text();
  const playlist = raw.endsWith('\\n') ? raw : raw + '\\n';
  const channelCount = (playlist.match(/^#EXTINF:/gm) || []).length;
  if (!playlist.trimStart().startsWith('#EXTM3U') || channelCount < 1000) {
    throw new Error(`IPTV-org playlist invalide/incomplète: ${channelCount} entrées détectées.`);
  }

  let existing = '';
  try { existing = fs.readFileSync(IPTV_OUT, 'utf8'); } catch {}
  if (existing === playlist) {
    console.log(`Catalogue IPTV-org inchangé: ${channelCount} entrées M3U.`);
    return;
  }
  fs.writeFileSync(IPTV_OUT, playlist, 'utf8');
  console.log(`Catalogue IPTV-org actualisé: ${channelCount} entrées M3U. Fichier: ${IPTV_OUT}`);
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
