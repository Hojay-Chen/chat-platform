#!/usr/bin/env bash
# 部署 companion.luxera.top (需 root)
# G1 物理拆分: 可执行 jar 由 chat 项目组装(聊天+应用平台同进程; 仿真 Agent 平台在
# simulation-agent-platform 仓库独立部署)。构建走 Gradle。
set -euo pipefail

BACKEND_JAR=/home/ubuntu/claude-workspace/chat-platform/chat/build/libs/chat-platform-1.0.0.jar
GRADLE=/home/ubuntu/tools/gradle/gradle-8.14.3/bin/gradle
FRONTEND_DIST=/home/ubuntu/claude-workspace/chat-platform/frontend/dist
# nginx 配置的源头在本仓 deploy/nginx/(与仓 2 同一约定)。
# 原先指向 infrastructure/nginx/sites/ —— 那棵树是 Docker Compose 时代的遗物
# (见 workspace CLAUDE.md「infrastructure/ 目录过时」), 内容停在 8 月:
# 它没有 G5 分流要的 /agent/api 落点, 于是每次跑 deploy.sh 都会把线上配置
# 悄悄倒退回拆分前。源头必须在版本库跟随本仓演进。
NGINX_SRC=/home/ubuntu/claude-workspace/chat-platform/deploy/nginx/companion.conf
NGINX_DST=/etc/nginx/conf.d/companion.conf
WEB_ROOT=/var/www/companion
BACKUP_DIR=/var/backups/luxera-companion

echo "==> 1. 编译打包(Gradle, chat 平台)"
cd /home/ubuntu/claude-workspace/chat-platform
$GRADLE -q :chat:bootJar
test -f "$BACKEND_JAR" || { echo "打包失败: $BACKEND_JAR 不存在"; exit 1; }

test -f "$FRONTEND_DIST/index.html" || { echo "缺前端产物 $FRONTEND_DIST —— 先 cd frontend && npm run build"; exit 1; }

echo "==> 2. 前端静态产物 → $WEB_ROOT"
mkdir -p "$BACKUP_DIR"
# 覆盖前留一份 —— 线上产物被换掉后没有回头路, 而回滚往往正是最需要它的时候
[ -d "$WEB_ROOT" ] && [ -n "$(ls -A "$WEB_ROOT" 2>/dev/null)" ] \
  && tar czf "$BACKUP_DIR/www-$(date +%Y%m%d-%H%M%S).tgz" -C "$WEB_ROOT" . \
  && echo "    旧产物已备份到 $BACKUP_DIR"
