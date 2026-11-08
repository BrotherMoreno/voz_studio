#!/usr/bin/env bash
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
source .venv/bin/activate
if [ ! -f .venv/instalado.txt ]; then
  python -m pip install --upgrade pip
  if command -v nvidia-smi >/dev/null 2>&1; then pip install "audio-separator[gpu]"; else pip install "audio-separator[cpu]"; fi
  pip install -r requirements.txt && echo ok > .venv/instalado.txt
fi
python app.py "$@"
