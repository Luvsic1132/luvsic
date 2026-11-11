@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem 优先用 py 启动器，其次 python，最后 pythonw
where py >nul 2>nul
if %errorlevel%==0 (
    start "" py -3 "%~dp0main.py"
    goto :eof
)
where python >nul 2>nul
if %errorlevel%==0 (
    start "" pythonw "%~dp0main.py"
    goto :eof
)
echo 未找到 Python。请先安装 Python 3.10+ 并勾选 "Add to PATH"。
pause
