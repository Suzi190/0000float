# Float 小手机 · 安卓壳（FloatShell）

一个极薄的安卓 App 壳：全屏 WebView 直接加载你自己的线上站点，外加一个**不依赖 Google 服务**的
离线推送长连接。目标用户：安卓机上收不到 Web Push 的用户
（国行机无 GMS、或所在网络到不了 Google 推送服务器）。

## 它解决什么

| 能力 | 网页版（安卓 Chrome） | 安卓壳 |
| --- | --- | --- |
| 页面功能 | ✅ | ✅ 完全一致（加载同一网页） |
| 沉浸式 | 受浏览器地址栏/状态栏限制 | ✅ 隐藏系统状态栏，全屏 |
| 键盘避让 | 依赖浏览器行为 | ✅ 原生 + 注入脚本双保险，输入栏不被键盘遮挡 |
| 备份导出 | 浏览器下载 | ✅ 原生分块写入系统「下载」目录，大备份不 OOM |
| 本地消息通知 | 需要页面在前台/Web Push | ✅ 网页通知转原生系统通知，后台也能弹 |
| 离线推送 | 依赖 FCM（需 GMS + 能连 Google） | ✅ 自建 Supabase Realtime 长连接，无 GMS 也能收 |
| 网页更新 | 自动 | ✅ 同样自动（壳只是浏览器，部署即生效） |

推送链路：`push-generate` 边缘函数生成完离线消息后，除了发 Web Push，
还会向 Supabase Realtime 的个人频道 `shellpush:<userId>` 广播一份；
壳内前台服务保持一条 WebSocket 长连接订阅该频道，收到即弹系统通知。
设置页的「测试」按钮走同一条链路，可直接验证。

## 一键构建（GitHub Actions）

仓库根目录已带工作流 `.github/workflows/android-shell.yml`：

1. 在仓库 **Settings → Secrets and variables → Actions → Variables** 新建
   `SHELL_SITE_URL`，值为你的 HTTPS 站点地址（同名 Secret 也兼容）。手动运行
   工作流时也可以临时填写 `site_url`，它会覆盖仓库变量。没有配置时工作流会
   直接报错，避免产出白屏包。
2. GitHub 仓库页 → **Actions** → **Build Android Shell APK** → **Run workflow**。
   （改动 `ai-virtual-phone-main/android-shell/**` 并推到 main 也会自动触发。）
3. 跑完后在该次运行的 **Artifacts** 里下载：
   - `float-shell-debug` —— debug 签名，**下载即可直接安装**，日常自用选这个；
   - `float-shell-release` —— 未配置签名密钥时是未签名包（装不了），配置后是正式签名包。
   > CI 每次生成的 debug 签名不同，覆盖安装前先卸载旧版；想长期覆盖升级请配置下面的正式签名。

### 正式签名（可选，想长期分发再做）

本地生成一个密钥库（一次性）：

```bash
keytool -genkeypair -v -keystore shell.keystore -alias floatshell \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 shell.keystore   # 得到一长串 base64
```

在仓库 **Settings → Secrets and variables → Actions** 添加四个 Secret：

| Secret | 内容 |
| --- | --- |
| `SHELL_KEYSTORE_BASE64` | 上面 base64 输出 |
| `SHELL_KEYSTORE_PASSWORD` | 密钥库密码 |
| `SHELL_KEY_ALIAS` | `floatshell`（或你起的别名） |
| `SHELL_KEY_PASSWORD` | 密钥密码 |

之后每次构建的 release 包即为已签名版。注意：debug 包和 release 包
签名不同，互相覆盖安装前要先卸载旧的。

## 安装与首次设置

1. 把 APK 传到手机（微信文件传输助手 / 网盘 / 数据线均可），点开安装，
   系统提示「未知来源」时允许本次安装。
2. 打开 App → 正常登录账号。
3. 允许**通知权限**（首次启动会弹）。
4. 保活（收推送的关键，尤其国产 ROM）：
   - 系统设置里把「小手机」加入**电池优化白名单 / 无限制后台**；
   - 允许**自启动**（小米/华为/OPPO/vivo 在各自的手机管家里）;
   - 最近任务里把它**锁定**（下拉卡片 → 锁），避免一键清理杀掉。
5. 通知栏会有一条「小手机 · 后台连接」的常驻小通知——这是长连接保活的
   代价。不想看到可以长按它 → 把「后台连接」这个通知渠道关掉显示，
   **不要关「角色消息」渠道**。

设置 → 离线推送里点「测试」：杀掉后台，约 6 秒后应收到系统通知即为连通。

## 数据存放与迁移

壳内网页数据（聊天记录、角色等）存在 App 自己的 WebView 沙箱里，
和手机浏览器**不互通**。从浏览器搬家：

- 浏览器里 设置 → 数据管理 → **导出备份**，壳里同一入口**导入**；
- 或两边都登录同一账号，用**云端备份**过渡。

导出位置：安卓 10+ 直接进系统「下载」目录；安卓 8/9 进 App 专属目录
（文件管理器 → `Android/data/app.floatphone.shell/files/Download`）。
导入时文件选择器已放宽为全部文件，`.zip` 备份不会被灰掉。

卸载 App 会清掉壳内数据，卸载前记得先导出或云备份。

## 更新模型

- **网页功能更新**：零成本。壳加载的是线上站点，Netlify 一部署，壳里即刻生效。
- **APK 更新**：只有改壳本身（推送逻辑、原生能力）才需要重新构建安装，预期很少。

## 实现速览

```
android-shell/
├── app/src/main/java/app/floatphone/shell/
│   ├── MainActivity.kt   # 全屏 WebView：沉浸式/站内导航/外链/文件选择/分块导出/通知桥/返回键
│   ├── PushService.kt    # 前台服务：借 WebView Cookie 取配置 → WS 长连接订阅 shellpush:<userId>
│   └── BootReceiver.kt   # 开机自启
├── app/src/main/res/raw/shell_bridge.js  # 页面加载后注入：通知/键盘/blob 导出增强
└── ...gradle 工程
```

- 壳的 UA 追加了 ` FloatShell/<版本>`，网页可借 `window.AndroidShell`
  或 UA 识别壳环境；桥上有 `getVersion()` / `openAppSettings()` /
  `requestIgnoreBatteryOptimization()` / `notify()` / `showToast()` /
  `beginFileSave()`、`appendFileSave()`、`endFileSave()`、`abortFileSave()`。
- 壳加载的是线上页面，网页代码不会打进 APK；沉浸式、键盘避让、通知和
  blob 导出都由 `shell_bridge.js` 在 `onPageFinished` 时注入完成。
- 推送服务首次连上后会向站点注册一条合成订阅（endpoint `shell:<userId>`），
  用途是让离线消息排期的「账号已订阅」门控放行；服务端对它只做
  Realtime 广播，不做 Web Push 投递。
- 壳内设置页的「离线推送」开关由壳自动接管（显示为已开启且不可关），
  Web Push 在 WebView 里本来就不可用。

## 服务端前提

- Supabase 项目已按 `docs/push-supabase.sql` 配好离线推送（pg_cron + 边缘函数）。
- `supabase/functions/push-generate/index.ts` 需要是包含 shellpush 广播的最新版
  （改动后需重新部署边缘函数）。
- Realtime 广播用 service key 直接调 HTTP API，无需额外建表或改配置。
