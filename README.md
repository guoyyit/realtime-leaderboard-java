# 实时排行榜与名次查询

基于 Spring Boot 3.4.1 的实时排行榜服务，覆盖从分数写入到名次读取的完整链路，支持并发写入、重启恢复、多榜单隔离与限流。

## 一、名次语义

- **名次定义**：采用**稠密名次（Dense Ranking）**，即「同分同名次，名次连续不跳变」。
  - 例如分数 `100, 90, 90, 80` 的名次依次为 `1, 2, 2, 3`。
  - 同分内按 `userId` 升序排列，保证顺序可复现。
- **提交语义**：每次提交携带单调递增的 `seq`（提交序号）。服务端对同一用户同一榜单只保留 `seq` 最大的分数（LWW）。
  - 重复提交（相同 `seq`）被忽略，不重复累计。
  - 乱序到达（更小 `seq`）被忽略，不覆盖已有分数。
  - 最终状态与到达顺序无关，并发提交下结果确定。

## 二、接口约定

### 提交分数

```
POST /api/leaderboards/{boardId}/scores
Content-Type: application/json

{
  "userId": "u1",
  "score": 100,
  "seq": 1
}
```

响应：

```json
{
  "status": "ACCEPTED",
  "userId": "u1",
  "score": 100,
  "seq": 1,
  "rank": 1
}
```

`status` 取值：

| 状态 | 含义 | HTTP 状态码 |
|------|------|-------------|
| `ACCEPTED` | 提交被接受并应用 | 200 |
| `DUPLICATE_IGNORED` | 重复提交（相同 seq）被忽略 | 200 |
| `OUT_OF_ORDER_IGNORED` | 乱序到达（更小 seq）被忽略 | 200 |
| `RATE_LIMITED` | 被限流拒绝 | 429 |
| `REJECTED_INVALID` | 输入非法被拒绝 | 400 |

### 名次区间分页

```
GET /api/leaderboards/{boardId}/range?start=1&end=10
```

- `start`、`end` 为稠密名次（1 起，闭区间）。
- 越界自动钳制：`start < 1` 钳制到 1；`end > 总名次数` 钳制到总名次数。
- `start > end` 返回空列表。
- 空榜单返回空列表。

响应：

```json
{
  "entries": [
    {"userId": "u1", "score": 100, "seq": 1, "rank": 1},
    {"userId": "u2", "score": 90, "seq": 1, "rank": 2}
  ],
  "totalUsers": 2,
  "totalRanks": 2,
  "startRank": 1,
  "endRank": 2
}
```

### 查询用户名次

```
GET /api/leaderboards/{boardId}/users/{userId}/rank
```

- 用户或榜单不存在返回 404。

### 查询用户附近排名

```
GET /api/leaderboards/{boardId}/users/{userId}/neighbors?range=3
```

- 返回稠密名次在 `[rank-range, rank+range]` 内的所有用户（含本人）。
- 用户不存在返回空列表。

### 列出所有榜单

```
GET /api/leaderboards
```

## 三、配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `leaderboard.data-dir` | `./leaderboard-data` | 本地持久化数据目录（WAL + 快照） |
| `leaderboard.wal-fsync` | `false` | 是否对 WAL 执行 fsync（更强持久性，更低性能） |
| `leaderboard.snapshot-every` | `1000` | 每写入多少条 WAL 后触发一次全量快照 |
| `leaderboard.rate-limit.per-user-per-second` | `10` | 每用户每秒允许的提交数（令牌桶填充速率） |
| `leaderboard.rate-limit.capacity` | `20` | 每用户令牌桶容量（突发上限） |
| `leaderboard.score-min` | `0` | 允许的最低分数（含） |
| `leaderboard.score-max` | `1000000000` | 允许的最高分数（含） |

## 四、架构设计

### 数据结构：增强跳表（Augmented Skip List）

- 参考 Redis `zskiplist` 实现，按分数降序排列。
- 每个节点代表一个**不同的分数**，节点内维护该分数下的用户集合（`TreeSet`，按 `userId` 升序）。
- 通过维护每一层的 `span`（跨越的节点数），支持：
  - 更新（插入/删除分数节点）：**O(log n)**
  - 名次查询（`rankOfScore`）：**O(log n)**
  - 区间查询（`scoresByRank`）：**O(log n + k)**，k 为返回的分数个数
- 总用户数与不同分数个数均为 O(1) 维护。

### 稠密名次计算

- 名次 = 分数严格大于当前分数的不同分数个数 + 1。
- 跳表中 `rankOfScore` 通过累加路径上的 `span` 得到，无需全量重排。

### 并发模型

- 每个榜单一把 `ReentrantReadWriteLock`。
- 写入路径：`校验 -> 限流 -> 追加 WAL -> 更新内存`。
  - WAL 先于内存更新；WAL 失败则内存不变，**不留下部分更新或脏数据**。
  - 写操作持有写锁，读操作持有读锁，读读不互斥。
- 限流为每用户独立的令牌桶，在写锁外执行。

### 持久化：WAL + 快照

- **WAL**（`wal.log`）：追加提交日志（JSON Lines），每次提交后 flush。
- **快照**（`snapshot.json`）：全量状态，每 `snapshot-every` 次提交后触发。
  - 快照采用「写临时文件 + 原子移动 + 清空 WAL」。
  - 即使在快照与清空 WAL 之间崩溃，重启后回放 WAL 也是幂等的（seq LWW），不丢数据。
- **重启恢复**：加载快照 + 回放 WAL，重建结果与增量维护一致。

### 增量维护与查询的取舍

- **写多读少**场景：跳表的 O(log n) 更新保证写入高效，查询通过 `span` 累加 O(log n) 得名次，无需全量重排。
- **快照开销**：快照为全量序列化，频率由 `snapshot-every` 控制。频率越高，重启恢复越快，但写入开销越大。默认 1000 次提交一次快照，在恢复速度与写入开销间取得平衡。
- **fsync 权衡**：`wal-fsync=false` 时写入仅 flush 到 OS 缓存，性能高但断电可能丢最后几条；`wal-fsync=true` 时每次写入强制落盘，持久性最强但性能下降。默认关闭，可根据业务持久性要求开启。

## 五、测试覆盖

- **增强跳表属性测试**：与朴素 `TreeMap` 实现进行 20 次随机操作对照，验证更新、名次、区间查询的正确性。
- **集成测试**：
  - 并发提交（同用户确定性、不同用户排名正确）
  - 重复提交与乱序到达
  - 同分并列（稠密名次、userId 排序）
  - 多榜单隔离
  - 分页边界（空榜单、越界钳制、start > end、用户不存在）
  - 附近排名
  - 重启恢复（含快照、重复提交幂等）
  - 限流
  - 非法输入（不破坏已有数据）
- **REST 接口测试**：验证从提交到查询的完整 HTTP 链路。

## 六、本地运行

```bash
# 构建
mvn compile

# 运行测试
mvn test

# 启动服务（端口随机，见日志）
mvn spring-boot:run
```
