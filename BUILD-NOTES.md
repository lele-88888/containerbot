# ContainerBot 构建与实现说明

> 最后更新：v1.23.2

## 目标环境

| 项 | 值 |
|---|---|
| Minecraft | 1.21.8（Fabric，Mojang 官方映射） |
| 实例目录 | `D:\.minecraft\versions\1.21.8` |
| 必需前置 | Fabric API 0.131.0+1.21.8、Carpet 1.21.7-1.4.177（v1.4.177+v250630） |
| 构建工具 | JDK 25 + Gradle 9.7.0 + Loom 1.18（`net.fabricmc.fabric-loom-remap`） |
| 产物 | `releases\containerbot-<版本>.jar` |

---

## 怎么构建

双击 [build-mod.bat](build-mod.bat)，或在项目目录执行：

```powershell
$env:JAVA_HOME  = "$PWD\tools\jdk-dist\jdk-25.0.4.1+1"
$env:GRADLE_USER_HOME = "$PWD\.gradle-home"
& "$PWD\tools\gradle-dist\gradle-9.7.0\bin\gradle.bat" -p "$PWD" build --console=plain --no-daemon
```

构建结束会自动执行 `archiveRelease`，把 jar 复制进 `releases\`。

**注意**：Loom 1.18 **要求 JDK 25 才能运行**（产物仍是 Java 21 字节码）。

---

## ⚠️ 本机环境级缺陷（务必保留修复，勿删）

### 现象

`java.nio.file.Files.isWritable()` 在这台机器上**对任何路径都返回 `false`**，而实际写入完全正常：

```
Files.isWritable = false        <- 说谎
File.canWrite    = true
DOS readOnly     = false
普通文件写入      -> 成功
ZipOutputStream  -> 成功
zipfs 写入        -> ReadOnlyFileSystemException   <- 只有它受害
```

### 根因

JDK 在 Windows 上用 `GetEffectiveRightsFromAcl` 计算有效权限，而该 Win32 函数**不考虑**登录会话关联的组权限（如 Authenticated Users）与调用者特权。JDK 又把异常吞掉了：

```java
// sun.nio.fs.AbstractFileSystemProvider
public boolean isWritable(Path path) {
    try { checkAccess(path, AccessMode.WRITE); }
    catch (IOException e) { return false; }     // <- 异常被吞
    return true;
}
```

### 后果

```java
// jdk.nio.zipfs.ZipFileSystem 构造器
this.readOnly = ro || releaseVersion.isPresent() || !Files.isWritable(zfpath);
```

于是**每个 zip/jar 都被强制只读**。受害的不止 Loom：

| 组件 | 症状 |
|---|---|
| `jdk.nio.zipfs.ZipFileSystem` | 写 zip/jar 抛 `ReadOnlyFileSystemException`（Loom 写 mappings 崩在这） |
| `tinyremapper.OutputConsumerPath` | 「the jar file ... can't be written」（Fabric Loader 重映射游戏 jar 崩在这） |

### 已排除的因素（都实测过）

- **沙盒**：普通 shell 同样复现
- **JDK 版本**：JDK 21 与 JDK 25 表现一致
- **完整性级别**：提权到 Medium 无效
- **文件 ACL**：移除 `Everyone:(DENY)` 后依旧
- **路径位置**：工作区、TEMP 均无效
- **重解析点**：逐级检查，无 junction

### 修复方式：`--patch-module`（**已取代旧的 Java agent 方案**）

修复源码在 [tools/patch-module/src/sun/nio/fs/AbstractFileSystemProvider.java](tools/patch-module/src/sun/nio/fs/AbstractFileSystemProvider.java)：
把 `isWritable` 改为用 `java.io.File.canWrite()` 判断（只查 DOS 只读属性，本机准确）。

编译后通过 [gradle.properties](gradle.properties) 挂载：

```properties
org.gradle.jvmargs=... --patch-module java.base=<绝对路径>/tools/patch-module/classes
```

启动 Fabric 服务端同理：

```powershell
java -Xmx2G --patch-module "java.base=<绝对路径>/tools/patch-module/classes" -jar fabric-server-...jar nogui
```

### ❌ 为什么不用 Java agent（旧方案已废弃）

早期做过 `tools/zipfs-fix/zipfs-fix-agent.jar`（ASM 改写字节码），**已放弃**，两个原因：

1. **Fabric Loader 会校验 classpath**：一旦发现重复的 ASM 类就拒绝启动
   （`IllegalStateException: duplicate ASM classes found on classpath`）
2. agent 若改用 JDK 内置 ASM，则需要 `--add-exports java.base/jdk.internal.org.objectweb.asm`，
   不加就静默失效——很不稳

`--patch-module` 不是 classpath 条目，不受上述限制，且无需任何第三方库。

### 内存注意

`org.gradle.jvmargs` 特意压到 `-Xmx1280m`：本机页面文件偏小，同时开测试服务端(2G) + Gradle 守护进程会触发
`os::commit_memory failed ... 页面文件太小` 而无法创建 JVM。

---

## 游戏内用法（完整命令表）

需要 OP 或开启作弊。物品名可用 `minecraft:diamond` 或简写 `diamond`，Tab 可补全。

### 任务

| 命令 | 作用 |
|---|---|
| `/cbot spawn <名字>` | 生成假人（在你站的位置） |
| `/cbot start <名字> <物品> <数量> [半径] [潜影盒阈值]` | **取货**：只拿指定物品 |
| `/cbot move <名字> [半径]` | **搬家**：掏空区域所有容器 |
| `/cbot status` | 查看进度 |
| `/cbot stop <名字>` | 停止（假人留下） |
| `/cbot despawn <名字>` | 移除假人（物品掉地上） |
| `/cbot clear [名字]` | 清空任务 |
| `/cbot pause [名字]` / `resume [名字]` | 暂停 / 继续（进度保留） |

### 目的地

| 命令 | 作用 |
|---|---|
| `/cbot deposit` | 准星对着箱子 → 设为目的地（倒入容器） |
| `/cbot deposit drop` | 准星对着任意方块 → **扔在地上** |
| `/cbot deposit box` | 切回「倒入容器」 |
| `/cbot deposit <x> <y> <z> [维度]` | 手输坐标，**自动判定**容器/地面 |
| `/cbot deposit clear` | 取消 |

### 区域

| 命令 | 作用 |
|---|---|
| `/cbot pos1` / `pos2` | 站一角/对角 → 设选区（自动记录你所在维度） |
| `/cbot pos1 <x> <y> <z> [维度]` | **手输坐标设角点**，控制台/命令方块也能用 |
| `/cbot pos2 <x> <y> <z> [维度]` | 同上 |
| `/cbot region` | 查看选区（含维度）+ 数其中容器数 |
| `/cbot region clear` | 清除选区 |

**两个角点必须同一维度**，混用会被明确拒绝。

**选区带维度**：如果选区设在别的维度，搬家/取货时假人会**自动传送过去**再开始扫描。
（否则它会去扫"当前维度里同坐标"的地方，一个箱子都找不到。）

### 调速与诊断

| 命令 | 作用 |
|---|---|
| `/cbot pace <tick>` | 节流：每处理完一个容器暂停 N tick（20 tick = 1 秒） |
| `/cbot rate <件/小时>` | 限速：限制实际搬运吞吐量 |
| `/cbot debug on\|off\|dump` | 诊断开关 / 导出最近 200 条记录 |

**pace 与 rate 的区别**：`pace` 限「动作频率」（容器之间固定停顿），`rate` 限「实际搬运量」
（不管容器里装多少，长期平均速率不超过设定值）。两者可叠加。

---

## 实现要点

| 环节 | 做法 |
|---|---|
| 生成假人 | 派发 Carpet 的 `/player <名字> spawn`，**不依赖 Carpet 编译期 API** |
| 驱动移动 | 反射 Carpet `EntityPlayerActionPack`（`setForward`/`setStrafing`/`look`/`lookAt`/`stopMovement`）；解析不到时降级为直接写原版 `zza`/`xxa` |
| 寻路 | **自写 A\***（8 方向 + 上台阶 + 下落，代价加权）；不能用 `WalkNodeEvaluator`——它强制要求 `Mob`，而假人是 `ServerPlayer` |
| 走路 | 设置 `yRot` + 通过动作包写前进输入，走真实物理而非瞬移 |
| 卡住兜底 | 60 tick 无位移 → 传送到容器旁 |
| 开箱（取货） | `ServerPlayerGameMode.useItemOn(...)`，与真人右键**完全同一条代码路径** |
| 开箱（投送） | 直接 `Player.openMenu(provider)`，见下方坑 #7 |
| 取货 | `AbstractContainerMenu.clicked(i, 0, ClickType.QUICK_MOVE, bot)`，即真人 Shift+左键 |
| 槽位过滤 | 只对 `slot.container == 箱子` 操作，**绝不能碰玩家背包侧**，否则会把背包东西塞进箱子 |
| 潜影盒 | 读 `DataComponents.CONTAINER`（1.20.5+ 的数据组件，不再是 NBT） |
| 跨维度 | 借用原版 `/execute in <维度> run tp`，不手写传送（1.21.8 无 `ServerPlayer.teleportTo(ServerLevel,...)` 签名） |

---

## 踩过的坑（都是实测出来的，勿重犯）

### 1. 自定义 Brigadier 参数类型会**把玩家踢下线**

v1.2.0 为支持 `minecraft:diamond` 里的冒号写了个自定义 `ArgumentType`。服务端能解析，但
Brigadier 把命令树同步给客户端时要序列化参数类型，而原生 `ArgumentTypes` 只认自己注册过的类型：

```
IllegalArgumentException: Unrecognized argument type com.containerbot.command.IdentifierArgument
→ 玩家进世界直接掉线（显示「无效的玩家数据」）
```

**结论**：要么用原生类型（`ResourceLocationArgument`），要么必须用 Fabric 的 `ArgumentTypeRegistry` 正确注册。

### 2. 箱子碰撞箱只有 14/16 高

站上去时脚在 `y = 64.875`，`blockPosition()` 恰好**等于箱子自身坐标**（不是 `above()`）。
导致 A\* 起点=终点且起点不合法 → `findPath` 返回 null → 误报「走不到」。

**结论**：判断「是否站在容器上」必须同时比较 `container` 和 `container.above()`。

### 3. Carpet 的 `setForward` 是**持续状态**

`stopMovement()` 只停「动作列表」，不停前进输入。任务完成后假人会沿原方向一直走。

**结论**：每 tick 只要不在行走就强制 `setForward(0)` + 清 `zza`/`xxa`。

### 4. 限速的计时不能放在等待分支之后

等待分支会 `return`，若 `taskTicks++` 在其后，等待期间计时不增长 →
预算 `速率×已用时间` 永远涨不上去 → 死循环（实测 64 件卡死 70 秒）。

**结论**：计时必须在等待分支**之前**累加（仅用户主动暂停时不计时）。

### 5. 进度只算「背包当前量」会因卸货而倒退

**结论**：进度 = 已交付 + 携带；且卸货箱必须从「待搬容器」中排除，否则会把刚送去的货搬回来。

### 6. `Inventory.getContainerSize()` 含护甲/副手槽

返回 41（36 主槽 + 4 护甲 + 1 副手），护甲副手永远是空的 → **背包满判定永不成立**。

**结论**：判满只看 0..35 这 36 个主槽位。

### 7. 跨维度传送后原版右键会被判成「物品交互」

诊断实测 `useItemOn` 返回 `wasItemInteraction=true` 但菜单不打开，连续 7 次重试全败。

**结论**：投送（目的地容器）改用 `openMenu` 直连；**取货**仍走真人右键路径（已验证可靠）。

### 8. PowerShell 的坑

- **传参吞双引号**：向原生程序传含 `"` 的参数会被吃掉 → RCON 命令变形。
  解决：给 [tools/Rcon.java](tools/Rcon.java) 加 `--file` 模式，从 UTF-8 文件逐行读命令。
