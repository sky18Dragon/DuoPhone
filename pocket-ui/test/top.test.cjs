// Contract/unit tests only: this host double does NOT establish native rendering.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function guest(hitTestBounds = () => 0) {
  let id = 1;
  const texts = new Map(), intents = [], boxes = [];
  const ui = {
    createNode: (type) => {
      const nid = ++id;
      if (type === 0) boxes.push({ id: nid, x: 0, y: 0, w: 0, h: 0, hitPass: 0, display: 0 });
      return nid;
    },
    setProp: (nid, p, v) => {
      const b = boxes.find(it => it.id === nid);
      if (!b) return;
      if (p === 28) b.x = v;
      else if (p === 25) b.y = v;
      else if (p === 1) b.w = v;
      else if (p === 2) b.h = v;
      else if (p === 29) b.display = v;
      else if (p === 32) b.hitPass = v;
    },
    insertBefore() {},
    setText: (n, t) => texts.set(n, t),
    hitTestBounds: (x, y) => {
      const custom = hitTestBounds(x, y);
      if (custom >= 0) return custom;
      const found = boxes.find(b => b.display === 0 && !b.hitPass && x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h);
      return found ? found.id : 0;
    },
  };
  const g = { ui, __duoDispatch: raw => intents.push(JSON.parse(raw)), Map };
  vm.createContext(g);
  vm.runInContext(fs.readFileSync('src/top.js', 'utf8'), g);
  return { g, texts, intents, boxes };
}

function packContact(id, x, y) {
  return (x > 511 || y > 511 ? (0x80000000 | (id << 20) | (y << 10) | x) : ((id << 18) | (y << 9) | x)) >>> 0;
}

const snapshot = {
  version: 1,
  kind: 'initialize',
  role: 'TOP',
  connection: 'READY',
  signalBars: 3,
  sim: 'READY',
  call: 'IDLE',
  page: 'PHONE',
  screen: 'PhoneDialing',
  dialDisplay: '**78',
  unreadCount: 2,
  draftLength: 0,
};

test('snapshot is authoritative and event does not mutate state', () => {
  const { g, texts } = guest();
  g.duoReceive(JSON.stringify(snapshot));
  assert.ok([...texts.values()].includes('**78'));
  g.duoReceive(JSON.stringify({ version: 1, kind: 'event', code: 'SAFE_CALL_BLOCKED' }));
  assert.ok([...texts.values()].includes('**78'));
  assert.ok([...texts.values()].includes('SAFE_CALL_BLOCKED'));
});

test('unknown protocol and role are rejected', () => {
  const { g } = guest();
  assert.throws(() => g.duoReceive(JSON.stringify({ ...snapshot, version: 2 })));
  assert.throws(() => g.duoReceive(JSON.stringify({ ...snapshot, role: 'BOTTOM' })));
});

test('confirm edge emits exactly one typed intent until released', () => {
  const { g, intents } = guest();
  g.duoReceive(JSON.stringify(snapshot));
  g.frame(0x2000, 0, [], []);
  g.frame(0x2000, 0, [], []);
  assert.deepEqual(intents, [{ version: 1, type: 'digit', digit: '1' }]);
  g.frame(0, 0, [], []);
  g.frame(0x4000, 0, [], []);
  assert.equal(intents[1].type, 'back');
});

test('no input before initialization', () => {
  const { g, intents } = guest();
  g.frame(0x2000, 0, [], []);
  assert.equal(intents.length, 0);
});

test('dpad navigates pure 5x3 dialpad rows cleanly', () => {
  const { g, intents } = guest();
  g.duoReceive(JSON.stringify(snapshot));
  // Starts on '1' (row 0, col 0). Up wraps to row 4 col 0 (BACK), Right to CALL, Up to '0'
  g.frame(0x10, 0, [], []); g.frame(0, 0, [], []);
  g.frame(0x20, 0, [], []); g.frame(0, 0, [], []);
  g.frame(0x2000, 0, [], []); g.frame(0, 0, [], []);
  assert.deepEqual(intents[0], { version: 1, type: 'call' });
  g.frame(0x10, 0, [], []); g.frame(0, 0, [], []);
  g.frame(0x2000, 0, [], []); g.frame(0, 0, [], []);
  assert.deepEqual(intents[1], { version: 1, type: 'digit', digit: '0' });
});

test('touch drag-off cancels tap and wide contact word triggers SMS/CALL rows', () => {
  const { g, intents } = guest(() => -1);
  g.duoReceive(JSON.stringify(snapshot));
  const hit1 = g.ui.hitTestBounds(84, 226);
  g.frame(0, 0, [packContact(1, 84, 226)], [hit1]);
  g.frame(0, 0, [packContact(1, 350, 10)], [hit1]);
  g.frame(0, 0, [], []);
  assert.equal(intents.length, 0);
  // Wide contact y > 511 on SMS page triggers EDIT SMS
  g.duoReceive(JSON.stringify({ ...snapshot, page: 'SMS', screen: 'SmsChat' }));
  const hitEdit = g.ui.hitTestBounds(104, 538);
  g.frame(0, 0, [packContact(2, 104, 538)], [hitEdit]);
  g.frame(0, 0, [], []);
  assert.deepEqual(intents, [{ version: 1, type: 'editDraft' }]);
});

test('active call switches call button to hangup and incoming switches to answer', () => {
  const { g, texts, intents } = guest(() => -1);
  g.duoReceive(JSON.stringify({ ...snapshot, call: 'ACTIVE', page: 'SETTINGS', loading: true }));
  assert.ok([...texts.values()].includes('HANGUP'));
  assert.ok([...texts.values()].includes('TOP POC / BUSY'));
  assert.equal(g.ui.hitTestBounds(84, 226), 0);
  g.duoReceive(JSON.stringify({ ...snapshot, call: 'INCOMING', page: 'PHONE', screen: 'IncomingCall' }));
  assert.ok([...texts.values()].includes('ANSWER'));
  const hitAnswer = g.ui.hitTestBounds(180, 538);
  g.frame(0, 0, [packContact(3, 180, 538)], [hitAnswer]);
  g.frame(0, 0, [], []);
  g.duoReceive(JSON.stringify({ ...snapshot, call: 'ACTIVE', page: 'PHONE', screen: 'InCall' }));
  const hitCall = g.ui.hitTestBounds(180, 538);
  g.frame(0, 0, [packContact(4, 180, 538)], [hitCall]);
  g.frame(0, 0, [], []);
  assert.deepEqual(intents, [{ version: 1, type: 'call' }, { version: 1, type: 'hangup' }]);
});

test('masked dial number and SMS draft preview render on top screen', () => {
  const { g, texts } = guest();
  g.duoReceive(JSON.stringify({ ...snapshot, page: 'PHONE', dialDisplay: '138****8000' }));
  assert.ok([...texts.values()].includes('138****8000'));
  g.duoReceive(JSON.stringify({ ...snapshot, page: 'SMS', smsTarget: '138****8000', smsDraftPreview: 'Hello 4G', draftLength: 8 }));
  assert.ok([...texts.values()].includes('138****8000'));
  assert.ok([...texts.values()].includes('To: 138****8000'));
  assert.ok([...texts.values()].includes('Hello 4G'));
});
