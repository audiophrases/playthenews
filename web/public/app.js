// Play the News, web version. Plays "My list" one source after another, each source's latest
// bulletin, in your own order or newest first, like the Android app. The list is kept in this
// browser; the server (server.js) fetches the feeds.
'use strict';

const RESTART_THRESHOLD_S = 3;
const REWIND_S = 15;
const FORWARD_S = 30;
/** Newest first waits at most this long for every source; slower ones are sorted last. */
const LATEST_WAIT_MS = 12000;
const KEY_LIST = 'playthenews.list';
const KEY_NEWEST_FIRST = 'playthenews.newestFirst';
const HINT_MINE = 'Plays top to bottom. Each source plays its latest bulletin.';
const HINT_NEWEST = 'The most recently updated sources play first. '
    + 'Your own order is kept for when you switch back.';

const $ = id => document.getElementById(id);
const audio = $('audio');

let directory = { languages: [], sources: [] };
/** The list in the user's own order, as saved. */
let list = [];
let newestFirst = false;
/** What is playing, in play order; meaningful while index >= 0. */
let queue = [];
let index = -1;
/** Bumped on every item change so stale async results are dropped. */
let playToken = 0;
let loading = false;
let playWhenReady = false;
let failures = 0;
let seeking = false;
let checking = false;
let checkRequest = 0;
let shownActive = -1;
/** Latest episode by source id, for sorting and ages. */
const latestById = new Map();

// ---- storage (may be unavailable, e.g. in private windows) ------------------------------

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch (e) {
    return fallback;
  }
}

function store(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (e) {
    // Not saved; it still works for this visit.
  }
}

// ---- sources -----------------------------------------------------------------------------

const id = s => s.kind + '|' + s.url;

function languageName(code) {
  const known = directory.languages.find(l => l.code === code);
  if (known) return known.name;
  try {
    const name = code && new Intl.DisplayNames([code], { type: 'language' }).of(code);
    if (name && name !== code) return name.charAt(0).toUpperCase() + name.slice(1);
  } catch (e) {
    // not a language code
  }
  return 'Podcast';
}

const label = s => languageName(s.lang);

async function fetchLatest(s) {
  const res = await fetch(`/api/latest?kind=${s.kind}&url=${encodeURIComponent(s.url)}`);
  const body = await res.json();
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  latestById.set(id(s), body);
  return body;
}

const publishedAt = s => (latestById.get(id(s)) || {}).publishedAt || 0;

/** Newest bulletin first; sources with no known date last, in their saved order (sort is stable). */
const byNewest = sources => sources.slice().sort((a, b) => publishedAt(b) - publishedAt(a));

const playOrder = () => (newestFirst ? byNewest(list) : list.slice());

/** Resolves every source in parallel; resolves after all are done or LATEST_WAIT_MS at most. */
function checkAll(sources, onEach) {
  const all = Promise.all(sources.map(s => fetchLatest(s).catch(() => null).then(() => onEach && onEach())));
  return Promise.race([all, new Promise(r => setTimeout(r, LATEST_WAIT_MS))]);
}

// ---- playback ----------------------------------------------------------------------------

async function playMyList() {
  if (!list.length) {
    toast('Your list is empty. Tap Edit list to add sources.');
    return;
  }
  if (!newestFirst) return playQueue(list.slice(), true);
  // The order depends on every source's latest bulletin, so check them all first.
  const token = ++playToken;
  stopAudio();
  index = -1;
  loading = true;
  playWhenReady = true;
  updatePlayIcon();
  showNothing();
  setStatus('Checking for the latest news…');
  renderQueue();
  await checkAll(list);
  if (token === playToken) playQueue(byNewest(list), playWhenReady);
}

function playQueue(q, play) {
  queue = q;
  failures = 0;
  playIndex(0, play);
}

