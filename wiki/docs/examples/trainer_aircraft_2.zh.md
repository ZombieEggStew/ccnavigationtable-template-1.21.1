# 示例 - 教练机v2

## [> 存档下载 <](https://github.com/ZombieEggStew/ccnavigationtable-template-1.21.1/blob/main/.design_guide/trainer%20aircraft.zip)
[OneDrive](https://1drv.ms/u/c/4fabe5939824c0f1/IQDyp7GaueyWSJS6sStblBfdAdL7uPhhr4vora3CftGzKo0)


## 改动
- 使用了新的[轴承](../actuators/servo-bearing.zh.md)，整合了副翼与升降舵，简化气动与传动布局
- 自动滚转加入积分环节，消除了残差

- 使用新的[引擎](../engine/overview.zh.md)，无需额外模组
- 控制程序自动管理引擎温度与混合比，燃料消耗大大降低


## 参数
- 质量：22kg
- 最高巡航高度：254格
- 最高巡航速度：42格/s
- 每10000格油耗：约100mb巧克力

## 操作说明
1. Tgt_alt 旋钮拧到目标高度
2. 打开auto_th开关
3. 起飞