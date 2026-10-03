# realtime-leaderboard-java

基于 Spring Boot 的实时排行榜与名次查询服务。纯本地实现：内存有序统计树 + 本地 WAL 持久化，
不依赖任何外部服务或真实凭据。

## 构建与测试

```bash
mvn compile   # 编译
mvn test      # 运行全部自动化测试
mvn spring-boot:run
```

## 名次语义（稳定且可复现）

- 名次从 **1** 开始，分数高者名次靠前。
- **同分排序规则**：先达成该分数者名次靠前（按分数首次达成时间升序）；
  若达成时间也相同，按服务端分配的单调递增序号升序。三者构成**全序**，
  因此不存在"同分不同名次"的歧义，任意时刻的排名完全确定、可复现。
- **分数更新语义**：同一用户在同一榜单中只保留历史提交里的**最高分**。
  重复提交、乱序到达的低分提交都不会覆盖或累计分数，并发提交下的最终结果确定。
- 名次在单次查询内一致：所有榜单操作在榜单级锁内串行执行，
  分页扫描得到的名次连续、无跳变、无重复。

## 多榜单（统计周期/分区）

每个 `boardId`（如 `daily`、`weekly`、`monthly`）对应一个完全独立的榜单，
同一用户在不同榜单中的成绩独立计算，任一榜单的更新不影响其它榜单。

## 接口约定

### 提交分数

```
POST /api/leaderboards/{boardId}/scores
Content-Type: application/json

{"userId": "alice", "score": 900}
```

响应 `200`：

```json
{"rank": 1, "userId": "alice", "score": 900, "updatedAtMs": 1735689600000}
```

幂等：同一用户重复提交相同或更低分数返回当前生效记录，不改变榜单状态。

### 按名次区间分页

```
GET /api/leaderboards/{boardId}/rankings?fromRank=1&size=20
```

- `fromRank`：起始名次（>= 1）；`size`：条数（1 ~ `leaderboard.max-page-size`）。
- 越界自动裁剪；榜单不存在或为空时返回空 `entries`。

### 查询指定用户附近排名

```
GET /api/leaderboards/{boardId}/users/{userId}/rank?context=2
```

- `context`：前后各取多少名（0 ~ `leaderboard.max-context`），结果含用户自身。
- 用户或榜单不存在时返回 `{"found": false, ...}`，邻居列表为空。

### 错误响应

| 状态码 | error              | 含义                                   |
|--------|--------------------|----------------------------------------|
| 400    | `INVALID_ARGUMENT` | 非法输入（id 字符集、分数范围、分页参数） |
| 429    | `RATE_LIMITED`     | 触发每用户限流                          |

失败请求不会产生任何部分更新：校验与限流在写 WAL 之前完成，
WAL 落盘失败时内存状态不变，不会留下脏数据。

## 持久化与重启恢复

- 每条被接受的提交先追加到本地 WAL（`leaderboard.wal-dir/leaderboard.wal`，每条 fsync）再更新内存。
- 重启时按序重放 WAL 重建全部榜单。由于写入语义是"同用户取最高分"，
  重放与顺序无关、天然幂等，**重建结果与增量维护的结果一致**（有自动化测试验证）。
- 崩溃造成的 WAL 尾部半行在重放时被忽略。
- 重放后序号水位推进到历史最大值之后，新提交不会与历史序号冲突。

## 性能设计：增量维护与查询的取舍

- 排名数据结构为**子树大小增强的 AVL 有序统计树**（`RankTree`）：
  插入/删除/按 key 求名次/按名次取元素均为 **O(log n)**。
- 每次提交只做一次增量调整（删除旧记录 + 插入新记录），
  查询直接读取树中名次，**从不对全量数据重排**，大榜单 + 高频提交下查询耗时可控。
- 取舍说明：相比"写入 O(1)、查询时全量排序 O(n log n)"的懒计算方案，
  本方案把成本放在写入路径（O(log n) 增量维护），换取读路径的稳定低延迟；
  相比跳表 + 名次遍历（名次查询 O(n)），有序统计树用额外的子树大小维护成本
  换来了 O(log n) 的名次查询。分页查询一个大小为 k 的区间成本为 O(k log n)。

## 限流与输入校验

- 每用户令牌桶限流（`RateLimiter`），超限返回 429；限流只拒绝请求，不触碰榜单数据。
- `boardId` / `userId` 必须匹配 `[A-Za-z0-9._-]{1,64}`；分数必须在 `[0, leaderboard.max-score]`。

## 配置项（`application.properties`，前缀 `leaderboard.`）

| 配置项                       | 默认值        | 说明                     |
|------------------------------|---------------|--------------------------|
| `wal-dir`                    | 临时目录下 wal | WAL 持久化目录           |
| `max-score`                  | 1000000000    | 分数上限（含）           |
| `rate-limit-per-second`      | 50            | 每用户每秒许可补充速率   |
| `rate-limit-burst`           | 100           | 每用户突发容量           |
| `max-page-size`              | 200           | 单次分页最大条数         |
| `max-context`                | 50            | 邻居查询最大上下文长度   |

## 测试覆盖

- `RankTreeTest`：有序统计树与参考实现随机对拍。
- `LeaderboardTest`：同分并列 tie-break、乱序/重复提交、分页与邻居边界、名次一致性。
- `ConcurrencyTest`：多线程并发提交收敛到确定性结果、同用户并发取最高分。
- `RecoveryAndIsolationTest`：WAL 重启恢复结果与增量维护一致、多榜单隔离。
- `ValidationAndRateLimitTest`：非法输入拒绝且无部分状态、限流不破坏榜单。
- `LeaderboardApiTest`：HTTP 接口契约（提交、分页、邻居、错误响应）。
