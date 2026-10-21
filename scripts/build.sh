#!/usr/bin/env bash
# 本地打包：停本地实例 -> 构建前端 -> 同步进后端 static -> 打 jar -> 校验。
# 全程不需要密码。在 Windows Git Bash 里跑：bash scripts/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
JAR="$ROOT/backend/target/logs-manage-system-1.0.0.jar"

echo "== 停本地 8081/8090（否则 jar 被占用，mvn package 报 Device or resource busy）=="
for PORT in 8081 8090; do
  PID=$(netstat -ano | grep -E ":$PORT .*LISTENING" | awk '{print $NF}' | head -1 || true)
  if [ -n "${PID:-}" ]; then
    echo "  停 $PORT (PID $PID)"
    taskkill //F //PID "$PID" >/dev/null 2>&1 || true
  fi
done

echo "== 构建前端 =="
cd "$ROOT/frontend"
npm run build

echo "== 同步进后端 static（先清空：资源名带哈希，不清会积压孤儿）=="
rm -rf "$ROOT/backend/src/main/resources/static/"*
cp -r "$ROOT/frontend/dist/"* "$ROOT/backend/src/main/resources/static/"

echo "== 打包 =="
cd "$ROOT/backend"
mvn clean package -DskipTests

echo "== 校验 =="
md5sum "$JAR"
# 字节码必须是 52（Java 8）—— 生产机 jdk1.8.0_131 只认到 52，编成 53 会 UnsupportedClassVersionError 起不来
VER=$(unzip -p "$JAR" BOOT-INF/classes/com/logsmanage/system/LogsManageSystemApplication.class | od -An -tu1 -j7 -N1 | tr -d ' ')
echo "字节码版本: $VER（必须 52）"
[ "$VER" = "52" ] || { echo "!! 字节码不是 52，中止"; exit 1; }
echo "== 打包完成：$JAR =="
echo "接着跑：bash scripts/deploy.sh"
