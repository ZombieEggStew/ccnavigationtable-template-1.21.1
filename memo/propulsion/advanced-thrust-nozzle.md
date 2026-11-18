# 矢量喷管 `ccpe:advanced_thrust_nozzle` 工作记录

> 状态（2026-11，**v10 定稿，用户实测"完全符合我的需求"**）：可转动模型 ✅、四活塞随动 ✅、频道 + 右键 GUI ✅、
> CC:T 外设（偏转 / 出力）✅、**偏转点直线插值 + `setDirection` 摇杆写法** ✅（用户实测"完全符合预期"）；
> `./gradlew.bat classes` 通过。
> 现行两个手感参数：**偏转平面线速度 160°/s**、**矢量喷管出力 2 pN/RPM**（定向喷管仍 1 pN/RPM）。
> 三处几何定稿：**枢轴 `(8,5,8)px`**（= 活塞头中面）、**活塞杆 = body 子件 + 轴向滑移**（§4.1）、
> **选择框 = `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)`**（§7）。
> 变更明细：第一轮（偏转点/摇杆/出力翻倍）见 §11；第二轮（用户重做模型 + 活塞头并进 base + 选择框两盒）见 §12.1–12.5；
> 第三轮（活塞杆改 body 子件、枢轴抬到 y5、越界核算）见 §12.6。**前三轮全部经用户实测通过。**
> 第四轮（`setThrustScale` 的出力系数同时缩放排气 / 进气粒子）见 §14 —— 已实现、编译通过，用户实测确认。
> 第六轮（尾迹发射密度也随出力系数缩放 ⇒ `setThrustScale(0)` 时高空不再出尾迹）见 §16 —— 已实现、编译通过，待实测。
> 第七轮（**尾迹高度区间拓宽**：Y100 起线性变浓、180~240 满、240~320 线性归零）见 §17 —— 已实现、编译通过，待实测。
> 第五轮（蓝图部署保留频道：实现 `PartialSafeNBT.writeSafe`，见 §15）—— 已实现、编译通过，待实测。
> ⚠️ 换模型后若动画错位，先对 §12.4 那张"模型数字 ↔ 代码常量"表；改动前先看 §4.1（活塞机构）与 §12.6（枢轴高度）。
>
> 七个能力一句话版：
> 1. **可倾斜喷口**：固定底板 + 绕枢轴 `(8,5,8)px`（= 四活塞头中面）在**锥角 15° 内**任意指向的喷口本体；
> 2. **四活塞随动**：活塞头固定（0 自由度，**已并进底座模型**）、活塞杆是 **body 的子件、只沿 body 轴伸缩**（1 自由度，§4）；
> 3. **推力 = 倾斜轴**：`nozzleAxis()` 钩子让推力与**全部粒子链路**一起跟着倾斜轴走；
> 4. **出力系数**：Lua `setThrustScale(0..1)` 乘在「风扇转速 × 气压」之上（默认 1.0）；**矢量喷管满出力 = 2 pN/RPM**；
>    同一系数**也决定排气 / 进气 / 尾迹粒子**（有效油门 = 转速 × 系数；尾迹只缩发射密度，见 §14、§16）；
> 5. **物理体作用域频道**：放置自动分配、只在物理体上生效、与 `short_range_linker` / 控制台共享频道空间；
> 6. **CC:T 外设**：`setDeflection(方位角, 锥角)`（极坐标）/ `setDirection(x, y)`（摇杆，`-1..1`）/ `getDeflection` /
>    `getMaxTilt` / `isMoving` / `setThrustScale` / `getThrustScale` / `getChannel`，可用 `ccpe.sensor_system.getPeripheral(ch)` 寻址。
> 7. **渐变到位**：Lua 只写目标，服务端**恒速**推进、客户端本地指数逼近平滑（§6.5），插值发生在**偏转平面**上 ⇒ 轴端走**直线**。
>
> 母文档：`memo/propulsion/thrust-nozzle.md`（定向喷管 `ccpe:thrust_nozzle` 本体；本方块**继承**它的一切）。

---

## 1. 需求与决策链（用户定稿）

| 议题 | 结论 |
|---|---|
| 参考对象 | `aeronautics:gyroscopic_propeller_bearing`（固定体 + 可倾盘 + 四个动态小活塞） |
| 与参考的差别 | 参考是"**固定体在下、可动盘在上**"；本方块是"**固定底板在下、可动 block 在上**"，**活塞上下镜像** ⇒ 自由度必须重算（§4） |
| 固定/可动划分 | 固定 = 底板 `base.json`（**含 4 个活塞头**）；可动 = `block.json`（裙边 + 筒身 + 十字臂 + 顶环），绕枢轴 `(8,5,8)px` |
| 推力方向 | **倾斜后的轴向**（锥内任意方向，矢量引擎语义） |
| 锥角钳位 | **15°**（几何依据见 §6.3） |
| 角度 API 形态 | 两种写法并存：极坐标 `setDeflection(方位角, 锥角)` + 摇杆 `setDirection(x, y)`（`-1..1`，方向=方位、长度=锥角），另给 `getDeflection` / `getMaxTilt` |
| 状态表示 | **偏转点 `(x, y)`**（长度=锥角、方向=方位）才是状态，方位/锥角只是它的极坐标读法；插值在偏转平面上做 ⇒ **走直线**（用户定稿） |
| 出力 API | `setThrustScale(0..1)` 乘在「转速 × 气压」上，默认 **1.0**；**矢量喷管满出力 = 2 pN/RPM**（`THRUST_OUTPUT_MULTIPLIER = 2.0`，2026-11 用户定稿；定向喷管仍 1 pN/RPM）。**同一系数也缩放排气 / 进气 / 尾迹粒子**（§14、§16：尾迹只缩发射密度，寿命仍看高度） |
| 选择框/碰撞箱 | **底板 `(0,0,0,16,4,16)` + 喷口包络 `(2,4,2,14,16,14)`** 两个盒（2026-11 用户定稿；**不随倾斜/动画改变**，六向由模型旋转生成） |
| GUI 内容 | **只有频道滚轮**，不做"加载物理体"开关（喷嘴不持有 force-load ticket） |
| 频道作用域 | 物理体（含约束链）；非物理体**不注册** |
| 外设方法 | 本阶段提供偏转 + 出力 + `getChannel`（早期一版曾定"暂不给 getChannel"，最终按外设形态给回） |
| 偏转是否瞬间到位 | **否**：Lua 只写目标，服务端**恒速**推进（§6.5），客户端用同一速度的墙钟纯函数画 |
| 渐变参数 | **写死在代码里**（偏转平面线速度 160°/s = 8°/tick、出力 0.25/tick），Lua 不提供速度/时长参数 |
| 平滑算法 | 视觉偏转点 = **一个朝 target 走的点**，每次渲染用 `System.nanoTime` **差值**推进一步（§6.5 v9）；**不要**改回"每 tick 累加 / 按调用频率推进 / (起点,终点,起始时刻) 三元组" |
| 方位角跨 0/360 | 不再有"最短路径"逻辑：目标是**偏转平面上的点**，走直线；穿过中心时方位瞬间反向属正常（§6.3 第 3 条） |
| `getDeflection` 返回 | **实际角 + 目标角都给**，另加 `isMoving()` |
| 出力系数是否渐变 | **是**，与角度同一套曲线（数值单位改成"系数/秒"） |

---

## 2. 结构与模型

模型目录 `src/main/resources/assets/ccpe/models/block/advanced_thrust_nozzle/`（用户自建 Blockbench 模型）：

| 文件 | 内容 | 用途 |
|---|---|---|
| `base.json` | 底板 `x0..16, y0..4, z0..16` + **4 个活塞头**（半径 6..8px、`y4..6`、切向 6px，坐在底板上表面） | **固定件**，走 blockstate 静态渲染 |
| `block.json` | 裙边 `y2..6`、筒身 `y6..14`、四条十字臂 `y9..13`（伸到方块四边）、顶环 `y14..18` | **可动部分**，走 BER 动态渲染 |
| ~~`piston_head.json`~~ | **已删除**（2026-11：头是固定件、与底座无相对移动 ⇒ 并进 `base.json`） | — |
| `piston_pole.json` | 1×7×2px 连杆（`from [-0.5,0,-1] to [0.5,7,1]`，局部原点 = 铰点） | 4 个活塞杆（可动件） |
| `item.json` | 上面全部 + `groups` 静态摆位 | 物品模型；**group 的摆位是几何基准的权威来源** |

**几何基准（从 `item.json` 反推，单位 px）**——2026-11 用户重做模型后：

| 部件 | 距中轴 | 高度 | 切向宽 |
|---|---|---|---|
| 底板 | 0..8（整块） | **0..4** | 16 |
| 活塞头 | **6..8** | **4..6** | 6 |
| 活塞杆 | **6..7** | **5..12** | 2 |
| 枢轴 | — | **y=5（= 活塞头中面，2026-11 第三轮调上来）**、x/z=8 | — |

⇒ **铰点 = 杆底面中心 = 距中轴 6.5px、高度 y=5px**（§4.3 的模型定位约定就用这个点）。
杆底面插进活塞头 1px（5 在 4..6 之间），绕铰点摆动时接缝始终被头挡住。

> ⚠️ 旧版几何（底板 y0..2、头 y0..2、杆 y1..8、枢轴 y1）已被这一版取代；`piston_pole.json` 本身<b>没变</b>
> （定位靠"局部原点 = 铰点"的约定，改高度只改代码里的 `PISTON_HINGE_HEIGHT`）。

---

## 3. 渲染（`AdvancedThrustNozzleRenderer`）

### 3.1 只走 BER，不注册 Flywheel Visual

照 `MyBearingPlateRenderer` 的先例：**没有 Visual 时 Flywheel 不会跳过原版 BE 渲染**，所以 BER 是唯一渲染来源。
⚠️ 因此渲染器里**不能**加 `VisualizationManager.supportsVisualization(...)` 提前返回，否则装 Flywheel 就整个消失。

