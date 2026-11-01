import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel, Segmented } from "../components/ui";
import { useCompany } from "../lib/CompanyProvider";
import { subscribeTeam, type TeamMember } from "../lib/data";
import { fmtDate } from "../lib/format";
import { addMember, changeRole, removeMember, sendInvite, teamErrorMessage, type AssignableRole } from "../lib/team";

const ROLE_INFO: Record<TeamMember["role"], { label: string; tone: "violet" | "blue" | "teal"; about: string }> = {
  owner: { label: "Owner", tone: "violet", about: "Everything, including managing the team" },
  manager: { label: "Manager", tone: "blue", about: "Everything except managing the team" },
  staff: { label: "Staff", tone: "teal", about: "Scanning, enrolling, checkout and counts; no set-up" },
};

type Notice = { kind: "ok" | "error"; text: string } | null;

export function TeamPage() {
  const { companyId, companyName, isOwner, uid: me } = useCompany();
  const [members, setMembers] = useState<TeamMember[]>();
  const [error, setError] = useState<Error>();
  const [show, setShow] = useState<"active" | "removed">("active");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  useEffect(() => subscribeTeam(companyId, setMembers, setError), [companyId]);

  const { active, removed } = useMemo(() => {
    const order = { owner: 0, manager: 1, staff: 2 };
    const sorted = [...(members ?? [])].sort(
      (a, b) => order[a.role] - order[b.role] || (a.name || a.email).localeCompare(b.name || b.email),
    );
    return { active: sorted.filter((m) => m.status === "active"), removed: sorted.filter((m) => m.status === "removed") };
  }, [members]);

  const run = async (key: string, work: () => Promise<string>) => {
    setBusy(key);
    setNotice(null);
    try {
      setNotice({ kind: "ok", text: await work() });
    } catch (e) {
      setNotice({ kind: "error", text: teamErrorMessage(e) });
    } finally {
      setBusy(null);
    }
  };

  const onRoleChange = (m: TeamMember, role: AssignableRole) =>
    run(`role-${m.uid}`, async () => {
      await changeRole(m.uid, role);
      return `${m.name || m.email} is now ${ROLE_INFO[role].label.toLowerCase()}. It applies the next time they open TagScout.`;
    });

  const onRemove = (m: TeamMember) => {
    const who = m.name || m.email;
    if (!window.confirm(`Remove ${who}? They'll be signed out and won't be able to sign in until they're added again.`)) return;
    void run(`remove-${m.uid}`, async () => {
      await removeMember(m.uid);
      return `${who} was removed.`;
    });
  };

  const onResend = (m: TeamMember) =>
    run(`invite-${m.uid}`, async () => {
      await sendInvite(m.email);
      return `Sent ${m.email} a new link to set their password.`;
    });

  const onAddBack = (m: TeamMember) =>
    run(`back-${m.uid}`, async () => {
      const role = m.role === "manager" ? "manager" : "staff";
      const r = await addMember({ email: m.email, name: m.name, role });
      if (r.status !== "already") await sendInvite(r.email);
      return `${m.name || m.email} was added back as ${role} and sent a link to set their password.`;
    });

  const list = show === "active" ? active : removed;

  return (
    <div className="page">
      <PageHeader
        title="Team"
        subtitle={`People who can sign in to ${companyName}.`}
        actions={
          <Segmented
            label="Which people"
            value={show}
            onChange={setShow}
            options={[
              { value: "active", label: "Active", count: active.length },
              { value: "removed", label: "Removed", count: removed.length },
            ]}
          />
        }
      />

      {isOwner ? <AddPerson onDone={setNotice} /> : null}

      {notice ? (
        <p className={`form-message form-message--${notice.kind === "ok" ? "ok" : "error"}`} role={notice.kind === "ok" ? "status" : "alert"}>
          {notice.text}
        </p>
      ) : null}

      {error ? <ErrorNote error={error} /> : null}
      {!members && !error ? <Loading text="Reading the team" /> : null}

      {members ? (
        <Panel title={show === "active" ? "Active" : "Removed"}>
          {list.length === 0 ? (
            <Empty title={show === "active" ? "No one here yet." : "No one has been removed."}>
              {show === "active" && isOwner ? "Add people above; each gets an email to set their password." : null}
            </Empty>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Person</th>
                    <th>Role</th>
                    <th>Added</th>
                    {isOwner ? <th className="num">Actions</th> : null}
                  </tr>
                </thead>
                <tbody>
                  {list.map((m) => {
                    const info = ROLE_INFO[m.role];
                    const editable = isOwner && m.role !== "owner" && m.status === "active";
                    return (
                      <tr key={m.uid}>
                        <td>
                          <p className="cell-title">
                            {m.name || m.email}
                            {m.uid === me ? <Badge tone="slate">You</Badge> : null}
                          </p>
                          {m.name ? <p className="cell-sub">{m.email}</p> : null}
                        </td>
                        <td>
                          {editable ? (
                            <select
                              className="role-select"
                              value={m.role}
                              disabled={busy !== null}
                              aria-label={`Role for ${m.name || m.email}`}
                              onChange={(e) => void onRoleChange(m, e.target.value as AssignableRole)}
                            >
                              <option value="manager">Manager</option>
                              <option value="staff">Staff</option>
                            </select>
                          ) : (
                            <Badge tone={info.tone}>{info.label}</Badge>
                          )}
                          <p className="cell-sub">{info.about}</p>
                        </td>
                        <td className="nowrap">{fmtDate(m.addedAt)}</td>
                        {isOwner ? (
                          <td className="num">
                            <div className="row-actions">
                              {editable ? (
                                <>
                                  <Button busy={busy === `invite-${m.uid}`} disabled={busy !== null} onClick={() => void onResend(m)}>
                                    Resend invite
                                  </Button>
                                  <Button busy={busy === `remove-${m.uid}`} disabled={busy !== null} onClick={() => onRemove(m)}>
                                    Remove
                                  </Button>
                                </>
                              ) : null}
                              {isOwner && m.status === "removed" ? (
                                <Button busy={busy === `back-${m.uid}`} disabled={busy !== null} onClick={() => void onAddBack(m)}>
                                  Add back
                                </Button>
                              ) : null}
                            </div>
                          </td>
                        ) : null}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {!isOwner ? <p className="note">Only the company owner can add, change or remove people.</p> : null}
        </Panel>
      ) : null}
    </div>
  );
}

function AddPerson({ onDone }: { onDone: (n: Notice) => void }) {
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<AssignableRole>("staff");
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    onDone(null);
    try {
      const r = await addMember({ email: email.trim(), name: name.trim(), role });
      if (r.status === "already") {
        onDone({ kind: "ok", text: `${r.email} is already in your team.` });
      } else {
        try {
          await sendInvite(r.email);
          onDone({ kind: "ok", text: `${r.email} was added as ${r.role} and sent an email to set their password.` });
        } catch {
          onDone({ kind: "error", text: `${r.email} was added, but the email didn't send. Use Resend invite.` });
        }
        setEmail("");
        setName("");
        setRole("staff");
      }
    } catch (err) {
      onDone({ kind: "error", text: teamErrorMessage(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Add a person">
      <form className="add-person" onSubmit={submit}>
        <label className="field">
          <span>Email</span>
          <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="off" />
        </label>
        <label className="field">
          <span>Name</span>
          <input type="text" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoComplete="off" />
        </label>
        <label className="field">
          <span>Role</span>
          <select value={role} onChange={(e) => setRole(e.target.value as AssignableRole)}>
            <option value="staff">Staff</option>
            <option value="manager">Manager</option>
          </select>
        </label>
        <Button type="submit" variant="primary" busy={busy} disabled={!email.trim()}>
          {busy ? "Adding" : "Add and send invite"}
        </Button>
      </form>
      <p className="add-person__hint">
        {ROLE_INFO[role].label}: {ROLE_INFO[role].about.toLowerCase()}. They'll get an email from TagScout to set their own password.
      </p>
    </Panel>
  );
}
