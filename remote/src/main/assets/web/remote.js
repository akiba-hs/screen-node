'use strict';

// Пульт проектора. Страницу раздаёт сама служба пульта на проекторе, и команды уходят ей
// напрямую (без сервера): кнопки пульта Wanbo (с удержанием), мышь, клавиатура, источники и
// приложения. Команды проектор выполняет только по пропуску, подписанному сервером (его
// выдаёт сервер по ссылке «Пульт»); пока проектор забронирован, — только у владельца брони.

const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
const TOUCH = matchMedia('(pointer: coarse)').matches;
const MOD = { shift: 1, ctrl: 2, alt: 4, meta: 8 };
// Клавиши-модификаторы сами по себе не шлём: их состояние идёт в mods каждой клавиши.
const MODIFIERS = new Set(['ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight', 'AltLeft', 'AltRight',
  'MetaLeft', 'MetaRight', 'OSLeft', 'OSRight', 'CapsLock', 'Fn', 'FnLock']);
const MAX_MOVE = 4000;   // предел одного движения (как на сервере)
const MAX_TEXT = 256;    // символов в одной команде text
const PAD_SPEED = 1.6;   // чувствительность тачпада телефона
const PAD_SCROLL = 28;   // пикселей движения двух пальцев на один щелчок колеса
const WHEEL_PX = 100;    // пикселей прокрутки мыши/тачпада компьютера на щелчок колеса
const SENTINEL = '​'; // невидимый символ в поле ввода: Backspace в «пустом» поле тоже ловится
// Связь с проектором пропала, а курсор или клавиатура перехвачены: столько ждём возвращения
// (короткий обрыв Wi-Fi), прежде чем отпустить их.
const CAPTURE_GRACE_MS = 3000;
const MAX_PASTE = 20000; // символов во вставке из буфера за раз
// Проверка связи: ping раз в PING_MS; если проектор молчит дольше DEAD_MS — соединение мертво
// (браузер сам замечает обрыв Wi-Fi очень нескоро).
const PING_MS = 3000;
const DEAD_MS = 9000;

// Хранилище браузера может быть недоступно (приватный режим) — пульт работает и без него.
const store = {
  get(k) { try { return localStorage.getItem(k) || ''; } catch { return ''; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* не страшно */ } },
};

// Пропуск пульта от сервера приходит во фрагменте адреса (#t=…): запоминаем и убираем из адреса.
(() => {
  const m = /(?:^#|&)t=([^&]+)/.exec(location.hash);
  if (!m) return;
  store.set('pult.ticket', decodeURIComponent(m[1]));
  history.replaceState(null, '', location.pathname + location.search);
})();

// Постоянный идентификатор этого браузера: под ним сервер хранит список приложений на пульте.
const CLIENT_ID = (() => {
  let id = store.get('pult.client');
  if (!id) {
    id = crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2) + Date.now();
    store.set('pult.client', id);
  }
  return id;
})();

const $ = (id) => document.getElementById(id);
const ui = {
  status: $('rc-status'), statusText: $('rc-status-text'), volume: $('rc-volume'),
  mouse: $('rc-mouse'), pointer: $('rc-pointer'), keyboard: $('rc-keyboard'),
  lock: $('rc-lock'), kbdBadge: $('rc-kbd-badge'), pad: $('rc-pad'), text: $('rc-text'),
  imeNote: $('rc-ime-note'), remote: document.querySelector('.remote'), back: $('rc-back'),
  paste: $('rc-paste'), pasteBox: $('rc-paste-box'), pasteCatch: $('rc-paste-catch'), pasteCancel: $('rc-paste-cancel'),
  favs: $('rc-favs'), favEdit: $('rc-fav-edit'), favHint: $('rc-fav-hint'),
};