### 3.2 变换链（外 → 内）

```
FACING 旋转（与 blockstate 的 x/y 旋转严格一致）
  → 绕枢轴倾斜 rotateAround(tilt, PIVOT)          // = translate(+P)·rotate·translate(−P)
  → 各部件自身平移 / 方位旋转
```

- **FACING 链逐步照抄已验证的 `ServoBearingRenderer`**：
  水平朝向先 `rotateCentered(rad(horizontalAngle(反朝向)), UP)`，再
  `rotateCentered(rad(-90 - verticalAngle), EAST)`——它与本方块 blockstate 的
  up/down(x180)/north(x90)/east·south·west(x90+y90·180·270) 六变体逐条对应。
- ⚠️ **`SuperByteBuffer` 的变换是"先调用 = 外层"**（内部 `PoseStack` 累乘 + `renderInto` 末尾自动 `reset()`，
  所以同一个 partial 在一个循环里画 4 次也不会累积变换）。外层必须先调用，部件平移最后调用。
- 枢轴 `PIVOT = (8,5,8)px = (0.5, 5/16, 0.5)`（**四活塞活塞头 y4..6px 的中面**）。
  高度这一条同时决定两件事：十字臂伸出方块四边的量（抬越高越少）与活塞杆底面在底座里的漂移量
  （y=5 时最小）⇒ y=5 是唯一的双赢点，**离线实测数据见 §12.6**，别随手改。

### 3.3 倾斜姿态来源

`AdvancedThrustNozzleBlockEntity#getRenderTiltQuaternion(float frameTicks)`：把模型空间 `+Y` 转到 `tiltDirModel()`
（由方位角 / 锥角派生）的**最小弧**四元数。渲染版先把服务端同步来的当前角经
`JoystickTilt#approach`（指数逼近、帧时间修正）平滑到本地动画状态，再合成四元数（§6.5）。
物理侧 `nozzleAxis()` 用同一个方向的**当前值**再按 blockstate 旋转转到 plot 系 ⇒ **推力方向与视觉方向一致**。

---

## 4. 活塞机构（自由度，**前后写错过三版，最终版在这里**）

### 4.1 最终机构（v5，2026-11 第二轮；用户实测方向见 §9）

| 部件 | 隶属 | 自由度 | 实现 |
|---|---|---|---|
| 活塞头 | **底座（固定件）** | **0** | **不写代码**：已并进 `base.json`，由 blockstate 静态模型渲染（`y4..6`、半径 6..8px） |
| 活塞杆 | **body 的子件** | **1（沿 body 自身 +Y 的滑动）** | `AdvancedThrustNozzleRenderer`：`rotateAround(tilt, PIVOT) + translate(径向钉死 + 滑移) + rotateY(azimuth)` —— **与 body 同一个 tilt、同一个枢轴** ⇒ 相对 body **无旋转**，只在臂槽里伸缩 |

视觉结果 = **固定的头 + 在 body 臂槽里伸缩的杆 + 随倾斜滑过杆顶的 body**。

**为什么必须是"body 的子件"**（v5 的核心，别再改回去）：
v3/v4 是"杆底面钉死在铰点、绕铰点吃 tilt"。两者朝向确实永远平行（相对旋转 = 0），但**旋转中心不同**
（杆绕铰点、body 绕枢轴）⇒ 相对位移 = `(I − R)(H − P)`，其中 `H − P = (6.5, 3, 0)px`：
15° 时**横向最多 ≈1px、轴向最多 ≈1.8px**。杆径向只有 1px 粗、臂槽每边只有 1px 余量 ⇒
那 1px 横向会让杆蹭出 body 侧壁、扎进臂槽边缘，"看起来像杆在乱飘"（用户 2026-11 实测反馈）。
改成 body 子件后横向恒为 0（径向钉死在槽心），**剩下的全部误差都被"轴向滑动"吃掉**。

**滑移量怎么来**（`pistonSlideAlongBodyAxis`）：反过来解——让**杆底面在世界/底座坐标系里的高度恒为
`PISTON_HINGE_HEIGHT`（活塞头夹持那一层）**：

```
slide = (PISTON_HINGE_HEIGHT − PIVOT.y − (R·radial).y) / (R·ŷ).y
```

展开出来的就是 `(R·radial).y = ±6.5px·sin15° ≈ ±1.68px` 的补偿项。它与参考实现
`GyroscopicPropellerBearingRenderer` 第 77 行 `translateDistance = p·n_tilted / (n_fixed·n_tilted)`
是**同一个公式的镜像**（那边沿固定轴把活塞"卡"在倾斜盘上，这边沿 body 轴把杆"卡"在固定活塞头上）。

**实测数值（px，`item.json` 静止姿态为基准；枢轴 y=5、夹取上限 12.9）**

| 倾斜 | 滑移 | 杆底(世界)y | 杆底(世界)径向 | 杆顶(body 系)y |
|---|---|---|---|---|
| 0° | 0.00 | 5.00（与模型完全一致） | 6.50（= 模型） | 12.00 |
| 朝本活塞 +15° | 0.90（夹取生效；未夹时 1.74） | 4.19（仍在头 y4..6 里） | 6.51 | 12.90 |
| 背本活塞 −15° | −1.74 | 5.00 | 6.73 | 10.26 |

⇒ **三个硬边界永远成立**：杆底 ∈ [4.19, 5]px（活塞头 y4..6）、**杆底径向 ∈ [6.51, 6.73]px**
（活塞头半径 6..8，且只占 0.2px 的横向漂移 ⇒ 看上去就是个铰点）、杆顶 ≤ 12.9px（十字臂带上沿 13）。
> 夹取必要性：不夹时朝本活塞一侧滑移 1.74px，杆顶会到 13.74px、冒出臂带上沿 0.74px
> （上限 = 臂带上沿 13 − 枢轴 y5 − 杆长 7 = 1px）。夹取只牺牲那一侧 0.8px 的夹持高度（藏在头里面）。
> 上限写 12.9 而不是 13 是为了避开**共面 z-fighting**（杆顶端面与臂带上表面完全重合会闪）。

### 4.2 三版错误（别再犯）

| 版本 | 做法 | 为什么错 |
|---|---|---|
| v1 | 照参考"头贴倾斜面升降、杆保持竖直" | 参考是"头顶在转盘上、杆朝下插进固定体"，本方块**上下镜像**，头在那个高度只能是固定件 |
| v2 | 头固定 + 杆沿自身轴**滑动**（`s = r·(N·d) + h0·(cosθ−1)`） | 滑动方向对了一半，但**滑移量的约束给错了**（v5 才是对的那条约束：卡在固定活塞头上，见 §4.1） |
| v3 | 头 0 自由度 + 杆**绕铰点转动** | 朝向正确、机构也像，但相对 body 混进 ≈1px 横向位移（旋转中心不同）⇒ 杆蹭出 body 侧壁（2026-11 用户实测） |
| v4 | 头并进 `base.json`（连渲染都不用了） | 头本来就钉在底板上、与底座没有相对移动 ⇒ 单独一个 partial 纯属多余；`piston_head.json` 已删 |
| v5 ✅ | 头固定 + 杆 = **body 子件、只沿 body 轴滑动**（滑移量由"杆底卡在夹持高度"反解 + 顶部夹取） | 相对 body 只剩上下位移，横向恒 0 |

### 4.3 模型定位约定：**局部原点 = 杆底面中心、局部 +X = 径向朝外**

| 模型 | 目标坐标 | 含义 |
|---|---|---|
| `piston_pole.json` | `from [-0.5, 0, -1] to [0.5, 7, 1]` | 底面中心 = 原点，杆沿 **+Y** 向上 7px；径向 1px(X)、切向 2px(Z)；**局部 +X = 径向朝外** |

对应代码常量（都是 px/16）：径向位置 `PISTON_HINGE_RADIUS = 6.5/16`、静止夹持高度
`PISTON_HINGE_HEIGHT = 5/16`、杆长 `PISTON_LENGTH = 7/16`（只用于顶部夹取）、
杆顶上限 `PISTON_TOP_LIMIT = 12.9/16`（臂带上沿 13 再留 0.1 防共面）。

> 改动模型时**只改 `from`/`to`**，UV 与尺寸不变；若在 Blockbench 里重新导出，必须保持这个坐标，
> 否则会退回旧定位（游戏读的是导出的 json）。换位置/换长度时**改上面那四个常量**，不要给模型加偏移。

---

## 5. 频道 + 右键 GUI

### 5.1 直接复用 `ShortRangeLinkerRegistry`（不新造注册表）

关键发现：`compat/cc/ShortRangeLinkerRegistry` 本来就是"物理体作用域频道表"
（`子次元UUID → (频道 → BlockEntity)`），而且**控制台早就和链接器共用它**。

| 能力 | 现成实现 |
|---|---|
| 放置自动分配（链内最小空闲） | `register(desired < 0, be)` |
| 同链冲突顺延 | 同上（返回实际频道） |
| **非物理体不注册** | 同上（返回 `-1`）⇒ 正好是"只在物理体上生效" |
| 按链查询 / 占用快照 / 注销 | `get(chainUuids, ch)` / `occupiedChannels(...)` / `unregisterAll(be)` |

频道号语义 = **喷嘴在物理体内的地址**（同链 1:1），与链接器/控制台共享同一频道空间。

### 5.2 生命周期

- `onLoad` → 注册；`setRemoved` → 注销；
- **`serverTick` 每 20 tick 复核**（轴承连/断后约束链变化 ⇒ 频道空间合并/拆分，同链冲突要顺延）；
- 离开物理体（拆到地上）⇒ `channel` 复位 `-1`，重新装上再自动分配；
- GUI 改动走 `AdvancedThrustNozzleConfigPayload` → 服务端 `applyChannel(desired)`（顺延回写 + 广播占用快照）。

