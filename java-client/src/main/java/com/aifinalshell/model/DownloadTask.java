package com.aifinalshell.model;

import javafx.beans.property.*;

public class DownloadTask {
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty remotePath = new SimpleStringProperty();
    private final StringProperty localPath = new SimpleStringProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty speed = new SimpleStringProperty("--");
    private final StringProperty status = new SimpleStringProperty("等待中");
    private final LongProperty totalSize = new SimpleLongProperty(0);
    private final LongProperty downloadedSize = new SimpleLongProperty(0);
    private final BooleanProperty isDirectory = new SimpleBooleanProperty(false);
    private volatile boolean cancelled = false;

    public DownloadTask(String fileName, String remotePath, String localPath, boolean isDirectory) {
        this.fileName.set(fileName);
        this.remotePath.set(remotePath);
        this.localPath.set(localPath);
        this.isDirectory.set(isDirectory);
    }

    public StringProperty fileNameProperty() { return fileName; }
    public StringProperty remotePathProperty() { return remotePath; }
    public StringProperty localPathProperty() { return localPath; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty speedProperty() { return speed; }
    public StringProperty statusProperty() { return status; }
    public LongProperty totalSizeProperty() { return totalSize; }
    public LongProperty downloadedSizeProperty() { return downloadedSize; }
    public BooleanProperty isDirectoryProperty() { return isDirectory; }

    public String getFileName() { return fileName.get(); }
    public String getRemotePath() { return remotePath.get(); }
    public String getLocalPath() { return localPath.get(); }
    public double getProgress() { return progress.get(); }
    public String getSpeed() { return speed.get(); }
    public String getStatus() { return status.get(); }
    public long getTotalSize() { return totalSize.get(); }
    public long getDownloadedSize() { return downloadedSize.get(); }
    public boolean isDirectory() { return isDirectory.get(); }
    public boolean isCancelled() { return cancelled; }

    public void setProgress(double value) { progress.set(value); }
    public void setSpeed(String value) { speed.set(value); }
    public void setStatus(String value) { status.set(value); }
    public void setTotalSize(long value) { totalSize.set(value); }
    public void setDownloadedSize(long value) { downloadedSize.set(value); }
    public void cancel() { this.cancelled = true; this.status.set("已取消"); }
}
