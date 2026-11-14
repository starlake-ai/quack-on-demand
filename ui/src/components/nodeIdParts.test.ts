import { describe, expect, it } from 'vitest';
import { nodeIdParts } from './nodeIdParts';

describe('nodeIdParts', () => {
  it('splits after each -- and keeps single hyphens inside their part', () => {
    expect(nodeIdParts('quack--acme--acme-sales--bi--1'))
      .toEqual(['quack--', 'acme--', 'acme-sales--', 'bi--', '1']);
  });

  it('gives the id back when joined', () => {
    for (const id of ['quack--acme--acme-sales--bi--1', 'quack--a--b--c-0123456789abcdef-2', 'plain', 'ends--', '--starts', ''])
      expect(nodeIdParts(id).join('')).toBe(id);
  });

  it('keeps a hashed id whole after its last --', () => {
    expect(nodeIdParts('quack--very-long-tenant-name-0123456789abcdef-3'))
      .toEqual(['quack--', 'very-long-tenant-name-0123456789abcdef-3']);
  });

  it('leaves an id with no separator as one part', () => {
    expect(nodeIdParts('node-7')).toEqual(['node-7']);
    expect(nodeIdParts('')).toEqual(['']);
  });
});
