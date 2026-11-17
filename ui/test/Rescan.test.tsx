import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from 'vitest-browser-react';
import Rescan, { type RescanReport } from '../src/pages/Rescan';
import { installFetchMock, jsonResponse } from './mockFetch';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const report = (overrides: Partial<RescanReport> = {}): RescanReport => ({
  applied: false,
  changes: [],
  log: [],
  restartRequired: [],
  errors: [],
  ...overrides,
});

function button(label: string): HTMLButtonElement {
  const found = Array.from(document.querySelectorAll('button')).find((b) => b.textContent === label);
  if (!found) throw new Error(`no button ${label}`);
  return found;
}

/** Waits for the first render, then clicks the button. */
async function click(label: string): Promise<void> {
  await vi.waitFor(() => button(label));
  button(label).click();
}

function sectionItems(title: string): string[] {
  const section = Array.from(document.querySelectorAll('.rescan-section')).find(
    (s) => s.querySelector('h3')?.textContent === title,
  );
  return section ? Array.from(section.querySelectorAll('li')).map((li) => li.textContent ?? '') : [];
}

describe('Rescan', () => {
  it('lists the changes without applying them', async () => {
    const fetchMock = installFetchMock([
      { method: 'GET', match: /\/changes$/, json: report({ changes: ['INSTALL my.ext (my.ext-1.0.jar)'] }) },
    ]);
    render(<Rescan />);

    await click('Show changes');

    await vi.waitFor(() => expect(sectionItems('Changes')).toEqual(['INSTALL my.ext (my.ext-1.0.jar)']));
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/polarion\/hotreload\/rest\/(internal|api)\/changes$/);
    expect(document.querySelector('.rescan-error')).toBeNull();
  });

  it('shows "none" when nothing changed', async () => {
    installFetchMock([{ method: 'GET', match: /\/changes$/, json: report({ log: ['nothing changed'] }) }]);
    render(<Rescan />);

    await click('Show changes');

    await vi.waitFor(() => expect(sectionItems('Changes')).toEqual(['none']));
    expect(sectionItems('Log')).toEqual(['nothing changed']);
  });

  it('applies the changes and shows every part of the report', async () => {
    installFetchMock([
      {
        method: 'POST',
        match: /\/rescan$/,
        json: report({
          applied: true,
          changes: ['UPDATE my.ext (my.ext-1.0.jar)'],
          log: ['my.ext: updated in place', 'refresh finished', 'refresh finished'],
          restartRequired: ['my.ext: Guice-Modules header'],
          errors: ['IllegalStateException: boom'],
        }),
      },
    ]);
    render(<Rescan />);

    await click('Rescan and apply');

    await vi.waitFor(() => expect(sectionItems('Errors')).toEqual(['IllegalStateException: boom']));
    expect(sectionItems('Log')).toEqual(['my.ext: updated in place', 'refresh finished', 'refresh finished']);
    expect(sectionItems('Restart required')).toEqual(['my.ext: Guice-Modules header']);
    expect(document.querySelectorAll('.rescan-error')).toHaveLength(2);
  });

  it('disables the buttons while a request runs', async () => {
    let finish: (response: Response) => void = () => undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => (finish = resolve))),
    );
    render(<Rescan />);

    await click('Rescan and apply');

    await vi.waitFor(() => expect(document.body.textContent).toContain('Working...'));
    expect(button('Show changes').disabled).toBe(true);
    finish(jsonResponse(report()));
    await vi.waitFor(() => expect(button('Show changes').disabled).toBe(false));
  });

  it('shows the error message of a rejected request', async () => {
    installFetchMock([
      {
        method: 'GET',
        match: /\/changes$/,
        status: 403,
        json: { errorMessage: 'Only global administrators can redeploy extensions' },
      },
    ]);
    render(<Rescan />);

    await click('Show changes');

    await vi.waitFor(() =>
      expect(document.querySelector('.alert-error')?.textContent).toBe(
        'HTTP 403: Only global administrators can redeploy extensions',
      ),
    );
  });

  it.each([
    ['a plain text body', 'Server error', 'HTTP 500: Server error'],
    ['JSON without a message', '{}', 'HTTP 500: {}'],
  ])('shows %s of a failed request as it is', async (_, body, expected) => {
    installFetchMock([{ method: 'GET', match: /\/changes$/, respond: () => new Response(body, { status: 500 }) }]);
    render(<Rescan />);

    await click('Show changes');

    await vi.waitFor(() => expect(document.querySelector('.alert-error')?.textContent).toBe(expected));
  });

  it('reports an unreadable report', async () => {
    installFetchMock([{ method: 'GET', match: /\/changes$/, respond: () => new Response('<html>', { status: 200 }) }]);
    render(<Rescan />);

    await click('Show changes');

    await vi.waitFor(() =>
      expect(document.querySelector('.alert-error')?.textContent).toBe(
        'The server answered with an unreadable report.',
      ),
    );
  });
});
