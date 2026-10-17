// Play the News, web version. Serves the player page, turns news sources into their latest
// episode and searches podcasts. Feeds are fetched here because many of them don't let browsers
// read them directly.
//
//   node web/server.js      then open the address it prints, on this PC or a phone on the same Wi-Fi
//
// The built-in sources and flags are the Android app's own: read from Directory.java and
// res/drawable-nodpi, so both apps stay in step. No dependencies beyond Node 18+.
'use strict';

const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

const PORT = Number(process.env.PORT) || 8090;
// All interfaces, so phones on the same Wi-Fi can connect; HOST=127.0.0.1 keeps it to this PC.
const HOST = process.env.HOST || '0.0.0.0';
const ROOT = path.join(__dirname, '..');
const PUBLIC = path.join(__dirname, 'public');
const FLAGS = path.join(ROOT, 'res', 'drawable-nodpi');
const ICON = path.join(ROOT, 'res', 'mipmap-xxxhdpi', 'ic_launcher.png');
const DIRECTORY_JAVA = path.join(ROOT, 'src', 'com', 'eugen', 'playthenews', 'Directory.java');

// Some CDNs (RAI's Akamai) reject anything that does not look like a browser.
const USER_AGENT = 'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 '
    + '(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36';
const TIMEOUT_MS = 15000;
const CACHE_MS = 10 * 60 * 1000;

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml',
};

// ---- built-in sources and flags ----------------------------------------------------------

/** The Android app's directory: entries look like rss("de", "at", "Ö1 Journale", "https://..."). */
function loadDirectory() {
  const java = fs.readFileSync(DIRECTORY_JAVA, 'utf8');
  const languages = [...java.matchAll(/\{"([a-z]+)", "([^"]+)"\}/g)]
      .map(m => ({ code: m[1], name: m[2] }));
  const sources = [...java.matchAll(/\b(rss|rai)\("([a-z]+)", "([a-z-]+)", "([^"]+)", "([^"]+)"\)/g)]
      .map(m => ({ kind: m[1], lang: m[2], country: m[3], name: m[4], url: m[5] }));
  if (!sources.length) throw new Error('No sources found in ' + DIRECTORY_JAVA);
  return { languages, sources };
}

/** Flag codes we have pictures for: flag_es_ct.png -> "es-ct". */
function loadFlagCodes() {
  return new Set(fs.readdirSync(FLAGS)
      .map(f => /^flag_([a-z_]+)\.png$/.exec(f))
      .filter(Boolean)
      .map(m => m[1].replace('_', '-')));
}

const directory = loadDirectory();
const flagCodes = loadFlagCodes();

/**
 * A flag for a feed that isn't in the directory: Catalan language, else the language's region
 * ("fr-CA"), else the feed's country domain, else the language's home country.
 */
function guessCountry(language, url) {
  const [lang, region] = (language || '').toLowerCase().replace('_', '-').split('-');
  if (lang === 'ca') return 'es-ct';
  if (region) return flagCodes.has(region) ? region : null; // pt-BR is not Portugal
  const tld = new URL(url).hostname.split('.').pop();
  if (tld === 'cat') return 'es-ct';
  if (tld === 'uk') return 'gb';
  if (flagCodes.has(tld)) return tld;
  const home = { de: 'de', fr: 'fr', it: 'it', es: 'es', pt: 'pt', nl: 'nl' }[lang];
  return home || null;
}

// ---- fetching ----------------------------------------------------------------------------

async function get(url, headers = {}) {
  const res = await fetch(url, {
    redirect: 'follow',
    signal: AbortSignal.timeout(TIMEOUT_MS),
    headers: { 'User-Agent': USER_AGENT, ...headers },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res;
}

/** A decoder for the feed's own encoding (some are ISO-8859-1, which UTF-8 would garble). */
function decoderFor(firstChunk, res) {
  const declared = /encoding=["']([\w-]+)/i.exec(Buffer.from(firstChunk.subarray(0, 200)).toString('latin1'))
      || /charset=([\w-]+)/i.exec(res.headers.get('content-type') || '');
  try {
    return new TextDecoder(declared ? declared[1] : 'utf-8');
  } catch (e) {
    return new TextDecoder('utf-8');
  }
}

/**
 * Downloads a feed only as far as its first episode with audio: feeds list newest first, and
 * some are over 10 MB (Times Radio's takes ~10 s in full).
 */
async function readFeed(url) {
  const res = await get(url);
  const reader = res.body.getReader();
  let decoder = null, xml = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (value) {
        decoder = decoder || decoderFor(value, res);
        const chunk = decoder.decode(value, { stream: true });
        xml += chunk;
        // Only worth trying once another item has closed (the tag may straddle chunks).
        if (/<\/(item|entry)>/.test(xml.slice(-chunk.length - 8))) {
          try {
            return parseFeed(xml, url);
          } catch (e) {
            // no complete item with audio yet
          }
        }
      }
      if (done) return parseFeed(xml + (decoder ? decoder.decode() : ''), url);
    }
  } finally {
    reader.cancel().catch(() => {});
  }
}

/** Where a link ends up after redirects, without downloading what's there. */
async function finalUrl(url) {
  const res = await get(url, { Range: 'bytes=0-0' });
  res.body?.cancel();
  return res.url;
}

// ---- RSS ---------------------------------------------------------------------------------

const NAMED = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

function entities(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (m, e) => {
    if (e[0] !== '#') return NAMED[e.toLowerCase()] ?? m;
    const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
    try { return String.fromCodePoint(code); } catch (err) { return m; }
  });
}

/** Text of an element's content: CDATA kept as is, everything else entity-decoded. */
function content(raw) {
  let out = '', last = 0;
  for (const m of raw.matchAll(/<!\[CDATA\[([\s\S]*?)\]\]>/g)) {
    out += entities(raw.slice(last, m.index)) + m[1];
    last = m.index + m[0].length;
  }
  return (out + entities(raw.slice(last))).trim();
}

function tagText(xml, tag) {
  const m = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`).exec(xml);
  return m ? content(m[1]) : null;
}

function attrOf(tag, name) {
  const m = new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`).exec(tag);
  return m ? entities(m[1] ?? m[2]) : null;
}