数据流：

```mermaid
flowchart LR
    Place["放置 / onLoad"] -->|"register(-1, be)"| Reg[ShortRangeLinkerRegistry]
    Reg -->|"链内最小空闲号"| BE[NozzleBE.channel]
    Tick["serverTick 每 20t"] -->|"复核：链变化→顺延"| Reg
    GUI["GUI 频道滚轮"] --> Payload[ConfigPayload] -->|"applyChannel"| Reg
    Reg -->|"get(chain, ch)"| Lua["ccpe.sensor_system.getPeripheral(ch)"]
```

### 5.3 文件

| 文件 | 内容 |
|---|---|
| `screen/AdvancedThrustNozzleMenu.java` | 照 `ShortRangeLinkerMenu`，**去掉 bodyLoad**：pos / onPhysicsBody / channel / occupiedChannels |
| `screen/AdvancedThrustNozzleScreen.java` | 照 `ShortRangeLinkerScreen`：频道滚轮（跳过同链占用 + Shift ×10）+ 非物理体提示 + tooltip 前景层 + 完成按钮；背景**复用** `textures/gui/gui_link.png` |
| `network/AdvancedThrustNozzleConfigPayload.java` / `…PacketHandlers.java` | pos + channel；服务端二次校验"非物理体忽略"→ `applyChannel` → 刷新占用快照 |

### 5.4 状态与坑

- `onPhysicsBody` **不落盘、也不进 update tag**：客户端以 `channel >= 0` 推断。
- **蓝图部署后频道会保留**（2026-11 已修，见 §15）：`AdvancedThrustNozzleBlockEntity` 现在实现了
  `PartialSafeNBT`，`writeSafe` 写出频道 + 偏转点 + 出力系数，部署时经 `loadWithComponents` → `loadAdditional`
  读回，并在**部署当 tick** 重登记频道（`registerChannel()`），不再需要等 20 tick 复核。
- 打开 GUI 前必须 `refreshOccupiedChannels()`，否则滚轮跳过的是旧占用。

---

## 6. CC:T 外设（偏转 + 出力）

### 6.1 Lua API（type = `advanced_thrust_nozzle`）

| 方法 | 语义 |
|---|---|
| `setDeflection(azimuthDeg, tiltDeg)` → bool | **极坐标写法**：方位角绕 FACING 轴（0..360，负值自动归正）、锥角自动钳到 `0..15`；非有限值返回 false。**只写目标值**，喷口在**偏转平面里沿直线**渐变过去（见 §6.3 第 3 条 / §6.5）。**推力方向 + 排气/进气/尾迹粒子 + 渲染同时渐变** |
| `setDirection(x, y)` → bool | **摇杆写法**（2026-11 新增）：`x`/`y` ∈ `-1..1`，**方向** = 方位角、**长度**按圆形映射 = 锥角（长度 0 = 直出、1 = 满锥角 15°）。输入先各自钳 `-1..1`、再整体钳进单位圆（斜推 `(1,1)` 等价 45° 满锥角）。同样只写目标、走直线渐变 |
| `getDeflection()` → `{azimuth, tilt, targetAzimuth, targetTilt, x, y, targetX, targetY}` | `azimuth`/`tilt` = 此刻**实际**角（由偏转点反算，渐变中与目标不同），`targetAzimuth`/`targetTilt` = 目标角；方位均已归正 0..360（**点落在直出中心时方位无意义、报 0**）；`x`/`y` = 实际偏转点（度，`锥角·(cos,sin) 方位`），`targetX`/`targetY` = 目标偏转点 |
| `getMaxTilt()` → `15` | 钳位上限（脚本归一化用） |
| `isMoving()` → bool | 是否还在渐变中（偏转或出力尚未到位） |
| `setThrustScale(scale)` → bool | 出力系数 0..1（越界钳位），乘在「风扇转速 × 气压」之上；**矢量喷管满出力 = 2 pN/RPM**（系数 1.0 ⇒ 2 倍额定；气压 1.0、256 RPM ⇒ 512 pN/喷管）；**同时也决定排气 / 进气 / 尾迹粒子**（有效油门 = 转速 × 系数 ⇒ 排气·进气的数量、排气速度、进气漏斗长度一起变，尾迹的**发射密度**同比例缩放；0 时三种粒子完全不出，尾迹寿命仍看高度；§14、§16）；同样按运动曲线渐变 |
| `getThrustScale()` → 0..1 | 当前出力系数（0..1 的比例值，渐变中是此刻实际值；实际推力 = 这个数 × 2 pN/RPM × 转速 × 气压） |
| `setInverted(enabled)` → bool | **出力反转（推力 ⇄ 吸力）**：`true` ⇒ 同一转速/出力系数下力沿轴的**符号整体翻过来**（原推力变吸力，负转速的吸力也反向）。**瞬间生效、不做渐变**（用户定稿 2026-11）；只翻符号、不动幅值与指向（大小仍由转速/气压/出力系数决定，轴朝哪仍由偏转决定）；粒子链路同步换向（**排气 ⇄ 进气**，气流永远与受力反向，见 §6.2 的 `thrustDirectionSign`）；状态进 NBT / 蓝图 |
| `isInverted()` → bool | 出力是否已反转（`true` = 推力变吸力方向） |
| `getChannel()` → int | 本喷嘴在物理体内的频道（-1 = 不在物理体上 / 未分配） |

用法：

```lua
local ss = require("ccpe.sensor_system")
local n  = ss.getPeripheral(0)        -- 频道 0 的喷嘴（物理体内自动分配的号）
print(n.getChannel(), n.getMaxTilt()) -- 0  15
n.setDeflection(90, 15)               -- 极坐标写法：方位 90° + 锥角 15°（渐进去，约 0.19s）
n.setThrustScale(0.5)                 -- 出力减半（粒子不变，同样渐变）
print(n.getDeflection().tilt, n.getThrustScale())

-- 出力反转（推力 ⇄ 吸力）：瞬间生效，粒子同步从排气切进气
n.setInverted(true)                   -- 力沿轴反向（原来的推力变成吸力）
print(n.isInverted())                 -- true
n.setInverted(false)                  -- 恢复正方向

-- 等它转到位
while n.isMoving() do sleep(0.05) end
print(n.getDeflection().azimuth, n.getDeflection().targetAzimuth) -- 两个相等

-- 摇杆写法：方向 = 方位、长度 = 锥角（走直线）
n.setDirection(1, 0)                  -- 朝模型 +X 满锥角（= setDeflection(0, 15)）
n.setDirection(0, -1)                 -- 朝模型 −Z 满锥角（= setDeflection(270, 15)）
n.setDirection(0.5, 0)                -- 朝 +X、半锥角 7.5°
n.setDirection(0, 0)                  -- 回直出
```

### 6.2 父类的两个钩子（**定向喷管行为不变**）

在 `ThrustNozzleBlockEntity` 新增：

| 钩子 | 默认行为 | 作用 |
|---|---|---|
| `protected Vec3 nozzleAxis(BlockState)` | 返回 FACING 法线 | 推力（`sable$physicsTick` 冲量）与**全部粒子链路**（喷口位置、粒子基向量、遮挡射线、进气漏斗、尾迹锚点）的唯一方向来源 |
| `protected double thrustScale()` | 返回 1.0 | 乘在「转速 × 气压」上的额外出力系数（矢量喷管返回 `Lua 系数 × 2`，见 §6.1） |
| `protected double thrustDirectionSign()` | 返回 `+1.0` | 出力方向系数（`-1` = 反向）：**同时**乘进 `sable$physicsTick` 的 `magnitude` 与 `tickClient` 的 `thrustSign` ⇒ 力反向的同一刻排气/进气自动互换（漏掉 `tickClient` 那处就会出现"排气跟推力同向"）；矢量喷管 Lua `setInverted` 返回 ±1 |

矢量喷管覆盖它们（`nozzleAxis` 返回倾斜轴转到 plot 系、`thrustScale` 返回 Lua 设的值、
`thrustDirectionSign` 返回 `setInverted` 的 ±1）⇒ **一处改、全链路生效**。

### 6.3 三个几何/数学要点

1. **模型空间 → plot 系的旋转**（最容易搞错）：vanilla blockstate 的模型旋转是
   **`R = Ry(−y) · Rx(−x)`**（`new Quaternionf().rotationYXZ(rad(−y), rad(−x), 0)`），
   六个朝向的 (x,y) 与 `blockstates/advanced_thrust_nozzle.json` 逐条对应：
   up (0,0)、down (180,0)、north (90,0)、east (90,90)、south (90,180)、west (90,270)。
   顺序用错会表现为"倾斜方向对、但推力朝反方向 / 转 90°"。
2. **为什么锥角钳 15°**（2026-11 第三轮按下表重新核算，枢轴 y=5、底板 y0..4）：
   - **裙边下沉进底板**：半径 5px × sin15° ≈ 1.3px ⇒ 最低到 y0.27px，仍在方块内且埋在底板里（看不见）；
   - **十字臂伸出方块四边**：1.80px（臂外端本来就顶在方块面上，只能减小不能消除，见 §12.6）；
   - **顶环超出方块顶**：3.39px（其中 2px 是模型本身 y14..18 就超了，跟锥角无关）；
   - **活塞杆**：滑移被 `PISTON_TOP_LIMIT` 夹住，杆顶 ≤ 12.9px、杆底留在活塞头里（§4.1 表）。
   想更保守就把 `MAX_TILT_DEG` 调小（侧向溢出近似线性：10° ⇒ 1.27px、12° ⇒ 1.49px）。