const st = {
  ws: null,
  open: false,
  backoff: 500,
  forbidden: false,
  status: { agent: false },
  favorites: null,      // приложения на пульте этого пользователя [{pkg, alt, label}]; null — не загружены
  favLoading: false,
  favError: '',
  favEdit: false,       // режим «Изменить»
  lostTimer: 0,         // отсчёт CAPTURE_GRACE_MS без связи с проектором
  lostNote: false,      // захват отпущен из-за обрыва связи — сказать об этом
  releasers: new Set(), // отпускание зажатых кнопок пульта (при уходе со страницы)
  kbd: false,           // клавиатура компьютера перехвачена
  kbdDown: new Set(),   // зажатые клавиши (KeyboardEvent.code)
  textFocus: false,     // поле ввода на телефоне в фокусе
  pointer: false,       // курсор компьютера перехвачен (Pointer Lock)
  mouseDown: new Set(), // зажатые кнопки мыши
  mx: 0, my: 0,         // накопленное движение (отправляется раз в кадр)
  wv: 0, wh: 0,         // накопленная прокрутка, в щелчках колеса
  frame: 0,
};

// ---------- WebSocket ----------

function connect() {
  const ws = new WebSocket(WS_URL);
  st.ws = ws;
  let lastSeen = Date.now();
  const heartbeat = setInterval(() => {
    if (ws.readyState !== WebSocket.OPEN) return;
    if (Date.now() - lastSeen > DEAD_MS) {
      ws.close();
      ws.onclose();
      return;
    }
    ws.send(JSON.stringify({ type: 'ping' }));
  }, PING_MS);
  ws.onopen = () => {
    st.open = true;
    st.backoff = 500;
    lastSeen = Date.now();
    ws.send(JSON.stringify({ type: 'hello', ticket: store.get('pult.ticket'), client: CLIENT_ID }));
    render();
  };
  ws.onmessage = (ev) => {
    lastSeen = Date.now();
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    handle(msg);
  };
  ws.onclose = () => {
    clearInterval(heartbeat);
    if (st.ws !== ws) return;
    st.ws = null;
    st.open = false;
    st.status = { agent: false };
    render();
    if (st.forbidden) return;
    setTimeout(connect, st.backoff);
    st.backoff = Math.min(st.backoff * 2, 8000);
  };
}

function handle(msg) {
  switch (msg.type) {
    case 'state': {
      const wasAgent = st.status.agent;
      st.status = msg.status || { agent: false };
      // Проектор принял пропуск (или переподключились): он не знает, что здесь идёт набор текста.
      if (st.status.agent && !wasAgent && (st.kbd || st.textFocus)) send({ type: 'ime', on: true });
      if (st.status.server) {
        ui.back.href = st.status.server;
        ui.back.hidden = false;
        if (st.favorites === null && !st.favLoading) loadFavorites();
      }
      render();
      break;
    }
    case 'forbidden':
      // Нет пропуска или он устарел: за новым — на сервер (он вернёт сюда же). Один раз за вкладку,
      // чтобы при отказе сервера не уйти в бесконечную переадресацию.
      if (msg.login && sessionStorage.getItem('pult.login') !== '1') {
        sessionStorage.setItem('pult.login', '1');
        const t = store.get('pult.ticket');
        location.href = msg.login + (t ? `?t=${encodeURIComponent(t)}` : '');
        return;
      }
      st.forbidden = true;
      showNote(msg.text || 'Проектор отказал в доступе к пульту.');
      render();
      break;
    case 'apps':
      showApps(msg.apps || []);
      break;

    case 'error':
      console.warn('пульт:', msg.text);
      break;
  }
}

function send(msg) {
  if (!st.ws || st.ws.readyState !== WebSocket.OPEN) return false;
  st.ws.send(JSON.stringify(msg));
  return true;
}

// ---------- Кнопки пульта ----------

// Кнопка с удержанием: down при нажатии, up при отпускании (как на настоящем пульте —
// удержание громкости или фокуса повторяет действие).
function bindHold(el, onDown, onUp) {
  let down = false;
  const release = () => {
    if (!down) return;
    down = false;
    el.classList.remove('is-down');
    st.releasers.delete(release);
    onUp();
  };
  el.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    try { el.setPointerCapture(e.pointerId); } catch { /* SVG в старых браузерах */ }
    if (down) return;
    down = true;
    el.classList.add('is-down');
    st.releasers.add(release);
    onDown();
  });
  el.addEventListener('pointerup', release);
  el.addEventListener('pointercancel', release);
  el.addEventListener('lostpointercapture', release);
  el.addEventListener('contextmenu', (e) => e.preventDefault());
  // С клавиатуры страницы (Tab + Enter/Пробел) — короткое нажатие.
  el.addEventListener('keydown', (e) => {
    if (st.kbd || e.repeat || (e.key !== 'Enter' && e.key !== ' ')) return;
    e.preventDefault();
    onDown();
    setTimeout(onUp, 80);
  });
}