function attr(xml, tag, name) {
  const m = new RegExp(`<${tag}\\b[^>]*>`).exec(xml);
  return m ? attrOf(m[0], name) : null;
}

/** First audio enclosure: RSS enclosure, Media RSS content or an Atom enclosure link. */
function enclosure(item) {
  for (const m of item.matchAll(/<(enclosure|media:content|link)\b[^>]*>/g)) {
    if (m[1] === 'link' && attrOf(m[0], 'rel') !== 'enclosure') continue;
    const type = attrOf(m[0], 'type');
    if (type && !type.startsWith('audio') && !type.includes('mpeg')) continue;
    const url = attrOf(m[0], m[1] === 'link' ? 'href' : 'url');
    if (url) return url;
  }
  return null;
}

/** "1:02:03", "12:34", or plain seconds. */
function parseDuration(s) {
  if (!s) return 0;
  const total = s.split(':').reduce((t, part) => t * 60 + parseFloat(part), 0);
  return Number.isFinite(total) ? Math.round(total * 1000) : 0;
}

function parseDate(s) {
  const t = s ? Date.parse(s) : NaN;
  return Number.isNaN(t) ? 0 : t;
}

/** Channel title, language and art, then the first item that has audio (feeds list newest first). */
function parseFeed(xml, url) {
  const firstItem = xml.search(/<(item|entry)[\s>]/);
  const head = firstItem >= 0 ? xml.slice(0, firstItem) : xml;
  const language = tagText(head, 'language');
  const channelArt = attr(head, 'itunes:image', 'href') || tagText(head, 'url');
  for (const m of xml.matchAll(/<(item|entry)[\s>][\s\S]*?<\/\1>/g)) {
    const item = m[0];
    const audioUrl = enclosure(item);
    if (!audioUrl) continue; // a text post; keep looking
    return {
      feedTitle: tagText(head, 'title'),
      country: guessCountry(language, url),
      language: language ? language.toLowerCase().split(/[-_]/)[0] : null,
      title: tagText(item, 'title'),
      audioUrl,
      artUrl: attr(item, 'itunes:image', 'href') || channelArt,
      durationMs: parseDuration(tagText(item, 'itunes:duration')),
      publishedAt: parseDate(tagText(item, 'pubDate') || tagText(item, 'published')
          || tagText(item, 'updated') || tagText(item, 'dc:date')),
    };
  }
  throw new Error(firstItem >= 0 ? 'Feed has no audio episode' : 'Not a podcast feed');
}

// ---- RAI ---------------------------------------------------------------------------------

/** Milliseconds a time zone is ahead of UTC at instant t. */
function zoneOffset(zone, t) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: zone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric',
    hour: 'numeric', minute: 'numeric', second: 'numeric',
  }).formatToParts(t).map(x => [x.type, x.value]));
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - t;
}

/** RaiPlay Sound's "17-10-2026" and "17:12", in Italian time. */
function romeTime(date, time) {
  const d = /^(\d\d)-(\d\d)-(\d{4})$/.exec(date || '');
  if (!d) return 0;
  const [h, mi] = (time || '00:00').split(':').map(Number);
  const local = Date.UTC(+d[3], +d[2] - 1, +d[1], h || 0, mi || 0);
  return local - zoneOffset('Europe/Rome', local);
}

