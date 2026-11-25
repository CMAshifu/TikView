"""
TikView engine for Android (runs inside the app through Chaquopy).

The web UI calls handle(method, path, body) through the Android JavaScript
bridge, so there's no network server and nothing other apps can reach.
Lives are played by the native player; recordings are saved straight from the
stream (FLV or HLS) and converted to MP4 by Android's own Media3 Transformer.
"""
import json
import os
import re
import ssl
import threading
import time
import urllib.request
from collections import deque
from pathlib import Path
from urllib.parse import parse_qs, unquote, urljoin, urlsplit

try:
    import certifi
    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except Exception:  # pragma: no cover
    SSL_CTX = ssl.create_default_context()

import yt_dlp

try:  # Android side (Kotlin). Absent when testing on a computer.
    from java import jclass
    Native = jclass("com.tikview.app.Native")
except Exception:
    Native = None

POLL_SECONDS = 90
USERNAME_RE = re.compile(r"^[A-Za-z0-9._]{1,40}$")
VIDEO_FORMAT = "b[vcodec^=h264]/b[vcodec^=avc]/b"
VIDEO_EXT = (".mp4", ".webm", ".mkv", ".mov", ".ts", ".flv")

DATA = DOWNLOADS = STREAMERS_FILE = None
streamers, live_status, video_cache, jobs, sessions = {}, {}, {}, {}, {}
lock = threading.RLock()
_started = False


# --------------------------------------------------------------------------- setup
def init(files_dir):
    """Called by the app at start-up (safe to call more than once)."""
    global DATA, DOWNLOADS, STREAMERS_FILE, streamers, _started
    with lock:
        if _started:
            return True
        DATA = Path(files_dir) / "tikview"
        DOWNLOADS = DATA / "downloads"
        STREAMERS_FILE = DATA / "streamers.json"
        DOWNLOADS.mkdir(parents=True, exist_ok=True)
        # recordings interrupted by the app being killed: finish them now
        for p in DOWNLOADS.glob("*_live_*"):
            if p.suffix in (".flv", ".ts") and p.stat().st_size > 0:
                threading.Thread(target=_finalize, args=(p,), daemon=True).start()
        if STREAMERS_FILE.exists():
            try:
                streamers = json.loads(STREAMERS_FILE.read_text())
            except Exception:
                streamers = {}
        _started = True
        threading.Thread(target=poller, daemon=True).start()
        update_service()
        return True


def short(msg, n=300):
    msg = re.sub(r"\x1b\[[0-9;]*m", "", str(msg)).replace("ERROR: ", "")
    return msg if len(msg) <= n else msg[:n] + "…"


def ydl_opts(**extra):
    o = {"quiet": True, "no_warnings": True, "noprogress": True, "fixup": "never",
         "cachedir": str(DATA / "cache")}
    cookie = DATA / "cookies.txt"
    if cookie.exists():
        o["cookiefile"] = str(cookie)
    o.update(extra)
    return o


def clean_user(u):
    u = unquote(u or "").strip()
    m = re.search(r"tiktok\.com/@([^/?#]+)", u)
    if m:
        u = m.group(1)
    u = u.lstrip("@")
    if not USERNAME_RE.match(u):
        raise ValueError("That doesn't look like a TikTok username.")
    return u


def find_local(video_id):
    if not video_id:
        return None
    for p in DOWNLOADS.glob(f"*_{video_id}.*"):
        if p.suffix.lower() in VIDEO_EXT and not p.name.endswith(".part"):
            return p.name
    return None


def save_streamers():
    STREAMERS_FILE.write_text(json.dumps(streamers, indent=2))


def native(method, *args):
    if Native is not None:
        try:
            return getattr(Native, method)(*args)
        except Exception:
            return None
    return None


