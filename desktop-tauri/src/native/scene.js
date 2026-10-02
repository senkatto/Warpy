// Coordinates and typography follow index.css. All actions dispatch to the
// existing controller; the painter never makes VPN or persistence decisions.
const nativeById = id => document.getElementById(id);
const nativeVisible = id => !nativeById(id)?.classList.contains('hidden');
const nativeText = id => nativeById(id)?.textContent || '';
const nativeLogo = document.querySelector('.logo');
const nativeSvgSources = new WeakMap();
let nativePrunedTreeVersion = -1;
let nativeScene = { ops: [], hits: [], scrolls: [] };
let nativeHover = 0;
let nativeFocus = 0;
let nativeSelection = false;
let nativeCaret = 0;
let nativeAnchor = 0;
let nativeTooltip = '';
let nativeSelect = null;
function nativeRect(x, y, w, h, color, radius = 0, stroke = 0) {
  nativeScene.ops.push({ kind: 'rect', x, y, w, h, color, radius, stroke });
}
function nativeLabel(text, x, y, w, h, size = 12, color = '#fff', weight = 400, align = 'left', wrap = false) {
  nativeScene.ops.push({ kind: 'text', text: String(text), x, y, w, h, size, color, weight, align, wrap });
}
function nativeMeasure(text, size, weight = 400) {
  return typeof __nativeMeasureText === 'function' ? __nativeMeasureText(String(text),size,weight) : String(text).length * size * .55;
}
function nativeSvg(element, x, y, w, h, color = '#ccc') {
  const svg = element?.tagName === 'SVG' ? element : element?.querySelector('svg');
  if (svg) {
    let cached = nativeSvgSources.get(svg);
    if (!cached || cached.version !== nativeTreeVersion || cached.color !== color) {
      let source = nativeMarkup(svg).replace(/currentColor/g, color);
      if (!svg.getAttribute('xmlns')) source = source.replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" ');
      cached = { version: nativeTreeVersion, color, source }; nativeSvgSources.set(svg, cached);
    }
    const source = cached.source;
    nativeScene.ops.push({ kind: 'svg', source, x, y, w, h });
  }
}
function nativeHit(element, x, y, w, h, kind = 'click') {
  if (!element || element.disabled || element.classList.contains('hidden')) return;
  element.nativeRect = { left: x, top: y, width: w, height: h, right: x + w, bottom: y + h };
  nativeScene.hits.push({ uid: element.uid, x, y, w, h, kind });
}
function nativeButton(element, x, y, w, h, primary = false, size = 13, radius = 10) {
  const hover = element?.uid === nativeHover;
  nativeRect(x, y, w, h, element?.disabled ? '#1c1c1e' : primary ? (hover ? '#e0e0e0' : '#fff') : hover ? '#29292c' : '#1c1c1e', radius);
  if (!primary && radius!==20) nativeRect(x,y,w,h,'#29292c',radius,1);
  nativeLabel(element?.textContent, x + 8, y, w - 16, h, size, element?.disabled ? '#555' : primary ? '#000' : '#fff', 600, 'center');
  nativeHit(element, x, y, w, h);
}
function nativeInput(element, x, y, w, h, size = 11) {
  nativeRect(x, y, w, h, '#111113', 9);
  nativeRect(x, y, w, h, element.uid === nativeFocus ? '#00c07f' : '#29292c', 9, 1);
  const value = element.value;
  const text = value || element.placeholder;
  const padding = element.id === 'share-link' ? 10 : element.tagName === 'TEXTAREA' ? 8 : 5;
  const displayed = element.uid === nativeFocus && !element.readOnly ? value.slice(0,nativeCaret) + '│' + value.slice(nativeCaret) : text;
  nativeLabel(displayed, x + (element.tagName === 'TEXTAREA' ? padding : 8), y + padding, w - (element.tagName === 'TEXTAREA' ? padding * 2 : 16), h - padding * 2, size, value ? '#fff' : '#626265', 400, element.id === 's-mtu' ? 'center' : 'left', element.tagName === 'TEXTAREA');
  nativeScene.ops.at(-1).break_all = element.id === 'share-link';
  nativeHit(element, x, y, w, h, 'input');
}
function nativeToggle(element, x, y) {
  nativeRect(x, y, 38, 20, element.checked ? '#00c07f' : '#242426', 10);
  nativeRect(x + (element.checked ? 21 : 3), y + 3, 14, 14, '#fff', 7);
  nativeHit(element, x - 5, y - 10, 48, 40, 'toggle');
}
function nativeSelectControl(element, x, y, w = 144) {
  nativeRect(x, y, w, 30, '#1c1c1e', 8);
  nativeRect(x, y, w, 30, '#303033', 8, 1);
  nativeLabel(element.children.find(child => child.getAttribute?.('value') === element.value)?.textContent || '', x + 8, y, w - 35, 30, 11);
  nativeLabel('⌄', x + w - 29, y, 20, 30, 18, '#ccc', 600, 'center');
  nativeHit(element, x, y, w, 30, 'select');
}
function nativeMain() {
  const power = nativeById('power-btn');
  const connected = power.classList.contains('connected');
  const connecting = power.classList.contains('connecting');
  const empty = power.classList.contains('empty');
  const canvas = nativeById('particles').nativeCanvas;
  for (const op of canvas?.ops || []) nativeScene.ops.push({ ...op, x: op.x + 40, y: op.y + 104,
    ...(op.kind === 'line' ? { w: op.w + 40, h: op.h + 104 } : {}) });
  if (empty) nativeButton(power, 124, 242, 172, 64, false, 14,20);
  else {
    nativeHit(power, 124, 188, 172, 172);
    if (!connected && !connecting) nativeSvg(power, 186, 250, 48, 48, power.classList.contains('error') ? '#ff4d4d' : '#666');
    if (connected) nativeLabel(nativeById('status-alert').classList.contains('visible') ? nativeText('status-alert').toUpperCase() : nativeText('uptime'), 124, 258, 172, 32, 15, '#00c07f', 600, 'center');
    if (connecting) {
      nativeScene.ops.push({kind:'svg',source:'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 50 50"><circle cx="25" cy="25" r="20" fill="none" stroke="white" stroke-opacity=".04" stroke-width="3"/></svg>',x:182,y:246,w:56,h:56});
      nativeScene.ops.push({kind:'svg',source:'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 50 50"><circle cx="25" cy="25" r="20" fill="none" stroke="#ffa726" stroke-linecap="round" stroke-dasharray="90 150" stroke-width="3"/></svg>',x:182,y:246,w:56,h:56,rotation:performance.now()/1400*Math.PI*2});
    }
  }
  const infoHeight = connected ? 139 : empty ? 49 : 89;
  const infoY = 444 + (680 - 424 - infoHeight - 79) / 2 - 15;
  nativeLabel(nativeText('server-name'), 70, infoY, 280, 19, 14, '#99999b', 400, 'center', true);
  if (nativeById('protocol-chip').style.display !== 'none') {
    const chip = nativeText('protocol-chip'); const width = nativeMeasure(chip,12,500) + 22;
    nativeRect(210 - width / 2, infoY + 33, width, 26, '#363638', 10, 1);
    nativeLabel(chip, 210 - width / 2, infoY + 33, width, 26, 12, '#b3b3b5', 500, 'center');
  }
  if (connected) {
    nativeLabel(`${nativeText('m-speed')} ${nativeText('m-speed-unit')}`, 70, infoY + 73, 280, 16, 12, '#808082', 500, 'center');
    nativeLabel(`${nativeText('m-ping')} ${nativeText('m-ping-unit')}`, 70, infoY + 93, 280, 16, 12, '#808082', 500, 'center');
  }
  nativeLabel(nativeText('error-msg'), 70, infoY + infoHeight - 18, 280, 30, 12, '#ff4d4d', 400, 'center', true);
  nativeRect(40, 621, 340, 55, nativeHover === nativeById('btn-profiles').uid ? '#252527' : '#1c1c1e', 18);
  const chip = nativeText('bottom-chip'); const chipWidth = chip ? nativeMeasure(chip,11,600) + 18 : 0;
  if (chip) {
    nativeRect(56, 637, chipWidth, 23, '#3d3d40', 8, 1);
    nativeLabel(chip, 56, 637, chipWidth, 23, 11, '#a0a0a2', 600, 'center');
  }
  let nameX = 56 + (chipWidth ? chipWidth + 12 : 0);
  const flag = nativeById('bottom-flag').querySelector('img');
  if (flag && nativeVisible('bottom-flag')) {
    nativeScene.ops.push({ kind: 'image', source: flag.src, x: nameX, y: 642, w: 20, h: 14 }); nameX += 28;
  }
  nativeLabel(nativeText('bottom-name'), nameX, 639, 340 - (nameX - 40) - 36, 19, 14, '#fff', 600);
  nativeSvg(nativeById('btn-profiles'), 344, 638.5, 20, 20, '#666');
  nativeHit(nativeById('btn-profiles'), 40, 621, 340, 55);
}
function nativeTopBar() {
  if (nativeVisible('overlay-settings')) return;
  nativeSvg(nativeLogo, 40, 47, 109, 20, '#fff');
  ['btn-add', 'btn-speed', 'btn-settings', 'win-min', 'win-close'].forEach((id, index) => {
    const el = nativeById(id); const x = 178 + index * 42;
    nativeRect(x, 40, 34, 34, el.uid === nativeHover ? id === 'win-close' ? '#ff4d4d' : '#2a2a2c' : '#1c1c1e', 17);
    nativeSvg(el, x + 9, 49, 16, 16); nativeHit(el, x, 40, 34, 34);
  });
}
function nativeDim(overlay) {
  nativeScene.hits = []; nativeScene.scrolls = [];
  nativeRect(20, 20, 380, 680, 'rgba(0,0,0,.65)', 36);
  nativeHit(nativeById(overlay).querySelector('.overlay-bg'), 20, 20, 380, 680);
}
function nativeDialog(overlay, height, width = 340, padding = 20, radius = 20) {
  nativeDim(overlay); const x = (420 - width) / 2; const y = (720 - height) / 2;
  nativeRect(x, y, width, height, '#121214', radius);
  // The dialog consumes clicks, so they never reach the outside-close handler.
  nativeScene.hits.push({ uid: 0, x, y, w: width, h: height, kind: 'block' });
  return { x: x + padding, y: y + padding, w: width - padding*2, h: height - padding*2 };
}
function nativeClose(id, x, y) { const el = nativeById(id); nativeLabel(el.textContent || '✕', x, y, 24, 26, 18, '#808082', 400, 'center'); nativeHit(el, x, y, 24, 26); }
function nativeProfiles() {
  nativeDim('overlay-profiles');
  const list = nativeById('profile-list');
  const height = Math.min(480, 84 + Math.max(1, list.children.length) * 66);
  const y = 700 - height;
  nativeRect(20, y, 380, height + 28, '#121214', 28);
  nativeScene.hits.push({ uid: 0, x: 20, y, w: 380, h: height, kind: 'block' });
  nativeLabel(nativeText('profiles-drawer-title'), 42, y + 22, 145, 34, 17, '#fff', 600);
  if (nativeVisible('profiles-add-btn')) nativeButton(nativeById('profiles-add-btn'), 42 + nativeMeasure(nativeText('profiles-drawer-title'),17,600) + 10, y + 25, nativeMeasure(nativeText('profiles-add-btn'),11,600) + 18, 29, false, 11);
  if (nativeVisible('profiles-back-btn')) nativeButton(nativeById('profiles-back-btn'), 290, y + 25, 66, 30, false, 11);
  if (nativeVisible('close-profiles')) nativeClose('close-profiles', 354, y + 25);
  const top = y + 70, bottom = 678;
  const maxScroll = Math.max(0, list.children.length * 66 - (bottom - top));
  list.scrollTop = Math.max(0, Math.min(maxScroll, list.scrollTop));
  nativeScene.scrolls.push({ uid: list.uid, x: 42, y: top, w: 338, h: bottom - top, max: maxScroll });
  nativeScene.ops.push({ kind: 'clip', x: 42, y: top, w: 338, h: bottom - top });
  list.children.forEach((container, i) => {
    const row = container.querySelector?.('.profile-group-header') || container;
    const rowY = top + i * 66 - list.scrollTop;
    if (row.classList.contains('empty-msg')) { nativeLabel(row.textContent,42,rowY+24,339,18,13,'#59595b',400,'center'); return; }
    nativeRect(42, rowY, 339, 58, row.uid === nativeHover ? '#1a1a1d' : '#121214', 18);
    nativeRect(42, rowY, 339, 58, '#1d1d20', 18, 1);
    let x = 57;
    if (row.querySelector?.('.active-dot')) { nativeRect(x + 6, rowY + 24, 9, 9, '#00c07f', 4.5); x += 35; }
    const flag = row.querySelector?.('img');
    if (flag) { nativeScene.ops.push({ kind: 'image', source: flag.src, x, y: rowY + 22, w: 20, h: 14 }); x += 28; }
    else if (row.querySelector?.('.p-flag-emoji')) { nativeLabel(row.querySelector('.p-flag-emoji').textContent,x,rowY+20,20,18,17); x+=28; }
    const title = row.querySelector?.('.p-item-name, .group-title')?.textContent || row.textContent;
    const details = row.querySelector?.('.p-item-proto, .group-count');
    const actions = row.querySelectorAll?.('.p-share, .p-del, .group-arrow') || [];
    const infoW = 381 - x - 15 - actions.length * 30 + 6;
    nativeLabel(title, x, rowY + 12.5, infoW, 17, 13, '#e0e0e1', 600);
    nativeLabel(details?.textContent?.toUpperCase() || '', x, rowY + 31.5, infoW, 14, 10, details?.classList.contains('unavailable') ? '#ff5d62' : '#666669', 600);
    if (rowY + 58 > top && rowY < bottom) {
      nativeHit(row, 42, Math.max(top, rowY), 339, Math.min(bottom, rowY + 58) - Math.max(top, rowY));
    }
    actions.forEach((action, index) => {
      const ax = 381 - 9 - (actions.length - index) * 30;
      nativeSvg(action, ax + 4, rowY + 21, 16, 16, '#777');
      if (action.classList.contains('group-open-arrow')) nativeScene.ops.at(-1).rotation=-Math.PI/2;
      if (rowY + 58 > top && rowY < bottom) nativeHit(action, ax, Math.max(top, rowY + 14), 26, Math.max(0, Math.min(bottom, rowY + 44) - Math.max(top, rowY + 14)));
    });
  });
  nativeScene.ops.push({ kind: 'unclip' });
  if (maxScroll) nativeRect(378, top + list.scrollTop / (maxScroll || 1) * (bottom - top - 40), 3, 40, '#303033', 1.5);
}
function nativeSettings() {
  nativeScene.hits = []; nativeScene.scrolls = [];
  nativeRect(20, 20, 380, 680, '#09090b', 36);
  const tunneling = nativeVisible('settings-tunneling-page');
  const header = nativeById(tunneling ? 'settings-tunneling-header' : 'settings-main-header');
  nativeClose(tunneling ? 'close-tunneling' : 'close-settings', 40, 46);
  nativeLabel(header.querySelector('span').textContent, 80, 44, 290, 34, 17, '#fff', 600);
  const body = nativeById(tunneling ? 'settings-tunneling-page' : 'settings-main-page');
  const top = 98, bottom = 603;
  const contentHeight = tunneling ? 273 : 528;
  body.scrollTop = Math.max(0, Math.min(Math.max(0, contentHeight - (bottom - top)), body.scrollTop));
  nativeScene.scrolls.push({ uid: body.uid, x: 40, y: top, w: 340, h: bottom - top, max: Math.max(0, contentHeight - (bottom - top)) });
  nativeScene.ops.push({ kind: 'clip', x: 40, y: top, w: 340, h: bottom - top });
  let y = top - body.scrollTop;
  if (!tunneling) {
    const nav = nativeById('btn-open-tunneling');
    nativeRect(46, y + 14, 26, 26, '#0b211c', 8);
    nativeLabel('⇄', 46, y + 14, 26, 26, 17, '#55dca7', 400, 'center');
    nativeLabel(nav.querySelector('.s-title').textContent, 84, y + 10, 271, 16, 12, '#55dca7', 600);
    nativeLabel(nav.querySelector('.s-description').textContent, 84, y + 29, 271, 14, 10, '#626265');
    nativeLabel('›', 364, y + 10, 12, 34, 22, '#6b6b6e'); nativeHit(nav, 44, y, 332, 54); y += 54;
    const ids = ['s-resume-on-boot', 's-kill-switch', 's-lan', 's-quic', 's-mtu'];
    for (const id of ids) {
      const input = nativeById(id); const label = input.closest('label, .setting');
      const title = label.querySelector('.s-title'); const desc = label.querySelector('.s-description');
      nativeRect(44, y, 332, 1, '#17171a');
      const descWidth = id === 's-mtu' ? 248 : 276;
      const descHeight = nativeMeasure(desc?.textContent || '',10) > descWidth ? 28 : 14;
      const labelHeight = id === 's-kill-switch' ? 48 : 38 + descHeight;
      const height = labelHeight + (id === 's-kill-switch' ? 22 : 0);
      const titleY = y + (id === 's-kill-switch' ? 10.5 : 9);
      nativeLabel(title?.textContent || 'MTU', 46, titleY, descWidth, 16, 12, '#55dca7', 600);
      nativeLabel(desc?.textContent || '', 46, titleY + 19, descWidth, descHeight, 10, '#626265', 400, 'left', true);
      if (id!=='s-mtu') nativeHit(input,44,y,332,labelHeight,'toggle');
      const help = label.querySelector('.setting-help');
      if (help) {
        const hx = Math.min(316, 46 + nativeMeasure(title?.textContent || '',12,600) + 6);
        nativeRect(hx, titleY, 16, 16, '#202023', 8); nativeLabel('?', hx, titleY, 16, 16, 10, '#858588', 700, 'center');
        nativeHit(help, hx - 2, titleY - 4, 20, 24, 'help');
      }
      if (id === 's-mtu') nativeInput(input, 306, y + (labelHeight - 1 - 30)/2, 68, 30, 12);
      else nativeToggle(input, 336, y + (labelHeight - 1 - 20)/2 + (id === 's-kill-switch' ? 3.5 : 0));
      if (id === 's-kill-switch') nativeLabel(nativeText('kill-switch-status'), 46, y + 48, 328, 14, 10, nativeById('kill-switch-status').dataset.state === 'armed' ? '#00c07f' : '#777');
      y += height;
    }
    nativeRect(44, y + 17, 332, 1, '#1b1b1e'); y += 26;
    nativeRect(44, y, 332, 1, '#1b1b1e');
    nativeSvg(nativeById('btn-language'), 46, y + 14, 20, 20, '#858588');
    nativeLabel(nativeById('btn-language').querySelector('[data-i18n="lang"]').textContent, 80, y + 16, 180, 16, 12, '#55dca7', 600);
    nativeLabel(nativeText('language-setting-value'), 260, y + 16, 72, 16, 11, '#808083', 400, 'right');
    nativeSvg(nativeById('btn-language').querySelector('.language-setting-chevron'), 344, y + 16, 16, 16, '#6b6b6e'); nativeHit(nativeById('btn-language'), 44, y, 332, 48);
    nativeRect(44, y + 47, 332, 1, '#1b1b1e'); y += 64;
    const update = nativeById('btn-check-update');
    nativeRect(44,y,332,40,'#1c1c1e',10); nativeRect(44,y,332,40,'#303033',10,1);
    const updateText = update.querySelector('span').textContent;
    const updateWidth = nativeMeasure(updateText,12,600);
    nativeSvg(update,210-(updateWidth+24)/2,y+12.5,15,15,'#ddd');
    nativeLabel(updateText,210-(updateWidth+24)/2+24,y,updateWidth,40,12,'#fff',600);
    nativeHit(update,44,y,332,40);
    nativeLabel(nativeText('update-status'),44,y+40,332,14,10,'#808083',400,'left',true);
  } else {
    for (const kind of ['apps', 'sites']) {
      const select = nativeById(`s-${kind}-mode`);
      nativeLabel(select.closest('.setting-group').querySelector('.s-title').textContent, 46, y + 18, 150, 16, 12, '#55dca7', 600);
      nativeSelectControl(select, 230, y + 11); y += 49;
      if (kind === 'apps') {
        nativeButton(nativeById('btn-app-browse'), 46, y, 160, 28, false, 11);
        nativeButton(nativeById('btn-app-running'), 214, y, 160, 28, false, 11); y += 44;
      }
      nativeInput(nativeById(`s-${kind}-list`), 46, y, 328, 54); y += 66;
      nativeRect(44, y, 332, 1, '#17171a');
    }
  }
  nativeScene.ops.push({ kind: 'unclip' });
  nativeButton(nativeById('btn-save-settings'), 40, 615, 340, 37, true);
  nativeLabel(nativeText('settings-version'), 40, 666, 340, 14, 10, '#555558', 400, 'center');
}
function nativeOtherDialogs() {
  if (nativeVisible('overlay-add')) {
    const { x, y, w } = nativeDialog('overlay-add', 273);
    nativeLabel(nativeById('overlay-add').querySelector('h3').textContent, x, y, w, 22, 17, '#fff', 600);
    nativeLabel(nativeById('overlay-add').querySelector('.hint').textContent, x, y + 28, w, 36, 12, '#777', 400, 'left', true);
    const button = nativeById('btn-clipboard-import');
    nativeRect(x, y + 72, w, 106, '#0e1b17', 14); nativeRect(x, y + 72, w, 106, '#174c36', 14, 1);
    nativeSvg(button, x + w / 2 - 12, y + 97, 24, 24, '#00c07f');
    nativeLabel(button.querySelector('span').textContent, x + 8, y + 133, w - 16, 16, 14, '#00c07f', 600, 'center');
    nativeHit(button, x, y + 72, w, 106); nativeButton(nativeById('cancel-add'), x, y + 194, w, 39);
  }
  if (nativeVisible('overlay-share')) {
    const { x, y, w } = nativeDialog('overlay-share', 449);
    nativeLabel(nativeById('overlay-share').querySelector('.drawer-head span').textContent, x, y, w - 28, 24, 17, '#fff', 600); nativeClose('close-share', x + w - 24, y);
    nativeRect(123, y + 44, 174, 174, '#fff', 12);
    const raw = nativeById('share-qr').src;
    if (raw.startsWith('data:image/svg+xml,')) nativeScene.ops.push({ kind: 'svg', source: decodeURIComponent(raw.slice(19)), x: 135, y: y + 56, w: 150, h: 150 });
    nativeInput(nativeById('share-link'), x, y + 230, w, 130);
    nativeButton(nativeById('btn-copy-share'), x, y + 372, w, 37, true);
  }
  if (nativeVisible('overlay-language')) {
    const { x, y, w } = nativeDialog('overlay-language', 162, 300, 18);
    nativeLabel(nativeById('overlay-language').querySelector('.drawer-head span').textContent, x, y, w - 28, 24, 17, '#fff', 600); nativeClose('close-language', x + w - 24, y);
    document.querySelectorAll('[data-language]').forEach((el, i) => {
      const rowY = y + 38 + i * 46;
      nativeRect(x, rowY, w, 42, el.classList.contains('active') ? '#10291f' : '#121214', 9);
      nativeLabel(el.querySelector('span').textContent, x + 12, rowY, w - 38, 42, 12, '#ddd');
      if (el.classList.contains('active')) nativeLabel('✓', x + w - 28, rowY, 20, 42, 14, '#00c07f', 700);
      nativeHit(el, x, rowY, w, 42);
    });
  }
  if (nativeVisible('overlay-running-apps')) {
    const list = nativeById('running-apps-list');
    const message = list.querySelector('.list-message');
    const listHeight = Math.min(251,message ? 74 : 18+list.children.length*40);
    const { x, y, w } = nativeDialog('overlay-running-apps', 249+listHeight,350);
    nativeLabel(nativeById('overlay-running-apps').querySelector('.drawer-head span').textContent, x, y, w - 28, 24, 17, '#fff', 600); nativeClose('close-running-apps', x + w - 24, y);
    nativeInput(nativeById('running-apps-search'), x, y + 38, w, 34, 12);
    nativeLabel(nativeById('running-apps-exclude-system').closest('label').querySelector('.s-title-small').textContent, x, y + 96, w - 48, 16, 12, '#aaa');
    nativeToggle(nativeById('running-apps-exclude-system'), x + w - 38, y + 94);
    const top = y + 140, height = listHeight;
    nativeRect(x,top,w,height,'#0f0f11',12); nativeRect(x,top,w,height,'#1f1f22',12,1);
    const max = Math.max(0, list.children.length * 40 + 18 - height); list.scrollTop = Math.min(max, Math.max(0, list.scrollTop));
    nativeScene.scrolls.push({ uid: list.uid, x, y: top, w, h: height, max });
    nativeScene.ops.push({ kind: 'clip', x, y: top, w, h: height });
    list.children.forEach((row, i) => {
      const ry = top + 9 + i * 40 - list.scrollTop;
      if (row.classList.contains('list-message')) { nativeLabel(row.textContent,x+8,ry,w-16,56,12,'#777',400,'center',true); return; }
      const checked = row.querySelector('input')?.checked;
      nativeRect(x + 21, ry + 10, 16, 16, checked ? '#00c07f' : '#303033', 3);
      if (checked) nativeLabel('✓', x + 21, ry + 8, 16, 20, 12, '#fff', 700, 'center');
      nativeLabel(row.textContent, x + 49, ry, w - 70, 36, 12, '#ddd');
      if (ry >= top && ry + 36 <= top + height) nativeHit(row, x, ry, w, 36);
    }); nativeScene.ops.push({ kind: 'unclip' });
    nativeButton(nativeById('cancel-running-apps'), x, top + height + 30, (w - 10) / 2, 39);
    nativeButton(nativeById('confirm-running-apps'), x + (w + 10) / 2, top + height + 30, (w - 10) / 2, 39, true);
  }
  if (nativeVisible('overlay-speedtest')) {
    const { x, y, w } = nativeDialog('overlay-speedtest', 353,320,24,28);
    if (nativeVisible('speedtest-results-area')) {
      ['down', 'up', 'ping'].forEach((kind, i) => {
        const row = nativeById(`speedtest-res-${kind}`).closest('.speedtest-res-row');
        const unit = row.querySelector('.speedtest-res-unit').textContent;
        const unitWidth = nativeMeasure(unit,14), rowX = 210-(92+unitWidth)/2, rowY = y+55.5+i*53;
        const color = kind==='down'?'#00c07f':kind==='up'?'#4da3ff':'#ffa726';
        nativeSvg(row,rowX,rowY+15,14,14,'#fff');
        nativeLabel(nativeText(`speedtest-res-${kind}`),rowX+36,rowY,48,35,26,color,700);
        nativeLabel(unit,rowX+92,rowY+14,unitWidth,19,14);
      });
    } else {
      for (const op of nativeById('speedtest-canvas').nativeCanvas?.ops || []) nativeScene.ops.push({ ...op, x: op.x + 110, y: op.y + y + 26,
        ...(op.kind === 'line' ? { w: op.w + 110, h: op.h + y + 26 } : {}) });
      nativeLabel(nativeText('speedtest-stage'), x, y + 103.5, w, 9, 9, nativeById('speedtest-stage').style.color || '#999', 700, 'center');
      const live = nativeById('speedtest-live-val');
      nativeLabel(live.children.filter(child=>!child.tagName).map(child=>child.textContent).join(''), x, y + 116.5, w, 20, 20, '#fff', 700, 'center');
      nativeLabel(live.querySelector('.gauge-unit')?.textContent || '', x, y+139.5,w,9,9,'#777',600,'center');
    }
    const close = nativeById('speedtest-btn-close'), action = nativeById('speedtest-btn-action');
    const closeWidth = nativeMeasure(close.textContent,13,600)+22, actionWidth = nativeMeasure(action.textContent,13,600)+20;
    const buttonX = 210-(closeWidth+actionWidth+10)/2;
    nativeButton(close, buttonX, y + 266, closeWidth, 39);
    nativeButton(action, buttonX+closeWidth+10,y+266,actionWidth,39,true);
  }
  for (const kind of ['confirm', 'message', 'settings-unsaved']) {
    if (!nativeVisible(`overlay-${kind}`)) continue;
    const el = nativeById(`overlay-${kind}`);
    const width = kind === 'settings-unsaved' ? 330 : 280;
    const message = el.querySelector('.confirm-message').textContent;
    const lines = Math.max(1,Math.ceil(nativeMeasure(message,13)/(width-42)));
    const textHeight = lines*19.5, buttonHeight = kind==='settings-unsaved' ? 33 : 32;
    const box = nativeDialog(`overlay-${kind}`,42+textHeight+30+buttonHeight,width,21);
    const {x,y,w} = box;
    nativeLabel(message,x,y,w,textHeight,13,'#f2f2f2',400,'center',true);
    const buttons = el.querySelectorAll('button');
    if (kind==='message') nativeButton(buttons[0],210-66,y+textHeight+30,132,32,true,12);
    else buttons.forEach((button,i)=>nativeButton(button,x+i*(w+8)/buttons.length,y+textHeight+30,(w-(buttons.length-1)*8)/buttons.length,buttonHeight,i===buttons.length-1,kind==='settings-unsaved'?11:12));
  }
}
globalThis.__nativeBuildScene = () => {
  nativeScene = { ops: [], hits: [], scrolls: [] };
  nativeRect(20, 20, 380, 680, '#09090b', 36);
  nativeMain();
  nativeTopBar();
  if (nativeVisible('update-banner')) {
    nativeRect(40, 90, 340, 72, '#17191a', 14);
    nativeLabel(nativeText('update-banner-title'), 53, 101, 190, 24, 12, '#fff', 600);
    nativeLabel(nativeText('update-banner-detail'), 53, 125, 190, 26, 10, '#888', 400, 'left', true);
    nativeButton(nativeById('update-banner-later'), 246, 108, 52, 30, false, 10);
    nativeButton(nativeById('update-banner-install'), 304, 108, 62, 30, true, 10);
  }
  if (nativeVisible('overlay-profiles')) nativeProfiles();
  if (nativeVisible('overlay-settings')) nativeSettings();
  nativeOtherDialogs();
  if (nativeSelect) {
    const { element, x, y, w } = nativeSelect;
    nativeRect(x, y, w, element.children.length * 32, '#242427', 8);
    element.children.forEach((option, i) => {
      nativeLabel(option.textContent, x + 8, y + i * 32, w - 16, 32, 11);
      nativeScene.hits.push({ uid: option.uid, x, y: y + i * 32, w, h: 32, kind: 'option' });
    });
  }
  if (nativeTooltip) {
    nativeRect(60, 558, 300, 76, '#29292c', 10);
    nativeLabel(nativeTooltip, 72, 566, 276, 60, 12, '#eee', 400, 'left', true);
  }
  if (nativePrunedTreeVersion !== nativeTreeVersion) {
    for (const [uid, element] of nativeElements) if (!element.isConnected) nativeElements.delete(uid);
    nativePrunedTreeVersion = nativeTreeVersion;
  }
  nativeDirty = false;
  return JSON.stringify(nativeScene);
};
function nativeFindHit(x, y) { return [...nativeScene.hits].reverse().find(hit => x >= hit.x && y >= hit.y && x < hit.x + hit.w && y < hit.y + hit.h); }
globalThis.__nativePointer = (type, x, y, delta = 0) => {
  const hit = nativeFindHit(x, y); const element = nativeElements.get(hit?.uid);
  if (type === 'move') {
    if (nativeHover !== (hit?.uid || 0)) {
      nativeElements.get(nativeHover)?.dispatchEvent(new NativeEvent('pointerleave'));
      nativeHover = hit?.uid || 0;
      element?.dispatchEvent(new NativeEvent('pointerenter'));
      nativeTooltip = hit?.kind === 'help' ? element.dataset.tooltip || '' : '';
      markNativeDirty();
    }
    return;
  }
  if (type === 'wheel') {
    const scroll = [...nativeScene.scrolls].reverse().find(item => x >= item.x && y >= item.y && x < item.x + item.w && y < item.y + item.h);
    if (scroll) { const el = nativeElements.get(scroll.uid); el.scrollTop = Math.max(0, Math.min(scroll.max, el.scrollTop + delta)); markNativeDirty(); } return;
  }
  nativeTooltip = '';
  if (!element) { nativeSelect = null; markNativeDirty(); return; }
  if (hit.kind === 'input') element.focus();
  else {
    nativeFocus = element.uid;
    if (hit.kind === 'toggle') { element.checked = !element.checked; element.dispatchEvent(new NativeEvent('change')); }
    else if (hit.kind === 'select') { nativeSelect = { element, x: hit.x, y: hit.y + 32, w: hit.w }; }
    else if (hit.kind === 'option') { const select = nativeSelect.element; select.value = element.getAttribute('value'); nativeSelect = null; select.dispatchEvent(new NativeEvent('change')); }
    else if (hit.kind === 'help') nativeTooltip = element.dataset.tooltip || '';
    else { nativeSelect = null; element.dispatchEvent(new NativeEvent('click')); }
  }
  markNativeDirty();
};
globalThis.__nativeSetFocus = uid => {
  nativeFocus = uid; nativeCaret = nativeElements.get(uid)?.value.length || 0;
  nativeAnchor = nativeCaret; nativeSelection = false; markNativeDirty();
};
globalThis.__nativeKey = async (key, ctrl = false, shift = false) => {
  const element = nativeElements.get(nativeFocus);
  if (key === 'Tab') {
    const seen = new Set();
    const controls = nativeScene.hits.filter(hit => hit.uid && !seen.has(hit.uid) && !['block','help'].includes(hit.kind) && !nativeElements.get(hit.uid)?.classList.contains('overlay-bg') && seen.add(hit.uid));
    const at = controls.findIndex(hit => hit.uid === nativeFocus);
    __nativeSetFocus(controls[(at + (shift ? -1 : 1) + controls.length) % controls.length]?.uid || 0); return;
  }
  if (key === 'Escape') { if (nativeSelect) { nativeSelect = null; markNativeDirty(); return; } nativeTooltip = ''; nativeWindowEvents.dispatchEvent(new NativeEvent('keydown', { key })); return; }
  if (element?.type === 'checkbox') {
    if (key === 'Enter' || key === ' ') { element.checked = !element.checked; element.dispatchEvent(new NativeEvent('change')); }
    return;
  }
  if (element?.tagName === 'SELECT') {
    const options = element.children; const at = options.findIndex(option => option.getAttribute('value') === element.value);
    if (key === 'ArrowUp' || key === 'ArrowDown') { element.value = options[Math.max(0,Math.min(options.length-1,at+(key==='ArrowUp'?-1:1)))].getAttribute('value'); element.dispatchEvent(new NativeEvent('change')); }
    if (key === 'Enter' || key === ' ') { const hit = nativeScene.hits.find(hit=>hit.uid===element.uid); nativeSelect = nativeSelect ? null : {element,x:hit.x,y:hit.y+32,w:hit.w}; markNativeDirty(); }
    return;
  }
  const editing = element && ['INPUT','TEXTAREA'].includes(element.tagName);
  if (ctrl && key.toLowerCase() === 'a' && editing) { nativeSelection = true; nativeAnchor = 0; nativeCaret = element.value.length; markNativeDirty(); return; }
  const start = nativeSelection ? Math.min(nativeAnchor,nativeCaret) : nativeCaret;
  const end = nativeSelection ? Math.max(nativeAnchor,nativeCaret) : nativeCaret;
  if (ctrl && ['c','x'].includes(key.toLowerCase()) && editing) {
    await navigator.clipboard.writeText(element.value.slice(start,end));
    if (key.toLowerCase()==='x' && !element.readOnly && nativeSelection) { element.value=element.value.slice(0,start)+element.value.slice(end); nativeCaret=start; nativeSelection=false; element.dispatchEvent(new NativeEvent('input')); }
    return;
  }
  if (ctrl && key.toLowerCase() === 'v') {
    const text = await navigator.clipboard.readText();
    if (editing && !element.readOnly) { element.value = element.value.slice(0,start) + text + element.value.slice(end); nativeCaret = start + text.length; nativeSelection = false; element.dispatchEvent(new NativeEvent('input')); }
    else nativeWindowEvents.dispatchEvent(new NativeEvent('paste', { clipboardData: { getData: () => text } })); return;
  }
  if (element && element.tagName !== 'INPUT' && element.tagName !== 'TEXTAREA') {
    if (key === 'Enter' || key === ' ') element.dispatchEvent(new NativeEvent('click'));
    return;
  }
  if (!element) return;
  if (['ArrowLeft','ArrowRight','Home','End'].includes(key)) {
    if (!shift) nativeAnchor = nativeCaret;
    nativeCaret = key === 'Home' ? 0 : key === 'End' ? element.value.length : Math.max(0,Math.min(element.value.length,nativeCaret+(key==='ArrowLeft'?-1:1)));
    nativeSelection = shift && nativeCaret !== nativeAnchor; markNativeDirty(); return;
  }
  if (element.readOnly) return;
  let from = start, to = end, insert = '';
  if (key === 'Backspace') from = nativeSelection ? start : Math.max(0,start-1);
  else if (key === 'Delete') to = nativeSelection ? end : Math.min(element.value.length,end+1);
  else if (key === 'Enter' && element.tagName === 'TEXTAREA') insert = '\n';
  else if ([...key].length === 1 && !ctrl) { if (element.type==='number' && !/\d/.test(key)) return; insert = key; }
  else return;
  element.value = element.value.slice(0,from) + insert + element.value.slice(to); nativeCaret = from + insert.length;
  nativeSelection = false; element.dispatchEvent(new NativeEvent('input')); markNativeDirty();
};
globalThis.__nativeDirty = () => nativeDirty;
const nativeQr = globalThis.qrcode;
globalThis.qrcode = (...args) => {
  const qr = nativeQr(...args);
  qr.createDataURL = () => 'data:image/svg+xml,' + encodeURIComponent(qr.createSvgTag({ cellSize: 5, margin: 10 }));
  return qr;
};
document.dispatchEvent(new NativeEvent('DOMContentLoaded'));
