import { describe, expect, it } from 'vitest';
import {
  PARKED_SECRET_REF_KEY, PROVIDER_FIELDS, SECRET_REF_KEY, issuerChangeDropsSecret,
  setAuthRequestFor, signInFormFor,
} from './authProviders';
import type { AuthProvider, TenantResponse } from './types';

const OIDC_PROVIDERS: AuthProvider[] = ['keycloak', 'google', 'azure', 'aws'];
const ISSUER = 'https://idp.acme.example/realms/acme';
const NEW_ISSUER = 'https://idp.other.example/realms/acme';

/** A keycloak tenant whose console sign-in is configured, with a stored client secret. */
function tenant(over: Partial<TenantResponse> = {}): TenantResponse {
  return {
    id: 'acme',
    name: 'acme',
    displayName: 'Acme',
    pools: [],
    disabled: false,
    authProvider: 'keycloak',
    authConfig: { issuerUrl: ISSUER, clientId: 'qod-console' },
    clientSecretSet: true,
    ...over,
  };
}

/** The editor opened on `t`, with `config` typed over the stored config. */
function edited(t: TenantResponse, config: Record<string, string>) {
  const form = signInFormFor(t);
  return { ...form, config: { ...form.config, ...config } };
}

describe('PROVIDER_FIELDS', () => {
  it('offers the console sign-in issuer URL, client ID and scopes on every OIDC provider', () => {
    for (const p of OIDC_PROVIDERS) {
      expect(PROVIDER_FIELDS[p].map(f => f.key))
        .toEqual(expect.arrayContaining(['issuerUrl', 'clientId', 'scopes']));
    }
    expect(PROVIDER_FIELDS.db).toEqual([]);
  });

  it('marks the secret reference operator-only and offers no clientSecret config field', () => {
    for (const p of OIDC_PROVIDERS) {
      expect(PROVIDER_FIELDS[p].find(f => f.key === SECRET_REF_KEY)?.operatorOnly).toBe(true);
      expect(PROVIDER_FIELDS[p].map(f => f.key)).not.toContain('clientSecret');
    }
  });
});

describe('signInFormFor', () => {
  it('opens with an empty secret input and no clientSecret key in the config', () => {
    const form = signInFormFor(
      tenant({ authConfig: { issuerUrl: ISSUER, clientSecret: 'stored-literal' } }),
    );
    expect(form.secret).toBe('');
    expect(form.removeSecret).toBe(false);
    expect(form.config).toStrictEqual({ issuerUrl: ISSUER });
  });
});