async function playIndex(i, play = true) {
  if (i < 0 || i >= queue.length) return stop();
  index = i;
  const token = ++playToken;
  const src = queue[i];
  stopAudio();
  loading = true;
  playWhenReady = play;
  updatePlayIcon();
  showSource(src, null);
  setStatus(play ? 'Loading…' : 'Paused');
  renderQueue();
  let ep;
  try {
    ep = await fetchLatest(src);
  } catch (e) {
    if (token === playToken) itemFailed(src, e);
    return;
  }
  if (token !== playToken) return;
  loading = false;
  showSource(src, ep);
  audio.src = ep.audioUrl;
  if (playWhenReady) {
    audio.play().catch(e => {
      // Before the first tap on the page the browser refuses to play; wait for the play button.
      if (token === playToken && e.name === 'NotAllowedError') setStatus('Tap play to listen');
    });
  } else {
    setStatus('Paused');
  }
  updatePlayIcon();
  if (queue[i + 1]) fetchLatest(queue[i + 1]).catch(() => {});
}

function itemFailed(src, err) {
  console.warn('skipping', src.name, err);
  loading = false;
  stopAudio();
  if (++failures >= Math.max(3, queue.length)) {
    index = -1;
    showNothing();
    setStatus('Nothing could be played. Check the network connection.');
    renderQueue();
    return;
  }
  toast(`Couldn't play ${src.name}, skipping`);
  const token = playToken;
  setTimeout(() => { if (token === playToken) skip(1); }, 300);
}

function skip(delta) {
  const next = index + delta;
  if (next >= queue.length) stop();
  else playIndex(Math.max(0, next));
}

function previous() {
  if (audio.currentTime > RESTART_THRESHOLD_S || index <= 0) audio.currentTime = 0;
  else playIndex(index - 1);
}

function togglePlay() {
  if (loading) {
    playWhenReady = !playWhenReady;
    setStatus(playWhenReady ? 'Loading…' : 'Paused');
    updatePlayIcon();
  } else if (index < 0) {
    playMyList();
  } else if (!audio.getAttribute('src')) {
    playIndex(index);
  } else if (audio.paused) {
    audio.play().catch(() => {});
  } else {
    audio.pause();
  }
}

const isPlaying = () => (loading ? playWhenReady : !audio.paused);

function stop() {
  ++playToken;
  loading = false;
  stopAudio();
  index = -1;
  showNothing();
  setStatus('Stopped');
  renderQueue();
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none';
}

function stopAudio() {
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  updatePlayIcon();
}

function seekBy(seconds) {
  if (!audio.getAttribute('src')) return;
  const end = Number.isFinite(audio.duration) ? audio.duration : Infinity;
  audio.currentTime = Math.min(Math.max(0, audio.currentTime + seconds), end);
}

audio.addEventListener('playing', () => {
  failures = 0;
  setStatus('Playing');
  updatePlayIcon();
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
});
audio.addEventListener('pause', () => {
  if (audio.getAttribute('src') && !audio.ended) setStatus('Paused');
  updatePlayIcon();
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
});
audio.addEventListener('waiting', () => setStatus('Loading…'));
audio.addEventListener('ended', () => skip(1));
audio.addEventListener('error', () => {
  if (audio.getAttribute('src') && index >= 0) itemFailed(queue[index], audio.error);
});
audio.addEventListener('timeupdate', updateSeek);
audio.addEventListener('durationchange', updateSeek);

// ---- now playing -------------------------------------------------------------------------

function showSource(src, ep) {
  const title = $('title');
  title.replaceChildren();
  if (src.country) title.append(flag(src.country));
  title.append(src.name);
  $('subtitle').textContent = ep && ep.title ? ep.title : label(src);
  $('art').src = ep && ep.artUrl ? ep.artUrl : '/icon.png';
  document.title = `${src.name} · Play the News`;
  updateSeek();
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: src.name,
      artist: ep && ep.title ? ep.title : label(src),
      album: 'My list',
      artwork: [{ src: ep && ep.artUrl ? ep.artUrl : '/icon.png' }],
    });
  }
}

