import { describe, expect, it } from 'vitest';
import {
  NOT_A_REFERENCE, PARKED_SECRET_REF_KEY, PROVIDER_FIELDS, SECRET_REF_KEY, clientSecretStatus,
  consoleSignInComplete, isSecretReference, issuerChangeDropsSecret, parkedRefShown,
  referenceFollowsNewIssuer, secretComesFromReference, setAuthRequestFor, signInFormFor,
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

/** A keycloak tenant whose only secret source is a reference (no stored value). */
function refTenant(over: Partial<TenantResponse> = {}): TenantResponse {
  return tenant({
    clientSecretSet: false,
    authConfig: { issuerUrl: ISSUER, clientId: 'qod-console', clientSecretRef: 'env:ACME_CS' },
    ...over,
  });
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

  it('does not warn when Remove secret is checked: the secret goes either way', () => {
    const t = tenant();
    const form = { ...edited(t, { issuerUrl: NEW_ISSUER }), removeSecret: true };
    expect(issuerChangeDropsSecret(t, form, false)).toBe(false);
    expect(issuerChangeDropsSecret(t, form, true)).toBe(false);
  });
});

describe('consoleSignInComplete', () => {
  const dbTenant = () => tenant({ authProvider: 'db', authConfig: {}, clientSecretSet: false });

  it('is complete when the issuer, the client ID and the stored secret are all kept', () => {
    const t = tenant();
    expect(consoleSignInComplete(t, signInFormFor(t), false)).toBe(true);
  });

  it('needs the issuer URL', () => {
    const t = tenant();
    const form = { ...edited(t, { issuerUrl: '  ' }), secret: 's3cret' };
    expect(consoleSignInComplete(t, form, false)).toBe(false);
  });

  it('needs the client ID', () => {
    const t = tenant();
    expect(consoleSignInComplete(t, edited(t, { clientId: '' }), false)).toBe(false);
  });

  it('needs a secret source: no stored secret and no reference is none', () => {
    const t = tenant({ clientSecretSet: false });
    expect(consoleSignInComplete(t, signInFormFor(t), false)).toBe(false);
  });

  it('counts a typed secret as the source', () => {
    const t = tenant({ clientSecretSet: false });
    expect(consoleSignInComplete(t, { ...signInFormFor(t), secret: 's3cret' }, false)).toBe(true);
  });

  it('leaves no source when Remove secret is checked', () => {
    const t = tenant();
    const form = { ...signInFormFor(t), removeSecret: true };
    expect(consoleSignInComplete(t, form, false)).toBe(false);
  });

  it('does not keep the stored secret under a new issuer unless a new one is typed', () => {
    const t = tenant();
    const moved = edited(t, { issuerUrl: NEW_ISSUER });
    expect(consoleSignInComplete(t, moved, false)).toBe(false);
    expect(consoleSignInComplete(t, { ...moved, secret: 's3cret' }, false)).toBe(true);
  });

  it('keeps a stored reference under the same issuer', () => {
    const t = refTenant();
    expect(consoleSignInComplete(t, signInFormFor(t), false)).toBe(true);
  });

  it('drops a tenant admin reference under a new issuer, as the server does', () => {
    const t = refTenant();
    expect(consoleSignInComplete(t, edited(t, { issuerUrl: NEW_ISSUER }), false)).toBe(false);
  });

  it('keeps a superuser reference under a new issuer', () => {
    const t = refTenant();
    expect(consoleSignInComplete(t, edited(t, { issuerUrl: NEW_ISSUER }), true)).toBe(true);
  });

  it('ignores a reference a tenant admin types, since it is never sent', () => {
    const t = tenant({ clientSecretSet: false });
    expect(consoleSignInComplete(t, edited(t, { clientSecretRef: 'env:ANY' }), false)).toBe(false);
  });

  it('counts a reference a superuser adds as the source, replacing the stored secret', () => {
    const t = tenant();
    const form = edited(t, { clientSecretRef: 'env:ACME_CS' });
    expect(consoleSignInComplete(t, form, true)).toBe(true);
    expect(consoleSignInComplete(t, { ...form, removeSecret: true }, true)).toBe(true);
  });

  it('does not count the parked reference as a source', () => {
    const t = tenant({
      clientSecretSet: false,
      authConfig: {
        issuerUrl: ISSUER, clientId: 'qod-console', [PARKED_SECRET_REF_KEY]: 'env:OLD',
      },
    });
    expect(consoleSignInComplete(t, signInFormFor(t), true)).toBe(false);
  });

  it('treats a db tenant as complete: it signs in with its password', () => {
    const t = dbTenant();
    expect(consoleSignInComplete(t, signInFormFor(t), false)).toBe(true);
  });

  it('is incomplete when a db tenant is switched to an OIDC provider without the fields', () => {
    const t = dbTenant();
    const form = { ...signInFormFor(t), provider: 'keycloak' as const };
    expect(consoleSignInComplete(t, form, false)).toBe(false);
    expect(consoleSignInComplete(t, form, true)).toBe(false);
  });
});

