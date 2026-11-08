"""
VozLimpia - app local para rescatar la voz en videos de conciertos con audio saturado.

Uso:
    python app.py                      -> abre la interfaz web en tu navegador
    python app.py video.mp4            -> modo consola (usa IA)
    python app.py video.mp4 --rapido   -> modo consola sin IA (más veloz, menos preciso)
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from audiofix import MODELOS, procesar

CARPETA_SALIDA = Path.home() / "VozLimpia_resultados"


# ------------------------------------------------------------------ interfaz web
def lanzar_web() -> None:
    import gradio as gr

    def ejecutar(archivo, modo, modelo, ambiente, reparar, realzar, progress=gr.Progress()):
        if not archivo:
            raise gr.Error("Sube primero un video o un audio.")
        ruta = archivo if isinstance(archivo, str) else archivo.name
        t0 = time.time()
        try:
            r = procesar(
                ruta, str(CARPETA_SALIDA),
                modo="ia" if modo.startswith("IA") else "rapido",
                modelo=MODELOS[modelo], ambiente=ambiente / 100,
                reparar=reparar, realzar=realzar,
                cb=lambda p, m: progress(p, desc=m))
        except Exception as e:
            raise gr.Error(str(e))
        informe = f"{r.informe}\n\nTiempo: {time.time() - t0:.0f} s\nGuardado en: {r.salida}"
        return (r.salida if r.es_video else None), r.audio_antes, r.audio_despues, informe

    with gr.Blocks(title="VozLimpia") as demo:
        gr.Markdown("# 🎤 VozLimpia\nRepara la saturación y rescata la voz del cantante "
                    "en tus videos de conciertos.")
        with gr.Row():
            with gr.Column():
                archivo = gr.File(label="Video o audio del concierto", type="filepath",
                                  file_types=[".mp4", ".mov", ".mkv", ".webm", ".avi",
                                              ".mp3", ".wav", ".m4a", ".aac", ".ogg"])
                modo = gr.Radio(["IA: separar la voz (recomendado)", "Rápido: sin IA"],
                                value="IA: separar la voz (recomendado)", label="Modo")
                modelo = gr.Dropdown(list(MODELOS), value=list(MODELOS)[0],
                                     label="Modelo de IA")
                ambiente = gr.Slider(0, 60, value=15, step=5,
                                     label="Ambiente a conservar (banda/público) %",
                                     info="0 = solo la voz · más alto = suena más natural, "
                                          "pero la voz se tapa más")
                reparar = gr.Checkbox(True, label="Reparar picos saturados")
                realzar = gr.Checkbox(True, label="Realzar la voz (EQ + compresión)")
                boton = gr.Button("Limpiar audio", variant="primary")
            with gr.Column():
                video = gr.Video(label="Resultado (video con el audio mejorado)")
                a_antes = gr.Audio(label="Audio original", type="filepath")
                a_despues = gr.Audio(label="Audio mejorado", type="filepath")
                informe = gr.Textbox(label="Informe", lines=7)
        boton.click(ejecutar, [archivo, modo, modelo, ambiente, reparar, realzar],
                    [video, a_antes, a_despues, informe])
    CARPETA_SALIDA.mkdir(parents=True, exist_ok=True)
    demo.queue().launch(inbrowser=True, show_error=True, allowed_paths=[str(CARPETA_SALIDA)])


# ------------------------------------------------------------------ consola
def lanzar_cli(args: argparse.Namespace) -> None:
    def cb(p: float, m: str) -> None:
        print(f"[{int(p * 100):3d}%] {m}", flush=True)

    r = procesar(args.archivo, args.salida, modo="rapido" if args.rapido else "ia",
                 modelo=MODELOS[args.modelo] if args.modelo in MODELOS else args.modelo,
                 ambiente=args.ambiente / 100, reparar=not args.sin_reparar,
                 realzar=not args.sin_realce, cb=cb)
    print("\n" + r.informe + f"\n\nArchivo final: {r.salida}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="VozLimpia")
    ap.add_argument("archivo", nargs="?", help="video/audio; si lo omites se abre la interfaz web")
    ap.add_argument("--rapido", action="store_true", help="sin IA (solo reparar + ecualizar)")
    ap.add_argument("--modelo", default=list(MODELOS)[0], help="nombre del modelo o archivo")
    ap.add_argument("--ambiente", type=float, default=15, help="%% de ambiente a conservar")
    ap.add_argument("--sin-reparar", action="store_true")
    ap.add_argument("--sin-realce", action="store_true")
    ap.add_argument("--salida", default=str(CARPETA_SALIDA))
    a = ap.parse_args()
    if a.archivo:
        lanzar_cli(a)
    else:
        lanzar_web()
