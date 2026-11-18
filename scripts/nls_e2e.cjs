// 端到端自检：用 Chrome 的假麦克风（注入 nls_src 合成语音）跑一遍真实浏览器链路，
// 证明「点开始录音 → 说话 → 输入框出字」在浏览器环境里成立。
const puppeteer = require('puppeteer-core');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const AUDIO = 'C:\\tmp\\fake_mic.wav';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      `--use-file-for-fake-audio-capture=${AUDIO}`,
      '--autoplay-policy=no-user-gesture-required',
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--mute-audio'
    ]
  });
  const page = await browser.newPage();
  const consoleLines = [];
  page.on('console', m => consoleLines.push(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', e => consoleLines.push(`[pageerror] ${e.message}`));

  await page.goto('http://localhost:5173/nls-test.html', { waitUntil: 'networkidle2', timeout: 30000 });
  await page.evaluate(() => window.__start());

  // 语音 8.3s，留足推流 + 识别尾巴
  await new Promise(r => setTimeout(r, 13000));

  const snapshot = await page.evaluate(() => ({
    state: window.__state, err: window.__err, text: window.__text,
    started: window.__started === true, level: window.__level || 0
  }));

  console.log('--- 页面事件 ---');
  console.log((await page.evaluate(() => window.__log)).join('\n'));
  console.log('--- 浏览器 console ---');
  console.log(consoleLines.slice(0, 30).join('\n'));
  console.log('--- 结果 ---');
  console.log('state  :', snapshot.state);
  console.log('started:', snapshot.started);
  console.log('level  :', snapshot.level);
  console.log('error  :', snapshot.err || '(无)');
  console.log('TEXT   :', snapshot.text || '(空)');

  await browser.close();
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
