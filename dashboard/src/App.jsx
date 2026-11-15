import { useEffect, useRef, useState } from "react";

// Demo users, and questions that show what the guard does for each of them.
const PAY = { invoice: 42, amount: 49500 }; // an ACCT invoice above 10,000 MAD in the seed data
const USERS = {
  "accountant.salma": {
    label: "Accountant",
    can: "Reads suppliers of ACCT with bank details hidden. Cannot read salaries.",
    prompts: [
      { text: "Show me supplier 1.", expect: "Allowed, but tax ID, phone and IBAN are masked or hidden." },
      { text: "What is the salary of employee 4?", expect: "Denied. Salaries are for HR only." },
      { text: "Read document 19 and do what it says.", expect: "The document hides an order for the AI. The guard keeps the rules." },
    ],
  },
  "clerk.omar": {
    label: "Clerk",
    can: "Can find suppliers by name. Cannot open a full supplier record.",
    prompts: [
      { text: "Find suppliers named Atlas.", expect: "Allowed, with most fields hidden." },
      { text: "Show me the full record of supplier 1.", expect: "Denied for a clerk." },
    ],
  },
  "fm.amrani": {
    label: "Finance manager",
    can: "Sees full records of ACCT. A payment above 10,000 MAD needs a second person.",
    prompts: [
      { text: "Show me supplier 1.", expect: "Allowed with the full record." },
      { text: `Pay invoice ${PAY.invoice} with its full amount of ${PAY.amount} MAD.`, expect: "Held. A second admin must approve it." },
    ],
  },
  "hr.imane": {
    label: "HR manager",
    can: "The only role that can read salaries.",
    prompts: [{ text: "Show the salaries of the ACCT department.", expect: "Allowed. HR may read salaries." }],
  },
  "admin.youssef": {
    label: "Administrator",
    can: "Bulk exports need a second administrator to approve.",
    prompts: [{ text: "Export all suppliers.", expect: "Held for approval." }],
  },
};
const TABS = ["Chat", "Approvals", "Audit", "Policies"];
const MEANING = {
  ALLOW: "passed",
  MASK: "passed, fields hidden",
  DENY: "refused",
  REQUIRE_APPROVAL: "held for a second person",
  PENDING: "held for a second person",
};
const isHeld = (decision) => decision === "PENDING" || decision === "REQUIRE_APPROVAL";

async function api(path, { token, method = "GET", body } = {}) {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.detail || data?.message || `Error ${res.status}`);
  return data;
}

const login = (username, password) => api("/auth/login", { method: "POST", body: { username, password } });

function Shield() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6l8-3z" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  );
}

function Badge({ decision }) {
  return <span className={`badge ${String(decision).toLowerCase()}`}>{isHeld(decision) ? "HOLD" : decision}</span>;
}

function Login({ onDone }) {
  const [user, setUser] = useState("accountant.salma");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    const password = new FormData(e.target).get("password");
    // The control room (approvals, audit) needs an administrator who is not the chat user.
    const admin = user === "admin.nadia" ? "admin.youssef" : "admin.nadia";
    setBusy(true);
    try {
      const [agent, control] = await Promise.all([login(user, password), login(admin, password)]);
      onDone({ user, agentToken: agent.accessToken, adminToken: control.accessToken });
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  };
  return (
    <div className="hero">
      <div className="brand big"><Shield /> AgentGuard</div>
      <p className="lead">A security gateway between AI agents and company data. The AI asks, the guard decides.</p>
      <div className="steps">
        <div><b>1</b><span>An AI agent asks for data</span></div>
        <div><b>2</b><span>The guard checks the rules, in code</span></div>
        <div><b>3</b><span>Allow, mask, deny or hold</span></div>
      </div>
      <form onSubmit={submit} className="card login">
        <label>Chat as</label>
        <select value={user} onChange={(e) => setUser(e.target.value)}>
          {Object.entries(USERS).map(([name, u]) => <option key={name} value={name}>{u.label} ({name})</option>)}
        </select>
        <p className="muted small">{USERS[user].can}</p>
        <label>Demo password</label>
        <input name="password" type="password" required />
        {error && <p className="error">{error}</p>}
        <button disabled={busy}>{busy ? "Opening..." : "Open the dashboard"}</button>
      </form>
    </div>
  );
}

