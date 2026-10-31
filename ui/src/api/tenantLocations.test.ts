import { describe, expect, it } from 'vitest';
import { addLocationRequest, isLocalFolder } from './tenantLocations';

describe('isLocalFolder', () => {
  it('names an absolute folder, nothing else', () => {
    expect(isLocalFolder(' /srv/qod/acme/in/ ')).toBe(true);
    expect(isLocalFolder('s3://acme-etl/')).toBe(false);
    expect(isLocalFolder('srv/qod')).toBe(false);
  });
});

describe('addLocationRequest', () => {
  it('sends the trimmed folder', () => {
    expect(addLocationRequest('acme', ' /srv/qod/acme/in ')).toEqual({ tenant: 'acme', location: '/srv/qod/acme/in' });
  });
});