function showNothing() {
  $('title').textContent = 'Play the News';
  $('subtitle').textContent = '';
  $('art').src = '/icon.png';
  document.title = 'Play the News';
  updateSeek();
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = null;
}

$('art').addEventListener('error', () => {
  if (!$('art').src.endsWith('/icon.png')) $('art').src = '/icon.png';
});

function updatePlayIcon() {
  $('play-icon').setAttribute('href', isPlaying() ? '#i-pause' : '#i-play');
}

function setStatus(text) {
  $('status').textContent = text;
}

function updateSeek() {
  const seek = $('seek');
  const dur = audio.duration;
  const known = audio.getAttribute('src') && Number.isFinite(dur) && dur > 0;
  seek.max = known ? Math.floor(dur) : 0;
  if (!seeking) {
    seek.value = known ? Math.floor(audio.currentTime) : 0;
    $('elapsed').textContent = formatTime(known ? audio.currentTime : 0);
  }
  const fromFeed = index >= 0 && (latestById.get(id(queue[index])) || {}).durationMs;
  $('duration').textContent = known ? formatTime(dur) : fromFeed ? formatTime(fromFeed / 1000) : '--:--';
  if (known && 'mediaSession' in navigator && navigator.mediaSession.setPositionState) {
    try {
      navigator.mediaSession.setPositionState({
        duration: dur, playbackRate: audio.playbackRate, position: Math.min(audio.currentTime, dur),
      });
    } catch (e) {
      // position briefly past the end while seeking
    }
  }
}

