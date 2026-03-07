/**
 * Loop Habit Tracker — Web UI
 *
 * Pixel-faithful replica of the Android app:
 *  - Same 20-colour palette (light + dark variants from Android styles.xml)
 *  - Same FontAwesome icons: fa-check (✓), fa-xmark (✗), fa-minus (skip), fa-question (?)
 *  - Same 48px checkmark button size
 *  - Dynamic column count: (screenWidth − labelWidth) / 48  (matches Android formula)
 *  - Full-width layout, no max-width constraint
 *  - Priority groups with collapse/expand, score ring, detail panel
 */

// ─── Palettes (Android colours) ──────────────────────────────────────────────

const LIGHT_PALETTE = [
  '#D32F2F','#E64A19','#F57C00','#FF8F00','#F9A825','#9E9D24',
  '#558B2F','#2E7D32','#00695C','#00838F','#0277BD','#1565C0',
  '#283593','#4527A0','#6A1B9A','#AD1457','#4E342E','#424242',
  '#757575','#9E9E9E'
];
const DARK_PALETTE = [
  '#EF9A9A','#FFAB91','#FFCC80','#FFECB3','#FFF59D','#E6EE9C',
  '#C5E1A5','#69F0AE','#80CBC4','#80DEEA','#81D4FA','#64B5F6',
  '#9FA8DA','#B39DDB','#CE93D8','#F48FB1','#BCAAA4','#F5F5F5',
  '#E0E0E0','#9E9E9E'
];

function palette() {
  return isDark() ? DARK_PALETTE : LIGHT_PALETTE;
}

function habitColor(colorIdx) {
  return palette()[colorIdx] ?? palette()[8];
}

// ─── Entry value constants (mirror Android Entry.kt) ─────────────────────────

const E = { SKIP: 3, YES_MANUAL: 2, YES_AUTO: 1, NO: 0, UNKNOWN: -1 };
const DAY_MS = 86_400_000;
const BTN_PX  = 48;   // checkmarkWidth/Height in Android dimens.xml (48dp)

// ─── Settings (localStorage) ─────────────────────────────────────────────────

function getSetting(key, def) {
  const v = localStorage.getItem('s_' + key);
  if (v === null) return def;
  try { return JSON.parse(v); } catch { return v; }
}
function saveSetting(key, val) {
  localStorage.setItem('s_' + key, JSON.stringify(val));
}

function isReverseDays()    { return getSetting('reverseDays',  false); }
function isSkipEnabled()    { return getSetting('skipEnabled',  false); }
function isQuestionMarks()  { return getSetting('questionMarks',false); }
function firstWeekday()     { return getSetting('firstWeekday',  1);    } // 0=Sun,1=Mon,6=Sat
function getApiKey()        { return getSetting('apiKey', '');          }

// ─── State ────────────────────────────────────────────────────────────────────

let habits        = [];
let allEntries    = {};     // { habitUuid: EntryDto[] }
let showArchived  = false;
let sortOrder     = 'BY_POSITION'; // BY_POSITION | BY_SCORE_DESC | BY_NAME_ASC
let selectedColor = 8;
let editingUuid   = null;
let currentDetailUuid = null;
let noteCtx       = null;   // { uuid, ts, value }
let numCtx        = null;   // { uuid, ts }
const groupCollapsed = {};

// ─── Dynamic column count ─────────────────────────────────────────────────────
// Android formula: buttonCount = (measuredWidth - labelWidth) / buttonWidth
// labelWidth = max(measuredWidth / 3, dp(160))

function computeCols() {
  const w = window.innerWidth;
  const labelW = Math.max(Math.floor(w / 3), 160);
  const cols = Math.max(1, Math.floor((w - labelW) / BTN_PX));
  return Math.min(cols, 28); // cap to prevent excessive history
}

let NUM_COLS = computeCols();

window.addEventListener('resize', () => {
  const c = computeCols();
  if (c !== NUM_COLS) { NUM_COLS = c; render(); }
});

// ─── Startup ──────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', async () => {
  loadTheme();
  buildColorGrid();
  loadSettingsUI();
  await loadAll();
  NUM_COLS = computeCols(); // recompute after layout is settled
  render();
});

