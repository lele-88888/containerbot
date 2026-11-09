# 版本归档说明

> 最后更新：v1.23.2

## 约定

**每个版本的最终 jar 都保存在 `releases/` 目录**，文件名即版本号：

```
releases/containerbot-<版本号>.jar
```

`build/libs/` 会随着后续构建被覆盖，**只有 `releases/` 是长期留档**。

---

## 已归档版本（按功能里程碑）

| 版本 | 主要内容 |
|---|---|
| **1.1.0** | 基础取货 + 区域选择（`pos1`/`pos2`/`region`）；修复假人名大小写不匹配；`start` 自动补建记录 |
| **1.2.0** | 修复「任务完成后假人一直走」；物品名免引号（**但此版引入自定义参数类型 bug，会导致玩家掉线**） |
| **1.2.1** | 修复 1.2.0 的掉线 bug：改用原生 `ResourceLocationArgument` |
| **1.3.0** | 任务队列、潜影盒整盒搬运、进度提示、清空任务 |
| **1.3.1** | 修复失败静默问题（`fail()` 从不发通知） |
| **1.4.0** | 指定卸货箱 `/cbot deposit` |
| **1.4.1** | 修复卸货死循环 + 走路停止兜底 |
| **1.5.0** | **搬家模式** `/cbot move`（掏空区域所有容器） |
| **1.5.1** | 修复「失败没有提示」：所有失败路径都会通知，并区分失败原因 |
| **1.6.0** | 搬家支持「扔在地上」+ 跨维度目的地（自动判定容器/地面） |
| **1.6.1 ~ 1.6.6** | 跨维度往返的一系列修复（回程、谎报成功、站位、A\* 起点等） |
| **1.7.0** | **常驻诊断设施** `/cbot debug on\|off\|dump`（状态迁移自动记录） |
| **1.7.1** | 加维度检查诊断 |
| **1.8.0** | **跨维度搬家完整跑通**（投送改用 `openMenu` 直连） |
| **1.8.1** | 节流间隔 `/cbot pace`（限制动作频率） |
| **1.9.0** | 暂停/继续 `/cbot pause` `/cbot resume`（进度保留） |
| **1.10.0** | 吞吐限速 `/cbot rate <件/小时>` |
| **1.10.1** | 修复限速死循环（计时位置错误导致预算永不增长） |
| **1.11.0** | Carpet 兼容性硬化：逐成员反射诊断 + `/cbot debug` 可查接入状态 |
| **1.12.0** | 区域选择支持坐标形式（控制台可用），补 `boxDim` 字段 |
| **1.12.1** | 区域选择完整：维度记录、跨维度校验、假人自动前往选区所在维度；区域选择首次实测通过 |
| **1.13.0** | 循环任务 `/cbot repeat <名字> <秒>`（跑完等 N 秒自动重跑，失败也继续） |
| **1.13.1** | 修复循环不重启（计时被 `task == null` 的提前返回挡住） |
| **1.13.2** | 修复停止循环后状态文案残留 |
| **1.14.0** | **多假人防抢箱子**：全局容器认领表（key = 维度 + 坐标） |
| **1.15.0** | 白名单/黑名单 `/cbot filter`；新增中文物品名解析（`ItemNames`）；`/cbot get` 中文友好取货 |
| **1.15.1** | 白/黑名单加逐槽位诊断（确认过滤逻辑正确） |
| **1.16.0** | **按物品分流** `/cbot route`：多条规则各送各的目的地，支持中文 |
| **1.17.0** | 搬运统计 `/cbot stats`；中文帮助 `/cbot help` |
| **1.17.1** | 修复统计明细记录时机（`ItemStack` 可变，点击后读到 Air/0） |
| **1.18.0** | 盘点 `/cbot scan`、导航 `/cbot goto`（远距/跨维度自动传送）、自检 `/cbot test` |
| **1.19.0** | 搬家完成后二次扫描核对：确认区域真的搬空，或如实报告还剩几个非空 |
| **1.20.0** | 自动进食 `/cbot eat`（行为模拟，默认关闭） |
| **1.20.1** | 加 `/cbot eat <名字> test` 强制吃一次，便于验证进食动作 |
| **1.21.0** | **饱食度维持 `/cbot feed`（默认开启）**：直接改 `FoodData`，可靠保命；`eat` 降级为可选 |
| **1.21.1** | 用临时阈值验证 feed 的两个 setter 确实写入 `FoodData`，验证后已还原 |
| **1.21.2** | `eat` 增加副手回退（主手/背包无食物时从副手进食） |
| **1.22.0** | 通配符匹配（`*_ore` 等）、精确数量模式 `/cbot exact`、假人分组 `/cbot tag` `/cbot group` |
| **1.22.1** | 分组名改用 `string()` 参数类型，支持引号包裹的中文组名（已用 UTF-8 命令文件验证） |
| **1.23.0** | **可用性安全策略**：假人默认标记为「有任务」，必须 `/cbot free` 才能派活；下线自动重新标记 |
| **1.23.1** | 修复守卫被误插进 `spawn` 导致无法生成假人 |
| **1.23.2** | 新增 `/cbot spawnfree`：生成并直接标记为空闲 |
| **1.24.0** | feed 加测试钩子 `/cbot feed <名字> test`（把饥饿值打到 10），并实测验证"10 → 20 自动补满" |
| **1.25.0** | **当前版本**。`eat` 改为默认开启、`feed` 改为默认关闭；测试钩子默认关闭，需 `/cbot debug hooks on` |

