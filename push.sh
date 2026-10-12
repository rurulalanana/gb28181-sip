#!/usr/bin/env bash
# 一键推送 gb28181-sip 到 GitHub
#
# 用法：
#   ./push.sh <github_token>
# 或
#   GH_TOKEN=xxx ./push.sh
#
# 前置条件：PAT 需对该仓库授予 Contents: Read and write

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE="https://github.com/rurulalanana/gb28181-sip.git"

TOKEN="${1:-${GH_TOKEN:-}}"
if [ -z "$TOKEN" ]; then
  echo "用法: $0 <github_token>" >&2
  exit 1
fi

# ---- 1. 校验 token 的 contents 写权限 ----
echo "==> 校验权限..."
PERM=$(curl -s -m 20 -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/rurulalanana/gb28181-sip" \
  | grep -o '"push": *[a-z]*' | head -1 || true)

CODE=$(curl -s -m 20 -o /dev/null -w "%{http_code}" -X PUT \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -d '{"message":"probe","content":"cHJvYmU="}' \
  "https://api.github.com/repos/rurulalanana/gb28181-sip/contents/.probe")

if [ "$CODE" != "200" ] && [ "$CODE" != "201" ]; then
  echo "✗ token 无 Contents 写权限（HTTP $CODE）" >&2
  echo "  请在 GitHub → Settings → Developer settings → Personal access tokens" >&2
  echo "  → Fine-grained tokens → 编辑该 token → Repository permissions" >&2
  echo "  → Contents 改为 'Read and write' → 保存" >&2
  exit 1
fi
echo "  权限正常"

# 清理探测文件
SHA=$(curl -s -m 20 -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/rurulalanana/gb28181-sip/contents/.probe" \
  | grep -o '"sha": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -n "$SHA" ]; then
  curl -s -m 20 -X DELETE -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -d "{\"message\":\"remove probe\",\"sha\":\"$SHA\"}" \
    "https://api.github.com/repos/rurulalanana/gb28181-sip/contents/.probe" >/dev/null
fi

# ---- 2. 推送 ----
cd "$REPO_DIR"
echo "==> 推送提交..."
GIT_TERMINAL_PROMPT=0 git push "https://x-access-token:${TOKEN}@github.com/rurulalanana/gb28181-sip.git" main

# ---- 3. 恢复干净 remote（避免 token 留在 .git/config）----
git remote set-url origin "$REMOTE"

echo "✓ 推送完成"
git log --oneline -3