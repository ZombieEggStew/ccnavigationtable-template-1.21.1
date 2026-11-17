# Agent 工作规则

## 工作原则：实践是检验真理的唯一标准（最高优先级，必须遵守）

- 证据不足时，向用户提出任意个具体、可操作的测试请求（例如「请进游戏验证 X，并告诉我 Y」），然后等待回复。
- 如果有debug需求，先写最小调试代码，请用户进游戏做针对性测试和信息收集，再根据结果定位问题。
- 优先参考已经验证成功的案例（如项目内已有实现、用户明确指出的可参考方块/类），不要盲目去翻游戏底层源码。
- 先收集证据：读日志、读相关源码、参考其他 mod 的同类型实现（用 `minecraft-research` 技能）。
- 不要过早确定解决方案；收集到足够的具体信息之后再开始解决问题。
- 重大改动前，先说明将要改动的文件与原因，征得用户同意。
- 参考其他 mod 源码解决问题时，记录参考来源。

## 复杂问题：多与用户合作（最高优先级，必须遵守）（最高优先级，必须遵守）

遇到以下任一情况，立即停止自行推理，向用户提问并等待回复，不要继续空转：

- 用户问题描述不清晰，需要进一步提供信息；
- 遇到矛盾信息，陷入自我怀疑；
- 连续 2 次推断被日志、源码或运行时表现推翻，思考陷入死胡同；
- 缺少关键运行时信息（崩溃日志、报错堆栈、复现步骤、客户端表现）；
- 需要验证 Minecraft / NeoForge / Create / CC:Tweaked 的运行时行为才能确定方案。

## 禁止长考：思考与排查预算（最高优先级，必须遵守）

长考 = 在缺少关键信息或证据不足时，继续长时间自主推理、反复读证据而不向用户提问。以下为硬性约束：

1. **动手前先判断**：这个问题我现在就能定位或给出方案吗？不能 → 立即用 `ask_user_question` 提问（复现步骤、具体表现、期望行为），禁止先埋头搜索。
2. **排查预算上限**：每个问题最多 3 轮「读证据 → 推理」的连续工具调用（读日志/源码/文件）。3 轮内没有定位到根因或没有把握 → 停止，汇报已确认的事实与剩余疑问，向用户请求 1~3 个可操作的测试或信息，然后等待回复。
3. **推断被推翻即停**：连续 2 次推断被日志、源码或运行时表现推翻 = 立即停下提问（沿用上方「工作原则」规则），禁止换新假设继续空转。
4. **成本意识**：每轮工具调用前问自己「这一步真的必要吗？还是我其实可以直接问用户」。宁可多问一句，也不要让用户为一次长考买单。



## Java 源码规则（仅修改 `src/main/java/**/*.java` 时适用）

- 这是一个 Minecraft 1.21.1 NeoForge mod，Java 源码位于 `src/main/java`，目标 Java 21、NeoForge 21.1.235。
- 只改当前请求相关的代码。将 `build/`、`bin/`、`run/`、`api/`、`references/`、`.research/`、`libs/` 视为只读，仅在需要时作为参考。
- 优先复用项目已有模式，不引入新抽象。
- 先读直接相关的类、最近的调用方或测试。需要确认外部 API 或参考实现时，使用 `minecraft-research` 技能。
- 用 `./gradlew.bat classes` 验证 Java 改动，除非存在更窄的相关检查。

## 资源与数据规则（仅修改 `src/main/resources/**/*.{json,lang,mcmeta,png}` 时适用）

- 这是一个 Minecraft 1.21.1 NeoForge mod，资源与数据文件位于 `src/main/resources`。
- 只改当前请求直接需要的资源或数据。
- 保持现有命名空间、JSON 布局与模型约定。
- 将 `build/`、`bin/`、`run/`、`api/`、`references/`、`.research/`、`libs/` 视为只读参考目录，不要编辑。

## PowerShell / 脚本规则（仅修改 `tools/**/*.ps1` 或写脚本/批量命令时适用）

### 编码：`.ps1` 正文必须纯 ASCII（最高优先级，已多次踩）

- 本机 PowerShell **5.1** 读取**无 BOM 的 UTF-8** 脚本时按 ANSI（本机 CP936/GBK）解码，中文注释与字符串里的 UTF-8 字节会被解成引号、反引号、括号，
  于是**紧随其后的一整段代码被当成字符串/注释吞掉**。症状：「循环体静默不执行、统计数据恒为 0、`Unexpected token` / `Missing closing '}'`」。
  已真实发生：`tools/digest_csv.ps1` 首版整个 CSV 解析循环被吞，排查了 5 轮才定位。