# --------------------------------------------------------------------------- TikTok
def check_live(user):
    prev = live_status.get(user, {}).get("live")
    try:
        with yt_dlp.YoutubeDL(ydl_opts()) as y:
            info = y.extract_info(f"https://www.tiktok.com/@{user}/live", download=False)
        st = {"live": True, "title": info.get("title") or info.get("description") or "",
              "viewers": info.get("concurrent_view_count"), "checked": time.time()}
    except Exception as e:
        msg = str(e).lower()
        st = {"live": False, "checked": time.time()}
        if "not currently live" not in msg and "not live" not in msg:
            st["error"] = short(e)
    live_status[user] = st
    if st["live"] and prev is False:
        native("notifyLive", user, st.get("title") or "")
    return st


def resolve_live(user):
    """Direct stream address + headers (FLV preferred: easiest to record)."""
    with yt_dlp.YoutubeDL(ydl_opts(format="b[ext=flv]/b")) as y:
        info = y.extract_info(f"https://www.tiktok.com/@{user}/live", download=False)
    if not info.get("url"):
        raise RuntimeError("Couldn't get the live stream address.")
    return {"url": info["url"], "headers": info.get("http_headers") or {},
            "protocol": info.get("protocol") or "", "ext": info.get("ext") or "",
            "title": info.get("title") or info.get("description") or ""}


MOBILE_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")


def follow_link(url):
    """Where a short TikTok share link (vm.tiktok.com/…) really points."""
    if not re.match(r"^https?://([a-z0-9-]+\.)*tiktok\.com/", url or ""):
        raise ValueError("That isn't a TikTok link.")
    from yt_dlp.networking import Request
    with yt_dlp.YoutubeDL(ydl_opts()) as y:
        resp = y.urlopen(Request(url, headers={"User-Agent": MOBILE_UA}))
        final = resp.url
        resp.close()
    return final


SEC_UID_RE = re.compile(r'"secUid"\s*:\s*"(MS4wLjABAAAA[\w-]{64})"')


def get_sec_uid(user, refresh=False):
    """TikTok's hidden account ID (needed to list videos). Cached per streamer."""
    cfg = streamers.get(user) or {}
    if cfg.get("sec_uid") and not refresh:
        return cfg["sec_uid"]
    from yt_dlp.networking import Request
    ua = MOBILE_UA
    urls = [
        f"https://www.tiktok.com/api-live/user/room/?aid=1988&uniqueId={user}&sourceType=54",
        f"https://webcast.tiktok.com/webcast/room/info_by_user/?aid=1988&unique_id={user}",
        f"https://www.tiktok.com/@{user}",
        f"https://www.tiktok.com/@{user}/live",
    ]
    sec = None
    with yt_dlp.YoutubeDL(ydl_opts()) as y:
        for url in urls:
            try:
                resp = y.urlopen(Request(url, headers={"User-Agent": ua, "Referer": "https://www.tiktok.com/"}))
                m = SEC_UID_RE.search(resp.read().decode("utf-8", "replace"))
            except Exception:
                continue
            if m:
                sec = m.group(1)
                break
    if sec and user in streamers:
        with lock:
            streamers[user]["sec_uid"] = sec
            save_streamers()
    return sec


def _fetch_profile(target, limit):
    with yt_dlp.YoutubeDL(ydl_opts(extract_flat="in_playlist", playlistend=limit)) as y:
        return y.extract_info(target, download=False)


def fetch_profile(user, limit):
    """Video list for a user: by known ID first, then by name, then look the ID up and retry."""
    sec = (streamers.get(user) or {}).get("sec_uid")
    if sec:
        try:
            return _fetch_profile(f"tiktokuser:{sec}", limit)
        except Exception:
            pass
    try:
        return _fetch_profile(f"https://www.tiktok.com/@{user}", limit)
    except Exception as first_error:
        sec = get_sec_uid(user, refresh=True)
        if not sec:
            raise RuntimeError(
                "TikTok didn't share this profile's video list. Their lives can still be "
                "watched and recorded, and you can paste or share any of their video links.") from first_error
        return _fetch_profile(f"tiktokuser:{sec}", limit)


