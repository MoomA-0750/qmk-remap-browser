/**
 * navigator.hid ポリフィル（WebHID標準互換のサブセット）。
 *
 * MainActivityが`WebViewCompat.addDocumentStartJavaScript()`でusevia.appの読み込み前に
 * 注入する想定（T8、未配線）。Kotlin側（`WebHidBridge` / `WebHidRpcRouter`,
 * `app/src/main/java/com/moomatechnica/qmkremap/bridge/`）とは、
 * `WebViewCompat.addWebMessageListener()`が注入するネイティブブリッジオブジェクト
 * （下記`NATIVE_OBJECT_NAME`）経由でJSON-RPC風にやり取りする。
 *
 * このファイル自体はWebView注入前提の生JSだが、`createWebHidPolyfill()`は
 * ネイティブブリッジ（`postMessage`を持つオブジェクト）を差し替え可能な形で
 * 切り出してあるので、実WebViewなしでもNode.jsからid相関・Promise resolve/reject・
 * requestDevice/getDevices/HIDDeviceの挙動をユニットテストできる
 * （`web/webhid-polyfill.test.js`、`node --test web/`で実行）。
 *
 * 実際のWebView結線（`WebViewCompat.addWebMessageListener`本体・real usevia.appでの
 * 動作）はT8で実装し実機検証が必要。
 */
