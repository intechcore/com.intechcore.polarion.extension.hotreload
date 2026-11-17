import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from 'vitest-browser-react';
import { page } from 'vitest/browser';
import App from '../src/App';
import { installFetchMock } from './mockFetch';
import { settleBeforeCapture, settleLayout } from './visualHelpers';

// Docker-only full-page snapshot of the Disclaimer page, one of this extension's administration-menu
// entries (hivemodule.xml, extender `disclaimer`). It renders the build-generated DISCLAIMER article,
// so the article markup is mocked here rather than read from the jar.
//
// ARTICLE is what the build makes of DISCLAIMER.md (webapp/.../html/disclaimer.html). The shape - the
// lead paragraph, the section bands, a list - is the point: this reference exists to
// catch a styling change in the help article, and a one-paragraph stand-in would show almost none of
// it. Edit it when DISCLAIMER.md gains or loses a kind of element, not on every wording change.

const ARTICLE = [
  '<p><strong>Use this extension in production at your own risk.</strong> Test it on a non-production ',
  'server first.</p>',
  '<h2 id="status">Status</h2>',
  '<p>The extension is in an early stage of development. It does not change the behavior of Polarion ',
  'yet.</p>',
  '<h2 id="what-to-check">What to check</h2>',
  '<ul><li>Install the extension on a test server and restart Polarion.</li>',
  '<li>Open the About page and compare the version with the jar you installed.</li></ul>',
].join('');

const origUrl = window.location.pathname + window.location.search;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', origUrl);
});

describe.skipIf(!__PIXEL_REFERENCES__)('Disclaimer page visual', () => {
  it('loaded (build-generated article)', async () => {
    installFetchMock([
      {
        method: 'GET',
        match: /\/disclaimer$/,
        respond: () => new Response(ARTICLE, { status: 200, headers: { 'Content-Type': 'text/html' } }),
      },
    ]);
    window.history.replaceState({}, '', '?feature=disclaimer&embedded=true');
    render(<App />);

    await vi.waitFor(() => expect(document.querySelector('article.markdown-body')).not.toBeNull());
    const app = document.querySelector('.app') as HTMLElement;
    await settleLayout();
    await page.viewport(1280, Math.ceil(app.scrollHeight) + 40);
    await settleBeforeCapture();
    await expect(page.elementLocator(app)).toMatchScreenshot('disclaimer-loaded');
  });
});
