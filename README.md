# PackSync

**自托管整合包自动同步模组** ——
服务端发布整合包，客户端在**进入游戏之前**自动把本地内容同步一致。

- 适配：Minecraft **1.20.1** / Forge **47.x**
- 当前版本：**1.0.1**
- 作者：**蓝韵诗笺**
- 许可：MIT

---

## 下载

**https://github.com/lanyunshijian/PackSync/releases/latest**

下载 `packsync-x.y.z.zip` 并解压，里面就是 `packsync-1.0.1.jar`（客户端与服务端通用），
丢进 `mods/` 目录即可。

也可以直接用 Maven 坐标引入：

```
com.dsh.packsync:packsync:1.0.1
```

包仓库地址：`https://maven.pkg.github.com/lanyunshijian/PackSync`

---

## 它解决什么问题

原版联机要求玩家手动装好整合包，版本一错就连不上、或者进去后一堆东西对不上。
PackSync 把这个过程搬到**登录握手阶段**：客户端还没进世界，就已经把该下的下完、
该删的删掉，然后提示重启，一次到位。

与常见的同类方案相比：

| | 常见做法 | PackSync |
|---|---|---|
| 同步时机 | 登录期 / 启动期 | 登录期 + 启动期（可独立开关） |
| 分发方式 | HTTP 直传服务端整合包 | **服务端直传** / **公网镜像**（Modrinth·CurseForge）/ **双源回退** 四种策略可选 |
| 指纹校验 | 有 | 有，且**必须人工输入并验证通过**才允许下载 |
| 信任模型 | 可自动信任新服务器 | **无自动信任**，一律人工核对 |
| 语言 | 英文 | 中文界面 |

---

## 已实现的功能

### 服务端
- 按 glob 规则扫描 `mods/`、`config/`、`resourcepacks/` 等目录，生成带 SHA-256 的**清单**
- 清单**增量重建**（只对变化的文件重新哈希），并在服务器启动完成后自动发布
- 自动生成 Ed25519 身份密钥到 **`<服务器根目录>/modpack-keys/`**
  （`server-identity.key` + `fingerprint.txt`）
