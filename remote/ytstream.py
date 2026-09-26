#!/usr/bin/env python3
"""
YouTube for Just Play, through yt-dlp's own extractor and HTTP stack.

The app asks /resolve for a link and gets back two URLs on this machine: one for the video
stream and one for the audio, which YouTube serves apart. Each is a plain seekable HTTP
resource. A byte range from the player becomes upstream range requests of at most 10 MB,
the chunking yt-dlp's downloader uses so YouTube does not throttle a long read, sent with
the headers yt-dlp chose for that format. When an upstream URL expires or is refused, the
link is extracted again once and the read carries on from the same byte.

Only this machine and the networks in JPLAY_STREAM_ALLOW are answered (comma-separated CIDRs;
default: the private IPv4/IPv6 ranges). Stream tokens are unguessable and last 12 hours.
"""
import ipaddress
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import yt_dlp
from yt_dlp.networking import Request
from yt_dlp.networking.exceptions import HTTPError

PORT = 8793
ALLOWED = [ipaddress.ip_network(n.strip(), strict=False) for n in
           (os.environ.get('JPLAY_STREAM_ALLOW') or '10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7').split(',') if n.strip()]
# H.264 up to 1080p with AAC decodes in hardware on the TV and the phone alike.
FORMAT = 'bv*[vcodec^=avc1][height<=1080]+ba[acodec^=mp4a]/b[vcodec^=avc1][height<=1080]/bv*[height<=1080]+ba/b'
CHUNK = 10 * 1024 * 1024
LIFETIME = 12 * 3600
TYPES = {'mp4': 'video/mp4', 'm4a': 'audio/mp4', 'webm': 'video/webm', 'weba': 'audio/webm'}

# The mweb client with a proof-of-origin token from the local bgutil provider (port 4416)
# is served in full; without a token YouTube stops every client roughly 10 MB in.
ydl = yt_dlp.YoutubeDL({'quiet': True, 'no_warnings': True, 'noplaylist': True, 'format': FORMAT,
                       'extractor_args': {'youtube': {'player_client': ['mweb']}}})
extracting = threading.Lock()
sessions = {}
sessions_lock = threading.Lock()


def log(message):
    print(time.strftime('%H:%M:%S'), message, file=sys.stderr, flush=True)


def clen(url):
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    try:
        return int(query['clen'][0])
    except (KeyError, IndexError, ValueError):
        return None


def describe(fmt):
    return {'url': fmt['url'], 'headers': dict(fmt.get('http_headers') or {}),
            'size': fmt.get('filesize') or clen(fmt['url']), 'ext': fmt.get('ext') or 'mp4',
            'available_at': fmt.get('available_at') or 0}


def ready(stream):
    """YouTube hands out stream URLs that answer 403 until a few seconds after extraction;
    yt-dlp's downloader sleeps until the format's available_at, and so does this."""
    wait = stream.get('available_at', 0) - time.time()
    if wait > 0:
        time.sleep(wait + 0.2)


def extract(source):
    with extracting:
        info = ydl.extract_info(source, download=False)
    picked = info.get('requested_formats') or [info]
    video = next((f for f in picked if f.get('vcodec') not in (None, 'none')), picked[0])
    audio = next((f for f in picked if f is not video and f.get('acodec') not in (None, 'none')), None)
    streams = {'v': describe(video)}
    if audio:
        streams['a'] = describe(audio)
    return info, streams


def refresh(session):
    with session['lock']:
        if time.time() - session['refreshed'] < 5:
            return
        _, streams = extract(session['source'])
        session['streams'].update(streams)
        session['refreshed'] = time.time()
        log('re-extracted ' + session['source'])


