import type { AuthProvider, SetTenantAuthRequest, TenantResponse } from './types';

/** Provider-specific config field surfaced in tenant create + edit
  * forms. Each form flattens the field set into a single
  * `Record<string, string>` and submits it as `authConfig`. `db` has
  * zero fields -- the username on `qodstate_user` IS the identity. */
export interface ProviderField {
  key:           string;
  label:         string;
  placeholder:   string;
  /** When true, an empty value is allowed -- the form's submit gate ignores
    * this field. Defaults to false (required). */
  optional?:     boolean;
  /** When true, only a superuser may add or change the value (anyone else
    * gets 403 superuser_required from `tenant/setAuth`): the edit form shows
    * it read-only to a tenant admin and sends the stored value back
    * unchanged. */
  operatorOnly?: boolean;
}

/** `authConfig` key of the issuer the tenant's console sign-in uses. A
  * stored client secret is bound to it: a save that changes it drops the
  * secret unless the same save supplies a new one. */
export const ISSUER_KEY = 'issuerUrl';

/** `authConfig` key of a secret reference (`env:NAME`) the manager resolves
  * from its own environment. Only a superuser may add or change one. */
export const SECRET_REF_KEY = 'clientSecretRef';

/** `authConfig` key under which Liquibase 0048 parked a reference saved
  * before 0.10.2. Nothing reads it. The server drops it once a save holds a
  * secret source or moves the issuer, and keeps it only while a save carries
  * it unchanged. */
export const PARKED_SECRET_REF_KEY = 'clientSecretRefUnconfirmed';

// The client secret value never travels in `authConfig` (`tenant/setAuth`
// refuses the key with 400 invalid_auth_config): it is the write-only
// `clientSecret` request field.
const SECRET_KEY = 'clientSecret';

// The prefixes of a secret reference (the vocabulary of the manager's
// `SecretRefResolver`), as `ParkedSecretRefs.ReferencePrefixes` lists them.
const SECRET_REF_PREFIXES = ['env:', 'aws-sm:', 'gcp-sm:', 'azure-kv:', 'vault:'];

/** Shown in place of a parked value that is not a secret reference: it may be
  * a literal secret typed into the wrong key, so it is never displayed (as
  * `ParkedSecretRefs.NotAReference` does for the boot log). */
export const NOT_A_REFERENCE = '<not a secret reference, not shown>';

/** Whether `value` starts with a known secret reference prefix, checked on
  * the raw value as the server does (case-sensitive, no trimming). */
export function isSecretReference(value: string): boolean {
  return SECRET_REF_PREFIXES.some(prefix => value.startsWith(prefix));
}

/** A parked value as the page shows it: a reference as stored, any other
  * value masked. */
export function parkedRefShown(value: string): string {
  return isSecretReference(value) ? value : NOT_A_REFERENCE;
}

/** A config value as the server reads it: trimmed, empty meaning none. */
function valueOf(config: Record<string, string>, key: string): string {
  return (config[key] ?? '').trim();
}

