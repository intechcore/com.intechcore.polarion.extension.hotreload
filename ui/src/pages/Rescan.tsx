import { type ReactNode, useState } from 'react';
import { PageLayout } from '@sbb-polarion/react-sbb-polarion';
import useRemote from '../services/useRemote';

/** The report of `GET /changes` and `POST /rescan`, see `RescanReport.java`. */
export interface RescanReport {
  applied: boolean;
  changes: string[];
  log: string[];
  restartRequired: string[];
  errors: string[];
}

type State =
  { kind: 'idle' } | { kind: 'working' } | { kind: 'failed'; message: string } | { kind: 'done'; report: RescanReport };

function Section({ title, items, error = false }: Readonly<{ title: string; items: string[]; error?: boolean }>) {
  if (items.length === 0) return null;
  return (
    <section className={error ? 'rescan-section rescan-error' : 'rescan-section'}>
      <h3>{title}</h3>
      <ul>
        {items.map((item, index) => (
          // The same line can appear twice in a log, so the index keeps the keys unique.
          <li key={`${index}-${item}`}>{item}</li>
        ))}
      </ul>
    </section>
  );
}

async function failureMessage(response: Response): Promise<string> {
  const text = await response.text();
  try {
    // The generic exception mappers send `message`; the network fallback of useRemote sends `errorMessage`.
    const json = JSON.parse(text) as { message?: string; errorMessage?: string };
    const message = json.message ?? json.errorMessage;
    if (message) return `HTTP ${response.status}: ${message}`;
  } catch {
    // Not JSON: show the body as it is.
  }
  return `HTTP ${response.status}: ${text}`;
}

/**
 * Rescan: compares the jars in the extensions folder with the running bundles and applies the
 * differences without a restart.
 */
export default function Rescan() {
  const { sendRequest } = useRemote();
  const [state, setState] = useState<State>({ kind: 'idle' });

  const run = async (method: 'GET' | 'POST', url: string) => {
    setState({ kind: 'working' });
    const response = await sendRequest({ method, url });
    if (!response.ok) {
      setState({ kind: 'failed', message: await failureMessage(response) });
      return;
    }
    try {
      setState({ kind: 'done', report: (await response.json()) as RescanReport });
    } catch {
      setState({ kind: 'failed', message: 'The server answered with an unreadable report.' });
    }
  };

  const working = state.kind === 'working';
  let result: ReactNode = null;
  if (working) {
    result = <p>Working...</p>;
  } else if (state.kind === 'failed') {
    result = <div className="alert alert-error">{state.message}</div>;
  } else if (state.kind === 'done') {
    const { report } = state;
    result = (
      <div className="rescan-report">
        <Section title="Changes" items={report.changes.length > 0 ? report.changes : ['none']} />
        <Section title="Log" items={report.log} />
        <Section title="Restart required" items={report.restartRequired} error />
        <Section title="Errors" items={report.errors} error />
      </div>
    );
  }

  return (
    <PageLayout title="Rescan Extensions">
      <p className="rescan-note">
        Compares the jars in the extensions folder with the running bundles. Rescan installs, updates or uninstalls the
        changed bundles and swaps their web applications and contributions without a restart. For development servers
        only: every rescan can leak the old class loaders.
      </p>
      <div className="rescan-buttons">
        <button type="button" className="sbb-btn--control" disabled={working} onClick={() => run('GET', '/changes')}>
          Show changes
        </button>
        <button type="button" className="sbb-btn--control" disabled={working} onClick={() => run('POST', '/rescan')}>
          Rescan and apply
        </button>
      </div>
      {result}
    </PageLayout>
  );
}
