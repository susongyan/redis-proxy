import type { ProxyConfig } from '../api/types';
import { maskTokens } from './status';
import { toYaml } from './yaml';

export type DiffLineKind = 'same' | 'add' | 'remove';

export interface DiffLine {
  kind: DiffLineKind;
  oldLine?: number;
  newLine?: number;
  text: string;
}

export interface DiffStats {
  added: number;
  removed: number;
  unchanged: number;
}

export interface YamlDiff {
  lines: DiffLine[];
  stats: DiffStats;
}

export interface SideBySideDiffCell {
  line?: number;
  text?: string;
}

export interface SideBySideDiffRow {
  kind: 'same' | 'change' | 'add' | 'remove';
  left: SideBySideDiffCell;
  right: SideBySideDiffCell;
}

function splitLines(config: ProxyConfig): string[] {
  return toYaml(maskTokens(config || {})).split('\n');
}

export function buildYamlDiff(from: ProxyConfig, to: ProxyConfig): YamlDiff {
  const left = splitLines(from);
  const right = splitLines(to);
  const lcs = Array.from({ length: left.length + 1 }, () => Array<number>(right.length + 1).fill(0));

  for (let i = left.length - 1; i >= 0; i -= 1) {
    for (let j = right.length - 1; j >= 0; j -= 1) {
      lcs[i][j] = left[i] === right[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }

  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < left.length && j < right.length) {
    if (left[i] === right[j]) {
      lines.push({ kind: 'same', oldLine: i + 1, newLine: j + 1, text: left[i] });
      i += 1;
      j += 1;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      lines.push({ kind: 'remove', oldLine: i + 1, text: left[i] });
      i += 1;
    } else {
      lines.push({ kind: 'add', newLine: j + 1, text: right[j] });
      j += 1;
    }
  }
  while (i < left.length) {
    lines.push({ kind: 'remove', oldLine: i + 1, text: left[i] });
    i += 1;
  }
  while (j < right.length) {
    lines.push({ kind: 'add', newLine: j + 1, text: right[j] });
    j += 1;
  }

  return {
    lines,
    stats: {
      added: lines.filter((line) => line.kind === 'add').length,
      removed: lines.filter((line) => line.kind === 'remove').length,
      unchanged: lines.filter((line) => line.kind === 'same').length
    }
  };
}

export function buildSideBySideYamlDiff(from: ProxyConfig, to: ProxyConfig): SideBySideDiffRow[] {
  const lines = buildYamlDiff(from, to).lines;
  const rows: SideBySideDiffRow[] = [];

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.kind === 'same') {
      rows.push({
        kind: 'same',
        left: { line: line.oldLine, text: line.text },
        right: { line: line.newLine, text: line.text }
      });
      continue;
    }

    const removed: DiffLine[] = [];
    const added: DiffLine[] = [];
    while (index < lines.length && lines[index].kind === 'remove') {
      removed.push(lines[index]);
      index += 1;
    }
    while (index < lines.length && lines[index].kind === 'add') {
      added.push(lines[index]);
      index += 1;
    }
    index -= 1;

    const count = Math.max(removed.length, added.length);
    for (let offset = 0; offset < count; offset += 1) {
      const left = removed[offset];
      const right = added[offset];
      rows.push({
        kind: left && right ? 'change' : left ? 'remove' : 'add',
        left: { line: left?.oldLine, text: left?.text },
        right: { line: right?.newLine, text: right?.text }
      });
    }
  }

  return rows;
}
