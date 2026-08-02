#!/usr/bin/env bash
# 参考 web-sim 的打包方式：构建 React 管理台 -> Maven 打包可执行 jar -> 生成发布归档
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RUN_TESTS=false
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --with-tests) RUN_TESTS=true; shift ;;
    -h|--help)
      cat <<'USAGE'
Usage: scripts/build-dist.sh [--with-tests]

构建 React 管理台、打包可执行 Spring Boot jar，并生成发布归档：
  - target/codeguard-<version>.tar.gz
  - target/codeguard-<version>.zip
  - target/dist/codeguard-<version>.tar.gz
  - target/dist/codeguard-<version>.zip

发布包内容：
  - codeguard-<version>.jar（可执行）
  - run.sh / stop.sh（Linux/macOS）、run.bat / stop.bat（Windows）
  - config/application.yml（外部配置）
  - config/vulndb/codeguard-vulndb.json（离线漏洞库种子）
  - README.md

选项：
  --with-tests  执行 Maven 测试阶段
  -h, --help    显示帮助
USAGE
      exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

require_command() {
  local cmd=$1
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "缺少命令: $cmd" >&2
    exit 1
  fi
}

require_command mvn
require_command npm
require_command tar
require_command zip

if [[ -d frontend ]]; then
  echo "==> 构建 React 管理台 (frontend -> src/main/resources/static/admin)"
  (cd frontend && npm install && npm run build)
fi

if [[ "$RUN_TESTS" == "true" ]]; then
  echo "==> mvn clean package"
  mvn clean package
else
  echo "==> mvn clean package -DskipTests"
  mvn clean package -DskipTests
fi

ARTIFACT_ID="$(mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout)"
VERSION="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
APP_NAME="${ARTIFACT_ID}-${VERSION}"
DIST_ROOT="${ROOT_DIR}/target/dist"
STAGING_DIR="${DIST_ROOT}/${APP_NAME}"
JAR_PATH="${ROOT_DIR}/target/${APP_NAME}.jar"
APPLICATION_CONFIG="${ROOT_DIR}/src/main/resources/application.yml"
VULNDB_DIR="${ROOT_DIR}/config/vulndb"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "未找到 jar: $JAR_PATH" >&2
  exit 1
fi

rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR/config/vulndb"

cp "$JAR_PATH" "$STAGING_DIR/"
cp "$APPLICATION_CONFIG" "$STAGING_DIR/config/application.yml"
# 离线漏洞库种子（供离线扫描使用）
if [[ -f "$VULNDB_DIR/codeguard-vulndb.json" ]]; then
  cp "$VULNDB_DIR/codeguard-vulndb.json" "$STAGING_DIR/config/vulndb/"
fi
if [[ -f README.md ]]; then
  cp README.md "$STAGING_DIR/"
fi

# run.sh / stop.sh（Linux/macOS）
cat > "$STAGING_DIR/run.sh" <<RUNEOF
#!/bin/sh
set -eu
APP_DIR=\$(CDPATH= cd -- "\$(dirname -- "\$0")" && pwd)
cd "\${APP_DIR}"
JAR_FILE="\${APP_DIR}/${APP_NAME}.jar"
PID_FILE="\${APP_DIR}/codeguard.pid"
LOG_DIR="\${APP_DIR}/logs"
mkdir -p "\${LOG_DIR}" "\${APP_DIR}/config/workspace" "\${APP_DIR}/config/repositories" "\${APP_DIR}/config/scans"
if [ -f "\${PID_FILE}" ]; then
  OLD_PID=\$(cat "\${PID_FILE}" 2>/dev/null || true)
  if [ -n "\${OLD_PID}" ] && kill -0 "\${OLD_PID}" 2>/dev/null; then
    echo "CodeGuard 已在运行, pid=\${OLD_PID}"
    exit 0
  fi
  rm -f "\${PID_FILE}"
fi
nohup java -jar "\${JAR_FILE}" "\$@" > "\${LOG_DIR}/codeguard.out" 2> "\${LOG_DIR}/codeguard.err" &
APP_PID=\$!
echo "\${APP_PID}" > "\${PID_FILE}"
sleep 4
if ! kill -0 "\${APP_PID}" 2>/dev/null; then
  echo "启动失败，最近日志：" >&2
  tail -n 60 "\${LOG_DIR}/codeguard.err" >&2 || true
  exit 1
