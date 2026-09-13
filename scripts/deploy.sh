#!/usr/bin/env bash
# 部署 companion.luxera.top (需 root)
# G1 物理拆分: 可执行 jar 由 chat 项目组装(聊天+应用平台同进程; 仿真 Agent 平台在
# simulation-agent-platform 仓库独立部署)。构建走 Gradle。
set -euo pipefail

BACKEND_JAR=/home/ubuntu/claude-workspace/chat-platform/chat/build/libs/chat-platform-1.0.0.jar
GRADLE=/home/ubuntu/tools/gradle/gradle-8.14.3/bin/gradle
FRONTEND_DIST=/home/ubuntu/claude-workspace/chat-platform/frontend/dist
NGINX_SRC=/home/ubuntu/claude-workspace/infrastructure/nginx/sites/companion.conf

echo "==> 1. 编译打包(Gradle, chat 平台)"
cd /home/ubuntu/claude-workspace/chat-platform
$GRADLE -q :chat:bootJar
test -f "$BACKEND_JAR" || { echo "打包失败: $BACKEND_JAR 不存在"; exit 1; }

echo "==> 2. 前端静态产物 → /var/www/companion"
mkdir -p /var/www/companion
rm -rf /var/www/companion/*
cp -r "$FRONTEND_DIST"/* /var/www/companion/
chown -R www-data:www-data /var/www/companion

echo "==> 3. nginx 配置 → /etc/nginx/conf.d/"
cp "$NGINX_SRC" /etc/nginx/conf.d/companion.conf

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
WorkingDirectory=/home/ubuntu/claude-workspace/chat-platform/backend
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

echo "==> 6. nginx 校验 + 重载"
nginx -t
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

echo "✅ 部署完成: https://companion.luxera.top"