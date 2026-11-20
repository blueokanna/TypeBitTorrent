"""Download a generated BEP 3 torrent from a loopback peer using the packaged client."""

import base64
import hashlib
import json
import re
import secrets
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from pathlib import Path


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def receive(stream, size):
    data = stream.read(size)
    if len(data) != size:
        raise EOFError('peer disconnected during transfer')
    return data


@contextmanager
def connection(port, info_hash, extensions=False):
    with socket.create_connection(('127.0.0.1', port), timeout=20) as peer, peer.makefile('rb') as stream:
        reserved = b'\x00' * 5 + b'\x10\x00\x00' if extensions else bytes(8)
        peer.sendall(b'\x13BitTorrent protocol' + reserved + info_hash + b'-TB0001-' + secrets.token_bytes(12))
        assert receive(stream, 68)[28:48] == info_hash
        yield peer, stream


def read_message(stream):
    size, = struct.unpack('!I', receive(stream, 4))
    assert size <= 1 << 20
    return receive(stream, size)


def send_message(peer, message):
    peer.sendall(struct.pack('!I', len(message)) + message)


def serve_metadata(port, info):
    with connection(port, hashlib.sha1(info).digest(), extensions=True) as (peer, stream):
        size = str(len(info)).encode('ascii')
        send_message(peer, b'\x14\x00d1:md11:ut_metadatai7ee13:metadata_sizei' + size + b'ee')
        local_id = None
        while True:
            message = read_message(stream)
            if message.startswith(b'\x14\x00'):
                match = re.search(rb'11:ut_metadatai([0-9]+)e', message[2:])
                assert match, 'client did not advertise ut_metadata'
                local_id = int(match[1])
            elif message.startswith(b'\x14\x07'):
                assert local_id and message[2:] == b'd8:msg_typei0e5:piecei0ee'
                header = b'd8:msg_typei1e5:piecei0e10:total_sizei' + size + b'ee'
                send_message(peer, bytes([20, local_id]) + header + info)
                return


def transfer(port, info_hash, payload, check_peer):
    with connection(port, info_hash) as (peer, stream):
        peer.sendall(b'\x00\x00\x00\x02\x05\x80' + b'\x00\x00\x00\x01\x01')  # bitfield, unchoke
        check_peer()
        served = set()
        while len(served) < len(payload) // 16384:
            message = read_message(stream)
            if message[:1] != b'\x06':
                continue
            index, begin, length = struct.unpack('!III', message[1:])
            assert index == 0 and begin % 16384 == 0 and length == 16384 and begin + length <= len(payload)
            block = b'\x07' + message[1:9] + payload[begin:begin + length]
            send_message(peer, block)
            served.add(begin)


@contextmanager
def running_client(command):
    with subprocess.Popen(command) as app:
        try:
            yield app
        finally:
            app.terminate()
            try:
                app.wait(timeout=15)
            except subprocess.TimeoutExpired:
                app.kill()


