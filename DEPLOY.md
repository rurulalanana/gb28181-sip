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
因此 `mvn deploy` 只把构件上传到「VALIDATED」状态，还需要一步 Publish。

### 方式 A：命令行（推荐，可脚本化）

用 Central 的 Publisher API。**认证方式是坑**：必须是
`Bearer base64(用户名:密码)`，直接 `Bearer <密码>` 会返回
`401 Invalid token`。

```bash
# 从 settings.xml 读凭据并拼成 Bearer token
U=$(sed -n '/<id>central<\/id>/,/<\/server>/p' ~/.m2/settings.xml \
    | sed -n 's/.*<username>\([^<]*\)<.*/\1/p' | head -1)
P=$(sed -n '/<id>central<\/id>/,/<\/server>/p' ~/.m2/settings.xml \
    | sed -n 's/.*<password>\([^<]*\)<.*/\1/p' | head -1)
AUTH=$(printf '%s:%s' "$U" "$P" | base64)

# 查待发布的 deployment（看 deploymentId 与状态）
curl -s "https://central.sonatype.com/api/v1/publisher/deployments" \
  -H "Authorization: Bearer $AUTH"

# 发布（成功返回 204，无响应体）
curl -X POST \
  "https://central.sonatype.com/api/v1/publisher/deployment/<deploymentId>" \
  -H "Authorization: Bearer $AUTH"
```

注意端点就是 `POST /deployment/{id}` 本身 —— **没有** `/publish` 后缀
（`/deployment/{id}/publish` 是 404）。

状态流转：`VALIDATED` → `PUBLISHING` → `PUBLISHED`，
`PUBLISHED` 之后同步到 `repo1.maven.org` 还要 1~30 分钟。

完整 API 文档：<https://central.sonatype.com/api-doc>

### 方式 B：网页

1. 打开 <https://central.sonatype.com/publishing/deployments>
2. 找到刚上传的 deployment（状态为 `VALIDATED` 才可发布）
3. 点击 **Publish**

### 方式 C：完全自动

把 pom 的 `central-publishing-maven-plugin` 配置改为
`<autoPublish>true</autoPublish>`（或 deploy 时加 `-DautoPublish=true`），
上传成功即自动发布，无需额外步骤。

### 验证

```bash
# 元数据里应出现新版本
curl -s https://repo1.maven.org/maven2/io/github/rurulalanana/gb28181-sip/maven-metadata.xml \
  | grep -oE '<version>[^<]+'

# 用全新本地仓库验证依赖可解析（模拟其他使用者）
mvn dependency:get -Dartifact=io.github.rurulalanana:gb28181-sip:1.0.4 \
  -Dmaven.repo.local=/tmp/m2check
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
mvn versions:set -DnewVersion=1.0.5
mvn clean deploy -Pcentral
```

> **Central 不允许覆盖已发布版本**，重发同一版本号会被拒绝。
> 所以「改了代码但没升版本号」是本流程最容易踩的坑 ——
> 发布前先 `git log` 看有没有新提交。

> 依赖本模块的下游（如 `gb28181-sip-zlm`、`gb28181-platform-starter`）
> 记得同步 `<sip.module.version>` 并重新构建，否则 fat jar 里还是旧库。

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

**Q: `gpg: signing failed` / 任何 gpg 操作永久挂起（lockfile 死循环）**

Git for Windows 自带的 GnuPG（`/usr/bin/gpg`）**缺少 `rng.exe`**，
且 `gpg-agent.exe` 无法启动。表现为：

```
gpg: removing stale lockfile (created by NNN)
gpg: removing stale lockfile (created by NNN)   ← 无限重复直到超时
```

`gpg --list-keys`、`--gen-key`、`--detach-sign` 全部受影响。

**解决**：安装 [Gpg4win](https://gpg4win.org/)，并把它的 `gpg.exe` 置于 `PATH` 最前面：

```bash
where gpg    # 确认不再指向 Git\usr\bin
gpg --version
gpg --list-secret-keys   # 应立即返回
```

> ⚠️ 无签名上传（`publishingType=UPLOAD_UNSIGNED`）对普通账号不可用，
> 会返回 500 InternalServerError。中央仓库的签名要求无法绕过。

**Q: Central 报 `Missing md5/sha1 checksum` 或 `Missing signature`**

手动构造 bundle 时必须包含：

- 每个构件对应 `.md5`、`.sha1`、`.asc` 文件
- 根目录 `manifest.json`，列出每个文件的 `filename`/`md5`/`sha1`/`sha256`/`sha512`
- `manifest.json` 的 `signed` 字段（true 表示已签名）

且 zip 内**不能有 `META-INF`**（`jar cf` 命令会自动添加，会导致
`Bundle has content that does NOT have a .pom file: META-INF`），
请用 Python `zipfile` 或 `mvn deploy` 生成。

**Q: GitHub 推送报 403**

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