function sendKey(key, down) {
  send({ type: 'key', key, down });
  if (navigator.vibrate && down && TOUCH) navigator.vibrate(8);
}

for (const el of document.querySelectorAll('[data-key]')) {
  const key = el.dataset.key;
  if (el instanceof SVGElement) el.setAttribute('tabindex', '0');
  if (el.dataset.confirm) {
    // Питание — только с подтверждением: включить проектор обратно из браузера нельзя.
    el.addEventListener('click', () => {
      if (!confirm(el.dataset.confirm)) return;
      sendKey(key, true);
      setTimeout(() => sendKey(key, false), 100);
    });
    continue;
  }
  bindHold(el, () => sendKey(key, true), () => sendKey(key, false));
}

for (const el of document.querySelectorAll('[data-launch]')) {
  el.addEventListener('click', () => send({ type: 'launch', target: el.dataset.launch }));
}

// ---------- Приложения на пульте пользователя ----------
// Кнопки с названиями (без значков) — те, что пользователь вынес себе из «Все приложения».
// Список хранит сервер (это данные пользователя, а не команды проектору): страница читает и
// сохраняет его запросом к серверу по пропуску пульта. Без сервера пульт работает, только
// список приложений недоступен.

function openApp(item) {
  send({ type: 'open', target: item.pkg, alt: item.alt || [] });
}

