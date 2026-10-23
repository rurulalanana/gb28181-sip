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
# 不用 `timeout`：macOS 默认没有该命令（实测报 command not found，
# 加上 set -e 会直接把脚本打断）。这里直接调 gpg，它自身很快返回。
if ! gpg --list-secret-keys >/dev/null 2>&1; then
  echo "✗ gpg 不可用（可能缺 rng.exe 或 gpg-agent 无法启动）" >&2
  echo "  Windows 用户请安装 https://gpg4win.org/ 并将 gpg.exe 置于 PATH 首位" >&2
  exit 1
fi
gpg --list-secret-keys --keyid-format LONG | grep -E "^ *[0-9A-F]{16}/" | head -3

echo "==> 构建"
mvn -B clean package

echo "==> 发布到 Central"
mvn -B deploy -Pcentral

# ----------------------------------------------------------------------
# 自动 Publish
#
# <p>插件默认 autoPublish=false，deploy 只到 VALIDATED。以前这里只打印
# 一句「请到网站点 Publish」——而用 curl 打 Central API 很容易踩两个坑：
#   1. 端点不是 /deployment/{id}/publish（那个 404），
#      而是 POST /deployment/{id} 本身；
#   2. 认证必须是 Bearer base64(user:password)，
#      直接 Bearer password 会 401 Invalid token。
# 索性脚本里直接做掉，省得每次手动去网站点。
# ----------------------------------------------------------------------
echo
echo "==> 自动 Publish"
SETTINGS="$HOME/.m2/settings.xml"
CENTRAL_USER=$(sed -n '/<id>central<\/id>/,/<\/server>/p' "$SETTINGS" 2>/dev/null \
  | sed -n 's/.*<username>\([^<]*\)<.*/\1/p' | head -1)
CENTRAL_PASS=$(sed -n '/<id>central<\/id>/,/<\/server>/p' "$SETTINGS" 2>/dev/null \
  | sed -n 's/.*<password>\([^<]*\)<.*/\1/p' | head -1)

if [ -n "$CENTRAL_USER" ] && [ -n "$CENTRAL_PASS" ]; then
  AUTH=$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASS" | base64)
  # 列出仍在 VALIDATED 的 deployment，逐个发布
  PENDING=$(curl -s -m 30 "https://central.sonatype.com/api/v1/publisher/deployments" \
    -H "Authorization: Bearer $AUTH" \
    | tr ',' '\n' | grep -oE '"deploymentId":"[^"]+"' | cut -d'"' -f4)
  for did in $PENDING; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 90 -X POST \
      "https://central.sonatype.com/api/v1/publisher/deployment/$did" \
      -H "Authorization: Bearer $AUTH")
    case "$code" in
      204) echo "  ✓ 已提交发布 deploymentId=$did" ;;
      400) echo "  · deploymentId=$did 已发布或无需发布（HTTP 400）" ;;
      *)   echo "  ⚠ deploymentId=$did 返回 HTTP $code，请到网站确认" ;;
    esac
  done
else
  echo "  ⚠ 未从 $SETTINGS 读到 central 凭据，请手动到网站 Publish："
  echo "    https://central.sonatype.com/publishing/deployments"
fi

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null | tail -1)
echo
echo "✓ 完成（版本 $VERSION）"
echo "  状态可从 VALIDATED → PUBLISHING → PUBLISHED，"
echo "  同步到 repo1.maven.org 通常 1~30 分钟，随后可验证："
echo "  mvn dependency:get -Dartifact=io.github.rurulalanana:gb28181-sip:$VERSION"