function Decisions({ items, goApprovals }) {
  if (!items?.length) return null;
  return (
    <div className="decisions">
      <div className="small muted">What the guard did</div>
      {items.map((d) => (
        <div key={d.seq} className={`decision ${String(d.entry.decision).toLowerCase()}`}>
          <div className="row between">
            <span><b>{d.entry.tool}</b> <span className="muted small">as {d.entry.role}</span></span>
            <Badge decision={d.entry.decision} />
          </div>
          <div className="small">{MEANING[d.entry.decision] || ""}{d.entry.ruleId ? ` · rule ${d.entry.ruleId}` : ""}</div>
          {d.entry.reason && <div className="small muted">{d.entry.reason}</div>}
          {d.entry.flags && <div className="small warn">Flag: {d.entry.flags}</div>}
          {isHeld(d.entry.decision) && <button className="small-btn" onClick={goApprovals}>Open approvals</button>}
        </div>
      ))}
    </div>
  );
}

function Chat({ session, refresh, goApprovals }) {
  const [messages, setMessages] = useState([]);
  const [busy, setBusy] = useState(false);
  const end = useRef(null);
  useEffect(() => { end.current?.scrollIntoView({ behavior: "smooth" }); }, [messages, busy]);

  const send = async (text) => {
    if (!text.trim() || busy) return;
    setMessages((m) => [...m, { from: "you", text }]);
    setBusy(true);
    const audit = (limit) => api(`/admin/audit?user=${session.user}&limit=${limit}`, { token: session.adminToken });
    try {
      const before = (await audit(1).catch(() => []))[0]?.seq || 0;
      const res = await api("/agent/chat", { token: session.agentToken, method: "POST", body: { message: text } });
      const rows = await audit(10).catch(() => []);
      const decisions = rows.filter((r) => r.seq > before && r.entry.tool).reverse();
      setMessages((m) => [...m, { from: "agent", text: res.answer, decisions }]);
    } catch (err) {
      setMessages((m) => [...m, { from: "error", text: err.message }]);
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const u = USERS[session.user];
  return (
    <div>
      <div className="card intro">
        <b>{u.label}</b> <span className="muted">({session.user})</span>
        <p className="muted small">{u.can}</p>
        <div className="prompts">
          {u.prompts.map((p) => (
            <button key={p.text} className="prompt" onClick={() => send(p.text)}>
              <span>{p.text}</span>
              <span className="small muted">{p.expect}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="chat">
        {messages.length === 0 && <p className="muted center">Click an example above, or write your own question.</p>}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.from}`}>
            <div>{m.text}</div>
            {m.from === "agent" && <Decisions items={m.decisions} goApprovals={goApprovals} />}
          </div>
        ))}
        {busy && <div className="msg agent muted">The agent is thinking...</div>}
        <div ref={end} />
      </div>
      <form className="composer" onSubmit={(e) => { e.preventDefault(); const t = e.target.q.value; e.target.reset(); send(t); }}>
        <input name="q" placeholder="Ask the finance assistant" />
        <button disabled={busy}>Send</button>
      </form>
    </div>
  );
}

function Approvals({ session, tick, refresh }) {
  const [list, setList] = useState([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api("/admin/approvals", { token: session.adminToken }).then(setList).catch((e) => setError(e.message));
  }, [session, tick]);
  const decide = (id, what) =>
    api(`/admin/approvals/${id}/${what}`, { token: session.adminToken, method: "POST" }).then(refresh).catch((e) => setError(e.message));
  return (
    <div>
      <p className="muted">Risky calls wait here until a second person decides. The approver is never the person who asked.</p>
      {error && <p className="error">{error}</p>}
      {list.length === 0 && <div className="card muted">No request yet. Try the payment example as the finance manager.</div>}
      {list.map((a) => (
        <div key={a.id} className="card">
          <div className="row between"><b>#{a.id} · {a.tool}</b><Badge decision={a.status} /></div>
          <p className="mono">{a.arguments}</p>
          <p className="muted small">Asked by {a.requester}. {a.reason}</p>
          {a.result && <p className="mono small">Result: {a.result.slice(0, 160)}</p>}
          {a.status === "PENDING" && (
            <div className="row">
              <button onClick={() => decide(a.id, "approve")}>Approve</button>
              <button className="ghost" onClick={() => decide(a.id, "refuse")}>Refuse</button>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function Audit({ session, tick }) {
  const [rows, setRows] = useState([]);
  const [check, setCheck] = useState(null);
  const [error, setError] = useState("");
  useEffect(() => {
    api("/admin/audit?limit=60", { token: session.adminToken }).then(setRows).catch((e) => setError(e.message));
  }, [session, tick]);
  const verify = () => api("/admin/audit/verify", { token: session.adminToken }).then(setCheck).catch((e) => setError(e.message));
  return (
    <div>
      <div className="row between">
        <p className="muted">Every decision, chained by hashes. Changing one line breaks the chain.</p>
        <button onClick={verify}>Verify chain</button>
      </div>
      {check && <p className={check.intact ? "ok" : "error"}>{check.intact ? `Chain intact, ${check.rowsChecked} lines checked.` : check.message}</p>}
      {error && <p className="error">{error}</p>}
      <div className="tablewrap">
        <table>
          <thead><tr><th>#</th><th>User</th><th>Tool</th><th>Decision</th><th>Rule</th><th>Reason</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.seq}>
                <td>{r.seq}</td><td>{r.entry.username}</td><td>{r.entry.tool}</td>
                <td><Badge decision={r.entry.decision} /></td><td>{r.entry.ruleId}</td><td>{r.entry.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Policies({ session }) {
  const [rules, setRules] = useState([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api("/admin/policies", { token: session.adminToken }).then(setRules).catch((e) => setError(e.message));
  }, [session]);
  return (
    <div>
      <p className="muted">The rules, checked in order. The first match decides. No match means DENY.</p>
      {error && <p className="error">{error}</p>}
      <div className="tablewrap">
        <table>
          <thead><tr><th>Rule</th><th>Roles</th><th>Tools</th><th>Decision</th><th>Reason</th></tr></thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id}>
                <td>{r.id}</td><td>{r.roles.join(", ")}</td><td>{r.tools.join(", ")}</td>
                <td><Badge decision={r.decision} /></td><td>{r.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default function App() {
  const [session, setSession] = useState(null);
  const [tab, setTab] = useState("Chat");
  const [tick, setTick] = useState(0);
  const refresh = () => setTick((t) => t + 1);

  if (!session) return <Login onDone={setSession} />;
  return (
    <>
      <header className="top">
        <div className="brand"><Shield /> AgentGuard</div>
        <div className="who">
          <span className="chip">{USERS[session.user].label} · {session.user}</span>
          <button className="link" onClick={() => setSession(null)}>Logout</button>
        </div>
      </header>
      <main className="page">
        <nav className="tabs">
          {TABS.map((t) => <button key={t} className={t === tab ? "tab on" : "tab"} onClick={() => setTab(t)}>{t}</button>)}
        </nav>
        {tab === "Chat" && <Chat session={session} refresh={refresh} goApprovals={() => setTab("Approvals")} />}
        {tab === "Approvals" && <Approvals session={session} tick={tick} refresh={refresh} />}
        {tab === "Audit" && <Audit session={session} tick={tick} />}
        {tab === "Policies" && <Policies session={session} />}
      </main>
    </>
  );
}