// Also re-render on full load in case viewport size changed after DOMContentLoaded
window.addEventListener('load', () => {
  const c = computeCols();
  if (c !== NUM_COLS) { NUM_COLS = c; render(); }
});

async function loadAll() {
  habits = await apiFetch('/api/habits') || [];
  const today = dayStart(Date.now());
  const from  = today - DAY_MS * 365 * 2;
  await Promise.all(habits.map(async h => {
    const entries = await apiFetch(`/api/habits/${h.uuid}/entries?from=${from}&to=${today + DAY_MS}`);
    allEntries[h.uuid] = entries || [];
  }));
}

// ─── Render ───────────────────────────────────────────────────────────────────

function render() {
  NUM_COLS = computeCols();
  renderHeader();
  renderHabitList();
}

function renderHeader() {
  const days = getDayRange(NUM_COLS);
  const hdr  = document.getElementById('header-dates');
  hdr.innerHTML = days.map(ts => {
    const d   = new Date(ts);
    const day = d.toLocaleDateString(undefined, { weekday: 'narrow' }).toUpperCase();
    const num = d.getDate();
    const isToday = dayStart(ts) === dayStart(Date.now());
    return `<div class="date-cell${isToday ? ' today' : ''}">${day}<br>${num}</div>`;
  }).join('');
}

function renderHabitList() {
  const listEl  = document.getElementById('habit-list');
  const emptyEl = document.getElementById('empty-state');

  let visible = habits.filter(h => showArchived ? true : !h.isArchived);
  visible = sortHabits(visible);

  if (emptyEl) emptyEl.style.display = visible.length === 0 ? 'block' : 'none';
  if (visible.length === 0) {
    listEl.innerHTML = '';
    return;
  }

  let html;
  if (sortOrder === 'BY_PRIORITY') {
    // Group by priority with collapsible section headers
    const groups = {};
    for (const h of visible) {
      const p = h.priority || 2;
      (groups[p] = groups[p] || []).push(h);
    }
    const PRIORITY_LABEL = { 1: 'High Priority', 2: 'Medium Priority', 3: 'Low Priority' };
    html = Object.keys(groups).map(Number).sort().map(p => {
      const label     = PRIORITY_LABEL[p] || `Priority ${p}`;
      const collapsed = groupCollapsed[p] ? 'collapsed' : '';
      return `
        <div class="group-header ${collapsed}" onclick="toggleGroup(${p})">
          ${escHtml(label)}
          <span class="chevron"><i class="fa-solid fa-chevron-down"></i></span>
        </div>
        <div class="group-content ${collapsed}">
          ${groups[p].map(habitRow).join('')}
        </div>`;
    }).join('');
  } else {
    // All other sort modes: flat list, no headers
    html = visible.map(habitRow).join('');
  }

  listEl.innerHTML = html;
}

// ─── Habit row ────────────────────────────────────────────────────────────────

function habitRow(h) {
  const color   = habitColor(h.color);
  const score   = computeScore(h);
  const days    = getDayRange(NUM_COLS);
  const emap    = buildEntryMap(h.uuid);
  const isNum   = h.type === 1;

  // Score ring SVG (15×15, r=5.5, stroke=3 — matches Android RingView)
  const r     = 5.5;
  const circ  = 2 * Math.PI * r;
  const dash  = (score * circ).toFixed(2);
  const ring  = `<svg class="ring-wrap" viewBox="0 0 15 15">
    <circle class="ring-bg" cx="7.5" cy="7.5" r="${r}"/>
    <circle class="ring-fg" cx="7.5" cy="7.5" r="${r}"
      style="stroke:${color};stroke-dasharray:${dash} ${circ.toFixed(2)}"/>
  </svg>`;

  const cells = days.map(ts => {
    const entry = emap[dayStart(ts)] || { value: E.UNKNOWN, notes: '' };
    return isNum ? numCell(h, ts, entry, color) : checkCell(h, ts, entry, color);
  }).join('');

  const archived = h.isArchived ? ' archived' : '';
  return `
    <div class="habit-row${archived}" data-uuid="${h.uuid}">
      <div class="row-left" onclick="openDetail('${h.uuid}',event)">
        ${ring}
        <span class="habit-name" style="color:${color}">${escHtml(h.name)}</span>
      </div>
      ${cells}
    </div>`;
}

