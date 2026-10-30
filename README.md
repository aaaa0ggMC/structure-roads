# Broken Connections 0.2.0

> [!WARNING]
> **免责声明 / Disclaimer**
> 
> 本项目处于 WIP（Work In Progress）阶段。作者不对本模组当前以及将来的质量、稳定性、可用性做任何形式的明示或暗示保证与担保，代码可能包含 AI 生成内容（AI Slop）。请在测试环境中评估并自行承担使用风险。
> 
> This project is a Work In Progress (WIP). The author makes NO warranties or guarantees of any kind regarding the quality, stability, or fitness for any purpose of this mod now or in the future. The codebase may contain AI-generated slop. Use strictly at your own risk.

Minecraft 1.12.2 的聚落局部道路模组，Java 8字节码，目标为本包Forge/Cleanroom。源码目录暂沿用 `dev/structure-roads/`；运行模组ID为 `brokenconnections`，配置为 `config/brokenconnections.cfg`，产物为 `broken-connections-0.2.0.jar`。

**核心是一个个独立聚落，不是远距离村庄路网。没有预览模式，没有Xaero接口、客户端渲染、网络同步或额外方块/生物。** 原版碎石方块直接构成实体道路。Xaero地图模组本身不受改动，探索后照常记录地形，不再有专属规划线。

## 生成规则

- 每个聚落默认最多2条出村路。仅检查256格内已记录的地表建筑；近邻选择每tick最多检查128个已知记录，不生成或强载新区块去找终点。
- 有合适的近邻就尝试连接；附近没有建筑或连接失败，生成独立废弃支路。不因此加大搜索半径或补一条极远道路。
- 支路的方向、目标长度和缺口按世界种子+结构ID+槽位固定。目标长度默认96–160格；遇到未知/不安全地形可保留至少48步的可达部分，否则暂缓，不削穿障碍。
- 废弃支路前半段完整，后半段逐渐减少两侧铺装并产生缺口，末端趋于破碎。已铺部分、刻意留空部分、局部停工位置都持久化，不因重进存档重复随机。
- 四格寻路网格，但检查边中每一格、三格宽路面和一步高差。避让建筑、农田、树干、陡坡、水面和容器。沿现有地表，不削山、不填海、没有桥梁/隧道。
- 原版村庄识别 `ViSR` 道路组件：从已有村路端部向外接，分别保护房屋/农田/井，不再用整个村庄大框把所有出口一并挡住。沙漠砂岩路和木桥只在已登记村路区域内视为可用起点，不把所有建筑材料全局加入白名单。
- 别的村庄格式没有可确认的道路组件时，保守从外围起路，不猜大门或穿建筑。

## 执行与保护

生成、扫描、施工都在服务端主线程。已加载、填充稳定的自然地形按需采样，只缓存局部寻路需要的区块，不普查全世界。快照最多256区块，旧快照淘汰；规划可以读取已观察的卸载区块快照，施工必须等区块实际加载并逐列实时复核。

区块独立等待，不因玩家继续探索就无限重置全局等待。施工按区块索引和完成位图进行，另一端卸载不阻塞当前可施工部分。默认每tick：采样最多128列（2ms软预算）、A*最多8节点（2ms软预算）、施工最多4列（1ms软预算）。单次第三方API调用不能被时间预算抢占。这些是代码上限，**不是实测TPS/FPS承诺**。

只允许安装后填充的区块施工，记录玩家放置/破坏并保护整个区块。升级时仅从旧 `structureroads_v1` 导入可施工区块/玩家编辑保护，**不继承已取消的旧路网或旧目标**。全新存档和继续探索产生的村庄自动生效。

在安装前已经存在、且没有旧版许可记录的村庄不会被静默回填。若明确想在当前旧区域测试，可以在附近执行 `/brokenconnections allowarea 8`，仅授权当前已加载的自然地形，玩家编辑保护仍保留，不强载区块。不要对有重要旧建筑的区域盲目授权；模组无法识别安装前所有玩家建筑。

## 结构适配

| 来源 | 起点 / 终点 / 避让 |
| --- | --- |
| 原版各生物群系村庄 | 自动聚落起点；确认原版路径组件时接现有村路、保护各建筑 |
| Village Names等沿用Village保存格式的生成器 | 自动识别聚落；未知路径组件保守从外围起路，不声称已确认所有模板入口 |
| 原版神庙/女巫小屋、林地府邸 | 默认只作附近连接目标，非道路起点；地下/海底设施不强接 |
| Recurrent Complex Volts 2.0.1.2 | 用真实包围盒避让；精确模板白名单分别指定聚落起点和地表目标，不把树木/残骸都当村庄 |
| Chocolate Quest Repoured 2.8.0B | 读取公共保护区；地表保护区为目标，精确区域名白名单才是聚落起点；生成中不接，不覆盖无保护区的地牢 |
| 其他使用MapGenStructureData的结构 | 配置明确保存标识，分别注册起点/目标/避让 |
| 冰火、Additional Structures等自定义WorldGenerator | **没有通用自动适配**。不因类名带Village就假定存在保存记录；可手工登记或后续专门接入 |

RC/CQR/Village Names当前不自动装回整合包。只有用户逐个加入时才启用已有可选适配；这些第三方接口已作本地静态核对，仍需有对应模组的实际存档测试。未知结构的数据保护不可能由方块白名单完全替代。

## 调试命令（权限2；正常生成不需要命令）

```text
/brokenconnections status
/brokenconnections sites [页码]
/brokenconnections routes [页码]
/brokenconnections settlement <ID> <包围半径>
/brokenconnections building <ID> <包围半径>
/brokenconnections protect <ID> <x1> <y1> <z1> <x2> <y2> <z2>
/brokenconnections allowarea [区块半径0–8]
/brokenconnections retry
```

别名 `/bcroads`。聚落/建筑在命令执行位置登记，内部被保护；retry只解除局部停工/冷却，不绕过避让。status显示聚落/路线/废弃支路/完成数、快照和待采样数、资格/编辑区块、适配器及容量状态，以及最近的等待/失败原因，不再只给无法解释的0条路。

## 构建与检查

在此源码目录运行：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk ../morph-extension/gradlew --offline --no-daemon build
```

沿用FG2.3 / Forge2847开发映射stable_39，Gradle堆2GB；不升级运行时依赖。构建的 `roadChecks` 使用无新增依赖的断言程序验证：局部连接、建筑全宽避让、未知地形不穿越、孤立安全前缀、预算上限、种子确定性与荒废缺口、非前缀施工进度、旧进度迁移、NBT往返、村路出口/建筑保护模式。

**逻辑测试和重混淆构建不代替游戏验证。** 尚未启动游戏抽查村庄/测TPS；原版各村庄、坡地/海边/林地、旧区域授权、存档重载和第三方生成器仍须实机查看。请用独立测试存档。旧0.1.0运行jar应移到mods外，再把本模组运行jar放共享池并同步；不要把source jar或两种生成器同时放实例mods。