def list_videos(user, limit):
    cached = video_cache.get(user)
    if cached and time.time() - cached[0] < 600 and len(cached[1]) >= limit:
        vids = cached[1][:limit]
    else:
        info = fetch_profile(user, limit)
        vids = []
        for e in list(info.get("entries") or [])[:limit]:
            if not e:
                continue
            thumbs = e.get("thumbnails") or [{}]
            vid = e.get("id")
            vids.append({
                "id": vid,
                "url": e.get("url") or f"https://www.tiktok.com/@{user}/video/{vid}",
                "title": e.get("title") or e.get("description") or "",
                "thumbnail": e.get("thumbnail") or thumbs[-1].get("url"),
                "duration": e.get("duration"), "timestamp": e.get("timestamp"),
                "views": e.get("view_count"),
            })
        video_cache[user] = (time.time(), vids)
    for v in vids:
        v["local"] = find_local(v["id"])
    return vids


# --------------------------------------------------------------------------- downloads
def run_download(job):
    def hook(d):
        if d["status"] == "downloading":
            tot = d.get("total_bytes") or d.get("total_bytes_estimate")
            job["progress"] = round(100 * d.get("downloaded_bytes", 0) / tot, 1) if tot else None
        elif d["status"] == "finished":
            job["progress"] = 100

    opts = ydl_opts(format=VIDEO_FORMAT, progress_hooks=[hook],
                    outtmpl=str(DOWNLOADS / "%(uploader_id,uploader|unknown)s_%(id)s.%(ext)s"))
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            info = y.extract_info(job["url"], download=True)
            path = Path(y.prepare_filename(info))
        job.update(state="done", file=path.name,
                   title=info.get("title") or info.get("description") or path.name)
    except Exception as e:
        job.update(state="error", error=short(e))


# --------------------------------------------------------------------------- live recording
def _open(url, headers, timeout=30):
    req = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX)


def _finalize(src):
    """Convert a finished .flv/.ts recording to .mp4 (no re-encoding). Returns file name."""
    src = Path(src)
    if not src.exists() or src.stat().st_size == 0:
        src.unlink(missing_ok=True)
        return None
    mp4 = src.with_suffix(".mp4")
    ok = native("remux", str(src), str(mp4))
    if ok and mp4.exists() and mp4.stat().st_size > 0:
        src.unlink(missing_ok=True)
        return mp4.name
    mp4.unlink(missing_ok=True)
    return src.name  # still plays inside TikView (and in VLC)


class Recorder:
    def __init__(self, user):
        self.user = user
        self.record = True
        self.state = "starting"
        self.started = time.time()
        self.stop_evt = threading.Event()
        self.rec_path = None
        self.final_file = None
        self.error = None
        self.title = ""
        self.thread = None

    def start(self):
        s = resolve_live(self.user)
        self.title = s["title"]
        hls = "m3u8" in s["protocol"] or ".m3u8" in s["url"]
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.rec_path = DOWNLOADS / f"{self.user}_live_{stamp}.{'ts' if hls else 'flv'}"
        self.state = "running"
        self.thread = threading.Thread(target=self._run, args=(s, hls), daemon=True)
        self.thread.start()

    def _run(self, s, hls):
        try:
            with open(self.rec_path, "wb") as f:
                (self._hls if hls else self._flv)(s["url"], s["headers"], f)
        except Exception as e:
            if not self.stop_evt.is_set():
                self.error = short(e)
        self.state = "finishing"
        update_service()
        self.final_file = _finalize(self.rec_path)
        self.state = "ended"
        update_service()

    def _flv(self, url, headers, f):
        with _open(url, headers) as r:
            while not self.stop_evt.is_set():
                chunk = r.read(64 * 1024)
                if not chunk:
                    break
                f.write(chunk)

    def _hls(self, url, headers, f):
        seen, misses = deque(maxlen=500), 0
        while not self.stop_evt.is_set():
            with _open(url, headers) as r:
                text = r.read().decode("utf-8", "replace")
            lines = [l.strip() for l in text.splitlines() if l.strip()]
            if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):  # master playlist
                url = urljoin(url, next(l for l in lines if not l.startswith("#")))
                continue
            new = 0
            for l in lines:
                if l.startswith("#") or self.stop_evt.is_set():
                    continue
                seg = urljoin(url, l)
                if seg in seen:
                    continue
                seen.append(seg)
                with _open(seg, headers) as r:
                    f.write(r.read())
                new += 1
            if "#EXT-X-ENDLIST" in text:
                break
            misses = 0 if new else misses + 1
            if misses > 15:  # ~30 s with no new video: the live is over
                break
            self.stop_evt.wait(2)

    def stop(self):
        self.stop_evt.set()

    def to_dict(self):
        return {"user": self.user, "state": self.state, "record": True, "ready": False,
                "started": self.started, "title": self.title, "error": self.error,
                "recording_file": self.rec_path.name if self.rec_path else None,
                "final_file": self.final_file}