// ─── Checkmark cell ───────────────────────────────────────────────────────────
// Mirrors CheckmarkButtonView.kt exactly:
//   YES_MANUAL/YES_AUTO/SKIP → colour = habit colour
//   NO                       → colour = contrast40 (low contrast)
//   UNKNOWN                  → colour = contrast40, icon = fa-question or fa-xmark

function checkCell(h, ts, entry, color) {
  const v    = entry.value;
  const notes = entry.notes || '';

  // Icon class — same FontAwesome codepoints as Android string resources
  let icon, cls;
  switch (v) {
    case E.YES_MANUAL:
      icon = 'fa-solid fa-check'; cls = 'chk-yes-manual'; break;
    case E.YES_AUTO:
      icon = 'fa-solid fa-check'; cls = 'chk-yes-auto'; break;
    case E.SKIP:
      icon = 'fa-solid fa-minus'; cls = 'chk-skip'; break;
    case E.NO:
      icon = 'fa-solid fa-xmark'; cls = 'chk-no'; break;
    default: // UNKNOWN
      icon = isQuestionMarks() ? 'fa-solid fa-question' : 'fa-solid fa-xmark';
      cls  = 'chk-unknown'; break;
  }

  // Colour: habit colour for yes/skip; contrast40 for no/unknown (applied via CSS class)
  const colorStyle = (v === E.YES_MANUAL || v === E.YES_AUTO || v === E.SKIP)
    ? `color:${color}`
    : '';

  const notesDot = notes
    ? `<div class="notes-dot" style="background:${color}"></div>`
    : '';

  return `
    <div class="check-cell" title="${formatDate(ts)}${notes ? ': ' + notes : ''}"
         onclick="toggleEntry(event,'${h.uuid}',${ts})"
         oncontextmenu="openNote(event,'${h.uuid}',${ts},${v})">
      <i class="${icon} ${cls}" style="${colorStyle}"></i>
      ${notesDot}
    </div>`;
}

// ─── Number cell ──────────────────────────────────────────────────────────────

function numCell(h, ts, entry, color) {
  const v = entry.value;
  let display, unit, colorStyle;

  if (v === E.UNKNOWN || v < 0) {
    display    = isQuestionMarks() ? '?' : '—';
    unit       = '';
    colorStyle = 'color:var(--contrast40)';
  } else if (v === E.SKIP * 1000 || v === 3) {
    display    = ''; // skip icon via FA
    unit       = '';
    colorStyle = `color:${color}`;
  } else {
    const num  = v / 1000;
    display    = num >= 1000 ? (num / 1000).toFixed(1) + 'k'
               : num >= 10   ? Math.round(num).toString()
               :               num.toFixed(1);
    unit       = h.unit || '';
    const met  = h.targetType === 0 ? num >= h.targetValue : num <= h.targetValue;
    colorStyle = met ? `color:${color}` : 'color:var(--contrast60)';
  }

  return `
    <div class="num-cell" style="${colorStyle}" title="${formatDate(ts)}"
         onclick="openNumEntry(event,'${h.uuid}',${ts})">
      ${v === (E.SKIP * 1000) ? '<i class="fa-solid fa-minus"></i>'
        : `<span>${escHtml(display)}</span>`}
      ${unit ? `<span class="num-unit">${escHtml(unit)}</span>` : ''}
    </div>`;
}

// ─── Entry toggling ───────────────────────────────────────────────────────────
// nextToggleValue from Android Entry.kt

function nextToggleValue(v) {
  if (v === E.YES_AUTO)   return E.YES_MANUAL;
  if (v === E.YES_MANUAL) return isSkipEnabled() ? E.SKIP : E.NO;
  if (v === E.SKIP)       return E.NO;
  if (v === E.NO)         return isQuestionMarks() ? E.UNKNOWN : E.YES_MANUAL;
  return E.YES_MANUAL; // UNKNOWN → YES_MANUAL
}

