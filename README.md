# ContainerBot

> Minecraft **1.21.8 Fabric** 服务端模组：让 **Carpet 假人自动开箱搬货**

Carpet 的假人（fake player）是 `ServerPlayer` 而不是 `Mob`，原版寻路用不了，也没有任何"去箱子里取货"的能力。
这个模组把这两件事都补上了——假人会**自己走过去、真的打开箱子、真的把东西搬进背包**。

---

## 功能

| 类别 | 能力 |
|---|---|
| **取货** | `/cbot start` 指定物品+数量，假人自己找箱子搬够为止 |
| **搬家** | `/cbot move` 掏空整个区域的所有容器，送到指定地点 |
| **跨维度** | 目的地设在下界也行，假人自己传送过去倒货再飞回来 |
| **扔地上** | 目的地是空位就直接倾倒（喂漏斗/收集装置） |
| **按物品分流** | `/cbot route` 多条规则，不同物品送不同目的地 |
| **潜影盒** | 装满货的潜影盒整盒扛走，进度按盒内真实数量计 |
| **区域限定** | `/cbot pos1/pos2` 框选长方体，只搬这里的 |
| **队列 / 循环** | 一个假人排队做多件事；`/cbot repeat` 定时重跑 |
| **白/黑名单** | `/cbot filter` 配合**通配符**（`*_ore`） |
| **精确数量** | `/cbot exact` 保证不超额 |
| **防抢箱子** | 多个假人同时干活时自动分工，不抢同一个箱子 |
| **导航 / 盘点** | `/cbot goto` 单纯走过去；`/cbot scan` 只统计不搬 |
| **调速 / 暂停** | `pace` 限动作频率、`rate` 限件/小时、`pause` 冻结 |
| **可观测** | `stats` 统计、`test` 自检、`debug` 诊断、搬空二次核对 |

---

## 安装

1. 需要 **Fabric Loader** + **Fabric API** + **Carpet**
2. 把 `releases/containerbot-<版本>.jar` 放进 `mods/`
3. 启动服务器 / 存档

> **安装新版本前必须删掉旧的** `containerbot-*.jar`（含被启动器改成 `.disabled` 的），
> 否则 Fabric 会因「duplicate mod id」拒绝启动。

---

## 快速上手

```
/cbot spawnfree Bot                 生成假人并标记为空闲
/cbot start Bot minecraft:diamond 64    取 64 个钻石
/cbot status                        看进度
/cbot despawn Bot                   移除（物品掉地上）
```

**搬家**：

```
/cbot deposit                       准星对着目标箱子 → 设为目的地
/cbot pos1  → 走对角 → /cbot pos2   框选要搬空的区域
/cbot region                        确认（会数出容器数量）
/cbot move Bot                      开搬
```

**注意**：假人默认被标记为「有任务」，必须 `/cbot free` 或 `/cbot spawnfree` 才能派活——
这是安全策略，避免误占正在钓鱼 / 手动操作中的假人。

完整命令：`/cbot help`

---

## 实现要点

- **自写 A\***：假人不是 `Mob`，原版 `WalkNodeEvaluator` 强制要求 `Mob`，只能自己实现
- **真实交互**：开箱用 `ServerPlayerGameMode.useItemOn`（等价真人右键），取货用
  `AbstractContainerMenu.clicked(QUICK_MOVE)`（等价 Shift+左键）
- **驱动移动**：反射 Carpet 的 `EntityPlayerActionPack`，失败时降级为直接写原版 `zza`/`xxa`
- **Carpet 解耦**：只反射 6 个成员 + 派发 `/player spawn`，无编译期依赖、无 Mixin，
  Carpet 升级不会崩（`/cbot debug` 可查接入状态）

### 这个项目里踩过的坑

见 [BUILD-NOTES.md](BUILD-NOTES.md)，其中值得单独提的：

- 自定义 Brigadier 参数类型若未注册，**会把玩家踢下线**
- 箱子碰撞箱只有 14/16 高，站上去时 `blockPosition()` 等于箱子自身坐标
- `ItemStack` 是可变对象，`QUICK_MOVE` 之后再读会得到 `Air`/`0`
- 等待分支若有提前 `return`，计时必须放在它**之前**，否则死循环
- **修 bug 必须先加诊断**——跨维度一个 bug 曾瞎猜 6 轮全落空，加诊断后 2 次定位

---

## 目录

```
src/            模组源码
releases/       每个版本的最终 jar（长期留档）
tools/          构建辅助工具（RCON 客户端、--patch-module 补丁等）
BUILD-NOTES.md  实现细节与踩坑记录
RELEASES.md     版本归档与发布流程
```

`tools/jdk-dist/`、`tools/gradle-dist/`、`testserver/` 等大文件不入库，重建方法见 BUILD-NOTES.md。

---

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.21.8 |
| Fabric Loader | ≥ 0.16 |
| Fabric API | 0.131.0+1.21.8 |
| Carpet | 1.4.177+v250630（1.21.7 分支构建） |
| Java | ≥ 21 |

构建需要 **JDK 25**（Loom 1.18 要求），产物编译为 Java 21 字节码。
