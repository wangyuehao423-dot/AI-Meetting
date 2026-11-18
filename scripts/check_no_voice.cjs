// 删掉语音后的回归检查：走到答题页，确认没有录音相关 UI、没有 JS 报错、输入框可用
const puppeteer = require('puppeteer-core');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const RESUME = 'E:\\important\\AI Meetting1\\resume-test.pdf';
const user = 'clean' + Date.now().toString().slice(-7);
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--mute-audio']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 1000 });
  const errs = [];
  page.on('pageerror', e => errs.push('[pageerror] ' + e.message));
  page.on('console', m => { if (m.type() === 'error' && !m.text().includes('favicon')) errs.push('[console] ' + m.text()); });

  const clickText = async t => page.evaluate(x => {
    const el = [...document.querySelectorAll('button')].find(b => b.textContent.trim().startsWith(x));
    if (el) { el.click(); return true; } return false;
  }, t);

  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle2' });
  await clickText('创建新账号');
  await page.waitForSelector('input');
  const inputs = await page.$$('input');
  await inputs[0].type(user);
  await inputs[1].type('Clean Tester');
  await inputs[2].type('test123456');
  await clickText('注册并登录');
  await page.waitForFunction(() => document.body.innerText.includes('选择目标岗位'), { timeout: 30000 });
  await page.waitForFunction(() => document.querySelectorAll('.position-option').length > 0, { timeout: 30000 });
  await page.evaluate(() => document.querySelector('.position-option').click());
  await (await page.$('input[type=file]')).uploadFile(RESUME);
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent.includes('开始面试') && !b.disabled), { timeout: 120000 });
  await clickText('开始面试');
  await page.waitForSelector('textarea', { timeout: 60000 });
  await sleep(1200);

  const ui = await page.evaluate(() => ({
    bodyHasVoiceWord: /录音|语音|转写|麦克风/.test(document.body.innerText),
    buttons: [...document.querySelectorAll('.answer-actions button')].map(b => b.textContent.trim()),
    textareaReadOnly: document.querySelector('textarea')?.readOnly,
    placeholder: document.querySelector('textarea')?.placeholder,
    hasTranscript: !!document.querySelector('.transcript, .voice-meter, .mic-line')
  }));

  console.log('页面是否还有"录音/语音/转写/麦克风"字样:', ui.bodyHasVoiceWord);
  console.log('答题区按钮:', JSON.stringify(ui.buttons));
  console.log('输入框只读:', ui.textareaReadOnly, '| placeholder:', ui.placeholder);
  console.log('残留语音组件:', ui.hasTranscript);
  console.log('JS 报错:', errs.length ? errs.join('\n') : '(无)');

  // 输入框能正常打字并提交
  await page.click('textarea');
  await page.keyboard.type('这是一段纯文字的作答，用来确认提交通路正常。');
  const typed = await page.evaluate(() => document.querySelector('textarea').value);
  console.log('输入框打字:', JSON.stringify(typed));

  await page.screenshot({ path: 'C:\\tmp\\after_remove.png' });
  await browser.close();
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