describe('clientSecretStatus', () => {
  it('reads set when a client secret is stored', () => {
    expect(clientSecretStatus(tenant())).toBe('set');
    expect(secretComesFromReference(tenant())).toBe(false);
  });

  it('reads not set when there is neither a stored secret nor a reference', () => {
    const t = tenant({ clientSecretSet: false });
    expect(clientSecretStatus(t)).toBe('not set');
    expect(secretComesFromReference(t)).toBe(false);
  });

  it('says the reference supplies the secret when only a reference is stored', () => {
    const t = refTenant();
    expect(clientSecretStatus(t)).toBe('not set (the secret reference supplies it)');
    expect(secretComesFromReference(t)).toBe(true);
  });

  it('ignores a blank reference and the parked reference', () => {
    const blank = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, clientSecretRef: '   ' },
    });
    const parked = tenant({
      clientSecretSet: false,
      authConfig: { issuerUrl: ISSUER, [PARKED_SECRET_REF_KEY]: 'env:OLD' },
    });
    expect(clientSecretStatus(blank)).toBe('not set');
    expect(clientSecretStatus(parked)).toBe('not set');
  });
});

describe('parked reference display', () => {
  it('shows a value with a known secret reference prefix as stored', () => {
    for (const ref of ['env:A', 'aws-sm:a', 'gcp-sm:a', 'azure-kv:a', 'vault:a']) {
      expect(isSecretReference(ref)).toBe(true);
      expect(parkedRefShown(ref)).toBe(ref);
    }
  });

  it('masks a value without a known prefix, since it may be a literal secret', () => {
    for (const value of ['hunter2', 'ENV:A', ' env:A', 'https://idp.example/env:A']) {
      expect(isSecretReference(value)).toBe(false);
      expect(parkedRefShown(value)).toBe(NOT_A_REFERENCE);
    }
  });
});

describe('referenceFollowsNewIssuer', () => {
  it('is true when a superuser keeps the stored reference under a new issuer', () => {
    const t = refTenant();
    expect(referenceFollowsNewIssuer(t, edited(t, { issuerUrl: NEW_ISSUER }), true)).toBe(true);
  });

  it('is true when a superuser enters a reference together with a new issuer', () => {
    const t = tenant({ clientSecretSet: false });
    const form = edited(t, { issuerUrl: NEW_ISSUER, clientSecretRef: 'env:ACME_CS' });
    expect(referenceFollowsNewIssuer(t, form, true)).toBe(true);
  });

  it('is false while the issuer is unchanged, compared trimmed', () => {
    const t = refTenant();
    expect(referenceFollowsNewIssuer(t, signInFormFor(t), true)).toBe(false);
    expect(referenceFollowsNewIssuer(t, edited(t, { issuerUrl: ` ${ISSUER} ` }), true)).toBe(false);
  });

  it('is false for a tenant admin: the server drops a carried reference under a new issuer', () => {
    const t = refTenant();
    expect(referenceFollowsNewIssuer(t, edited(t, { issuerUrl: NEW_ISSUER }), false)).toBe(false);
  });

  it('is false when a typed secret replaces the reference', () => {
    const t = refTenant();
    const form = { ...edited(t, { issuerUrl: NEW_ISSUER }), secret: 's3cret' };
    expect(referenceFollowsNewIssuer(t, form, true)).toBe(false);
  });

  it('is false without a reference, or when the new issuer is blank', () => {
    const none = tenant();
    const moved = edited(none, { issuerUrl: NEW_ISSUER });
    expect(referenceFollowsNewIssuer(none, moved, true)).toBe(false);
    const t = refTenant();
    expect(referenceFollowsNewIssuer(t, edited(t, { issuerUrl: '  ' }), true)).toBe(false);
  });

  it('is false for a db tenant, which sends no secret to any issuer', () => {
    const t = refTenant({ authProvider: 'db' });
    expect(referenceFollowsNewIssuer(t, edited(t, { issuerUrl: NEW_ISSUER }), true)).toBe(false);
  });
});