function formatTime(seconds) {
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

// ---- queue -------------------------------------------------------------------------------

/** Before anything plays, shows what My list will play. */
function renderQueue() {
  const rows = index >= 0 ? queue : playOrder();
  $('empty').hidden = rows.length > 0;
  $('queue').replaceChildren(...rows.map((s, i) => {
    const li = el('li', index >= 0 && i === index ? 'active' : '');
    li.append(flag(s.country), el('span', 'text', `${i + 1}. ${s.name}  ·  ${label(s)}`));
    li.addEventListener('click', () => {
      if (index < 0) {
        queue = rows;
        failures = 0;
      }
      playIndex(i);
    });
    return li;
  }));
  // Keep the playing item visible when the queue advances, not on every redraw.
  if (index !== shownActive) {
    shownActive = index;
    const active = $('queue').querySelector('.active');
    if (active) active.scrollIntoView({ block: 'nearest' });
  }
}

/** After an edit: keeps the current item playing within the new order. */
function onListEdited() {
  if (index < 0) return;
  const currentId = id(queue[index]);
  const q = playOrder();
  let i = q.findIndex(s => id(s) === currentId);
  if (i < 0) {
    // The playing source was removed: keep playing it, continue with what took its place.
    q.splice(Math.min(index, q.length), 0, queue[index]);
    i = Math.min(index, q.length - 1);
  }
  queue = q;
  index = i;
}

// ---- list editor -------------------------------------------------------------------------

function renderEdit() {
  $('order-mine').setAttribute('aria-checked', String(!newestFirst));
  $('order-newest').setAttribute('aria-checked', String(newestFirst));
  $('hint').textContent = newestFirst ? HINT_NEWEST : HINT_MINE;
  const rows = newestFirst ? byNewest(list) : list;
  $('list').replaceChildren(...rows.map((s, i) => {
    const li = el('li');
    const text = el('div', 'text');
    text.append(el('div', 'name', `${i + 1}. ${s.name}`), el('div', 'label', subtitle(s)));
    li.append(flag(s.country), text);
    // Newest first sets the order itself; moving only applies to the user's own order.
    if (!newestFirst) {
      li.append(iconButton('i-up', 'Move up', i === 0, () => move(i, -1)),
          iconButton('i-down', 'Move down', i === rows.length - 1, () => move(i, 1)));
    }
    li.append(iconButton('i-close', 'Remove', false, () => remove(s)));
    return li;
  }));
}

function subtitle(s) {
  if (!newestFirst) return label(s);
  const t = publishedAt(s);
  if (t) return `${label(s)}  ·  ${age(t)}`;
  return checking ? `${label(s)}  ·  checking…` : label(s);
}

/** "just now", "25 min ago", "3 h ago", "2 days ago". */
function age(time) {
  const min = Math.floor((Date.now() - time) / 60000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min} min ago`;
  if (min < 24 * 60) return `${Math.floor(min / 60)} h ago`;
  const days = Math.floor(min / (24 * 60));
  return days === 1 ? '1 day ago' : `${days} days ago`;
}

function saveList() {
  store(KEY_LIST, list);
  onListEdited();
  renderEdit();
  renderQueue();
}

function move(i, delta) {
  const j = i + delta;
  if (j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  saveList();
}

function remove(s) {
  list = list.filter(x => x !== s);
  saveList();
}

function setNewestFirst(on) {
  if (on === newestFirst) return;
  newestFirst = on;
  store(KEY_NEWEST_FIRST, on);
  saveList();
  refreshLatest();
}

/** Newest first needs every source's latest bulletin; rows update as answers come in. */
function refreshLatest() {
  if (!newestFirst || !list.length) return;
  const request = ++checkRequest;
  checking = true;
  const update = () => {
    if (request !== checkRequest) return;
    renderEdit();
    renderQueue();
  };
  update();
  checkAll(list, update).then(() => {
    if (request !== checkRequest) return;
    checking = false;
    // The playing queue was sorted with what was known then; bring it in line with the screen.
    onListEdited();
    update();
  });
}

// ---- adding sources ----------------------------------------------------------------------

let addMode = null;
let addCandidates = [];

function openAdd() {
  showGroups();
  $('add-dialog').showModal();
}

function showGroups() {
  addMode = null;
  $('add-title').textContent = 'Add from';
  $('add-confirm').hidden = true;
  $('add-body').replaceChildren(
      ...directory.languages.map(l => {
        const n = directory.sources.filter(s => s.lang === l.code).length;
        const b = choice(l.name, () => showSources(l));
        b.append(el('span', 'count', `(${n})`));
        return b;
      }),
      choice('Search podcasts…', showSearch),
      choice('Podcast feed address…', showFeedForm));
}

/** Confirm button for the current step: "Add", or "Search" while searching. */
function showConfirm(text) {
  $('add-confirm').hidden = false;
  $('add-confirm').disabled = false;
  $('add-confirm').textContent = text;
}

/** A text input that acts on Enter itself: the form's own Enter would press Cancel. */
function dialogInput(type, id, placeholder, onEnter) {
  const input = document.createElement('input');
  input.type = type;
  input.id = id;
  input.placeholder = placeholder;
  input.autocomplete = 'off';
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      e.preventDefault();
      onEnter();
    }
  });
  return input;
}

function showSearch() {
  addMode = 'search';
  $('add-title').textContent = 'Search podcasts';
  showConfirm('Search');
  const box = el('div', 'feed');
  const input = dialogInput('search', 'search-term', 'Podcast name', runSearch);
  const help = el('p', '', "Searches Apple's podcast directory.");
  help.id = 'feed-help';
  box.append(input, help);
  const results = el('div', 'results');
  results.id = 'results';
  $('add-body').replaceChildren(box, results);
  input.focus();
}

let searchRequest = 0;

/** The store to search: the browser's region ("ca-ES" -> es), else Apple's default. */
const storeCountry = () => ((navigator.language || '').split('-')[1] || '').toLowerCase();

async function runSearch() {
  const term = $('search-term').value.trim();
  if (!term) return;
  const request = ++searchRequest;
  $('feed-help').textContent = 'Searching…';
  $('search-term').blur(); // lets the phone keyboard go so the results show
  let found;
  try {
    const res = await fetch(`/api/search?q=${encodeURIComponent(term)}&country=${storeCountry()}`);
    found = await res.json();
    if (!res.ok) throw new Error(found.error || `HTTP ${res.status}`);
  } catch (e) {
    if (request === searchRequest) $('feed-help').textContent = `Search failed: ${e.message}`;
    return;
  }
  if (request !== searchRequest || addMode !== 'search') return;
  $('feed-help').textContent = found.length ? 'Tap one to add it.' : 'No podcasts found.';
  $('results').replaceChildren(...found.map(r => {
    const b = choice('', () => addFeed(r.feedUrl, r.name));
    b.classList.add('result');
    const cover = el('img', 'cover');
    cover.src = r.artUrl || '/icon.png';
    cover.alt = '';
    cover.loading = 'lazy';
    const detail = [r.author, r.genre, r.updatedAt ? `updated ${age(r.updatedAt)}` : '']
        .filter(Boolean).join('  ·  ');
    const text = el('div', 'result-text');
    text.append(el('div', 'result-name', r.name), el('div', 'result-detail', detail));
    b.append(cover, text);
    return b;
  }));
}

function showSources(lang) {
  const have = new Set(list.map(id));
  addCandidates = directory.sources.filter(s => s.lang === lang.code && !have.has(id(s)));
  if (!addCandidates.length) {
    toast(`All ${lang.name} sources are already in your list`);
    return;
  }
  addMode = 'sources';
  $('add-title').textContent = lang.name;
  showConfirm('Add');
  $('add-body').replaceChildren(...addCandidates.map((s, i) => {
    const row = el('label', 'choice');
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.dataset.index = i;
    row.append(box, flag(s.country), el('span', '', s.name));
    return row;
  }));
}

function showFeedForm() {
  addMode = 'feed';
  $('add-title').textContent = 'Podcast feed';
  showConfirm('Add');
  const box = el('div', 'feed');
  const input = dialogInput('url', 'feed-url', 'https://example.com/podcast.xml', confirmAdd);
  const help = el('p', '', "Paste the address of any podcast's RSS feed. It plays the newest episode.");
  help.id = 'feed-help';
  box.append(input, help);
  $('add-body').replaceChildren(box);
  input.focus();
}

async function confirmAdd() {
  if (addMode === 'sources') {
    const picked = [...$('add-body').querySelectorAll('input:checked')]
        .map(box => addCandidates[Number(box.dataset.index)]);
    $('add-dialog').close();
    if (!picked.length) return;
    list.push(...picked);
    saveList();
    refreshLatest();
    toast(`Added ${picked.length} ${picked.length === 1 ? 'source' : 'sources'}`);
  } else if (addMode === 'feed') {
    await addFeed($('feed-url').value.trim());
  } else if (addMode === 'search') {
    await runSearch();
  }
}

let adding = false;

/**
 * Checks that url is a podcast feed with audio and adds it. A feed the directory lists is added
 * as the directory has it; any other gets its language and flag from the feed itself.
 */
async function addFeed(url, name) {
  if (!url || adding) return;
  if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
  const known = directory.sources.find(s => s.url === url) || { kind: 'rss', url };
  const existing = list.find(s => id(s) === id(known));
  if (existing) {
    $('feed-help').textContent = `${existing.name} is already in your list.`;
    return;
  }
  adding = true;
  $('feed-help').textContent = name ? `Checking ${name}…` : 'Checking…';
  $('add-confirm').disabled = true;
  try {
    const ep = await fetchLatest(known);
    const s = known.name ? known : {
      kind: 'rss', name: name || ep.feedTitle || new URL(url).hostname, lang: ep.language,
      country: ep.country, url,
    };
    list.push(s);
    saveList();
    refreshLatest();
    $('add-dialog').close();
    toast(`Added ${s.name}`);
  } catch (e) {
    $('feed-help').textContent = `That doesn't look like a podcast feed: ${e.message}`;
    $('add-confirm').disabled = false;
  } finally {
    adding = false;
  }
}

