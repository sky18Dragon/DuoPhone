/* PocketJS v0.12.0 pure smartphone dialer UI (DarkPhonePalette).
 * Kotlin owns every business field; bottom deck drives tab/navigation state.
 * Focus ring stays hidden on touch and appears only on hardware D-pad navigation. */
(function () {
  'use strict';
  const P = {w:1,h:2,pos:24,top:25,left:28,display:29,hitPass:32,bg:64,radius:68,border:70,borderW:71,color:96,font:97,align:98};
  /** @param {number} r @param {number} g @param {number} b */
  const color = (r,g,b) => (0xff000000 | b<<16 | g<<8 | r) >>> 0;
  const C = {
    bg: color(18, 21, 27),
    cardBg: color(28, 33, 43),
    primaryText: color(237, 240, 247),
    secondaryText: color(147, 157, 173),
    accentBlue: color(110, 164, 255),
    dialKeyBg: color(36, 41, 52),
    callGreen: color(46, 184, 96),
    callRed: color(224, 72, 72),
    barMuted: color(58, 65, 80),
    homeBar: color(74, 81, 96),
    white: color(255, 255, 255),
  };
  /** @type {{role:string,connection:string,signalBars:number,sim:string,call:string,page:string,screen:string,dialDisplay:string,unreadCount:number,draftLength:number,loading?:boolean,smsTarget?:string,smsDraftPreview?:string}|null} */
  let snapshot = null;
  let focus = 0, showFocus = false, priorKeys = 0, eventFrames = 0;
  /** @type {Map<number,{hit:number,x:number,y:number}>} */
  let active = new Map();
  /** @type {{id:number,labelId:number,baseBg:number,baseInk:number,fire:()=>void}[]} */
  const buttons = [];
  /** @type {number[]} */ const phoneNodes = [];
  /** @type {number[]} */ const smsNodes = [];
  /** @type {number[]} */ const settingsNodes = [];

  /** @param {number} type @param {number} x @param {number} y @param {number} w @param {number} h @param {number=} bg @param {number[]=} group */
  function node(type,x,y,w,h,bg,group) {
    const id = ui.createNode(type);
    [[P.pos,1],[P.left,x],[P.top,y],[P.w,w],[P.h,h]].forEach(([p,v])=>ui.setProp(id,p,v));
    if (bg !== undefined) ui.setProp(id, P.bg, bg);
    ui.insertBefore(1, id, 0);
    if (group) group.push(id);
    return id;
  }

  /** @param {number} x @param {number} y @param {number} w @param {number} h @param {string} label @param {number} slot @param {number=} align @param {number=} ink @param {number[]=} group */
  function text(x,y,w,h,label,slot,align=0,ink=C.primaryText,group) {
    const id = node(1, x, y, w, h, undefined, group);
    ui.setProp(id, P.font, slot);
    ui.setProp(id, P.align, align);
    ui.setProp(id, P.color, ink);
    ui.setProp(id, P.hitPass, 1);
    ui.setText(id, label);
    return id;
  }

  /** @param {string} type @param {Record<string,string>} extra */
  function send(type,extra={}) { globalThis.__duoDispatch(JSON.stringify({version:1,type,...extra})); }

  /** @param {number} x @param {number} y @param {number} w @param {number} h @param {number} radius @param {number} bg @param {string} label @param {number} slot @param {number} labelY @param {string} type @param {Record<string,string>} extra @param {number=} ink @param {number[]=} group */
  function pill(x,y,w,h,radius,bg,label,slot,labelY,type,extra={},ink=C.primaryText,group) {
    const id = node(0, x, y, w, h, bg, group);
    ui.setProp(id, P.radius, radius);
    const labelId = text(x, y + labelY, w, h - labelY, label, slot, 1, ink, group);
    const entry = { id, labelId, baseBg: bg, baseInk: ink, fire: () => send(type, extra) };
    buttons.push(entry);
    return entry;
  }

  ui.setProp(1, P.bg, C.bg);

  // Authentic smartphone status bar
  const status = text(22, 10, 140, 18, '4G LTE', 0, 0, C.primaryText);
  const sigBars = [6, 9, 12, 15].map((h, i) => {
    const bar = node(0, 254 + i * 6, 23 - h, 4, h, C.barMuted);
    ui.setProp(bar, P.radius, 1);
    ui.setProp(bar, P.hitPass, 1);
    return bar;
  });
  const badge4g = text(282, 10, 26, 16, '4G', 0, 0, C.barMuted);
  const batBox = node(0, 312, 11, 24, 11, C.bg);
  ui.setProp(batBox, P.radius, 3);
  ui.setProp(batBox, P.border, C.barMuted);
  ui.setProp(batBox, P.borderW, 1);
  ui.setProp(batBox, P.hitPass, 1);
  const batFill = node(0, 314, 13, 17, 7, C.primaryText);
  ui.setProp(batFill, P.radius, 1);
  ui.setProp(batFill, P.hitPass, 1);
  const batCap = node(0, 337, 14, 2, 5, C.primaryText);
  ui.setProp(batCap, P.radius, 1);
  ui.setProp(batCap, P.hitPass, 1);

  // Clean upper display zone (blank until numbers are typed or sub-page active)
  const dial = text(18, 88, 324, 38, ' ', 3, 1, C.primaryText);
  const subtitle = text(24, 134, 312, 18, ' ', 0, 1, C.accentBlue);
  const toast = text(24, 156, 312, 18, ' ', 0, 1, C.callGreen);

  // Pure PHONE layer: 3x4 circular T9 keypad (indices 0..11)
  const digits = ['1','2','3','4','5','6','7','8','9','*','0','#'];
  const subLetters = ['', 'ABC', 'DEF', 'GHI', 'JKL', 'MNO', 'PQRS', 'TUV', 'WXYZ', '', '+', ''];
  digits.forEach((d, i) => {
    const x = 50 + (i % 3) * 96;
    const y = 192 + Math.floor(i / 3) * 78;
    const hasSub = subLetters[i].length > 0;
    pill(x, y, 68, 68, 34, C.dialKeyBg, d, 2, hasSub ? 11 : 20, 'digit', { digit: d }, C.primaryText, phoneNodes);
    if (hasSub) text(x, y + 44, 68, 16, subLetters[i], 0, 1, C.secondaryText, phoneNodes);
  });

  // Pure call control row (indices 12..14): subtle X/DEL appear when digits exist, green CALL circle
  const clearBtn = pill(50, 504, 68, 68, 34, C.bg, ' ', 1, 25, 'back', {}, C.secondaryText, phoneNodes);
  const callBtn = pill(146, 504, 68, 68, 34, C.callGreen, 'CALL', 0, 27, 'call', {}, C.white, phoneNodes);
  callBtn.fire = () => send(snapshot && snapshot.call !== 'IDLE' && snapshot.call !== 'INCOMING' ? 'hangup' : 'call');
  const delBtn = pill(242, 504, 68, 68, 34, C.bg, ' ', 0, 27, 'backspace', {}, C.secondaryText, phoneNodes);

  // Dedicated SMS layer (hidden on dialer; visible when bottom deck selects SMS)
  const smsCard = node(0, 28, 224, 304, 96, C.cardBg, smsNodes);
  ui.setProp(smsCard, P.radius, 18);
  ui.setProp(smsCard, P.hitPass, 1);
  const smsLine1 = text(44, 248, 272, 20, 'Messages synced with bottom deck', 0, 1, C.secondaryText, smsNodes);
  const smsLine2 = text(44, 276, 272, 20, 'Use buttons below to edit or send', 0, 1, C.accentBlue, smsNodes);
  pill(36, 516, 136, 44, 22, C.cardBg, 'EDIT SMS', 1, 14, 'editDraft', {}, C.accentBlue, smsNodes);
  pill(188, 516, 136, 44, 22, C.callGreen, 'SEND', 1, 14, 'sendSms', {}, C.white, smsNodes);

  // Dedicated SETTINGS layer (hidden on dialer; visible when bottom deck selects SETTINGS)
  const sysCard = node(0, 28, 224, 304, 112, C.cardBg, settingsNodes);
  ui.setProp(sysCard, P.radius, 18);
  ui.setProp(sysCard, P.hitPass, 1);
  const sysInfo1 = text(44, 252, 272, 20, 'MODEM DISCONNECTED', 0, 1, C.primaryText, settingsNodes);
  const sysInfo2 = text(44, 282, 272, 20, 'Full Mode Active', 0, 1, C.secondaryText, settingsNodes);

  // Sleek smartphone home indicator bar only (no bottom menu bar)
  const homeIndicator = node(0, 124, 624, 112, 4, C.homeBar);
  ui.setProp(homeIndicator, P.radius, 2);
  ui.setProp(homeIndicator, P.hitPass, 1);

  const phoneRows = [
    [0, 1, 2],
    [3, 4, 5],
    [6, 7, 8],
    [9, 10, 11],
    [12, 13, 14],
  ];
  const smsRows = [[15, 16]];

  /** @param {string} pageName */
  function setPageVisibility(pageName) {
    phoneNodes.forEach(n => ui.setProp(n, P.display, pageName === 'PHONE' ? 0 : 1));
    smsNodes.forEach(n => ui.setProp(n, P.display, pageName === 'SMS' ? 0 : 1));
    settingsNodes.forEach(n => ui.setProp(n, P.display, pageName === 'SETTINGS' ? 0 : 1));
  }

  /** @param {string} str @param {number=} defaultSlot */
  function setDialText(str, defaultSlot = 3) {
    const slot = str.length > 16 ? 1 : (str.length > 12 ? 2 : defaultSlot);
    ui.setProp(dial, P.font, slot);
    ui.setText(dial, str);
  }

  /** @param {number[][]} grid @param {number} idx @returns {[number,number]} */
  function focusPos(grid, idx) {
    for (let r = 0; r < grid.length; r++) {
      const c = grid[r].indexOf(idx);
      if (c >= 0) return [r, c];
    }
    return [0, 0];
  }

  function paintFocus() {
    buttons.forEach((b, i) => {
      ui.setProp(b.id, P.bg, b.baseBg);
      ui.setProp(b.labelId, P.color, b.baseInk);
      if (showFocus && i === focus) {
        ui.setProp(b.id, P.border, C.accentBlue);
        ui.setProp(b.id, P.borderW, 2);
      } else {
        ui.setProp(b.id, P.borderW, 0);
      }
    });
  }

  globalThis.duoReceive = function (raw) {
    const value = JSON.parse(raw);
    if (value.version !== 1) throw Error('unsupported protocol');
    if (value.kind === 'event') { ui.setText(toast, value.code); eventFrames = 180; return; }
    if (value.kind !== 'initialize' && value.kind !== 'state') throw Error('unsupported message');
    if (value.role !== 'TOP') throw Error('wrong display role');
    snapshot = value;

    const bars = Math.max(0, Math.min(4, value.signalBars | 0));
    sigBars.forEach((b, i) => ui.setProp(b, P.bg, i < bars ? C.primaryText : C.barMuted));
    const cellular = value.connection === 'READY' || bars > 0;
    ui.setProp(badge4g, P.color, cellular ? C.primaryText : C.barMuted);
    ui.setText(status, cellular ? '4G LTE' : 'No Service');

    const incomingCall = value.call === 'INCOMING';
    const activeCall = value.call !== 'IDLE' && !incomingCall;
    callBtn.baseBg = activeCall ? C.callRed : C.callGreen;
    ui.setText(callBtn.labelId, activeCall ? 'HANGUP' : (incomingCall ? 'ANSWER' : 'CALL'));

    const hasDigits = Boolean(value.dialDisplay && value.dialDisplay.length > 0);
    ui.setText(clearBtn.labelId, hasDigits ? 'X' : ' ');
    ui.setText(delBtn.labelId, hasDigits ? 'DEL' : ' ');

    setPageVisibility(value.page);
    if (value.page === 'SMS') {
      if (focus < 15 || focus > 16) focus = 15;
      const target = (value.smsTarget || '').trim();
      const preview = (value.smsDraftPreview || '').trim();
      setDialText(target ? target.slice(0, 20) : ('UNREAD ' + value.unreadCount), 2);
      ui.setText(subtitle, 'Messages');
      ui.setText(smsLine1, target ? ('To: ' + target.slice(0, 24)) : 'Messages synced with bottom deck');
      ui.setText(smsLine2, preview ? preview.slice(0, 32) : (value.draftLength > 0 ? ('Draft: ' + value.draftLength + ' chars') : 'Use buttons below to edit or send'));
    } else if (value.page === 'SETTINGS') {
      setDialText('TOP POC / ' + (value.loading ? 'BUSY' : 'READY'), 2);
      ui.setText(subtitle, 'Settings');
      ui.setText(sysInfo1, 'MODEM ' + value.connection + ' / SIM ' + value.sim);
      ui.setText(sysInfo2, value.loading ? 'BUSY' : 'Full Mode Active');
    } else {
      if (focus > 14) focus = 0;
      if (hasDigits) {
        setDialText(value.dialDisplay, 3);
        ui.setText(subtitle, activeCall ? ('CALL ' + value.call) : 'Add Number');
      } else {
        setDialText(' ', 3);
        ui.setText(subtitle, activeCall ? ('CALL ' + value.call) : ' ');
      }
    }
    paintFocus();
  };

  globalThis.frame = function (keys, analog, contacts, hits) {
    /** @type {Map<number,{hit:number,x:number,y:number}>} */
    const next = new Map();
    for (let i = 0; i < contacts.length; i++) {
      const word = contacts[i] >>> 0;
      if (word & 0x40000000) { active.delete((word >>> 18) & 255); continue; }
      const wide = (word & 0x80000000) !== 0;
      const id = wide ? (word >>> 20) & 255 : (word >>> 18) & 255;
      const y = wide ? (word >>> 10) & 1023 : (word >>> 9) & 511;
      const x = wide ? word & 1023 : word & 511;
      next.set(id, { hit: hits[i], x, y });
    }
    for (const [id, entry] of active) if (!next.has(id) && snapshot) {
      if (entry.hit > 0 && ui.hitTestBounds(entry.x, entry.y) === entry.hit) {
        const index = buttons.findIndex(b => b.id === entry.hit);
        if (index >= 0) { focus = index; showFocus = false; buttons[index].fire(); paintFocus(); }
      }
    }
    active = next;
    const edge = keys & ~priorKeys;
    priorKeys = keys;
    const grid = snapshot && snapshot.page === 'SMS' ? smsRows : (snapshot && snapshot.page === 'SETTINGS' ? [] : phoneRows);
    if (grid.length > 0) {
      const vUp = (edge & 0x10) !== 0, vDown = (edge & 0x40) !== 0;
      if (vUp !== vDown) {
        const [r, c] = focusPos(grid, focus);
        const nr = (r + (vUp ? -1 : 1) + grid.length) % grid.length;
        focus = grid[nr][Math.min(c, grid[nr].length - 1)];
        showFocus = true;
        paintFocus();
      }
      const hLeft = (edge & 0x80) !== 0, hRight = (edge & 0x20) !== 0;
      if (hLeft !== hRight) {
        const [r, c] = focusPos(grid, focus);
        const row = grid[r];
        focus = row[(c + (hLeft ? -1 : 1) + row.length) % row.length];
        showFocus = true;
        paintFocus();
      }
      if (snapshot && (edge & 0x2000)) buttons[focus].fire();
    }
    if (snapshot && (edge & 0x4000)) send('back');
    if (eventFrames > 0 && --eventFrames === 0) ui.setText(toast, ' ');
  };
  setPageVisibility('PHONE');
  paintFocus();
})();
