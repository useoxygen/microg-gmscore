#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
"""Synthetic iPhone-role DCT HTTP peer. Apple attestation is a fixture, never verified trust."""
import argparse
import collections
import contextlib
import http.client
import http.server
import pathlib
import queue
import socket
import subprocess
import tempfile
import threading
import time
import urllib.parse

TYPE = 'application/vnd.dct.control+proto'

def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)

def integer(field, value):
    return varint(field << 3) + varint(value)

def message(field, value=b''):
    return varint((field << 3) | 2) + varint(len(value)) + value

def fields(data):
    def take(offset):
        value = 0
        for shift in range(0, 70, 7):
            if offset >= len(data):
                raise ValueError('truncated protobuf')
            part = data[offset]
            offset += 1
            value |= (part & 127) << shift
            if part < 128:
                return value, offset
        raise ValueError('oversized varint')
    result = {}
    offset = 0
    while offset < len(data):
        key, offset = take(offset)
        tag, wire = key >> 3, key & 7
        if tag == 0 or tag in result:
            raise ValueError('invalid or duplicate field')
        value, offset = take(offset)
        if wire == 2:
            end = offset + value
            if end > len(data):
                raise ValueError('truncated message')
            value, offset = data[offset:end], end
        elif wire != 0:
            raise ValueError('unsupported fixture wire type')
        result[tag] = value
    return result

def frame(tag, body=b''):
    return message(1, message(tag, body))

class Peer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, integrity='challenge'):
        super().__init__(address, Handler)
        self.integrity = integrity
        self.notifications = collections.deque()
        self.condition = threading.Condition()
    def enqueue(self, body):
        with self.condition:
            self.notifications.append(body)
            self.condition.notify_all()

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_):
        pass
    def reply(self, body=b'', kind=TYPE, status=200, headers=None):
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('x-dct-version', 'v1')
        self.send_header('Connection', 'close')
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True
    def do_GET(self):
        target = urllib.parse.urlsplit(self.path)
        if self.headers.get('x-dct-version') != 'v1':
            return self.reply(status=400)
        if target.path == '/notifications':
            with self.server.condition:
                if target.query == 'ack' and self.server.notifications:
                    self.server.notifications.popleft()
                if not self.server.notifications:
                    self.server.condition.wait(2)
                body = self.server.notifications[0] if self.server.notifications else b''
            return self.reply(body)
        if target.path == '/fixture/files':
            boundary = 'fixture_boundary'
            body = b''
            for data, metadata in [(b'abc', 'AQI='), (bytes(range(256)), 'AwQ=')]:
                body += (f'--{boundary}\r\nContent-Type: application/octet-stream\r\nContent-Length: {len(data)}\r\nX-OS-Migration-Metadata: base64; {metadata}\r\n\r\n').encode() + data + b'\r\n'
            return self.reply(body + f'--{boundary}--\r\n'.encode(), 'multipart/mixed; boundary=' + boundary)
        return self.reply(status=404)
    def do_POST(self):
        try:
            length = int(self.headers.get('Content-Length', '-1'))
            if not 0 <= length <= 16 * 1024 * 1024 or self.headers.get('x-dct-version') != 'v1':
                return self.reply(status=400)
            body = self.rfile.read(length)
            if len(body) != length:
                return self.reply(status=400)
            if self.path == '/capabilities':
                fields(body)
                return self.reply(message(1, integer(1, 1) + integer(2, 1) + integer(3, 1)))
            if self.path == '/controls':
                control = fields(fields(body)[1])
                if 1 in control:
                    print('STAGE peer device-info', flush=True)
                    return self.reply(frame(2, message(1, b'Mac synthetic peer') + integer(2, 2) + message(4, b'Apple')))
                if 11 in control:
                    print('STAGE peer integrity ' + self.server.integrity, flush=True)
                    self.server.enqueue(frame(13, integer(1, 1) + message(3)))
                    answer = message(1) if self.server.integrity == 'skip' else message(2, message(1, b'58585360821') + message(2, b'synthetic-public-salt'))
                    return self.reply(frame(12, answer))
                if 13 in control:
                    attestation = fields(control[13])
                    if attestation.get(1) != 1 or not fields(attestation.get(2, b'')).get(1):
                        return self.reply(status=400)
                    print('STAGE Google token present; fixture checks shape only, no verdict validation', flush=True)
                    self.server.enqueue(frame(14))
                    return self.reply()
                if 14 in control:
                    print('STAGE peer integrity ACK', flush=True)
                    return self.reply()
                if 3 in control:
                    print('BLOCKED Mac has no iPhone Wi-Fi Aware; no upgrade advertised', flush=True)
                    return self.reply(frame(4))
                if 5 in control:
                    return self.reply(frame(6))
                if 7 in control:
                    return self.reply(frame(8))
                return self.reply(status=400)
            if self.path in ('/_callback', '/file-content-ack'):
                return self.reply()
            if self.path == '/communications':
                return self.reply(b'\x08\x01', 'application/vnd.os-migration.control+proto')
            return self.reply(status=404)
        except (ValueError, KeyError, TypeError):
            self.reply(status=400)

