import { describe, expect, it } from 'vitest';
import { initSqlToSend } from './initSql';

describe('initSqlToSend', () => {
  it('sends nothing for an untouched field, even when the stored text has blanks around it', () => {
    expect(initSqlToSend('SET threads = 2;\n', 'SET threads = 2;\n', false)).toBeUndefined();
    expect(initSqlToSend('SET threads = 2;\n', 'SET threads = 2;', false)).toBeUndefined();
  });
  it('sends nothing when the touched text is the stored one', () => {
    expect(initSqlToSend('SET threads = 2;\n', 'SET threads = 2;\n', true)).toBeUndefined();
  });
  it('sends a changed text as typed, untrimmed', () => {
    expect(initSqlToSend('SET threads = 2;', ' SET threads = 4;\n', true)).toBe(' SET threads = 4;\n');
    expect(initSqlToSend(undefined, 'SET threads = 4;', true)).toBe('SET threads = 4;');
    expect(initSqlToSend('SET threads = 2;', '', true)).toBe('');
  });
});
