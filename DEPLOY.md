# 发布指南

本文档说明如何把 `gb28181-sip` 发布到 **Sonatype Central**（Maven 中央仓库），
以及备用的 GitHub Packages / 阿里云效通道。

---

## 1. 前置条件

| 项 | 要求 |
|---|---|
| Sonatype 账号 | 在 [central.sonatype.com](https://central.sonatype.com/) 注册并验证邮箱 |
| Namespace | 已在 Central Portal 绑定 `io.github.rurulalanana`（**一次性操作**） |
| GPG 密钥 | 中央仓库**强制要求**所有构件签名 |
| Maven | 3.6.3+ |

> ⚠️ 若尚未在 Central Portal 绑定 namespace，需先访问
> https://central.sonatype.com/publishing/deployments → 右上角账户菜单 →
> **Add Namespace**，填入 `io.github.rurulalanana`，验证域名或 GitHub 账号。
> 绑定后才能发布该 groupId 下的构件。

---

## 2. 生成 GPG 密钥

```bash
gpg --full-generate-key
# 选择：RSA and RSA / 4096 / 永不过期 / 姓名 / 邮箱
```

查看指纹：

```bash
gpg --list-secret-keys --keyid-format LONG
```

导出公钥并上传到 keyserver：

```bash
gpg --armor --export <指纹> > gb28181-sip-public.asc
gpg --keyserver keyserver.ubuntu.com --send-keys <指纹>
```

也可在 <https://keyserver.ubuntu.com> 网页粘贴公钥。

---

## 3. 配置 settings.xml

编辑 `~/.m2/settings.xml`：

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>你的 Sonatype 用户名</username>
      <password>你的 Sonatype 密码</password>
    </server>

    <!-- 使用 GPG 私钥时需要（Windows 尤其需要）-->
    <server>
      <id>gpg.passphrase</id>
      <passphrase>你的 GPG 私钥口令</passphrase>
    </server>
  </servers>
</settings>
```

密码位置读取顺序：

- 环境变量 `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`
- 或直接写在 `settings.xml`（如上）
- Central Portal 也支持 **User Token**：`username` 填 token 名，`password` 填 token 值

---

## 4. 发布

```bash
# 先本地验证
mvn clean verify

# 发布到 Sonatype Central
mvn clean deploy -Pcentral
```

若私钥有口令，可用环境变量避免交互：

```bash
# macOS / Linux
export GPG_PASSPHRASE=你的口令
mvn clean deploy -Pcentral

# Windows CMD
set GPG_PASSPHRASE=你的口令
mvn clean deploy -Pcentral
```

构建产物：

```
target/gb28181-sip-1.0.0.jar           主构件
target/gb28181-sip-1.0.0-sources.jar  源码
target/gb28181-sip-1.0.0-javadoc.jar  文档
```

每个构件对应的 `.asc` 签名文件由 `maven-gpg-plugin` 自动生成。

---

## 5. 完成发布

`central-publishing-maven-plugin` 的 `autoPublish` 默认为 `false`，
因此上传后需**手动发布**：

1. 打开 <https://central.sonatype.com/publishing/deployments>
2. 找到刚上传的 deployment（状态为 `VALIDATED` 才可发布）
3. 点击 **Publish**

若配置为 `autoPublish=true`，上传成功即自动发布。

同步到 Maven Central 通常需要 1~30 分钟，之后可这样验证：

```bash
mvn dependency:get -Dartifact=io.github.rurulalanana:gb28181-sip:1.0.0
```

---

## 6. 备用通道

### GitHub Packages

```bash
mvn deploy -Pgithub
```

`settings.xml` 需配置：

```xml
<server>
  <id>github</id>
  <username>rurulalanana</username>
  <password>需含 packages:write 权限的 PAT</password>
</server>
```

消费方仓库地址：

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/rurulalanana/gb28181-sip</url>
</repository>
```

### 阿里云效 Packages

```bash
mvn deploy -Paliyun-rds
```

`settings.xml` 需配置 `aliyun-rds` server。消费方仓库地址：

```xml
<repository>
  <id>aliyun-rds</id>
  <url>https://packages.aliyun.com/maven/repository/</url>
</repository>
```

---

## 7. 版本升级

修改 `pom.xml` 中的 `<version>` 后发布新版本：

```bash
mvn versions:set -DnewVersion=1.0.1
mvn clean deploy -Pcentral
```

> Central 不允许覆盖已发布版本，重发同一版本号会被拒绝。

---

## 8. 常见问题

**Q: `gpg: agent refused operation` / 密钥生成卡死**

Windows 上 Git for Windows 自带的 GnuPG 可能缺少 `rng.exe`，
导致任何涉及随机数的操作（生成密钥、签名）永久挂起。

解决：安装完整的 [Gpg4win](https://gpg4win.org/)，
并把 `gpg.exe` 所在目录加到 `PATH` 最前面，然后：

```bash
where gpg   # 确认路径
gpg --version
```

**Q: `gpg: signing failed: Inappropriate ioctl for device`**

命令行签名时的终端问题，加 `--pinentry-mode loopback`（POM 已配置）。

**Q: 上传报 401/403**

- 401：用户名或密码错误，检查 `settings.xml` 的 `central` server
- 403：namespace 未在 Central Portal 绑定，或 groupId 与绑定值不一致

**Q: 发布后 Maven Central 拉不到**

同步有延迟（最长约 30 分钟）。也可用
<https://search.maven.org/solrsearch/select?q=g:io.github.rurulalanana>
确认是否已同步。

**Q: GitHub 推送报 403**

fine-grained PAT 需要为该仓库授予 **Contents: Read and write** 权限。
仅勾选 Metadata 只有读权限。