fi
echo "CodeGuard 已启动, pid=\${APP_PID}"
echo "管理台: http://localhost:9997/admin"
RUNEOF
chmod +x "$STAGING_DIR/run.sh"

cat > "$STAGING_DIR/stop.sh" <<STOPEOF
#!/bin/sh
set -eu
APP_DIR=\$(CDPATH= cd -- "\$(dirname -- "\$0")" && pwd)
cd "\${APP_DIR}"
PID_FILE="\${APP_DIR}/codeguard.pid"
if [ -f "\${PID_FILE}" ]; then
  PID=\$(cat "\${PID_FILE}")
  if kill -0 "\${PID}" 2>/dev/null; then
    kill "\${PID}"
    echo "CodeGuard 已停止 (pid=\${PID})"
  else
    echo "进程不存在"
  fi
  rm -f "\${PID_FILE}"
else
  echo "未找到 pid 文件"
fi
STOPEOF
chmod +x "$STAGING_DIR/stop.sh"

# run.bat / stop.bat（Windows，参考 web-sim）
convert_to_crlf() {
  awk '{ printf "%s\r\n", $0 }' "$1" > "$1.tmp" && mv "$1.tmp" "$1"
}

cat > "$STAGING_DIR/run.bat" <<RUNBATEOF
@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "APP_NAME=${APP_NAME}"
set "APP_DIR=%CD%"
set "JAR_FILE=%CD%\\%APP_NAME%.jar"
set "PID_FILE=%CD%\\codeguard.pid"
set "LOG_DIR=%CD%\\logs"
set "BOOTSTRAP_OUT_FILE=%LOG_DIR%\\codeguard.bootstrap.out"
set "BOOTSTRAP_ERR_FILE=%LOG_DIR%\\codeguard.bootstrap.err"
set "APP_ARGS=%*"
if "%CODEGUARD_START_WAIT_SECONDS%"=="" set "CODEGUARD_START_WAIT_SECONDS=4"

if not exist "%JAR_FILE%" (
  echo Jar file not found: "%JAR_FILE%"
  exit /b 1
)

if exist "%PID_FILE%" (
  set /p OLD_PID=<"%PID_FILE%"
  if not "!OLD_PID!"=="" (
    set "APP_PID=!OLD_PID!"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "\$pidValue = [int]\$env:APP_PID; \$jar = \$env:JAR_FILE; \$process = Get-CimInstance Win32_Process | Where-Object { \$_.ProcessId -eq \$pidValue }; if (\$process -and ((-not \$process.CommandLine) -or (\$process.CommandLine -like ('*' + \$jar + '*')))) { exit 0 } else { exit 1 }" >nul 2>nul
    if !ERRORLEVEL! EQU 0 (
      echo CodeGuard is already running, pid=!OLD_PID!
      exit /b 0
    )
  )
  del /f /q "%PID_FILE%" >nul 2>nul
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%CD%\\config\\workspace" mkdir "%CD%\\config\\workspace"
if not exist "%CD%\\config\\repositories" mkdir "%CD%\\config\\repositories"
if not exist "%CD%\\config\\scans" mkdir "%CD%\\config\\scans"

powershell -NoProfile -ExecutionPolicy Bypass -Command "\$jar = \$env:JAR_FILE; \$appArgs = \$env:APP_ARGS; \$quote = [char]34; \$argLine = '-jar ' + \$quote + \$jar + \$quote; if (\$appArgs) { \$argLine = \$argLine + ' ' + \$appArgs }; \$process = Start-Process -FilePath 'java' -ArgumentList \$argLine -WorkingDirectory \$env:APP_DIR -RedirectStandardOutput \$env:BOOTSTRAP_OUT_FILE -RedirectStandardError \$env:BOOTSTRAP_ERR_FILE -WindowStyle Hidden -PassThru; \$process.Id" > "%PID_FILE%"

if errorlevel 1 (
  del /f /q "%PID_FILE%" >nul 2>nul
  echo CodeGuard failed to start.
  exit /b 1
)

set /p APP_PID=<"%PID_FILE%"
timeout /t %CODEGUARD_START_WAIT_SECONDS% /nobreak >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Get-Process -Id %APP_PID% -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>nul
if errorlevel 1 (
  del /f /q "%PID_FILE%" >nul 2>nul
  echo CodeGuard failed to start. Recent log output:
  powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path \$env:BOOTSTRAP_ERR_FILE) { Get-Content \$env:BOOTSTRAP_ERR_FILE -Tail 80 }; if (Test-Path \$env:BOOTSTRAP_OUT_FILE) { Get-Content \$env:BOOTSTRAP_OUT_FILE -Tail 80 }"
  exit /b 1
)