def start_recording(user):
    with lock:
        s = sessions.get(user)
        if s and s.state in ("starting", "running"):
            return s
        s = Recorder(user)
        sessions[user] = s
    try:
        s.start()
    except Exception:
        s.state = "ended"
        raise
    update_service()
    return s


def update_service():
    """Keep Android's foreground service (and its notification) in sync."""
    rec = [u for u, s in sessions.items() if s.state in ("starting", "running", "finishing")]
    auto = [u for u, c in streamers.items() if c.get("auto_record")]
    if rec:
        text = "Recording " + ", ".join("@" + u for u in rec)
    elif auto:
        text = f"Watching {len(auto)} streamer{'s' if len(auto) > 1 else ''} for lives"
    else:
        text = None
    native("serviceUpdate", text, bool(rec))


def poller():
    while True:
        for u in list(streamers):
            try:
                st = check_live(u)
                if st["live"] and (streamers.get(u) or {}).get("auto_record"):
                    s = sessions.get(u)
                    if not s or s.state == "ended":
                        start_recording(u)
            except Exception as e:
                live_status.setdefault(u, {})["error"] = short(e)
        time.sleep(POLL_SECONDS)


# --------------------------------------------------------------------------- request router
class HttpError(Exception):
    def __init__(self, status, msg):
        super().__init__(msg)
        self.status = status


ROUTES = []


def route(method, pattern):
    def deco(fn):
        ROUTES.append((method, re.compile("^" + pattern + "$"), fn))
        return fn
    return deco


def handle(method, path, body=""):
    """Entry point for the JavaScript bridge. Returns a JSON string {status, body}."""
    try:
        parts = urlsplit(path)
        query = {k: v[0] for k, v in parse_qs(parts.query).items()}
        data = json.loads(body) if body else {}
        for m, rx, fn in ROUTES:
            mt = rx.match(parts.path)
            if m == method and mt:
                return json.dumps({"status": 200, "body": fn(data, query, *mt.groups())})
        raise HttpError(404, "Not found")
    except HttpError as e:
        return json.dumps({"status": e.status, "body": {"error": str(e)}})
    except ValueError as e:
        return json.dumps({"status": 400, "body": {"error": str(e)}})
    except Exception as e:
        return json.dumps({"status": 502, "body": {"error": short(e)}})


@route("GET", "/api/streamers")
def r_streamers(d, q):
    return [{"username": u, **cfg, "status": live_status.get(u, {}),
             "session": sessions[u].to_dict() if u in sessions else None}
            for u, cfg in sorted(streamers.items())]


@route("POST", "/api/streamers")
def r_add(d, q):
    u = clean_user(d.get("username"))
    with lock:
        streamers.setdefault(u, {"auto_record": False, "added": time.time()})
        save_streamers()
    threading.Thread(target=check_live, args=(u,), daemon=True).start()
    return {"username": u}