async function favoritesRequest(method, body) {
  const res = await fetch(new URL('api/pult/favorites', st.status.server), {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Pult-Ticket': store.get('pult.ticket'),
      'X-Pult-Client': CLIENT_ID,
    },
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  if (!res.ok) throw new Error((await res.text()).trim() || res.statusText);
  return (await res.json()).items || [];
}

async function loadFavorites() {
  st.favLoading = true;
  try {
    st.favorites = await favoritesRequest('GET');
    st.favError = '';
  } catch (err) {
    st.favError = 'Список приложений недоступен: ' + err.message;
    setTimeout(() => { if (st.favorites === null) loadFavorites(); }, 10_000);
  }
  st.favLoading = false;
  renderFavorites();
  if (apps.items.length) markStars();
}

async function saveFavorites(items) {
  const before = st.favorites;
  st.favorites = items;
  renderFavorites();
  try {
    st.favorites = await favoritesRequest('PUT', { items });
    st.favError = '';
  } catch (err) {
    st.favorites = before;
    st.favError = 'Не удалось сохранить список приложений: ' + err.message;
  }
  renderFavorites();
  if (apps.items.length) markStars();
}

function isFavorite(pkg) {
  return !!(st.favorites || []).find((f) => f.pkg === pkg || (f.alt || []).includes(pkg));
}

function toggleFavorite(app) {
  if (st.favorites === null) return; // список ещё не загружен с сервера
  const list = st.favorites;
  const i = list.findIndex((f) => f.pkg === app.pkg || (f.alt || []).includes(app.pkg));
  if (i >= 0) saveFavorites(list.filter((_, j) => j !== i));
  else saveFavorites([...list, { pkg: app.pkg, label: app.label.slice(0, 64) }]);
}

function moveFavorite(i, d) {
  const list = [...st.favorites];
  const j = i + d;
  if (j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  saveFavorites(list);
}

function tool(text, title, cls, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = `rc-fav__tool ${cls}`;
  b.textContent = text;
  b.title = title;
  b.setAttribute('aria-label', title);
  b.addEventListener('click', (e) => { e.stopPropagation(); onClick(); });
  return b;
}

function renderFavorites() {
  const list = st.favorites || [];
  ui.favs.replaceChildren(...list.map((item, i) => {
    const wrap = document.createElement('div');
    wrap.className = 'rc-fav';
    const btn = document.createElement('button');
    btn.className = 'btn';
    btn.type = 'button';
    btn.textContent = item.label;
    btn.title = item.pkg;
    btn.addEventListener('click', () => { if (!st.favEdit) openApp(item); });
    const tools = document.createElement('div');
    tools.className = 'rc-fav__tools';
    tools.append(
      tool('←', 'Левее', '', () => moveFavorite(i, -1)),
      tool('→', 'Правее', '', () => moveFavorite(i, 1)),
      tool('✕', 'Убрать с пульта', 'rc-fav__tool--del', () => saveFavorites(list.filter((_, j) => j !== i))),
    );
    wrap.append(btn, tools);
    return wrap;
  }));
  ui.favs.classList.toggle('rc-favs--edit', st.favEdit);
  ui.favHint.hidden = !st.favEdit && !st.favError;
  ui.favHint.textContent = st.favError || 'Нажмите ✕, чтобы убрать приложение с пульта, ← и → — чтобы переставить. ' +
    'Добавить — в «Все приложения» (звёздочка ☆).';
  ui.favEdit.textContent = st.favEdit ? 'Готово' : 'Изменить';
  ui.favEdit.classList.toggle('is-on', st.favEdit);
}

ui.favEdit.addEventListener('click', () => {
  st.favEdit = !st.favEdit;
  renderFavorites();
});

// ---------- Все приложения ----------

const apps = {
  dialog: $('rc-apps'), list: $('rc-apps-list'), search: $('rc-apps-search'), status: $('rc-apps-status'),
  items: [],    // [{pkg, label, icon, el}]
  loadedAt: 0,
};
const APPS_TTL = 60_000; // список обновляется, если окно открыли через минуту и позже

$('rc-apps-open').addEventListener('click', () => {
  apps.dialog.showModal();
  if (!TOUCH) apps.search.focus();
  if (Date.now() - apps.loadedAt > APPS_TTL) {
    apps.status.textContent = 'Загрузка списка приложений…';
    // Названия — на языке этой страницы, если у приложения есть такой перевод.
    if (!send({ type: 'apps', lang: navigator.language || '' })) apps.status.textContent = 'Нет связи с сервером.';
  }
});
$('rc-apps-close').addEventListener('click', () => apps.dialog.close());
apps.dialog.addEventListener('click', (e) => { if (e.target === apps.dialog) apps.dialog.close(); });
apps.search.addEventListener('input', filterApps);

function showApps(list) {
  apps.loadedAt = Date.now();
  apps.list.replaceChildren();
  apps.items = list.map((a) => {
    const wrap = document.createElement('div');
    wrap.className = 'rc-app-wrap';
    const el = document.createElement('button');
    el.className = 'rc-app';
    el.type = 'button';
    const img = document.createElement('img');
    img.alt = '';
    img.src = `data:image/png;base64,${a.icon}`;
    const label = document.createElement('span');
    label.className = 'rc-app__label';
    label.textContent = a.label;
    const pkg = document.createElement('span');
    pkg.className = 'rc-app__pkg';
    pkg.textContent = a.pkg;
    el.append(img, label, pkg);
    el.addEventListener('click', () => {
      send({ type: 'open', target: a.pkg });
      apps.dialog.close();
    });
    // Звёздочка — вынести приложение на пульт (или убрать с него).
    const star = document.createElement('button');
    star.type = 'button';
    star.className = 'rc-app__star';
    star.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleFavorite(a);
      markStars();
    });
    wrap.append(el, star);
    apps.list.append(wrap);
    return { ...a, el: wrap, star, key: `${a.label}\n${a.pkg}`.toLowerCase() };
  });
  markStars();
  filterApps();
}

function markStars() {
  for (const it of apps.items) {
    const on = isFavorite(it.pkg);
    it.star.textContent = on ? '★' : '☆';
    it.star.classList.toggle('is-on', on);
    it.star.title = on ? 'Убрать с пульта' : 'Добавить на пульт';
    it.star.setAttribute('aria-label', it.star.title);
  }
}

// Поиск и по названию, и по имени пакета.
function filterApps() {
  const q = apps.search.value.trim().toLowerCase();
  let shown = 0;
  for (const it of apps.items) {
    const ok = !q || it.key.includes(q);
    it.el.hidden = !ok;
    if (ok) shown++;
  }
  apps.status.textContent = apps.items.length && !shown ? 'Ничего не найдено.' : '';
}

