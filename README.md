# 阅读 Web（legado-web）

把 [阅读（LegadoTeam/legado）](https://github.com/LegadoTeam/legado) 的书源引擎搬到服务端，用网页阅读：

- **后端**：Kotlin + Ktor 3 + Exposed 1.x，数据库支持 **SQLite / PostgreSQL**
- **前端**：Vue 3 + Vite + TypeScript + Pinia + Naive UI
- **书源引擎**：移植自 LegadoTeam/legado（引擎修复同步到`531f9fbf`），兼容阅读 3.0 书源 JSON 与纯 JS 书源
- **本地书籍**：上传 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF
- **规则**：替换规则、字典规则、TXT 目录规则
- **备份与恢复**：支持与阅读 App 的备份 zip 互通，可恢复 App 备份中的书架（含进度、分组、书签）、书源、替换规则、字典规则、TXT 目录规则、书源订阅，导出的 zip 也能在 App 中恢复；支持 WebDAV。

> 本项目不内置任何书源，只提供通用的规则解析工具。请遵守所在地法律，仅用于合法获取的内容。

## 快速开始

### AI 书源

在“我的”中添加 OpenAI 兼容模型，仅创建者能使用，密钥加密保存在服务端且不返回前端。书源校验的失败条目可以启动 AI 修复；模型新增的 JavaScript 需要先确认执行，再重新校验并确认保存。验证码会终止修复。

书源管理的“AI 创建书源”接受带搜索表单的网站网址和测试书名，从实际页面生成搜索、详情、目录、正文规则，四阶段校验通过后手动保存。生成 JavaScript 时暂停，用户确认执行后才继续校验。每次校验提供脱敏的请求、响应预览、声明字符编码和解析结果计数。模型配置不进入 App 兼容备份，迁移需保留数据目录及引擎密钥或重新配置模型。

### Docker

```bash
docker compose up -d --build
```

打开 `http://服务器IP:8955`（端口在 `docker-compose.yml` 中修改），第一个注册的账号就是管理员。

也可以直接使用 GitHub Actions 构建好的镜像：

```bash
# 使用构建好的镜像，不在本机构建（端口、数据目录与 docker-compose.yml 相同）
docker compose -f docker-compose.ghcr.yml up -d

# 更新到最新版本
docker compose -f docker-compose.ghcr.yml pull && docker compose -f docker-compose.ghcr.yml up -d
```

不用 Compose 时：

```bash
docker run -d --name legado-web -p 8955:8080 -v ./data:/data -e TZ=Asia/Shanghai -e PUID=1000 -e PGID=1000 ghcr.io/kukume/legado-web:latest
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

- `webView:true`、`java.webView()`、资源嗅探需要浏览器：服务本身不启动浏览器，在后台“系统设置”的“浏览器调试地址”中填写一个浏览器的调试地址（Chrome DevTools Protocol）。Docker 下按 `docker-compose.yml` 中的 `browser` 服务另外启动，填 `http://cloak:9222`；CloakBrowser 需要许可证，免费领取地址 https://cloakbrowser.dev/free ，填进 `.env` 的 `CLOAKBROWSER_LICENSE_KEY`，密钥缓存在挂载的 `./cloak-data` 里，重建容器不会丢；本地开发可用 `chrome --remote-debugging-port=9222 --user-data-dir=<临时目录>` 启动后填 `http://127.0.0.1:9222`
- `startBrowser` / `startBrowserAwait` 等打开的网页：默认下载网页在弹窗中显示；系统设置中开启“使用远程浏览器”后，在服务器的浏览器里打开并把画面实时传到弹窗中操作（同一时间只打开一个）
- `java.toast` 在登录、按钮操作、发现页中以提示弹出；`getReadBookConfig` / `getThemeConfig` 返回默认值；`refreshBookInfo` / `refreshBookToc` / `refreshContent` 暂不自动刷新页面
- rar / 7z 解压不支持（zip 支持）；RSS 源暂未实现；在线朗读引擎的登录界面（loginUi）暂不支持；PDF 只显示页面图像，不能选中文字、朗读
- 书源直接调用 Android 类（`Packages.android.*`）的写法无法运行（常用的 `android.util.Base64` 已提供兼容实现）


## 许可证

书源引擎代码移植自 [LegadoTeam/legado](https://github.com/LegadoTeam/legado)（GPL-3.0），本项目同样以 **GPL-3.0** 发布。JS 引擎 `htmlunit-core-js`（MPL-2.0 / Apache-2.0）的许可与源码说明见 `third_party/licenses/htmlunit-core-js/`。