@route("PATCH", "/api/streamers/([^/]+)")
def r_update(d, q, user):
    if user not in streamers:
        raise HttpError(404, "Unknown streamer")
    with lock:
        if "auto_record" in d:
            streamers[user]["auto_record"] = bool(d["auto_record"])
        save_streamers()
    update_service()
    return streamers[user]


@route("DELETE", "/api/streamers/([^/]+)")
def r_delete(d, q, user):
    with lock:
        streamers.pop(user, None)
        save_streamers()
    update_service()
    return {"ok": True}


@route("POST", "/api/streamers/([^/]+)/check")
def r_check(d, q, user):
    return check_live(clean_user(user))


@route("GET", "/api/streamers/([^/]+)/videos")
def r_videos(d, q, user):
    limit = max(1, min(int(q.get("limit", 24)), 100))
    return list_videos(clean_user(user), limit)


@route("POST", "/api/download")
def r_download(d, q):
    url = (d.get("url") or "").strip()
    if not re.match(r"^https?://([a-z0-9-]+\.)*tiktok\.com/", url):
        raise HttpError(400, "Please give a TikTok video link.")
    local = find_local(d.get("id"))
    if local:
        return {"id": None, "state": "done", "file": local, "title": d.get("title", "")}
    job_id = str(len(jobs) + 1)
    job = {"id": job_id, "url": url, "state": "downloading", "progress": 0,
           "title": d.get("title") or url, "created": time.time()}
    jobs[job_id] = job
    threading.Thread(target=run_download, args=(job,), daemon=True).start()
    return job


@route("POST", "/api/resolve-link")
def r_resolve_link(d, q):
    return {"url": follow_link((d.get("url") or "").strip())}


@route("GET", "/api/jobs")
def r_jobs(d, q):
    return sorted(jobs.values(), key=lambda j: -j["created"])[:30]


@route("GET", "/api/jobs/([^/]+)")
def r_job(d, q, job_id):
    if job_id not in jobs:
        raise HttpError(404, "Unknown job")
    return jobs[job_id]


@route("GET", "/api/library")
def r_library(d, q):
    active = {s.rec_path.name for s in sessions.values() if s.rec_path and s.state != "ended"}
    files = []
    for p in DOWNLOADS.iterdir():
        if p.is_file() and p.suffix.lower() in VIDEO_EXT and p.name not in active:
            st = p.stat()
            files.append({"file": p.name, "size": st.st_size, "mtime": st.st_mtime,
                          "live": "_live_" in p.name})
    return sorted(files, key=lambda f: -f["mtime"])


@route("DELETE", "/api/library/(.+)")
def r_delete_file(d, q, name):
    p = (DOWNLOADS / unquote(name)).resolve()
    if p.parent != DOWNLOADS.resolve() or not p.exists():
        raise HttpError(404, "Not found")
    p.unlink()
    return {"ok": True}


@route("GET", "/api/live")
def r_live(d, q):
    return [s.to_dict() for s in sessions.values()]


@route("GET", "/api/live/([^/]+)/resolve")
def r_resolve(d, q, user):
    u = clean_user(user)
    try:
        s = resolve_live(u)
    except Exception as e:
        if "not currently live" in str(e).lower() or "not live" in str(e).lower():
            raise HttpError(404, f"@{u} is not live right now.")
        raise
    live_status[u] = {"live": True, "title": s["title"], "checked": time.time()}
    return s


@route("POST", "/api/live/([^/]+)/start")
def r_record(d, q, user):
    u = clean_user(user)
    try:
        return start_recording(u).to_dict()
    except Exception as e:
        if "not currently live" in str(e).lower() or "not live" in str(e).lower():
            raise HttpError(404, f"@{u} is not live right now.")
        raise


@route("POST", "/api/live/([^/]+)/stop")
def r_stop(d, q, user):
    s = sessions.get(user)
    if not s:
        raise HttpError(404, "No recording")
    s.stop()
    if s.thread:
        s.thread.join(5)
    return s.to_dict()
