package com.recordandgrow.infra.health;

/**
 * 健康检查结果。
 */
public class HealthStatus {

    private String status;
    private String database;
    private boolean vectorExtension;

    public HealthStatus() {
    }

    private HealthStatus(String status, String database, boolean vectorExtension) {
        this.status = status;
        this.database = database;
        this.vectorExtension = vectorExtension;
    }

    public static HealthStatus up(boolean vectorExtension) {
        return new HealthStatus("UP", "UP", vectorExtension);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public boolean isVectorExtension() {
        return vectorExtension;
    }

    public void setVectorExtension(boolean vectorExtension) {
        this.vectorExtension = vectorExtension;
    }
}
