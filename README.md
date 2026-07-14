# 🌟 StarShell - AI原生运维终端

> 让 AI 成为你的专属运维工程师

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey.svg)](https://github.com/zongyu888/starshell)

---

## 📖 项目简介

StarShell 是全球首个 **AI 原生运维终端**，将 AI 大模型深度集成到终端操作中。不同于传统 SSH 客户端，StarShell 让 AI 直接在终端中实时操作服务器，提供沉浸式的运维体验。

### 🎯 核心创新

**AI 在终端中实时操作** - 不是给你命令让你复制粘贴，而是 AI 直接在终端中逐字符输入并执行命令，就像一个真实的运维工程师在旁边操作！

---

## ✨ 核心功能

### 1. 🤖 AI 实时操作终端
- **打字机效果**：AI 逐字符输入命令，沉浸式观看 AI 操作过程
- **智能对话**：自然语言交流，AI 理解你的运维需求
- **实时反馈**：AI 根据命令执行结果自动调整策略
- **可见可控**：全程可见 AI 操作，随时可以干预或停止

### 2. 🛠️ 20+ 专业运维工具
- **execute_shell** - 执行 Shell 命令
- **read_log** - 智能日志分析
- **manage_service** - 服务管理（启动/停止/重启）
- **install_package** - 软件包安装
- **upload_file / download_file** - SFTP 文件传输
- **check_port / check_process** - 端口和进程检查
- **disk_usage / memory_usage** - 磁盘和内存分析
- **network_diagnose** - 网络诊断
- **security_audit** - 安全审计
- 更多工具持续增加...

### 3. 📊 实时监控面板
连接服务器后自动启动监控：
- CPU 使用率、内存占用、磁盘空间
- 网络上传下载速度
- 最耗资源的进程排行
- 系统运行时间、负载平均
- 异常自动告警

### 4. 🧠 智能日志分析
- AI 自动读取日志文件
- 识别错误类型和错误级别
- 分析根本原因
- 直接给出修复命令

### 5. 🚀 一键部署
- 自动生成完整部署脚本
- 包含环境检查、备份、安装依赖、配置服务、健康检查
- 支持回滚脚本生成
- 支持 Spring Boot、Nginx、MySQL、Docker 等常用服务

### 6. 🔒 安全审计
- 自动检查开放端口、SSH 配置、用户权限
- 防火墙状态检查
- 生成专业审计报告
- 提供修复命令和加固建议

### 7. ⚡ 性能优化建议
- CPU、内存、磁盘、网络瓶颈分析
- 内核参数调优建议
- 进程优先级调整
- 内存配置优化

### 8. 🔌 多模型支持
支持所有主流 AI 模型：
- **OpenAI**：GPT-4o、GPT-4 Turbo、GPT-3.5
- **Anthropic**：Claude 3.5 Sonnet、Claude 3 Opus
- **Ollama**：本地部署模型
- **Custom**：自定义 API 服务器
- **Free**：免费模型支持

---

## 🖼️ 界面预览

```
┌─────────────────────────────────────────────────────────────────┐
│ 🖥️ Server Monitor          │ 💬 AI Terminal                    │
│ ┌───────────────────────┐   │ ┌─────────────────────────────┐   │
│ │ CPU: 45.2% ████████░░  │   │ │ User: 帮我检查Nginx日志     │   │
│ │ Mem: 62.1% ██████████░ │   │ │ AI: 好的,让我看看...       │   │
│ │ Disk: 78.5% ███████████ │   │ │ $ tail -100 /var/log/nginx  │   │
│ │ Net: ↑1.2MB/s ↓45KB/s  │   │ │ [2024-07-14] ERROR: ...    │   │
│ ├───────────────────────┤   │ │ 发现问题: 配置文件错误      │   │
│ │ Top Processes:         │   │ │ 修复命令: vim /etc/nginx/   │   │
│ │ 1. java (2.1GB)        │   │ └─────────────────────────────┘   │
│ │ 2. nginx (512MB)       │   │                                    │
│ │ 3. mysql (380MB)       │   │ ┌─────────────────────────────┐   │
│ └───────────────────────┘   │ │ 💡 建议命令:                 │   │
│                              │ │ systemctl restart nginx      │   │
│                              │ │ [执行] [复制] [解释]        │   │
│                              │ └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🚀 快速开始

### 系统要求
- **操作系统**：Windows 10/11、macOS 10.15+、Linux
- **Java**：JDK 17 或更高版本（已内置运行时，无需单独安装）
- **网络**：需要连接互联网以使用 AI 功能

### 下载安装

#### Windows 用户

1. 下载 `StarShell-v1.0.2-windows.zip`
2. 解压到任意目录
3. 双击运行 `StarShell.exe`

> **⚠️ 关于 SmartScreen 安全提示**
>
> 由于这是开源项目，没有购买昂贵的代码签名证书，Windows 可能会显示 "Microsoft Defender SmartScreen 阻止了无法识别的应用启动" 的警告。
>
> **这是正常的，请按以下步骤运行：**
> 1. 点击弹窗中的 **"更多信息"**
> 2. 点击 **"仍要运行"**
> 3. 即可正常启动 StarShell
>
> **为什么安全？**
> - 代码完全开源，可在 GitHub 查看所有源代码
> - 构建过程透明，由 GitHub Actions 自动构建
> - 不包含任何恶意代码
> - 你可以自行从源代码构建

#### macOS 用户

1. 下载 `StarShell-v1.0.2-macos.zip`
2. 解压得到 `StarShell.app`
3. 将 `StarShell.app` 拖到 `应用程序` 文件夹
4. 右键点击 → **"打开"**（首次运行需要这样操作）

> **⚠️ 关于 macOS 安全提示**
>
> 首次运行时，macOS 可能会提示 "无法验证开发者"。请右键点击 → **"打开"** → **"打开"** 即可。

#### Linux 用户

1. 下载 `StarShell-v1.0.2-linux.zip`
2. 解压到任意目录
3. 运行：
   ```bash
   chmod +x StarShell/bin/StarShell
   ./StarShell/bin/StarShell
   ```

### 从源码构建
```bash
# 克隆项目
git clone https://github.com/zongyu888/starshell.git
cd starshell/java-client

# 构建项目（需要 JDK 17+）
mvn clean package -DskipTests

# 生成的安装包位于：
# target/installer/StarShell/StarShell.exe (Windows)
# target/installer/StarShell/bin/StarShell (Linux/macOS)
```

---

## 🎯 使用指南

### 1. 配置 AI 模型
首次运行时，点击右上角的 **⚙️ 设置** 按钮，选择你喜欢的 AI 模型：

- **OpenAI GPT-4o**：最强大，推荐使用
- **Claude 3.5 Sonnet**：性价比高，推理能力强
- **Ollama**：完全免费，本地运行，隐私性最好
- **免费模型**：无需 API Key，开箱即用

### 2. 添加服务器
点击左侧的 **➕ 新建连接** 按钮：
1. 输入服务器 IP 地址
2. 输入 SSH 端口（默认 22）
3. 输入用户名和密码（或选择 SSH Key）
4. 点击 **连接**

### 3. 开始使用 AI
连接成功后，在右侧 AI 终端中输入你的需求：

**示例对话**：
```
用户: 帮我检查一下服务器的内存使用情况

AI: 好的，让我来查看一下...
$ free -h
              total        used        free      shared  buff/cache   available
Mem:           15Gi       8.2Gi       2.1Gi       512Mi       5.1Gi       6.5Gi
Swap:         8.0Gi       2.1Gi       5.9Gi

当前内存使用情况：
- 总内存：15GB
- 已使用：8.2GB (54.7%)
- 可用内存：6.5GB

内存使用正常，还有充足的空间。
```

### 4. 高级功能

#### 智能部署
```
用户: 帮我部署一个 Spring Boot 项目

AI: 好的，我来帮你部署。让我先生成部署脚本...

部署脚本已生成，包含以下步骤：
1. ✅ 环境检查（Java、Maven）
2. ✅ 备份旧版本
3. ✅ 上传新版本
4. ✅ 停止旧服务
5. ✅ 启动新服务
6. ✅ 健康检查

是否执行这个部署脚本？[执行] [修改] [取消]
```

#### 安全审计
```
用户: 帮我做一个安全审计

AI: 好的，让我来检查服务器的安全状况...

安全审计报告：
🔴 高危：SSH 允许 root 登录
🟡 中危：防火墙未启用
🟡 中危：存在弱密码用户

修复建议：
1. 禁用 root SSH 登录：
   $ sudo sed -i 's/PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config
   
2. 启用防火墙：
   $ sudo systemctl start firewalld
   $ sudo systemctl enable firewalld

[一键修复所有问题]
```

---

## 🏗️ 技术架构

### 技术栈
- **语言**：Java 17
- **框架**：JavaFX 17（图形界面）
- **SSH 库**：JSch 0.1.55
- **网络库**：Netty 4.1
- **数据库**：H2 Database（本地存储）
- **构建工具**：Maven
- **打包工具**：jpackage（原生安装包）

### 项目结构
```
starshell/
├── java-client/                 # Java 客户端代码
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/aifinalshell/
│   │   │   │   ├── ai/          # AI 服务核心
│   │   │   │   ├── agent/       # Agent 和工具系统
│   │   │   │   ├── provider/    # AI 模型提供商
│   │   │   │   ├── controller/  # UI 控制器
│   │   │   │   ├── ssh/         # SSH 连接管理
│   │   │   │   ├── monitor/     # 服务器监控
│   │   │   │   └── deploy/      # 部署服务
│   │   │   └── resources/       # 资源文件
│   │   └── test/                # 测试代码
│   └── pom.xml                  # Maven 配置
├── docs/                        # 文档
└── README.md                    # 项目说明
```

---

## 🤝 贡献指南

欢迎贡献代码、报告 Bug、提出新功能建议！

### 如何贡献
1. Fork 本仓库
2. 创建你的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交你的修改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建一个 Pull Request

### 开发环境搭建
```bash
# 1. 克隆你的 Fork
git clone https://github.com/你的用户名/starshell.git

# 2. 安装 JDK 17+
# Windows: 从 https://adoptium.net/ 下载安装
# macOS: brew install openjdk@17
# Linux: sudo apt install openjdk-17-jdk

# 3. 导入 IDE
# IntelliJ IDEA: File -> Open -> 选择项目根目录
# Eclipse: Import -> Maven -> Existing Maven Projects

# 4. 运行项目
# 找到 AiFinalShellApp.java，右键 -> Run
```

---

## 📝 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源协议。

---

## 🙏 致谢

感谢以下开源项目：
- [JSch](http://www.jcraft.com/jsch/) - Java SSH 库
- [JavaFX](https://openjfx.io/) - Java 图形界面框架
- [Netty](https://netty.io/) - 高性能网络库
- [OpenAI](https://openai.com/) - GPT 系列 AI 模型
- [Anthropic](https://www.anthropic.com/) - Claude 系列 AI 模型

---

## 📞 联系方式

- **GitHub Issues**: https://github.com/zongyu888/starshell/issues
- **邮箱**: starshell@example.com

---

## ⭐ Star History

如果这个项目对你有帮助，请给个 ⭐ Star 支持一下！

[![Star History Chart](https://api.star-history.com/svg?repos=zongyu888/starshell&type=Date)](https://star-history.com/#zongyu888/starshell&Date)

---

**Made with ❤️ by StarShell Team**