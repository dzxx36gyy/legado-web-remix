# 阅读 Web（legado-web-remix）

本仓库 fork 自 [kukume/legado-web](https://github.com/kukume/legado-web)，在原项目基础上增加和修改了下面“本 fork 的改动”中列出的功能。镜像由本仓库的 GitHub Actions 构建，发布为 `ghcr.io/dzxx36gyy/legado-web-remix`。

原项目简介：把 [阅读（LegadoTeam/legado）](https://github.com/LegadoTeam/legado) 的书源引擎搬到服务端，用网页阅读：

- **后端**：Kotlin + Ktor 3 + Exposed 1.x，数据库支持 **SQLite / PostgreSQL**
- **前端**：Vue 3 + Vite + TypeScript + Pinia + Naive UI
- **书源引擎**：移植自 LegadoTeam/legado（引擎修复同步到`531f9fbf`），兼容阅读 3.0 书源 JSON 与纯 JS 书源
- **本地书籍**：上传 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF
- **规则**：替换规则、字典规则、TXT 目录规则
- **备份与恢复**：支持与阅读 App 的备份 zip 互通，可恢复 App 备份中的书架（含进度、分组、书签）、书源、替换规则、字典规则、TXT 目录规则、书源订阅，导出的 zip 也能在 App 中恢复；支持 WebDAV。

> 本项目不内置任何书源，只提供通用的规则解析工具。请遵守所在地法律，仅用于合法获取的内容。

## 本 fork 的改动

### 新增

- **AI 书源**：在“我的 → 朗读与 AI”中添加 OpenAI 兼容模型（预设 DeepSeek、OpenRouter、硅基流动、Kilo Gateway，可从服务商拉取模型列表），仅创建者能使用，密钥加密保存在服务端且不返回前端。
  - **AI 检测**：书源列表中对单个私有书源依次检查搜索、详情、目录、正文，通过的书源不请求模型；没有配置模型时只检测不修复。
  - **AI 修复**：只修复失败的阶段，显示每个阶段的进度、耗时和真实失败原因；模型新增的 JavaScript 需要先确认执行，再重新校验并确认保存。验证码会终止修复。
  - **AI 创建书源**：输入带搜索表单的网站网址和测试书名，从实际页面生成搜索、详情、目录、正文规则，四阶段校验通过后手动保存，未保存前不写书源表。每轮请求记录脱敏的请求、响应预览和字符编码诊断。
  - 流式接口等待模型时每 15 秒发送心跳，避免反向代理 60 秒超时断开。
- **朗读**：朗读引擎支持订阅 HttpTTS JSON 链接（编辑、手动刷新、每天自动更新）；可选“整章连续播放”（服务端用 FFmpeg 合成整章 WAV，默认关闭）。
- **规则订阅**：替换规则、字典规则、TXT 目录规则支持链接导入和订阅。TTS 和规则订阅只有创建者能编辑、刷新、删除；书源订阅仍由管理员管理。
- **书源**：管理员书源分为私有列表和公共列表，编辑、导入、还原只改私有列表，通过“增量同步”或“覆盖同步”发布到公共列表（预览变更数量，带并发锁）；书源列表显示所属订阅（多对多）；分组字段支持多选和新建；可“全选本页”或“全选全部”。
- **发现页**：书源选择旁增加分组筛选。
- **网站账号**：一键清空当前用户的 Cookie，默认显示 5 条可展开，支持按域名搜索。
- **阅读统计**：只统计书架上的书，仅浏览过的书归入“其他”；编辑模式下可隐藏某本书，隐藏后不计入任何统计。
- **同步与备份**：与阅读 App 兼容的 WebDAV 阅读进度同步；多设备实时进度推送；离线进度按时间比较，离线时读得更靠后的进度不会被旧的在线进度覆盖。备份恢复在一个只做数据库读写的事务中完成，TTS/AI 密钥用单独的备份密码加密，书源与订阅的关联按地址写入备份。
- **OPDS**：本地书籍和已缓存的在线书籍提供 OPDS 目录。
- **阅读器与界面**：背景图、排版、亮度和 App 阅读样式导入；应用主题、移动端导航、44px 触控区域、响应式对话框；英文和繁体中文翻译。
- **Docker**：CloakBrowser 许可证和数据持久化到 `./cloak-data`，可改用 Chrome headless；JVM 堆内存溢出时退出由 Compose 自动重启。

### 修复

- 章节加载和换源的竞态：迟到的旧请求不会覆盖新结果，手动与自动换源共用版本号，书架刷新与换源互斥。
- 云端进度响应较慢时不会把阅读位置拉回旧位置；停止朗读或离开页面后不再继续播放。
- JS 替换规则在当前用户的引擎范围内执行，脚本里的 `java` 对象可用，且只能读取当前用户的数据。
- 错误响应对任何 `Accept` 请求头都返回 JSON（修复 ndjson 请求被当成 406）；只有 401 才清除登录，502/503/504 显示“服务器暂时不可用”并保留离线缓存。
- 订阅刷新、WebDAV 待上传记录的并发写入；恢复时同名朗读引擎导致回滚；还原后搜索缓存和规则缓存在事务提交后才清除。
- 书源列表查询条数固定，不随书源数量增长；OPDS 章节数缓存。

## 快速开始

### Docker

```bash
docker compose up -d --build
```

打开 `http://服务器IP:8955`（端口在 `docker-compose.yml` 中修改），第一个注册的账号就是管理员。

也可以直接使用本仓库 GitHub Actions 构建好的镜像 `ghcr.io/dzxx36gyy/legado-web-remix`（amd64 / arm64）。自己 fork 后，Actions 会发布到 `ghcr.io/<用户名>/<仓库名>`，在 `.env` 中设置 `LEGADO_IMAGE` 指向它：

```bash
# 使用构建好的镜像，不在本机构建（端口、数据目录与 docker-compose.yml 相同）
docker compose -f docker-compose.ghcr.yml up -d

# 更新到最新版本
docker compose -f docker-compose.ghcr.yml pull && docker compose -f docker-compose.ghcr.yml up -d
```

不用 Compose 时：

```bash
docker run -d --name legado-web -p 8955:8080 -v ./data:/data -e TZ=Asia/Shanghai -e PUID=1000 -e PGID=1000 ghcr.io/dzxx36gyy/legado-web-remix:latest
```

### 本地开发

需要 JDK 21、Node 22.12+。

```bash
# 后端（开发时用 18080 端口，与前端代理一致；数据在 ./data）
PORT=18080 ./gradlew :server:run

# 前端（5173 端口，/api 自动代理到 18080，可用 LEGADO_API 修改）
cd web && npm install && npm run dev
```

```bash
# 检查缺少的翻译；加 -- --write 自动补上繁体、把缺少英文的条目写到 scripts/i18n-todo.json
cd web && npm run i18n
```

打包成单个 jar（包含前端）：

```bash
./gradlew :server:buildFatJar -PwithWeb
java -jar server/build/libs/legado-web.jar
```

## 配置

`server/src/main/resources/application.yaml`，均可用环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | 8080 | 端口 |
| `LEGADO_DATA_DIR` | ./data | 数据目录（SQLite 数据库、缓存、自动生成的 JWT 密钥） |
| `LEGADO_DB_TYPE` | sqlite | `sqlite` 或 `postgres` |
| `LEGADO_DB_URL` | - | JDBC 地址，如 `jdbc:postgresql://localhost:5432/legado` |
| `LEGADO_DB_USER` / `LEGADO_DB_PASSWORD` | - | PostgreSQL 账号 |
| `LEGADO_JWT_SECRET` | 自动生成 | JWT 密钥（更换后所有用户需重新登录） |
| `LEGADO_ENGINE_SECRET` | 自动生成 | 书源登录信息的加密密钥，保存在 `dataDir/.engine-secret`，迁移数据时请一并保留 |
| `LEGADO_CORS` | - | 允许跨域的前端地址（前后端分开部署时使用） |
| `LEGADO_SEARCH_CACHE_TTL_MINUTES` | 60 | 单书源搜索结果的内存缓存有效分钟数，`0` 禁用；按用户隔离，重启清空，总计最多 5000 条、每用户最多 500 条，按 LRU 淘汰 |
| `LEGADO_CAPTCHA_DISABLED` | false | 设为 `true` 时忽略“系统设置”里的人机验证（Cap 服务不可用、管理员登录不了时临时使用） |

Docker Compose 可在同目录 `.env` 中设置 `LEGADO_SEARCH_CACHE_TTL_MINUTES=60`；直接运行 jar 时通过进程环境变量设置。修改后重启生效。成功的空结果也缓存，失败和超时不缓存；编辑、删除、启停或重新导入书源（含订阅刷新）会清除该书源所有用户的缓存，“重新搜索”绕过读取并保存新结果。

## 代理、网站验证与网站账号

- 代理：管理员在后台“系统设置”的“代理”中维护代理列表（名称 + `http|socks4|socks5://[用户名:密码@]主机:端口`），可选全局代理和不走代理的网站（`主机名` 或 `*.后缀`）。每个书源在编辑页的“代理”中选择“跟随全局”“不使用代理”或列表中的某个代理；普通用户只能选列表里的名称，不能自己填地址。优先级：规则里写的 `proxy`（阅读的 `socks5://host:port@用户名@密码` 格式）> 书源设置 > 全局代理
- 书源请求、`webView` 和远程浏览器使用同一个代理，Cloudflare 等验证通过后的 Cookie（与出口 IP、User-Agent 绑定）才能继续使用。浏览器不支持带账号密码的 SOCKS 代理，此时浏览器相关的请求会直接报错，不会绕过代理
- 网站验证：书源请求遇到 Cloudflare 等的 JS 质询页时，自动用浏览器（需配置“浏览器调试地址”）打开同一网址等待通过，保存 Cookie 和浏览器的 User-Agent 后重试；同一网站同时只解一次。最多等约 45 秒仍未通过时，改为在远程浏览器弹窗中由用户手动完成，失败后一分钟内不再自动尝试
- 网站账号：在“我的 → 网站账号”中输入任意网址，用远程浏览器登录后保存 Cookie，书源请求会带上这些 Cookie；也可查看、删除已保存的 Cookie。Cookie 按用户分开保存。之后请求被重定向到登录页时会提示登录已失效

## 书源兼容性

网页版的限制：

- `webView:true`、`java.webView()`、资源嗅探需要浏览器：服务本身不启动浏览器，在后台“系统设置”的“浏览器调试地址”中填写一个浏览器的调试地址（Chrome DevTools Protocol）。Docker 下按 `docker-compose.yml` 中的 `cloak` 服务另外启动，填 `http://cloak:9222`（改用备选的 `browser` 服务时填 `http://browser:9222`）；CloakBrowser 需要许可证，免费领取地址 https://cloakbrowser.dev/free ，填进 `.env` 的 `CLOAKBROWSER_LICENSE_KEY`，密钥缓存在挂载的 `./cloak-data` 里，重建容器不会丢；本地开发可用 `chrome --remote-debugging-port=9222 --user-data-dir=<临时目录>` 启动后填 `http://127.0.0.1:9222`
- `startBrowser` / `startBrowserAwait` 等打开的网页：默认下载网页在弹窗中显示；系统设置中开启“使用远程浏览器”后，在服务器的浏览器里打开并把画面实时传到弹窗中操作（同一时间只打开一个）
- `java.toast` 在登录、按钮操作、发现页中以提示弹出；`getReadBookConfig` / `getThemeConfig` 返回默认值；`refreshBookInfo` / `refreshBookToc` / `refreshContent` 暂不自动刷新页面
- rar / 7z 解压不支持（zip 支持）；RSS 源暂未实现；在线朗读引擎的登录界面（loginUi）暂不支持；PDF 只显示页面图像，不能选中文字、朗读
- 书源直接调用 Android 类（`Packages.android.*`）的写法无法运行（常用的 `android.util.Base64` 已提供兼容实现）


## 许可证

本仓库 fork 自 [kukume/legado-web](https://github.com/kukume/legado-web)。书源引擎代码移植自 [LegadoTeam/legado](https://github.com/LegadoTeam/legado)（GPL-3.0），本项目同样以 **GPL-3.0** 发布。JS 引擎 `htmlunit-core-js`（MPL-2.0 / Apache-2.0）的许可与源码说明见 `third_party/licenses/htmlunit-core-js/`。