def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]

def call(port, path, body=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    try:
        connection.request('GET' if body is None else 'POST', path, body=body,
                           headers={'x-dct-version': 'v1', 'Content-Type': TYPE})
        response = connection.getresponse()
        assert response.status == 200, response.status
        return response.read()
    finally:
        connection.close()

def verify(port):
    assert 2 in fields(fields(call(port, '/controls', frame(1)))[1])
    assert 1 in fields(call(port, '/capabilities', message(1)))
    assert 12 in fields(fields(call(port, '/controls', frame(11, message(2, message(2, b'salt')))))[1])
    first = call(port, '/notifications')
    assert 13 in fields(fields(first)[1])
    assert first == call(port, '/notifications')  # retained until consumed-message ACK
    call(port, '/controls', frame(13, integer(1, 1) + message(2, message(1, b'public-synthetic-token'))))
    assert 14 in fields(fields(call(port, '/notifications?ack'))[1])
    call(port, '/controls', frame(14))
    assert b'Content-Length: 256\r\n' in call(port, '/fixture/files')
    assert call(port, '/communications', b'') == b'\x08\x01'
    assert call(port, '/file-content-ack', b'') == b''
    print('PASS HTTP info/capability/integrity shape/notification ACK/application/multipart fixtures')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--integrity', choices=['challenge', 'skip'], default='challenge')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--pake-peer', type=pathlib.Path)
    args = parser.parse_args()
    with Peer(('127.0.0.1', args.port), args.integrity) as peer:
        if args.self_test:
            thread = threading.Thread(target=peer.serve_forever, daemon=True)
            thread.start()
            try:
                if not args.pake_peer:
                    verify(peer.server_port)
                else:
                    with tempfile.TemporaryDirectory(prefix='dct-peer-') as directory:
                        config = pathlib.Path(directory) / 'credentials'
                        config.write_text('abc234\ndef567\n')
                        config.chmod(0o600)
                        tls_port, clear_port = free_port(), free_port()
                        processes = []
                        try:
                            for arguments in [('--server', tls_port, peer.server_port), ('--client', clear_port, tls_port)]:
                                processes.append(subprocess.Popen([str(args.pake_peer), arguments[0], str(config), '127.0.0.1', str(arguments[1]), '127.0.0.1', str(arguments[2])], stdout=subprocess.PIPE, text=True))
                                assert processes[-1].stdout.readline().startswith('READY PAKE')
                            verify(clear_port)
                            print('PASS fixture HTTP through two real PAKE TLS1.3 proxies')
                        finally:
                            for process in processes:
                                process.terminate()
                            for process in processes:
                                process.wait(timeout=15)
            finally:
                peer.shutdown()
        else:
            print(f'READY synthetic HTTP peer on 127.0.0.1:{peer.server_port}; 60 second deadline', flush=True)
            threading.Timer(60, peer.shutdown).start()
            peer.serve_forever()

if __name__ == '__main__':
    main()