// Кнопка «мышь» на пульте: на компьютере — перехват курсора, на телефоне — к тачпаду.
ui.mouse.addEventListener('click', () => {
  if (TOUCH) {
    ui.pad.scrollIntoView({ behavior: 'smooth', block: 'center' });
    ui.pad.classList.add('is-active');
    setTimeout(() => ui.pad.classList.remove('is-active'), 800);
  } else {
    togglePointer();
  }
});

// ---------- Мышь: общая отправка ----------

function schedule() {
  if (!st.frame) st.frame = requestAnimationFrame(flush);
}

// Движение и прокрутка копятся и уходят раз в кадр: ~60 сообщений в секунду максимум.
function flush() {
  st.frame = 0;
  const dx = Math.trunc(st.mx), dy = Math.trunc(st.my);
  if (dx || dy) {
    send({ type: 'move', dx: clamp(dx, MAX_MOVE), dy: clamp(dy, MAX_MOVE) });
    st.mx -= dx;
    st.my -= dy;
  }
  const v = Math.trunc(st.wv), h = Math.trunc(st.wh);
  if (v || h) {
    send({ type: 'wheel', v: clamp(v, 50), h: clamp(h, 50) });
    st.wv -= v;
    st.wh -= h;
  }
}

function clamp(v, max) { return Math.max(-max, Math.min(max, v)); }

function mouseButton(button, down) {
  if (down) st.mouseDown.add(button); else if (!st.mouseDown.delete(button)) return;
  send({ type: 'button', button, down });
}

function releaseMouse() {
  for (const b of [...st.mouseDown]) mouseButton(b, false);
}

// ---------- Мышь компьютера: Pointer Lock ----------

const BUTTONS = ['left', 'middle', 'right'];

function togglePointer() {
  if (document.pointerLockElement) {
    document.exitPointerLock();
    return;
  }
  ui.lock.hidden = false;
  const p = ui.lock.requestPointerLock();
  if (p && p.catch) {
    p.catch(() => {
      ui.lock.hidden = true;
      showNote('Браузер не дал захватить курсор. Кликните по странице и попробуйте ещё раз.');
    });
  }
}

ui.pointer.addEventListener('click', togglePointer);

document.addEventListener('pointerlockchange', () => {
  st.pointer = document.pointerLockElement === ui.lock;
  if (st.pointer) st.lostNote = false;
  ui.lock.hidden = !st.pointer;
  if (!st.pointer) releaseMouse();
  render();
});

ui.lock.addEventListener('mousemove', (e) => {
  if (!st.pointer) return;
  st.mx += e.movementX;
  st.my += e.movementY;
  schedule();
});
ui.lock.addEventListener('mousedown', (e) => {
  if (st.pointer && BUTTONS[e.button]) mouseButton(BUTTONS[e.button], true);
});
ui.lock.addEventListener('mouseup', (e) => {
  if (st.pointer && BUTTONS[e.button]) mouseButton(BUTTONS[e.button], false);
});
ui.lock.addEventListener('contextmenu', (e) => e.preventDefault());
ui.lock.addEventListener('wheel', (e) => {
  if (!st.pointer) return;
  e.preventDefault();
  const k = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 800 : 1; // строки/страницы → пиксели
  // Колесо Linux: «+» — вверх и вправо; в браузере deltaY > 0 — вниз.
  st.wv += -e.deltaY * k / WHEEL_PX;
  st.wh += e.deltaX * k / WHEEL_PX;
  schedule();
}, { passive: false });

// ---------- Клавиатура компьютера ----------

// Печатный символ (в текущей раскладке — так язык определяется сам) уходит текстом,
// остальное (Enter, стрелки, Ctrl+C…) — клавишей с модификаторами.
function isText(e) {
  if (e.isComposing || [...e.key].length !== 1) return false;
  if (e.metaKey) return false;
  // Ctrl без Alt — сочетание; Ctrl+Alt (AltGr в Windows) печатает символ.
  if (e.ctrlKey && !e.altKey) return false;
  return true;
}

function mods(e) {
  return (e.shiftKey ? MOD.shift : 0) | (e.ctrlKey ? MOD.ctrl : 0) | (e.altKey ? MOD.alt : 0) | (e.metaKey ? MOD.meta : 0);
}

function sendText(text) {
  const chars = [...text];
  for (let i = 0; i < chars.length; i += MAX_TEXT) {
    send({ type: 'text', text: chars.slice(i, i + MAX_TEXT).join('') });
  }
}