> 归档从 **1.1.0 开始**。更早的构建已被覆盖、无法追溯——这正是加这套机制的原因。
> **注意：`1.0.0` 从未归档过**，不要试图回滚到它。

---

## 发一个新版本怎么做

1. **改版本号**（[gradle.properties](gradle.properties) 的 `version=`）：

   ```properties
   version=1.12.0
   ```

2. **构建**——双击 [build-mod.bat](build-mod.bat)，或在项目目录执行：

   ```powershell
   $env:JAVA_HOME  = "$PWD\tools\jdk-dist\jdk-25.0.4.1+1"
   $env:GRADLE_USER_HOME = "$PWD\.gradle-home"
   & "$PWD\tools\gradle-dist\gradle-9.7.0\bin\gradle.bat" -p "$PWD" build --console=plain --no-daemon
   ```

   `build` 结束后会自动执行 `archiveRelease`，把 jar 复制进 `releases/`，并打印：

   ```
   === 已归档版本 1.12.0 ===
   ```

3. **部署到实例**——`build-mod.bat` 会自动做，且**先删掉 mods 里的旧版本**。

> ⚠️ **不要用 PowerShell 的 `Set-Content` 改 `gradle.properties`**：
> PS 5.1 会写入 BOM、把中文注释变乱码、还会吞掉换行，直接导致构建失败。
> 用 `[System.IO.File]::WriteAllText(路径, 文本, New-Object System.Text.UTF8Encoding($false))`。

---

## ⚠️ 一个必须注意的坑

**mods 目录里绝对不能同时存在两个同 id 的 jar。**

Fabric 会因为「duplicate mod id」直接拒绝启动。所以每次装新版本前，必须先删掉旧的
`containerbot-*.jar`——**包括被启动器改成 `.disabled` 后缀的**。

```bat
del /q "%MODS%\containerbot-*.jar" >nul 2>&1
del /q "%MODS%\containerbot-*.jar.disabled" >nul 2>&1
copy /y "!JAR!" "%MODS%" >nul
```

如果是手动复制，请自己记得先删旧的。

---

## 回滚到旧版本

从 `releases/` 里把想要的那个 jar 复制进 mods（**先删掉当前的**）：

```
releases/containerbot-1.8.0.jar  ->  D:\.minecraft\versions\1.21.8\mods\
```

---

## 归档任务是怎么接上去的

[build.gradle](build.gradle) 末尾：

```groovy
tasks.register('archiveRelease', Copy) { ... }

tasks.named('build') {
    finalizedBy 'archiveRelease'
}
```

用 `finalizedBy` 而不是 `dependsOn`，避免和 `build` 形成循环依赖。
归档只会挑 `*-<当前版本号>.jar`，所以 `build/libs` 里万一残留旧版本的 jar 也不会被误归档。
