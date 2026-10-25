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
  * before 0.10.2, until a superuser saves it again. Nothing reads it. */
export const PARKED_SECRET_REF_KEY = 'clientSecretRefUnconfirmed';

// The client secret value never travels in `authConfig` (`tenant/setAuth`
// refuses the key with 400 invalid_auth_config): it is the write-only
// `clientSecret` request field.
const SECRET_KEY = 'clientSecret';

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
  * secret, which must be entered again for the new issuer. */
export function issuerChangeDropsSecret(
  stored: TenantResponse,
  form: SignInForm,
  superuser: boolean,
): boolean {
  const issuer = (config: Record<string, string>) => (config[ISSUER_KEY] ?? '').trim();
  const storedRef = (stored.authConfig[SECRET_REF_KEY] ?? '').trim();
  const formRef = (form.config[SECRET_REF_KEY] ?? '').trim();
  const issuerChanged = issuer(stored.authConfig) !== issuer(form.config);
  const secretSaved = stored.clientSecretSet || storedRef !== '';
  const secretSupplied =
    (!form.removeSecret && form.secret !== '') || (superuser && formRef !== '');
  return issuerChanged && secretSaved && !secretSupplied;
}
