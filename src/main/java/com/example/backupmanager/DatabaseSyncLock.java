package com.example.backupmanager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
class DatabaseSyncLock {
    private static final long ASSET_LOCK_KEY = 4778697448896070721L;
    private final DataSource dataSource;

    DatabaseSyncLock(DataSource dataSource) { this.dataSource = dataSource; }

    Lease acquireAssetLock() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                connection.close();
                return () -> {};
            }
            try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setLong(1, ASSET_LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        throw new IllegalStateException("数据库资产同步已在其他实例运行");
                    }
                }
            }
            final Connection locked = connection;
            return () -> release(locked);
        } catch (SQLException error) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { /* original error wins */ }
            }
            throw new IllegalStateException("无法获取数据库资产同步锁", error);
        }
    }

    private static void release(Connection connection) {
        try {
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, ASSET_LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException("数据库资产同步锁释放失败");
                    }
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("数据库资产同步锁释放失败", error);
        } finally {
            try { connection.close(); }
            catch (SQLException error) { throw new IllegalStateException("数据库资产同步连接关闭失败", error); }
        }
    }

    interface Lease extends AutoCloseable {
        @Override void close();
    }
}
