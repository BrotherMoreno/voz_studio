"""
audiofix.py - Núcleo de VozLimpia.
Repara la saturación (clipping) del audio de videos de conciertos y rescata la voz.

Pipeline:
  1. Extrae el audio del video (ffmpeg).
  2. Repara los picos recortados (declipping por interpolación Hermite).
  3. (Modo IA) Separa la voz del resto con modelos de UVR/Demucs vía audio-separator.
  4. Realza la voz (EQ + compresión + limitador) con pedalboard.
  5. Mezcla voz + un poco de ambiente, normaliza y vuelve a unir con el video.
"""
from __future__ import annotations

import logging
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
Progress = Callable[[float, str], None]

# Modelos de separación disponibles (se descargan solos la primera vez).
MODELOS = {
    "Rápido y muy bueno (MDX-Net Voc FT)": "UVR-MDX-NET-Voc_FT.onnx",
    "Máxima calidad (BS-Roformer)": "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
    "Alternativo (Demucs FT, más lento)": "htdemucs_ft.yaml",
}


# --------------------------------------------------------------------------- ffmpeg
def ffmpeg_exe() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg  # trae un ffmpeg propio, no hay que instalar nada

    return imageio_ffmpeg.get_ffmpeg_exe()


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    kw = {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # sin ventana negra
    return subprocess.run(cmd, capture_output=True, text=True, errors="replace",
                          stdin=subprocess.DEVNULL, **kw)


def tiene_video(path: str) -> bool:
    r = _run([ffmpeg_exe(), "-hide_banner", "-i", path])
    return "Video:" in r.stderr and "attached pic" not in r.stderr


def extraer_audio(src: str, wav: Path) -> None:
    r = _run([ffmpeg_exe(), "-y", "-v", "error", "-i", src, "-vn", "-ac", "2",
              "-ar", str(SR), "-c:a", "pcm_f32le", str(wav)])
    if r.returncode != 0:
        raise RuntimeError(f"No pude leer el audio del archivo:\n{r.stderr[-600:]}")


# --------------------------------------------------------------------------- análisis
@dataclass
class Diagnostico:
    pico_db: float
    saturado_pct: float
    graves_vs_voz_db: float  # >0 = los graves tapan a la voz

    def texto(self) -> str:
        return (f"pico {self.pico_db:+.1f} dBFS · muestras saturadas {self.saturado_pct:.2f} % · "
                f"graves vs voz {self.graves_vs_voz_db:+.1f} dB")


def diagnosticar(x: np.ndarray, sr: int = SR) -> Diagnostico:
    m = x.mean(axis=1) if x.ndim == 2 else x
    pico = float(np.abs(x).max()) + 1e-9
    sat = float((np.abs(x) >= 0.99).mean() * 100)
    f, p = signal.welch(m, sr, nperseg=4096)
    graves = p[(f >= 20) & (f < 250)].sum() + 1e-18
    voz = p[(f >= 1000) & (f < 4000)].sum() + 1e-18
    return Diagnostico(20 * np.log10(pico), sat, float(10 * np.log10(graves / voz)))


# --------------------------------------------------------------------------- declipping
def _declip_canal(c: np.ndarray, thr: float, max_run: int) -> tuple[np.ndarray, int]:
    n = len(c)
    mask = np.abs(c) >= thr
    if not mask.any():
        return c, 0
    d = np.diff(np.concatenate(([0], mask.view(np.int8), [0])))
    starts, ends = np.where(d == 1)[0], np.where(d == -1)[0] - 1
    y = c.copy()
    tope = 3.0 * thr  # no inventar picos absurdos
    arreglados = 0
    for s, e in zip(starts, ends):
        largo_run = e - s + 1
        if largo_run > max_run or s < 2 or e > n - 3:
            continue
        i0, i1 = s - 1, e + 1
        L = i1 - i0
        m0 = (c[i0] - c[i0 - 1]) * L
        m1 = (c[i1 + 1] - c[i1]) * L
        t = np.arange(1, L) / L
        t2, t3 = t * t, t * t * t
        v = ((2 * t3 - 3 * t2 + 1) * c[i0] + (t3 - 2 * t2 + t) * m0 +
             (-2 * t3 + 3 * t2) * c[i1] + (t3 - t2) * m1)
        sg = 1.0 if c[s:e + 1].mean() > 0 else -1.0
        y[s:e + 1] = sg * np.clip(sg * v, thr, tope)  # nunca por debajo del nivel de corte
        arreglados += 1
    return y, arreglados


def reparar_saturacion(x: np.ndarray, max_run: int = 96) -> tuple[np.ndarray, int]:
    """Reconstruye los picos recortados. Devuelve (audio, nº de picos reparados)."""
    pico = float(np.abs(x).max())
    if pico < 0.3:
        return x, 0
    # Tras comprimir (WhatsApp/AAC) los picos recortados quedan redondeados, no planos:
    # por eso se tratan como "zona de saturación" todas las crestas del 10 % superior.
    thr = 0.90 * pico
    if (np.abs(x) >= thr).mean() < 3e-4:  # casi no hay picos altos: audio limpio
        return x, 0
    y = np.empty_like(x)
    total = 0
    for ch in range(x.shape[1]):
        y[:, ch], k = _declip_canal(x[:, ch].astype(np.float64), thr, max_run)
        total += k
    return y.astype(np.float32), total


# --------------------------------------------------------------------------- cadenas de efectos
def _board(kind: str):
    from pedalboard import (Compressor, HighpassFilter, HighShelfFilter, Limiter,
                            LowShelfFilter, PeakFilter, Pedalboard)

    if kind == "voz":  # sobre la voz ya aislada
        return Pedalboard([
            HighpassFilter(90), PeakFilter(350, -2.0, 1.0), PeakFilter(2800, 3.0, 0.9),
            HighShelfFilter(7000, 1.5),
            Compressor(threshold_db=-22, ratio=3.0, attack_ms=4, release_ms=90),
            Limiter(threshold_db=-1.0)])
    if kind == "mezcla":  # sin IA: no hay voz aislada, así que se recortan graves a la fuerza
        return Pedalboard([
            HighpassFilter(100), LowShelfFilter(250, -9.0), PeakFilter(400, -2.5, 0.9),
            PeakFilter(2800, 4.0, 0.9), HighShelfFilter(6000, 2.5),
            Compressor(threshold_db=-24, ratio=3.5, attack_ms=4, release_ms=90),
            Limiter(threshold_db=-1.0)])
    if kind == "ambiente":  # el "resto" (banda/público) que se deja de fondo
        return Pedalboard([HighpassFilter(120), LowShelfFilter(250, -6.0),
                           Limiter(threshold_db=-3.0)])
    raise ValueError(kind)


def _aplicar(kind: str, x: np.ndarray) -> np.ndarray:
    return _board(kind)(x.T.astype(np.float32), SR).T


# --------------------------------------------------------------------------- separación IA
def separar_voz(wav: Path, carpeta: Path, modelo: str, cb: Progress) -> tuple[np.ndarray, np.ndarray]:
    try:
        from audio_separator.separator import Separator
    except ImportError as e:
        raise RuntimeError("Falta 'audio-separator'. Ejecuta el instalador de nuevo "
                           "o usa el modo 'Rápido (sin IA)'.") from e

    cb(0.25, "Cargando modelo de IA (la primera vez se descarga, ~100–600 MB)…")
    sep = Separator(output_dir=str(carpeta), output_format="WAV",
                    normalization_threshold=0.9, log_level=logging.WARNING)
    sep.load_model(model_filename=modelo)
    cb(0.40, "Separando la voz del resto… (puede tardar unos minutos)")
    salidas = sep.separate(str(wav))

    voz, resto = None, []
    for s in salidas:
        p = Path(s) if Path(s).is_absolute() and Path(s).exists() else carpeta / Path(s).name
        a, sr = sf.read(str(p), dtype="float32", always_2d=True)
        if sr != SR:
            a = signal.resample_poly(a, SR, sr, axis=0).astype(np.float32)
        nombre = p.name.lower()
        es_voz = "vocals" in nombre and "no_vocals" not in nombre and "instrumental" not in nombre
        if es_voz and voz is None:
            voz = a
        else:
            resto.append(a)
    if voz is None:
        raise RuntimeError("El modelo no devolvió una pista de voz.")
    n = len(voz)
    inst = np.zeros_like(voz)
    for r in resto:
        r = r[:n] if len(r) >= n else np.pad(r, ((0, n - len(r)), (0, 0)))
        inst += r
    return voz, inst


# --------------------------------------------------------------------------- pipeline
@dataclass
class Resultado:
    salida: str
    audio_antes: str
    audio_despues: str
    es_video: bool
    informe: str
    avisos: list[str] = field(default_factory=list)


def _guardar_wav(path: Path, x: np.ndarray) -> str:
    sf.write(str(path), np.clip(x, -1, 1), SR, subtype="PCM_16")
    return str(path)


def procesar(src: str, carpeta_salida: str, modo: str = "ia",
             modelo: str = "UVR-MDX-NET-Voc_FT.onnx", ambiente: float = 0.15,
             reparar: bool = True, realzar: bool = True,
             cb: Optional[Progress] = None) -> Resultado:
    """modo: 'ia' (separa la voz) o 'rapido' (solo reparación + ecualización)."""
    cb = cb or (lambda p, m: None)
    out_dir = Path(carpeta_salida)
    out_dir.mkdir(parents=True, exist_ok=True)
    avisos: list[str] = []
    stem = Path(src).stem

    with tempfile.TemporaryDirectory(prefix="vozlimpia_") as tmp:
        tmp = Path(tmp)
        cb(0.05, "Extrayendo el audio…")
        wav = tmp / "original.wav"
        extraer_audio(src, wav)
        x, _ = sf.read(str(wav), dtype="float32", always_2d=True)
        antes = diagnosticar(x)
        antes_wav = _guardar_wav(out_dir / f"{stem}_antes.wav", x)

        if reparar:
            cb(0.15, "Reparando los picos saturados…")
            x, n_rep = reparar_saturacion(x)
            avisos.append(f"Picos reparados: {n_rep}")
        g = 0.9 / (np.abs(x).max() + 1e-9)  # deja margen para que los modelos no recorten
        x = (x * g).astype(np.float32)

        if modo == "ia":
            base = tmp / "reparado.wav"
            sf.write(str(base), x, SR, subtype="FLOAT")
            try:
                voz, inst = separar_voz(base, tmp, modelo, cb)
                cb(0.80, "Realzando la voz…")
                voz = _aplicar("voz", voz) if realzar else voz
                mezcla = voz + ambiente * _aplicar("ambiente", inst)
            except Exception as e:  # si falla la IA, no dejamos al usuario sin resultado
                avisos.append(f"La IA falló ({e}). Usé el modo rápido.")
                mezcla = _aplicar("mezcla", x)
        else:
            cb(0.60, "Ecualizando para destacar la voz…")
            mezcla = _aplicar("mezcla", x)

        n = min(len(mezcla), len(x))
        mezcla = mezcla[:n]
        mezcla = _aplicar_limite(mezcla)
        despues = diagnosticar(mezcla)
        proc_wav = tmp / "procesado.wav"
        sf.write(str(proc_wav), mezcla, SR, subtype="FLOAT")
        despues_wav = _guardar_wav(out_dir / f"{stem}_despues.wav", mezcla)

        cb(0.90, "Uniendo audio y video…")
        es_video = tiene_video(src)
        af = "loudnorm=I=-14:TP=-1.5:LRA=11"
        if es_video:
            salida = out_dir / f"{stem}_limpio.mp4"
            cmd = [ffmpeg_exe(), "-y", "-v", "error", "-i", src, "-i", str(proc_wav),
                   "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
                   "-b:a", "192k", "-af", af, "-movflags", "+faststart", "-shortest", str(salida)]
            r = _run(cmd)
            if r.returncode != 0:  # contenedor raro: recodificamos el video
                cmd[cmd.index("copy")] = "libx264"
                r = _run(cmd)
        else:
            salida = out_dir / f"{stem}_limpio.m4a"
            r = _run([ffmpeg_exe(), "-y", "-v", "error", "-i", str(proc_wav), "-c:a", "aac",
                      "-b:a", "192k", "-af", af, str(salida)])
        if r.returncode != 0:
            raise RuntimeError(f"ffmpeg falló:\n{r.stderr[-600:]}")

    informe = (f"ANTES : {antes.texto()}\nDESPUÉS: {despues.texto()}\n"
               f"Claridad de voz: {antes.graves_vs_voz_db - despues.graves_vs_voz_db:+.1f} dB "
               f"menos dominio de graves.\n" + "\n".join(avisos))
    cb(1.0, "Listo")
    return Resultado(str(salida), antes_wav, despues_wav, es_video, informe, avisos)


def _aplicar_limite(x: np.ndarray) -> np.ndarray:
    from pedalboard import Limiter, Pedalboard

    return Pedalboard([Limiter(threshold_db=-1.0)])(x.T.astype(np.float32), SR).T