async function toggleEntry(event, uuid, ts) {
  event.stopPropagation();
  const emap    = buildEntryMap(uuid);
  const current = emap[dayStart(ts)] || { value: E.UNKNOWN, notes: '' };
  const next    = nextToggleValue(current.value);
  await saveEntry(uuid, ts, next, current.notes);
}

async function saveEntry(uuid, ts, value, notes) {
  const dto = { timestamp: dayStart(ts), value, notes: notes || '' };
  await apiFetch(`/api/habits/${uuid}/entries`, 'POST', dto);
  if (!allEntries[uuid]) allEntries[uuid] = [];
  const idx = allEntries[uuid].findIndex(e => e.timestamp === dayStart(ts));
  if (idx >= 0) allEntries[uuid][idx] = dto; else allEntries[uuid].push(dto);
  renderHabitList();
  if (currentDetailUuid === uuid) renderDetail(uuid);
}

// ─── Numerical entry modal ────────────────────────────────────────────────────

function openNumEntry(event, uuid, ts) {
  event.stopPropagation();
  const h    = habits.find(x => x.uuid === uuid);
  const emap = buildEntryMap(uuid);
  const cur  = emap[dayStart(ts)];
  numCtx     = { uuid, ts };
  document.getElementById('num-modal-title').textContent = `${h.name} — ${formatDate(ts)}`;
  document.getElementById('num-modal-unit').textContent  = h.unit ? `Value (${h.unit})` : 'Value';
  document.getElementById('num-input').value = cur ? (cur.value / 1000).toFixed(2) : '';
  document.getElementById('num-modal').style.display = 'flex';
  setTimeout(() => document.getElementById('num-input').focus(), 50);
}
function closeNumModal() {
  document.getElementById('num-modal').style.display = 'none';
  numCtx = null;
}
async function saveNum() {
  if (!numCtx) return;
  const val = parseFloat(document.getElementById('num-input').value);
  if (isNaN(val)) { showToast('Enter a valid number'); return; }
  await saveEntry(numCtx.uuid, numCtx.ts, Math.round(val * 1000), '');
  closeNumModal();
}
async function saveNumSkip() {
  if (!numCtx) return;
  await saveEntry(numCtx.uuid, numCtx.ts, E.SKIP, '');
  closeNumModal();
}

// ─── Note modal ───────────────────────────────────────────────────────────────

function openNote(event, uuid, ts, currentValue) {
  event.preventDefault();
  event.stopPropagation();
  const emap = buildEntryMap(uuid);
  const cur  = emap[dayStart(ts)] || { value: currentValue, notes: '' };
  noteCtx    = { uuid, ts, value: cur.value };
  document.getElementById('note-text').value = cur.notes || '';
  document.getElementById('note-modal').style.display = 'flex';
}
function closeNoteModal() {
  document.getElementById('note-modal').style.display = 'none';
  noteCtx = null;
}
async function saveNote() {
  if (!noteCtx) return;
  const notes = document.getElementById('note-text').value;
  await saveEntry(noteCtx.uuid, noteCtx.ts, noteCtx.value, notes);
  closeNoteModal();
}

// ─── Detail panel ─────────────────────────────────────────────────────────────

function openDetail(uuid, event) {
  if (event) event.stopPropagation();
  currentDetailUuid = uuid;
  document.getElementById('detail-panel').classList.add('open');
  renderDetail(uuid);
}

function closeDetail() {
  document.getElementById('detail-panel').classList.remove('open');
  currentDetailUuid = null;
}

