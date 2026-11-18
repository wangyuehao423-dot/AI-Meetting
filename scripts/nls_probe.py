# -*- coding: utf-8 -*-
"""
NLS 链路探针：把「浏览器采集出来的音频」按浏览器的发送逻辑重放一遍，
用来判断「收不到文字」是协议问题、还是浏览器降采样把音频弄坏了。

用法：python scripts/nls_probe.py <wav路径> [decimate|poly]
"""
import asyncio
import json
import sys
import uuid
import wave

import numpy as np
import websockets

URL = 'wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1'
APPKEY = 'lWEvLzMhwYKYMVFL'
RATE_OUT = 16000


def read_wav_f32(path):
    """读 wav -> float32 单声道（-1~1），返回 (samples, sr)"""
    with wave.open(path, 'rb') as w:
        ch, sr, width, n = w.getnchannels(), w.getframerate(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    if width == 2:
        data = np.frombuffer(raw, dtype='<i2').astype(np.float32) / 32768.0
    elif width == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        v = np.where(v & 0x800000, v - 0x1000000, v)
        data = (v.astype(np.float32) / 8388608.0)
    else:
        data = np.frombuffer(raw, dtype='<i4').astype(np.float32) / 2147483648.0
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    return data, sr


def decimate_browser(x, sr_src):
    """完全复刻前端 main.jsx 里的写法：input[floor(i*ratio)] 直接抽样"""
    ratio = sr_src / RATE_OUT
    n = int(len(x) / ratio)
    idx = (np.arange(n) * ratio).astype(np.int64)
    return x[np.clip(idx, 0, len(x) - 1)]


def poly_resample(x, sr_src):
    """正确做法：先低通再重采样（polyphase）"""
    from math import gcd
    from scipy.signal import resample_poly
    g = gcd(int(sr_src), RATE_OUT)
    return resample_poly(x, RATE_OUT // g, int(sr_src) // g).astype(np.float32)


def to_pcm16(x):
    return (np.clip(x, -1.0, 1.0) * 32767).astype('<i2')


def mid():
    return uuid.uuid4().hex


async def run(path, mode):
    token = __import__('urllib.request', fromlist=['x'])
    import urllib.request
    with urllib.request.urlopen('http://localhost:18080/api/speech/token', timeout=10) as r:
        cred = json.loads(r.read().decode())
    print(f'[token] {cred["token"][:8]}... appkey={cred["appKey"]}')

    x, sr = read_wav_f32(path)
    print(f'[wav] {sr}Hz ch-mono len={len(x)/sr:.2f}s rms={float(np.sqrt((x**2).mean())):.4f}')
    y = decimate_browser(x, sr) if mode == 'decimate' else poly_resample(x, sr)
    pcm = to_pcm16(y)
    dur = len(pcm) / RATE_OUT
    rms = float(np.sqrt(((pcm.astype(np.float32) / 32768.0) ** 2).mean()))
    print(f'[{mode}] -> 16k len={dur:.2f}s rms={rms:.4f} peak={int(np.abs(pcm.astype(np.int32)).max())}')

    uri = f'{URL}?token={cred["token"]}'
    async with websockets.connect(uri, max_size=None) as ws:
        task_id = mid()
        await ws.send(json.dumps({
            'header': {'appkey': cred['appKey'], 'message_id': mid(), 'task_id': task_id,
                       'namespace': 'SpeechTranscriber', 'name': 'StartTranscription'},
            'payload': {'format': 'pcm', 'sample_rate': RATE_OUT,
                        'enable_intermediate_result': True,
                        'enable_punctuation_prediction': True,
                        'enable_inverse_text_normalization': True}
        }))
        got = []
        started = asyncio.Event()

        async def reader():
            try:
                while True:
                    msg = await asyncio.wait_for(ws.recv(), timeout=6)
                    d = json.loads(msg)
                    h = d.get('header', {})
                    p = d.get('payload', {})
                    name = h.get('name')
                    st = h.get('status')
                    if st != 20000000:
                        print(f'  !! {name} status={st} {h.get("status_text")}')
                        return
                    if name == 'TranscriptionResultChanged':
                        print(f'  ~ {p.get("result","")}')
                    elif name == 'SentenceEnd':
                        got.append(p.get('result', ''))
                        print(f'  = {p.get("result","")}')
                    elif name in ('TranscriptionStarted', 'SentenceBegin', 'TranscriptionCompleted'):
                        print(f'  . {name}')
                        if name == 'TranscriptionStarted':
                            started.set()
                    if name == 'TranscriptionCompleted':
                        return
            except asyncio.TimeoutError:
                print('  (reader timeout)')

        rt = asyncio.create_task(reader())

        # 必须等服务端进入 STARTED 状态再推音频，否则报 40000002 Invalid binary message
        try:
            await asyncio.wait_for(started.wait(), timeout=10)
            print('[state] TranscriptionStarted -> 开始推流')
        except asyncio.TimeoutError:
            print('[!] 等 TranscriptionStarted 超时')
            await rt
            return

        # 浏览器节奏：每 4096 个 48k 样本推一次 => 85.3ms
        chunk = 1365  # 4096 / 3
        for i in range(0, len(pcm), chunk):
            seg = pcm[i:i + chunk]
            frame = b'\x02\x00\x00\x00' + seg.tobytes()
            await ws.send(frame)
            await asyncio.sleep(0.085)
        await ws.send(b'\x04\x00\x00\x00')
        print('[sent end]')
        await asyncio.wait_for(rt, timeout=10)
        print('[RESULT]', ''.join(got) or '(空)')


if __name__ == '__main__':
    p = sys.argv[1]
    m = sys.argv[2] if len(sys.argv) > 2 else 'decimate'
    asyncio.run(run(p, m))
