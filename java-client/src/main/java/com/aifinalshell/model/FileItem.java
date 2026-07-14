package com.aifinalshell.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class FileItem {
    private final StringProperty name;
    private final StringProperty size;
    private final StringProperty fileType;
    private final StringProperty permissions;
    private final StringProperty modifiedTime;
    private final StringProperty ownerGroup;
    private final StringProperty fullPath;
    private final boolean isDirectory;
    private final long sizeBytes;

    public FileItem(String name, long sizeBytes, String permissions, String modifiedTime, boolean isDirectory) {
        this.name = new SimpleStringProperty(name);
        this.sizeBytes = sizeBytes;
        this.size = new SimpleStringProperty(formatSize(sizeBytes));
        this.permissions = new SimpleStringProperty(permissions);
        this.modifiedTime = new SimpleStringProperty(modifiedTime);
        this.fullPath = new SimpleStringProperty("");
        this.isDirectory = isDirectory;
        this.fileType = new SimpleStringProperty(resolveFileType(name, isDirectory));
        this.ownerGroup = new SimpleStringProperty("");
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty sizeProperty() { return size; }
    public StringProperty fileTypeProperty() { return fileType; }
    public StringProperty permissionsProperty() { return permissions; }
    public StringProperty modifiedTimeProperty() { return modifiedTime; }
    public StringProperty ownerGroupProperty() { return ownerGroup; }
    public StringProperty fullPathProperty() { return fullPath; }

    public String getName() { return name.get(); }
    public String getSize() { return size.get(); }
    public String getFileType() { return fileType.get(); }
    public String getPermissions() { return permissions.get(); }
    public String getModifiedTime() { return modifiedTime.get(); }
    public String getOwnerGroup() { return ownerGroup.get(); }
    public String getFullPath() { return fullPath.get(); }
    public boolean isDirectory() { return isDirectory; }
    public long getSizeBytes() { return sizeBytes; }

    public void setFullPath(String path) { this.fullPath.set(path); }
    public void setOwnerGroup(String value) { this.ownerGroup.set(value); }
    public void setFileType(String value) { this.fileType.set(value); }

    private String formatSize(long bytes) {
        if (isDirectory) return "<DIR>";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String resolveFileType(String name, boolean isDir) {
        if (isDir) return "文件夹";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "文件";
        String ext = name.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "txt": case "log": case "md": case "cfg": case "conf": case "ini": case "yml": case "yaml": case "json": case "xml": case "properties": case "csv":
                return "文本文件";
            case "sh": case "bash": case "zsh":
                return "Shell脚本";
            case "py":
                return "Python文件";
            case "java":
                return "Java文件";
            case "js": case "ts": case "jsx": case "tsx":
                return "JS/TS文件";
            case "go":
                return "Go文件";
            case "rs":
                return "Rust文件";
            case "c": case "cpp": case "h": case "hpp":
                return "C/C++文件";
            case "html": case "htm": case "css":
                return "网页文件";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "svg": case "ico":
                return "图片文件";
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
                return "音频文件";
            case "mp4": case "avi": case "mkv": case "mov": case "wmv":
                return "视频文件";
            case "zip": case "tar": case "gz": case "bz2": case "xz": case "7z": case "rar":
                return "压缩文件";
            case "rpm": case "deb":
                return "安装包";
            case "pdf":
                return "PDF文件";
            case "doc": case "docx":
                return "Word文档";
            case "xls": case "xlsx":
                return "Excel表格";
            case "ppt": case "pptx":
                return "PPT演示";
            case "so": case "dll": case "dylib":
                return "动态库";
            case "bin": case "exe":
                return "可执行文件";
            case "lock":
                return "锁定文件";
            default:
                return ext.toUpperCase() + "文件";
        }
    }

    @Override
    public String toString() {
        return (isDirectory ? "[DIR] " : "") + getName();
    }
}
