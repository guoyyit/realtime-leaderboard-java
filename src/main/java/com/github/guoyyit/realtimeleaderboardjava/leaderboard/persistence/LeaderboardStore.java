package com.github.guoyyit.realtimeleaderboardjava.leaderboard.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 排行榜本地持久化：WAL + 周期性快照。
 *
 * <p>数据目录下包含：
 * <ul>
 *   <li>snapshot.json：全量快照（boardId -> userId -> {score, seq}）</li>
 *   <li>wal.log：快照之后的追加提交日志（JSON Lines）</li>
 * </ul>
 *
 * <p>写入路径：先追加 WAL 并落盘，再更新内存。快照采用「写临时文件 + 原子移动 + 清空 WAL」，
 * 即使在快照与清空 WAL 之间崩溃，重启后回放 WAL 也是幂等的（seq LWW），因此不会丢数据。
 */
public class LeaderboardStore implements AutoCloseable {

    public record WalEntry(String boardId, String userId, long score, long seq) {
    }

    public record UserScoreState(long score, long seq) {
    }

    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final String SNAPSHOT_TMP_FILE = "snapshot.json.tmp";
    private static final String WAL_FILE = "wal.log";

    private final Path dataDir;
    private final boolean walFsync;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FileOutputStream walOut;
    private BufferedWriter walWriter;
    private long walLinesSinceSnapshot;

    public LeaderboardStore(Path dataDir, boolean walFsync) throws IOException {
        this.dataDir = dataDir;
        this.walFsync = walFsync;
        Files.createDirectories(dataDir);
        openWal();
    }

    private void openWal() throws IOException {
        Path walFile = dataDir.resolve(WAL_FILE);
        this.walOut = new FileOutputStream(walFile.toFile(), true);
        this.walWriter = new BufferedWriter(new OutputStreamWriter(walOut, StandardCharsets.UTF_8));
        this.walLinesSinceSnapshot = 0;
    }

    /** 追加一条 WAL 记录并落盘。 */
    public synchronized void append(WalEntry entry) throws IOException {
        walWriter.write(objectMapper.writeValueAsString(entry));
        walWriter.newLine();
        walWriter.flush();
        if (walFsync) {
            walOut.getFD().sync();
        }
        walLinesSinceSnapshot++;
    }

    /** 加载快照并回放 WAL，返回全量榜单状态。 */
    public Map<String, Map<String, UserScoreState>> load() throws IOException {
        Map<String, Map<String, UserScoreState>> boards = new HashMap<>();

        Path snapshotFile = dataDir.resolve(SNAPSHOT_FILE);
        if (Files.exists(snapshotFile)) {
            JsonNode root = objectMapper.readTree(snapshotFile.toFile());
            JsonNode boardsNode = root.get("boards");
            if (boardsNode != null && boardsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = boardsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    Map<String, UserScoreState> users = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> userFields = field.getValue().fields();
                    while (userFields.hasNext()) {
                        Map.Entry<String, JsonNode> userField = userFields.next();
                        JsonNode stateNode = userField.getValue();
                        users.put(userField.getKey(),
                                new UserScoreState(stateNode.get("score").asLong(), stateNode.get("seq").asLong()));
                    }
                    boards.put(field.getKey(), users);
                }
            }
        }

        Path walFile = dataDir.resolve(WAL_FILE);
        if (Files.exists(walFile)) {
            for (String line : Files.readAllLines(walFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                WalEntry entry = objectMapper.readValue(line, WalEntry.class);
                boards.computeIfAbsent(entry.boardId(), k -> new HashMap<>())
                        .merge(entry.userId(),
                                new UserScoreState(entry.score(), entry.seq()),
                                (existing, incoming) -> existing.seq() >= incoming.seq() ? existing : incoming);
            }
        }
        return boards;
    }

    /** 写入全量快照并清空 WAL。 */
    public synchronized void snapshot(Map<String, Map<String, UserScoreState>> boards) throws IOException {
        Path tmp = dataDir.resolve(SNAPSHOT_TMP_FILE);
        objectMapper.writeValue(tmp.toFile(), Map.of("boards", boards));

        Path snapshotFile = dataDir.resolve(SNAPSHOT_FILE);
        Files.move(tmp, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        walWriter.close();
        Files.writeString(dataDir.resolve(WAL_FILE), "", StandardCharsets.UTF_8);
        openWal();
    }

    /** 自上次快照以来写入的 WAL 行数。 */
    public long walLinesSinceSnapshot() {
        return walLinesSinceSnapshot;
    }

    /** 关闭底层资源。 */
    @Override
    public synchronized void close() throws IOException {
        if (walWriter != null) {
            walWriter.close();
        }
    }
}
