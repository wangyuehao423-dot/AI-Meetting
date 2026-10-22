// ============================================================
// NLS 语音转写客户端 —— 前端这一头（纯采集端，音频走后端中继）
// ============================================================
// 一次 start() 干四件事：
//   1. getUserMedia 拿麦克风，AudioContext 把音频降采样成 16k 单声道 PCM
//   2. 打开业务 WebSocket，发 {type:'audio_start'} 通知后端建立阿里云 NLS 上行
//   3. 采集的裸 PCM 以二进制帧持续推给后端（阿里云 NLS 的帧头由后端
//      AliyunNlsRealtimeRelay 补齐，前端不再直连 NLS）
//   4. 后端把 NLS 识别的临时文本以 {type:'asr_preview'} 推回，这里转给界面实时预览
//
// 为什么改成"后端中继"而不是前端直连 NLS：
//   项目亮点写的是"基于 WebSocket + 阿里云 NLS 的实时 ASR 链路"，且要求后端存在
//   "音频 → NLS" 的转发。此前 AccessKey 只lease Token 到前端、音频故事在前端点对点
//   直连 NLS，后端收不到音频。现在前端只负责采集与推流，接入点、鉴权与识别结果
//   都收敛到后端，链路才名副其实。
// ============================================================

// 采样率只支持 16k / 8k，项目统一用 16k
const SAMPLE_RATE = 16000;

export class NlsTranscriptionClient {

  constructor(options) {
    this.sessionId = options.sessionId;
    this.onPreview = options.onPreview || (() => {});   // 后端推回的预览文本
    this.onError = options.onError || (() => {});
    this.business = null;      // 业务 WebSocket：上行音频 + 下行预览
    this.audioContext = null;
    this.processor = null;
    this.micStream = null;
    this.recording = false;
  }

  async start() {
    // 先建业务 WS，再拿麦克风，最后发 audio_start —— 顺序保证后端上行就绪后才会有音频。
    this.openBusinessSocket();
    // 只要音频；拿不到就直接报错，让用户改用文本输入
    this.micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // 等 WS 真正 OPEN 再发音频，否则 start 后的第一批帧会被后端当成"无上行"丢帧。
    await new Promise(resolve => {
      if (this.business?.readyState === WebSocket.OPEN) {
        resolve();
        return;
      }
      this.business.addEventListener('open', () => resolve(), { once: true });
    });
    // 通知后端：为本会话建立上行 NLS 连接。
    this.business.send(JSON.stringify({ type: 'audio_start' }));
    this.recording = true;
    this.startCapture();   // 采集 + 降采样 + 分包推送
  }

  // 业务 WS：地址与 WebSocketConfig 注册的路由一致；
  // 同一连接既上行音频帧，又下行 NLS 预览文本。
  openBusinessSocket() {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    this.business = new WebSocket(
        `${scheme}://${location.host}/ws/interviews/${this.sessionId}`
    );
    // 下行：后端把 NLS 的临时识别结果以 asr_preview 推回，刷新界面预览。
    this.business.onmessage = event => {
      if (typeof event.data !== 'string') return;
      const message = JSON.parse(event.data);
      if (message.type === 'asr_preview' && message.text) {
        this.onPreview(message.text);
      }
    };
    this.business.onerror = () => this.onError('语音连接异常');
  }

  // 采集：AudioContext 默认采样率通常是 48k，这里线性降采样到 16k 再发
  startCapture() {
    this.audioContext = new AudioContext();
    const source = this.audioContext.createMediaStreamSource(this.micStream);
    // 用 ScriptProcessor 逐块拿 PCM：已废弃但零依赖、够教学用；
    // 生产可换 AudioWorklet（多一个 worklet 文件，逻辑完全相同）。
    this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);
    const ratio = this.audioContext.sampleRate / SAMPLE_RATE;
    this.processor.onaudioprocess = event => {
      if (this.business?.readyState !== WebSocket.OPEN) return;   // WS 没连上就丢帧，不缓存
      const input = event.inputBuffer.getChannelData(0);
      // 降采样 + float32 → int16（NLS 要的是 16 位小端 PCM）
      const pcm = new Int16Array(Math.floor(input.length / ratio));
      for (let i = 0; i < pcm.length; i += 1) {
        const sample = input[Math.floor(i * ratio)];
        pcm[i] = Math.max(-1, Math.min(1, sample)) * 0x7fff | 0;
      }
      // 直接发裸 PCM 二进制帧；NLS 的 4 字节帧头由后端 AliyunNlsRealtimeRelay 补。
      this.business.send(pcm.buffer);
    };
    source.connect(this.processor);
    this.processor.connect(this.audioContext.destination);
  }

  stop() {
    this.recording = false;
    // 停麦前先告诉后端"音频发完了"，后端据此发 0x04 结束帧并关闭 NLS 上行。
    if (this.business?.readyState === WebSocket.OPEN) {
      this.business.send(JSON.stringify({ type: 'audio_end' }));
    }
    // 关麦克风：浏览器才会撤掉录音指示条
    this.micStream?.getTracks().forEach(track => track.stop());
    this.processor?.disconnect();
    this.audioContext?.close();
    // 关闭业务 WS：后端 afterConnectionClosed 会清理队列、转写上下文与 NLS 上行
    this.business?.close();
  }
}