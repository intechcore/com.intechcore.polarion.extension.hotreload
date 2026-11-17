import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from 'vitest-browser-react';
import { page } from 'vitest/browser';
import App from '../src/App';
import type { RescanReport } from '../src/pages/Rescan';
import { installFetchMock } from './mockFetch';
import { settleBeforeCapture, settleLayout } from './visualHelpers';

// Docker-only full-page snapshots of the Rescan Extensions page (hivemodule.xml, extender `rescan`).
// The reports mirror what RedeployService writes, so the lines have a realistic length and shape.
//
// Every case sizes the viewport to the page before its capture, so the order of the cases does not
// matter for the references.

const NO_CHANGES: RescanReport = {
  applied: false,
  changes: [],
  log: ['nothing changed'],
  restartRequired: [],
  errors: [],
};

const ONE_CHANGE: RescanReport = {
  applied: true,
  changes: [
    'UPDATE ch.sbb.polarion.extension.pdf-exporter (ch.sbb.polarion.extension.pdf-exporter-9.1.0-SNAPSHOT.jar)',
  ],
  log: [
    'context /polarion/pdf-exporter removed',
    'context /polarion/pdf-exporter-app removed',
    'ch.sbb.polarion.extension.pdf-exporter: updated in place',
    'refresh finished',
    'context /polarion/pdf-exporter added',
    'context /polarion/pdf-exporter-app added',
    'administration pages: removed [pdf-exporter/about, pdf-exporter/settings]',
    'administration pages: added [pdf-exporter/about, pdf-exporter/settings]',
  ],
  restartRequired: [],
  errors: [],
};

const SEVERAL_CHANGES: RescanReport = {
  applied: true,
  changes: [
    'INSTALL ch.sbb.polarion.extension.diff-tool (ch.sbb.polarion.extension.diff-tool-6.2.0.jar)',
    'UPDATE ch.sbb.polarion.extension.generic (ch.sbb.polarion.extension.generic-16.4.0.jar)',
    'REPLACE ch.sbb.polarion.extension.excel-importer (ch.sbb.polarion.extension.excel-importer-4.1.0.jar)',
    'UNINSTALL ch.sbb.polarion.extension.mailworkflow',
  ],
  log: [
    'dependent bundles refreshed too: [ch.sbb.polarion.extension.pdf-exporter, ch.sbb.polarion.extension.docx-exporter]',
    'context /polarion/excel-importer removed',
    'context /polarion/mailworkflow removed',
    'workflow functions: removed [SendMailFunction]',
    'ch.sbb.polarion.extension.mailworkflow: uninstalled',
    'ch.sbb.polarion.extension.generic: updated in place',
    'ch.sbb.polarion.extension.excel-importer: replaced by ch.sbb.polarion.extension.excel-importer-4.1.0.jar',
    'ch.sbb.polarion.extension.diff-tool: installed',
    'refresh finished',
    'context /polarion/diff-tool added',
    'context /polarion/excel-importer added',
    "workflow function 'CompareDocuments': added",
    'WARNING ch.sbb.polarion.extension.diff-tool: plugin.xml extension point com.polarion.example.point is not refreshed',
    'admin-menu-organizer contexts restarted to re-apply the menu order',
  ],
  restartRequired: [
    'ch.sbb.polarion.extension.diff-tool: Guice-Modules header (ch.sbb.polarion.extension.diff_tool.DiffToolModule): the Guice injector is built once at startup',
  ],
  errors: ['IllegalStateException: context /polarion/docx-exporter NOT added, see the Polarion log'],
};

const origUrl = window.location.pathname + window.location.search;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', origUrl);
});

/** Opens the page, presses `button` when given, and waits until the report or the given text shows. */
async function open(report: RescanReport | null, button?: string): Promise<void> {
  installFetchMock(
    report
      ? [
          { method: 'GET', match: /\/changes$/, json: report },
          { method: 'POST', match: /\/rescan$/, json: report },
        ]
      : [],
  );
  window.history.replaceState({}, '', '?feature=rescan&embedded=true');
  render(<App />);

  await vi.waitFor(() => expect(document.querySelector('.rescan-buttons button')).not.toBeNull());
  if (button) {
    const target = Array.from(document.querySelectorAll('button')).find((b) => b.textContent === button);
    target!.click();
    await vi.waitFor(() => expect(document.querySelector('.rescan-report')).not.toBeNull());
  }
}

async function capture(name: string): Promise<void> {
  const app = document.querySelector('.app') as HTMLElement;
  await settleLayout();
  await page.viewport(1280, Math.ceil(app.scrollHeight) + 40);
  await settleBeforeCapture();
  await expect(page.elementLocator(app)).toMatchScreenshot(name);
}

describe.skipIf(!__PIXEL_REFERENCES__)('Rescan page visual', () => {
  it('before the first request', async () => {
    await open(null);
    await capture('rescan-initial');
  });

  it('no changes', async () => {
    await open(NO_CHANGES, 'Show changes');
    await capture('rescan-no-changes');
  });

  it('one change applied', async () => {
    await open(ONE_CHANGE, 'Rescan and apply');
    await capture('rescan-one-change');
  });

  it('several changes applied, with a restart and an error', async () => {
    await open(SEVERAL_CHANGES, 'Rescan and apply');
    await capture('rescan-several-changes');
  });
});