/** Newest card of a RaiPlay Sound program. */
async function resolveRai(programUrl) {
  const json = await (await get(programUrl)).json();
  const card = json.block && json.block.cards && json.block.cards[0];
  if (!card) throw new Error('RAI lists no episodes for this program');
  const audio = card.audio || {};
  // downloadable_audio leads to a plain MP3; audio.url to HLS behind a token that expires in minutes.
  const relinker = (card.downloadable_audio && card.downloadable_audio.url) || audio.url;
  let poster = audio.poster || card.image || null;
  if (poster && poster.startsWith('/')) poster = 'https://www.raiplaysound.it' + poster;
  return {
    title: card.title || audio.title,
    audioUrl: await finalUrl(relinker),
    artUrl: poster,
    durationMs: parseDuration(audio.duration),
    publishedAt: romeTime(card.create_date, card.create_time),
  };
}

// ---- podcast search ----------------------------------------------------------------------

/** Podcasts by name from Apple's public podcast directory (no account or key needed). */
async function searchPodcasts(term, country) {
  const store = /^[a-z]{2}$/i.test(country || '') ? `&country=${country}` : '';
  const url = 'https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30'
      + `&term=${encodeURIComponent(term)}${store}`;
  const { results } = await (await get(url)).json();
  return results
      .filter(r => r.feedUrl) // a few listings don't publish their feed
      .map(r => ({
        name: r.collectionName,
        author: r.artistName || '',
        genre: r.primaryGenreName || '',
        feedUrl: r.feedUrl,
        artUrl: r.artworkUrl100 || null,
        updatedAt: parseDate(r.releaseDate),
      }));
}

// ---- latest episode, cached --------------------------------------------------------------

const cache = new Map(); // "kind|url" -> { at, promise }

function latest(kind, url) {
  const key = kind + '|' + url;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.promise;
  const promise = kind === 'rai' ? resolveRai(url) : readFeed(url);
  cache.set(key, { at: Date.now(), promise });
  promise.catch(() => { if (cache.get(key)?.promise === promise) cache.delete(key); });
  return promise;
}

// ---- HTTP --------------------------------------------------------------------------------

function send(res, status, body, type) {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

function json(res, status, value) {
  send(res, status, JSON.stringify(value), 'application/json; charset=utf-8');
}

function file(res, filePath) {
  fs.readFile(filePath, (err, data) => {
    if (err) return send(res, 404, 'Not found', 'text/plain');
    send(res, 200, data, TYPES[path.extname(filePath)] || 'application/octet-stream');
  });
}

async function handle(req, res) {
  const u = new URL(req.url, 'http://localhost');
  if (req.method !== 'GET') return send(res, 405, 'Method not allowed', 'text/plain');

  if (u.pathname === '/api/directory') return json(res, 200, directory);

  if (u.pathname === '/api/search') {
    const term = (u.searchParams.get('q') || '').trim();
    if (!term) return json(res, 400, { error: 'Nothing to search for' });
    try {
      return json(res, 200, await searchPodcasts(term, u.searchParams.get('country')));
    } catch (e) {
      return json(res, 502, { error: e.name === 'TimeoutError' ? 'Timed out' : e.message });
    }
  }

  if (u.pathname === '/api/latest') {
    const kind = u.searchParams.get('kind') === 'rai' ? 'rai' : 'rss';
    const url = u.searchParams.get('url') || '';
    if (!/^https?:\/\//i.test(url)) return json(res, 400, { error: 'Not a web address' });
    try {
      return json(res, 200, await latest(kind, url));
    } catch (e) {
      // fetch's own message is just "fetch failed"; the cause says why (ENOTFOUND, ECONNRESET...).
      const cause = e.cause && (e.cause.code || e.cause.message);
      const reason = e.name === 'TimeoutError' ? 'Timed out' : cause ? `${e.message} (${cause})` : e.message;
      console.warn(`could not resolve ${url}: ${reason}`);
      return json(res, 502, { error: reason });
    }
  }

  const flag = /^\/flags\/([a-z]{2}(?:-[a-z]{2})?)\.png$/.exec(u.pathname);
  if (flag) return file(res, path.join(FLAGS, `flag_${flag[1].replace('-', '_')}.png`));
  if (u.pathname === '/icon.png') return file(res, ICON);

  const rel = u.pathname === '/' ? 'index.html' : decodeURIComponent(u.pathname.slice(1));
  const filePath = path.join(PUBLIC, rel);
  if (!filePath.startsWith(PUBLIC + path.sep)) return send(res, 404, 'Not found', 'text/plain');
  return file(res, filePath);
}

http.createServer((req, res) => {
  handle(req, res).catch(e => {
    console.error(e);
    if (!res.headersSent) send(res, 500, 'Server error', 'text/plain');
  });
}).listen(PORT, HOST, () => {
  console.log(`Play the News web: ${directory.sources.length} built-in sources, ${flagCodes.size} flags`);
  console.log(`  on this PC:    http://localhost:${PORT}`);
  if (HOST !== '0.0.0.0') return;
  for (const nets of Object.values(os.networkInterfaces())) {
    for (const n of nets) {
      if (n.family === 'IPv4' && !n.internal) console.log(`  on your Wi-Fi: http://${n.address}:${PORT}`);
    }
  }
});
