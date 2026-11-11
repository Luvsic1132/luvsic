#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Luvsic  ——  本地音乐播放器
Python 宿主 (pywebview + WebView2) + 本地音频流服务 (支持 Range) + 在线歌词检索
"""
import os
import sys
import subprocess

# PyInstaller --windowed 下没有标准输出，print() 会崩，兜底到 devnull
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w", encoding="utf-8")

import io
import re
import json
import time
import socket
import base64
import hashlib
import mimetypes
import threading
import urllib.parse
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

if getattr(sys, "frozen", False):
    # PyInstaller 打包后：只读资源在 _MEIPASS，可写数据放 exe 同目录（安装目录可写）
    ROOT = os.path.dirname(sys.executable)
    _RES = getattr(sys, "_MEIPASS", ROOT)
    UI_DIR = os.path.join(_RES, "ui")
    DATA_DIR = os.path.join(ROOT, "data")
else:
    ROOT = os.path.dirname(os.path.abspath(__file__))
    # 仓库结构：ui/ 与 desktop/ 同级；也兼容 ui/ 与 main.py 同级的旧布局
    _ui_local = os.path.join(ROOT, "ui")
    UI_DIR = (_ui_local if os.path.isdir(_ui_local)
              else os.path.join(os.path.dirname(ROOT), "ui"))
    DATA_DIR = os.path.join(ROOT, "data")
LYRICS_DIR = os.path.join(DATA_DIR, "lyrics")

LOG_PATH = os.path.join(DATA_DIR, "luvsic.log")


def _init_log():
    """打包版没有控制台：把 stdout/stderr 落到 data\luvsic.log"""
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        f = open(LOG_PATH, "a", encoding="utf-8", buffering=1)
        sys.stdout = f
        sys.stderr = f
        print("\n===== Luvsic start %s =====" % time.strftime("%Y-%m-%d %H:%M:%S"), flush=True)
    except Exception:
        pass

CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
LIB_PATH = os.path.join(DATA_DIR, "library.json")
PLAYLISTS_PATH = os.path.join(DATA_DIR, "playlists.json")

AUDIO_EXT = {".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus",
             ".aac", ".wma", ".ape", ".aiff", ".aif", ".dsf", ".mp4"}

for d in (DATA_DIR, LYRICS_DIR):
    os.makedirs(d, exist_ok=True)

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")


# ────────────────────────────── 工具 ──────────────────────────────
APP_VERSION = "1.28.2"


def _ver_tuple(v):
    out = []
    for part in str(v or "").split("."):
        try:
            out.append(int(part))
        except Exception:
            out.append(0)
    return tuple(out)


def _ver_newer(a, b):
    ta, tb = _ver_tuple(a), _ver_tuple(b)
    n = max(len(ta), len(tb))
    ta = ta + (0,) * (n - len(ta))
    tb = tb + (0,) * (n - len(tb))
    return ta > tb


def _load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save_json(path, obj):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
        # 关键数据（曲库/歌单/配置）在覆盖前先留一份 .bak（真正的上一版）
        try:
            base = os.path.basename(path)
            if base in ("library.json", "playlists.json", "config.json") and os.path.exists(path):
                with open(path, "rb") as _f:
                    _d = _f.read()
                with open(path + ".bak", "wb") as _f:
                    _f.write(_d)
        except Exception:
            pass
        os.replace(tmp, path)
    except Exception:
        pass


def http_get(url, headers=None, timeout=8, method="GET", data=None):
    hdrs = {"User-Agent": UA, "Accept": "application/json, text/plain, */*"}
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, headers=hdrs, method=method, data=data)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


# ── 歌名清洗：剥掉版本后缀/括号/时间戳，只留歌名本体 ──
_VW_CN = ('完整版?','纯享版?','现场版?','dj版?','3d环绕?','环绕','高清','mv','官方',
          '重制版?','remix','cover','翻唱','伴奏','instrumental','demo','钢琴版?','吉他版?',
          '古筝版?','空灵版?','空灵治愈旋律','治愈','慢摇','加长版?','串烧','原版?','新版?',
          '旧版?','抖音','快手','车载','热歌','升调','降调','女声','男声','纯音乐','无损',
          '立体声','修复','深度','重低音','完整')
_BRACKET_RE = re.compile(
    r'[（(]\s*[^（()）]*?(?:%s)[^（()）]*?\s*[)）]' % '|'.join(_VW_CN), re.IGNORECASE)
_TAIL_CN_RE = re.compile(
    r'[\s\-·・_,，]\s*(?:%s)\s*$' % '|'.join(_VW_CN), re.IGNORECASE)
_TAIL_EN_RE = re.compile(r'[\s\-·・]\s*(dj|3d|mv|hd|hq|tc|live|remix|cover|demo|inst)\s*$', re.I)
_PS_PREFIX_RE = re.compile(r'^\d{8,}[\s_\-]*PsCopy[\s_\-]*', re.I)
_PS_TOKEN_RE = re.compile(r'[\s_\-]+PsCopy[\s_\-]+', re.I)
_NUM_PREFIX_RE = re.compile(r'^\d{8,}[\s_\-]+')
_SINGER_TITLE_RE = re.compile(r'^[^-]{1,40}-(.+)$')


def clean_title(title, path=''):
    """剥掉版本括号/尾部版本词/年份/时间戳前缀，只留歌名本体。过短或清空则保留原样。"""
    t = (title or '').strip()
    orig = t
    t = _PS_PREFIX_RE.sub('', t)
    t = _PS_TOKEN_RE.sub(' ', t)
    t = _NUM_PREFIX_RE.sub('', t)

    prev = None
    while prev != t:
        prev = t
        t = _BRACKET_RE.sub('', t)

    prev = None
    while prev != t:
        prev = t
        t = _TAIL_CN_RE.sub('', t)
        t = _TAIL_EN_RE.sub('', t)

    t = re.sub(r'[·・\-—_\s]+(19|20)\d{2}\s*$', '', t)
    if len(t) > 6:
        t = re.sub(r'(19|20)\d{2}$', '', t)

    if '_PsCopy_' in (path or ''):
        m = _SINGER_TITLE_RE.match(t)
        if m and len(m.group(1).strip()) >= 2:
            t = m.group(1).strip()

    t = t.strip(' -–—·・_.,，、|/\\')
    t = re.sub(r'\s{2,}', ' ', t).strip()
    if len(t) < 2:
        return orig
    return t


def clean_artist(artist, path=''):
    """清洗歌手名：去掉 1782994xxxx_PsCopy_ 之类的脏前缀/时间戳"""
    a = (artist or '').strip()
    if not a:
        return ''
    orig = a
    a = _PS_PREFIX_RE.sub('', a)
    a = _PS_TOKEN_RE.sub(' ', a)
    a = _NUM_PREFIX_RE.sub('', a)
    a = re.sub(r'\s{2,}', ' ', a).strip(' -–—·・_.,，、|/\\')
    if len(a) < 1:
        return ''
    return a


def guess_text(path):
    """从文件名猜 标题 / 歌手"""
    name = os.path.splitext(os.path.basename(path))[0]
    name = _PS_PREFIX_RE.sub('', name)
    name = _PS_TOKEN_RE.sub(' ', name)
    name = _NUM_PREFIX_RE.sub('', name)
    name = name.replace("_", " ")
    for sep in (" - ", "-", "–", "—"):
        if sep in name:
            a, b = name.split(sep, 1)
            a, b = a.strip(), b.strip()
            if a and b:
                # 常见：歌手 - 标题 / 标题 - 歌手，取较简洁者兜底两个都试
                return {"title": b, "artist": a, "alt_title": a, "alt_artist": b}
    return {"title": name.strip(), "artist": "", "alt_title": "", "alt_artist": ""}


# ────────────────────────────── 元数据 ──────────────────────────────
def read_tags(path):
    info = {"title": "", "artist": "", "album": "", "duration": 0, "hasCover": False}
    try:
        import mutagen
    except Exception:
        g = guess_text(path)
        info["title"] = g["title"]
        info["artist"] = g["artist"]
        return info
    try:
        m = mutagen.File(path, easy=True)
        dur = 0
        try:
            raw = mutagen.File(path)
            dur = int(raw.info.length) if raw and raw.info else 0
        except Exception:
            pass
        if m:
            def g(k):
                v = m.get(k)
                if not v:
                    return ""
                v = v[0] if isinstance(v, list) else v
                return str(v).strip()
            info["title"] = g("title")
            info["artist"] = g("artist") or g("albumartist")
            info["album"] = g("album")
        info["duration"] = dur
        info["hasCover"] = bool(extract_cover(path))
    except Exception:
        pass
    if not info["title"]:
        g = guess_text(path)
        info["title"] = g["title"]
        if not info["artist"]:
            info["artist"] = g["artist"]
    info["title"] = _t2s(clean_title(info["title"], path))
    info["artist"] = _t2s(clean_artist(info["artist"], path))
    return info


def extract_cover(path):
    """返回 (bytes, mime) 或 None"""
    try:
        import mutagen
        f = mutagen.File(path)
        if f is None:
            return None
        # ID3 (mp3)
        if hasattr(f, "tags") and f.tags:
            for key in list(f.tags.keys()):
                if str(key).startswith("APIC"):
                    apic = f.tags[key]
                    return (bytes(apic.data), apic.mime or "image/jpeg")
            # MP4
            if "covr" in f.tags:
                c = f.tags["covr"][0]
                mime = "image/png" if getattr(c, "imageformat", None) == 14 else "image/jpeg"
                return (bytes(c), mime)
        # FLAC
        if getattr(f, "pictures", None):
            p = f.pictures[0]
            return (bytes(p.data), p.mime or "image/jpeg")
    except Exception:
        pass
    # 同名封面文件（在线下载时自动保存的 <歌名>.jpg / .png）
    stem = os.path.splitext(path)[0]
    for ext in (".jpg", ".png", ".jpeg", ".webp"):
        sp = stem + ext
        if os.path.isfile(sp):
            try:
                with open(sp, "rb") as fh:
                    return (fh.read(), "image/png" if ext == ".png" else "image/jpeg")
            except Exception:
                pass
    # 同目录封面文件
    base = os.path.dirname(path)
    for nm in ("cover.jpg", "cover.png", "folder.jpg", "folder.png",
               "front.jpg", "album.jpg", "AlbumArtSmall.jpg"):
        p = os.path.join(base, nm)
        if os.path.isfile(p):
            try:
                with open(p, "rb") as fh:
                    return (fh.read(), "image/png" if p.lower().endswith(".png") else "image/jpeg")
            except Exception:
                pass
    return None


# ────────────────────────────── 歌词 ──────────────────────────────
def _clean_query(s):
    if not s:
        return ""
    s = s.strip()
    # 去掉常见噪声后缀
    for junk in ("(Live)", "[Live]", "(live)", "(Remastered)", "(Explicit)",
                 "【", "】", "官方", "完整版", "高音质", "无损", "MV", "(MV)"):
        s = s.replace(junk, " ")
    s = s.replace("_", " ")
    return " ".join(s.split())


def _parse_candidates(title, artist, path):
    out = []
    t = _clean_query(title)
    a = _clean_query(artist)
    if t and a:
        out.append((t, a))
    if t:
        out.append((t, ""))
    g = guess_text(path)
    for tt, aa in ((g["title"], g["artist"]), (g["alt_title"], g["alt_artist"])):
        tt, aa = _clean_query(tt), _clean_query(aa)
        if tt and (tt, aa) not in out:
            out.append((tt, aa))
    seen, res = set(), []
    for c in out:
        if c[0] and c not in seen:
            seen.add(c)
            res.append(c)
    return res[:4]


_NORM_RE = re.compile(r"[\s\-_·・'\"’“”!！?？,，.。:：、/\&+()（）\[\]【】]")


def _norm(s):
    return _NORM_RE.sub("", (s or "").lower())


def _base_title(s):
    """歌名主体：去掉括号内容、· 后版本名、Live/Remix/伴奏/特别版 等后缀"""
    x = s or ""
    x = re.sub(r"[（(\[【].*?[)）\]】]", "", x)
    for sep in ("·", "・"):
        if sep in x:
            x = x.split(sep)[0]
    x = re.sub(r"(?i)[\s\-_]*(live|remix|dj版|cover|翻唱|伴奏|纯音乐|instrumental|特别版|片段|剪辑版|加速版|降速版|片段版).*$", "", x)
    x = re.sub(r"(?i)[\s\-_]*\d?\.\d+x.*$", "", x)
    return _norm(x)


def _title_sim(a, b):
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if a in b or b in a:
        return 0.9
    sa, sb = set(a), set(b)
    return len(sa & sb) / float(max(len(sa), len(sb)))


def _artist_match(na, cand_name):
    """na 已归一化；歌手串支持「周杰伦. / 沈幼楚」这种多人"""
    if not na:
        return True
    c = _norm(cand_name)
    if not c:
        return False
    return c == na or c in na or na in c


def lrclib_search(title, artist, duration=None):
    """严格匹配：歌名主体 + 歌手 + 时长差≤12s，不合格直接跳过（宁可没歌词也不放错歌）"""
    params = {"track_name": title}
    if artist:
        params["artist_name"] = artist
    url = "https://lrclib.net/api/search?" + urllib.parse.urlencode(params)
    try:
        txt = http_get(url, headers={"Accept": "application/json"})
        arr = json.loads(txt)
    except Exception:
        return None
    if not isinstance(arr, list) or not arr:
        return None
    bt = _base_title(title)
    na = _norm(artist)
    best, best_score = None, -1
    for it in arr[:10]:
        cand_title = it.get("trackName") or ""
        if _title_sim(bt, _base_title(cand_title)) < 0.85:
            continue
        if na and not _artist_match(na, it.get("artistName")):
            continue
        it_d = it.get("duration") or 0
        if duration and it_d and abs(float(it_d) - float(duration)) > 12:
            continue
        syn = it.get("syncedLyrics") or ""
        pln = it.get("plainLyrics") or ""
        if not syn and not pln:
            continue
        score = 6 if syn else 0
        if duration and it_d:
            diff = abs(float(it_d) - float(duration))
            score += max(0, 4 - int(diff / 3))
        if score > best_score:
            best_score, best = score, it
    if not best:
        return None
    return {
        "source": "LRCLIB",
        "synced": best.get("syncedLyrics") or "",
        "plain": best.get("plainLyrics") or "",
        "title": best.get("trackName") or title,
        "artist": best.get("artistName") or artist,
        "album": best.get("albumName") or "",
    }


def _score_netease_song(song, title, artist, duration):
    """严格打分：歌名主体必须 ≥0.85，有歌手就必须对上，时长差 >12s 直接淘汰"""
    bt = _base_title(title)
    bs = _title_sim(bt, _base_title(song.get("name") or ""))
    if bs < 0.85:
        return -1.0
    na = _norm(artist)
    artists = song.get("artists") or []
    if na:
        if not any(_artist_match(na, a.get("name") or "") for a in artists):
            return -1.0
    score = bs * 3.0 + 2.0
    dur = (song.get("duration") or 0) / 1000.0
    if duration and dur:
        diff = abs(dur - float(duration))
        if diff > 12:
            return -1.0
        score += max(0.0, 2.0 - diff / 12.0)
    return score


def netease_search(title, artist, duration=None):
    q = (title + " " + artist).strip() if artist else title
    url = ("https://music.163.com/api/search/get/web?"
           + urllib.parse.urlencode({"s": q, "type": 1, "limit": 10, "offset": 0}))
    hdrs = {"Referer": "https://music.163.com/", "Accept": "application/json"}
    try:
        arr = json.loads(http_get(url, headers=hdrs))
    except Exception:
        return None
    songs = (arr.get("result") or {}).get("songs") or []
    if not songs:
        return None
    best, best_score = None, -1.0
    for song in songs:
        sc = _score_netease_song(song, title, artist, duration)
        if sc > best_score:
            best_score, best = sc, song
    if best is None or best_score < 0:
        return None
    sid = best.get("id")
    if not sid:
        return None
    lurl = "https://music.163.com/api/song/lyric?" + urllib.parse.urlencode(
        {"id": sid, "lv": 1, "kv": 1, "tv": -1})
    try:
        d = json.loads(http_get(lurl, headers=hdrs))
    except Exception:
        return None
    syn = (d.get("lrc") or {}).get("lyric") or ""
    tlyric = (d.get("tlyric") or {}).get("lyric") or ""
    if not syn.strip():
        return None
    artists = " / ".join(a.get("name", "") for a in (best.get("artists") or []))
    return {
        "source": "网易云音乐",
        "synced": syn,
        "plain": "",
        "trans": tlyric,
        "title": best.get("name") or title,
        "artist": artists or artist,
        "album": (best.get("album") or {}).get("name") or "",
    }


def netease_song_list(keyword, limit=10, timeout=12):
    """网易云公开搜索：只取歌曲元数据（歌名 / 歌手 / 专辑 / 时长），不取歌词、不取音频。
    用于本地曲库「补全未知歌手」——只做文本查询，不涉及任何音频下载。"""
    q = (keyword or "").strip()
    if not q:
        return []
    url = ("https://music.163.com/api/search/get/web?"
           + urllib.parse.urlencode({"s": q, "type": 1, "limit": int(limit), "offset": 0}))
    hdrs = {"Referer": "https://music.163.com/", "Accept": "application/json"}
    try:
        arr = json.loads(http_get(url, headers=hdrs))
    except Exception:
        return []
    out = []
    for song in ((arr.get("result") or {}).get("songs") or []):
        artists = " / ".join(a.get("name", "") for a in (song.get("artists") or []))
        out.append({
            "title": song.get("name") or "",
            "artist": artists,
            "album": (song.get("album") or {}).get("name") or "",
            "duration": int((song.get("duration") or 0) / 1000),
        })
    return out


MOBILE_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")


def kugou_find_song(title, artist="", duration=0):
    """酷狗搜歌：严格过滤歌名主体/歌手/时长，返回 (hash, songname, singername, duration秒)"""
    try:
        q = (title + " " + artist).strip()
        surl = ("http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword="
                + urllib.parse.quote(q) + "&page=1&pagesize=10&showtype=1")
        body = http_get(surl, headers={"Referer": "https://m.kugou.com/",
                                       "User-Agent": MOBILE_UA})
        info = ((json.loads(body or "{}").get("data") or {}).get("info") or [])
        bt = _base_title(title)
        na = _norm(artist)
        best, best_score = None, -1.0
        for it in info:
            bs = _title_sim(bt, _base_title(it.get("songname") or ""))
            if bs < 0.85:
                continue
            if na and not _artist_match(na, it.get("singername") or ""):
                continue
            dur = int(it.get("duration") or 0)
            score = bs * 3.0 + 2.0
            if duration and dur:
                diff = abs(dur - float(duration))
                if diff > 12:
                    continue
                score += max(0.0, 2.0 - diff / 12.0)
            if score > best_score:
                best_score, best = score, it
        if not best:
            return None
        h = best.get("hash") or ""
        if not h:
            return None
        return (h, best.get("songname") or title, best.get("singername") or artist,
                int(best.get("duration") or 0))
    except Exception:
        return None


def kugou_search(title, artist, duration=None):
    """酷狗歌词：华语歌命中率最好（网易云没有周杰伦这类版权）"""
    t = (title or "").strip()
    if not t:
        return None
    data = kugou_find_song(t, artist or "", duration or 0)
    if not data:
        return None
    hash_, songname, singername, dur = data
    try:
        curl = ("http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword="
                + urllib.parse.quote(t) + "&duration=" + str(int(duration or 0))
                + "&hash=" + hash_)
        cbody = http_get(curl, headers={"Referer": "https://m.kugou.com/",
                                        "User-Agent": MOBILE_UA})
        cands = (json.loads(cbody or "{}").get("candidates") or [])
        if not cands:
            return None
        cand = cands[0]
        durl = ("http://lyrics.kugou.com/download?ver=1&client=pc&id="
                + str(cand.get("id") or "") + "&accesskey=" + str(cand.get("accesskey") or "")
                + "&fmt=lrc&charset=utf8")
        dbody = http_get(durl, headers={"Referer": "https://m.kugou.com/",
                                        "User-Agent": MOBILE_UA})
        content = (json.loads(dbody or "{}").get("content") or "")
        if not content:
            return None
        lrc = base64.b64decode(content).decode("utf-8", "ignore")
        if lrc.startswith("﻿"):
            lrc = lrc[1:]
        if not lrc.strip():
            return None
        return {"source": "酷狗音乐", "synced": _clean_lyrics(lrc), "plain": "",
                "title": songname or title, "artist": singername or artist, "album": ""}
    except Exception:
        return None


# ── 歌词清洗：繁→简、过滤 credits 行 ──
CREDITS_RE = re.compile(
    r'^\s*(作词|作詞|词|詞|作曲|曲|編曲|编曲|填詞|填词|製作人?|制作人?|監製|监制|混音|母帶|母带|和聲|和声|'
    r'配唱|吉他|結他|鋼琴|钢琴|貝斯|贝斯|鼓|鍵盤|键盘|演奏|演唱|主唱|'
    r'lyrics?|compos\w*|writ\w*|arrang\w*|produc\w*|mix\w*|master\w*|music|vocal\w*)'
    r'\s*[：:／/-].*$', re.IGNORECASE)

_LRC_TS = re.compile(r'^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]')

_CC = None


def _t2s(text):
    """繁体 → 简体（OpenCC 单例）"""
    global _CC
    if not text:
        return text
    try:
        if _CC is None:
            from opencc import OpenCC
            _CC = OpenCC('t2s')
        # 妳/牠 是异体字，OpenCC t2s 不转换
        return _CC.convert(text).replace('妳', '你').replace('牠', '它')
    except Exception:
        return text


def _core(line):
    return _LRC_TS.sub('', line).strip()


_META_LINE = re.compile(r'^\s*\[[a-zA-Z]{2,}:.*\]\s*$')


def _clean_lyrics(text):
    """去掉 作曲：/制作人：/Lyrics by 之类的 credits 行，正文保留"""
    if not text:
        return ''
    keep = []
    for line in text.splitlines():
        if _META_LINE.match(line):
            continue
        c = _core(line)
        if c and CREDITS_RE.match(c):
            continue
        keep.append(line)
    return '\n'.join(keep).strip()


def _has_substance(text):
    """过滤 credits 后是否还有真正的歌词内容"""
    if not text:
        return False
    n = 0
    for line in text.splitlines():
        c = _core(line)
        if c and not CREDITS_RE.match(c):
            n += 1
            if n >= 4:          # 少于 4 行的多半是残缺歌词（某些源只回一两行）
                return True
    return False


def _lyric_cache_path(title, artist):
    key = hashlib.md5((title + "|" + artist).encode("utf-8")).hexdigest()
    return os.path.join(LYRICS_DIR, key + ".json")


# ────────────────────────────── 酷狗歌单 / 批量下载辅助 ──────────────────────────────
def _first_url(text):
    m = re.search(r"https?://[^\s\"'<>，。；]+", text or "")
    return m.group(0) if m else ""












# ────────────────────────────── 静态 / 媒体 服务 ──────────────────────────────
_API = {"inst": None}
_WIN = {"hwnd": 0}
_READY = threading.Event()


SYNC_PATH = os.path.join(DATA_DIR, "sync.json")
DEFAULT_INVITE = ""   # 不再内置任何仓库；用户自行配置
USER_PATH = os.path.join(DATA_DIR, "user.json")
STATS_PATH = os.path.join(DATA_DIR, "stats.json")
TOMB_PATH = os.path.join(DATA_DIR, "tombstones.json")   # 删除墓碑：同步时广播删除，避免删的歌被合并回来
INVITE_PREFIX = "LUVSIC1."
SYNC_FILE = "luvsic-sync.json"
# 管理员账号ID（可多个）：只有这些 ID 能看到「账号管理」面板
ADMIN_IDS = set()   # 开源版不预设管理员；本地仓库的创建者自动成为管理员


def invite_encode(provider, repo, token, name=""):
    import base64 as _b64
    raw = json.dumps({"v": 1, "p": provider, "r": repo, "t": token, "n": name},
                     ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return INVITE_PREFIX + _b64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def invite_decode(code):
    import base64 as _b64
    code = (code or "").strip()
    if code.startswith(INVITE_PREFIX):
        code = code[len(INVITE_PREFIX):]
    code = code.replace("-", "+").replace("_", "/")
    code += "=" * (-len(code) % 4)
    raw = _b64.b64decode(code)
    d = json.loads(raw.decode("utf-8"))
    if not d.get("r") or not d.get("t"):
        raise ValueError("邀请码不完整")
    d["p"] = d.get("p") or "github"
    return d


class SyncClient:
    """极简仓库同步客户端：GitHub 的 contents API"""

    def __init__(self, provider, repo, token):
        self.provider = (provider or "github").lower()
        self.repo = (repo or "").strip().strip("/")
        self.token = (token or "").strip()
        base = os.environ.get("LUVSIC_SYNC_BASE")
        self.base = (base.rstrip("/") if base else "https://api.github.com")
        self.github = (self.provider == "github")

    def _url(self, path):
        import urllib.parse as up
        p = up.quote(path, safe="/")
        if self.github:
            return "%s/repos/%s/contents/%s" % (self.base, self.repo, p)
        return "%s/repos/%s/contents/%s" % (self.base, self.repo, p)

    def _headers(self):
        h = {"Accept": "application/json", "User-Agent": "Luvsic-Sync"}
        if self.github:
            h["Authorization"] = "Bearer " + self.token
        return h

    def _req(self, method, path, data=None, params=None):
        import urllib.request
        import urllib.parse as up
        url = self._url(path)
        if params:
            url += "?" + up.urlencode(params)
        body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
        req = urllib.request.Request(url, data=body, method=method, headers=self._headers())
        if body is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(body or "{}")
            except Exception:
                return e.code, {"raw": body}

    def get_file(self, path=SYNC_FILE):
        import base64
        st, data = self._req("GET", path)
        if st == 404:
            return None, None
        if st != 200:
            detail = ((data or {}).get("message") or (data or {}).get("error")
                      or " ".join((data or {}).get("messages") or []) or "")
            raise RuntimeError("读取失败 HTTP %s %s" % (st, detail))
        if isinstance(data, list) or not isinstance(data, dict) or not data.get("content"):
            return None, None
        content = base64.b64decode((data.get("content") or "").replace(chr(10), ""))
        return content, data.get("sha")

    def get_dir(self, path="users"):
        st, data = self._req("GET", path)
        if st == 404:
            return []
        if st != 200:
            raise RuntimeError("读取目录失败 HTTP %s" % st)
        if isinstance(data, list):
            return [x.get("name") for x in data if x.get("name")]
        return []

    def put_file(self, content, sha=None, message="Luvsic 同步", path=SYNC_FILE):
        import base64
        payload = {
            "content": base64.b64encode(content).decode("ascii"),
            "message": message,
        }
        if sha:
            payload["sha"] = sha
        # GitHub Contents API：新建和更新都用 PUT
        method = "PUT"
        st, data = self._req(method, path, data=payload)
        if st not in (200, 201):
            detail = ((data or {}).get("message") or (data or {}).get("error")
                      or " ".join((data or {}).get("messages") or []) or "")
            raise RuntimeError("写入失败 HTTP %s %s" % (st, detail))
        return data

def api_dispatch(name, args):
    inst = _API["inst"]
    if inst is None:
        raise RuntimeError("api not ready")
    if name.startswith("_"):
        raise ValueError("forbidden")
    fn = getattr(inst, name, None)
    if not callable(fn):
        raise ValueError("unknown api: " + name)
    return fn(*args)


class Handler(BaseHTTPRequestHandler):
    server_version = "Luvsic/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
            self.close_connection = True

    def _send(self, code, body=b"", ctype="application/octet-stream", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except Exception:
                pass

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""

        if parsed.path == "/report":
            print("FRONTEND-REPORT: " + raw.decode("utf-8", "replace"), flush=True)
            return self._send(200, b"ok", "text/plain")

        if parsed.path == "/api":
            qs = urllib.parse.parse_qs(parsed.query)
            name = qs.get("name", [""])[0]
            try:
                args = json.loads(raw.decode("utf-8")) if raw else []
                if not isinstance(args, list):
                    args = [args]
            except Exception:
                args = []
            try:
                result = api_dispatch(name, args)
                body = json.dumps(result, ensure_ascii=False, default=str).encode("utf-8")
                return self._send(200, body, "application/json; charset=utf-8")
            except Exception as e:
                body = json.dumps({"__error": f"{type(e).__name__}: {e}"},
                                  ensure_ascii=False).encode("utf-8")
                return self._send(200, body, "application/json; charset=utf-8")

        return self._send(404, b"", "text/plain")

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)
        p = parsed.path
        if p == "/music":
            return self.serve_audio(qs.get("p", [""])[0])
        if p == "/ready":
            _READY.set()
            return self._send(200, b"ok", "text/plain")
        if p == "/report":
            d = qs.get("d", [""])[0]
            print("FRONTEND-REPORT: " + urllib.parse.unquote(d), flush=True)
            return self._send(200, b"ok", "text/plain")
        if p == "/cover":
            return self.serve_cover(qs.get("p", [""])[0])
        if p in ("/", "/index.html"):
            return self.serve_file(os.path.join(UI_DIR, "index.html"))
        return self.serve_file(os.path.join(UI_DIR, p.lstrip("/")))

    def serve_file(self, path):
        path = os.path.normpath(path)
        if not path.startswith(UI_DIR) or not os.path.isfile(path):
            return self._send(404, b"not found", "text/plain")
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        if path.endswith(".html"):
            ctype = "text/html; charset=utf-8"
        elif path.endswith(".js"):
            ctype = "application/javascript; charset=utf-8"
        elif path.endswith(".css"):
            ctype = "text/css; charset=utf-8"
        with open(path, "rb") as f:
            body = f.read()
        # 前端文件禁缓存：改版即生效，不受 Edge 启发式缓存影响
        self._send(200, body, ctype, {"Cache-Control": "no-store"})

    def serve_cover(self, spath):
        if not spath:
            return self._send(404, b"", "text/plain")
        path = urllib.parse.unquote(spath)
        if not os.path.isfile(path):
            return self._send(404, b"", "text/plain")
        got = extract_cover(path)
        if not got:
            return self._send(404, b"", "text/plain")
        data, mime = got
        self._send(200, data, mime, {"Cache-Control": "no-cache, must-revalidate"})

    def serve_audio(self, spath):
        if not spath:
            return self._send(400, b"", "text/plain")
        path = urllib.parse.unquote(spath)
        if not os.path.isfile(path):
            return self._send(404, b"", "text/plain")
        ext = os.path.splitext(path)[1].lower()
        ctype = {
            ".mp3": "audio/mpeg", ".flac": "audio/flac", ".m4a": "audio/mp4",
            ".wav": "audio/wav", ".ogg": "audio/ogg", ".opus": "audio/ogg",
            ".aac": "audio/aac", ".wma": "audio/x-ms-wma", ".mp4": "audio/mp4",
            ".aiff": "audio/aiff", ".aif": "audio/aiff", ".ape": "audio/x-ape",
            ".dsf": "audio/dsf",
        }.get(ext, "application/octet-stream")
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        partial = False
        if rng and rng.startswith("bytes="):
            try:
                s = rng[6:].split(",")[0]
                a, b = s.split("-")
                if a == "":
                    start = max(0, size - int(b))
                else:
                    start = int(a)
                if b:
                    end = min(int(b), size - 1)
                partial = True
            except Exception:
                start, end, partial = 0, size - 1, False
        length = max(0, end - start + 1)
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        try:
            with open(path, "rb") as f:
                f.seek(start)
                left = length
                while left > 0:
                    chunk = f.read(min(262144, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        pass


def tint_window(hwnd):
    """把系统标题栏染成与界面同色的深色，让窗口边框几乎隐形。"""
    import ctypes
    dwm = ctypes.windll.dwmapi
    # COLORREF = 0x00BBGGRR
    attrs = (
        (20, 1),            # DWMWA_USE_IMMERSIVE_DARK_MODE
        (35, 0x00100B0A),   # DWMWA_CAPTION_COLOR  (#0A0B10)
        (36, 0x00A8C6E0),   # DWMWA_TEXT_COLOR     (#E0C6A8)
        (34, 0x00181412),   # DWMWA_BORDER_COLOR
        (33, 2),            # DWMWA_WINDOW_CORNER_PREFERENCE = round
    )
    for attr, val in attrs:
        v = ctypes.c_int(val)
        try:
            dwm.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(v), ctypes.sizeof(v))
        except Exception:
            pass
    return True


def strip_caption(hwnd):
    """（保留）彻底去掉系统标题栏；对 Chromium 窗口会导致重建，勿用于 Edge。"""
    import ctypes
    u = ctypes.windll.user32
    try:
        dwm = ctypes.windll.dwmapi
        for attr, val in ((20, 1), (33, 2), (34, 0x2E2A28)):   # 深色 / 圆角 / 边框色
            v = ctypes.c_int(val)
            dwm.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(v), ctypes.sizeof(v))
    except Exception:
        pass
    GWL_STYLE = -16
    WS_CAPTION = 0x00C00000
    style = u.GetWindowLongW(hwnd, GWL_STYLE)
    u.SetWindowLongW(hwnd, GWL_STYLE, style & ~WS_CAPTION)
    u.SetWindowPos(hwnd, 0, 0, 0, 0, 0, 0x0020 | 0x0002 | 0x0001 | 0x0004)
    u.DrawMenuBar(hwnd)
    return True


def find_edge():
    cands = [
        os.path.expandvars(r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%LocalAppData%\Microsoft\Edge\Application\msedge.exe"),
        r"C://Program Files (x86)//Microsoft//Edge//Application//msedge.exe",
    ]
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return ""


def enum_windows():
    import ctypes
    from ctypes import wintypes
    u = ctypes.windll.user32
    out = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def cb(h, _):
        if u.IsWindowVisible(h):
            n = u.GetWindowTextLengthW(h)
            if n:
                b = ctypes.create_unicode_buffer(n + 1)
                u.GetWindowTextW(h, b, n + 1)
                pid = wintypes.DWORD()
                u.GetWindowThreadProcessId(h, ctypes.byref(pid))
                out.append((h, b.value, pid.value))
        return True

    u.EnumWindows(cb, 0)
    return out


_MUTEX = {"handle": None, "first": None}


def acquire_instance():
    """第一个实例返回 True；已有活着的实例返回 False（同一进程只判断一次）"""
    if _MUTEX["first"] is not None:
        return _MUTEX["first"]
    try:
        import ctypes
        k32 = ctypes.windll.kernel32
        k32.SetLastError(0)
        h = k32.CreateMutexW(None, False, "LuvsicSingleInstance_v1")
        err = k32.GetLastError()
        _MUTEX["handle"] = h
        _MUTEX["first"] = (err != 183)   # 183 = ERROR_ALREADY_EXISTS
        return _MUTEX["first"]
    except Exception:
        return True


def close_stale_window(hwnd):
    """后端已死、只剩一个 Edge 窗口时：关掉它，避免下次启动误以为程序在运行"""
    try:
        import ctypes
        ctypes.windll.user32.PostMessageW(hwnd, 0x0010, 0, 0)   # WM_CLOSE
        time.sleep(0.6)
    except Exception:
        pass


def find_app_window(hint="Luvsic", tries=40, delay=0.25):
    low = hint.lower()
    for _ in range(tries):
        for h, title, pid in enum_windows():
            t = title.strip().lower()
            # 必须精确匹配 "Luvsic"（或 "Luvsic - xxx" / "Luvsic — xxx"）。
            # 不能用裸 startswith：其他窗口只要标题以 "Luvsic" 开头（例如浏览器页面
            # 标题为「Luvsic 交接摘要…」）就会被误判成 App 窗口，导致双击图标只把
            # 那个窗口切到前台、App 自己打不开。
            if t == low or t.startswith(low + " - ") or t.startswith(low + " — "):
                return h
        time.sleep(delay)
    return 0


def center_window(hwnd, w, h):
    import ctypes
    u = ctypes.windll.user32
    sw = u.GetSystemMetrics(0)
    sh = u.GetSystemMetrics(1)
    x = max(0, (sw - w) // 2)
    y = max(0, (sh - h) // 2 - 20)
    u.MoveWindow(hwnd, x, y, w, h, True)


def free_port(prefer=8756):
    for port in [prefer] + list(range(8757, 8860)):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    return 0


# ────────────────────────────── JS 桥 ──────────────────────────────
class Api:
    def __init__(self):
        self._window = None
        self._title = "Luvsic"
        self._maxed = False
        self._lock = threading.Lock()

    # ---- 窗口 ----
    def _hwnd(self):
        h = _WIN.get("hwnd") or 0
        if not h:
            h = find_app_window("Luvsic", tries=2, delay=0)
            _WIN["hwnd"] = h
        return h

    def min_window(self):
        import ctypes
        h = self._hwnd()
        if h:
            ctypes.windll.user32.ShowWindow(h, 6)     # SW_MINIMIZE
        return True

    def max_window(self):
        import ctypes
        u = ctypes.windll.user32
        h = self._hwnd()
        if not h:
            return False
        self._maxed = not getattr(self, "_maxed", False)
        u.ShowWindow(h, 3 if self._maxed else 9)      # SW_MAXIMIZE / SW_RESTORE
        return self._maxed

    def close_window(self):
        import ctypes
        h = self._hwnd()
        if h:
            ctypes.windll.user32.PostMessageW(h, 0x0010, 0, 0)
        return True

    def start_drag(self):
        import ctypes
        u = ctypes.windll.user32
        h = self._hwnd()
        if h:
            u.ReleaseCapture()
            u.PostMessageW(h, 0x0112, 0xF012, 0)      # WM_SYSCOMMAND / SC_MOVE|HTCAPTION
        return True

    def toggle_top(self):
        import ctypes
        u = ctypes.windll.user32
        h = self._hwnd()
        if h:
            u.SetWindowPos(h, -1, 0, 0, 0, 0, 0x0001 | 0x0002)
        return True

    # ---- 配置 ----
    def get_config(self):
        return _load_json(CONFIG_PATH, {})

    def save_config(self, cfg):
        _save_json(CONFIG_PATH, cfg or {})
        return True

    # ---- 选目录 ----
    # ---- 原生文件 / 文件夹对话框（纯 Win32，无外部依赖）----
    def _native_folder(self, title="选择音乐文件夹", hwnd=0):
        import ctypes
        from ctypes import wintypes
        shell32 = ctypes.windll.shell32
        ole32 = ctypes.windll.ole32

        class BROWSEINFOW(ctypes.Structure):
            _fields_ = [
                ("hwndOwner", wintypes.HWND),
                ("pidlRoot", ctypes.c_void_p),
                ("pszDisplayName", wintypes.LPWSTR),
                ("lpszTitle", wintypes.LPCWSTR),
                ("ulFlags", wintypes.UINT),
                ("lpfn", ctypes.c_void_p),
                ("lParam", wintypes.LPARAM),
                ("iImage", ctypes.c_int),
            ]

        try:
            ole32.CoInitialize(None)
        except Exception:
            pass

        buf = ctypes.create_unicode_buffer(260)
        bi = BROWSEINFOW()
        bi.hwndOwner = hwnd or 0
        bi.pszDisplayName = ctypes.cast(buf, wintypes.LPWSTR)
        bi.lpszTitle = title
        bi.ulFlags = 0x0040 | 0x0010 | 0x0001   # NEWDIALOGSTYLE|EDITBOX|RETURNONLYFSDIRS

        shell32.SHBrowseForFolderW.restype = ctypes.c_void_p
        shell32.SHBrowseForFolderW.argtypes = [ctypes.POINTER(BROWSEINFOW)]
        pidl = shell32.SHBrowseForFolderW(ctypes.byref(bi))
        if not pidl:
            return ""
        path = ctypes.create_unicode_buffer(1024)
        shell32.SHGetPathFromIDListW.argtypes = [ctypes.c_void_p, wintypes.LPWSTR]
        shell32.SHGetPathFromIDListW.restype = wintypes.BOOL
        shell32.SHGetPathFromIDListW(pidl, path)
        ole32.CoTaskMemFree.argtypes = [ctypes.c_void_p]
        ole32.CoTaskMemFree(pidl)
        return path.value or ""

    def _native_files(self, title="选择音频文件", hwnd=0, filt=None, multi=True):
        import ctypes
        from ctypes import wintypes
        comdlg32 = ctypes.windll.comdlg32
        ole32 = ctypes.windll.ole32

        class OPENFILENAMEW(ctypes.Structure):
            _fields_ = [
                ("lStructSize", wintypes.DWORD),
                ("hwndOwner", wintypes.HWND),
                ("hInstance", wintypes.HINSTANCE),
                ("lpstrFilter", wintypes.LPCWSTR),
                ("lpstrCustomFilter", wintypes.LPWSTR),
                ("nMaxCustFilter", wintypes.DWORD),
                ("nFilterIndex", wintypes.DWORD),
                ("lpstrFile", wintypes.LPWSTR),
                ("nMaxFile", wintypes.DWORD),
                ("lpstrFileTitle", wintypes.LPWSTR),
                ("nMaxFileTitle", wintypes.DWORD),
                ("lpstrInitialDir", wintypes.LPCWSTR),
                ("lpstrTitle", wintypes.LPCWSTR),
                ("Flags", wintypes.DWORD),
                ("nFileOffset", wintypes.WORD),
                ("nFileExtension", wintypes.WORD),
                ("lpstrDefExt", wintypes.LPCWSTR),
                ("lCustData", wintypes.LPARAM),
                ("lpfnHook", ctypes.c_void_p),
                ("lpTemplateName", wintypes.LPCWSTR),
                ("pvReserved", ctypes.c_void_p),
                ("dwReserved", wintypes.DWORD),
                ("FlagsEx", wintypes.DWORD),
            ]

        try:
            ole32.CoInitialize(None)
        except Exception:
            pass

        if filt is None:
            filt = "音频文件\0*.mp3;*.flac;*.m4a;*.wav;*.ogg;*.opus;*.aac;*.wma;*.ape;*.aiff\0所有文件\0*.*\0\0"
        size = 32768
        buf = ctypes.create_unicode_buffer(size)
        ofn = OPENFILENAMEW()
        ofn.lStructSize = ctypes.sizeof(OPENFILENAMEW)
        ofn.hwndOwner = hwnd or 0
        ofn.lpstrFilter = filt
        ofn.lpstrFile = ctypes.cast(buf, wintypes.LPWSTR)
        ofn.nMaxFile = size
        ofn.lpstrTitle = title
        flags = 0x00080000 | 0x00001000 | 0x00000008  # EXPLORER|FILEMUSTEXIST|PATHMUSTEXIST
        if multi:
            flags |= 0x00000200  # ALLOWMULTISELECT
        ofn.Flags = flags
        ofn.lpstrDefExt = "mp3"

        comdlg32.GetOpenFileNameW.argtypes = [ctypes.POINTER(OPENFILENAMEW)]
        comdlg32.GetOpenFileNameW.restype = wintypes.BOOL
        if not comdlg32.GetOpenFileNameW(ctypes.byref(ofn)):
            return []

        raw = buf[:]
        parts = []
        cur = []
        for ch in raw:
            if ch == "\0":
                if cur:
                    parts.append("".join(cur))
                    cur = []
                else:
                    break
            else:
                cur.append(ch)
        if cur:
            parts.append("".join(cur))
        if len(parts) <= 1:
            return parts
        folder = parts[0]
        return [os.path.join(folder, p) for p in parts[1:]]

    def pick_download_folder(self):
        """选择「搜索歌曲下载到」的文件夹（只返回路径，不改曲库目录）"""
        return self._native_folder(title="选择下载保存文件夹", hwnd=self._hwnd())

    def pick_folder(self):
        return self._native_folder(hwnd=self._hwnd())

    def pick_files(self):
        v = self._native_files(hwnd=self._hwnd())
        return v if isinstance(v, list) else []

    def pick_image(self):
        """选择一张图片（壁纸用），单选，返回路径或空串"""
        filt = "图片文件\0*.jpg;*.jpeg;*.png;*.webp;*.bmp\0所有文件\0*.*\0\0"
        v = self._native_files(title="选择壁纸图片", hwnd=self._hwnd(), filt=filt, multi=False)
        if isinstance(v, list) and v:
            return v[0]
        return v if isinstance(v, str) else ""

    def pick_file(self, title="选择文件", filt=None):
        v = self._native_files(title=title, hwnd=self._hwnd(), filt=filt, multi=False)
        if isinstance(v, list) and v:
            return v[0]
        return v if isinstance(v, str) else ""

    def export_playlist_m3u(self, playlist_id=""):
        """把歌单导出成 .m3u 文件（选目录保存）。playlist_id 为空 = 全部音乐。"""
        try:
            def norm(it):
                if isinstance(it, dict):
                    return it
                sp = str(it or "")
                return {"path": sp, "name": os.path.basename(sp)}

            name = "全部音乐"
            items = [norm(t) for t in (self.cached_library().get("tracks") or [])]
            if playlist_id:
                pl = None
                for p in self.get_playlists().get("playlists") or []:
                    if str(p.get("id")) == str(playlist_id):
                        pl = p
                        break
                if pl is None:
                    return {"ok": False, "msg": "歌单不存在"}
                items = [norm(it) for it in (pl.get("items") or [])]
                name = str(pl.get("name") or "歌单")
            folder = self.pick_folder()
            if not folder:
                return {"ok": False, "msg": "已取消"}
            lines = ["#EXTM3U"]
            cnt = 0
            for t in items:
                p = t.get("path") or ""
                if not p:
                    continue
                title = t.get("title") or t.get("name") or os.path.basename(p)
                artist = t.get("artist") or ""
                try:
                    dur = int(float(t.get("duration") or 0))
                except Exception:
                    dur = 0
                label = (artist + " - " + title) if artist else title
                lines.append("#EXTINF:%d,%s" % (dur, label))
                lines.append(p)
                cnt += 1
            fn = re.sub(r'[\\/:*?"<>|]+', "_", name) + ".m3u"
            dst = os.path.join(folder, fn)
            with open(dst, "w", encoding="utf-8-sig") as f:
                f.write("\n".join(lines) + "\n")
            return {"ok": True, "path": dst, "count": cnt,
                    "msg": "已导出 %d 首到 %s" % (cnt, dst)}
        except Exception as e:
            return {"ok": False, "msg": "导出失败：%s" % e}

    def export_backup(self):
        """一键备份：曲库/歌单/统计/设置打包成 zip（选目录保存）。"""
        try:
            import zipfile
            folder = self.pick_folder()
            if not folder:
                return {"ok": False, "msg": "已取消"}
            fn = "Luvsic备份-" + time.strftime("%Y%m%d-%H%M") + ".zip"
            dst = os.path.join(folder, fn)
            files = ["library.json", "playlists.json", "stats.json", "config.json",
                     "tombstones.json", "user.json", "nicknames.json"]
            cnt = 0
            with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
                for f in files:
                    p = os.path.join(DATA_DIR, f)
                    if os.path.isfile(p):
                        z.write(p, f)
                        cnt += 1
            return {"ok": True, "path": dst, "count": cnt,
                    "msg": "已备份 %d 个文件到 %s" % (cnt, dst)}
        except Exception as e:
            return {"ok": False, "msg": "备份失败：%s" % e}

    def import_backup(self, zip_path=""):
        """一键恢复：选 zip → 先把当前数据备份到 data/backup-before-import → 覆盖。"""
        try:
            import shutil
            import zipfile
            if not zip_path:
                zip_path = self.pick_file("选择 Luvsic 备份文件",
                                          "备份文件\0*.zip\0所有文件\0*.*\0\0")
            if not zip_path or not os.path.isfile(zip_path):
                return {"ok": False, "msg": "已取消或文件不存在"}
            bak_dir = os.path.join(DATA_DIR, "backup-before-import")
            os.makedirs(bak_dir, exist_ok=True)
            stamp = time.strftime("%Y%m%d-%H%M%S")
            allowed = {"library.json", "playlists.json", "stats.json", "config.json",
                       "tombstones.json", "user.json", "nicknames.json"}
            with zipfile.ZipFile(zip_path) as z:
                names = set(z.namelist()) & allowed
                if not names:
                    return {"ok": False, "msg": "这个 zip 里没有 Luvsic 数据文件"}
                for f in ["library.json", "playlists.json", "stats.json", "config.json",
                          "tombstones.json", "user.json", "nicknames.json"]:
                    p = os.path.join(DATA_DIR, f)
                    if os.path.isfile(p):
                        shutil.copy2(p, os.path.join(bak_dir, f + "." + stamp))
                cnt = 0
                for n in names:
                    z.extract(n, DATA_DIR)
                    cnt += 1
            return {"ok": True, "count": cnt,
                    "msg": "已恢复 %d 个文件（恢复前数据备份在 data/backup-before-import）" % cnt}
        except Exception as e:
            return {"ok": False, "msg": "恢复失败：%s" % e}

    def set_custom_wallpaper(self, spath):
        """把用户选的图片归一化为 ui/wall/custom.jpg，作为固定壁纸"""
        import shutil
        path = urllib.parse.unquote(spath or "")
        if not path or not os.path.isfile(path):
            return {"ok": False, "msg": "文件不存在"}
        if os.path.splitext(path)[1].lower() not in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
            return {"ok": False, "msg": "不是支持的图片格式"}
        wall_dir = os.path.join(UI_DIR, "wall")
        os.makedirs(wall_dir, exist_ok=True)
        # 清掉旧的自定义壁纸
        for e in (".jpg", ".jpeg", ".png", ".webp", ".bmp"):
            p = os.path.join(wall_dir, "custom" + e)
            if os.path.isfile(p):
                try: os.remove(p)
                except Exception: pass
        dst = os.path.join(wall_dir, "custom.jpg")
        try:
            from PIL import Image
            Image.open(path).convert("RGB").save(dst, "JPEG", quality=90)
        except Exception:
            shutil.copyfile(path, dst)
        return {"ok": True, "url": "/wall/custom.jpg"}

    # ---- 音乐库 ----
    def set_folder(self, folder):
        """只记住音乐文件夹（不自动扫入曲库，扫描后由用户勾选）"""
        folder = (folder or "").strip()
        if not folder or not os.path.isdir(folder):
            return {"ok": False, "msg": "目录不存在"}
        cfg = self.get_config() or {}
        cfg["folder"] = folder
        self.save_config(cfg)
        return {"ok": True, "folder": folder}

    def _scan_tracks(self, folder):
        """只扫描并返回曲目列表，不写曲库文件"""
        tracks = []
        for base, dirs, files in os.walk(folder):
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            for fn in files:
                if os.path.splitext(fn)[1].lower() in AUDIO_EXT:
                    tracks.append(os.path.join(base, fn))
            if len(tracks) > 6000:
                break
        out = []
        for path in tracks:
            t = read_tags(path)
            out.append({
                "path": path,
                "name": os.path.basename(path),
                "title": t["title"],
                "artist": t["artist"],
                "album": t["album"],
                "duration": t["duration"],
                "hasCover": t["hasCover"],
            })
        out.sort(key=lambda x: (x["artist"] or "zzz", x["title"]))
        return out

    def scan_folder(self, folder):
        if not folder or not os.path.isdir(folder):
            return {"ok": False, "tracks": [], "msg": "目录不存在"}
        out = self._scan_tracks(folder)
        _save_json(LIB_PATH, {"folder": folder, "tracks": out, "ts": int(time.time())})
        try:
            self._clear_tombstones(out)
        except Exception:
            pass
        return {"ok": True, "tracks": out, "folder": folder, "msg": f"{len(out)} 首"}

    def library_candidates(self, folder=""):
        """扫描文件夹里的候选歌曲（不写库），并标出哪些已在曲库里"""
        cfg = self.get_config() or {}
        folder = (folder or "").strip() or (cfg.get("folder") or "")
        if not folder or not os.path.isdir(folder):
            return {"ok": False, "tracks": [], "folder": folder, "msg": "先选择音乐文件夹"}
        out = self._scan_tracks(folder)
        cur = set(t.get("path") for t in (self.cached_library().get("tracks") or []))
        for t in out:
            t["inLib"] = t["path"] in cur
        return {"ok": True, "tracks": out, "folder": folder, "msg": f"{len(out)} 首"}

    def library_set_picks(self, paths):
        """按用户勾选的路径写入曲库（电脑版/手机版统一入口）"""
        paths = [p for p in (paths or []) if isinstance(p, str)]
        cfg = self.get_config() or {}
        folder = cfg.get("folder") or ""
        if not folder or not os.path.isdir(folder):
            return {"ok": False, "tracks": [], "msg": "先选择音乐文件夹"}
        out = self._scan_tracks(folder)
        want = set(paths)
        sel = [t for t in out if t["path"] in want]
        _save_json(LIB_PATH, {"folder": folder, "tracks": sel, "ts": int(time.time())})
        try:
            self._clear_tombstones(sel)
        except Exception:
            pass
        return {"ok": True, "tracks": sel, "folder": folder, "msg": f"已加入 {len(sel)} 首"}

    # ---- 歌曲信息编辑 / 联网补全 ----
    @staticmethod
    def _write_tags(path, title, artist, album):
        """把歌名/歌手/专辑写回音频文件标签（支持常见格式）"""
        import mutagen
        f = mutagen.File(path, easy=True)
        if f is None:
            raise RuntimeError("不支持写这个格式的标签")
        f["title"] = title or ""
        f["artist"] = artist or ""
        f["album"] = album or ""
        f.save()

    def update_track_meta(self, path, title="", artist="", album=""):
        data = self.cached_library()
        tracks = data.get("tracks") or []
        hit = None
        for t in tracks:
            if t.get("path") == path:
                hit = t
                break
        if hit is None:
            return {"ok": False, "msg": "曲库里没有这首歌", "track": None}
        title = (title or "").strip() or hit.get("title") or ""
        artist = (artist or "").strip()
        album = (album or "").strip()
        hit["title"] = _t2s(title)
        hit["artist"] = _t2s(artist)
        hit["album"] = _t2s(album)
        _save_json(LIB_PATH, data)
        msg = "已保存"
        try:
            self._write_tags(path, hit["title"], hit["artist"], hit["album"])
            msg = "已保存（含文件标签）"
        except Exception as e:
            msg = "已存到曲库（文件标签写入失败：%s）" % e
        return {"ok": True, "msg": msg, "track": hit}





    _META_FIX = {"state": {}, "thread": None}

    def _meta_score(self, track, song):
        t1 = track.get("title") or ""
        t2 = song.get("title") or ""
        sim = _title_sim(_norm(t1), _norm(t2))
        if sim < 0.6:
            return -1
        d1 = int(track.get("duration") or 0)
        d2 = int(song.get("duration") or 0)
        if d1 and d2 and abs(d1 - d2) > 12:
            return -1
        return sim

    def meta_fix_start(self):
        """把曲库里「未知艺术家」的歌，按歌名联网匹配补全歌手 / 专辑。
        数据源：网易云公开搜索（只读文本元数据）。"""
        th = self._META_FIX.get("thread")
        if th and th.is_alive():
            return {"ok": True, "msg": "已经在补全中"}
        st = self._META_FIX["state"]
        st.clear()
        st.update({"running": True, "done": 0, "total": 0, "fixed": 0, "msg": "准备中"})

        def worker():
            import concurrent.futures as cf
            data = self.cached_library()
            tracks = data.get("tracks") or []
            todo = [t for t in tracks
                    if not (t.get("artist") or "").strip()
                    or (t.get("artist") or "").strip() in ("未知艺术家", "未知歌手")]
            st["total"] = len(todo)
            st["msg"] = "补全中"

            def work(t):
                kw = (t.get("title") or "").strip()
                if not kw:
                    return False
                try:
                    songs = netease_song_list(kw, 10)
                except Exception:
                    return False
                best, bs = None, -1.0
                for song in songs:
                    sc = self._meta_score(t, song)
                    if sc > bs:
                        bs, best = sc, song
                if not best or bs < 0.6:
                    return False
                artist = (best.get("artist") or "").strip()
                if not artist:
                    return False
                t["artist"] = _t2s(artist)
                if not (t.get("album") or "").strip():
                    t["album"] = _t2s(best.get("album") or "")
                return True

            try:
                with cf.ThreadPoolExecutor(max_workers=4) as ex:
                    for ok in ex.map(work, todo):
                        st["done"] += 1
                        if ok:
                            st["fixed"] += 1
                _save_json(LIB_PATH, data)
                st.update({"running": False, "msg": "完成，补全 %d 首" % st["fixed"]})
            except Exception as e:
                st.update({"running": False, "msg": "失败：%s" % e})

        th = threading.Thread(target=worker, daemon=True)
        th.start()
        self._META_FIX["thread"] = th
        return {"ok": True, "msg": "开始补全"}

    def meta_fix_status(self):
        return {"ok": True, "state": self._META_FIX["state"]}

    def cached_library(self):
        data = _load_json(LIB_PATH, {"folder": "", "tracks": []})
        # 旧曲库里的歌名/歌手可能是从文件名硬解析出来的脏数据，读出来时顺手清洗一遍
        try:
            for t in (data.get("tracks") or []):
                p = t.get("path") or ""
                t["title"] = _t2s(clean_title(t.get("title") or "", p))
                t["artist"] = _t2s(clean_artist(t.get("artist") or "", p))
        except Exception:
            pass
        return data

    @staticmethod
    def _safe_name(s):
        s = re.sub('[' + chr(92) + '/:*?"<>|' + chr(13) + chr(10) + chr(9) + ']+', ' ', s or '')
        return re.sub('[' + chr(92) + 's]+', ' ', s).strip(' .')[:120]






    # ---- 批量下载（后台线程 + 状态查询）----



    # ---- 微信歌曲导入 ----
    # 主人的歌都是经「文件传输助手」发的 MP3；m4a/aac 基本是手机录音、通话、语音条，不收
    _WX_EXT = {".mp3", ".flac", ".ape", ".wav"}
    _WX_SKIP_WORDS = ("录音", "通话", "语音", "视频", "检测", "祝福", "wx_voice", "voice-", "~$")
    _WX_SKIP_RE = re.compile(r"^\d{8}_\d+|^REC\d|^wx_camera", re.I)

    @staticmethod
    def _norm_stem(fn):
        """去重用的名字指纹：去掉扩展名、(1)(2) 这类副本后缀、空格，小写"""
        s = os.path.splitext(os.path.basename(fn))[0]
        s = re.sub(r"[\(（]\s*\d+\s*[\)）]", "", s)
        s = re.sub(r"\s+", "", s).lower()
        return s

    def _wx_candidates(self):
        """可能的微信文件根目录（优先用户指定的）"""
        cfg = self.get_config() or {}
        cands = []
        root = (cfg.get("wxRoot") or "").strip()
        if root:
            cands.append(root)
        home = os.path.expanduser("~")
        for d in ("C:", "D:", "E:", "F:", "G:"):
            cands.append(d + "\\xwechat_files")                       # 微信 4.x
            cands.append(os.path.join(d + "\\", "Documents", "xwechat_files"))
        cands.append(os.path.join(home, "Documents", "xwechat_files"))
        cands.append(os.path.join(home, "xwechat_files"))
        cands.append(os.path.join(home, "Documents", "WeChat Files"))  # 微信 3.x
        seen, out = set(), []
        for p in cands:
            k = os.path.normcase(os.path.abspath(p))
            if k in seen:
                continue
            seen.add(k)
            if os.path.isdir(p):
                out.append(p)
        return out

    def _wx_scan(self, root):
        """扫一个微信根目录，返回 (歌曲路径, 跳过的非歌曲数)"""
        songs, skipped = [], 0
        if not root or not os.path.isdir(root):
            return songs, skipped
        for acct in os.listdir(root):
            ad = os.path.join(root, acct)
            if not os.path.isdir(ad):
                continue
            # 4.x: <账号>\msg\file\YYYY-MM\*   3.x: <账号>\FileStorage\File\YYYY-MM\*
            for base_root in (os.path.join(ad, "msg", "file"),
                              os.path.join(ad, "FileStorage", "File")):
                if not os.path.isdir(base_root):
                    continue
                for base, dirs, files in os.walk(base_root):
                    dirs[:] = [d for d in dirs if not d.startswith((".", "@", "_"))]
                    for fn in files:
                        ext = os.path.splitext(fn)[1].lower()
                        if ext not in self._WX_EXT:      # 只要歌曲类格式
                            continue
                        # 剔掉录音 / 通话 / 视频 / 考试音频这类非歌曲
                        if any(w in fn for w in self._WX_SKIP_WORDS) or self._WX_SKIP_RE.search(fn):
                            skipped += 1
                            continue
                        p = os.path.join(base, fn)
                        try:
                            if os.path.getsize(p) < 100 * 1024:   # 太小，多半是语音碎片
                                skipped += 1
                                continue
                        except OSError:
                            continue
                        songs.append(p)
        return songs, skipped

    @staticmethod
    def _norm_text(s):
        """标题指纹：繁转简 + 去标点空白 + 小写"""
        s = _t2s(s or "")
        s = re.sub(r"[\s\-_·・'\"’“”!！?？,，.。:：、/\\&+()（）\[\]【】]", "", s).lower()
        return s

    def scan_wechat_music(self, root=""):
        """一键：把微信收到的音频复制进曲库，再重建索引（不删除微信原件）"""
        import shutil
        cfg = self.get_config() or {}
        lib = (cfg.get("folder") or "").strip()
        if not lib or not os.path.isdir(lib):
            return {"ok": False, "msg": "还没设置曲库文件夹，先去曲库页选一个"}

        roots = [root] if (root and os.path.isdir(root)) else self._wx_candidates()
        if not roots:
            return {"ok": False, "needPick": True, "msg": "没找到微信文件目录"}

        songs, skipped = [], 0
        for r in roots[:3]:
            s, k = self._wx_scan(r)
            songs += s
            skipped += k
        if not songs:
            return {"ok": True, "found": 0, "added": 0, "skipped": skipped, "dup": 0,
                    "msg": "微信里没找到音频文件"}

        # 曲库里已有的：① 文件名指纹（吃掉 (1)(2) 副本）② 标题+时长指纹（吃掉落名重复）
        exist, lib_titles = set(), {}
        try:
            for fn in os.listdir(lib):
                if os.path.splitext(fn)[1].lower() in AUDIO_EXT:
                    exist.add(self._norm_stem(fn))
        except OSError:
            pass
        for t in (self.cached_library().get("tracks") or []):
            tt = self._norm_text(t.get("title"))
            if tt:
                lib_titles.setdefault(tt, []).append(t.get("duration") or 0)

        # 同一首歌只留体积最大的那份
        best, dup = {}, 0
        for p in songs:
            key = self._norm_stem(p)
            if key in exist:
                dup += 1
                continue
            try:
                sz = os.path.getsize(p)
                tags = read_tags(p)
            except Exception:
                continue
            tt = self._norm_text(tags.get("title"))
            dur = tags.get("duration") or 0
            if tt and tt in lib_titles:
                olds = [d for d in lib_titles[tt] if d]
                if not dur or not olds or any(abs(dur - d) <= 10 for d in olds):
                    dup += 1
                    continue
            if key not in best or sz > best[key][1]:
                best[key] = (p, sz)

        added, names = 0, []
        for src, _sz in best.values():
            fn = os.path.basename(src)
            dst = os.path.join(lib, fn)
            if os.path.exists(dst):                      # 同名不同曲，加序号
                stem, ext = os.path.splitext(fn)
                i = 2
                while os.path.exists(dst):
                    dst = os.path.join(lib, "%s (%d)%s" % (stem, i, ext))
                    i += 1
            try:
                shutil.copy2(src, dst)
            except Exception:
                continue
            added += 1
            names.append(os.path.basename(dst))

        if not added:
            return {"ok": True, "found": len(songs), "added": 0, "skipped": skipped,
                    "dup": dup, "msg": "微信里的歌都已经在曲库了"}

        sc = self.scan_folder(lib)
        tracks = sc.get("tracks", []) if isinstance(sc, dict) else []
        return {"ok": True, "found": len(songs), "added": added, "skipped": skipped,
                "dup": dup, "names": names[:40], "folder": lib, "tracks": tracks,
                "msg": "新导入 %d 首，曲库共 %d 首" % (added, len(tracks))}


    # ---- 歌单 / 移除 ----
    # ---- 账号与同步（邀请码 = 平台+私有仓库+令牌）----
    def _sync_conf(self):
        return _load_json(SYNC_PATH, {})

    def _sync_client(self):
        c = self._sync_conf()
        if not c.get("repo") or not c.get("token"):
            return None
        return SyncClient(c.get("provider"), c.get("repo"), c.get("token"))

    # ---- 用户资料 / 听歌时长 ----
    def _user_conf(self):
        return _load_json(USER_PATH, {})

    def _user_id(self):
        d = self._user_conf()
        uid = str(d.get("id") or "")
        if re.match(r"^\d{8}$", uid):
            return uid
        # 旧版本可能是 u 开头或 uuid：迁移成 8 位数字，保证好友/排行榜可用
        if uid and not re.match(r"^\d{8}$", uid):
            import random
            d["id"] = "%08d" % random.randint(10_000_000, 99_999_999)
            _save_json(USER_PATH, d)
            return d["id"]
        # 自愈：user.json 的 id 意外丢失时，从 sync.json 冗余的 userId 恢复
        c = self._sync_conf()
        sid = str(c.get("userId") or "")
        if re.match(r"^\d{8}$", sid):
            d["id"] = sid
            _save_json(USER_PATH, d)
            return sid
        if not d.get("id"):
            # 生成 8 位数字 ID：好友码、排行榜等功能都要求 8 位
            import random
            d["id"] = "%08d" % random.randint(10_000_000, 99_999_999)
            _save_json(USER_PATH, d)
            return d["id"]
        return d["id"]

    def _remote_path(self, name):
        return "users/%s/%s" % (self._user_id(), name)

    def profile_get(self):
        d = self._user_conf()
        return {"ok": True, "id": self._user_id(), "name": d.get("name") or "",
                "avatar": d.get("avatar") or ""}

    def profile_set(self, name="", avatar="", avatar_path=""):
        d = self._user_conf()
        self._user_id()
        if name:
            d["name"] = (name or "")[:24]
        if avatar:
            d["avatar"] = avatar
        if avatar_path:
            p = urllib.parse.unquote(avatar_path)
            if os.path.isfile(p):
                b = open(p, "rb").read()
                if len(b) > 400 * 1024:
                    return {"ok": False, "msg": "头像图片太大（请小于 400KB）"}
                ext = os.path.splitext(p)[1].lower().lstrip(".") or "png"
                mime = "jpeg" if ext in ("jpg", "jpeg") else ext
                d["avatar"] = "data:image/%s;base64,%s" % (mime, base64.b64encode(b).decode("ascii"))
        _save_json(USER_PATH, d)
        # 改完立刻把资料推到云端，并把昵称同步进账号表（好友/管理页立刻看到新名字）
        try:
            cli = self._sync_client()
            uid = self._user_id()
            if cli and uid:
                prof = self.profile_get() or {}
                pl = {"id": uid, "name": prof.get("name", ""),
                      "avatar": prof.get("avatar", ""), "updatedAt": int(time.time())}
                raw = json.dumps(pl, ensure_ascii=False).encode("utf-8")
                _, sha = cli.get_file("users/%s/profile.json" % uid)
                cli.put_file(raw, sha, "更新资料", path="users/%s/profile.json" % uid)
                raw2, sha2 = cli.get_file("accounts.json")
                if raw2:
                    acc = json.loads(raw2.decode("utf-8", "replace"))
                    for a in acc.get("accounts") or []:
                        if str(a.get("id")) == str(uid):
                            a["name"] = prof.get("name", "")
                            break
                    acc["updatedAt"] = int(time.time())
                    cli.put_file(json.dumps(acc, ensure_ascii=False).encode("utf-8"), sha2,
                                 "同步账号昵称", path="accounts.json")
        except Exception:
            pass
        return {"ok": True, "profile": self.profile_get()}

    def stats_add(self, seconds=0):
        try:
            sec = float(seconds)
        except Exception:
            sec = 0.0
        if sec <= 0:
            return {"ok": True}
        d = _load_json(STATS_PATH, {"days": {}, "total": 0})
        day = time.strftime("%Y-%m-%d")
        d.setdefault("days", {})
        d["days"][day] = round(float(d["days"].get(day, 0)) + sec, 1)
        d["total"] = round(float(d.get("total", 0)) + sec, 1)
        _save_json(STATS_PATH, d)
        return {"ok": True, "total": int(d["total"])}

    def stats_get(self):
        d = _load_json(STATS_PATH, {"days": {}, "total": 0})
        day = time.strftime("%Y-%m-%d")
        return {"ok": True, "today": int(float(d.get("days", {}).get(day, 0))),
                "total": int(float(d.get("total", 0)))}

    def _stats_device_key(self):
        dev_id, _ = self._device_id()
        return "desktop:" + dev_id

    def _stats_merge_remote(self, cli):
        """按设备累加听歌时长并上报（电脑/手机共用同一套云端结构）。

        云端结构：
          {"devices": {"desktop:<设备ID>": 秒, "android:<设备ID>": 秒}, "total": 秒之和}
        规则：
          - 同一设备反复上报，只取该设备自己的最大值（不会把历史重复累加）
          - 不同设备的值相加 = 账号总时长
          - 读云端失败或云端数据损坏时返回 None，调用方跳过本次上报，避免误覆盖
        """
        try:
            uid = self._user_id()
            if not uid:
                return None
            path = "users/%s/stats.json" % uid
            raw, sha = cli.get_file(path)
            devices = {}
            if raw is not None:
                try:
                    old = json.loads(raw.decode("utf-8", "replace"))
                except Exception:
                    return None
                if isinstance(old.get("devices"), dict):
                    devices = dict(old.get("devices") or {})
            st = self.stats_get()
            local_total = int(st.get("total") or 0)
            key = self._stats_device_key()
            prev = int(devices.get(key) or 0)
            devices[key] = max(prev, local_total)
            total = sum(int(v or 0) for v in devices.values())
            # 周榜/月榜：合并最近 30 天的每日时长（同一天取大值，不重复累加）
            local_stats = _load_json(STATS_PATH, {})
            local_days = local_stats.get("days") or {}
            cutoff = time.strftime("%Y-%m-%d", time.localtime(time.time() - 30 * 86400))
            old_days = old.get("days") if (raw is not None and isinstance(old.get("days"), dict)) else {}
            days = dict(old_days or {})
            for d, v in (local_days.items() if isinstance(local_days, dict) else []):
                if str(d) < cutoff:
                    continue
                try:
                    vv = int(float(v or 0))
                except Exception:
                    continue
                days[str(d)] = max(int(days.get(str(d)) or 0), vv)
            # 「正在听」：当前播放的歌曲（只存歌名，不存歌手）
            now = getattr(self, "_now_playing", None)
            payload = {"id": uid, "name": (self.profile_get() or {}).get("name", ""),
                       "devices": devices, "total": total, "days": days,
                       "updatedAt": int(time.time())}
            if now and now.get("title"):
                payload["now"] = {"title": now.get("title"), "playing": bool(now.get("playing")),
                                  "updatedAt": int(now.get("updatedAt") or time.time())}
            cli.put_file(json.dumps(payload, ensure_ascii=False).encode("utf-8"), sha,
                         "更新听歌时长", path=path)
            return total
        except Exception:
            return None

    def set_now_playing(self, title="", playing=False):
        """前端切歌/播放暂停时告诉后端「正在听什么」，用于好友榜「正在听」（只存歌名）。"""
        self._now_playing = {"title": str(title or "")[:80], "playing": bool(playing),
                             "updatedAt": int(time.time())}
        try:
            import threading
            threading.Thread(target=self._push_now, daemon=True).start()
        except Exception:
            pass
        return {"ok": True}

    def _push_now(self):
        """把「正在听」写进云端 stats.json 的 now 字段（不改 total），10 秒节流。"""
        try:
            now = getattr(self, "_now_playing", None)
            if not now or not now.get("title"):
                return
            ts = int(now.get("updatedAt") or 0)
            if ts - int(getattr(self, "_now_pushed_at", 0) or 0) < 10:
                return
            cli = self._sync_client()
            if not cli:
                return
            uid = self._user_id()
            if not uid:
                return
            path = "users/%s/stats.json" % uid
            raw, sha = cli.get_file(path)
            data = {}
            if raw is not None:
                try:
                    data = json.loads(raw.decode("utf-8", "replace")) or {}
                except Exception:
                    return
            if not isinstance(data, dict):
                return
            data["now"] = {"title": now.get("title"), "playing": bool(now.get("playing")),
                           "updatedAt": ts}
            data.setdefault("id", uid)
            data["updatedAt"] = int(time.time())
            cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), sha,
                         "更新正在听", path=path)
            self._now_pushed_at = ts
        except Exception:
            pass

    # ---- 同步：单仓库 Hub，所有人同一个仓库，按用户分目录 ----
    def _sync_stamp(self):
        ts = 0
        for p in (LIB_PATH, PLAYLISTS_PATH):
            try:
                ts = max(ts, int(os.path.getmtime(p)))
            except Exception:
                pass
        return ts

    def _sync_payload(self):
        lib = self.cached_library()
        pl = self.get_playlists()
        return {
            "v": 1,
            "updatedAt": int(time.time()),
            "library": {"folder": lib.get("folder") or "", "tracks": lib.get("tracks") or []},
            "playlists": pl.get("playlists") or [],
            "tombstones": self._load_tombstones(),
        }

    # ── 跨设备曲库匹配：云端保存的是别的设备的路径，不能直接覆盖本机曲库 ──
    @staticmethod
    def _norm_text(s):
        s = str(s or "").lower()
        return re.sub(r"[\s\-_·・,，.。;；:：'\"`~!！?？()（）\[\]【】{}<>《》/\\|+&]", "", s)

    def _track_key(self, t):
        a = self._norm_text(t.get("title") or "")
        b = self._norm_text(t.get("artist") or "")
        return (a + "|" + b) if a else ""

    def _track_stable_key(self, t):
        """曲目「身份证」：歌名归一化|歌手归一化|时长取整（秒）。

        删歌墓碑用它做 key；以后 stableId 正式上线时天然兼容（同一套规则）。
        """
        a = self._norm_text(t.get("title") or "")
        if not a:
            return ""
        b = self._norm_text(t.get("artist") or "")
        try:
            d = int(round(float(t.get("duration") or 0)))
        except Exception:
            d = 0
        return a + "|" + b + "|" + str(d)

    def _load_tombstones(self):
        d = _load_json(TOMB_PATH, {"tombstones": []})
        arr = d.get("tombstones") if isinstance(d, dict) else None
        return [x for x in (arr or []) if isinstance(x, dict) and x.get("key")]

    def _save_tombstones(self, tombs):
        _save_json(TOMB_PATH, {"tombstones": tombs})

    @staticmethod
    def _merge_tombstones(local, remote):
        """两个设备的墓碑取并集：同一个 key 保留最新删除时间。"""
        out = {}
        for x in (local or []) + (remote or []):
            if not isinstance(x, dict):
                continue
            k = str(x.get("key") or "")
            if not k:
                continue
            try:
                ts = int(x.get("deletedAt") or 0)
            except Exception:
                ts = 0
            old = out.get(k)
            if old is None or ts > int(old.get("deletedAt") or 0):
                out[k] = {"key": k, "deletedAt": ts,
                          "title": str(x.get("title") or "")}
        return list(out.values())

    def _add_tombstones(self, tracks):
        """删歌时记墓碑（去重，记最新删除时间）。"""
        tombs = self._load_tombstones()
        now = int(time.time())
        have = {x.get("key") for x in tombs}
        for t in tracks or []:
            k = self._track_stable_key(t)
            if k and k not in have:
                tombs.append({"key": k, "deletedAt": now, "title": str(t.get("title") or "")})
                have.add(k)
        self._save_tombstones(tombs)

    def _clear_tombstones(self, tracks):
        """重新添加曲目时清掉对应墓碑（视为恢复），避免下次同步又被删。"""
        tombs = self._load_tombstones()
        if not tombs:
            return
        drop = {self._track_stable_key(t) for t in (tracks or [])}
        keep = [x for x in tombs if x.get("key") not in drop]
        if len(keep) != len(tombs):
            self._save_tombstones(keep)

    def _filter_tombstoned(self, tracks, tombs):
        keys = {x.get("key") for x in (tombs or [])}
        if not keys:
            return tracks
        return [t for t in tracks if self._track_stable_key(t) not in keys]

    def _apply_payload(self, data):
        """拉取云端曲库：合并而不是覆盖。

        云端曲库的 path 通常是「另一台设备」的路径，直接落盘会导致本机曲库全变
        “无法播放”。这里按「文件名 → 标题+歌手+时长」把云端曲目匹配到本机文件，
        匹配到的换成本机 path；匹配不到的保留条目并标记 offline（未下载）。
        本机已有的曲目全部保留，升级/同步都不会把已下载进曲库的歌弄丢。
        """
        lib = data.get("library") or {}
        cfg = self.get_config() or {}
        folder = lib.get("folder") or cfg.get("folder") or ""
        remote_tracks = lib.get("tracks") or []
        local = _load_json(LIB_PATH, {"folder": "", "tracks": []})
        local_tracks = local.get("tracks") or []
        before_paths = {t.get("path") for t in local_tracks if t.get("path")}

        # 删除同步：合并云端墓碑到本地；墓碑命中的歌（本机/云端）都不再进入曲库
        tombs = self._merge_tombstones(self._load_tombstones(), data.get("tombstones") or [])
        self._save_tombstones(tombs)
        tomb_keys = {x.get("key") for x in tombs}
        local_tracks = [t for t in local_tracks if self._track_stable_key(t) not in tomb_keys]
        remote_tracks = [t for t in remote_tracks if self._track_stable_key(t) not in tomb_keys]

        by_name, by_key = {}, {}
        for t in local_tracks:
            p = t.get("path") or ""
            nm = self._norm_text(os.path.splitext(os.path.basename(p or t.get("name") or ""))[0])
            if nm and nm not in by_name:
                by_name[nm] = t
            k = self._track_key(t)
            if k and k not in by_key:
                by_key[k] = t

        merged, merged_map = [], {}

        def _add(t):
            p = t.get("path") or ""
            if p and p in merged_map:
                return merged_map[p]
            if p:
                merged_map[p] = t
            merged.append(t)
            return t

        for t in local_tracks:          # 本机曲目优先全保留
            _add(t)

        fixed = missing = 0
        for rt in remote_tracks:
            p = rt.get("path") or ""
            if p and os.path.isfile(p):
                _add(rt)
                continue
            hit = None
            nm = self._norm_text(os.path.splitext(os.path.basename(rt.get("name") or p))[0])
            if nm:
                hit = by_name.get(nm)
            if hit is None:
                hit = by_key.get(self._track_key(rt))
            if hit is not None:
                # 时长差太大视为不同版本（同名不同曲），不硬配
                try:
                    a = float(rt.get("duration") or 0)
                    b = float(hit.get("duration") or 0)
                    if a > 0 and b > 0 and abs(a - b) > 3:
                        hit = None
                except Exception:
                    pass
            if hit is not None:
                hp = hit.get("path") or ""
                exist = merged_map.get(hp)
                if exist is not None:
                    # 本机已有这首歌：用云端的干净标题/歌手覆盖，不重复添加
                    for k2 in ("title", "artist", "album"):
                        if rt.get(k2):
                            exist[k2] = rt[k2]
                    if rt.get("hasCover"):
                        exist["hasCover"] = True
                    exist.pop("offline", None)
                else:
                    nt = dict(hit)
                    for k2 in ("title", "artist", "album"):
                        if rt.get(k2):
                            nt[k2] = rt[k2]
                    nt["path"] = hp
                    nt["name"] = hit.get("name") or nt.get("name") or ""
                    nt.pop("offline", None)
                    _add(nt)
                fixed += 1
            else:
                u = dict(rt)
                u["offline"] = True
                key = self._track_key(rt) or p
                if key:
                    dup = False
                    for x in merged:
                        if (x.get("path") or "") == key or self._track_key(x) == key:
                            dup = True
                            break
                    if dup:
                        continue
                merged.append(u)
                missing += 1

        _save_json(LIB_PATH, {"folder": folder, "tracks": merged,
                              "ts": int(data.get("updatedAt") or time.time())})
        _save_json(PLAYLISTS_PATH, {"playlists": data.get("playlists") or []})
        try:
            self._fix_playlists(merged)
        except Exception:
            pass
        if folder and os.path.isdir(folder) and cfg.get("folder") != folder:
            cfg["folder"] = folder
            self.save_config(cfg)
        after_paths = {t.get("path") for t in merged if t.get("path")}
        added = len(after_paths - before_paths)
        removed = len(before_paths - after_paths)
        return {"fixed": fixed, "offline": missing, "total": len(merged),
                "added": added, "removed": removed}

    def _fix_playlists(self, tracks=None):
        """歌单跨设备匹配：本机有的歌直接对应本地文件；本机没有的保留条目并标 offline。

        歌单条目兼容两种格式：旧格式=纯路径字符串，新格式=对象
        {path,name,title,artist,duration,offline}。
        """
        if tracks is None:
            tracks = self.cached_library().get("tracks") or []
        by_name, by_key = {}, {}
        for t in tracks:
            p = t.get("path") or ""
            nm = self._norm_text(os.path.splitext(os.path.basename(p or t.get("name") or ""))[0])
            if nm and nm not in by_name:
                by_name[nm] = t
            k = self._track_key(t)
            if k and k not in by_key:
                by_key[k] = t

        d = _load_json(PLAYLISTS_PATH, {"playlists": []})
        changed = False
        for pl in d.get("playlists") or []:
            items = pl.get("items") or []
            new_items = []
            for it in items:
                if isinstance(it, dict):
                    o = dict(it)
                else:
                    s = str(it or "")
                    o = {"path": s, "name": os.path.basename(s)}
                p = o.get("path") or ""
                if p and os.path.isfile(p):
                    if o.pop("offline", None):
                        changed = True
                    new_items.append(o)
                    continue
                hit = None
                nm = self._norm_text(os.path.splitext(os.path.basename(p or o.get("name") or ""))[0])
                if nm:
                    hit = by_name.get(nm)
                if hit is None:
                    hit = by_key.get(self._track_key(o))
                if hit is None and o.get("title"):
                    nt = self._norm_text(o.get("title"))
                    for t in tracks:
                        if self._norm_text(t.get("title") or "") == nt:
                            hit = t
                            break
                if hit is not None and (hit.get("path") or "") and os.path.isfile(hit.get("path") or ""):
                    o["path"] = hit.get("path") or p
                    o["name"] = hit.get("name") or o.get("name") or ""
                    for k2 in ("title", "artist", "duration", "album"):
                        if hit.get(k2) and not o.get(k2):
                            o[k2] = hit[k2]
                    o.pop("offline", None)
                    changed = True
                elif hit is not None:
                    # 命中的是曲库里的「未下载」条目：补全标题信息，仍标未下载
                    o["path"] = hit.get("path") or p
                    for k2 in ("title", "artist", "duration"):
                        if hit.get(k2):
                            o[k2] = hit[k2]
                    if not o.get("offline"):
                        changed = True
                    o["offline"] = True
                else:
                    if not o.get("offline"):
                        changed = True
                    o["offline"] = True
                new_items.append(o)
            pl["items"] = new_items
        if changed:
            _save_json(PLAYLISTS_PATH, d)
        return d

    def _sync_snapshot_path(self):
        return os.path.join(DATA_DIR, "sync_snapshot.json")

    def _sync_snapshot_load(self):
        d = _load_json(self._sync_snapshot_path(), {})
        arr = d.get("paths") if isinstance(d, dict) else None
        return set(arr) if isinstance(arr, list) else set()

    def _sync_snapshot_save(self):
        try:
            lib = _load_json(LIB_PATH, {"tracks": []})
            paths = sorted(set((t.get("path") or "")
                               for t in (lib.get("tracks") or []) if t.get("path")))
            _save_json(self._sync_snapshot_path(), {"paths": paths, "at": int(time.time())})
        except Exception:
            pass

    def _sync_backup_local(self):
        try:
            if os.path.exists(LIB_PATH):
                # 注意：这里必须「复制」而不是 os.replace「移走」。
                # 紧跟其后的 _apply_payload() 要读 LIB_PATH 做合并，移走的话会读到空曲库，
                # 导致「合并」退化成「用云端覆盖本机曲库」，本机独有的歌从曲库消失。
                import shutil
                shutil.copy2(LIB_PATH, LIB_PATH + ".before_sync")
        except Exception:
            pass

    def _hub_push_social(self, cli):
        """把昵称头像、听歌时长推到 Hub，供排行榜读取"""
        try:
            prof = self.profile_get()
            pl = {"id": prof["id"], "name": prof["name"], "avatar": prof["avatar"],
                  "updatedAt": int(time.time())}
            raw = json.dumps(pl, ensure_ascii=False).encode("utf-8")
            _, sha = cli.get_file(self._remote_path("profile.json"))
            cli.put_file(raw, sha, "更新资料", path=self._remote_path("profile.json"))
        except Exception:
            pass
        try:
            self._stats_merge_remote(cli)
        except Exception:
            pass

    def hub_flush_stats(self):
        """把本机累计的听歌时长推到 Hub（按设备累加，排行榜用）"""
        try:
            cli = self._sync_client()
            if not cli:
                return {"ok": False, "msg": "未登录"}
            uid = self._user_id()
            if not uid:
                return {"ok": False, "msg": "未登录"}
            total = self._stats_merge_remote(cli)
            if total is None:
                return {"ok": False, "msg": "读取云端失败，已跳过本次上报"}
            return {"ok": True, "total": total}
        except Exception as e:
            return {"ok": False, "msg": str(e)[:100]}

    def hub_touch_login(self):
        """每次打开 App 更新账号表里的最近登录时间/设备（管理员界面直接可见）"""
        try:
            cli = self._sync_client()
            uid = self._user_id()
            if not cli or not uid:
                return {"ok": False, "msg": "未登录"}
            info = {}
            try:
                info = self.device_info() or {}
            except Exception:
                pass
            plat = info.get("platform", "desktop")
            dev = info.get("device", "")
            raw, sha = cli.get_file("accounts.json")
            if not raw:
                return {"ok": False, "msg": "账号表不存在"}
            d = json.loads(raw.decode("utf-8", "replace"))
            hit = None
            for a in d.get("accounts") or []:
                if str(a.get("id")) == str(uid):
                    hit = a
                    break
            if hit is None:
                return {"ok": False, "msg": "账号不在白名单"}
            hit["lastLoginAt"] = int(time.time())
            hit["lastDevice"] = dev
            hit["lastPlatform"] = plat
            d["updatedAt"] = int(time.time())
            cli.put_file(json.dumps(d, ensure_ascii=False).encode("utf-8"), sha,
                         "更新最近登录时间", path="accounts.json")
            try:
                log = {"id": uid, "name": (self.profile_get() or {}).get("name", ""),
                       "platform": plat, "device": dev,
                       "lastLoginAt": int(time.time()), "loginCount": 0}
                lp = "users/%s/login.json" % uid
                _, lsha = cli.get_file(lp)
                cli.put_file(json.dumps(log, ensure_ascii=False).encode("utf-8"), lsha,
                             "更新登录时间", path=lp)
            except Exception:
                pass
            return {"ok": True}
        except Exception as e:
            return {"ok": False, "msg": str(e)[:100]}

    def _default_client(self):
        """开源版没有内置 Hub：必须用户自己配置仓库，否则返回 None。"""
        if not DEFAULT_INVITE:
            return None
        try:
            d = invite_decode(DEFAULT_INVITE)
        except Exception:
            return None
        return SyncClient(d.get("p"), d.get("r"), d.get("t"))

    def login_with_id(self, account_id, password=""):
        """登录门禁：校验 8 位账号ID 是否在 Hub 白名单里"""
        aid = "".join(ch for ch in str(account_id or "") if ch.isdigit())
        if len(aid) != 8:
            return {"ok": False, "msg": "请输入 8 位数字账号ID"}
        conf = self._sync_conf()
        cli = self._sync_client() or self._default_client()
        if cli is None:
            return {"ok": False, "msg": "客户端未配置 Hub"}
        try:
            raw, acc_sha = cli.get_file("accounts.json")
            data = json.loads(raw.decode("utf-8", "replace")) if raw else {"accounts": []}
        except Exception as e:
            return {"ok": False, "msg": "连接服务器失败，检查网络：%s" % e}
        hit = None
        for a in data.get("accounts") or []:
            if str(a.get("id")) == aid:
                hit = a
                break
        is_admin_aid = aid in ADMIN_IDS
        new_pending = False
        if hit is None:
            # 新账号第一次登录：自动生成申请记录（管理员账号直接授权，避免锁死自己）
            hit = {"id": aid, "name": "", "createdAt": int(time.time()),
                   "enabled": is_admin_aid, "admin": is_admin_aid,
                   "devices": [], "note": ""}
            if not is_admin_aid:
                hit["pending"] = True
            data.setdefault("accounts", []).append(hit)
            data["updatedAt"] = int(time.time())
            new_pending = not is_admin_aid
        if not hit.get("enabled", True):
            # 待管理员批准：把申请写进云端后拒绝本次登录
            if new_pending:
                try:
                    cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), acc_sha,
                                 "新账号登录申请", path="accounts.json")
                except Exception:
                    pass
            return {"ok": False, "needApprove": True, "pendingAccount": True,
                    "msg": "新账号已提交登录申请，等待管理员批准"}
        # ---- 新设备登录检查（管理员账号跳过；账号第一次登录自动授权本机）----
        dev_id, dev_name = self._device_id()
        devices = hit.get("devices") if isinstance(hit.get("devices"), list) else []
        approved = any(isinstance(d, dict) and str(d.get("id")) == dev_id for d in devices)
        if is_admin_aid and not approved:
            # 管理员账号在新设备登录：必须输入管理员密码（防别人知道账号ID直接登录）
            pwd_hash = str(hit.get("loginPasswordHash") or "")
            ok_pw = bool(pwd_hash) and hashlib.sha256(
                ("luvsic:" + str(password or "")).encode("utf-8")).hexdigest() == pwd_hash
            if not ok_pw:
                return {"ok": False, "needPassword": True,
                        "msg": "管理员密码错误" if password else "管理员账号在新设备登录需要密码"}
            devices.append({"id": dev_id, "name": dev_name,
                            "at": int(time.time()), "approved": True})
            hit["devices"] = devices
            try:
                data["updatedAt"] = int(time.time())
                cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), acc_sha,
                             "管理员新设备登录（密码验证）", path="accounts.json")
            except Exception:
                pass
            approved = True
        if not approved:
            if not devices:
                hit["devices"] = [{"id": dev_id, "name": dev_name,
                                   "at": int(time.time()), "approved": True}]
                try:
                    data["updatedAt"] = int(time.time())
                    cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), acc_sha,
                                 "首次登录授权本机", path="accounts.json")
                except Exception:
                    pass
            else:
                pend = hit.get("pendingDevices") if isinstance(hit.get("pendingDevices"), list) else []
                if not any(isinstance(x, dict) and str(x.get("id")) == dev_id for x in pend):
                    pend.append({"id": dev_id, "name": dev_name, "at": int(time.time())})
                    hit["pendingDevices"] = pend
                    try:
                        data["updatedAt"] = int(time.time())
                        cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), acc_sha,
                                     "新设备登录申请", path="accounts.json")
                    except Exception:
                        pass
                return {"ok": False, "needApprove": True, "device": dev_name,
                        "msg": "这是新设备，登录需要管理员批准。请把设备名「%s」发给管理员" % dev_name}
        # 账号ID 同时作为跨设备统一的用户ID
        user = self._user_conf()
        old_id = str(user.get("id") or "")
        user["id"] = aid
        # 换账号时不继承上一个账号的昵称；默认昵称 = 账号ID
        nicks = self._nicks_load()
        # 本机缓存过这个账号的昵称 → 直接用（换号再换回也不会丢）
        if old_id != aid:
            user["name"] = str(nicks.get(aid) or "")
        cur_name = str(user.get("name") or "").strip()
        # 用户设置过的昵称优先，不被白名单覆盖（升级/重登都不改昵称）
        if not cur_name and hit.get("name"):
            user["name"] = hit["name"]
        if not str(user.get("name") or "").strip():
            # 看看这个账号在云端有没有设置过昵称
            try:
                raw_p, _ = cli.get_file("users/%s/profile.json" % aid)
                if raw_p:
                    pj = json.loads(raw_p.decode("utf-8", "replace"))
                    if str(pj.get("name") or "").strip():
                        user["name"] = pj["name"]
            except Exception:
                pass
        if not str(user.get("name") or "").strip():
            user["name"] = aid
        _save_json(USER_PATH, user)
        # 记录到本机昵称缓存
        try:
            nicks[aid] = str(user.get("name") or "")
            self._nicks_save(nicks)
        except Exception:
            pass
        _save_json(SYNC_PATH, {"provider": cli.provider, "repo": cli.repo, "token": cli.token,
                               "name": "Luvsic Hub", "at": int(time.time()),
                               "userId": aid, "baseRemoteTs": 0, "baseLocalTs": 0})
        # 记录最近一次登录（供管理员查看谁在使用）
        try:
            log = {"id": aid, "name": (hit.get("name") or user.get("name") or ""),
                   "platform": "desktop", "device": socket.gethostname(),
                   "lastLoginAt": int(time.time()), "loginCount": int(hit.get("loginCount") or 0) + 1}
            path = "users/%s/login.json" % aid
            _, lsha = cli.get_file(path)
            cli.put_file(json.dumps(log, ensure_ascii=False).encode("utf-8"), lsha,
                         "记录登录", path=path)
            hit["loginCount"] = log["loginCount"]
            try:
                _dev = {}
                try:
                    _dev = self.device_info() or {}
                except Exception:
                    pass
                hit["lastDevice"] = _dev.get("device", "")
                hit["lastPlatform"] = _dev.get("platform", "")
            except Exception:
                pass
            hit["lastLoginAt"] = log["lastLoginAt"]
            hit["lastDevice"] = log["device"]
            b = json.dumps(data, ensure_ascii=False).encode("utf-8")
            _, asha = cli.get_file("accounts.json")
            if asha:
                cli.put_file(b, asha, "更新最近登录", path="accounts.json")
        except Exception:
            pass
        r = self.sync_now()
        out = {"ok": True, "msg": "登录成功，账号ID：" + aid, "accountId": aid,
               "sync": r.get("action") or "", "profile": self.profile_get()}
        return out

    def sync_config(self, repo, token, branch="main"):
        """用户直接填自己的 GitHub 仓库来启用云同步（开源版推荐方式）。
        repo: "owner/repo"；token: GitHub PAT（repo 权限）。
        不填就不用云端，本地功能不受影响。"""
        repo = (repo or "").strip().strip("/")
        token = (token or "").strip()
        if not repo or "/" not in repo or repo.count("/") != 1:
            return {"ok": False, "msg": "仓库格式应为 owner/repo，例如 yourname/luvsic-data"}
        if not token:
            return {"ok": False, "msg": "请填入 GitHub Personal Access Token"}
        cli = SyncClient("github", repo, token)
        # 连通性验证：尝试读一次 users 目录（不存在也是正常，只验证鉴权）
        try:
            cli.get_dir("users")
        except Exception as e:
            msg = str(e)
            if "401" in msg or "403" in msg:
                return {"ok": False, "msg": "令牌无效或没有 repo 权限：%s" % msg}
            if "404" in msg:
                return {"ok": False, "msg": "仓库不存在或令牌无权访问：%s" % msg}
            return {"ok": False, "msg": "连接失败：%s" % msg}
        _save_json(SYNC_PATH, {"provider": "github", "repo": repo, "token": token,
                               "name": repo, "at": int(time.time()),
                               "baseRemoteTs": 0, "baseLocalTs": 0})
        r = self.sync_now()
        r["msg"] = "已连接 " + repo + "。" + (r.get("msg") or "")
        return r

    def sync_test(self, repo, token):
        """只测连通性，不保存配置"""
        repo = (repo or "").strip().strip("/")
        token = (token or "").strip()
        if not repo or "/" not in repo or not token:
            return {"ok": False, "msg": "仓库和令牌都要填"}
        cli = SyncClient("github", repo, token)
        try:
            cli.get_dir("users")
            return {"ok": True, "msg": "连接成功，可以点「连接」了"}
        except Exception as e:
            return {"ok": False, "msg": "连接失败：%s" % e}

    def sync_login(self, code):
        try:
            d = invite_decode(code)
        except Exception as e:
            return {"ok": False, "msg": "邀请码无效：%s" % e}
        cli = SyncClient(d.get("p"), d.get("r"), d.get("t"))
        try:
            cli.get_dir("users")
        except Exception as e:
            return {"ok": False, "msg": "连接仓库失败：%s" % e}
        _save_json(SYNC_PATH, {"provider": d.get("p"), "repo": d.get("r"), "token": d.get("t"),
                               "name": d.get("n") or d.get("r"), "at": int(time.time()),
                               "baseRemoteTs": 0, "baseLocalTs": 0})
        r = self.sync_now()
        r["msg"] = "登录成功，" + (r.get("msg") or "")
        return r

    def sync_status(self):
        c = self._sync_conf()
        st = getattr(self, "_sync_state", {})
        logged = bool(c.get("repo") and c.get("token"))
        uid = self._user_id()
        return {"ok": True, "loggedIn": logged, "needsLogin": not logged,
                "provider": c.get("provider") or "", "repo": c.get("repo") or "",
                "name": c.get("name") or "", "at": c.get("at") or 0,
                "userId": uid, "isAdmin": uid in ADMIN_IDS, "last": st}

    def sync_logout(self):
        try:
            if os.path.exists(SYNC_PATH):
                os.remove(SYNC_PATH)
        except Exception:
            pass
        self._sync_state = {}
        return {"ok": True, "msg": "已退出同步"}

    def sync_now(self):
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        conf = self._sync_conf()
        data_path = self._remote_path("data.json")
        state = {"at": int(time.time()), "action": "", "msg": ""}
        try:
            remote_bytes, sha = cli.get_file(data_path)
            lts = self._sync_stamp()
            if remote_bytes is None:
                payload = self._sync_payload()
                cli.put_file(json.dumps(payload, ensure_ascii=False).encode("utf-8"), None,
                             "初始化 Luvsic 同步数据", path=data_path)
                conf.update({"baseRemoteTs": payload["updatedAt"], "baseLocalTs": self._sync_stamp()})
                _save_json(SYNC_PATH, conf)
                action = "push"
                msg = "已上传本机曲库/歌单（%d 首）" % len(
                    (payload.get("library") or {}).get("tracks") or [])
            else:
                try:
                    remote = json.loads(remote_bytes.decode("utf-8", "replace"))
                except Exception:
                    remote = {}
                rts = int(remote.get("updatedAt") or 0)
                base_r = int(conf.get("baseRemoteTs") or 0)
                base_l = int(conf.get("baseLocalTs") or 0)
                local_changed = lts > base_l
                remote_changed = rts > base_r
                if remote_changed and not local_changed:
                    self._sync_backup_local()
                    mr = self._apply_payload(remote)
                    conf.update({"baseRemoteTs": rts, "baseLocalTs": self._sync_stamp()})
                    _save_json(SYNC_PATH, conf)
                    action = "pull"
                    # 合并后把结果推回云端：云端保持两端并集，不会丢掉本机独有信息
                    try:
                        payload2 = self._sync_payload()
                        cli.put_file(json.dumps(payload2, ensure_ascii=False).encode("utf-8"), sha,
                                     "Luvsic 同步（合并后）", path=data_path)
                        conf.update({"baseRemoteTs": payload2["updatedAt"],
                                     "baseLocalTs": self._sync_stamp()})
                        _save_json(SYNC_PATH, conf)
                    except Exception:
                        pass
                    msg = "已合并云端：新增 %d 首" % mr.get("added", 0)
                    if mr.get("removed"):
                        msg += "、移除 %d 首" % mr["removed"]
                    if mr["fixed"]:
                        msg += "、%d 首换回本机路径" % mr["fixed"]
                    if mr["offline"]:
                        msg += "、%d 首未下载" % mr["offline"]
                    msg += "（本机共 %d 首）" % mr["total"]
                elif local_changed and not remote_changed:
                    # push 前先合并云端：避免用本机旧数据覆盖另一端的元数据（歌手/标题等）
                    try:
                        self._apply_payload(remote)
                    except Exception:
                        pass
                    payload = self._sync_payload()
                    cli.put_file(json.dumps(payload, ensure_ascii=False).encode("utf-8"), sha,
                                 "Luvsic 同步", path=data_path)
                    conf.update({"baseRemoteTs": payload["updatedAt"], "baseLocalTs": self._sync_stamp()})
                    _save_json(SYNC_PATH, conf)
                    snap = self._sync_snapshot_load()
                    cur = set(t.get("path") for t in (payload.get("library") or {}).get("tracks") or []
                              if t.get("path"))
                    d_up = len(cur - snap) if snap else 0
                    d_del = len(snap - cur) if snap else 0
                    action = "push"
                    if snap and (d_up or d_del):
                        msg = "已上传本机改动：+%d 首" % d_up
                        if d_del:
                            msg += "、-%d 首" % d_del
                    else:
                        msg = "已上传本机改动"
                elif local_changed and remote_changed:
                    self._sync_backup_local()
                    mr = self._apply_payload(remote)
                    conf.update({"baseRemoteTs": rts, "baseLocalTs": self._sync_stamp()})
                    _save_json(SYNC_PATH, conf)
                    action = "conflict"
                    # 合并后把结果推回云端：两端并集统一，避免下次一端 pull 时又被覆盖
                    try:
                        payload2 = self._sync_payload()
                        cli.put_file(json.dumps(payload2, ensure_ascii=False).encode("utf-8"), sha,
                                     "Luvsic 同步（合并后）", path=data_path)
                        conf.update({"baseRemoteTs": payload2["updatedAt"],
                                     "baseLocalTs": self._sync_stamp()})
                        _save_json(SYNC_PATH, conf)
                    except Exception:
                        pass
                    msg = "两端都有改动，已合并：新增 %d 首" % mr.get("added", 0)
                    if mr.get("removed"):
                        msg += "、移除 %d 首" % mr["removed"]
                    if mr["fixed"]:
                        msg += "、%d 首换回本机路径" % mr["fixed"]
                    if mr["offline"]:
                        msg += "、%d 首未下载" % mr["offline"]
                    msg += "（本机共 %d 首，旧版已备份）" % mr["total"]
                else:
                    action, msg = "none", "已是最新"
            self._sync_snapshot_save()
            self._hub_push_social(cli)
            state.update({"action": action, "msg": msg, "ok": True})
        except Exception as e:
            state.update({"action": "error", "msg": "同步失败：%s" % e, "ok": False})
        self._sync_state = state
        return {"ok": state.get("ok", False), "msg": state.get("msg"), "action": state.get("action")}

    def hub_logins(self):
        """管理员：列出所有账号的最近登录情况"""
        cli = self._sync_client()
        if cli is None:
            cli = self._default_client()
        if cli is None:
            return {"ok": False, "msg": "未配置 Hub", "list": []}
        rows = []
        try:
            ids = cli.get_dir("users")
            for uid in [x for x in ids if re.match(r"^\d{8}$", str(x))]:
                row = {"id": uid, "name": "", "platform": "", "device": "", "lastLoginAt": 0}
                try:
                    raw, _ = cli.get_file("users/%s/login.json" % uid)
                    if raw:
                        row.update(json.loads(raw.decode("utf-8", "replace")))
                except Exception:
                    pass
                if not row.get("lastLoginAt"):
                    try:
                        raw, _ = cli.get_file("users/%s/profile.json" % uid)
                        if raw:
                            p = json.loads(raw.decode("utf-8", "replace"))
                            row["name"] = row["name"] or p.get("name") or ""
                            row["lastLoginAt"] = int(p.get("updatedAt") or 0)
                    except Exception:
                        pass
                rows.append(row)
        except Exception as e:
            return {"ok": False, "msg": "读取失败：%s" % e, "list": []}
        rows.sort(key=lambda r: int(r.get("lastLoginAt") or 0), reverse=True)
        return {"ok": True, "list": rows}

    def _hub_admin_client(self):
        if self._user_id() not in ADMIN_IDS:
            return None, {"ok": False, "msg": "只有管理员可以操作", "list": []}
        cli = self._sync_client() or self._default_client()
        if cli is None:
            return None, {"ok": False, "msg": "未配置 Hub", "list": []}
        return cli, None

    def hub_accounts(self):
        """管理员：账号管理列表（只读一次账号表；最近登录时间/设备已在表里）"""
        cli, err = self._hub_admin_client()
        if err:
            return err
        try:
            raw, _ = cli.get_file("accounts.json")
            d = json.loads(raw.decode("utf-8", "replace")) if raw else {"accounts": []}
        except Exception as e:
            return {"ok": False, "msg": "读取失败：%s" % e, "list": []}
        rows = []
        for a in d.get("accounts") or []:
            aid = str(a.get("id") or "")
            if not re.match(r"^\d{8}$", aid):
                continue
            devs = [x.get("name") for x in (a.get("devices") or [])
                    if isinstance(x, dict) and x.get("name")]
            rows.append({
                "id": aid,
                "name": a.get("name") or "",
                "enabled": a.get("enabled", True),
                "admin": bool(a.get("admin")) or aid in ADMIN_IDS,
                "note": a.get("note") or "",
                "platform": a.get("lastPlatform") or "",
                "device": a.get("lastDevice") or (devs[-1] if devs else ""),
                "pending": bool(a.get("pending")),
                "lastLoginAt": int(a.get("lastLoginAt") or 0) or max(
                    [int(x.get("at") or 0) for x in (a.get("devices") or [])
                     if isinstance(x, dict)] or [0]),
                "loginCount": int(a.get("loginCount") or 0),
            })
        rows.sort(key=lambda r: (-int(r["lastLoginAt"] or 0), r["id"]))
        return {"ok": True, "list": rows, "total": len(rows),
                "adminIds": sorted(ADMIN_IDS)}

    def hub_set_account(self, account_id, enabled=True):
        """管理员：允许 / 禁止某个账号ID登录"""
        aid = "".join(ch for ch in str(account_id or "") if ch.isdigit())
        if len(aid) != 8:
            return {"ok": False, "msg": "账号ID 格式不对"}
        cli, err = self._hub_admin_client()
        if err:
            return {"ok": False, "msg": err.get("msg")}
        if aid in ADMIN_IDS and not enabled:
            return {"ok": False, "msg": "不能禁止管理员自己登录"}
        try:
            raw, sha = cli.get_file("accounts.json")
            d = json.loads(raw.decode("utf-8", "replace")) if raw else {"accounts": []}
            hit = None
            for a in d.get("accounts") or []:
                if str(a.get("id")) == aid:
                    hit = a
                    break
            if hit is None:
                return {"ok": False, "msg": "白名单里找不到这个账号ID"}
            hit["enabled"] = bool(enabled)
            d["updatedAt"] = int(time.time())
            cli.put_file(json.dumps(d, ensure_ascii=False).encode("utf-8"), sha,
                         "更新账号权限", path="accounts.json")
            return {"ok": True, "msg": ("已允许 %s 登录" if enabled else "已禁止 %s 登录") % aid,
                    "id": aid, "enabled": bool(enabled)}
        except Exception as e:
            return {"ok": False, "msg": "操作失败：%s" % e}

    def hub_set_note(self, account_id, note=""):
        """管理员：给账号写备注（只存在 Hub 白名单里，普通用户看不到）"""
        aid = "".join(ch for ch in str(account_id or "") if ch.isdigit())
        if len(aid) != 8:
            return {"ok": False, "msg": "账号ID 格式不对"}
        cli, err = self._hub_admin_client()
        if err:
            return {"ok": False, "msg": err.get("msg")}
        try:
            raw, sha = cli.get_file("accounts.json")
            d = json.loads(raw.decode("utf-8", "replace")) if raw else {"accounts": []}
            hit = None
            for a in d.get("accounts") or []:
                if str(a.get("id")) == aid:
                    hit = a
                    break
            if hit is None:
                return {"ok": False, "msg": "白名单里找不到这个账号ID"}
            note = str(note or "").strip()[:40]
            if note:
                hit["note"] = note
            else:
                hit.pop("note", None)
            d["updatedAt"] = int(time.time())
            cli.put_file(json.dumps(d, ensure_ascii=False).encode("utf-8"), sha,
                         "更新账号备注", path="accounts.json")
            return {"ok": True, "msg": ("备注已保存" if note else "备注已清除"), "id": aid, "note": note}
        except Exception as e:
            return {"ok": False, "msg": "操作失败：%s" % e}

    # ---- 好友（8 位好友码互加，好友列表存 users/<id>/friends.json）----
    # ---- 设备识别（新设备登录需要管理员批准）----
    def _device_name(self):
        try:
            import socket
            return socket.gethostname() or "Windows"
        except Exception:
            return "Windows"

    def _device_id(self):
        pth = os.path.join(DATA_DIR, "device.json")
        d = _load_json(pth, {})
        if not isinstance(d, dict) or not re.match(r"^[A-Za-z0-9_-]{8,40}$", str(d.get("id") or "")):
            import uuid
            d = {"id": "d" + uuid.uuid4().hex[:16], "name": self._device_name()}
            _save_json(pth, d)
        return str(d.get("id")), str(d.get("name") or self._device_name())

    def device_info(self):
        dev_id, dev_name = self._device_id()
        return {"ok": True, "deviceId": dev_id, "device": dev_name}

    def _nicks_path(self):
        return os.path.join(os.path.dirname(USER_PATH), "nicknames.json")

    def _nicks_load(self):
        try:
            with open(self._nicks_path(), "r", encoding="utf-8") as f:
                d = json.load(f)
                return d if isinstance(d, dict) else {}
        except Exception:
            return {}

    def _nicks_save(self, nicks):
        try:
            _save_json(self._nicks_path(), nicks)
        except Exception:
            pass

    def _friends_path(self):
        return self._remote_path("friends.json")

    # ---- 好友申请：inbox（我收到的）/ sent（我发出的）----
    @staticmethod
    def _read_remote_json(cli, path):
        try:
            raw, sha = cli.get_file(path)
            if raw:
                return json.loads(raw.decode("utf-8", "replace")), sha
        except Exception:
            pass
        return None, None

    @staticmethod
    def _put_remote_json(cli, path, obj, sha, msg="Luvsic"):
        cli.put_file(json.dumps(obj, ensure_ascii=False).encode("utf-8"), sha, msg, path=path)

    def _ensure_user_dir(self, cli, uid):
        """保证 users/<uid>/ 目录存在，供远端写入时定位路径"""
        try:
            if cli.get_file("users/%s/.keep" % uid)[0] is not None:
                return
        except Exception:
            pass
        try:
            cli.put_file(b"1", None, "初始化用户目录", path="users/%s/.keep" % uid)
        except Exception:
            pass

    # ---- 好友码（与登录账号ID分离：加好友只暴露好友码，不知道账号ID就登不进别人账号）----
    def _load_accounts(self, cli):
        try:
            raw, sha = cli.get_file("accounts.json")
            if raw:
                return json.loads(raw.decode("utf-8", "replace")), sha
        except Exception:
            pass
        return {"accounts": []}, None

    def _ensure_friend_codes(self, cli):
        """保证每个账号都有 8 位好友码（随机、唯一、不等于账号ID），返回 (按好友码, 按账号ID)"""
        import random as _random
        data, sha = self._load_accounts(cli)
        accs = data.get("accounts") or []
        used = set()
        for a in accs:
            fc = str(a.get("friendCode") or "").strip()
            if re.match(r"^\d{8}$", fc):
                used.add(fc)
        changed = False
        for a in accs:
            fc = str(a.get("friendCode") or "").strip()
            if re.match(r"^\d{8}$", fc):
                continue
            cand = ""
            for _ in range(300):
                c = str(_random.randint(10000000, 99999999))
                if c in used or c == str(a.get("id")):
                    continue
                cand = c
                break
            if not cand:
                continue
            a["friendCode"] = cand
            used.add(cand)
            changed = True
        if changed:
            try:
                data["updatedAt"] = int(time.time())
                cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), sha,
                             "生成好友码", path="accounts.json")
            except Exception:
                pass
        by_code = {}
        by_id = {}
        for a in accs:
            fc = str(a.get("friendCode") or "").strip()
            if re.match(r"^\d{8}$", fc):
                by_code[fc] = a
            by_id[str(a.get("id"))] = a
        return by_code, by_id

    def my_friend_code(self):
        cli = self._sync_client()
        me = self._user_id()
        if cli is None:
            return {"ok": False, "msg": "还没有登录", "code": ""}
        try:
            by_code, by_id = self._ensure_friend_codes(cli)
        except Exception as e:
            return {"ok": False, "msg": "读取好友码失败：%s" % e, "code": ""}
        a = by_id.get(str(me)) or {}
        return {"ok": True, "code": str(a.get("friendCode") or ""), "id": str(me),
                "msg": "好友码只用于加好友，不能用来登录"}

    def _friend_meta(self, cli, ids):
        """昵称/最近登录：昵称优先用对方在 App 里设置的（users/<id>/profile.json），其次白名单"""
        meta = {}
        try:
            raw, _ = cli.get_file("accounts.json")
            if raw:
                for a in (json.loads(raw.decode("utf-8", "replace")).get("accounts") or []):
                    meta[str(a.get("id"))] = a
        except Exception:
            pass
        out = {}
        for fid in ids:
            a = meta.get(fid) or {}
            name = ""
            try:
                raw_p, _ = cli.get_file("users/%s/profile.json" % fid)
                if raw_p:
                    name = str((json.loads(raw_p.decode("utf-8", "replace")).get("name") or "")).strip()
            except Exception:
                pass
            if not name:
                name = str(a.get("name") or "").strip()
            # 最近登录时间：以对方目录里的 login.json 为准
            login_at = int(a.get("lastLoginAt") or 0)
            try:
                raw_l, _ = cli.get_file("users/%s/login.json" % fid)
                if raw_l:
                    lj = json.loads(raw_l.decode("utf-8", "replace"))
                    login_at = max(login_at, int(lj.get("lastLoginAt") or 0))
            except Exception:
                pass
            out[fid] = {"name": name,
                        "lastLoginAt": login_at,
                        "enabled": a.get("enabled", True)}
        return out

    # ---- 歌单分享（只发歌名/歌手/时长，不含路径）----
    def share_playlist(self, playlist_id="", friend_id=""):
        """把歌单分享给好友（写入对方的 share_inbox.json）。"""
        try:
            import uuid
            cli = self._sync_client()
            if cli is None:
                return {"ok": False, "msg": "还没有登录"}
            me = str(self._user_id())
            fid = "".join(ch for ch in str(friend_id or "") if ch.isdigit())
            if not re.match(r"^\d{8}$", fid):
                return {"ok": False, "msg": "好友账号格式不对"}
            if fid == me:
                return {"ok": False, "msg": "不能分享给自己"}
            friends = []
            try:
                raw_f, _ = cli.get_file(self._friends_path())
                if raw_f:
                    for f in (json.loads(raw_f.decode("utf-8", "replace")).get("friends") or []):
                        if isinstance(f, dict) and re.match(r"^\d{8}$", str(f.get("id") or "")):
                            friends.append(str(f.get("id")))
            except Exception:
                pass
            if fid not in friends:
                return {"ok": False, "msg": "对方不是你的好友"}
            pl = None
            for p in self.get_playlists().get("playlists") or []:
                if str(p.get("id")) == str(playlist_id):
                    pl = p
                    break
            if pl is None:
                return {"ok": False, "msg": "歌单不存在"}
            tracks = []
            for it in (pl.get("items") or []):
                o = it if isinstance(it, dict) else {"path": str(it or ""), "name": os.path.basename(str(it or ""))}
                ttl = str(o.get("title") or o.get("name") or "").strip()
                if not ttl:
                    continue
                try:
                    dur = int(float(o.get("duration") or 0))
                except Exception:
                    dur = 0
                tracks.append({"title": ttl, "artist": str(o.get("artist") or "").strip(), "duration": dur})
            if not tracks:
                return {"ok": False, "msg": "这个歌单是空的"}
            prof = self.profile_get() or {}
            share = {"id": "sp" + uuid.uuid4().hex[:10], "name": str(pl.get("name") or "歌单"),
                     "from": me, "fromName": str(prof.get("name") or me),
                     "at": int(time.time()), "tracks": tracks[:300]}
            path = "users/%s/share_inbox.json" % fid
            raw, sha = cli.get_file(path)
            inbox = {"shares": []}
            if raw:
                try:
                    inbox = json.loads(raw.decode("utf-8", "replace")) or {"shares": []}
                except Exception:
                    inbox = {"shares": []}
            shares = [x for x in (inbox.get("shares") or []) if isinstance(x, dict)]
            shares.append(share)
            now = time.time()
            shares = [x for x in shares if int(x.get("at") or 0) > now - 30 * 86400][-50:]
            inbox["shares"] = shares
            cli.put_file(json.dumps(inbox, ensure_ascii=False).encode("utf-8"), sha, "分享歌单", path=path)
            return {"ok": True, "msg": "已分享给 %s（%d 首）" % (share["fromName"], len(share["tracks"]))}
        except Exception as e:
            return {"ok": False, "msg": "分享失败：%s" % e}

    def share_inbox_get(self):
        """读取「收到的分享歌单」列表（30 天内）。"""
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "list": [], "msg": "还没有登录"}
        try:
            raw, _ = cli.get_file("users/%s/share_inbox.json" % self._user_id())
            d = json.loads(raw.decode("utf-8", "replace")) if raw else {"shares": []}
            now = time.time()
            shares = [x for x in (d.get("shares") or [])
                      if isinstance(x, dict) and int(x.get("at") or 0) > now - 30 * 86400]
            out = []
            for x in shares:
                out.append({"id": x.get("id"), "name": x.get("name"), "from": x.get("from"),
                            "fromName": x.get("fromName"), "at": x.get("at"),
                            "count": len(x.get("tracks") or [])})
            return {"ok": True, "list": out}
        except Exception as e:
            return {"ok": False, "list": [], "msg": "读取失败：%s" % e}

    def import_shared_playlist(self, share_id=""):
        """把收到的分享歌单导入成本地歌单：本地有的用本地路径，没有的标未下载。"""
        try:
            import uuid
            cli = self._sync_client()
            if cli is None:
                return {"ok": False, "msg": "还没有登录"}
            path = "users/%s/share_inbox.json" % self._user_id()
            raw, sha = cli.get_file(path)
            d = json.loads(raw.decode("utf-8", "replace")) if raw else {"shares": []}
            shares = [x for x in (d.get("shares") or []) if isinstance(x, dict)]
            target = None
            for x in shares:
                if str(x.get("id")) == str(share_id):
                    target = x
                    break
            if target is None:
                return {"ok": False, "msg": "分享不存在或已过期"}
            # 本地曲库索引：标题+歌手
            by_key = {}
            for t in (self.cached_library().get("tracks") or []):
                k = self._track_key(t)
                if k and k not in by_key:
                    by_key[k] = t
            items = []
            matched = missing = 0
            for st in (target.get("tracks") or []):
                if not isinstance(st, dict):
                    continue
                ttl = str(st.get("title") or "").strip()
                if not ttl:
                    continue
                art = str(st.get("artist") or "").strip()
                try:
                    dur = int(float(st.get("duration") or 0))
                except Exception:
                    dur = 0
                k = self._norm_text(ttl) + "|" + self._norm_text(art)
                hit = by_key.get(k)
                if hit is not None:
                    items.append({"path": hit.get("path") or "",
                                  "name": hit.get("name") or os.path.basename(hit.get("path") or ""),
                                  "title": hit.get("title") or ttl,
                                  "artist": hit.get("artist") or art,
                                  "duration": hit.get("duration") or dur})
                    matched += 1
                else:
                    items.append({"path": "", "name": ttl, "title": ttl, "artist": art,
                                  "duration": dur, "offline": True})
                    missing += 1
            name = "%s 的分享 · %s" % (str(target.get("fromName") or target.get("from") or "好友"),
                                      str(target.get("name") or "歌单"))
            pls = self.get_playlists()
            pls.setdefault("playlists", [])
            new_id = "p" + uuid.uuid4().hex[:8]
            pls["playlists"].append({"id": new_id, "name": name, "items": items})
            self.save_playlists(pls)
            d["shares"] = [x for x in shares if str(x.get("id")) != str(share_id)]
            cli.put_file(json.dumps(d, ensure_ascii=False).encode("utf-8"), sha,
                         "导入分享歌单", path=path)
            return {"ok": True, "playlistId": new_id, "name": name,
                    "matched": matched, "missing": missing,
                    "msg": "已导入「%s」：%d 首本地可播、%d 首未下载" % (name, matched, missing)}
        except Exception as e:
            return {"ok": False, "msg": "导入失败：%s" % e}

    def friends_list(self):
        cli = self._sync_client()
        me = self._user_id()
        if not re.match(r"^\d{8}$", me):
            return {"ok": False, "msg": "账号ID未绑定，请退出登录后重新输入账号ID", "list": [], "myCode": me}
        if cli is None:
            return {"ok": False, "msg": "还没有登录", "list": [], "myCode": me}
        try:
            raw, _ = cli.get_file(self._friends_path())
            data = json.loads(raw.decode("utf-8", "replace")) if raw else {"friends": []}
        except Exception as e:
            return {"ok": False, "msg": "读取好友失败：%s" % e, "list": [], "myCode": me}
        ids = []
        for f in data.get("friends") or []:
            fid = str(f.get("id") or "") if isinstance(f, dict) else str(f)
            if re.match(r"^\d{8}$", fid) and fid not in ids:
                ids.append(fid)
        try:
            by_code, by_id = self._ensure_friend_codes(cli)
        except Exception:
            by_code, by_id = {}, {}
        nicks = {}
        for f in data.get("friends") or []:
            if isinstance(f, dict) and f.get("nick"):
                nicks[str(f.get("id"))] = str(f.get("nick"))
        rows = []
        for fid in ids:
            a = by_id.get(fid) or {}
            rows.append({"id": fid,
                         "code": str(a.get("friendCode") or ""),
                         "name": a.get("name") or "",
                         "nick": nicks.get(fid, ""),
                         "enabled": a.get("enabled", True),
                         "lastLoginAt": int(a.get("lastLoginAt") or 0),
                         "lastDevice": a.get("lastDevice") or ""})
        rows.sort(key=lambda r: -int(r["lastLoginAt"] or 0))
        mine = by_id.get(str(me)) or {}
        return {"ok": True, "list": rows, "myCode": str(mine.get("friendCode") or ""),
                "total": len(rows)}

    def friends_add(self, code):
        """添加好友 = 输入对方的好友码发申请（好友码 ≠ 登录账号ID），对方同意后成为好友"""
        fc = "".join(ch for ch in str(code or "") if ch.isdigit())
        if len(fc) != 8:
            return {"ok": False, "msg": "请输入 8 位好友码"}
        me = self._user_id()
        if not re.match(r"^\d{8}$", me):
            return {"ok": False, "msg": "本机账号未绑定，请先退出登录重新登录"}
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        try:
            by_code, by_id = self._ensure_friend_codes(cli)
        except Exception as e:
            return {"ok": False, "msg": "读取好友码失败：%s" % e}
        hit = by_code.get(fc)
        if hit is None:
            return {"ok": False, "msg": "好友码不存在，让对方确认一下"}
        aid = str(hit.get("id") or "")
        if aid == me:
            return {"ok": False, "msg": "不能添加自己为好友"}
        if not hit.get("enabled", True):
            return {"ok": False, "msg": "对方账号已停用"}
        # 已经是好友？
        myfd, _ = self._read_remote_json(cli, self._friends_path())
        my_friends = [f for f in ((myfd or {}).get("friends") or []) if isinstance(f, dict)]
        if any(str(f.get("id")) == aid for f in my_friends):
            return {"ok": True, "msg": "已经是好友了", "id": aid}
        # 已经发过申请？
        sentd, ssha = self._read_remote_json(cli, "users/%s/sent.json" % me)
        sent = [x for x in ((sentd or {}).get("sent") or []) if isinstance(x, dict)]
        if any(str(x.get("to")) == aid for x in sent):
            return {"ok": True, "msg": "已经发过申请了，等对方同意", "id": aid}
        # 写对方 inbox
        self._ensure_user_dir(cli, aid)
        inbox, isha = self._read_remote_json(cli, "users/%s/inbox.json" % aid)
        reqs = [r for r in ((inbox or {}).get("requests") or [])
                if isinstance(r, dict) and str(r.get("from")) != me]
        reqs.append({"from": me, "name": self._user_conf().get("name") or "",
                     "at": int(time.time())})
        try:
            self._put_remote_json(cli, "users/%s/inbox.json" % aid, {"requests": reqs}, isha,
                                  "好友申请")
        except Exception as e:
            return {"ok": False, "msg": "发送申请失败：%s" % e}
        # 写自己的 sent
        sent = [x for x in sent if str(x.get("to")) != aid]
        sent.append({"to": aid, "at": int(time.time())})
        try:
            self._put_remote_json(cli, "users/%s/sent.json" % me, {"sent": sent}, ssha,
                                  "记录已发申请")
        except Exception:
            pass
        return {"ok": True, "msg": "已发送好友申请，等对方同意", "id": aid,
                "name": hit.get("name") or "", "pending": True}

    # ---- 自动更新 ----
    def app_version(self):
        return {"ok": True, "version": APP_VERSION, "platform": "desktop"}

    def _update_rel(self):
        """从 Hub 的 updates.json 里读桌面版更新信息"""
        cli = self._sync_client()
        if cli is None:
            raise Exception("还没有登录（更新检查需要联网）")
        raw, _ = cli.get_file("updates.json")
        data = json.loads(raw.decode("utf-8", "replace")) if raw else {}
        info = (data.get("desktop") or {}) if isinstance(data, dict) else {}
        return cli, info

    def _raw_url(self, rel):
        c = self._sync_conf()
        repo = str(c.get("repo") or "")
        token = str(c.get("token") or "")
        rel = str(rel or "").strip().lstrip("/")
        if not repo or not token or not rel:
            return ""
        from urllib.parse import quote
        # GitHub 公开仓库直接取 raw，不带 token
        return "https://raw.githubusercontent.com/%s/main/%s" % (repo, quote(rel))

    def check_update(self):
        ver = APP_VERSION
        try:
            cli, info = self._update_rel()
        except Exception as e:
            return {"ok": False, "msg": "读取更新信息失败：%s" % e, "current": ver}
        latest = str(info.get("version") or "").strip()
        if not latest:
            return {"ok": True, "hasUpdate": False, "current": ver, "msg": "已是最新版本"}
        need = _ver_newer(latest, ver)
        out = {"ok": True, "hasUpdate": need, "current": ver, "latest": latest,
               "notes": str(info.get("notes") or ""), "file": str(info.get("file") or "")}
        if need:
            out["url"] = self._raw_url(info.get("file") or "")
        return out

    def _update_progress_path(self):
        return os.path.join(DATA_DIR, "update_progress.json")

    def update_progress(self):
        try:
            with open(self._update_progress_path(), "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {"stage": "idle", "percent": 0}

    def download_update(self, file_rel=""):
        """下载更新：支持分片（parts 列表 + sha256 校验），也兼容旧的单文件 file"""
        try:
            cli, info = self._update_rel()
        except Exception as e:
            return {"ok": False, "msg": "读取更新信息失败：%s" % e}
        parts = info.get("parts")
        sha_expect = str(info.get("sha256") or "").strip()
        if not isinstance(parts, list) or not parts:
            one = str(info.get("file") or "").strip()
            if not one:
                return {"ok": False, "msg": "更新信息里没有安装包地址"}
            parts = [one]
        dest_dir = os.path.join(DATA_DIR, "updates")
        try:
            os.makedirs(dest_dir, exist_ok=True)
        except Exception:
            pass
        first = str(parts[0]).strip().lstrip("/")
        name = re.sub(r"\.part\d+$", "", os.path.basename(first)) or "Luvsic-update.exe"
        dest = os.path.join(dest_dir, name)
        tmp = dest + ".part"
        _save_json(self._update_progress_path(),
                   {"stage": "downloading", "percent": 0, "parts": len(parts)})
        try:
            chunks = []
            total = len(parts)
            for i, rel in enumerate(parts):
                rp = str(rel).strip().lstrip("/")
                raw, _ = cli.get_file(rp)
                if not raw:
                    raise Exception("分片缺失：%s" % rp)
                chunks.append(raw)
                _save_json(self._update_progress_path(),
                           {"stage": "downloading",
                            "percent": int((i + 1) * 90 / total),
                            "part": i + 1, "parts": total})
            data = b"".join(chunks)
            if sha_expect:
                import hashlib
                got = hashlib.sha256(data).hexdigest()
                if got != sha_expect:
                    raise Exception("安装包校验失败（下载不完整）")
            with open(tmp, "wb") as f:
                f.write(data)
            if os.path.isfile(dest):
                os.remove(dest)
            os.replace(tmp, dest)
        except Exception as e:
            try:
                if os.path.isfile(tmp):
                    os.remove(tmp)
            except Exception:
                pass
            _save_json(self._update_progress_path(), {"stage": "error", "percent": 0, "msg": str(e)})
            return {"ok": False, "msg": "下载失败：%s" % e}
        _save_json(self._update_progress_path(), {"stage": "done", "percent": 100})
        return {"ok": True, "path": dest, "size": os.path.getsize(dest),
                "msg": "下载完成，可以安装新版本"}

    def run_update(self, path=""):
        """启动刚下载的安装程序（安装器会自动关闭旧程序并保留数据）"""
        p = str(path or "").strip()
        if not p:
            try:
                cli, info = self._update_rel()
                rel = str(info.get("file") or "")
                p = os.path.join(DATA_DIR, "updates", os.path.basename(rel))
            except Exception:
                p = ""
        if not p or not os.path.isfile(p):
            return {"ok": False, "msg": "安装包不存在，请先下载"}
        try:
            subprocess.Popen([p], close_fds=True)
        except Exception as e:
            return {"ok": False, "msg": "启动安装程序失败：%s" % e}
        return {"ok": True, "msg": "正在启动安装程序，安装完成后重新打开即可"}

    # ---- 管理员：新设备登录审批 ----
    def admin_pending_devices(self):
        cli = self._sync_client()
        me = str(self._user_id())
        if me not in ADMIN_IDS:
            return {"ok": False, "msg": "只有管理员可以查看", "list": []}
        if cli is None:
            return {"ok": False, "msg": "还没有登录", "list": []}
        try:
            data, _ = self._load_accounts(cli)
        except Exception as e:
            return {"ok": False, "msg": "读取失败：%s" % e, "list": []}
        rows = []
        for a in data.get("accounts") or []:
            pend = a.get("pendingDevices") if isinstance(a.get("pendingDevices"), list) else []
            for d in pend:
                if not isinstance(d, dict):
                    continue
                rows.append({"account": str(a.get("id")), "accountName": str(a.get("name") or ""),
                             "deviceId": str(d.get("id") or ""), "device": str(d.get("name") or ""),
                             "at": int(d.get("at") or 0)})
        rows.sort(key=lambda x: -x["at"])
        return {"ok": True, "list": rows, "total": len(rows)}

    def admin_approve_device(self, account, device_id):
        cli = self._sync_client()
        me = str(self._user_id())
        if me not in ADMIN_IDS:
            return {"ok": False, "msg": "只有管理员可以操作"}
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        try:
            data, sha = self._load_accounts(cli)
        except Exception as e:
            return {"ok": False, "msg": "读取失败：%s" % e}
        for a in data.get("accounts") or []:
            if str(a.get("id")) != str(account):
                continue
            pend = [d for d in (a.get("pendingDevices") or [])
                    if isinstance(d, dict) and str(d.get("id")) != str(device_id)]
            dev_name = ""
            for d in (a.get("pendingDevices") or []):
                if isinstance(d, dict) and str(d.get("id")) == str(device_id):
                    dev_name = str(d.get("name") or "")
            a["pendingDevices"] = pend
            devices = [d for d in (a.get("devices") or []) if isinstance(d, dict)]
            if not any(str(d.get("id")) == str(device_id) for d in devices):
                devices.append({"id": str(device_id), "name": dev_name,
                                "at": int(time.time()), "approved": True})
            a["devices"] = devices
            break
        try:
            data["updatedAt"] = int(time.time())
            cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), sha,
                         "批准新设备登录", path="accounts.json")
        except Exception as e:
            return {"ok": False, "msg": "保存失败：%s" % e}
        return {"ok": True, "msg": "已允许这个设备登录"}

    def admin_deny_device(self, account, device_id):
        cli = self._sync_client()
        me = str(self._user_id())
        if me not in ADMIN_IDS:
            return {"ok": False, "msg": "只有管理员可以操作"}
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        try:
            data, sha = self._load_accounts(cli)
        except Exception as e:
            return {"ok": False, "msg": "读取失败：%s" % e}
        for a in data.get("accounts") or []:
            if str(a.get("id")) != str(account):
                continue
            a["pendingDevices"] = [d for d in (a.get("pendingDevices") or [])
                                   if isinstance(d, dict) and str(d.get("id")) != str(device_id)]
            break
        try:
            data["updatedAt"] = int(time.time())
            cli.put_file(json.dumps(data, ensure_ascii=False).encode("utf-8"), sha,
                         "拒绝新设备登录", path="accounts.json")
        except Exception as e:
            return {"ok": False, "msg": "保存失败：%s" % e}
        return {"ok": True, "msg": "已拒绝这个设备"}

    def friends_requests(self):
        """我收到的好友申请"""
        me = self._user_id()
        if not re.match(r"^\d{8}$", me):
            return {"ok": False, "msg": "账号ID未绑定", "list": []}
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录", "list": []}
        inbox, _ = self._read_remote_json(cli, "users/%s/inbox.json" % me)
        reqs = [r for r in ((inbox or {}).get("requests") or []) if isinstance(r, dict)]
        ids = [str(r.get("from")) for r in reqs if re.match(r"^\d{8}$", str(r.get("from") or ""))]
        meta = self._friend_meta(cli, ids)
        try:
            by_code, by_id = self._ensure_friend_codes(cli)
        except Exception:
            by_code, by_id = {}, {}
        rows = []
        for r in reqs:
            fid = str(r.get("from") or "")
            if not re.match(r"^\d{8}$", fid):
                continue
            a = by_id.get(fid) or {}
            rows.append({"from": fid, "code": str(a.get("friendCode") or ""),
                         "name": (meta.get(fid) or {}).get("name") or "",
                         "at": int(r.get("at") or 0)})
        rows.sort(key=lambda x: -x["at"])
        return {"ok": True, "list": rows, "total": len(rows)}

    def friends_sent(self):
        """我发出的、还没被同意的好友申请"""
        me = self._user_id()
        if not re.match(r"^\d{8}$", me):
            return {"ok": True, "list": []}
        cli = self._sync_client()
        if cli is None:
            return {"ok": True, "list": []}
        sentd, _ = self._read_remote_json(cli, "users/%s/sent.json" % me)
        items = [x for x in ((sentd or {}).get("sent") or []) if isinstance(x, dict)]
        ids = [str(x.get("to")) for x in items if re.match(r"^\d{8}$", str(x.get("to") or ""))]
        meta = self._friend_meta(cli, ids)
        try:
            by_code, by_id = self._ensure_friend_codes(cli)
        except Exception:
            by_code, by_id = {}, {}
        rows = []
        for x in items:
            to = str(x.get("to") or "")
            if not re.match(r"^\d{8}$", to):
                continue
            a = by_id.get(to) or {}
            rows.append({"to": to, "code": str(a.get("friendCode") or ""),
                         "name": (meta.get(to) or {}).get("name") or "",
                         "at": int(x.get("at") or 0)})
        rows.sort(key=lambda x: -x["at"])
        return {"ok": True, "list": rows, "total": len(rows)}

    def friends_accept(self, code):
        """同意好友申请：双方互为好友"""
        fid = "".join(ch for ch in str(code or "") if ch.isdigit())
        if len(fid) != 8:
            return {"ok": False, "msg": "好友码格式不对"}
        me = self._user_id()
        if not re.match(r"^\d{8}$", me):
            return {"ok": False, "msg": "账号ID未绑定"}
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        # 1) 我的好友列表加对方
        myfd, msha = self._read_remote_json(cli, self._friends_path())
        my_friends = [f for f in ((myfd or {}).get("friends") or []) if isinstance(f, dict)]
        if not any(str(f.get("id")) == fid for f in my_friends):
            my_friends.append({"id": fid, "addedAt": int(time.time())})
            try:
                self._put_remote_json(cli, self._friends_path(), {"friends": my_friends}, msha,
                                      "同意好友")
            except Exception as e:
                return {"ok": False, "msg": "保存失败：%s" % e}
        # 2) 对方好友列表加我
        his_path = "users/%s/friends.json" % fid
        hisfd, hsha = self._read_remote_json(cli, his_path)
        his_friends = [f for f in ((hisfd or {}).get("friends") or []) if isinstance(f, dict)]
        if not any(str(f.get("id")) == me for f in his_friends):
            his_friends.append({"id": me, "addedAt": int(time.time())})
            try:
                self._ensure_user_dir(cli, fid)
                self._put_remote_json(cli, his_path, {"friends": his_friends}, hsha, "互相添加好友")
            except Exception:
                pass
        # 3) 移除我的 inbox 里这条
        inbox, isha = self._read_remote_json(cli, "users/%s/inbox.json" % me)
        reqs = [r for r in ((inbox or {}).get("requests") or [])
                if isinstance(r, dict) and str(r.get("from")) != fid]
        try:
            self._put_remote_json(cli, "users/%s/inbox.json" % me, {"requests": reqs}, isha,
                                  "处理好友申请")
        except Exception:
            pass
        # 4) 移除对方的 sent 里这条
        try:
            sentd, ssha = self._read_remote_json(cli, "users/%s/sent.json" % fid)
            sent = [x for x in ((sentd or {}).get("sent") or [])
                    if isinstance(x, dict) and str(x.get("to")) != me]
            self._put_remote_json(cli, "users/%s/sent.json" % fid, {"sent": sent}, ssha,
                                  "处理好友申请")
        except Exception:
            pass
        return {"ok": True, "msg": "已同意，你们现在是好友了", "id": fid}

    def friends_reject(self, code):
        """拒绝好友申请"""
        fid = "".join(ch for ch in str(code or "") if ch.isdigit())
        if len(fid) != 8:
            return {"ok": False, "msg": "好友码格式不对"}
        me = self._user_id()
        if not re.match(r"^\d{8}$", me):
            return {"ok": False, "msg": "账号ID未绑定"}
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        inbox, isha = self._read_remote_json(cli, "users/%s/inbox.json" % me)
        reqs = [r for r in ((inbox or {}).get("requests") or [])
                if isinstance(r, dict) and str(r.get("from")) != fid]
        try:
            self._put_remote_json(cli, "users/%s/inbox.json" % me, {"requests": reqs}, isha,
                                  "拒绝好友申请")
        except Exception as e:
            return {"ok": False, "msg": "操作失败：%s" % e}
        try:
            sentd, ssha = self._read_remote_json(cli, "users/%s/sent.json" % fid)
            sent = [x for x in ((sentd or {}).get("sent") or [])
                    if isinstance(x, dict) and str(x.get("to")) != me]
            self._put_remote_json(cli, "users/%s/sent.json" % fid, {"sent": sent}, ssha,
                                  "好友申请被拒绝")
        except Exception:
            pass
        return {"ok": True, "msg": "已拒绝好友申请", "id": fid}

    def friends_remove(self, code):
        aid = "".join(ch for ch in str(code or "") if ch.isdigit())
        if len(aid) != 8:
            return {"ok": False, "msg": "好友码格式不对"}
        if not re.match(r"^\d{8}$", self._user_id()):
            return {"ok": False, "msg": "账号ID未绑定，请退出登录后重新输入账号ID"}
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        try:
            raw, sha = cli.get_file(self._friends_path())
            if not raw:
                return {"ok": True, "msg": "好友列表本来就是空的"}
            fd = json.loads(raw.decode("utf-8", "replace"))
        except Exception as e:
            return {"ok": False, "msg": "读取好友失败：%s" % e}
        before = [f for f in (fd.get("friends") or []) if isinstance(f, dict)]
        after = [f for f in before if str(f.get("id")) != aid]
        if len(after) == len(before):
            return {"ok": True, "msg": "这个好友不在列表里"}
        fd["friends"] = after
        try:
            cli.put_file(json.dumps(fd, ensure_ascii=False).encode("utf-8"), sha,
                         "删除好友", path=self._friends_path())
        except Exception as e:
            return {"ok": False, "msg": "保存失败：%s" % e}
        return {"ok": True, "msg": "已删除好友 " + aid, "id": aid}

    def friends_note(self, code, note=""):
        """给好友设置备注名（只存在自己的好友列表里，仅自己可见）"""
        aid = "".join(ch for ch in str(code or "") if ch.isdigit())
        if len(aid) != 8:
            return {"ok": False, "msg": "好友码格式不对"}
        if not re.match(r"^\d{8}$", self._user_id()):
            return {"ok": False, "msg": "账号ID未绑定，请退出登录后重新输入账号ID"}
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录"}
        try:
            raw, sha = cli.get_file(self._friends_path())
            if not raw:
                return {"ok": False, "msg": "好友列表还是空的"}
            fd = json.loads(raw.decode("utf-8", "replace"))
        except Exception as e:
            return {"ok": False, "msg": "读取好友失败：%s" % e}
        friends = [f for f in (fd.get("friends") or []) if isinstance(f, dict)]
        hit = None
        for f in friends:
            if str(f.get("id")) == aid:
                hit = f
                break
        if hit is None:
            return {"ok": False, "msg": "这个好友不在列表里"}
        nick = str(note or "").strip()[:20]
        if nick:
            hit["nick"] = nick
        else:
            hit.pop("nick", None)
        fd["friends"] = friends
        try:
            cli.put_file(json.dumps(fd, ensure_ascii=False).encode("utf-8"), sha,
                         "好友备注", path=self._friends_path())
        except Exception as e:
            return {"ok": False, "msg": "保存失败：%s" % e}
        return {"ok": True, "msg": ("备注已保存" if nick else "备注已清除"),
                "id": aid, "nick": nick}

    def hub_leaderboard(self):
        """好友排行榜：只统计「我自己 + 我的好友」的听歌时长"""
        cli = self._sync_client()
        if cli is None:
            return {"ok": False, "msg": "还没有登录", "list": []}
        me = str(self._user_id())
        allow = set()
        if re.match(r"^\d{8}$", me):
            allow.add(me)
            try:
                raw_f, _ = cli.get_file(self._friends_path())
                if raw_f:
                    for f in (json.loads(raw_f.decode("utf-8", "replace")).get("friends") or []):
                        fid = str(f.get("id") or "") if isinstance(f, dict) else str(f)
                        if re.match(r"^\d{8}$", fid):
                            allow.add(fid)
            except Exception:
                pass
        if not allow:
            return {"ok": True, "list": []}
        out = []
        seen = set()
        disabled = set()
        try:
            raw_acc, _ = cli.get_file("accounts.json")
            if raw_acc:
                for a in (json.loads(raw_acc.decode("utf-8", "replace")).get("accounts") or []):
                    if not a.get("enabled", True):
                        disabled.add(str(a.get("id")))
        except Exception:
            pass
        # 白名单昵称 + 我自己给好友的备注（一次取回）
        meta = {}
        try:
            _bc, _bi = self._ensure_friend_codes(cli)
            raw_acc, _ = cli.get_file("accounts.json")
            if raw_acc:
                for a in (json.loads(raw_acc.decode("utf-8", "replace")).get("accounts") or []):
                    meta[str(a.get("id"))] = a
        except Exception:
            pass
        nicks = {}
        try:
            raw_f2, _ = cli.get_file(self._friends_path())
            if raw_f2:
                for f in (json.loads(raw_f2.decode("utf-8", "replace")).get("friends") or []):
                    if isinstance(f, dict) and f.get("id") and str(f.get("nick") or "").strip():
                        nicks[str(f.get("id"))] = str(f.get("nick")).strip()
        except Exception:
            pass
        # 自己 + 好友全部列出（没有听歌时长也显示 0 分钟，产生时长后自动排序上升）
        out = []
        for uid in sorted(allow):
            info = meta.get(uid) or {}
            total = 0
            # 显示名优先级：我给对方的备注 > 对方在 App 设置的昵称 > 白名单名字 > 好友码
            name = nicks.get(uid, "")
            if not name:
                try:
                    raw_p, _ = cli.get_file("users/%s/profile.json" % uid)
                    if raw_p:
                        name = str((json.loads(raw_p.decode("utf-8", "replace")).get("name") or "")).strip()
                except Exception:
                    pass
            if not name:
                name = str(info.get("name") or "").strip()
            week = month = 0
            now_title = ""
            now_playing = False
            try:
                raw_s, _ = cli.get_file("users/%s/stats.json" % uid)
                if raw_s:
                    sd = json.loads(raw_s.decode("utf-8", "replace"))
                    total = int(sd.get("total") or 0)
                    days = sd.get("days") if isinstance(sd.get("days"), dict) else {}
                    if days:
                        tt = time.time()
                        cut7 = time.strftime("%Y-%m-%d", time.localtime(tt - 6 * 86400))
                        cut30 = time.strftime("%Y-%m-%d", time.localtime(tt - 29 * 86400))
                        week = sum(int(v or 0) for d, v in days.items() if str(d) >= cut7)
                        month = sum(int(v or 0) for d, v in days.items() if str(d) >= cut30)
                    nw = sd.get("now") if isinstance(sd.get("now"), dict) else None
                    if nw and nw.get("title") and int(nw.get("updatedAt") or 0) > time.time() - 300:
                        now_title = str(nw.get("title") or "")[:60]
                        now_playing = bool(nw.get("playing"))
            except Exception:
                pass
            ac = meta.get(uid) or {}
            out.append({"id": uid, "code": str(ac.get("friendCode") or ""),
                        "nick": nicks.get(uid, ""),
                        "name": name or "", "total": total,
                        "week": week, "month": month,
                        "nowTitle": now_title, "nowPlaying": now_playing,
                        "me": str(uid) == str(me)})
        out.sort(key=lambda x: x["total"], reverse=True)
        return {"ok": True, "list": out}

    def library_repair(self):
        """校验修复曲库：路径失效的条目按文件名在曲库目录里找回来；顺手修歌单。"""
        data = self.cached_library()
        tracks = data.get("tracks") or []
        folder = data.get("folder") or ""
        # 先建一次「小写文件名 -> 路径」索引：避免每首歌都 walk 整个目录，
        # 且用 lower() 匹配，兼容 Windows 不区分大小写（You are all I need ↔ You Are All I Need）
        index = {}
        if folder and os.path.isdir(folder):
            for root, _dirs, files in os.walk(folder):
                for fn in files:
                    stem = os.path.splitext(fn)[0]
                    if stem:
                        index.setdefault(stem.lower(), os.path.join(root, fn))
        fixed = missing = 0
        for t in tracks:
            p = t.get("path") or ""
            if p and os.path.isfile(p):
                t.pop("offline", None)
                continue
            base = os.path.splitext(os.path.basename(p or t.get("name") or ""))[0]
            hit = index.get(base.lower()) if base else None
            if hit:
                t["path"] = hit
                t["name"] = os.path.basename(hit)
                t["title"] = _t2s(clean_title(t.get("title") or "", hit))
                t.pop("offline", None)
                fixed += 1
            else:
                t["offline"] = True
                missing += 1
        _save_json(LIB_PATH, data)
        try:
            self._fix_playlists(tracks)
        except Exception:
            pass
        return {"ok": True, "fixed": fixed, "offline": missing, "tracks": len(tracks),
                "msg": "曲库已校验：共 %d 首%s%s" % (
                    len(tracks),
                    ("，修复 %d 首" % fixed) if fixed else "",
                    ("，%d 首未下载" % missing) if missing else "")}

    def get_playlists(self):
        d = _load_json(PLAYLISTS_PATH, {"playlists": []})
        if not isinstance(d, dict) or "playlists" not in d:
            return {"playlists": []}
        # 打开歌单时顺手校验一次：别的设备来的路径 → 换成本机文件 / 标未下载
        try:
            d = self._fix_playlists()
        except Exception:
            pass
        return d

    def save_playlists(self, obj):
        data = obj if isinstance(obj, dict) and isinstance(obj.get("playlists"), list) else {"playlists": []}
        # 规范化：纯路径字符串条目转对象；再从曲库补上标题/歌手，保证 UI 能正常显示
        try:
            tracks = self.cached_library().get("tracks") or []
            by_path = {t.get("path"): t for t in tracks if t.get("path")}
            for pl in data.get("playlists") or []:
                items = pl.get("items") or []
                norm = []
                for it in items:
                    if isinstance(it, dict):
                        o = dict(it)
                    else:
                        s = str(it or "")
                        o = {"path": s, "name": os.path.basename(s)}
                    hit = by_path.get(o.get("path") or "")
                    if hit:
                        if not o.get("title"):
                            o["title"] = hit.get("title") or ""
                        if not o.get("artist"):
                            o["artist"] = hit.get("artist") or ""
                        if not o.get("duration"):
                            o["duration"] = hit.get("duration") or 0
                    norm.append(o)
                pl["items"] = norm
        except Exception:
            pass
        _save_json(PLAYLISTS_PATH, data)
        return True

    def remove_tracks(self, paths):
        """只从曲库索引移除，不动磁盘上的文件。
        同时记录「墓碑」：同步时广播给其他设备，避免删掉的歌被合并回来。"""
        lib = _load_json(LIB_PATH, {"folder": "", "tracks": []})
        gone = set(paths or [])
        all_tracks = lib.get("tracks", [])
        removed = [t for t in all_tracks if t.get("path") in gone]
        kept = [t for t in all_tracks if t.get("path") not in gone]
        lib["tracks"] = kept
        _save_json(LIB_PATH, lib)
        try:
            self._add_tombstones(removed)
        except Exception:
            pass
        return {"ok": True, "count": len(kept)}


    # ---- 歌词 ----
    def get_lyrics(self, title, artist, path, duration=0, prefer="", force=False):
        """严格匹配歌词；prefer 可指定优先来源（换一版），force 跳过缓存"""
        key = _lyric_cache_path(title or "", artist or "")
        if not force:
            cached = _load_json(key, None)
            if cached and (cached.get("synced") or cached.get("plain")):
                cached["cached"] = True
                return cached

        base_title = _clean_query(title or '')
        if re.match(r'^(新?\s*录音\s*\d*|REC\d*)$', base_title) or base_title in ('讲课', '语文', '上课', '会议'):
            return {"synced": "", "plain": "", "source": "",
                    "instrumental": True, "msg": "纯音乐"}

        cands = _parse_candidates(title, artist, path)
        cjk = bool(re.search(r"[一-鿿]", title or ""))
        order = ["netease", "kugou", "lrclib"] if cjk else ["lrclib", "netease", "kugou"]
        if prefer:
            order = [prefer] + [x for x in order if x != prefer]

        def _hit(src, t, a):
            if src == "netease":
                f = netease_search(t, a, duration)
            elif src == "kugou":
                f = kugou_search(t, a, duration)
            else:
                f = lrclib_search(t, a, duration)
            if f and _has_substance(f.get("synced") or f.get("plain")):
                return f
            return None

        def _line_count(obj):
            body = (obj or {}).get("synced") or (obj or {}).get("plain") or ""
            n = 0
            for line in body.splitlines():
                c = _core(line)
                if c and not CREDITS_RE.match(c):
                    n += 1
            return n

        found = None
        best_n = 0
        for src in order:
            pool = cands[:3] if src == "netease" else cands
            for t, a in pool:
                # 已知歌手时，空歌手候选跳过：否则会命中同名翻唱
                if (artist or "").strip() and not a:
                    continue
                hit = _hit(src, t, a)
                if hit:
                    n = _line_count(hit)
                    if n > best_n:
                        best_n = n
                        found = hit
                    break
            if found:
                break
        if not found:
            return {"synced": "", "plain": "", "source": "",
                    "instrumental": True, "msg": "没找到匹配的歌词"}

        for k in ("synced", "plain", "trans"):
            if found.get(k):
                found[k] = _t2s(found[k])
        for k in ("synced", "plain"):
            if found.get(k):
                _cl = _clean_lyrics(found[k])
                if _has_substance(_cl):
                    found[k] = _cl
        found["cached"] = False
        # 行数太少（残缺）就不缓存，下次还能重新换源
        _body = found.get("synced") or found.get("plain") or ""
        _cnt = sum(1 for ln in _body.splitlines() if _core(ln) and not CREDITS_RE.match(_core(ln)))
        if _cnt >= 4:
            _save_json(key, found)
        return found

    def clear_lyrics_cache(self):
        n = 0
        for fn in os.listdir(LYRICS_DIR):
            if fn.endswith(".json"):
                try:
                    os.remove(os.path.join(LYRICS_DIR, fn))
                    n += 1
                except Exception:
                    pass
        return n

    def open_in_folder(self, path):
        try:
            os.startfile(os.path.dirname(path))
        except Exception:
            pass
        return True


# ────────────────────────────── 入口 ──────────────────────────────
def _app_exe_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def _msgbox(text, title="Luvsic 更新", flags=0x00000040):
    try:
        import ctypes
        return ctypes.windll.user32.MessageBoxW(0, text, title, flags)
    except Exception:
        return 0


def _luvsic_running():
    """App 是否正在运行（排除当前 --check-update 进程自己）"""
    try:
        import subprocess as sp
        out = sp.run(["tasklist", "/FI", "IMAGENAME eq Luvsic.exe", "/FO", "CSV"],
                     capture_output=True, text=True,
                     encoding="mbcs", errors="ignore").stdout
        me = os.getpid()
        for line in out.splitlines():
            parts = [x.strip('"') for x in line.split('","')]
            if len(parts) >= 2 and parts[0].lower() == "luvsic.exe":
                try:
                    if int(parts[1]) != me:
                        return True
                except Exception:
                    pass
        return False
    except Exception:
        return False


def _silent_update_check():
    """计划任务调用：用户没在用（App 未运行）时静默更新"""
    if _luvsic_running():
        return  # 用户正在使用，交给启动时的弹窗确认，别打断
    api = Api()
    _API["inst"] = api
    r = api.check_update()
    if not (r.get("ok") and r.get("hasUpdate")):
        return
    d = api.download_update()
    if not d.get("ok"):
        return
    try:
        inst = str(d.get("path") or "")
        if os.path.isfile(inst):
            import subprocess as sp
            flags = getattr(sp, "CREATE_NO_WINDOW", 0)
            sp.Popen([inst, "--silent"], creationflags=flags, close_fds=True)
    except Exception:
        pass


def _auto_update_check(api, delay=20):
    """启动后延迟检查：用户正在使用 → 弹窗确认；确认后下载并升级"""
    try:
        import time as _t
        _t.sleep(delay)
        if find_app_window("Luvsic", tries=1, delay=0) is None:
            return
        r = api.check_update()
        if not (r.get("ok") and r.get("hasUpdate")):
            return
        latest = str(r.get("latest") or "")
        notes = str(r.get("notes") or "")[:300]
        msg = ("发现新版本 v%s。\n%s\n\n要现在更新吗？\n"
               "（点「是」后会自动下载并安装，软件会关闭重启；曲库、歌单、好友等数据都会保留）" % (latest, notes))
        if _msgbox(msg, "Luvsic 更新", 0x00000004 | 0x00000040) != 6:
            return
        d = api.download_update()
        if not d.get("ok"):
            _msgbox("下载失败：%s" % (d.get("msg") or "未知错误"), "Luvsic 更新", 0x30)
            return
        api.run_update(d.get("path") or "")
    except Exception:
        pass


def _hub_touch_login(api, delay=8):
    """App 启动几秒后把登录时间刷到 Hub（管理员界面能看到最近打开时间）"""
    try:
        import time as _t
        _t.sleep(delay)
        api.hub_touch_login()
    except Exception:
        pass


def main():
    import subprocess as sp

    # 计划任务调用：App 未运行时静默检查并更新（不启动界面）
    if "--check-update" in sys.argv:
        try:
            _silent_update_check()
        except Exception:
            pass
        return

    port = free_port()
    srv = Server(("127.0.0.1", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    api = Api()
    _API["inst"] = api
    try:
        import threading as _th
        _th.Thread(target=_auto_update_check, args=(api,), daemon=True).start()
        _th.Thread(target=_hub_touch_login, args=(api,), daemon=True).start()
    except Exception:
        pass

    selftest = "--selftest" in sys.argv
    native_frame = "--native-frame" in sys.argv

    url = f"http://127.0.0.1:{port}/index.html"
    if selftest:
        demo_dir = os.environ.get("LUMEN_TEST_DIR", "").strip()
        if demo_dir and os.path.isdir(demo_dir):
            r = api.scan_folder(demo_dir)
            print("SELFTEST-LIB:", json.dumps(r, ensure_ascii=False)[:300], flush=True)
        url += "?selftest=1"

    first_instance = acquire_instance()
    existing = find_app_window("Luvsic", tries=1, delay=0)
    if existing:
        if not first_instance:
            # 真的还有一个 Luvsic 在跑：把窗口叫到前面，本进程退出
            try:
                import ctypes
                u = ctypes.windll.user32
                u.ShowWindow(existing, 9)
                u.SetForegroundWindow(existing)
            except Exception:
                pass
            print("EVENT: already running", flush=True)
            return
        # 只剩窗口、后端已经没了（僵尸窗口）：关掉它，继续正常启动
        print("EVENT: stale window, restarting", flush=True)
        close_stale_window(existing)
        time.sleep(0.4)

    edge = find_edge()
    if not edge:
        print("ERROR: 未找到 Microsoft Edge，无法创建窗口", flush=True)
        return

    W, H = 1200, 800
    profile = os.path.join(DATA_DIR, "edge-profile")
    args = [
        edge,
        "--app=" + url,
        f"--window-size={W},{H}",
        "--window-position=-12000,-12000",
        "--user-data-dir=" + profile,
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-features=Translate,msEdgeTranslate,MediaRouter,msEdgeSync,msEdgeIdentity"
        "Feature,msImplicitSignin,msEdgeWelcomePage,EdgeCollections,EdgeShoppingAssistant,AutofillServerCommunication",
        "--disable-sync",
        "--no-service-autorun",
        "--disable-background-networking",
        "--disable-component-update",
        "--disable-client-side-phishing-detection",
        "--no-pings",
        "--autoplay-policy=no-user-gesture-required",
        "--disable-background-timer-throttling",
        "--disable-renderer-backgrounding",
        "--disable-backgrounding-occluded-windows",
    ]
    proc = sp.Popen(args)

    if not _READY.wait(30):
        print("EVENT: page not ready in time", flush=True)

    hwnd = find_app_window("Luvsic", tries=60, delay=0.2)
    if hwnd:
        _WIN["hwnd"] = hwnd
        if not native_frame:
            tint_window(hwnd)
        center_window(hwnd, W, H + 32)
        try:
            import ctypes
            ctypes.windll.user32.SetForegroundWindow(hwnd)
        except Exception:
            pass
        print("EVENT: window ready", flush=True)
    else:
        print("EVENT: window not found", flush=True)

    # 关键：Edge 可能把 --app 窗口交给已有浏览器进程后自己退出，
    # 所以不能靠 proc.wait() 保活；这里盯窗口句柄（IsWindow），消失 10 秒才退出。
    import ctypes
    user32 = ctypes.windll.user32
    watch = hwnd or 0
    gone = 0
    try:
        while True:
            time.sleep(1.0)
            alive = bool(watch and user32.IsWindow(watch))
            if not alive:
                # 句柄失效时再用标题兜底找一次（窗口可能重建）
                alt = find_app_window("Luvsic", tries=1, delay=0)
                if alt:
                    if alt != watch:
                        print("EVENT: window re-attached hwnd=%s -> %s" % (watch, alt), flush=True)
                        watch = alt
                    alive = True
            if alive:
                gone = 0
                continue
            gone += 1
            if gone == 1:
                print("EVENT: window not found, waiting...", flush=True)
            if gone >= 10:
                print("EVENT: window closed (10s), exit", flush=True)
                break
            if proc.poll() is None:
                continue
    except KeyboardInterrupt:
        pass
    finally:
        try:
            if proc.poll() is None:
                proc.terminate()
        except Exception:
            pass
        try:
            srv.shutdown()
        except Exception:
            pass


if __name__ == "__main__":
    _init_log()
    try:
        main()
    except Exception:
        import traceback
        try:
            print(traceback.format_exc(), flush=True)
        except Exception:
            pass
        raise
