import { useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import {
  ISSUER_KEY, PARKED_SECRET_REF_KEY, PROVIDER_FIELDS, PROVIDER_LABELS, SECRET_REF_KEY,
  clientSecretStatus, consoleSignInComplete, isSecretReference, issuerChangeDropsSecret,
  parkedRefShown, referenceFollowsNewIssuer, secretComesFromReference, setAuthRequestFor,
  signInFormFor, type ProviderField, type SignInForm,
} from '../api/authProviders';
import type { AuthProvider, TenantResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { EditIcon } from './Icons';
import { Modal } from './Modal';

const EMPTY_FORM: SignInForm = { provider: 'db', config: {}, secret: '', removeSecret: false };

/** Auth-provider page for one tenant (`/t/:tenant/auth-provider`). Shows the tenant's
  * current provider + config and lets the admin swap it. Users / roles /
  * groups under the tenant are unaffected by this swap -- it's only a
  * change to HOW the tenant's users authenticate.
  *
  * `db` config is intentionally empty: the username on `qodstate_user`
  * IS the identity, so there's nothing to capture beyond the provider
  * choice.
  *
  * The client secret of the tenant's console sign-in is write-only: the
  * server only reports whether one is stored (`clientSecretSet`), so this
  * form never prefills or displays it. The secret reference
  * (`clientSecretRef`) is operator-only: editable by a superuser, read-only
  * for a tenant admin, whose save carries it back unchanged. */
export default function AuthProviderSection({ tenantName }: { tenantName: string }) {
  const { superuser } = useAuth();
  const [tenant, setTenant]   = useState<TenantResponse | null>(null);
  const [editing, setEditing] = useState(false);
  const [error, setError]     = useState<string | null>(null);

  // Edit-form state. Initialized from the current tenant when "Edit" is clicked;
  // the secret input starts empty every time.
  const [form, setForm] = useState<SignInForm>(EMPTY_FORM);

  function reload() {
    setError(null);
    api.listTenants()
      .then(r => {
        const t = r.tenants.find(x => x.name === tenantName);
        if (!t) setError(`tenant '${tenantName}' not found`);
        else setTenant(t);
      })
      .catch(e => setError(errorMessage(e)));
  }

  useEffect(() => { reload(); /* eslint-disable-next-line */ }, [tenantName]);

  function openEditor() {
    if (!tenant) return;
    setForm(signInFormFor(tenant));
    setEditing(true);
    setError(null);
  }

  function closeEditor() {
    setEditing(false);
    setForm(EMPTY_FORM); // a typed secret goes with the form
    setError(null);
  }

  function setConfigField(key: string, value: string) {
    setForm(prev => ({ ...prev, config: { ...prev.config, [key]: value } }));
  }

  function pickProvider(p: AuthProvider) {
    setForm({ ...EMPTY_FORM, provider: p }); // discard prior provider's fields on switch
  }

  function setRemoveSecret(remove: boolean) {
    setForm(prev => ({ ...prev, removeSecret: remove, secret: remove ? '' : prev.secret }));
  }

  async function save(ev: React.FormEvent) {
    ev.preventDefault();
    if (!tenant) return;
    setError(null);
    try {
      const updated = await api.setTenantAuth(setAuthRequestFor(tenantName, tenant, form, superuser));
      setTenant(updated);
      setEditing(false);
      setForm(EMPTY_FORM);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  if (!tenant) {
    return (
      <div className="card">
        <div className="card-title">Auth provider</div>
        {error ? <div className="login-err">Error: {error}</div> : <div className="loading">Loading…</div>}
      </div>
    );
  }

  const storedFields = PROVIDER_FIELDS[tenant.authProvider];
  const formFields   = PROVIDER_FIELDS[form.provider];
  const formReady = formFields.every(
    f => f.optional || (form.config[f.key] ?? '').trim().length > 0
  );
  // A tenant admin sees an operator-only field only when it holds a value
  // (read-only): they cannot add one.
  const fieldShown = (f: ProviderField, config: Record<string, string>) =>
    !f.operatorOnly || superuser || (config[f.key] ?? '').trim() !== '';
  const readOnlyField = (f: ProviderField) => !!f.operatorOnly && !superuser;
  const storedParked = tenant.authConfig[PARKED_SECRET_REF_KEY];
  const formParked   = form.config[PARKED_SECRET_REF_KEY];
  const dropsSecret  = formFields.length > 0 && issuerChangeDropsSecret(tenant, form, superuser);
  // Warned before the save, from what the server will keep (see consoleSignInComplete).
  const incomplete   = !consoleSignInComplete(tenant, form, superuser);
  const refMoves     = referenceFollowsNewIssuer(tenant, form, superuser);
  const formIssuer   = (form.config[ISSUER_KEY] ?? '').trim();
  const formRef      = (form.config[SECRET_REF_KEY] ?? '').trim();
  // What to do about a parked value: a reference can be restored by a superuser, anything else
  // is no reference, so a secret source has to be set instead.
  const parkedAdvice = (parked: string) => isSecretReference(parked)
    ? (superuser
        ? "To restore it, first check that the issuer URL is the tenant's own identity provider (the secret is sent to it), then enter the reference again as the client secret ref."
        : 'A superuser can restore it, or enter the client secret above.')
    : (superuser
        ? 'It is not a secret reference, so there is nothing to restore: set a client secret above or a client secret ref.'
        : 'It is not a secret reference, so there is nothing to restore: enter the client secret above, or ask a superuser to set a reference.');

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Auth provider</div>
        {!editing && (
          <button className="icon-btn" title="Edit" aria-label="Edit auth provider" onClick={openEditor}><EditIcon /></button>
        )}
      </div>
      <p className="subtle">
        Every user in this tenant authenticates through the chosen provider.
        Changing the provider does NOT delete the tenant's users, roles, or
        groups; for OIDC providers, future handshakes will be matched
        against the new `iss` claim instead of the directory. The client
        secret is write-only: once saved, it is never shown again.
      </p>
      {error && !editing && <div className="login-err">Error: {error}</div>}

      <table>
        <tbody>
          <tr>
            <th style={{ textAlign: 'left', width: 160 }}>Provider</th>
            <td><code>{tenant.authProvider}</code> <span className="subtle">- {PROVIDER_LABELS[tenant.authProvider]}</span></td>
          </tr>
          {storedFields.length === 0 ? (
            <tr>
              <th style={{ textAlign: 'left' }}>Config</th>
              <td className="subtle">(none - the username on each user record IS the identity)</td>
            </tr>
          ) : (
            <>
              {storedFields.filter(f => fieldShown(f, tenant.authConfig)).map(f => (
                <tr key={f.key}>
                  <th style={{ textAlign: 'left' }}>{f.label}</th>
                  <td>
                    <code>{tenant.authConfig[f.key] ?? <span className="subtle">(not set)</span>}</code>
                    {readOnlyField(f) && <span className="subtle"> (set by an operator)</span>}
                  </td>
                </tr>
              ))}
              <tr>
                <th style={{ textAlign: 'left' }}>Client secret</th>
                <td>
                  {tenant.clientSecretSet ? clientSecretStatus(tenant)
                    : <span className="subtle">{clientSecretStatus(tenant)}</span>}
                </td>
              </tr>
              {storedParked && (
                <tr>
                  <th style={{ textAlign: 'left' }}>Parked secret ref</th>
                  <td>
                    <code>{parkedRefShown(storedParked)}</code>{' '}
                    <span className="subtle">
                      {isSecretReference(storedParked)
                        ? '(saved before 0.10.2; unused until a superuser saves it again. '
                        : '(saved before 0.10.2 and unused; it is not a secret reference, so set a client secret or a reference instead. '}
                      Entering a client secret or changing the issuer URL drops it)
                    </span>
                  </td>
                </tr>
              )}
            </>
          )}
        </tbody>
      </table>

      {editing && (
        <Modal maxWidth={560} scrollBackdrop onClose={closeEditor}>
            <div className="card-title">Edit auth provider</div>
            <form onSubmit={save}>
              <label>
                Provider
                <select
                  value={form.provider}
                  onChange={ev => pickProvider(ev.target.value as AuthProvider)}
                >
                  {(Object.keys(PROVIDER_LABELS) as AuthProvider[]).map(k => (
                    <option key={k} value={k}>{PROVIDER_LABELS[k]}</option>
                  ))}
                </select>
              </label>
              {formFields.length === 0 ? (
                <p className="subtle" style={{ marginTop: 0 }}>
                  <code>db</code> needs no extra config - the username on each
                  user record IS the identity.
                </p>
              ) : (
                <>
                  <p className="subtle" style={{ marginTop: 0 }}>
                    Console sign-in: with an OIDC provider a password login
                    naming this tenant is always refused. Once the issuer URL,
                    the client ID and a client secret (or a secret reference,
                    which only a superuser can set) are all set, this tenant's
                    admins sign in through its identity provider at <code>/ui/?tenant={tenantName}</code>;
                    until then nobody but a superuser, through the system login,
                    can open this tenant's console. SQL clients: bearer tokens
                    are checked against this tenant's own client when every field
                    its provider needs is filled in (the client ID, plus the base
                    URL and realm, AD tenant ID, or region and user pool ID), and
                    against the manager-wide config from <code>application.conf</code> otherwise.
                  </p>
                  {formFields.filter(f => fieldShown(f, form.config)).map(f => (
                    readOnlyField(f) ? (
                      <label key={f.key}>
                        {f.label}
                        <input value={form.config[f.key] ?? ''} readOnly disabled />
                        <span className="subtle">
                          Set by an operator: only a superuser can change it, a new
                          issuer URL removes it, and entering a client secret
                          replaces it.
                        </span>
                      </label>
                    ) : (
                      <label key={f.key}>
                        {f.label}
                        <input
                          value={form.config[f.key] ?? ''}
                          onChange={ev => setConfigField(f.key, ev.target.value)}
                          placeholder={f.placeholder}
                          required={!f.optional}
                        />
                        {f.key === SECRET_REF_KEY && formRef !== '' && (
                          <span className="subtle">Entering a client secret replaces it.</span>
                        )}
                      </label>
                    )
                  ))}
                  <label>
                    Client secret{' '}
                    <span className="subtle">({tenant.clientSecretSet ? 'set' : 'not set'})</span>
                    <input
                      type="password"
                      value={form.secret}
                      onChange={ev => setForm(prev => ({ ...prev, secret: ev.target.value }))}
                      placeholder={tenant.clientSecretSet ? 'leave blank to keep' : 'enter the client secret'}
                      disabled={form.removeSecret}
                      autoComplete="new-password"
                    />
                    {secretComesFromReference(tenant) && (
                      <span className="subtle">
                        The secret reference supplies it; entering a client secret
                        replaces the reference.
                      </span>
                    )}
                  </label>
                  {tenant.clientSecretSet && (
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={form.removeSecret}
                        onChange={ev => setRemoveSecret(ev.target.checked)}
                      />
                      <span>Remove secret</span>
                    </label>
                  )}
                  {formParked && (
                    <p className="subtle">
                      The client secret ref saved before 0.10.2 is parked and unused:{' '}
                      <code>{parkedRefShown(formParked)}</code>.{' '}
                      {parkedAdvice(formParked)}{' '}
                      Entering a client secret, or changing the issuer URL, drops it.
                    </p>
                  )}
                  {dropsSecret && (
                    <p role="alert" style={{ color: 'var(--warn)' }}>
                      The issuer URL changed: saving removes the saved client secret,
                      which belongs to the previous issuer, and it must be entered
                      again. Enter the new issuer's secret above to keep console
                      sign-in working.
                    </p>
                  )}
                  {incomplete && (
                    <p role="alert" style={{ color: 'var(--warn)' }}>
                      Saving leaves console sign-in incomplete: until the issuer URL,
                      the client ID and a client secret (or a secret reference) are
                      all set, nobody but a superuser, through the system login, can
                      open this tenant's console.
                    </p>
                  )}
                  {refMoves && (
                    <p role="alert" style={{ color: 'var(--warn)' }}>
                      The secret reference <code>{formRef}</code> names a secret of the
                      manager's environment, and with this issuer URL that secret is
                      sent to <code>{formIssuer}</code>. Confirm that it is this
                      tenant's own identity provider before saving.
                    </p>
                  )}
                </>
              )}
              {error && <div className="login-err">Error: {error}</div>}
              <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeEditor}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }} disabled={!formReady}>Save</button>
              </div>
            </form>
        </Modal>
      )}
    </div>
  );
}
