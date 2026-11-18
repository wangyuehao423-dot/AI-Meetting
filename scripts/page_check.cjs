// 检查主页面能否正常渲染（有没有白屏 / JS 报错 / 资源 404）
const puppeteer = require('puppeteer-core');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--mute-audio']
  });
  const page = await browser.newPage();
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push('[console] ' + m.text()); });
  page.on('pageerror', e => errors.push('[pageerror] ' + e.message));
  page.on('requestfailed', r => errors.push('[reqfail] ' + r.url() + ' ' + (r.failure()?.errorText || '')));
  page.on('response', r => { if (r.status() >= 400) errors.push(`[${r.status()}] ${r.url()}`); });

  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise(r => setTimeout(r, 2500));

  const info = await page.evaluate(() => ({
    rootHtmlLen: (document.getElementById('root')?.innerHTML || '').length,
    bodyText: document.body.innerText.slice(0, 220),
    hasMain: !!document.querySelector('main'),
    hasTextarea: !!document.querySelector('textarea')
  }));

  console.log('root 内容长度:', info.rootHtmlLen);
  console.log('有 main 元素 :', info.hasMain);
  console.log('有 textarea  :', info.hasTextarea);
  console.log('页面文字片段 :', JSON.stringify(info.bodyText));
  console.log('--- 问题 ---');
  console.log(errors.length ? errors.slice(0, 15).join('\n') : '(无)');
  await browser.close();
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