- **`Set-Content -Encoding UTF8` 会写 BOM**：读 UTF-8 文件时按 GBK 解码 → 中文变乱码、吞换行、构建崩溃。
  解决：改文件用 `[System.IO.File]::WriteAllText(path, text, New-Object System.Text.UTF8Encoding($false))`。

### 9. 修 bug **必须先加诊断**

跨维度一个 bug 打了 9 个版本（1.6.0→1.8.0），前 6 轮全靠猜——改传送落点、改位置同步、改开箱姿势，
**全都改在从未被执行到的代码路径上**。加上诊断后 **2 次运行就定位**到「传送分支没进入」。

**结论**：诊断已做成常驻设施（[Diag.java](src/main/java/com/containerbot/Diag.java) + `/cbot debug`），
状态迁移自动带「原因」记录，不必每次改代码临时加输出。

---

## v1.13 ~ v1.22 新增的命令

### 自动化

| 命令 | 作用 |
|---|---|
| `/cbot repeat <名字> <秒>` | **循环任务**：跑完等 N 秒自动重跑（失败也继续） |
| `/cbot repeat <名字> off` | 停止循环 |
| `/cbot pace <tick>` | 节流：每处理完一个容器停 N tick（20 = 1 秒） |
| `/cbot rate <件/小时>` | 限速：限制实际搬运吞吐量 |
| `/cbot pause\|resume [名字]` | 暂停 / 继续（进度保留，可从暂停处接着跑） |

