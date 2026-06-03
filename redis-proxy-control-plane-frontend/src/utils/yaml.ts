function scalar(value: unknown): string {
  if (value === null || value === undefined) {
    return 'null';
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  const text = String(value);
  if (text === '') {
    return '""';
  }
  if (/^[A-Za-z0-9_./:@-]+$/.test(text)) {
    return text;
  }
  return JSON.stringify(text);
}

function writeYaml(value: unknown, indent: number): string[] {
  const pad = ' '.repeat(indent);
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return [`${pad}[]`];
    }
    return value.flatMap((item) => {
      if (item && typeof item === 'object') {
        const lines = writeYaml(item, indent + 2);
        if (lines.length === 0) {
          return [`${pad}- {}`];
        }
        const [first, ...rest] = lines;
        return [`${pad}- ${first.trimStart()}`, ...rest];
      }
      return [`${pad}- ${scalar(item)}`];
    });
  }
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).flatMap(([key, item]) => {
      if (item && typeof item === 'object') {
        const lines = writeYaml(item, indent + 2);
        return [`${pad}${key}:`, ...lines];
      }
      return [`${pad}${key}: ${scalar(item)}`];
    });
  }
  return [`${pad}${scalar(value)}`];
}

export function toYaml(value: unknown): string {
  return writeYaml(value, 0).join('\n');
}
