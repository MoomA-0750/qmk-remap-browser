'use strict';

/**
 * webhid-polyfill.js のユニットテスト（実WebView・実USBデバイス不要）。
 *
 * `createWebHidPolyfill()`にモックのネイティブブリッジ（postMessageを記録するだけの
 * オブジェクト）を渡し、Kotlin側からの応答を`handleNativeMessage()`で直接注入することで、
 * T5(JSON-RPC風ブリッジのid相関・resolve/reject)とT6(requestDevice/getDevices/
 * HIDDeviceの挙動)のうち実機を必要としない部分を検証する。
 *
 * 実行: `node --test web/`
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const { createWebHidPolyfill } = require(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'webhid-polyfill.js')
);

function createMockNativeBridge() {
  return {
    sent: [],
    postMessage(json) {
      this.sent.push(JSON.parse(json));
    }
  };
}

function lastSent(bridge) {
  return bridge.sent[bridge.sent.length - 1];
}

test('requestDevice resolves with HIDDevice-shaped objects built from the native result', () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  const promise = polyfill.navigatorHid.requestDevice({ filters: [{ usagePage: 0xff60, usage: 0x61 }] });

  const request = lastSent(bridge);
  assert.equal(request.method, 'requestDevice');
  assert.deepEqual(request.params, { filters: [{ usagePage: 0xff60, usage: 0x61 }] });

  polyfill.handleNativeMessage(
    JSON.stringify({
      id: request.id,
      result: [{ deviceId: 1, vendorId: 0xfeed, productId: 0x0001, productName: 'Corne' }]
    })
  );

  return promise.then((devices) => {
    assert.equal(devices.length, 1);
    assert.equal(devices[0].productName, 'Corne');
    assert.equal(devices[0].opened, false);
  });
});

test('requestDevice resolves with an empty array when no device is permitted', () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  const promise = polyfill.navigatorHid.requestDevice({});
  const request = lastSent(bridge);
  polyfill.handleNativeMessage(JSON.stringify({ id: request.id, result: [] }));

  return promise.then((devices) => {
    assert.deepEqual(devices, []);
  });
});

test('requestDevice rejects when native side reports an error, preserving Promise semantics', async () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  const promise = polyfill.navigatorHid.requestDevice({});
  const request = lastSent(bridge);
  polyfill.handleNativeMessage(JSON.stringify({ id: request.id, error: 'permission denied' }));

  await assert.rejects(promise, /permission denied/);
});

test('concurrent calls resolve independently even when native responses arrive out of order', async () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  const first = polyfill.navigatorHid.getDevices();
  const firstRequest = lastSent(bridge);
  const second = polyfill.navigatorHid.getDevices();
  const secondRequest = lastSent(bridge);

  assert.notEqual(firstRequest.id, secondRequest.id);

  // 2番目の呼び出しの応答を先に届ける
  polyfill.handleNativeMessage(
    JSON.stringify({ id: secondRequest.id, result: [{ deviceId: 2, vendorId: 1, productId: 2, productName: 'second' }] })
  );
  polyfill.handleNativeMessage(
    JSON.stringify({ id: firstRequest.id, result: [{ deviceId: 1, vendorId: 1, productId: 2, productName: 'first' }] })
  );

  const [firstResult, secondResult] = await Promise.all([first, second]);
  assert.equal(firstResult[0].productName, 'first');
  assert.equal(secondResult[0].productName, 'second');
});

test('sendReport serializes Uint8Array data as a plain array in the native call', () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  const promise = polyfill.navigatorHid.requestDevice({});
  const requestId = lastSent(bridge).id;
  polyfill.handleNativeMessage(
    JSON.stringify({ id: requestId, result: [{ deviceId: 5, vendorId: 1, productId: 1, productName: 'kb' }] })
  );

  return promise.then((devices) => {
    const device = devices[0];
    const openPromise = device.open();
    polyfill.handleNativeMessage(JSON.stringify({ id: lastSent(bridge).id, result: null }));

    return openPromise.then(() => {
      assert.equal(device.opened, true);

      device.sendReport(0, new Uint8Array([1, 2, 3]));
      const sendRequest = lastSent(bridge);
      assert.equal(sendRequest.method, 'sendReport');
      assert.equal(sendRequest.params.deviceId, 5);
      assert.equal(sendRequest.params.reportId, 0);
      assert.deepEqual(sendRequest.params.data, [1, 2, 3]);
    });
  });
});

test('oninputreport fires with the pushed report once the device is open', async () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  const devicesPromise = polyfill.navigatorHid.requestDevice({});
  const requestId = lastSent(bridge).id;
  polyfill.handleNativeMessage(
    JSON.stringify({ id: requestId, result: [{ deviceId: 9, vendorId: 1, productId: 1, productName: 'kb' }] })
  );
  const [device] = await devicesPromise;

  const openPromise = device.open();
  polyfill.handleNativeMessage(JSON.stringify({ id: lastSent(bridge).id, result: null }));
  await openPromise;

  let received = null;
  device.oninputreport = (event) => {
    received = event;
  };

  polyfill.handleNativeMessage(
    JSON.stringify({ event: 'inputreport', deviceId: 9, reportId: 1, data: [10, 20, 30] })
  );

  assert.ok(received, 'oninputreport should have fired');
  assert.equal(received.reportId, 1);
  assert.equal(received.device, device);
  assert.equal(received.data.getUint8(0), 10);
  assert.equal(received.data.getUint8(1), 20);
  assert.equal(received.data.getUint8(2), 30);
});

test('inputreport for an unopened/unknown deviceId is silently ignored', () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  // openされていないdeviceId宛のイベントで例外が飛ばないことだけを確認する
  assert.doesNotThrow(() => {
    polyfill.handleNativeMessage(JSON.stringify({ event: 'inputreport', deviceId: 999, reportId: 0, data: [] }));
  });
});

test('malformed JSON from native does not throw', () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  assert.doesNotThrow(() => {
    polyfill.handleNativeMessage('{not valid json');
  });
});

test('a response with an id that has no pending call is ignored, not thrown', () => {
  const bridge = createMockNativeBridge();
  const polyfill = createWebHidPolyfill(bridge);

  assert.doesNotThrow(() => {
    polyfill.handleNativeMessage(JSON.stringify({ id: 999999, result: 'nobody was waiting' }));
  });
});