### 物品名

| 能力 | 示例 |
|---|---|
| 英文 ID | `minecraft:diamond` / `diamond` |
| 中文名 | `钻石`（**需要客户端语言为中文**） |
| **通配符** | `*_ore`（所有矿石）、`minecraft:*_ingot`、`*矿石*` |

通配符同时匹配**物品 ID**和**本地化名**，可写在 `filter` / `route` 的物品列表里。

### 过滤与分流

| 命令 | 作用 |
|---|---|
| `/cbot filter <名字> white <物品...>` | 白名单（只搬这些） |
| `/cbot filter <名字> black <物品...>` | 黑名单（这些不搬） |
| `/cbot filter <名字> clear` | 清除过滤 |
| `/cbot route <名字> <x> <y> <z> <物品...>` | **按物品分流**：这些物品单独送到该坐标；连写多条实现多目的地 |

物品列表用 `greedyString`（放在参数最后），所以**支持中文和通配符**。

### 精确数量

| 命令 | 作用 |
|---|---|
| `/cbot exact <名字> on\|off` | **精确数量模式**（默认关） |

`QUICK_MOVE` 是整组搬，要 32 个但箱里是 64 一组就会拿 64。
开启后，**超过剩余需求的那一组不会被搬**，保证绝不超额。
代价：如果只有一大组，可能一个都拿不到——宁可少给也不多给，任务结束会如实报告差额。