3. **偏转点 {@code (x, y)} 才是状态，方位角/锥角只是它的极坐标读法**（2026-11 改版，用户定稿）：
   状态是偏转平面上的一个点，`长度 = 锥角`、`方向 = 方位角`（`(锥角·cos 方位, 锥角·sin 方位)`），
   方向合成仍是球坐标 `(sin r/r · x, cos r, sin r/r · y)`（`tiltDirModel`，`r` = 锥角）。
   **插值发生在偏转平面上**（`stepDeflectionTowardTarget`：把剩余距离缩到步长后按比例分摊到 x/y）
   ⇒ 轴端**走直线**。两种 Lua 写法都只是"怎么算目标点"的区别（§6.1）。
   - 旧版（v1..v8）是「方位角 + 锥角」两条极坐标轴**各自恒速推进** ⇒ 只改方位角时锥角不变 ⇒
     轴端在锥面上画圆弧，`(90,15)→(-90,15)` 实测就是**半圆**（用户第一版反馈的现象）。
   - 现行：同样两段命令 ⇒ 弦长 30° 的直线，中途穿过中心（锥角降到 0 再抬起），
     耗时 `30 / 160°/s = 0.19s`（旧圆弧 180° 是 0.75s）。
   - ⚠️ 走直线穿过中心时**方位角会瞬间反向**（中心处方位无意义、报 0），这是正常现象，不是跳变 bug。

### 6.4 注册与同步

- `compat/cc/CCPeripheralCapabilities` 加一条 capability → `getPeripheral()`（懒加载内嵌 `Peripheral`）。
- `compat/cc/SensorSystemAPI#getPeripheral(ch)` 加喷嘴分支（链接器 → 控制台 → **喷嘴**，三者同一频道空间）。
- `DefX` / `DefY` / `TargetDefX` / `TargetDefY` / `ThrustScale` / `TargetThrustScale` / `Inverted` 进 `saveAdditional`；
  **七个都要进 `getUpdateTag`**（客户端 BER 与粒子要读：前四个决定倾斜姿态，`ThrustScale` 那对决定粒子强度（见 §14），
  `Inverted` 决定客户端走排气还是进气链路）。
  旧键 `AzimuthDeg` / `TiltDeg` / `TargetAzimuthDeg` / `TargetTiltDeg`
  **只在 `loadAdditional` 里兼容读取**（旧存档换算成偏转点），不再写出。
- **不在 tick 里广播**（v8 定稿，见 §6.5）：`requestDeflection` / `requestDirection` / `requestThrustScale` 一次命令只
  `syncToClient()` 一次（客户端靠"目标值"自己推进动画），`tickMotion()` 只 `setChanged()` 落盘。

### 6.5 渐变到位：视觉偏转点 = **一个朝 target 走的点**（每次渲染用墙钟差值推进一步）

> 状态：**v8 落地并经用户实测确认（"偏转动画平滑了"）；v9 改为偏转点，用户实测"完全符合预期"**。

**八版演进（别再走回头路）**

| 版本 | 动画时钟 / 做法 | 结果 |
|---|---|---|
| v1 | 服务端梯形曲线 + 客户端 `partialTicks` 在 prev→current 插值 | ❌ "看起来有点怪" |
| v2 | 追**同步来的当前角**（`JoystickTilt#approach`）+ 每 tick 广播 | ❌ "卡卡的" |
| v3 | 渲染里按**帧时间**追目标角 | ❌ 仍"卡卡的" |
| v4 | 客户端 **BE tick** 推 prev/current + `Mth.lerp(partialTicks, …)`（照 `create-propulsion-simulated`） | ❌ "完全没有动画，直接瞬移" |
| v5 | `AnimationTickHolder.getRenderTime` 当纯函数时钟 | ❌ 仍"瞬间到达" |
| v6 | `System.nanoTime` 墙钟当纯函数时钟 + `(from, to, start)` 三元组 | ❌ 实测 `out` 恒为 `(90,15)`：包正常到、`target` 正常在变，但 `animTo` 自第一包后不再更新 ⇒ `from == to` ⇒ 永远不动 |
| v7 | **视觉角只是一个数**：`visual += sign(target−visual) × 速度 × Δt` | ⚠️ 动起来了，但仍"瞬间跳到目标" |
| v8 ✅ | v7 + **`loadAdditional` 不再重置视觉状态** + **目标角也进 NBT** | ✅ 用户实测"偏转动画平滑了" |

**v7 为什么还是瞬间跳（真正的根因，最值得记住的一条）**
物理体（Sable 子次元）会**周期性重载方块实体数据**（`loadAdditional` 反复跑，不是每 tick 但很频繁）。v7 在
`loadAdditional` 末尾写了 `visualInit = false` ⇒ **每次重载后第一帧都把视觉角吸附到服务端当前角**，
"渐变"当场退化成"跳转"；同时因为只存了当前角、没存目标角，重载后 `target` 被重置成 `value`
（`Axis.reset`），客户端连"终点"都丢了 ⇒ 只能一格一格跟服务端的 20Hz 步进。

⇒ **两条铁律**：
1. **不要在任何"重载/同步"路径里重置动画状态**（`loadAdditional` / `handleUpdateTag` 都不许碰）；
2. **动画需要的两个端都要能持久化**（`value` + `target` 一起进 NBT），否则重载后只剩"半步"信息。

**教训合集（每一版都死在"时钟"或"存下来的状态"上）**
1. 不能用同步包频率（v2）；
2. 不能用渲染调用频率（v3）；
3. 不能用客户端 BE 的 tick 计数（v4，物理体/子次元上不可靠 —— 用户提示"monitor 的 knob/joystick 也踩过"）；
4. 也不能在渲染路径上用 `level.isClientSide` 做提前返回（v5：物理体/子次元上的侧标记不可靠，
   一旦判成服务端就返回真值 ⇒ 直接在视觉上"瞬移"到目标）；
5. **不要存 `(起点, 终点, 起始时刻)` 三元组**（v6）：一旦其中一步没更新就永久卡死，且**没有自愈路径**；
   视觉角只保留"一个数 + 直接读 target"，就没有可失效的中间状态；
6. **Sable 物理体会重载 BE**（v7→v8）：重载路径不得重置动画状态，且 `value`/`target` 都要落盘。

**v9 的状态与算法**（2026-11 改成偏转点，v8 的"每轴一个数"升级成"平面一个点"）

| 项 | 做法 |
|---|---|
| 状态 | `defX`/`defY`（`Axis`：服务端 `value`+`target`）、`visualDefX`/`visualDefY`/`visualLastNanos`/`visualInit` |
| 推进 | `advanceVisualAnimation()`：`Δt = nanoTime − visualLastNanos`（单次上限 0.25s），把视觉点**沿直线**朝目标推进 `速度×Δt`（`stepDeflectionTowardTarget` 同款二维步进） |
| 频率无关 | `Δt` 是**差值** ⇒ 一帧内渲染被调用两次（多 pass）、或帧率高低，积分都正确 |
| 首次 | `visualInit`：只有**真正的新实例**第一次渲染时落在同步来的当前点（重载不重置） |
| 同步包 | `handleUpdateTag` 只 `setSynced(value, target)`，**完全不碰视觉点** |
| 落盘 | `value` **和** `target` 都进 `saveAdditional`（重载/读档后动画不会丢终点） |
| 调试 | `DEBUG_ANIM_LOG`（默认 `false`，`static final` ⇒ printf 被 javac 消除、零开销）：打开后打印 `[ATN-anim]`（每 20 次渲染）/`[ATN-pkt ]`/`[ATN-load]`，均带 `inst=` 实例哈希 |

| 参数 | 当前值（实测确认的观感） | 耗时 |
|---|---|---|
| `DEFLECTION_DEG_PER_SECOND` | **160°/s**（偏转平面里的**直线**速度，2026-11 用户二次提速，旧值 80） | 中心→满锥角 15° = 0.09s；反向全行程（弦 30°）= 0.19s；垂直换向 90°（弦 21.2°）= 0.13s |
| `THRUST_OUTPUT_MULTIPLIER` | **2.0**（矢量喷管满出力 2 pN/RPM；2026-11 用户定稿，**定向喷管不动**） | 气压 1.0、256 RPM ⇒ 512 pN/喷管（原先 256） |
| `THRUST_SCALE_PER_TICK` | 0.25/tick | 出力 0→1 = 0.2s |

> ⚠️ 旧版有 `AZIMUTH_DEG_PER_SECOND`（240）/`TILT_DEG_PER_SECOND`（80）两条极坐标速度；改成偏转点后
> **合并成一条平面线速度**，服务端每 tick 步长由 `/20` 自动得出（当前 160°/s ⇒ 8°/tick）。
> 想要更快的手感就调这一个常量；它同时被**服务端 tick（真实推力方向）**和**客户端视觉推进**读取，一改两边同步。

**要点**

| 项 | 做法 |
|---|---|
| 轴 | `Axis` 只有 `value`（服务端真值 / 客户端同步值）+ `target`；偏转用**两个**轴（`defX`/`defY`） |
| Lua 写什么 | `requestDeflection` / `requestDirection` / `requestThrustScale` **只写 target**；偏转目标点长度恒 ≤ 15°（圆盘是凸的 ⇒ 直线插值不会跑出锥角范围） |
| 中途保存 | `value` + `target` 都落盘 ⇒ 读档会**继续走完**剩余行程（不是停在半路） |
| 调试日志 | 渲染线程上的同步 stdout 会掉帧 ⇒ `DEBUG_ANIM_LOG` 默认关闭，只在排查时临时打开 |

---

## 7. 文件清单

### Java

