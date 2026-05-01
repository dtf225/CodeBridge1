@echo off
chcp 65001 >nul
echo ================================
echo   跨设备验证码同步工具 - 桌面端
echo ================================
echo.
echo 正在检查依赖...

if not exist "node_modules" (
    echo 首次运行，正在安装依赖...
    call npm install
    if %ERRORLEVEL% NEQ 0 (
        echo 依赖安装失败，请确认已安装 Node.js
        pause
        exit /b 1
    )
)

echo 正在启动...
echo.
call npm start
pause