### 导航与盘点

| 命令 | 作用 |
|---|---|
| `/cbot scan [半径]` | **只盘点不搬**：统计区域内的容器类型与内容物 |
| `/cbot goto <名字> <x> <y> <z> [维度]` | **单纯走过去**：同维度 64 格内走，超过或跨维度直接传送 |
| `/cbot test` | **一键自检**：Carpet 接入、物品名解析、A*、容器扫描 |

### 分组

| 命令 | 作用 |
|---|---|
| `/cbot tag <名字> <组名>` | 加入分组 |
| `/cbot untag <名字> <组名>` | 移出分组 |
| `/cbot group` | 列出所有分组 |
| `/cbot group <组名> pause\|resume\|stop` | 对整组执行 |
| `/cbot group <组名> feed on\|off` | 整组开关饱食度维持 |
| `/cbot group <组名> exact on\|off` | 整组开关精确数量 |

**组名含中文时要用引号**：`/cbot tag BotA "采矿组"`。ASCII 组名（如 `mining`）不需要引号。

### 统计与生存

| 命令 | 作用 |
|---|---|
| `/cbot stats [名字]` | 搬运统计：任务、耗时、件数、速率、按物品明细 |
| `/cbot help` | 中文帮助 |
| `/cbot feed <名字> on\|off` | 饱食度维持（**默认关闭**）：直接改 `FoodData` 补满饥饿值，相当于"作弊式保命"，想开自己开 |
| `/cbot eat <名字> on\|off` | 自动进食（**默认开启**）：让假人像真人一样吃背包里的食物 |
| `/cbot feed <名字> test` | 测试钩子：把饥饿值打到 10（**需先 `/cbot debug hooks on`**） |
| `/cbot eat <名字> test` | 测试钩子：强制吃一次（**需先 `/cbot debug hooks on`**） |