(function (global) {
  'use strict';

  var NATIVE_OBJECT_NAME = 'QmkRemapNative';

  function bytesToUint8Array(bytesLike) {
    if (bytesLike instanceof Uint8Array) return bytesLike;
    if (Array.isArray(bytesLike)) return new Uint8Array(bytesLike);
    return new Uint8Array(0);
  }

  /**
   * @param {{ postMessage: function(string): void }} nativeBridge
   *   `WebViewCompat.addWebMessageListener`が注入するオブジェクト相当。
   *   テストではこれをモックに差し替える。
   */
  function createWebHidPolyfill(nativeBridge) {
    var nextRequestId = 1;
    var pendingCalls = new Map(); // id -> {resolve, reject}
    var openDevices = new Map(); // deviceId -> HIDDeviceインスタンス（oninputreport配送用）

    function callNative(method, params) {
      return new Promise(function (resolve, reject) {
        var id = nextRequestId++;
        pendingCalls.set(id, { resolve: resolve, reject: reject });
        nativeBridge.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
      });
    }

    /** Kotlin側からの応答/イベントを受け取る。壊れたメッセージはクラッシュさせず無視する。 */
    function handleNativeMessage(rawMessage) {
      var data;
      try {
        data = typeof rawMessage === 'string' ? JSON.parse(rawMessage) : rawMessage;
      } catch (e) {
        return;
      }
      if (data == null || typeof data !== 'object') return;

      if (data.event === 'inputreport') {
        var device = openDevices.get(data.deviceId);
        if (device) device._dispatchInputReport(data.reportId, data.data);
        return;
      }

      if (typeof data.id !== 'number') return;
      var pending = pendingCalls.get(data.id);
      if (!pending) return; // 該当idなし（タイムアウト後の遅延応答等）は無視
      pendingCalls.delete(data.id);

      if (Object.prototype.hasOwnProperty.call(data, 'error') && data.error != null) {
        pending.reject(new Error(data.error));
      } else {
        pending.resolve(data.result);
      }
    }

    function HIDDevice(descriptor) {
      this.opened = false;
      this.vendorId = descriptor.vendorId;
      this.productId = descriptor.productId;
      this.productName = descriptor.productName || '';
      this.collections = descriptor.collections || [];
      this._deviceId = descriptor.deviceId;
      this.oninputreport = null;
      this._inputReportListeners = [];
    }

    HIDDevice.prototype.open = function () {
      var self = this;
      return callNative('open', { deviceId: this._deviceId }).then(function () {
        self.opened = true;
        openDevices.set(self._deviceId, self);
      });
    };

    HIDDevice.prototype.close = function () {
      var self = this;
      return callNative('close', { deviceId: this._deviceId }).then(function () {
        self.opened = false;
        openDevices.delete(self._deviceId);
      });
    };

    // 標準WebHIDの`forget()`はデバイスへの許可自体を取り消すAPI。V1ではOS側のUSB権限管理を
    // 取り消す仕組みまでは実装しない（spec.md/plan.mdのスコープ外）ため、呼び出し側を
    // 例外で落とさないよう即resolveする最小実装とする。
    HIDDevice.prototype.forget = function () {
      var self = this;
      return Promise.resolve().then(function () {
        self.opened = false;
        openDevices.delete(self._deviceId);
      });
    };

    HIDDevice.prototype.sendReport = function (reportId, data) {
      return callNative('sendReport', {
        deviceId: this._deviceId,
        reportId: reportId,
        data: Array.from(bytesToUint8Array(data))
      });
    };

    HIDDevice.prototype.sendFeatureReport = function (reportId, data) {
      return callNative('sendFeatureReport', {
        deviceId: this._deviceId,
        reportId: reportId,
        data: Array.from(bytesToUint8Array(data))
      });
    };

    HIDDevice.prototype.receiveFeatureReport = function (reportId) {
      return callNative('receiveFeatureReport', { deviceId: this._deviceId, reportId: reportId }).then(
        function (result) {
          var bytes = bytesToUint8Array(result);
          return new DataView(bytes.buffer);
        }
      );
    };

    HIDDevice.prototype.addEventListener = function (type, listener) {
      if (type === 'inputreport') this._inputReportListeners.push(listener);
    };

    HIDDevice.prototype.removeEventListener = function (type, listener) {
      if (type !== 'inputreport') return;
      var idx = this._inputReportListeners.indexOf(listener);
      if (idx >= 0) this._inputReportListeners.splice(idx, 1);
    };

    HIDDevice.prototype._dispatchInputReport = function (reportId, data) {
      var bytes = bytesToUint8Array(data);
      var event = { device: this, reportId: reportId, data: new DataView(bytes.buffer) };
      if (typeof this.oninputreport === 'function') this.oninputreport(event);
      this._inputReportListeners.forEach(function (listener) {
        listener(event);
      });
    };

    // 標準WebHIDの`HID`は`EventTarget`を継承しており、`navigator.hid.addEventListener('connect'|'disconnect', ...)`
    // が呼べることを前提にしたサイト側コードがある（usevia.appも初期化時に呼んでおり、
    // 未実装だと`TypeError: navigator.hid.addEventListener is not a function`で例外になり
    // ページの初期化そのものが止まる）。V1では実際のconnect/disconnectイベント発火までは
    // 要件にないため（spec.md参照）、例外を起こさないよう登録だけ受け付ける最小実装とする。
    var connectListeners = [];
    var disconnectListeners = [];

    function listenersFor(type) {
      if (type === 'connect') return connectListeners;
      if (type === 'disconnect') return disconnectListeners;
      return null;
    }

    var hid = {
      requestDevice: function (options) {
        return callNative('requestDevice', options || {}).then(function (descriptors) {
          return (descriptors || []).map(function (d) {
            return new HIDDevice(d);
          });
        });
      },
      getDevices: function () {
        return callNative('getDevices', {}).then(function (descriptors) {
          return (descriptors || []).map(function (d) {
            return new HIDDevice(d);
          });
        });
      },
      addEventListener: function (type, listener) {
        var list = listenersFor(type);
        if (list) list.push(listener);
      },
      removeEventListener: function (type, listener) {
        var list = listenersFor(type);
        if (!list) return;
        var idx = list.indexOf(listener);
        if (idx >= 0) list.splice(idx, 1);
      }
    };

    return {
      navigatorHid: hid,
      handleNativeMessage: handleNativeMessage,
      HIDDevice: HIDDevice,
      _pendingCallCount: function () {
        return pendingCalls.size;
      }
    };
  }

  // ブラウザ(WebView)注入時: window.navigator.hid を実際に定義する。
  // T8でaddWebMessageListenerを実配線するまではwindow[NATIVE_OBJECT_NAME]が
  // 存在しないため何もしない(usevia.appからはWebHID未対応ブラウザとして見える)。
  if (typeof window !== 'undefined' && window.navigator) {
    var bridge = global[NATIVE_OBJECT_NAME];
    if (bridge) {
      var polyfill = createWebHidPolyfill(bridge);
      Object.defineProperty(window.navigator, 'hid', {
        value: polyfill.navigatorHid,
        configurable: true
      });
      bridge.onmessage = function (event) {
        polyfill.handleNativeMessage(event.data);
      };
    }
  }

  // Node.jsでのユニットテスト用エクスポート(実ブラウザの挙動には影響しない)。
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { createWebHidPolyfill: createWebHidPolyfill, NATIVE_OBJECT_NAME: NATIVE_OBJECT_NAME };
  }
})(typeof window !== 'undefined' ? window : globalThis);