describe('setAuthRequestFor', () => {
  it('omits clientSecret when the secret input is empty, so the stored secret is kept', () => {
    const t = tenant();
    expect(setAuthRequestFor('acme', t, signInFormFor(t), false)).toStrictEqual({
      name: 'acme',
      authProvider: 'keycloak',
      authConfig: { issuerUrl: ISSUER, clientId: 'qod-console' },
    });
  });

  it('sends a typed secret unchanged', () => {
    const t = tenant();
    const req = setAuthRequestFor('acme', t, { ...signInFormFor(t), secret: ' s3cret ' }, false);
    expect(req.clientSecret).toBe(' s3cret ');
  });

  it('sends an empty clientSecret when Remove secret is checked', () => {
    const t = tenant();
    const form = { ...signInFormFor(t), secret: 'typed', removeSecret: true };
    expect(setAuthRequestFor('acme', t, form, false).clientSecret).toBe('');
  });

  it('drops blank config values, so a cleared field removes its key', () => {
    const t = tenant();
    const req = setAuthRequestFor('acme', t, edited(t, { clientId: '  ', scopes: 'openid email' }), false);
    expect(req.authConfig).toStrictEqual({ issuerUrl: ISSUER, scopes: 'openid email' });
  });

  it('carries the stored reference unchanged for a tenant admin', () => {
    const t = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, clientId: 'c', clientSecretRef: 'env:ACME_CS' },
    });
    const req = setAuthRequestFor('acme', t, edited(t, { clientId: 'c2' }), false);
    expect(req.authConfig).toStrictEqual({
      issuerUrl: ISSUER, clientId: 'c2', clientSecretRef: 'env:ACME_CS',
    });
  });

  it('never adds or changes a reference for a tenant admin', () => {
    const none = tenant();
    const added = setAuthRequestFor('acme', none, edited(none, { clientSecretRef: 'env:ANY' }), false);
    expect(added.authConfig).not.toHaveProperty(SECRET_REF_KEY);

    const stored = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, clientSecretRef: 'env:ACME_CS' },
    });
    const changed = setAuthRequestFor('acme', stored, edited(stored, { clientSecretRef: 'env:OTHER' }), false);
    expect(changed.authConfig[SECRET_REF_KEY]).toBe('env:ACME_CS');
  });

  it('sends the reference a superuser enters', () => {
    const t = tenant({ clientSecretSet: false });
    const req = setAuthRequestFor('acme', t, edited(t, { clientSecretRef: 'env:ACME_CS' }), true);
    expect(req.authConfig[SECRET_REF_KEY]).toBe('env:ACME_CS');
  });

  it('stops sending the parked reference once the request carries a reference or a secret', () => {
    const t = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, [PARKED_SECRET_REF_KEY]: 'env:OLD' },
    });
    const withRef = setAuthRequestFor('acme', t, edited(t, { clientSecretRef: 'env:OLD' }), true);
    expect(withRef.authConfig).not.toHaveProperty(PARKED_SECRET_REF_KEY);
    const withSecret = setAuthRequestFor('acme', t, { ...signInFormFor(t), secret: 's3cret' }, false);
    expect(withSecret.authConfig).not.toHaveProperty(PARKED_SECRET_REF_KEY);
  });

  it('keeps the parked reference while the request carries neither', () => {
    const t = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, [PARKED_SECRET_REF_KEY]: 'env:OLD' },
    });
    const req = setAuthRequestFor('acme', t, signInFormFor(t), false);
    expect(req.authConfig[PARKED_SECRET_REF_KEY]).toBe('env:OLD');
  });
});

describe('issuerChangeDropsSecret', () => {
  it('warns when the issuer changes and no new secret is entered', () => {
    const t = tenant();
    expect(issuerChangeDropsSecret(t, edited(t, { issuerUrl: NEW_ISSUER }), false)).toBe(true);
    expect(issuerChangeDropsSecret(t, edited(t, { issuerUrl: NEW_ISSUER }), true)).toBe(true);
  });

  it('warns a tenant admin whose carried reference a new issuer drops', () => {
    const t = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, clientSecretRef: 'env:ACME_CS' },
    });
    expect(issuerChangeDropsSecret(t, edited(t, { issuerUrl: NEW_ISSUER }), false)).toBe(true);
  });

  it('does not warn a superuser who pairs a reference with the new issuer', () => {
    const t = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, clientSecretRef: 'env:ACME_CS' },
    });
    expect(issuerChangeDropsSecret(t, edited(t, { issuerUrl: NEW_ISSUER }), true)).toBe(false);
    expect(issuerChangeDropsSecret(
      t, edited(t, { issuerUrl: NEW_ISSUER, clientSecretRef: 'env:ACME_CS_NEW' }), true,
    )).toBe(false);
  });

  it('compares issuers trimmed', () => {
    const t = tenant();
    expect(issuerChangeDropsSecret(t, edited(t, { issuerUrl: `  ${ISSUER} ` }), false)).toBe(false);
  });

  it('does not warn when the save supplies a new secret', () => {
    const t = tenant();
    const newIssuer = edited(t, { issuerUrl: NEW_ISSUER });
    expect(issuerChangeDropsSecret(t, { ...newIssuer, secret: 'new-secret' }, false)).toBe(false);
  });

  it('does not warn when no secret is saved', () => {
    const t = tenant({ clientSecretSet: false });
    expect(issuerChangeDropsSecret(t, edited(t, { issuerUrl: NEW_ISSUER }), false)).toBe(false);
  });
});