mkdir -p "$WEB_ROOT"
rm -rf "${WEB_ROOT:?}"/*
cp -r "$FRONTEND_DIST"/* "$WEB_ROOT/"
chown -R www-data:www-data "$WEB_ROOT"

echo "==> 3. nginx 配置 → $NGINX_DST"
[ -f "$NGINX_DST" ] && cp "$NGINX_DST" "$BACKUP_DIR/nginx-$(date +%Y%m%d-%H%M%S).conf"
cp "$NGINX_SRC" "$NGINX_DST"
# 只在装上去之后校验; 校验不过就把备份放回去, 不留一个坏配置在 conf.d 里
if ! nginx -t; then
  echo "  ✗ nginx 配置校验失败, 回滚"
  [ -f "$(ls -t "$BACKUP_DIR"/nginx-*.conf 2>/dev/null | head -1)" ] \
    && cp "$(ls -t "$BACKUP_DIR"/nginx-*.conf | head -1)" "$NGINX_DST"
  exit 1
fi

echo "==> 4. /etc/hosts 本机解析 (幂等)"
grep -q 'companion.luxera.top' /etc/hosts \
  || echo '127.0.0.1 companion.luxera.top   # 伴侣平台' >> /etc/hosts

echo "==> 5. systemd 服务 luxera-companion-backend"
cat > /etc/systemd/system/luxera-companion-backend.service <<EOF
[Unit]
Description=Luxera Companion Platform Backend (Spring Boot)
After=network.target
Wants=network.target

[Service]
Type=simple
User=ubuntu
# 敏感配置(如 DEEPSEEK_API_KEY)放在 /etc/companion/.env, 不入 git
EnvironmentFile=/etc/companion/.env
# 跨仓共享密钥(JWT_SECRET / AGENT_PLATFORM_INTERNAL_KEY / SIMULATOR_TOKEN_SECRET)。
# **少了这一行 8081 会退回 application.yml 里的 dev 默认值**, 而 8091/8092 读的是
# 共享文件 —— 于是 8081 签的 JWT 在 8091 验不过(403)、/internal 的 HMAC 签名也
# 对不上。症状是"跨服务调用全线 403", 但每个服务单看都是健康的, 极难归因
# (G7 部署时真的踩到了)。列在 /etc/companion/.env 之后, 冲突时以共享文件为准。
EnvironmentFile=-/etc/luxera/shared-secrets.env
# 必须是**存在**的目录 —— 原先指向 .../chat-platform/backend, 而 backend/ 在
# G1 物理拆分后已不存在(拆成 chat/ application/ common/ contract/ …)。
# systemd 起不来会报 status=200/CHDIR + "Changing to the requested working
# directory failed", 然后按 Restart=always 每 5s 重试一次, 无声地转上几个月。
WorkingDirectory=/home/ubuntu/claude-workspace/chat-platform
ExecStart=/usr/bin/java -jar $BACKEND_JAR
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable luxera-companion-backend >/dev/null 2>&1 || true
systemctl restart luxera-companion-backend

echo "==> 6. nginx 重载"
systemctl reload nginx

echo "==> 7. 等待后端就绪"
for i in $(seq 1 60); do
  if curl -sf http://127.0.0.1:8081/api/health >/dev/null 2>&1; then
    echo "后端 UP"
    break
  fi
  sleep 1
done
curl -s http://127.0.0.1:8081/api/health && echo
# 仓 2 的功能服务(8091)是另一个部署单元, 这里只如实报告不代管。
#
# 探活不能照抄 8081 的 /api/health —— 8091 **没有**这个映射, 访问它是 404。
# 但 404 恰恰说明服务活着: 端口没人监听时 curl 根本拿不到状态码(000)。
# 所以判据是"有没有 HTTP 响应", 不是"状态码是不是 200"。
CODE8091=$(curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8091/ 2>/dev/null || echo 000)
if [ "$CODE8091" != "000" ]; then
  echo "仿真 Agent 服务(8091) UP (探活实得 $CODE8091 —— 它没有 /api/health, 有响应即活着)"
else
  echo "⚠ 仿真 Agent 服务(8091) 未起 —— /agent/api/** 会 502(G5 分流的伴侣域全在这里)"
  echo "   拉起: sudo systemctl start luxera-agent-server"
fi

echo "==> 8. 分流落点体检"
# reload 后老 worker 还会短暂服务几秒, 立刻断言会假阴性 —— 先等健康端点稳定
for _ in $(seq 1 8); do
  curl -sf http://127.0.0.1:8081/api/health >/dev/null 2>&1 && break || sleep 2
done
# 关键断言: /agent/api/ 必须**不再**落到 SPA 回退。
# 少这段 location 时它返回的是 index.html(HTTP 200) —— 不是 404, 所以肉眼
# 完全看不出问题, 只有前端拿着 HTML 去 JSON.parse 时才炸。
#
# ⚠ 必须打 https —— 打 http 拿到的是 80 端口那条 `return 301`, 301 会让这个
# 断言"通过"却什么也没验证(第一版就是这么被骗过去的)。用 -k 加 Host 头直连
# 本机 443, 绕过 DNS 与证书, 把变量收敛到"nginx 怎么路由"这一件事上。
SPA=$(curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1/agent/api/companions \
        -H 'Host: companion.luxera.top')
if [ "$SPA" = "502" ] || [ "$SPA" = "401" ] || [ "$SPA" = "403" ]; then
  echo "    ✓ /agent/api/** 已路由到 8091 (无 SPA 回退, 实得 $SPA)"
elif [ "$SPA" = "200" ]; then
  echo "    ✗ /agent/api/** 实得 200 —— 回退到 index.html 了, 检查 $NGINX_DST 里的 /agent/api/ location"
else
  echo "    ? /agent/api/** 实得 $SPA (既非回退也非预期错误码, 人工看一眼)"
fi

echo "✅ 部署完成: https://companion.luxera.top"