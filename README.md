# 简易浏览器（Simple Browser）

基于 Android 系统 WebView 的 Kotlin 简易浏览器，使用 Material 3 设计，界面文案不含 emoji。

## 功能清单

- 地址栏与搜索：输入网址直接跳转，输入关键词按所选搜索引擎搜索。
- 历史记录：自动记录访问过的网页，最多保留 500 条，可查看、单条删除、清空。
- 书签：当前页一键加入书签，可查看、单条删除、清空，点击即打开。
- User-Agent 定制：系统默认、Android Chrome、iPhone Safari、Windows Chrome、自定义字符串，另提供桌面模式快捷开关。
- 导航：后退、前进、刷新、首页按钮；系统返回键优先回退到上一页，回退到头后连续按两次才退出。
- 固定网页：见下节。
- 首页可选：空白页、Google、goy.com、自定义网址。
- 搜索引擎可选：Google、Bing、百度、DuckDuckGo、自定义模板（用 `%s` 表示关键词）。
- 下载管理：默认 8 个并发任务、每个任务最多 32 个并发分片，支持暂停、继续、断点续传、删除、打开已完成文件。
- 其他：页面加载进度、JavaScript 弹窗处理、网页文件上传选择、HTTP 明文兼容。

## 固定网页规则

开启时会读取当前网页域名，提取「可注册主域」并锁定。例如当前页是 `exp.goy.com`，锁定基准就是 `goy.com`。

之后放行：

- `goy.com`
- `xxx.goy.com`
- `xy.xxx.goy.com`

拦截：

- `other.com`
- `evilgoy.com`
- `goy.com.evil.com`
- `tel:`、`mailto:`、`intent:` 等外部协议跳转

判定口径：`host == base` 或 `host.endsWith("." + base)`，并配合常见两级公共后缀表处理 `co.uk`、`com.cn`、`com.hk` 等情况。页面里引用的第三方图片、脚本等子资源不属于「页面跳转」，不会被拦截；由 JavaScript 打开的新窗口、POST 提交与服务端重定向也会经过拦截检查。

实现位置：`app/src/main/java/com/yuanbao/simplebrowser/core/DomainGuard.kt` 与 `MainActivity.kt` 的 `WebViewClient`、`WebChromeClient`。

> 主域提取为轻量近似实现，未引入 Public Suffix List 数据库；若需要覆盖全部国家后缀，可替换为完整 PSL 解析库。

## 下载并发说明

Android 应用内不使用多进程下载，需求中的「8 任务、每个 32 进程」实现为：

- 全局最多 8 个下载任务同时运行，超出的排队。
- 每个任务按 HTTP Range 切分，最多 32 个分片并发下载，分片数可在设置中调整（1 到 64）。
- 小文件和服务端不支持 Range 时自动退化为单流下载。
- 分片写入应用私有下载目录，完成后合并；未完成的分片保留，用于暂停后续传。
- 下载目录为 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，Android 10 及以上不需要存储权限。

实现位置：`app/src/main/java/com/yuanbao/simplebrowser/download/DownloadEngine.kt`。

## 项目结构与技术栈

- Kotlin 1.9.25，Android Gradle Plugin 8.7.3，Gradle 8.9，JDK 17
- compileSdk / targetSdk 34，minSdk 26
- ViewBinding、Material 3、原生 SQLiteOpenHelper、OkHttp
- 无第三方浏览器内核，使用系统 WebView

```text
app/src/main/java/com/yuanbao/simplebrowser/
├── MainActivity.kt              主界面与 WebView 客户端
├── core/                        偏好设置、域名判定、数据库、数据模型
├── download/                    下载引擎与下载管理界面
└── ui/                          设置、历史与书签界面
```

## 本地构建

需要 JDK 17、Android SDK Platform 34 与 Build Tools 34.0.0。

```bash
./gradlew assembleDebug
```

若要本地打签名包，先准备 `release.jks`，再注入环境变量：

```bash
export KEYSTORE_FILE=/absolute/path/to/release.jks
export KEYSTORE_PASSWORD=你的库密码
export KEY_ALIAS=simplebrowser
export KEY_PASSWORD=你的密钥密码
./gradlew assembleRelease
```

未设置签名环境变量时，`assembleDebug` 与未签名 release 仍可正常配置，不会因缺少 keystore 报错。

## GitHub Actions 正式版构建

工作流：`.github/workflows/build-release.yml`

触发方式：

- 推送到 `main`：构建签名 APK 并上传为 Actions 产物。
- 推送 `v*` 标签：额外创建 GitHub Release 并附上签名 APK。
- 手动 `workflow_dispatch`。

流程依次完成：JDK 17、Gradle 8.9、从 Secret 解码 keystore、`assembleRelease`、`apksigner verify` 校验证书、上传 APK、清理 runner 上的 keystore 临时文件。

仓库需要以下 Actions Secrets，已由初始化脚本写入：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `release.jks` 的 base64 编码（无换行） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名，固定为 `simplebrowser` |
| `KEY_PASSWORD` | 密钥密码 |

### 统一签名与升级

- `applicationId` 固定为 `com.yuanbao.simplebrowser`，不随构建类型变化。
- 所有正式版都使用同一份 `release.jks` 签名，因此可以覆盖安装升级。
- 每次 CI 构建用 `GITHUB_RUN_NUMBER` 注入递增的 `versionCode`，`versionName` 为 `1.0.<run_number>`；打标签时用标签名作为版本名。
- 请另行保管 keystore 与密码：丢失后新包将无法覆盖安装，用户只能卸载重装。

### 密钥与凭据安全

- keystore、密码、GitHub Token 均不进入仓库，`.gitignore` 已排除 `*.jks`、`keystore.properties` 等。
- CI 只在 runner 的临时目录解码 keystore，构建结束即删除。
- 若 Token 曾以明文出现在聊天、日志或终端历史中，应尽快在 GitHub 设置中撤销并重新生成，之后只存放在 Secrets 中。

## 已知限制

- 固定网页的主域提取采用近似后缀表，不含完整 Public Suffix List。
- 下载分片对不支持 Range 的服务器自动退化为单流，此时 32 并发不生效。
- 单窗口浏览器，JavaScript 的 `window.open` 会在当前窗口打开。
