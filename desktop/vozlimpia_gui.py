"""
VozLimpia - interfaz de escritorio (Tkinter) que se empaqueta como .exe.
Usa el mismo núcleo (audiofix.py) que la versión web y la de consola.
"""
from __future__ import annotations

import multiprocessing
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

# En el .exe sin consola stdout/stderr son None y algunas librerías fallan al escribir.
for _n in ("stdout", "stderr"):
    if getattr(sys, _n) is None:
        setattr(sys, _n, open(os.devnull, "w"))

from audiofix import MODELOS, procesar  # noqa: E402

CARPETA_SALIDA = Path.home() / "VozLimpia_resultados"
TIPOS = [("Video o audio", "*.mp4 *.mov *.mkv *.webm *.avi *.mp3 *.wav *.m4a *.aac *.ogg"),
         ("Todos los archivos", "*.*")]


def abrir_ruta(ruta: str) -> None:
    if sys.platform == "win32":
        os.startfile(ruta)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", ruta])
    else:
        subprocess.Popen(["xdg-open", ruta])


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("VozLimpia")
        self.geometry("660x680")
        self.minsize(580, 600)
        self.cola: queue.Queue = queue.Queue()
        self.archivo = tk.StringVar()
        self.modo = tk.StringVar(value="ia")
        self.modelo = tk.StringVar(value=list(MODELOS)[0])
        self.ambiente = tk.DoubleVar(value=15)
        self.reparar = tk.BooleanVar(value=True)
        self.realzar = tk.BooleanVar(value=True)
        self.resultado = None
        self._interfaz()
        self.after(100, self._revisar_cola)

    # ------------------------------------------------------------ interfaz
    def _interfaz(self) -> None:
        pad = {"padx": 14, "pady": 6}
        ttk.Label(self, text="🎤 VozLimpia", font=("Segoe UI", 20, "bold")).pack(anchor="w", **pad)
        ttk.Label(self, text="Repara la saturación y rescata la voz del cantante en tus videos "
                             "de conciertos.", wraplength=620).pack(anchor="w", padx=14)

        f = ttk.LabelFrame(self, text="1. Archivo")
        f.pack(fill="x", **pad)
        ttk.Entry(f, textvariable=self.archivo).pack(side="left", fill="x", expand=True, padx=8, pady=8)
        ttk.Button(f, text="Elegir…", command=self._elegir).pack(side="right", padx=8, pady=8)

        f = ttk.LabelFrame(self, text="2. Modo")
        f.pack(fill="x", **pad)
        ttk.Radiobutton(f, text="IA: separar la voz (recomendado)", value="ia",
                        variable=self.modo).pack(anchor="w", padx=8, pady=(6, 0))
        ttk.Radiobutton(f, text="Rápido: sin IA", value="rapido",
                        variable=self.modo).pack(anchor="w", padx=8)
        ttk.Label(f, text="Modelo de IA").pack(anchor="w", padx=8, pady=(6, 0))
        ttk.Combobox(f, textvariable=self.modelo, values=list(MODELOS), state="readonly"
                     ).pack(fill="x", padx=8, pady=(0, 6))
        ttk.Label(f, text="Ambiente a conservar (banda/público) %  ·  0 = solo la voz"
                  ).pack(anchor="w", padx=8)
        tk.Scale(f, from_=0, to=60, resolution=5, orient="horizontal", variable=self.ambiente
                 ).pack(fill="x", padx=8, pady=(0, 6))
        ttk.Checkbutton(f, text="Reparar picos saturados", variable=self.reparar
                        ).pack(anchor="w", padx=8)
        ttk.Checkbutton(f, text="Realzar la voz (EQ + compresión)", variable=self.realzar
                        ).pack(anchor="w", padx=8, pady=(0, 8))

        self.boton = ttk.Button(self, text="Limpiar audio", command=self._iniciar)
        self.boton.pack(fill="x", **pad)
        self.barra = ttk.Progressbar(self, maximum=100)
        self.barra.pack(fill="x", padx=14)
        self.estado = ttk.Label(self, text="Listo.")
        self.estado.pack(anchor="w", padx=14, pady=(2, 6))

        self.informe = tk.Text(self, height=9, wrap="word", state="disabled")
        self.informe.pack(fill="both", expand=True, padx=14, pady=4)

        fila = ttk.Frame(self)
        fila.pack(fill="x", **pad)
        self.btn_resultado = ttk.Button(fila, text="Abrir resultado", state="disabled",
                                        command=lambda: abrir_ruta(self.resultado.salida))
        self.btn_resultado.pack(side="left", expand=True, fill="x", padx=(0, 4))
        ttk.Button(fila, text="Abrir carpeta de resultados",
                   command=self._abrir_carpeta).pack(side="left", expand=True, fill="x", padx=(4, 0))

    def _elegir(self) -> None:
        ruta = filedialog.askopenfilename(title="Elige el video o audio", filetypes=TIPOS)
        if ruta:
            self.archivo.set(ruta)

    def _abrir_carpeta(self) -> None:
        CARPETA_SALIDA.mkdir(parents=True, exist_ok=True)
        abrir_ruta(str(CARPETA_SALIDA))

    def _escribir_informe(self, texto: str) -> None:
        self.informe.configure(state="normal")
        self.informe.delete("1.0", "end")
        self.informe.insert("1.0", texto)
        self.informe.configure(state="disabled")

    # ------------------------------------------------------------ proceso
    def _iniciar(self) -> None:
        ruta = self.archivo.get().strip()
        if not ruta or not Path(ruta).is_file():
            messagebox.showwarning("VozLimpia", "Elige primero un video o un audio.")
            return
        self.boton.configure(state="disabled")
        self.btn_resultado.configure(state="disabled")
        self.barra["value"] = 0
        self._escribir_informe("")
        args = dict(modo=self.modo.get(), modelo=MODELOS[self.modelo.get()],
                    ambiente=self.ambiente.get() / 100, reparar=self.reparar.get(),
                    realzar=self.realzar.get())
        threading.Thread(target=self._trabajo, args=(ruta, args), daemon=True).start()

    def _trabajo(self, ruta: str, args: dict) -> None:
        t0 = time.time()
        try:
            r = procesar(ruta, str(CARPETA_SALIDA),
                         cb=lambda p, m: self.cola.put(("p", p, m)), **args)
            self.cola.put(("ok", r, time.time() - t0))
        except Exception as e:  # noqa: BLE001
            self.cola.put(("err", str(e), 0))

    def _revisar_cola(self) -> None:
        try:
            while True:
                tipo, a, b = self.cola.get_nowait()
                if tipo == "p":
                    self.barra["value"] = a * 100
                    self.estado.configure(text=b)
                elif tipo == "ok":
                    self.resultado = a
                    self.barra["value"] = 100
                    self.estado.configure(text="Listo.")
                    self._escribir_informe(f"{a.informe}\n\nTiempo: {b:.0f} s\nGuardado en: {a.salida}")
                    self.btn_resultado.configure(state="normal")
                    self.boton.configure(state="normal")
                else:
                    self.estado.configure(text="Error")
                    self._escribir_informe(f"Error:\n{a}")
                    self.boton.configure(state="normal")
        except queue.Empty:
            pass
        self.after(100, self._revisar_cola)


if __name__ == "__main__":
    multiprocessing.freeze_support()
    App().mainloop()