function tapKey(code, n = 1) {
  for (let i = 0; i < n; i++) {
    send({ type: 'kbd', key: code, down: true, mods: 0 });
    send({ type: 'kbd', key: code, down: false, mods: 0 });
  }
}

function releaseKeys() {
  for (const code of [...st.kbdDown]) send({ type: 'kbd', key: code, down: false, mods: 0 });
  st.kbdDown.clear();
}

function setKeyboard(on) {
  if (on) st.lostNote = false;
  if (st.kbd === on) return;
  st.kbd = on;
  if (!on) releaseKeys();
  send({ type: 'ime', on: on || st.textFocus });
  render();
}

ui.keyboard.addEventListener('click', () => {
  setKeyboard(!st.kbd);
  ui.keyboard.blur(); // иначе Пробел/Enter снова нажмут эту кнопку
});

function onKey(e) {
  if (!st.kbd || e.target === ui.text || e.target === ui.pasteCatch) return;
  e.preventDefault();
  e.stopPropagation();
  if (e.type === 'keydown' && isText(e)) {
    sendText(e.key);
    return;
  }
  if (!e.code || MODIFIERS.has(e.code)) return;
  const down = e.type === 'keydown';
  // Повторы при удержании (e.repeat) тоже шлём: проектор повторит клавишу.
  if (down) st.kbdDown.add(e.code); else if (!st.kbdDown.delete(e.code)) return;
  send({ type: 'kbd', key: e.code, down, mods: mods(e) });
}
window.addEventListener('keydown', onKey, true);
window.addEventListener('keyup', onKey, true);

// ---------- Вставка из буфера обмена (компьютер) ----------
// На телефоне не нужна: вставленный в поле ввода текст и так уходит на проектор.

function typeOnProjector(raw) {
  let text = raw.replace(/\r\n?/g, '\n');
  if (!text) return;
  if ([...text].length > MAX_PASTE) {
    showNote(`В буфере слишком много текста — на проектор уйдут первые ${MAX_PASTE} символов.`);
    text = [...text].slice(0, MAX_PASTE).join('');
  }
  // Текст на любом языке печатает клавиатура пульта на проекторе: включаем её на время вставки.
  const wanted = st.kbd || st.textFocus;
  if (!wanted) send({ type: 'ime', on: true });
  text.split('\n').forEach((line, i) => {
    if (i > 0) tapKey('Enter');
    if (line) sendText(line);
  });
  if (!wanted) send({ type: 'ime', on: false });
}

function closePasteBox() {
  ui.pasteBox.hidden = true;
  ui.pasteCatch.value = '';
}

ui.paste.addEventListener('click', async () => {
  ui.paste.blur();
  // Читать буфер скриптом браузер разрешает только по HTTPS; иначе ловим обычную вставку.
  if (window.isSecureContext && navigator.clipboard && navigator.clipboard.readText) {
    try {
      typeOnProjector(await navigator.clipboard.readText());
      return;
    } catch { /* нет разрешения — вставка вручную */ }
  }
  ui.pasteBox.hidden = false;
  ui.pasteCatch.focus();
});
ui.pasteCatch.addEventListener('paste', (e) => {
  e.preventDefault();
  typeOnProjector(e.clipboardData ? e.clipboardData.getData('text/plain') : '');
  closePasteBox();
});
ui.pasteCatch.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePasteBox(); });
ui.pasteCancel.addEventListener('click', closePasteBox);

// ---------- Телефон: тачпад ----------

const pts = new Map(); // активные касания: id → {x, y}
let gesture = null;    // {fingers, moved, t0, drag, timer}

function avg() {
  let x = 0, y = 0;
  for (const p of pts.values()) { x += p.x; y += p.y; }
  return { x: x / pts.size, y: y / pts.size };
}

ui.pad.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  try { ui.pad.setPointerCapture(e.pointerId); } catch { /* не страшно */ }
  pts.set(e.pointerId, { x: e.clientX, y: e.clientY, x0: e.clientX, y0: e.clientY });
  ui.pad.classList.add('is-active');
  if (pts.size === 1) {
    gesture = { fingers: 1, moved: false, t0: performance.now(), drag: false, timer: 0 };
    // Удержание без движения — зажать левую кнопку (перетаскивание).
    gesture.timer = setTimeout(() => {
      if (gesture && gesture.fingers === 1 && !gesture.moved) {
        gesture.drag = true;
        mouseButton('left', true);
        if (navigator.vibrate) navigator.vibrate(20);
      }
    }, 450);
  } else if (gesture) {
    clearTimeout(gesture.timer);
    gesture.fingers = Math.max(gesture.fingers, pts.size);
  }
});