| 文件 | 职责 |
|---|---|
| `block/AdvancedThrustNozzleBlock.java` | `extends ThrustNozzleBlock`：**两个盒的选择框/碰撞箱（六向由模型旋转生成）**、自己的 BE 类型、`getTicker`（客户端粒子 / 服务端频道复核）、空手右键开 GUI |
| `block/AdvancedThrustNozzleBlockEntity.java` | 频道状态机 + 图标 NBT/同步 + 偏转点 / 出力系数 + 内嵌 CC:T `Peripheral` + `nozzleAxis`/`thrustScale` 覆盖（含 2× 出力倍率） |
| `block/AdvancedThrustNozzleRenderer.java` | 只走 BER：block 部分（绕枢轴倾斜）+ 4 活塞杆（头并进 `base.json`，这里不画） |
| `screen/AdvancedThrustNozzleMenu.java` / `…Screen.java` | 频道 GUI |
| `network/AdvancedThrustNozzleConfigPayload.java` / `…PacketHandlers.java` | 频道保存 |
| `block/ThrustNozzleBlock.java`（微改） | 抽 `createBlockEntity` / `entityType()` / `shapeFor(Direction)` 三个钩子 |
| `block/ThrustNozzleBlockEntity.java`（微改） | 加 `protected (BlockEntityType, BlockPos, BlockState)` 构造器 + `nozzleAxis` / `thrustScale` 钩子 |
| `block/MyModPartialModels.java` | +2 PartialModel：`ADVANCED_THRUST_NOZZLE_{BLOCK,PISTON_POLE}`（`PISTON_HEAD` 已随 `piston_head.json` 删除，**别再注册**：缺模型会在烘焙时报错） |
| 注册 | `MyModBlocks` / `MyModBlockEntities` / `MyModCreativeModeTabs` / `MyModMenus` / `ModPackets` / `CCPeripheralExtenderClient`（BER + Screen）/ `CCPeripheralCapabilities` / `SensorSystemAPI` |

### 资源

`blockstates/advanced_thrust_nozzle.json`（**只指到 `base`**，六变体同 thrust_nozzle）、
`models/item/advanced_thrust_nozzle.json`、loot_table、physics_block_properties（mass/volume 0.5/0.5）、
recipe、lang（`block.ccpe.advanced_thrust_nozzle*` + `gui.ccpe.advanced_thrust_nozzle.require_body`）。

---

## 8. 已知边界 / 未做

- **偏转速度写死**（偏转平面线速度 160°/s、出力 0.25/tick，**无加减速**），Lua 不可配（用户定稿）。
- **出力的两倍是矢量喷管专属**（`THRUST_OUTPUT_MULTIPLIER`）：定向喷管 `ccpe:thrust_nozzle` 仍是 1 pN/RPM，
  两种喷管同机混装时"同转速不同推力"是预期差异，不是 bug。
- 渐变中**中途保存会连目标一起落盘** ⇒ 读档后会继续转完剩余行程（不是停在半路）。
- **出力系数影响排气 / 进气 / 高空尾迹的发射密度**（§14、§16）：尾迹仍按移动位移与高度发射，
  但间距再按有效油门放大 ⇒ 出力 0 时"没有排气、也没有尾迹"（2026-11 第六轮定稿；
  此前"系数 0 仍有尾迹"是刻意解耦时的行为，已被推翻）。尾迹**寿命**仍只由高度适宜度决定。
- 推力**不做遮挡判定**（与定向喷管一致：喷口被堵照样出力）。
- 蓝图部署后**频道会保留**（§15 已修）；偏转/出力与频道一起进蓝图。
- 没有"实际出力 pN"读数（需要调试时加 `getThrust()`）。
- `getDeflection` 给角度 + 偏转点分量，但**不给世界系方向向量**（需要时可以加）。
- 偏转点走直线时，**穿过中心那一刻方位角瞬间反向**（中心处方位无意义、报 0）；脚本若用方位角做反馈要避开这种中途采样。
- 摇杆 `setDirection` 的**长度**不含死区/曲线映射，线性映射到锥角（要遥杆手感曲线就在 Lua 侧处理）。
- 选择框 / 碰撞箱是**静止姿态的最大包络**（`(0,0,0,16,4,16)` + `(2,4,2,14,16,14)`），**不跟倾斜/活塞动画变**：
  15° 锥角下喷口横向只偏 ≈1.5px，包络能盖住；要严格贴合得做逐帧形状（不划算）。
- 顶环在模型空间到 `y=18px`（伸出方块上表面 2px，用户模型如此），碰撞箱仍封顶在 16px ⇒ 视觉顶端会略微超出碰撞体。
- 活塞机构只有 4 个方位（`rotateY(-90°·i)`），不支持任意角度的活塞数量。

---

## 9. 验证记录

| 项 | 结论 |
|---|---|
| block 部分绕枢轴转、六向 FACING 都对 | ✅ 用户实测"转动正常"（枢轴历史：`(8,1,8)` → `(8,2,8)` → **`(8,5,8)` 定稿**） |
| 活塞机构（头固定 + 杆绕铰点转） | ❌ v3：换新模型后暴露出 ≈1px 横向乱飘（§4.1/§12.5）⇒ 改成 v5 |
| 活塞机构 v5（头并进 base + 杆 = body 子件、只沿 body 轴滑动） | ✅ **用户实测"只有上下的相对移动了，符合我的需求"**（2026-11 第二轮） |
| 模型重做：头并进 `base.json`、杆上移到 `y5..12`、夹持层 `y5` | ✅ 用户实测通过（2026-11 第三轮确认"完全符合我的需求"） |
| 活塞杆底座端（枢轴 y2→**y5**：杆底径向漂移 ±0.7px → **±0.11px**，臂侧向溢出 2.57 → 1.80px） | ✅ **用户实测通过**；离线核算表见 §12.6 |
| 选择框/碰撞箱 `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)`（六向按模型旋转生成） | ✅ 用户实测通过（2026-11 第三轮） |
| 最大锥角时仍有轻微越界（十字臂伸出方块四边 1.80px、顶环超出方块顶 3.39px） | ⚠️ 用户已接受（几何必然：臂外端顶在方块面上、顶环本身做到 y18；要改只能动 `MAX_TILT_DEG` 或模型） |
| 整块选择框 / 碰撞箱 | 📜 历史：阶段一用整块正方形，2026-11 已换成上面两个盒 |
| 频道自动分配 / 只在物理体 / GUI | ✅ 用户实测"这一步没问题" |
| CC:T 外设偏转 + 出力 | ✅ 用户实测"没问题" |
| 偏转渐变（视觉偏转点 = 朝 target 走的点，`nanoTime` 差值推进） | ✅ **用户实测"偏转动画平滑了"**（v8：`loadAdditional` 不再重置视觉状态 + 目标角落盘） |
| 偏转轨迹是锥面圆弧（`(90,15)→(-90,15)` 走半圆） | 📜 历史：v8 及之前是极坐标两轴独立推进导致的**预期行为**；**v9 已改成偏转平面直线插值，不再走弧**（见 §6.3 第 3 条） |
| 偏转提速 2×（方位 120→**240**°/s、锥角 40→**80**°/s，动画与真实推力同源同时变快） | ⚠️ 已被 v9 的单一平面线速度 80°/s 取代（见上表） |
| v9：`setDirection(x, y)` 摇杆写法 + 全链路直线插值 | ✅ **用户实测"完全符合预期"**（2026-11） |
| 偏转再提速 80→**160**°/s（平面线速度，动画与真实推力同源） | ✅ 已落地（用户反馈"功能基本齐全"；手感若还要调只改这一个常量） |
| 矢量喷管出力翻倍（`THRUST_OUTPUT_MULTIPLIER = 2.0` ⇒ 2 pN/RPM；定向喷管不变） | ✅ 已落地（同上；要校数值只改这一个常量） |
| 出力系数同时缩放排气 / 进气粒子（有效油门 = 转速 × 系数，客户端本地补同一条渐变，§14） | ✅ 已实现；用户实测 `setThrustScale(0)` 时排气/进气不出（本轮的 bug 报告即由此而来） |
| 出力系数同时缩放**尾迹发射密度**（`setThrustScale(0)` 时高空不出尾迹，§16） | ⏳ 已实现、`gradlew classes` 通过，**待用户进游戏实测** |

---

## 10. 参考来源（均只读）

| 参考 | 借鉴点 |
|---|---|
| `references/Simulated-Project-main/aeronautics/.../gyroscopic_propeller_bearing/{GyroscopicPropellerBearingRenderer,GyroscopicPropellerBearingVisual,GyroscopicPropellerBearingBlockEntity}.java` | 枢轴倾斜手法（`translate(+pivot) → rotateCentered → translate(−pivot)`）、四活塞位置/朝向、`tiltQuat` + previous/插值、锥角钳位思路 |
| `references/create-propulsion-simulated-main/.../vector_thruster/{VectorThrusterBlockEntity.java,VectorThrusterRenderer.java}` | 偏转动画的 prev/current + `Mth.lerp(partialTick, …)` 骨架、只在命令时 `notifyUpdate`（**但客户端 tick 驱动在 Sable 物理体上不可靠，最终没采用，见 §6.5**） |
| 同目录 `piston_head.json` / `piston_pole.json` | 与本方块的活塞杆 partial 几何逐位相同（**但活塞自由度是镜像的，只能参考变换链手法**；本方块已删掉 `piston_head.json`，头并进 `base.json`，见 §4.1） |
| `block/ServoBearingBlockEntity.java` 内嵌 `Peripheral` + `block/ServoBearingRenderer.java` | CC:T 外设写法（`getType`/`equals`/`@LuaFunction(mainThread=true)`/懒加载）与六向 FACING 渲染链 |
| `block/ShortRangeLinkerBlock.java` / `…BlockEntity.java` / `screen/ShortRangeLinker*` / `network/ShortRangeLinker*` | 频道注册生命周期、GUI（滚轮 + 非物理体提示 + tooltip 前景层）、payload 处理器 |
| `compat/cc/ShortRangeLinkerRegistry.java` | **物理体作用域频道表**（自动分配 / 顺延 / 链聚合 / 非物理体返回 -1） |
| `block/MyBearingPlateRenderer.java` | "只走 BER、不注册 Visual"的合法范例 |
| `memo/propulsion/thrust-nozzle.md` | 母文档：推力/气压/粒子/尾迹/染料/扳手全部继承自它 |

