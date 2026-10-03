package com.github.guoyyit.realtimeleaderboardjava.persistence;

import com.github.guoyyit.realtimeleaderboardjava.model.ScoreRecord;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地写前日志（WAL）：每条被接受的提交先落盘（含 fsync）后生效，重启时按序重放重建榜单。
 * 由于写入语义是"同用户取最高分"，重放顺序不影响最终结果，重建与增量维护一致。
 * 崩溃导致的尾部半行在重放时被忽略，不会产生脏数据。
 */
public class WalStore implements Closeable {

    private static final String FILE_NAME = "leaderboard.wal";

    private final Path file;
    private FileChannel channel;

    public WalStore(Path directory) {
        try {
            Files.createDirectories(directory);
            this.file = directory.resolve(FILE_NAME);
            this.channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open WAL in " + directory, e);
        }
    }

    /** 追加一条记录并刷盘（fsync），保证重启后不丢失。 */
    public synchronized void append(String boardId, ScoreRecord record) {
        String line = boardId + '\t' + record.userId() + '\t' + record.score() + '\t'
                + record.updatedAtMs() + '\t' + record.seq() + '\n';
        try {
            channel.write(StandardCharsets.UTF_8.encode(line));
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append WAL", e);
        }
    }

    /** 读取全部历史记录（按写入顺序）；尾部损坏行被忽略。 */
    public List<WalEntry> readAll() {
        List<WalEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                return entries;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                WalEntry entry = parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read WAL", e);
        }
        return entries;
    }

    private static WalEntry parse(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 5) {
            return null;
        }
        try {
            ScoreRecord record = new ScoreRecord(parts[1], Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]), Long.parseLong(parts[4]));
            return new WalEntry(parts[0], record);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close WAL", e);
        }
    }

    /** WAL 中的一条记录。 */
    public record WalEntry(String boardId, ScoreRecord record) {
    }
}