ui.pad.addEventListener('pointermove', (e) => {
  const p = pts.get(e.pointerId);
  if (!p || !gesture) return;
  const before = avg();
  const dx = e.clientX - p.x, dy = e.clientY - p.y;
  p.x = e.clientX;
  p.y = e.clientY;
  if (Math.hypot(p.x - p.x0, p.y - p.y0) > 8) {
    gesture.moved = true;
    if (!gesture.drag) clearTimeout(gesture.timer);
  }
  if (pts.size === 1 && gesture.fingers === 1) {
    // Небольшое ускорение: медленно — точно, быстро — далеко.
    const f = PAD_SPEED * (1 + Math.min(Math.hypot(dx, dy) / 14, 2));
    st.mx += dx * f;
    st.my += dy * f;
  } else if (pts.size >= 2) {
    const after = avg();
    // «Естественная» прокрутка, как на телефоне: палец вверх — содержимое вверх.
    st.wv += (after.y - before.y) / PAD_SCROLL;
    st.wh -= (after.x - before.x) / PAD_SCROLL;
  }
  schedule();
});

function padUp(e, cancelled) {
  if (!pts.delete(e.pointerId) || !gesture) return;
  if (pts.size > 0) return;
  ui.pad.classList.remove('is-active');
  clearTimeout(gesture.timer);
  const quick = performance.now() - gesture.t0 < 300;
  if (gesture.drag) {
    mouseButton('left', false);
  } else if (!cancelled && !gesture.moved && quick) {
    const b = gesture.fingers >= 2 ? 'right' : 'left';
    mouseButton(b, true);
    mouseButton(b, false);
  }
  gesture = null;
}
ui.pad.addEventListener('pointerup', (e) => padUp(e, false));
ui.pad.addEventListener('pointercancel', (e) => padUp(e, true));
ui.pad.addEventListener('contextmenu', (e) => e.preventDefault());

for (const el of document.querySelectorAll('[data-button]')) {
  bindHold(el, () => mouseButton(el.dataset.button, true), () => mouseButton(el.dataset.button, false));
}

// ---------- Телефон: поле ввода ----------
// Экранная клавиатура телефона не даёт честных событий клавиш (автозамена, подсказки),
// поэтому сравниваем содержимое поля до и после: удалённое — Backspace, новое — текстом.

let lastText = SENTINEL;

function resetText() {
  ui.text.value = SENTINEL;
  lastText = SENTINEL;
  ui.text.setSelectionRange(1, 1);
}

ui.text.addEventListener('focus', () => {
  st.textFocus = true;
  send({ type: 'ime', on: true });
  resetText();
  render();
});
ui.text.addEventListener('blur', () => {
  st.textFocus = false;
  send({ type: 'ime', on: st.kbd });
  render();
});
ui.text.addEventListener('input', () => {
  const a = [...lastText], b = [...ui.text.value];
  let p = 0;
  while (p < a.length && p < b.length && a[p] === b[p]) p++;
  const removed = a.length - p;
  const added = b.slice(p).filter((ch) => ch !== SENTINEL).join('');
  if (removed > 0) tapKey('Backspace', Math.min(removed, 50));
  if (added) sendText(added.replace(/\n/g, ''));
  if (added.includes('\n')) tapKey('Enter');
  lastText = ui.text.value;
  if (!lastText.startsWith(SENTINEL) || lastText.length > 200 || added.includes('\n')) resetText();
});
ui.text.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    tapKey('Enter');
    resetText();
  }
});

// ---------- Общее ----------

// Ушли со страницы или свернули её — ничего не должно остаться зажатым.
function releaseAll() {
  for (const r of [...st.releasers]) r();
  releaseKeys();
  releaseMouse();
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    releaseAll();
    if (document.pointerLockElement) document.exitPointerLock();
    setKeyboard(false);
  }
});
window.addEventListener('blur', () => {
  releaseKeys();
  releaseMouse();
});

