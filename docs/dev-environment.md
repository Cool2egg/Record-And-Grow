# 开发环境备忘（2026-10-20 实测）

> 这台机器上 `mvn` 和 `java` 都不在项目需要的版本上，记一份，免得每次重新找。

## 1. 已装的东西

| 项 | 路径 | 说明 |
|---|---|---|
| JDK 21 | `C:\Users\cool2egg\.jdks\temurin-21.0.12.1` | 项目要的版本；IDEA 里应该已经指向它 |
| JDK 17 | `D:\develop` | **当前 `JAVA_HOME` 指向这里**，跟项目不匹配 |
| Java（PATH） | `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe` | 17.0.15 |
| Maven 3.9.16 | `C:\Users\cool2egg\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd` | Maven Wrapper 缓存里已解压好的，能直接用 |
| RocketMQ 5.3.2 | `D:\rocketmq-5.3.2\rocketmq-all-5.3.2-bin-release` | 本机已有完整发行版 |

## 2. 一次性配好（推荐）

把 Maven 挪到好记的位置，然后写进用户环境变量：

```powershell
Copy-Item 'C:\Users\cool2egg\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0' 'D:\apache-maven-3.9.16' -Recurse
[Environment]::SetEnvironmentVariable('MAVEN_HOME','D:\apache-maven-3.9.16','User')
[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Users\cool2egg\.jdks\temurin-21.0.12.1','User')
$userPath = [Environment]::GetEnvironmentVariable('Path','User')
[Environment]::SetEnvironmentVariable('Path', "$userPath;D:\apache-maven-3.9.16\bin", 'User')
```

**新开一个终端**再验证：

```powershell
mvn -v
# 期望：Apache Maven 3.9.16 / Java version: 21.0.12.1
java -version
# 若 PATH 里 Oracle javapath 排在前面，看到的还是 17，这不影响 Maven（它读 JAVA_HOME）
```

## 3. 不想改环境变量时（临时用）

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21.0.12.1"
$mvn = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd"
& $mvn -o -Dtest='!*IntegrationTest' test      # 单测
& $mvn -Dtest='*IntegrationTest' test          # 集成测试（要 Docker）
```

`-o` 是离线模式：本地仓库已经有模块一到四的全部依赖，能省掉一次网络往返；缺依赖时去掉 `-o`。

## 4. 沙箱限制（DSH 会话内）

在 DSH 的受限会话里跑 Maven 会失败：

```
Failed to execute goal ... maven-resources-plugin ... 
FileNotFoundException: D:\RecordAndGrow\target\classes\application.yml (拒绝访问)
```

原因不是 Maven，是 `target/` 这个**已存在**的目录缺少沙箱进程那条权限项（新建的目录反而正常，实测在仓库根目录新建目录并写入是通的）。`target/` 不能改名也不能删除，所以会话内绕不过去。

同样的限制也适用于 `.git/` 和 `src/`（2026-10-20 实测，写入均被拒）。也就是说，**受限会话里跑不了 `mvn`、`git commit`，只有文件编辑工具能改源码**。

处理方式二选一：

1. **本机终端或 IDEA 里跑**（不受沙箱限制）；
2. 把会话切到**完全权限**（full access）——模块五要跑 Maven、git 和 Docker/RocketMQ，**建议全程用完全权限**。

> 2026-10-20 已对 `target/` 跑过一次权限修复脚本，只补上了「写所有权」（`writeOwner`），没有解决这条；恢复脚本在 `D:\acl-recovery\`。

## 5. RocketMQ 本机版

```powershell
cd 'D:\rocketmq-5.3.2\rocketmq-all-5.3.2-bin-release'
# 先起 NameServer（新开一个窗口）
.\bin\mqnamesrv.cmd
# 再起 Broker（另一个窗口，需要 JAVA_HOME 指向 JDK 21 或 17 均可）
.\bin\mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true
```

默认 namesrv 地址与设计稿一致：`127.0.0.1:9876`。用本机版时不需要 `docker compose` 起 rocketmq 服务。
