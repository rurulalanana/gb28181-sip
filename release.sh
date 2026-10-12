#!/usr/bin/env bash
# 发布 gb28181-sip 到 Sonatype Central
#
# 用法：./release.sh
#
# 前置条件：
#   1. GPG 私钥已导入（gpg --list-secret-keys 能看到）
#   2. ~/.m2/settings.xml 配置了 central server
#   3. Central Portal 已绑定 namespace io.github.rurulalanana

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> 检查 GPG 密钥"
if ! timeout 15 gpg --list-secret-keys >/dev/null 2>&1; then
  echo "✗ gpg 不可用（可能缺 rng.exe 或 gpg-agent 无法启动）" >&2
  echo "  Windows 用户请安装 https://gpg4win.org/ 并将 gpg.exe 置于 PATH 首位" >&2
  exit 1
fi
gpg --list-secret-keys --keyid-format LONG | grep -E "^ *[0-9A-F]{16}/" | head -3

echo "==> 构建"
mvn -B clean package

echo "==> 发布到 Central"
mvn -B deploy -Pcentral

echo
echo "✓ 上传完成"
echo "  请到 https://central.sonatype.com/publishing/deployments"
echo "  找到 deployment 确认状态为 VALIDATED 后点击 Publish"
echo
echo "  同步到 Maven Central 通常需 1~30 分钟，随后可验证："
echo "  mvn dependency:get -Dartifact=io.github.rurulalanana:gb28181-sip:$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null | tail -1)"