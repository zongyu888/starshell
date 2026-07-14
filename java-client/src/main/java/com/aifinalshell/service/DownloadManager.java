package com.aifinalshell.service;

import com.aifinalshell.model.DownloadTask;
import com.aifinalshell.ssh.SshConnectionManager;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadManager {
    private static final Logger logger = LoggerFactory.getLogger(DownloadManager.class);
    private static DownloadManager instance;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<DownloadTask> tasks = Collections.synchronizedList(new ArrayList<>());
    private String defaultDownloadPath;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private DownloadManager() {
        String userHome = System.getProperty("user.home");
        defaultDownloadPath = userHome + File.separator + "Downloads" + File.separator + "StarShell";
    }

    public static synchronized DownloadManager getInstance() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    public String getDefaultDownloadPath() { return defaultDownloadPath; }
    public void setDefaultDownloadPath(String path) { this.defaultDownloadPath = path; }
    public List<DownloadTask> getTasks() { return tasks; }

    public DownloadTask downloadFile(String connKey, String remotePath, String localPath) {
        String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        DownloadTask task = new DownloadTask(fileName, remotePath, localPath, false);
        tasks.add(0, task);

        executor.submit(() -> {
            try {
                task.setStatus("下载中...");
                Path localDir = Paths.get(localPath).getParent();
                if (localDir != null) Files.createDirectories(localDir);

                SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(connKey);

                long remoteSize = 0;
                try {
                    remoteSize = sftp.getFileSize(remotePath);
                } catch (Exception e) {
                    // ignore
                }
                final long totalSize = remoteSize;
                Platform.runLater(() -> task.setTotalSize(totalSize));

                // Use rsync-style progress via SSH command for better progress tracking
                if (totalSize > 0) {
                    downloadWithProgress(connKey, task, sftp, remotePath, localPath);
                } else {
                    sftp.download(remotePath, localPath);
                    Platform.runLater(() -> {
                        task.setProgress(1.0);
                        task.setSpeed("完成");
                        task.setStatus("已完成");
                    });
                }

                sftp.close();
            } catch (Exception e) {
                logger.error("Download failed: {}", remotePath, e);
                Platform.runLater(() -> task.setStatus("失败: " + e.getMessage()));
            }
        });

        return task;
    }

    private void downloadWithProgress(String connKey, DownloadTask task,
                                       SshConnectionManager.SftpChannel sftp,
                                       String remotePath, String localPath) throws Exception {
        // Download in a thread and track via file size growth
        Thread downloadThread = new Thread(() -> {
            try {
                sftp.download(remotePath, localPath);
            } catch (Exception e) {
                if (!task.isCancelled()) {
                    Platform.runLater(() -> task.setStatus("失败: " + e.getMessage()));
                }
            }
        });
        downloadThread.start();

        // Monitor progress
        long startTime = System.currentTimeMillis();
        long lastSize = 0;
        long lastCheckTime = startTime;
        while (downloadThread.isAlive() && !task.isCancelled()) {
            Thread.sleep(500);
            long now = System.currentTimeMillis();
            File localFile = new File(localPath);
            long currentSize = localFile.exists() ? localFile.length() : 0;
            long totalSize = task.getTotalSize();

            if (totalSize > 0) {
                double prog = Math.min((double) currentSize / totalSize, 0.99);
                // Calculate actual speed based on real elapsed time (not assumed 0.5s)
                long elapsedMs = now - lastCheckTime;
                long speedBytes = elapsedMs > 0
                        ? (long) ((currentSize - lastSize) * 1000.0 / elapsedMs)
                        : 0;
                lastSize = currentSize;
                lastCheckTime = now;

                Platform.runLater(() -> {
                    task.setProgress(prog);
                    task.setDownloadedSize(currentSize);
                    task.setSpeed(formatSpeed(speedBytes));
                });
            }
        }

        if (!task.isCancelled()) {
            Platform.runLater(() -> {
                task.setProgress(1.0);
                task.setSpeed("完成");
                task.setStatus("已完成");
            });
        }
    }

    public DownloadTask downloadFolder(String connKey, String remotePath, String localPath) {
        String folderName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        DownloadTask task = new DownloadTask(folderName, remotePath, localPath, true);
        tasks.add(0, task);

        executor.submit(() -> {
            try {
                task.setStatus("扫描文件...");
                Path localDir = Paths.get(localPath);
                Files.createDirectories(localDir);

                // List all files recursively
                String cmd = "find " + remotePath + " -type f 2>/dev/null";
                String result = SshConnectionManager.getInstance().executeCommand(connKey, cmd);

                if (result == null || result.trim().isEmpty()) {
                    Platform.runLater(() -> {
                        task.setStatus("空文件夹");
                        task.setProgress(1.0);
                    });
                    return;
                }

                String[] files = result.trim().split("\n");
                int totalFiles = files.length;
                int completedFiles = 0;

                for (String file : files) {
                    if (task.isCancelled()) break;

                    String trimmedFile = file.trim();
                    if (trimmedFile.isEmpty()) continue;

                    // Calculate relative path
                    String relativePath = trimmedFile.substring(remotePath.length());
                    if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

                    String localFilePath = localPath + File.separator + relativePath.replace("/", File.separator);
                    File localFile = new File(localFilePath);
                    localFile.getParentFile().mkdirs();

                    Platform.runLater(() -> {
                        task.setStatus("下载: " + trimmedFile);
                    });

                    try {
                        // Use try-with-resources to prevent SFTP channel leaks
                        SshConnectionManager.SftpChannel sftp = SshConnectionManager.getInstance().openSftp(connKey);
                        try {
                            sftp.download(trimmedFile, localFilePath);
                        } finally {
                            sftp.close();
                        }
                        completedFiles++;

                        final int cf = completedFiles;
                        Platform.runLater(() -> {
                            task.setProgress((double) cf / totalFiles);
                            task.setDownloadedSize(cf);
                            task.setTotalSize(totalFiles);
                            task.setSpeed(cf + "/" + totalFiles);
                        });
                    } catch (Exception e) {
                        logger.error("Failed to download: {}", trimmedFile, e);
                    }
                }

                final int finalCompleted = completedFiles;
                final int finalTotal = totalFiles;
                Platform.runLater(() -> {
                    task.setProgress(1.0);
                    task.setSpeed("完成");
                    task.setStatus(task.isCancelled() ? "已取消" : "已完成 (" + finalCompleted + "/" + finalTotal + " 文件)");
                });
            } catch (Exception e) {
                logger.error("Folder download failed", e);
                Platform.runLater(() -> task.setStatus("失败: " + e.getMessage()));
            }
        });

        return task;
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1048576) return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%.1f MB/s", bytesPerSec / 1048576.0);
    }

    public void shutdown() {
        running.set(false);
        executor.shutdownNow();
    }
}