$('add-dialog').querySelector('form').addEventListener('submit', e => {
  if (e.submitter && e.submitter.value === 'ok') {
    e.preventDefault();
    confirmAdd();
  }
});

// ---- small DOM helpers -------------------------------------------------------------------

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text != null) e.textContent = text;
  return e;
}

/** A 20×15 flag, or a blank of the same size so names stay in one column. */
function flag(country) {
  if (!country) return el('span', 'flag');
  const img = el('img', 'flag');
  img.src = `/flags/${country}.png`;
  img.alt = '';
  return img;
}

function svgIcon(symbol) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#' + symbol);
  svg.append(use);
  return svg;
}

function iconButton(symbol, text, disabled, onClick) {
  const b = el('button', 'icon');
  b.type = 'button';
  b.setAttribute('aria-label', text);
  b.disabled = disabled;
  b.append(svgIcon(symbol));
  b.addEventListener('click', onClick);
  return b;
}

function choice(text, onClick) {
  const b = el('button', 'choice', text);
  b.type = 'button';
  b.addEventListener('click', onClick);
  return b;
}

let toastTimer;

function toast(text) {
  const t = $('toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2500);
}

// ---- wiring ------------------------------------------------------------------------------

function route() {
  const editing = location.hash === '#list';
  $('player').hidden = editing;
  $('edit').hidden = !editing;
  if (editing) {
    renderEdit();
    refreshLatest();
  }
  window.scrollTo(0, 0);
}

function setupMediaSession() {
  if (!('mediaSession' in navigator)) return;
  const handlers = {
    play: () => { if (!isPlaying()) togglePlay(); },
    pause: () => { if (isPlaying()) togglePlay(); },
    stop,
    previoustrack: previous,
    nexttrack: () => skip(1),
    seekbackward: d => seekBy(-(d.seekOffset || REWIND_S)),
    seekforward: d => seekBy(d.seekOffset || FORWARD_S),
    seekto: d => { if (audio.getAttribute('src')) audio.currentTime = d.seekTime; },
  };
  for (const [action, handler] of Object.entries(handlers)) {
    try {
      navigator.mediaSession.setActionHandler(action, handler);
    } catch (e) {
      // this browser doesn't offer that action
    }
  }
}

async function start() {
  try {
    directory = await (await fetch('/api/directory')).json();
  } catch (e) {
    toast('Could not reach the Play the News server');
  }
  // Directory entries are refreshed from the current directory (names, flags).
  list = load(KEY_LIST, [])
      .filter(s => s && s.kind && s.url && s.name)
      .map(s => directory.sources.find(d => id(d) === id(s)) || s);
  newestFirst = load(KEY_NEWEST_FIRST, false) === true;

  $('play').addEventListener('click', togglePlay);
  $('prev').addEventListener('click', previous);
  $('next').addEventListener('click', () => { if (index >= 0) skip(1); });
  $('rewind').addEventListener('click', () => seekBy(-REWIND_S));
  $('forward').addEventListener('click', () => seekBy(FORWARD_S));
  $('stop').addEventListener('click', stop);
  $('add').addEventListener('click', openAdd);
  $('order-mine').addEventListener('click', () => setNewestFirst(false));
  $('order-newest').addEventListener('click', () => setNewestFirst(true));
  $('seek').addEventListener('input', () => {
    seeking = true;
    $('elapsed').textContent = formatTime(Number($('seek').value));
  });
  $('seek').addEventListener('change', () => {
    seeking = false;
    if (audio.getAttribute('src')) audio.currentTime = Number($('seek').value);
  });
  document.addEventListener('keydown', e => {
    if (e.code === 'Space' && !e.target.closest('input, button, a, dialog')) {
      e.preventDefault();
      togglePlay();
    }
  });
  window.addEventListener('hashchange', route);

  setupMediaSession();
  route();
  renderQueue();
  setStatus(list.length ? 'Tap play to start your list' : '');
  // Newest first: sort the list (and show ages) before anything plays.
  refreshLatest();
}

start();