// Two readers of these `qodstate_tenant.authConfig` keys
// (src/main/scala/ai/starlake/quack/edge/auth/):
//  - console sign-in (OidcEndpointResolver; ManagementAuthModeResolver picks
//    the login mode): issuerUrl + clientId + a client secret (the write-only
//    value, or a superuser's clientSecretRef) + optional scopes, for every
//    provider but `db`;
//  - FlightSQL bearer validation (TenantOidcRegistry): clientId plus the
//    provider's own fields (keycloak baseUrl + realm, azure tenantId, aws
//    region + userPoolId). It reads no secret, and its override is
//    all-or-nothing: with one of its fields missing it falls back to the
//    global `quack-flightsql.auth.<provider>.*` config from application.conf.
// Every field is therefore `optional`: a blank form is valid and means "use
// the global client".
export const PROVIDER_FIELDS: Record<AuthProvider, ProviderField[]> = {
  db: [],
  keycloak: [
    { key: 'baseUrl',      label: 'Base URL (optional)',
      placeholder: 'https://keycloak.example.com', optional: true },
    { key: 'realm',        label: 'Realm (optional)',
      placeholder: '<realm>', optional: true },
    { key: 'clientId',     label: 'Client ID (optional)',
      placeholder: '<client-id>', optional: true },
    { key: ISSUER_KEY,     label: 'Issuer URL (optional)',
      placeholder: 'https://keycloak.example.com/realms/<realm>', optional: true },
    { key: 'scopes',       label: 'Scopes (optional)',
      placeholder: 'openid email profile', optional: true },
    { key: SECRET_REF_KEY, label: 'Client secret ref (optional, superuser only)',
      placeholder: 'env:KC_CS_<tenant>', optional: true, operatorOnly: true },
  ],
  google: [
    { key: 'clientId',     label: 'OAuth client ID (optional)',
      placeholder: '<your-tenant>.apps.googleusercontent.com', optional: true },
    { key: ISSUER_KEY,     label: 'Issuer URL (optional)',
      placeholder: 'https://accounts.google.com', optional: true },
    { key: 'scopes',       label: 'Scopes (optional)',
      placeholder: 'openid email profile', optional: true },
    { key: SECRET_REF_KEY, label: 'OAuth client secret ref (optional, superuser only)',
      placeholder: 'env:GOOGLE_CS_<tenant>', optional: true, operatorOnly: true },
  ],
  azure: [
    { key: 'tenantId',     label: 'AD tenant ID (optional)',
      placeholder: '<directory-id>', optional: true },
    { key: 'clientId',     label: 'Client ID (optional)',
      placeholder: '<app-registration-id>', optional: true },
    { key: ISSUER_KEY,     label: 'Issuer URL (optional)',
      placeholder: 'https://login.microsoftonline.com/<directory-id>/v2.0', optional: true },
    { key: 'scopes',       label: 'Scopes (optional)',
      placeholder: 'openid email profile', optional: true },
    { key: SECRET_REF_KEY, label: 'Client secret ref (optional, superuser only)',
      placeholder: 'env:AZURE_CS_<tenant>', optional: true, operatorOnly: true },
  ],
  aws: [
    // AWS Cognito JWT validation only needs the JWKS URL (region +
    // userPoolId) and the app client id; the console sign-in below needs a
    // client secret like every other provider.
    { key: 'region',       label: 'AWS region (optional)',
      placeholder: 'eu-west-1', optional: true },
    { key: 'userPoolId',   label: 'User pool ID (optional)',
      placeholder: '<userpool-id>', optional: true },
    { key: 'clientId',     label: 'App client ID (optional)',
      placeholder: '<app-client-id>', optional: true },
    { key: ISSUER_KEY,     label: 'Issuer URL (optional)',
      placeholder: 'https://cognito-idp.<region>.amazonaws.com/<userpool-id>', optional: true },
    { key: 'scopes',       label: 'Scopes (optional)',
      placeholder: 'openid email profile', optional: true },
    { key: SECRET_REF_KEY, label: 'Client secret ref (optional, superuser only)',
      placeholder: 'env:AWS_CS_<tenant>', optional: true, operatorOnly: true },
  ],
};

export const PROVIDER_LABELS: Record<AuthProvider, string> = {
  db:       'db (username + password, managed here)',
  keycloak: 'keycloak (OIDC)',
  google:   'google (OIDC)',
  azure:    'azure (OIDC)',
  aws:      'aws (Cognito, OIDC)',
};

/** The edit form of a tenant's sign-in. */
export interface SignInForm {
  provider:     AuthProvider;
  config:       Record<string, string>;
  /** The write-only client secret input; '' keeps the stored secret. */
  secret:       string;
  /** "Remove secret" is checked: the save sends `clientSecret: ''`. */
  removeSecret: boolean;
}

/** The form the editor opens with: the stored config without any
  * `clientSecret` key (a secret never travels in `authConfig`), and an empty
  * secret input, since the server never returns the stored secret. */
export function signInFormFor(t: TenantResponse): SignInForm {
  const config = Object.fromEntries(
    Object.entries(t.authConfig).filter(([key]) => key !== SECRET_KEY),
  );
  return { provider: t.authProvider, config, secret: '', removeSecret: false };
}

/** The `tenant/setAuth` request a save sends.
  *  - A blank config value is dropped, so a cleared field removes its key;
  *    other values go as typed.
  *  - `clientSecret` is omitted when the secret input is empty (the server
  *    keeps the stored secret while the issuer is unchanged), `''` when
  *    "Remove secret" is checked, and the typed value otherwise.
  *  - A tenant admin (`superuser` false) never adds or changes a reference:
  *    the stored one goes back unchanged while the form still holds it, so
  *    saving other fields never answers 403.
  *  - Once the request carries a reference or a secret, the parked
  *    reference is not sent back: the tenant has a new secret source. */
export function setAuthRequestFor(
  tenantName: string,
  stored: TenantResponse,
  form: SignInForm,
  superuser: boolean,
): SetTenantAuthRequest {
  const config: Record<string, string> = {};
  for (const [key, value] of Object.entries(form.config)) {
    if (key !== SECRET_KEY && value.trim() !== '') config[key] = value;
  }
  if (!superuser) {
    const storedRef = stored.authConfig[SECRET_REF_KEY] ?? '';
    if (SECRET_REF_KEY in config && storedRef.trim() !== '') config[SECRET_REF_KEY] = storedRef;
    else delete config[SECRET_REF_KEY];
  }
  const secret = form.removeSecret ? '' : form.secret;
  if (SECRET_REF_KEY in config || secret !== '') delete config[PARKED_SECRET_REF_KEY];
  const req: SetTenantAuthRequest = {
    name: tenantName, authProvider: form.provider, authConfig: config,
  };
  if (form.removeSecret || form.secret !== '') req.clientSecret = secret;
  return req;
}

