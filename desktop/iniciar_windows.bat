@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo === VozLimpia ===
if not exist .venv (
  echo Creando entorno (necesitas Python 3.10 a 3.12)...
  py -3 -m venv .venv || python -m venv .venv
)
call .venv\Scripts\activate
if not exist .venv\instalado.txt (
  echo Instalando (solo la primera vez, puede tardar varios minutos)...
  python -m pip install --upgrade pip
  where nvidia-smi >nul 2>nul
  if %errorlevel%==0 (
    pip install "audio-separator[gpu]"
  ) else (
    pip install "audio-separator[cpu]"
  )
  pip install -r requirements.txt
  echo ok> .venv\instalado.txt
)
python app.py
pause
