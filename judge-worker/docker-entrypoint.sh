#!/bin/sh
# ============================================================
# judge-worker 容器入口：先自愈沙箱工作目录属主，再降权运行 JVM。
#
# 为什么需要（2026-11-10 死信告警根因）：
#   /cj-sandbox 是 Docker Desktop VM 侧的 bind 源，daemon 重启后会被重建为
#   root:root 0755；worker 以 uid 1001 运行时无法创建 judge-<uuid> 目录 →
#   AccessDeniedException → 编译沙箱故障（SE）→ 重试耗尽转 DEAD。
#   一次性 sandbox-work-init 不随 daemon 自启，故入口每次启动都幂等修复一次。
#
# 权限说明：仅本脚本以 root 执行（compose: user "0:0"），JVM 进程经 setpriv
# 降回 uid 1001 + 补充组 0（docker.sock 访问所需），与旧行为一致。
# ============================================================
set -eu

WORK_ROOT="${CJ_WORKER_WORK_ROOT:-/cj-sandbox}"
JUDGE_UID="$(id -u judge)"
JUDGE_GID="$(id -g judge)"

mkdir -p "$WORK_ROOT/artifacts"
# seccomp profile 由 compose 以只读文件挂载到 /opt/seccomp-src.json
if [ -f /opt/seccomp-src.json ]; then
    cp -f /opt/seccomp-src.json "$WORK_ROOT/judge-seccomp.json"
fi
chown -R "$JUDGE_UID:$JUDGE_GID" "$WORK_ROOT"

# HOME 必须指向 judge 可访问目录：若沿用 root 的 /root，沙箱派发时 docker CLI
# 会因读不到 /root/.docker/config.json 向 stderr 打印告警，污染各用例的 stderr 摘要。
export HOME=/app

# --groups=0：保持 docker.sock（root:root 0660）的组位访问能力（同 compose group_add）
exec setpriv --reuid="$JUDGE_UID" --regid="$JUDGE_GID" --groups=0 -- \
    java -XX:MaxRAMPercentage=75.0 -jar /app/app.jar