function renderDetail(uuid) {
  const h = habits.find(x => x.uuid === uuid);
  if (!h) return;
  const color = habitColor(h.color);
  const score = computeScore(h);

  // Header
  document.getElementById('dp-name').textContent  = h.name;
  document.getElementById('dp-name').style.color  = color;

  // Archive button label
  document.getElementById('dp-archive-label').textContent = h.isArchived ? 'Unarchive' : 'Archive';

  // Score ring (32px rendered, same proportions as habit row)
  const r    = 5.5;
  const circ = 2 * Math.PI * r;
  const fg   = document.getElementById('dp-ring-fg');
  fg.style.stroke           = color;
  fg.style.strokeDasharray  = `${(score * circ).toFixed(2)} ${circ.toFixed(2)}`;

  // History calendar
  const today      = dayStart(Date.now());
  const rangeStart = today - DAY_MS * 365 * 2;
  const emap       = buildEntryMap(uuid);

  // Align to selected first weekday
  const fwd        = firstWeekday(); // 0=Sun,1=Mon,6=Sat
  const startDate  = new Date(rangeStart);
  const dow        = startDate.getDay(); // 0=Sun
  const daysBack   = ((dow - fwd) + 7) % 7;
  const alignedStart = rangeStart - daysBack * DAY_MS;

  let histHtml = '';
  for (let ts = alignedStart; ts <= today + DAY_MS; ts += DAY_MS) {
    if (ts < rangeStart) { histHtml += '<div></div>'; continue; }
    const e   = emap[ts];
    const v   = e ? e.value : E.UNKNOWN;
    let cls, bg = '';
    switch (v) {
      case E.YES_MANUAL: cls = 'hcell'; bg = `background:${color}`; break;
      case E.YES_AUTO:   cls = 'hcell hcell-yes-auto'; bg = `background:${color}`; break;
      case E.SKIP:       cls = 'hcell hcell-skip'; break;
      case E.NO:         cls = 'hcell hcell-no'; break;
      default:           cls = 'hcell hcell-unknown'; break;
    }
    histHtml += `<div class="${cls}" style="${bg}" title="${formatDate(ts)}"
      onclick="toggleEntry({stopPropagation:()=>{}},'${uuid}',${ts})"></div>`;
  }
  document.getElementById('dp-history').innerHTML = histHtml;

  // Streaks
  const { current, best } = computeStreaks(uuid);
  const stEl = document.getElementById('dp-streaks');
  if (best > 0) {
    stEl.innerHTML = `
      <div class="streak-row">
        <span>Current streak</span>
        <span class="streak-val" style="color:${color}">${current} day${current !== 1 ? 's' : ''}</span>
      </div>
      <div class="streak-row">
        <span>Best streak</span>
        <span class="streak-val">${best} day${best !== 1 ? 's' : ''}</span>
      </div>`;
  } else {
    stEl.innerHTML = `<div style="color:var(--contrast60);font-size:13px">No streaks yet</div>`;
  }

  // Frequency
  const freqText = h.freqNum === 1 && h.freqDen === 1 ? 'Every day'
    : h.freqNum === 1 && h.freqDen === 7 ? 'Every week'
    : `${h.freqNum} time${h.freqNum !== 1 ? 's' : ''} per ${h.freqDen} day${h.freqDen !== 1 ? 's' : ''}`;
  document.getElementById('dp-freq').textContent = freqText;

  // Score chart
  renderScoreChart(uuid, color);
}

function renderScoreChart(uuid, color) {
  const canvas = document.getElementById('score-chart');
  const ctx    = canvas.getContext('2d');
  const w      = canvas.offsetWidth || 328;
  const h      = 80;
  canvas.width  = w;
  canvas.height = h;
  ctx.clearRect(0, 0, w, h);

  const emap  = buildEntryMap(uuid);
  const habit = habits.find(x => x.uuid === uuid);
  const today = dayStart(Date.now());
  const DAYS  = 90;

  const scores = [];
  for (let i = DAYS; i >= 0; i--) {
    let count = 0;
    for (let j = 0; j < Math.max(habit.freqDen || 1, 1); j++) {
      const e = emap[today - (i + j) * DAY_MS];
      if (e && (e.value === E.YES_MANUAL || e.value === E.YES_AUTO)) count++;
    }
    const exp = habit.freqNum || 1;
    scores.push(Math.min(1, count / exp));
  }

  // Area fill
  ctx.beginPath();
  for (let i = 0; i <= DAYS; i++) {
    const x = (i / DAYS) * w;
    const y = h - scores[i] * (h - 4) - 2;
    i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  }
  ctx.strokeStyle = color;
  ctx.lineWidth   = 2;
  ctx.stroke();
  ctx.lineTo(w, h); ctx.lineTo(0, h); ctx.closePath();
  ctx.fillStyle = color + '33';
  ctx.fill();
}

