# PCL-Java21

[Plain Craft Launcher](https://github.com/Meloong-Git/PCL)（PCL，VB.NET + WPF 桌面启动器）核心逻辑的 **Java 21 / Spring Boot** 移植版，配套 **Vue 3 平台模式前端**（顶部状态栏 + 左侧标签 + 右侧页面）。

原工程里约 1/3 的代码是 WPF 界面（`Controls/`、`Pages/`、`XAML`），这部分在 Java 侧重写为四个页面：**启动 / 下载 / 设置 / 更多**。

## 环境

| 项 | 要求 |
|---|---|
| JDK | 21 及以上（开发验证版本 21.0.6 LTS） |
| Maven | 3.9+ |
| Node | 18+，仅在前端需要重新构建时使用 |
| 系统 | Windows 10 / 11 x64（启动、独显、进程优先级等依赖 Win32 API） |

## 构建与运行

```bash
# 打包（测试可选，加 -DskipTests 跳过）
mvn clean package

# 运行
java -Dpcl.data-dir=./data -jar target/pcl-java21.jar
```

启动后访问 <http://localhost:18080/>。Windows 下也可以直接双击 `启动器.bat`，它会自动找 Java 21、检测端口占用、就绪后打开浏览器。

前端已构建产物（`src/main/resources/static/`）随源码一起提交，只改后端时无需 Node。若需改前端，用你自己的 Vue 3 工程重新 `npm run build`，把 `dist/` 覆盖到 `src/main/resources/static/` 即可。

**文件位置约定**：设置、缓存、日志与游戏文件都放在「程序所在目录」旁（`data/`、`.minecraft/`），不写 C 盘。可用 `--pcl.data-dir` / `--pcl.minecraft-dir` 覆盖。

## 界面（Vue 3 平台模式）

| 页面 | 内容 |
|---|---|
| 启动 | 正版（设备码登录）/ 离线单选；玩家名；启动游戏按钮 + 当前版本卡片；「版本选择」页列出已下载的整合包与游戏 |
| 下载 | 原版游戏 → 最新版本 / 正式版 / 预览版 / 远古版本 / 愚人节版本 → 版本号 + 发行日期 → 可选扩展 Forge / NeoForge / Fabric / OptiFine → 开始下载（后台任务 + 进度日志） |
| 设置 | 启动选项（默认隔离版本 5 档、进程优先级）、内存分配（自动 / 自定义进度条 0.3G~物理内存）、高级选项（JVM 参数、游戏参数、启动前命令、Java 列表、内存管理 5 档、三个多选） |
| 更多 | 帮助（MC 指南 11 篇）、关于与鸣谢、百宝箱（今日人品 / 内存优化 / 清理垃圾） |

前端支持 `#/launch`、`#/download`、`#/settings`、`#/more` 锚点直达。

## 正版登录说明

微软已停用账号密码直登，后端实现的是官方 **OAuth 设备码流程**（Xbox Live → XSTS → Minecraft Services）。需要配置自己的 Azure 应用 ID：

```bash
set PCL_MS_CLIENT_ID=你的客户端ID
```

未配置时界面会给出明确提示，离线登录不受影响。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/PUT | `/api/settings` | 设置读写（隔离、内存、JVM/GC、Java、账户），落盘 `data/settings.json` |
| GET | `/api/instances` | 已安装实例列表（MC 版本、加载器、Mod 数） |
| POST | `/api/instances/{name}/command` | 只生成启动命令，不启动 |
| POST | `/api/instances/{name}/launch` | 启动游戏，返回 PID 与日志路径 |
| GET/POST | `/api/java`、`/api/java/refresh` | Java 扫描 |
| GET | `/api/minecraft/versions?category=` | 版本清单（latest/release/snapshot/ancient/fool） |
| POST | `/api/install/version` | 后台安装原版 `{id, assets}` |
| POST | `/api/install/loader` | 后台安装加载器 `{mcVersion, loader, version}` |
| GET | `/api/install/loaders?mc=` | 查询某版本可用的加载器 |
| GET | `/api/install/jobs/{id}` | 安装任务进度与日志 |
| POST | `/api/auth/device` / `poll` / `offline` / `logout` | 微软设备码登录、离线切换 |
| GET | `/api/toolbox/luck` | 今日人品（同一天同一名字固定） |
| POST | `/api/toolbox/memory` | 内存优化（EmptyWorkingSet） |
| GET/POST | `/api/toolbox/garbage[/clean]` | 日志与崩溃报告扫描 / 清理 |

## 模块划分

```
com.meloong.pcl
├── version   版本 JSON 解析、规则判定、inheritsFrom 合并、支持库解析
├── launch    Java 检测、启动参数构建、natives 解压、进程启动、优先级/独显
├── download  镜像源、并发下载、版本清单、加载器安装（Fabric/Forge/NeoForge/OptiFine）、后台任务
├── instance  实例扫描、Mod 扫描与启停
├── settings  设置持久化与生效（隔离策略、内存、GC、账户）
├── auth      离线 UUID、微软设备码登录
├── tool      百宝箱（人品 / 内存优化 / 清理垃圾）
├── web       REST 控制器
└── config    配置与 Jackson/HTTP 装配
```

## 常见问题

**点了「启动游戏」，有 Java 进程但没有游戏窗口**

看版本目录下的 `PCL-Java-*.log`。Forge / NeoForge 1.17+ 启动要过 FML 的模块系统，类路径里只要多一个
同名模块就会直接崩，且窗口还没创建就退出，表现为「有进程、没界面」。典型两种：

| 日志关键字 | 原因 | 现在怎么处理 |
|---|---|---|
| `Module named org.objectweb.asm was already on the JVMs module path ... but class-path contains it at asm-9.3.jar` | 原版 JSON 带 asm 9.3，NeoForge 用 asm 9.7，两个版本都进了 `-cp` | 合并继承链时按 `group:artifact[:classifier]` 去重，子版本覆盖父版本，只保留 9.7（在 `-DignoreList` 里，会被 FML 剔除） |
| `Modules minecraft and _1._21._2 export package ... to module mixin_synthetic` | 原版 `1.21.2.jar` 与安装器生成的补丁客户端 `client-*-srg.jar` 同时成为 `minecraft` 模块 | 主类是 `BootstrapLauncher` 时不再把原版 jar 放进 `-cp`，客户端由 FML 通过 `-DlibraryDirectory` 装入 |

**进游戏后贴图、字体、声音缺失**

资源文件（assets/objects）没下载。下载页现已在安装原版时一并下载资源；老实例在启动时会后台按版本
资源索引补全（不阻塞启动，补完下次启动即完整），也可以重新在下载页装一次该版本。

**安装 Forge / NeoForge 时报「安装器退出码 1」**

安装器自己只会去 `maven.neoforged.net` / `maven.minecraftforge.net` 拉依赖，这两个域名在国内经常直接
`Connection refused`，而它的 `--mirror` 参数只影响 Mojang 元数据，改不了 maven 地址。

现在的处理方式：安装前先读安装器里的 `install_profile.json`，把清单中每个 artifact 按 BMCLAPI 镜像
下载并校验到 `libraries/`，再让安装器以 `--offline` 只做本地组装（解包、打补丁）。失败会退回联网模式，
两种日志都在 `logs/installer-*.log`。老版本 Forge（1.7.10 / 1.12.2 一批）清单是 v0 格式、没有离线参数，
同样会预下载，安装器检测到文件已存在就不再联网。

## 测试

```bash
mvn test
```

38 个用例覆盖：版本号换算、规则判定、继承合并、参数去重与占位符替换、离线 UUID、整合包并行安装、CurseForge 免 Key 解析。

## 仓库说明

`.minecraft/`、`data/`、`cache/`、`logs/`、`dist/`、`target/`、`cpcl/` 均不入库：

- `.minecraft/` 是游戏本体与存档，体积可达数 GB
- `data/settings.json` 含本机玩家名、内存设置等个人配置
- `cpcl/`、`dist/` 是打包产物（瘦 jar + 运行库 + 发布 zip）

## 许可

原项目 PCL 的源代码版权归 Meloong-Git / LTCat 所有，使用前请阅读其
[LICENCE](https://github.com/Meloong-Git/PCL/blob/main/LICENCE)。
本移植工程仅用于学习与内部研究。