def check(launcher, root):
    payload = secrets.token_bytes(65536)
    info = (b'd6:lengthi65536e4:name9:smoke.bin12:piece lengthi65536e6:pieces20:'
            + hashlib.sha1(payload).digest() + b'e')
    info_hash = hashlib.sha1(info).digest()
    port = free_port()
    files = root / 'downloads'
    files.mkdir()
    settings = {'connection': {'useRandomPort': True}, 'bitTorrent': {
        'enableDht': False, 'enableLsd': False, 'enableUpnp': False,
        'enableNatPmp': False, 'useDefaultTrackers': False}}
    (root / 'settings.json').write_text(json.dumps(settings), encoding='utf-8')
    cached_info = info.replace(b'smoke.bin', b'cache.bin')
    cached_hash = hashlib.sha1(cached_info).hexdigest()
    tracker = 'udp://127.0.0.1:19000/announce'
    restored = {'hash': cached_hash, 'name': 'cache.bin', 'kind': 'MAGNET', 'saveDir': str(files),
                'data': f'magnet:?xt=urn:btih:{cached_hash}&tr={tracker}', 'addedAt': 0, 'paused': True,
                'infoBase64': base64.b64encode(cached_info).decode('ascii')}
    fresh_info = info.replace(b'smoke.bin', b'fresh.bin')
    fresh_hash = hashlib.sha1(fresh_info).hexdigest()
    # Simulate a valid info dictionary cached under the wrong magnet hash.
    stale = dict(restored, hash=fresh_hash, name='pending', paused=False,
                 data=f'magnet:?xt=urn:btih:{fresh_hash}&tr={tracker}')
    (root / 'torrents.json').write_text(json.dumps([restored, stale]), encoding='utf-8')

    def api(path, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{port}/api/{path}',
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={'X-TypeBit': '1', 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=4) as response:
            return json.load(response)

    def wait_state(predicate):
        deadline = time.monotonic() + 45
        state = {}
        while time.monotonic() < deadline and app.poll() is None:
            try:
                state = api('state')
                if predicate(state):
                    return state
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                pass
            time.sleep(0.2)
        raise RuntimeError(f'packaged client failed to reach expected state: {state}')

    command = [launcher, '--headless', '--bind=127.0.0.1', f'--port={port}',
               f'--data={root}', f'--downloads={files}', '--password=smoke-test']

    def task(state, hash_hex=info_hash.hex()):
        return next((t for t in state['torrents'] if t['hash'] == hash_hex), {})

    def check_peer():
        state = wait_state(lambda s: task(s).get('seeds') == 1)
        assert task(state)['peers'] == 1
        peers = api('detail?hash=' + info_hash.hex())['peers']
        assert any(p['seed'] and p['phase'] == 2 for p in peers)

    with running_client(command) as app:
        state = wait_state(lambda s: s['engineRunning'] and s['listenPort'] > 0 and
                           any(t['hash'] == cached_hash and t['metadataReady'] for t in s['torrents']))
        cached = api('detail?hash=' + cached_hash)
        assert cached['files'][0]['path'] == 'cache.bin'
        assert tracker in [t['url'] for t in cached['trackers']]
        assert task(state, fresh_hash)['metadataReady'] is False
        serve_metadata(state['listenPort'], fresh_info)
        wait_state(lambda s: task(s, fresh_hash).get('metadataReady'))
        fresh = api('detail?hash=' + fresh_hash)
        assert fresh['files'][0]['path'] == 'fresh.bin'
        assert tracker in [t['url'] for t in fresh['trackers']]
        metainfo = base64.b64encode(b'd4:info' + info + b'e').decode('ascii')
        assert api('torrents/add', {'torrentBase64': metainfo, 'savePath': str(files)})['ok']
        wait_state(lambda s: task(s).get('metadataReady'))
        detail = api('detail?hash=' + info_hash.hex())
        assert detail['files'][0]['length'] == len(payload)
        resume = root / 'resume.bin'
        old_resume = resume.read_bytes() if resume.exists() else b''
        transfer(state['listenPort'], info_hash, payload, check_peer)
        state = wait_state(lambda s: task(s).get('status') == 'SEEDING' and
                           resume.exists() and resume.read_bytes() != old_resume)
        row = task(state)
        assert row['progress'] == 1 and row['havePieces'] == 1 and row['isComplete']
        assert (files / 'smoke.bin').read_bytes() == payload
        logs = api('logs?after=0')
        assert logs
        cursor = logs[-1]['seq'] + 1
        assert all(entry['seq'] >= cursor for entry in api(f'logs?after={cursor}'))

    with running_client(command) as app:
        state = wait_state(lambda s: task(s).get('status') == 'SEEDING')
        assert task(state)['havePieces'] == 1 and (files / 'smoke.bin').read_bytes() == payload
        assert task(state, fresh_hash)['metadataReady'] and api('detail?hash=' + fresh_hash)['files'][0]['path'] == 'fresh.bin'
        print(f"Packaged download and restart verified: {row['name']}, {len(payload)} bytes, {row['status']}")


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as directory:
        check(Path(sys.argv[1]).resolve(), Path(directory))