**`eat` vs `feed` 的分工**：`eat` 是**默认行为**（自然、但依赖背包有食物）；`feed` 是**可选兜底**（可靠、不消耗物品，但属于直接改数据）。两者可共存，也可都关掉。

**测试钩子默认关闭**，避免日常误触改写 `FoodData`。需要验证时先 `/cbot debug hooks on`。

---

## v1.13 ~ v1.22 踩的新坑

| 坑 | 说明 |
|---|---|
| **循环计时被提前返回挡住** | 一轮结束后 `task == null`，`tick()` 在 `if (task == null) return;` 处提前返回，等待倒计时永远不执行 → 循环不重启。**必须放在该 return 之前** |
| **限速计时同类问题** | `taskTicks++` 放在等待分支之后 → 等待期间不计时 → 预算永不增长 → 死循环。**计时必须在等待之前累加** |
| **`ItemStack` 是可变的** | `menu.clicked(QUICK_MOVE)` 之后原槽位被清空，此时再读 `stack.getItem()` / `getCount()` 得到的是 `Air` / `0`。**统计必须在点击前取快照** |
| **容器认领的 key 要带维度** | 同坐标在主世界/下界/末地都存在，只用 `BlockPos` 会把不同维度的箱子当成同一个 |
| **假人被移除必须释放认领** | 否则那些箱子被永久视为"有人在搬"，其他假人永远跳过 |
| **`Inventory.selected` 已私有** | 1.21.5+ 改用 `getSelectedSlot()` / `setSelectedSlot(int)` |
| **`ItemStack.isEdible()` 不存在** | 改用 `stack.has(DataComponents.FOOD)` 判断是否食物 |
| **`Inventory.getOffhandItem()` 不存在** | 用 `LivingEntity.getOffhandItem()` |
| **原版没有 Saturation 状态效果** | 只有 Hunger。想维持饱食度必须直接操作 `FoodData.setFoodLevel()` / `setSaturation()` |
| **满饥饿值时原版不允许进食** | 所以"强制吃"在 `foodLevel = 20` 时必然无效，这是原版规则不是 bug |
| **新假人的 saturation 是 20.0** | 不是 5.0。Hunger 效果先扣这层厚缓冲，`foodLevel` 很久都不动——别误判成"FoodData 不 tick" |
| **中文物品名依赖服务端语言** | 专用服务端的物品名恒为英文（`Carpet language` 管不了），中文名只在客户端为中文的集成服务端上生效 |

---



## 可用性安全策略（v1.23）

**核心规则：绝不假设假人空闲。** 假人可能正在做我们管不着的事——Carpet 钓鱼、手动指令、别的模组——贸然派活会打断甚至搞乱它。

| 命令 | 作用 |
|---|---|
| `/cbot spawn <名字>` | 生成假人，**默认标记为「有任务」** |
| `/cbot spawnfree <名字>` | 生成并**直接标记为空闲**（= spawn + free） |
| `/cbot free <名字>` | 标记为空闲，可以接任务 |
| `/cbot busy <名字>` | 标记为有任务，收回 |
| `/cbot free` | 列出所有假人的占用状态 |

**受守卫限制的命令**：`start` / `get` / `move` / `route` / `goto`
**不受限**：`spawn` / `spawnfree` / `despawn` / `stop` / `status` / `stats`（管理动作，不是派活）

被拒绝时的提示会明确告诉你怎么解除：

```
假人 BotA 已被标记为「有任务」，用 /cbot free BotA 解除
假人 BotA 当前正在执行任务（行走中），用 /cbot stop BotA 停掉
```

### 生命周期

```
spawn         → 有任务（默认）
free          → 空闲，可接任务
接任务并完成   → 保持空闲（可复用）
假人下线       → 自动重新标记为「有任务」
```

**「下线自动标记」的适用范围**：只在**非 `/cbot despawn` 触发**的下线生效（Carpet 移除、服务器重启、被踢）。因为 `/cbot despawn` 会直接删除记录，标记也就没有意义了。

### ⚠️ 直接用 Carpet 的 `/player <名字> spawn` 会怎样

