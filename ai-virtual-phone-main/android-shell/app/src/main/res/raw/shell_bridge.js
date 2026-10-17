/*
 * 注入到线上页面的壳增强脚本。
 *
 * 该 APK 只是 WebView 壳，加载的是线上站点，网页源码的改动不会进 APK，
 * 所以通知、键盘避让、blob 导出这三件网页侧能力由原生在 onPageFinished
 * 时注入本脚本完成。脚本要兼容站点后续的小改动，尽量只依赖稳定选择器。
 */
(function () {
  "use strict";

  if (window.__floatShellEnhancementsInstalled) return;
  var bridge = window.AndroidShell;
  if (!bridge) return;
  window.__floatShellEnhancementsInstalled = true;

  // ── 1. 系统通知：把网页 Notification / ServiceWorker 通知转到原生 ──
  function nativeNotify(title, options) {
    try {
      var body = "";
      if (options && typeof options === "object") {
        body = options.body || "";
      } else if (typeof options === "string") {
        body = options;
      }
      bridge.notify(String(title == null ? "小手机" : title), String(body || ""));
    } catch (e) {
      /* 通知失败不能影响聊天主流程 */
    }
  }

  var ShellNotification = function (title, options) {
    nativeNotify(title, options);
    // 网页会访问 notification.onclick / notification.close()，
    // 返回一个最小可用对象，避免在 Android 上抛异常。
    return { close: function () {}, onclick: null };
  };
  ShellNotification.permission = "granted";
  ShellNotification.requestPermission = function (callback) {
    if (typeof callback === "function") callback("granted");
    return Promise.resolve("granted");
  };
  try {
    Object.defineProperty(window, "Notification", {
      configurable: true,
      writable: true,
      value: ShellNotification,
    });
  } catch (e) {
    try {
      window.Notification = ShellNotification;
    } catch (e2) {
      /* ignore */
    }
  }

  // Android Chrome 的系统通知必须走 ServiceWorkerRegistration.showNotification，
  // 页面里 new Notification() 会抛 Illegal constructor。这里直接把这个入口
  // 换成原生通知，网页的 browser-notification.ts 无需改动。
  try {
    var swProto = window.ServiceWorkerRegistration && window.ServiceWorkerRegistration.prototype;
    if (swProto) {
      swProto.showNotification = function (title, options) {
        nativeNotify(title, options);
        return Promise.resolve();
      };
      swProto.getNotifications = function () {
        return Promise.resolve([]);
      };
    }
  } catch (e) {
    /* ignore */
  }

  // ── 2. 键盘避让 ──
  // 网页用 100lvh 铺满，Android 键盘弹出时布局视口不一定缩小，
  // 输入栏会留在键盘后面。这里量出「聊天容器底部 - 可视区底部」的差值，
  // 直接把输入栏抬起来；WebView 若已经被 adjustResize 压缩，差值为 0。
  var style = document.createElement("style");
  style.id = "float-shell-keyboard-style";
  style.textContent =
    ".chat-input-bar{bottom:var(--shell-keyboard-inset,0px)!important;}" +
    ".chat-room-wrapper > .page-body::after{flex-basis:calc(var(--chat-bottom-reserve,120px) + var(--shell-keyboard-inset,0px))!important;}" +
    ".chat-room-wrapper > .page-body{scroll-padding-bottom:calc(var(--chat-bottom-reserve,120px) + var(--shell-keyboard-inset,0px) + 18px)!important;}";
  if (!document.getElementById(style.id)) {
    (document.head || document.documentElement).appendChild(style);
  }

  var keyboardFrame = 0;

  function editableFocused() {
    var el = document.activeElement;
    if (!el) return false;
    var tag = el.tagName;
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable === true;
  }

  function measureKeyboardInset() {
    keyboardFrame = 0;
    var vv = window.visualViewport;
    if (!vv) return;
    var root = document.documentElement;
    var lift = parseFloat(getComputedStyle(root).getPropertyValue("--mobile-keyboard-lift")) || 0;
    // 网页自己的 Android 键盘位移已经生效时不要抬第二次。
    if (lift > 0 || !editableFocused()) {
      root.style.setProperty("--shell-keyboard-inset", "0px");
      return;
    }
    var shell = document.querySelector(".chat-room-wrapper") || document.querySelector(".phone-shell");
    if (!shell) {
      root.style.setProperty("--shell-keyboard-inset", "0px");
      return;
    }
    var rect = shell.getBoundingClientRect();
    var visibleBottom = vv.offsetTop + vv.height;
    var inset = Math.max(0, Math.round(rect.bottom - visibleBottom));
    if (inset < 24) inset = 0;
    root.style.setProperty("--shell-keyboard-inset", inset + "px");
  }

  function scheduleKeyboardInset() {
    if (keyboardFrame) cancelAnimationFrame(keyboardFrame);
    keyboardFrame = requestAnimationFrame(measureKeyboardInset);
  }

  var viewport = window.visualViewport;
  if (viewport) {
    viewport.addEventListener("resize", scheduleKeyboardInset);
    viewport.addEventListener("scroll", scheduleKeyboardInset);
  }
  window.addEventListener("resize", scheduleKeyboardInset);
  document.addEventListener("focusin", function () { setTimeout(scheduleKeyboardInset, 80); }, true);
  document.addEventListener("focusout", function () { setTimeout(scheduleKeyboardInset, 80); }, true);
  scheduleKeyboardInset();


  // ── 3. Blob 导出：分块交给原生写 MediaStore ──
  var handledBlobUrls = Object.create(null);
  var blobRegistry = Object.create(null);

  // 记住 createObjectURL 生成的 Blob，导出时直接拿原对象，
  // 不依赖 fetch(blob:)（部分站点 CSP 会拦 connect-src blob:）。
  try {
    var originalCreateObjectURL = URL.createObjectURL;
    if (typeof originalCreateObjectURL === "function") {
      URL.createObjectURL = function (obj) {
        var url = originalCreateObjectURL.call(URL, obj);
        try {
          if (obj && typeof obj.size === "number") {
            var now = Date.now();
            for (var key in blobRegistry) {
              if (now - blobRegistry[key].ts > 60000) delete blobRegistry[key];
            }
            blobRegistry[url] = { blob: obj, ts: now };
            var keys = Object.keys(blobRegistry);
            if (keys.length > 20) delete blobRegistry[keys[0]];
          }
        } catch (e) { /* ignore */ }
        return url;
      };
    }
  } catch (e) { /* ignore */ }

  function saveBlobWithBridge(blob, filename) {
    return new Promise(function (resolve, reject) {
      try {
        var begin = bridge.beginFileSave(filename || "float-download", blob.type || "application/octet-stream");
        if (begin !== "ok") {
          reject(new Error(begin));
          return;
        }
      } catch (e) {
        reject(e);
        return;
      }

      var chunkSize = 256 * 1024;
      var offset = 0;

      function abort(err) {
        try { bridge.abortFileSave(); } catch (e) { /* ignore */ }
        reject(err);
      }

      function next() {
        if (offset >= blob.size) {
          try {
            var result = String(bridge.endFileSave());
            if (result.indexOf("ERR:") === 0) reject(new Error(result));
            else resolve(result);
          } catch (e) {
            reject(e);
          }
          return;
        }

        var end = Math.min(offset + chunkSize, blob.size);
        var slice = blob.slice(offset, end);
        offset = end;
        var reader = new FileReader();
        reader.onerror = function () {
          abort(reader.error || new Error("读取导出文件失败"));
        };
        reader.onload = function () {
          try {
            var dataUrl = String(reader.result || "");
            var comma = dataUrl.indexOf(",");
            var base64 = comma >= 0 ? dataUrl.slice(comma + 1) : "";
            var res = bridge.appendFileSave(base64);
            if (res !== "ok") {
              abort(new Error(res));
              return;
            }
            setTimeout(next, 0);
          } catch (e) {
            abort(e);
          }
        };
        try {
          reader.readAsDataURL(slice);
        } catch (e) {
          abort(e);
        }
      }

      next();
    });
  }

  function saveUrlWithBridge(url, filename) {
    if (!url) return Promise.resolve("skipped");
    var stamp = handledBlobUrls[url];
    if (stamp && Date.now() - stamp < 60000) return Promise.resolve("skipped");
    handledBlobUrls[url] = Date.now();
    var entry = blobRegistry[url];
    var blobPromise = entry && entry.blob
      ? Promise.resolve(entry.blob)
      : fetch(url).then(function (response) { return response.blob(); });
    return blobPromise
      .then(function (blob) { return saveBlobWithBridge(blob, filename || "float-download"); })
      .catch(function (err) {
        delete handledBlobUrls[url];
        console.error("[FloatShell] export failed", err);
        try {
          bridge.showToast("导出失败：" + (err && err.message ? err.message : err));
        } catch (e) { /* ignore */ }
        return "ERR";
      });
  }

  // download-utils.ts 会创建 <a download> 后 .click()，这里在捕获阶段截住，
  // 避免 WebView 把 blob: 交给 DownloadListener 后直接忽略。
  document.addEventListener("click", function (event) {
    var node = event.target;
    var anchor = node && node.closest ? node.closest("a[download]") : null;
    if (!anchor) return;
    var href = anchor.href || "";
    if (href.indexOf("blob:") !== 0 && href.indexOf("data:") !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    saveUrlWithBridge(href, anchor.download || "float-download");
  }, true);

  // 原生 DownloadListener 收到 blob:/data: 时的兜底入口。
  window.__floatShellSaveBlob = function (url, filename) {
    return saveUrlWithBridge(url, filename);
  };
})();