// ─── Create / Edit habit ──────────────────────────────────────────────────────

function openCreateHabit() {
  editingUuid = null;
  document.getElementById('habit-modal-title').textContent = 'New Habit';
  document.getElementById('f-name').value        = '';
  document.getElementById('f-question').value    = '';
  document.getElementById('f-description').value = '';
  document.getElementById('f-type').value        = '0';
  document.getElementById('f-priority').value    = '2';
  document.getElementById('f-freq-num').value    = '1';
  document.getElementById('f-freq-den').value    = '1';
  document.getElementById('f-target-type').value = '0';
  document.getElementById('f-target-value').value= '1';
  document.getElementById('f-unit').value        = '';
  selectColor(8);
  updateTypeFields();
  document.getElementById('habit-modal').style.display = 'flex';
  setTimeout(() => document.getElementById('f-name').focus(), 60);
}

function openEditHabit(uuid) {
  const h = habits.find(x => x.uuid === uuid);
  if (!h) return;
  editingUuid = uuid;
  document.getElementById('habit-modal-title').textContent = 'Edit Habit';
  document.getElementById('f-name').value         = h.name;
  document.getElementById('f-question').value     = h.question    || '';
  document.getElementById('f-description').value  = h.description || '';
  document.getElementById('f-type').value         = String(h.type);
  document.getElementById('f-priority').value     = String(h.priority || 2);
  document.getElementById('f-freq-num').value     = String(h.freqNum  || 1);
  document.getElementById('f-freq-den').value     = String(h.freqDen  || 1);
  document.getElementById('f-target-type').value  = String(h.targetType  || 0);
  document.getElementById('f-target-value').value = String(h.targetValue || 1);
  document.getElementById('f-unit').value         = h.unit || '';
  selectColor(h.color);
  updateTypeFields();
  document.getElementById('habit-modal').style.display = 'flex';
  setTimeout(() => document.getElementById('f-name').focus(), 60);
}

function closeHabitModal() {
  document.getElementById('habit-modal').style.display = 'none';
}

function updateTypeFields() {
  const isNum = document.getElementById('f-type').value === '1';
  document.getElementById('num-fields').style.display = isNum ? 'block' : 'none';
}

async function saveHabit() {
  const name = document.getElementById('f-name').value.trim();
  if (!name) { showToast('Please enter a habit name'); return; }

  const dto = {
    uuid:         editingUuid || '',
    name,
    question:     document.getElementById('f-question').value.trim(),
    description:  document.getElementById('f-description').value.trim(),
    type:         parseInt(document.getElementById('f-type').value),
    color:        selectedColor,
    freqNum:      parseInt(document.getElementById('f-freq-num').value)    || 1,
    freqDen:      parseInt(document.getElementById('f-freq-den').value)    || 1,
    targetType:   parseInt(document.getElementById('f-target-type').value) || 0,
    targetValue:  parseFloat(document.getElementById('f-target-value').value) || 0,
    unit:         document.getElementById('f-unit').value.trim(),
    isArchived:   editingUuid ? (habits.find(h => h.uuid === editingUuid)?.isArchived ?? false) : false,
    priority:     parseInt(document.getElementById('f-priority').value)    || 2,
    position:     editingUuid ? (habits.find(h => h.uuid === editingUuid)?.position ?? habits.length) : habits.length,
    reminderHour: null, reminderMin: null, reminderDays: null,
    updatedAt:    Date.now(),
  };

  if (editingUuid) {
    const updated = await apiFetch(`/api/habits/${editingUuid}`, 'PUT', dto);
    const idx = habits.findIndex(h => h.uuid === editingUuid);
    if (idx >= 0 && updated) habits[idx] = updated;
  } else {
    const created = await apiFetch('/api/habits', 'POST', dto);
    if (created) { habits.push(created); allEntries[created.uuid] = []; }
  }
  closeHabitModal();
  render();
}