/** Whether saving `form` removes the tenant's saved secret: the issuer
  * changes (compared trimmed, empty meaning none) while a secret is saved (a
  * stored value or a reference) and the save brings none for the new issuer.
  * A typed secret always does; a reference does only in a superuser's save,
  * since a superuser may pair a reference with a new issuer while a tenant
  * admin's carried reference is dropped. The server then removes the saved
  * secret, which must be entered again for the new issuer. Never while
  * "Remove secret" is checked: the secret goes whatever the issuer does, and
  * `consoleSignInComplete` says what is left. */
export function issuerChangeDropsSecret(
  stored: TenantResponse,
  form: SignInForm,
  superuser: boolean,
): boolean {
  if (form.removeSecret) return false;
  const storedRef = valueOf(stored.authConfig, SECRET_REF_KEY);
  const issuerChanged = valueOf(stored.authConfig, ISSUER_KEY) !== valueOf(form.config, ISSUER_KEY);
  const secretSaved = stored.clientSecretSet || storedRef !== '';
  const secretSupplied =
    form.secret !== '' || (superuser && valueOf(form.config, SECRET_REF_KEY) !== '');
  return issuerChanged && secretSaved && !secretSupplied;
}

/** The console sign-in `tenant/setAuth` stores for the request `form` makes,
  * decided the way `TenantSignInWrite.apply` decides it. */
interface SavedSignIn {
  /** The trimmed `issuerUrl` and `clientId` stored, '' for none. */
  issuer:      string;
  clientId:    string;
  /** The save changes the issuer (compared trimmed, empty meaning none). */
  issuerMoved: boolean;
  /** The request adds a reference or changes the stored one (compared trimmed). */
  referenceChanged: boolean;
  /** The trimmed `clientSecretRef` the save keeps, '' for none. */
  reference:   string;
  /** A stored client secret value survives the save. */
  value:       boolean;
}

function savedSignIn(stored: TenantResponse, form: SignInForm, superuser: boolean): SavedSignIn {
  const req = setAuthRequestFor(stored.name, stored, form, superuser);
  const issuer = valueOf(req.authConfig, ISSUER_KEY);
  const issuerMoved = issuer !== valueOf(stored.authConfig, ISSUER_KEY);
  const requestedRef = valueOf(req.authConfig, SECRET_REF_KEY);
  const storedRef = valueOf(stored.authConfig, SECRET_REF_KEY);
  const refChanged = requestedRef !== '' && requestedRef !== storedRef;
  const valueGiven = (req.clientSecret ?? '') !== '';
  // A reference the request carries stays unless a value replaces it: a
  // superuser's under any issuer, anyone else's only under the same one.
  const keepsRef = requestedRef !== '' && !valueGiven && (superuser || !issuerMoved);
  // A typed value is stored; an omitted one keeps the stored value only under
  // the same issuer and with no reference added or changed; '' removes it.
  const keepsValue = valueGiven ||
    (req.clientSecret === undefined && stored.clientSecretSet && !issuerMoved && !refChanged);
  return {
    issuer,
    clientId: valueOf(req.authConfig, 'clientId'),
    issuerMoved,
    referenceChanged: refChanged,
    reference: keepsRef ? requestedRef : '',
    value: keepsValue,
  };
}

/** Whether the tenant's console sign-in works once `form` is saved, the way
  * the server decides it (`ManagementAuthModeResolver`): a `db` tenant signs
  * in with its password; any other provider needs `issuerUrl`, `clientId` and
  * a secret source that survives the save (a typed value, the stored value
  * kept under the same issuer, or a reference kept). Without them a password
  * login naming the tenant is still refused and only a superuser, through the
  * system login, can open the tenant's console. */
export function consoleSignInComplete(
  stored: TenantResponse,
  form: SignInForm,
  superuser: boolean,
): boolean {
  if (form.provider === 'db') return true;
  const saved = savedSignIn(stored, form, superuser);
  return saved.issuer !== '' && saved.clientId !== '' && (saved.value || saved.reference !== '');
}

/** Whether saving `form` keeps a secret reference that is new to the saved
  * issuer: kept under a new issuer, or added or changed by this save
  * (re-entering a parked reference included, since the parked key leaves the
  * stored reference empty). Only a superuser may write such a reference. The
  * environment secret it names is then sent to the saved issuer, so the
  * superuser should confirm that it is the tenant's own identity provider. */
export function referenceNeedsIssuerCheck(
  stored: TenantResponse,
  form: SignInForm,
  superuser: boolean,
): boolean {
  if (form.provider === 'db') return false;
  const saved = savedSignIn(stored, form, superuser);
  return saved.issuer !== '' && saved.reference !== '' &&
    (saved.issuerMoved || saved.referenceChanged);
}

/** The tenant's only secret source is a reference: no stored value, a
  * non-blank `clientSecretRef`. */
export function secretComesFromReference(t: TenantResponse): boolean {
  return !t.clientSecretSet && valueOf(t.authConfig, SECRET_REF_KEY) !== '';
}

/** The "Client secret" status line of the read table. */
export function clientSecretStatus(t: TenantResponse): string {
  if (t.clientSecretSet) return 'set';
  return secretComesFromReference(t) ? 'not set (the secret reference supplies it)' : 'not set';
}