---

## 11. 变更记录（2026-11 本轮）

> 本节只记「这一轮做了什么、为什么」，技术细节都在上面各节。

### 11.1 时间线

| # | 用户诉求 | 调查结论 | 改动 |
|---|---|---|---|
| 1 | 「喷口路径走的是一个**半圆**，是预期的吗？」 | **是**：旧版状态是「方位角 + 锥角」两条极坐标轴，各自恒速独立推进 ⇒ 只改方位角时锥角不变 ⇒ 轴端在 15° 圆锥面上画弧。用户当时选择「先就这样」 | 无代码改动，只在 §6.3 第 3 条 + §9 记为"预期行为" |
| 2 | 「偏转要**快一点**（动画和真实推力都要快）」 | 速度只由两个常量决定，且服务端 ticker 与客户端视觉读同一份 | 方位 120→**240**°/s、锥角 40→**80**°/s（用户选 2×） |
| 3 | 「加一个方法：输入 `x, y`（-1..1），方向控方位、**长度映射到圆形上**控锥角，用来**走直线**」 | 走直线必须让插值发生在**偏转平面**上（极坐标插值永远是弧）；两条命令必须共用同一套插值语义，否则视图与推力会脱节 | **v9 重构**：状态从两条极坐标轴换成**偏转点 `(x, y)`**（§6.3 第 3 条）；新增 Lua `setDirection(x, y)`（§6.1）；`setDeflection` 也改走直线；速度合并成单一平面线速度 **80°/s**；NBT 键改 `DefX/DefY/...` 并保留旧键兼容读取 |
| 4 | 实测："完全符合预期" | — | §9 记 ✅ |
| 5 | 「偏转**再快一点**」+「出力改为现在的**两倍（2*RPM）**」 | 出力倍率只需乘在子类 `thrustScale()` 上；用户选择"只翻倍矢量喷管"，避免动已标定的定向喷管 | 平面线速度 80→**160**°/s；新增 `THRUST_OUTPUT_MULTIPLIER = 2.0`（2 pN/RPM） |

### 11.2 本轮触碰的文件

| 文件 | 改了什么 |
|---|---|
| `src/main/java/com/zzy205/myfirstmod/block/AdvancedThrustNozzleBlockEntity.java` | **主战场**：偏转点状态（`defX`/`defY` + 视觉 `visualDefX`/`visualDefY`）、`requestDirection`、`stepDeflectionTowardTarget`（二维直线恒速）、`advanceVisualAnimation`（同款二维步进）、`tiltDirModel(x,y)` 新签名、`getDeflection` 增 `x/y/targetX/targetY`、`setDirection` Lua 方法、NBT/同步键迁移 + 旧键兼容、`THRUST_OUTPUT_MULTIPLIER` |
| `memo/propulsion/advanced-thrust-nozzle.md` | 本轮全部结论（§1/§6.1/§6.3/§6.4/§6.5/§8/§9/§10 + 本节） |
| `memo/propulsion/thrust-nozzle.md` | §8 加一条「两种喷管出力口径不同」的警告（定向 1 pN/RPM vs 矢量 2 pN/RPM），避免以后混装时误判为 bug |

> 用户测试脚本（`run/saves/.../computer/2/startup.lua`）属按规范**只读**目录，本轮未改；测试用的
> `setDirection` 片段见 §6.1 用法示例。

### 11.3 若后续要微调，只动两个常量

| 想改的东西 | 常量 | 现值 |
|---|---|---|
| 偏转快慢（动画 + 真实推力同时） | `DEFLECTION_DEG_PER_SECOND` | 160°/s（= 8°/tick） |
| 矢量喷管出力 | `THRUST_OUTPUT_MULTIPLIER` | 2.0（2 pN/RPM） |

⚠️ 不要改回去的坑（都在 §6.5 有记录）：别用同步包/tick 计数/渲染调用频率当动画时钟；别在
`loadAdditional` / `handleUpdateTag` 里重置视觉状态；`value` 与 `target` 必须一起落盘。

---

## 12. 变更记录（2026-11 第二轮：用户重做模型）

### 12.1 用户诉求

> 「我重新制作了模型……去掉了 `piston_head`，整合进了 base 中，因为活塞头本来就是固定在底座上的，
> 与底座没有相对移动；而且我调整了活塞杆的初始位置（`item.json` 中）；并且选择框/碰撞箱需要改为
> `[0,0,0,16,4,16]` + `[2,4,2,14,16,14]`（不需要随着动画改变）。」

### 12.2 模型的新坐标（从 `item.json` 读出来的）

| 部件 | 旧 | 新 |
|---|---|---|
| 底板（`base.json`） | `x2..14, y0..2, z2..14` | **`x0..16, y0..4, z0..16`** |
| 活塞头 | 独立 `piston_head.json`，半径 6..8、`y0..2` | **并进 `base.json`**，半径 6..8、**`y4..6`** |
| 活塞杆 | `y1..8` | **`y5..12`**（`piston_pole.json` 本身没变） |
| 可动 block | 裙边 `y1..5`、筒身 `y4..13`、臂 `y6..10`、顶环 `y13..16` | 裙边 `y2..6`、筒身 `y6..14`、臂 `y9..13`、顶环 `y14..18` |
| 枢轴 | `(8,1,8)` | **`(8,2,8)`**（底板中面 = 裙边下沿） |
| 铰点 | 半径 6.5、`y=1` | 半径 6.5、**`y=5`** |

### 12.3 代码改动

| 文件 | 改了什么 |
|---|---|
| `block/AdvancedThrustNozzleBlock.java` | 选择框/碰撞箱：整块正方形 → **`(0,0,0,16,4,16)` + `(2,4,2,14,16,14)`**；盒子不再对称 ⇒ 新增 `buildShapes()`（复用父类 `rotateX`/`rotateY`）生成六向盒，`shapeFor` 查表（与 blockstate 的 x/y 旋转严格对应） |
| `block/AdvancedThrustNozzleRenderer.java` | `PIVOT` y `1/16`→**`2/16`**；杆改成 **body 的子件**（`rotateAround(tilt, PIVOT)` + 径向钉死 + 沿 body 轴滑移，滑移量 `pistonSlideAlongBodyAxis` 反解 + `PISTON_TOP_LIMIT` 夹取）；**删掉活塞头的渲染**（头在 `base.json` 里，由 blockstate 静态画） |
| `block/MyModPartialModels.java` | 删掉 `ADVANCED_THRUST_NOZZLE_PISTON_HEAD`（`piston_head.json` 已删，注册了会在模型烘焙时报缺文件） |
| `assets/ccpe/models/block/advanced_thrust_nozzle/piston_head.json` | （用户删除） |

### 12.4 以后调模型时要同步的常量

| 模型里的东西 | 代码常量 |
|---|---|
| 杆底面高度（px） | `AdvancedThrustNozzleRenderer.PISTON_HINGE_HEIGHT`（现 5/16） |
| 杆的径向中心（px） | `AdvancedThrustNozzleRenderer.PISTON_HINGE_RADIUS`（现 6.5/16） |
| 杆长（px） | `AdvancedThrustNozzleRenderer.PISTON_LENGTH`（现 7/16，只用于顶部夹取） |
| 十字臂带上沿（px） | `AdvancedThrustNozzleRenderer.PISTON_TOP_LIMIT`（现 12.9/16 = 上沿 13 留 0.1 防共面） |
| 倾斜枢轴（px） | `AdvancedThrustNozzleRenderer.PIVOT`（**现 (8,5,8)/16**，理由见 §12.6） |
| 底板/喷口包络（px） | `AdvancedThrustNozzleBlock.BASE_SHAPE`（现 `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)`） |

### 12.5 第二轮追加（活塞杆动画"乱飘"）

用户反馈："活塞管与 body 之间不止有上下相对位移，还有其他位移或者旋转；活塞管与底座的连接没问题"。

| 项 | 结论 |
|---|---|
| 根因 | v3/v4 的杆绕**铰点**转、body 绕**枢轴**转 ⇒ 相对位移 `(I−R)(H−P)` 混进 ≈1px 横向分量（杆才 1px 粗、臂槽余量 1px） |
| 改法 | 杆改成 **body 子件**：`rotateAround(tilt, PIVOT)` 与 body 完全同源 + 径向钉死槽心 6.5px + 沿 body +Y 滑移；滑移量由"杆底卡在夹持高度 y=5"反解（= 参考实现 `translateDistance` 的镜像） |
| 副作用处理 | 朝本活塞侧满锥角时滑移会把杆顶顶出臂带上沿 ⇒ 用 `PISTON_TOP_LIMIT`（12.9px）夹住，杆底沉进活塞头内部看不见 |
| 文件 | `block/AdvancedThrustNozzleRenderer.java`（唯一改动）、本 memo |

### 12.6 第三轮：枢轴高度（活塞杆底座端 / body 越界）

用户问三件事：杆连底座的铰点在哪、body 的旋转中心在哪、抬旋转中心能不能缓解最大角的穿模。

| 问题 | 答案 |
|---|---|
| body 旋转中心 | `PIVOT = (8, P, 8)px`，**2026-11 第三轮从 y=2 抬到 y=5**（= 四活塞活塞头 y4..6px 的中面） |
| 杆的"铰点" | **没有固定铰点**：杆是 body 的子件，横向钉死在 body 系的半径 6.5px，只有 1 个沿 body 轴的滑动自由度 ⇒ 在**底座坐标系**里它的底面在一个小范围里"蹭"（见下表）。这是几何上的必然：`杆底钉死` 与 `相对 body 只有上下位移` 两条不能同时严格成立 |
| 抬枢轴能不能缓解穿模 | **能，部分缓解**（下表）。十字臂外端本来就顶在方块四边的 x/z=0/16px，转多少就溢多少，**只能减小、不能消除**；顶环顶上的溢出跟枢轴无关（模型本身 y14..18 就已超出方块顶 2px） |

