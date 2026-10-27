import { describe, expect, it } from 'vitest';
import { EMPTY_LOCATION_FORM, addLocationRequest, locationKind, nodesNote } from './tenantLocations';

describe('locationKind', () => {
  it('names an s3 bucket and a local folder, nothing else', () => {
    expect(locationKind(' s3://acme-etl/ ')).toBe('s3');
    expect(locationKind('/srv/qod/acme/in/')).toBe('local');
    expect(locationKind('gs://acme-etl/')).toBeNull();
    expect(locationKind('srv/qod')).toBeNull();
  });
});

describe('addLocationRequest', () => {
  it('sends the credentials of an s3 location, blank fields left out', () => {
    expect(addLocationRequest('acme', {
      ...EMPTY_LOCATION_FORM,
      location: ' s3://acme-etl/ ',
      accessKeyId: 'AKIA',
      secretAccessKey: 'sekr3t',
      endpoint: 'http://minio:9000',
    })).toEqual({
      tenant: 'acme',
      location: 's3://acme-etl/',
      objectStore: { s3_access_key_id: 'AKIA', s3_secret_access_key: 'sekr3t', s3_endpoint: 'http://minio:9000' },
    });
  });

  it('sends no objectStore for a local folder, whatever the form holds', () => {
    expect(addLocationRequest('acme', {
      ...EMPTY_LOCATION_FORM,
      location: '/srv/qod/acme/in',
      accessKeyId: 'AKIA',
      secretAccessKey: 'sekr3t',
    })).toEqual({ tenant: 'acme', location: '/srv/qod/acme/in' });
  });
});

describe('nodesNote', () => {
  it('says how the change reached the running nodes', () => {
    expect(nodesNote({ nodesUpdated: 2, nodesFailed: [] })).toBe('Applied on 2 running node(s).');
    expect(nodesNote({ nodesUpdated: 0, nodesFailed: [] })).toBeNull();
    expect(nodesNote({ nodesUpdated: 1, nodesFailed: ['quack-acme-db-bi-2'] })).toBe(
      'Applied on 1 running node(s). Not applied on quack-acme-db-bi-2: it applies at the node\'s next restart.',
    );
  });
});
