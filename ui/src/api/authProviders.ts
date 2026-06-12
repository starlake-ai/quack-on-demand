import type { AuthProvider } from './types';

/** Provider-specific config field surfaced in tenant create + edit
  * forms. Each form flattens the field set into a single
  * `Record<string, string>` and submits it as `authConfig`. `db` has
  * zero fields -- the username on `qodstate_user` IS the identity. */
export interface ProviderField {
  key:         string;
  label:       string;
  placeholder: string;
  /** When true, an empty value is allowed -- the form's submit gate ignores
    * this field. Defaults to false (required). */
  optional?:   boolean;
}

// Per-tenant OIDC overrides are all-or-nothing: TenantOidcRegistry only
// builds a per-tenant authenticator when EVERY required field is present;
// otherwise it falls back to the global `quack-flightsql.auth.<provider>.*`
// config from application.conf. So we mark every per-tenant OIDC field as
// `optional` (a blank form is valid -- means "use the global client") and
// rely on the operator filling out the whole group when they intend to
// override. Field names match the keys the registry reads from
// `qodstate_tenant.authConfig` (see
// src/main/scala/ai/starlake/quack/edge/auth/TenantOidcRegistry.scala).
export const PROVIDER_FIELDS: Record<AuthProvider, ProviderField[]> = {
  db: [],
  keycloak: [
    { key: 'baseUrl',         label: 'Base URL (optional)',
      placeholder: 'https://keycloak.example.com', optional: true },
    { key: 'realm',           label: 'Realm (optional)',
      placeholder: '<realm>', optional: true },
    { key: 'clientId',        label: 'Client ID (optional)',
      placeholder: '<client-id>', optional: true },
    { key: 'clientSecretRef', label: 'Client secret ref (optional)',
      placeholder: 'env:KC_CS_<tenant>', optional: true },
  ],
  google: [
    { key: 'clientId',        label: 'OAuth client ID (optional)',
      placeholder: '<your-tenant>.apps.googleusercontent.com', optional: true },
    { key: 'clientSecretRef', label: 'OAuth client secret ref (optional)',
      placeholder: 'env:GOOGLE_CS_<tenant>', optional: true },
  ],
  azure: [
    { key: 'tenantId',        label: 'AD tenant ID (optional)',
      placeholder: '<directory-id>', optional: true },
    { key: 'clientId',        label: 'Client ID (optional)',
      placeholder: '<app-registration-id>', optional: true },
    { key: 'clientSecretRef', label: 'Client secret ref (optional)',
      placeholder: 'env:AZURE_CS_<tenant>', optional: true },
  ],
  aws: [
    // AWS Cognito JWT validation only needs the JWKS URL (region +
    // userPoolId) and the app client id; no client secret is required.
    { key: 'region',     label: 'AWS region (optional)',
      placeholder: 'eu-west-1', optional: true },
    { key: 'userPoolId', label: 'User pool ID (optional)',
      placeholder: '<userpool-id>', optional: true },
    { key: 'clientId',   label: 'App client ID (optional)',
      placeholder: '<app-client-id>', optional: true },
  ],
};

export const PROVIDER_LABELS: Record<AuthProvider, string> = {
  db:       'db (username + password, managed here)',
  keycloak: 'keycloak (OIDC)',
  google:   'google (OIDC)',
  azure:    'azure (OIDC)',
  aws:      'aws (Cognito, OIDC)',
};