**离线核算（tilt=15°，单位 px；正数 = 超出方块边界）**

| 枢轴 y | 侧面溢出(臂) | 顶上溢出(顶环) | 裙边最低 y | 杆底世界径向 | 杆底世界 y |
|---|---|---|---|---|---|
| 0 | 3.09 | 3.22 | 0.10 | 5.39..7.83 | 4.11..5.00 |
| **2（第二轮用的）** | **2.57** | 3.28 | 0.17 | **5.93..7.31** | 4.08..5.00 |
| 4 | 2.06 | 3.35 | 0.24 | 6.46..6.80 | 4.25..5.00 |
| **5（现用）** | **1.80** | 3.39 | 0.27 | **6.51..6.73** | 4.19..5.00 |
| 6 | 1.54 | 3.42 | 0.31 | 6.28..7.00 | 4.32..5.00 |
| 8 | 1.02 | 3.49 | 0.37 | 5.76..7.53 | 4.39..5.00 |

⇒ **y=5 是唯一双赢点**：里面那一列（杆底径向漂移）从 ±0.7px 收到 **±0.11px** ⇒ 底座那一端看起来就是个铰点
（正是用户抱怨"连接底座的转动有些奇怪"的解法）；同时臂的侧向溢出从 2.57px 减到 1.80px（−30%）。
y=8 侧向溢出更小（1.02px），但杆底漂移反而涨到 ±0.9px，不划算。

**锥角与侧向溢出的关系（侧向溢出 ≈ 线性于锥角）**

| 锥角 | 枢轴 y=5 |
|---|---|
| 10° | 1.27px |
| 12° | 1.49px |
| 15° | 1.80px |

> 若要进一步压穿模，剩下三条路（都要用户拍板）：① 把 `AdvancedThrustNozzleBlockEntity.MAX_TILT_DEG` 从 15 调到 12/10；
> ② 模型里把十字臂外端从 x/z=0/16px 收进 1~2px；③ 顶环从 y18 收回到 y16（静止超顶 2px 是它造成的，
> 与倾斜无关）。三条 memo 不擅自改。

---

## 13. 交付状态（2026-11 定稿，用户实测"完全符合我的需求"）

### 13.1 一句话状态

矢量喷管功能闭环：**能倾斜、能随动、能寻址、能 Lua 控制、动画平滑**，几何、渲染、物理、外设四条链路
的常量都集中在两处（`AdvancedThrustNozzleRenderer` 的几何常量 + `AdvancedThrustNozzleBlockEntity` 的手感常量），
改模型/调手感都不需要动逻辑。

### 13.2 可调常量一览（想调什么改哪里）

| 想改的东西 | 文件 / 常量 | 现值 |
|---|---|---|
| 偏转快慢（动画 + 真实推力同时） | `AdvancedThrustNozzleBlockEntity.DEFLECTION_DEG_PER_SECOND` | 160°/s（= 8°/tick） |
| 矢量喷管出力 | `AdvancedThrustNozzleBlockEntity.THRUST_OUTPUT_MULTIPLIER` | 2.0D（2 pN/RPM） |
| 出力系数渐变速度 | `AdvancedThrustNozzleBlockEntity.THRUST_SCALE_PER_TICK` | 0.25f/tick |
| 最大锥角 | `AdvancedThrustNozzleBlockEntity.MAX_TILT_DEG` | 15° |
| 倾斜枢轴 | `AdvancedThrustNozzleRenderer.PIVOT` | (8,5,8)px |
| 杆径向 / 夹持层 / 杆长 / 杆顶上限 | `PISTON_HINGE_RADIUS` / `PISTON_HINGE_HEIGHT` / `PISTON_LENGTH` / `PISTON_TOP_LIMIT` | 6.5 / 5 / 7 / 12.9 px |
| 选择框 / 碰撞箱 | `AdvancedThrustNozzleBlock.BASE_SHAPE` | `(0,0,0,16,4,16)` + `(2,4,2,14,16,14)` |

### 13.3 三轮改动汇总（本轮 2026-11 全部）

| 轮次 | 用户诉求 | 落地内容 |
|---|---|---|
| 一 | 偏转要走直线 / 更快 / 出力翻倍 | 状态从极坐标两轴换成**偏转点 `(x,y)`**（§6.3 第 3 条）+ Lua `setDirection` + 平面线速度 160°/s + `THRUST_OUTPUT_MULTIPLIER = 2.0`（§11） |
| 二 | 重做模型（头并进 base、杆上移）、选择框改两盒 | 删 `piston_head.json` 与对应 partial；枢轴 (8,1,8)→(8,2,8)；杆改"body 子件 + 轴向滑移"；选择框六向两盒（§12.1–12.5） |
| 三 | 杆与底座端的转动看着怪 / 最大角穿模 | 枢轴 (8,2,8)→**(8,5,8)**（杆底漂移 ±0.7→±0.11px、臂侧向溢出 −30%）；`PISTON_TOP_LIMIT` 12.9px 防共面；越界核算表（§12.6） |

### 13.4 明确"不做 / 已接受"的边界

- 最大锥角时十字臂仍伸出方块四边 **1.80px**、顶环仍超出方块顶 **3.39px**（几何必然，§12.6）——**用户已接受**。
- 杆底在活塞头里仍有 0.2×0.8px 的微观漂移（过约束的必然结果）——视觉上等同于铰点。
- 推力**不做遮挡判定**、蓝图部署后**频道重分配**、`getThrust` 读数未做（与定向喷管一致，见 §8）。

---

## 14. 变更记录（2026-11 第四轮：出力系数影响粒子）

### 14.1 用户诉求与定稿

> 「帮我改一下 `setThrustScale` 设定出力会影响产生的粒子。」

两个细节由用户在选项里定稿：

| 议题 | 结论 |
|---|---|
| 影响哪些粒子 | **只影响排气 + 进气**；**高空尾迹不动**（尾迹按位移与高度发射，与出力无关）<br>⚠️ 2026-11 第六轮已推翻这一条：尾迹的发射密度也要随出力，见 §16 |
| 怎么影响 | **与油门同一套口径**：把「有效油门 = 油门 × 出力系数」喂给现有粒子链路 ⇒ **数量、排气速度、进气漏斗长度一起缩**；系数 0 = 排气/进气完全不出 |

### 14.2 落地方式（一处乘法，不新增参数）

原实现只有 `throttle = |转速| / 256` 一个强度来源，`thrustScale()` 只在 `sable$physicsTick` 的推力公式里读过一次。

| 文件 | 改了什么 |
|---|---|
| `block/ThrustNozzleBlockEntity.java` | 新增钩子 `protected float particleIntensityScale()`（**默认 1.0 ⇒ 定向喷管行为不变**）；`tickClient` 里把它乘进油门得到 `particleThrottle`，再喂给 `tickIntake(..., particleThrottle)`、排气 `count`、`spawnExhaust(..., particleThrottle)`。~~`tickContrail` 不经过它 ⇒ 尾迹天然不受影响~~（第六轮已改：`tickContrail` 也读同一个 `particleThrottle`，见 §16） |
| `block/AdvancedThrustNozzleBlockEntity.java` | 覆盖钩子返回 `thrustScaleAxis.value`；`requestThrustScale` 从 `setChanged()` 改成 **`syncToClient()`**（客户端得知道新目标）；`ThrustScale` + 新增 `TargetThrustScale` 进 `saveAdditional` / `loadAdditional` / **`getUpdateTag` / `handleUpdateTag`** |

### 14.3 客户端怎么得到这个系数（关键）

同步包仍然**只在命令时发一次**（§6.5 的铁律：不逐 tick 广播），所以客户端拿到的是「命令那一刻的值 + 目标」，
而服务端那份 `value` 是 `tickMotion` 每 tick 推进的。若客户端直接用 `value`，粒子会在包到达时**瞬变**。

⇒ 覆盖的 `particleIntensityScale()` 在客户端**本地补一步**：`thrustScaleAxis.stepToward(THRUST_SCALE_PER_TICK)`
（0.25/tick，与服务端同一个常量、同一速度）⇒ 粒子与推力**同步在 0.2s 内平滑衰减**。
副作用：客户端这个字段不再严格等于服务端快照，但它只被粒子密度读，且物理体周期性重载（`loadAdditional`）会把
`value` 落回服务端真值 ⇒ 自愈。

### 14.4 效果对照

| 出力系数 | 推力 | 排气粒子 | 进气粒子 | 高空尾迹（§16 起） |
|---|---|---|---|---|
| 1.0 | 2 pN/RPM × 转速 × 气压 | 满密度 / 满速度 | 满密度 / 满漏斗 8 格 | 满密度（间距不变） |
| 0.5 | 减半 | 数量、速度 ≈ 转速减半的观感 | 数量减半 / 漏斗 4 格 | 间距翻倍 ⇒ 更稀（**寿命不变**） |
| 0 | **0** | **完全不出** | **完全不出** | **完全不出**（间距 → ∞） |

### 14.5 验证

`./gradlew.bat classes` ✅ 通过。**待用户实测**：`setThrustScale(0.5)` / `(0)` 时排气与进气应当明显变稀/停掉，
且与推力的渐变同步（约 0.2s）。~~高空尾迹应当照旧~~ → 已被 §16 推翻：尾迹也按同一系数缩密度。

---

## 15. 变更记录（2026-11 第五轮：蓝图部署保留频道）

### 15.1 根因（Create 源码事实）