- 因此：**脚本注释、变量说明、输出标签一律用英文**。中文只允许出现在 `Write-Host` 之外的**文档/memo** 里。
- 万不得已需要中文文本 → 把文件保存为 **UTF-8 with BOM**，并在写完后复核首字节是 `EF BB BF`；
  或者改为从外部 UTF-8 文件读取文本再打印。**不要**用"先写中文试一下"的方式排错。
- 一次性 `pwsh -Command` 命令行不写文件，不受此限；但**禁止**把中文塞进 `.ps1` 文件再跑。

### 调用方式与语法

- 固定用 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\xxx.ps1 <参数>` 调脚本：
  交互 shell 是 pwsh 7，但**脚本体必须按 PS 5.1 兼容**写 —— 禁用 `??`、`?.`、三元运算符、`-AsHashtable`、`Get-ChildItem -ErrorRecurse` 等 PS7 专有语法。
- `-File` 传参**不做数组拆分**：`[string[]]$Cols` 收到 `a,b,c` 会当成单个字符串 ⇒ 脚本内自己 `$_ -split ','`。
- 路径含空格（如 `run\saves\New World\computercraft\...`）必须加引号或使用 `-LiteralPath`。
- `$a[$x..([Math]::Min($y, $z))]` 这类「范围里套方法调用」在 PS 5.1 解析失败 ⇒ 拆成 `for` 循环。
- `$arr | Select-Object -First N` 会**提前关闭管道**：对原生命令或写文件的脚本会产生 broken-pipe 导致的 `[exit code: 1]`（伪失败），判定失败前先确认是不是这个。
- `@()` 包住 `-split` / `Get-Content` 的结果，否则单行文件会退化成字符串，`.Count` 变成字符数。

### 数据量纪律

- **不把大 CSV / 大日志读进上下文**：先跑摘要器（CC:T 飞行日志用 `tools/digest_csv.ps1`，输出 ≤30 行），只吃摘要结果。
- 超过 2 MB 的文件必须 `StreamReader` 流式处理 + 抽样 stride，禁止 `Get-Content` 整体读入（`run/flight_logs/` 单文件最大约 74 MB）。
- 脚本输出控制在 ≤30 行；用退出码 0 / 非 0 区分，并在输出里写清 `NOFILE` / `EMPTY` / `NO DATA` 这类可判定原因。
- 改完脚本必须**用仓库内真实文件跑一遍再交付**（最好覆盖正常、空文件、缺列、超大文件四种），把实测输出贴出来；只写不跑不算完成。

### 位置约定

- 可复用的通用工具放 `tools/`；一次性实验脚本放 `temp/`；`.tmp_luatest/` 保留给 Lua 干跑夹具。

### 读取文本时的编码（与写入同源的问题）

- 本仓库的 `.md` / `.json` / `.lang` 大多是**无 BOM 的 UTF-8**。PS 5.1 的 `Get-Content` 默认按 ANSI 读取，会打印成乱码（容易被误判成"文件损坏/内容不符"）：
  - 读文本一律 `Get-Content -LiteralPath <路径> -Encoding UTF8`，或用 `[System.IO.File]::ReadAllText(<路径>, [System.Text.Encoding]::UTF8)`。
  - 批量改写文件必须显式指定编码：`[System.IO.File]::WriteAllText(<路径>, $text, (New-Object System.Text.UTF8Encoding($false)))`（`$false` = 不写 BOM，保持原样）。
- `read` / `grep` 工具直接按 UTF-8 读，不受此限 —— 需要看内容时优先用它们，不要用 `Get-Content` 打印。

## memo 文档规则（仅修改或新增 `memo/**/*.md` 时适用）

- `memo/` 已按主题分目录：`project/`（代码地图/踩坑/调试）、`flight/`（CC:T 飞行与自动驾驶）、`propulsion/`（喷管与引擎）、`peripherals/`（传感器/频道/通信/机械件）、`cockpit/`（控制台/Monitor/屏幕/GUI）、`rendering/`（渲染/兼容/注册）、`archive/`（历史记录）。**`memo/README.md` 是唯一目录索引**。
- 新文档必须落到对应主题目录，并在 `memo/README.md` 对应表格加一行；**不要平铺在 `memo/` 根下**。
- 文件名用英文短横线、无空格、无中文（中文路径在 PowerShell / 工具链里有编码坑）；日期型记录进 `archive/`。
- 移动或改名后必须校验引用：全仓 grep `memo/` 路径，逐条确认目标文件存在（包括 `.agents/skills/*/SKILL.md`）。
- 同一结论只维护一处：控制手法与常数 → `memo/flight/starship15-constants.md`；当前进度 → `memo/flight/starship15-ROUND.md`；历史证据与推导 → `memo/flight/starship15-catch.md`。
- 超过 ~100 KB 的 memo（`memo/flight/flip-autopilot.md` 320 KB、`memo/flight/starship15-catch.md` 77 KB）只作检索用，按标题 / §N 定向读，禁止整篇读。
