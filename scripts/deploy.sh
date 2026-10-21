#!/usr/bin/env bash
# 发布到生产 180：上传 -> 校验 MD5 -> 备份旧 jar -> 停旧 -> 替换 -> 启动 -> 健康检查。
# 在你自己的终端跑：bash scripts/deploy.sh
# ssh/scp 会各弹一次密码输入（共两次），这是正常的 —— 直接输密码即可。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

HOST=root@127.0.0.1
REMOTE_DIR=/root/logs_manage_system_release
JAR="$ROOT/backend/target/logs-manage-system-1.0.0.jar"
JAVA=/usr/java/jdk1.8.0_131/bin/java

[ -f "$JAR" ] || { echo "!! 找不到 jar，先跑 bash scripts/build.sh"; exit 1; }

LOCAL_MD5=$(md5sum "$JAR" | awk '{print $1}')
echo "本地 MD5: $LOCAL_MD5"

echo "== 上传（传成 .jar.new，先不覆盖在跑的）=="
scp "$JAR" "$HOST:$REMOTE_DIR/app/logs-manage-system-1.0.0.jar.new"

echo "== 远端替换 + 启动（下面会再要一次密码）=="
ssh "$HOST" 'bash -s' "$LOCAL_MD5" <<'REMOTE'
set -euo pipefail
LOCAL_MD5="$1"
DIR=/root/logs_manage_system_release
JAVA=/usr/java/jdk1.8.0_131/bin/java
cd "$DIR/app"

# 校验上传完整性
REMOTE_MD5=$(md5sum logs-manage-system-1.0.0.jar.new | awk '{print $1}')
echo "远端 MD5: $REMOTE_MD5"
[ "$REMOTE_MD5" = "$LOCAL_MD5" ] || { echo "!! MD5 不一致，上传损坏，中止"; rm -f logs-manage-system-1.0.0.jar.new; exit 1; }

# 备份当前在跑的 jar（带时间戳）
if [ -f logs-manage-system-1.0.0.jar ]; then
  TS=$(date +%Y%m%d_%H%M%S)
  cp -p logs-manage-system-1.0.0.jar "$DIR/backup/logs-manage-system-1.0.0.jar.$TS"
  echo "已备份: backup/logs-manage-system-1.0.0.jar.$TS"
fi

# 停旧进程：必须精确匹配本应用 jar，别误杀同机 /opt/journal-system
MATCH=$(ps -ef | grep -F 'logs-manage-system-1.0.0.jar' | grep -v grep | grep -v '.jar.new' || true)
echo "$MATCH"
CNT=$(echo -n "$MATCH" | grep -c . || true)
if [ "$CNT" -gt 1 ]; then echo "!! 匹配到多个进程，人工确认后再发，中止"; exit 1; fi
PID=$(echo "$MATCH" | awk '{print $2}')
if [ -n "${PID:-}" ]; then
  kill "$PID"
  for i in $(seq 1 15); do kill -0 "$PID" 2>/dev/null || { echo "旧进程已退出"; break; }; sleep 1; done
  kill -0 "$PID" 2>/dev/null && { echo "15s 未退出，强杀"; kill -9 "$PID"; sleep 2; }
fi

# 替换
mv -f logs-manage-system-1.0.0.jar.new logs-manage-system-1.0.0.jar

# 启动：egd urandom 不是可选项，不加启动要 ~87 秒（熵池阻塞）
setsid nohup "$JAVA" -Djava.security.egd=file:/dev/./urandom -jar logs-manage-system-1.0.0.jar > app.log 2>&1 < /dev/null &
sleep 2
ps -ef | grep -F 'logs-manage-system-1.0.0.jar' | grep -v grep || { echo "!! 进程没起来，看 app.log"; exit 1; }

# 健康检查：给足 60 次 x 3 秒（启动慢，别过早判失败）
echo "== 健康检查 =="
for i in $(seq 1 60); do
  C=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/ || true)
  echo "$i: $C"
  [ "$C" = "200" ] && { echo "== 发布成功 =="; break; }
  sleep 3
done
REMOTE

echo "== deploy.sh 结束 =="
echo "回退：ssh $HOST，把 backup/ 里对应时间戳的 jar 拷回 app/logs-manage-system-1.0.0.jar，重复停旧+启动两步。"
