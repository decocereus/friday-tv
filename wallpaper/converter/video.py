from __future__ import annotations

import json
import subprocess
from pathlib import Path

PASSTHROUGH_CODECS = {"h264", "hevc", "vp9", "av1"}
MAX_WIDTH = 3840
MAX_HEIGHT = 2160
CONTAINER = {"vp9": ".webm"}


class VideoError(Exception):
    pass


def probe(path: Path) -> dict:
    done = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams", "-of", "json", str(path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if done.returncode != 0:
        raise VideoError(f"{path}: ffprobe failed: {done.stderr.strip()}")
    try:
        streams = json.loads(done.stdout)["streams"]
    except (json.JSONDecodeError, KeyError) as exc:
        raise VideoError(f"{path}: ffprobe gave no video stream ({exc})") from exc
    if not streams:
        raise VideoError(f"{path}: no video stream")
    return streams[0]


def convert(source: Path, out: Path) -> dict:
    stream = probe(source)
    codec = str(stream.get("codec_name", "")).lower()
    width = int(stream.get("width") or 0)
    height = int(stream.get("height") or 0)
    for existing in out.glob("video.*"):
        existing.unlink()

    if codec in PASSTHROUGH_CODECS and width <= MAX_WIDTH and height <= MAX_HEIGHT:
        target = out / ("video" + CONTAINER.get(codec, ".mp4"))
        command = ["ffmpeg", "-y", "-v", "error", "-i", str(source), "-c:v", "copy", "-an", str(target)]
        if _run(command) and target.exists():
            return {"file": target.name, "width": width, "height": height, "codec": codec, "mode": "remux"}
        target.unlink(missing_ok=True)
        target = out / "video.mkv"
        if _run(["ffmpeg", "-y", "-v", "error", "-i", str(source), "-c:v", "copy", "-an", str(target)]):
            return {"file": target.name, "width": width, "height": height, "codec": codec, "mode": "remux"}
        raise VideoError(f"{source}: remux failed")

    target = out / "video.mp4"
    scale = "scale=-2:1080" if height > 1080 else "scale=-2:" + str(height - height % 2)
    command = [
        "ffmpeg", "-y", "-v", "error", "-i", str(source),
        "-an", "-vf", scale, "-c:v", "hevc_videotoolbox", "-q:v", "55", "-tag:v", "hvc1", str(target),
    ]
    if not _run(command):
        raise VideoError(f"{source}: transcode to HEVC failed")
    stream = probe(target)
    return {
        "file": target.name,
        "width": int(stream.get("width") or 0),
        "height": int(stream.get("height") or 0),
        "codec": "hevc",
        "mode": "transcode",
    }


def preview(source: Path, out: Path, width: int = 640) -> str:
    target = out / "preview.jpg"
    command = [
        "ffmpeg", "-y", "-v", "error", "-i", str(source), "-frames:v", "1",
        "-vf", f"scale='min({width},iw)':-2:flags=lanczos", "-q:v", "4", str(target),
    ]
    if not _run(command):
        raise VideoError(f"{source}: could not make a preview")
    return target.name


def _run(command: list[str]) -> bool:
    done = subprocess.run(command, capture_output=True, text=True, check=False)
    return done.returncode == 0
