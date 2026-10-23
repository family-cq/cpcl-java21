# 移植说明（VB.NET / C# → Java 21）

## 一、文件级对照

| 原文件 | 原语言 | Java 实现 | 说明 |
|---|---|---|---|
| `PCLCS/Minecraft/McVersion.cs` | C# | `version/McVersionNames.java`、`version/VersionNumber.java`、`version/McVersion.java` | 版本号三段式换算、Drop 序数、快照/愚人节判定、加载器识别正则 |
| `PCLCS/Java.cs`（`Java`、`JavaUtils`） | C# | `launch/JavaRuntime.java`、`launch/JavaLocator.java` | `-XshowSettings:properties -version` 解析、候选目录搜索、排序 |
| `Modules/Minecraft/ModLaunch.vb` — `McLaunchArgumentsJVM` | VB.NET | `launch/LaunchCommandBuilder.java#buildJvmArgs` | JSON JVM 参数、自定义参数、内存、GC、编码、Log4j |
| `ModLaunch.vb` — `McLaunchArgumentsGame` | VB.NET | `#buildGameArgs` | 新旧参数格式、全屏、自动进服（QuickPlay） |
| `ModLaunch.vb` — `McLaunchArgumentsReplace` | VB.NET | `#placeholders` | 全部 `${...}` 占位符 |
| `ModLaunch.vb` — `SplitJavaArguments` | VB.NET | `#splitArguments` | 双引号与 `\"` 转义处理 |
| `ModLaunch.vb` — `DeduplicateJavaArguments` | VB.NET | `#deduplicate` | 单参数去重、键值对覆盖、`-50` 不当成开关 |
| `ModLaunch.vb` — `McLaunchNatives` / `GetNativesFolder` | VB.NET | `launch/NativeExtractor.java` | 按平台解压 dll/so/dylib、删除残留 |
| `ModLaunch.vb` — `McLaunchRun` / `McLaunchWait` | VB.NET | `launch/GameLauncher.java` | 进程启动、日志重定向、等待退出 |
| `ModLaunch.vb` — `McLoginLegacyUuid` | VB.NET | `auth/OfflineAuthService.java` | `MD5("OfflinePlayer:"+name)` |
| `Modules/Minecraft/ModMinecraft.vb` — `McJsonRuleCheck` | VB.NET | `version/RuleEvaluator.java` | OS/arch/features 规则累积判定 |
| `ModMinecraft.vb` — `McLibListGetWithJson` / `McLibGet` | VB.NET | `version/LibraryResolver.java`、`version/MavenCoordinate.java` | 坐标 → 路径、classifier 挑选、rules 过滤 |
| `ModMinecraft.vb` — `McInstance.Load`（Inherit 展开） | VB.NET | `version/VersionResolver.java` | 递归合并 `inheritsFrom`，子版本优先 |
| `ModMinecraft.vb` — `InitMcInstanceList` | VB.NET | `instance/InstanceScanner.java` | 实例扫描与排序（简化缓存） |
| `Pages/PageInstance/PageInstanceMod.xaml.vb` | VB.NET | `instance/ModScanner.java` | Mod 元数据读取、`.disabled` 启停 |
| `Pages/PageInstance/PageInstanceSetup.xaml.vb` — `GetRam` | VB.NET | `launch/MemoryAllocator.java` | 四阶段自动内存分配 |
| `ModDownload.vb` — `DlSource*Get` | VB.NET | `download/DownloadSources.java` | BMCLAPI 镜像替换规则 |
| `ModNet.vb`（下载调度部分） | VB.NET | `download/DownloadManager.java` | 并发、重试、SHA1 校验 |
| `ModDownload.vb` — 版本清单相关 | VB.NET | `download/VersionManifestService.java` | 清单缓存、版本安装 |
| `Modules/ModMain.vb`、`ModBase.vb`（设置 / 路径） | VB.NET | `config/PclProperties.java` | 路径与设置项映射 |

## 二、未移植部分及原因

| 原模块 | 原因 |
|---|---|
| `Controls/`、`Pages/*.xaml`、`FormMain`、`ModAnimation` | WPF 界面与自绘控件，依赖 XAML/依赖属性，Java 侧改为 Web 控制台 |
| `McLoginMs*`（微软 OAuth）、`McLoginAuth*`（Authlib-Injector） | 需要外网 OAuth/第三方验证服务，移植版仅保留离线登录 |
| `Launch/JavaWrapper.jar`、`LwjglUnsafeAgent.jar` | 原工程随包发布的二进制补丁，缺少源码与 jar 无法复现 |
| `ModDownload` 的 Forge/Fabric/OptiFine 安装器 | 涉及大量安装器交互（运行 installer jar、解析日志），代码量接近整个移植工程 |
| `ModNet` 的分片续传、下载源自动禁用、限速 | 属于传输层优化，当前实现保留并发 + 重试 + 校验 |
| `ModCrash`（崩溃分析）、`ModMusic`、皮肤、帮助文档 | 与本工程目标（核心启动链路）无关 |
| `PCLCS/Resource/*`（CurseForge/Modrinth 搜索） | 依赖第三方 API 的完整资源模型 |

## 三、行为差异（有意为之）

1. **参数不再手动加引号。** 原实现拼一条命令行字符串，所以对含空格的参数补双引号；
   Java 侧直接把参数列表交给 `ProcessBuilder`，加了引号反而会把引号带进参数值。
   仅在接口返回的 `display` 字段中为展示目的补引号。
2. **`${version_type}` 直接使用启动器名。** 原实现会在自定义信息为空时把 `--versionType` 整个删掉；
   本版保留该参数并填入启动器名，避免部分整合包因缺少 `--versionType` 参数值报错。
3. **Fabric 版本号回显带分隔符。** 原实现 `Replace("+build", "")` 会把 `0.15.11+build.3` 变成 `0.15.113`，
   本实现保留为 `0.15.11.3`。
4. **规则判定支持多平台。** 原实现只识别 `windows`（非 Windows 一律不匹配）；
   本实现对 `windows` / `linux` / `osx` 做同名比较，Windows 上的结果与原实现完全一致。
5. **空 `rules` 数组返回 false**（与原实现一致，未做「空即放行」的修正，以保证行为对齐）。
6. **内存算法取「可用物理内存」。** .NET 的 `AvailablePhysicalMemory` 在 Java 侧用
   `OperatingSystemMXBean#getFreeMemorySize()` 近似，非 HotSpot 实现时退化为固定 4 GB。
7. **实例隔离默认开启**（游戏目录 = `versions/<id>`），与原 PCL 新版默认一致，可通过请求体 `isolate` 关闭。

## 四、编译与验证

```
mvn -s H:\idea\.m2\repository\settings.xml -Dmaven.repo.local=H:\idea\.m2\repository clean package
```

测试覆盖（23 个用例）：

- `McVersionNamesTest` —— 1.x 旧格式、26.x 新格式、愚人节版本映射、Drop 换算
- `RuleEvaluatorTest` —— OS 版本正则、allow/disallow 累积、x86/arm 排除、features
- `VersionResolverTest` —— 继承合并、参数拼接顺序、支持库去重、父版本缺失容错
- `LaunchCommandBuilderTest` —— 占位符全部替换、`-Xmx`、GC、主类位置、参数去重、引号切分
- `OfflineAuthServiceTest` —— 离线 UUID 与 Minecraft 服务端算法一致
