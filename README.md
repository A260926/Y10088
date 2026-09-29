# 状态机一致性实验室（State Machine Consistency Lab）

用**同一份 JSON 声明**同时验证：

- **事件轨迹**：手工录入或从事件集合生成的有界交错，逐步执行；
- **服务响应**：外部实现上传自己的响应序列，逐步比较并给出**第一处分歧**；
- **并发交错**：声明可交换的事件对，两个顺序都必须达到相同状态与输出集合。

技术栈：Kotlin + Ktor（Netty）网页/JSON API + SQLite（JDBC）持久化。

## 安装与演示

```bash
./gradlew build -x test          # 安装（跳过测试）
./gradlew test                   # 15 个测试
./gradlew run --args='--port 5218'
```

打开 http://localhost:5218 ，页面标题为**“状态机一致性实验室”**，
顶部直接展示内置示例：**六步失败轨迹缩减成两步反例**（`cancel`, `confirm`）。

数据库默认写在 `./lab.db`，可用 `--db /path/to.db`（或 `LAB_DB` 环境变量）覆盖。

## 声明格式

顶层字段：`title` / `initial` / `events` / `illegal` / `onGuardFail` /
`unorderedOutputFields` / `commutative`。

- `illegal`: `reject`（默认，非法事件拒绝）｜`accept`（承认但无动作）｜`skip`（忽略）。
- 表达式为结构化 JSON，不发明需要转义的字符串语言：
  `{"lit": 1}`、`{"at":"attempt"}` / `{"at":"x","source":"event"}`、
  `{"expr":{"op":"lt","args":[...]}}`，支持 `and or not eq ne lt le gt ge add sub mul div if`。
- 效果：`{"set":{"path":"payment.amount","value":{"expr":...}}}`、`{"remove":{"path":"..."}}`。
- 输出模板同样是 JSON，值位置可以嵌 `lit/at/expr`，动作产生的可观察输出由此声明。

示例见 `src/main/kotlin/lab/engine/seed/SeedData.kt`（重试、取消、确认 + heartbeat）。

## 主要 API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/definitions` | 创建 draft：`{name, body}` |
| `PUT /api/definitions/{name}/versions/{v}/draft` | 改 draft，体含 `expectedRevision`（乐观锁，冲突 409） |
| `POST /api/definitions/{name}/versions` | 已发布版本不可修改，只能创建新版本 |
| `POST /api/definitions/{name}/versions/{v}/publish` | 发布 |
| `POST /api/runs` | `{definition, version?, idempotencyKey?, initialState?, events, externalResponses?}` |
| `GET /api/runs/{id}` / `/by-key/{key}` | 运行详情（逐步保存执行前状态、输入、守卫判定、输出、执行后状态、定义版本） |
| `GET /api/runs/{id}/comparison` | 第一处分歧 |
| `GET /api/runs/{id}/reduce` | 确定性反例缩减 + 每次尝试与判定 |
| `POST /api/interleavings` | `{parties:[[事件...],...], limit}` 有界交错 |
| `POST /api/commutativity` | 可交换检查；输出集合忽略 `unorderedOutputFields`，其余字节严格 |

## 关键语义

- **幂等**：相同 `idempotencyKey` 返回原运行（200）；键相同但初始状态或事件不同 → **409 冲突**。
- **分歧分型**：`MISMATCH` / `TIMEOUT` / `MISSING_RESPONSE` / `EXTRA_RESPONSE`，不会折成统一“失败”。
- **缩减**：按固定顺序反复尝试删除单个事件，保留原相对顺序；仍然分歧就提交删除，
  直至一轮内无事件可删。每次尝试（尝试删除的索引、保留索引、removed/restored 判定）都持久可见。
- **可交换**：仅当事件对被声明为可交换，且 ab/ba 终态规范字节相同、输出集合相同才算通过。
- **崩溃恢复**：写步骤与推进游标是两个独立事务。进程在两者之间退出，
  恢复时已落库步骤视为动作已发生、绝不重复触发（`action_invocations` 与步骤计数一致）。
- **定义版本**：发布即不可变；draft 用 `revision` 乐观锁，两个页面并发保存时后到者收到 409；
  已有运行永久绑定创建时的定义版本。

## 测试覆盖

`src/test/kotlin/lab`：

- 非法事件策略、守卫边界（第 6 次 retry）；
- 幂等命中与幂等冲突（单元 + HTTP 409）；
- 可交换误判（retry 先到达边界使两顺序终态不同）；
- 超时 / 缺响应 / 多响应三种不同结果；
- 六步 → 两步反例缩减、多次运行稳定、对反例再缩减不可继续删；
- 两个崩溃窗口恢复（步骤提交后游标未推进 / 动作后提交前退出）；
- draft 修订冲突保护与发布后不可改；
- 端到端：首页标题、种子缩减、交错有界、可交换 API。