async function deleteHabit(uuid) {
  const h = habits.find(x => x.uuid === uuid);
  if (!confirm(`Delete "${h?.name}"?\nThis cannot be undone.`)) return;
  await apiFetch(`/api/habits/${uuid}`, 'DELETE');
  habits = habits.filter(x => x.uuid !== uuid);
  delete allEntries[uuid];
  closeDetail();
  render();
  showToast('Habit deleted');
}

async function toggleArchiveCurrentHabit() {
  const h = habits.find(x => x.uuid === currentDetailUuid);
  if (!h) return;
  const dto = { ...h, isArchived: !h.isArchived, updatedAt: Date.now() };
  const updated = await apiFetch(`/api/habits/${h.uuid}`, 'PUT', dto);
  if (updated) {
    const idx = habits.findIndex(x => x.uuid === h.uuid);
    if (idx >= 0) habits[idx] = updated;
  }
  closeDetail();
  render();
  showToast(h.isArchived ? 'Habit unarchived' : 'Habit archived');
}

// ─── Color picker ─────────────────────────────────────────────────────────────

function buildColorGrid() {
  const grid = document.getElementById('color-grid');
  grid.innerHTML = LIGHT_PALETTE.map((c, i) =>
    `<div class="color-swatch${i === selectedColor ? ' selected' : ''}"
       style="background:${c}" onclick="selectColor(${i})"></div>`
  ).join('');
}

function selectColor(idx) {
  selectedColor = idx;
  document.querySelectorAll('.color-swatch').forEach((el, i) =>
    el.classList.toggle('selected', i === idx)
  );
}

// ─── Import ───────────────────────────────────────────────────────────────────

function openImport()  { document.getElementById('import-status').textContent = ''; document.getElementById('import-modal').style.display = 'flex'; }
function closeImport() { document.getElementById('import-modal').style.display = 'none'; }

function handleImportDrop(event) {
  event.preventDefault();
  document.getElementById('import-drop').classList.remove('dragover');
  const file = event.dataTransfer.files[0];
  if (file) handleImportFile(file);
}

async function handleImportFile(file) {
  if (!file) return;
  const status = document.getElementById('import-status');
  status.style.color = 'inherit';
  status.textContent = `Importing ${file.name}…`;
  try {
    const form = new FormData();
    form.append('file', file);
    const res  = await fetch('/api/import', { method: 'POST', body: form, headers: apiHeaders(true) });
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      status.style.color = 'green';
      status.textContent = '✓ Imported successfully';
      await loadAll();
      render();
      showToast('Import complete');
    } else {
      status.style.color = 'red';
      status.textContent = `✗ ${data.error || 'Import failed'}`;
    }
  } catch (e) {
    status.style.color = 'red';
    status.textContent = `✗ ${e.message}`;
  }
}

// ─── Sorting / grouping ───────────────────────────────────────────────────────

function cycleSortOrder() {
  const ORDERS = ['BY_POSITION', 'BY_SCORE_DESC', 'BY_NAME_ASC', 'BY_PRIORITY'];
  const LABELS = { BY_POSITION: 'Manual order', BY_SCORE_DESC: 'By score ↓', BY_NAME_ASC: 'A → Z', BY_PRIORITY: 'By priority' };
  sortOrder = ORDERS[(ORDERS.indexOf(sortOrder) + 1) % ORDERS.length];
  showToast(`Sort: ${LABELS[sortOrder]}`);
  renderHabitList();
}

function toggleArchived() {
  showArchived = !showArchived;
  document.getElementById('btn-show-archived').classList.toggle('active', showArchived);
  renderHabitList();
}

function toggleGroup(priority) {
  groupCollapsed[priority] = !groupCollapsed[priority];
  renderHabitList();
}

function sortHabits(list) {
  const copy = [...list];
  if (sortOrder === 'BY_SCORE_DESC') return copy.sort((a, b) => computeScore(b) - computeScore(a));
  if (sortOrder === 'BY_NAME_ASC')   return copy.sort((a, b) => a.name.localeCompare(b.name));
  if (sortOrder === 'BY_PRIORITY')   return copy.sort((a, b) => (a.priority - b.priority) || (a.position - b.position));
  return copy.sort((a, b) => a.position - b.position); // BY_POSITION: manual order only
}