Create 6.x 的 `BlockHelper.prepareBlockEntityData` 只在三种情况下把 BE 数据存进蓝图：
block 带 `create:safe_nbt` 标签、注册了 `SafeNbtWriter`、或者 **BE 实现了
`com.simibubi.create.api.schematic.nbt.PartialSafeNBT`** —— 否则**一个字段都不存**。

`ThrustNozzleBlockEntity extends BlockEntity`，没有实现它 ⇒ 蓝图里喷管没有任何数据
⇒ 部署后 `Channel`、偏转点、出力系数**全部丢失**，频道只能重新自动分配（正是 §8 那条"未补"）。
（§5.4 当年写的是"父类没实现 `SafeNbt.writeSafe`，要持久化就补"，本轮补上了。）

### 15.2 改动

| 文件 | 改了什么 |
|---|---|
| `block/AdvancedThrustNozzleBlockEntity.java` | `implements PartialSafeNBT` + 新增 `writeSafe(...)`：写 `Channel` + `DefX/DefY` + `TargetDefX/TargetDefY` + `ThrustScale` + `TargetThrustScale`（键名与 `saveAdditional` 完全一致，部署时走普通 `loadAdditional`，不需要额外的 read 路径）。**运行时快照一律不写**（`OccupiedChannels`、`onPhysicsBody`、视觉动画字段） |
| 同上 | `loadAdditional` 末尾（`level != null` 且服务端）立即 `registerChannel()`：修「部署时 `onLoad` 先于 `loadAdditional`、用默认频道登记」的时序问题（详见 `memo/peripherals/short-range-link.md` §11） |

### 15.3 部署顺序与为什么需要那一次立即重登记

`BlockHelper.placeSchematicBlock` 是 `setBlock`（建 BE ⇒ 立刻 `onLoad`）→ 然后 `loadWithComponents`。
所以 `onLoad` 那一刻 `channel` 还是默认 `-1`（自动分配），真实频道要到 `loadAdditional` 才读回来。
若不立即重登记，就要等 ≤20 tick 的例行复核才落回原频道（期间 Lua 在新号上查不到），
而且同链一次部署多个设备时，期间的「顺延」可能把别人刚恢复的号抢走。
逐方块串行执行 ⇒ 加上这次立即登记后，每个设备在部署当 tick 就落位。

> 世界加载（读档）路径不受影响：那时 `loadAdditional` 里 `level` 还是 null，这段被跳过，仍由 `onLoad` 负责。

### 15.4 验证

`./gradlew.bat classes` ✅ 通过。**待用户实测**：
复制一架带若干矢量喷管（频道各不相同）的飞机 → 打印到新位置 → **部署后立刻**用
`ccpe.sensor_system.getPeripheral(ch)` 查每一台，都应该已经在原来的频道上；
若新位置同链已有设备占了某个号，那台才顺延（预期）。

---

## 16. 变更记录（2026-11 第六轮：尾迹也随出力系数）

### 16.1 用户现象与诉求

> 「风扇接入了应力，但喷口通过 Lua 设定 `setThrustScale(0)`，在高空仍然会产生尾迹粒子。修复一下，
> 粒子生成与开启的推力大小有关。」

现象只出现在**矢量喷管**（定向喷管没有 `setThrustScale`，`particleIntensityScale()` 恒 1.0 ⇒ 不可能触发）：
风扇在转（`getFanSpeed() != 0` ⇒ `tickClient` 不走早退）⇒ 排气/进气已被 §14 挡掉（`count = 0`），
但 `tickContrail` 完全不读 `particleThrottle` ⇒ 高空照旧按位移吐尾迹。这正是 §14 当时**刻意**留下的行为
（"尾迹代表航迹的冰晶，与出力强弱解耦"），本轮被用户推翻。

### 16.2 落地方式（一处传参 + 一个早退 + 一个除数）

| 文件 | 改了什么 |
|---|---|
| `block/ThrustNozzleBlockEntity.java` | `tickClient` 把已有的 `particleThrottle`（= `\|转速\|/256 × particleIntensityScale()`）**传给** `tickContrail(..., particleThrottle)`；`tickContrail` 新增 `@param intensity`，开头 `intensity <= 0` ⇒ **清锚点/累积量后直接返回**；间距公式从 `trailSpacing() / max(s, TRAIL_SPACING_S_FLOOR)` 改成**再除** `max(intensity, 1.0E-3)`（密度 ∝ 有效油门）。同步更新 `particleIntensityScale()` / `trailSpacing()` / `tickClient` 三处注释 |
| `block/AdvancedThrustNozzleBlockEntity.java` | 只改注释（`setThrustScale` Lua 文档 + `particleIntensityScale()` 覆盖处的说明）：印象里"尾迹不在此列"已作废 |

**为什么不只是"系数 0 才停"**：用户的原话是"粒子生成与开启的**推力大小**有关"，所以做成**连续缩放**
（系数 0.5 ⇒ 间距翻倍 ⇒ 尾迹更稀），与 §14 的排气/进气口径完全一致（同一个 `particleThrottle`）。

**两个刻意的取舍**

1. **尾迹寿命不随出力变**：寿命只由高度适宜度 `s` 决定（`baseLife = trailLifeMin() + (trailLifeMax()−trailLifeMin())*s`）。
   出力小只是"单位航程排的水汽少"（密度降低），不是"冰晶更快消失"。这与 `TRAIL_SPACING_S_FLOOR` 的存在理由一致：
   不能用抽走粒子去表达强度，否则看起来是"断线"而不是"变淡"。
2. **系数 0 时必须清 `contrailAccum`（不只是 return）**：锚点每 tick 都在更新，但如果累积位移留着，
   重新开出力那一刻会把这段时间攒下的距离一次性吐成一串尾迹。所以 0 分支里同时把
   `contrailAnchorWorld = null`、`contrailAccum = 0`（下次启动那一 tick 只记录锚点，不发射）。

### 16.3 客户端渐变口径没有变坏

`particleIntensityScale()` 在矢量喷管的客户端实现里会**顺手把 `thrustScaleAxis` 朝目标推一步**
（`THRUST_SCALE_PER_TICK`，§14.3），所以**每 tick 只能调用它一次**，否则客户端会按 2×/tick 推进、
与推力的 0.2s 渐变脱节。因此本轮**没有**在 `tickContrail` 里再调一次，而是由 `tickClient` 算好后**传参**。

### 16.4 效果对照（叠加 §14.4）

| 场景 | 推力 | 排气 / 进气 | 高空尾迹 |
|---|---|---|---|
| 风扇 256 RPM、系数 1.0 | 满 | 满密度 | 满密度（与旧行为一致） |
| 风扇 256 RPM、系数 0.5 | 减半 | 变稀/变慢 | **间距翻倍 ⇒ 更稀**（寿命不变） |
| 风扇 256 RPM、系数 0（本轮 bug 场景） | 0 | 完全不出 | **完全不出** |
| 风扇 0 RPM（`speed == 0` 早退） | 0 | 完全不出 | 完全不出（原有逻辑，未变） |

> ⚠️ 连带的**语义变化**：尾迹密度现在也乘 `|转速|/256`。低转速巡航（比如 100 RPM）时尾迹会比满载更稀
> —— 这正是"尾迹 ∝ 排气量"的物理含义；但如果有脚本喜欢"低速也拉满尾迹"，就得把转速拉高。

### 16.5 验证

`./gradlew.bat classes` ✅ 通过（仅既有 deprecation 警告）。

**待用户进游戏实测**（同一架高空飞机，三个对照）：
1. `setThrustScale(0)` → 高空**完全不再出现尾迹**（等待约 1 分钟，让已有尾迹自然消散后再观察）；
2. `setThrustScale(1)` → 尾迹恢复原状；
3. `setThrustScale(0.5)` → 尾迹明显更稀（但单颗的寿命/大小与 1.0 时一致）。

---

## 17. 变更记录（2026-11 第七轮：尾迹高度区间拓宽）

### 17.1 用户诉求

> 「拓宽现在尾迹粒子出现的高度区间；改为高度 100 到 180 密度线性增大，240 到 320 密度线性减少。」

紧接着 §16 的同一批改动。**两种喷管共用父类的 `tickContrail`**，所以只改父类，
矢量喷管自己的参数（偏转 / 出力系数 / 频道 / 蓝图）都不用动。

### 17.2 受影响的地方

| 项 | 变化 |
|---|---|
| 适宜度曲线 | 温度模型（`TRAIL_T_*` + `smoothstep01` + `TRAIL_Y_MIN(160)`）⇒ **纯高度分段线性**：`TRAIL_Y_RISE_START(100)` / `TRAIL_Y_FULL(180)` / `TRAIL_Y_FADE_START(240)` / `TRAIL_Y_FADE_END(320)` |
| 密度口径 | 间距 = `trailSpacing() / s / 有效油门`，**删掉 `TRAIL_SPACING_S_FLOOR`**（否则低空线性会被压成台阶；§16.2 里提到它的那句已过时） |
| 寿命 | 仍按 s 线性插值（`lifeMin 360` → `lifeMax 1200`）⇒ 峰带 60 s、区间两端 18 s |
| 湍流系数 | `trailTurbulence` 的下限 160 → 100（低空更蓬松的语义不变） |
| 与 §16 的关系 | **完全叠加**：密度 = s(高度) × 有效油门(转速 × 出力系数)；`setThrustScale(0)` 仍然完全不出 |

实现细节、性能对照与验证清单统一记在母文档 **`memo/propulsion/thrust-nozzle.md` §9**
（含"为什么必须删掉 s 的低下限"与"新增的低空段对同屏粒子数的影响"），本节只做索引，避免两处不一致。

### 17.3 验证

`./gradlew.bat classes` ✅ 通过。**待用户实测**（与母文档 §9.5 同一组）：
Y≈110 有极稀尾迹、≈150 明显、≈180 起满、≈200 最浓、≈280 变稀、≈315 几乎消失、≥320 没有。


