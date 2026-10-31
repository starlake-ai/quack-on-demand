import { describe, expect, it } from 'vitest';
import { EMPTY_S3_FORM, addS3Request, describeEndpoint } from './s3Credentials';

describe('addS3Request', () => {
  it('sends the scope and the keys, blank optional fields left out', () => {
    expect(addS3Request({
      ...EMPTY_S3_FORM,
      scope: ' s3://acme-etl/in/ ',
      accessKeyId: ' AKIA ',
      secretAccessKey: 'sekr3t',
      endpoint: 'http://127.0.0.1:9000',
    })).toEqual({
      scope: 's3://acme-etl/in/',
      accessKeyId: 'AKIA',
      secretAccessKey: 'sekr3t',
      endpoint: 'http://127.0.0.1:9000',
    });
  });

  it('keeps the session token, region and URL style when given', () => {
    expect(addS3Request({
      ...EMPTY_S3_FORM,
      scope: 's3://b/',
      accessKeyId: 'k',
      secretAccessKey: 's',
      sessionToken: 't',
      region: 'eu-west-3',
      urlStyle: 'path',
    })).toEqual({
      scope: 's3://b/', accessKeyId: 'k', secretAccessKey: 's', sessionToken: 't', region: 'eu-west-3', urlStyle: 'path',
    });
  });
});

describe('describeEndpoint', () => {
  it('names AWS for none and the transport otherwise', () => {
    expect(describeEndpoint(null)).toBe('AWS');
    expect(describeEndpoint(undefined)).toBe('AWS');
    expect(describeEndpoint('http://127.0.0.1:9000')).toBe('127.0.0.1:9000 (plain HTTP)');
    expect(describeEndpoint('minio.internal:9000')).toBe('minio.internal:9000 (TLS)');
  });
});