// ─── Settings modal ───────────────────────────────────────────────────────────

function openSettings() {
  loadSettingsUI();
  document.getElementById('settings-modal').style.display = 'flex';
}
function closeSettings() {
  document.getElementById('settings-modal').style.display = 'none';
  render(); // apply visual settings (question marks icons, skip toggle cycle)
}

function loadSettingsUI() {
  document.getElementById('s-reverse-days').checked = isReverseDays();
  document.getElementById('s-skip').checked          = isSkipEnabled();
  document.getElementById('s-question').checked      = isQuestionMarks();
  document.getElementById('s-first-day').value       = String(firstWeekday());
  document.getElementById('s-api-key').value         = getApiKey();
}

// ─── Computations ─────────────────────────────────────────────────────────────

function computeScore(h) {
  const emap  = buildEntryMap(h.uuid);
  const today = dayStart(Date.now());
  const DAYS  = 30;
  let count   = 0;
  for (let i = 0; i < DAYS; i++) {
    const e = emap[today - i * DAY_MS];
    if (e && (e.value === E.YES_MANUAL || e.value === E.YES_AUTO)) count++;
  }
  const expected = DAYS * (h.freqNum || 1) / (h.freqDen || 1);
  return expected > 0 ? Math.min(1, count / expected) : 0;
}

function computeStreaks(uuid) {
  const emap  = buildEntryMap(uuid);
  const today = dayStart(Date.now());
  let current = 0, best = 0, run = 0;
  for (let i = 0; i <= 365 * 2; i++) {
    const e = emap[today - i * DAY_MS];
    if (e && (e.value === E.YES_MANUAL || e.value === E.YES_AUTO)) {
      run++;
      if (run > best) best = run;
      if (i === 0 || current === run - 1) current = run;
    } else {
      run = 0;
    }
  }
  return { current, best };
}

function buildEntryMap(uuid) {
  const map = {};
  for (const e of (allEntries[uuid] || [])) map[dayStart(e.timestamp)] = e;
  return map;
}

// Returns array of timestamps for the last N days
// Reversed if reverseDays setting is on (newest on left)
function getDayRange(count) {
  const today = dayStart(Date.now());
  const days  = [];
  for (let i = count - 1; i >= 0; i--) days.push(today - i * DAY_MS);
  return isReverseDays() ? days.reverse() : days;
}

function dayStart(ts) {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);  // local midnight — idempotent, matches Android local-date behavior
  return d.getTime();
}

// ─── Theme ────────────────────────────────────────────────────────────────────

function isDark() {
  return document.documentElement.getAttribute('data-theme') === 'dark';
}

function loadTheme() {
  const saved = localStorage.getItem('theme') || 'light';
  document.documentElement.setAttribute('data-theme', saved);
}

function toggleTheme() {
  const next = isDark() ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  localStorage.setItem('theme', next);
  // Re-render habit list so ring/icon colours update to dark palette
  renderHabitList();
}

// ─── API helpers ──────────────────────────────────────────────────────────────

function apiHeaders(multipart = false) {
  const h = {};
  const k = getApiKey();
  if (k) h['X-API-Key'] = k;
  if (!multipart) h['Content-Type'] = 'application/json';
  return h;
}

async function apiFetch(url, method = 'GET', body = null) {
  const opts = { method, headers: apiHeaders() };
  if (body) opts.body = JSON.stringify(body);
  try {
    const res = await fetch(url, opts);
    if (res.status === 401) {
      const key = prompt('Enter the server API key:');
      if (key) { saveSetting('apiKey', key); location.reload(); }
      return null;
    }
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  } catch (e) {
    console.error('API error:', url, e);
    return null;
  }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

function escHtml(str) {
  return String(str ?? '').replace(/[&<>"']/g, m =>
    ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[m])
  );
}

function formatDate(ts) {
  return new Date(ts).toLocaleDateString(undefined, { weekday:'short', month:'short', day:'numeric' });
}

function showToast(msg, ms = 2200) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => t.classList.remove('show'), ms);
}
