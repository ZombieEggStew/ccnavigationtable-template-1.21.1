# 物理数据读取

需要pe附着在物理体上的任意方块上

| 方法 | 返回值 | 说明 |
|---|---|---|
| `getPhysicsPos(ch)` | `{x, y, z}` | 物理体的世界坐标（m）|
| `getPhysicsOrientation(ch)` | `{x, y, z, w}` | 物理体的旋转四元数 |
| `getPhysicsCenterOfMass(ch)` | `{x, y, z}` | 物理体的质心世界坐标 |
| `getPhysicsMass(ch)` | `number` | pe附着的方块所在的物理体的质量（kg）|
| `getPhysicsChainMass(ch)` | `number` | 物理体链总质量（kg）|
| `getPhysicsGravityForce(ch)` | `number` | pe附着的方块所在的物理体的重力（pN）|
| `getPhysicsChainGravityForce(ch)` | `number` | 物理体链总重力（pN）|

经过测试 一个物理体上装配过后的 物理轴承 会计算两次重力

所以getPhysicsChainGravityForce的值可能会与你手动计算的值不符

如果你需重力参与精确计算，尽管使用 getPhysicsChainGravityForce 