def size_of(stream):
    if stream['size'] is None:
        response = ydl.urlopen(Request(stream['url'], headers={**stream['headers'], 'Range': 'bytes=0-0'}))
        total = response.headers.get('Content-Range', '').rpartition('/')[2]
        response.close()
        stream['size'] = int(total) if total.isdigit() else None
    return stream['size']


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        pass

    def allowed(self):
        address = ipaddress.ip_address(self.client_address[0])
        if address.version == 6 and address.ipv4_mapped:
            address = address.ipv4_mapped
        return address.is_loopback or any(address in network for network in ALLOWED)

    def reply(self, code, body):
        data = (json.dumps(body) + '\n').encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if not self.allowed():
            self.close_connection = True
            return
        url = urllib.parse.urlparse(self.path)
        if url.path == '/resolve':
            return self.resolve(urllib.parse.parse_qs(url.query).get('url', [''])[0])
        parts = url.path.strip('/').split('/')
        if len(parts) == 3 and parts[0] == 's':
            return self.relay(parts[1], parts[2])
        self.send_error(404)

    def resolve(self, source):
        if not source:
            return self.reply(400, {'error': 'url is required'})
        try:
            info, streams = extract(source)
        except Exception as error:
            log('extract failed for %s: %s' % (source, error))
            return self.reply(502, {'error': str(error).splitlines()[0][:300]})
        for stream in streams.values():
            ready(stream)
        token = secrets.token_urlsafe(18)
        with sessions_lock:
            for stale in [t for t, s in sessions.items() if s['created'] < time.time() - LIFETIME]:
                del sessions[stale]
            sessions[token] = {'source': source, 'streams': streams, 'created': time.time(),
                               'refreshed': 0, 'lock': threading.Lock()}
        base = 'http://%s/s/%s' % (self.headers.get('Host') or '%s:%d' % (self.server.server_address[0], PORT), token)
        body = {'title': info.get('title'), 'duration': info.get('duration'), 'video': base + '/v'}
        if 'a' in streams:
            body['audio'] = base + '/a'
        log('resolved %s: %s' % (source, info.get('title')))
        self.reply(200, body)

    def relay(self, token, kind):
        with sessions_lock:
            session = sessions.get(token)
        if not session or kind not in session['streams']:
            return self.send_error(404)
        stream = session['streams'][kind]
        ready(stream)
        try:
            size = size_of(stream)
        except Exception as error:
            log('size probe failed: %s' % error)
            return self.send_error(502)
        first, last, partial = 0, (size - 1 if size else None), False
        match = re.match(r'bytes=(\d*)-(\d*)$', self.headers.get('Range', ''))
        if match and size and (match.group(1) or match.group(2)):
            if match.group(1) == '':
                first = max(0, size - int(match.group(2)))
            else:
                first = int(match.group(1))
                last = min(int(match.group(2)), size - 1) if match.group(2) else size - 1
            if first > last:
                self.send_response(416)
                self.send_header('Content-Range', 'bytes */%d' % size)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            partial = True
        self.send_response(206 if partial else 200)
        self.send_header('Content-Type', TYPES.get(stream['ext'], 'application/octet-stream'))
        self.send_header('Accept-Ranges', 'bytes')
        if size:
            self.send_header('Content-Length', str(last - first + 1))
        else:
            self.close_connection = True
        if partial:
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (first, last, size))
        self.end_headers()
        if self.command == 'HEAD':
            return
        position, retried = first, False
        try:
            while last is None or position <= last:
                upto = position + CHUNK - 1 if last is None else min(last, position + CHUNK - 1)
                try:
                    upstream = ydl.urlopen(Request(stream['url'], headers={**stream['headers'], 'Range': 'bytes=%d-%d' % (position, upto)}))
                except HTTPError as error:
                    if error.status in (403, 404, 410) and not retried:
                        retried = True
                        refresh(session)
                        stream = session['streams'][kind]
                        ready(stream)
                        continue
                    raise
                retried = False
                got = 0
                try:
                    while True:
                        data = upstream.read(262144)
                        if not data:
                            break
                        self.wfile.write(data)
                        position += len(data)
                        got += len(data)
                finally:
                    upstream.close()
                if got == 0:
                    break
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            log('relay failed at byte %d: %s' % (position, error))
            self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == '__main__':
    server = Server(('0.0.0.0', PORT), Handler)
    log('yt-dlp %s streaming for Just Play on port %d' % (yt_dlp.version.__version__, PORT))
    server.serve_forever()
