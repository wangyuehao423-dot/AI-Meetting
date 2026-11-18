// 真实业务页面端到端：注册 -> 选岗位 -> 传简历 -> 开始面试 -> 点开始录音 -> 读输入框
const puppeteer = require('puppeteer-core');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const AUDIO = 'C:\\tmp\\fake_mic.wav';
const RESUME = 'E:\\important\\AI Meetting1\\resume-test.pdf';
const user = 'nls' + Date.now().toString().slice(-8);

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      `--use-file-for-fake-audio-capture=${AUDIO}`,
      '--autoplay-policy=no-user-gesture-required',
      '--no-sandbox', '--disable-dev-shm-usage', '--mute-audio'
    ]
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  const errs = [];
  page.on('pageerror', e => errs.push('[pageerror] ' + e.message));

  const clickText = async (text) => {
    const ok = await page.evaluate(t => {
      const el = [...document.querySelectorAll('button')].find(b => b.textContent.trim().startsWith(t));
      if (el) { el.click(); return true; }
      return false;
    }, text);
    if (!ok) console.log(`[!] 没找到按钮: ${text}`);
    return ok;
  };

  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle2' });

  // 1 注册
  await clickText('创建新账号');
  await page.waitForSelector('input', { timeout: 10000 });
  const inputs = await page.$$('input');
  await inputs[0].type(user);
  await inputs[1].type('NLS Tester');       // displayName（注册态才有）
  await inputs[2].type('test123456');
  await clickText('注册并登录');
  console.log('[1] 注册提交:', user);

  // 2 等工作台出现
  await page.waitForFunction(() => document.body.innerText.includes('选择目标岗位'), { timeout: 30000 });
  console.log('[2] 已进入工作台');

  // 3 选岗位
  await page.waitForFunction(() => document.querySelectorAll('.position-option').length > 0, { timeout: 30000 });
  await page.evaluate(() => document.querySelector('.position-option').click());
  console.log('[3] 已选岗位');

  // 4 传简历
  const fileInput = await page.$('input[type=file]');
  await fileInput.uploadFile(RESUME);
  console.log('[4] 简历已上传，等待解析…');

  // 5 等解析完成（按钮可点）
  await page.waitForFunction(
    () => [...document.querySelectorAll('button')].some(b => b.textContent.includes('开始面试') && !b.disabled),
    { timeout: 120000 }
  );
  console.log('[5] 简历解析完成');

  await clickText('开始面试');
  await page.waitForSelector('textarea', { timeout: 60000 });
  console.log('[6] 已进入答题页');

  // 7 记录录音前状态
  const before = await page.evaluate(() => ({
    notice: document.querySelector('.alert')?.textContent || '',
    btn: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).filter(t => /录音|连接/.test(t))
  }));
  console.log('[7] 录音前按钮:', JSON.stringify(before.btn), '提示:', JSON.stringify(before.notice));

  // 8 点开始录音
  await clickText('开始录音');
  await sleep(3000);
  const mid = await page.evaluate(() => ({
    recording: !!document.querySelector('.voice-meter'),
    hint: document.querySelector('.transcript small')?.textContent || '',
    mic: document.querySelector('.mic-line')?.textContent || '',
    devOptions: document.querySelectorAll('.mic-line select option').length,
    len: document.querySelector('textarea')?.value.length || 0
  }));
  console.log('[8] 3s 后:', JSON.stringify(mid));

  // 9 等语音跑完
  await sleep(12000);
  const after = await page.evaluate(() => ({
    text: document.querySelector('textarea')?.value || '',
    notice: document.querySelector('.alert')?.textContent || '',
    hint: document.querySelector('.transcript small')?.textContent || ''
  }));
  console.log('[9] 12s 后 textarea 长度:', after.text.length);
  console.log('    textarea 内容:', after.text.slice(0, 160) || '(空)');
  console.log('    状态提示:', after.hint);
  console.log('    全局提示:', after.notice);

  // 10 停止录音，看统计与回放链接有没有出来
  await clickText('停止录音');
  await sleep(2500);
  const stopped = await page.evaluate(() => ({
    stats: [...document.querySelectorAll('.transcript small')].map(e => e.textContent.trim()).join(' | '),
    hasWav: !!document.querySelector('a.wav-link'),
    wavHref: document.querySelector('a.wav-link')?.href?.slice(0, 22) || ''
  }));
  console.log('[10] 停止后统计:', stopped.stats);
  console.log('     下载链接:', stopped.hasWav ? stopped.wavHref + '…' : '(无)');
  console.log('[err]', errs.length ? errs.join('\n') : '(无)');

  await page.screenshot({ path: 'C:\\tmp\\nls_page.png' });
  console.log('截图: C:\\tmp\\nls_page.png');
  await browser.close();
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
