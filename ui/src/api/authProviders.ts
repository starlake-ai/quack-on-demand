import type { AuthProvider } from './types';

/** Provider-specific config field surfaced in tenant create + edit
  * forms. Each form flattens the field set into a single
  * `Record<string, string>` and submits it as `authConfig`. `db` has
  * zero fields -- the username on `qodstate_user` IS the identity. */
export interface ProviderField {
  key:         string;
  label:       string;
  placeholder: string;
}

export const PROVIDER_FIELDS: Record<AuthProvider, ProviderField[]> = {
  db: [],
  keycloak: [
    { key: 'issuer', label: 'Issuer URL',
      placeholder: 'https://keycloak.example.com/realms/<realm>' },
    { key: 'realm',  label: 'Realm name',     placeholder: 'tpch' },
  ],
  google: [
    { key: 'issuer', label: 'Issuer URL',       placeholder: 'accounts.google.com' },
    { key: 'hd',     label: 'Workspace domain', placeholder: 'example.com' },
  ],
  azure: [
    { key: 'issuer',   label: 'Issuer URL',
      placeholder: 'https://login.microsoftonline.com/<tenant-id>/v2.0' },
    { key: 'tenantId', label: 'AD tenant id',   placeholder: '<directory-id>' },
  ],
  aws: [
    { key: 'issuer',     label: 'Issuer URL',
      placeholder: 'https://cognito-idp.<region>.amazonaws.com/<userpool>' },
    { key: 'userPoolId', label: 'User pool id', placeholder: '<userpool-id>' },
  ],
};

export const PROVIDER_LABELS: Record<AuthProvider, string> = {
  db:       'db (username + password, managed here)',
  keycloak: 'keycloak (OIDC)',
  google:   'google (OIDC)',
  azure:    'azure (OIDC)',
  aws:      'aws (Cognito, OIDC)',
};
