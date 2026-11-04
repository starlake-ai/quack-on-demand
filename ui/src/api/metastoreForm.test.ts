import { describe, expect, it } from 'vitest';
import { editableMetastore, metastoreToSend, superuserKeys } from './metastoreForm';

// A row on another server, as database/list shows it: no password.
const stored = { pgHost: 'pg.other', pgPort: '6000', pgUser: 'me', dbName: 'acme_lake', schemaName: 'main' };

describe('superuserKeys', () => {
  it('holds every key that names a Postgres server', () => {
    expect([...superuserKeys('ducklake')].sort()).toEqual(['dbName', 'pgHost', 'pgPassword', 'pgPort', 'pgUser']);
    expect(superuserKeys('memory').has('dbName')).toBe(true);
  });
  it('leaves dbName to the writer of a duckdb-file, whose catalog it names', () => {
    expect([...superuserKeys('duckdb-file')].sort()).toEqual(['pgHost', 'pgPassword', 'pgPort', 'pgUser']);
  });
});

describe('editableMetastore', () => {
  it('shows a superuser all of it', () => {
    expect(editableMetastore(stored, 'ducklake', true)).toEqual(stored);
  });
  it('shows anyone else no key of the server', () => {
    expect(editableMetastore(stored, 'ducklake', false)).toEqual({ schemaName: 'main' });
    expect(editableMetastore({ dbName: 'cat', schemaName: 'main', pgHost: 'h' }, 'duckdb-file', false))
      .toEqual({ dbName: 'cat', schemaName: 'main' });
  });
});

describe('metastoreToSend', () => {
  it('sends a superuser what they typed', () => {
    expect(metastoreToSend(stored, { pgHost: 'pg.third' }, 'ducklake', true)).toEqual({ pgHost: 'pg.third' });
  });
  it('carries the server back as the row holds it, so that an edit changes none of it', () => {
    const sent = metastoreToSend(stored, { schemaName: 's2' }, 'ducklake', false);
    expect(sent).toEqual({ ...stored, schemaName: 's2' });
    expect('pgPassword' in sent).toBe(false);
  });
  it('drops what was typed for a key of the server', () => {
    const sent = metastoreToSend(
      stored,
      { pgHost: 'pg.other', pgPort: '1', pgUser: 'x', pgPassword: 'sent', dbName: 'other', schemaName: 'main' },
      'ducklake',
      false,
    );
    expect(sent).toEqual(stored);
  });
  it('keeps a duckdb-file dbName as typed', () => {
    expect(metastoreToSend({ dbName: 'cat', schemaName: 'main' }, { dbName: 'cat2', schemaName: 'main' }, 'duckdb-file', false))
      .toEqual({ dbName: 'cat2', schemaName: 'main' });
  });
});
