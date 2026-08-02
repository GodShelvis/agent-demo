#!/bin/bash
# 一键启动：后端(8081) + 前端(5173)
# 用法：./start.sh    （停止：./start.sh stop）
cd "$(dirname "$0")"

if [ "$1" = "stop" ]; then
  pkill -f "course-rag-backend" 2>/dev/null
  pkill -f "vite" 2>/dev/null
  echo "已停止后端与前端"
  exit 0
fi

set -a; . ./.env; set +a

echo "==> 启动后端 (端口 8081) ..."
cd backend
JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "java")
nohup "$JAVA17/bin/java" -jar target/course-rag-backend-1.0.0.jar \
  --spring.ai.openai.api-key="$DEEPSEEK_API_KEY" \
  > ../backend.log 2>&1 &
cd ..

echo "==> 启动前端 (端口 5173) ..."
(cd frontend && nohup npm run dev > ../frontend.log 2>&1 &)

sleep 3
echo "==> 完成！浏览器打开： http://localhost:5173"
echo "    （后端日志 backend.log / 前端日志 frontend.log）"