- **不会创建 `LootBot` 记录** → `/cbot status`、`/cbot free` 看不到它
- **`feed`（饱食度维持）不生效** —— 没有记录就没人 tick 它，假人会真的饿死
- 之后用 `/cbot start ...` 会**自动补建记录**，但默认是「有任务」，需要先 `free`

**结论**：只要想用本模组的任何功能，就用 `/cbot spawn` / `/cbot spawnfree`。
只有"纯 Carpet 假人"（钓鱼、挂机）才适合直接用 `/player`。

需要精确控制生成点时，可以 `/player Bot spawn at <x> <y> <z> [in <维度>]`，再 `/cbot free Bot` 接管。

---



## Carpet 版本变化怎么办

本模组对 Carpet 的依赖面**只有 6 个反射成员 + `/player spawn` 命令**，其余全是原版 API：

```
carpet.fakes.ServerPlayerInterface#getActionPack()
carpet.helpers.EntityPlayerActionPack#setForward(float)
                                  #setStrafing(float)
                                  #look(float, float)
                                  #lookAt(Vec3)
                                  #stopMovement()
```

**不会崩**：纯反射 + `try/catch`，无编译期依赖、无 Mixin 进 Carpet、`fabric.mod.json` 里是软依赖；
解析失败会永久降级为直接写原版输入字段。

**升级后如何判断**：执行 `/cbot debug`，会显示逐成员解析结果：

```
Carpet 动作包: 可用（全部成员解析成功）
Carpet 动作包: 不可用（缺失: setForward, stopMovement）
```

启动日志也会记录同样信息。真对不上时，只需改
[CarpetBridge.java](src/main/java/com/containerbot/CarpetBridge.java) 里 6 行 `pick(...)`。

---

## 已知未验证 / 待办

| 项 | 状态 |
|---|---|
| 区域选择 `pos1`/`pos2`/`region` | ✅ 已实测（v1.12.1） |
| 定时/循环任务 | ✅ 已实测（v1.13.1） |
| 多假人防抢同一箱子 | ✅ 已实测（v1.14.0，两个假人各搬 192 件互不冲突） |
| 按物品分流多目的地 | ✅ 已实测（v1.16.0） |
| 精确数量不超量 | ✅ 已实现（v1.22.0，`/cbot exact`）。**采用"超额的组不搬"策略**，保证不超出但不保证补齐 |
| 中文物品名输入 | ✅ 已实现（**需客户端语言为中文**）。专用服务端恒为英文，验证不了 |
| 扫描/盘点、导航、自检 | ✅ 已实测（v1.18.0） |
| 饱食度维持 `feed` | ✅ 已实测（v1.21.1，两个 setter 均确认写入 `FoodData`） |
| 分组/标签 | ✅ 已实测（v1.22.1）。ASCII 组名直接写；中文组名需双引号 `/cbot tag BotA "采矿组"` —— 已用 UTF-8 命令文件验证引号与中文完整到达服务端，分组操作 2/2 生效 |
| **自动进食 `eat` 真的吃下去** | ⚠️ **未验证**。满饥饿度时原版禁止进食，而假人在无头服务端难以制造饥饿状态。代码路径（找食物 → 切手 → 发起进食）已通过诊断确认执行 |
| **服务器重启后任务恢复** | ❌ 未做。状态全在内存，重启后循环/选区/目的地全部丢失——**这是"长期挂机"的最后一块拼图** |
| 目的地自动轮转 | ❌ 未做（目的地满了会报错停下） |
| 分区并行（多假人自动分工） | ❌ 未做 |
| BossBar 进度条 | ❌ 未做 |
| 失败告警（声音/标题） | ❌ 未做 |


---

## 版本陷阱记录

- `AbstractContainerMenu.clicked(int,int,ClickType,Player)` 在 **1.21.8 是公有的**，
  但 **1.21.10 已改名 `doClick`**，按最新版写会编译失败。
- Loom 1.18.2 **要求 JDK 25** 才能运行（产物仍编译为 Java 21 字节码）。
- 1.21.8 官方模板已从 Yarn 改用 **Mojang 官方映射**，插件 ID 也改为 `net.fabricmc.fabric-loom-remap`。
- `Item` **没有** `getDescription()`；取显示名要包一个 `ItemStack` 再调 `getHoverName()`。