- 登录期握手：在客户端进世界前完成身份、版本、清单三方核对
- **内建同端口分流**：MC 端口上收到 `/packsync/` 开头的 HTTP 请求会自动转给分发服务，
  因此防火墙可以只放行游戏端口（见 [端口](#端口)）
- 管理命令，别名 `/pks`，需 OP：
  | 命令 | 作用 |
  |---|---|
  | `/packsync generate` | 重新生成整合包清单 |
  | `/packsync host start\|stop\|restart\|status` | 分发服务的启停与状态 |
  | `/packsync host fingerprint` | 查看本服身份指纹 |
  | `/packsync host keys` | 查看密钥信息 |
  | `/packsync config reload` | 重读配置文件 |
- **无论客户端装没装本模组、清单对不对，连接都不会被本模组阻断**

### 客户端
- 进服前的 **11 个界面**：状态 → 设置 → 同步设置 → 指纹输入 → 危险确认 → 下载进度 →
  变更日志 → 取件 → 重启提示 → 出错 → 跳过验证确认
- **四种下载模式**，游戏内可随时切换：
  1. `SERVER_ONLY` —— 只从服务端下载，完全不查询公共站
  2. `PUBLIC_ONLY` —— 只从 Modrinth / CurseForge 下载，匹配不到即失败
  3. `PUBLIC_FIRST`（默认）—— 优先公网，失败回退服务端
  4. `SERVER_FIRST` —— 优先服务端，失败回退公网
- 指纹校验为**强制人工输入**，服务端指纹不会显示给客户端；验证不过就不下载
- 下载可取消、有逐文件进度与实时速率
- 客户端侧 `/packsync verify`：不需要 OP 就能核对本地文件与服务端清单
- 主菜单右下角 **PackSync** 按钮、可自定义快捷键（默认右 Shift）
- 本地记录每台服务器已同步清单，避免重复下载

### 传输与安全
- 传输层：X25519 密钥交换 + Ed25519 身份签名 + HKDF 派生 + **AES-256-GCM** 分帧
- 计数器严格递增，防重放；用途隔离（不同用途派生不同子密钥）
- 全部基于 JDK 内置密码学实现，**不引入任何第三方加密依赖**
- 公网下载走自实现的 IPv4 强制 HTTP 客户端，绕开
  `-Djava.net.preferIPv6Addresses=system` 导致的 IPv6 不通问题
- 地址解析正确处理 IPv6（含裸 IPv6 + 端口、方括号写法），拼 URL 时自动加方括号

---

## 端口

**端口由服务端决定，客户端不配置端口。**

分发服务监听一个**独立端口**，与游戏端口必然不同 —— MC 自己占着游戏端口，
分发服务不可能再绑同一个口。默认取 `MC 端口 + 1`（MC 在 25565 → 同步在 25566）。

**想把端口固定下来（推荐）**，把两个字段都写死，此后不再有任何推算：

```json
"bindPort":      25566,     // 分发服务监听哪个端口
"portToSend":    25566,     // 告诉客户端去连哪个端口
"reconcilePort": 0
```

防火墙放行 `25565`（游戏）与 `25566`（同步）即可，规则写一次就不用再动。

**想再少开一个口**：把 `portToSend` 设为 `25565`。分发服务内建同端口分流，
MC 端口上收到 `/packsync/` 开头的 HTTP 请求会自动转给它（GET/POST 均已实测），
于是防火墙只需放行游戏端口。

客户端侧只有一种情况会"自己算"：服务端没能下发端口（托管未运行、还没有清单），
那时按默认部署回落成 `MC 端口 + 1`。这只是兜底，不是又一个可配项。

> 完整说明见 [使用说明.md 6.1 节](使用说明.md)。

---

## 构建

需要 **JDK 17**。Gradle 通过 wrapper 自带，无需预装。

```bash
./gradlew build
```

产物：`build/libs/packsync-1.0.1.jar`。

跑单元测试（核心层零 MC 依赖，不需要启动游戏）：

```bash
./gradlew test
```

> 内存较小的机器请勿并行执行多个 Gradle 构建；工程已在
> `gradle.properties` 中关闭 daemon 并限制堆为 2G。

---

## 使用

### 服务端

1. 把 `packsync-1.0.1.jar` 丢进服务端的 `mods/` 目录。
2. 启动一次服务器 —— 会生成 `config/packsync-server.json`，
   并在服务端根目录建出 `modpack-keys/`。
3. 按需编辑 `config/packsync-server.json`（同步哪些目录、端口、下载提示语等），
   然后执行 `/packsync config reload`，或直接重启。
4. 把 **`modpack-keys/fingerprint.txt` 第一行的指纹**通过可信渠道
   （群公告、群文件等）发给玩家。

### 客户端

1. 把 `packsync-1.0.1.jar` 丢进客户端的 `mods/` 目录。
2. 连接服务器。若本地没有该服务器的可信指纹记录，会**强制弹出指纹输入界面**。
3. 从服主那里拿到指纹，粘贴进去（支持整段粘贴，会自动提取）→ 验证通过后才开始下载。
4. 下载完成后按提示重启游戏，即与服务端一致。

指纹一致性也通过 `/packsync verify` 随时复核。

---

## 1.0.1 修了什么

- **IPv6 联机时同步必然失败** —— 地址解析把端口并进了主机名
  （`2409:...:88e8:25565`），且拼 URL 时 IPv6 没加方括号，最终抛
  `MalformedURLException`。用 IPv6 连服的玩家此前 **100% 无法同步**。
- 老配置里被记坏的主机名会**自动清洗**，无需手工改。
- 服务端 `portToSend`（下发给客户端的对外端口）此前是死配置 —— 声明了却从未被读取，
  自本版起真正生效。

---

## 文档

- [使用说明.md](使用说明.md) —— 面向使用者的完整说明
- [docs/启动期架构验证.md](docs/启动期架构验证.md) —— 双模组加载架构为何必须两层
- [docs/同端口分流与命令-验证.md](docs/同端口分流与命令-验证.md) —— 同端口 HTTP 分流验证
- [docs/端到端验证证据.md](docs/端到端验证证据.md) —— 端到端测试证据

---

## 目录结构

```
src/main/java   locator 与平台无关核心（配置 / 清单 / 加密 / 传输），零 MC 依赖
src/main/resources  IModLocator 服务注册、外层 pack.mcmeta
src/mod/java    游戏内部分（事件、命令、界面、mixin），独立模块 packsync_mod
src/mod/resources   内层 mods.toml、mixin 配置
src/test/java   核心层单元测试
gradle/         Gradle wrapper
```

> 为什么要拆两层：Forge 不允许同一个 jar 同时充当「locator 提供者模块」和
> 「游戏内 mod 模块」，实测会抛
> `java.lang.module.ResolutionException: Module forge reads more than one module named packsync`。
> 因此外层只放 locator 与核心，游戏内部分单独装箱为 `packsync_mod`，
> 再由 locator 以 jar-in-jar 方式装载。

---

## 许可

MIT，见 [LICENSE](LICENSE)。
