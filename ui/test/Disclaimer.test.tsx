import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from 'vitest-browser-react';
import Disclaimer from '../src/pages/Disclaimer';
import { installFetchMock } from './mockFetch';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const article = (body: string, status = 200) => ({
  method: 'GET',
  match: /\/disclaimer$/,
  respond: () => new Response(body, { status, headers: { 'Content-Type': 'text/html' } }),
});

describe('Disclaimer', () => {
  it('renders the build-generated article from the extension REST API', async () => {
    const fetchMock = installFetchMock([article('<p>Use this extension in production at your own risk.</p>')]);
    render(<Disclaimer />);

    await vi.waitFor(() => expect(document.querySelector('article.markdown-body')).not.toBeNull());
    expect(document.body.textContent).toContain('at your own risk');
    expect(String(fetchMock.mock.calls[0][0])).toBe('/polarion/hotreload/rest/internal/disclaimer');
  });

  it('points at the online source when the build generated no disclaimer', async () => {
    installFetchMock([article('')]);
    render(<Disclaimer />);

    await vi.waitFor(() => expect(document.body.textContent).toContain('No disclaimer has been generated'));
    const link = document.querySelector<HTMLAnchorElement>('a[target="_blank"]');
    expect(link?.href).toContain('/blob/main/DISCLAIMER.md');
  });

  it('treats a blank article as missing', async () => {
    installFetchMock([article('   ')]);
    render(<Disclaimer />);

    await vi.waitFor(() => expect(document.body.textContent).toContain('No disclaimer has been generated'));
  });

  it('treats a non-OK response as missing', async () => {
    installFetchMock([article('<p>ignored</p>', 404)]);
    render(<Disclaimer />);

    await vi.waitFor(() => expect(document.body.textContent).toContain('No disclaimer has been generated'));
  });

  it('survives a failing request', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('network down'))),
    );
    render(<Disclaimer />);

    await vi.waitFor(() => expect(document.body.textContent).toContain('No disclaimer has been generated'));
  });

  it.each([
    ['an answer', (settle: Settle) => settle.resolve(new Response('<p>late</p>', { status: 200 }))],
    ['a failure', (settle: Settle) => settle.reject(new Error('late'))],
  ])('ignores %s that arrives after the page closed', async (_, finish) => {
    const settle = {} as Settle;
    const pending = new Promise<Response>((resolve, reject) => Object.assign(settle, { resolve, reject }));
    const fetchMock = vi.fn(() => pending);
    vi.stubGlobal('fetch', fetchMock);
    render(<Disclaimer />);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());

    cleanup();
    finish(settle);
    await pending.catch(() => undefined);

    expect(document.body.textContent).not.toContain('late');
    expect(document.body.textContent).not.toContain('No disclaimer has been generated');
  });
});

interface Settle {
  resolve: (response: Response) => void;
  reject: (error: Error) => void;
}