function showNote(text) {
  ui.imeNote.textContent = text;
  ui.imeNote.hidden = !text;
}

function setStatus(cls, text) {
  ui.status.className = `status status--${cls}`;
  ui.statusText.textContent = text;
}

// Связь с проектором пропала, а курсор или клавиатура перехвачены: после короткого ожидания
// отпускаем их — иначе пользователь не сможет управлять своим компьютером, не зная почему.
function watchCapture(linked) {
  if (linked || !(st.kbd || st.pointer)) {
    clearTimeout(st.lostTimer);
    st.lostTimer = 0;
    return;
  }
  if (st.lostTimer) return;
  st.lostTimer = setTimeout(() => {
    st.lostTimer = 0;
    if (st.open && st.status.agent && !st.status.locked) return;
    if (document.pointerLockElement) document.exitPointerLock();
    st.lostNote = true;
    setKeyboard(false);
  }, CAPTURE_GRACE_MS);
}

function render() {
  const s = st.status;
  const locked = !!(s.agent && s.locked);
  if (st.forbidden) setStatus('offline', 'Нет доступа к пульту');
  else if (!st.open) setStatus('connecting', 'Нет связи с проектором, переподключаемся…');
  else if (!s.agent) setStatus('connecting', 'Подключение к проектору…');
  else if (locked) setStatus('locked', 'Проектор забронирован — пульт работает только у того, кто его забронировал');
  else setStatus('live', 'Пульт подключён к проектору');
  document.body.classList.toggle('is-locked', locked);
  watchCapture(st.open && s.agent && !locked);

  if (s.agent && s.max) {
    // Без звука система сообщает громкость 0 — показываем просто «звук выключен».
    ui.volume.textContent = s.muted ? 'Звук выключен' : `Громкость ${Math.round(((s.volume || 0) * 100) / s.max)}%`;
    ui.volume.hidden = false;
  } else {
    ui.volume.hidden = true;
  }
  ui.remote.classList.toggle('is-offline', !s.agent || !st.open);
  if (s.max && !volDrag) {
    $('rc-vol').max = s.max;
    $('rc-vol').value = s.muted ? 0 : s.volume || 0;
  }

  ui.pointer.textContent = st.pointer ? 'Отпустить курсор' : 'Захватить курсор';
  ui.pointer.classList.toggle('is-on', st.pointer);
  ui.mouse.classList.toggle('is-on', st.pointer);
  ui.keyboard.textContent = st.kbd ? 'Отпустить клавиатуру' : 'Захватить клавиатуру';
  ui.keyboard.classList.toggle('is-on', st.kbd);
  ui.kbdBadge.hidden = !st.kbd;

  if (!st.forbidden) {
    const wantText = st.kbd || st.textFocus;
    let note = st.lostNote ? 'Связь с проектором пропадала — курсор и клавиатура отпущены. Захватите их снова.' : '';
    // Устройства ввода не найдены — кнопки и мышь не работают; это важнее остальных подсказок.
    if (s.agent && s.max && !s.input) {
      note = 'Кнопки, мышь и клавиатура на этом проекторе недоступны (не найдены его устройства ввода). ' +
        'Работают громкость, «Домой», настройки, источники и приложения.';
    } else if (s.agent && wantText && !s.ime) {
      note = 'Печать на любом языке не настроена: приложению пульта не выдано разрешение ' +
        '(его выдаёт install.sh). ' +
        'Латиница, цифры, Enter, Backspace, стрелки и мышь работают.';
    }
    showNote(note);
  }
}

// Прошли на страницу — флаг «уже ходили за пропуском» сбрасывается после успешного входа.
setTimeout(() => { if (st.status.agent) sessionStorage.removeItem('pult.login'); }, 5000);

// Громкость ползунком: пока его тянут, состояние с проектора его не двигает.
const vol = $('rc-vol');
let volDrag = false;
vol.addEventListener('pointerdown', () => { volDrag = true; });
vol.addEventListener('pointerup', () => { volDrag = false; });
vol.addEventListener('change', () => { volDrag = false; });
vol.addEventListener('input', () => send({ type: 'volume', v: Number(vol.value) }));

connect();
render();
renderFavorites();