echo CodeGuard started, pid=%APP_PID%
echo Bootstrap log: %BOOTSTRAP_OUT_FILE%
echo Bootstrap error log: %BOOTSTRAP_ERR_FILE%
echo Admin URL: http://localhost:9997/admin
RUNBATEOF

cat > "$STAGING_DIR/stop.bat" <<STOPBATEOF
@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "APP_NAME=${APP_NAME}"
set "JAR_FILE=%CD%\\%APP_NAME%.jar"
set "PID_FILE=%CD%\\codeguard.pid"
set "STOPPED=false"

if exist "%PID_FILE%" (
  set /p APP_PID=<"%PID_FILE%"
  if not "!APP_PID!"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "\$pidValue = [int]\$env:APP_PID; \$jar = \$env:JAR_FILE; \$process = Get-CimInstance Win32_Process | Where-Object { \$_.ProcessId -eq \$pidValue }; if (\$process -and ((-not \$process.CommandLine) -or (\$process.CommandLine -like ('*' + \$jar + '*')))) { Stop-Process -Id \$pidValue -Force; exit 0 } else { exit 1 }" >nul 2>nul
    if !ERRORLEVEL! EQU 0 (
      echo CodeGuard stopped, pid=!APP_PID!
      set "STOPPED=true"
    )
  )
  del /f /q "%PID_FILE%" >nul 2>nul
)

if "%STOPPED%"=="false" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "\$jar = '%JAR_FILE%'; \$processes = Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -like ('*' + \$jar + '*') }; if (\$processes) { \$processes | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }; exit 0 } else { exit 1 }" >nul 2>nul
  if !ERRORLEVEL! EQU 0 (
    echo CodeGuard stopped
    set "STOPPED=true"
  )
)

if "%STOPPED%"=="false" (
  echo CodeGuard is not running
)
STOPBATEOF

convert_to_crlf "$STAGING_DIR/run.bat"
convert_to_crlf "$STAGING_DIR/stop.bat"

# 归档
(cd "$DIST_ROOT" && tar -czf "${APP_NAME}.tar.gz" "$APP_NAME" && zip -qr "${APP_NAME}.zip" "$APP_NAME")
cp "${DIST_ROOT}/${APP_NAME}.tar.gz" "${ROOT_DIR}/target/"
cp "${DIST_ROOT}/${APP_NAME}.zip" "${ROOT_DIR}/target/"

# SHA-256 校验和（jar / tar.gz / zip）
generate_sha256() {
  local file=$1
  if command -v shasum >/dev/null 2>&1; then
    (cd "$(dirname "$file")" && shasum -a 256 "$(basename "$file")" > "$(basename "$file").sha256")
  elif command -v sha256sum >/dev/null 2>&1; then
    (cd "$(dirname "$file")" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")
  fi
}
generate_sha256 "${ROOT_DIR}/target/${APP_NAME}.jar"
generate_sha256 "${ROOT_DIR}/target/${APP_NAME}.tar.gz"
generate_sha256 "${ROOT_DIR}/target/${APP_NAME}.zip"
cp "${ROOT_DIR}/target/${APP_NAME}.tar.gz.sha256" "${DIST_ROOT}/" 2>/dev/null || true
cp "${ROOT_DIR}/target/${APP_NAME}.zip.sha256" "${DIST_ROOT}/" 2>/dev/null || true
cp "${ROOT_DIR}/target/${APP_NAME}.jar.sha256" "${DIST_ROOT}/" 2>/dev/null || true

echo ""
echo "构建完成："
echo "  ${ROOT_DIR}/target/${APP_NAME}.jar"
echo "  ${ROOT_DIR}/target/${APP_NAME}.tar.gz"
echo "  ${ROOT_DIR}/target/${APP_NAME}.zip"
echo "  ${ROOT_DIR}/target/${APP_NAME}.sha256（jar / tar.gz / zip）"
echo ""
echo "运行：tar -xzf target/${APP_NAME}.tar.gz && cd ${APP_NAME} && ./run.sh"
