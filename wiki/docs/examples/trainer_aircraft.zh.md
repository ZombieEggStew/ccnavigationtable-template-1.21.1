# 示例 - 教练机

[存档](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/1.1.3/.design_guide/trainer%20aircraft.zip)

## 需求mod
- CC:Tweaked
- Create
- Sable
- Create: Aeronautics
- Create: Diesel Generators


自带控制程序：trainer aircraft\computercraft\computer\0\startup.lua

控制面板如下图所示，图中：

- 1：屏幕开关
- 2：航行灯开关
- 3：引擎开关
- 4：升降舵阻尼模式开关
- 5：自动滚转模式开关
- 6：高度设定旋钮
- 7：自动油门模式开关

左侧屏幕从上到下依次为：

- Alt： 当前高度
- Speed： 当前速度
- Pitch： 俯仰角
- Roll： 滚转角
- Yaw： 偏航角
- Stress: 已使用应力百分比
- Fuel: 剩余燃料百分比

右侧屏幕从上到下依次为：

- Tgt：由高度设定旋钮设定的目标高度
- Tgt：保持在目标高度需要的速度
- VS：垂直速度

![monitor](../img/monitor_3.png)

## 操作说明
1. 手持 Diesel Generators 模组的燃料桶，右键起落架之间的流体端口，为飞机加满燃料
2. 坐上坐垫
3. 打开引擎开关
4. 按住空格，将油门推到1档，进入缓慢滑行状态（按住Ctrl减少油门）
5. 按Q或者E踩踏板，控制前轮转向，控制飞机进入合适的滑行道
6. 按住空格推满油门起飞
7. 高度升至300m左右时点按W，防止爬升过高
8. 转动高度设定旋钮（准心对准旋钮按住鼠标右键，围绕旋钮移动鼠标），设定目标高度
9. 打开自动油门模式开关，飞机将自动控制油门，保持在目标高度

