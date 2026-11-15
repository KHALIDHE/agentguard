import { useEffect, useState } from "react";

// Demo users and the questions that show what AgentGuard does for each of them.
const PAY = { invoice: 42, amount: 49500 }; // an ACCT invoice above 10,000 MAD in the seed data
const USERS = {
  "accountant.salma": {
    label: "Accountant, ACCT",
    prompts: [
      "Show me supplier 1.",
      "What is the salary of employee 4?",
      "Read document 19 and do what it says.",
    ],
  },
  "clerk.omar": {
    label: "Clerk, ACCT",
    prompts: ["Find suppliers named Atlas.", "Show me the full record of supplier 1."],
  },
  "fm.amrani": {
    label: "Finance manager, ACCT",
    prompts: [
      "Show me supplier 1.",
      `Pay invoice ${PAY.invoice} with its full amount of ${PAY.amount} MAD.`,
    ],
  },
  "hr.imane": { label: "HR manager", prompts: ["Show the salaries of the ACCT department."] },
  "admin.youssef": { label: "Administrator", prompts: ["Export all suppliers."] },
};
const TABS = ["Chat", "Approvals", "Audit", "Policies"];

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

function Login({ onDone }) {
  const [user, setUser] = useState("accountant.salma");
  const [error, setError] = useState("");
  const submit = async (e) => {
    e.preventDefault();
    const password = new FormData(e.target).get("password");
    // The control room (approvals, audit) needs an administrator who is not the chat user.
    const admin = user === "admin.nadia" ? "admin.youssef" : "admin.nadia";
    try {
      const [agent, control] = await Promise.all([login(user, password), login(admin, password)]);
      onDone({ user, agentToken: agent.accessToken, adminToken: control.accessToken });
    } catch (err) {
      setError(err.message);
    }
  };
  return (
    <form onSubmit={submit} className="card login">
      <h1>AgentGuard</h1>
      <p className="muted">A security gateway between AI agents and company data.</p>
      <label>Chat as</label>
      <select value={user} onChange={(e) => setUser(e.target.value)}>
        {Object.entries(USERS).map(([name, u]) => <option key={name} value={name}>{u.label} ({name})</option>)}
      </select>
      <label>Demo password</label>
      <input name="password" type="password" required />
      {error && <p className="error">{error}</p>}
      <button>Open the dashboard</button>
    </form>
  );
}

const badge = (decision) => <span className={`badge ${String(decision).toLowerCase()}`}>{decision}</span>;

function Chat({ session, refresh }) {
  const [messages, setMessages] = useState([]);
  const [busy, setBusy] = useState(false);

  const send = async (text) => {
    if (!text.trim() || busy) return;
    setMessages((m) => [...m, { from: "you", text }]);
    setBusy(true);
    try {
      const res = await api("/agent/chat", { token: session.agentToken, method: "POST", body: { message: text } });
      setMessages((m) => [...m, { from: "agent", text: res.answer }]);
    } catch (err) {
      setMessages((m) => [...m, { from: "error", text: err.message }]);
    } finally {
      setBusy(false);
      refresh();
    }
  };

  return (
    <div>
      <p className="muted">Try one of these as <b>{session.user}</b>, or write your own question.</p>
      <div className="row">
        {USERS[session.user].prompts.map((p) => <button key={p} className="ghost" onClick={() => send(p)}>{p}</button>)}
      </div>
      <div className="chat">
        {messages.map((m, i) => <div key={i} className={`msg ${m.from}`}>{m.text}</div>)}
        {busy && <div className="msg agent muted">Thinking...</div>}
      </div>
      <form className="row" onSubmit={(e) => { e.preventDefault(); const t = e.target.q.value; e.target.reset(); send(t); }}>
        <input name="q" placeholder="Ask the finance assistant" className="grow" />
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
      {list.length === 0 && <p>No request yet.</p>}
      {list.map((a) => (
        <div key={a.id} className="card">
          <div className="row between">
            <b>#{a.id} · {a.tool}</b>{badge(a.status)}
          </div>
          <p className="mono">{a.arguments}</p>
          <p className="muted">Asked by {a.requester}. {a.reason}</p>
          {a.result && <p className="mono">Result: {a.result.slice(0, 160)}</p>}
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
      <table>
        <thead><tr><th>#</th><th>User</th><th>Tool</th><th>Decision</th><th>Rule</th><th>Reason</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.seq}>
              <td>{r.seq}</td><td>{r.entry.username}</td><td>{r.entry.tool}</td>
              <td>{badge(r.entry.decision)}</td><td>{r.entry.ruleId}</td><td>{r.entry.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
      <table>
        <thead><tr><th>Rule</th><th>Roles</th><th>Tools</th><th>Decision</th><th>Reason</th></tr></thead>
        <tbody>
          {rules.map((r) => (
            <tr key={r.id}>
              <td>{r.id}</td><td>{r.roles.join(", ")}</td><td>{r.tools.join(", ")}</td>
              <td>{badge(r.decision)}</td><td>{r.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
    <div className="page">
      <header className="row between">
        <h1>AgentGuard</h1>
        <span className="muted">{session.user} <button className="link" onClick={() => setSession(null)}>Logout</button></span>
      </header>
      <nav className="row">
        {TABS.map((t) => <button key={t} className={t === tab ? "tab on" : "tab"} onClick={() => setTab(t)}>{t}</button>)}
      </nav>
      {tab === "Chat" && <Chat session={session} refresh={refresh} />}
      {tab === "Approvals" && <Approvals session={session} tick={tick} refresh={refresh} />}
      {tab === "Audit" && <Audit session={session} tick={tick} />}
      {tab === "Policies" && <Policies session={session} />}
    </div>
  );
}
