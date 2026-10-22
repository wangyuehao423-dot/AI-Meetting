import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
// 从这里开始是新增的：阿里云 NLS 实时转写客户端（麦克风采集 → 16k PCM → NLS → 分片上报后端）。
// 原来这段录音逻辑是"MediaRecorder 录完就丢"的占位实现，换成真实链路。
import { NlsTranscriptionClient } from './nlsTranscriptionClient';
// 新增结束

const API_ROOT = import.meta.env.VITE_API_ROOT || '';

const restoreStoredUser = () => {
  try {
    const raw = localStorage.getItem('interview_user');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
};

const normalizeResume = value => value
  ? { ...value, id: String(value.id), userId: String(value.userId) }
  : null;

const request = async (url, options = {}) => {
  const token = localStorage.getItem('interview_token');
  const isForm = options.body instanceof FormData;
  const response = await fetch(`${API_ROOT}${url}`, {
    ...options,
    headers: {
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      ...(options.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  });
  const body = await response.text();
  if (!response.ok) {
    if (response.status === 401) {
      localStorage.removeItem('interview_token');
      localStorage.removeItem('interview_user');
      window.location.reload();
    }
    let message = body;
    try {
      message = JSON.parse(body).message || body;
    } catch {
      // Keep the raw response when the server did not return JSON.
    }
    // 从这里开始是新增的：账号在服务端已不存在（数据库重建/被删等）时，本地残留的
    // 登录态是无效的。与其让用户在每个写接口上反复撞 "user not found"，不如检测到
    // 这类错误就清掉本地登录态、跳回注册界面，走一次正常的重新注册流程。
    if (typeof message === 'string' && message.includes('user not found')) {
      localStorage.removeItem('interview_token');
      localStorage.removeItem('interview_user');
      window.location.href = `${window.location.pathname}?register=1`;
      throw new Error(message);
    }
    // 新增结束
    throw new Error(message || `HTTP ${response.status}`);
  }
  return body ? JSON.parse(body) : null;
};

const idForRequest = () => (
  globalThis.crypto?.randomUUID?.() ||
  `${Date.now()}-${Math.random().toString(16).slice(2)}`
);

const escapeHtml = value => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#039;');

const markdownToHtml = markdown => {
  const safe = escapeHtml(markdown || '');
  return safe
    .replace(/^### (.*)$/gm, '<h3>$1</h3>')
    .replace(/^## (.*)$/gm, '<h2>$1</h2>')
    .replace(/^# (.*)$/gm, '<h1>$1</h1>')
    .replace(/^\s*[-*] (.*)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/\n/g, '<br />')
    .replace(/^(.+)$/s, '<p>$1</p>');
};

// 从这里开始是删除的：原 defaultPositions 是三份写死在代码里的岗位兜底数据。
// 为什么必须删：岗位是业务数据，必须来自后端 /api/positions（数据库 interview_position），
// 代码里再留一份"API 挂了就用假岗位"的副本，用户会拿到和后端不一致的岗位列表，
// 且这些假岗位没有真实技能/描述支撑，属于典型的模拟数据。删掉后岗位列表完全由
// API 驱动：加载失败就明确提示重试，而不是静默展示假数据。

function App() {
  const [user, setUser] = useState(restoreStoredUser);
  const [form, setForm] = useState({ username: '', password: '', displayName: '' });
  // 从这里开始是修改的：初始界面支持从 URL 直达注册表单。
  // 为什么需要：一键启动脚本打开浏览器时带 ?register=1，新用户第一眼看到的就是
  // 注册界面（正常访问流程的第一步），而不是登录界面或"未登录"的主界面。
  const [register, setRegister] = useState(
    () => new URLSearchParams(window.location.search).get('register') === '1'
  );
  const [positions, setPositions] = useState([]);
  const [position, setPosition] = useState(null);
  const [resume, setResume] = useState(null);
  const [session, setSession] = useState(null);
  const [question, setQuestion] = useState(null);
  // 从这里开始是新增的：当前这道原题派生出的追问。
  // 追问存在 interview_follow_up 表、不作为新题插入 interview_question，
  // 所以它不会出现在 question 里，必须单独查出来放这里。
  // 非空表示"现在要答的是追问"，提交答案时会带上 followUp=true。
  const [followUp, setFollowUp] = useState(null);
  // 新增结束
  const [answer, setAnswer] = useState('');
  const [transcript, setTranscript] = useState('');
  const [report, setReport] = useState(null);
  const [screen, setScreen] = useState('SETUP');
  const [busy, setBusy] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [recording, setRecording] = useState(false);
  // 从这里开始是修改的：原来这里是 MediaRecorder 的 ref，现在换成 NLS 转写客户端
  // （它内部自己持有两条 WebSocket 与 AudioContext，stop() 时统一释放）。
  const nlsClient = useRef(null);
  // 面试 WebSocket 的 ref：会话开始时建立，页面卸载或会话切换时关闭。
  const socketRef = useRef(null);
  // 修改结束
  const requestIdRef = useRef(null);

  const userId = user?.userId;
  const currentAnswer = useMemo(() => (answer || transcript).trim(), [answer, transcript]);

  useEffect(() => {
    if (!user) return;
    // 从这里开始是修改的：岗位列表完全由后端 /api/positions 驱动，不再有任何硬编码兜底。
    request('/api/positions')
      .then(items => {
        const values = Array.isArray(items) ? items : [];
        setPositions(values);
        setPosition(values[0] || null);
        if (!values.length) {
          setNotice('暂无可用岗位，请稍后刷新重试。');
        }
      })
      .catch(() => {
        setPositions([]);
        setPosition(null);
        setNotice('岗位加载失败，请刷新页面重试。');
      });

    request(`/api/resumes?userId=${encodeURIComponent(userId)}`)
      .then(value => setResume(normalizeResume(value)))
      .catch(() => setResume(null));
  }, [user, userId]);

  const run = async action => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
    } catch (caught) {
      setError(caught.message || '请求失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const auth = () => run(async () => {
    const endpoint = register ? '/api/auth/register' : '/api/auth/login';
    const payload = register
      ? form
      : { username: form.username, password: form.password };
    const value = await request(endpoint, {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    localStorage.setItem('interview_token', value.token);
    localStorage.setItem('interview_user', JSON.stringify(value));
    setUser(value);
  });

  const logout = () => {
    localStorage.removeItem('interview_token');
    localStorage.removeItem('interview_user');
    setUser(null);
    setSession(null);
    setScreen('SETUP');
  };

  const uploadResume = file => run(async () => {
    if (!file) return;
    const data = new FormData();
    data.append('userId', userId);
    data.append('file', file);
    const value = await request('/api/resumes', { method: 'POST', body: data });
    setResume(normalizeResume(value));
    setNotice('简历解析完成，可以开始面试。');
  });

  const startInterview = () => run(async () => {
    // 从这里开始是修改的：岗位为空单独提示（岗位现在完全来自 API，加载失败/为空时
    // 不能让用户带着 null 岗位进入面试）。
    if (!position) {
      throw new Error('请先选择目标岗位');
    }
    if (!resume || resume.status !== 'PARSED') {
      throw new Error('请先上传并解析简历，再选择岗位');
    }
    // 从这里开始是修改的：创建面试。后端 InterviewController.create 用的是
    // @RequestParam（查询参数），不是 JSON 请求体，原来发 JSON 会 400；
    // 另外创建完必须再调一次 /start，否则后端 preparationTasks.prepare(sessionId)
    // 不会跑，后面取题拿不到东西。
    const query = new URLSearchParams({
      userId: String(userId),
      resumeId: String(resume.id),
      positionId: String(position.id)
    });
    const value = await request(`/api/interviews?${query}`, {
      method: 'POST'
    });
    setSession(value);
    setScreen('ANSWERING');
    // 启动面试：状态机 INIT → ...，同时异步准备第一题。
    await request(`/api/interviews/${value.sessionId}/start`, {
      method: 'POST'
    });
    // 取第一题：后端只有 POST .../questions 这个入口（没有 GET .../questions/current）。
    // 从这里开始是修改的：用 takeQuestion 统一取题——异步首题可能尚未落库，
    // takeQuestion 会等待就绪并校验 ACTIVE，避免一次性请求撞上 500 直接报错。
    const first = await takeQuestion(value.sessionId);
    setQuestion(first);
    // 新增：开始一场新面试时清掉上一场残留的追问，否则第一题会被上一场的追问盖住。
    setFollowUp(null);
    // 修改结束
  });

  // 从这里开始是修改的：报告生成期间持续轮询状态，直到离开 GENERATING（COMPLETED 或 FAILED）。
  // 为什么必须等到终态而不是轮询固定次数就停：之前触发生成后只轮询 60 次，若 AI 生成慢，
  // 60 秒后轮询结束、拿到的还是 GENERATING，界面就永远卡在"报告状态：GENERATING"，
  // 其实后端还在跑、稍后会 COMPLETED/FAILED，只是前端不再等。
  const pollReportUntilSettled = async (maxAttempts = 90) => {
    // 单轮最多等 maxAttempts 秒（默认 90 秒）：AI 生成报告通常几十秒内完成。
    // 若这一轮等完仍是 GENERATING，本方法会把后端最新状态返回给调用方（loadReport），
    // 由调用方决定是否做"兜底重触发"；这里只负责"等价满一轮之后带着最新状态返回"，
    // 不再像之前那样自己无限等下去——无限等只会让界面永远停在"生成中"。
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const s = await request(`/api/reports/${session.sessionId}/status`);
      if (s.status !== 'GENERATING') {
        return s;
      }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    return request(`/api/reports/${session.sessionId}/status`);
  };
  // 修改结束

  // 从这里开始是修改的：报告改成"先读已有状态 → 没有/已失败才 POST 触发生成 → 轮询到终态"。
  const loadReport = async () => {
    if (!session) return;
    // 立即给出"生成中"的真实状态，而不是停在空白转圈，让用户明确知道在生成哪一步。
    setReport({ status: 'GENERATING' });
    // 先读一次状态。首次进报告页时库里还没有记录，status 接口返回 404，
    // 用 try/catch 接住并当作"没有报告"，自然会落到下面的"触发生成"分支。
    let existing = null;
    try {
      existing = await request(`/api/reports/${session.sessionId}/status`);
    } catch {
      existing = null;
    }
    // 已完成：直接展示，不重新生成。
    if (existing && existing.markdown) {
      setReport(existing);
      return;
    }
    // 没有记录，或上一次生成失败：POST 触发生成（后端幂等，已在生成中的不会重复起）。
    if (!existing || existing.status === 'FAILED') {
      try {
        await request(`/api/reports/${session.sessionId}`, { method: 'POST' });
      } catch {
        // 幂等触发失败（例如已在生成中）不致命，下面继续轮询即可。
      }
    }
    // 已在生成中或刚触发：持续轮询直到 COMPLETED/FAILED 再展示，绝不再固定停在 GENERATING。
    // 从这里开始是新增的：兜底重试。
    // 为什么需要兜底：单次 pollReportUntilSettled 默认只等一轮（90 秒）。如果上一轮那个
    // 异步生成任务因 AI 上游超时/异常而没有真正起效，数据库记录就会一直停在 GENERATING，
    // 信息公开说等再久也不会自己变终态（因为前端只对 GENERATING 轮询、不会重新触发）。
    // 于是这里在每一轮等满后仍看到 GENERATING 时，显式再 POST 一次触发生成——后端对已
    // 存在的 GENERATING 记录会"重置状态 + 重新触发一个新的异步生成任务"——把卡死的记录
    // "救活"。最多兜底重试 2 次，总计最长等约 270 秒（3 轮 × 90 秒）。若重试完仍是
    // GENERATING，就把后端真实状态展示出来让用户点「刷新报告」手动再试。
    let settled = await pollReportUntilSettled();
    let retries = 0;
    while (settled.status === 'GENERATING' && retries < 2) {
      try {
        await request(`/api/reports/${session.sessionId}`, { method: 'POST' });
      } catch {
        // 重置触发即使失败也不致命，下一轮轮询仍会尽力读到终态。
      }
      retries += 1;
      settled = await pollReportUntilSettled();
    }
    // 新增结束
    setReport(settled);
  };
  // 修改结束

  // 从这里开始是新增的：每次切到报告页都重新拉取一次报告。
  // 为什么必须加这一条：侧边栏"查看报告"（setScreen('REPORT')）本身不会触发加载，
  // 如果 report 状态还残留着上一次（旧逻辑）留下的 GENERATING，切进来就会一直
  // 显示"报告状态：GENERATING"直到手动点"刷新报告"。这里在 screen 变成 REPORT
  // 时先清掉残留状态、再重新 loadReport（新逻辑：已 COMPLETED 就直接展示正文，
  // 不会触发重新生成），保证切进报告页看到的永远是实时状态而不是残留的假象。
  useEffect(() => {
    if (screen === 'REPORT' && session) {
      setReport(null);
      loadReport();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [screen]);
  // 新增结束

  // 从这里开始是新增的：面试 WebSocket 连接（含 ping/pong 保活）。
  /**
   * 连接面试 WebSocket，并每 10 秒发一次 ping 保持连接活跃。
   *
   * 为什么需要 ping/pong 保活：某些网络设备（负载均衡器、防火墙）会关闭
   * 长时间空闲的 WebSocket 连接；定期发送心跳包让连接始终有数据往来。
   *
   * 为什么用 setInterval 而不是 setTimeout：ping 是周期性动作，
   * setInterval 不用自己重排下一次；代价是必须在 onclose 里清除定时器，
   * 否则连接断开后定时器还在往一个已关闭的 socket 上发消息。
   *
   * @param sessionId 面试会话 ID，出现在 URL 路径上，服务端靠握手拦截器解析它
   * @returns 已建立（或正在建立）的 WebSocket 实例，调用方可自行持有以便关闭
   */
  const connectInterviewSocket = sessionId => {
    // 同源推导协议与地址：https 页面必须用 wss，否则浏览器会拦掉混合内容。
    const socket = new WebSocket(
      `${location.protocol === 'https:' ? 'wss' : 'ws'}`
        + `://${location.host}/ws/interviews/${sessionId}`
    );

    // 每 10 秒发一次 ping。先判断 readyState：连接还在握手时 send 会抛异常。
    const pingTimer = window.setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'ping' }));
      }
    }, 10000);

    // 处理接收到的消息。
    socket.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.type === 'pong') {
        // 服务端对 ping 的响应，只证明连接还活着，不需要改任何界面状态。
        return;
      }
      if (message.type === 'state') {
        // 服务端推送状态变更：只覆盖 state 与 version 两个字段，
        // 用函数式更新合并，避免把 previous 直接替换掉导致 sessionId 等字段丢失。
        setSession(previous => ({
          ...(previous || {}),
          state: message.state,
          version: message.version
        }));
      }
    };

    // 连接关闭时清除 ping 定时器：不清的话定时器会一直空转，
    // 直到页面卸载，属于典型的内存与定时器泄漏。
    socket.onclose = () => {
      window.clearInterval(pingTimer);
    };

    return socket;
  };

  // 会话建立后连上 /ws/interviews/{sessionId} 并开始保活；
  // 会话换掉或组件卸载时关掉旧连接，避免留下"孤儿 WebSocket"。
  // 为什么依赖写成 session?.sessionId：这个 effect 只关心"连的是哪个会话"，
  // 状态推进（state / version 变化）时不应该重连。
  useEffect(() => {
    if (!session?.sessionId) {
      return undefined;
    }
    socketRef.current = connectInterviewSocket(session.sessionId);
    return () => {
      socketRef.current?.close();
      socketRef.current = null;
      // 离开答题页时把还在跑的语音输入也停掉，否则麦克风会一直占着。
      nlsClient.current?.stop();
      nlsClient.current = null;
    };
  }, [session?.sessionId]);
  // 新增结束

  // 从这里开始是新增的：等待状态推进的轮询函数。
  // 为什么用轮询而不是让后端推：状态推进发生在异步评分线程里，前端只需要一个
  // 简单可靠的"就绪信号"，1 秒一次的轻量 GET 足够，也不必引入长连接。
  // 为什么放在 App() 内部：每次拿到状态都要 setSession 写回组件状态，
  // 放在模块顶层拿不到组件内的 setter。
  const waitForNextQuestionState = async sessionId => {
    // 上限 150 秒：后端评分/追问/出题是多次 AI 调用，正常几十秒内会推进到下一题。
    // 万一某环节异常导致状态停在中间态，也不能让用户无限转圈，超时就明确报错。
    for (let attempt = 0; attempt < 150; attempt += 1) {
      const current = await request(
        `/api/interviews/${sessionId}/state`
      );
      // 每次拿到的最新状态都写回 session：状态一起更新，界面不会停在旧值。
      setSession(previous => ({
        ...(previous || {}),
        ...current
      }));
      // 已经可以答下一题：直接返回，交给调用方去取题目。
      if (
        current.state === 'QUESTIONING'
        || current.state === 'ANSWERING'
      ) {
        return current;
      }
      // 面试结束：同样返回，由调用方跳报告页，不要再等下去。
      if (current.state === 'ENDED') {
        return current;
      }
      // 还在评分 / 推进中：等 1 秒再查，避免打满后端。
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    throw new Error('等待下一题超时，请刷新页面重试。');
  };
  // 新增结束

  // 从这里开始是新增的：统一的"取题并等待就绪"助手，供首次取题与后续取题复用。
  // 为什么需要它：后端取题接口 POST /api/interviews/{sessionId}/questions 在异步
  // 出题尚未落库时，会返回 HTTP 500（singleflight 的可重试超时信号），或返回
  // status 非 ACTIVE 的中间态题。之前两个取题点都只调一次 request，撞上 500 就
  // 直接把错误抛给用户，红色报错卡在答题页。这里统一做"有限次重取 + 校验 ACTIVE"：
  // 失败或不就绪就等 1.5 秒再来一次，直到拿到一道可答的 ACTIVE 题，或超时明确报错。
  const takeQuestion = async sessionId => {
    const maxAttempts = 10; // 单题最多等约 10*1.5=15 秒：首题生成通常几十秒内完成。
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      let next;
      try {
        next = await request(`/api/interviews/${sessionId}/questions`, {
          method: 'POST'
        });
      } catch (error) {
        // 后端 singleflight 超时会以 500 形式抛出"可重试"信号，这里吞掉后重试，
        // 不把可恢复的生成中态当成失败直接展示给用户。
      }
      // 只有 ACTIVE 的题才可作答：预生成题和中间态都不能展示。
      if (next && next.id && next.status === 'ACTIVE') {
        return next;
      }
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
    throw new Error('题目生成超时，请稍后刷新重试。');
  };
  // 新增结束

  const submit = () => {
    if (submitting) return;
    setSubmitting(true);

    return run(async () => {
    if (!currentAnswer || !question || !session) {
      throw new Error('请输入答案后再提交');
    }
    const requestId = requestIdRef.current || idForRequest();
    requestIdRef.current = requestId;

    // 从这里开始是新增的：这一趟提交的是"原题作答"还是"追问作答"。
    // 追问复用原题的 questionId，只能靠这个布尔值区分；后端据此把答案写进
    // interview_answer 的 is_follow_up=1 那一行，并且不推进主线状态机
    // （否则同一道题会被算成两道题，题序错乱）。
    const isFollowUp = Boolean(followUp);
    // 新增结束

    // 从这里开始是修改的：提交答案的路径改成后端真实存在的
    // POST /api/answers/{sessionId}/answers（AnswerController 类级是 /api/answers）。
    await request(`/api/answers/${session.sessionId}/answers`, {
      method: 'POST',
      body: JSON.stringify({
        questionId: question.id,
        requestId,
        answer: currentAnswer,
        // 新增：追问作答标记，由上面的 isFollowUp 决定。
        followUp: isFollowUp
      })
    });
    requestIdRef.current = null;
    setAnswer('');
    setTranscript('');

    // 先等后端把状态推进到位（评分 → 追问裁决 → 预生成下一题），再去取下一题。
    // 旧写法提交返回就立刻要下一题，此时后端还在评分，拿到的还是旧题。
    const state = await waitForNextQuestionState(session.sessionId);
    // 状态已经 ENDED：这场面试到此为止，直接进报告页。
    if (state.state === 'ENDED') {
      setFollowUp(null);
      setScreen('REPORT');
      await loadReport();
      return;
    }

    // 从这里开始是新增的：取下一题之前，先看这道题有没有派生追问。
    // 为什么顺序不能反：追问不推进主线状态机，如果先取下一题，界面上就会直接
    // 跳到新题，候选人再也没机会答追问；而且追问答案要挂在刚答完的原题上，
    // 原题一旦被下一题顶掉就找不回来了。
    // 为什么只在"原题作答"这一趟查：追问的追问不生成，答追问时再查一次纯属多余。
    if (!isFollowUp) {
      const pending = await request(
        `/api/interviews/${session.sessionId}/follow-up?questionId=${question.id}`
      );
      // 查到了追问：留在当前页，把追问当作新的一道"题"展示。
      // question 保持不变（追问的 questionId 就是原题的 questionId）。
      if (pending && pending.content) {
        setFollowUp(pending);
        setNotice('这道题还有一次追问，请继续作答。');
        return;
      }
    }
    setFollowUp(null);
    // 新增结束

    // 状态已推进：这时候才去取下一题（后端只有 POST .../questions）。
    // 从这里开始是修改的：改用统一的 takeQuestion——后端出下一题可能碰 singleflight
    // 可重试超时(500)或短暂未 ACTIVE，takeQuestion 会重取到就绪，替代原来的一次性 request。
    const next = await takeQuestion(session.sessionId);
    setQuestion(next);
    setNotice('答案已提交，已进入下一题。');
    // 修改结束
    }).finally(() => {
      setSubmitting(false);
    });
  };

  const finishInterview = () => run(async () => {
    if (!session) return;
    // 从这里开始是修改的：后端结束面试的映射是 /end，不是 /finish。
    await request(`/api/interviews/${session.sessionId}/end`, {
      method: 'POST',
      body: JSON.stringify({})
    });
    // 修改结束
    // 新增：结束面试时清掉未作答的追问，避免下次进答题页还挂着上一场的追问。
    setFollowUp(null);
    setScreen('REPORT');
    await loadReport();
  });

  // 从这里开始是修改的：录音从"MediaRecorder 录完就丢"的占位实现，
  // 换成真实的实时转写链路（NlsTranscriptionClient）：
  // 麦克风采集 → 降采样成 16k PCM → 推给阿里云 NLS → 把分片按业务协议上报后端，
  // 后端去重合并后落进 interview_answer.answer。界面上只用 NLS 回来的临时结果做预览。
  const toggleRecording = async () => {
    if (recording) {
      // 停止：关麦克风、关两条 WebSocket、清 ping 定时器都在 stop() 里做。
      nlsClient.current?.stop();
      nlsClient.current = null;
      setRecording(false);
      setNotice('语音输入已停止，转写文本已随答案上报。');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setError('当前浏览器不支持录音，请使用文本输入。');
      return;
    }
    if (!session) {
      setError('请先开始面试，再使用语音输入。');
      return;
    }
    try {
      const client = new NlsTranscriptionClient({
        sessionId: session.sessionId,
        // 取 /api/speech/token 时要带上登录态；JwtAuthenticationFilter 认的是 Authorization 头。
        jwt: localStorage.getItem('interview_token') || '',
        // 临时结果只做界面预览，最终文本由后端去重合并后统一取。
        onPreview: text => setTranscript(text),
        onError: message => setError(message)
      });
      nlsClient.current = client;
      await client.start();
      setRecording(true);
    } catch (caught) {
      nlsClient.current = null;
      setRecording(false);
      setError(caught.message || '语音输入启动失败，请改用文本输入。');
    }
  };
  // 修改结束

  if (!user) {
    return (
      <>
        <style>{styles}</style>
        <main className="auth-shell">
          <section className="auth-panel">
            <div className="brand-mark">AI</div>
            <p className="eyebrow">INTERVIEW WORKSPACE</p>
            <h1>码上面试</h1>
            <p className="muted">从简历解析到面试复盘，集中完成准备与练习。</p>
            <div className="form-stack">
              <label>用户名<input value={form.username} onChange={event => setForm({ ...form, username: event.target.value })} placeholder="输入用户名" /></label>
              {register && <label>显示名称<input value={form.displayName} onChange={event => setForm({ ...form, displayName: event.target.value })} placeholder="面试页面显示名称" /></label>}
              <label>密码<input type="password" value={form.password} onChange={event => setForm({ ...form, password: event.target.value })} placeholder="输入密码" onKeyDown={event => event.key === 'Enter' && auth()} /></label>
              {error && <p className="error">{error}</p>}
              <button className="primary-button" disabled={busy} onClick={auth}>{busy ? '处理中...' : register ? '注册并登录' : '登录'}</button>
              <button className="link-button" onClick={() => { setRegister(!register); setError(''); }}>{register ? '已有账号，返回登录' : '创建新账号'}</button>
            </div>
          </section>
        </main>
      </>
    );
  }

  return (
    <>
      <style>{styles}</style>
      <main className="app-shell">
        <header className="topbar">
          <div><span className="brand-mark small">AI</span><strong>码上面试</strong></div>
          <div className="user-actions"><span>{user.displayName || user.username}</span><button className="ghost-button" onClick={logout}>退出</button></div>
        </header>
        <div className="workspace">
          <aside className="sidebar">
            <p className="eyebrow">WORKFLOW</p>
            {[
              ['SETUP', '准备面试'],
              ['ANSWERING', '进行答题'],
              ['REPORT', '查看报告']
            ].map(([key, label], index) => (
              <button key={key} className={`step ${screen === key ? 'active' : ''}`} onClick={() => key !== 'ANSWERING' || session ? setScreen(key) : null}>
                <span>{String(index + 1).padStart(2, '0')}</span>{label}
              </button>
            ))}
            <div className="sidebar-note"><strong>练习提示</strong><p>先完成简历解析，系统会根据岗位生成面试题。</p></div>
          </aside>
          <section className="content">
            <div className="page-heading">
              <div><p className="eyebrow">AI INTERVIEW</p><h1>{screen === 'SETUP' ? '准备你的下一场面试' : screen === 'ANSWERING' ? '专注回答当前问题' : '复盘你的面试表现'}</h1></div>
              <div className="status-chip"><span className="status-dot" />系统已就绪</div>
            </div>
            {error && <div className="alert error">{error}</div>}
            {notice && <div className="alert success">{notice}</div>}

            {screen === 'SETUP' && (
              <div className="setup-grid">
                <section className="section-block">
                  <div className="section-title"><span className="number">01</span><div><h2>选择目标岗位</h2><p>确定本次练习的重点方向</p></div></div>
                  <div className="position-list">
                    {positions.map(item => <button key={item.id} className={`position-option ${position?.id === item.id ? 'selected' : ''}`} onClick={() => setPosition(item)}><span><strong>{item.name}</strong><small>{item.description || '为你生成匹配的面试题'}</small></span><span className="radio" /></button>)}
                  </div>
                </section>
                <section className="section-block">
                  <div className="section-title"><span className="number">02</span><div><h2>上传你的简历</h2><p>支持 PDF、DOC、DOCX，最大 10MB</p></div></div>
                  <label className="upload-box"><input type="file" accept=".pdf,.doc,.docx" onChange={event => uploadResume(event.target.files?.[0])} /><span className="upload-icon">↑</span><strong>{busy ? '正在解析...' : '点击选择简历文件'}</strong><small>{resume?.fileName || '上传后将自动提取简历内容'}</small></label>
                  {resume && <div className="resume-status"><span className="check">✓</span><div><strong>{resume.fileName}</strong><small>{resume.status === 'PARSED' ? '解析完成，可以开始面试' : '正在解析简历'}</small></div></div>}
                  <button className="primary-button wide" disabled={busy || !position || !resume || resume.status !== 'PARSED'} onClick={startInterview}>开始面试 <span>→</span></button>
                </section>
              </div>
            )}

            {screen === 'ANSWERING' && (
              <section className="interview-panel">
                {/* 从这里开始是修改的：追问优先展示。
                    追问是原题的补充考察，不作为新题插入 interview_question，
                    所以它不在 question 里，而是单独查出来放在 followUp 里。
                    followUp 非空时整块替换成追问内容，提交时带上 followUp=true。 */}
                <div className="question-meta"><span>{followUp ? 'FOLLOW-UP' : `QUESTION ${question?.index || 1}`}</span><span>{followUp ? (followUp.level || '追问') : (question?.category || position?.name || '综合面试')}</span></div>
                <h2>{followUp ? followUp.content : (question?.content || question?.question || '正在加载题目...')}</h2>
                {/* 修改结束 */}
                <textarea value={answer} onChange={event => setAnswer(event.target.value)} placeholder="输入你的回答，尽量结合具体项目和结果..." />
                {transcript && <div className="transcript"><small>语音转写</small><p>{transcript}</p></div>}
                <div className="answer-actions"><button className={`secondary-button ${recording ? 'recording' : ''}`} onClick={toggleRecording}>{recording ? '停止录音' : '开始录音'}</button><button className="primary-button" disabled={submitting} onClick={submit}>{submitting ? '提交中...' : '提交答案'} <span>→</span></button></div>
                <button className="finish-button" onClick={finishInterview}>结束面试并查看报告</button>
              </section>
            )}

            {screen === 'REPORT' && (
              <section className="report-panel">
                <div className="report-header"><div><p className="eyebrow">INTERVIEW REPORT</p><h2>本次面试报告</h2></div><button className="secondary-button" onClick={() => run(loadReport)}>刷新报告</button></div>
                {/* 从这里开始是修改的：后端 ReportView 只有 sessionId / status / markdown
                    三个字段，原来的 summary、content 在后端根本不存在。
                    status 不是 COMPLETED 时 markdown 为 null，此时显示后端给的状态，
                    而不是笼统一句"请稍后刷新"。 */}
                {report ? (
                  report.markdown
                    ? <article className="report-document" dangerouslySetInnerHTML={{ __html: markdownToHtml(report.markdown) }} />
                    : <div className="empty-state"><span className="loader" />{`报告状态：${report.status || 'GENERATING'}，正在生成。若较长时间无进展，请点「刷新报告」重试。`}</div>
                ) : <div className="empty-state"><span className="loader" />正在加载面试报告...</div>}
                {/* 修改结束 */}
              </section>
            )}
          </section>
        </div>
      </main>
    </>
  );
}

const styles = `
* { box-sizing: border-box; }
:root { font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #17212b; background: #f5f7f8; font-synthesis: none; }
body { margin: 0; min-width: 320px; background: #f5f7f8; } button, input, textarea { font: inherit; } button { cursor: pointer; }
.auth-shell { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: #eaf0ef; }
.auth-panel { width: min(430px, 100%); padding: 42px; background: #fff; border: 1px solid #d9e1e0; box-shadow: 0 18px 50px #18332a14; }
.brand-mark { display: grid; place-items: center; width: 48px; height: 48px; background: #1c6b5a; color: white; font-weight: 800; letter-spacing: 0; }
.brand-mark.small { width: 30px; height: 30px; font-size: 12px; display: inline-grid; margin-right: 10px; vertical-align: middle; }
.eyebrow { margin: 0 0 10px; color: #1c6b5a; font-size: 11px; font-weight: 800; letter-spacing: 1.5px; }
h1, h2, p { margin-top: 0; } h1 { margin-bottom: 10px; font-size: clamp(28px, 4vw, 42px); letter-spacing: 0; } h2 { margin-bottom: 6px; font-size: 22px; } .muted, .section-title p, .page-heading p { color: #718087; }
.form-stack { display: grid; gap: 16px; margin-top: 30px; } label { display: grid; gap: 7px; color: #405057; font-size: 13px; font-weight: 700; } input, textarea { width: 100%; border: 1px solid #ccd8d5; background: #fbfcfc; color: #17212b; outline: 0; } input { height: 46px; padding: 0 13px; } textarea { min-height: 220px; padding: 16px; resize: vertical; line-height: 1.7; } input:focus, textarea:focus { border-color: #1c6b5a; box-shadow: 0 0 0 3px #1c6b5a18; }
.primary-button, .secondary-button, .ghost-button, .link-button { border: 0; transition: .2s ease; } .primary-button { min-height: 46px; padding: 0 18px; background: #1c6b5a; color: #fff; font-weight: 800; } .primary-button:hover { background: #155344; } .primary-button:disabled { opacity: .5; cursor: not-allowed; } .primary-button span { margin-left: 12px; } .wide { width: 100%; margin-top: 20px; } .secondary-button { min-height: 44px; padding: 0 16px; border: 1px solid #b9cbc7; background: #fff; color: #1c6b5a; font-weight: 800; } .secondary-button:hover { background: #eef6f3; } .ghost-button, .link-button { background: transparent; color: #1c6b5a; font-weight: 700; } .link-button { padding: 5px; }
.error { color: #b63c35; } .alert { margin-bottom: 22px; padding: 13px 16px; font-size: 14px; } .alert.error { background: #fff0ee; border-left: 3px solid #cf544b; } .alert.success { background: #edf8f2; border-left: 3px solid #2c936b; color: #236d51; }
.app-shell { min-height: 100vh; } .topbar { height: 68px; display: flex; align-items: center; justify-content: space-between; padding: 0 5vw; background: #fff; border-bottom: 1px solid #dae4e1; } .topbar strong { font-size: 16px; } .user-actions { display: flex; align-items: center; gap: 18px; color: #69777b; font-size: 14px; }
.workspace { display: grid; grid-template-columns: 240px minmax(0, 1fr); min-height: calc(100vh - 68px); max-width: 1440px; margin: auto; } .sidebar { padding: 42px 24px; background: #f0f5f3; border-right: 1px solid #dae4e1; } .step { width: 100%; display: flex; align-items: center; gap: 13px; padding: 14px 10px; border: 0; border-left: 2px solid transparent; background: transparent; color: #6c7b7d; text-align: left; font-weight: 700; } .step span { color: #9aaba8; font-size: 12px; } .step.active { border-left-color: #1c6b5a; background: #fff; color: #1c6b5a; } .sidebar-note { margin-top: 80px; padding: 16px; background: #dfece8; color: #245b4e; font-size: 12px; line-height: 1.6; } .sidebar-note p { margin: 7px 0 0; }
.content { width: min(100%, 1050px); padding: 55px 6vw 80px; } .page-heading { display: flex; justify-content: space-between; align-items: start; gap: 24px; margin-bottom: 42px; } .status-chip { display: flex; align-items: center; gap: 8px; padding: 8px 11px; color: #377560; background: #e7f3ed; font-size: 12px; font-weight: 700; } .status-dot { width: 7px; height: 7px; border-radius: 50%; background: #38a878; }
.setup-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; } .section-block, .interview-panel, .report-panel { background: #fff; border: 1px solid #d9e3e0; padding: 30px; } .section-title { display: flex; gap: 15px; align-items: flex-start; margin-bottom: 25px; } .number { color: #1c6b5a; font-size: 12px; font-weight: 900; } .position-list { display: grid; gap: 10px; } .position-option { display: flex; justify-content: space-between; gap: 14px; width: 100%; padding: 15px; border: 1px solid #dce6e3; background: #fff; color: #17212b; text-align: left; } .position-option.selected { border-color: #1c6b5a; background: #f1f8f5; } .position-option strong, .position-option small { display: block; } .position-option small, .resume-status small { margin-top: 5px; color: #7c8b8e; font-size: 12px; } .radio { width: 17px; height: 17px; flex: 0 0 auto; border: 2px solid #b6c8c4; border-radius: 50%; } .selected .radio { border: 5px solid #1c6b5a; }
.upload-box { min-height: 190px; display: grid; align-content: center; justify-items: center; gap: 10px; padding: 24px; border: 1px dashed #9ebdb5; background: #f7fbfa; text-align: center; cursor: pointer; } .upload-box input { display: none; } .upload-icon { display: grid; place-items: center; width: 36px; height: 36px; background: #dceee8; color: #1c6b5a; font-size: 22px; } .upload-box small { color: #81908e; font-weight: 400; } .resume-status { display: flex; align-items: center; gap: 12px; margin-top: 15px; padding: 13px; background: #edf7f2; } .check { color: #16815a; font-size: 20px; } .resume-status strong, .resume-status small { display: block; }
.question-meta { display: flex; justify-content: space-between; color: #1c6b5a; font-size: 12px; font-weight: 800; letter-spacing: 1px; } .interview-panel h2 { max-width: 760px; margin: 38px 0 24px; font-size: clamp(24px, 4vw, 36px); line-height: 1.4; } .answer-actions { display: flex; justify-content: space-between; gap: 12px; margin-top: 16px; } .recording { color: #b63c35; border-color: #e5aaa5; } .finish-button { display: block; margin: 30px auto 0; padding: 4px; border: 0; background: transparent; color: #7c8b8e; font-size: 13px; } .transcript { margin-top: 12px; padding: 12px 15px; background: #f4f7f7; color: #526166; } .transcript p { margin: 4px 0 0; }
.report-header { display: flex; align-items: start; justify-content: space-between; gap: 16px; border-bottom: 1px solid #e0e8e6; padding-bottom: 25px; margin-bottom: 25px; } .report-document { line-height: 1.8; color: #35454a; } .report-document h1, .report-document h2, .report-document h3 { color: #17212b; margin-top: 28px; } .report-document p { margin-bottom: 14px; } .report-document li { margin: 5px 0; } .report-document code { padding: 2px 5px; background: #edf2f1; } .empty-state { display: grid; place-items: center; gap: 15px; min-height: 250px; color: #718087; } .loader { width: 22px; height: 22px; border: 3px solid #d5e1de; border-top-color: #1c6b5a; border-radius: 50%; animation: spin .8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 800px) { .workspace { display: block; } .sidebar { display: flex; gap: 8px; padding: 12px 18px; overflow-x: auto; border-right: 0; border-bottom: 1px solid #dae4e1; } .sidebar .eyebrow, .sidebar-note { display: none; } .step { min-width: 120px; padding: 10px; border-left: 0; border-bottom: 2px solid transparent; } .step.active { border-bottom-color: #1c6b5a; } .content { padding: 35px 18px 55px; } .setup-grid { grid-template-columns: 1fr; } .page-heading { display: block; margin-bottom: 28px; } .status-chip { width: fit-content; margin-top: 18px; } .section-block, .interview-panel, .report-panel { padding: 22px; } }
@media (max-width: 480px) { .auth-panel { padding: 28px 22px; } .topbar { padding: 0 18px; } .user-actions span { display: none; } .answer-actions { flex-direction: column-reverse; } .answer-actions button { width: 100%; } }
`;

createRoot(document.getElementById('root')).render(<App />);
